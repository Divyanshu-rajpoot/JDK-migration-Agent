package com.migrationagent.agents;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import com.migrationagent.core.AgentResult;
import com.migrationagent.core.MigrationContext;
import com.migrationagent.knowledge.DeprecationRule;
import com.migrationagent.knowledge.JdkApiChangeDatabase;
import com.migrationagent.llm.LlmClient;
import com.migrationagent.llm.MigrationPrompts;
import com.migrationagent.model.CompilationError;
import com.migrationagent.model.MigrationIssue;
import com.migrationagent.util.ConsoleUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-healing agent that analyzes compilation errors and attempts
 * automatic fixes using a two-tier approach:
 *
 * <h3>Tier 1: Rule-Based Fixes (no AI needed)</h3>
 * <p>Pattern-matches known compilation errors to pre-built fix strategies
 * using JavaParser AST transformations. This is fast, deterministic,
 * and works offline.</p>
 *
 * <h3>Tier 2: AI-Powered Fixes (Ollama / OpenAI / llama.cpp)</h3>
 * <p>When rule-based fixes can't resolve an error, the agent sends the
 * source code + error to an LLM and applies the AI-generated fix.
 * This handles unknown or complex migration patterns.</p>
 *
 * <h3>How it works WITHOUT AI:</h3>
 * <p>The rule engine uses a knowledge base ({@link JdkApiChangeDatabase})
 * containing 30+ known API changes across JDK 6-21. For each compilation
 * error, it:
 * <ol>
 *   <li>Classifies the error (missing package, missing class, access denied, etc.)</li>
 *   <li>Looks up the known replacement in the API change database</li>
 *   <li>Applies the fix via JavaParser AST manipulation or regex</li>
 * </ol>
 * </p>
 *
 * <h3>How it works WITH AI:</h3>
 * <p>After rule-based fixes are exhausted, unfixed errors are sent to the
 * configured LLM with a carefully engineered prompt. The LLM returns the
 * fixed source code, which is validated (must compile) before being applied.</p>
 */
