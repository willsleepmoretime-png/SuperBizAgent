package org.example.memory.context;

import org.example.memory.working.WorkingMemory;
import org.example.memory.working.WorkingMemoryMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ContextBudgetManager {
    private final TokenEstimator estimator;
    private final ContextBudgetProperties properties;

    public ContextBudgetManager(TokenEstimator estimator, ContextBudgetProperties properties) {
        this.estimator = estimator;
        this.properties = properties;
    }

    public MemoryContext fit(String question, WorkingMemory memory) {
        int questionTokens = estimator.estimate(question);
        int availableInput = Math.max(0, properties.getMaxInputTokens() - properties.getReserveOutputTokens());
        if (properties.getSystemPromptTokens() + questionTokens > availableInput) {
            throw new IllegalArgumentException("当前问题过长，超过本次模型输入预算，请缩短问题或改为上传文件");
        }
        int availableForMemory = Math.max(0, availableInput - properties.getSystemPromptTokens() - questionTokens);
        int messageBudget = Math.max(0, Math.min(properties.getWorkingMemoryTokens(), availableForMemory));

        List<WorkingMemoryMessage> source = memory == null ? List.of() : memory.getRecentMessages();
        List<WorkingMemoryMessage> selectedReverse = new ArrayList<>();
        int messageTokens = 0;
        int includedPairs = 0;
        for (int end = source.size(); end >= 2; end -= 2) {
            WorkingMemoryMessage user = source.get(end - 2);
            WorkingMemoryMessage assistant = source.get(end - 1);
            int pairTokens = estimateMessage(user) + estimateMessage(assistant);
            if (messageTokens + pairTokens > messageBudget) break;
            selectedReverse.add(assistant);
            selectedReverse.add(user);
            messageTokens += pairTokens;
            includedPairs++;
        }
        Collections.reverse(selectedReverse);

        int remaining = Math.max(0, availableForMemory - messageTokens);
        String originalSummary = memory == null ? "" : memory.getSummary();
        String summary = fitText(originalSummary, Math.max(0, Math.min(properties.getSummaryTokens(), remaining)));
        int summaryTokens = estimator.estimate(summary);
        int droppedPairs = Math.max(0, source.size() / 2 - includedPairs);
        List<String> warnings = new ArrayList<>();
        if (droppedPairs > 0) warnings.add("部分较早对话因 Token 预算未进入本轮 Prompt");
        if (!originalSummary.isBlank() && summary.isBlank()) warnings.add("会话摘要因 Token 预算未进入本轮 Prompt");

        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("systemPromptReserved", properties.getSystemPromptTokens());
        breakdown.put("currentQuestion", questionTokens);
        breakdown.put("workingMemory", messageTokens);
        breakdown.put("workingSummary", summaryTokens);
        int estimatedInput = properties.getSystemPromptTokens() + questionTokens + messageTokens + summaryTokens;
        ContextUsage usage = new ContextUsage(properties.getMaxInputTokens(), properties.getReserveOutputTokens(),
                estimatedInput, Map.copyOf(breakdown), includedPairs, droppedPairs, List.copyOf(warnings));
        return new MemoryContext(summary, selectedReverse, usage);
    }

    private int estimateMessage(WorkingMemoryMessage message) {
        return estimator.estimate(message.content()) + 4;
    }

    private String fitText(String text, int budget) {
        if (text == null || text.isBlank() || budget <= 0) return "";
        if (estimator.estimate(text) <= budget) return text;
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            String candidate = text.substring(text.length() - mid);
            if (estimator.estimate(candidate) <= budget) low = mid;
            else high = mid - 1;
        }
        return low == 0 ? "" : text.substring(text.length() - low);
    }
}
