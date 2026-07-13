package org.example.agent.workflow.model;

import java.util.List;

public record InvestigationPlan(PlannerDecision decision, List<InvestigationStep> steps, String reason) {
    public InvestigationPlan {
        decision = decision == null ? PlannerDecision.ABORT : decision;
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
