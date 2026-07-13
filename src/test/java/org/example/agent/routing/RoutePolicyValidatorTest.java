package org.example.agent.routing;

import org.example.agent.budget.AgentBudget;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class RoutePolicyValidatorTest {
    @Test void removesDeniedLogsWithoutChangingIntentSource() {
        RouteDecision proposed = new RouteDecision(ExecutionMode.AIOPS_WORKFLOW, IntentType.INCIDENT_INVESTIGATION,
                Set.of(ToolCapability.METRICS, ToolCapability.LOGS), RouteSource.LLM, 0.8, false, "classified");
        RouteDecision result = new RoutePolicyValidator().validate("根因分析但不要查询日志", proposed,
                new AgentBudget(3,4,12000));
        assertThat(result.capabilities()).containsExactly(ToolCapability.METRICS);
        assertThat(result.executionMode()).isEqualTo(ExecutionMode.AIOPS_WORKFLOW);
    }
    @Test void zeroToolBudgetSafelyDowngradesToSingleChat() {
        RouteDecision proposed = new RouteDecision(ExecutionMode.AIOPS_WORKFLOW, IntentType.INCIDENT_INVESTIGATION,
                Set.of(ToolCapability.METRICS), RouteSource.RULE, null, false, "rule");
        RouteDecision result = new RoutePolicyValidator().validate("完整排查", proposed, new AgentBudget(0,4,12000));
        assertThat(result.executionMode()).isEqualTo(ExecutionMode.SINGLE_AGENT);
        assertThat(result.intent()).isEqualTo(IntentType.GENERAL_CHAT);
    }
}
