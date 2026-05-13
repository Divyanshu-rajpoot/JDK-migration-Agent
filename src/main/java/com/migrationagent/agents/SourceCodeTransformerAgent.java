package com.migrationagent.agents;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.migrationagent.core.AgentResult;
import com.migrationagent.core.MigrationContext;
import com.migrationagent.knowledge.DeprecationRule;
import com.migrationagent.knowledge.ExternalRulesLoader;
import com.migrationagent.knowledge.JdkApiChangeDatabase;
import com.migrationagent.llm.LlmClient;
import com.migrationagent.llm.MigrationPrompts;
import com.migrationagent.model.DependencyInfo;
import com.migrationagent.model.MigrationIssue;
import com.migrationagent.util.ConsoleUI;
import com.migrationagent.util.MavenHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Transforms source code and build files to be compatible with the target JDK.
 *
 * <h3>Transform strategy:</h3>
 * <ol>
 *   <li><b>Build file versions</b> — update Java version in pom.xml/build.gradle</li>
 *   <li><b>Dependency versions</b> — update library versions from compatibility data</li>
 *   <li><b>Rule-based AST transforms</b> — apply known code fixes (built-in + external)</li>
 *   <li><b>AI transforms</b> — for files with unresolved issues, ask AI for the fixed code</li>
 * </ol>
 */
