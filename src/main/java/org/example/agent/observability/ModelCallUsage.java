package org.example.agent.observability;

public record ModelCallUsage(String callId, String requestId, String taskId, AgentStage stage,
                             Integer round, String model, long inputTokens, long outputTokens,
                             long totalTokens, UsageSource source, long durationMs) {}
