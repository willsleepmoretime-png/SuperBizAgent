package org.example.agent.budget;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class AgentBudget {
    private final int maxToolCalls;
    private final int maxModelCalls;
    private final long maxTotalTokens;
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicLong totalTokens = new AtomicLong();

    public AgentBudget(int maxToolCalls, int maxModelCalls, long maxTotalTokens) {
        if (maxToolCalls < 0 || maxModelCalls < 1 || maxTotalTokens < 1) {
            throw new IllegalArgumentException("Agent budget values are invalid");
        }
        this.maxToolCalls = maxToolCalls;
        this.maxModelCalls = maxModelCalls;
        this.maxTotalTokens = maxTotalTokens;
    }

    public static AgentBudget from(AgentBudgetProperties properties) {
        return new AgentBudget(properties.getMaxToolCalls(), properties.getMaxModelCalls(), properties.getMaxTotalTokens());
    }

    public void beforeModelCall() {
        int used = modelCalls.incrementAndGet();
        if (used > maxModelCalls) {
            throw new BudgetExceededException("模型调用次数超过预算: " + maxModelCalls);
        }
    }

    public void beforeToolCall(String toolName) {
        int used = toolCalls.incrementAndGet();
        if (used > maxToolCalls) {
            throw new BudgetExceededException("工具调用次数超过预算: " + maxToolCalls + ", tool=" + toolName);
        }
    }

    public void addTokens(long tokens) {
        if (tokens <= 0) return;
        long used = totalTokens.addAndGet(tokens);
        if (used > maxTotalTokens) {
            throw new BudgetExceededException("Token 使用量超过预算: " + maxTotalTokens);
        }
    }

    public int getToolCalls() { return toolCalls.get(); }
    public int getModelCalls() { return modelCalls.get(); }
    public long getTotalTokens() { return totalTokens.get(); }
    public int getMaxToolCalls() { return maxToolCalls; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public long getMaxTotalTokens() { return maxTotalTokens; }
    public long getRemainingTokens() { return Math.max(0, maxTotalTokens - totalTokens.get()); }
    public int getRemainingToolCalls() { return Math.max(0, maxToolCalls - toolCalls.get()); }
    public int getRemainingModelCalls() { return Math.max(0, maxModelCalls - modelCalls.get()); }
}
