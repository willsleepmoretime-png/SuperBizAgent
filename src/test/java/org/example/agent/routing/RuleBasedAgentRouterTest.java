package org.example.agent.routing;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedAgentRouterTest {
    private final RuleBasedAgentRouter router = new RuleBasedAgentRouter();

    @Test void explicitDocumentQueryUsesSingleAgent() {
        RouteDecision route = router.route("查询内部文档发布流程").orElseThrow();
        assertThat(route.executionMode()).isEqualTo(ExecutionMode.SINGLE_AGENT);
        assertThat(route.intent()).isEqualTo(IntentType.DOCUMENT_QA);
    }
    @Test void explicitIncidentInvestigationUsesWorkflow() {
        RouteDecision route = router.route("结合告警日志做完整排查和根因分析").orElseThrow();
        assertThat(route.executionMode()).isEqualTo(ExecutionMode.AIOPS_WORKFLOW);
        assertThat(route.intent()).isEqualTo(IntentType.INCIDENT_INVESTIGATION);
    }
    @Test void ambiguousTextDoesNotInventRuleConfidence() {
        assertThat(router.route("帮我看看系统怎么了")).isEmpty();
    }
}
