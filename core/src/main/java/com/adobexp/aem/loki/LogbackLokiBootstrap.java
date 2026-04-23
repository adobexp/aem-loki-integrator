/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
import org.slf4j.MDC;

/**
 * Forwards AEM / Sling / Felix log events to a Grafana Loki push endpoint.
 *
 * <p>Starting with version 3.x the forwarder has two independent event
 * sources, both feeding the same batching and HTTP push pipeline:
 * <ol>
 *   <li><b>Facade</b> ({@code facadeEnabled}, default {@code true}) -
 *       consumer bundles call {@link com.adobexp.log.Logger} instead of
 *       {@link org.slf4j.Logger}. Every call is delegated to SLF4J (so
 *       Logback keeps writing {@code aemerror.log}) and tee'd to Loki
 *       with structured args intact. Zero file I/O, no regex parse,
 *       captures only code that uses the facade.</li>
 *   <li><b>File tailer</b> ({@code tailerEnabled}, default {@code false}) -
 *       follows the on-disk AEM log files (by default {@code error.log}
 *       under {@code $SLING_HOME/logs} or the AEMaaCS equivalent),
 *       parses each entry with the standard AEM layout, and enqueues it
 *       on the same push queue. Useful as a fallback to capture
 *       third-party bundle logs ({@code com.adobe.*}, {@code com.day.*},
 *       {@code org.apache.sling.*} etc.) that do not call through the
 *       facade.</li>
 * </ol>
 *
 * <p>The bundle relies only on {@code java.nio.file}, {@code java.net.http}
 * and the OSGi DS annotations; it has zero compile-time or runtime
 * dependency on Logback, SLF4J internals, {@code org.slf4j.spi}, or any
 * third-party logging library, which keeps AEMaaCS Code Quality rule
 * {@code java:S1874} happy.
 *
 * <p>The class name (and therefore the OSGi PID) is kept unchanged from
 * the 1.x and 2.x releases so existing
 * {@code com.adobexp.aem.loki.LogbackLokiBootstrap.cfg.json}
 * configurations continue to apply; the new {@code facadeEnabled} and
 * {@code tailerEnabled} flags are optional and default to sensible
 * values for a fresh install.
 *
 * <p>Internally this class is deliberately kept as just the OSGi
 * component shell: activation, deactivation, event ingestion, batch
 * flush and HTTP push. All the building blocks it relies on live in
 * their own single-responsibility classes in this package:
 * <ul>
 *   <li>{@link Level}, {@link ParsedEntry}, {@link QueuedEvent} -
 *       event data holders</li>
 *   <li>{@link LoggerFilter}, {@link LabelSpec},
 *       {@link MessageToken}, {@link PatternExpander} -
 *       configuration parsers and pattern expansion</li>
 *   <li>{@link LogFileTailer}, {@link LogDirectoryProbe} - on-disk
 *       tailer and path discovery</li>
 *   <li>{@link LokiStream}, {@link LokiJson} - push body grouping and
 *       JSON/gzip serialisation</li>
 *   <li>{@link DaemonThreadFactory} - scheduler thread naming</li>
 * </ul>
 */
@Component(
        service = {},
        immediate = true,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        property = {"process.label=AEM-LOKI | Loki Log Forwarder"})
