/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

/**
 * Log levels recognised in AEM's default {@code error.log} layout. Ordered
 * from most severe (lowest {@link #ordinal()}) to least severe so that
 * threshold comparisons in {@link LoggerFilter#matches(String, Level)} can
 * reuse the natural enum ordering.
 */
enum Level {
    AUDIT, ERROR, WARN, INFO, DEBUG, TRACE;

    /**
     * Parses the level tag as emitted by the Sling Commons Log layout
     * (e.g. the {@code *INFO*} in {@code "*INFO* [thread] ..."}). Returns
     * {@link #INFO} for unknown or {@code null} input.
     */
    static Level fromAemTag(String tag) {
        if (tag == null) {
            return INFO;
        }
        switch (tag) {
            case "AUDIT":
                return AUDIT;
            case "ERROR":
                return ERROR;
            case "WARNING":
            case "WARN":
                return WARN;
            case "INFO":
                return INFO;
            case "DEBUG":
                return DEBUG;
            case "TRACE":
                return TRACE;
            default:
                return INFO;
        }
    }
}
