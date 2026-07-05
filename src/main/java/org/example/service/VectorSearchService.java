package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.config.RagProperties;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 向量搜索服务
 * 负责从 Milvus 中搜索相似向量
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private RerankService rerankService;

    /**
     * 搜索相似文档
     * 
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        long searchStartNs = System.nanoTime();
        try {
            int finalTopK = topK > 0 ? topK : ragProperties.getTopK();
            int candidateK = Math.max(finalTopK, ragProperties.getCandidateK());
            logger.info("开始搜索相似文档, 查询: {}, topK: {}, candidateK: {}", query, finalTopK, candidateK);

            // RAG_OPTIMIZATION: 使用轻量查询扩展做多路召回，减少用户问题表述和文档写法不一致导致的漏召回。
            List<String> queryVariants = buildQueryVariants(query);
            Map<String, SearchResult> deduplicatedResults = new LinkedHashMap<>();
            int rawCandidateCount = 0;

            for (String queryVariant : queryVariants) {
                List<SearchResult> variantResults = searchSingleQuery(queryVariant, candidateK);
                rawCandidateCount += variantResults.size();
                for (SearchResult result : variantResults) {
                    SearchResult existing = deduplicatedResults.get(result.getId());
                    if (existing == null || result.getScore() < existing.getScore()) {
                        result.setMatchedQuery(queryVariant);
                        deduplicatedResults.put(result.getId(), result);
                    }
                }
            }

            List<SearchResult> candidates = deduplicatedResults.values().stream()
                    .filter(this::matchesDistanceThreshold)
                    .toList();
            List<SearchResult> rerankedResults = rerankWithFallback(query, candidates, finalTopK);

            logger.info("搜索完成, queryVariants={}, 原始候选 {} 个, 去重后 {} 个, 返回 {} 个",
                    queryVariants.size(), rawCandidateCount, deduplicatedResults.size(), rerankedResults.size());
            logger.info("PERF vector_search.total durationMs={} candidateCount={} filteredCount={} returnedCount={}",
                    elapsedMs(searchStartNs), rawCandidateCount, deduplicatedResults.size(), rerankedResults.size());
            return rerankedResults;

        } catch (Exception e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    private List<SearchResult> rerankWithFallback(String query, List<SearchResult> candidates, int finalTopK) {
        if (ragProperties.getRerank().isEnabled()) {
            try {
                return rerankService.rerank(query, candidates, finalTopK);
            } catch (Exception e) {
                logger.warn("DashScope 模型重排失败，降级为本地轻量规则重排: {}", e.getMessage());
            }
        }

        return lightweightRerank(query, candidates, finalTopK);
    }

    private List<SearchResult> lightweightRerank(String query, List<SearchResult> candidates, int finalTopK) {
        // RAG_OPTIMIZATION: 候选结果用问题关键词、标题、文件名做轻量重排，作为模型重排失败时的兜底。
        return candidates.stream()
                .map(result -> applyRerankScore(query, result))
                // RAG_OPTIMIZATION: 低置信过滤，避免把明显不相关的 chunk 塞进 Prompt。
                .filter(this::matchesQualityThreshold)
                .sorted(Comparator.comparing(SearchResult::getRerankScore).reversed())
                .limit(finalTopK)
                .toList();
    }

    /**
     * RAG_OPTIMIZATION: 调试用基线检索，保留优化前的“单 query + Milvus topK”行为，
     * 用于和 query expansion + rerank 后的结果做可视化对比。
     */
    public List<SearchResult> searchBaselineSimilarDocuments(String query, int topK) {
        int finalTopK = topK > 0 ? topK : ragProperties.getTopK();
        List<SearchResult> baselineResults = searchSingleQuery(query, finalTopK);
        return baselineResults.stream()
                .filter(this::matchesDistanceThreshold)
                .limit(finalTopK)
                .toList();
    }

    private List<SearchResult> searchSingleQuery(String query, int candidateK) {
        long singleSearchStartNs = System.nanoTime();

        // 1. 将查询文本向量化
        long embeddingStartNs = System.nanoTime();
        List<Float> queryVector = embeddingService.generateQueryVector(query);
        logger.info("PERF vector_search.embedding durationMs={} vectorDim={} queryVariant={}",
                elapsedMs(embeddingStartNs), queryVector.size(), query);
        logger.debug("查询向量生成成功, 维度: {}", queryVector.size());

        // 2. 构建搜索参数
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withVectorFieldName("vector")
                .withVectors(Collections.singletonList(queryVector))
                .withTopK(candidateK)
                .withMetricType(io.milvus.param.MetricType.L2)
                .withOutFields(List.of("id", "content", "metadata"))
                .withParams("{\"nprobe\":16}")
                .build();

        // 3. 执行搜索
        long milvusStartNs = System.nanoTime();
        R<SearchResults> searchResponse = milvusClient.search(searchParam);
        logger.info("PERF vector_search.milvus durationMs={} status={} queryVariant={}",
                elapsedMs(milvusStartNs), searchResponse.getStatus(), query);

        if (searchResponse.getStatus() != 0) {
            throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
        List<SearchResult> results = new ArrayList<>();

        for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
            SearchResult result = new SearchResult();
            result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
            result.setContent((String) wrapper.getFieldData("content", 0).get(i));
            result.setScore(wrapper.getIDScore(0).get(i).getScore());

            Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
            if (metadataObj != null) {
                result.setMetadata(metadataObj.toString());
            }

            results.add(result);
        }

        logger.info("PERF vector_search.single_total durationMs={} returnedCount={} queryVariant={}",
                elapsedMs(singleSearchStartNs), results.size(), query);
        return results;
    }

    /**
     * RAG_OPTIMIZATION: 基于常见运维问法做轻量 query expansion。
     * 后续如果要更强效果，可以把这里替换成 LLM query rewrite 或专门的 query-rewrite prompt。
     */
    private List<String> buildQueryVariants(String query) {
        Set<String> variants = new LinkedHashSet<>();
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            return List.of(normalizedQuery);
        }

        variants.add(normalizedQuery);
        String lowerQuery = normalizedQuery.toLowerCase(Locale.ROOT);

        if (containsAny(lowerQuery, "cpu", "处理器")) {
            variants.add(normalizedQuery + " CPU 使用率过高 high cpu usage 进程排查");
        }
        if (containsAny(lowerQuery, "内存", "memory", "oom")) {
            variants.add(normalizedQuery + " 内存使用率过高 memory high usage OOM 排查");
        }
        if (containsAny(lowerQuery, "磁盘", "disk", "容量")) {
            variants.add(normalizedQuery + " 磁盘空间不足 disk high usage 容量排查");
        }
        if (containsAny(lowerQuery, "慢", "延迟", "响应", "timeout", "超时")) {
            variants.add(normalizedQuery + " 响应慢 slow response timeout 延迟排查");
        }
        if (containsAny(lowerQuery, "不可用", "故障", "宕机", "服务", "unavailable")) {
            variants.add(normalizedQuery + " 服务不可用 service unavailable 故障恢复");
        }

        return variants.stream().limit(4).toList();
    }

    private SearchResult applyRerankScore(String query, SearchResult result) {
        Set<String> queryTerms = extractQueryTerms(query);
        Set<String> domainTerms = extractDomainTerms(query);
        queryTerms.addAll(domainTerms);
        String content = safeLower(result.getContent());
        String metadata = safeLower(result.getMetadata());

        int contentHits = 0;
        int metadataHits = 0;
        for (String term : queryTerms) {
            if (content.contains(term)) {
                contentHits++;
            }
            if (metadata.contains(term)) {
                metadataHits++;
            }
        }

        // L2 距离越小越好，因此用 1/(1+score) 转成相似度方向，再叠加可解释的关键词命中。
        float vectorScore = 1.0f / (1.0f + Math.max(0.0f, result.getScore()));
        float rerankScore = vectorScore + contentHits * 0.08f + metadataHits * 0.15f;
        result.setRerankScore(rerankScore);
        result.setContentHitCount(contentHits);
        result.setMetadataHitCount(metadataHits);
        markQuality(query, result);
        return result;
    }

    private Set<String> extractQueryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return terms;
        }

        String[] parts = query.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-zA-Z0-9]+", " ")
                .trim()
                .split("\\s+");
        for (String part : parts) {
            if (part.length() >= 2) {
                terms.add(part);
            }
        }
        return terms;
    }

    private Set<String> extractDomainTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);

        if (containsAny(text, "cpu", "处理器")) {
            terms.add("cpu");
            terms.add("使用率");
            terms.add("进程");
            terms.add("负载");
        }
        if (containsAny(text, "内存", "memory", "oom")) {
            terms.add("内存");
            terms.add("memory");
            terms.add("oom");
            terms.add("泄漏");
        }
        if (containsAny(text, "磁盘", "disk", "容量")) {
            terms.add("磁盘");
            terms.add("disk");
            terms.add("容量");
            terms.add("空间");
        }
        if (containsAny(text, "告警", "监控", "prometheus", "alert")) {
            terms.add("告警");
            terms.add("监控");
            terms.add("prometheus");
            terms.add("alert");
        }
        if (containsAny(text, "日志", "error", "exception", "异常")) {
            terms.add("日志");
            terms.add("error");
            terms.add("exception");
            terms.add("异常");
        }
        return terms;
    }

    private void markQuality(String query, SearchResult result) {
        boolean operationsQuery = isOperationsQuery(query);
        boolean hasKeywordEvidence = result.getContentHitCount() + result.getMetadataHitCount() > 0;

        if (operationsQuery && !hasKeywordEvidence && result.getScore() > 1.0f) {
            result.setConfidenceLevel("LOW");
            result.setQualityReason("运维类问题无关键词命中且向量距离偏高，判定为低置信结果");
            return;
        }

        if (!hasKeywordEvidence) {
            result.setConfidenceLevel("MEDIUM");
            result.setQualityReason("仅依赖向量相似度，缺少关键词或元数据命中");
            return;
        }

        result.setConfidenceLevel("HIGH");
        result.setQualityReason("存在内容或元数据关键词命中");
    }

    private boolean matchesQualityThreshold(SearchResult result) {
        return !"LOW".equals(result.getConfidenceLevel());
    }

    private boolean isOperationsQuery(String query) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return containsAny(text,
                "cpu", "处理器", "内存", "memory", "oom", "磁盘", "disk",
                "告警", "监控", "prometheus", "alert", "日志", "error", "exception",
                "故障", "排查", "不可用", "响应慢", "超时");
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean matchesDistanceThreshold(SearchResult result) {
        float maxDistance = ragProperties.getMaxDistance();
        return maxDistance <= 0 || result.getScore() <= maxDistance;
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;
        // RAG_OPTIMIZATION: 记录重排信息，方便日志、前端或 Prompt 里解释检索依据。
        private float rerankScore;
        private int contentHitCount;
        private int metadataHitCount;
        private String matchedQuery;
        private String confidenceLevel;
        private String qualityReason;

    }
}
