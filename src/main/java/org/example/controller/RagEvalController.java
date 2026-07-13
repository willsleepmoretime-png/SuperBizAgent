package org.example.controller;

import org.example.dto.RagEvalCase;
import org.example.service.RagEvalService;
import org.example.service.RagChunkCatalogService;
import org.example.service.VectorIndexService;
import org.example.service.DenseBaselineEvalService;
import org.example.service.SparseRetriever;
import org.example.service.HybridExperimentService;
import org.example.service.RagEvalRunStore;
import org.example.service.RerankExperimentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RAG 检索质量评测接口。
 */
@RestController
@RequestMapping("/api/rag/eval")
public class RagEvalController {

    private final RagEvalService ragEvalService;
    private final RagChunkCatalogService ragChunkCatalogService;
    private final VectorIndexService vectorIndexService;
    private final DenseBaselineEvalService denseBaselineEvalService;
    private final SparseRetriever sparseRetriever;
    private final HybridExperimentService hybridExperimentService;
    private final RagEvalRunStore runStore;
    private final RerankExperimentService rerankExperimentService;

    public RagEvalController(RagEvalService ragEvalService,
                             RagChunkCatalogService ragChunkCatalogService,
                             VectorIndexService vectorIndexService,
                             DenseBaselineEvalService denseBaselineEvalService,
                             SparseRetriever sparseRetriever,
                             HybridExperimentService hybridExperimentService,
                             RagEvalRunStore runStore,
                             RerankExperimentService rerankExperimentService) {
        this.ragEvalService = ragEvalService;
        this.ragChunkCatalogService = ragChunkCatalogService;
        this.vectorIndexService = vectorIndexService;
        this.denseBaselineEvalService = denseBaselineEvalService;
        this.sparseRetriever = sparseRetriever;
        this.hybridExperimentService = hybridExperimentService;
        this.runStore = runStore;
        this.rerankExperimentService = rerankExperimentService;
    }

    /**
     * 导出冻结语料中的全部 chunk，供人工标注和复核 Ground Truth。
     */
    @GetMapping("/chunks")
    public ResponseEntity<RagChunkCatalogService.RagChunkCatalog> exportAllChunks() {
        return ResponseEntity.ok(ragChunkCatalogService.exportAll());
    }

    /**
     * 使用冻结评测语料重建索引。该接口会调用 Embedding 与 Milvus，仅用于受控评测环境。
     */
    @PostMapping("/index-corpus")
    public ResponseEntity<EvalCorpusIndexReport> indexEvalCorpus() {
        RagChunkCatalogService.RagChunkCatalog catalog = ragChunkCatalogService.exportAll();
        VectorIndexService.IndexingResult result = vectorIndexService.indexDirectory(
                ragChunkCatalogService.getCorpusPath());
        int bm25ChunkCount = sparseRetriever.rebuild();
        return ResponseEntity.ok(new EvalCorpusIndexReport(
                result.isSuccess(),
                catalog.documentCount(),
                catalog.chunkCount(),
                bm25ChunkCount,
                result.getSuccessCount(),
                result.getFailCount(),
                result.getDurationMs(),
                result.getFailedFiles(),
                result.getErrorMessage()
        ));
    }

    public record EvalCorpusIndexReport(
            boolean success,
            int expectedDocumentCount,
            int expectedChunkCount,
            int bm25IndexedChunkCount,
            int indexedDocumentCount,
            int failedDocumentCount,
            long durationMs,
            java.util.Map<String, String> failedFiles,
            String errorMessage
    ) {
    }

    /** 运行冻结数据集上的纯 Dense baseline。 */
    @PostMapping("/baseline/dense")
    public ResponseEntity<DenseBaselineEvalService.DenseBaselineReport> runDenseBaseline(
            @RequestParam(defaultValue = "5") int maxK,
            @RequestParam(defaultValue = "all") String split) {
        return ResponseEntity.ok(denseBaselineEvalService.run(maxK, split));
    }

    /** 运行冻结数据集上的独立 Java BM25 baseline。 */
    @PostMapping("/baseline/bm25")
    public ResponseEntity<DenseBaselineEvalService.DenseBaselineReport> runBm25Baseline(
            @RequestParam(defaultValue = "5") int maxK,
            @RequestParam(defaultValue = "all") String split) {
        return ResponseEntity.ok(denseBaselineEvalService.runBm25(maxK, split));
    }

