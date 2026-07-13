package org.example.dto.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsLegacyChatRequestFields() throws Exception {
        AgentRequest request = mapper.readValue(
                "{\"Id\":\"session-1\",\"Question\":\"hello\"}", AgentRequest.class);
        assertThat(request.getId()).isEqualTo("session-1");
        assertThat(request.getQuestion()).isEqualTo("hello");
    }

    @Test
    void acceptsNewChatRequestAliases() throws Exception {
        AgentRequest request = mapper.readValue(
                "{\"sessionId\":\"session-2\",\"userRequest\":\"hello\"}", AgentRequest.class);
        assertThat(request.getId()).isEqualTo("session-2");
        assertThat(request.getQuestion()).isEqualTo("hello");
    }

    @Test
    void keepsLegacyResponseShape() throws Exception {
        JsonNode json = mapper.valueToTree(AgentResponse.success("answer"));
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("answer").asText()).isEqualTo("answer");
        assertThat(json.has("errorMessage")).isTrue();
    }

    @Test
    void keepsExactLegacySseShape() throws Exception {
        JsonNode json = mapper.valueToTree(AgentSseEvent.content("chunk"));
        assertThat(json.size()).isEqualTo(2);
        assertThat(json.get("type").asText()).isEqualTo("content");
        assertThat(json.get("data").asText()).isEqualTo("chunk");
    }

    @Test
    void acceptsEmptyAndExplicitAiOpsCommands() throws Exception {
        AiOpsCommand empty = mapper.readValue("{}", AiOpsCommand.class);
        AiOpsCommand explicit = mapper.readValue("{\"question\":\"检查订单服务\"}", AiOpsCommand.class);
        assertThat(empty.effectiveUserRequest()).contains("自动化告警排查任务");
        assertThat(empty.ensureTaskId()).isNotBlank();
        assertThat(explicit.effectiveUserRequest()).isEqualTo("检查订单服务");
    }
}
