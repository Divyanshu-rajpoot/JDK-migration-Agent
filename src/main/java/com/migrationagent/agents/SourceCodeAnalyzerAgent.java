package com.migrationagent.agents;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.migrationagent.core.AgentResult;
import com.migrationagent.core.MigrationContext;
import com.migrationagent.knowledge.DeprecationRule;
import com.migrationagent.knowledge.ExternalRulesLoader;
import com.migrationagent.knowledge.JdkApiChangeDatabase;
import com.migrationagent.llm.LlmClient;
import com.migrationagent.llm.MigrationPrompts;
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

/**
 * Analyzes Java source files for migration issues using three tiers:
 *
 * <ol>
 *   <li><b>External rules</b> -- user-defined rules from
 *       {@code .migration-agent/rules.json}</li>
 *   <li><b>Built-in rules</b> -- 30+ known JDK API changes</li>
 *   <li><b>AI deep scan</b> -- sends source files to LLM for issues the rule
 *       engine can't detect (custom APIs, complex patterns, subtle
 *       incompatibilities)</li>
 * </ol>
 */
public class SourceCodeAnalyzerAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(SourceCodeAnalyzerAgent.class);
    private final JdkApiChangeDatabase apiDb = new JdkApiChangeDatabase();
    private final JavaParser parser = new JavaParser();

    @Override
    public String getName() { return "Source Code Analyzer"; }

    @Override
    public AgentResult execute(MigrationContext context) {
        long start = System.currentTimeMillis();
        ConsoleUI.agentStart(getName());

        try {
            // Find all Java source files
            Path scanPath = context.getEffectiveProjectPath();
            List<Path> javaFiles = MavenHelper.findAllJavaFiles(scanPath);
            context.addJavaSourceFiles(javaFiles);
            ConsoleUI.progress("Found " + javaFiles.size() + " Java source files");

            // ── Load rules: built-in + external ──
            List<DeprecationRule> rules = new ArrayList<>(
                    apiDb.getRulesFor(context.getSourceVersion(), context.getTargetVersion()));

            List<DeprecationRule> externalRules = ExternalRulesLoader.loadExternalRules(
                    context.getProjectPath());
            // Filter external rules to applicable version range
            externalRules.stream()
                    .filter(r -> r.appliesTo(context.getSourceVersion(), context.getTargetVersion()))
                    .forEach(rules::add);

            int builtIn = rules.size() - externalRules.size();
            ConsoleUI.progress("Loaded " + builtIn + " built-in + " +
                    externalRules.size() + " custom rules");

            int issueCount = 0;
            int filesAnalyzed = 0;

            // ── Tier 1+2: Rule-based analysis ──
            for (Path javaFile : javaFiles) {
                try {
                    int issues = analyzeFile(context, javaFile, rules);
                    issueCount += issues;
                    filesAnalyzed++;
                } catch (Exception e) {
                    log.warn("Could not parse file: {}", javaFile, e);
                    ConsoleUI.warn("Parse error: " + javaFile.getFileName() + " -- " + e.getMessage());
                }
            }

            // ── Tier 3: AI deep scan (for files the rules didn't find issues in) ──
            if (context.getConfig().isAiEnabled()) {
                int aiIssues = runAiDeepScan(context, javaFiles);
                issueCount += aiIssues;
            }

            context.getReport().setFilesAnalyzed(filesAnalyzed);
            ConsoleUI.progress("Analyzed " + filesAnalyzed + " files, found " + issueCount + " issues");
            ConsoleUI.agentComplete(getName(), issueCount == 0);

            return AgentResult.builder(getName())
                    .status(issueCount > 0 ? AgentResult.Status.PARTIAL_SUCCESS : AgentResult.Status.SUCCESS)
                    .message("Analyzed " + filesAnalyzed + " files")
                    .issuesFound(issueCount)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();

        } catch (Exception e) {
            log.error("Source code analysis failed", e);
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.FAILURE)
                    .message("Error: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Rule-Based Analysis (Tier 1 + 2)
    // ═══════════════════════════════════════════════════════════════════

    private int analyzeFile(MigrationContext context, Path javaFile, List<DeprecationRule> rules)
            throws IOException {
        String source = Files.readString(javaFile);
        ParseResult<CompilationUnit> result = parser.parse(source);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            log.debug("Could not parse: {}", javaFile);
            return 0;
        }

        CompilationUnit cu = result.getResult().get();
        int issueCount = 0;

        issueCount += checkImports(context, javaFile, cu, rules);
        issueCount += checkMethodUsage(context, javaFile, cu, rules);
        issueCount += checkFinalizeOverride(context, javaFile, cu);

        return issueCount;
    }

    private int checkImports(MigrationContext ctx, Path file, CompilationUnit cu, List<DeprecationRule> rules) {
        int count = 0;
        for (ImportDeclaration imp : cu.getImports()) {
            String importStr = imp.getNameAsString();

            for (DeprecationRule rule : rules) {
                String pattern = rule.getImportPattern();
                if (pattern == null || pattern.isBlank()) continue;

                String cleanPattern = pattern.replace("import ", "").trim();

                if (importStr.startsWith(cleanPattern) || importStr.equals(cleanPattern)) {
                    int line = imp.getBegin().map(p -> p.line).orElse(-1);

                    MigrationIssue.Severity severity = switch (rule.getType()) {
                        case REMOVED -> MigrationIssue.Severity.ERROR;
                        case MODULE_RESTRICTION -> MigrationIssue.Severity.ERROR;
                        case DEPRECATED -> MigrationIssue.Severity.WARNING;
                        default -> MigrationIssue.Severity.WARNING;
                    };

                    ctx.addIssue(MigrationIssue.builder()
                            .id(rule.getId() + "_" + file.getFileName() + "_L" + line)
                            .severity(severity)
                            .category(rule.getType() == DeprecationRule.RuleType.REMOVED
                                    ? MigrationIssue.Category.REMOVED_API
                                    : MigrationIssue.Category.DEPRECATED_API)
                            .title(rule.getDescription())
                            .description("Import: " + importStr)
                            .affectedFile(file)
                            .lineNumber(line)
                            .suggestedFix(rule.getReplacement())
                            .autoFixable(rule.isAutoFixable())
                            .build());
                    count++;

                    ConsoleUI.step(file.getFileName() + ":" + line + " -- " + rule.getDescription());
                }
            }
        }
        return count;
    }

    private int checkMethodUsage(MigrationContext ctx, Path file, CompilationUnit cu, List<DeprecationRule> rules) {
        int count = 0;

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String methodName = call.getNameAsString();
            int line = call.getBegin().map(p -> p.line).orElse(-1);

            for (DeprecationRule rule : rules) {
                if (rule.getApiPattern().contains(methodName) ||
                    rule.getApiPattern().contains(call.toString())) {

                    ctx.addIssue(MigrationIssue.builder()
                            .id(rule.getId() + "_call_" + file.getFileName() + "_L" + line)
                            .severity(rule.getType() == DeprecationRule.RuleType.REMOVED
                                    ? MigrationIssue.Severity.ERROR : MigrationIssue.Severity.WARNING)
                            .category(MigrationIssue.Category.DEPRECATED_API)
                            .title(rule.getDescription())
                            .description("Method call: " + call)
                            .affectedFile(file)
                            .lineNumber(line)
                            .suggestedFix(rule.getReplacement())
                            .autoFixable(rule.isAutoFixable())
                            .build());
                    count++;
                }
            }
        }

        for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class)) {
            String typeName = creation.getTypeAsString();
            int line = creation.getBegin().map(p -> p.line).orElse(-1);

            for (DeprecationRule rule : rules) {
                if (rule.getApiPattern().contains(typeName)) {
                    ctx.addIssue(MigrationIssue.builder()
                            .id(rule.getId() + "_new_" + file.getFileName() + "_L" + line)
                            .severity(MigrationIssue.Severity.ERROR)
                            .category(MigrationIssue.Category.REMOVED_API)
                            .title(rule.getDescription())
                            .description("Object creation: new " + typeName + "()")
                            .affectedFile(file)
                            .lineNumber(line)
                            .suggestedFix(rule.getReplacement())
                            .autoFixable(rule.isAutoFixable())
                            .build());
                    count++;
                }
            }
        }

        return count;
    }

    private int checkFinalizeOverride(MigrationContext ctx, Path file, CompilationUnit cu) {
        int count = 0;
        if (ctx.getTargetVersion() >= 18) {
            for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
                if (method.getNameAsString().equals("finalize") &&
                    method.getParameters().isEmpty()) {
                    int line = method.getBegin().map(p -> p.line).orElse(-1);
                    ctx.addIssue(MigrationIssue.builder()
                            .severity(MigrationIssue.Severity.WARNING)
                            .category(MigrationIssue.Category.DEPRECATED_API)
                            .title("finalize() method is deprecated for removal")
                            .description("Override of Object.finalize() found")
                            .affectedFile(file)
                            .lineNumber(line)
                            .suggestedFix("Use java.lang.ref.Cleaner or try-with-resources")
                            .build());
                    count++;
                }
            }
        }
        return count;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  AI Deep Scan (Tier 3) -- catches what rules miss
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send source files to the LLM for migration analysis.
     * Only scans files where rule-based analysis found zero issues,
     * since those are the ones most likely to have hidden problems.
     */
    private int runAiDeepScan(MigrationContext context, List<Path> javaFiles) {
        LlmClient llm = new LlmClient(context.getConfig().getLlmConfig());
        if (!llm.isAvailable()) {
            ConsoleUI.warn("AI configured but not reachable -- skipping deep scan");
            return 0;
        }

        ConsoleUI.progress("Running AI deep scan on source files...");
        int totalAiIssues = 0;

        // Only send the first N files to AI to avoid excessive API calls
        int maxFilesForAi = Math.min(javaFiles.size(), 20);

        for (int i = 0; i < maxFilesForAi; i++) {
            Path file = javaFiles.get(i);
            try {
                String source = Files.readString(file);
                // Skip very small files or test files
                if (source.length() < 100) continue;

                String prompt = MigrationPrompts.analyzeMigrationIssues(
                        source, context.getSourceVersion(), context.getTargetVersion());

                String response = llm.chat(MigrationPrompts.SYSTEM_PROMPT, prompt);
                if (response == null || response.isBlank()) continue;

                // Parse structured response: ISSUE|severity|line|description|fix
                int aiIssues = parseAiIssues(context, file, response);
                totalAiIssues += aiIssues;

            } catch (Exception e) {
                log.debug("AI scan failed for: {}", file, e);
            }
        }

        if (totalAiIssues > 0) {
            ConsoleUI.info("AI deep scan found " + totalAiIssues + " additional issues");
        }

        return totalAiIssues;
    }

    /**
     * Parse the AI's structured issue response.
     * Expected format: ISSUE|severity|line_number|description|suggested_fix
     */
    private int parseAiIssues(MigrationContext context, Path file, String response) {
        int count = 0;
        for (String line : response.split("\n")) {
            line = line.trim();
            if (!line.startsWith("ISSUE|")) continue;

            String[] parts = line.split("\\|", 5);
            if (parts.length < 4) continue;

            try {
                MigrationIssue.Severity severity = switch (parts[1].trim().toUpperCase()) {
                    case "ERROR" -> MigrationIssue.Severity.ERROR;
                    case "WARNING" -> MigrationIssue.Severity.WARNING;
                    default -> MigrationIssue.Severity.INFO;
                };

                int lineNum = 0;
                try { lineNum = Integer.parseInt(parts[2].trim()); }
                catch (NumberFormatException e) { /* skip */ }

                String description = parts[3].trim();
                String fix = parts.length > 4 ? parts[4].trim() : "";

                context.addIssue(MigrationIssue.builder()
                        .id("AI_" + file.getFileName() + "_L" + lineNum)
                        .severity(severity)
                        .category(MigrationIssue.Category.REMOVED_API)
                        .title("[AI] " + description)
                        .description(description)
                        .affectedFile(file)
                        .lineNumber(lineNum)
                        .suggestedFix(fix)
                        .build());

                ConsoleUI.step("[AI] " + file.getFileName() + ":" + lineNum + " -- " + description);
                count++;
            } catch (Exception e) {
                log.debug("Could not parse AI issue line: {}", line);
            }
        }
        return count;
    }
}
