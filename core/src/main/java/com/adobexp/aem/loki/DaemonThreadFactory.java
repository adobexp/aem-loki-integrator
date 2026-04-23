/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link ThreadFactory} that produces daemon threads with a shared name
 * prefix and a monotonically-increasing counter. Used for every executor
 * started by the forwarder so threads never prevent the JVM from
 * shutting down cleanly, and so their names in {@code jstack} output
 * clearly identify the bundle that owns them.
 */
final class DaemonThreadFactory implements ThreadFactory {

    private final String namePrefix;
    private final AtomicInteger counter = new AtomicInteger();

    DaemonThreadFactory(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        final Thread t = new Thread(r, namePrefix + '-' + counter.incrementAndGet());
        t.setDaemon(true);
        return t;
    }
}
