package com.migrationagent.knowledge;

/**
 * Represents a single deprecation/removal rule for JDK migration.
 */
public class DeprecationRule {

    public enum RuleType {
        DEPRECATED, REMOVED, BEHAVIOR_CHANGE, MODULE_RESTRICTION, BUILD_CHANGE
    }

    public enum RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    private final String id;
    private final RuleType type;
    private final RiskLevel risk;
    private final int removedInVersion;
    private final int deprecatedInVersion;
    private final String apiPattern;
    private final String importPattern;
    private final String description;
    private final String replacement;
    private final String replacementImport;
    private final boolean autoFixable;

    private DeprecationRule(Builder b) {
        this.id = b.id; this.type = b.type; this.risk = b.risk;
        this.removedInVersion = b.removedInVersion;
        this.deprecatedInVersion = b.deprecatedInVersion;
        this.apiPattern = b.apiPattern; this.importPattern = b.importPattern;
        this.description = b.description; this.replacement = b.replacement;
        this.replacementImport = b.replacementImport; this.autoFixable = b.autoFixable;
    }

    /** Public constructor for external rule loading (JSON deserialization). */
    public DeprecationRule(String id, RuleType type, String description,
                           String importPattern, String apiPattern,
                           String replacementImport, String replacement,
                           int removedInVersion, int deprecatedInVersion,
                           boolean autoFixable) {
        this.id = id;
        this.type = type;
        this.risk = RiskLevel.MEDIUM;
        this.description = description;
        this.importPattern = importPattern;
        this.apiPattern = apiPattern;
        this.replacementImport = replacementImport;
        this.replacement = replacement;
        this.removedInVersion = removedInVersion;
        this.deprecatedInVersion = deprecatedInVersion;
        this.autoFixable = autoFixable;
    }

    public String getId() { return id; }
    public RuleType getType() { return type; }
    public RiskLevel getRisk() { return risk; }
    public int getRemovedInVersion() { return removedInVersion; }
    public int getDeprecatedInVersion() { return deprecatedInVersion; }
    public String getApiPattern() { return apiPattern; }
    public String getImportPattern() { return importPattern; }
    public String getDescription() { return description; }
    public String getReplacement() { return replacement; }
    public String getReplacementImport() { return replacementImport; }
    public boolean isAutoFixable() { return autoFixable; }

    public boolean appliesTo(int sourceVersion, int targetVersion) {
        if (removedInVersion > 0) {
            return sourceVersion < removedInVersion && targetVersion >= removedInVersion;
        }
        if (deprecatedInVersion > 0) {
            return sourceVersion < deprecatedInVersion && targetVersion >= deprecatedInVersion;
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s — %s (risk: %s)", type, apiPattern, description, risk);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id;
        private RuleType type = RuleType.DEPRECATED;
        private RiskLevel risk = RiskLevel.MEDIUM;
        private int removedInVersion, deprecatedInVersion;
        private String apiPattern = "", importPattern = "", description = "";
        private String replacement = "", replacementImport = "";
        private boolean autoFixable = false;

        public Builder id(String v) { this.id = v; return this; }
        public Builder type(RuleType v) { this.type = v; return this; }
        public Builder risk(RiskLevel v) { this.risk = v; return this; }
        public Builder removedIn(int v) { this.removedInVersion = v; return this; }
        public Builder deprecatedIn(int v) { this.deprecatedInVersion = v; return this; }
        public Builder apiPattern(String v) { this.apiPattern = v; return this; }
        public Builder importPattern(String v) { this.importPattern = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder replacement(String v) { this.replacement = v; return this; }
        public Builder replacementImport(String v) { this.replacementImport = v; return this; }
        public Builder autoFixable(boolean v) { this.autoFixable = v; return this; }

        public DeprecationRule build() {
            if (id == null) id = "RULE-" + System.nanoTime();
            return new DeprecationRule(this);
        }
    }
}
