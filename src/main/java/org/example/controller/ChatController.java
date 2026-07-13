package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.agent.workflow.AiOpsWorkflowRunner;
import org.example.dto.agent.AgentRequest;
import org.example.dto.agent.AgentResponse;
import org.example.dto.agent.AgentSseEvent;
import org.example.dto.agent.AiOpsCommand;
import org.example.dto.agent.AiOpsResult;
import org.example.agent.routing.ExecutionMode;
import org.example.agent.single.SingleAgentRoutingContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.memory.working.WorkingMemory;
import org.example.memory.working.WorkingMemoryScope;
import org.example.memory.working.WorkingMemoryService;
import org.example.memory.context.AgentContextService;
import org.example.memory.context.MemoryContext;
import org.example.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private AiOpsWorkflowRunner aiOpsWorkflowRunner;
    
    @Autowired
    private ChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkingMemoryService workingMemoryService;

    @Autowired
    private AgentContextService agentContextService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<AgentResponse>> chat(@RequestBody AgentRequest request) {
        long requestStartNs = System.nanoTime();
        try {
            logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(AgentResponse.error("问题内容不能为空")));
            }
            WorkingMemoryScope memoryScope = memoryScope(request);

            SingleAgentRoutingContext routing = chatService.prepareRoute(
                    request.getQuestion(), request.getId(), request.getMode(), usage -> {});
            if (routing.route().executionMode() == ExecutionMode.AIOPS_WORKFLOW) {
                AiOpsCommand command = new AiOpsCommand();
                command.setTaskId(request.getId());
                command.setUserRequest(request.getQuestion());
                command.setAllowedCapabilities(routing.route().capabilities());
                AiOpsResult result = aiOpsWorkflowRunner.run(command, chatService.createAiOpsChatModel(),
                        chatService.getToolCallbacks());
                AgentResponse response = result.report() == null
                        ? AgentResponse.error(result.errorMessage()) : AgentResponse.success(result.report());
                if (result.report() != null) {
                    workingMemoryService.appendPair(memoryScope, request.getQuestion(), result.report());
                }
                return ResponseEntity.ok(ApiResponse.success(response));
            }

            MemoryContext memoryContext = agentContextService.build(memoryScope, request.getQuestion());
            logger.info("会话上下文消息对数: {}, 预计输入 Token: {}",
                    memoryContext.usage().includedMessagePairs(), memoryContext.usage().estimatedInputTokens());

            // 创建 DashScope API 和 ChatModel
            long modelCreateStartNs = System.nanoTime();
            DashScopeApi dashScopeApi = chatService.createDashScopeApi();
            DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);
            logger.info("PERF chat.model_create durationMs={}", elapsedMs(modelCreateStartNs));

            //  记录可用工具
            chatService.logAvailableTools();

            logger.info("开始 ReactAgent 对话（支持自动工具调用）");
            
            // 构建系统提示词（包含历史消息）
            String systemPrompt = chatService.buildSystemPrompt(memoryContext);
            
            // 创建 ReactAgent
            logger.info("PERF chat.prompt length={} historyPairs={}", systemPrompt.length(),
                    memoryContext.usage().includedMessagePairs());
            // AGENT_OPTIMIZATION: 普通对话入口接入工具 Router + Agent 缓存，只挂载当前问题相关工具。
            ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt, routing);
            
            // 执行对话
            String fullAnswer = chatService.executeChat(agent, request.getQuestion());
            
            // 更新会话历史
            workingMemoryService.appendPair(memoryScope, request.getQuestion(), fullAnswer);
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}, 摘要长度: {}",
                request.getId(), memoryContext.usage().includedMessagePairs() + 1, memoryContext.summary().length());
            
            logger.info("PERF chat.total durationMs={} answerLength={}",
                    elapsedMs(requestStartNs), fullAnswer.length());
            AgentResponse response = AgentResponse.success(fullAnswer);
            response.setUsage(chatService.getUsage(agent, true));
            response.setContextUsage(memoryContext.usage());
            return ResponseEntity.ok(ApiResponse.success(response));

        } catch (Exception e) {
            logger.info("PERF chat.total durationMs={} failed=true", elapsedMs(requestStartNs));
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(AgentResponse.error(e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            WorkingMemoryScope scope = new WorkingMemoryScope(request.getUserId(), request.getId());
            if (workingMemoryService.clear(scope)) {
                return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody AgentRequest request) {
        long requestStartNs = System.nanoTime();
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(AgentSseEvent.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        try {
            memoryScope(request);
        } catch (IllegalArgumentException e) {
            sendEvent(emitter, AgentSseEvent.error(e.getMessage()));
            emitter.complete();
            return emitter;
        }

        executor.execute(() -> {
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                SingleAgentRoutingContext routing = chatService.prepareRoute(request.getQuestion(), request.getId(), request.getMode(),
                        usage -> sendEvent(emitter, AgentSseEvent.usage(toJson(usage))));
                if (routing.route().executionMode() == ExecutionMode.AIOPS_WORKFLOW) {
                    executeAutoWorkflow(emitter, request, routing);
                    return;
                }
                WorkingMemoryScope memoryScope = memoryScope(request);

                MemoryContext memoryContext = agentContextService.build(memoryScope, request.getQuestion());
                logger.info("ReactAgent 会话上下文消息对数: {}, 预计输入 Token: {}",
                        memoryContext.usage().includedMessagePairs(), memoryContext.usage().estimatedInputTokens());
                sendEvent(emitter, AgentSseEvent.contextUsage(toJson(memoryContext.usage())));

                // 创建 DashScope API 和 ChatModel
                long modelCreateStartNs = System.nanoTime();
                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);
                logger.info("PERF chat_stream.model_create durationMs={}", elapsedMs(modelCreateStartNs));

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");
                
                // 构建系统提示词（包含历史消息）
                String systemPrompt = chatService.buildSystemPrompt(memoryContext);
                
                // 创建 ReactAgent
                logger.info("PERF chat_stream.prompt length={} historyPairs={}", systemPrompt.length(),
                        memoryContext.usage().includedMessagePairs());
                // AGENT_OPTIMIZATION: 流式对话入口接入工具 Router + Agent 缓存，只挂载当前问题相关工具。
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt, routing);
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                final long[] firstChunkNs = {0L};
                
                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());
                
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        if (firstChunkNs[0] == 0L) {
                                            firstChunkNs[0] = System.nanoTime();
                                            logger.info("PERF chat_stream.first_chunk durationMs={}",
                                                    elapsedMs(requestStartNs));
                                        }
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(AgentSseEvent.content(chunk), MediaType.APPLICATION_JSON));
                                        
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(AgentSseEvent.error(error.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("PERF chat_stream.total durationMs={} answerLength={} firstChunkMs={}",
                                    elapsedMs(requestStartNs),
                                    fullAnswer.length(),
                                    firstChunkNs[0] == 0L ? -1 : nanosToMs(firstChunkNs[0] - requestStartNs));
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}", 
                                request.getId(), fullAnswer.length());
                            
                            // 更新会话历史
                            workingMemoryService.appendPair(memoryScope, request.getQuestion(), fullAnswer);
                            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}, 摘要长度: {}",
                                request.getId(), memoryContext.usage().includedMessagePairs() + 1,
                                    memoryContext.summary().length());
                            
                            // 发送完成标记
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(AgentSseEvent.done(), MediaType.APPLICATION_JSON));
                            chatService.getUsage(agent, true);
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(AgentSseEvent.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps(@RequestBody(required = false) AiOpsCommand command) {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                DashScopeChatModel chatModel = chatService.createAiOpsChatModel();
                ToolCallback[] toolCallbacks = chatService.getToolCallbacks();

                emitter.send(SseEmitter.event().name("message").data(AgentSseEvent.content("正在读取告警并拆解任务...\n")));
                
                AiOpsCommand effectiveCommand = command == null ? AiOpsCommand.legacyDefault() : command;
                emitter.send(SseEmitter.event().name("message")
                        .data(AgentSseEvent.taskCreated(effectiveCommand.ensureTaskId()), MediaType.APPLICATION_JSON));
                AiOpsResult result = aiOpsWorkflowRunner.run(effectiveCommand, chatModel, toolCallbacks,
                        event -> sendEvent(emitter, event));

                if (result.report() == null) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentSseEvent.error(result.errorMessage()), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                // 输出最终报告
                if (result.report() != null) {
                    String finalReportText = result.report();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());
                    
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentSseEvent.report(finalReportText), MediaType.APPLICATION_JSON));
                    
                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentSseEvent.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(AgentSseEvent.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(AgentSseEvent.error("AI Ops 流程失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }


    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId,
                                                                            @RequestParam String userId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            WorkingMemory memory = workingMemoryService.load(new WorkingMemoryScope(userId, sessionId));
            if (!memory.getRecentMessages().isEmpty() || !memory.getSummary().isBlank()) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(memory.getRecentMessages().size() / 2);
                response.setSummaryLength(memory.getSummary().length());
                response.setCreateTime(memory.getCreatedAt().toEpochMilli());
                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.ok(ApiResponse.error("会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    private WorkingMemoryScope memoryScope(AgentRequest request) {
        return new WorkingMemoryScope(request.getUserId(), request.getId());
    }

    private static long elapsedMs(long startNs) {
        return nanosToMs(System.nanoTime() - startNs);
    }

    private static long nanosToMs(long nanos) {
        return nanos / 1_000_000;
    }

    private void sendEvent(SseEmitter emitter, AgentSseEvent event) {
        try {
            emitter.send(SseEmitter.event().name("message").data(event, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void executeAutoWorkflow(SseEmitter emitter, AgentRequest request, SingleAgentRoutingContext routing) {
        try {
            AiOpsCommand command = new AiOpsCommand();
            command.setTaskId(request.getId());
            command.setUserRequest(request.getQuestion());
            command.setAllowedCapabilities(routing.route().capabilities());
            sendEvent(emitter, AgentSseEvent.taskCreated(command.ensureTaskId()));
            AiOpsResult result = aiOpsWorkflowRunner.run(command, chatService.createAiOpsChatModel(),
                    chatService.getToolCallbacks(), event -> sendEvent(emitter, event));
            if (result.report() == null) {
                sendEvent(emitter, AgentSseEvent.error(result.errorMessage()));
            } else {
                sendEvent(emitter, AgentSseEvent.report(result.report()));
                workingMemoryService.appendPair(memoryScope(request), request.getQuestion(), result.report());
            }
            sendEvent(emitter, AgentSseEvent.done());
            emitter.complete();
        } catch (Exception e) {
            sendEvent(emitter, AgentSseEvent.error(e.getMessage()));
            emitter.completeWithError(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"serializationError\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    @Deprecated
    public static class ChatRequest extends AgentRequest {}

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;

        private String userId;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private int summaryLength;
        private long createTime;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse extends AgentResponse {

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage extends AgentSseEvent {

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }
}
