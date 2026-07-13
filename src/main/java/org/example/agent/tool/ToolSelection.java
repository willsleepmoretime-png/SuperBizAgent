package org.example.agent.tool;

import org.springframework.ai.tool.ToolCallback;

public record ToolSelection(Object[] methodTools, ToolCallback[] callbacks) {
    public ToolSelection {
        methodTools = methodTools.clone();
        callbacks = callbacks.clone();
    }
}