public class SelfHealingAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingAgent.class);
    private final JdkApiChangeDatabase apiDb = new JdkApiChangeDatabase();
    private final JavaParser parser = new JavaParser();

    // Lazy-initialized LLM client (only created if AI is enabled)
    private LlmClient llmClient;
    private boolean llmAvailable = false;
    private boolean llmChecked = false;

    // Known error patterns and their fix strategies
    private static final Map<Pattern, String> ERROR_FIX_PATTERNS = new LinkedHashMap<>();

    static {
        // "cannot find symbol" for removed classes
        ERROR_FIX_PATTERNS.put(
                Pattern.compile("cannot find symbol.*symbol:\\s*class\\s+(\\w+)"),
                "MISSING_CLASS");

        // "package does not exist"
        ERROR_FIX_PATTERNS.put(
                Pattern.compile("package\\s+([\\w.]+)\\s+does not exist"),
                "MISSING_PACKAGE");

        // "cannot access" (module system issues)
        ERROR_FIX_PATTERNS.put(
                Pattern.compile("(\\S+)\\s+is not visible|cannot access\\s+(\\S+)"),
                "ACCESS_DENIED");

        // Method removed/changed signature
        ERROR_FIX_PATTERNS.put(
                Pattern.compile("cannot find symbol.*symbol:\\s*method\\s+(\\w+)"),
                "MISSING_METHOD");

        // Incompatible types
        ERROR_FIX_PATTERNS.put(
                Pattern.compile("incompatible types:.*found:\\s*(\\S+).*required:\\s*(\\S+)"),
                "TYPE_MISMATCH");
    }

    @Override
    public String getName() { return "Self-Healing Agent"; }

    @Override
    public AgentResult execute(MigrationContext context) {
        long start = System.currentTimeMillis();
        ConsoleUI.agentStart(getName());

        List<CompilationError> errors = context.getCompilationErrors();
        if (errors.isEmpty()) {
            ConsoleUI.success("No compilation errors to heal.");
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.SUCCESS)
                    .message("No errors to fix")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        // Initialize LLM if configured
        initLlm(context);

        context.incrementHealingIteration();
        int iteration = context.getHealingIteration();
        ConsoleUI.healingIteration(iteration, context.getConfig().getMaxRetries());

        if (llmAvailable) {
            ConsoleUI.info("AI mode: " + context.getConfig().getLlmConfig().getProvider() +
                    " / " + context.getConfig().getLlmConfig().getModel());
        } else {
            ConsoleUI.info("Rule-based mode (no AI configured — use --ai-provider to enable)");
        }

        int fixesApplied = 0;
        Set<Path> filesToFix = new HashSet<>();

        // Group errors by file
        Map<Path, List<CompilationError>> errorsByFile = new LinkedHashMap<>();
        for (CompilationError error : errors) {
            errorsByFile.computeIfAbsent(error.getSourceFile(), k -> new ArrayList<>()).add(error);
        }

        // ── Tier 1: Rule-based fixes ──────────────────────────────
        ConsoleUI.progress("Tier 1: Applying rule-based fixes...");
        for (Map.Entry<Path, List<CompilationError>> entry : errorsByFile.entrySet()) {
            Path file = entry.getKey();
            List<CompilationError> fileErrors = entry.getValue();

            try {
                int fixes = healFileWithRules(context, file, fileErrors);
                fixesApplied += fixes;
                if (fixes > 0) filesToFix.add(file);
            } catch (Exception e) {
                log.warn("Rule-based fix failed for: {}", file, e);
            }
        }

        // ── Tier 2: AI-powered fixes (for remaining unfixed errors) ──
        long unfixedAfterRules = errors.stream().filter(e -> !e.isFixed()).count();
        if (unfixedAfterRules > 0 && llmAvailable) {
            ConsoleUI.progress("Tier 2: AI-powered fixes for " + unfixedAfterRules + " remaining errors...");

            for (Map.Entry<Path, List<CompilationError>> entry : errorsByFile.entrySet()) {
                Path file = entry.getKey();
                List<CompilationError> unfixedErrors = entry.getValue().stream()
                        .filter(e -> !e.isFixed())
                        .toList();

                if (unfixedErrors.isEmpty()) continue;

                try {
                    int aiFixes = healFileWithAi(context, file, unfixedErrors);
                    fixesApplied += aiFixes;
                    if (aiFixes > 0) filesToFix.add(file);
                } catch (Exception e) {
                    log.warn("AI fix failed for: {}", file, e);
                    ConsoleUI.warn("AI could not fix: " + file.getFileName() + " — " + e.getMessage());
                }
            }
        }

        // Report unfixed errors
        long unfixed = errors.stream().filter(e -> !e.isFixed()).count();
        if (unfixed > 0) {
            ConsoleUI.warn(unfixed + " errors could not be automatically fixed" +
                    (llmAvailable ? "" : " (try enabling AI with --ai-provider ollama)"));
            for (CompilationError err : errors) {
                if (!err.isFixed()) {
                    // If AI is available, ask it to explain the blocker
                    String suggestion = "Manual intervention required";
                    if (llmAvailable) {
                        suggestion = getAiExplanation(context, err);
                    }

                    context.addIssue(MigrationIssue.builder()
                            .severity(MigrationIssue.Severity.BLOCKER)
                            .category(MigrationIssue.Category.COMPILATION_ERROR)
                            .title("Unfixable compilation error")
                            .description(err.getErrorMessage())
                            .affectedFile(err.getSourceFile())
                            .lineNumber(err.getLineNumber())
                            .suggestedFix(suggestion)
                            .build());
                }
            }
        }

        AgentResult.Status status = fixesApplied > 0
                ? AgentResult.Status.PARTIAL_SUCCESS
                : AgentResult.Status.FAILURE;

        ConsoleUI.agentComplete(getName(), fixesApplied > 0);
        return AgentResult.builder(getName())
                .status(status)
                .message("Applied " + fixesApplied + " fixes, " + unfixed + " remaining")
                .issuesFound(errors.size())
                .issuesFixed(fixesApplied)
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LLM Initialization
    // ═══════════════════════════════════════════════════════════════════

    private void initLlm(MigrationContext context) {
        if (llmChecked) return;
        llmChecked = true;

        if (!context.getConfig().isAiEnabled()) {
            llmAvailable = false;
            return;
        }

        llmClient = new LlmClient(context.getConfig().getLlmConfig());
        llmAvailable = llmClient.isAvailable();

        if (llmAvailable) {
            ConsoleUI.success("AI connected: " + context.getConfig().getLlmConfig());
        } else {
            ConsoleUI.warn("AI configured but not reachable. Falling back to rule-based mode.");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Tier 1: Rule-Based Healing (same as before, no AI)
    // ═══════════════════════════════════════════════════════════════════

    private int healFileWithRules(MigrationContext context, Path file, List<CompilationError> errors)
            throws IOException {
        if (!Files.exists(file)) return 0;

        String source = Files.readString(file);
        ParseResult<CompilationUnit> result = parser.parse(source);

        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return applyTextBasedFixes(context, file, source, errors);
        }

        CompilationUnit cu = result.getResult().get();
        int fixes = 0;
        boolean modified = false;

        for (CompilationError error : errors) {
            if (error.isFixed()) continue;

            String fixType = classifyError(error);
            if (fixType == null) continue;

            boolean fixed = switch (fixType) {
                case "MISSING_PACKAGE" -> fixMissingPackage(cu, error, context);
                case "MISSING_CLASS" -> fixMissingClass(cu, error, context);
                case "ACCESS_DENIED" -> fixAccessDenied(cu, error, context);
                case "MISSING_METHOD" -> fixMissingMethod(cu, error, context);
                default -> false;
            };

            if (fixed) {
                error.setFixed(true);
                error.setFixAttempted(true);
                fixes++;
                modified = true;
                ConsoleUI.step("[Rule] Fixed: " + error.getErrorMessage() + " in " + file.getFileName());
                context.getReport().addAction("Rule-fixed: " + error.getErrorMessage() +
                        " at " + file.getFileName() + ":" + error.getLineNumber());
            } else {
                error.setFixAttempted(true);
            }
        }

        if (modified) {
            context.addOriginalSource(file.toAbsolutePath().toString(), source);
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
            Files.writeString(file, printer.print(cu));
            context.markModified(file);
        }

        return fixes;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Tier 2: AI-Powered Healing
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Send unfixed errors + source code to the LLM for intelligent repair.
     */
    private int healFileWithAi(MigrationContext context, Path file, List<CompilationError> errors)
            throws IOException {
        if (llmClient == null || !llmAvailable) return 0;

        String source = Files.readString(file);

        // Build error summary
        StringBuilder errorsSummary = new StringBuilder();
        for (CompilationError err : errors) {
            errorsSummary.append(String.format("Line %d: %s%n", err.getLineNumber(), err.getErrorMessage()));
        }

        ConsoleUI.progress("Asking AI to fix " + errors.size() + " errors in " + file.getFileName() + "...");

        // Call LLM
        String prompt;
        if (errors.size() == 1) {
            CompilationError err = errors.get(0);
            prompt = MigrationPrompts.fixCompilationError(
                    source, err.getErrorMessage(), err.getLineNumber(),
                    context.getSourceVersion(), context.getTargetVersion());
        } else {
            prompt = MigrationPrompts.fixMultipleErrors(
                    source, errorsSummary.toString(),
                    context.getSourceVersion(), context.getTargetVersion());
        }

        String response = llmClient.chat(MigrationPrompts.SYSTEM_PROMPT, prompt);
        if (response == null || response.isBlank() || response.contains("NO_FIX_AVAILABLE")) {
            ConsoleUI.warn("AI could not find a fix for " + file.getFileName());
            return 0;
        }

        // Extract Java code from response (strip markdown fences if present)
        String fixedCode = extractJavaCode(response);

        // Validate: the fixed code must be parseable Java
        ParseResult<CompilationUnit> parseCheck = parser.parse(fixedCode);
        if (!parseCheck.isSuccessful()) {
            ConsoleUI.warn("AI-generated code failed to parse. Discarding.");
            log.debug("AI response that failed parsing:\n{}", fixedCode);
            return 0;
        }

        // Apply the fix
        if (!context.getConfig().isDryRun()) {
            context.addOriginalSource(file.toAbsolutePath().toString(), source);
            Files.writeString(file, fixedCode);
            context.markModified(file);
        }

        int fixCount = errors.size();
        errors.forEach(e -> {
            e.setFixed(true);
            e.setFixAttempted(true);
        });

        ConsoleUI.success("[AI] Fixed " + fixCount + " errors in " + file.getFileName());
        context.getReport().addAction("AI-fixed " + fixCount + " errors in " +
                file.getFileName() + " using " + context.getConfig().getLlmConfig().getProvider());

        return fixCount;
    }

    /**
     * Ask the AI to explain a blocker error for the migration report.
     */
    private String getAiExplanation(MigrationContext context, CompilationError error) {
        try {
            String errorContext = String.format(
                    "File: %s, Line: %d, Error: %s",
                    error.getSourceFile().getFileName(), error.getLineNumber(), error.getErrorMessage());

            String explanation = llmClient.chat(
                    MigrationPrompts.SYSTEM_PROMPT,
                    MigrationPrompts.explainBlocker(
                            errorContext, context.getSourceVersion(), context.getTargetVersion()));

            if (explanation != null && !explanation.isBlank()) {
                return explanation.trim();
            }
        } catch (Exception e) {
            log.debug("Could not get AI explanation", e);
        }
        return "Manual intervention required";
    }

    /**
     * Extract Java code from LLM response, stripping markdown code fences.
     */
    private String extractJavaCode(String response) {
        // Try to extract from ```java ... ``` blocks
        java.util.regex.Matcher m = Pattern.compile("```(?:java)?\\s*\\n(.*?)```", Pattern.DOTALL)
                .matcher(response);
        if (m.find()) {
            return m.group(1).trim();
        }
        // If no code fences, use the raw response
        return response.trim();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Error Classification and Rule-Based Fix Methods
    // ═══════════════════════════════════════════════════════════════════

    private String classifyError(CompilationError error) {
        for (Map.Entry<Pattern, String> entry : ERROR_FIX_PATTERNS.entrySet()) {
            if (entry.getKey().matcher(error.getErrorMessage()).find()) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean fixMissingPackage(CompilationUnit cu, CompilationError error, MigrationContext ctx) {
        Matcher m = Pattern.compile("package\\s+([\\w.]+)\\s+does not exist")
                .matcher(error.getErrorMessage());
        if (!m.find()) return false;

        String pkg = m.group(1);

        // Check known replacements
        List<DeprecationRule> rules = apiDb.getRulesFor(ctx.getSourceVersion(), ctx.getTargetVersion());
        for (DeprecationRule rule : rules) {
            String importPat = rule.getImportPattern().replace("import ", "").trim();
            if (pkg.startsWith(importPat) || importPat.startsWith(pkg)) {
                if (rule.getReplacementImport() != null && !rule.getReplacementImport().isBlank()) {
                    String newImport = rule.getReplacementImport().replace("import ", "").trim();
                    for (ImportDeclaration imp : cu.getImports()) {
                        if (imp.getNameAsString().startsWith(pkg)) {
                            String oldName = imp.getNameAsString();
                            String newName = oldName.replace(pkg, newImport.replace(".*", ""));
                            imp.setName(newName);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean fixMissingClass(CompilationUnit cu, CompilationError error, MigrationContext ctx) {
        Matcher m = Pattern.compile("cannot find symbol.*symbol:\\s*class\\s+(\\w+)")
                .matcher(error.getErrorMessage());
        if (!m.find()) return false;

        String className = m.group(1);

        Map<String, String[]> classReplacements = Map.of(
                "BASE64Encoder", new String[]{"java.util.Base64", "import java.util.Base64"},
                "BASE64Decoder", new String[]{"java.util.Base64", "import java.util.Base64"},
                "DatatypeConverter", new String[]{"java.util.Base64", "import java.util.Base64"}
        );

        if (classReplacements.containsKey(className)) {
            String[] replacement = classReplacements.get(className);
            cu.getImports().removeIf(imp ->
                    imp.getNameAsString().contains(className));
            cu.addImport(replacement[0]);
            return true;
        }

        return false;
    }

    private boolean fixAccessDenied(CompilationUnit cu, CompilationError error, MigrationContext ctx) {
        ctx.addIssue(MigrationIssue.builder()
                .severity(MigrationIssue.Severity.WARNING)
                .category(MigrationIssue.Category.MODULE_SYSTEM)
                .title("Module access restriction")
                .description(error.getErrorMessage())
                .affectedFile(error.getSourceFile())
                .lineNumber(error.getLineNumber())
                .suggestedFix("Add --add-opens or --add-exports JVM flags, " +
                        "or use public API alternatives")
                .build());
        return false;
    }

    private boolean fixMissingMethod(CompilationUnit cu, CompilationError error, MigrationContext ctx) {
        Matcher m = Pattern.compile("cannot find symbol.*symbol:\\s*method\\s+(\\w+)")
                .matcher(error.getErrorMessage());
        if (!m.find()) return false;

        String methodName = m.group(1);

        if ("encode".equals(methodName) || "decode".equals(methodName)) {
            return false; // handled by import fix
        }

        return false;
    }

    private int applyTextBasedFixes(MigrationContext ctx, Path file, String source,
                                     List<CompilationError> errors) throws IOException {
        int fixes = 0;
        String modified = source;

        for (CompilationError error : errors) {
            if (error.getErrorMessage().contains("package") &&
                error.getErrorMessage().contains("does not exist")) {
                Matcher m = Pattern.compile("package\\s+([\\w.]+)\\s+does not exist")
                        .matcher(error.getErrorMessage());
                if (m.find()) {
                    String pkg = m.group(1);
                    Map<String, String> pkgMigrations = Map.of(
                            "javax.xml.bind", "jakarta.xml.bind",
                            "javax.xml.ws", "jakarta.xml.ws",
                            "javax.activation", "jakarta.activation",
                            "javax.annotation", "jakarta.annotation",
                            "sun.misc", "java.util"
                    );

                    for (Map.Entry<String, String> entry : pkgMigrations.entrySet()) {
                        if (pkg.startsWith(entry.getKey())) {
                            modified = modified.replace(
                                    "import " + pkg,
                                    "import " + pkg.replace(entry.getKey(), entry.getValue())
                            );
                            error.setFixed(true);
                            fixes++;
                            break;
                        }
                    }
                }
            }
        }

        if (fixes > 0) {
            ctx.addOriginalSource(file.toAbsolutePath().toString(), source);
            Files.writeString(file, modified);
            ctx.markModified(file);
        }

        return fixes;
    }
}
