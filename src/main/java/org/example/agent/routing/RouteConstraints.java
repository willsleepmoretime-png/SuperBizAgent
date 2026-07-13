package org.example.agent.routing;

import java.util.Set;

public record RouteConstraints(Set<ToolCapability> deniedCapabilities, int maxToolCalls) {
    public RouteConstraints {
        deniedCapabilities = Set.copyOf(deniedCapabilities);
    }
}
