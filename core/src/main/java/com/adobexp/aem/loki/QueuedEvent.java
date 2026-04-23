/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

/**
 * Thin wrapper around a {@link ParsedEntry} used as the element type of
 * the internal push queue. Declared as a separate holder (rather than
 * using {@code ParsedEntry} directly) to keep the option open for
 * adding push-time state (retry counters, arrival timestamps) without
 * mutating the immutable {@link ParsedEntry}.
 */
final class QueuedEvent {

    final ParsedEntry entry;

    QueuedEvent(ParsedEntry entry) {
        this.entry = entry;
    }
}
