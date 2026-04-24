package com.adobexp.log;

/**
 * Package-private {@link Logger} implementation that (1) delegates every
 * call to the underlying SLF4J logger so AEM's Logback pipeline keeps
 * producing {@code aemerror.log} lines exactly as the Sling
 * {@code LogManager} configuration dictates, and (2) tees a rendered
 * copy to the currently registered {@link FacadeSink} (the Loki
 * forwarder) when one is active and its {@link FacadeSink#accepts}
 * filter allows the event through.
 *
 * <p><b>Decoupled filter model (since 3.2.0).</b> Earlier versions
 * short-circuited the tee branch on {@code delegate.isXxxEnabled()},
 * which coupled Loki's visibility into {@code com.pg.*} (or any other
 * logger tree) to whatever level was configured in
 * {@code org.apache.sling.commons.log.LogManager.factory.config~*}. In
 * practice that made it impossible to run {@code aemerror.log} at
 * {@code WARN} (cheap) while shipping {@code DEBUG} for the same
 * loggers to Loki (rich observability). That gate has been removed:
 * the adapter now unconditionally calls the SLF4J delegate (so the
 * Logback pipeline keeps filtering for {@code error.log}) and
 * unconditionally offers every event to the currently registered
 * sink. The sink's {@link FacadeSink#accepts} hook is the single
 * authority for what reaches Loki.
 *
 * <p><b>Performance.</b> When no sink is registered the {@code tee}
 * branch short-circuits on the first {@code volatile} read and the
 * adapter is effectively a thin wrapper over SLF4J. When a sink is
 * registered, {@code accepts} is called before any string formatting,
 * so events destined to be dropped cost only the sink's cheap filter
 * lookup (a logger-name prefix check and a level compare in the
 * Loki bootstrap).
 */
final class FacadeLoggerAdapter implements Logger {

    private final String name;
    private final org.slf4j.Logger delegate;

    FacadeLoggerAdapter(String name) {
        this.name = name;
        this.delegate = org.slf4j.LoggerFactory.getLogger(name);
    }

    @Override public String getName() { return name; }

    @Override public boolean isTraceEnabled() { return delegate.isTraceEnabled(); }
    @Override public boolean isDebugEnabled() { return delegate.isDebugEnabled(); }
    @Override public boolean isInfoEnabled()  { return delegate.isInfoEnabled();  }
    @Override public boolean isWarnEnabled()  { return delegate.isWarnEnabled();  }
    @Override public boolean isErrorEnabled() { return delegate.isErrorEnabled(); }

    // ---- TRACE -----------------------------------------------------------

    @Override public void trace(String msg) {
        delegate.trace(msg);
        tee(Level.TRACE, msg, null);
    }
    @Override public void trace(String format, Object arg) {
        delegate.trace(format, arg);
        teeFormat(Level.TRACE, format, arg);
    }
    @Override public void trace(String format, Object arg1, Object arg2) {
        delegate.trace(format, arg1, arg2);
        teeFormat(Level.TRACE, format, arg1, arg2);
    }
    @Override public void trace(String format, Object... args) {
        delegate.trace(format, args);
        teeFormat(Level.TRACE, format, args);
    }
    @Override public void trace(String msg, Throwable t) {
        delegate.trace(msg, t);
        tee(Level.TRACE, msg, t);
    }

    // ---- DEBUG -----------------------------------------------------------

    @Override public void debug(String msg) {
        delegate.debug(msg);
        tee(Level.DEBUG, msg, null);
    }
    @Override public void debug(String format, Object arg) {
        delegate.debug(format, arg);
        teeFormat(Level.DEBUG, format, arg);
    }
    @Override public void debug(String format, Object arg1, Object arg2) {
        delegate.debug(format, arg1, arg2);
        teeFormat(Level.DEBUG, format, arg1, arg2);
    }
    @Override public void debug(String format, Object... args) {
        delegate.debug(format, args);
        teeFormat(Level.DEBUG, format, args);
    }
    @Override public void debug(String msg, Throwable t) {
        delegate.debug(msg, t);
        tee(Level.DEBUG, msg, t);
    }

    // ---- INFO ------------------------------------------------------------

