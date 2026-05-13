package com.migrationagent.model;

import java.nio.file.Path;

/**
 * Represents a single issue found during migration analysis.
 */
public class MigrationIssue {

    public enum Severity {
        /** Informational — no action needed */
        INFO,
        /** Warning — should be addressed but migration can proceed */
        WARNING,
        /** Error — must be fixed for migration to succeed */
        ERROR,
        /** Blocker — cannot be automatically fixed, requires human intervention */
        BLOCKER
    }

    public enum Category {
        DEPRECATED_API,
        REMOVED_API,
        INCOMPATIBLE_DEPENDENCY,
        TIGHTLY_COUPLED_DEPENDENCY,
        COMPILATION_ERROR,
        SOURCE_INCOMPATIBILITY,
        MODULE_SYSTEM,
        RUNTIME_ISSUE,
        BUILD_CONFIG,
        UNKNOWN
    }

    private final String id;
    private final Severity severity;
    private final Category category;
    private final String title;
    private final String description;
    private final Path affectedFile;
    private final int lineNumber;
    private final String suggestedFix;
    private final boolean autoFixable;
    private boolean resolved;

    private MigrationIssue(Builder builder) {
        this.id = builder.id;
        this.severity = builder.severity;
        this.category = builder.category;
        this.title = builder.title;
        this.description = builder.description;
        this.affectedFile = builder.affectedFile;
        this.lineNumber = builder.lineNumber;
        this.suggestedFix = builder.suggestedFix;
        this.autoFixable = builder.autoFixable;
        this.resolved = false;
    }

    public String getId() { return id; }
    public Severity getSeverity() { return severity; }
    public Category getCategory() { return category; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Path getAffectedFile() { return affectedFile; }
    public int getLineNumber() { return lineNumber; }
    public String getSuggestedFix() { return suggestedFix; }
    public boolean isAutoFixable() { return autoFixable; }
    public boolean isResolved() { return resolved; }
    public void markResolved() { this.resolved = true; }

    @Override
    public String toString() {
        String location = affectedFile != null
                ? affectedFile.getFileName() + (lineNumber > 0 ? ":" + lineNumber : "")
                : "project-wide";
        return String.format("[%s/%s] %s @ %s", severity, category, title, location);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String id;
        private Severity severity = Severity.WARNING;
        private Category category = Category.UNKNOWN;
        private String title = "";
        private String description = "";
        private Path affectedFile;
        private int lineNumber = -1;
        private String suggestedFix = "";
        private boolean autoFixable = false;

        public Builder id(String id) { this.id = id; return this; }
        public Builder severity(Severity s) { this.severity = s; return this; }
        public Builder category(Category c) { this.category = c; return this; }
        public Builder title(String t) { this.title = t; return this; }
        public Builder description(String d) { this.description = d; return this; }
        public Builder affectedFile(Path f) { this.affectedFile = f; return this; }
        public Builder lineNumber(int n) { this.lineNumber = n; return this; }
        public Builder suggestedFix(String f) { this.suggestedFix = f; return this; }
        public Builder autoFixable(boolean a) { this.autoFixable = a; return this; }

        public MigrationIssue build() {
            if (id == null || id.isBlank()) {
                id = "ISSUE-" + System.nanoTime();
            }
            return new MigrationIssue(this);
        }
    }
}
