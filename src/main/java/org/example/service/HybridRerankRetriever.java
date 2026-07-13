package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Weighted RRF 候选经过 gte-rerank-v2 精排；模型失败时原样回退 RRF。 */
@Service
public class HybridRerankRetriever {

    private static final Logger logger = LoggerFactory.getLogger(HybridRerankRetriever.class);

    private final HybridRetriever hybridRetriever;
    private final RerankService rerankService;

    public HybridRerankRetriever(HybridRetriever hybridRetriever, RerankService rerankService) {
        this.hybridRetriever = hybridRetriever;
        this.rerankService = rerankService;
    }

    public List<VectorSearchService.SearchResult> search(
            String query, int finalTopK, int rerankCandidateK,
            int denseCandidateK, int bm25CandidateK, int rrfK,
            double denseWeight, double bm25Weight) {
        return searchDetailed(query, finalTopK, rerankCandidateK, denseCandidateK,
                bm25CandidateK, rrfK, denseWeight, bm25Weight, false).results();
    }

    public HybridRerankResult searchDetailed(
            String query, int finalTopK, int rerankCandidateK,
            int denseCandidateK, int bm25CandidateK, int rrfK,
            double denseWeight, double bm25Weight, boolean protectDualTop3) {
        int finalK = Math.max(1, finalTopK);
        int candidateK = Math.max(finalK, rerankCandidateK);
        List<VectorSearchService.SearchResult> rrfCandidates = hybridRetriever.search(
                query, candidateK, denseCandidateK, bm25CandidateK, rrfK, denseWeight, bm25Weight);
        try {
            List<VectorSearchService.SearchResult> reranked = rerankService.rerank(query, rrfCandidates, finalK);
            for (int i = 0; i < reranked.size(); i++) {
                VectorSearchService.SearchResult item = reranked.get(i);
                item.setRerankRank(i + 1);
                item.setRerankFallback(false);
                item.setRerankProtected(false);
                item.setScore(item.getRerankScore());
            }
            List<VectorSearchService.SearchResult> protectedResults = protectDualTop3
                    ? applyDualTop3Protection(reranked, rrfCandidates, finalK)
                    : reranked;
            return new HybridRerankResult(protectedResults, rrfCandidates, false);
        } catch (Exception e) {
            logger.warn("gte-rerank-v2 调用失败，回退 Weighted RRF: {}", e.getMessage());
            List<VectorSearchService.SearchResult> fallback = rrfCandidates.stream().limit(finalK).toList();
            for (int i = 0; i < fallback.size(); i++) {
                fallback.get(i).setRerankRank(null);
                fallback.get(i).setRerankFallback(true);
                fallback.get(i).setRerankProtected(false);
            }
            return new HybridRerankResult(fallback, rrfCandidates, true);
        }
    }

    private List<VectorSearchService.SearchResult> applyDualTop3Protection(
            List<VectorSearchService.SearchResult> reranked,
            List<VectorSearchService.SearchResult> rrfCandidates,
            int finalTopK) {
        Set<String> selectedIds = new HashSet<>();
        reranked.forEach(item -> selectedIds.add(item.getId()));
        VectorSearchService.SearchResult protectedCandidate = rrfCandidates.stream()
                .filter(item -> item.getDenseRank() != null && item.getDenseRank() <= 3)
                .filter(item -> item.getBm25Rank() != null && item.getBm25Rank() <= 3)
                .filter(item -> !selectedIds.contains(item.getId()))
                .findFirst()
                .orElse(null);
        if (protectedCandidate == null) {
            return reranked;
        }

        List<VectorSearchService.SearchResult> result = new ArrayList<>(reranked);
        protectedCandidate.setRerankProtected(true);
        protectedCandidate.setRerankFallback(false);
        protectedCandidate.setRerankRank(null);
        if (result.size() >= finalTopK) {
            result.remove(result.size() - 1);
        }
        result.add(protectedCandidate);
        return List.copyOf(result);
    }

    public record HybridRerankResult(List<VectorSearchService.SearchResult> results,
                                     List<VectorSearchService.SearchResult> candidates,
                                     boolean fallback) {}
}
