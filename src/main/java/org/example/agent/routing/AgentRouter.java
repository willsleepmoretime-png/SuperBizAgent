package org.example.agent.routing;

import org.example.agent.budget.AgentBudget;
import org.example.agent.observability.TokenUsageCollector;

public interface AgentRouter {
    RouteDecision route(String question);

    default RouteDecision route(String question, AgentBudget budget) {
        return route(question);
    }

    default RouteDecision route(String question, AgentBudget budget, TokenUsageCollector collector, String requestId) {
        return route(question, budget);
    }
}