public class SourceCodeTransformerAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(SourceCodeTransformerAgent.class);
    private final JdkApiChangeDatabase apiDb = new JdkApiChangeDatabase();
    private final JavaParser parser = new JavaParser();

    @Override
    public String getName() { return "Source Code Transformer"; }

    @Override
    public AgentResult execute(MigrationContext context) {
        long start = System.currentTimeMillis();
        ConsoleUI.agentStart(getName());

        if (context.getConfig().isDryRun()) {
            ConsoleUI.warn("DRY RUN — skipping transformations");
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.SKIPPED)
                    .message("Dry run mode")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        int fixCount = 0;

        try {
            // 1. Update build file Java version
            fixCount += updateBuildFileVersions(context);

            // 2. Update dependency versions
            fixCount += updateDependencyVersions(context);

            // 3. Rule-based source transforms (built-in + external)
            fixCount += transformSourceFilesWithRules(context);

            // 4. AI-powered transforms for files with remaining issues
            if (context.getConfig().isAiEnabled()) {
                fixCount += transformSourceFilesWithAi(context);
            }

            ConsoleUI.agentComplete(getName(), true);
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.SUCCESS)
                    .message("Applied " + fixCount + " transformations")
                    .issuesFixed(fixCount)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();

        } catch (Exception e) {
            log.error("Transformation failed", e);
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.FAILURE)
                    .message("Error: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Build file updates
    // ═══════════════════════════════════════════════════════════════════

    private int updateBuildFileVersions(MigrationContext context) throws IOException {
        int count = 0;
        for (Path buildFile : context.getBuildFiles()) {
            if (buildFile.getFileName().toString().equals("pom.xml")) {
                String content = Files.readString(buildFile);
                String updated = MavenHelper.updatePomJavaVersion(content, context.getTargetVersion());
                if (!content.equals(updated)) {
                    Files.writeString(buildFile, updated);
                    context.markModified(buildFile);
                    context.getReport().addAction("Updated Java version to " +
                            context.getTargetVersion() + " in " + buildFile.getFileName());
                    ConsoleUI.step("Updated Java version in " + buildFile);
                    count++;
                }
            }
        }
        return count;
    }

    private int updateDependencyVersions(MigrationContext context) throws IOException {
        int count = 0;
        List<DependencyInfo> needsUpgrade = context.getDependencies().stream()
                .filter(d -> d.getStatus() == DependencyInfo.CompatibilityStatus.NEEDS_UPGRADE)
                .toList();

        if (needsUpgrade.isEmpty()) return 0;

        for (Path buildFile : context.getBuildFiles()) {
            if (!buildFile.getFileName().toString().equals("pom.xml")) continue;

            String content = Files.readString(buildFile);
            boolean changed = false;

            for (DependencyInfo dep : needsUpgrade) {
                if (dep.getRecommendedVersion() == null) continue;

                String updated = MavenHelper.updateDependencyVersion(
                        content, dep.getGroupId(), dep.getArtifactId(), dep.getRecommendedVersion());

                if (!content.equals(updated)) {
                    content = updated;
                    changed = true;
                    count++;
                    context.getReport().addDependencyChange(dep.getCoordinate(), dep.getRecommendedVersion());
                    context.getReport().addAction("Updated " + dep.getCoordinate() +
                            " → " + dep.getRecommendedVersion());
                    ConsoleUI.step("Updated " + dep.getArtifactId() + " → " + dep.getRecommendedVersion());
                }
            }

            if (changed) {
                Files.writeString(buildFile, content);
                context.markModified(buildFile);
            }
        }

        return count;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Rule-Based Transforms (built-in + external)
    // ═══════════════════════════════════════════════════════════════════

    private int transformSourceFilesWithRules(MigrationContext context) {
        // Merge built-in + external auto-fixable rules
        List<DeprecationRule> autoFixRules = new ArrayList<>(
                apiDb.getAutoFixableRules(context.getSourceVersion(), context.getTargetVersion()));

        List<DeprecationRule> externalRules = ExternalRulesLoader.loadExternalRules(
                context.getProjectPath());
        externalRules.stream()
                .filter(DeprecationRule::isAutoFixable)
                .filter(r -> r.appliesTo(context.getSourceVersion(), context.getTargetVersion()))
                .forEach(autoFixRules::add);

        if (autoFixRules.isEmpty()) {
            ConsoleUI.progress("No auto-fixable rules apply for this migration path.");
            return 0;
        }

        ConsoleUI.progress("Applying " + autoFixRules.size() + " auto-fix rules...");

        int totalFixes = 0;
        for (Path javaFile : context.getJavaSourceFiles()) {
            try {
                int fixes = transformFileWithRules(context, javaFile, autoFixRules);
                totalFixes += fixes;
            } catch (Exception e) {
                log.warn("Could not transform: {}", javaFile, e);
            }
        }

        return totalFixes;
    }

    private int transformFileWithRules(MigrationContext ctx, Path file, List<DeprecationRule> rules)
            throws IOException {
        String source = Files.readString(file);
        ParseResult<CompilationUnit> result = parser.parse(source);

        if (!result.isSuccessful() || result.getResult().isEmpty()) return 0;

        CompilationUnit cu = result.getResult().get();
        int fixes = 0;
        boolean modified = false;

        for (DeprecationRule rule : rules) {
            if (rule.getImportPattern() == null || rule.getImportPattern().isBlank()) continue;

            String oldImport = rule.getImportPattern().replace("import ", "").trim();

            for (ImportDeclaration imp : cu.getImports()) {
                if (imp.getNameAsString().startsWith(oldImport)) {
                    if (rule.getReplacementImport() != null && !rule.getReplacementImport().isBlank()) {
                        String newImport = rule.getReplacementImport().replace("import ", "").trim();
                        imp.setName(newImport);
                        modified = true;
                        fixes++;

                        fixes += applyCodeTransformation(cu, rule);

                        ctx.getReport().addAction("Replaced import " + oldImport +
                                " → " + newImport + " in " + file.getFileName());
                    }
                }
            }
        }

        if (modified) {
            ctx.addOriginalSource(file.toAbsolutePath().toString(), source);
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
            String transformed = printer.print(cu);
            Files.writeString(file, transformed);
            ctx.markModified(file);

            ctx.getIssues().stream()
                    .filter(i -> i.getAffectedFile() != null && i.getAffectedFile().equals(file))
                    .filter(MigrationIssue::isAutoFixable)
                    .forEach(MigrationIssue::markResolved);
        }

        return fixes;
    }

    private int applyCodeTransformation(CompilationUnit cu, DeprecationRule rule) {
        int fixes = 0;

        switch (rule.getId()) {
            case "SUN_BASE64_ENCODER" -> {
                for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
                    if (creation.getTypeAsString().equals("BASE64Encoder")) {
                        MethodCallExpr replacement = new MethodCallExpr(
                                new MethodCallExpr(new NameExpr("Base64"), "getEncoder"),
                                "encodeToString"
                        );
                        creation.getParentNode().ifPresent(parent -> {
                            if (parent instanceof MethodCallExpr mce) {
                                replacement.setArguments(mce.getArguments());
                                mce.replace(replacement);
                            }
                        });
                        fixes++;
                    }
                }
            }
            case "SUN_BASE64_DECODER" -> {
                for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
                    if (creation.getTypeAsString().equals("BASE64Decoder")) {
                        MethodCallExpr replacement = new MethodCallExpr(
                                new MethodCallExpr(new NameExpr("Base64"), "getDecoder"),
                                "decode"
                        );
                        creation.getParentNode().ifPresent(parent -> {
                            if (parent instanceof MethodCallExpr mce) {
                                replacement.setArguments(mce.getArguments());
                                mce.replace(replacement);
                            }
                        });
                        fixes++;
                    }
                }
            }
            case "JAXB_DATATYPECONVERTER" -> {
                for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
                    if (call.getNameAsString().equals("printBase64Binary")) {
                        MethodCallExpr replacement = new MethodCallExpr(
                                new MethodCallExpr(new NameExpr("Base64"), "getEncoder"),
                                "encodeToString"
                        );
                        replacement.setArguments(call.getArguments());
                        call.replace(replacement);
                        fixes++;
                    } else if (call.getNameAsString().equals("parseBase64Binary")) {
                        MethodCallExpr replacement = new MethodCallExpr(
                                new MethodCallExpr(new NameExpr("Base64"), "getDecoder"),
                                "decode"
                        );
                        replacement.setArguments(call.getArguments());
                        call.replace(replacement);
                        fixes++;
                    }
                }
            }
        }

        return fixes;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  AI-Powered Transforms (for files with unresolved issues)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * For files that still have unresolved issues after rule-based transforms,
     * send them to the LLM for intelligent rewriting.
     */
    private int transformSourceFilesWithAi(MigrationContext context) {
        LlmClient llm = new LlmClient(context.getConfig().getLlmConfig());
        if (!llm.isAvailable()) {
            ConsoleUI.warn("AI configured but not reachable — skipping AI transforms");
            return 0;
        }

        // Find files that still have unresolved auto-fixable issues
        List<Path> filesWithIssues = context.getIssues().stream()
                .filter(i -> !i.isResolved() && i.isAutoFixable())
                .map(MigrationIssue::getAffectedFile)
                .filter(f -> f != null)
                .distinct()
                .toList();

        if (filesWithIssues.isEmpty()) return 0;

        ConsoleUI.progress("AI transforming " + filesWithIssues.size() + " files with unresolved issues...");
        int totalFixes = 0;

        for (Path file : filesWithIssues) {
            try {
                int fixes = transformFileWithAi(context, file, llm);
                totalFixes += fixes;
            } catch (Exception e) {
                log.warn("AI transform failed for: {}", file, e);
            }
        }

        return totalFixes;
    }

    private int transformFileWithAi(MigrationContext context, Path file, LlmClient llm)
            throws IOException {
        String source = Files.readString(file);

        // Gather unresolved issues for this file
        List<MigrationIssue> fileIssues = context.getIssues().stream()
                .filter(i -> file.equals(i.getAffectedFile()) && !i.isResolved())
                .toList();

        StringBuilder issuesSummary = new StringBuilder();
        for (MigrationIssue issue : fileIssues) {
            issuesSummary.append(String.format("Line %d: %s — %s%n",
                    issue.getLineNumber(), issue.getTitle(), issue.getDescription()));
        }

        ConsoleUI.progress("AI transforming: " + file.getFileName() + " (" + fileIssues.size() + " issues)...");

        String prompt = MigrationPrompts.fixMultipleErrors(
                source, issuesSummary.toString(),
                context.getSourceVersion(), context.getTargetVersion());

        String response = llm.chat(MigrationPrompts.SYSTEM_PROMPT, prompt);
        if (response == null || response.isBlank() || response.contains("NO_FIX_AVAILABLE")) {
            return 0;
        }

        // Extract and validate code
        String fixedCode = extractJavaCode(response);
        ParseResult<CompilationUnit> parseCheck = parser.parse(fixedCode);
        if (!parseCheck.isSuccessful()) {
            ConsoleUI.warn("AI-generated code for " + file.getFileName() + " failed to parse. Skipping.");
            return 0;
        }

        // Apply fix
        Files.writeString(file, fixedCode);
        context.markModified(file);

        // Mark issues as resolved
        fileIssues.forEach(MigrationIssue::markResolved);

        ConsoleUI.success("[AI] Transformed " + file.getFileName() + " (" + fileIssues.size() + " issues)");
        context.getReport().addAction("[AI] Transformed " + file.getFileName() +
                " — " + fileIssues.size() + " issues resolved");

        return fileIssues.size();
    }

    private String extractJavaCode(String response) {
        java.util.regex.Matcher m = Pattern.compile("```(?:java)?\\s*\\n(.*?)```", Pattern.DOTALL)
                .matcher(response);
        if (m.find()) {
            return m.group(1).trim();
        }
        return response.trim();
    }
}
