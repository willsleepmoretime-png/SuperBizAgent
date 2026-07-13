package org.example.memory.working;

import java.time.Instant;

public record WorkingMemoryMessage(String role, String content, Instant createdAt) {
    public WorkingMemoryMessage {
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("不支持的消息角色: " + role);
        }
        content = content == null ? "" : content;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
