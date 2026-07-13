package org.example.agent.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.workflow.model.PlannerDecision;
import org.example.agent.workflow.model.ToolType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerServiceTest {
    private final PlannerService planner = new PlannerService(new ObjectMapper());

    @Test
    void parsesAndNormalizesOneTypedStep() {
        var plan = planner.parse("""
                {"decision":"EXECUTE","steps":[
                  {"description":"query alerts","toolType":"QUERY_ALERTS","parameters":{}},
                  {"description":"must wait","toolType":"QUERY_LOGS","parameters":{}}
                ],"reason":"start with alerts"}
                """);
        assertThat(plan.decision()).isEqualTo(PlannerDecision.EXECUTE);
        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).stepId()).isNotBlank();
        assertThat(plan.steps().get(0).toolType()).isEqualTo(ToolType.QUERY_ALERTS);
    }

    @Test
    void rejectsInvalidPlannerOutput() {
        var plan = planner.parse("not-json");
        assertThat(plan.decision()).isEqualTo(PlannerDecision.ABORT);
        assertThat(plan.reason()).contains("Invalid planner output");
    }
}
