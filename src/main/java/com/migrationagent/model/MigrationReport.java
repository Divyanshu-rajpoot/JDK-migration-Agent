package com.migrationagent.model;

import com.migrationagent.core.MigrationConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Comprehensive migration report generated at the end of a migration run.
 * Supports both single-module and multi-module project reporting.
 */
public class MigrationReport {

    private final MigrationConfig config;
    private final Instant startTime;
    private Instant endTime;

    private final List<MigrationIssue> allIssues = new ArrayList<>();
    private final List<String> actionsPerformed = new ArrayList<>();
    private final Map<String, String> dependencyChanges = new LinkedHashMap<>();

    // Per-module tracking for multi-module projects
    private final List<ModuleInfo> moduleResults = new ArrayList<>();

    private int filesAnalyzed;
    private int filesModified;
    private int compilationAttempts;
    private boolean migrationSuccessful;

    public MigrationReport(MigrationConfig config) {
        this.config = config;
        this.startTime = Instant.now();
    }

    // ─── Issue tracking ──────────────────────────────────────────────
    public void addIssue(MigrationIssue issue) { allIssues.add(issue); }

    public List<MigrationIssue> getAllIssues() { return Collections.unmodifiableList(allIssues); }

    public List<MigrationIssue> getUnresolvedIssues() {
        return allIssues.stream().filter(i -> !i.isResolved()).collect(Collectors.toList());
    }

    public List<MigrationIssue> getBlockers() {
        return allIssues.stream()
                .filter(i -> i.getSeverity() == MigrationIssue.Severity.BLOCKER && !i.isResolved())
                .collect(Collectors.toList());
    }

    // ─── Actions ─────────────────────────────────────────────────────
    public void addAction(String action) { actionsPerformed.add(action); }
    public List<String> getActionsPerformed() { return Collections.unmodifiableList(actionsPerformed); }

    // ─── Dependency changes ──────────────────────────────────────────
    public void addDependencyChange(String coordinate, String newVersion) {
        dependencyChanges.put(coordinate, newVersion);
    }
    public Map<String, String> getDependencyChanges() {
        return Collections.unmodifiableMap(dependencyChanges);
    }

    // ─── Module results ──────────────────────────────────────────────
    public void setModuleResults(List<ModuleInfo> modules) {
        moduleResults.clear();
        moduleResults.addAll(modules);
    }
    public List<ModuleInfo> getModuleResults() {
        return Collections.unmodifiableList(moduleResults);
    }

    // ─── Stats ───────────────────────────────────────────────────────
    public void setFilesAnalyzed(int n) { this.filesAnalyzed = n; }
    public void setFilesModified(int n) { this.filesModified = n; }
    public void setCompilationAttempts(int n) { this.compilationAttempts = n; }
    public void setMigrationSuccessful(boolean b) { this.migrationSuccessful = b; }
    public void complete() { this.endTime = Instant.now(); }

    public int getFilesAnalyzed() { return filesAnalyzed; }
    public int getFilesModified() { return filesModified; }
    public int getCompilationAttempts() { return compilationAttempts; }
    public boolean isMigrationSuccessful() { return migrationSuccessful; }

    public Duration getDuration() {
        Instant end = endTime != null ? endTime : Instant.now();
        return Duration.between(startTime, end);
    }

    // ─── Report generation ───────────────────────────────────────────

