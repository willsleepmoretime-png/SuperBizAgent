package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.RagEvalCase;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只执行 Query Embedding -> Milvus 的纯 Dense 离线基线评测。 */
@Service
public class DenseBaselineEvalService {

    private static final DateTimeFormatter RUN_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final DenseRetriever denseRetriever;
    private final SparseRetriever sparseRetriever;
    private final HybridRetriever hybridRetriever;
    private final RagEvalRunStore runStore;
    private final HybridRerankRetriever hybridRerankRetriever;
    private final ObjectMapper objectMapper;

    public DenseBaselineEvalService(DenseRetriever denseRetriever, SparseRetriever sparseRetriever,
                                    HybridRetriever hybridRetriever,
                                    HybridRerankRetriever hybridRerankRetriever,
                                    ObjectMapper objectMapper,
                                    RagEvalRunStore runStore) {
        this.denseRetriever = denseRetriever;
        this.sparseRetriever = sparseRetriever;
        this.hybridRetriever = hybridRetriever;
        this.hybridRerankRetriever = hybridRerankRetriever;
        this.objectMapper = objectMapper;
        this.runStore = runStore;
    }

    public DenseBaselineReport run(int maxK, String split) {
        return runWith((query, topK) -> SearchExecution.simple(denseRetriever.search(query, topK)), "DENSE", "L2_DISTANCE",
                Map.of("finalTopK", Math.max(1, maxK)), maxK, split);
    }

    public DenseBaselineReport runBm25(int maxK, String split) {
        return runWith((query, topK) -> SearchExecution.simple(sparseRetriever.search(query, topK)), "BM25", "BM25_SCORE",
                Map.of("k1", 1.2d, "b", 0.75d, "finalTopK", Math.max(1, maxK)), maxK, split);
    }

    public DenseBaselineReport runHybrid(int maxK, String split, int denseCandidateK,
                                         int bm25CandidateK, int rrfK) {
        return runHybrid(maxK, split, denseCandidateK, bm25CandidateK, rrfK, 1.0d, 1.0d);
    }

    public DenseBaselineReport runHybridRerank(int maxK, String split, int rerankCandidateK,
                                               int denseCandidateK, int bm25CandidateK, int rrfK,
                                               double denseWeight, double bm25Weight) {
        return runHybridRerank(maxK, split, rerankCandidateK, denseCandidateK, bm25CandidateK,
                rrfK, denseWeight, bm25Weight, false);
    }

    public DenseBaselineReport runHybridRerank(int maxK, String split, int rerankCandidateK,
                                               int denseCandidateK, int bm25CandidateK, int rrfK,
                                               double denseWeight, double bm25Weight,
                                               boolean protectDualTop3) {
        RankedSearch search = (query, topK) -> {
            HybridRerankRetriever.HybridRerankResult result = hybridRerankRetriever.searchDetailed(
                    query, topK, rerankCandidateK, denseCandidateK, bm25CandidateK,
                    rrfK, denseWeight, bm25Weight, protectDualTop3);
            return new SearchExecution(result.results(), result.candidates(), result.fallback());
        };
        return runWith(search, "HYBRID_RERANK", "RERANK_SCORE",
                Map.of("denseCandidateK", denseCandidateK, "bm25CandidateK", bm25CandidateK,
                        "rrfK", rrfK, "denseWeight", denseWeight, "bm25Weight", bm25Weight,
                        "rerankCandidateK", rerankCandidateK, "rerankModel", "gte-rerank-v2",
                        "protectDualTop3", protectDualTop3, "finalTopK", Math.max(1, maxK)), maxK, split);
    }

    public DenseBaselineReport runHybrid(int maxK, String split, int denseCandidateK,
                                         int bm25CandidateK, int rrfK,
                                         double denseWeight, double bm25Weight) {
        RankedSearch search = (query, topK) -> SearchExecution.simple(hybridRetriever.search(
                query, topK, denseCandidateK, bm25CandidateK, rrfK, denseWeight, bm25Weight));
        return runWith(search, "HYBRID_RRF", "RRF_SCORE",
                Map.of("denseCandidateK", denseCandidateK, "bm25CandidateK", bm25CandidateK,
                        "rrfK", rrfK, "denseWeight", denseWeight, "bm25Weight", bm25Weight,
                        "finalTopK", Math.max(1, maxK)), maxK, split);
    }

