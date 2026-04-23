/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Populates per-request {@link org.slf4j.MDC} keys at the start of every
 * Sling request so that downstream log events (both Logback's
 * {@code aemerror.log} and our Loki forwarder's {@code %X{key}} tokens)
 * can be correlated back to the user, session and request that triggered
 * them.
 *
 * <p>The filter is registered as a plain {@link Filter} service with
 * {@code sling.filter.scope=REQUEST|FORWARD|INCLUDE} which makes the Sling
 * Engine wire it into the request pipeline ahead of servlet dispatch. A
 * high {@code service.ranking} guarantees the MDC keys are set before any
 * downstream filter runs.
 *
 * <p>Keys set (when enabled by configuration):
 * <ul>
 *   <li>{@code userID}        - from {@code request.getRemoteUser()} (this
 *       covers the Sling / JCR resolver user, since Sling copies the JCR
 *       user id into the request principal). Optionally SHA-256 truncated.</li>
 *   <li>{@code sessionID}     - HTTP session id, or a resolver-identity
 *       hash, or both concatenated, depending on configuration.</li>
 *   <li>{@code requestID}     - 16-hex-char random id per request, unique
 *       and cheap (no external correlation headers needed).</li>
 *   <li>{@code resourcePath}  - {@code request.getRequestURI()} truncated
 *       to a configurable max length, for filtering by authored path.</li>
 * </ul>
 *
 * <p>All keys are cleared in the {@code finally} block to avoid leakage
 * into pooled request threads. Previously-set values (from nested
 * {@code INCLUDE} / {@code FORWARD} dispatches, or from outer frameworks)
 * are saved and restored.
 */
@Component(
        service = Filter.class,
        configurationPid = "com.adobexp.aem.loki.LokiMdcFilter",
        configurationPolicy = ConfigurationPolicy.OPTIONAL,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.scope=FORWARD",
                "sling.filter.scope=INCLUDE",
                "sling.filter.pattern=.*",
                "service.ranking:Integer=20000"
        })
@Designate(ocd = LokiMdcFilter.Config.class)
public final class LokiMdcFilter implements Filter {

    private static final Logger SELF_LOG = LoggerFactory.getLogger(LokiMdcFilter.class);

    private static final String SESSION_SOURCE_HTTP = "http";
    private static final String SESSION_SOURCE_RESOLVER = "resolver";
    private static final String SESSION_SOURCE_BOTH = "both";

    private final AtomicReference<Config> configRef = new AtomicReference<>();

    @ObjectClassDefinition(
            name = "AEM Loki Integrator - MDC Request Filter",
            description = "Populates SLF4J MDC keys (userID, sessionID, requestID, "
                    + "resourcePath) per Sling request so Loki (and aemerror.log) "
                    + "can correlate log lines to the user that triggered them. "
                    + "Pattern tokens in LogbackLokiBootstrap.messagePattern read "
                    + "these keys via %X{key}.")
    public @interface Config {

        @AttributeDefinition(
                name = "Enabled",
                description = "Master switch. When false, the filter is a no-op "
                        + "and no MDC keys are set.")
        boolean enabled() default true;

        @AttributeDefinition(
                name = "User ID MDC key",
                description = "Name of the MDC key that receives the request user "
                        + "id. Set to blank to skip user id capture.")
        String userIdKey() default "userID";

        @AttributeDefinition(
                name = "Hash user ID",
                description = "When true, the user id is replaced with the first "
                        + "12 hex characters of its SHA-256 before going into MDC. "
                        + "Recommended for production to avoid persisting user "
                        + "handles in Loki while still allowing per-user grouping.")
        boolean hashUserId() default false;

        @AttributeDefinition(
                name = "Session ID MDC key",
                description = "Name of the MDC key that receives the session "
                        + "identifier. Set to blank to skip session capture.")
        String sessionIdKey() default "sessionID";

        @AttributeDefinition(
                name = "Session ID source",
                description = "Which identifier is placed in the session MDC key. "
                        + "'http' uses javax.servlet HttpSession.getId(); 'resolver' "
                        + "uses a hex identity hash of the Sling ResourceResolver / "
                        + "JCR session bound to the request; 'both' concatenates "
                        + "them as <http>@<resolver>.",
                options = {
                        @org.osgi.service.metatype.annotations.Option(label = "HTTP session id", value = SESSION_SOURCE_HTTP),
                        @org.osgi.service.metatype.annotations.Option(label = "Resolver identity hash", value = SESSION_SOURCE_RESOLVER),
                        @org.osgi.service.metatype.annotations.Option(label = "Both (http@resolver)", value = SESSION_SOURCE_BOTH)
                })
        String sessionIdSource() default SESSION_SOURCE_HTTP;

