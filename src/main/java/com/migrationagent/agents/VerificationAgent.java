package com.migrationagent.agents;

import com.migrationagent.core.AgentResult;
import com.migrationagent.core.MigrationContext;
import com.migrationagent.util.ConsoleUI;
import com.migrationagent.util.MavenHelper;
import com.migrationagent.util.ProcessRunner;
import com.migrationagent.util.ProcessRunner.ProcessResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Runs the project's test suite to verify migration correctness.
 */
public class VerificationAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(VerificationAgent.class);
    private static final int TEST_TIMEOUT_SECONDS = 600; // 10 minutes

    @Override
    public String getName() { return "Verification Agent"; }

    @Override
    public AgentResult execute(MigrationContext context) {
        long start = System.currentTimeMillis();
        ConsoleUI.agentStart(getName());

        if (context.getConfig().isDryRun()) {
            ConsoleUI.warn("DRY RUN — skipping verification");
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.SKIPPED)
                    .message("Dry run mode")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        File projectDir = context.getProjectPath().toFile();
        MavenHelper.BuildSystem buildSystem = MavenHelper.detectBuildSystem(context.getProjectPath());

        ConsoleUI.progress("Running test suite...");

        ProcessResult result;
        if (buildSystem == MavenHelper.BuildSystem.MAVEN) {
            result = ProcessRunner.runMaven(projectDir, TEST_TIMEOUT_SECONDS, "test", "-q");
        } else if (buildSystem == MavenHelper.BuildSystem.GRADLE) {
            result = ProcessRunner.runGradle(projectDir, TEST_TIMEOUT_SECONDS, "test", "--no-daemon");
        } else {
            ConsoleUI.warn("No build system detected — cannot run tests.");
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.SKIPPED)
                    .message("No build system for test execution")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        if (result.isSuccess()) {
            ConsoleUI.success("All tests passed!");
            ConsoleUI.agentComplete(getName(), true);
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.SUCCESS)
                    .message("All tests passed")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        // Parse test failures
        String output = result.combinedOutput();
        int failures = countTestFailures(output);

        ConsoleUI.error("Tests failed: " + failures + " failure(s)");
        log.debug("Test output:\n{}", output);

        ConsoleUI.agentComplete(getName(), false);
        return AgentResult.builder(getName())
                .status(AgentResult.Status.FAILURE)
                .message("Test failures: " + failures)
                .issuesFound(failures)
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private int countTestFailures(String output) {
        int count = 0;
        for (String line : output.split("\n")) {
            if (line.contains("Tests run:") && line.contains("Failures:")) {
                try {
                    String failStr = line.substring(line.indexOf("Failures:") + 10);
                    failStr = failStr.substring(0, failStr.indexOf(",")).trim();
                    count += Integer.parseInt(failStr);
                } catch (Exception e) {
                    // ignore parse issues
                }
            }
        }
        return Math.max(count, 1); // at least 1 if tests failed
    }
}