    private DenseBaselineReport runWith(RankedSearch search, String mode, String scoreType,
                                        Map<String, Object> retrievalConfig, int maxK, String split) {
        int finalMaxK = Math.max(1, maxK);
        Dataset dataset = loadDataset();
        String normalizedSplit = split == null || split.isBlank() ? "all" : split.trim();
        List<CaseResult> results = new ArrayList<>();

        for (RagEvalCase evalCase : dataset.cases()) {
            if (!"all".equalsIgnoreCase(normalizedSplit)
                    && !normalizedSplit.equalsIgnoreCase(evalCase.getSplit())) {
                continue;
            }
            results.add(evaluateCase(evalCase, finalMaxK, search, scoreType));
        }

        List<CaseResult> answerable = results.stream().filter(CaseResult::answerable).toList();
        List<CaseResult> noAnswer = results.stream().filter(result -> !result.answerable()).toList();
        Instant now = Instant.now();
        DenseBaselineReport report = new DenseBaselineReport(
                mode.toLowerCase() + "-" + RUN_ID_TIME.format(now),
                now.toString(),
                dataset.datasetVersion(),
                dataset.corpusVersion(),
                dataset.chunkerVersion(),
                mode,
                scoreType,
                retrievalConfig,
                normalizedSplit,
                finalMaxK,
                results.size(),
                answerable.size(),
                noAnswer.size(),
                summarize(answerable, finalMaxK),
                summarizeNoAnswer(noAnswer, scoreType),
                results
        );
        runStore.save(report);
        return report;
    }

    private CaseResult evaluateCase(RagEvalCase evalCase, int maxK, RankedSearch search, String scoreType) {
        long start = System.nanoTime();
        SearchExecution execution = search.search(evalCase.getQuery(), maxK);
        List<VectorSearchService.SearchResult> retrieved = execution.results();
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        Map<String, Integer> relevance = evalCase.getRelevantChunks() == null
                ? Map.of() : evalCase.getRelevantChunks();
        List<RetrievedChunk> chunks = new ArrayList<>();
        int relevantRetrieved = 0;
        int firstRelevantRank = 0;
        double dcg = 0.0d;

        for (int i = 0; i < retrieved.size(); i++) {
            VectorSearchService.SearchResult item = retrieved.get(i);
            int rank = i + 1;
            int grade = relevance.getOrDefault(item.getId(), 0);
            if (grade > 0) {
                relevantRetrieved++;
                if (firstRelevantRank == 0) {
                    firstRelevantRank = rank;
                }
            }
            dcg += gain(grade) / log2(rank + 1.0d);
            chunks.add(new RetrievedChunk(rank, item.getId(), item.getScore(), scoreType,
                    item.getDenseRank(), item.getDenseDistance(), item.getBm25Rank(), item.getBm25Score(),
                    item.getRrfScore(), item.getRerankScore(), item.getRerankRank(),
                    Boolean.TRUE.equals(item.getRerankFallback()), Boolean.TRUE.equals(item.getRerankProtected()),
                    grade, grade > 0));
        }

        int totalRelevant = relevance.size();
        int candidateRelevant = 0;
        int candidateFirstRelevantRank = 0;
        for (int i = 0; i < execution.candidates().size(); i++) {
            if (relevance.getOrDefault(execution.candidates().get(i).getId(), 0) > 0) {
                candidateRelevant++;
                if (candidateFirstRelevantRank == 0) {
                    candidateFirstRelevantRank = i + 1;
                }
            }
        }
        double candidateRecall = totalRelevant == 0 ? 0.0d : (double) candidateRelevant / totalRelevant;
        int droppedRelevantCount = Math.max(0, candidateRelevant - relevantRetrieved);
        boolean rerankPromoted = firstRelevantRank > 0 && candidateFirstRelevantRank > 0
                && firstRelevantRank < candidateFirstRelevantRank;
        double recall = totalRelevant == 0 ? 0.0d : (double) relevantRetrieved / totalRelevant;
        double precision = (double) relevantRetrieved / maxK;
        double reciprocalRank = firstRelevantRank == 0 ? 0.0d : 1.0d / firstRelevantRank;
        double idcg = idealDcg(relevance.values(), maxK);
        double ndcg = idcg == 0.0d ? 0.0d : dcg / idcg;

        return new CaseResult(
                evalCase.getId(), evalCase.getQuery(), evalCase.isAnswerable(), evalCase.getSplit(),
                evalCase.getTags(), relevance, latencyMs, firstRelevantRank,
                firstRelevantRank == 1, firstRelevantRank > 0 && firstRelevantRank <= 3,
                firstRelevantRank > 0 && firstRelevantRank <= 5,
                recall, precision, reciprocalRank, ndcg, candidateRecall, candidateFirstRelevantRank,
                rerankPromoted, droppedRelevantCount, execution.fallback(), chunks
        );
    }

