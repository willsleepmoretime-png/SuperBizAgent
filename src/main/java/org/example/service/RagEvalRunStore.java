package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将评测运行保存为 JSON，并支持逐 Case 比较。 */
@Service
public class RagEvalRunStore {

    private final ObjectMapper objectMapper;
    private final Path runsPath;

    public RagEvalRunStore(ObjectMapper objectMapper,
                           @Value("${rag.eval.runs-path:./rag-eval-runs}") String runsPath) {
        this.objectMapper = objectMapper;
        this.runsPath = resolveRunsPath(runsPath);
    }

    public synchronized void save(DenseBaselineEvalService.DenseBaselineReport report) {
        try {
            Files.createDirectories(runsPath);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(runsPath.resolve(report.runId() + ".json").toFile(), report);
        } catch (Exception e) {
            throw new IllegalStateException("保存 RAG 评测运行失败: " + report.runId(), e);
        }
    }

    public List<String> listRunIds() {
        if (!Files.isDirectory(runsPath)) {
            return List.of();
        }
        try (var files = Files.list(runsPath)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("读取评测历史失败", e);
        }
    }

    public RunComparison compare(String leftRunId, String rightRunId) {
        DenseBaselineEvalService.DenseBaselineReport left = load(leftRunId);
        DenseBaselineEvalService.DenseBaselineReport right = load(rightRunId);
        Map<String, DenseBaselineEvalService.CaseResult> leftCases = new LinkedHashMap<>();
        left.cases().forEach(item -> leftCases.put(item.id(), item));
        List<CaseRankChange> changes = new ArrayList<>();
        for (DenseBaselineEvalService.CaseResult rightCase : right.cases()) {
            DenseBaselineEvalService.CaseResult leftCase = leftCases.get(rightCase.id());
            if (leftCase == null || !rightCase.answerable()) {
                continue;
            }
            int leftComparable = leftCase.firstRelevantRank() == 0 ? Integer.MAX_VALUE : leftCase.firstRelevantRank();
            int rightComparable = rightCase.firstRelevantRank() == 0 ? Integer.MAX_VALUE : rightCase.firstRelevantRank();
            if (leftComparable != rightComparable) {
                String direction = rightComparable < leftComparable ? "IMPROVED" : "REGRESSED";
                changes.add(new CaseRankChange(rightCase.id(), rightCase.query(), leftCase.firstRelevantRank(),
                        rightCase.firstRelevantRank(), direction));
            }
        }
        return new RunComparison(leftRunId, rightRunId, left.mode(), right.mode(),
                metricDelta(left.metrics(), right.metrics()), changes);
    }

    private DenseBaselineEvalService.DenseBaselineReport load(String runId) {
        try {
            Path file = runsPath.resolve(runId + ".json").normalize();
            if (!file.getParent().equals(runsPath) || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("评测运行不存在: " + runId);
            }
            return objectMapper.readValue(file.toFile(), DenseBaselineEvalService.DenseBaselineReport.class);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("读取评测运行失败: " + runId, e);
        }
    }

    private MetricDelta metricDelta(DenseBaselineEvalService.Metrics left, DenseBaselineEvalService.Metrics right) {
        return new MetricDelta(right.hitAt1() - left.hitAt1(), right.hitAt3() - left.hitAt3(),
                right.hitAt5() - left.hitAt5(), right.recallAtK() - left.recallAtK(),
                right.precisionAtK() - left.precisionAtK(), right.mrr() - left.mrr(),
                right.ndcgAtK() - left.ndcgAtK(), right.p95LatencyMs() - left.p95LatencyMs());
    }

    private Path resolveRunsPath(String configuredPath) {
        Path configured = Paths.get(configuredPath).normalize();
        if (configured.isAbsolute()) {
            return configured;
        }
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("pom.xml"))) {
            return cwd.resolve(configured).normalize();
        }
        try {
            Path codeLocation = Paths.get(RagEvalRunStore.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path projectRoot = codeLocation.getParent().getParent();
            return projectRoot.resolve(configured).normalize();
        } catch (Exception ignored) {
            return configured.toAbsolutePath().normalize();
        }
    }

    public record MetricDelta(double hitAt1, double hitAt3, double hitAt5, double recallAtK,
                              double precisionAtK, double mrr, double ndcgAtK, double p95LatencyMs) {}
    public record CaseRankChange(String id, String query, int leftRank, int rightRank, String direction) {}
    public record RunComparison(String leftRunId, String rightRunId, String leftMode, String rightMode,
                                MetricDelta metricDelta, List<CaseRankChange> caseChanges) {}
}
