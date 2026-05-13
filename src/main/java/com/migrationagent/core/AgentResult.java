package com.migrationagent.core;

/**
 * Result returned by each agent after execution.
 */
public class AgentResult {

    public enum Status {
        SUCCESS,
        PARTIAL_SUCCESS,
        FAILURE,
        SKIPPED
    }

    private final Status status;
    private final String agentName;
    private final String message;
    private final int issuesFound;
    private final int issuesFixed;
    private final long durationMs;

    private AgentResult(Builder builder) {
        this.status = builder.status;
        this.agentName = builder.agentName;
        this.message = builder.message;
        this.issuesFound = builder.issuesFound;
        this.issuesFixed = builder.issuesFixed;
        this.durationMs = builder.durationMs;
    }

    public Status getStatus() { return status; }
    public String getAgentName() { return agentName; }
    public String getMessage() { return message; }
    public int getIssuesFound() { return issuesFound; }
    public int getIssuesFixed() { return issuesFixed; }
    public long getDurationMs() { return durationMs; }

    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isFailure() { return status == Status.FAILURE; }

    @Override
    public String toString() {
        return String.format("[%s] %s — %s (found: %d, fixed: %d, %dms)",
                status, agentName, message, issuesFound, issuesFixed, durationMs);
    }

    public static Builder builder(String agentName) {
        return new Builder(agentName);
    }

    public static class Builder {
        private final String agentName;
        private Status status = Status.SUCCESS;
        private String message = "";
        private int issuesFound = 0;
        private int issuesFixed = 0;
        private long durationMs = 0;

        Builder(String agentName) { this.agentName = agentName; }

        public Builder status(Status s) { this.status = s; return this; }
        public Builder message(String m) { this.message = m; return this; }
        public Builder issuesFound(int n) { this.issuesFound = n; return this; }
        public Builder issuesFixed(int n) { this.issuesFixed = n; return this; }
        public Builder durationMs(long ms) { this.durationMs = ms; return this; }

        public AgentResult build() { return new AgentResult(this); }
    }
}
