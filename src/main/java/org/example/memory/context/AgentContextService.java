package org.example.memory.context;

import org.example.memory.working.WorkingMemory;
import org.example.memory.working.WorkingMemoryScope;
import org.example.memory.working.WorkingMemoryService;
import org.springframework.stereotype.Service;

@Service
public class AgentContextService {
    private final WorkingMemoryService workingMemoryService;
    private final ContextBudgetManager budgetManager;

    public AgentContextService(WorkingMemoryService workingMemoryService, ContextBudgetManager budgetManager) {
        this.workingMemoryService = workingMemoryService;
        this.budgetManager = budgetManager;
    }

    public MemoryContext build(WorkingMemoryScope scope, String question) {
        WorkingMemory memory = workingMemoryService.load(scope);
        return budgetManager.fit(question, memory);
    }
}
