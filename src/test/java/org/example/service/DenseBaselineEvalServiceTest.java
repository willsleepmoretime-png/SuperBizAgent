package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DenseBaselineEvalServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void separatesAnswerableMetricsFromNoAnswerCases() {
        VectorSearchService.SearchResult hit = new VectorSearchService.SearchResult();
        hit.setId("cpu_high_usage.md::CPU使用率过高告警处理方案/告警名称::aea28ea91212");
        hit.setScore(0.1f);
        DenseRetriever retriever = (query, topK) ->
                "HighCPUUsage 告警在什么条件下触发？".equals(query) ? List.of(hit) : List.of();
        SparseRetriever sparseRetriever = new SparseRetriever() {
            @Override public List<VectorSearchService.SearchResult> search(String query, int topK) { return List.of(); }
            @Override public int rebuild() { return 0; }
        };

        DenseBaselineEvalService.DenseBaselineReport report =
                new DenseBaselineEvalService(retriever, sparseRetriever,
                        new HybridRetriever(retriever, sparseRetriever),
                        new HybridRerankRetriever(new HybridRetriever(retriever, sparseRetriever),
                                (query, candidates, topK) -> candidates.stream().limit(topK).toList()),
                        new ObjectMapper(),
                        new RagEvalRunStore(new ObjectMapper(), tempDir.toString())).run(5, "all");

        assertThat(report.datasetVersion()).isEqualTo("rag-eval-v1");
        assertThat(report.answerableCaseCount()).isEqualTo(50);
        assertThat(report.noAnswerCaseCount()).isEqualTo(10);
        assertThat(report.metrics().hitAt1()).isEqualTo(1.0d / 50.0d);
        assertThat(report.metrics().candidateRecall()).isEqualTo(report.metrics().recallAtK());
        assertThat(report.noAnswerSummary().caseCount()).isEqualTo(10);
    }
}
