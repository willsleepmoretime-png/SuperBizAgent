package org.example.agent.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LlmIntentClassifierTest {
    private final LlmIntentClassifier classifier = new LlmIntentClassifier(null, new ObjectMapper());
    @Test void parsesSeparatedExecutionAndIntent() throws Exception {
        RouteDecision result = classifier.parse("""
          {"executionMode":"AIOPS_WORKFLOW","intent":"INCIDENT_INVESTIGATION",
           "capabilities":["METRICS","LOGS"],"score":0.86,"requiresClarification":false,"reason":"complex"}
          """);
        assertThat(result.executionMode()).isEqualTo(ExecutionMode.AIOPS_WORKFLOW);
        assertThat(result.intent()).isEqualTo(IntentType.INCIDENT_INVESTIGATION);
        assertThat(result.classifierScore()).isEqualTo(0.86);
    }
}
