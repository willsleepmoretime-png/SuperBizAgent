package org.example.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 单条 RAG 检索评测样例。
 */
@Getter
@Setter
public class RagEvalCase {

    private String id;

    /**
     * 用户查询。
     */
    private String query;

    /**
     * 人工标注的相关文档或 chunk 标识。
     *
     * 支持格式：
     * - Milvus 内部 id
     * - 文件名#chunk-0，例如 cpu_high_usage.md#chunk-0
     * - 文件名#0，例如 cpu_high_usage.md#0
     * - 文件名，例如 cpu_high_usage.md
     */
    private List<String> relevantDocIds = new ArrayList<>();

    /** chunkId 到人工相关度（1/2/3）的映射。 */
    private Map<String, Integer> relevantChunks = new LinkedHashMap<>();

    private boolean answerable = true;
    private List<String> tags = new ArrayList<>();
    private String split = "dev";
    private String notes;

    public List<String> getRelevantDocIds() {
        if (relevantChunks != null && !relevantChunks.isEmpty()) {
            return new ArrayList<>(relevantChunks.keySet());
        }
        return relevantDocIds == null ? List.of() : relevantDocIds;
    }
}
