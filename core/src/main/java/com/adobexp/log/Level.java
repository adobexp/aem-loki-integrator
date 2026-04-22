package com.adobexp.log;

/**
 * Severity levels supported by the {@link Logger} facade. Mirrors the
 * five SLF4J levels; intentionally excludes the {@code AUDIT} and
 * {@code OFF} pseudo-levels used by AEM's Logback layout - those are
 * handled on the Loki server side once events are ingested.
 */
public enum Level {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR
}
