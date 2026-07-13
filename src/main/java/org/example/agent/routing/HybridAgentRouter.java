package org.example.agent.routing;

import org.example.agent.budget.AgentBudget;
import org.example.agent.observability.TokenUsageCollector;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class HybridAgentRouter implements AgentRouter {
    private final RuleBasedAgentRouter ruleRouter;
    private final LlmIntentClassifier classifier;
    private final RoutePolicyValidator policyValidator;

    public HybridAgentRouter(RuleBasedAgentRouter ruleRouter,
                             LlmIntentClassifier classifier,
                             RoutePolicyValidator policyValidator) {
        this.ruleRouter = ruleRouter;
        this.classifier = classifier;
        this.policyValidator = policyValidator;
    }

    @Override
    public RouteDecision route(String question) {
        // Debug/legacy calls do not own an execution budget; use a bounded local budget.
        return route(question, new AgentBudget(3, 4, 12000));
    }

    @Override
    public RouteDecision route(String question, AgentBudget budget) {
        return route(question, budget, new TokenUsageCollector(), null);
    }

    @Override
    public RouteDecision route(String question, AgentBudget budget, TokenUsageCollector collector, String requestId) {
        RouteDecision proposed = ruleRouter.route(question)
                .orElseGet(() -> classifier.classify(question, budget, collector, requestId));
        return policyValidator.validate(question, proposed, budget);
    }
}
