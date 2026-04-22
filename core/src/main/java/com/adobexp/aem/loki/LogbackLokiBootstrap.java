package com.adobexp.aem.loki;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sling.commons.log.logback.ConfigProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
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
import org.xml.sax.InputSource;

/**
 * Registers a Logback configuration fragment with the AEM (Sling Commons Log)
 * Logback runtime through the {@link ConfigProvider} extension point. The
 * fragment wires a Loki (loki4j) appender and attaches it to a configurable
 * set of loggers.
 *
 * <p>Every Loki setting - push URL, credentials, labels, batching, logger
 * names and levels - is taken from the OSGi configuration bound to the
 * {@link Config} OCD. No Logback XML template is shipped with the bundle;
 * the fragment is built from the current configuration on every
 * activate/modify and served through {@link #getConfigSource()}.</p>
 *
 * <p>By implementing {@link ConfigProvider} the bundle never references any
 * {@code ch.qos.logback.*} internal API, so the AEMaaCS Code Quality rule
 * {@code java:S1874} does not flag it.</p>
 *
 * <p>On AEMaaCS the Sling Commons Log {@code ConfigSourceTracker} does not
 * reliably schedule a Logback reset when a new {@link ConfigProvider} service
 * is registered at runtime, so this component also forces a reconfigure by
 * touching the {@code org.apache.sling.commons.log.LogManager} configuration
 * with a transient delta property. The touch is retried a few times in a
 * background thread to survive AEMaaCS startup races.</p>
 */
@Component(
        service = ConfigProvider.class,
        immediate = true,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        property = {"process.label=AEM-LOKI | LogbackLokiBootstrap"})
@Designate(ocd = LogbackLokiBootstrap.Config.class)
public class LogbackLokiBootstrap implements ConfigProvider {

    private static final String APPENDER_NAME = "LOKI";
    private static final String EMPTY_FRAGMENT = "<included/>";
    private static final String DEFAULT_MESSAGE_PATTERN = "*%level* [%thread] %logger{100} | %msg %ex";

    private static final String SLING_LOG_MANAGER_PID = "org.apache.sling.commons.log.LogManager";
    private static final String RELOAD_TRIGGER_KEY = "_aemLokiReloadTrigger";

    /** Delays (ms) at which the Logback reload is attempted after activation. */
    private static final long[] RELOAD_DELAYS_MS = {0L, 2_000L, 10_000L, 30_000L, 90_000L};

    private static final Logger LOG = LoggerFactory.getLogger(LogbackLokiBootstrap.class);

    private volatile String renderedConfig = EMPTY_FRAGMENT;
    private volatile BundleContext bundleContext;
    private volatile ScheduledExecutorService reloadExecutor;

    @Activate
    @Modified
    protected void activate(BundleContext ctx, Config config) {
        this.bundleContext = ctx;
        this.renderedConfig = renderFragment(config);
        LOG.info("LogbackLokiBootstrap ConfigProvider ready (url={}, labels={}, loggers={})",
                config.url(),
                config.labels().length,
                config.loggers().length);
        scheduleLogbackReload();
    }

    @Deactivate
    protected void deactivate() {
        this.renderedConfig = EMPTY_FRAGMENT;
        try {
            scheduleLogbackReload();
        } finally {
            shutdownReloadExecutor();
        }
    }

    @Override
    public InputSource getConfigSource() {
        return new InputSource(new StringReader(renderedConfig));
    }

    // ------------------------------------------------------------------ reload

