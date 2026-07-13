package org.example.agent.routing;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class RuleBasedAgentRouter {
    public Optional<RouteDecision> route(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT).trim();
        if (text.isEmpty()) return Optional.empty();
        boolean time = containsAny(text, "时间", "日期", "几点", "今天几号", "time", "date");
        boolean docs = containsAny(text, "文档", "知识库", "流程", "最佳实践", "操作手册", "sop", "rag");
        boolean metrics = containsAny(text, "告警", "监控", "指标", "prometheus", "alert", "cpu", "内存", "磁盘", "响应时间");
        boolean logs = containsAny(text, "日志", "错误日志", "报错", "error", "exception", "cls", "查询日志");
        boolean investigation = containsAny(text, "完整排查", "根因分析", "故障调查", "多步骤排查", "告警分析报告", "incident investigation");
        if (investigation) {
            Set<ToolCapability> tools = capabilities(false, true, true, true);
            return Optional.of(decision(ExecutionMode.AIOPS_WORKFLOW, IntentType.INCIDENT_INVESTIGATION, tools));
        }
        if (time && !docs && !metrics && !logs) return Optional.of(decision(ExecutionMode.SINGLE_AGENT, IntentType.DATE_TIME, capabilities(true, false, false, false)));
        if (docs && !metrics && !logs) return Optional.of(decision(ExecutionMode.SINGLE_AGENT, IntentType.DOCUMENT_QA, capabilities(false, true, false, false)));
        if (metrics || logs) return Optional.of(decision(ExecutionMode.SINGLE_AGENT, IntentType.OBSERVABILITY_QUERY, capabilities(false, docs, metrics, logs)));
        if (containsAny(text, "你好", "您好", "谢谢", "你是谁", "hello", "hi")) return Optional.of(decision(ExecutionMode.SINGLE_AGENT, IntentType.GENERAL_CHAT, Set.of()));
        return Optional.empty();
    }

    private RouteDecision decision(ExecutionMode execution, IntentType intent, Set<ToolCapability> tools) {
        return new RouteDecision(execution, intent, tools, RouteSource.RULE, null, false, "Explicit rule matched");
    }
    private Set<ToolCapability> capabilities(boolean time, boolean docs, boolean metrics, boolean logs) {
        Set<ToolCapability> result = EnumSet.noneOf(ToolCapability.class);
        if (time) result.add(ToolCapability.DATE_TIME); if (docs) result.add(ToolCapability.INTERNAL_DOCS);
        if (metrics) result.add(ToolCapability.METRICS); if (logs) result.add(ToolCapability.LOGS);
        return result;
    }
    private boolean containsAny(String text, String... values) { for (String value : values) if (text.contains(value)) return true; return false; }
}
