package com.migrationagent.agents;

import com.migrationagent.core.AgentResult;
import com.migrationagent.core.MigrationContext;
import com.migrationagent.knowledge.CompatibilityMatrix;
import com.migrationagent.knowledge.CompatibilityMatrix.LibraryCompat;
import com.migrationagent.knowledge.ExternalRulesLoader;
import com.migrationagent.llm.LlmClient;
import com.migrationagent.llm.MigrationPrompts;
import com.migrationagent.model.DependencyInfo;
import com.migrationagent.model.MigrationIssue;
import com.migrationagent.util.ConsoleUI;
import com.migrationagent.util.MavenCentralClient;
import com.migrationagent.util.MavenHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes project dependencies and checks compatibility with the target JDK.
 *
 * <h3>Three-tier lookup for each dependency:</h3>
 * <ol>
 *   <li><b>External rules</b> — user-defined JSON
 *       ({@code .migration-agent/dependencies.json})</li>
 *   <li><b>Built-in matrix</b> — hardcoded data for 40+ popular libraries</li>
 *   <li><b>AI lookup</b> — asks the LLM for compatible version (if AI enabled)</li>
 * </ol>
 *
 * <p>This means even internal/proprietary libraries get handled — either through
 * user-provided rules or through AI analysis.</p>
 */
