package org.example.agent.routing;

import org.example.agent.budget.AgentBudget;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

@Component
public class RoutePolicyValidator {

    public RouteDecision validate(String question, RouteDecision proposed, AgentBudget budget) {
        RouteConstraints constraints = constraintsFor(question, budget);
        Set<ToolCapability> allowed = proposed.capabilities().isEmpty()
                ? EnumSet.noneOf(ToolCapability.class)
                : EnumSet.copyOf(proposed.capabilities());
        allowed.removeAll(constraints.deniedCapabilities());
        if (constraints.maxToolCalls() == 0) allowed.clear();

        ExecutionMode execution = allowed.isEmpty() ? ExecutionMode.SINGLE_AGENT : proposed.executionMode();
        IntentType intent = allowed.isEmpty() && proposed.intent() != IntentType.GENERAL_CHAT
                ? IntentType.GENERAL_CHAT : proposed.intent();
        String reason = proposed.reason();
        if (!allowed.equals(proposed.capabilities())) {
            reason += "; policy removed denied or over-budget capabilities";
        }
        return new RouteDecision(execution, intent, allowed, proposed.source(), proposed.classifierScore(),
                proposed.requiresClarification(), reason);
    }

    RouteConstraints constraintsFor(String question, AgentBudget budget) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        Set<ToolCapability> denied = EnumSet.noneOf(ToolCapability.class);
        if (containsAny(text, "不要查日志", "不要查询日志", "禁止查询日志", "without logs", "no logs")) {
            denied.add(ToolCapability.LOGS);
        }
        if (containsAny(text, "不要查指标", "不要查询监控", "禁止查询指标", "without metrics", "no metrics")) {
            denied.add(ToolCapability.METRICS);
        }
        if (containsAny(text, "不要查文档", "不要查询文档", "禁止访问知识库", "without docs", "no docs")) {
            denied.add(ToolCapability.INTERNAL_DOCS);
        }
        return new RouteConstraints(denied, budget.getMaxToolCalls());
    }

    private boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
