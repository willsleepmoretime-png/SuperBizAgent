package org.example.agent.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.agent.workflow.model.IncidentState;
import org.example.agent.workflow.model.InvestigationStep;
import org.example.agent.workflow.model.ToolEvidence;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class ToolExecutor {
    private final DateTimeTools dateTimeTools;
    private final InternalDocsTools internalDocsTools;
    private final QueryMetricsTools metricsTools;
    private final Optional<QueryLogsTools> logsTools;
    private final ObjectMapper objectMapper;

    public ToolExecutor(DateTimeTools dateTimeTools, InternalDocsTools internalDocsTools,
                        QueryMetricsTools metricsTools, Optional<QueryLogsTools> logsTools,
                        ObjectMapper objectMapper) {
        this.dateTimeTools = dateTimeTools;
        this.internalDocsTools = internalDocsTools;
        this.metricsTools = metricsTools;
        this.logsTools = logsTools;
        this.objectMapper = objectMapper;
    }

    public ToolEvidence execute(IncidentState state, InvestigationStep step, ToolCallback[] externalCallbacks) {
        String evidenceId = "ev-" + UUID.randomUUID();
        try {
            if (step.toolType() == null) throw new IllegalArgumentException("toolType is required");
            if (!state.allows(step.toolType())) {
                return ToolEvidence.failure(evidenceId, step, "Tool capability denied by route policy: " + step.toolType());
            }
            if (state.failureLimitReached(step.toolType())) {
                return ToolEvidence.failure(evidenceId, step, "Tool failure limit reached");
            }
            String fingerprint = step.toolType() + ":" + canonicalParameters(step);
            if (!state.registerInvocation(fingerprint)) {
                return ToolEvidence.failure(evidenceId, step, "Duplicate invocation rejected: " + fingerprint);
            }
            state.getBudget().beforeToolCall(step.toolType().name());
            String result = switch (step.toolType()) {
                case GET_CURRENT_TIME -> dateTimeTools.getCurrentDateTime();
                case SEARCH_INTERNAL_DOCS -> internalDocsTools.queryInternalDocs(stringParam(step, "query", step.description()));
                case QUERY_ALERTS, QUERY_METRICS -> metricsTools.queryPrometheusAlerts();
                case QUERY_LOGS -> executeLogs(step, externalCallbacks);
            };
            if (result == null || result.isBlank()) return ToolEvidence.failure(evidenceId, step, "Tool returned empty result");
            return ToolEvidence.success(evidenceId, step, result);
        } catch (Exception e) {
            return ToolEvidence.failure(evidenceId, step, e.getMessage());
        }
    }

    private String executeLogs(InvestigationStep step, ToolCallback[] callbacks) throws Exception {
        if (logsTools.isPresent()) {
            return logsTools.get().queryLogs(
                    stringParam(step, "region", "ap-guangzhou"),
                    stringParam(step, "logTopic", "application-logs"),
                    stringParam(step, "query", ""),
                    intParam(step, "limit", 20));
        }
        ToolCallback callback = Arrays.stream(callbacks == null ? new ToolCallback[0] : callbacks)
                .filter(item -> item.getToolDefinition().name().toLowerCase(Locale.ROOT).contains("querylogs"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No log query tool is available"));
        return callback.call(objectMapper.writeValueAsString(step.parameters()));
    }

    private String canonicalParameters(InvestigationStep step) {
        try {
            return objectMapper.writeValueAsString(step.parameters().entrySet().stream()
                    .sorted(Comparator.comparing(java.util.Map.Entry::getKey)).toList());
        } catch (Exception e) {
            return step.parameters().toString();
        }
    }

    private String stringParam(InvestigationStep step, String name, String fallback) {
        Object value = step.parameters().get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private Integer intParam(InvestigationStep step, String name, int fallback) {
        Object value = step.parameters().get(name);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException e) { return fallback; }
    }
}