    /** 运行 Dense + BM25 + RRF 的 Hybrid 离线评测。 */
    @PostMapping("/baseline/hybrid")
    public ResponseEntity<DenseBaselineEvalService.DenseBaselineReport> runHybridBaseline(
            @RequestParam(defaultValue = "5") int maxK,
            @RequestParam(defaultValue = "20") int denseCandidateK,
            @RequestParam(defaultValue = "20") int bm25CandidateK,
            @RequestParam(defaultValue = "60") int rrfK,
            @RequestParam(defaultValue = "1.0") double denseWeight,
            @RequestParam(defaultValue = "1.0") double bm25Weight,
            @RequestParam(defaultValue = "all") String split) {
        return ResponseEntity.ok(denseBaselineEvalService.runHybrid(
                maxK, split, denseCandidateK, bm25CandidateK, rrfK, denseWeight, bm25Weight));
    }

    /** 运行 Weighted RRF + gte-rerank-v2；模型失败时按 Case 回退 RRF。 */
    @PostMapping("/baseline/hybrid-rerank")
    public ResponseEntity<DenseBaselineEvalService.DenseBaselineReport> runHybridRerankBaseline(
            @RequestParam(defaultValue = "5") int maxK,
            @RequestParam(defaultValue = "20") int rerankCandidateK,
            @RequestParam(defaultValue = "20") int denseCandidateK,
            @RequestParam(defaultValue = "10") int bm25CandidateK,
            @RequestParam(defaultValue = "30") int rrfK,
            @RequestParam(defaultValue = "1.0") double denseWeight,
            @RequestParam(defaultValue = "0.5") double bm25Weight,
            @RequestParam(defaultValue = "false") boolean protectDualTop3,
            @RequestParam(defaultValue = "all") String split) {
        return ResponseEntity.ok(denseBaselineEvalService.runHybridRerank(
                maxK, split, rerankCandidateK, denseCandidateK, bm25CandidateK,
                rrfK, denseWeight, bm25Weight, protectDualTop3));
    }

    /** 在 dev split 上运行预定义 Hybrid 参数矩阵，不接收 test 参数。 */
    @PostMapping("/experiments/hybrid/default")
    public ResponseEntity<HybridExperimentService.ExperimentMatrix> runDefaultHybridMatrix(
            @RequestParam(defaultValue = "5") int maxK) {
        return ResponseEntity.ok(hybridExperimentService.runDefaultMatrix(maxK));
    }

    /** 仅在 dev split 运行 Rerank candidate/protection 参数矩阵。 */
    @PostMapping("/experiments/rerank/default")
    public ResponseEntity<RerankExperimentService.ExperimentMatrix> runDefaultRerankMatrix(
            @RequestParam(defaultValue = "5") int maxK) {
        return ResponseEntity.ok(rerankExperimentService.runDefaultMatrix(maxK));
    }

    @GetMapping("/runs")
    public ResponseEntity<List<String>> listRuns() {
        return ResponseEntity.ok(runStore.listRunIds());
    }

    @GetMapping("/runs/compare")
    public ResponseEntity<RagEvalRunStore.RunComparison> compareRuns(
            @RequestParam String leftRunId,
            @RequestParam String rightRunId) {
        return ResponseEntity.ok(runStore.compare(leftRunId, rightRunId));
    }

    /**
     * 使用 resources/rag_eval_cases.json 中的默认评测集计算 Hit@K 和 MRR。
     */
    @GetMapping
    public ResponseEntity<RagEvalService.RagEvalReport> evaluateDefaultCases() {
        return ResponseEntity.ok(ragEvalService.evaluateDefaultCases());
    }

    /**
     * 使用请求体中的评测集计算 Hit@K 和 MRR。
     */
    @PostMapping
    public ResponseEntity<RagEvalService.RagEvalReport> evaluate(
            @RequestBody List<RagEvalCase> cases,
            @RequestParam(defaultValue = "5") int maxK) {
        return ResponseEntity.ok(ragEvalService.evaluate(cases, maxK));
    }
}
