package com.migrationagent;

import com.migrationagent.core.MigrationConfig;
import com.migrationagent.core.MigrationContext;
import com.migrationagent.core.MigrationOrchestrator;
import com.migrationagent.llm.LlmConfig;
import com.migrationagent.util.ConsoleUI;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * JDK Migration Agent — Main entry point.
 * <p>
 * A self-healing, multi-agent system that automatically migrates Java applications
 * from one JDK version to another with minimal human intervention.
 * </p>
 */
@Command(
    name = "migration-agent",
    mixinStandardHelpOptions = true,
    version = "JDK Migration Agent 1.0.0",
    description = "Automated JDK migration agent with self-healing capabilities.",
    subcommands = {
        MigrationAgentApp.MigrateCommand.class,
        MigrationAgentApp.AnalyzeCommand.class,
        MigrationAgentApp.MigrateWorkspaceCommand.class,
        MigrationAgentApp.InitConfigCommand.class
    }
)
public class MigrationAgentApp implements Callable<Integer> {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new MigrationAgentApp()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        ConsoleUI.printBanner();
        System.out.println("Use --help to see available commands.");
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Shared AI options mixin (reused across commands)
    // ═══════════════════════════════════════════════════════════════════

    static class AiOptions {
        @Option(names = {"--ai-provider"},
                description = "AI provider: NONE, OLLAMA, LLAMACPP, OPENAI, OPENAI_COMPATIBLE, GITHUB_COPILOT (default: NONE)")
        String aiProvider = "NONE";

        @Option(names = {"--ai-model"},
                description = "AI model name (e.g. codellama:13b, gpt-4o, deepseek-coder)")
        String aiModel;

        @Option(names = {"--ai-url"},
                description = "AI API base URL (default: auto-detected from provider)")
        String aiUrl;

        @Option(names = {"--ai-key"},
                description = "API key for cloud AI providers (OpenAI, GitHub Copilot)")
        String aiKey;

