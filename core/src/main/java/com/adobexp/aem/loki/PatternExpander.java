/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.util.Objects;

/**
 * Expands label templates and environment references into their
 * runtime values. Two flavours are offered:
 * <ul>
 *   <li>{@link #expand(String, QueuedEvent)} - used for label templates;
 *       supports both {@code %xxx} conversion words and {@code ${...}}
 *       environment references.</li>
 *   <li>{@link #expandEnvRefs(String)} - used for file-system paths in
 *       {@link LogDirectoryProbe}; handles only {@code ${...}}.</li>
 * </ul>
 *
 * <p>The {@code messagePattern} used by the Loki push body takes a
 * different, token-list path via {@link MessageToken#parse(String)}
 * because that pattern is compiled once at activation and reused per
 * event, while labels are cheap enough to expand lazily.
 */
final class PatternExpander {

    private PatternExpander() {
        // static utility
    }

    /**
     * Expands a single label template for one event. Recognises both
     * {@code %xxx[{arg}]} conversion words and {@code ${...}} environment
     * references.
     */
    static String expand(String template, QueuedEvent ev) {
        if (template == null) {
            return "";
        }
        final int len = template.length();
        final StringBuilder sb = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            final char c = template.charAt(i);
            if (c == '$' && i + 1 < len && template.charAt(i + 1) == '{') {
                final int close = template.indexOf('}', i + 2);
                if (close > 0) {
                    sb.append(expandEnvRef(template.substring(i + 2, close)));
                    i = close + 1;
                    continue;
                }
            }
            if (c == '%') {
                int j = i + 1;
                while (j < len && (template.charAt(j) == '-' || Character.isDigit(template.charAt(j)))) {
                    j++;
                }
                final int nameStart = j;
                while (j < len && Character.isLetter(template.charAt(j))) {
                    j++;
                }
                if (nameStart < j) {
                    final String name = template.substring(nameStart, j).toLowerCase();
                    String argText = null;
                    if (j < len && template.charAt(j) == '{') {
                        final int close = template.indexOf('}', j + 1);
                        if (close > 0) {
                            argText = template.substring(j + 1, close);
                            j = close + 1;
                        }
                    }
                    sb.append(resolveConversion(name, argText, ev));
                    i = j;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String resolveConversion(String name, String argText, QueuedEvent ev) {
        switch (name) {
            case "level":
            case "p":
                return ev.entry.level.name();
            case "logger":
            case "c": {
                final Integer max = MessageToken.parseIntOrNull(argText);
                return max != null ? MessageToken.truncateLogger(ev.entry.loggerName, max) : ev.entry.loggerName;
            }
            case "thread":
            case "t":
                return ev.entry.threadName;
            case "msg":
            case "m":
            case "message":
                return Objects.toString(ev.entry.message, "");
            case "ex":
            case "exception":
            case "throwable":
                return ev.entry.throwableText == null ? "" : ev.entry.throwableText;
            case "x":
            case "mdc": {
                if (ev.entry.mdc == null) {
                    return "";
                }
                if (argText == null || argText.isEmpty()) {
                    return ev.entry.mdc.toString();
                }
                final String v = ev.entry.mdc.get(argText);
                return v == null ? "" : v;
            }
            default:
                return argText == null ? "%" + name : "%" + name + "{" + argText + "}";
        }
    }

    /**
     * Expands only {@code ${...}} environment references. Used to
     * substitute environment variables into file system paths before
     * the tailer tries to open them.
     */
    static String expandEnvRefs(String template) {
        if (template == null || template.indexOf("${") < 0) {
            return template;
        }
        final int len = template.length();
        final StringBuilder sb = new StringBuilder(len + 16);
        int i = 0;
        while (i < len) {
            final char c = template.charAt(i);
            if (c == '$' && i + 1 < len && template.charAt(i + 1) == '{') {
                final int close = template.indexOf('}', i + 2);
                if (close > 0) {
                    sb.append(expandEnvRef(template.substring(i + 2, close)));
                    i = close + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String expandEnvRef(String expr) {
        if (expr.startsWith("env:")) {
            final String rest = expr.substring(4);
            final int sc = rest.indexOf(';');
            final String name;
            String fallback = "";
            if (sc >= 0) {
                name = rest.substring(0, sc).trim();
                final String tail = rest.substring(sc + 1).trim();
                if (tail.startsWith("default=")) {
                    fallback = tail.substring("default=".length());
                }
            } else {
                name = rest.trim();
            }
            final String v = System.getenv(name);
            return v != null ? v : fallback;
        }
        final String v = System.getenv(expr);
        if (v != null) {
            return v;
        }
        return Objects.toString(System.getProperty(expr), "");
    }
}
