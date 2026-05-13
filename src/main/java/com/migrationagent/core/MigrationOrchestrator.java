package com.migrationagent.core;

import com.migrationagent.agents.*;
import com.migrationagent.model.ModuleInfo;
import com.migrationagent.util.ConsoleUI;
import com.migrationagent.util.FileBackupManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Main orchestrator that coordinates all migration agents across
 * single-module and multi-module projects.
 *
 * <h3>Multi-Module Flow:</h3>
 * <ol>
 *   <li>Backup entire workspace</li>
 *   <li>Discover all modules and resolve dependency order</li>
 *   <li>For each module (in topological order):
 *     <ul>
 *       <li>Analyze dependencies</li>
 *       <li>Analyze source code</li>
 *       <li>Transform code and build files</li>
 *       <li>Compile → self-healing loop</li>
 *     </ul>
 *   </li>
 *   <li>Run full verification (tests) on root project</li>
 *   <li>Generate consolidated report with per-module breakdown</li>
 * </ol>
 */
public class MigrationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MigrationOrchestrator.class);

    private final MigrationContext context;
    private final List<AgentResult> agentResults = new ArrayList<>();

    // Agents
    private final ModuleDiscoveryAgent moduleDiscovery = new ModuleDiscoveryAgent();
    private final DependencyAnalyzerAgent dependencyAnalyzer = new DependencyAnalyzerAgent();
    private final SourceCodeAnalyzerAgent sourceAnalyzer = new SourceCodeAnalyzerAgent();
    private final SourceCodeTransformerAgent transformer = new SourceCodeTransformerAgent();
    private final CompilationAgent compiler = new CompilationAgent();
    private final SelfHealingAgent healer = new SelfHealingAgent();
    private final VerificationAgent verifier = new VerificationAgent();
    private final AICodeReviewAgent codeReviewer = new AICodeReviewAgent();
    private final ReportAgent reporter = new ReportAgent();
    private final WebDashboardAgent dashboardServer = new WebDashboardAgent();

    public MigrationOrchestrator(MigrationContext context) {
        this.context = context;
    }

    /**
     * Execute the full migration pipeline.
     *
     * @return true if migration completed successfully
     */
    public boolean executeMigration() {
        log.info("Starting migration: {}", context.getConfig());

        ConsoleUI.section("JDK " + context.getSourceVersion() + " -> JDK " + context.getTargetVersion() + " Migration");
        ConsoleUI.keyValue("Project", context.getProjectPath().toString());
        ConsoleUI.keyValue("Source JDK", String.valueOf(context.getSourceVersion()));
        ConsoleUI.keyValue("Target JDK", String.valueOf(context.getTargetVersion()));
        ConsoleUI.keyValue("Dry Run", String.valueOf(context.getConfig().isDryRun()));
        ConsoleUI.keyValue("Max Retries", String.valueOf(context.getConfig().getMaxRetries()));

        try {
            // ── Phase 1: Backup ──────────────────────────────────
            if (context.getConfig().isCreateBackup() && !context.getConfig().isDryRun()) {
                ConsoleUI.section("Phase 1: Backup");
                Path backup = FileBackupManager.createBackup(context.getProjectPath());
                context.setBackupDirectory(backup);
            }

            // ── Phase 2: Module Discovery ────────────────────────
            ConsoleUI.section("Phase 2: Module Discovery");
            AgentResult discoveryResult = moduleDiscovery.execute(context);
            agentResults.add(discoveryResult);

            if (discoveryResult.isFailure()) {
                ConsoleUI.error("Module discovery failed. Cannot proceed.");
                context.getReport().setMigrationSuccessful(false);
                reporter.execute(context);
                return false;
            }

            // ── Route to single-module or multi-module pipeline ──
            boolean success;
            if (context.isMultiModule()) {
                ConsoleUI.info("Multi-module project detected: " +
                        context.getDiscoveredModules().size() + " modules");
                success = executeMultiModuleMigration();
            } else {
                ConsoleUI.info("Single-module project -- running standard pipeline");
                success = executeSingleModuleMigration();
            }

            // ── Final Report ─────────────────────────────────────
            ConsoleUI.section("Final Migration Report");
            context.getReport().setMigrationSuccessful(success);

            // Add per-module summary to report
            if (context.isMultiModule()) {
                addModuleSummaryToReport();
            }

            reporter.execute(context);
            dashboardServer.execute(context);
            return success;

        } catch (Exception e) {
            log.error("Migration failed with unexpected error", e);
            ConsoleUI.error("Migration failed: " + e.getMessage());
            context.getReport().setMigrationSuccessful(false);
            reporter.execute(context);
            dashboardServer.execute(context);
            return false;
        }
    }

    /**
     * Execute analysis-only mode (no modifications).
     */
    public void executeAnalysis() {
        log.info("Starting analysis: {}", context.getConfig());

        ConsoleUI.section("Analysis: JDK " + context.getSourceVersion() + " -> JDK " + context.getTargetVersion());

        // Module Discovery
        ConsoleUI.section("Module Discovery");
        moduleDiscovery.execute(context);

        if (context.isMultiModule()) {
            // Analyze each module
            for (ModuleInfo module : context.getDiscoveredModules()) {
                if (module.isParent()) {
                    analyzeParentModule(module);
                } else {
                    analyzeChildModule(module);
                }
            }
        } else {
            // Single-module analysis
            ConsoleUI.section("Dependency Analysis");
            dependencyAnalyzer.execute(context);

            ConsoleUI.section("Source Code Analysis");
            sourceAnalyzer.execute(context);
        }

        // Report
        ConsoleUI.section("Analysis Report");
        context.getReport().setMigrationSuccessful(false);
        if (context.isMultiModule()) {
            addModuleSummaryToReport();
        }
        reporter.execute(context);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Multi-Module Migration Pipeline
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Migrate modules one-by-one in dependency order.
     * Parent POM is updated first, then child modules from leaves to root.
     */
    private boolean executeMultiModuleMigration() {
        List<ModuleInfo> modules = context.getDiscoveredModules();
        int totalModules = modules.size();
        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < totalModules; i++) {
            ModuleInfo module = modules.get(i);

            ConsoleUI.section(String.format("Module %d/%d: %s", i + 1, totalModules, module.getName()));
            context.setCurrentModule(module);
            module.setStatus(ModuleInfo.ModuleStatus.IN_PROGRESS);

            if (module.isParent()) {
                // Parent/aggregator — only update build file version
                boolean ok = migrateParentModule(module);
                module.setStatus(ok ? ModuleInfo.ModuleStatus.SUCCESS : ModuleInfo.ModuleStatus.PARTIAL_SUCCESS);
                if (ok) successCount++;
                continue;
            }

            // Check if dependencies of this module have all succeeded
            boolean depsReady = checkModuleDependenciesReady(module);
            if (!depsReady) {
                ConsoleUI.warn("Skipping " + module.getName() +
                        " -- dependency module(s) failed migration");
                module.setStatus(ModuleInfo.ModuleStatus.SKIPPED);
                module.setStatusMessage("Dependency module(s) not migrated successfully");
                context.getReport().addAction("SKIPPED module " + module.getName() +
                        " due to failed dependency module");
                continue;
            }

            // Reset transient state for this module
            context.resetForNextModule();

            boolean moduleSuccess = migrateChildModule(module);

            if (moduleSuccess) {
                module.setStatus(ModuleInfo.ModuleStatus.SUCCESS);
                module.setStatusMessage("Migration successful");
                successCount++;
            } else {
                module.setStatus(ModuleInfo.ModuleStatus.FAILED);
                module.setStatusMessage("Migration had issues -- see report");
                failCount++;
            }

            ConsoleUI.info("Module " + module.getName() + ": " + module.getStatus());
        }

        // Full project verification (compile + test from root)
        if (failCount == 0 && !context.getConfig().isDryRun()) {
            ConsoleUI.section("Full Project Verification");
            context.setCurrentModule(null); // back to root
            context.resetForNextModule();

            AgentResult compileResult = compiler.execute(context);
            agentResults.add(compileResult);

            if (compileResult.isSuccess()) {
                ConsoleUI.success("Full project compilation successful!");
                AgentResult testResult = verifier.execute(context);
                agentResults.add(testResult);
                
                // Code Review
                ConsoleUI.section("AI Code Review");
                AgentResult reviewResult = codeReviewer.execute(context);
                agentResults.add(reviewResult);
            } else {
                ConsoleUI.warn("Full project compilation failed -- entering self-healing");
                runCompileHealLoop();
            }
        }

        ConsoleUI.section("Module Migration Summary");
        printModuleSummaryTable();

        return failCount == 0;
    }

    /**
     * Migrate a single parent/aggregator module — update build file version only.
     */
    private boolean migrateParentModule(ModuleInfo module) {
        context.resetForNextModule();
        context.addBuildFile(module.getBuildFile());

        ConsoleUI.progress("Updating parent POM Java version...");

        // Run transformer on parent (will update Java version in POM)
        AgentResult transformResult = transformer.execute(context);
        agentResults.add(transformResult);

        context.getReport().addAction("Updated parent module " + module.getName() +
                " Java version to " + context.getTargetVersion());
        return true;
    }

    /**
     * Full migration pipeline for a single child module.
     */
    private boolean migrateChildModule(ModuleInfo module) {
        ConsoleUI.progress("Migrating module: " + module.getName() + " @ " + module.getPath());

        // Dependency Analysis for this module
        ConsoleUI.agentStart("Dependency Analysis [" + module.getName() + "]");
        AgentResult depResult = dependencyAnalyzer.execute(context);
        agentResults.add(depResult);

        // Check for blocker dependencies
        if (!context.getBlockedDependencies().isEmpty()) {
            ConsoleUI.error("Module " + module.getName() +
                    " has tightly coupled dependencies!");
            context.getBlockedDependencies().forEach(dep ->
                    ConsoleUI.error("  BLOCKER: " + dep.getCoordinate()));
        }

        // Source Code Analysis
        ConsoleUI.agentStart("Source Analysis [" + module.getName() + "]");
        AgentResult srcResult = sourceAnalyzer.execute(context);
        agentResults.add(srcResult);
        module.setFilesAnalyzed(context.getJavaSourceFiles().size());
        module.setIssuesFound(srcResult.getIssuesFound() + depResult.getIssuesFound());

        // Transformation
        ConsoleUI.agentStart("Transformation [" + module.getName() + "]");
        AgentResult transformResult = transformer.execute(context);
        agentResults.add(transformResult);
        module.setIssuesFixed(transformResult.getIssuesFixed());

        // Compile + Self-Heal
        boolean compileSuccess = runCompileHealLoop();
        module.setFilesModified(context.getModifiedFiles().size());

        return compileSuccess;
    }

    /**
     * Analyze a parent module (read-only).
     */
    private void analyzeParentModule(ModuleInfo module) {
        ConsoleUI.section("Parent Module: " + module.getName());
        context.setCurrentModule(module);
        context.resetForNextModule();
        context.addBuildFile(module.getBuildFile());
        dependencyAnalyzer.execute(context);
        ConsoleUI.info("Child modules: " + String.join(", ", module.getChildModuleNames()));
    }

    /**
     * Analyze a child module (read-only).
     */
    private void analyzeChildModule(ModuleInfo module) {
        ConsoleUI.section("Module: " + module.getName());
        context.setCurrentModule(module);
        context.resetForNextModule();

        dependencyAnalyzer.execute(context);
        sourceAnalyzer.execute(context);
        module.setFilesAnalyzed(context.getJavaSourceFiles().size());
        module.setIssuesFound(context.getIssues().size());
    }

    /**
     * Check that all modules this one depends on have been migrated successfully.
     */
    private boolean checkModuleDependenciesReady(ModuleInfo module) {
        for (String depName : module.getDependsOnModules()) {
            for (ModuleInfo other : context.getDiscoveredModules()) {
                if (other.getName().equals(depName)) {
                    if (other.getStatus() != ModuleInfo.ModuleStatus.SUCCESS &&
                        other.getStatus() != ModuleInfo.ModuleStatus.PARTIAL_SUCCESS) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Single-Module Migration Pipeline (original flow)
    // ═══════════════════════════════════════════════════════════════════

    private boolean executeSingleModuleMigration() {
        // Dependency Analysis
        ConsoleUI.section("Phase 3: Dependency Analysis");
        AgentResult depResult = dependencyAnalyzer.execute(context);
        agentResults.add(depResult);

        if (!context.getBlockedDependencies().isEmpty()) {
            ConsoleUI.error("Found " + context.getBlockedDependencies().size() +
                    " tightly coupled dependencies that cannot be migrated!");
            context.getBlockedDependencies().forEach(dep ->
                    ConsoleUI.error("  BLOCKER: " + dep.getCoordinate() + " -- " + dep.getNotes()));
            ConsoleUI.warn("Migration will proceed, but these dependencies need manual resolution.");
        }

        // Source Code Analysis
        ConsoleUI.section("Phase 4: Source Code Analysis");
        AgentResult srcResult = sourceAnalyzer.execute(context);
        agentResults.add(srcResult);

        // Transformation
        ConsoleUI.section("Phase 5: Code Transformation");
        AgentResult transformResult = transformer.execute(context);
        agentResults.add(transformResult);

        // Compile + Self-Healing Loop
        ConsoleUI.section("Phase 6: Compilation & Self-Healing");
        boolean compilationSuccess = runCompileHealLoop();

        // Verification
        if (compilationSuccess && !context.getConfig().isDryRun()) {
            ConsoleUI.section("Phase 7: Verification");
            AgentResult verifyResult = verifier.execute(context);
            agentResults.add(verifyResult);
            
            // Code Review
            ConsoleUI.section("Phase 8: AI Code Review");
            AgentResult reviewResult = codeReviewer.execute(context);
            agentResults.add(reviewResult);
        }

        return compilationSuccess && context.getReport().getBlockers().isEmpty();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Self-Healing Compilation Loop
    // ═══════════════════════════════════════════════════════════════════

    private boolean runCompileHealLoop() {
        for (int i = 0; i <= context.getConfig().getMaxRetries(); i++) {
            AgentResult compileResult = compiler.execute(context);
            agentResults.add(compileResult);

            if (compileResult.isSuccess()) {
                ConsoleUI.success("Compilation successful on attempt " + (i + 1) + "!");
                return true;
            }
            if (compileResult.getStatus() == AgentResult.Status.SKIPPED) {
                return true; // dry run
            }
            if (!context.canRetry()) {
                ConsoleUI.error("Max self-healing retries (" + context.getConfig().getMaxRetries() +
                        ") exhausted. Compilation still failing.");
                return false;
            }

            AgentResult healResult = healer.execute(context);
            agentResults.add(healResult);

            if (healResult.getIssuesFixed() == 0) {
                ConsoleUI.error("Self-healing could not fix any more errors. Stopping.");
                return false;
            }

            ConsoleUI.info("Fixed " + healResult.getIssuesFixed() + " issues. Retrying compilation...");
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Reporting Helpers
    // ═══════════════════════════════════════════════════════════════════

    private void printModuleSummaryTable() {
        String header = String.format("  %-25s %-15s %8s %8s %8s %8s",
                "MODULE", "STATUS", "FILES", "MODIFIED", "ISSUES", "FIXED");
        ConsoleUI.info(header);
        ConsoleUI.info("  " + "-".repeat(80));

        for (ModuleInfo m : context.getDiscoveredModules()) {
            String statusIcon = switch (m.getStatus()) {
                case SUCCESS -> "[OK]";
                case PARTIAL_SUCCESS -> "[!]";
                case FAILED -> "[X]";
                case SKIPPED -> "[>>]";
                case IN_PROGRESS -> "[LOOP]";
                default -> "[...]";
            };
            String row = String.format("  %-25s %s %-12s %8d %8d %8d %8d",
                    m.getName(),
                    statusIcon,
                    m.getStatus().name(),
                    m.getFilesAnalyzed(),
                    m.getFilesModified(),
                    m.getIssuesFound(),
                    m.getIssuesFixed());
            ConsoleUI.info(row);
        }
    }

    private void addModuleSummaryToReport() {
        context.getReport().addAction("=== MODULE SUMMARY ===");
        for (ModuleInfo m : context.getDiscoveredModules()) {
            String line = String.format("[%s] %s -- files: %d, modified: %d, issues: %d, fixed: %d",
                    m.getStatus(), m.getName(),
                    m.getFilesAnalyzed(), m.getFilesModified(),
                    m.getIssuesFound(), m.getIssuesFixed());
            context.getReport().addAction(line);
            if (m.getStatusMessage() != null && !m.getStatusMessage().isBlank()) {
                context.getReport().addAction("  -> " + m.getStatusMessage());
            }
        }
    }
}
