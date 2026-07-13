package org.example.memory.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "memory.context")
public class ContextBudgetProperties {
    private int maxInputTokens = 8000;
    private int reserveOutputTokens = 2000;
    private int systemPromptTokens = 1000;
    private int workingMemoryTokens = 1800;
    private int summaryTokens = 600;

    public int getMaxInputTokens() { return maxInputTokens; }
    public void setMaxInputTokens(int maxInputTokens) { this.maxInputTokens = maxInputTokens; }
    public int getReserveOutputTokens() { return reserveOutputTokens; }
    public void setReserveOutputTokens(int reserveOutputTokens) { this.reserveOutputTokens = reserveOutputTokens; }
    public int getSystemPromptTokens() { return systemPromptTokens; }
    public void setSystemPromptTokens(int systemPromptTokens) { this.systemPromptTokens = systemPromptTokens; }
    public int getWorkingMemoryTokens() { return workingMemoryTokens; }
    public void setWorkingMemoryTokens(int workingMemoryTokens) { this.workingMemoryTokens = workingMemoryTokens; }
    public int getSummaryTokens() { return summaryTokens; }
    public void setSummaryTokens(int summaryTokens) { this.summaryTokens = summaryTokens; }
}
