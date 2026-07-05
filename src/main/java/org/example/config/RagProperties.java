package org.example.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索与生成配置。
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /**
     * 返回给大模型的最终文档数量。
     */
    private int topK = 3;

    /**
     * 从 Milvus 召回的候选数量，用于后续过滤或重排。
     */
    private int candidateK = 12;

    /**
     * L2 距离阈值。小于等于该值的结果才会进入上下文，0 表示不启用过滤。
     */
    private float maxDistance = 0.0f;

    /**
     * 生成模型名称。
     */
    private String model = "qwen3-30b-a3b-thinking-2507";

    /**
     * 独立重排模型配置。
     */
    private Rerank rerank = new Rerank();

    @Getter
    @Setter
    public static class Rerank {

        /**
         * 是否启用模型重排。关闭后仅使用本地轻量规则重排。
         */
        private boolean enabled = false;

        /**
         * DashScope rerank 模型名称。
         */
        private String model = "gte-rerank-v2";

        /**
         * 是否让 DashScope 返回文档内容，用于稳定映射回原始检索结果。
         */
        private boolean returnDocuments = true;
    }
}
