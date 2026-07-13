package org.example.memory.context;

import java.util.List;
import java.util.Map;

public record ContextUsage(
        int maxInputTokens,
        int reservedOutputTokens,
        int estimatedInputTokens,
        Map<String, Integer> breakdown,
        int includedMessagePairs,
        int droppedMessagePairs,
        List<String> warnings) {
}
