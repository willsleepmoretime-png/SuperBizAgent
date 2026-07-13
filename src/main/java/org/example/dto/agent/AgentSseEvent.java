package org.example.dto.agent;

/**
 * Unified SSE payload. Its wire shape intentionally remains exactly
 * {"type": "...", "data": "..."} for legacy browser compatibility.
 */
public class AgentSseEvent {
    private String type;
    private String data;

    protected AgentSseEvent() {}

    protected AgentSseEvent(String type, String data) {
        this.type = type;
        this.data = data;
    }

    public static AgentSseEvent content(String data) { return new AgentSseEvent("content", data); }
    public static AgentSseEvent error(String data) { return new AgentSseEvent("error", data); }
    public static AgentSseEvent done() { return new AgentSseEvent("done", null); }
    public static AgentSseEvent taskCreated(String taskId) { return new AgentSseEvent("task_created", taskId); }
    public static AgentSseEvent report(String report) { return new AgentSseEvent("report", report); }
    public static AgentSseEvent plan(String json) { return new AgentSseEvent("plan", json); }
    public static AgentSseEvent stepStarted(String json) { return new AgentSseEvent("step_started", json); }
    public static AgentSseEvent toolResult(String json) { return new AgentSseEvent("tool_result", json); }
    public static AgentSseEvent usage(String json) { return new AgentSseEvent("usage", json); }
    public static AgentSseEvent contextUsage(String json) { return new AgentSseEvent("context_usage", json); }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getData() { return data; }
    public void setData(String data) { this.data = data; }
}
