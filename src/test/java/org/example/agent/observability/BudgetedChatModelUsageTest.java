package org.example.agent.observability;

import org.example.agent.budget.AgentBudget;
import org.example.agent.budget.BudgetedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetedChatModelUsageTest {

    @Test
    void recordsEstimatedUsageStageRoundAndDuration() {
        TokenUsageCollector collector = new TokenUsageCollector();
        ChatModel delegate = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("response"))));
        BudgetedChatModel model = new BudgetedChatModel(delegate, new AgentBudget(1, 2, 1000), collector,
                AgentStage.PLANNER, "request-1", "task-1", () -> 2);

        model.call(new Prompt("input text"));

        ModelCallUsage usage = collector.summary().calls().get(0);
        assertThat(usage.stage()).isEqualTo(AgentStage.PLANNER);
        assertThat(usage.round()).isEqualTo(2);
        assertThat(usage.requestId()).isEqualTo("request-1");
        assertThat(usage.taskId()).isEqualTo("task-1");
        assertThat(usage.source()).isEqualTo(UsageSource.ESTIMATED);
        assertThat(usage.totalTokens()).isPositive();
        assertThat(usage.durationMs()).isGreaterThanOrEqualTo(0);
    }
}
