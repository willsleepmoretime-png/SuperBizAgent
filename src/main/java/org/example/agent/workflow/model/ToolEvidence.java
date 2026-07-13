package org.example.agent.workflow.model;

import java.time.Instant;

public record ToolEvidence(String evidenceId, String stepId, ToolType toolType, boolean success,
                           String content, String errorMessage, Instant createdAt) {
    public static ToolEvidence success(String id, InvestigationStep step, String content) {
        return new ToolEvidence(id, step.stepId(), step.toolType(), true, content, null, Instant.now());
    }

    public static ToolEvidence failure(String id, InvestigationStep step, String error) {
        return new ToolEvidence(id, step.stepId(), step.toolType(), false, null, error, Instant.now());
    }
}
