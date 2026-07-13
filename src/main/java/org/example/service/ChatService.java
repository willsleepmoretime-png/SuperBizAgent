package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.model.AgentModelFactory;
import org.example.agent.prompt.SingleAgentPromptBuilder;
import org.example.agent.routing.AgentRouter;
import org.example.agent.routing.RouteDecision;
import org.example.agent.single.SingleAgentService;
import org.example.agent.tool.AgentToolRegistry;
import org.example.agent.tool.ToolSelection;
import org.example.agent.observability.ModelCallUsage;
import org.example.agent.observability.TokenUsageSummary;
import org.example.memory.context.MemoryContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.example.agent.single.SingleAgentRoutingContext;

/**
 * Backward-compatible facade for existing controllers and debug endpoints.
 * New code should depend on the focused agent components directly.
 */
@Service
public class ChatService {
    private final AgentModelFactory modelFactory;
    private final SingleAgentPromptBuilder promptBuilder;
    private final AgentRouter router;
    private final AgentToolRegistry toolRegistry;
    private final SingleAgentService singleAgentService;

    public ChatService(AgentModelFactory modelFactory,
                       SingleAgentPromptBuilder promptBuilder,
                       AgentRouter router,
                       AgentToolRegistry toolRegistry,
                       SingleAgentService singleAgentService) {
        this.modelFactory = modelFactory;
        this.promptBuilder = promptBuilder;
        this.router = router;
        this.toolRegistry = toolRegistry;
        this.singleAgentService = singleAgentService;
    }

    public DashScopeApi createDashScopeApi() {
        return modelFactory.dashScopeApi();
    }

    public DashScopeChatModel createChatModel(DashScopeApi api, double temperature, int maxToken, double topP) {
        return modelFactory.create(api, temperature, maxToken, topP);
    }

    public DashScopeChatModel createStandardChatModel(DashScopeApi api) {
        return modelFactory.standardModel(api);
    }

    public DashScopeChatModel createAiOpsChatModel() {
        return modelFactory.aiOpsModel();
    }

    public String buildSystemPrompt(List<Map<String, String>> history) {
        return promptBuilder.build(history);
    }

    public String buildSystemPrompt(String memorySummary, List<Map<String, String>> history) {
        return promptBuilder.build(memorySummary, history);
    }

    public String buildSystemPrompt(MemoryContext context) {
        return promptBuilder.build(context);
    }

    public Object[] buildMethodToolsArray() {
        return toolRegistry.allMethodTools();
    }

    public Object[] buildRoutedMethodToolsArray(String question) {
        return routedSelection(question).methodTools();
    }

    public String getToolRouteSummary(String question) {
        return router.route(question).summary();
    }

    public ToolCallback[] getToolCallbacks() {
        return toolRegistry.allCallbacks();
    }

    public ToolCallback[] getRoutedToolCallbacks(String question) {
        return routedSelection(question).callbacks();
    }

    public void logAvailableTools() {
        toolRegistry.logAvailableTools();
    }

    public ReactAgent createReactAgent(DashScopeChatModel model, String systemPrompt) {
        return singleAgentService.createAgentWithAllTools(model, systemPrompt);
    }

    public ReactAgent createReactAgent(DashScopeChatModel model, String systemPrompt, String question) {
        return singleAgentService.createAgent(model, systemPrompt, question);
    }

    public ReactAgent createReactAgent(DashScopeChatModel model, String systemPrompt, String question,
                                       String requestId, Consumer<ModelCallUsage> usageListener) {
        return singleAgentService.createAgent(model, systemPrompt, question, requestId, usageListener);
    }

    public SingleAgentRoutingContext prepareRoute(String question, String requestId,
                                                  Consumer<ModelCallUsage> usageListener) {
        return singleAgentService.prepareRoute(question, requestId, usageListener);
    }

    public SingleAgentRoutingContext prepareRoute(String question, String requestId, String explicitMode,
                                                  Consumer<ModelCallUsage> usageListener) {
        return singleAgentService.prepareRoute(question, requestId, explicitMode, usageListener);
    }

    public ReactAgent createReactAgent(DashScopeChatModel model, String systemPrompt,
                                       SingleAgentRoutingContext context) {
        return singleAgentService.createAgent(model, systemPrompt, context);
    }

    public ReactAgent createReactAgentWithoutOptimization(DashScopeChatModel model, String systemPrompt) {
        return singleAgentService.createAgentWithAllTools(model, systemPrompt);
    }

    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        return singleAgentService.execute(agent, question);
    }

    public TokenUsageSummary getUsage(ReactAgent agent, boolean remove) {
        return singleAgentService.usage(agent, remove);
    }

    private ToolSelection routedSelection(String question) {
        RouteDecision route = router.route(question);
        return toolRegistry.select(route);
    }
}