    private Metrics summarize(List<CaseResult> cases, int maxK) {
        if (cases.isEmpty()) {
            return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        return new Metrics(
                average(cases, item -> item.hitAt1() ? 1 : 0),
                average(cases, item -> item.hitAt3() ? 1 : 0),
                average(cases, item -> item.hitAt5() ? 1 : 0),
                average(cases, CaseResult::recallAtK),
                average(cases, CaseResult::precisionAtK),
                average(cases, CaseResult::reciprocalRank),
                average(cases, CaseResult::ndcgAtK),
                average(cases, CaseResult::latencyMs),
                percentile(cases, 0.50d),
                percentile(cases, 0.95d),
                average(cases, CaseResult::candidateRecall),
                average(cases, item -> item.rerankPromoted() ? 1 : 0),
                average(cases, item -> item.rerankDroppedRelevantCount() > 0 ? 1 : 0),
                average(cases, item -> item.rerankFallback() ? 1 : 0)
        );
    }

    private NoAnswerSummary summarizeNoAnswer(List<CaseResult> cases, String scoreType) {
        List<Float> top1Scores = cases.stream()
                .filter(item -> !item.retrieved().isEmpty())
                .map(item -> item.retrieved().get(0).score())
                .sorted()
                .toList();
        return new NoAnswerSummary(
                cases.size(), scoreType,
                top1Scores.isEmpty() ? null : (double) top1Scores.get(0),
                top1Scores.isEmpty() ? null : top1Scores.stream().mapToDouble(Float::doubleValue).average().orElse(0),
                top1Scores.isEmpty() ? null : (double) top1Scores.get(top1Scores.size() - 1),
                "未设置拒答阈值；本次仅记录无答案 Top1 " + scoreType + " 分布"
        );
    }

    private double average(List<CaseResult> cases, java.util.function.ToDoubleFunction<CaseResult> value) {
        return cases.stream().mapToDouble(value).average().orElse(0.0d);
    }

    private double percentile(List<CaseResult> cases, double percentile) {
        List<Long> values = cases.stream().map(CaseResult::latencyMs).sorted().toList();
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private double idealDcg(java.util.Collection<Integer> grades, int maxK) {
        List<Integer> sorted = grades.stream().sorted(Comparator.reverseOrder()).limit(maxK).toList();
        double result = 0.0d;
        for (int i = 0; i < sorted.size(); i++) {
            result += gain(sorted.get(i)) / log2(i + 2.0d);
        }
        return result;
    }

    private double gain(int relevance) {
        return Math.pow(2.0d, relevance) - 1.0d;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0d);
    }

    private Dataset loadDataset() {
        try {
            ClassPathResource resource = new ClassPathResource("rag_eval_cases.json");
            try (InputStream input = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(input);
                List<RagEvalCase> cases = new ArrayList<>();
                for (JsonNode item : root.path("cases")) {
                    cases.add(objectMapper.treeToValue(item, RagEvalCase.class));
                }
                if (!"frozen".equals(root.path("status").asText())) {
                    throw new IllegalStateException("只有 frozen 评测集可以运行正式 baseline");
                }
                return new Dataset(root.path("datasetVersion").asText(), root.path("corpusVersion").asText(),
                        root.path("chunkerVersion").asText(), cases);
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取冻结评测集失败", e);
        }
    }

    private record Dataset(String datasetVersion, String corpusVersion, String chunkerVersion,
                           List<RagEvalCase> cases) {}

    public record DenseBaselineReport(String runId, String runAt, String datasetVersion, String corpusVersion,
                                      String chunkerVersion, String mode, String scoreType,
                                      Map<String, Object> retrievalConfig, String split, int maxK, int caseCount,
                                      int answerableCaseCount, int noAnswerCaseCount, Metrics metrics,
                                      NoAnswerSummary noAnswerSummary, List<CaseResult> cases) {}

    public record Metrics(double hitAt1, double hitAt3, double hitAt5, double recallAtK,
                          double precisionAtK, double mrr, double ndcgAtK, double averageLatencyMs,
                          double p50LatencyMs, double p95LatencyMs, double candidateRecall,
                          double rerankPromoteRate, double rerankDropRate, double fallbackRate) {}

    public record NoAnswerSummary(int caseCount, String scoreType, Double minTop1Score, Double averageTop1Score,
                                  Double maxTop1Score, String note) {}

    public record CaseResult(String id, String query, boolean answerable, String split, List<String> tags,
                             Map<String, Integer> relevantChunks, long latencyMs, int firstRelevantRank,
                             boolean hitAt1, boolean hitAt3, boolean hitAt5, double recallAtK,
                             double precisionAtK, double reciprocalRank, double ndcgAtK, double candidateRecall,
                             int candidateFirstRelevantRank, boolean rerankPromoted,
                             int rerankDroppedRelevantCount, boolean rerankFallback,
                             List<RetrievedChunk> retrieved) {}

    public record RetrievedChunk(int rank, String chunkId, float score, String scoreType,
                                 Integer denseRank, Float denseDistance, Integer bm25Rank, Float bm25Score,
                                 Double rrfScore, Float rerankScore, Integer rerankRank,
                                 boolean rerankFallback, boolean rerankProtected,
                                 int relevance, boolean relevant) {}

    @FunctionalInterface
    private interface RankedSearch {
        SearchExecution search(String query, int topK);
    }

    private record SearchExecution(List<VectorSearchService.SearchResult> results,
                                   List<VectorSearchService.SearchResult> candidates,
                                   boolean fallback) {
        static SearchExecution simple(List<VectorSearchService.SearchResult> results) {
            return new SearchExecution(results, results, false);
        }
    }
}
