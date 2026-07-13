package org.example.memory.working;

public record WorkingMemoryScope(String userId, String sessionId) {
    public WorkingMemoryScope {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
    }
}
