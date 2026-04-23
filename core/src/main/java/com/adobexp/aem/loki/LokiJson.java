/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Serialises a batch of {@link LokiStream}s into the Loki push JSON
 * body and optionally gzips it when the compressed form is smaller than
 * the raw body (Loki honours {@code Content-Encoding: gzip}). Both
 * methods are static and allocation-minimal: one {@link StringBuilder}
 * for the body, one {@link ByteArrayOutputStream} for compression.
 *
 * <p>The writer does not depend on any JSON library so the bundle keeps
 * its zero-third-party-logging-library promise and its tiny
 * Import-Package list.
 */
final class LokiJson {

    private LokiJson() {
        // static utility
    }

    /**
     * Builds the raw JSON body for a Loki push request, of the form
     * {@code {"streams":[{"stream":{...labels...},"values":[[...ns...,"...line..."]]}]}}.
     */
    static byte[] buildJsonBody(Iterable<LokiStream> streams) {
        final StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"streams\":[");
        boolean firstStream = true;
        for (LokiStream s : streams) {
            if (!firstStream) {
                sb.append(',');
            }
            firstStream = false;
            sb.append("{\"stream\":{");
            boolean firstLabel = true;
            for (Map.Entry<String, String> e : s.labels.entrySet()) {
                if (!firstLabel) {
                    sb.append(',');
                }
                firstLabel = false;
                appendJsonString(sb, e.getKey());
                sb.append(':');
                appendJsonString(sb, e.getValue());
            }
            sb.append("},\"values\":[");
            boolean firstValue = true;
            for (String[] v : s.values) {
                if (!firstValue) {
                    sb.append(',');
                }
                firstValue = false;
                sb.append('[');
                appendJsonString(sb, v[0]);
                sb.append(',');
                appendJsonString(sb, v[1]);
                sb.append(']');
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Appends a JSON-escaped string literal (including surrounding
     * double quotes) to {@code sb}. Handles the six mandatory escapes
     * plus {@code \\uXXXX} for other control characters; relies on the
     * UTF-8 encoder to produce the final bytes so no surrogate-pair
     * handling is needed here.
     */
    static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        if (value == null) {
            sb.append('"');
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /**
     * Returns a gzip-compressed copy of {@code raw} when the result is
     * strictly smaller than the input, otherwise {@code null}. Very
     * small payloads (&lt; 1 KiB) are never compressed because the gzip
     * envelope overhead usually dominates.
     */
    static byte[] gzipIfSmaller(byte[] raw) {
        if (raw == null || raw.length < 1024) {
            return null;
        }
        try (ByteArrayOutputStream buf = new ByteArrayOutputStream(raw.length / 2);
             GZIPOutputStream gz = new GZIPOutputStream(buf)) {
            gz.write(raw);
            gz.finish();
            final byte[] out = buf.toByteArray();
            return out.length < raw.length ? out : null;
        } catch (IOException e) {
            return null;
        }
    }
}
