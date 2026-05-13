package com.migrationagent.core;

import com.migrationagent.model.CompilationError;
import com.migrationagent.model.DependencyInfo;
import com.migrationagent.model.MigrationIssue;
import com.migrationagent.model.MigrationReport;
import com.migrationagent.model.ModuleInfo;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared mutable state across all agents during a migration run.
 * Thread-safe for concurrent agent access.
 *
 * <p>Supports both single-module and multi-module project structures.
 * When operating in multi-module mode, each module gets its own tracking
 * and the orchestrator processes them in dependency order.</p>
 */
public class MigrationContext {

    private final MigrationConfig config;
    private final MigrationReport report;

    // ─── Multi-Module Support ────────────────────────────────────────
    /** All discovered modules in topological (migration) order */
    private List<ModuleInfo> discoveredModules = new CopyOnWriteArrayList<>();

    /** The module currently being processed (null if single-module mode) */
    private volatile ModuleInfo currentModule;

    // ─── Discovered project metadata ─────────────────────────────────
    private final List<Path> javaSourceFiles = new CopyOnWriteArrayList<>();
    private final List<Path> buildFiles = new CopyOnWriteArrayList<>();
    private final List<DependencyInfo> dependencies = new CopyOnWriteArrayList<>();

    // Issues found during analysis
    private final List<MigrationIssue> issues = new CopyOnWriteArrayList<>();

    // Compilation state
    private final List<CompilationError> compilationErrors = new CopyOnWriteArrayList<>();
    private int healingIteration = 0;

    // Files that have been modified
    private final Set<Path> modifiedFiles = ConcurrentHashMap.newKeySet();
    private final Map<String, String> originalSources = new ConcurrentHashMap<>();
    private final Map<String, String> codeReviews = new ConcurrentHashMap<>();

    // Dependencies that could not be updated (tightly coupled)
    private final List<DependencyInfo> blockedDependencies = new CopyOnWriteArrayList<>();

    // Backup directory
    private Path backupDirectory;

    public MigrationContext(MigrationConfig config) {
        this.config = config;
        this.report = new MigrationReport(config);
    }

    // ─── Config ──────────────────────────────────────────────────────
    public MigrationConfig getConfig() { return config; }
    public Path getProjectPath() { return config.getProjectPath(); }
    public int getSourceVersion() { return config.getSourceVersion(); }
    public int getTargetVersion() { return config.getTargetVersion(); }

    // ─── Multi-Module ────────────────────────────────────────────────
    public void setDiscoveredModules(List<ModuleInfo> modules) {
        this.discoveredModules = new CopyOnWriteArrayList<>(modules);
    }
    public List<ModuleInfo> getDiscoveredModules() {
        return Collections.unmodifiableList(discoveredModules);
    }
    public boolean isMultiModule() {
        return discoveredModules.size() > 1;
    }
    /** Get only non-parent leaf modules (the ones that need code migration). */
    public List<ModuleInfo> getLeafModules() {
        return discoveredModules.stream()
                .filter(m -> !m.isParent())
                .toList();
    }
    public ModuleInfo getCurrentModule() { return currentModule; }
    public void setCurrentModule(ModuleInfo module) { this.currentModule = module; }

    /**
     * Returns the effective project path — if a module is being processed,
     * returns the module path; otherwise returns the root project path.
     */
    public Path getEffectiveProjectPath() {
        return currentModule != null ? currentModule.getPath() : config.getProjectPath();
    }

    // ─── Source Files ────────────────────────────────────────────────
    public void addJavaSourceFile(Path file) { javaSourceFiles.add(file); }
    public void addJavaSourceFiles(Collection<Path> files) { javaSourceFiles.addAll(files); }
    public List<Path> getJavaSourceFiles() { return Collections.unmodifiableList(javaSourceFiles); }
    public void clearJavaSourceFiles() { javaSourceFiles.clear(); }

    // ─── Build Files ─────────────────────────────────────────────────
    public void addBuildFile(Path file) { buildFiles.add(file); }
    public List<Path> getBuildFiles() { return Collections.unmodifiableList(buildFiles); }
    public void clearBuildFiles() { buildFiles.clear(); }

    // ─── Dependencies ────────────────────────────────────────────────
    public void addDependency(DependencyInfo dep) { dependencies.add(dep); }
    public void addDependencies(Collection<DependencyInfo> deps) { dependencies.addAll(deps); }
    public List<DependencyInfo> getDependencies() { return Collections.unmodifiableList(dependencies); }
    public void clearDependencies() { dependencies.clear(); }

    // ─── Issues ──────────────────────────────────────────────────────
    public void addIssue(MigrationIssue issue) {
        issues.add(issue);
        report.addIssue(issue);
        // Also track on current module if in multi-module mode
        if (currentModule != null) {
            currentModule.addIssue(issue);
        }
    }
    public List<MigrationIssue> getIssues() { return Collections.unmodifiableList(issues); }
    public long getIssueCount(MigrationIssue.Severity severity) {
        return issues.stream().filter(i -> i.getSeverity() == severity).count();
    }

    // ─── Compilation ─────────────────────────────────────────────────
    public void setCompilationErrors(List<CompilationError> errors) {
        compilationErrors.clear();
        compilationErrors.addAll(errors);
    }
    public List<CompilationError> getCompilationErrors() {
        return Collections.unmodifiableList(compilationErrors);
    }
    public void clearCompilationErrors() { compilationErrors.clear(); }

    // ─── Healing ─────────────────────────────────────────────────────
    public int getHealingIteration() { return healingIteration; }
    public void incrementHealingIteration() { healingIteration++; }
    public void resetHealingIteration() { healingIteration = 0; }
    public boolean canRetry() { return healingIteration < config.getMaxRetries(); }

    // ─── Modified Files ──────────────────────────────────────────────
    public void markModified(Path file) { modifiedFiles.add(file); }
    public Set<Path> getModifiedFiles() { return Collections.unmodifiableSet(modifiedFiles); }

    public void addOriginalSource(String absolutePath, String source) {
        originalSources.putIfAbsent(absolutePath, source);
    }
    public Map<String, String> getOriginalSources() { return Collections.unmodifiableMap(originalSources); }

    public void setCodeReviews(Map<String, String> reviews) {
        this.codeReviews.clear();
        this.codeReviews.putAll(reviews);
    }
    public Map<String, String> getCodeReviews() { return Collections.unmodifiableMap(codeReviews); }

    // ─── Blocked Dependencies ────────────────────────────────────────
    public void addBlockedDependency(DependencyInfo dep) { blockedDependencies.add(dep); }
    public List<DependencyInfo> getBlockedDependencies() {
        return Collections.unmodifiableList(blockedDependencies);
    }

    // ─── Backup ──────────────────────────────────────────────────────
    public Path getBackupDirectory() { return backupDirectory; }
    public void setBackupDirectory(Path dir) { this.backupDirectory = dir; }

    // ─── Report ──────────────────────────────────────────────────────
    public MigrationReport getReport() { return report; }

    /**
     * Reset per-module transient state (source files, deps, errors)
     * while preserving global state (modules list, report, modified files).
     * Called between module iterations.
     */
    public void resetForNextModule() {
        javaSourceFiles.clear();
        buildFiles.clear();
        dependencies.clear();
        compilationErrors.clear();
        healingIteration = 0;
    }
}
