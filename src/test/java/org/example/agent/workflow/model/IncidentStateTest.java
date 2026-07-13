package org.example.agent.workflow.model;

import org.example.agent.budget.AgentBudget;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import org.example.agent.routing.ToolCapability;

class IncidentStateTest {

    @Test
    void tracksFailuresAndDuplicateFingerprintsInCode() {
        IncidentState state = new IncidentState("task", "request", new AgentBudget(5, 5, 1000), 3, 2);
        InvestigationStep step = new InvestigationStep("s1", "logs", ToolType.QUERY_LOGS, Map.of(), StepStatus.PENDING);

        state.addEvidence(ToolEvidence.failure("e1", step, "failed"));
        assertThat(state.failureLimitReached(ToolType.QUERY_LOGS)).isFalse();
        state.addEvidence(ToolEvidence.failure("e2", step, "failed again"));
        assertThat(state.failureLimitReached(ToolType.QUERY_LOGS)).isTrue();
        assertThat(state.registerInvocation("same")).isTrue();
        assertThat(state.registerInvocation("same")).isFalse();
    }

    @Test
    void mapsWorkflowToolsToRouteCapabilities() {
        IncidentState state = new IncidentState("task", "request", new AgentBudget(5, 5, 1000), 3, 2,
                Set.of(ToolCapability.METRICS));
        assertThat(state.allows(ToolType.QUERY_ALERTS)).isTrue();
        assertThat(state.allows(ToolType.QUERY_LOGS)).isFalse();
        assertThat(state.allows(ToolType.SEARCH_INTERNAL_DOCS)).isFalse();
    }
}
