package org.example.agent.observability;

import java.util.List;

public record TokenUsageSummary(long inputTokens, long outputTokens, long totalTokens,
                                long totalDurationMs, List<ModelCallUsage> calls) {
    public TokenUsageSummary { calls = List.copyOf(calls); }
}
