package com.migrationagent.model;

/**
 * Represents a Maven/Gradle dependency with compatibility info.
 */
public class DependencyInfo {

    public enum CompatibilityStatus {
        /** Compatible with target JDK */
        COMPATIBLE,
        /** Needs version upgrade to be compatible */
        NEEDS_UPGRADE,
        /** Tightly coupled to current JDK version — cannot upgrade */
        TIGHTLY_COUPLED,
        /** Unknown compatibility */
        UNKNOWN
    }

    private final String groupId;
    private final String artifactId;
    private final String currentVersion;
    private String recommendedVersion;
    private CompatibilityStatus status;
    private String notes;

    public DependencyInfo(String groupId, String artifactId, String currentVersion) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.currentVersion = currentVersion;
        this.status = CompatibilityStatus.UNKNOWN;
    }

    public String getGroupId() { return groupId; }
    public String getArtifactId() { return artifactId; }
    public String getCurrentVersion() { return currentVersion; }
    public String getRecommendedVersion() { return recommendedVersion; }
    public CompatibilityStatus getStatus() { return status; }
    public String getNotes() { return notes; }

    public void setRecommendedVersion(String v) { this.recommendedVersion = v; }
    public void setStatus(CompatibilityStatus s) { this.status = s; }
    public void setNotes(String n) { this.notes = n; }

    /** Returns the Maven coordinate string groupId:artifactId:version */
    public String getCoordinate() {
        return groupId + ":" + artifactId + ":" + currentVersion;
    }

    /** Returns the Maven coordinate string with recommended version */
    public String getRecommendedCoordinate() {
        return groupId + ":" + artifactId + ":" + (recommendedVersion != null ? recommendedVersion : currentVersion);
    }

    @Override
    public String toString() {
        return String.format("%s [%s] → %s",
                getCoordinate(), status,
                recommendedVersion != null ? recommendedVersion : "N/A");
    }
}
