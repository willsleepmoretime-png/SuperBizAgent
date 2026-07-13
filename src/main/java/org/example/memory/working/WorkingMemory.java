package org.example.memory.working;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class WorkingMemory {
    private String userId;
    private String sessionId;
    private List<WorkingMemoryMessage> recentMessages = new ArrayList<>();
    private String summary = "";
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public static WorkingMemory empty(WorkingMemoryScope scope) {
        WorkingMemory memory = new WorkingMemory();
        memory.userId = scope.userId();
        memory.sessionId = scope.sessionId();
        return memory;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public List<WorkingMemoryMessage> getRecentMessages() { return recentMessages; }
    public void setRecentMessages(List<WorkingMemoryMessage> recentMessages) {
        this.recentMessages = recentMessages == null ? new ArrayList<>() : new ArrayList<>(recentMessages);
    }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary == null ? "" : summary; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
