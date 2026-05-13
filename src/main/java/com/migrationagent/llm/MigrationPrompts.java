package com.migrationagent.llm;

/**
 * Pre-built prompt templates for the migration agent's LLM interactions.
 *
 * <p>Each prompt is carefully designed to get precise, actionable code fixes
 * from the LLM — not generic advice. The system prompt establishes the LLM
 * as a Java migration expert, and each user prompt provides the exact context
 * needed for the specific task.</p>
 */
public final class MigrationPrompts {

    private MigrationPrompts() {} // utility class

    // ═══════════════════════════════════════════════════════════════════
    //  System Prompt — establishes the AI's persona and constraints
    // ═══════════════════════════════════════════════════════════════════

    public static final String SYSTEM_PROMPT = """
            You are an expert Java developer specializing in JDK version migrations.
            You have deep knowledge of:
            - All JDK API changes from Java 6 through Java 21
            - Module system (JPMS) introduced in Java 9
            - Removed APIs: sun.misc.*, javax.xml.bind.*, CORBA, Nashorn, etc.
            - Deprecated APIs across all versions
            - Build tool configurations (Maven, Gradle)
            - Common library compatibility across JDK versions

            RULES:
            1. Always respond with ONLY the fixed Java code — no explanations unless asked.
            2. Preserve all existing functionality — only change what's needed for migration.
            3. Keep the same code style and formatting as the original.
            4. If you add new imports, use the standard JDK replacements.
            5. If there is truly no fix possible, respond with exactly: NO_FIX_AVAILABLE
            6. Never suggest downgrading the JDK version.
            7. Respond with the COMPLETE fixed file, not just the changed lines.
            """;

    // ═══════════════════════════════════════════════════════════════════
    //  Fix Compilation Error — most common use case
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Build a prompt to fix a compilation error.
     *
     * @param sourceCode     the full source file content
     * @param errorMessage   the javac error message
     * @param lineNumber     the line number of the error
     * @param sourceVersion  JDK version the code was written for
     * @param targetVersion  JDK version being migrated to
     */
    public static String fixCompilationError(String sourceCode, String errorMessage,
                                              int lineNumber, int sourceVersion, int targetVersion) {
        return String.format("""
                Fix the following Java compilation error. The code is being migrated from JDK %d to JDK %d.

                COMPILATION ERROR at line %d:
                %s

                SOURCE CODE:
                ```java
                %s
                ```

                Return ONLY the complete fixed Java source code.
                """, sourceVersion, targetVersion, lineNumber, errorMessage, sourceCode);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Fix Multiple Errors in a File
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Build a prompt to fix multiple compilation errors in one file.
     */
    public static String fixMultipleErrors(String sourceCode, String errorsText,
                                            int sourceVersion, int targetVersion) {
        return String.format("""
                Fix ALL the following Java compilation errors. The code is being migrated from JDK %d to JDK %d.

                COMPILATION ERRORS:
                %s

                SOURCE CODE:
                ```java
                %s
                ```

                Return ONLY the complete fixed Java source code with ALL errors resolved.
                """, sourceVersion, targetVersion, errorsText, sourceCode);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Analyze Migration Issues
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Ask the LLM to analyze code for migration issues.
     */
    public static String analyzeMigrationIssues(String sourceCode, int sourceVersion, int targetVersion) {
        return String.format("""
                Analyze the following Java code for migration issues when moving from JDK %d to JDK %d.

                For each issue found, respond in this EXACT format (one per line):
                ISSUE|<severity>|<line_number>|<description>|<suggested_fix>

                Where severity is one of: ERROR, WARNING, INFO

                SOURCE CODE:
                ```java
                %s
                ```
                """, sourceVersion, targetVersion, sourceCode);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Suggest Dependency Upgrade
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Ask the LLM for the best compatible version of a dependency.
     */
    public static String suggestDependencyVersion(String groupId, String artifactId,
                                                    String currentVersion, int targetJdk) {
        return String.format("""
                What is the recommended version of %s:%s for JDK %d?

                Current version: %s
                Target JDK: %d

                Respond in EXACTLY this format:
                VERSION|<recommended_version>|<reason>

                If the current version is already compatible, respond:
                COMPATIBLE|<current_version>|Already compatible with JDK %d

                If the library has been replaced, respond:
                REPLACED|<new_groupId>:<new_artifactId>:<new_version>|<migration_notes>
                """, groupId, artifactId, targetJdk, currentVersion, targetJdk, targetJdk);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Check if Module is Tightly Coupled
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Ask the LLM whether a module can be migrated or is tightly coupled.
     */
    public static String checkTightCoupling(String pomContent, String sampleCode,
                                              int sourceVersion, int targetVersion) {
        return String.format("""
                Analyze if this Java module can be migrated from JDK %d to JDK %d,
                or if it is tightly coupled to JDK %d and CANNOT be migrated.

                POM.XML:
                ```xml
                %s
                ```

                SAMPLE CODE:
                ```java
                %s
                ```

                Respond in EXACTLY this format:
                MIGRATABLE|<confidence_percent>|<notes>
                or
                TIGHTLY_COUPLED|<confidence_percent>|<blocking_reason>
                """, sourceVersion, targetVersion, sourceVersion, pomContent, sampleCode);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Explain Error (for human-readable report)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Ask the LLM to explain a migration blocker in human terms.
     */
    public static String explainBlocker(String errorContext, int sourceVersion, int targetVersion) {
        return String.format("""
                Explain the following JDK %d to JDK %d migration issue in simple terms
                for a developer who needs to fix it manually.

                ISSUE:
                %s

                Provide:
                1. What the issue is
                2. Why it can't be auto-fixed
                3. Step-by-step instructions to fix it manually
                4. Any gotchas or common mistakes

                Keep it concise (max 10 lines).
                """, sourceVersion, targetVersion, errorContext);
    }
}
