package org.example.agent.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.workflow.model.IncidentState;
import org.example.agent.workflow.model.InvestigationPlan;
import org.example.agent.workflow.model.InvestigationStep;
import org.example.agent.workflow.model.PlannerDecision;
import org.example.agent.workflow.model.StepStatus;
import org.example.agent.workflow.model.ToolEvidence;
import org.example.agent.workflow.model.ToolType;
import org.example.dto.agent.AiOpsCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class AiOpsWorkflowEngineTest {

    @Test
    void executesOneTypedStepThenFinishesAndReports() {
        SequencePlanner planner = new SequencePlanner();
        StubExecutor executor = new StubExecutor();
        CapturingReporter reporter = new CapturingReporter();
        AiOpsWorkflowEngine engine = new AiOpsWorkflowEngine(planner, executor, reporter, properties(4), new ObjectMapper());
        AiOpsCommand command = new AiOpsCommand();
        command.setUserRequest("check alerts");

        List<String> events = new ArrayList<>();
        var result = engine.run(command, null, new org.springframework.ai.tool.ToolCallback[0],
                event -> events.add(event.getType()));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.report()).contains("evidence=1");
        assertThat(executor.calls).isEqualTo(1);
        assertThat(reporter.captured.getEvidence()).hasSize(1);
        assertThat(events).containsExactly("plan", "step_started", "tool_result", "plan");
    }

    @Test
    void stopsAtMaximumRoundsInJava() {
        AiOpsWorkflowEngine engine = new AiOpsWorkflowEngine(new EndlessPlanner(), new StubExecutor(),
                new CapturingReporter(), properties(1), new ObjectMapper());
        var result = engine.run(AiOpsCommand.legacyDefault(), null, new org.springframework.ai.tool.ToolCallback[0]);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.errorMessage()).contains("Maximum planning rounds");
    }

    private AiOpsWorkflowProperties properties(int rounds) {
        AiOpsWorkflowProperties value = new AiOpsWorkflowProperties();
        value.setMaxRounds(rounds);
        value.setMaxToolCalls(5);
        value.setMaxModelCalls(8);
        value.setMaxTotalTokens(10000);
        value.setReportTokenReserve(100);
        return value;
    }

    private static class SequencePlanner extends PlannerService {
        int calls;
        SequencePlanner() { super(new ObjectMapper()); }
        @Override public InvestigationPlan plan(IncidentState state, org.springframework.ai.chat.model.ChatModel model,
                                                org.example.agent.observability.TokenUsageCollector collector) {
            calls++;
            if (calls == 1) return executePlan(calls);
            return new InvestigationPlan(PlannerDecision.FINISH, List.of(), "enough evidence");
        }
    }

    private static class EndlessPlanner extends PlannerService {
        int calls;
        EndlessPlanner() { super(new ObjectMapper()); }
        @Override public InvestigationPlan plan(IncidentState state, org.springframework.ai.chat.model.ChatModel model,
                                                org.example.agent.observability.TokenUsageCollector collector) {
            return executePlan(++calls);
        }
    }

    private static InvestigationPlan executePlan(int number) {
        return new InvestigationPlan(PlannerDecision.EXECUTE, List.of(new InvestigationStep(
                "s" + number, "query", ToolType.QUERY_ALERTS, Map.of("round", number), StepStatus.PENDING)), "continue");
    }

    private static class StubExecutor extends ToolExecutor {
        int calls;
        StubExecutor() { super(null, null, null, Optional.empty(), new ObjectMapper()); }
        @Override public ToolEvidence execute(IncidentState state, InvestigationStep step,
                                              org.springframework.ai.tool.ToolCallback[] callbacks) {
            calls++;
            state.getBudget().beforeToolCall(step.toolType().name());
            return ToolEvidence.success("ev-" + calls, step, "alert evidence");
        }
    }

    private static class CapturingReporter extends ReportGenerator {
        IncidentState captured;
        CapturingReporter() { super(new ObjectMapper()); }
        @Override public String generate(IncidentState state, org.springframework.ai.chat.model.ChatModel model,
                                         org.example.agent.observability.TokenUsageCollector collector) {
            captured = state;
            return "report evidence=" + state.getEvidence().size();
        }
    }
}
