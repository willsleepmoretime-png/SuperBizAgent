package org.example.memory.working;

import java.util.Optional;

public interface WorkingMemoryRepository {
    Optional<WorkingMemory> find(WorkingMemoryScope scope);
    void save(WorkingMemoryScope scope, WorkingMemory memory);
    boolean delete(WorkingMemoryScope scope);
}
