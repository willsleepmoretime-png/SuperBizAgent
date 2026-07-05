package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel;
import com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankOptions;
import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import org.example.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 DashScope 独立 rerank 模型的候选文档精排。
 */
@Service
public class DashScopeRerankService implements RerankService {

    private static final Logger logger = LoggerFactory.getLogger(DashScopeRerankService.class);
    private static final String RESULT_ID_METADATA_KEY = "searchResultId";

    private final RagProperties ragProperties;
    private final String apiKey;
    private volatile DashScopeRerankModel rerankModel;

    public DashScopeRerankService(RagProperties ragProperties,
                                  @Value("${dashscope.api.key}") String apiKey) {
        this.ragProperties = ragProperties;
        this.apiKey = apiKey;
    }

    @Override
    public List<VectorSearchService.SearchResult> rerank(
            String query,
            List<VectorSearchService.SearchResult> candidates,
            int topK
    ) {
        if (!ragProperties.getRerank().isEnabled()) {
            throw new IllegalStateException("DashScope rerank is disabled");
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        long startNs = System.nanoTime();
        List<Document> documents = new ArrayList<>(candidates.size());
        Map<String, VectorSearchService.SearchResult> resultById = new LinkedHashMap<>();

        for (VectorSearchService.SearchResult candidate : candidates) {
            String resultId = candidate.getId();
            if (resultId == null || resultId.isBlank()) {
                resultId = "candidate-" + documents.size();
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put(RESULT_ID_METADATA_KEY, resultId);
            if (candidate.getMetadata() != null) {
                metadata.put("sourceMetadata", candidate.getMetadata());
            }
            metadata.put("vectorScore", candidate.getScore());

            documents.add(new Document(resultId, candidate.getContent(), metadata));
            resultById.put(resultId, candidate);
        }

        DashScopeRerankOptions options = new DashScopeRerankOptions();
        options.setModel(ragProperties.getRerank().getModel());
        options.setTopN(topK);
        options.setReturnDocuments(ragProperties.getRerank().isReturnDocuments());

        RerankResponse response = getRerankModel().call(new RerankRequest(query, documents, options));
        List<VectorSearchService.SearchResult> reranked = new ArrayList<>();

        for (DocumentWithScore documentWithScore : response.getResults()) {
            Document document = documentWithScore.getOutput();
            String resultId = resolveResultId(document);
            VectorSearchService.SearchResult original = resultById.get(resultId);
            if (original == null) {
                continue;
            }

            Double score = documentWithScore.getScore();
            original.setRerankScore(score == null ? 0.0f : score.floatValue());
            original.setConfidenceLevel("HIGH");
            original.setQualityReason("DashScope rerank 模型语义相关性重排");
            reranked.add(original);
        }

        if (reranked.isEmpty()) {
            throw new IllegalStateException("DashScope rerank returned no matched documents");
        }

        List<VectorSearchService.SearchResult> topResults = reranked.stream()
                .sorted(Comparator.comparing(VectorSearchService.SearchResult::getRerankScore).reversed())
                .limit(topK)
                .toList();
        logger.info("PERF vector_search.model_rerank durationMs={} candidateCount={} returnedCount={} model={}",
                elapsedMs(startNs), candidates.size(), topResults.size(), ragProperties.getRerank().getModel());
        return topResults;
    }

    private DashScopeRerankModel getRerankModel() {
        DashScopeRerankModel localModel = rerankModel;
        if (localModel == null) {
            synchronized (this) {
                localModel = rerankModel;
                if (localModel == null) {
                    DashScopeApi dashScopeApi = DashScopeApi.builder()
                            .apiKey(apiKey)
                            .build();
                    localModel = new DashScopeRerankModel(dashScopeApi);
                    rerankModel = localModel;
                    logger.info("DashScope rerank model initialized, model={}", ragProperties.getRerank().getModel());
                }
            }
        }
        return localModel;
    }

    private String resolveResultId(Document document) {
        if (document == null) {
            return null;
        }
        Object metadataId = document.getMetadata().get(RESULT_ID_METADATA_KEY);
        if (metadataId != null) {
            return metadataId.toString();
        }
        return document.getId();
    }

    private static long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }
}
