package org.example.agent.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.single")
public class AgentBudgetProperties {
    private int maxToolCalls = 3;
    private int maxModelCalls = 4;
    private long maxTotalTokens = 12000;

    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public void setMaxModelCalls(int maxModelCalls) { this.maxModelCalls = maxModelCalls; }
    public long getMaxTotalTokens() { return maxTotalTokens; }
    public void setMaxTotalTokens(long maxTotalTokens) { this.maxTotalTokens = maxTotalTokens; }
}
