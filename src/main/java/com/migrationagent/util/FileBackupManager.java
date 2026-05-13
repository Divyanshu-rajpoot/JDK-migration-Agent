package com.migrationagent.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Manages project backups before migration changes.
 */
public class FileBackupManager {

    private static final Logger log = LoggerFactory.getLogger(FileBackupManager.class);

    /**
     * Create a full backup of the project directory.
     *
     * @param projectPath the project root to back up
     * @return path to the backup directory
     */
    public static Path createBackup(Path projectPath) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String backupName = projectPath.getFileName() + "_backup_" + timestamp;
        Path backupDir = projectPath.getParent().resolve(backupName);

        log.info("Creating backup: {} → {}", projectPath, backupDir);
        ConsoleUI.progress("Creating project backup...");

        copyDirectory(projectPath, backupDir);

        long fileCount = countFiles(backupDir);
        ConsoleUI.success("Backup created: " + backupDir + " (" + fileCount + " files)");
        log.info("Backup completed: {} files", fileCount);

        return backupDir;
    }

    /**
     * Restore project from backup.
     */
    public static void restoreFromBackup(Path backupDir, Path projectPath) throws IOException {
        log.info("Restoring from backup: {} → {}", backupDir, projectPath);
        ConsoleUI.progress("Restoring from backup...");

        // Delete current project contents (except backup)
        deleteDirectory(projectPath);

        // Copy backup back
        copyDirectory(backupDir, projectPath);

        ConsoleUI.success("Project restored from backup.");
    }

    /**
     * Copy a directory tree.
     */
    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Skip common non-essential directories
                String dirName = dir.getFileName().toString();
                if (dirName.equals("target") || dirName.equals("build") ||
                    dirName.equals(".git") || dirName.equals("node_modules") ||
                    dirName.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                Path targetDir = target.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetFile = target.resolve(source.relativize(file));
                Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Delete a directory tree.
     */
    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static long countFiles(Path dir) throws IOException {
        return Files.walk(dir).filter(Files::isRegularFile).count();
    }
}
