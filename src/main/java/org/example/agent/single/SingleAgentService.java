package org.example.agent.single;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.budget.AgentBudget;
import org.example.agent.budget.AgentBudgetProperties;
import org.example.agent.budget.BudgetedChatModel;
import org.example.agent.routing.AgentRouter;
import org.example.agent.routing.RouteDecision;
import org.example.agent.tool.AgentToolRegistry;
import org.example.agent.tool.ToolSelection;
import org.example.agent.observability.AgentStage;
import org.example.agent.observability.ModelCallUsage;
import org.example.agent.observability.TokenUsageCollector;
import org.example.agent.observability.TokenUsageSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class SingleAgentService {
    private static final Logger logger = LoggerFactory.getLogger(SingleAgentService.class);

    private final AgentRouter router;
    private final AgentToolRegistry toolRegistry;
    private final AgentBudgetProperties budgetProperties;
    private final Map<ReactAgent, TokenUsageCollector> collectors = new ConcurrentHashMap<>();

    public SingleAgentService(AgentRouter router,
                              AgentToolRegistry toolRegistry,
                              AgentBudgetProperties budgetProperties) {
        this.router = router;
        this.toolRegistry = toolRegistry;
        this.budgetProperties = budgetProperties;
    }

    public ReactAgent createAgent(ChatModel model, String systemPrompt, String question) {
        return createAgent(model, systemPrompt, question, UUID.randomUUID().toString(), usage -> {});
    }

    public ReactAgent createAgent(ChatModel model, String systemPrompt, String question,
                                  String requestId, Consumer<ModelCallUsage> usageListener) {
        return createAgent(model, systemPrompt, prepareRoute(question, requestId, usageListener));
    }

    public SingleAgentRoutingContext prepareRoute(String question, String requestId,
                                                  Consumer<ModelCallUsage> usageListener) {
        return prepareRoute(question, requestId, null, usageListener);
    }

    public SingleAgentRoutingContext prepareRoute(String question, String requestId, String explicitMode,
                                                  Consumer<ModelCallUsage> usageListener) {
        AgentBudget budget = AgentBudget.from(budgetProperties);
        TokenUsageCollector collector = new TokenUsageCollector(usageListener);
        RouteDecision route = router.route(question, budget, collector, requestId);
        if (explicitMode != null && !explicitMode.isBlank()) {
            String mode = explicitMode.trim().toLowerCase(java.util.Locale.ROOT);
            if (java.util.Set.of("aiops", "workflow", "multi", "multi_agent").contains(mode)) {
                java.util.Set<org.example.agent.routing.ToolCapability> capabilities = route.capabilities().isEmpty()
                        ? java.util.EnumSet.allOf(org.example.agent.routing.ToolCapability.class) : route.capabilities();
                route = new RouteDecision(org.example.agent.routing.ExecutionMode.AIOPS_WORKFLOW,
                        org.example.agent.routing.IntentType.INCIDENT_INVESTIGATION, capabilities,
                        org.example.agent.routing.RouteSource.EXPLICIT, null, false, "Explicit workflow mode");
            } else if (java.util.Set.of("single", "single_agent", "quick", "stream").contains(mode)) {
                route = new RouteDecision(org.example.agent.routing.ExecutionMode.SINGLE_AGENT, route.intent(),
                        route.capabilities(), org.example.agent.routing.RouteSource.EXPLICIT, null,
                        route.requiresClarification(), "Explicit single-agent mode");
            }
        }
        return new SingleAgentRoutingContext(requestId, route, budget, collector);
    }

    public ReactAgent createAgent(ChatModel model, String systemPrompt, SingleAgentRoutingContext context) {
        AgentBudget budget = context.budget();
        TokenUsageCollector collector = context.collector();
        RouteDecision route = context.route();
        ToolSelection selection = toolRegistry.select(route, budget);
        logger.info("single_agent execution={} intent={} source={} classifierScore={} methodToolCount={} callbackToolCount={} budget=[models:{},tools:{},tokens:{}]",
                route.executionMode(), route.intent(), route.source(), route.classifierScore(), selection.methodTools().length,
                selection.callbacks().length, budget.getMaxModelCalls(), budget.getMaxToolCalls(), budget.getMaxTotalTokens());

        // Agents are intentionally request-scoped objects. Dynamic conversation prompts are never cached.
        ReactAgent agent = ReactAgent.builder()
                .name("intelligent_assistant")
                .model(new BudgetedChatModel(model, budget, collector, AgentStage.SINGLE_AGENT,
                        context.requestId(), null, () -> null))
                .systemPrompt(systemPrompt)
                .methodTools(selection.methodTools())
                .tools(selection.callbacks())
                .build();
        collectors.put(agent, collector);
        return agent;
    }

    public ReactAgent createAgentWithAllTools(ChatModel model, String systemPrompt) {
        AgentBudget budget = AgentBudget.from(budgetProperties);
        ToolSelection selection = toolRegistry.all(budget);
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(new BudgetedChatModel(model, budget))
                .systemPrompt(systemPrompt)
                .methodTools(selection.methodTools())
                .tools(selection.callbacks())
                .build();
    }

    public String execute(ReactAgent agent, String question) throws GraphRunnerException {
        long startNs = System.nanoTime();
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        String answer = agent.call(question).getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        logger.info("PERF chat_service.agent_call durationMs={} questionLength={} answerLength={}",
                (System.nanoTime() - startNs) / 1_000_000,
                question == null ? 0 : question.length(), answer.length());
        return answer;
    }

    public TokenUsageSummary usage(ReactAgent agent, boolean remove) {
        TokenUsageCollector collector = remove ? collectors.remove(agent) : collectors.get(agent);
        return collector == null ? new TokenUsageCollector().summary() : collector.summary();
    }
}