@Designate(ocd = LogbackLokiBootstrap.Config.class)
public class LogbackLokiBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(LogbackLokiBootstrap.class);

    private static final String DEFAULT_MESSAGE_PATTERN = "*%level* [%thread] %logger | %msg";

    /** Prefix used to detect and drop our own log events (recursion guard). */
    private static final String SELF_LOGGER_PREFIX = "com.adobexp.aem.loki";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MIN_QUEUE_CAPACITY = 1024;

    private volatile Config config;
    private volatile BlockingQueue<QueuedEvent> queue;
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> flushTask;
    private volatile ScheduledFuture<?> tailTask;
    private volatile HttpClient httpClient;
    private volatile URI pushUri;
    private volatile String authHeader;
    private volatile List<LoggerFilter> loggerFilters = Collections.emptyList();
    private volatile List<LabelSpec> labelSpecs = Collections.emptyList();
    private volatile List<MessageToken> messageTokens = Collections.emptyList();
    private volatile List<LogFileTailer> tailers = Collections.emptyList();
    private volatile com.adobexp.log.FacadeSink facadeSink;

    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong pushedBatches = new AtomicLong();
    private final AtomicLong pushedEvents = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private final AtomicLong seenCount = new AtomicLong();
    private final AtomicLong seenCountLastReport = new AtomicLong();
    private final AtomicLong matchedCount = new AtomicLong();
    private final AtomicLong matchedCountLastReport = new AtomicLong();

    // ======================================================================
    //  Lifecycle
    // ======================================================================

    @Activate
    @Modified
    protected void activate(Config cfg) {
        shutdown();
        final String url = trimToEmpty(cfg.url());
        if (url.isEmpty()) {
            LOG.warn("Loki push URL is empty - forwarder will stay dormant.");
            return;
        }

        final URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            LOG.error("Invalid Loki push URL '{}' - forwarder will stay dormant: {}",
                    url, e.toString());
            return;
        }

        this.config = cfg;
        this.pushUri = uri;
        this.loggerFilters = LoggerFilter.parse(cfg.loggers());
        if (this.loggerFilters.isEmpty()) {
            LOG.warn("No loggers configured on {} - forwarder will not forward anything.",
                    Config.class.getName());
        }
        this.labelSpecs = LabelSpec.parse(cfg.labels());
        this.messageTokens = MessageToken.parse(
                firstNonBlank(cfg.messagePattern(), DEFAULT_MESSAGE_PATTERN));
        this.authHeader = buildAuthHeader(cfg.username(), cfg.password());

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        final int capacity = Math.max(cfg.batchMaxItems() * 4, MIN_QUEUE_CAPACITY);
        this.queue = new ArrayBlockingQueue<>(capacity);

        final int threadPoolSize = cfg.tailerEnabled() ? 2 : 1;
        this.scheduler = Executors.newScheduledThreadPool(
                threadPoolSize, new DaemonThreadFactory("aem-loki-forwarder"));

        final long flushMs = Math.max(cfg.batchTimeoutMs(), 250L);
        this.flushTask = this.scheduler.scheduleAtFixedRate(
                this::flushSafe, flushMs, flushMs, TimeUnit.MILLISECONDS);

        // --------- Tailer (opt-in) ---------------------------------------
        final List<Path> resolved;
        final long tailMs;
        if (cfg.tailerEnabled()) {
            resolved = LogDirectoryProbe.resolveLogFiles(cfg.logFiles());
            final List<LogFileTailer> ts = new ArrayList<>(resolved.size());
            final List<Path> existing = new ArrayList<>();
            final List<Path> missing = new ArrayList<>();
            for (Path p : resolved) {
                ts.add(new LogFileTailer(p));
                if (Files.isRegularFile(p)) {
                    existing.add(p);
                } else {
                    missing.add(p);
                }
            }
            this.tailers = ts;
            if (!missing.isEmpty()) {
                LOG.warn("Loki forwarder: {} configured log file(s) do not exist yet - "
                                + "the tailer will pick them up once they appear: {}",
                        missing.size(), missing);
            }
            if (existing.isEmpty()) {
                LOG.warn("Loki forwarder: none of the configured log files exist right now - "
                        + "check 'logFiles' against the actual AEM log directory.");
                LogDirectoryProbe.reportLogDirInventory();
            } else if (cfg.verbose()) {
                LogDirectoryProbe.reportLogDirInventory();
            }
            tailMs = Math.max(cfg.tailIntervalMs(), 100L);
            this.tailTask = this.scheduler.scheduleAtFixedRate(
                    this::tailSafe, tailMs, tailMs, TimeUnit.MILLISECONDS);
        } else {
            resolved = Collections.emptyList();
            this.tailers = Collections.emptyList();
            this.tailTask = null;
            tailMs = 0L;
        }

        // --------- Facade sink (opt-out) ---------------------------------
        if (cfg.facadeEnabled()) {
            this.facadeSink = new BootstrapFacadeSink();
            com.adobexp.log.LoggerFactory.setSink(this.facadeSink);
        } else {
            this.facadeSink = null;
        }

        LOG.info("Loki forwarder started (url={}, facadeEnabled={}, tailerEnabled={}, "
                        + "logFiles={}, batchMaxItems={}, batchTimeoutMs={}, tailIntervalMs={}, "
                        + "queueCapacity={}, loggers={}, labels={}, verbose={})",
                url, cfg.facadeEnabled(), cfg.tailerEnabled(), resolved,
                cfg.batchMaxItems(), cfg.batchTimeoutMs(), tailMs, capacity,
                this.loggerFilters, this.labelSpecs.size(), cfg.verbose());
    }

    @Deactivate
    protected void deactivate() {
        shutdown();
    }

    private void shutdown() {
        // Unregister the facade sink FIRST so no further events can enter
        // the pipeline while we are draining.
        final com.adobexp.log.FacadeSink prevSink = this.facadeSink;
        this.facadeSink = null;
        if (prevSink != null) {
            // Only clear the global sink if it still points at us; a
            // subsequent activation may have already installed its own.
            if (com.adobexp.log.LoggerFactory.getSink() == prevSink) {
                com.adobexp.log.LoggerFactory.setSink(null);
            }
        }
        final ScheduledFuture<?> flush = this.flushTask;
        this.flushTask = null;
        if (flush != null) {
            flush.cancel(false);
        }
        final ScheduledFuture<?> tail = this.tailTask;
        this.tailTask = null;
        if (tail != null) {
            tail.cancel(false);
        }
        final ScheduledExecutorService exec = this.scheduler;
        this.scheduler = null;
        if (exec != null) {
            try {
                if (tail != null) {
                    exec.submit(this::tailSafe).get(2, TimeUnit.SECONDS);
                }
                exec.submit(this::flushSafe).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best-effort drain
            }
            exec.shutdownNow();
        }
        for (LogFileTailer t : this.tailers) {
            t.close();
        }
        this.tailers = Collections.emptyList();
        this.queue = null;
        this.httpClient = null;
        this.pushUri = null;
        this.authHeader = null;
        this.config = null;
    }

    // ======================================================================
    //  Event ingestion
    // ======================================================================

    private void tailSafe() {
        try {
            final Config cfg = this.config;
            if (cfg == null) {
                return;
            }
            final boolean verbose = cfg.verbose();
            for (LogFileTailer t : this.tailers) {
                try {
                    t.tick(this::ingestLine, verbose);
                } catch (Throwable th) {
                    if (verbose) {
                        LOG.warn("Log tailer error on {}: {}", t.path, th.toString());
                    }
                }
            }
        } catch (Throwable t) {
            // never let the scheduler task die
            final Config cfg = this.config;
            if (cfg != null && cfg.verbose()) {
                LOG.warn("Log tailer failed", t);
            }
        }
    }

    /**
     * {@link com.adobexp.log.FacadeSink} implementation that routes facade
     * events into the same batching/push pipeline used by the tailer.
     * Runs on the caller's thread (the bundle that invoked
     * {@code LOG.info(...)}), so it must be non-blocking. The heavy
     * lifting - HTTP push, gzip, JSON assembly - happens asynchronously
     * on the forwarder's scheduler thread during {@link #flush()}.
     */
    private final class BootstrapFacadeSink implements com.adobexp.log.FacadeSink {
        @Override
        public void accept(String loggerName, com.adobexp.log.Level level,
                           String message, Throwable throwable) {
            if (loggerName == null || loggerName.startsWith(SELF_LOGGER_PREFIX)) {
                return;
            }
            final String threadName = Thread.currentThread().getName();
            final Level aemLevel = toAemLevel(level);
            final String finalMessage = message == null ? "" : message;
            final String throwableText = throwable == null ? null : stringifyThrowable(throwable);
            Map<String, String> mdcSnapshot = null;
            try {
                mdcSnapshot = MDC.getCopyOfContextMap();
            } catch (Throwable ignored) {
                // SLF4J MDC should always be available, but stay defensive
                // so a missing SLF4J binding (during tests) does not crash
                // the caller's log call.
            }
            final ParsedEntry entry = new ParsedEntry(
                    System.currentTimeMillis(),
                    aemLevel,
                    threadName,
                    loggerName,
                    finalMessage,
                    throwableText,
                    mdcSnapshot);
            ingestLine(entry);
        }
    }

    private static Level toAemLevel(com.adobexp.log.Level level) {
        if (level == null) {
            return Level.INFO;
        }
        switch (level) {
            case TRACE: return Level.TRACE;
            case DEBUG: return Level.DEBUG;
            case INFO:  return Level.INFO;
            case WARN:  return Level.WARN;
            case ERROR: return Level.ERROR;
            default:    return Level.INFO;
        }
    }

    private static String stringifyThrowable(Throwable t) {
        if (t == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(256);
        sb.append(t.getClass().getName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("\n\tat ").append(e);
        }
        Throwable cause = t.getCause();
        while (cause != null && cause != t) {
            sb.append("\nCaused by: ").append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
            for (StackTraceElement e : cause.getStackTrace()) {
                sb.append("\n\tat ").append(e);
            }
            cause = cause.getCause();
        }
        return sb.toString();
    }

    /**
     * Accepts a single parsed log entry and, if it matches the configured
     * filters, enqueues it for the next Loki push. Invoked from the tailer
     * thread and (via {@link BootstrapFacadeSink}) from application threads
     * that called the facade.
     */
    private void ingestLine(ParsedEntry entry) {
        final Config cfg = this.config;
        final BlockingQueue<QueuedEvent> q = this.queue;
        if (cfg == null || q == null || entry == null) {
            return;
        }
        if (entry.loggerName.startsWith(SELF_LOGGER_PREFIX)) {
            return; // avoid feedback loop from our own diagnostics
        }
        seenCount.incrementAndGet();
        if (!matchesAnyFilter(entry.loggerName, entry.level)) {
            return;
        }
        matchedCount.incrementAndGet();

        final QueuedEvent queued = new QueuedEvent(entry);
        if (!q.offer(queued)) {
            // Ring-buffer behaviour: drop the oldest event to accept the newest.
            q.poll();
            if (!q.offer(queued)) {
                droppedCount.incrementAndGet();
                return;
            }
            droppedCount.incrementAndGet();
        }
        if (q.size() >= cfg.batchMaxItems()) {
            final ScheduledExecutorService exec = this.scheduler;
            if (exec != null) {
                try {
                    exec.execute(this::flushSafe);
                } catch (Exception ignored) {
                    // scheduler shutting down; scheduled flush will catch up
                }
            }
        }
    }

    private boolean matchesAnyFilter(String loggerName, Level level) {
        final List<LoggerFilter> filters = this.loggerFilters;
        if (filters.isEmpty()) {
            return false;
        }
        for (LoggerFilter f : filters) {
            if (f.matches(loggerName, level)) {
                return true;
            }
        }
        return false;
    }

    // ======================================================================
    //  Flush + HTTP push
    // ======================================================================

    private void flushSafe() {
        try {
            flush();
        } catch (Throwable t) {
            final Config cfg = this.config;
            if (cfg != null && cfg.verbose()) {
                LOG.warn("Loki forwarder flush failed", t);
            }
        }
    }

    private void flush() {
        final BlockingQueue<QueuedEvent> q = this.queue;
        final Config cfg = this.config;
        final HttpClient client = this.httpClient;
        final URI uri = this.pushUri;
        if (q == null || cfg == null || client == null || uri == null) {
            return;
        }
        if (cfg.verbose()) {
            final long seen = seenCount.get();
            final long matched = matchedCount.get();
            final long dSeen = seen - seenCountLastReport.getAndSet(seen);
            final long dMatched = matched - matchedCountLastReport.getAndSet(matched);
            LOG.info("Loki forwarder heartbeat: seen+={}, matched+={}, queued={}, dropped={}, pushedEvents={}, failedBatches={}",
                    dSeen, dMatched, q.size(), droppedCount.get(),
                    pushedEvents.get(), failedBatches.get());
        }
        if (q.isEmpty()) {
            return;
        }

        final int max = Math.max(cfg.batchMaxItems(), 1);
        final List<QueuedEvent> drained = new ArrayList<>(Math.min(max, q.size()));
        q.drainTo(drained, max);
        if (drained.isEmpty()) {
            return;
        }

        // Group events by the resolved label set so the Loki push body uses
        // as few streams as possible.
        final Map<String, LokiStream> streams = new LinkedHashMap<>();
        for (QueuedEvent ev : drained) {
            final Map<String, String> labels = resolveLabels(ev);
            final String key = streamKey(labels);
            final LokiStream s = streams.computeIfAbsent(key, k -> new LokiStream(labels));
            s.values.add(new String[]{
                    Long.toString(ev.entry.epochMillis * 1_000_000L),
                    formatMessage(ev)
            });
        }

        final byte[] body = LokiJson.buildJsonBody(streams.values());
        final byte[] compressed = LokiJson.gzipIfSmaller(body);
        final boolean gz = compressed != null;
        final byte[] payload = gz ? compressed : body;

        final HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json");
        if (gz) {
            req.header("Content-Encoding", "gzip");
        }
        if (this.authHeader != null) {
            req.header("Authorization", this.authHeader);
        }
        req.POST(HttpRequest.BodyPublishers.ofByteArray(payload));

        try {
            final HttpResponse<String> resp = client.send(
                    req.build(), HttpResponse.BodyHandlers.ofString());
            final int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                pushedBatches.incrementAndGet();
                pushedEvents.addAndGet(drained.size());
                if (cfg.verbose()) {
                    LOG.info("Loki push OK ({} events, {} streams, {} bytes{}, status={})",
                            drained.size(), streams.size(), payload.length,
                            gz ? " gzip" : "", status);
                }
            } else {
                failedBatches.incrementAndGet();
                LOG.warn("Loki push failed: status={}, events={}, body={}",
                        status, drained.size(), truncate(resp.body(), 512));
            }
        } catch (Exception e) {
            failedBatches.incrementAndGet();
            LOG.warn("Loki push error: events={}, error={}", drained.size(), e.toString());
        }
    }

    private Map<String, String> resolveLabels(QueuedEvent ev) {
        final List<LabelSpec> specs = this.labelSpecs;
        if (specs.isEmpty()) {
            final Map<String, String> fallback = new LinkedHashMap<>(1);
            fallback.put("app", "aem");
            return fallback;
        }
        final Map<String, String> out = new LinkedHashMap<>(specs.size());
        for (LabelSpec spec : specs) {
            final String value = spec.resolve(ev);
            if (value != null && !value.isEmpty()) {
                out.put(spec.key, value);
            }
        }
        if (out.isEmpty()) {
            out.put("app", "aem");
        }
        return out;
    }

    private static String streamKey(Map<String, String> labels) {
        final StringBuilder sb = new StringBuilder(64);
        for (Map.Entry<String, String> e : labels.entrySet()) {
            sb.append(e.getKey()).append('\u0001').append(e.getValue()).append('\u0002');
        }
        return sb.toString();
    }

    private String formatMessage(QueuedEvent ev) {
        final StringBuilder sb = new StringBuilder(128);
        for (MessageToken t : this.messageTokens) {
            t.append(sb, ev);
        }
        return sb.toString();
    }

    // ======================================================================
    //  Small helpers
    // ======================================================================

    private static String buildAuthHeader(String username, String password) {
        final String u = trimToEmpty(username);
        final String p = trimToEmpty(password);
        if (u.isEmpty() && p.isEmpty()) {
            return null;
        }
        final String creds = u + ':' + p;
        return "Basic " + Base64.getEncoder()
                .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
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

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ======================================================================
    //  OSGi metatype
    // ======================================================================

    @ObjectClassDefinition(
            name = "AEM | Loki Integrator - Logback bootstrap",
            description = "Forwards AEM / Sling / Felix log events to a Grafana Loki "
                    + "push endpoint by tailing the on-disk error.log produced by "
                    + "Sling Commons Log. All settings are read from this configuration.")
    public @interface Config {

        @AttributeDefinition(
                name = "Loki push URL",
                description = "Full URL of the Loki push endpoint, "
                        + "e.g. http://localhost:3100/loki/api/v1/push.")
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
                        + "Values may contain conversion words (%level, %logger, %thread) "
                        + "and environment references (${HOSTNAME}, ${env:NAME;default=FOO}).",
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
                description = "Reserved for future use. Ignored in 2.x (queue capacity is "
                        + "derived from batchMaxItems).")
        long sendQueueMaxBytes() default 0L;

        @AttributeDefinition(
                name = "Verbose",
                description = "If true, every successful Loki push logs a confirmation line plus "
                        + "periodic heartbeat statistics.")
        boolean verbose() default false;

        @AttributeDefinition(
                name = "Message pattern",
                description = "Pattern used to render the log line sent to Loki. "
                        + "Supports %level, %logger{N}, %thread, %msg, %date, %n.")
        String messagePattern() default DEFAULT_MESSAGE_PATTERN;

        @AttributeDefinition(
                name = "Loggers",
                description = "Loggers whose events are forwarded to Loki. "
                        + "One entry per logger in the form '<name>:<LEVEL>' or '<name>=<LEVEL>', "
                        + "for example 'com.adobexp.aem:DEBUG'. Use 'ROOT' to forward everything.",
                cardinality = Integer.MAX_VALUE)
        String[] loggers() default {
                "com.adobexp.aem:DEBUG"
        };

        @AttributeDefinition(
                name = "Facade: enabled",
                description = "When true (default), classes that use com.adobexp.log.Logger / "
                        + "com.adobexp.log.LoggerFactory have their events tee'd to Loki in "
                        + "addition to the normal SLF4J/Logback path. This is the preferred "
                        + "source for first-party code logs because it ships structured "
                        + "arguments with zero file I/O.")
        boolean facadeEnabled() default true;

        @AttributeDefinition(
                name = "Tailer: enabled",
                description = "When true, the forwarder also tails the on-disk AEM error.log "
                        + "and forwards matching lines to Loki. Use this to capture logs from "
                        + "third-party bundles (com.adobe.*, com.day.*, org.apache.sling.*, "
                        + "org.apache.jackrabbit.*, io.wcm.* etc.) that do not call through "
                        + "the facade. Disabled by default; first-party code should rely on "
                        + "the facade, which is more efficient and works on every AEMaaCS "
                        + "pod regardless of where logs end up on disk.")
        boolean tailerEnabled() default false;

        @AttributeDefinition(
                name = "Log files",
                description = "On-disk log files to tail. Only used when 'tailerEnabled' is "
                        + "true. Entries can be absolute paths or paths relative to "
                        + "$SLING_HOME/logs. Supports ${HOSTNAME} and ${env:NAME;default=X} "
                        + "substitutions. On AEMaaCS the default 'error.log' resolves to the "
                        + "standard Sling error log of the running author/publish instance.",
                cardinality = Integer.MAX_VALUE)
        String[] logFiles() default {"error.log"};

        @AttributeDefinition(
                name = "Tail polling interval (ms)",
                description = "How often the log files are polled for new data. 500ms is a good "
                        + "default; lower values shorten Loki ingestion latency at the cost of more I/O.")
        int tailIntervalMs() default 500;
    }
}
