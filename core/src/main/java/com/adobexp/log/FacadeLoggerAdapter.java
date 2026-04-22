package com.adobexp.log;

/**
 * Package-private {@link Logger} implementation that (1) delegates every
 * call to the underlying SLF4J logger so AEM's Logback pipeline keeps
 * producing {@code aemerror.log} lines exactly as before, and (2) tees
 * a rendered copy to the currently registered {@link FacadeSink} (the
 * Loki forwarder) when it is active and the SLF4J level check passes.
 *
 * <p>The adapter caches nothing per call and allocates one small
 * {@link MessageFormatter.FormattedMessage} per event. When no sink is
 * registered (most application lifetimes, since the forwarder activates
 * only when an OSGi config is present), the tee branch short-circuits
 * on the first {@code volatile} read and the adapter is effectively a
 * thin wrapper over SLF4J.
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
        if (delegate.isTraceEnabled()) tee(Level.TRACE, msg, null);
    }
    @Override public void trace(String format, Object arg) {
        delegate.trace(format, arg);
        if (delegate.isTraceEnabled()) tee(Level.TRACE, MessageFormatter.format(format, arg));
    }
    @Override public void trace(String format, Object arg1, Object arg2) {
        delegate.trace(format, arg1, arg2);
        if (delegate.isTraceEnabled()) tee(Level.TRACE, MessageFormatter.format(format, arg1, arg2));
    }
    @Override public void trace(String format, Object... args) {
        delegate.trace(format, args);
        if (delegate.isTraceEnabled()) tee(Level.TRACE, MessageFormatter.format(format, args));
    }
    @Override public void trace(String msg, Throwable t) {
        delegate.trace(msg, t);
        if (delegate.isTraceEnabled()) tee(Level.TRACE, msg, t);
    }

    // ---- DEBUG -----------------------------------------------------------

    @Override public void debug(String msg) {
        delegate.debug(msg);
        if (delegate.isDebugEnabled()) tee(Level.DEBUG, msg, null);
    }
    @Override public void debug(String format, Object arg) {
        delegate.debug(format, arg);
        if (delegate.isDebugEnabled()) tee(Level.DEBUG, MessageFormatter.format(format, arg));
    }
    @Override public void debug(String format, Object arg1, Object arg2) {
        delegate.debug(format, arg1, arg2);
        if (delegate.isDebugEnabled()) tee(Level.DEBUG, MessageFormatter.format(format, arg1, arg2));
    }
    @Override public void debug(String format, Object... args) {
        delegate.debug(format, args);
        if (delegate.isDebugEnabled()) tee(Level.DEBUG, MessageFormatter.format(format, args));
    }
    @Override public void debug(String msg, Throwable t) {
        delegate.debug(msg, t);
        if (delegate.isDebugEnabled()) tee(Level.DEBUG, msg, t);
    }

    // ---- INFO ------------------------------------------------------------

    @Override public void info(String msg) {
        delegate.info(msg);
        if (delegate.isInfoEnabled()) tee(Level.INFO, msg, null);
    }
    @Override public void info(String format, Object arg) {
        delegate.info(format, arg);
        if (delegate.isInfoEnabled()) tee(Level.INFO, MessageFormatter.format(format, arg));
    }
    @Override public void info(String format, Object arg1, Object arg2) {
        delegate.info(format, arg1, arg2);
        if (delegate.isInfoEnabled()) tee(Level.INFO, MessageFormatter.format(format, arg1, arg2));
    }
    @Override public void info(String format, Object... args) {
        delegate.info(format, args);
        if (delegate.isInfoEnabled()) tee(Level.INFO, MessageFormatter.format(format, args));
    }
    @Override public void info(String msg, Throwable t) {
        delegate.info(msg, t);
        if (delegate.isInfoEnabled()) tee(Level.INFO, msg, t);
    }

    // ---- WARN ------------------------------------------------------------

    @Override public void warn(String msg) {
        delegate.warn(msg);
        if (delegate.isWarnEnabled()) tee(Level.WARN, msg, null);
    }
    @Override public void warn(String format, Object arg) {
        delegate.warn(format, arg);
        if (delegate.isWarnEnabled()) tee(Level.WARN, MessageFormatter.format(format, arg));
    }
    @Override public void warn(String format, Object arg1, Object arg2) {
        delegate.warn(format, arg1, arg2);
        if (delegate.isWarnEnabled()) tee(Level.WARN, MessageFormatter.format(format, arg1, arg2));
    }
    @Override public void warn(String format, Object... args) {
        delegate.warn(format, args);
        if (delegate.isWarnEnabled()) tee(Level.WARN, MessageFormatter.format(format, args));
    }
    @Override public void warn(String msg, Throwable t) {
        delegate.warn(msg, t);
        if (delegate.isWarnEnabled()) tee(Level.WARN, msg, t);
    }

    // ---- ERROR -----------------------------------------------------------

    @Override public void error(String msg) {
        delegate.error(msg);
        if (delegate.isErrorEnabled()) tee(Level.ERROR, msg, null);
    }
    @Override public void error(String format, Object arg) {
        delegate.error(format, arg);
        if (delegate.isErrorEnabled()) tee(Level.ERROR, MessageFormatter.format(format, arg));
    }
    @Override public void error(String format, Object arg1, Object arg2) {
        delegate.error(format, arg1, arg2);
        if (delegate.isErrorEnabled()) tee(Level.ERROR, MessageFormatter.format(format, arg1, arg2));
    }
    @Override public void error(String format, Object... args) {
        delegate.error(format, args);
        if (delegate.isErrorEnabled()) tee(Level.ERROR, MessageFormatter.format(format, args));
    }
    @Override public void error(String msg, Throwable t) {
        delegate.error(msg, t);
        if (delegate.isErrorEnabled()) tee(Level.ERROR, msg, t);
    }

    // ---- internal --------------------------------------------------------

    private void tee(Level level, String message, Throwable t) {
        final FacadeSink s = LoggerFactory.getSink();
        if (s == null) {
            return;
        }
        try {
            s.accept(name, level, message == null ? "" : message, t);
        } catch (Throwable ignored) {
            // Never let a misbehaving sink crash the calling thread.
        }
    }

    private void tee(Level level, MessageFormatter.FormattedMessage fm) {
        final FacadeSink s = LoggerFactory.getSink();
        if (s == null) {
            return;
        }
        try {
            s.accept(name, level, fm.message, fm.throwable);
        } catch (Throwable ignored) {
            // Never let a misbehaving sink crash the calling thread.
        }
    }
}
