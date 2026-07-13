package org.example.agent.routing;

import java.util.Set;

public record RouteDecision(ExecutionMode executionMode, IntentType intent,
                            Set<ToolCapability> capabilities, RouteSource source,
                            Double classifierScore, boolean requiresClarification, String reason) {
    public RouteDecision { capabilities = Set.copyOf(capabilities); }
    public boolean allows(ToolCapability capability) { return capabilities.contains(capability); }
    public String summary() {
        return String.format("execution=%s,intent=%s,source=%s,dateTime=%s,docs=%s,metrics=%s,logs=%s",
                executionMode, intent, source, allows(ToolCapability.DATE_TIME),
                allows(ToolCapability.INTERNAL_DOCS), allows(ToolCapability.METRICS), allows(ToolCapability.LOGS));
    }
}
