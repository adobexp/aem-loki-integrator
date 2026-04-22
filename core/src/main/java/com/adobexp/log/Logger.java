package com.adobexp.log;

/**
 * Drop-in logging facade. Mirrors the public surface of
 * {@link org.slf4j.Logger} so application code can migrate by rewriting
 * only the imports (no log-statement bodies). Calls are transparently
 * forwarded to SLF4J - so AEM's standard Logback pipeline keeps writing
 * to {@code aemerror.log} as before - and additionally tee'd to the
 * Loki forwarder queue when the companion OSGi component
 * {@code com.adobexp.aem.loki.LogbackLokiBootstrap} is active and its
 * configured logger filter matches the event.
 *
 * <p>To use the facade, replace
 * <pre>
 * import org.slf4j.Logger;
 * import org.slf4j.LoggerFactory;
 * </pre>
 * with
 * <pre>
 * import com.adobexp.log.Logger;
 * import com.adobexp.log.LoggerFactory;
 * </pre>
 * Everything else in your class stays byte-for-byte identical.
 *
 * <p>If the Loki component is not active (for example because the
 * {@code com.adobexp.aem.loki.LogbackLokiBootstrap} configuration has
 * not been deployed, or because {@code facadeEnabled} is false), the
 * facade is a transparent pass-through to SLF4J at near-zero cost.
 */
public interface Logger {

    String getName();

    boolean isTraceEnabled();
    boolean isDebugEnabled();
    boolean isInfoEnabled();
    boolean isWarnEnabled();
    boolean isErrorEnabled();

    void trace(String msg);
    void trace(String format, Object arg);
    void trace(String format, Object arg1, Object arg2);
    void trace(String format, Object... args);
    void trace(String msg, Throwable t);

    void debug(String msg);
    void debug(String format, Object arg);
    void debug(String format, Object arg1, Object arg2);
    void debug(String format, Object... args);
    void debug(String msg, Throwable t);

    void info(String msg);
    void info(String format, Object arg);
    void info(String format, Object arg1, Object arg2);
    void info(String format, Object... args);
    void info(String msg, Throwable t);

    void warn(String msg);
    void warn(String format, Object arg);
    void warn(String format, Object arg1, Object arg2);
    void warn(String format, Object... args);
    void warn(String msg, Throwable t);

    void error(String msg);
    void error(String format, Object arg);
    void error(String format, Object arg1, Object arg2);
    void error(String format, Object... args);
    void error(String msg, Throwable t);
}