        LlmConfig buildConfig() {
            LlmConfig.Provider provider;
            try {
                provider = LlmConfig.Provider.valueOf(aiProvider.toUpperCase().replace("-", "_"));
            } catch (IllegalArgumentException e) {
                ConsoleUI.warn("Unknown AI provider: " + aiProvider + ". Using NONE.");
                provider = LlmConfig.Provider.NONE;
            }

            LlmConfig.Builder builder = LlmConfig.builder().provider(provider);

            if (provider == LlmConfig.Provider.NONE) {
                return builder.build();
            }

            // Set defaults per provider, then override with user values
            LlmConfig defaults = LlmConfig.forProvider(provider, aiModel, aiKey);
            builder.baseUrl(aiUrl != null ? aiUrl : defaults.getBaseUrl());
            builder.model(aiModel != null ? aiModel : defaults.getModel());
            builder.apiKey(aiKey != null ? aiKey : defaults.getApiKey());

            return builder.build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // migrate command
    // ─────────────────────────────────────────────────────────────────────
    @Command(
        name = "migrate",
        description = "Migrate a Java project from JDK version X to version Y."
    )
    static class MigrateCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to the Java project root directory.")
        private Path projectPath;

        @Option(names = {"-f", "--from"}, required = true, description = "Source JDK version (e.g. 6, 8, 11, 17).")
        private int sourceVersion;

        @Option(names = {"-t", "--to"}, required = true, description = "Target JDK version (e.g. 8, 11, 17, 21).")
        private int targetVersion;

        @Option(names = {"-m", "--modules"}, split = ",",
                description = "Comma-separated list of modules to migrate. If omitted, migrates all.")
        private List<String> modules;

        @Option(names = {"--max-retries"}, defaultValue = "10",
                description = "Maximum self-healing retry iterations (default: ${DEFAULT-VALUE}).")
        private int maxRetries;

        @Option(names = {"--dry-run"}, defaultValue = "false",
                description = "Analyze and report without making changes.")
        private boolean dryRun;

        @Option(names = {"--backup"}, defaultValue = "true",
                description = "Create backup before making changes (default: ${DEFAULT-VALUE}).")
        private boolean backup;

        // AI options
        @Option(names = {"--ai-provider"},
                description = "AI provider: NONE, OLLAMA, LLAMACPP, OPENAI, OPENAI_COMPATIBLE, GITHUB_COPILOT (default: NONE)")
        private String aiProvider = "NONE";

        @Option(names = {"--ai-model"},
                description = "AI model name (e.g. codellama:13b, gpt-4o, deepseek-coder)")
        private String aiModel;

        @Option(names = {"--ai-url"},
                description = "AI API base URL (overrides default)")
        private String aiUrl;

        @Option(names = {"--ai-key"},
                description = "API key for cloud providers (OpenAI, GitHub Copilot). Can also use env OPENAI_API_KEY.")
        private String aiKey;

        @Override
        public Integer call() {
            ConsoleUI.printBanner();

            // Validate inputs
            if (!Files.exists(projectPath)) {
                ConsoleUI.error("Project path does not exist: " + projectPath);
                return 1;
            }
            if (sourceVersion >= targetVersion) {
                ConsoleUI.error("Source version (" + sourceVersion + ") must be less than target version (" + targetVersion + ").");
                return 1;
            }

            // Resolve API key from env if not provided
            if (aiKey == null || aiKey.isBlank()) {
                aiKey = System.getenv("OPENAI_API_KEY");
            }
            if (aiKey == null || aiKey.isBlank()) {
                aiKey = System.getenv("GITHUB_TOKEN");
            }

            // Build AI config
            LlmConfig llmConfig = buildLlmConfig(aiProvider, aiModel, aiUrl, aiKey);

            ConsoleUI.info("Starting migration: JDK " + sourceVersion + " → JDK " + targetVersion);
            ConsoleUI.info("Project: " + projectPath.toAbsolutePath());
            if (modules != null && !modules.isEmpty()) {
                ConsoleUI.info("Modules: " + String.join(", ", modules));
            }
            if (dryRun) {
                ConsoleUI.warn("DRY RUN mode — no changes will be made.");
            }
            if (llmConfig.isEnabled()) {
                ConsoleUI.info("AI: " + llmConfig.getProvider() + " / " + llmConfig.getModel() +
                        " @ " + llmConfig.getBaseUrl());
            }

            // Build configuration
            MigrationConfig config = MigrationConfig.builder()
                    .projectPath(projectPath.toAbsolutePath())
                    .sourceVersion(sourceVersion)
                    .targetVersion(targetVersion)
                    .modules(modules)
                    .maxRetries(maxRetries)
                    .dryRun(dryRun)
                    .createBackup(backup)
                    .llmConfig(llmConfig)
                    .build();

            // Create context and orchestrator
            MigrationContext context = new MigrationContext(config);
            MigrationOrchestrator orchestrator = new MigrationOrchestrator(context);

            // Run migration
            boolean success = orchestrator.executeMigration();

            if (success) {
                ConsoleUI.success("Migration completed successfully!");
                return 0;
            } else {
                ConsoleUI.error("Migration completed with issues. Check the report for details.");
                return 1;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // migrate-workspace command: migrate multiple applications at once
    // ─────────────────────────────────────────────────────────────────────
    @Command(
        name = "migrate-workspace",
        description = "Migrate multiple applications/modules in a workspace directory. " +
                      "Each subdirectory with a pom.xml or build.gradle is treated as a separate application."
    )
    static class MigrateWorkspaceCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to the workspace root containing multiple applications.")
        private Path workspacePath;

        @Option(names = {"-f", "--from"}, required = true, description = "Source JDK version.")
        private int sourceVersion;

        @Option(names = {"-t", "--to"}, required = true, description = "Target JDK version.")
        private int targetVersion;

        @Option(names = {"-a", "--apps"}, split = ",",
                description = "Comma-separated list of application names to migrate. If omitted, migrates all.")
        private List<String> apps;

        @Option(names = {"--max-retries"}, defaultValue = "10",
                description = "Maximum self-healing retry iterations per module (default: ${DEFAULT-VALUE}).")
        private int maxRetries;

        @Option(names = {"--dry-run"}, defaultValue = "false",
                description = "Analyze and report without making changes.")
        private boolean dryRun;

        @Option(names = {"--backup"}, defaultValue = "true",
                description = "Create backup before making changes (default: ${DEFAULT-VALUE}).")
        private boolean backup;

        @Option(names = {"--ai-provider"}, description = "AI provider (OLLAMA, OPENAI, etc.)")
        private String aiProvider = "NONE";

        @Option(names = {"--ai-model"}, description = "AI model name")
        private String aiModel;

        @Option(names = {"--ai-url"}, description = "AI API base URL")
        private String aiUrl;

        @Option(names = {"--ai-key"}, description = "API key for cloud AI")
        private String aiKey;

        @Override
        public Integer call() {
            ConsoleUI.printBanner();

            if (!Files.exists(workspacePath)) {
                ConsoleUI.error("Workspace path does not exist: " + workspacePath);
                return 1;
            }
            if (sourceVersion >= targetVersion) {
                ConsoleUI.error("Source version (" + sourceVersion + ") must be less than target version (" + targetVersion + ").");
                return 1;
            }

            if (aiKey == null || aiKey.isBlank()) aiKey = System.getenv("OPENAI_API_KEY");
            if (aiKey == null || aiKey.isBlank()) aiKey = System.getenv("GITHUB_TOKEN");

            LlmConfig llmConfig = buildLlmConfig(aiProvider, aiModel, aiUrl, aiKey);

            ConsoleUI.info("Workspace migration: JDK " + sourceVersion + " → JDK " + targetVersion);
            ConsoleUI.info("Workspace: " + workspacePath.toAbsolutePath());
            if (llmConfig.isEnabled()) {
                ConsoleUI.info("AI: " + llmConfig.getProvider() + " / " + llmConfig.getModel());
            }

            MigrationConfig config = MigrationConfig.builder()
                    .projectPath(workspacePath.toAbsolutePath())
                    .sourceVersion(sourceVersion)
                    .targetVersion(targetVersion)
                    .modules(apps)
                    .maxRetries(maxRetries)
                    .dryRun(dryRun)
                    .createBackup(backup)
                    .llmConfig(llmConfig)
                    .build();

            MigrationContext context = new MigrationContext(config);
            MigrationOrchestrator orchestrator = new MigrationOrchestrator(context);

            boolean success = orchestrator.executeMigration();

            if (success) {
                ConsoleUI.success("All applications migrated successfully!");
                return 0;
            } else {
                ConsoleUI.error("Some applications had migration issues. Check the report.");
                return 1;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // analyze command
    // ─────────────────────────────────────────────────────────────────────
    @Command(
        name = "analyze",
        description = "Analyze a Java project for migration compatibility without making changes."
    )
    static class AnalyzeCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to the Java project root directory.")
        private Path projectPath;

        @Option(names = {"-t", "--target"}, required = true, description = "Target JDK version (e.g. 8, 11, 17, 21).")
        private int targetVersion;

        @Option(names = {"-m", "--modules"}, split = ",",
                description = "Comma-separated list of modules to analyze.")
        private List<String> modules;

        @Option(names = {"--ai-provider"}, description = "AI provider for enhanced analysis")
        private String aiProvider = "NONE";

        @Option(names = {"--ai-model"}, description = "AI model name")
        private String aiModel;

        @Option(names = {"--ai-url"}, description = "AI API base URL")
        private String aiUrl;

        @Option(names = {"--ai-key"}, description = "API key")
        private String aiKey;

        @Override
        public Integer call() {
            ConsoleUI.printBanner();

            if (!Files.exists(projectPath)) {
                ConsoleUI.error("Project path does not exist: " + projectPath);
                return 1;
            }

            // Detect source version from build files
            int detectedSource = detectSourceVersion(projectPath);
            ConsoleUI.info("Detected source JDK version: " + detectedSource);
            ConsoleUI.info("Target JDK version: " + targetVersion);

            if (aiKey == null || aiKey.isBlank()) aiKey = System.getenv("OPENAI_API_KEY");
            LlmConfig llmConfig = buildLlmConfig(aiProvider, aiModel, aiUrl, aiKey);

            MigrationConfig config = MigrationConfig.builder()
                    .projectPath(projectPath.toAbsolutePath())
                    .sourceVersion(detectedSource)
                    .targetVersion(targetVersion)
                    .modules(modules)
                    .dryRun(true) // analyze only
                    .createBackup(false)
                    .llmConfig(llmConfig)
                    .build();

            MigrationContext context = new MigrationContext(config);
            MigrationOrchestrator orchestrator = new MigrationOrchestrator(context);

            orchestrator.executeAnalysis();

            ConsoleUI.success("Analysis complete. See report above.");
            return 0;
        }

        private int detectSourceVersion(Path project) {
            Path pomFile = project.resolve("pom.xml");
            if (Files.exists(pomFile)) {
                try {
                    String content = Files.readString(pomFile);
                    var matcher = java.util.regex.Pattern.compile(
                            "<(?:maven\\.compiler\\.source|source)>\\s*(\\d+(?:\\.\\d+)?)\\s*</",
                            java.util.regex.Pattern.DOTALL
                    ).matcher(content);
                    if (matcher.find()) {
                        String version = matcher.group(1);
                        if (version.startsWith("1.")) {
                            return Integer.parseInt(version.substring(2));
                        }
                        return Integer.parseInt(version);
                    }
                } catch (Exception e) {
                    // Fall through
                }
            }
            ConsoleUI.warn("Could not detect source JDK version. Assuming JDK 8.");
            return 8;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // init-config command: generate template config files
    // ─────────────────────────────────────────────────────────────────────
    @Command(
        name = "init-config",
        description = "Generate template .migration-agent/rules.json and dependencies.json " +
                      "files in your project. Edit these to add custom rules for your codebase."
    )
    static class InitConfigCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to the project root directory.")
        private Path projectPath;

        @Override
        public Integer call() {
            ConsoleUI.printBanner();

            if (!Files.exists(projectPath)) {
                ConsoleUI.error("Project path does not exist: " + projectPath);
                return 1;
            }

            try {
                com.migrationagent.knowledge.ExternalRulesLoader.generateTemplateRulesFile(projectPath);
                ConsoleUI.success("Generated config files:");
                ConsoleUI.info("  " + projectPath.resolve(".migration-agent/rules.json"));
                ConsoleUI.info("  " + projectPath.resolve(".migration-agent/dependencies.json"));
                ConsoleUI.info("");
                ConsoleUI.info("Edit these files to add custom rules for your project's");
                ConsoleUI.info("internal libraries, proprietary APIs, and dependencies.");
                ConsoleUI.info("The agent will load them automatically on the next run.");
                return 0;
            } catch (Exception e) {
                ConsoleUI.error("Failed to generate config: " + e.getMessage());
                return 1;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Shared utility
    // ─────────────────────────────────────────────────────────────────────

    static LlmConfig buildLlmConfig(String provider, String model, String url, String key) {
        LlmConfig.Provider p;
        try {
            p = LlmConfig.Provider.valueOf(provider.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return LlmConfig.builder().build(); // NONE
        }

        if (p == LlmConfig.Provider.NONE) {
            return LlmConfig.builder().build();
        }

        LlmConfig defaults = LlmConfig.forProvider(p, model, key);
        return LlmConfig.builder()
                .provider(p)
                .baseUrl(url != null ? url : defaults.getBaseUrl())
                .model(model != null ? model : defaults.getModel())
                .apiKey(key != null ? key : defaults.getApiKey())
                .build();
    }
}
