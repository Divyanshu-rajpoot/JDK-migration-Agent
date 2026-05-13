package com.migrationagent.agents;

import com.migrationagent.core.AgentResult;
import com.migrationagent.core.MigrationContext;

/**
 * Base interface for all migration agents.
 * Each agent performs a specific phase of the migration process.
 */
public interface Agent {

    /** Human-readable name for logging and reporting. */
    String getName();

    /**
     * Execute the agent's task.
     *
     * @param context shared migration context
     * @return result of agent execution
     */
    AgentResult execute(MigrationContext context);
}
