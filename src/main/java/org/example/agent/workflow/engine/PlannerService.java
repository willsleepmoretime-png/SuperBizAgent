package org.example.agent.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.budget.BudgetedChatModel;
import org.example.agent.workflow.model.IncidentState;
import org.example.agent.workflow.model.InvestigationPlan;
import org.example.agent.workflow.model.InvestigationStep;
import org.example.agent.workflow.model.PlannerDecision;
import org.example.agent.workflow.model.StepStatus;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.example.agent.observability.AgentStage;
import org.example.agent.observability.TokenUsageCollector;

import java.util.List;
import java.util.UUID;

@Service
public class PlannerService {
    private final ObjectMapper objectMapper;

    public PlannerService(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public InvestigationPlan plan(IncidentState state, ChatModel model) {
        return plan(state, model, new TokenUsageCollector());
    }

    public InvestigationPlan plan(IncidentState state, ChatModel model, TokenUsageCollector collector) {
        String prompt = """
                你是 AIOps Planner。你没有任何工具，只能根据任务、已有证据和预算制定下一步。
                只输出 JSON：{"decision":"EXECUTE|FINISH|ABORT|REQUIRE_HUMAN_INPUT","steps":[{"stepId":"可选","description":"...","toolType":"QUERY_ALERTS|QUERY_METRICS|QUERY_LOGS|SEARCH_INTERNAL_DOCS|GET_CURRENT_TIME","parameters":{}}],"reason":"..."}
                decision=EXECUTE 时只能给出一个下一步；证据足够时 FINISH；不得编造证据。
                任务：%s
                当前轮次：%d/%d
                剩余工具次数：%d
                剩余模型次数：%d
                已有证据：%s
                """.formatted(state.getUserRequest(), state.getCurrentRound(), state.getMaxRounds(),
                state.getBudget().getRemainingToolCalls(), state.getBudget().getRemainingModelCalls(),
                writeJson(state.getEvidence()));
        ChatResponse response = new BudgetedChatModel(model, state.getBudget(), collector, AgentStage.PLANNER,
                state.getTaskId(), state.getTaskId(), state::getCurrentRound).call(new Prompt(prompt));
        return parse(response.getResult().getOutput().getText());
    }

    InvestigationPlan parse(String raw) {
        try {
            String json = stripFence(raw);
            InvestigationPlan parsed = objectMapper.readValue(json, InvestigationPlan.class);
            List<InvestigationStep> normalized = parsed.steps().stream().limit(1)
                    .map(step -> new InvestigationStep(
                            step.stepId() == null || step.stepId().isBlank() ? UUID.randomUUID().toString() : step.stepId(),
                            step.description(), step.toolType(), step.parameters(), StepStatus.PENDING))
                    .toList();
            if (parsed.decision() == PlannerDecision.EXECUTE && normalized.isEmpty()) {
                return new InvestigationPlan(PlannerDecision.ABORT, List.of(), "Planner returned no executable step");
            }
            return new InvestigationPlan(parsed.decision(), normalized, parsed.reason());
        } catch (Exception e) {
            return new InvestigationPlan(PlannerDecision.ABORT, List.of(), "Invalid planner output: " + e.getMessage());
        }
    }

    private String stripFence(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("```")) value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        return value;
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "[]"; }
    }
}
