package org.example.agent.workflow.model;

import org.example.agent.budget.AgentBudget;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.example.agent.routing.ToolCapability;

public class IncidentState {
    private final String taskId;
    private final String userRequest;
    private final AgentBudget budget;
    private final int maxRounds;
    private final int failureLimit;
    private final Set<ToolCapability> allowedCapabilities;
    private final List<InvestigationStep> steps = new ArrayList<>();
    private final List<ToolEvidence> evidence = new ArrayList<>();
    private final Map<ToolType, Integer> toolFailureCounts = new EnumMap<>(ToolType.class);
    private final Set<String> invocationFingerprints = new HashSet<>();
    private WorkflowStatus status = WorkflowStatus.CREATED;
    private InvestigationPlan currentPlan;
    private int currentRound;
    private String terminationReason;

    public IncidentState(String taskId, String userRequest, AgentBudget budget, int maxRounds, int failureLimit) {
        this(taskId, userRequest, budget, maxRounds, failureLimit, java.util.EnumSet.allOf(ToolCapability.class));
    }

    public IncidentState(String taskId, String userRequest, AgentBudget budget, int maxRounds, int failureLimit,
                         Set<ToolCapability> allowedCapabilities) {
        this.taskId = taskId;
        this.userRequest = userRequest;
        this.budget = budget;
        this.maxRounds = maxRounds;
        this.failureLimit = failureLimit;
        this.allowedCapabilities = allowedCapabilities == null
                ? Set.copyOf(java.util.EnumSet.allOf(ToolCapability.class)) : Set.copyOf(allowedCapabilities);
    }

    public void beginRound() { currentRound++; status = WorkflowStatus.PLANNING; }
    public void applyPlan(InvestigationPlan plan) { currentPlan = plan; steps.addAll(plan.steps()); }
    public void addEvidence(ToolEvidence item) {
        evidence.add(item);
        if (!item.success()) toolFailureCounts.merge(item.toolType(), 1, Integer::sum);
    }
    public boolean registerInvocation(String fingerprint) { return invocationFingerprints.add(fingerprint); }
    public boolean failureLimitReached(ToolType type) { return toolFailureCounts.getOrDefault(type, 0) >= failureLimit; }
    public void terminate(WorkflowStatus finalStatus, String reason) { status = finalStatus; terminationReason = reason; }
    public void setStatus(WorkflowStatus status) { this.status = status; }

    public String getTaskId() { return taskId; }
    public String getUserRequest() { return userRequest; }
    public AgentBudget getBudget() { return budget; }
    public int getMaxRounds() { return maxRounds; }
    public int getCurrentRound() { return currentRound; }
    public WorkflowStatus getStatus() { return status; }
    public InvestigationPlan getCurrentPlan() { return currentPlan; }
    public List<InvestigationStep> getSteps() { return List.copyOf(steps); }
    public List<ToolEvidence> getEvidence() { return List.copyOf(evidence); }
    public Map<ToolType, Integer> getToolFailureCounts() { return Map.copyOf(toolFailureCounts); }
    public String getTerminationReason() { return terminationReason; }
    public boolean allows(ToolType type) {
        ToolCapability required = switch (type) {
            case QUERY_ALERTS, QUERY_METRICS -> ToolCapability.METRICS;
            case QUERY_LOGS -> ToolCapability.LOGS;
            case SEARCH_INTERNAL_DOCS -> ToolCapability.INTERNAL_DOCS;
            case GET_CURRENT_TIME -> ToolCapability.DATE_TIME;
        };
        return allowedCapabilities.contains(required);
    }
    public Set<ToolCapability> getAllowedCapabilities() { return allowedCapabilities; }
}
