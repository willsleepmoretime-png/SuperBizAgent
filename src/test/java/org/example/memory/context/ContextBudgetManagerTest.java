package org.example.memory.context;

import org.example.memory.working.WorkingMemory;
import org.example.memory.working.WorkingMemoryMessage;
import org.example.memory.working.WorkingMemoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextBudgetManagerTest {

    @Test
    void keepsNewestCompletePairsWithinBudget() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        properties.setMaxInputTokens(100);
        properties.setReserveOutputTokens(10);
        properties.setSystemPromptTokens(10);
        properties.setWorkingMemoryTokens(20);
        properties.setSummaryTokens(10);
        ContextBudgetManager manager = new ContextBudgetManager(new SimpleTokenEstimator(), properties);
        WorkingMemory memory = WorkingMemory.empty(new WorkingMemoryScope("user", "session"));
        memory.setRecentMessages(List.of(
                message("user", "old-question-1234567890"), message("assistant", "old-answer-1234567890"),
                message("user", "new"), message("assistant", "answer")));
        memory.setSummary("older summary");

        MemoryContext context = manager.fit("current question", memory);

        assertEquals(2, context.recentMessages().size());
        assertEquals("new", context.recentMessages().get(0).content());
        assertEquals(1, context.usage().includedMessagePairs());
        assertEquals(1, context.usage().droppedMessagePairs());
        assertTrue(context.usage().estimatedInputTokens()
                <= context.usage().maxInputTokens() - context.usage().reservedOutputTokens());
    }

    @Test
    void dropsSummaryWhenNoBudgetRemains() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        properties.setMaxInputTokens(30);
        properties.setReserveOutputTokens(5);
        properties.setSystemPromptTokens(15);
        properties.setWorkingMemoryTokens(10);
        properties.setSummaryTokens(10);
        WorkingMemory memory = WorkingMemory.empty(new WorkingMemoryScope("user", "session"));
        memory.setSummary("this summary cannot fit");

        MemoryContext context = new ContextBudgetManager(new SimpleTokenEstimator(), properties)
                .fit("question", memory);

        assertTrue(context.summary().isEmpty());
        assertFalse(context.usage().warnings().isEmpty());
    }

    @Test
    void rejectsQuestionThatCannotFitWithoutTruncation() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        properties.setMaxInputTokens(20);
        properties.setReserveOutputTokens(5);
        properties.setSystemPromptTokens(14);
        ContextBudgetManager manager = new ContextBudgetManager(new SimpleTokenEstimator(), properties);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> manager.fit("a question that is too long", null));

        assertTrue(error.getMessage().contains("问题过长"));
    }

    private WorkingMemoryMessage message(String role, String content) {
        return new WorkingMemoryMessage(role, content, Instant.now());
    }
}
