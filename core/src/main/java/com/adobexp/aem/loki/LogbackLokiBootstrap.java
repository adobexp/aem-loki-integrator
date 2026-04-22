package com.adobexp.aem.loki;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards AEM / Sling / Felix log events to a Grafana Loki push endpoint.
 *
 * <p>The component tails the on-disk AEM log files produced by Sling
 * Commons Log (by default {@code $SLING_HOME/logs/error.log}), parses each
 * entry with the standard AEM layout (date, time, level, thread, logger,
 * message), batches the events in-memory and pushes them to Loki over
 * {@link HttpClient} with optional gzip compression and Basic auth.
 *
 * <p>The tailing approach is used deliberately: it captures <b>every</b>
 * log line Logback writes (on AEM classic and AEMaaCS alike) while relying
 * only on {@code java.nio.file}, {@code java.net.http} and
 * {@code org.osgi.service.component}. The bundle therefore has zero
 * compile-time or runtime dependency on Logback, SLF4J internals,
 * {@code org.slf4j.spi}, or any third-party logging library, which keeps
 * AEMaaCS Code Quality rule {@code java:S1874} happy.
 *
 * <p>The class name (and therefore the OSGi PID) is kept unchanged from the
 * 1.x releases so existing
 * {@code com.adobexp.aem.loki.LogbackLokiBootstrap.cfg.json}
 * configurations continue to apply without modification.
 */
@Component(
        service = {},
        immediate = true,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        property = {"process.label=AEM-LOKI | Loki Log Forwarder"})
