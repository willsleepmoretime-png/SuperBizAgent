package org.example.agent.workflow.engine;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.example.agent.budget.AgentBudget;
import org.example.agent.budget.BudgetExceededException;
import org.example.agent.workflow.AiOpsWorkflowRunner;
import org.example.agent.workflow.WorkflowEventListener;
import org.example.agent.observability.TokenUsageCollector;
import org.example.agent.workflow.model.IncidentState;
import org.example.agent.workflow.model.InvestigationPlan;
import org.example.agent.workflow.model.InvestigationStep;
import org.example.agent.workflow.model.PlannerDecision;
import org.example.agent.workflow.model.StepStatus;
import org.example.agent.workflow.model.ToolEvidence;
import org.example.agent.workflow.model.WorkflowStatus;
import org.example.dto.agent.AiOpsCommand;
import org.example.dto.agent.AiOpsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.agent.AgentSseEvent;

@Service
public class AiOpsWorkflowEngine implements AiOpsWorkflowRunner {
    private static final Logger logger = LoggerFactory.getLogger(AiOpsWorkflowEngine.class);

    private final PlannerService planner;
    private final ToolExecutor executor;
    private final ReportGenerator reporter;
    private final AiOpsWorkflowProperties properties;
    private final ObjectMapper objectMapper;

    public AiOpsWorkflowEngine(PlannerService planner, ToolExecutor executor,
                               ReportGenerator reporter, AiOpsWorkflowProperties properties,
                               ObjectMapper objectMapper) {
        this.planner = planner;
        this.executor = executor;
        this.reporter = reporter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiOpsResult run(AiOpsCommand command, DashScopeChatModel model, ToolCallback[] callbacks) {
        return run(command, model, callbacks, WorkflowEventListener.noop());
    }

    @Override
    public AiOpsResult run(AiOpsCommand command, DashScopeChatModel model, ToolCallback[] callbacks,
                           WorkflowEventListener listener) {
        AiOpsCommand effective = command == null ? AiOpsCommand.legacyDefault() : command;
        String taskId = effective.ensureTaskId();
        AgentBudget budget = new AgentBudget(properties.getMaxToolCalls(), properties.getMaxModelCalls(),
                properties.getMaxTotalTokens());
        IncidentState state = new IncidentState(taskId, effective.effectiveUserRequest(), budget,
                properties.getMaxRounds(), properties.getToolFailureLimit(), effective.getAllowedCapabilities());
        TokenUsageCollector collector = new TokenUsageCollector(usage ->
                listener.onEvent(AgentSseEvent.usage(json(usage))));

        try {
            runLoop(state, model, callbacks, collector, listener);
        } catch (BudgetExceededException e) {
            state.terminate(WorkflowStatus.PARTIAL, e.getMessage());
        } catch (Exception e) {
            logger.error("AIOps state machine failed taskId={}", taskId, e);
            state.terminate(WorkflowStatus.FAILED, e.getMessage());
        }

        WorkflowStatus terminalStatus = state.getStatus();
        state.setStatus(WorkflowStatus.REPORTING);
        String report;
        try {
            report = reporter.generate(state, model, collector);
        } catch (Exception e) {
            report = fallbackReport(state, e.getMessage());
        }
        state.setStatus(terminalStatus);
        boolean completed = terminalStatus == WorkflowStatus.COMPLETED;
        String status = completed ? "COMPLETED" : "PARTIAL";
        return new AiOpsResult(taskId, status, report, completed ? null : state.getTerminationReason());
    }

    private void runLoop(IncidentState state, DashScopeChatModel model, ToolCallback[] callbacks,
                         TokenUsageCollector collector, WorkflowEventListener listener) {
        while (true) {
            if (state.getCurrentRound() >= state.getMaxRounds()) {
                state.terminate(WorkflowStatus.PARTIAL, "Maximum planning rounds reached");
                return;
            }
            if (state.getBudget().getRemainingToolCalls() == 0) {
                state.terminate(WorkflowStatus.PARTIAL, "Tool call budget exhausted");
                return;
            }
            if (state.getBudget().getRemainingModelCalls() <= 1
                    || state.getBudget().getRemainingTokens() <= properties.getReportTokenReserve()) {
                state.terminate(WorkflowStatus.PARTIAL, "Budget reserved for final report");
                return;
            }

            state.beginRound();
            InvestigationPlan plan = planner.plan(state, model, collector);
            state.applyPlan(plan);
            listener.onEvent(AgentSseEvent.plan(json(plan)));
            if (plan.decision() == PlannerDecision.FINISH) {
                state.terminate(WorkflowStatus.COMPLETED, "Planner finished: " + plan.reason());
                return;
            }
            if (plan.decision() == PlannerDecision.ABORT) {
                state.terminate(WorkflowStatus.ABORTED, "Planner aborted: " + plan.reason());
                return;
            }
            if (plan.decision() == PlannerDecision.REQUIRE_HUMAN_INPUT) {
                state.terminate(WorkflowStatus.PARTIAL, "Human input required: " + plan.reason());
                return;
            }

            InvestigationStep step = plan.steps().get(0);
            if (state.failureLimitReached(step.toolType())) {
                state.terminate(WorkflowStatus.PARTIAL, "Tool failure limit reached: " + step.toolType());
                return;
            }
            state.setStatus(WorkflowStatus.EXECUTING);
            listener.onEvent(AgentSseEvent.stepStarted(json(step)));
            ToolEvidence evidence = executor.execute(state, step.withStatus(StepStatus.RUNNING), callbacks);
            state.addEvidence(evidence);
            listener.onEvent(AgentSseEvent.toolResult(json(evidence)));
            if (!evidence.success() && state.failureLimitReached(step.toolType())) {
                state.terminate(WorkflowStatus.PARTIAL, "Tool failed repeatedly: " + step.toolType());
                return;
            }
        }
    }

    private String fallbackReport(IncidentState state, String reportError) {
        StringBuilder report = new StringBuilder("# 告警分析报告\n\n");
        report.append("## 执行状态\n\n").append(state.getTerminationReason()).append("\n\n");
        report.append("## 已收集证据\n\n");
        for (ToolEvidence item : state.getEvidence()) {
            report.append("- [evidence:").append(item.evidenceId()).append("] ")
                    .append(item.success() ? item.content() : item.errorMessage()).append("\n");
        }
        report.append("\n## 报告生成说明\n\n无法完成模型报告生成：").append(reportError);
        return report.toString();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return "{\"serializationError\":\"" + e.getMessage().replace("\"", "'") + "\"}"; }
    }
}
