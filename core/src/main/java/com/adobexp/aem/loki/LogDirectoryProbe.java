/*
 * Copyright (c) Adobexp. Licensed under the terms in the LICENSE file.
 */
package com.adobexp.aem.loki;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers on-disk log files for the opt-in tailer. AEM ships error
 * logs under a handful of different paths depending on topology
 * (classic quickstart vs. AEMaaCS pods vs. custom deployments), so the
 * probe has to try all of them in priority order.
 *
 * <p>Candidate AEM log directories, ordered from most specific
 * (runtime-exposed) to most generic (container defaults). The first
 * directory that contains a matching file name wins.
 * <ul>
 *     <li>{@code sling.home}/logs - honoured when Sling exposes the property</li>
 *     <li>{@code $SLING_HOME/logs} - honoured when the runtime exports it</li>
 *     <li>{@code user.dir/crx-quickstart/logs} - classic AEM quickstart layout</li>
 *     <li>{@code user.dir/logs} - when the JVM is started from inside crx-quickstart</li>
 *     <li>{@code /opt/aem/crx-quickstart/logs} - AEMaaCS author/publish pod layout</li>
 *     <li>{@code /crx-quickstart/logs} - classic container layout</li>
 *     <li>{@code /var/log/experience-manager} - AEMaaCS canonical log mount</li>
 *     <li>{@code /var/log/aem} - fallback mount some AEMaaCS images use</li>
 *     <li>{@code /var/log/sling} - last-resort fallback</li>
 * </ul>
 *
 * <p>Candidate <em>file names</em> (probed for each directory when the
 * configured entry is just a file name): {@code error.log},
 * {@code aemerror.log}, {@code aem-error.log}. The actual on-disk name
 * differs between AEM classic ({@code error.log}) and AEMaaCS pods
 * ({@code aemerror.log}).
 */
final class LogDirectoryProbe {

    private static final Logger LOG = LoggerFactory.getLogger(LogDirectoryProbe.class);

    private LogDirectoryProbe() {
        // static utility
    }

    /**
     * Resolves the configured {@code logFiles} entries against the
     * known candidate directories, returning one absolute path per
     * entry. Relative entries are first expanded for environment
     * references and then probed against every candidate directory
     * with a set of known name aliases.
     */
    static List<Path> resolveLogFiles(String[] cfg) {
        final List<String> sources = new ArrayList<>();
        if (cfg != null) {
            for (String s : cfg) {
                if (s != null && !s.trim().isEmpty()) {
                    sources.add(s.trim());
                }
            }
        }
        if (sources.isEmpty()) {
            sources.add("error.log");
        }

        final List<Path> baseDirs = candidateLogDirs();
        final List<Path> out = new ArrayList<>(sources.size());
        for (String s : sources) {
            final String expanded = PatternExpander.expandEnvRefs(s);
            final Path given = Paths.get(expanded);
            if (given.isAbsolute()) {
                out.add(given.normalize());
                continue;
            }
            final List<String> nameAliases = aliasFileNames(expanded);
            Path chosen = null;
            outer:
            for (Path base : baseDirs) {
                for (String alias : nameAliases) {
                    final Path candidate = base.resolve(alias).normalize();
                    if (Files.isRegularFile(candidate)) {
                        chosen = candidate;
                        break outer;
                    }
                }
            }
            if (chosen == null) {
                // Fall back to the first candidate directory even if the file
                // does not exist yet - the tailer will notice when it appears.
                chosen = baseDirs.isEmpty()
                        ? given.toAbsolutePath().normalize()
                        : baseDirs.get(0).resolve(expanded).normalize();
            }
            out.add(chosen);
        }
        return out;
    }

    private static List<String> aliasFileNames(String given) {
        // Only alias plain file names (e.g. "error.log"), not nested paths.
        if (given.contains("/") || given.contains(File.separator)) {
            return Collections.singletonList(given);
        }
        final List<String> out = new ArrayList<>(3);
        out.add(given);
        if (given.equalsIgnoreCase("error.log")) {
            out.add("aemerror.log");
            out.add("aem-error.log");
        } else if (given.equalsIgnoreCase("aemerror.log") || given.equalsIgnoreCase("aem-error.log")) {
            out.add("error.log");
        }
        return out;
    }

    static List<Path> candidateLogDirs() {
        final List<Path> out = new ArrayList<>();
        final String slingHomeProp = System.getProperty("sling.home");
        if (slingHomeProp != null && !slingHomeProp.trim().isEmpty()) {
            out.add(Paths.get(slingHomeProp, "logs").normalize());
        }
        final String slingHomeEnv = System.getenv("SLING_HOME");
        if (slingHomeEnv != null && !slingHomeEnv.trim().isEmpty()) {
            out.add(Paths.get(slingHomeEnv, "logs").normalize());
        }
        final String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.trim().isEmpty()) {
            out.add(Paths.get(userDir, "crx-quickstart", "logs").normalize());
            out.add(Paths.get(userDir, "logs").normalize());
            out.add(Paths.get(userDir).normalize());
        }
        out.add(Paths.get("/opt/aem/crx-quickstart/logs").normalize());
        out.add(Paths.get("/crx-quickstart/logs").normalize());
        out.add(Paths.get("/var/log/experience-manager").normalize());
        out.add(Paths.get("/var/log/aem").normalize());
        out.add(Paths.get("/var/log/sling").normalize());
        // De-duplicate while preserving order
        final List<Path> dedup = new ArrayList<>(out.size());
        for (Path p : out) {
            if (!dedup.contains(p)) {
                dedup.add(p);
            }
        }
        return dedup;
    }

    /**
     * Prints a concise inventory of every candidate log directory: whether
     * it exists, and a listing of up to 20 log files it contains. This is
     * invaluable on AEMaaCS pods where the actual log location is not
     * documented and varies between image revisions. The output is one log
     * line per directory.
     */
    static void reportLogDirInventory() {
        for (Path dir : candidateLogDirs()) {
            if (!Files.isDirectory(dir)) {
                LOG.info("Loki forwarder probe: {} (not present)", dir);
                continue;
            }
            final List<String> names = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> {
                            final String n = p.getFileName().toString().toLowerCase();
                            return n.endsWith(".log") || n.contains("error") || n.contains("access")
                                    || n.contains("request") || n.contains("stdout")
                                    || n.contains("stderr");
                        })
                        .limit(20L)
                        .forEach(p -> {
                            long size = -1L;
                            try {
                                size = Files.size(p);
                            } catch (IOException ignored) {
                                /* noop */
                            }
                            names.add(p.getFileName() + "(" + size + "b)");
                        });
            } catch (IOException | SecurityException e) {
                LOG.info("Loki forwarder probe: {} (unreadable: {})", dir, e.toString());
                continue;
            }
            LOG.info("Loki forwarder probe: {} -> {}", dir, names);
        }
    }
}
