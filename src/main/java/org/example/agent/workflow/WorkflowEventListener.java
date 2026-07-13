package org.example.agent.workflow;

import org.example.dto.agent.AgentSseEvent;

@FunctionalInterface
public interface WorkflowEventListener {
    void onEvent(AgentSseEvent event);
    static WorkflowEventListener noop() { return event -> {}; }
}
