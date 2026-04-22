package com.adobexp.aem.loki;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Attaches a Loki (loki4j) appender directly to AEM's Logback
 * {@code LoggerContext} through SLF4J + reflection.
 *
 * <p>Design notes - especially for AEMaaCS:
 * <ul>
 *   <li>The bundle does not compile against any {@code ch.qos.logback.*}
 *       class or against the internal
 *       {@code org.apache.sling.commons.log.logback} API, which keeps
 *       AEMaaCS Code Quality rule {@code java:S1874} green.</li>
 *   <li>On activation the component locates the live Logback
 *       {@code ch.qos.logback.classic.LoggerContext} through
 *       {@link LoggerFactory#getILoggerFactory()}, creates an instance of
 *       {@code com.github.loki4j.logback.Loki4jAppender} via the
 *       LoggerContext's class loader (the Loki4j classes are published by
 *       the {@code aem-loki-integrator.loki4j} fragment attached to the
 *       Sling log host), configures it from OSGi properties and attaches
 *       it to the loggers listed in {@link Config#loggers()}.</li>
 *   <li>Because Logback may rebuild its context at any time (for example
 *       when Sling Commons Log re-applies its configuration), a daemon
 *       watchdog periodically verifies that our appender is still started
 *       and attached, re-attaching it if necessary. This replaces the
 *       previous {@code ConfigProvider}/reconfigure dance that did not
 *       fire reliably on AEMaaCS.</li>
 * </ul>
 */
@Component(
        service = {},
        immediate = true,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        property = {"process.label=AEM-LOKI | LogbackLokiBootstrap"})
@Designate(ocd = LogbackLokiBootstrap.Config.class)
public class LogbackLokiBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(LogbackLokiBootstrap.class);

    private static final String APPENDER_NAME = "AEM_LOKI_APPENDER";
    private static final String DEFAULT_MESSAGE_PATTERN = "*%level* [%thread] %logger{100} | %msg %ex";

    private static final String LOGBACK_LOGGER_CONTEXT_CLASS = "ch.qos.logback.classic.LoggerContext";
    private static final String LOGBACK_CONTEXT_CLASS = "ch.qos.logback.core.Context";
    private static final String LOGBACK_APPENDER_IFACE = "ch.qos.logback.core.Appender";
    private static final String LOGBACK_LEVEL_CLASS = "ch.qos.logback.classic.Level";

    private static final String LOKI_APPENDER_CLASS = "com.github.loki4j.logback.Loki4jAppender";
    private static final String LOKI_HTTP_SENDER_IFACE = "com.github.loki4j.logback.HttpSender";
    private static final String LOKI_JAVA_HTTP_SENDER_CLASS = "com.github.loki4j.logback.JavaHttpSender";
    private static final String LOKI_BASIC_AUTH_CLASS =
            "com.github.loki4j.logback.AbstractHttpSender$BasicAuth";
    private static final String LOKI_ENCODER_IFACE = "com.github.loki4j.logback.Loki4jEncoder";
    private static final String LOKI_JSON_ENCODER_CLASS = "com.github.loki4j.logback.JsonEncoder";
    private static final String LOKI_LABEL_CFG_CLASS =
            "com.github.loki4j.logback.AbstractLoki4jEncoder$LabelCfg";
    private static final String LOKI_MESSAGE_CFG_CLASS =
            "com.github.loki4j.logback.AbstractLoki4jEncoder$MessageCfg";

    /** How often the watchdog re-checks that our appender is still attached. */
    private static final long WATCHDOG_INTERVAL_SECONDS = 30L;

    private volatile Config currentConfig;
    private volatile Object attachedAppender;
    private volatile ScheduledExecutorService watchdog;
    private volatile ScheduledFuture<?> watchdogTask;
    private final Object lock = new Object();

    @Activate
    @Modified
    protected void activate(Config config) {
        synchronized (lock) {
            detachInternal();
            this.currentConfig = config;
            attachInternal(config);
            startWatchdog();
        }
    }

    @Deactivate
    protected void deactivate() {
        synchronized (lock) {
            stopWatchdog();
            detachInternal();
            this.currentConfig = null;
        }
    }

    // ---------------------------------------------------------------- attach

    private void attachInternal(Config config) {
        final String url = trimToEmpty(config.url());
        if (url.isEmpty()) {
            LOG.warn("Loki push URL is empty; appender will not be attached.");
            return;
        }

        final ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!isLogback(factory)) {
            LOG.warn("SLF4J ILoggerFactory is '{}', not Logback; Loki appender cannot be attached.",
                    factory.getClass().getName());
            return;
        }

        final ClassLoader cl = factory.getClass().getClassLoader();
        final Class<?> appenderClass;
        try {
            appenderClass = Class.forName(LOKI_APPENDER_CLASS, true, cl);
        } catch (ClassNotFoundException e) {
            LOG.error("Loki4jAppender class '{}' is not reachable via Logback's class loader. "
                            + "The 'aem-loki-integrator.loki4j' fragment is not attached to '"
                            + "org.apache.sling.commons.log'. Logs will NOT be pushed to Loki.",
                    LOKI_APPENDER_CLASS);
            return;
        }

        final Object appender;
        try {
            appender = buildAppender(appenderClass, factory, config, url);
        } catch (Exception e) {
            LOG.error("Failed to build Loki4jAppender via reflection", e);
            return;
        }

        final List<String> attached = new ArrayList<>();
        try {
            attached.addAll(attachToLoggers(factory, appender, config));
        } catch (Exception e) {
            LOG.error("Failed to attach Loki4jAppender to Logback loggers", e);
            stopAppender(appender);
            return;
        }

        this.attachedAppender = appender;
        LOG.info("Loki appender attached via reflection (url={}, labels={}, loggers={}, verbose={})",
                url,
                config.labels() == null ? 0 : config.labels().length,
                attached,
                config.verbose());
    }

    private Object buildAppender(Class<?> appenderClass, Object loggerContext, Config config, String url)
            throws Exception {
        final ClassLoader cl = loggerContext.getClass().getClassLoader();
        final Class<?> contextClass = loadClass(cl, LOGBACK_CONTEXT_CLASS);
        final Object appender = appenderClass.getDeclaredConstructor().newInstance();

        invoke(appender, "setContext", loggerContext, contextClass);
        invoke(appender, "setName", APPENDER_NAME, String.class);

        invokeSetter(appender, "setBatchMaxItems", config.batchMaxItems(), int.class);
        invokeSetter(appender, "setBatchTimeoutMs", (long) config.batchTimeoutMs(), long.class);
        if (config.sendQueueMaxBytes() > 0L) {
            invokeSetter(appender, "setSendQueueMaxBytes", config.sendQueueMaxBytes(), long.class);
        }
        invokeSetter(appender, "setVerbose", config.verbose(), boolean.class);

        configureHttp(appender, cl, url, config);
        configureFormat(appender, cl, loggerContext, config);

        invoke(appender, "start");
        return appender;
    }

    /**
     * Builds the {@code com.github.loki4j.logback.JavaHttpSender} and wires
     * it through {@code Loki4jAppender.setHttp(HttpSender)}. JavaHttpSender
     * is the JDK-based implementation (uses {@code java.net.http.HttpClient})
     * so it has no third-party runtime dependencies and always resolves on
     * AEM's JDK 11+.
     */
    private void configureHttp(Object appender, ClassLoader cl, String url, Config config) throws Exception {
        final Class<?> senderClass = loadClass(cl, LOKI_JAVA_HTTP_SENDER_CLASS);
        final Class<?> senderIface = loadClass(cl, LOKI_HTTP_SENDER_IFACE);

        final Object sender = senderClass.getDeclaredConstructor().newInstance();
        invokeSetter(sender, "setUrl", url, String.class);

        final String username = trimToEmpty(config.username());
        final String password = trimToEmpty(config.password());
        if (!username.isEmpty() || !password.isEmpty()) {
            final Class<?> basicAuth = loadClass(cl, LOKI_BASIC_AUTH_CLASS);
            final Object auth = basicAuth.getDeclaredConstructor().newInstance();
            invokeSetter(auth, "setUsername", username, String.class);
            invokeSetter(auth, "setPassword", password, String.class);
            invokeSetter(sender, "setAuth", auth, basicAuth);
        }

        // Loki4jAppender#setHttp(HttpSender) expects the interface type.
        invokeSetter(appender, "setHttp", sender, senderIface);
    }

    /**
     * Builds a {@code com.github.loki4j.logback.JsonEncoder} with the
     * configured label &amp; message patterns and attaches it through
     * {@code Loki4jAppender.setFormat(Loki4jEncoder)}.
     */
    private void configureFormat(Object appender, ClassLoader cl, Object loggerContext, Config config)
            throws Exception {
        final Class<?> encoderClass = loadClass(cl, LOKI_JSON_ENCODER_CLASS);
        final Class<?> encoderIface = loadClass(cl, LOKI_ENCODER_IFACE);
        final Class<?> labelsClass = loadClass(cl, LOKI_LABEL_CFG_CLASS);
        final Class<?> messageClass = loadClass(cl, LOKI_MESSAGE_CFG_CLASS);
        final Class<?> contextClass = loadClass(cl, LOGBACK_CONTEXT_CLASS);

        final String labelPattern = buildLabelPattern(config.labels());
        final String messagePattern = firstNonBlank(config.messagePattern(), DEFAULT_MESSAGE_PATTERN);

        final Object encoder = encoderClass.getDeclaredConstructor().newInstance();
        invoke(encoder, "setContext", loggerContext, contextClass);

        final Object label = labelsClass.getDeclaredConstructor().newInstance();
        invokeSetter(label, "setPattern", labelPattern, String.class);
        invokeSetter(encoder, "setLabel", label, labelsClass);

        final Object msg = messageClass.getDeclaredConstructor().newInstance();
        invokeSetter(msg, "setPattern", messagePattern, String.class);
        invokeSetter(encoder, "setMessage", msg, messageClass);

        invoke(encoder, "start");

        // Loki4jAppender#setFormat(Loki4jEncoder) expects the interface.
        invokeSetter(appender, "setFormat", encoder, encoderIface);
    }

    private List<String> attachToLoggers(Object loggerContext, Object appender, Config config)
            throws Exception {
        final List<String> names = new ArrayList<>();
        final List<LoggerEntry> loggers = parseLoggers(config.loggers());
        if (loggers.isEmpty()) {
            LOG.warn("No loggers configured; Loki appender is attached but not wired to any logger.");
            return names;
        }
        final ClassLoader cl = loggerContext.getClass().getClassLoader();
        final Class<?> levelClass = loadClass(cl, LOGBACK_LEVEL_CLASS);
        final Method toLevel = levelClass.getMethod("toLevel", String.class);

        final Method getLogger = loggerContext.getClass().getMethod("getLogger", String.class);
        for (LoggerEntry entry : loggers) {
            final Object logger = getLogger.invoke(loggerContext, entry.name);
            final Object level = toLevel.invoke(null, entry.level);
            try {
                invoke(logger, "setLevel", level, levelClass);
            } catch (NoSuchMethodException ex) {
                LOG.debug("Logback logger '{}' has no setLevel(Level) method; skipping level override", entry.name);
            }
            try {
                invokeSetter(logger, "setAdditive", entry.additivity, boolean.class);
            } catch (NoSuchMethodException ex) {
                LOG.debug("Logback logger '{}' has no setAdditive(boolean) method; skipping", entry.name);
            }
            try {
                final Class<?> appenderIface = loadClass(cl, LOGBACK_APPENDER_IFACE);
                final Method addAppender = logger.getClass().getMethod("addAppender", appenderIface);
                addAppender.invoke(logger, appender);
            } catch (NoSuchMethodException nsme) {
                LOG.warn("Logback logger '{}' does not expose addAppender(Appender); cannot attach Loki appender", entry.name);
                continue;
            }
            names.add(entry.name + ':' + entry.level);
        }
        return names;
    }

    // --------------------------------------------------------------- detach

    private void detachInternal() {
        final Object appender = this.attachedAppender;
        this.attachedAppender = null;
        if (appender == null) {
            return;
        }
        final ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (isLogback(factory)) {
            try {
                final ClassLoader cl = factory.getClass().getClassLoader();
                final Class<?> appenderIface = loadClass(cl, LOGBACK_APPENDER_IFACE);
                final Method getLoggerList = factory.getClass().getMethod("getLoggerList");
                @SuppressWarnings("unchecked")
                final Iterable<Object> loggers = (Iterable<Object>) getLoggerList.invoke(factory);
                for (Object logger : loggers) {
                    try {
                        final Method detach = logger.getClass().getMethod("detachAppender", appenderIface);
                        detach.invoke(logger, appender);
                    } catch (NoSuchMethodException ignored) {
                        // older Logback versions use a different method name; ignore.
                    }
                }
            } catch (Exception e) {
                LOG.debug("Could not cleanly detach Loki appender from Logback loggers", e);
            }
        }
        stopAppender(appender);
    }

    private static void stopAppender(Object appender) {
        if (appender == null) {
            return;
        }
        try {
            appender.getClass().getMethod("stop").invoke(appender);
        } catch (Exception ignored) {
            // best-effort
        }
    }

    // ------------------------------------------------------------ watchdog

    private void startWatchdog() {
        stopWatchdog();
        final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aem-loki-watchdog");
            t.setDaemon(true);
            return t;
        });
        this.watchdog = exec;
        this.watchdogTask = exec.scheduleAtFixedRate(this::reattachIfNeeded,
                WATCHDOG_INTERVAL_SECONDS,
                WATCHDOG_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    private void stopWatchdog() {
        final ScheduledFuture<?> task = this.watchdogTask;
        this.watchdogTask = null;
        if (task != null) {
            task.cancel(false);
        }
        final ScheduledExecutorService exec = this.watchdog;
        this.watchdog = null;
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    private void reattachIfNeeded() {
        synchronized (lock) {
            final Config config = this.currentConfig;
            if (config == null) {
                return;
            }
            final Object appender = this.attachedAppender;
            if (appender != null && isAppenderStillLive(appender)) {
                return;
            }
            LOG.info("Loki appender missing or stopped - re-attaching.");
            detachInternal();
            attachInternal(config);
        }
    }

    private boolean isAppenderStillLive(Object appender) {
        try {
            final Object started = appender.getClass().getMethod("isStarted").invoke(appender);
            if (!(started instanceof Boolean) || !((Boolean) started)) {
                return false;
            }
        } catch (Exception ignored) {
            return false;
        }
        try {
            final ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            if (!isLogback(factory)) {
                return false;
            }
            final ClassLoader cl = factory.getClass().getClassLoader();
            final Class<?> appenderIface = loadClass(cl, LOGBACK_APPENDER_IFACE);
            final Method getLoggerList = factory.getClass().getMethod("getLoggerList");
            @SuppressWarnings("unchecked")
            final Iterable<Object> loggers = (Iterable<Object>) getLoggerList.invoke(factory);
            for (Object logger : loggers) {
                try {
                    final Method getAppender = logger.getClass().getMethod("getAppender", String.class);
                    final Object existing = getAppender.invoke(logger, APPENDER_NAME);
                    if (existing == appender) {
                        return true;
                    }
                } catch (NoSuchMethodException ignored) {
                    // ignore and continue
                }
                // also check via iteration if possible
                try {
                    final Method iter = logger.getClass().getMethod("iteratorForAppenders");
                    final java.util.Iterator<?> it = (java.util.Iterator<?>) iter.invoke(logger);
                    while (it.hasNext()) {
                        if (it.next() == appender) {
                            return true;
                        }
                    }
                } catch (NoSuchMethodException ignored) {
                    // ignore
                }
            }
        } catch (Exception e) {
            LOG.debug("Watchdog couldn't verify Loki appender attachment state", e);
            return false;
        }
        return false;
    }

    // ----------------------------------------------------------- reflection

    private static boolean isLogback(ILoggerFactory factory) {
        if (factory == null) {
            return false;
        }
        // Walk the class hierarchy so we also accept anonymous Logback
        // subclasses or vendor-specific wrappers.
        for (Class<?> c = factory.getClass(); c != null; c = c.getSuperclass()) {
            if (LOGBACK_LOGGER_CONTEXT_CLASS.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> loadClass(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, true, cl);
    }

    private static void invoke(Object target, String method) throws Exception {
        target.getClass().getMethod(method).invoke(target);
    }

    private static void invoke(Object target, String method, Object arg, Class<?> paramType) throws Exception {
        target.getClass().getMethod(method, paramType).invoke(target, arg);
    }

    private static void invokeSetter(Object target, String method, Object value, Class<?> paramType) throws Exception {
        Method m;
        try {
            m = target.getClass().getMethod(method, paramType);
        } catch (NoSuchMethodException nsme) {
            // Try to find a compatible single-arg setter by walking the hierarchy.
            m = findCompatibleSetter(target.getClass(), method, value);
            if (m == null) {
                throw nsme;
            }
        }
        m.invoke(target, value);
    }

    private static Method findCompatibleSetter(Class<?> cls, String name, Object value) {
        for (Method m : cls.getMethods()) {
            if (!name.equals(m.getName()) || m.getParameterCount() != 1) {
                continue;
            }
            final Class<?> p = m.getParameterTypes()[0];
            if (value == null || p.isInstance(value)) {
                return m;
            }
        }
        return null;
    }

    // --------------------------------------------------------- config utils

    private static String buildLabelPattern(String[] labels) {
        if (labels == null || labels.length == 0) {
            return "app=aem";
        }
        final Map<String, String> merged = new LinkedHashMap<>();
        for (String raw : labels) {
            if (raw == null) {
                continue;
            }
            final String entry = raw.trim();
            if (entry.isEmpty()) {
                continue;
            }
            final int idx = entry.indexOf('=');
            if (idx <= 0 || idx == entry.length() - 1) {
                LOG.warn("Ignoring malformed label '{}' (expected key=value)", entry);
                continue;
            }
            merged.put(entry.substring(0, idx).trim(), entry.substring(idx + 1).trim());
        }
        if (merged.isEmpty()) {
            return "app=aem";
        }
        final StringBuilder joined = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (!first) {
                joined.append(',');
            }
            joined.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return joined.toString();
    }

    private static List<LoggerEntry> parseLoggers(String[] loggers) {
        if (loggers == null || loggers.length == 0) {
            return Collections.emptyList();
        }
        final List<LoggerEntry> out = new ArrayList<>(loggers.length);
        for (String raw : loggers) {
            if (raw == null) {
                continue;
            }
            final String entry = raw.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            final int idx = findSeparator(entry);
            if (idx <= 0 || idx == entry.length() - 1) {
                LOG.warn("Ignoring malformed logger entry '{}' (expected <package>:<LEVEL> or <package>=<LEVEL>)", entry);
                continue;
            }
            final String name = entry.substring(0, idx).trim();
            final String level = entry.substring(idx + 1).trim().toUpperCase();
            if (!isValidLevel(level)) {
                LOG.warn("Ignoring logger entry '{}' - unknown level '{}'", entry, level);
                continue;
            }
            out.add(new LoggerEntry(name, level, false));
        }
        return out;
    }

    private static int findSeparator(String entry) {
        final int colon = entry.indexOf(':');
        final int equals = entry.indexOf('=');
        if (colon == -1) {
            return equals;
        }
        if (equals == -1) {
            return colon;
        }
        return Math.min(colon, equals);
    }

    private static boolean isValidLevel(String level) {
        switch (level) {
            case "TRACE":
            case "DEBUG":
            case "INFO":
            case "WARN":
            case "ERROR":
            case "OFF":
            case "ALL":
                return true;
            default:
                return false;
        }
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

    private static final class LoggerEntry {
        final String name;
        final String level;
        final boolean additivity;

        LoggerEntry(String name, String level, boolean additivity) {
            this.name = Objects.requireNonNull(name);
            this.level = Objects.requireNonNull(level);
            this.additivity = additivity;
        }
    }

    @ObjectClassDefinition(
            name = "AEM | Loki Integrator - Logback bootstrap",
            description = "Attaches a Loki (loki4j) appender to AEM's Logback LoggerContext. "
                    + "All appender and logger settings are read from this configuration.")
    public @interface Config {

        @AttributeDefinition(
                name = "Loki push URL",
                description = "Full URL of the Loki push endpoint, e.g. http://localhost:3100/loki/api/v1/push.")
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
                        + "Values may contain Logback conversion words and Logback context properties (e.g. %level, ${HOSTNAME}).",
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
                description = "Maximum size of the in-memory send buffer in bytes. 0 uses the loki4j default.")
        long sendQueueMaxBytes() default 0L;

        @AttributeDefinition(
                name = "Verbose",
                description = "Enable verbose diagnostic output on the loki4j appender.")
        boolean verbose() default false;

        @AttributeDefinition(
                name = "Message pattern",
                description = "Logback encoder pattern for the message body sent to Loki.")
        String messagePattern() default DEFAULT_MESSAGE_PATTERN;

        @AttributeDefinition(
                name = "Loggers",
                description = "Logback loggers routed to the LOKI appender. "
                        + "One entry per logger in the form '<package>:<LEVEL>' or '<package>=<LEVEL>', "
                        + "for example 'com.adobexp.aem:DEBUG'.",
                cardinality = Integer.MAX_VALUE)
        String[] loggers() default {
                "com.adobexp.aem:DEBUG"
        };
    }
}
