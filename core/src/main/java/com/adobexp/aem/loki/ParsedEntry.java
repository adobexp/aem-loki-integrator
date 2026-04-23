/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Single log event that has been accepted by either the facade sink
 * ({@link com.adobexp.log.FacadeSink}) or the on-disk log tailer
 * ({@link LogFileTailer}), and is now ready for batching and push.
 *
 * <p>The class is immutable and fields are package-private so that the
 * pattern tokenizer ({@link MessageToken}) and the label/pattern expander
 * ({@link PatternExpander}) can access them without going through
 * getters, keeping the per-event render allocation-free.
 */
final class ParsedEntry {

    final long epochMillis;
    final Level level;
    final String threadName;
    final String loggerName;
    final String message;

    /**
     * Pre-rendered throwable stack trace, used by the {@code %ex} /
     * {@code %exception} / {@code %throwable} pattern tokens. Non-null
     * only for facade-originated events that carried a {@link Throwable};
     * tailer-originated events keep this {@code null} because the raw
     * stack-trace lines are already appended to {@link #message} by the
     * line parser.
     */
    final String throwableText;

    /**
     * Snapshot of the SLF4J {@link org.slf4j.MDC} context map at the
     * moment the event was recorded. Used by the {@code %X{key}} /
     * {@code %mdc{key}} pattern tokens. {@code null} (or empty) when
     * the calling thread had no MDC keys set, or when the event came
     * from the tailer path. The map is unmodifiable.
     */
    final Map<String, String> mdc;

    ParsedEntry(long epochMillis, Level level, String threadName, String loggerName, String message) {
        this(epochMillis, level, threadName, loggerName, message, null, null);
    }

    ParsedEntry(long epochMillis, Level level, String threadName, String loggerName,
                String message, String throwableText) {
        this(epochMillis, level, threadName, loggerName, message, throwableText, null);
    }

    ParsedEntry(long epochMillis, Level level, String threadName, String loggerName,
                String message, String throwableText, Map<String, String> mdc) {
        this.epochMillis = epochMillis;
        this.level = level;
        this.threadName = threadName;
        this.loggerName = loggerName;
        this.message = message;
        this.throwableText = throwableText;
        this.mdc = (mdc == null || mdc.isEmpty())
                ? null
                : Collections.unmodifiableMap(new HashMap<>(mdc));
    }
}
