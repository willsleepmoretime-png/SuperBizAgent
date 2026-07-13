package org.example.dto.agent;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.UUID;
import java.util.Set;
import org.example.agent.routing.ToolCapability;

/** Input contract for an AIOps workflow. An empty command preserves legacy automatic analysis. */
public class AiOpsCommand {
    @JsonAlias({"question", "Question", "task", "message"})
    private String userRequest;
    private String taskId;
    private String userId;
    private String mode;
    private Set<ToolCapability> allowedCapabilities;

    public static AiOpsCommand legacyDefault() {
        return new AiOpsCommand();
    }

    public String effectiveUserRequest() {
        if (userRequest == null || userRequest.isBlank()) {
            return "你是企业级 SRE，接到了自动化告警排查任务。请结合工具调用，执行**规划→执行→再规划**的闭环，并最终按照固定模板输出《告警分析报告》。禁止编造虚假数据，如连续多次查询失败需诚实反馈无法完成的原因。";
        }
        return userRequest.trim();
    }

    public String ensureTaskId() {
        if (taskId == null || taskId.isBlank()) {
            taskId = UUID.randomUUID().toString();
        }
        return taskId;
    }

    public String getUserRequest() { return userRequest; }
    public void setUserRequest(String userRequest) { this.userRequest = userRequest; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Set<ToolCapability> getAllowedCapabilities() { return allowedCapabilities; }
    public void setAllowedCapabilities(Set<ToolCapability> allowedCapabilities) { this.allowedCapabilities = allowedCapabilities; }
}
