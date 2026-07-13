package org.example.agent.model;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AgentModelFactory {
    private static final Logger logger = LoggerFactory.getLogger(AgentModelFactory.class);

    private final String apiKey;
    private volatile DashScopeApi cachedApi;
    private volatile DashScopeChatModel cachedStandardModel;
    private volatile DashScopeChatModel cachedClassifierModel;

    public AgentModelFactory(@Value("${spring.ai.dashscope.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    public DashScopeApi dashScopeApi() {
        DashScopeApi local = cachedApi;
        if (local == null) {
            synchronized (this) {
                local = cachedApi;
                if (local == null) {
                    local = DashScopeApi.builder().apiKey(apiKey).build();
                    cachedApi = local;
                    logger.info("AGENT_OPTIMIZATION DashScopeApi 初始化并缓存完成");
                }
            }
        }
        return local;
    }

    public DashScopeChatModel create(double temperature, int maxToken, double topP) {
        return create(dashScopeApi(), temperature, maxToken, topP);
    }

    public DashScopeChatModel create(DashScopeApi api, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    public DashScopeChatModel standardModel() {
        return standardModel(dashScopeApi());
    }

    public DashScopeChatModel standardModel(DashScopeApi api) {
        DashScopeChatModel local = cachedStandardModel;
        if (local == null) {
            synchronized (this) {
                local = cachedStandardModel;
                if (local == null) {
                    local = create(api, 0.7, 2000, 0.9);
                    cachedStandardModel = local;
                    logger.info("AGENT_OPTIMIZATION 标准 ChatModel 初始化并缓存完成");
                }
            }
        }
        return local;
    }

    public DashScopeChatModel aiOpsModel() {
        return create(0.3, 8000, 0.9);
    }

    public DashScopeChatModel classifierModel() {
        DashScopeChatModel local = cachedClassifierModel;
        if (local == null) {
            synchronized (this) {
                local = cachedClassifierModel;
                if (local == null) {
                    local = create(0.0, 300, 0.1);
                    cachedClassifierModel = local;
                }
            }
        }
        return local;
    }
}
