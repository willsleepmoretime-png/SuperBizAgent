package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册，所以设置为 optional,真实环境通过mcp配置注入
    private QueryLogsTools queryLogsTools;

    @Autowired(required = false)
    private ToolCallbackProvider tools;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    // AGENT_OPTIMIZATION: DashScopeApi 和标准 ChatModel 复用，避免每次请求重复构建基础模型对象。
    private volatile DashScopeApi cachedDashScopeApi;
    private volatile DashScopeChatModel cachedStandardChatModel;

    // AGENT_OPTIMIZATION: Agent 按 systemPrompt + 工具路由结果缓存。历史变化会产生新 key，因此缓存做简单上限控制。
    private final Map<String, ReactAgent> agentCache = new ConcurrentHashMap<>();
    private static final int MAX_AGENT_CACHE_SIZE = 128;

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        DashScopeApi localApi = cachedDashScopeApi;
        if (localApi == null) {
            synchronized (this) {
                localApi = cachedDashScopeApi;
                if (localApi == null) {
                    localApi = DashScopeApi.builder()
                            .apiKey(dashScopeApiKey)
                            .build();
                    cachedDashScopeApi = localApi;
                    logger.info("AGENT_OPTIMIZATION DashScopeApi 初始化并缓存完成");
                }
            }
        }
        return localApi;
    }

    /**
     *
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        DashScopeChatModel localModel = cachedStandardChatModel;
        if (localModel == null) {
            synchronized (this) {
                localModel = cachedStandardChatModel;
                if (localModel == null) {
                    localModel = createChatModel(dashScopeApi, 0.7, 2000, 0.9);
                    cachedStandardChatModel = localModel;
                    logger.info("AGENT_OPTIMIZATION 标准 ChatModel 初始化并缓存完成");
                }
            }
        }
        return localModel;
    }

    /**
     * 构建系统提示词（包含历史消息）
     * @param history 历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(List<Map<String, String>> history) {
        return buildSystemPrompt("", history);
    }

    /**
     * 构建系统提示词（包含滚动摘要和最近历史消息）
     * @param memorySummary 被短期窗口淘汰的历史摘要
     * @param history 最近历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(String memorySummary, List<Map<String, String>> history) {
        StringBuilder systemPromptBuilder = new StringBuilder();
        
        // 基础系统提示
        systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询天气信息、搜索内部文档知识库，以及查询 Prometheus 告警信息。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户需要查询腾讯云日志时，请调用腾讯云mcp服务查询,默认查询地域ap-guangzhou,查询时间范围为近一个月。\n");
        systemPromptBuilder.append("使用记忆时要优先参考最近对话；会话摘要只作为远期上下文，不要把摘要中的旧结论当作当前事实直接复述。\n\n");

        // 添加被短期窗口淘汰的历史摘要
        if (memorySummary != null && !memorySummary.isBlank()) {
            systemPromptBuilder.append("--- 会话摘要（较早历史，可能不完整） ---\n");
            systemPromptBuilder.append(memorySummary.trim()).append("\n");
            systemPromptBuilder.append("--- 会话摘要结束 ---\n\n");
        }

        // 添加最近历史消息
        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 最近对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 最近对话历史结束 ---\n\n");
        }
        
        systemPromptBuilder.append("请基于以上上下文回答用户的新问题；如果记忆和当前问题冲突，以当前问题为准。");
        
        return systemPromptBuilder.toString();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    public Object[] buildMethodToolsArray() {
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * AGENT_OPTIMIZATION: 工具 Router，根据用户意图只挂载相关 method tools。
     */
    public Object[] buildRoutedMethodToolsArray(String question) {
        ToolRoute route = routeQuestion(question);
        List<Object> selectedTools = new ArrayList<>();

        if (route.useDateTime) {
            selectedTools.add(dateTimeTools);
        }
        if (route.useInternalDocs) {
            selectedTools.add(internalDocsTools);
        }
        if (route.useMetrics) {
            selectedTools.add(queryMetricsTools);
        }
        if (route.useLogs && queryLogsTools != null) {
            selectedTools.add(queryLogsTools);
        }

        logger.info("AGENT_OPTIMIZATION tool_router route={} selectedMethodTools={}",
                route.name, selectedTools.stream().map(tool -> tool.getClass().getSimpleName()).toList());
        return selectedTools.toArray();
    }

    public String getToolRouteSummary(String question) {
        ToolRoute route = routeQuestion(question);
        return String.format("route=%s,dateTime=%s,docs=%s,metrics=%s,logs=%s",
                route.name, route.useDateTime, route.useInternalDocs, route.useMetrics, route.useLogs);
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        if (tools == null) {
            return new ToolCallback[0];
        }
        return tools.getToolCallbacks();
    }

    /**
     * AGENT_OPTIMIZATION: 外部 MCP 工具也按路由粗过滤。未知工具名保守保留在运维排障类路由里。
     */
    public ToolCallback[] getRoutedToolCallbacks(String question) {
        if (tools == null) {
            return new ToolCallback[0];
        }
        ToolRoute route = routeQuestion(question);
        if (route.useLogs || route.useMetrics) {
            return tools.getToolCallbacks();
        }
        return Arrays.stream(tools.getToolCallbacks())
                .filter(toolCallback -> {
                    String name = toolCallback.getToolDefinition().name().toLowerCase(Locale.ROOT);
                    return (route.useInternalDocs && name.contains("doc"))
                            || (route.useDateTime && (name.contains("time") || name.contains("date")));
                })
                .toArray(ToolCallback[]::new);
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        if (tools == null) {
            logger.info("可用工具列表: MCP 未启用，当前没有外部工具回调");
            return;
        }
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt) {
        return createReactAgent(chatModel, systemPrompt, null);
    }

    /**
     * AGENT_OPTIMIZATION: 创建带工具路由和缓存的 ReactAgent。
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @param question 用户问题，用于工具 Router
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel, String systemPrompt, String question) {
        String routeSummary = getToolRouteSummary(question);
        String cacheKey = routeSummary + ":" + systemPrompt.hashCode();

        ReactAgent cachedAgent = agentCache.get(cacheKey);
        if (cachedAgent != null) {
            logger.info("AGENT_OPTIMIZATION agent_cache hit route={}", routeSummary);
            return cachedAgent;
        }

        if (agentCache.size() >= MAX_AGENT_CACHE_SIZE) {
            agentCache.clear();
            logger.info("AGENT_OPTIMIZATION agent_cache cleared maxSize={}", MAX_AGENT_CACHE_SIZE);
        }

        Object[] methodTools = question == null ? buildMethodToolsArray() : buildRoutedMethodToolsArray(question);
        ToolCallback[] routedToolCallbacks = question == null ? getToolCallbacks() : getRoutedToolCallbacks(question);
        logger.info("AGENT_OPTIMIZATION agent_cache miss route={} methodToolCount={} callbackToolCount={}",
                routeSummary, methodTools.length, routedToolCallbacks.length);

        ReactAgent agent = ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(methodTools)
                .tools(routedToolCallbacks)
                .build();
        agentCache.put(cacheKey, agent);
        return agent;
    }

    public ReactAgent createReactAgentWithoutOptimization(DashScopeChatModel chatModel, String systemPrompt) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray())
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        long startNs = System.nanoTime();
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        logger.info("PERF chat_service.agent_call durationMs={} questionLength={} answerLength={}",
                elapsedMs(startNs), question == null ? 0 : question.length(), answer.length());
        return answer;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private ToolRoute routeQuestion(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean time = containsAny(text, "时间", "日期", "几点", "今天", "明天", "昨天", "time", "date");
        boolean docs = containsAny(text, "文档", "知识库", "流程", "方案", "最佳实践", "怎么处理", "如何处理", "排查", "故障", "cpu", "内存", "磁盘", "响应慢", "不可用", "rag");
        boolean metrics = containsAny(text, "告警", "监控", "指标", "prometheus", "alert", "cpu", "内存", "磁盘", "响应时间");
        boolean logs = containsAny(text, "日志", "错误日志", "异常", "报错", "error", "exception", "cls", "查询日志");

        if (!time && !docs && !metrics && !logs) {
            return new ToolRoute("CHAT_ONLY", false, false, false, false);
        }
        if (docs && metrics && logs) {
            return new ToolRoute("AIOPS_FULL", false, true, true, true);
        }
        if (docs && (metrics || logs)) {
            return new ToolRoute("AIOPS_PARTIAL", false, true, metrics, logs);
        }
        if (docs) {
            return new ToolRoute("DOCS_RAG", false, true, false, false);
        }
        if (metrics || logs) {
            return new ToolRoute("OBSERVABILITY", false, false, metrics, logs);
        }
        return new ToolRoute("DATETIME", time, false, false, false);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private record ToolRoute(
            String name,
            boolean useDateTime,
            boolean useInternalDocs,
            boolean useMetrics,
            boolean useLogs
    ) {
    }
}