        @AttributeDefinition(
                name = "Request ID MDC key",
                description = "Name of the MDC key that receives a per-request "
                        + "correlation id. Set to blank to skip request id "
                        + "generation.")
        String requestIdKey() default "requestID";

        @AttributeDefinition(
                name = "Resource path MDC key",
                description = "Name of the MDC key that receives the requested "
                        + "resource path. Set to blank to skip path capture.")
        String resourcePathKey() default "resourcePath";

        @AttributeDefinition(
                name = "Resource path max length",
                description = "Upper bound on the characters placed into the "
                        + "resource path MDC key; longer values are truncated to "
                        + "avoid bloating every log line for long query strings.",
                type = org.osgi.service.metatype.annotations.AttributeType.INTEGER)
        int resourcePathMaxLength() default 256;

        @AttributeDefinition(
                name = "Skip anonymous users",
                description = "When true, no user id is placed into MDC for "
                        + "anonymous requests. sessionID and requestID are still "
                        + "populated.")
        boolean skipAnonymous() default false;
    }

    @Activate
    @Modified
    protected void activate(Config cfg) {
        configRef.set(cfg);
        SELF_LOG.info("LokiMdcFilter active (enabled={}, userIdKey={}, sessionIdKey={}, "
                        + "requestIdKey={}, resourcePathKey={}, sessionSource={}, "
                        + "hashUserId={}, skipAnonymous={})",
                cfg.enabled(), cfg.userIdKey(), cfg.sessionIdKey(),
                cfg.requestIdKey(), cfg.resourcePathKey(), cfg.sessionIdSource(),
                cfg.hashUserId(), cfg.skipAnonymous());
    }

    @Deactivate
    protected void deactivate() {
        configRef.set(null);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // no-op; configuration is OSGi-driven.
    }

