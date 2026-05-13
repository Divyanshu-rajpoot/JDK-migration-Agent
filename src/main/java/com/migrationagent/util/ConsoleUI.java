package com.migrationagent.util;

/**
 * Console output with ANSI colors and formatting (ASCII-safe).
 */
public class ConsoleUI {

    // ANSI color codes
    private static final String RESET   = "\u001B[0m";
    private static final String RED     = "\u001B[31m";
    private static final String GREEN   = "\u001B[32m";
    private static final String YELLOW  = "\u001B[33m";
    private static final String BLUE    = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN    = "\u001B[36m";
    private static final String WHITE   = "\u001B[37m";
    private static final String BOLD    = "\u001B[1m";
    private static final String DIM     = "\u001B[2m";

    // Disable colors if not a terminal
    private static final boolean COLORS_ENABLED = System.console() != null ||
            System.getenv("FORCE_COLOR") != null;

    private static String colorize(String color, String text) {
        return COLORS_ENABLED ? color + text + RESET : text;
    }

    /** Print the application banner. */
    public static void printBanner() {
        String banner = """
                
                ==============================================================
                |                                                            |
                |  JDK MIGRATION AGENT                                       |
                |  Automated Java Application Migration System               |
                |  v1.0.0                                                    |
                |                                                            |
                ==============================================================
                """;
        System.out.println(colorize(CYAN, banner));
    }

    /** Info message (blue) */
    public static void info(String message) {
        System.out.println(colorize(BLUE, "  [INFO] ") + message);
    }

    /** Success message (green) */
    public static void success(String message) {
        System.out.println(colorize(GREEN, "  [OK] ") + colorize(GREEN, message));
    }

    /** Warning message (yellow) */
    public static void warn(String message) {
        System.out.println(colorize(YELLOW, "  [WARN] ") + colorize(YELLOW, message));
    }

    /** Error message (red) */
    public static void error(String message) {
        System.out.println(colorize(RED, "  [ERROR] ") + colorize(RED, message));
    }

    /** Agent header (magenta, bold) */
    public static void agentStart(String agentName) {
        String line = "-".repeat(60);
        System.out.println();
        System.out.println(colorize(MAGENTA + BOLD, "  [*] Agent: " + agentName));
        System.out.println(colorize(DIM, "  " + line));
    }

    /** Agent completed */
    public static void agentComplete(String agentName, boolean success) {
        if (success) {
            System.out.println(colorize(GREEN, "  [OK] " + agentName + " -- completed successfully."));
        } else {
            System.out.println(colorize(RED, "  [ERROR] " + agentName + " -- completed with issues."));
        }
    }

    /** Progress indicator */
    public static void progress(String message) {
        System.out.println(colorize(CYAN, "  > ") + message);
    }

    /** Sub-step indicator */
    public static void step(String message) {
        System.out.println(colorize(DIM, "    - ") + message);
    }

    /** Healing loop iteration */
    public static void healingIteration(int iteration, int maxRetries) {
        System.out.println(colorize(YELLOW + BOLD,
                String.format("  [LOOP] Self-healing iteration %d/%d", iteration, maxRetries)));
    }

    /** Section header */
    public static void section(String title) {
        String line = "=".repeat(60);
        System.out.println();
        System.out.println(colorize(BOLD, "  " + line));
        System.out.println(colorize(BOLD, "  " + title));
        System.out.println(colorize(BOLD, "  " + line));
    }

    /** Print a key-value pair */
    public static void keyValue(String key, String value) {
        System.out.printf("  %-25s %s%n", colorize(DIM, key + ":"), value);
    }
}
