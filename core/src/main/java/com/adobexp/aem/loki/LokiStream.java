/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One entry in the Loki HTTP push body. Loki groups events by an
 * identical label set, so we build one {@code LokiStream} per unique
 * set of resolved labels and append every matching event to its
 * {@link #values} list.
 *
 * <p>Named {@code LokiStream} (not {@code Stream}) to avoid accidental
 * shadowing of {@link java.util.stream.Stream} when a file in this
 * package imports the {@code java.util.stream} package.
 */
final class LokiStream {

    final Map<String, String> labels;
    final List<String[]> values = new ArrayList<>();

    LokiStream(Map<String, String> labels) {
        this.labels = labels;
    }
}
