package org.example.agent.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.budget.BudgetedChatModel;
import org.example.agent.workflow.model.IncidentState;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.example.agent.observability.AgentStage;
import org.example.agent.observability.TokenUsageCollector;

@Service
public class ReportGenerator {
    private final ObjectMapper objectMapper;

    public ReportGenerator(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public String generate(IncidentState state, ChatModel model) {
        return generate(state, model, new TokenUsageCollector());
    }

    public String generate(IncidentState state, ChatModel model, TokenUsageCollector collector) {
        String prompt = """
                你是 AIOps Reporter。只能根据下面的结构化证据生成 Markdown《告警分析报告》，不得调用工具或补造事实。
                每个关键结论必须引用对应 Evidence ID，格式 [evidence:ev-xxx]。
                如果证据不足、失败或流程提前终止，必须明确写出“无法确认”及原因。
                用户任务：%s
                流程状态：%s
                终止原因：%s
                证据：%s
                """.formatted(state.getUserRequest(), state.getStatus(), state.getTerminationReason(), json(state.getEvidence()));
        ChatResponse response = new BudgetedChatModel(model, state.getBudget(), collector, AgentStage.REPORTER,
                state.getTaskId(), state.getTaskId(), state::getCurrentRound).call(new Prompt(prompt));
        return response.getResult().getOutput().getText();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "[]"; }
    }
}