    @Override public void info(String msg) {
        delegate.info(msg);
        tee(Level.INFO, msg, null);
    }
    @Override public void info(String format, Object arg) {
        delegate.info(format, arg);
        teeFormat(Level.INFO, format, arg);
    }
    @Override public void info(String format, Object arg1, Object arg2) {
        delegate.info(format, arg1, arg2);
        teeFormat(Level.INFO, format, arg1, arg2);
    }
    @Override public void info(String format, Object... args) {
        delegate.info(format, args);
        teeFormat(Level.INFO, format, args);
    }
    @Override public void info(String msg, Throwable t) {
        delegate.info(msg, t);
        tee(Level.INFO, msg, t);
    }

    // ---- WARN ------------------------------------------------------------

    @Override public void warn(String msg) {
        delegate.warn(msg);
        tee(Level.WARN, msg, null);
    }
    @Override public void warn(String format, Object arg) {
        delegate.warn(format, arg);
        teeFormat(Level.WARN, format, arg);
    }
    @Override public void warn(String format, Object arg1, Object arg2) {
        delegate.warn(format, arg1, arg2);
        teeFormat(Level.WARN, format, arg1, arg2);
    }
    @Override public void warn(String format, Object... args) {
        delegate.warn(format, args);
        teeFormat(Level.WARN, format, args);
    }
    @Override public void warn(String msg, Throwable t) {
        delegate.warn(msg, t);
        tee(Level.WARN, msg, t);
    }

    // ---- ERROR -----------------------------------------------------------

    @Override public void error(String msg) {
        delegate.error(msg);
        tee(Level.ERROR, msg, null);
    }
    @Override public void error(String format, Object arg) {
        delegate.error(format, arg);
        teeFormat(Level.ERROR, format, arg);
    }
    @Override public void error(String format, Object arg1, Object arg2) {
        delegate.error(format, arg1, arg2);
        teeFormat(Level.ERROR, format, arg1, arg2);
    }
    @Override public void error(String format, Object... args) {
        delegate.error(format, args);
        teeFormat(Level.ERROR, format, args);
    }
    @Override public void error(String msg, Throwable t) {
        delegate.error(msg, t);
        tee(Level.ERROR, msg, t);
    }

    // ---- internal --------------------------------------------------------

    /**
     * Fast path for calls that have already rendered a message (and
     * optionally a throwable). No formatting is performed; the sink's
     * filter still gets to drop the event before any allocation.
     */
    private void tee(Level level, String message, Throwable t) {
        final FacadeSink s = LoggerFactory.getSink();
        if (s == null) {
            return;
        }
        if (!s.accepts(name, level)) {
            return;
        }
        try {
            s.accept(name, level, message == null ? "" : message, t);
        } catch (Throwable ignored) {
            // Never let a misbehaving sink crash the calling thread.
        }
    }

    /**
     * Deferred-formatting path for parameterized log calls. The sink is
     * consulted first via {@link FacadeSink#accepts}, so events that
     * will be dropped never incur the cost of
     * {@link MessageFormatter#format}.
     */
    private void teeFormat(Level level, String format, Object arg) {
        final FacadeSink s = LoggerFactory.getSink();
        if (s == null) {
            return;
        }
        if (!s.accepts(name, level)) {
            return;
        }
        final MessageFormatter.FormattedMessage fm = MessageFormatter.format(format, arg);
        try {
            s.accept(name, level, fm.message, fm.throwable);
        } catch (Throwable ignored) {
            // Never let a misbehaving sink crash the calling thread.
        }
    }

    private void teeFormat(Level level, String format, Object arg1, Object arg2) {
        final FacadeSink s = LoggerFactory.getSink();
        if (s == null) {
            return;
        }
        if (!s.accepts(name, level)) {
            return;
        }
        final MessageFormatter.FormattedMessage fm = MessageFormatter.format(format, arg1, arg2);
        try {
            s.accept(name, level, fm.message, fm.throwable);
        } catch (Throwable ignored) {
            // Never let a misbehaving sink crash the calling thread.
        }
    }

    private void teeFormat(Level level, String format, Object... args) {
        final FacadeSink s = LoggerFactory.getSink();
        if (s == null) {
            return;
        }
        if (!s.accepts(name, level)) {
            return;
        }
        final MessageFormatter.FormattedMessage fm = MessageFormatter.format(format, args);
        try {
            s.accept(name, level, fm.message, fm.throwable);
        } catch (Throwable ignored) {
            // Never let a misbehaving sink crash the calling thread.
        }
    }
}
