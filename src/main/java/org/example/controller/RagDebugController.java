package org.example.controller;

import org.example.config.RagProperties;
import org.example.service.VectorSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG_OPTIMIZATION: RAG 调试接口，用于直接查看“实体化”的检索结果。
 */
@RestController
@RequestMapping("/api/rag/debug")
public class RagDebugController {

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private RagProperties ragProperties;

    @GetMapping("/search")
    public ResponseEntity<RagDebugSearchResponse> search(
            @RequestParam String query,
            @RequestParam(required = false) Integer topK) {
        int finalTopK = topK == null || topK <= 0 ? ragProperties.getTopK() : topK;
        List<VectorSearchService.SearchResult> results =
                vectorSearchService.searchSimilarDocuments(query, finalTopK);

        List<RagDebugSearchItem> items = results.stream()
                .map(RagDebugSearchItem::from)
                .toList();
        return ResponseEntity.ok(new RagDebugSearchResponse(query, finalTopK, items.size(), items));
    }

    @GetMapping("/compare")
    public ResponseEntity<RagDebugCompareResponse> compare(
            @RequestParam String query,
            @RequestParam(required = false) Integer topK) {
        int finalTopK = topK == null || topK <= 0 ? ragProperties.getTopK() : topK;

        // RAG_OPTIMIZATION: baseline=优化前单路向量检索，optimized=当前 query expansion + rerank 检索。
        List<VectorSearchService.SearchResult> baselineResults =
                vectorSearchService.searchBaselineSimilarDocuments(query, finalTopK);
        List<VectorSearchService.SearchResult> optimizedResults =
                vectorSearchService.searchSimilarDocuments(query, finalTopK);

        List<RagDebugSearchItem> baselineItems = baselineResults.stream()
                .map(RagDebugSearchItem::from)
                .toList();
        List<RagDebugSearchItem> optimizedItems = optimizedResults.stream()
                .map(RagDebugSearchItem::from)
                .toList();

        return ResponseEntity.ok(new RagDebugCompareResponse(
                query,
                finalTopK,
                buildCompareMetrics(baselineResults, optimizedResults),
                baselineItems,
                optimizedItems
        ));
    }

    private RagDebugCompareMetrics buildCompareMetrics(
            List<VectorSearchService.SearchResult> baselineResults,
            List<VectorSearchService.SearchResult> optimizedResults) {
        Set<String> baselineIds = toIds(baselineResults);
        Set<String> optimizedIds = toIds(optimizedResults);
        Set<String> intersection = new LinkedHashSet<>(baselineIds);
        intersection.retainAll(optimizedIds);

        Set<String> newlyRecalled = new LinkedHashSet<>(optimizedIds);
        newlyRecalled.removeAll(baselineIds);

        Set<String> dropped = new LinkedHashSet<>(baselineIds);
        dropped.removeAll(optimizedIds);

        float baselineAvgDistance = averageDistance(baselineResults);
        float optimizedAvgDistance = averageDistance(optimizedResults);
        float optimizedAvgRerankScore = averageRerankScore(optimizedResults);

        return new RagDebugCompareMetrics(
                baselineResults.size(),
                optimizedResults.size(),
                intersection.size(),
                newlyRecalled.size(),
                dropped.size(),
                baselineAvgDistance,
                optimizedAvgDistance,
                optimizedAvgRerankScore,
                newlyRecalled,
                dropped
        );
    }

    private Set<String> toIds(List<VectorSearchService.SearchResult> results) {
        Set<String> ids = new LinkedHashSet<>();
        for (VectorSearchService.SearchResult result : results) {
            ids.add(result.getId());
        }
        return ids;
    }

    private float averageDistance(List<VectorSearchService.SearchResult> results) {
        if (results.isEmpty()) {
            return 0.0f;
        }
        float total = 0.0f;
        for (VectorSearchService.SearchResult result : results) {
            total += result.getScore();
        }
        return total / results.size();
    }

    private float averageRerankScore(List<VectorSearchService.SearchResult> results) {
        if (results.isEmpty()) {
            return 0.0f;
        }
        float total = 0.0f;
        for (VectorSearchService.SearchResult result : results) {
            total += result.getRerankScore();
        }
        return total / results.size();
    }

    public record RagDebugSearchResponse(
            String query,
            int topK,
            int resultCount,
            List<RagDebugSearchItem> results
    ) {
    }

    public record RagDebugCompareResponse(
            String query,
            int topK,
            RagDebugCompareMetrics metrics,
            List<RagDebugSearchItem> baselineResults,
            List<RagDebugSearchItem> optimizedResults
    ) {
    }

    public record RagDebugCompareMetrics(
            int baselineCount,
            int optimizedCount,
            int overlapCount,
            int newlyRecalledCount,
            int droppedBaselineCount,
            float baselineAvgDistance,
            float optimizedAvgDistance,
            float optimizedAvgRerankScore,
            Set<String> newlyRecalledIds,
            Set<String> droppedBaselineIds
    ) {
    }

    public record RagDebugSearchItem(
            String id,
            float distance,
            float rerankScore,
            int contentHitCount,
            int metadataHitCount,
            String confidenceLevel,
            String qualityReason,
            String matchedQuery,
            String metadata,
            String content
    ) {
        private static RagDebugSearchItem from(VectorSearchService.SearchResult result) {
            return new RagDebugSearchItem(
                    result.getId(),
                    result.getScore(),
                    result.getRerankScore(),
                    result.getContentHitCount(),
                    result.getMetadataHitCount(),
                    result.getConfidenceLevel(),
                    result.getQualityReason(),
                    result.getMatchedQuery(),
                    result.getMetadata(),
                    result.getContent()
            );
        }
    }
}
