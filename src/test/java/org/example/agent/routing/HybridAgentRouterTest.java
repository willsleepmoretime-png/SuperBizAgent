package org.example.agent.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.budget.AgentBudget;
import org.example.agent.observability.TokenUsageCollector;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class HybridAgentRouterTest {
    @Test void explicitRuleSkipsLlm() {
        StubClassifier classifier = new StubClassifier(fallback());
        RouteDecision result = new HybridAgentRouter(new RuleBasedAgentRouter(), classifier,
                new RoutePolicyValidator()).route("查询 Prometheus 告警", new AgentBudget(3,4,12000));
        assertThat(result.source()).isEqualTo(RouteSource.RULE);
        assertThat(classifier.calls).isZero();
    }
    @Test void ambiguousRequestUsesLlm() {
        RouteDecision llm = new RouteDecision(ExecutionMode.AIOPS_WORKFLOW, IntentType.INCIDENT_INVESTIGATION,
                Set.of(ToolCapability.METRICS, ToolCapability.LOGS), RouteSource.LLM, 0.82, false, "multi-step");
        StubClassifier classifier = new StubClassifier(llm);
        RouteDecision result = new HybridAgentRouter(new RuleBasedAgentRouter(), classifier,
                new RoutePolicyValidator()).route("帮我看看系统怎么了", new AgentBudget(3,4,12000));
        assertThat(result.executionMode()).isEqualTo(ExecutionMode.AIOPS_WORKFLOW);
        assertThat(classifier.calls).isEqualTo(1);
    }
    private static RouteDecision fallback() { return new RouteDecision(ExecutionMode.SINGLE_AGENT, IntentType.GENERAL_CHAT,
            Set.of(), RouteSource.FALLBACK, null, false, "fallback"); }
    private static class StubClassifier extends LlmIntentClassifier {
        final RouteDecision result; int calls;
        StubClassifier(RouteDecision result) { super(null, new ObjectMapper()); this.result=result; }
        @Override public RouteDecision classify(String q, AgentBudget b, TokenUsageCollector c, String id) { calls++; return result; }
    }
}