    /**
     * Schedules a chain of Logback reload attempts on a daemon thread. Each
     * attempt touches the {@code org.apache.sling.commons.log.LogManager}
     * configuration with a unique timestamp value so that Felix
     * ConfigurationAdmin is forced to deliver the update to the Sling
     * {@code LogConfigManager} ManagedService, which in turn calls
     * {@code LogbackManager.configChanged()} and makes Logback re-query every
     * registered {@link ConfigProvider}.
     *
     * <p>The reload is retried a handful of times with increasing delays so it
     * still fires if ConfigurationAdmin, the Sling LogConfigManager or our own
     * component were not yet fully wired at the first attempt.</p>
     */
    private void scheduleLogbackReload() {
        shutdownReloadExecutor();
        final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aem-loki-logback-reload");
            t.setDaemon(true);
            return t;
        });
        this.reloadExecutor = exec;
        final AtomicInteger attempt = new AtomicInteger(0);
        for (long delayMs : RELOAD_DELAYS_MS) {
            exec.schedule(() -> {
                int n = attempt.incrementAndGet();
                boolean ok = triggerLogbackReload(n);
                if (ok && n >= RELOAD_DELAYS_MS.length) {
                    shutdownReloadExecutor();
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void shutdownReloadExecutor() {
        final ScheduledExecutorService exec = this.reloadExecutor;
        this.reloadExecutor = null;
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    private boolean triggerLogbackReload(int attempt) {
        final BundleContext ctx = this.bundleContext;
        if (ctx == null) {
            LOG.debug("Logback reload attempt #{}: bundle context not available", attempt);
            return false;
        }
        ServiceReference<ConfigurationAdmin> ref = null;
        try {
            ref = ctx.getServiceReference(ConfigurationAdmin.class);
            if (ref == null) {
                LOG.debug("Logback reload attempt #{}: ConfigurationAdmin service not available", attempt);
                return false;
            }
            final ConfigurationAdmin ca = ctx.getService(ref);
            if (ca == null) {
                LOG.debug("Logback reload attempt #{}: ConfigurationAdmin lookup returned null", attempt);
                return false;
            }
            final Configuration cfg = ca.getConfiguration(SLING_LOG_MANAGER_PID, null);
            final Dictionary<String, Object> current = cfg.getProperties();
            final Dictionary<String, Object> next = copyProperties(current);
            next.put(RELOAD_TRIGGER_KEY, String.valueOf(System.currentTimeMillis()));
            cfg.update(next);
            LOG.info("Triggered Logback reconfigure via {} (attempt #{})",
                    SLING_LOG_MANAGER_PID, attempt);
            return true;
        } catch (Exception ex) {
            LOG.warn("Logback reload attempt #{} failed: {}", attempt, ex.toString());
            return false;
        } finally {
            if (ref != null) {
                try {
                    ctx.ungetService(ref);
                } catch (IllegalStateException ignored) {
                    // bundle context might be gone during shutdown
                }
            }
        }
    }

    private static Dictionary<String, Object> copyProperties(Dictionary<String, Object> src) {
        final Dictionary<String, Object> out = new Hashtable<>();
        if (src != null) {
            final java.util.Enumeration<String> keys = src.keys();
            while (keys.hasMoreElements()) {
                final String k = keys.nextElement();
                final Object v = src.get(k);
                if (v != null) {
                    out.put(k, v);
                }
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- render

    private static String renderFragment(Config config) {
        final String url = trimToEmpty(config.url());
        if (url.isEmpty()) {
            LOG.warn("Loki push URL is empty - no appender will be registered");
            return EMPTY_FRAGMENT;
        }

        final String labelPattern = buildLabelPattern(config.labels());
        final String messagePattern = firstNonBlank(config.messagePattern(), DEFAULT_MESSAGE_PATTERN);
        final List<LoggerEntry> loggers = parseLoggers(config.loggers());

        final StringBuilder xml = new StringBuilder(1024);
        xml.append("<included>\n");

        xml.append("  <appender name=\"").append(APPENDER_NAME)
                .append("\" class=\"com.github.loki4j.logback.Loki4jAppender\">\n");
        xml.append("    <batchMaxItems>").append(config.batchMaxItems()).append("</batchMaxItems>\n");
        xml.append("    <batchTimeoutMs>").append(config.batchTimeoutMs()).append("</batchTimeoutMs>\n");
        if (config.sendQueueMaxBytes() > 0L) {
            xml.append("    <sendQueueMaxBytes>").append(config.sendQueueMaxBytes()).append("</sendQueueMaxBytes>\n");
        }
        if (config.verbose()) {
            xml.append("    <verbose>true</verbose>\n");
        }

        xml.append("    <http>\n");
        xml.append("      <url>").append(escapeXml(url)).append("</url>\n");
        final String username = trimToEmpty(config.username());
        final String password = trimToEmpty(config.password());
        if (!username.isEmpty() || !password.isEmpty()) {
            xml.append("      <auth>\n");
            xml.append("        <username>").append(escapeXml(username)).append("</username>\n");
            xml.append("        <password>").append(escapeXml(password)).append("</password>\n");
            xml.append("      </auth>\n");
        }
        xml.append("    </http>\n");

        xml.append("    <format>\n");
        xml.append("      <label>\n");
        xml.append("        <pattern>").append(escapeXml(labelPattern)).append("</pattern>\n");
        xml.append("      </label>\n");
        xml.append("      <message>\n");
        xml.append("        <pattern>").append(escapeXml(messagePattern)).append("</pattern>\n");
        xml.append("      </message>\n");
        xml.append("    </format>\n");
        xml.append("  </appender>\n");

        for (LoggerEntry logger : loggers) {
            xml.append("  <logger name=\"").append(escapeXml(logger.name))
                    .append("\" level=\"").append(escapeXml(logger.level))
                    .append("\" additivity=\"").append(logger.additivity).append("\">\n");
            xml.append("    <appender-ref ref=\"").append(APPENDER_NAME).append("\"/>\n");
            xml.append("  </logger>\n");
        }

        xml.append("</included>\n");
        return xml.toString();
    }

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

    private static String escapeXml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&apos;");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
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
            description = "Registers a Loki (loki4j) appender with the AEM Logback runtime. "
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
