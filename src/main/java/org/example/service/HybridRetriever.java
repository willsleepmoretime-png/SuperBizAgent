package org.example.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dense 与 BM25 独立召回后使用 Reciprocal Rank Fusion 融合。 */
@Service
public class HybridRetriever {

    private final DenseRetriever denseRetriever;
    private final SparseRetriever sparseRetriever;

    public HybridRetriever(DenseRetriever denseRetriever, SparseRetriever sparseRetriever) {
        this.denseRetriever = denseRetriever;
        this.sparseRetriever = sparseRetriever;
    }

    public List<VectorSearchService.SearchResult> search(String query, int finalTopK,
                                                          int denseCandidateK, int bm25CandidateK,
                                                          int rrfK) {
        return search(query, finalTopK, denseCandidateK, bm25CandidateK, rrfK, 1.0d, 1.0d);
    }

    public List<VectorSearchService.SearchResult> search(String query, int finalTopK,
                                                          int denseCandidateK, int bm25CandidateK,
                                                          int rrfK, double denseWeight, double bm25Weight) {
        int finalK = Math.max(1, finalTopK);
        int denseK = Math.max(finalK, denseCandidateK);
        int bm25K = Math.max(finalK, bm25CandidateK);
        int fusionK = Math.max(1, rrfK);
        double finalDenseWeight = requireNonNegativeWeight("denseWeight", denseWeight);
        double finalBm25Weight = requireNonNegativeWeight("bm25Weight", bm25Weight);
        if (finalDenseWeight == 0.0d && finalBm25Weight == 0.0d) {
            throw new IllegalArgumentException("denseWeight 和 bm25Weight 不能同时为 0");
        }

        List<VectorSearchService.SearchResult> dense = denseRetriever.search(query, denseK);
        List<VectorSearchService.SearchResult> bm25 = sparseRetriever.search(query, bm25K);
        Map<String, Candidate> candidates = new LinkedHashMap<>();

        for (int i = 0; i < dense.size(); i++) {
            VectorSearchService.SearchResult item = dense.get(i);
            Candidate candidate = candidates.computeIfAbsent(item.getId(), ignored -> new Candidate(item));
            candidate.denseRank = i + 1;
            candidate.denseDistance = item.getScore();
        }
        for (int i = 0; i < bm25.size(); i++) {
            VectorSearchService.SearchResult item = bm25.get(i);
            Candidate candidate = candidates.computeIfAbsent(item.getId(), ignored -> new Candidate(item));
            candidate.bm25Rank = i + 1;
            candidate.bm25Score = item.getScore();
            if ((candidate.source.getContent() == null || candidate.source.getContent().isBlank())
                    && item.getContent() != null) {
                candidate.source = item;
            }
        }

        candidates.values().forEach(candidate -> {
            if (candidate.denseRank != null) {
                candidate.rrfScore += finalDenseWeight / (fusionK + candidate.denseRank);
            }
            if (candidate.bm25Rank != null) {
                candidate.rrfScore += finalBm25Weight / (fusionK + candidate.bm25Rank);
            }
        });

        return candidates.values().stream()
                .sorted(Comparator.comparingDouble((Candidate item) -> item.rrfScore).reversed()
                        .thenComparingInt(Candidate::bestRank)
                        .thenComparing(item -> item.source.getId()))
                .limit(finalK)
                .map(this::toSearchResult)
                .toList();
    }

    private double requireNonNegativeWeight(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0d) {
            throw new IllegalArgumentException(name + " 必须是有限非负数");
        }
        return value;
    }

    private VectorSearchService.SearchResult toSearchResult(Candidate candidate) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(candidate.source.getId());
        result.setContent(candidate.source.getContent());
        result.setMetadata(candidate.source.getMetadata());
        result.setScore((float) candidate.rrfScore);
        result.setDenseRank(candidate.denseRank);
        result.setDenseDistance(candidate.denseDistance);
        result.setBm25Rank(candidate.bm25Rank);
        result.setBm25Score(candidate.bm25Score);
        result.setRrfScore(candidate.rrfScore);
        return result;
    }

    private static final class Candidate {
        private VectorSearchService.SearchResult source;
        private Integer denseRank;
        private Float denseDistance;
        private Integer bm25Rank;
        private Float bm25Score;
        private double rrfScore;

        private Candidate(VectorSearchService.SearchResult source) {
            this.source = source;
        }

        private int bestRank() {
            int dense = denseRank == null ? Integer.MAX_VALUE : denseRank;
            int bm25 = bm25Rank == null ? Integer.MAX_VALUE : bm25Rank;
            return Math.min(dense, bm25);
        }
    }
}
