package org.example.agent.budget;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentBudgetTest {

    @Test
    void enforcesModelCallLimitBeforeInvocation() {
        AgentBudget budget = new AgentBudget(2, 1, 100);
        budget.beforeModelCall();
        assertThatThrownBy(budget::beforeModelCall)
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("模型调用次数");
    }

    @Test
    void enforcesToolCallLimitBeforeInvocation() {
        AgentBudget budget = new AgentBudget(1, 2, 100);
        budget.beforeToolCall("first");
        assertThatThrownBy(() -> budget.beforeToolCall("second"))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("工具调用次数");
    }

    @Test
    void enforcesAccumulatedTokenLimit() {
        AgentBudget budget = new AgentBudget(1, 2, 10);
        budget.addTokens(6);
        assertThatThrownBy(() -> budget.addTokens(5))
                .isInstanceOf(BudgetExceededException.class)
                .hasMessageContaining("Token");
        assertThat(budget.getTotalTokens()).isEqualTo(11);
    }
}
