package com.migrationagent.core;

import com.migrationagent.llm.LlmConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable configuration for a migration run.
 */
public class MigrationConfig {

    private final Path projectPath;
    private final int sourceVersion;
    private final int targetVersion;
    private final List<String> modules;
    private final int maxRetries;
    private final boolean dryRun;
    private final boolean createBackup;
    private final LlmConfig llmConfig;

    private MigrationConfig(Builder builder) {
        this.projectPath = builder.projectPath;
        this.sourceVersion = builder.sourceVersion;
        this.targetVersion = builder.targetVersion;
        this.modules = builder.modules != null ? List.copyOf(builder.modules) : List.of();
        this.maxRetries = builder.maxRetries;
        this.dryRun = builder.dryRun;
        this.createBackup = builder.createBackup;
        this.llmConfig = builder.llmConfig;
    }

    public Path getProjectPath() { return projectPath; }
    public int getSourceVersion() { return sourceVersion; }
    public int getTargetVersion() { return targetVersion; }
    public List<String> getModules() { return modules; }
    public int getMaxRetries() { return maxRetries; }
    public boolean isDryRun() { return dryRun; }
    public boolean isCreateBackup() { return createBackup; }
    public LlmConfig getLlmConfig() { return llmConfig; }

    /** Returns true if AI/LLM is enabled for this migration. */
    public boolean isAiEnabled() {
        return llmConfig != null && llmConfig.isEnabled();
    }

    /** Returns true if a specific set of modules was specified. */
    public boolean hasModuleFilter() {
        return modules != null && !modules.isEmpty();
    }

    /**
     * Normalizes JDK version strings like "1.6" → 6, "1.8" → 8.
     */
    public static int normalizeVersion(String version) {
        if (version == null || version.isBlank()) return 0;
        version = version.trim();
        if (version.startsWith("1.")) {
            return Integer.parseInt(version.substring(2));
        }
        // Handle "17.0.1" style
        int dotIdx = version.indexOf('.');
        if (dotIdx > 0) {
            version = version.substring(0, dotIdx);
        }
        return Integer.parseInt(version);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        String ai = llmConfig != null && llmConfig.isEnabled()
                ? ", ai=" + llmConfig.getProvider() + "/" + llmConfig.getModel()
                : ", ai=NONE";
        return String.format("MigrationConfig[%s: JDK %d → %d, modules=%s, dryRun=%b%s]",
                projectPath, sourceVersion, targetVersion, modules, dryRun, ai);
    }

    public static class Builder {
        private Path projectPath;
        private int sourceVersion;
        private int targetVersion;
        private List<String> modules;
        private int maxRetries = 10;
        private boolean dryRun = false;
        private boolean createBackup = true;
        private LlmConfig llmConfig = LlmConfig.builder().build(); // NONE by default

        public Builder projectPath(Path p) { this.projectPath = p; return this; }
        public Builder sourceVersion(int v) { this.sourceVersion = v; return this; }
        public Builder targetVersion(int v) { this.targetVersion = v; return this; }
        public Builder modules(List<String> m) { this.modules = m; return this; }
        public Builder maxRetries(int r) { this.maxRetries = r; return this; }
        public Builder dryRun(boolean d) { this.dryRun = d; return this; }
        public Builder createBackup(boolean b) { this.createBackup = b; return this; }
        public Builder llmConfig(LlmConfig c) { this.llmConfig = c; return this; }

        public MigrationConfig build() {
            if (projectPath == null) throw new IllegalStateException("projectPath is required");
            if (sourceVersion <= 0) throw new IllegalStateException("sourceVersion is required");
            if (targetVersion <= 0) throw new IllegalStateException("targetVersion is required");
            return new MigrationConfig(this);
        }
    }
}
