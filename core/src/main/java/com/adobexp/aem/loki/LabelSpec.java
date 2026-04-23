/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parsed {@code "key=valueTemplate"} label specification. The value
 * template may mix literal text with a small set of conversion words
 * that are expanded per event by {@link PatternExpander#expand(String, QueuedEvent)}:
 * <ul>
 *     <li>{@code %level}                 - event level (ERROR / WARN / ...)</li>
 *     <li>{@code %logger} / {@code %logger{N}} - logger name, optionally truncated</li>
 *     <li>{@code %thread}                - thread name parsed from the log line</li>
 *     <li>{@code %X{key}} / {@code %mdc{key}} - SLF4J MDC value at emit time</li>
 *     <li>{@code ${HOSTNAME}}            - value of the {@code HOSTNAME} env var</li>
 *     <li>{@code ${env:NAME;default=X}}  - env var {@code NAME} or fallback {@code X}</li>
 * </ul>
 */
final class LabelSpec {

    private static final Logger LOG = LoggerFactory.getLogger(LabelSpec.class);

    final String key;
    final String template;

    LabelSpec(String key, String template) {
        this.key = key;
        this.template = template;
    }

    String resolve(QueuedEvent ev) {
        return PatternExpander.expand(this.template, ev);
    }

    static List<LabelSpec> parse(String[] raw) {
        if (raw == null || raw.length == 0) {
            return Collections.emptyList();
        }
        final Map<String, LabelSpec> merged = new LinkedHashMap<>();
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            final String e = entry.trim();
            if (e.isEmpty() || e.startsWith("#")) {
                continue;
            }
            final int eq = e.indexOf('=');
            if (eq <= 0 || eq == e.length() - 1) {
                LOG.warn("Ignoring malformed label '{}' (expected key=value)", e);
                continue;
            }
            final String key = e.substring(0, eq).trim();
            final String template = e.substring(eq + 1).trim();
            if (!key.isEmpty() && !template.isEmpty()) {
                merged.put(key, new LabelSpec(key, template));
            }
        }
        return new ArrayList<>(merged.values());
    }
}
