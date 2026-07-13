package org.example.memory.working;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkingMemoryService {
    private static final Logger logger = LoggerFactory.getLogger(WorkingMemoryService.class);
    private final WorkingMemoryRepository repository;
    private final WorkingMemoryProperties properties;

    public WorkingMemoryService(WorkingMemoryRepository repository, WorkingMemoryProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public WorkingMemory load(WorkingMemoryScope scope) {
        try {
            return repository.find(scope).orElseGet(() -> WorkingMemory.empty(scope));
        } catch (RuntimeException e) {
            logger.warn("Redis 短期记忆读取失败，当前请求降级为无历史模式 userId={} sessionId={}",
                    scope.userId(), scope.sessionId());
            logger.debug("Redis 短期记忆读取异常", e);
            return WorkingMemory.empty(scope);
        }
    }

    public void appendPair(WorkingMemoryScope scope, String userQuestion, String assistantAnswer) {
        try {
            WorkingMemory memory = repository.find(scope).orElseGet(() -> WorkingMemory.empty(scope));
            List<WorkingMemoryMessage> messages = new ArrayList<>(memory.getRecentMessages());
            Instant now = Instant.now();
            messages.add(new WorkingMemoryMessage("user", userQuestion, now));
            messages.add(new WorkingMemoryMessage("assistant", assistantAnswer, now));

            int maxMessages = Math.max(1, properties.getMaxWindowPairs()) * 2;
            StringBuilder summary = new StringBuilder(memory.getSummary() == null ? "" : memory.getSummary());
            while (messages.size() > maxMessages) {
                WorkingMemoryMessage oldUser = messages.remove(0);
                WorkingMemoryMessage oldAssistant = messages.isEmpty() ? null : messages.remove(0);
                appendSummary(summary, oldUser, oldAssistant);
            }

            memory.setRecentMessages(messages);
            memory.setSummary(trimSummary(summary.toString()));
            memory.setUpdatedAt(now);
            repository.save(scope, memory);
        } catch (RuntimeException e) {
            logger.warn("Redis 短期记忆写入失败，本轮回答不会保存 userId={} sessionId={}",
                    scope.userId(), scope.sessionId());
            logger.debug("Redis 短期记忆写入异常", e);
        }
    }

    public boolean clear(WorkingMemoryScope scope) {
        try {
            return repository.delete(scope);
        } catch (RuntimeException e) {
            logger.warn("Redis 短期记忆删除失败 userId={} sessionId={}", scope.userId(), scope.sessionId());
            logger.debug("Redis 短期记忆删除异常", e);
            return false;
        }
    }

    public List<Map<String, String>> promptHistory(WorkingMemory memory) {
        List<Map<String, String>> result = new ArrayList<>();
        for (WorkingMemoryMessage message : memory.getRecentMessages()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", message.role());
            item.put("content", message.content());
            result.add(item);
        }
        return result;
    }

    private void appendSummary(StringBuilder summary, WorkingMemoryMessage user, WorkingMemoryMessage assistant) {
        summary.append("用户曾问: ").append(abbreviate(user == null ? "" : user.content(), 500)).append('\n');
        summary.append("助手曾答: ").append(abbreviate(assistant == null ? "" : assistant.content(), 800)).append('\n');
    }

    private String trimSummary(String summary) {
        int limit = Math.max(500, properties.getSummaryMaxChars());
        return summary.length() <= limit ? summary : summary.substring(summary.length() - limit);
    }

    private String abbreviate(String text, int maxLength) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }
}
