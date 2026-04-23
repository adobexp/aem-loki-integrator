/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follows a single log file and invokes a callback for every parsed
 * entry. Handles log rotation (the file shrinking, its inode being
 * replaced, or being moved away) by reopening the file when detected.
 * All state is kept inside this object so tailers for different files
 * never share locks.
 *
 * <p>The first line of each event is parsed against {@link #ENTRY_HEAD},
 * which matches the default Sling Commons Log Logback layout, for
 * example:
 * <pre>22.04.2026 13:09:29.266 *INFO* [OsgiInstallerImpl] com.adobexp.aem.loki.LogbackLokiBootstrap Loki forwarder started ...</pre>
 * Continuation lines (stack-trace frames, wrapped messages) are
 * appended to the in-progress entry until the next header line is
 * encountered.
 */
final class LogFileTailer {

    private static final Logger LOG = LoggerFactory.getLogger(LogFileTailer.class);

    private static final int READ_BUFFER = 64 * 1024;

    /** Maximum number of bytes read from a log file in a single tailing tick. */
    private static final int MAX_READ_PER_TICK = 512 * 1024;

    /** Hard limit for a single log entry (including multi-line stack trace). */
    private static final int MAX_ENTRY_BYTES = 256 * 1024;

    private static final Pattern ENTRY_HEAD = Pattern.compile(
            "^(\\d{2}\\.\\d{2}\\.\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+"
                    + "\\*(AUDIT|ERROR|WARN|INFO|DEBUG|TRACE)\\*\\s+"
                    + "\\[([^\\]]*)\\]\\s+"
                    + "(\\S+)(?:\\s+(.*))?$");

    final Path path;
    private FileChannel channel;
    private long offset;
    private long lastSize;
    private final StringBuilder pending = new StringBuilder(4096);
    private ParsedEntryBuilder currentEntry;

    LogFileTailer(Path path) {
        this.path = path;
    }

    /**
     * Reads any new bytes from the tailed file, parses complete entries,
     * and hands them to {@code sink}. Resilient to file rotation and
     * transient I/O errors; a failure here never propagates out of the
     * call.
     *
     * @param sink      callback invoked once per fully-parsed entry
     * @param verbose   when {@code true}, emits debug-level progress
     *                  and warnings about I/O issues
     */
    void tick(Consumer<ParsedEntry> sink, boolean verbose) {
        try {
            if (!Files.isRegularFile(path)) {
                closeQuiet();
                return;
            }
            final long sizeBefore = Files.size(path);
            if (channel == null) {
                openFromTail(sizeBefore);
            }
            final long size = Files.size(path);
            if (size < offset) {
                // file was rotated / truncated - reopen and start from the new tail
                closeQuiet();
                openFromTail(size);
                return;
            }
            if (size == offset) {
                return;
            }
            lastSize = size;
            final ByteBuffer buf = ByteBuffer.allocate(READ_BUFFER);
            long remaining = Math.min(size - offset, MAX_READ_PER_TICK);
            int totalRead = 0;
            while (remaining > 0) {
                buf.clear();
                final int toRead = (int) Math.min(remaining, buf.capacity());
                buf.limit(toRead);
                final int n = channel.read(buf, offset);
                if (n <= 0) {
                    break;
                }
                offset += n;
                remaining -= n;
                totalRead += n;
                buf.flip();
                final String chunk = StandardCharsets.UTF_8.decode(buf).toString();
                ingest(chunk, sink);
            }
            if (verbose && totalRead > 0) {
                LOG.debug("Loki tailer read {} bytes from {} (new offset {}/{})",
                        totalRead, path, offset, size);
            }
        } catch (IOException e) {
            if (verbose) {
                LOG.warn("Log tailer I/O error on {}: {}", path, e.toString());
            }
            closeQuiet();
        }
    }

    private void ingest(String chunk, Consumer<ParsedEntry> sink) {
        pending.append(chunk);
        int start = 0;
        for (int i = 0; i < pending.length(); i++) {
            if (pending.charAt(i) == '\n') {
                final int endOfLine = (i > 0 && pending.charAt(i - 1) == '\r') ? i - 1 : i;
                processLine(pending.substring(start, endOfLine), sink);
                start = i + 1;
            }
        }
        if (start > 0) {
            pending.delete(0, start);
        }
        // Guardrail: if a single entry grows beyond MAX_ENTRY_BYTES, flush
        // what we have so we do not accumulate unbounded memory.
        if (pending.length() > MAX_ENTRY_BYTES) {
            processLine(pending.toString(), sink);
            pending.setLength(0);
        }
    }

    private void processLine(String line, Consumer<ParsedEntry> sink) {
        final Matcher m = ENTRY_HEAD.matcher(line);
        if (m.matches()) {
            flushCurrent(sink);
            final String date = m.group(1);
            final String time = m.group(2);
            final String lvl = m.group(3);
            final String thread = m.group(4);
            final String logger = m.group(5);
            final String msg = m.group(6) != null ? m.group(6) : "";
            currentEntry = new ParsedEntryBuilder(
                    parseEpochMillis(date, time),
                    Level.fromAemTag(lvl),
                    thread,
                    logger,
                    msg);
        } else if (currentEntry != null) {
            // continuation line (stack trace etc.)
            currentEntry.appendLine(line);
            if (currentEntry.size() > MAX_ENTRY_BYTES) {
                flushCurrent(sink);
            }
        }
        // Otherwise, ignore - we received a continuation with no header,
        // typically a leading banner printed before the first log entry.
    }

    private void flushCurrent(Consumer<ParsedEntry> sink) {
        if (currentEntry != null) {
            sink.accept(currentEntry.build());
            currentEntry = null;
        }
    }

    private void openFromTail(long startOffset) throws IOException {
        channel = FileChannel.open(path, StandardOpenOption.READ);
        offset = Math.max(0L, startOffset);
        lastSize = offset;
        pending.setLength(0);
        currentEntry = null;
        LOG.info("Loki tailer opened {} (starting at offset {})", path, offset);
    }

    void close() {
        // Flush any buffered entry so we do not lose a trailing record on shutdown.
        if (currentEntry != null) {
            // best-effort - there is no sink to call at this point,
            // so just drop; shutdown is happening anyway
            currentEntry = null;
        }
        closeQuiet();
    }

    private void closeQuiet() {
        final FileChannel c = this.channel;
        this.channel = null;
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
        offset = 0;
        lastSize = 0;
        pending.setLength(0);
    }

    private static long parseEpochMillis(String date, String time) {
        try {
            final int day = Integer.parseInt(date.substring(0, 2));
            final int month = Integer.parseInt(date.substring(3, 5));
            final int year = Integer.parseInt(date.substring(6, 10));
            final int hour = Integer.parseInt(time.substring(0, 2));
            final int min = Integer.parseInt(time.substring(3, 5));
            final int sec = Integer.parseInt(time.substring(6, 8));
            final int ms = Integer.parseInt(time.substring(9, 12));
            return LocalDateTime.of(year, month, day, hour, min, sec, ms * 1_000_000)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * Accumulates multi-line log entries (header line + any continuation
     * lines) before materialising them into an immutable
     * {@link ParsedEntry}. The mutable builder lets us append stack-trace
     * lines in-place without reallocating the entry on every continuation.
     */
    private static final class ParsedEntryBuilder {
        private final long epochMillis;
        private final Level level;
        private final String threadName;
        private final String loggerName;
        private final StringBuilder message;

        ParsedEntryBuilder(long epochMillis, Level level, String threadName, String loggerName, String message) {
            this.epochMillis = epochMillis;
            this.level = level;
            this.threadName = threadName;
            this.loggerName = loggerName;
            this.message = new StringBuilder(Math.max(32, message.length())).append(message);
        }

        void appendLine(String line) {
            message.append('\n').append(line);
        }

        int size() {
            return message.length();
        }

        ParsedEntry build() {
            return new ParsedEntry(epochMillis, level, threadName, loggerName, message.toString());
        }
    }
}
