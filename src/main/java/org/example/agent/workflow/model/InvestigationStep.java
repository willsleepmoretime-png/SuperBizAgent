package org.example.agent.workflow.model;

import java.util.Map;

public record InvestigationStep(String stepId, String description, ToolType toolType,
                                Map<String, Object> parameters, StepStatus status) {
    public InvestigationStep {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        status = status == null ? StepStatus.PENDING : status;
    }

    public InvestigationStep withStatus(StepStatus next) {
        return new InvestigationStep(stepId, description, toolType, parameters, next);
    }
}
