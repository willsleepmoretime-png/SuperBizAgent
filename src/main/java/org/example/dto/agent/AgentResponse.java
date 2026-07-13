package org.example.dto.agent;

import org.example.agent.observability.TokenUsageSummary;
import org.example.memory.context.ContextUsage;

/** Unified non-streaming response while retaining legacy response fields. */
public class AgentResponse {
    private boolean success;
    private String answer;
    private String errorMessage;
    private TokenUsageSummary usage;
    private ContextUsage contextUsage;

    public static AgentResponse success(String answer) {
        AgentResponse response = new AgentResponse();
        response.success = true;
        response.answer = answer;
        return response;
    }

    public static AgentResponse error(String errorMessage) {
        AgentResponse response = new AgentResponse();
        response.success = false;
        response.errorMessage = errorMessage;
        return response;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public TokenUsageSummary getUsage() { return usage; }
    public void setUsage(TokenUsageSummary usage) { this.usage = usage; }
    public ContextUsage getContextUsage() { return contextUsage; }
    public void setContextUsage(ContextUsage contextUsage) { this.contextUsage = contextUsage; }
}
