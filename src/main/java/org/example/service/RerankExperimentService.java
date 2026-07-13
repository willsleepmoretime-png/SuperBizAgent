package org.example.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 仅在 dev split 比较 Rerank 候选数和高置信候选保护。 */
@Service
public class RerankExperimentService {

    private final DenseBaselineEvalService evalService;

    public RerankExperimentService(DenseBaselineEvalService evalService) {
        this.evalService = evalService;
    }

    public ExperimentMatrix runDefaultMatrix(int maxK) {
        List<ExperimentConfig> configs = List.of(
                new ExperimentConfig("R10", 10, false),
                new ExperimentConfig("R15", 15, false),
                new ExperimentConfig("R20", 20, false),
                new ExperimentConfig("RP10", 10, true),
                new ExperimentConfig("RP15", 15, true),
                new ExperimentConfig("RP20", 20, true)
        );
        List<ExperimentResult> results = new ArrayList<>();
        for (ExperimentConfig config : configs) {
            DenseBaselineEvalService.DenseBaselineReport report = evalService.runHybridRerank(
                    maxK, "dev", config.rerankCandidateK(),
                    20, 10, 30, 1.0d, 0.5d, config.protectDualTop3());
            results.add(new ExperimentResult(config, report.runId(), report.metrics()));
        }
        return new ExperimentMatrix("dev", maxK, results);
    }

    public record ExperimentConfig(String id, int rerankCandidateK, boolean protectDualTop3) {}
    public record ExperimentResult(ExperimentConfig config, String runId,
                                   DenseBaselineEvalService.Metrics metrics) {}
    public record ExperimentMatrix(String split, int finalTopK, List<ExperimentResult> results) {}
}
