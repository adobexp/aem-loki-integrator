package com.adobexp.log;

/**
 * Callback contract used by {@link Logger} implementations to hand off a
 * rendered log event to whatever Loki forwarder is currently active.
 *
 * <p>This interface is the single extension point between the facade
 * (logger implementation) and the forwarder (OSGi component). The Loki
 * bootstrap registers an implementation in its {@code @Activate} method
 * and unregisters in {@code @Deactivate}; while no sink is registered,
 * every facade call still reaches SLF4J exactly as it would have
 * without the facade, but nothing is tee'd to Loki.
 *
 * <p><b>Filter independence (since 3.2.0).</b> Starting with the
 * decoupled-filtering contract, the facade adapter no longer consults
 * SLF4J's {@code isXxxEnabled()} to decide whether to tee an event.
 * Each call is always offered to the currently registered sink, which
 * is free to accept or reject it via {@link #accepts(String, Level)}.
 * This means the filter that controls what reaches Loki is completely
 * independent of the Sling {@code LogManager} configuration that
 * controls what reaches {@code aemerror.log}. A team can legitimately
 * run {@code error.log} at {@code WARN} while shipping {@code DEBUG}
 * from the same loggers to Loki.
 *
 * <p>Implementations MUST be non-blocking and exception-safe: the sink
 * runs on the caller's thread (typically an HTTP worker or a scheduled
 * job) and must not throw or block to avoid degrading the application.
 */
@FunctionalInterface
public interface FacadeSink {

    /**
     * Cheap, side-effect-free filter check. Called by the facade
     * adapter before formatting a parameterized log message so that
     * discarded events cost almost nothing. A sink with its own
     * per-logger / per-level filter SHOULD override this method; the
     * default implementation accepts everything, which preserves the
     * pre-3.2.0 "tee and let the sink decide later" contract for
     * backwards compatibility with third-party sinks.
     *
     * @param loggerName fully-qualified logger name
     * @param level      severity of the event
     * @return {@code true} if the sink would accept this event, in
     *         which case the adapter will format the message and call
     *         {@link #accept(String, Level, String, Throwable)}
     */
    default boolean accepts(String loggerName, Level level) {
        return true;
    }

    /**
     * Handed a fully-rendered message. The facade has already performed
     * SLF4J-style {@code {}} substitution, and the argument-level
     * throwable (if any) has been extracted from the variadic arg array
     * according to SLF4J semantics.
     *
     * @param loggerName fully-qualified logger name (i.e. the class name
     *                   passed to {@link LoggerFactory#getLogger})
     * @param level      severity of the event
     * @param message    rendered message (never {@code null}; pass an
     *                   empty string if nothing was provided)
     * @param throwable  optional exception associated with the event, or
     *                   {@code null} if none. Sinks MUST NOT rely on a
     *                   stack trace being present in {@code message} -
     *                   they should call {@link Throwable#getStackTrace}
     *                   themselves if they need one.
     */
    void accept(String loggerName, Level level, String message, Throwable throwable);
}
