/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A pre-parsed token in the configured {@code messagePattern}. Parsing
 * the pattern once at configuration time (rather than re-parsing on
 * every event) avoids allocation on the hot path, which matters in AEM
 * deployments where tens of thousands of log events per minute are
 * routine.
 *
 * <p>Supported conversion words (subset of Logback's {@code PatternLayout}):
 * <ul>
 *   <li>{@code %level} / {@code %p}</li>
 *   <li>{@code %logger} / {@code %c} / {@code %logger{N}}</li>
 *   <li>{@code %thread} / {@code %t}</li>
 *   <li>{@code %msg} / {@code %m} / {@code %message}</li>
 *   <li>{@code %date} / {@code %d} - ISO-8601 event timestamp</li>
 *   <li>{@code %n} - newline</li>
 *   <li>{@code %ex} / {@code %exception} / {@code %throwable} - stack trace</li>
 *   <li>{@code %X{key}} / {@code %mdc{key}} - SLF4J MDC value</li>
 * </ul>
 * Unknown tokens are emitted verbatim so configuration typos are visible
 * in the Loki stream rather than silently dropped.
 */
interface MessageToken {

    void append(StringBuilder sb, QueuedEvent ev);

    static List<MessageToken> parse(String pattern) {
        final List<MessageToken> out = new ArrayList<>();
        final int len = pattern.length();
        final StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < len) {
            final char c = pattern.charAt(i);
            if (c != '%') {
                literal.append(c);
                i++;
                continue;
            }
            if (literal.length() > 0) {
                final String lit = literal.toString();
                out.add((sb, ev) -> sb.append(lit));
                literal.setLength(0);
            }
            int j = i + 1;
            while (j < len && (pattern.charAt(j) == '-' || Character.isDigit(pattern.charAt(j)))) {
                j++;
            }
            final int nameStart = j;
            while (j < len && Character.isLetter(pattern.charAt(j))) {
                j++;
            }
            if (nameStart == j) {
                literal.append('%');
                i++;
                continue;
            }
            final String token = pattern.substring(nameStart, j).toLowerCase();
            String argText = null;
            if (j < len && pattern.charAt(j) == '{') {
                final int close = pattern.indexOf('}', j + 1);
                if (close > 0) {
                    argText = pattern.substring(j + 1, close);
                    j = close + 1;
                }
            }
            out.add(buildToken(token, argText));
            i = j;
        }
        if (literal.length() > 0) {
            final String lit = literal.toString();
            out.add((sb, ev) -> sb.append(lit));
        }
        return out;
    }

    static MessageToken buildToken(String name, String argText) {
        switch (name) {
            case "level":
            case "p":
                return (sb, ev) -> sb.append(ev.entry.level.name());
            case "logger":
            case "c":
                final Integer max = parseIntOrNull(argText);
                if (max != null) {
                    final int cap = max;
                    return (sb, ev) -> sb.append(truncateLogger(ev.entry.loggerName, cap));
                }
                return (sb, ev) -> sb.append(ev.entry.loggerName);
            case "thread":
            case "t":
                return (sb, ev) -> sb.append(ev.entry.threadName);
            case "msg":
            case "m":
            case "message":
                return (sb, ev) -> sb.append(ev.entry.message == null ? "" : ev.entry.message);
            case "date":
            case "d":
                return (sb, ev) -> sb.append(Instant.ofEpochMilli(ev.entry.epochMillis));
            case "n":
                return (sb, ev) -> sb.append('\n');
            case "ex":
            case "exception":
            case "throwable":
                return (sb, ev) -> {
                    if (ev.entry.throwableText != null && !ev.entry.throwableText.isEmpty()) {
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                            sb.append('\n');
                        }
                        sb.append(ev.entry.throwableText);
                    }
                };
            case "x":
            case "mdc":
                final String key = argText;
                if (key == null || key.isEmpty()) {
                    return (sb, ev) -> {
                        if (ev.entry.mdc != null && !ev.entry.mdc.isEmpty()) {
                            sb.append(ev.entry.mdc);
                        }
                    };
                }
                return (sb, ev) -> {
                    if (ev.entry.mdc != null) {
                        final String v = ev.entry.mdc.get(key);
                        if (v != null) {
                            sb.append(v);
                        }
                    }
                };
            default:
                final String literal = argText == null
                        ? '%' + name
                        : '%' + name + '{' + argText + '}';
                return (sb, ev) -> sb.append(literal);
        }
    }

    /** Parses a non-empty decimal integer, or returns {@code null} if the input is not one. */
    static Integer parseIntOrNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Truncates a dotted logger name to at most {@code max} characters by
     * abbreviating leading path segments to their first letter, while
     * always keeping the final segment (the simple class name) intact.
     * Example: {@code "com.foo.bar.Baz"} with {@code max=12} becomes
     * {@code "c.foo.bar.Baz"}.
     */
    static String truncateLogger(String name, int max) {
        if (name == null || name.length() <= max || max <= 0) {
            return name == null ? "" : name;
        }
        int overflow = name.length() - max;
        final StringBuilder out = new StringBuilder(max);
        int i = 0;
        while (i < name.length() && overflow > 0) {
            final int dot = name.indexOf('.', i);
            if (dot < 0) {
                break;
            }
            final int segLen = dot - i;
            if (segLen > 1) {
                out.append(name.charAt(i)).append('.');
                overflow -= (segLen - 1);
                i = dot + 1;
            } else {
                out.append(name, i, dot + 1);
                i = dot + 1;
            }
        }
        out.append(name, i, name.length());
        return out.toString();
    }
}
