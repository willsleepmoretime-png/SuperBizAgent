package org.example.agent.prompt;

import org.example.memory.context.MemoryContext;
import org.example.memory.working.WorkingMemoryMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class SingleAgentPromptBuilder {

    public String build(List<Map<String, String>> history) {
        return build("", history);
    }

    public String build(String memorySummary, List<Map<String, String>> history) {
        List<WorkingMemoryMessage> messages = history == null ? List.of() : history.stream()
                .filter(item -> item.get("role") != null)
                .map(item -> new WorkingMemoryMessage(item.get("role"), item.get("content"), Instant.now()))
                .toList();
        return build(new MemoryContext(memorySummary, messages, null));
    }

    public String build(MemoryContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n");
        prompt.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        prompt.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        prompt.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        prompt.append("当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。\n");
        prompt.append("使用记忆时要优先参考最近对话；会话摘要只作为远期上下文，不要把摘要中的旧结论当作当前事实直接复述。\n\n");

        if (context.summary() != null && !context.summary().isBlank()) {
            prompt.append("--- 会话摘要（较早历史，可能不完整） ---\n");
            prompt.append(context.summary().trim()).append("\n");
            prompt.append("--- 会话摘要结束 ---\n\n");
        }
        if (!context.recentMessages().isEmpty()) {
            prompt.append("--- 最近对话历史 ---\n");
            for (WorkingMemoryMessage message : context.recentMessages()) {
                if ("user".equals(message.role())) {
                    prompt.append("用户: ").append(message.content()).append("\n");
                } else if ("assistant".equals(message.role())) {
                    prompt.append("助手: ").append(message.content()).append("\n");
                }
            }
            prompt.append("--- 最近对话历史结束 ---\n\n");
        }
        prompt.append("请基于以上上下文回答用户的新问题；如果记忆和当前问题冲突，以当前问题为准。");
        return prompt.toString();
    }
}
