package org.example.memory.context;

import org.example.memory.working.WorkingMemoryMessage;

import java.util.List;

public record MemoryContext(
        String summary,
        List<WorkingMemoryMessage> recentMessages,
        ContextUsage usage) {

    public MemoryContext {
        summary = summary == null ? "" : summary;
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
    }
}