    @Override
    public void destroy() {
        // no-op.
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        final Config cfg = configRef.get();
        if (cfg == null || !cfg.enabled() || !(request instanceof HttpServletRequest)) {
            chain.doFilter(request, response);
            return;
        }

        final HttpServletRequest httpReq = (HttpServletRequest) request;
        final List<String> putKeys = new ArrayList<>(4);
        final Deque<Map.Entry<String, String>> restoreStack = new ArrayDeque<>(4);

        try {
            // --- userID ---
            final String userIdKey = trimToNull(cfg.userIdKey());
            if (userIdKey != null) {
                final String rawUser = safeRemoteUser(httpReq);
                if (rawUser != null && !(cfg.skipAnonymous() && isAnonymous(rawUser))) {
                    final String value = cfg.hashUserId() ? hashId(rawUser) : rawUser;
                    saveAndPut(restoreStack, putKeys, userIdKey, value);
                }
            }

            // --- sessionID ---
            final String sessionIdKey = trimToNull(cfg.sessionIdKey());
            if (sessionIdKey != null) {
                final String sid = deriveSessionId(httpReq, cfg.sessionIdSource());
                if (sid != null) {
                    saveAndPut(restoreStack, putKeys, sessionIdKey, sid);
                }
            }

            // --- requestID ---
            final String requestIdKey = trimToNull(cfg.requestIdKey());
            if (requestIdKey != null) {
                saveAndPut(restoreStack, putKeys, requestIdKey, shortCorrelationId());
            }

            // --- resourcePath ---
            final String resourcePathKey = trimToNull(cfg.resourcePathKey());
            if (resourcePathKey != null) {
                final String path = safeRequestPath(httpReq, cfg.resourcePathMaxLength());
                if (path != null) {
                    saveAndPut(restoreStack, putKeys, resourcePathKey, path);
                }
            }

            chain.doFilter(request, response);
        } finally {
            // Restore (or remove) every key we touched, in reverse order.
            while (!restoreStack.isEmpty()) {
                final Map.Entry<String, String> prev = restoreStack.pop();
                if (prev.getValue() == null) {
                    MDC.remove(prev.getKey());
                } else {
                    MDC.put(prev.getKey(), prev.getValue());
                }
            }
            // Defensive: if the stack got corrupted (shouldn't), make sure
            // every key we put is gone.
            if (!putKeys.isEmpty() && !restoreStack.isEmpty()) {
                for (String k : putKeys) {
                    MDC.remove(k);
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void saveAndPut(Deque<Map.Entry<String, String>> stack,
                                   List<String> putKeys,
                                   String key, String value) {
        final String prev = MDC.get(key);
        stack.push(new AbstractMapEntry(key, prev));
        MDC.put(key, value);
        putKeys.add(key);
    }

    private static String safeRemoteUser(HttpServletRequest req) {
        try {
            final String u = req.getRemoteUser();
            if (u != null && !u.isEmpty()) {
                return u;
            }
            if (req.getUserPrincipal() != null && req.getUserPrincipal().getName() != null) {
                return req.getUserPrincipal().getName();
            }
        } catch (Throwable ignored) {
            // never let authentication lookup crash the filter chain.
        }
        return null;
    }

    private static boolean isAnonymous(String user) {
        return user == null || user.isEmpty() || "anonymous".equalsIgnoreCase(user);
    }

    private static String deriveSessionId(HttpServletRequest req, String source) {
        final String src = source == null ? SESSION_SOURCE_HTTP : source.toLowerCase();
        final String httpId = httpSessionId(req);
        final String resolverId = resolverIdentityHash(req);
        switch (src) {
            case SESSION_SOURCE_RESOLVER:
                return resolverId != null ? resolverId : httpId;
            case SESSION_SOURCE_BOTH:
                if (httpId == null && resolverId == null) {
                    return null;
                }
                return (httpId == null ? "-" : httpId) + "@" + (resolverId == null ? "-" : resolverId);
            case SESSION_SOURCE_HTTP:
            default:
                return httpId != null ? httpId : resolverId;
        }
    }

    private static String httpSessionId(HttpServletRequest req) {
        try {
            final HttpSession s = req.getSession(false);
            if (s != null) {
                return s.getId();
            }
        } catch (Throwable ignored) {
            // treat as absent.
        }
        return null;
    }

    /**
     * Tries to extract a stable-per-request resolver identity hash without
     * importing the Sling API directly (keeps the bundle's Import-Package
     * list minimal and tolerant of Sling absence in non-Sling deployments).
     * Uses reflection against {@code adaptTo(...)} on the request to reach
     * the bound resolver, then takes an identity hash.
     */
    private static String resolverIdentityHash(HttpServletRequest req) {
        try {
            final java.lang.reflect.Method adaptTo = req.getClass().getMethod("getResourceResolver");
            final Object resolver = adaptTo.invoke(req);
            if (resolver != null) {
                return Integer.toHexString(System.identityHashCode(resolver));
            }
        } catch (NoSuchMethodException ignored) {
            // Not a SlingHttpServletRequest. Fine.
        } catch (Throwable ignored) {
            // Reflection path best-effort only.
        }
        return null;
    }

    private static String safeRequestPath(HttpServletRequest req, int max) {
        try {
            String path = req.getRequestURI();
            if (path == null) {
                return null;
            }
            if (max > 0 && path.length() > max) {
                path = path.substring(0, max);
            }
            return path;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String shortCorrelationId() {
        final UUID u = UUID.randomUUID();
        final long bits = u.getMostSignificantBits() ^ u.getLeastSignificantBits();
        return padLeftHex(bits, 16);
    }

    private static String padLeftHex(long v, int width) {
        final String hex = Long.toHexString(v);
        if (hex.length() >= width) {
            return hex.substring(hex.length() - width);
        }
        final StringBuilder sb = new StringBuilder(width);
        for (int i = hex.length(); i < width; i++) {
            sb.append('0');
        }
        sb.append(hex);
        return sb.toString();
    }

    private static String hashId(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] out = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6 && i < out.length; i++) {
                sb.append(String.format("%02x", out[i] & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every standard JRE; extremely unlikely.
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        final String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Minimal {@link java.util.Map.Entry} implementation for the restore
     * stack. Avoids importing {@code AbstractMap.SimpleImmutableEntry} just
     * to allow a {@code null} value (which it does permit, but this keeps
     * the intent explicit and the class compact).
     */
    private static final class AbstractMapEntry implements Map.Entry<String, String> {
        private final String key;
        private final String value;

        AbstractMapEntry(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override public String getKey() { return key; }
        @Override public String getValue() { return value; }
        @Override public String setValue(String v) { throw new UnsupportedOperationException(); }
    }

    // Package-private accessor used by tests.
    Map<String, String> snapshotConfig() {
        final Config c = configRef.get();
        if (c == null) {
            return java.util.Collections.emptyMap();
        }
        final Map<String, String> m = new HashMap<>();
        m.put("enabled", Boolean.toString(c.enabled()));
        m.put("userIdKey", c.userIdKey());
        m.put("sessionIdKey", c.sessionIdKey());
        m.put("requestIdKey", c.requestIdKey());
        m.put("resourcePathKey", c.resourcePathKey());
        m.put("sessionIdSource", c.sessionIdSource());
        m.put("hashUserId", Boolean.toString(c.hashUserId()));
        m.put("skipAnonymous", Boolean.toString(c.skipAnonymous()));
        m.put("resourcePathMaxLength", Integer.toString(c.resourcePathMaxLength()));
        return m;
    }
}
