package org.example.agent.prompt;

import org.junit.jupiter.api.Test;
import org.example.memory.context.MemoryContext;
import org.example.memory.working.WorkingMemoryMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SingleAgentPromptBuilderTest {
    private final SingleAgentPromptBuilder builder = new SingleAgentPromptBuilder();

    @Test
    void keepsSummaryAndRecentHistoryOrdering() {
        String prompt = builder.build("较早摘要", List.of(
                Map.of("role", "user", "content", "上一问"),
                Map.of("role", "assistant", "content", "上一答")));

        assertThat(prompt)
                .contains("会话摘要（较早历史，可能不完整）")
                .contains("用户: 上一问")
                .contains("助手: 上一答")
                .endsWith("请基于以上上下文回答用户的新问题；如果记忆和当前问题冲突，以当前问题为准。");
        assertThat(prompt.indexOf("较早摘要")).isLessThan(prompt.indexOf("用户: 上一问"));
    }

    @Test
    void rendersTypedMemoryContext() {
        MemoryContext context = new MemoryContext("摘要",
                List.of(new WorkingMemoryMessage("user", "最近问题", Instant.now())),
                null);

        String prompt = builder.build(context);

        assertThat(prompt).contains("摘要", "最近问题");
    }
}
