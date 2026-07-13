package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import io.reactivex.Flowable;
import org.example.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG (Retrieval-Augmented Generation) 服务
 * 结合向量检索和大语言模型生成答案
 * 马尔孔多总是在下雨
 */


@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private RagPromptBuilder ragPromptBuilder;

    @Value("${dashscope.api.key}")
    private String apiKey;

    private Generation generation;

   // API Key 是「token 家族」里偏长期、偏简单的一种凭证。
    @PostConstruct
    public void init() {
        // 设置 API Key 和 Base URL
        Constants.apiKey = apiKey;
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        
        // 创建 Generation 实例
        generation = new Generation();
        
        logger.info("RAG 服务初始化完成，model: {}, topK: {}",
                ragProperties.getModel(), ragProperties.getTopK());
    }

    /**
     * 流式处理用户问题（不带历史消息）
     * 
     * @param question 用户问题
     * @param callback 流式回调接口
     */
    public void queryStream(String question, StreamCallback callback) {
        queryStream(question, new ArrayList<>(), callback);
    }

    /**
     * 流式处理用户问题（带历史消息）
     * 1.没有对question进行预处理，这个question Emedding
     * @param question 用户问题
     * @param history 历史消息列表，格式：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
     * @param callback 流式回调接口
     *
     */
    public void queryStream(String question, List<Map<String, String>> history, StreamCallback callback) {
        long requestStartNs = System.nanoTime();
        try {
            logger.info("收到 RAG 流式查询: {}", question);

            // 1. 从向量数据库检索相关文档
            List<VectorSearchService.SearchResult> searchResults = 
                vectorSearchService.searchSimilarDocuments(question, ragProperties.getTopK());
            logger.info("PERF rag.search durationMs={} resultCount={}",
                    elapsedMs(requestStartNs), searchResults.size());

            // 发送检索结果
            callback.onSearchResults(searchResults);

            if (searchResults.isEmpty()) {
                logger.warn("未找到相关文档");
                callback.onComplete("抱歉，我在知识库中没有找到相关信息来回答您的问题。", "");
                return;
            }

            // 2. 构建上下文和提示词
            RagPromptBuilder.RagPrompt prompt = ragPromptBuilder.build(question, searchResults);
            logger.info("PERF rag.prompt contextLength={} promptLength={} historyPairs={}",
                    prompt.context().length(), prompt.userPrompt().length(), history.size() / 2);

            // 3. 流式调用大语言模型（传入历史消息）
            generateAnswerStream(prompt, history, callback);
            logger.info("PERF rag.total durationMs={}", elapsedMs(requestStartNs));

        } catch (Exception e) {
            logger.info("PERF rag.total durationMs={} failed=true", elapsedMs(requestStartNs));
            logger.error("RAG 流式查询失败", e);
            callback.onError(e);
        }
    }

    /**
     * 生成答案（流式）
     * 
     * @param prompt 当前问题的提示词
     * @param history 历史消息列表
     * @param callback 流式回调接口
     */
    private void generateAnswerStream(RagPromptBuilder.RagPrompt prompt,
                                      List<Map<String, String>> history, StreamCallback callback)
            throws NoApiKeyException, ApiException, InputRequiredException {
        long llmStartNs = System.nanoTime();
        
        // 构建消息列表：历史消息 + 当前问题
        List<Message> messages = new ArrayList<>();

        messages.add(Message.builder()
                .role(Role.SYSTEM.getValue())
                .content(prompt.systemPrompt())
                .build());
        
        // 添加历史消息
        for (Map<String, String> historyMsg : history) {
            String role = historyMsg.get("role");
            String content = historyMsg.get("content");
            
            if ("user".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.USER.getValue())
                        .content(content)
                        .build());
            } else if ("assistant".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.ASSISTANT.getValue())
                        .content(content)
                        .build());
            }
        }
        
        // 添加当前用户问题
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(prompt.userPrompt())
                .build();
        messages.add(userMsg);
        
        logger.debug("发送给AI模型的消息数量: {}（包含 {} 条历史消息）", 
            messages.size(), history.size());

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(ragProperties.getModel())
                .incrementalOutput(true)
                .resultFormat("message")
                .messages(messages)
                .build();

        logger.info("开始调用AI模型流式接口...");
        
        logger.info("PERF rag.llm.request model={} messageCount={} promptLength={}",
                ragProperties.getModel(), messages.size(), prompt.userPrompt().length());
        Flowable<GenerationResult> result = generation.streamCall(param);
        
        StringBuilder reasoningContent = new StringBuilder();
        StringBuilder finalContent = new StringBuilder();
        final long[] firstChunkNs = {0L};
        
        logger.info("开始接收AI模型流式响应...");

            result.blockingForEach(message -> {
            if (message.getOutput() != null && 
                message.getOutput().getChoices() != null && 
                !message.getOutput().getChoices().isEmpty()) {
                
                // 获取消息内容
                // 注意：qwen3-30b-a3b-thinking-2507 模型会在 content 中返回完整内容
                // reasoning 部分可能需要通过特殊方式提取或者直接包含在 content 中
                String content = message.getOutput().getChoices().get(0).getMessage().getContent();

                if (content != null && !content.isEmpty()) {
                    if (firstChunkNs[0] == 0L) {
                        firstChunkNs[0] = System.nanoTime();
                        logger.info("PERF rag.llm.first_chunk durationMs={}", elapsedMs(llmStartNs));
                    }
                    logger.debug("收到AI模型内容块: {}", content);
                    
                    // 对于 thinking 模型，content 可能包含思考过程和最终答案
                    // 这里我们将所有内容都作为答案返回
                    finalContent.append(content);
                        callback.onContentChunk(content);
                    
                    logger.debug("已调用 onContentChunk 回调");
                } else {
                    logger.debug("收到空内容块，跳过");
                }
            }
        });
        
        logger.info("AI模型流式响应完成，总内容长度: {}", finalContent.length());

        logger.info("PERF rag.llm.total durationMs={} firstChunkMs={} answerLength={}",
                elapsedMs(llmStartNs),
                firstChunkNs[0] == 0L ? -1 : nanosToMs(firstChunkNs[0] - llmStartNs),
                finalContent.length());
        callback.onComplete(finalContent.toString(), reasoningContent.toString());
        logger.info("已调用 onComplete 回调");
    }

    /**
     * 流式回调接口
     */
    private static long elapsedMs(long startNs) {
        return nanosToMs(System.nanoTime() - startNs);
    }

    private static long nanosToMs(long nanos) {
        return nanos / 1_000_000;
    }

    public interface StreamCallback {
        void onSearchResults(List<VectorSearchService.SearchResult> results);
        void onReasoningChunk(String chunk);
        void onContentChunk(String chunk);
        void onComplete(String fullContent, String fullReasoning);
        void onError(Exception e);
    }
}
