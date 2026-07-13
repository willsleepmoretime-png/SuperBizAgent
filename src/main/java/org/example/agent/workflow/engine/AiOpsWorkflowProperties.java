package org.example.agent.workflow.engine;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent.aiops")
public class AiOpsWorkflowProperties {
    private int maxRounds = 6;
    private int maxToolCalls = 8;
    private int maxModelCalls = 10;
    private long maxTotalTokens = 30000;
    private long reportTokenReserve = 4000;
    private int toolFailureLimit = 3;

    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
    public int getMaxModelCalls() { return maxModelCalls; }
    public void setMaxModelCalls(int maxModelCalls) { this.maxModelCalls = maxModelCalls; }
    public long getMaxTotalTokens() { return maxTotalTokens; }
    public void setMaxTotalTokens(long maxTotalTokens) { this.maxTotalTokens = maxTotalTokens; }
    public long getReportTokenReserve() { return reportTokenReserve; }
    public void setReportTokenReserve(long reportTokenReserve) { this.reportTokenReserve = reportTokenReserve; }
    public int getToolFailureLimit() { return toolFailureLimit; }
    public void setToolFailureLimit(int toolFailureLimit) { this.toolFailureLimit = toolFailureLimit; }
}
