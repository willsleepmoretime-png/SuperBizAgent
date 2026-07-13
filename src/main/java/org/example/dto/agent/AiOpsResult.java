package org.example.dto.agent;

public record AiOpsResult(
        String taskId,
        String status,
        String report,
        String errorMessage
) {
    public static AiOpsResult completed(String taskId, String report) {
        return new AiOpsResult(taskId, "COMPLETED", report, null);
    }

    public static AiOpsResult partial(String taskId, String message) {
        return new AiOpsResult(taskId, "PARTIAL", null, message);
    }
}
