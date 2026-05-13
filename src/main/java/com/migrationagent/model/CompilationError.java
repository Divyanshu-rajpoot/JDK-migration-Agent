package com.migrationagent.model;

import java.nio.file.Path;

/**
 * Represents a parsed compilation error from javac / Maven build output.
 */
public class CompilationError {

    public enum ErrorType {
        SYMBOL_NOT_FOUND,
        INCOMPATIBLE_TYPES,
        DEPRECATED_API,
        REMOVED_API,
        ACCESS_ERROR,
        MODULE_ERROR,
        SYNTAX_ERROR,
        UNCHECKED_WARNING,
        OTHER
    }

    private final Path sourceFile;
    private final int lineNumber;
    private final int columnNumber;
    private final String errorMessage;
    private final String rawOutput;
    private ErrorType errorType;
    private boolean fixAttempted;
    private boolean fixed;

    public CompilationError(Path sourceFile, int lineNumber, int columnNumber,
                            String errorMessage, String rawOutput) {
        this.sourceFile = sourceFile;
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
        this.errorMessage = errorMessage;
        this.rawOutput = rawOutput;
        this.errorType = classifyError(errorMessage);
        this.fixAttempted = false;
        this.fixed = false;
    }

    public Path getSourceFile() { return sourceFile; }
    public int getLineNumber() { return lineNumber; }
    public int getColumnNumber() { return columnNumber; }
    public String getErrorMessage() { return errorMessage; }
    public String getRawOutput() { return rawOutput; }
    public ErrorType getErrorType() { return errorType; }
    public boolean isFixAttempted() { return fixAttempted; }
    public boolean isFixed() { return fixed; }

    public void setErrorType(ErrorType type) { this.errorType = type; }
    public void setFixAttempted(boolean b) { this.fixAttempted = b; }
    public void setFixed(boolean b) { this.fixed = b; }

    /**
     * Classify the compilation error based on the error message content.
     */
    private static ErrorType classifyError(String message) {
        if (message == null) return ErrorType.OTHER;
        String lower = message.toLowerCase();

        if (lower.contains("cannot find symbol") || lower.contains("symbol not found")) {
            return ErrorType.SYMBOL_NOT_FOUND;
        }
        if (lower.contains("incompatible types") || lower.contains("type mismatch")) {
            return ErrorType.INCOMPATIBLE_TYPES;
        }
        if (lower.contains("has been deprecated") || lower.contains("is deprecated")) {
            return ErrorType.DEPRECATED_API;
        }
        if (lower.contains("has been removed") || lower.contains("does not exist")) {
            return ErrorType.REMOVED_API;
        }
        if (lower.contains("not accessible") || lower.contains("not visible") ||
            lower.contains("does not export")) {
            return ErrorType.ACCESS_ERROR;
        }
        if (lower.contains("module") && (lower.contains("not found") || lower.contains("reads"))) {
            return ErrorType.MODULE_ERROR;
        }
        if (lower.contains("illegal") || lower.contains("expected") || lower.contains("';'")) {
            return ErrorType.SYNTAX_ERROR;
        }
        if (lower.contains("unchecked") || lower.contains("rawtype")) {
            return ErrorType.UNCHECKED_WARNING;
        }
        return ErrorType.OTHER;
    }

    @Override
    public String toString() {
        String fileName = sourceFile != null ? sourceFile.getFileName().toString() : "<unknown>";
        return String.format("%s:%d — [%s] %s", fileName, lineNumber, errorType, errorMessage);
    }
}
