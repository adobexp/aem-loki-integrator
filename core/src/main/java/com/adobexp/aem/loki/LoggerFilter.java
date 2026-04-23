/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parsed per-logger filter. A filter matches an event when either the
 * logger name is exactly {@link #name} or starts with {@code name + "."},
 * and the event level is at least as severe as the configured
 * {@link #threshold} (lower {@link Level#ordinal()} is more severe:
 * AUDIT &lt; ERROR &lt; WARN &lt; INFO &lt; DEBUG &lt; TRACE).
 * The special names {@code ROOT} and an empty string match everything.
 */
final class LoggerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(LoggerFilter.class);

    final String name;
    final Level threshold;
    final boolean matchAll;

    LoggerFilter(String name, Level threshold) {
        this.name = name;
        this.threshold = threshold;
        this.matchAll = name.isEmpty() || "ROOT".equalsIgnoreCase(name);
    }

    boolean matches(String loggerName, Level level) {
        if (level.ordinal() > threshold.ordinal()) {
            return false;
        }
        if (matchAll) {
            return true;
        }
        if (loggerName.equals(name)) {
            return true;
        }
        return loggerName.length() > name.length()
                && loggerName.startsWith(name)
                && loggerName.charAt(name.length()) == '.';
    }

    @Override
    public String toString() {
        return (matchAll ? "ROOT" : name) + ':' + threshold;
    }

    static List<LoggerFilter> parse(String[] raw) {
        if (raw == null || raw.length == 0) {
            return Collections.emptyList();
        }
        final List<LoggerFilter> out = new ArrayList<>(raw.length);
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            final String e = entry.trim();
            if (e.isEmpty() || e.startsWith("#")) {
                continue;
            }
            final int sep = findSeparator(e);
            if (sep <= 0 || sep == e.length() - 1) {
                LOG.warn("Ignoring malformed logger entry '{}' "
                        + "(expected <name>:<LEVEL> or <name>=<LEVEL>)", e);
                continue;
            }
            final String name = e.substring(0, sep).trim();
            final String lvl = e.substring(sep + 1).trim().toUpperCase();
            final Level threshold = parseLevel(lvl);
            if (threshold == null) {
                LOG.warn("Ignoring logger entry '{}' - unknown level '{}'", e, lvl);
                continue;
            }
            out.add(new LoggerFilter(name, threshold));
        }
        return out;
    }

    private static int findSeparator(String s) {
        final int colon = s.indexOf(':');
        final int equals = s.indexOf('=');
        if (colon == -1) {
            return equals;
        }
        if (equals == -1) {
            return colon;
        }
        return Math.min(colon, equals);
    }

    private static Level parseLevel(String level) {
        switch (level) {
            case "AUDIT":
                return Level.AUDIT;
            case "ERROR":
            case "SEVERE":
            case "FATAL":
                return Level.ERROR;
            case "WARN":
            case "WARNING":
                return Level.WARN;
            case "INFO":
                return Level.INFO;
            case "DEBUG":
            case "FINE":
                return Level.DEBUG;
            case "TRACE":
            case "FINER":
            case "FINEST":
            case "ALL":
                return Level.TRACE;
            case "OFF":
                return Level.AUDIT; // keep only AUDIT-level events
            default:
                return null;
        }
    }
}