    /**
     * Generate a human-readable text report.
     */
    public String generateTextReport() {
        StringBuilder sb = new StringBuilder();
        String line = "=".repeat(70);
        String thinLine = "-".repeat(70);

        sb.append("\n").append(line).append("\n");
        sb.append("  JDK MIGRATION REPORT\n");
        sb.append(line).append("\n\n");

        // Summary
        sb.append("  Migration: JDK ").append(config.getSourceVersion())
          .append(" -> JDK ").append(config.getTargetVersion()).append("\n");
        sb.append("  Project:   ").append(config.getProjectPath()).append("\n");
        sb.append("  Duration:  ").append(formatDuration(getDuration())).append("\n");
        sb.append("  Status:    ").append(migrationSuccessful ? "[SUCCESS] SUCCESS" : "[!] NEEDS ATTENTION").append("\n\n");

        // Statistics
        sb.append(thinLine).append("\n");
        sb.append("  STATISTICS\n");
        sb.append(thinLine).append("\n");
        sb.append("  Files analyzed:         ").append(filesAnalyzed).append("\n");
        sb.append("  Files modified:         ").append(filesModified).append("\n");
        sb.append("  Compilation attempts:   ").append(compilationAttempts).append("\n");
        sb.append("  Total issues found:     ").append(allIssues.size()).append("\n");
        sb.append("  Issues auto-resolved:   ").append(allIssues.stream().filter(MigrationIssue::isResolved).count()).append("\n");
        sb.append("  Remaining issues:       ").append(getUnresolvedIssues().size()).append("\n");
        if (!moduleResults.isEmpty()) {
            sb.append("  Modules discovered:     ").append(moduleResults.size()).append("\n");
            long succeeded = moduleResults.stream()
                    .filter(m -> m.getStatus() == ModuleInfo.ModuleStatus.SUCCESS).count();
            long failed = moduleResults.stream()
                    .filter(m -> m.getStatus() == ModuleInfo.ModuleStatus.FAILED).count();
            sb.append("  Modules succeeded:      ").append(succeeded).append("\n");
            sb.append("  Modules failed:         ").append(failed).append("\n");
        }
        sb.append("\n");

        // ── Per-Module Breakdown ──
        if (!moduleResults.isEmpty()) {
            sb.append(thinLine).append("\n");
            sb.append("  MODULE BREAKDOWN\n");
            sb.append(thinLine).append("\n");
            sb.append(String.format("  %-25s %-15s %7s %7s %7s %7s%n",
                    "MODULE", "STATUS", "FILES", "MODIFIED", "ISSUES", "FIXED"));
            sb.append("  ").append("-".repeat(68)).append("\n");
            for (ModuleInfo m : moduleResults) {
                String statusIcon = switch (m.getStatus()) {
                    case SUCCESS -> "[OK]";
                    case PARTIAL_SUCCESS -> "[!]";
                    case FAILED -> "[X]";
                    case SKIPPED -> "[>>]";
                    default -> "[...]";
                };
                sb.append(String.format("  %-25s %s %-12s %7d %7d %7d %7d%n",
                        m.getName(), statusIcon, m.getStatus().name(),
                        m.getFilesAnalyzed(), m.getFilesModified(),
                        m.getIssuesFound(), m.getIssuesFixed()));
                if (m.getStatusMessage() != null && !m.getStatusMessage().isBlank()) {
                    sb.append("    -> ").append(m.getStatusMessage()).append("\n");
                }
                // Show inter-module dependencies
                if (!m.getDependsOnModules().isEmpty()) {
                    sb.append("    depends on: ").append(String.join(", ", m.getDependsOnModules())).append("\n");
                }
            }
            sb.append("\n");
        }

        // Dependency changes
        if (!dependencyChanges.isEmpty()) {
            sb.append(thinLine).append("\n");
            sb.append("  DEPENDENCY UPDATES\n");
            sb.append(thinLine).append("\n");
            dependencyChanges.forEach((coord, newVer) ->
                    sb.append("  - ").append(coord).append(" -> ").append(newVer).append("\n"));
            sb.append("\n");
        }

        // Blockers (things that need human intervention)
        List<MigrationIssue> blockers = getBlockers();
        if (!blockers.isEmpty()) {
            sb.append(thinLine).append("\n");
            sb.append("  [!] REQUIRES HUMAN INTERVENTION (").append(blockers.size()).append(")\n");
            sb.append(thinLine).append("\n");
            for (MigrationIssue blocker : blockers) {
                sb.append("\n  [").append(blocker.getCategory()).append("] ").append(blocker.getTitle()).append("\n");
                sb.append("    ").append(blocker.getDescription()).append("\n");
                if (blocker.getAffectedFile() != null) {
                    sb.append("    File: ").append(blocker.getAffectedFile());
                    if (blocker.getLineNumber() > 0) sb.append(":").append(blocker.getLineNumber());
                    sb.append("\n");
                }
                if (blocker.getSuggestedFix() != null && !blocker.getSuggestedFix().isBlank()) {
                    sb.append("    Suggestion: ").append(blocker.getSuggestedFix()).append("\n");
                }
            }
            sb.append("\n");
        }

        // Unresolved non-blocker issues
        List<MigrationIssue> unresolved = getUnresolvedIssues().stream()
                .filter(i -> i.getSeverity() != MigrationIssue.Severity.BLOCKER)
                .collect(Collectors.toList());
        if (!unresolved.isEmpty()) {
            sb.append(thinLine).append("\n");
            sb.append("  REMAINING ISSUES (").append(unresolved.size()).append(")\n");
            sb.append(thinLine).append("\n");
            for (MigrationIssue issue : unresolved) {
                sb.append("  [").append(issue.getSeverity()).append("] ").append(issue.getTitle());
                if (issue.getAffectedFile() != null) {
                    sb.append(" @ ").append(issue.getAffectedFile().getFileName());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Actions performed
        if (!actionsPerformed.isEmpty()) {
            sb.append(thinLine).append("\n");
            sb.append("  ACTIONS PERFORMED (").append(actionsPerformed.size()).append(")\n");
            sb.append(thinLine).append("\n");
            for (int i = 0; i < actionsPerformed.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(actionsPerformed.get(i)).append("\n");
            }
            sb.append("\n");
        }

        sb.append(line).append("\n");
        return sb.toString();
    }

    private String formatDuration(Duration d) {
        long mins = d.toMinutes();
        long secs = d.toSecondsPart();
        if (mins > 0) return mins + "m " + secs + "s";
        return secs + "." + (d.toMillisPart() / 100) + "s";
    }
}
