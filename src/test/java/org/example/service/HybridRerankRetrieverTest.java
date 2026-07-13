package org.example.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRerankRetrieverTest {

    @Test
    void returnsModelOrderAndKeepsRrfEvidence() {
        HybridRetriever hybrid = hybridRetriever();
        RerankService reranker = (query, candidates, topK) -> {
            List<VectorSearchService.SearchResult> reversed = new ArrayList<>(candidates);
            Collections.reverse(reversed);
            reversed.forEach(item -> item.setRerankScore(0.9f));
            return reversed.stream().limit(topK).toList();
        };

        List<VectorSearchService.SearchResult> results = new HybridRerankRetriever(hybrid, reranker)
                .search("q", 2, 3, 3, 3, 60, 1.0d, 0.5d);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(item -> !Boolean.TRUE.equals(item.getRerankFallback()));
        assertThat(results).extracting(VectorSearchService.SearchResult::getRerankRank)
                .containsExactly(1, 2);
        assertThat(results).allMatch(item -> item.getRrfScore() != null);
    }

    @Test
    void fallsBackToRrfOrderWhenModelFails() {
        HybridRetriever hybrid = hybridRetriever();
        RerankService failing = (query, candidates, topK) -> {
            throw new IllegalStateException("model unavailable");
        };

        List<VectorSearchService.SearchResult> expected = hybrid.search("q", 2, 3, 3, 60, 1.0d, 0.5d);
        List<VectorSearchService.SearchResult> actual = new HybridRerankRetriever(hybrid, failing)
                .search("q", 2, 3, 3, 3, 60, 1.0d, 0.5d);

        assertThat(actual).extracting(VectorSearchService.SearchResult::getId)
                .containsExactlyElementsOf(expected.stream().map(VectorSearchService.SearchResult::getId).toList());
        assertThat(actual).allMatch(item -> Boolean.TRUE.equals(item.getRerankFallback()));
    }

    @Test
    void protectsOneDualTop3CandidateWhenRerankerDropsIt() {
        HybridRetriever hybrid = hybridRetriever();
        RerankService dropsFirst = (query, candidates, topK) -> candidates.stream()
                .skip(1).limit(topK).peek(item -> item.setRerankScore(0.8f)).toList();

        HybridRerankRetriever.HybridRerankResult result = new HybridRerankRetriever(hybrid, dropsFirst)
                .searchDetailed("q", 2, 3, 3, 3, 60, 1.0d, 0.5d, true);

        assertThat(result.results()).hasSize(2);
        assertThat(result.results()).anyMatch(item -> Boolean.TRUE.equals(item.getRerankProtected()));
        assertThat(result.results()).extracting(VectorSearchService.SearchResult::getId)
                .contains(result.candidates().get(0).getId());
    }

    private HybridRetriever hybridRetriever() {
        DenseRetriever dense = (query, topK) -> List.of(result("a", 0.1f), result("b", 0.2f), result("c", 0.3f));
        SparseRetriever sparse = new SparseRetriever() {
            @Override public List<VectorSearchService.SearchResult> search(String query, int topK) {
                return List.of(result("b", 10f), result("c", 8f), result("a", 6f));
            }
            @Override public int rebuild() { return 3; }
        };
        return new HybridRetriever(dense, sparse);
    }

    private VectorSearchService.SearchResult result(String id, float score) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(id);
        result.setContent(id);
        result.setScore(score);
        return result;
    }
}
