package com.migrationagent.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.migrationagent.core.AgentResult;
import com.migrationagent.core.MigrationContext;
import com.migrationagent.model.MigrationReport;
import com.migrationagent.util.ConsoleUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Generates and displays the final migration report.
 */
public class ReportAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ReportAgent.class);

    @Override
    public String getName() { return "Report Generator"; }

    @Override
    public AgentResult execute(MigrationContext context) {
        long start = System.currentTimeMillis();
        ConsoleUI.agentStart(getName());

        MigrationReport report = context.getReport();
        report.setFilesModified(context.getModifiedFiles().size());
        report.complete();

        // Print report to console
        String textReport = report.generateTextReport();
        System.out.println(textReport);

        // Save report to file
        try {
            Path reportFile = context.getProjectPath().resolve("migration-report.txt");
            Files.writeString(reportFile, textReport);
            ConsoleUI.success("Report saved to: " + reportFile);

            // Also save a JSON summary
            Path jsonReport = context.getProjectPath().resolve("migration-report.json");
            Files.writeString(jsonReport, generateJsonSummary(report, context));
            ConsoleUI.success("JSON report saved to: " + jsonReport);

        } catch (IOException e) {
            log.error("Could not save report file", e);
            ConsoleUI.warn("Could not save report to file: " + e.getMessage());
        }

        ConsoleUI.agentComplete(getName(), true);
        return AgentResult.builder(getName())
                .status(AgentResult.Status.SUCCESS)
                .message("Report generated")
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private String generateJsonSummary(MigrationReport report, MigrationContext context) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        
        ObjectNode summary = root.putObject("migrationSummary");
        summary.put("sourceVersion", context.getSourceVersion());
        summary.put("targetVersion", context.getTargetVersion());
        summary.put("projectPath", context.getProjectPath().toString());
        summary.put("success", report.isMigrationSuccessful());
        summary.put("durationMs", report.getDuration().toMillis());
        summary.put("filesAnalyzed", report.getFilesAnalyzed());
        summary.put("filesModified", report.getFilesModified());
        summary.put("compilationAttempts", report.getCompilationAttempts());
        summary.put("totalIssues", report.getAllIssues().size());
        summary.put("resolvedIssues", report.getAllIssues().stream().filter(i -> i.isResolved()).count());
        summary.put("unresolvedIssues", report.getUnresolvedIssues().size());
        summary.put("blockers", report.getBlockers().size());

        ObjectNode depChanges = root.putObject("dependencyChanges");
        for (Map.Entry<String, String> entry : report.getDependencyChanges().entrySet()) {
            depChanges.put(entry.getKey(), entry.getValue());
        }

        ArrayNode actions = root.putArray("actionsPerformed");
        for (String action : report.getActionsPerformed()) {
            actions.add(action);
        }
        
        // Add Diffs and AI Reviews
        ArrayNode diffsNode = root.putArray("fileChanges");
        Map<String, String> originalSources = context.getOriginalSources();
        Map<String, String> reviews = context.getCodeReviews();
        
        for (Path modifiedFile : context.getModifiedFiles()) {
            try {
                String absPath = modifiedFile.toAbsolutePath().toString();
                String original = originalSources.getOrDefault(absPath, "");
                String current = Files.readString(modifiedFile);
                String fileName = modifiedFile.getFileName().toString();
                
                ObjectNode fileNode = diffsNode.addObject();
                fileNode.put("fileName", fileName);
                fileNode.put("absolutePath", absPath);
                fileNode.put("originalCode", original);
                fileNode.put("modifiedCode", current);
                
                if (reviews.containsKey(fileName)) {
                    fileNode.put("aiReview", reviews.get(fileName));
                }
            } catch (Exception e) {
                log.debug("Failed to read modified file for report: {}", modifiedFile, e);
            }
        }

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.error("Failed to generate JSON report", e);
            return "{}";
        }
    }
}
