package com.migrationagent.knowledge;

import java.util.*;

/**
 * Known dependency compatibility data for popular Java libraries.
 * Maps library coordinates to minimum compatible JDK versions and recommended upgrade versions.
 */
public class CompatibilityMatrix {

    /**
     * Compatibility entry for a library.
     */
    public record LibraryCompat(
            String groupId,
            String artifactId,
            int minJdkVersion,
            String latestVersionForJdk8,
            String latestVersionForJdk11,
            String latestVersionForJdk17,
            String latestVersionForJdk21,
            boolean tightlyCoupled
    ) {
        public String getRecommendedVersion(int targetJdk) {
            if (targetJdk >= 21 && latestVersionForJdk21 != null) return latestVersionForJdk21;
            if (targetJdk >= 17 && latestVersionForJdk17 != null) return latestVersionForJdk17;
            if (targetJdk >= 11 && latestVersionForJdk11 != null) return latestVersionForJdk11;
            if (targetJdk >= 8 && latestVersionForJdk8 != null) return latestVersionForJdk8;
            return null;
        }
    }

    private final Map<String, LibraryCompat> compatMap = new HashMap<>();

    public CompatibilityMatrix() {
        initializeMatrix();
    }

    public Optional<LibraryCompat> lookup(String groupId, String artifactId) {
        return Optional.ofNullable(compatMap.get(key(groupId, artifactId)));
    }

    /** Register an externally-loaded library compatibility entry. */
    public void addExternal(LibraryCompat compat) {
        compatMap.put(key(compat.groupId(), compat.artifactId()), compat);
    }

    private String key(String g, String a) { return g + ":" + a; }

    private void add(String g, String a, int min, String j8, String j11, String j17, String j21, boolean tight) {
        compatMap.put(key(g, a), new LibraryCompat(g, a, min, j8, j11, j17, j21, tight));
    }

    private void initializeMatrix() {
        // ── Spring Framework ──
        add("org.springframework", "spring-core", 8, "5.3.39", "5.3.39", "6.1.16", "6.2.2", false);
        add("org.springframework", "spring-context", 8, "5.3.39", "5.3.39", "6.1.16", "6.2.2", false);
        add("org.springframework", "spring-web", 8, "5.3.39", "5.3.39", "6.1.16", "6.2.2", false);
        add("org.springframework", "spring-webmvc", 8, "5.3.39", "5.3.39", "6.1.16", "6.2.2", false);

        // ── Spring Boot ──
        add("org.springframework.boot", "spring-boot-starter", 8, "2.7.18", "2.7.18", "3.4.1", "3.4.1", false);
        add("org.springframework.boot", "spring-boot-starter-web", 8, "2.7.18", "2.7.18", "3.4.1", "3.4.1", false);
        add("org.springframework.boot", "spring-boot-starter-data-jpa", 8, "2.7.18", "2.7.18", "3.4.1", "3.4.1", false);

        // ── Hibernate ──
        add("org.hibernate", "hibernate-core", 8, "5.6.15.Final", "5.6.15.Final", "6.6.4.Final", "6.6.4.Final", false);
        add("org.hibernate.orm", "hibernate-core", 11, null, "6.4.12.Final", "6.6.4.Final", "6.6.4.Final", false);

        // ── Jackson ──
        add("com.fasterxml.jackson.core", "jackson-databind", 8, "2.17.3", "2.17.3", "2.18.2", "2.18.2", false);
        add("com.fasterxml.jackson.core", "jackson-core", 8, "2.17.3", "2.17.3", "2.18.2", "2.18.2", false);

        // ── Apache Commons ──
        add("org.apache.commons", "commons-lang3", 8, "3.14.0", "3.14.0", "3.17.0", "3.17.0", false);
        add("commons-io", "commons-io", 8, "2.16.1", "2.16.1", "2.18.0", "2.18.0", false);
        add("commons-collections", "commons-collections", 6, "3.2.2", "3.2.2", "3.2.2", "3.2.2", true);
        add("org.apache.commons", "commons-collections4", 8, "4.4", "4.4", "4.5.0-M3", "4.5.0-M3", false);

        // ── Logging ──
        add("org.slf4j", "slf4j-api", 8, "1.7.36", "2.0.16", "2.0.16", "2.0.16", false);
        add("ch.qos.logback", "logback-classic", 8, "1.2.13", "1.4.14", "1.5.16", "1.5.16", false);
        add("org.apache.logging.log4j", "log4j-core", 8, "2.17.2", "2.24.3", "2.24.3", "2.24.3", false);

        // ── JUnit ──
        add("junit", "junit", 5, "4.13.2", "4.13.2", "4.13.2", "4.13.2", false);
        add("org.junit.jupiter", "junit-jupiter", 8, "5.10.5", "5.10.5", "5.11.4", "5.11.4", false);

        // ── Guava ──
        add("com.google.guava", "guava", 8, "33.4.0-jre", "33.4.0-jre", "33.4.0-jre", "33.4.0-jre", false);

        // ── Lombok ──
        add("org.projectlombok", "lombok", 8, "1.18.36", "1.18.36", "1.18.36", "1.18.36", false);

        // ── Apache HttpClient ──
        add("org.apache.httpcomponents", "httpclient", 6, "4.5.14", "4.5.14", "4.5.14", "4.5.14", false);
        add("org.apache.httpcomponents.client5", "httpclient5", 8, "5.4.1", "5.4.1", "5.4.1", "5.4.1", false);

        // ── Servlet API ──
        add("javax.servlet", "javax.servlet-api", 7, "4.0.1", "4.0.1", null, null, true);
        add("jakarta.servlet", "jakarta.servlet-api", 11, null, "5.0.0", "6.1.0", "6.1.0", false);

        // ── JAXB standalone ──
        add("jakarta.xml.bind", "jakarta.xml.bind-api", 8, "2.3.3", "4.0.2", "4.0.2", "4.0.2", false);
        add("org.glassfish.jaxb", "jaxb-runtime", 8, "2.3.9", "4.0.5", "4.0.5", "4.0.5", false);

        // ── Mockito ──
        add("org.mockito", "mockito-core", 8, "4.11.0", "5.14.2", "5.14.2", "5.14.2", false);
    }
}