@Designate(ocd = LogbackLokiBootstrap.Config.class)
public class LogbackLokiBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(LogbackLokiBootstrap.class);

    private static final String DEFAULT_MESSAGE_PATTERN = "*%level* [%thread] %logger | %msg";

    /** Prefix used to detect and drop our own log events (recursion guard). */
    private static final String SELF_LOGGER_PREFIX = "com.adobexp.aem.loki";

    /** Maximum number of bytes read from a log file in a single tailing tick. */
    private static final int MAX_READ_PER_TICK = 512 * 1024;

    /** Hard limit for a single log entry (including multi-line stack trace). */
    private static final int MAX_ENTRY_BYTES = 256 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MIN_QUEUE_CAPACITY = 1024;

    /**
     * Matches the first line of an AEM/Sling log entry, as written by the
     * default Sling Commons Log Logback layout, for example:
     * <pre>22.04.2026 13:09:29.266 *INFO* [OsgiInstallerImpl] com.adobexp.aem.loki.LogbackLokiBootstrap Loki forwarder started ...</pre>
     */
    private static final Pattern ENTRY_HEAD = Pattern.compile(
            "^(\\d{2}\\.\\d{2}\\.\\d{4})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+"
                    + "\\*(AUDIT|ERROR|WARN|INFO|DEBUG|TRACE)\\*\\s+"
                    + "\\[([^\\]]*)\\]\\s+"
                    + "(\\S+)(?:\\s+(.*))?$");

    private volatile Config config;
    private volatile BlockingQueue<QueuedEvent> queue;
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> flushTask;
    private volatile ScheduledFuture<?> tailTask;
    private volatile HttpClient httpClient;
    private volatile URI pushUri;
    private volatile String authHeader;
    private volatile List<LoggerFilter> loggerFilters = Collections.emptyList();
    private volatile List<LabelSpec> labelSpecs = Collections.emptyList();
    private volatile List<MessageToken> messageTokens = Collections.emptyList();
    private volatile List<LogFileTailer> tailers = Collections.emptyList();

    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong pushedBatches = new AtomicLong();
    private final AtomicLong pushedEvents = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private final AtomicLong seenCount = new AtomicLong();
    private final AtomicLong seenCountLastReport = new AtomicLong();
    private final AtomicLong matchedCount = new AtomicLong();
    private final AtomicLong matchedCountLastReport = new AtomicLong();

    @Activate
    @Modified
    protected void activate(Config cfg) {
        shutdown();
        final String url = trimToEmpty(cfg.url());
        if (url.isEmpty()) {
            LOG.warn("Loki push URL is empty - forwarder will stay dormant.");
            return;
        }

        final URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            LOG.error("Invalid Loki push URL '{}' - forwarder will stay dormant: {}",
                    url, e.toString());
            return;
        }

        this.config = cfg;
        this.pushUri = uri;
        this.loggerFilters = LoggerFilter.parse(cfg.loggers());
        if (this.loggerFilters.isEmpty()) {
            LOG.warn("No loggers configured on {} - forwarder will not forward anything.",
                    Config.class.getName());
        }
        this.labelSpecs = LabelSpec.parse(cfg.labels());
        this.messageTokens = MessageToken.parse(
                firstNonBlank(cfg.messagePattern(), DEFAULT_MESSAGE_PATTERN));
        this.authHeader = buildAuthHeader(cfg.username(), cfg.password());

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        final int capacity = Math.max(cfg.batchMaxItems() * 4, MIN_QUEUE_CAPACITY);
        this.queue = new ArrayBlockingQueue<>(capacity);

        final List<Path> resolved = resolveLogFiles(cfg.logFiles());
        final List<LogFileTailer> ts = new ArrayList<>(resolved.size());
        final List<Path> existing = new ArrayList<>();
        final List<Path> missing = new ArrayList<>();
        for (Path p : resolved) {
            ts.add(new LogFileTailer(p));
            if (Files.isRegularFile(p)) {
                existing.add(p);
            } else {
                missing.add(p);
            }
        }
        this.tailers = ts;
        if (!missing.isEmpty()) {
            LOG.warn("Loki forwarder: {} configured log file(s) do not exist yet - "
                            + "the tailer will pick them up once they appear: {}",
                    missing.size(), missing);
        }
        if (existing.isEmpty()) {
            LOG.warn("Loki forwarder: none of the configured log files exist right now - "
                    + "check 'logFiles' against the actual AEM log directory.");
            reportLogDirInventory();
        } else if (cfg.verbose()) {
            reportLogDirInventory();
        }

        this.scheduler = Executors.newScheduledThreadPool(2, new DaemonThreadFactory("aem-loki-forwarder"));

        final long flushMs = Math.max(cfg.batchTimeoutMs(), 250L);
        this.flushTask = this.scheduler.scheduleAtFixedRate(
                this::flushSafe, flushMs, flushMs, TimeUnit.MILLISECONDS);

        final long tailMs = Math.max(cfg.tailIntervalMs(), 100L);
        this.tailTask = this.scheduler.scheduleAtFixedRate(
                this::tailSafe, tailMs, tailMs, TimeUnit.MILLISECONDS);

        LOG.info("Loki forwarder started (url={}, logFiles={}, batchMaxItems={}, batchTimeoutMs={}, "
                        + "tailIntervalMs={}, queueCapacity={}, loggers={}, labels={}, verbose={})",
                url, resolved, cfg.batchMaxItems(), cfg.batchTimeoutMs(), tailMs, capacity,
                this.loggerFilters, this.labelSpecs.size(), cfg.verbose());
    }

    @Deactivate
    protected void deactivate() {
        shutdown();
    }

    private void shutdown() {
        final ScheduledFuture<?> flush = this.flushTask;
        this.flushTask = null;
        if (flush != null) {
            flush.cancel(false);
        }
        final ScheduledFuture<?> tail = this.tailTask;
        this.tailTask = null;
        if (tail != null) {
            tail.cancel(false);
        }
        final ScheduledExecutorService exec = this.scheduler;
        this.scheduler = null;
        if (exec != null) {
            try {
                exec.submit(this::tailSafe).get(2, TimeUnit.SECONDS);
                exec.submit(this::flushSafe).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best-effort drain
            }
            exec.shutdownNow();
        }
        for (LogFileTailer t : this.tailers) {
            t.close();
        }
        this.tailers = Collections.emptyList();
        this.queue = null;
        this.httpClient = null;
        this.pushUri = null;
        this.authHeader = null;
        this.config = null;
    }

    // ---------------------------------------------------------- tailing

    /**
     * Candidate AEM log directories probed when the configured log file
     * entry is relative. Ordered from most specific (runtime-exposed)
     * to most generic (container defaults). Covers:
     * <ul>
     *     <li>{@code sling.home}/logs - honoured when Sling exposes the property</li>
     *     <li>{@code $SLING_HOME/logs} - honoured when the runtime exports it</li>
     *     <li>{@code user.dir/crx-quickstart/logs} - classic AEM quickstart layout</li>
     *     <li>{@code user.dir/logs} - when the JVM is started from inside crx-quickstart</li>
     *     <li>{@code /opt/aem/crx-quickstart/logs} - AEMaaCS author/publish pod layout</li>
     *     <li>{@code /crx-quickstart/logs} - classic container layout</li>
     *     <li>{@code /var/log/experience-manager} - AEMaaCS canonical log mount</li>
     *     <li>{@code /var/log/aem} - fallback mount some AEMaaCS images use</li>
     *     <li>{@code /var/log/sling} - last-resort fallback</li>
     * </ul>
     *
     * Candidate <em>file names</em> (probed for each directory when the
     * configured entry is just a file name): {@code error.log},
     * {@code aemerror.log}, {@code aem-error.log}. The actual on-disk name
     * differs between AEM classic ({@code error.log}) and AEMaaCS pods
     * ({@code aemerror.log}).
     */
    private static List<Path> resolveLogFiles(String[] cfg) {
        final List<String> sources = new ArrayList<>();
        if (cfg != null) {
            for (String s : cfg) {
                if (s != null && !s.trim().isEmpty()) {
                    sources.add(s.trim());
                }
            }
        }
        if (sources.isEmpty()) {
            sources.add("error.log");
        }

        final List<Path> baseDirs = candidateLogDirs();
        final List<Path> out = new ArrayList<>(sources.size());
        for (String s : sources) {
            final String expanded = expandEnvRefs(s);
            final Path given = Paths.get(expanded);
            if (given.isAbsolute()) {
                out.add(given.normalize());
                continue;
            }
            final List<String> nameAliases = aliasFileNames(expanded);
            Path chosen = null;
            outer:
            for (Path base : baseDirs) {
                for (String alias : nameAliases) {
                    final Path candidate = base.resolve(alias).normalize();
                    if (Files.isRegularFile(candidate)) {
                        chosen = candidate;
                        break outer;
                    }
                }
            }
            if (chosen == null) {
                // Fall back to the first candidate directory even if the file
                // does not exist yet - the tailer will notice when it appears.
                chosen = baseDirs.isEmpty()
                        ? given.toAbsolutePath().normalize()
                        : baseDirs.get(0).resolve(expanded).normalize();
            }
            out.add(chosen);
        }
        return out;
    }

    private static List<String> aliasFileNames(String given) {
        // Only alias plain file names (e.g. "error.log"), not nested paths.
        if (given.contains("/") || given.contains(File.separator)) {
            return Collections.singletonList(given);
        }
        final List<String> out = new ArrayList<>(3);
        out.add(given);
        if (given.equalsIgnoreCase("error.log")) {
            out.add("aemerror.log");
            out.add("aem-error.log");
        } else if (given.equalsIgnoreCase("aemerror.log") || given.equalsIgnoreCase("aem-error.log")) {
            out.add("error.log");
        }
        return out;
    }

    private static List<Path> candidateLogDirs() {
        final List<Path> out = new ArrayList<>();
        final String slingHomeProp = System.getProperty("sling.home");
        if (slingHomeProp != null && !slingHomeProp.trim().isEmpty()) {
            out.add(Paths.get(slingHomeProp, "logs").normalize());
        }
        final String slingHomeEnv = System.getenv("SLING_HOME");
        if (slingHomeEnv != null && !slingHomeEnv.trim().isEmpty()) {
            out.add(Paths.get(slingHomeEnv, "logs").normalize());
        }
        final String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.trim().isEmpty()) {
            out.add(Paths.get(userDir, "crx-quickstart", "logs").normalize());
            out.add(Paths.get(userDir, "logs").normalize());
            out.add(Paths.get(userDir).normalize());
        }
        out.add(Paths.get("/opt/aem/crx-quickstart/logs").normalize());
        out.add(Paths.get("/crx-quickstart/logs").normalize());
        out.add(Paths.get("/var/log/experience-manager").normalize());
        out.add(Paths.get("/var/log/aem").normalize());
        out.add(Paths.get("/var/log/sling").normalize());
        // De-duplicate while preserving order
        final List<Path> dedup = new ArrayList<>(out.size());
        for (Path p : out) {
            if (!dedup.contains(p)) {
                dedup.add(p);
            }
        }
        return dedup;
    }

    /**
     * Prints a concise inventory of every candidate log directory: whether
     * it exists, and a listing of up to 20 log files it contains. This is
     * invaluable on AEMaaCS pods where the actual log location is not
     * documented and varies between image revisions. The output is one log
     * line per directory.
     */
    private static void reportLogDirInventory() {
        for (Path dir : candidateLogDirs()) {
            if (!Files.isDirectory(dir)) {
                LOG.info("Loki forwarder probe: {} (not present)", dir);
                continue;
            }
            final List<String> names = new ArrayList<>();
            try (java.util.stream.Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> {
                            final String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(".log") || n.contains("error") || n.contains("access")
                                    || n.contains("request") || n.contains("stdout")
                                    || n.contains("stderr");
                        })
                        .limit(20L)
                        .forEach(p -> {
                            long size = -1L;
                            try { size = Files.size(p); } catch (IOException ignored) { /* noop */ }
                            names.add(p.getFileName() + "(" + size + "b)");
                        });
            } catch (IOException | SecurityException e) {
                LOG.info("Loki forwarder probe: {} (unreadable: {})", dir, e.toString());
                continue;
            }
            LOG.info("Loki forwarder probe: {} -> {}", dir, names);
        }
    }

    private void tailSafe() {
        try {
            final Config cfg = this.config;
            if (cfg == null) {
                return;
            }
            for (LogFileTailer t : this.tailers) {
                try {
                    t.tick(this::ingestLine, cfg);
                } catch (Throwable th) {
                    if (cfg.verbose()) {
                        LOG.warn("Log tailer error on {}: {}", t.path, th.toString());
                    }
                }
            }
        } catch (Throwable t) {
            // never let the scheduler task die
            final Config cfg = this.config;
            if (cfg != null && cfg.verbose()) {
                LOG.warn("Log tailer failed", t);
            }
        }
    }

    /**
     * Accepts a single parsed log entry and, if it matches the configured
     * filters, enqueues it for the next Loki push. Invoked from the tailer
     * thread.
     */
    private void ingestLine(ParsedEntry entry) {
        final Config cfg = this.config;
        final BlockingQueue<QueuedEvent> q = this.queue;
        if (cfg == null || q == null || entry == null) {
            return;
        }
        if (entry.loggerName.startsWith(SELF_LOGGER_PREFIX)) {
            return; // avoid feedback loop from our own diagnostics
        }
        seenCount.incrementAndGet();
        if (!matchesAnyFilter(entry.loggerName, entry.level)) {
            return;
        }
        matchedCount.incrementAndGet();

        final QueuedEvent queued = new QueuedEvent(entry);
        if (!q.offer(queued)) {
            // Ring-buffer behaviour: drop the oldest event to accept the newest.
            q.poll();
            if (!q.offer(queued)) {
                droppedCount.incrementAndGet();
                return;
            }
            droppedCount.incrementAndGet();
        }
        if (q.size() >= cfg.batchMaxItems()) {
            final ScheduledExecutorService exec = this.scheduler;
            if (exec != null) {
                try {
                    exec.execute(this::flushSafe);
                } catch (Exception ignored) {
                    // scheduler shutting down; scheduled flush will catch up
                }
            }
        }
    }

    private boolean matchesAnyFilter(String loggerName, Level level) {
        final List<LoggerFilter> filters = this.loggerFilters;
        if (filters.isEmpty()) {
            return false;
        }
        for (LoggerFilter f : filters) {
            if (f.matches(loggerName, level)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------- flush + push

    private void flushSafe() {
        try {
            flush();
        } catch (Throwable t) {
            final Config cfg = this.config;
            if (cfg != null && cfg.verbose()) {
                LOG.warn("Loki forwarder flush failed", t);
            }
        }
    }

    private void flush() {
        final BlockingQueue<QueuedEvent> q = this.queue;
        final Config cfg = this.config;
        final HttpClient client = this.httpClient;
        final URI uri = this.pushUri;
        if (q == null || cfg == null || client == null || uri == null) {
            return;
        }
        if (cfg.verbose()) {
            final long seen = seenCount.get();
            final long matched = matchedCount.get();
            final long dSeen = seen - seenCountLastReport.getAndSet(seen);
            final long dMatched = matched - matchedCountLastReport.getAndSet(matched);
            LOG.info("Loki forwarder heartbeat: seen+={}, matched+={}, queued={}, dropped={}, pushedEvents={}, failedBatches={}",
                    dSeen, dMatched, q.size(), droppedCount.get(),
                    pushedEvents.get(), failedBatches.get());
        }
        if (q.isEmpty()) {
            return;
        }

        final int max = Math.max(cfg.batchMaxItems(), 1);
        final List<QueuedEvent> drained = new ArrayList<>(Math.min(max, q.size()));
        q.drainTo(drained, max);
        if (drained.isEmpty()) {
            return;
        }

        // Group events by the resolved label set so the Loki push body uses
        // as few streams as possible.
        final Map<String, Stream> streams = new LinkedHashMap<>();
        for (QueuedEvent ev : drained) {
            final Map<String, String> labels = resolveLabels(ev);
            final String key = streamKey(labels);
            final Stream s = streams.computeIfAbsent(key, k -> new Stream(labels));
            s.values.add(new String[]{
                    Long.toString(ev.entry.epochMillis * 1_000_000L),
                    formatMessage(ev)
            });
        }

        final byte[] body = buildJsonBody(streams.values());
        final byte[] compressed = gzipIfSmaller(body);
        final boolean gz = compressed != null;
        final byte[] payload = gz ? compressed : body;

        final HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json");
        if (gz) {
            req.header("Content-Encoding", "gzip");
        }
        if (this.authHeader != null) {
            req.header("Authorization", this.authHeader);
        }
        req.POST(HttpRequest.BodyPublishers.ofByteArray(payload));

        try {
            final HttpResponse<String> resp = client.send(
                    req.build(), HttpResponse.BodyHandlers.ofString());
            final int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                pushedBatches.incrementAndGet();
                pushedEvents.addAndGet(drained.size());
                if (cfg.verbose()) {
                    LOG.info("Loki push OK ({} events, {} streams, {} bytes{}, status={})",
                            drained.size(), streams.size(), payload.length,
                            gz ? " gzip" : "", status);
                }
            } else {
                failedBatches.incrementAndGet();
                LOG.warn("Loki push failed: status={}, events={}, body={}",
                        status, drained.size(), truncate(resp.body(), 512));
            }
        } catch (Exception e) {
            failedBatches.incrementAndGet();
            LOG.warn("Loki push error: events={}, error={}", drained.size(), e.toString());
        }
    }

    // ----------------------------------------------------- label resolving

    private Map<String, String> resolveLabels(QueuedEvent ev) {
        final List<LabelSpec> specs = this.labelSpecs;
        if (specs.isEmpty()) {
            final Map<String, String> fallback = new LinkedHashMap<>(1);
            fallback.put("app", "aem");
            return fallback;
        }
        final Map<String, String> out = new LinkedHashMap<>(specs.size());
        for (LabelSpec spec : specs) {
            final String value = spec.resolve(ev);
            if (value != null && !value.isEmpty()) {
                out.put(spec.key, value);
            }
        }
        if (out.isEmpty()) {
            out.put("app", "aem");
        }
        return out;
    }

    private static String streamKey(Map<String, String> labels) {
        final StringBuilder sb = new StringBuilder(64);
        for (Map.Entry<String, String> e : labels.entrySet()) {
            sb.append(e.getKey()).append('\u0001').append(e.getValue()).append('\u0002');
        }
        return sb.toString();
    }

    // ------------------------------------------------------ message format

    private String formatMessage(QueuedEvent ev) {
        final StringBuilder sb = new StringBuilder(128);
        for (MessageToken t : this.messageTokens) {
            t.append(sb, ev);
        }
        return sb.toString();
    }

    // --------------------------------------------------------- JSON writer

    private static byte[] buildJsonBody(Iterable<Stream> streams) {
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"streams\":[");
        boolean firstStream = true;
        for (Stream s : streams) {
            if (!firstStream) {
                sb.append(',');
            }
            firstStream = false;
            sb.append("{\"stream\":{");
            boolean firstLabel = true;
            for (Map.Entry<String, String> e : s.labels.entrySet()) {
                if (!firstLabel) {
                    sb.append(',');
                }
                firstLabel = false;
                appendJsonString(sb, e.getKey());
                sb.append(':');
                appendJsonString(sb, e.getValue());
            }
            sb.append("},\"values\":[");
            boolean firstValue = true;
            for (String[] v : s.values) {
                if (!firstValue) {
                    sb.append(',');
                }
                firstValue = false;
                sb.append('[');
                appendJsonString(sb, v[0]);
                sb.append(',');
                appendJsonString(sb, v[1]);
                sb.append(']');
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        if (value == null) {
            sb.append('"');
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static byte[] gzipIfSmaller(byte[] raw) {
        if (raw == null || raw.length < 1024) {
            return null;
        }
        try (ByteArrayOutputStream buf = new ByteArrayOutputStream(raw.length / 2);
             GZIPOutputStream gz = new GZIPOutputStream(buf)) {
            gz.write(raw);
            gz.finish();
            final byte[] out = buf.toByteArray();
            return out.length < raw.length ? out : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------- auth & misc

    private static String buildAuthHeader(String username, String password) {
        final String u = trimToEmpty(username);
        final String p = trimToEmpty(password);
        if (u.isEmpty() && p.isEmpty()) {
            return null;
        }
        final String creds = u + ':' + p;
        return "Basic " + Base64.getEncoder()
                .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred == null) {
            return fallback;
        }
        final String t = preferred.trim();
        return t.isEmpty() ? fallback : preferred;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ======================================================================
    //  Supporting types
    // ======================================================================

    /** Log levels recognised in AEM's default error.log layout. */
    enum Level {
        AUDIT, ERROR, WARN, INFO, DEBUG, TRACE;

        static Level fromAemTag(String tag) {
            if (tag == null) return INFO;
            switch (tag) {
                case "AUDIT":   return AUDIT;
                case "ERROR":   return ERROR;
                case "WARNING":
                case "WARN":    return WARN;
                case "INFO":    return INFO;
                case "DEBUG":   return DEBUG;
                case "TRACE":   return TRACE;
                default:        return INFO;
            }
        }
    }

    /** An event parsed out of an AEM log file line (including continuation). */
    static final class ParsedEntry {
        final long epochMillis;
        final Level level;
        final String threadName;
        final String loggerName;
        final String message;

        ParsedEntry(long epochMillis, Level level, String threadName, String loggerName, String message) {
            this.epochMillis = epochMillis;
            this.level = level;
            this.threadName = threadName;
            this.loggerName = loggerName;
            this.message = message;
        }
    }

    private static final class QueuedEvent {
        final ParsedEntry entry;

        QueuedEvent(ParsedEntry entry) {
            this.entry = entry;
        }
    }

    private static final class Stream {
        final Map<String, String> labels;
        final List<String[]> values = new ArrayList<>();

        Stream(Map<String, String> labels) {
            this.labels = labels;
        }
    }

    private static final class DaemonThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String namePrefix;
        private final java.util.concurrent.atomic.AtomicInteger counter =
                new java.util.concurrent.atomic.AtomicInteger();

        DaemonThreadFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            final Thread t = new Thread(r, namePrefix + '-' + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }

    /**
     * Follows a single log file and invokes a callback for every parsed
     * entry. Handles log rotation (the file shrinking, its inode being
     * replaced, or being moved away) by reopening the file when detected.
     * All state is kept inside this object so tailers for different files
     * never share locks.
     */
    static final class LogFileTailer {
        private static final int READ_BUFFER = 64 * 1024;

        final Path path;
        private FileChannel channel;
        private long offset;
        private long lastSize;
        private final StringBuilder pending = new StringBuilder(4096);
        private ParsedEntryBuilder currentEntry;

        LogFileTailer(Path path) {
            this.path = path;
        }

        void tick(java.util.function.Consumer<ParsedEntry> sink, Config cfg) {
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
                if (cfg != null && cfg.verbose() && totalRead > 0) {
                    LOG.debug("Loki tailer read {} bytes from {} (new offset {}/{})",
                            totalRead, path, offset, size);
                }
            } catch (IOException e) {
                if (cfg != null && cfg.verbose()) {
                    LOG.warn("Log tailer I/O error on {}: {}", path, e.toString());
                }
                closeQuiet();
            }
        }

        private void ingest(String chunk, java.util.function.Consumer<ParsedEntry> sink) {
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

        private void processLine(String line, java.util.function.Consumer<ParsedEntry> sink) {
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

        private void flushCurrent(java.util.function.Consumer<ParsedEntry> sink) {
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
                return java.time.LocalDateTime.of(year, month, day, hour, min, sec, ms * 1_000_000)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            } catch (Exception e) {
                return System.currentTimeMillis();
            }
        }
    }

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

    /**
     * Parsed per-logger filter. A filter matches an event when either the
     * logger name is exactly {@link #name} or starts with {@code name + "."},
     * and the event level is at least as severe as the configured
     * {@link #threshold} (lower {@code Level#ordinal()} is more severe:
     * AUDIT &lt; ERROR &lt; WARN &lt; INFO &lt; DEBUG &lt; TRACE).
     * The special names {@code ROOT} and an empty string match everything.
     */
    static final class LoggerFilter {
        final String name;
        final Level threshold;
        final boolean matchAll;

        LoggerFilter(String name, Level threshold) {
            this.name = name;
            this.threshold = threshold;
            this.matchAll = name.isEmpty() || "ROOT".equalsIgnoreCase(name);
        }

        boolean matches(String loggerName, Level level) {
            if (level.ordinal() > threshold.ordinal()) {
                return false;
            }
            if (matchAll) {
                return true;
            }
            if (loggerName.equals(name)) {
                return true;
            }
            return loggerName.length() > name.length()
                    && loggerName.startsWith(name)
                    && loggerName.charAt(name.length()) == '.';
        }

        @Override
        public String toString() {
            return (matchAll ? "ROOT" : name) + ':' + threshold;
        }

        static List<LoggerFilter> parse(String[] raw) {
            if (raw == null || raw.length == 0) {
                return Collections.emptyList();
            }
            final List<LoggerFilter> out = new ArrayList<>(raw.length);
            for (String entry : raw) {
                if (entry == null) {
                    continue;
                }
                final String e = entry.trim();
                if (e.isEmpty() || e.startsWith("#")) {
                    continue;
                }
                final int sep = findSeparator(e);
                if (sep <= 0 || sep == e.length() - 1) {
                    LOG.warn("Ignoring malformed logger entry '{}' "
                            + "(expected <name>:<LEVEL> or <name>=<LEVEL>)", e);
                    continue;
                }
                final String name = e.substring(0, sep).trim();
                final String lvl = e.substring(sep + 1).trim().toUpperCase();
                final Level threshold = parseLevel(lvl);
                if (threshold == null) {
                    LOG.warn("Ignoring logger entry '{}' - unknown level '{}'", e, lvl);
                    continue;
                }
                out.add(new LoggerFilter(name, threshold));
            }
            return out;
        }

        private static int findSeparator(String s) {
            final int colon = s.indexOf(':');
            final int equals = s.indexOf('=');
            if (colon == -1) return equals;
            if (equals == -1) return colon;
            return Math.min(colon, equals);
        }

        private static Level parseLevel(String level) {
            switch (level) {
                case "AUDIT": return Level.AUDIT;
                case "ERROR":
                case "SEVERE":
                case "FATAL": return Level.ERROR;
                case "WARN":
                case "WARNING": return Level.WARN;
                case "INFO": return Level.INFO;
                case "DEBUG":
                case "FINE": return Level.DEBUG;
                case "TRACE":
                case "FINER":
                case "FINEST":
                case "ALL": return Level.TRACE;
                case "OFF": return Level.AUDIT; // keep only AUDIT-level events
                default: return null;
            }
        }
    }

    /**
     * Parsed "key=valueTemplate" label spec. Supports a small set of tokens
     * in the value template so labels can mix static and per-event data:
     * <ul>
     *     <li>{@code %level} - event level (ERROR / WARN / ...)</li>
     *     <li>{@code %logger} / {@code %logger{N}} - logger name (optionally truncated)</li>
     *     <li>{@code %thread} - thread name parsed from the log line</li>
     *     <li>{@code ${HOSTNAME}} - value of the {@code HOSTNAME} env var</li>
     *     <li>{@code ${env:NAME;default=X}} - env var {@code NAME} or fallback {@code X}</li>
     * </ul>
     */
    static final class LabelSpec {
        final String key;
        final String template;

        LabelSpec(String key, String template) {
            this.key = key;
            this.template = template;
        }

        String resolve(QueuedEvent ev) {
            return expand(this.template, ev);
        }

        static List<LabelSpec> parse(String[] raw) {
            if (raw == null || raw.length == 0) {
                return Collections.emptyList();
            }
            final Map<String, LabelSpec> merged = new LinkedHashMap<>();
            for (String entry : raw) {
                if (entry == null) {
                    continue;
                }
                final String e = entry.trim();
                if (e.isEmpty() || e.startsWith("#")) {
                    continue;
                }
                final int eq = e.indexOf('=');
                if (eq <= 0 || eq == e.length() - 1) {
                    LOG.warn("Ignoring malformed label '{}' (expected key=value)", e);
                    continue;
                }
                final String key = e.substring(0, eq).trim();
                final String template = e.substring(eq + 1).trim();
                if (!key.isEmpty() && !template.isEmpty()) {
                    merged.put(key, new LabelSpec(key, template));
                }
            }
            return new ArrayList<>(merged.values());
        }
    }

    /**
     * A pre-parsed token in the configured message pattern. Building the
     * pattern once at configuration time avoids per-event regex/string
     * scanning.
     */
    interface MessageToken {
        void append(StringBuilder sb, QueuedEvent ev);

        static List<MessageToken> parse(String pattern) {
            final List<MessageToken> out = new ArrayList<>();
            final int len = pattern.length();
            final StringBuilder literal = new StringBuilder();
            int i = 0;
            while (i < len) {
                final char c = pattern.charAt(i);
                if (c != '%') {
                    literal.append(c);
                    i++;
                    continue;
                }
                if (literal.length() > 0) {
                    final String lit = literal.toString();
                    out.add((sb, ev) -> sb.append(lit));
                    literal.setLength(0);
                }
                int j = i + 1;
                while (j < len && (pattern.charAt(j) == '-' || Character.isDigit(pattern.charAt(j)))) {
                    j++;
                }
                final int nameStart = j;
                while (j < len && Character.isLetter(pattern.charAt(j))) {
                    j++;
                }
                if (nameStart == j) {
                    literal.append('%');
                    i++;
                    continue;
                }
                final String token = pattern.substring(nameStart, j).toLowerCase();
                Integer arg = null;
                if (j < len && pattern.charAt(j) == '{') {
                    final int close = pattern.indexOf('}', j + 1);
                    if (close > 0) {
                        try {
                            arg = Integer.parseInt(pattern.substring(j + 1, close));
                        } catch (NumberFormatException ignored) {
                            // ignore malformed arg
                        }
                        j = close + 1;
                    }
                }
                out.add(buildToken(token, arg));
                i = j;
            }
            if (literal.length() > 0) {
                final String lit = literal.toString();
                out.add((sb, ev) -> sb.append(lit));
            }
            return out;
        }

        static MessageToken buildToken(String name, Integer arg) {
            switch (name) {
                case "level":
                case "p":
                    return (sb, ev) -> sb.append(ev.entry.level.name());
                case "logger":
                case "c":
                    if (arg != null) {
                        final int max = arg;
                        return (sb, ev) -> sb.append(truncateLogger(ev.entry.loggerName, max));
                    }
                    return (sb, ev) -> sb.append(ev.entry.loggerName);
                case "thread":
                case "t":
                    return (sb, ev) -> sb.append(ev.entry.threadName);
                case "msg":
                case "m":
                case "message":
                    return (sb, ev) -> sb.append(ev.entry.message == null ? "" : ev.entry.message);
                case "date":
                case "d":
                    return (sb, ev) -> sb.append(java.time.Instant.ofEpochMilli(ev.entry.epochMillis));
                case "n":
                    return (sb, ev) -> sb.append('\n');
                default:
                    final String literal = '%' + name;
                    return (sb, ev) -> sb.append(literal);
            }
        }

        static String truncateLogger(String name, int max) {
            if (name == null || name.length() <= max || max <= 0) {
                return name == null ? "" : name;
            }
            int overflow = name.length() - max;
            final StringBuilder out = new StringBuilder(max);
            int i = 0;
            while (i < name.length() && overflow > 0) {
                final int dot = name.indexOf('.', i);
                if (dot < 0) {
                    break;
                }
                final int segLen = dot - i;
                if (segLen > 1) {
                    out.append(name.charAt(i)).append('.');
                    overflow -= (segLen - 1);
                    i = dot + 1;
                } else {
                    out.append(name, i, dot + 1);
                    i = dot + 1;
                }
            }
            out.append(name, i, name.length());
            return out.toString();
        }
    }

    // ---------------------------------------------------- label expansion

    private static String expand(String template, QueuedEvent ev) {
        if (template == null) {
            return "";
        }
        final int len = template.length();
        final StringBuilder sb = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            final char c = template.charAt(i);
            if (c == '$' && i + 1 < len && template.charAt(i + 1) == '{') {
                final int close = template.indexOf('}', i + 2);
                if (close > 0) {
                    sb.append(expandEnvRef(template.substring(i + 2, close)));
                    i = close + 1;
                    continue;
                }
            }
            if (c == '%') {
                int j = i + 1;
                while (j < len && (template.charAt(j) == '-' || Character.isDigit(template.charAt(j)))) {
                    j++;
                }
                final int nameStart = j;
                while (j < len && Character.isLetter(template.charAt(j))) {
                    j++;
                }
                if (nameStart < j) {
                    final String name = template.substring(nameStart, j).toLowerCase();
                    Integer arg = null;
                    if (j < len && template.charAt(j) == '{') {
                        final int close = template.indexOf('}', j + 1);
                        if (close > 0) {
                            try {
                                arg = Integer.parseInt(template.substring(j + 1, close));
                            } catch (NumberFormatException ignored) {
                                // ignore malformed arg
                            }
                            j = close + 1;
                        }
                    }
                    sb.append(resolveConversion(name, arg, ev));
                    i = j;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String resolveConversion(String name, Integer arg, QueuedEvent ev) {
        switch (name) {
            case "level":
            case "p":
                return ev.entry.level.name();
            case "logger":
            case "c":
                return arg != null ? MessageToken.truncateLogger(ev.entry.loggerName, arg) : ev.entry.loggerName;
            case "thread":
            case "t":
                return ev.entry.threadName;
            case "msg":
            case "m":
            case "message":
                return Objects.toString(ev.entry.message, "");
            default:
                return "%" + name;
        }
    }

    private static String expandEnvRefs(String template) {
        if (template == null || template.indexOf("${") < 0) {
            return template;
        }
        final int len = template.length();
        final StringBuilder sb = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            final char c = template.charAt(i);
            if (c == '$' && i + 1 < len && template.charAt(i + 1) == '{') {
                final int close = template.indexOf('}', i + 2);
                if (close > 0) {
                    sb.append(expandEnvRef(template.substring(i + 2, close)));
                    i = close + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String expandEnvRef(String expr) {
        if (expr.startsWith("env:")) {
            final String rest = expr.substring(4);
            final int sc = rest.indexOf(';');
            final String name;
            String fallback = "";
            if (sc >= 0) {
                name = rest.substring(0, sc).trim();
                final String tail = rest.substring(sc + 1).trim();
                if (tail.startsWith("default=")) {
                    fallback = tail.substring("default=".length());
                }
            } else {
                name = rest.trim();
            }
            final String v = System.getenv(name);
            return v != null ? v : fallback;
        }
        final String v = System.getenv(expr);
        if (v != null) {
            return v;
        }
        return Objects.toString(System.getProperty(expr), "");
    }

    // ======================================================================
    //  OSGi metatype
    // ======================================================================

    @ObjectClassDefinition(
            name = "AEM | Loki Integrator - Logback bootstrap",
            description = "Forwards AEM / Sling / Felix log events to a Grafana Loki "
                    + "push endpoint by tailing the on-disk error.log produced by "
                    + "Sling Commons Log. All settings are read from this configuration.")
    public @interface Config {

        @AttributeDefinition(
                name = "Loki push URL",
                description = "Full URL of the Loki push endpoint, "
                        + "e.g. http://localhost:3100/loki/api/v1/push.")
        String url() default "http://localhost:3100/loki/api/v1/push";

        @AttributeDefinition(
                name = "Basic auth username",
                description = "Username for Loki basic auth. Leave empty to disable authentication.")
        String username() default "";

        @AttributeDefinition(
                name = "Basic auth password",
                description = "Password for Loki basic auth. Leave empty to disable authentication.",
                type = AttributeType.PASSWORD)
        String password() default "";

        @AttributeDefinition(
                name = "Labels",
                description = "Loki labels attached to every stream. One 'key=value' pair per entry. "
                        + "Values may contain conversion words (%level, %logger, %thread) "
                        + "and environment references (${HOSTNAME}, ${env:NAME;default=FOO}).",
                cardinality = Integer.MAX_VALUE)
        String[] labels() default {
                "app=aem",
                "environment=LOCAL",
                "tier=Author",
                "host=${HOSTNAME}",
                "level=%level"
        };

        @AttributeDefinition(
                name = "Batch: max items",
                description = "Maximum number of log events per Loki HTTP batch.")
        int batchMaxItems() default 700;

        @AttributeDefinition(
                name = "Batch: max age (ms)",
                description = "Maximum time (in milliseconds) a batch is held before being pushed to Loki.")
        int batchTimeoutMs() default 9000;

        @AttributeDefinition(
                name = "Send queue: max bytes",
                description = "Reserved for future use. Ignored in 2.x (queue capacity is "
                        + "derived from batchMaxItems).")
        long sendQueueMaxBytes() default 0L;

        @AttributeDefinition(
                name = "Verbose",
                description = "If true, every successful Loki push logs a confirmation line plus "
                        + "periodic heartbeat statistics.")
        boolean verbose() default false;

        @AttributeDefinition(
                name = "Message pattern",
                description = "Pattern used to render the log line sent to Loki. "
                        + "Supports %level, %logger{N}, %thread, %msg, %date, %n.")
        String messagePattern() default DEFAULT_MESSAGE_PATTERN;

        @AttributeDefinition(
                name = "Loggers",
                description = "Loggers whose events are forwarded to Loki. "
                        + "One entry per logger in the form '<name>:<LEVEL>' or '<name>=<LEVEL>', "
                        + "for example 'com.adobexp.aem:DEBUG'. Use 'ROOT' to forward everything.",
                cardinality = Integer.MAX_VALUE)
        String[] loggers() default {
                "com.adobexp.aem:DEBUG"
        };

        @AttributeDefinition(
                name = "Log files",
                description = "On-disk log files to tail. Entries can be absolute paths or paths "
                        + "relative to $SLING_HOME/logs. Supports ${HOSTNAME} and ${env:NAME;default=X} "
                        + "substitutions. On AEMaaCS the default 'error.log' resolves to the standard "
                        + "Sling error log of the running author/publish instance.",
                cardinality = Integer.MAX_VALUE)
        String[] logFiles() default {"error.log"};

        @AttributeDefinition(
                name = "Tail polling interval (ms)",
                description = "How often the log files are polled for new data. 500ms is a good "
                        + "default; lower values shorten Loki ingestion latency at the cost of more I/O.")
        int tailIntervalMs() default 500;
    }
}
