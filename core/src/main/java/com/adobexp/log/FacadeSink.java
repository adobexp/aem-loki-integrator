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
 * <p>Implementations MUST be non-blocking and exception-safe: the sink
 * runs on the caller's thread (typically an HTTP worker or a scheduled
 * job) and must not throw or block to avoid degrading the application.
 */
@FunctionalInterface
public interface FacadeSink {

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
