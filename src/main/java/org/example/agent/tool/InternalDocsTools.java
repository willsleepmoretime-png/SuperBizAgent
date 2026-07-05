package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.example.config.RagProperties;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    private static final ObjectMapper STATIC_OBJECT_MAPPER = new ObjectMapper();
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";
    
    private final VectorSearchService vectorSearchService;

    private final RagProperties ragProperties;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 构造函数注入依赖
     * Spring 会自动注入 VectorSearchService
     */
    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService, RagProperties ragProperties) {
        this.vectorSearchService = vectorSearchService;
        this.ragProperties = ragProperties;
    }
    
    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") 
            String query) {
        try {
            // 使用向量搜索服务检索相关文档
            List<VectorSearchService.SearchResult> searchResults = 
                    vectorSearchService.searchSimilarDocuments(query, ragProperties.getTopK());
            
            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }
            
            SearchToolResponse response = SearchToolResponse.from(query, searchResults);
            return objectMapper.writeValueAsString(response);
            
        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}", 
                    e.getMessage());
        }
    }

    private record SearchToolResponse(
            String status,
            String query,
            int resultCount,
            List<SearchToolItem> results
    ) {
        private static SearchToolResponse from(String query, List<VectorSearchService.SearchResult> searchResults) {
            List<SearchToolItem> items = searchResults.stream()
                    .map(SearchToolItem::from)
                    .toList();
            return new SearchToolResponse("success", query, items.size(), items);
        }
    }

    private record SearchToolItem(
            String id,
            float distance,
            String source,
            String fileName,
            Integer chunkIndex,
            Integer totalChunks,
            String title,
            // RAG_OPTIMIZATION: 把检索实体的重排信息返回给 Agent，方便 Agent 判断引用可信度。
            Float rerankScore,
            Integer contentHitCount,
            Integer metadataHitCount,
            String matchedQuery,
            String content
    ) {
        private static SearchToolItem from(VectorSearchService.SearchResult result) {
            Map<String, Object> metadata = parseMetadata(result.getMetadata());
            return new SearchToolItem(
                    result.getId(),
                    result.getScore(),
                    asString(metadata.get("_source")),
                    asString(metadata.get("_file_name")),
                    asInteger(metadata.get("chunkIndex")),
                    asInteger(metadata.get("totalChunks")),
                    asString(metadata.get("title")),
                    result.getRerankScore(),
                    result.getContentHitCount(),
                    result.getMetadataHitCount(),
                    result.getMatchedQuery(),
                    result.getContent()
            );
        }
    }

    private static Map<String, Object> parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Map.of();
        }

        try {
            return STATIC_OBJECT_MAPPER.readValue(metadata, new TypeReference<>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
