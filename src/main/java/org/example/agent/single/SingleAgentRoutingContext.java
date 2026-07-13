package org.example.agent.single;

import org.example.agent.budget.AgentBudget;
import org.example.agent.observability.TokenUsageCollector;
import org.example.agent.routing.RouteDecision;

public record SingleAgentRoutingContext(String requestId, RouteDecision route,
                                        AgentBudget budget, TokenUsageCollector collector) {}
