package com.migrationagent.agents;

import com.migrationagent.core.AgentResult;
import com.migrationagent.core.MigrationContext;
import com.migrationagent.model.CompilationError;
import com.migrationagent.util.ConsoleUI;
import com.migrationagent.util.MavenHelper;
import com.migrationagent.util.ProcessRunner;
import com.migrationagent.util.ProcessRunner.ProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles the project and parses compilation errors.
 * This agent invokes the project's build system (Maven/Gradle) and captures
 * any compilation errors for the SelfHealingAgent to fix.
 */
public class CompilationAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(CompilationAgent.class);
    private static final int BUILD_TIMEOUT_SECONDS = 300; // 5 minutes

    // Pattern to match javac error output: filepath:line: error: message
    private static final Pattern JAVAC_ERROR_PATTERN = Pattern.compile(
            "(.+\\.java):(\\d+):\\s*error:\\s*(.+)"
    );

    // Pattern for Maven compilation error
    private static final Pattern MAVEN_ERROR_PATTERN = Pattern.compile(
            "\\[ERROR\\]\\s+(.+\\.java):\\[(\\d+),(\\d+)\\]\\s*(.+)"
    );

    @Override
    public String getName() { return "Compilation Agent"; }

    @Override
    public AgentResult execute(MigrationContext context) {
        long start = System.currentTimeMillis();
        ConsoleUI.agentStart(getName());

        if (context.getConfig().isDryRun()) {
            ConsoleUI.warn("DRY RUN — skipping compilation");
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.SKIPPED)
                    .message("Dry run mode")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        File projectDir = context.getProjectPath().toFile();
        MavenHelper.BuildSystem buildSystem = MavenHelper.detectBuildSystem(context.getProjectPath());

        ConsoleUI.progress("Building project with " + buildSystem + "...");

        ProcessResult result;
        if (buildSystem == MavenHelper.BuildSystem.MAVEN) {
            result = ProcessRunner.runMaven(projectDir, BUILD_TIMEOUT_SECONDS,
                    "compile", "-q", "--fail-at-end");
        } else if (buildSystem == MavenHelper.BuildSystem.GRADLE) {
            result = ProcessRunner.runGradle(projectDir, BUILD_TIMEOUT_SECONDS,
                    "compileJava", "--no-daemon");
        } else {
            // Try raw javac
            ConsoleUI.warn("No build system detected. Attempting raw javac compilation.");
            result = compileWithJavac(context);
        }

        context.getReport().setCompilationAttempts(
                context.getReport().getCompilationAttempts() + 1);

        if (result.isSuccess()) {
            ConsoleUI.success("Project compiled successfully!");
            context.clearCompilationErrors();
            ConsoleUI.agentComplete(getName(), true);

            return AgentResult.builder(getName())
                    .status(AgentResult.Status.SUCCESS)
                    .message("Compilation successful")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        // Parse compilation errors
        List<CompilationError> errors = parseErrors(result.combinedOutput(), context.getProjectPath());
        context.setCompilationErrors(errors);

        ConsoleUI.error("Compilation failed with " + errors.size() + " errors");
        for (CompilationError err : errors) {
            ConsoleUI.step(err.toString());
        }

        ConsoleUI.agentComplete(getName(), false);
        return AgentResult.builder(getName())
                .status(AgentResult.Status.FAILURE)
                .message("Compilation failed: " + errors.size() + " errors")
                .issuesFound(errors.size())
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    /**
     * Parse compilation errors from build output.
     */
    private List<CompilationError> parseErrors(String output, Path projectPath) {
        List<CompilationError> errors = new ArrayList<>();

        for (String line : output.split("\n")) {
            // Try Maven error pattern first
            Matcher mavenMatcher = MAVEN_ERROR_PATTERN.matcher(line);
            if (mavenMatcher.find()) {
                Path file = resolveFilePath(mavenMatcher.group(1).trim(), projectPath);
                int lineNum = Integer.parseInt(mavenMatcher.group(2));
                int colNum = Integer.parseInt(mavenMatcher.group(3));
                String msg = mavenMatcher.group(4).trim();

                errors.add(new CompilationError(file, lineNum, colNum, msg, line));
                continue;
            }

            // Try javac error pattern
            Matcher javacMatcher = JAVAC_ERROR_PATTERN.matcher(line);
            if (javacMatcher.find()) {
                Path file = resolveFilePath(javacMatcher.group(1).trim(), projectPath);
                int lineNum = Integer.parseInt(javacMatcher.group(2));
                String msg = javacMatcher.group(3).trim();

                errors.add(new CompilationError(file, lineNum, 0, msg, line));
            }
        }

        return errors;
    }

    private Path resolveFilePath(String filePath, Path projectPath) {
        Path path = Path.of(filePath);
        if (path.isAbsolute()) return path;
        return projectPath.resolve(path);
    }

    /**
     * Fallback: compile all Java files directly with javac.
     */
    private ProcessResult compileWithJavac(MigrationContext context) {
        // Find all java files and compile them
        StringBuilder fileList = new StringBuilder();
        for (Path javaFile : context.getJavaSourceFiles()) {
            fileList.append(javaFile.toAbsolutePath()).append(" ");
        }

        return ProcessRunner.run(
                context.getProjectPath().toFile(),
                BUILD_TIMEOUT_SECONDS,
                "javac", "-d", "target/classes", fileList.toString().trim()
        );
    }
}
