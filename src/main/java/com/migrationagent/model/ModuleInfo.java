package com.migrationagent.model;

import java.nio.file.Path;
import java.util.*;

/**
 * Represents a single module within a multi-module project or a standalone application
 * within a multi-application workspace.
 *
 * <p>A "module" can be:</p>
 * <ul>
 *   <li>A Maven module defined in a parent POM's {@code <modules>} section</li>
 *   <li>A Gradle sub-project in settings.gradle</li>
 *   <li>A standalone application directory in a multi-app workspace</li>
 * </ul>
 */
public class ModuleInfo {

    public enum ModuleStatus {
        /** Not yet processed */
        PENDING,
        /** Currently being migrated */
        IN_PROGRESS,
        /** Migration completed successfully */
        SUCCESS,
        /** Migration completed with warnings */
        PARTIAL_SUCCESS,
        /** Migration failed */
        FAILED,
        /** Skipped (user filtered it out, or tightly coupled) */
        SKIPPED
    }

    private final String name;
    private final Path path;
    private final Path buildFile;          // pom.xml or build.gradle path
    private final boolean isParent;        // true if this is a parent/aggregator module

    // Discovered relationships
    private final List<String> childModuleNames = new ArrayList<>();
    private final List<String> dependsOnModules = new ArrayList<>(); // inter-module deps
    private final List<DependencyInfo> externalDependencies = new ArrayList<>();
    private final List<MigrationIssue> issues = new ArrayList<>();

    // Status tracking
    private ModuleStatus status = ModuleStatus.PENDING;
    private int filesAnalyzed;
    private int filesModified;
    private int issuesFound;
    private int issuesFixed;
    private String statusMessage = "";

    public ModuleInfo(String name, Path path, Path buildFile, boolean isParent) {
        this.name = name;
        this.path = path;
        this.buildFile = buildFile;
        this.isParent = isParent;
    }

    // ─── Identity ────────────────────────────────────────────────────
    public String getName() { return name; }
    public Path getPath() { return path; }
    public Path getBuildFile() { return buildFile; }
    public boolean isParent() { return isParent; }

    // ─── Relationships ───────────────────────────────────────────────
    public void addChildModule(String moduleName) { childModuleNames.add(moduleName); }
    public List<String> getChildModuleNames() { return Collections.unmodifiableList(childModuleNames); }

    public void addDependsOnModule(String moduleName) { dependsOnModules.add(moduleName); }
    public List<String> getDependsOnModules() { return Collections.unmodifiableList(dependsOnModules); }

    public void addExternalDependency(DependencyInfo dep) { externalDependencies.add(dep); }
    public void addExternalDependencies(Collection<DependencyInfo> deps) { externalDependencies.addAll(deps); }
    public List<DependencyInfo> getExternalDependencies() { return Collections.unmodifiableList(externalDependencies); }

    // ─── Issues ──────────────────────────────────────────────────────
    public void addIssue(MigrationIssue issue) { issues.add(issue); }
    public List<MigrationIssue> getIssues() { return Collections.unmodifiableList(issues); }

    // ─── Status ──────────────────────────────────────────────────────
    public ModuleStatus getStatus() { return status; }
    public void setStatus(ModuleStatus s) { this.status = s; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String msg) { this.statusMessage = msg; }

    public int getFilesAnalyzed() { return filesAnalyzed; }
    public void setFilesAnalyzed(int n) { this.filesAnalyzed = n; }
    public int getFilesModified() { return filesModified; }
    public void setFilesModified(int n) { this.filesModified = n; }
    public int getIssuesFound() { return issuesFound; }
    public void setIssuesFound(int n) { this.issuesFound = n; }
    public int getIssuesFixed() { return issuesFixed; }
    public void setIssuesFixed(int n) { this.issuesFixed = n; }

    @Override
    public String toString() {
        return String.format("Module[%s @ %s, status=%s, children=%d, depsOn=%d]",
                name, path.getFileName(), status, childModuleNames.size(), dependsOnModules.size());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModuleInfo that = (ModuleInfo) o;
        return Objects.equals(name, that.name) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, path);
    }
}
