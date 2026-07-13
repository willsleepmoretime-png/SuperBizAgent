package org.example.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrieverTest {

    @Test
    void deduplicatesByChunkIdAndRewardsCandidatesFoundByBothRetrievers() {
        DenseRetriever dense = (query, topK) -> List.of(result("dense-only", 0.1f), result("shared", 0.2f));
        SparseRetriever sparse = new SparseRetriever() {
            @Override
            public List<VectorSearchService.SearchResult> search(String query, int topK) {
                return List.of(result("shared", 12.0f), result("bm25-only", 10.0f));
            }
            @Override public int rebuild() { return 3; }
        };

        List<VectorSearchService.SearchResult> results =
                new HybridRetriever(dense, sparse).search("query", 3, 20, 20, 60);

        assertThat(results).extracting(VectorSearchService.SearchResult::getId)
                .containsExactly("shared", "dense-only", "bm25-only");
        assertThat(results).extracting(VectorSearchService.SearchResult::getId).doesNotHaveDuplicates();
        assertThat(results.get(0).getDenseRank()).isEqualTo(2);
        assertThat(results.get(0).getBm25Rank()).isEqualTo(1);
        assertThat(results.get(0).getRrfScore())
                .isEqualTo(1.0d / 62.0d + 1.0d / 61.0d);
    }

    @Test
    void usesRanksInsteadOfMixingDenseDistanceAndBm25Score() {
        DenseRetriever dense = (query, topK) -> List.of(result("a", 999f), result("b", 0.001f));
        SparseRetriever sparse = new SparseRetriever() {
            @Override public List<VectorSearchService.SearchResult> search(String query, int topK) { return List.of(); }
            @Override public int rebuild() { return 0; }
        };

        assertThat(new HybridRetriever(dense, sparse).search("query", 2, 2, 2, 60))
                .extracting(VectorSearchService.SearchResult::getId)
                .containsExactly("a", "b");
    }

    @Test
    void appliesRetrieverWeightsToRankContributions() {
        DenseRetriever dense = (query, topK) -> List.of(result("dense", 0.1f), result("shared", 0.2f));
        SparseRetriever sparse = new SparseRetriever() {
            @Override public List<VectorSearchService.SearchResult> search(String query, int topK) {
                return List.of(result("shared", 10f));
            }
            @Override public int rebuild() { return 2; }
        };

        List<VectorSearchService.SearchResult> results = new HybridRetriever(dense, sparse)
                .search("query", 2, 2, 1, 30, 1.0d, 0.3d);

        assertThat(results.get(0).getRrfScore())
                .isEqualTo(1.0d / 32.0d + 0.3d / 31.0d);
        assertThat(results).extracting(VectorSearchService.SearchResult::getId)
                .containsExactly("shared", "dense");
    }

    private static VectorSearchService.SearchResult result(String id, float score) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(id);
        result.setContent(id);
        result.setScore(score);
        return result;
    }
}
