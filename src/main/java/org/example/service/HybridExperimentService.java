package org.example.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 只允许在 dev split 上调参，防止用 test 选择 Hybrid 配置。 */
@Service
public class HybridExperimentService {

    private final DenseBaselineEvalService evalService;

    public HybridExperimentService(DenseBaselineEvalService evalService) {
        this.evalService = evalService;
    }

    public ExperimentMatrix runDefaultMatrix(int maxK) {
        List<ExperimentConfig> configs = List.of(
                new ExperimentConfig("H1", 20, 5, 60, 1.0d, 1.0d),
                new ExperimentConfig("H2", 20, 10, 60, 1.0d, 1.0d),
                new ExperimentConfig("H3", 20, 10, 30, 1.0d, 1.0d),
                new ExperimentConfig("H4", 20, 10, 30, 1.0d, 0.7d),
                new ExperimentConfig("H5", 20, 10, 30, 1.0d, 0.5d),
                new ExperimentConfig("H6", 20, 10, 30, 1.0d, 0.3d)
        );
        List<ExperimentResult> results = new ArrayList<>();
        for (ExperimentConfig config : configs) {
            DenseBaselineEvalService.DenseBaselineReport report = evalService.runHybrid(
                    maxK, "dev", config.denseCandidateK(), config.bm25CandidateK(), config.rrfK(),
                    config.denseWeight(), config.bm25Weight());
            results.add(new ExperimentResult(config, report.runId(), report.metrics()));
        }
        return new ExperimentMatrix("dev", maxK, results);
    }

    public record ExperimentConfig(String id, int denseCandidateK, int bm25CandidateK, int rrfK,
                                   double denseWeight, double bm25Weight) {}
    public record ExperimentResult(ExperimentConfig config, String runId,
                                   DenseBaselineEvalService.Metrics metrics) {}
    public record ExperimentMatrix(String split, int finalTopK, List<ExperimentResult> results) {}
}
