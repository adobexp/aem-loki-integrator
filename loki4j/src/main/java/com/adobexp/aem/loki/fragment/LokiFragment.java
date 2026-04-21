package com.adobexp.aem.loki.fragment;

/**
 * Marker type for the {@code aem-loki-integrator.loki4j} OSGi fragment bundle.
 *
 * <p>This class exists only so the Maven source and javadoc plugins have a
 * public type to process during a Sonatype Central release build. The fragment
 * bundle itself does not expose any public Java API of its own - it re-exports
 * the {@code com.github.loki4j.*} packages of the embedded
 * {@code loki-logback-appender} so they become visible inside the Logback
 * runtime hosted by {@code org.apache.sling.commons.log}.</p>
 *
 * <p>All runtime wiring is done by
 * {@code com.adobexp.aem.loki.LogbackLokiBootstrap} in the sibling
 * {@code aem-loki-integrator.core} bundle. There is nothing to instantiate
 * here.</p>
 */
public final class LokiFragment {

    /**
     * The OSGi Bundle-SymbolicName this class belongs to. Kept as a simple
     * constant so consumers can reference the fragment bundle by name without
     * having to string-match it by hand.
     */
    public static final String BUNDLE_SYMBOLIC_NAME = "aem-loki-integrator.loki4j";

    private LokiFragment() {
        // No instances.
    }
}
