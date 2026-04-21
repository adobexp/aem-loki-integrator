/**
 * AEM Loki Integrator - loki4j fragment bundle.
 *
 * <p>This artifact is an OSGi <em>fragment</em> of
 * {@code org.apache.sling.commons.log} (the AEM host bundle that embeds the
 * Logback runtime). It repackages the third-party
 * <a href="https://github.com/loki4j/loki-logback-appender">loki-logback-appender</a>
 * JAR so its classes become visible to the Logback context without having to
 * be loaded by any customer bundle.</p>
 *
 * <p>The bundle intentionally ships <strong>no</strong> public Java API of
 * its own. It only exposes, via the OSGi {@code Export-Package} header, the
 * packages already defined inside {@code loki-logback-appender}:</p>
 *
 * <ul>
 *   <li>{@code com.github.loki4j.logback}</li>
 *   <li>{@code com.github.loki4j.pkg.*}</li>
 *   <li>{@code com.github.loki4j.util.*}</li>
 * </ul>
 *
 * <p>Wiring, configuration and activation are handled by
 * {@code com.adobexp.aem.loki.LogbackLokiBootstrap} in the sibling
 * {@code aem-loki-integrator.core} bundle - see that artifact for the public
 * entry points. This {@code package-info} exists so that the Maven source and
 * javadoc plugins have a compilable source unit to process during a release
 * build (Sonatype Central requires a non-empty {@code -sources.jar} and
 * {@code -javadoc.jar} next to every main JAR).</p>
 *
 * @see com.github.loki4j.logback.Loki4jAppender
 */
package com.adobexp.aem.loki.fragment;
