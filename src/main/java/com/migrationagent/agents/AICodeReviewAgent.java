package com.migrationagent.agents;

import com.migrationagent.core.AgentResult;
import com.migrationagent.core.MigrationContext;
import com.migrationagent.llm.LlmClient;
import com.migrationagent.llm.MigrationPrompts;
import com.migrationagent.util.ConsoleUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reviews code changes made during migration using AI to ensure quality,
 * performance, and security. Optimized to run only on modified files to save credits.
 */
public class AICodeReviewAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(AICodeReviewAgent.class);

    @Override
    public String getName() {
        return "AI Code Reviewer";
    }

    @Override
    public AgentResult execute(MigrationContext context) {
        long start = System.currentTimeMillis();
        ConsoleUI.agentStart(getName());

        if (!context.getConfig().isAiEnabled()) {
            ConsoleUI.info("AI is disabled. Skipping code review.");
            ConsoleUI.agentComplete(getName(), true);
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.SKIPPED)
                    .message("AI disabled")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        LlmClient llm = new LlmClient(context.getConfig().getLlmConfig());
        if (!llm.isAvailable()) {
            ConsoleUI.warn("AI not reachable. Skipping review.");
            ConsoleUI.agentComplete(getName(), false);
            return AgentResult.builder(getName())
                    .status(AgentResult.Status.SKIPPED)
                    .message("AI unreachable")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }

        Map<String, String> originalSources = context.getOriginalSources();
        Map<String, String> reviews = new HashMap<>();

        int reviewedCount = 0;

        for (Path modifiedFile : context.getModifiedFiles()) {
            try {
                String currentSource = Files.readString(modifiedFile);
                String originalSource = originalSources.get(modifiedFile.toAbsolutePath().toString());

                if (originalSource != null && !originalSource.equals(currentSource)) {
                    ConsoleUI.progress("Reviewing changes in " + modifiedFile.getFileName() + "...");
                    
                    String prompt = "Review the following Java code migration from JDK " + context.getSourceVersion() +
                            " to JDK " + context.getTargetVersion() + ".\n" +
                            "Keep your review under 3 sentences. Focus on potential bugs, security issues, or performance regressions.\n\n" +
                            "ORIGINAL CODE:\n```java\n" + originalSource + "\n```\n\n" +
                            "MODIFIED CODE:\n```java\n" + currentSource + "\n```";

                    String review = llm.chat("You are an expert Java architect performing a strict code review.", prompt);
                    
                    if (review != null && !review.isBlank()) {
                        reviews.put(modifiedFile.getFileName().toString(), review.trim());
                        ConsoleUI.step("Review generated for " + modifiedFile.getFileName());
                        reviewedCount++;
                    }
                }
            } catch (Exception e) {
                log.warn("Could not review file: {}", modifiedFile, e);
            }
        }

        // Add reviews to the context for the ReportAgent to use
        context.setCodeReviews(reviews);

        ConsoleUI.agentComplete(getName(), true);
        return AgentResult.builder(getName())
                .status(AgentResult.Status.SUCCESS)
                .message("Reviewed " + reviewedCount + " files")
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }
}
