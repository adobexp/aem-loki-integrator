package com.adobexp.log;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point for acquiring {@link Logger} instances. API-compatible with
 * {@link org.slf4j.LoggerFactory} for the two methods application code
 * normally uses: {@link #getLogger(Class)} and {@link #getLogger(String)}.
 * Loggers are cached per name so repeated lookups are cheap.
 *
 * <p>The companion OSGi forwarder component (see
 * {@code com.adobexp.aem.loki.LogbackLokiBootstrap}) registers a
 * {@link FacadeSink} here during activation so every call made through
 * the facade is also tee'd to Loki. When no sink is registered (the
 * component is inactive or {@code facadeEnabled} is {@code false}), the
 * facade behaves as a transparent SLF4J wrapper.
 */
public final class LoggerFactory {

    private static volatile FacadeSink sink;

    /** Caches one adapter per logger name to avoid repeated SLF4J lookups. */
    private static final ConcurrentHashMap<String, Logger> CACHE = new ConcurrentHashMap<>();

    private LoggerFactory() {
        // utility class - not instantiable
    }

    public static Logger getLogger(Class<?> clazz) {
        Objects.requireNonNull(clazz, "clazz");
        return getLogger(clazz.getName());
    }

    public static Logger getLogger(String name) {
        Objects.requireNonNull(name, "name");
        return CACHE.computeIfAbsent(name, FacadeLoggerAdapter::new);
    }

    /**
     * Installs the sink that receives every facade event in addition to
     * SLF4J. Called by the Loki bootstrap on activation. Passing
     * {@code null} removes the currently registered sink (called on
     * deactivation).
     *
     * <p>Concurrency: writes are ordered via a {@code volatile} field
     * so readers on other threads see the new sink on the next call.
     * A sink change is effectively instantaneous from the facade's
     * perspective.
     */
    public static void setSink(FacadeSink newSink) {
        sink = newSink;
    }

    /**
     * Returns the currently registered sink, or {@code null}. Used by
     * the adapter to avoid the tee work when no forwarder is active.
     */
    public static FacadeSink getSink() {
        return sink;
    }
}
