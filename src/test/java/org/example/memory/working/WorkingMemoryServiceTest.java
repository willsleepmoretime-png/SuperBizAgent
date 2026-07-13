package org.example.memory.working;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkingMemoryServiceTest {

    @Test
    void keepsLatestPairsAndSummarizesEvictedPairs() {
        WorkingMemoryProperties properties = properties(2);
        FakeRepository repository = new FakeRepository();
        WorkingMemoryService service = new WorkingMemoryService(repository, properties);
        WorkingMemoryScope scope = new WorkingMemoryScope("user-a", "session-1");

        service.appendPair(scope, "question-1", "answer-1");
        service.appendPair(scope, "question-2", "answer-2");
        service.appendPair(scope, "question-3", "answer-3");

        WorkingMemory memory = service.load(scope);
        assertEquals(4, memory.getRecentMessages().size());
        assertEquals("question-2", memory.getRecentMessages().get(0).content());
        assertTrue(memory.getSummary().contains("question-1"));
        assertTrue(memory.getSummary().contains("answer-1"));
        assertEquals(Duration.ofDays(7), properties.getTtl());
    }

    @Test
    void isolatesSameSessionForDifferentUsers() {
        FakeRepository repository = new FakeRepository();
        WorkingMemoryService service = new WorkingMemoryService(repository, properties(6));

        service.appendPair(new WorkingMemoryScope("user-a", "same-session"), "A", "answer-A");
        service.appendPair(new WorkingMemoryScope("user-b", "same-session"), "B", "answer-B");

        assertEquals("A", service.load(new WorkingMemoryScope("user-a", "same-session"))
                .getRecentMessages().get(0).content());
        assertEquals("B", service.load(new WorkingMemoryScope("user-b", "same-session"))
                .getRecentMessages().get(0).content());
    }

    @Test
    void clearDeletesConversation() {
        FakeRepository repository = new FakeRepository();
        WorkingMemoryService service = new WorkingMemoryService(repository, properties(6));
        WorkingMemoryScope scope = new WorkingMemoryScope("user-a", "session-1");
        service.appendPair(scope, "question", "answer");

        assertTrue(service.clear(scope));
        assertTrue(service.load(scope).getRecentMessages().isEmpty());
    }

    @Test
    void repositoryFailureDegradesToEmptyMemory() {
        WorkingMemoryRepository failing = new WorkingMemoryRepository() {
            public Optional<WorkingMemory> find(WorkingMemoryScope scope) { throw new IllegalStateException("down"); }
            public void save(WorkingMemoryScope scope, WorkingMemory memory) { throw new IllegalStateException("down"); }
            public boolean delete(WorkingMemoryScope scope) { throw new IllegalStateException("down"); }
        };
        WorkingMemoryService service = new WorkingMemoryService(failing, properties(6));

        assertTrue(service.load(new WorkingMemoryScope("user-a", "session-1")).getRecentMessages().isEmpty());
        assertDoesNotThrow(() -> service.appendPair(
                new WorkingMemoryScope("user-a", "session-1"), "question", "answer"));
        assertFalse(service.clear(new WorkingMemoryScope("user-a", "session-1")));
    }

    private static WorkingMemoryProperties properties(int maxPairs) {
        WorkingMemoryProperties properties = new WorkingMemoryProperties();
        properties.setMaxWindowPairs(maxPairs);
        properties.setSummaryMaxChars(3000);
        properties.setTtl(Duration.ofDays(7));
        return properties;
    }

    private static class FakeRepository implements WorkingMemoryRepository {
        private final Map<WorkingMemoryScope, WorkingMemory> values = new HashMap<>();
        public Optional<WorkingMemory> find(WorkingMemoryScope scope) { return Optional.ofNullable(values.get(scope)); }
        public void save(WorkingMemoryScope scope, WorkingMemory memory) { values.put(scope, memory); }
        public boolean delete(WorkingMemoryScope scope) { return values.remove(scope) != null; }
    }
}