public class DependencyAnalyzerAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DependencyAnalyzerAgent.class);
    private final CompatibilityMatrix compatMatrix = new CompatibilityMatrix();
    private final MavenCentralClient mavenClient = new MavenCentralClient();

    @Override
    public String getName() { return "Dependency Analyzer"; }

    @Override
    public AgentResult execute(MigrationContext context) {
        long start = System.currentTimeMillis();
        ConsoleUI.agentStart(getName());

        try {
            // Load external dependency compatibility data
            List<LibraryCompat> externalDeps = ExternalRulesLoader.loadExternalDependencies(
                    context.getProjectPath());
            if (!externalDeps.isEmpty()) {
                ConsoleUI.progress("Loaded " + externalDeps.size() + " custom dependency rules");
                externalDeps.forEach(d -> compatMatrix.addExternal(d));
            }

            MavenHelper.BuildSystem buildSystem = MavenHelper.detectBuildSystem(
                    context.getEffectiveProjectPath());
            ConsoleUI.progress("Detected build system: " + buildSystem);

            List<DependencyInfo> deps;
            if (buildSystem == MavenHelper.BuildSystem.MAVEN) {
                deps = analyzeMavenDependencies(context);
            } else if (buildSystem == MavenHelper.BuildSystem.GRADLE) {
                deps = analyzeGradleDependencies(context);
            } else {
                ConsoleUI.warn("No recognized build system found. Skipping dependency analysis.");
                return AgentResult.builder(getName())
                        .status(AgentResult.Status.SKIPPED)
                        .message("No build system detected")
                        .durationMs(System.currentTimeMillis() - start)
                        .build();
            }

            context.addDependencies(deps);
            int issues = checkCompatibility(context, deps);

            ConsoleUI.agentComplete(getName(), issues == 0);
            return AgentResult.builder(getName())
                    .status(issues > 0 ? AgentResult.Status.PARTIAL_SUCCESS : AgentResult.Status.SUCCESS)
                    .message("Analyzed " + deps.size() + " dependencies, found " + issues + " issues")
                    .issuesFound(issues)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();

        } catch (Exception e) {
            log.error("Dependency analysis failed", e);
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.FAILURE)
                    .message("Error: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    private List<DependencyInfo> analyzeMavenDependencies(MigrationContext context) throws IOException {
        List<DependencyInfo> allDeps = new ArrayList<>();
        List<Path> pomFiles = MavenHelper.findPomFiles(context.getEffectiveProjectPath());

        for (Path pomFile : pomFiles) {
            context.addBuildFile(pomFile);
            String content = Files.readString(pomFile);
            List<DependencyInfo> deps = parseMavenDependencies(content);
            allDeps.addAll(deps);
            ConsoleUI.step("Found " + deps.size() + " dependencies in " + pomFile.getFileName());
        }

        return allDeps;
    }

    private List<DependencyInfo> parseMavenDependencies(String pomContent) {
        List<DependencyInfo> deps = new ArrayList<>();

        Pattern depPattern = Pattern.compile(
                "<dependency>\\s*" +
                "<groupId>([^<]+)</groupId>\\s*" +
                "<artifactId>([^<]+)</artifactId>\\s*" +
                "(?:<version>([^<]*)</version>)?",
                Pattern.DOTALL
        );

        Matcher matcher = depPattern.matcher(pomContent);
        while (matcher.find()) {
            String groupId = matcher.group(1).trim();
            String artifactId = matcher.group(2).trim();
            String version = matcher.group(3) != null ? matcher.group(3).trim() : "managed";

            if (version.startsWith("${")) {
                String propName = version.substring(2, version.length() - 1);
                String resolved = resolveProperty(pomContent, propName);
                if (resolved != null) version = resolved;
            }

            deps.add(new DependencyInfo(groupId, artifactId, version));
        }

        return deps;
    }

    private String resolveProperty(String pomContent, String propertyName) {
        Pattern propPattern = Pattern.compile(
                "<" + Pattern.quote(propertyName) + ">([^<]+)</" + Pattern.quote(propertyName) + ">");
        Matcher m = propPattern.matcher(pomContent);
        return m.find() ? m.group(1).trim() : null;
    }

    private List<DependencyInfo> analyzeGradleDependencies(MigrationContext context) throws IOException {
        List<DependencyInfo> allDeps = new ArrayList<>();
        List<Path> gradleFiles = MavenHelper.findGradleFiles(context.getEffectiveProjectPath());

        for (Path gradleFile : gradleFiles) {
            context.addBuildFile(gradleFile);
            String content = Files.readString(gradleFile);
            List<DependencyInfo> deps = parseGradleDependencies(content);
            allDeps.addAll(deps);
            ConsoleUI.step("Found " + deps.size() + " dependencies in " + gradleFile.getFileName());
        }

        return allDeps;
    }

    private List<DependencyInfo> parseGradleDependencies(String content) {
        List<DependencyInfo> deps = new ArrayList<>();

        Pattern gradleDepPattern = Pattern.compile(
                "(?:implementation|compile|api|testImplementation|testCompile|runtimeOnly|compileOnly)" +
                "\\s*['\"]([^:]+):([^:]+):([^'\"]+)['\"]"
        );

        Matcher matcher = gradleDepPattern.matcher(content);
        while (matcher.find()) {
            deps.add(new DependencyInfo(matcher.group(1), matcher.group(2), matcher.group(3)));
        }

        return deps;
    }

    /**
     * Three-tier compatibility check:
     *   1. External rules → 2. Built-in matrix → 3. AI fallback
     */
    private int checkCompatibility(MigrationContext context, List<DependencyInfo> deps) {
        int issueCount = 0;
        int targetVersion = context.getTargetVersion();
        LlmClient llm = null;

        // Initialize AI if available
        if (context.getConfig().isAiEnabled()) {
            llm = new LlmClient(context.getConfig().getLlmConfig());
            if (!llm.isAvailable()) llm = null;
        }

        int unknownCount = 0;

        for (DependencyInfo dep : deps) {
            // Skip inter-module dependencies (${project.version} etc.)
            if (dep.getCurrentVersion().contains("${") || dep.getCurrentVersion().equals("managed")) {
                dep.setStatus(DependencyInfo.CompatibilityStatus.UNKNOWN);
                ConsoleUI.step(dep.getCoordinate() + " -- MANAGED/VARIABLE (skipped)");
                continue;
            }

            Optional<LibraryCompat> compat = compatMatrix.lookup(dep.getGroupId(), dep.getArtifactId());

            if (compat.isPresent()) {
                // ── Known library: use matrix data ──
                issueCount += applyMatrixResult(context, dep, compat.get(), targetVersion);
            } else {
                // ── Tier 3: Query Maven Central API ──
                Optional<String> latestVersion = mavenClient.findLatestVersion(dep.getGroupId(), dep.getArtifactId());
                if (latestVersion.isPresent() && !latestVersion.get().equals(dep.getCurrentVersion())) {
                    String recVersion = latestVersion.get();
                    dep.setStatus(DependencyInfo.CompatibilityStatus.NEEDS_UPGRADE);
                    dep.setRecommendedVersion(recVersion);
                    dep.setNotes("Found latest version via Maven Central API: " + recVersion);

                    context.addIssue(MigrationIssue.builder()
                            .severity(MigrationIssue.Severity.WARNING)
                            .category(MigrationIssue.Category.INCOMPATIBLE_DEPENDENCY)
                            .title("Needs upgrade: " + dep.getCoordinate())
                            .description("Found newer version on Maven Central: " + recVersion)
                            .suggestedFix("Update version to " + recVersion)
                            .autoFixable(true)
                            .build());

                    ConsoleUI.step(dep.getCoordinate() + " -- NEEDS_UPGRADE -> " + recVersion + " (Maven API)");
                    issueCount++;
                } else if (llm != null) {
                    // ── Tier 4: Unknown library: ask AI ──
                    issueCount += askAiForDependency(context, dep, llm, targetVersion);
                } else {
                    // ── No data available ──
                    dep.setStatus(DependencyInfo.CompatibilityStatus.UNKNOWN);
                    unknownCount++;
                    ConsoleUI.step(dep.getCoordinate() + " -- UNKNOWN (no data, no Maven match, no AI)");
                }
            }
        }

        if (unknownCount > 0) {
            ConsoleUI.warn(unknownCount + " dependencies could not be checked. " +
                    "Add them to .migration-agent/dependencies.json or enable AI with --ai-provider");
        }

        return issueCount;
    }

    /**
     * Apply result from the built-in or external compatibility matrix.
     */
    private int applyMatrixResult(MigrationContext context, DependencyInfo dep,
                                   LibraryCompat lc, int targetVersion) {
        String recommended = lc.getRecommendedVersion(targetVersion);

        if (lc.tightlyCoupled() && recommended == null) {
            dep.setStatus(DependencyInfo.CompatibilityStatus.TIGHTLY_COUPLED);
            dep.setNotes("Tightly coupled to JDK " + lc.minJdkVersion() +
                    " -- cannot migrate to JDK " + targetVersion);
            context.addBlockedDependency(dep);

            context.addIssue(MigrationIssue.builder()
                    .severity(MigrationIssue.Severity.BLOCKER)
                    .category(MigrationIssue.Category.TIGHTLY_COUPLED_DEPENDENCY)
                    .title("Tightly coupled: " + dep.getCoordinate())
                    .description(dep.getNotes())
                    .suggestedFix("Find an alternative library or keep current JDK version")
                    .build());

            ConsoleUI.step(dep.getCoordinate() + " -- TIGHTLY_COUPLED");
            return 1;

        } else if (recommended != null && !recommended.equals(dep.getCurrentVersion())) {
            dep.setStatus(DependencyInfo.CompatibilityStatus.NEEDS_UPGRADE);
            dep.setRecommendedVersion(recommended);
            dep.setNotes("Upgrade to " + recommended + " for JDK " + targetVersion);

            context.addIssue(MigrationIssue.builder()
                    .severity(MigrationIssue.Severity.WARNING)
                    .category(MigrationIssue.Category.INCOMPATIBLE_DEPENDENCY)
                    .title("Needs upgrade: " + dep.getCoordinate())
                    .description("Upgrade to version " + recommended)
                    .suggestedFix("Update version to " + recommended)
                    .autoFixable(true)
                    .build());

            ConsoleUI.step(dep.getCoordinate() + " -- NEEDS_UPGRADE -> " + recommended);
            return 1;

        } else {
            dep.setStatus(DependencyInfo.CompatibilityStatus.COMPATIBLE);
            ConsoleUI.step(dep.getCoordinate() + " -- COMPATIBLE");
            return 0;
        }
    }

    /**
     * Ask the AI for the recommended version of an unknown dependency.
     * Parses structured response: VERSION|x.y.z|reason
     */
    private int askAiForDependency(MigrationContext context, DependencyInfo dep,
                                    LlmClient llm, int targetVersion) {
        ConsoleUI.progress("Asking AI about: " + dep.getCoordinate() + "...");

        String prompt = MigrationPrompts.suggestDependencyVersion(
                dep.getGroupId(), dep.getArtifactId(),
                dep.getCurrentVersion(), targetVersion);

        String response = llm.chat(MigrationPrompts.SYSTEM_PROMPT, prompt);
        if (response == null || response.isBlank()) {
            dep.setStatus(DependencyInfo.CompatibilityStatus.UNKNOWN);
            ConsoleUI.step(dep.getCoordinate() + " -- AI could not determine");
            return 0;
        }

        // Parse AI response
        String line = response.lines().filter(l -> l.startsWith("VERSION|") ||
                l.startsWith("COMPATIBLE|") || l.startsWith("REPLACED|"))
                .findFirst().orElse(response.trim());

        if (line.startsWith("COMPATIBLE|")) {
            dep.setStatus(DependencyInfo.CompatibilityStatus.COMPATIBLE);
            ConsoleUI.step(dep.getCoordinate() + " -- COMPATIBLE (AI verified)");
            return 0;

        } else if (line.startsWith("VERSION|")) {
            String[] parts = line.split("\\|", 3);
            if (parts.length >= 2) {
                String newVersion = parts[1].trim();
                dep.setStatus(DependencyInfo.CompatibilityStatus.NEEDS_UPGRADE);
                dep.setRecommendedVersion(newVersion);
                dep.setNotes("AI recommendation: " + (parts.length > 2 ? parts[2] : ""));

                context.addIssue(MigrationIssue.builder()
                        .severity(MigrationIssue.Severity.WARNING)
                        .category(MigrationIssue.Category.INCOMPATIBLE_DEPENDENCY)
                        .title("Needs upgrade: " + dep.getCoordinate())
                        .description("AI recommends: " + newVersion)
                        .suggestedFix("Update version to " + newVersion)
                        .autoFixable(true)
                        .build());

                ConsoleUI.step(dep.getCoordinate() + " -- NEEDS_UPGRADE -> " + newVersion + " (AI)");
                return 1;
            }

        } else if (line.startsWith("REPLACED|")) {
            String[] parts = line.split("\\|", 3);
            if (parts.length >= 2) {
                dep.setStatus(DependencyInfo.CompatibilityStatus.TIGHTLY_COUPLED);
                dep.setNotes("Library replaced: " + parts[1] +
                        (parts.length > 2 ? " -- " + parts[2] : ""));

                context.addIssue(MigrationIssue.builder()
                        .severity(MigrationIssue.Severity.WARNING)
                        .category(MigrationIssue.Category.INCOMPATIBLE_DEPENDENCY)
                        .title("Library replaced: " + dep.getCoordinate())
                        .description("Replace with: " + parts[1])
                        .suggestedFix("Replace with " + parts[1])
                        .build());

                ConsoleUI.step(dep.getCoordinate() + " -- REPLACED -> " + parts[1] + " (AI)");
                return 1;
            }
        }

        dep.setStatus(DependencyInfo.CompatibilityStatus.UNKNOWN);
        ConsoleUI.step(dep.getCoordinate() + " -- UNKNOWN (AI response unparseable)");
        return 0;
    }
}
