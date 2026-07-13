package org.example.agent.budget;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

public class BudgetedToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final AgentBudget budget;

    public BudgetedToolCallback(ToolCallback delegate, AgentBudget budget) {
        this.delegate = delegate;
        this.budget = budget;
    }

    @Override
    public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }

    @Override
    public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }

    @Override
    public String call(String input) {
        budget.beforeToolCall(getToolDefinition().name());
        return delegate.call(input);
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        budget.beforeToolCall(getToolDefinition().name());
        return delegate.call(input, toolContext);
    }
}
