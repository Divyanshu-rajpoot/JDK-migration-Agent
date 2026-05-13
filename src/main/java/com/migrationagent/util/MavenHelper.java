package com.migrationagent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven/Gradle build file utilities.
 */
public class MavenHelper {

    private static final Logger log = LoggerFactory.getLogger(MavenHelper.class);

    /**
     * Detect the build system used by the project.
     */
    public enum BuildSystem {
        MAVEN, GRADLE, UNKNOWN
    }

    /**
     * Detect whether the project uses Maven or Gradle.
     */
    public static BuildSystem detectBuildSystem(Path projectPath) {
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return BuildSystem.MAVEN;
        }
        if (Files.exists(projectPath.resolve("build.gradle")) ||
            Files.exists(projectPath.resolve("build.gradle.kts"))) {
            return BuildSystem.GRADLE;
        }
        return BuildSystem.UNKNOWN;
    }

    /**
     * Find all pom.xml files in the project (for multi-module projects).
     */
    public static List<Path> findPomFiles(Path projectPath) throws IOException {
        List<Path> pomFiles = new ArrayList<>();
        Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.equals("target") || name.equals(".git") || name.equals("node_modules")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("pom.xml")) {
                    pomFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return pomFiles;
    }

    /**
     * Find all build.gradle files in the project.
     */
    public static List<Path> findGradleFiles(Path projectPath) throws IOException {
        List<Path> gradleFiles = new ArrayList<>();
        Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.equals("build") || name.equals(".git") || name.equals(".gradle")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.equals("build.gradle") || name.equals("build.gradle.kts") ||
                    name.equals("settings.gradle") || name.equals("settings.gradle.kts")) {
                    gradleFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return gradleFiles;
    }

    /**
     * Find all Java source files in the project.
     */
    public static List<Path> findJavaFiles(Path projectPath) throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.equals("target") || name.equals("build") ||
                    name.equals(".git") || name.equals("node_modules") ||
                    name.equals("test") || name.equals(".idea")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    javaFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return javaFiles;
    }

    /**
     * Find all Java source files including test files.
     */
    public static List<Path> findAllJavaFiles(Path projectPath) throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        Files.walkFileTree(projectPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (name.equals("target") || name.equals("build") ||
                    name.equals(".git") || name.equals("node_modules") ||
                    name.equals(".idea")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    javaFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return javaFiles;
    }

    /**
     * Update the Java version in a pom.xml file content string.
     *
     * @param pomContent   existing pom.xml content
     * @param targetVersion target JDK version (e.g. 17)
     * @return updated pom.xml content
     */
    public static String updatePomJavaVersion(String pomContent, int targetVersion) {
        String ver = String.valueOf(targetVersion);

        // Update <maven.compiler.source> and <maven.compiler.target>
        pomContent = pomContent.replaceAll(
                "(<maven\\.compiler\\.source>)\\s*[^<]+\\s*(</maven\\.compiler\\.source>)",
                "$1" + ver + "$2");
        pomContent = pomContent.replaceAll(
                "(<maven\\.compiler\\.target>)\\s*[^<]+\\s*(</maven\\.compiler\\.target>)",
                "$1" + ver + "$2");

        // Update <source> and <target> in compiler plugin configuration
        pomContent = pomContent.replaceAll(
                "(<source>)\\s*[^<]+\\s*(</source>)",
                "$1" + ver + "$2");
        pomContent = pomContent.replaceAll(
                "(<target>)\\s*[^<]+\\s*(</target>)",
                "$1" + ver + "$2");

        // Update <java.version> property (Spring Boot style)
        pomContent = pomContent.replaceAll(
                "(<java\\.version>)\\s*[^<]+\\s*(</java\\.version>)",
                "$1" + ver + "$2");

        // Update <release> tag if present (Java 9+)
        if (targetVersion >= 9) {
            pomContent = pomContent.replaceAll(
                    "(<release>)\\s*[^<]+\\s*(</release>)",
                    "$1" + ver + "$2");
        }

        return pomContent;
    }

    /**
     * Update a dependency version in pom.xml content.
     */
    public static String updateDependencyVersion(String pomContent,
                                                   String groupId, String artifactId,
                                                   String newVersion) {
        // Pattern: find the dependency block and update version
        String pattern = String.format(
                "(<dependency>\\s*<groupId>%s</groupId>\\s*<artifactId>%s</artifactId>\\s*<version>)[^<]+(</version>)",
                escapeRegex(groupId), escapeRegex(artifactId));

        return pomContent.replaceAll(pattern, "$1" + newVersion + "$2");
    }

    private static String escapeRegex(String text) {
        return text.replace(".", "\\.");
    }
}
