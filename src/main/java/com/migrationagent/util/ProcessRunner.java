package com.migrationagent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Utility for running external processes (javac, mvn, gradle) and capturing output.
 */
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    /**
     * Result of a process execution.
     */
    public record ProcessResult(
            int exitCode,
            String stdout,
            String stderr,
            long durationMs
    ) {
        public boolean isSuccess() { return exitCode == 0; }

        /** Combined output (stdout + stderr) */
        public String combinedOutput() {
            StringBuilder sb = new StringBuilder();
            if (!stdout.isBlank()) sb.append(stdout);
            if (!stderr.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(stderr);
            }
            return sb.toString();
        }
    }

    /**
     * Run a command in the given working directory.
     *
     * @param workingDir  the directory to run the command in
     * @param timeoutSecs max seconds to wait
     * @param command     the command and arguments
     * @return process result
     */
    public static ProcessResult run(File workingDir, int timeoutSecs, String... command) {
        log.debug("Running command: {} in {}", String.join(" ", command), workingDir);
        long start = System.currentTimeMillis();

        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(workingDir)
                    .redirectErrorStream(false);

            // Set environment to use UTF-8
            pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");

            Process process = pb.start();

            // Read stdout and stderr concurrently to avoid deadlock
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());

            boolean completed = process.waitFor(timeoutSecs, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - start;

            if (!completed) {
                process.destroyForcibly();
                return new ProcessResult(-1, stdout, "Process timed out after " + timeoutSecs + "s", duration);
            }

            return new ProcessResult(process.exitValue(), stdout, stderr, duration);

        } catch (IOException | InterruptedException e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Process execution failed", e);
            return new ProcessResult(-1, "", "Error: " + e.getMessage(), duration);
        }
    }

    /**
     * Run a Maven command in the given project directory.
     */
    public static ProcessResult runMaven(File projectDir, int timeoutSecs, String... goals) {
        // Determine mvn command (mvnw if available, else mvn)
        String mvnCmd = determineMavenCommand(projectDir);

        String[] command = new String[goals.length + 1];
        command[0] = mvnCmd;
        System.arraycopy(goals, 0, command, 1, goals.length);

        return run(projectDir, timeoutSecs, command);
    }

    /**
     * Run a Gradle command in the given project directory.
     */
    public static ProcessResult runGradle(File projectDir, int timeoutSecs, String... tasks) {
        String gradleCmd = determineGradleCommand(projectDir);

        String[] command = new String[tasks.length + 1];
        command[0] = gradleCmd;
        System.arraycopy(tasks, 0, command, 1, tasks.length);

        return run(projectDir, timeoutSecs, command);
    }

    /**
     * Compile a single Java file using javac.
     */
    public static ProcessResult compileJavaFile(File sourceFile, File outputDir, String classpath) {
        if (!outputDir.exists()) outputDir.mkdirs();

        if (classpath != null && !classpath.isBlank()) {
            return run(sourceFile.getParentFile(), 60,
                    "javac", "-d", outputDir.getAbsolutePath(),
                    "-cp", classpath, sourceFile.getAbsolutePath());
        } else {
            return run(sourceFile.getParentFile(), 60,
                    "javac", "-d", outputDir.getAbsolutePath(),
                    sourceFile.getAbsolutePath());
        }
    }

    private static String determineMavenCommand(File projectDir) {
        // Check for Maven wrapper
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            File mvnw = new File(projectDir, "mvnw.cmd");
            if (mvnw.exists()) return mvnw.getAbsolutePath();
            mvnw = new File(projectDir, "mvnw.bat");
            if (mvnw.exists()) return mvnw.getAbsolutePath();
            return "mvn.cmd";
        } else {
            File mvnw = new File(projectDir, "mvnw");
            if (mvnw.exists()) return mvnw.getAbsolutePath();
            return "mvn";
        }
    }

    private static String determineGradleCommand(File projectDir) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            File gradlew = new File(projectDir, "gradlew.bat");
            if (gradlew.exists()) return gradlew.getAbsolutePath();
            return "gradle.bat";
        } else {
            File gradlew = new File(projectDir, "gradlew");
            if (gradlew.exists()) return gradlew.getAbsolutePath();
            return "gradle";
        }
    }

    private static String readStream(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        }
    }
}
