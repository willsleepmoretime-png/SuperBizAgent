package org.example.agent.tool;

import org.example.agent.routing.RouteDecision;
import org.example.agent.routing.ToolCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.example.agent.budget.AgentBudget;
import org.example.agent.budget.BudgetedToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class AgentToolRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AgentToolRegistry.class);

    private final DateTimeTools dateTimeTools;
    private final InternalDocsTools internalDocsTools;
    private final QueryMetricsTools queryMetricsTools;
    private final QueryLogsTools queryLogsTools;
    private final ToolCallbackProvider callbackProvider;

    public AgentToolRegistry(DateTimeTools dateTimeTools,
                             InternalDocsTools internalDocsTools,
                             QueryMetricsTools queryMetricsTools,
                             Optional<QueryLogsTools> queryLogsTools,
                             Optional<ToolCallbackProvider> callbackProvider) {
        this.dateTimeTools = dateTimeTools;
        this.internalDocsTools = internalDocsTools;
        this.queryMetricsTools = queryMetricsTools;
        this.queryLogsTools = queryLogsTools.orElse(null);
        this.callbackProvider = callbackProvider.orElse(null);
    }

    public Object[] allMethodTools() {
        if (queryLogsTools != null) {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        }
        return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
    }

    public ToolCallback[] allCallbacks() {
        return callbackProvider == null ? new ToolCallback[0] : callbackProvider.getToolCallbacks();
    }

    public ToolSelection select(RouteDecision route) {
        List<Object> selected = new ArrayList<>();
        if (route.allows(ToolCapability.DATE_TIME)) selected.add(dateTimeTools);
        if (route.allows(ToolCapability.INTERNAL_DOCS)) selected.add(internalDocsTools);
        if (route.allows(ToolCapability.METRICS)) selected.add(queryMetricsTools);
        if (route.allows(ToolCapability.LOGS) && queryLogsTools != null) selected.add(queryLogsTools);

        ToolCallback[] callbacks = selectCallbacks(route);
        logger.info("AGENT_OPTIMIZATION tool_registry route={} selectedMethodTools={}",
                route.intent(), selected.stream().map(tool -> tool.getClass().getSimpleName()).toList());
        return new ToolSelection(selected.toArray(), callbacks);
    }

    public ToolSelection select(RouteDecision route, AgentBudget budget) {
        ToolSelection selected = select(route);
        ToolCallback[] methodCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(selected.methodTools())
                .build()
                .getToolCallbacks();
        ToolCallback[] combined = new ToolCallback[methodCallbacks.length + selected.callbacks().length];
        System.arraycopy(methodCallbacks, 0, combined, 0, methodCallbacks.length);
        System.arraycopy(selected.callbacks(), 0, combined, methodCallbacks.length, selected.callbacks().length);
        ToolCallback[] budgeted = Arrays.stream(combined)
                .map(callback -> new BudgetedToolCallback(callback, budget))
                .toArray(ToolCallback[]::new);
        return new ToolSelection(new Object[0], budgeted);
    }

    public ToolSelection all(AgentBudget budget) {
        ToolCallback[] methodCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(allMethodTools())
                .build()
                .getToolCallbacks();
        ToolCallback[] external = allCallbacks();
        ToolCallback[] combined = new ToolCallback[methodCallbacks.length + external.length];
        System.arraycopy(methodCallbacks, 0, combined, 0, methodCallbacks.length);
        System.arraycopy(external, 0, combined, methodCallbacks.length, external.length);
        return new ToolSelection(new Object[0], Arrays.stream(combined)
                .map(callback -> new BudgetedToolCallback(callback, budget))
                .toArray(ToolCallback[]::new));
    }

    private ToolCallback[] selectCallbacks(RouteDecision route) {
        ToolCallback[] all = allCallbacks();
        // Preserve the existing conservative behavior for observability routes in phase one.
        if (route.allows(ToolCapability.LOGS) || route.allows(ToolCapability.METRICS)) return all;
        return Arrays.stream(all)
                .filter(callback -> {
                    String name = callback.getToolDefinition().name().toLowerCase(Locale.ROOT);
                    return (route.allows(ToolCapability.INTERNAL_DOCS) && name.contains("doc"))
                            || (route.allows(ToolCapability.DATE_TIME)
                            && (name.contains("time") || name.contains("date")));
                })
                .toArray(ToolCallback[]::new);
    }

    public void logAvailableTools() {
        ToolCallback[] callbacks = allCallbacks();
        if (callbackProvider == null) {
            logger.info("可用工具列表: MCP 未启用，当前没有外部工具回调");
            return;
        }
        logger.info("可用工具列表:");
        for (ToolCallback callback : callbacks) {
            logger.info(">>> {}", callback.getToolDefinition().name());
        }
    }
}
