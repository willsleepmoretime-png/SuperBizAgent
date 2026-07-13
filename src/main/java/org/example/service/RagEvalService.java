package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.RagEvalCase;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RAG 检索质量评测服务。
 */
@Service
public class RagEvalService {

    private final VectorSearchService vectorSearchService;
    private final ObjectMapper objectMapper;

    public RagEvalService(VectorSearchService vectorSearchService, ObjectMapper objectMapper) {
        this.vectorSearchService = vectorSearchService;
        this.objectMapper = objectMapper;
    }

    public RagEvalReport evaluateDefaultCases() {
        return evaluate(loadDefaultCases(), 5);
    }

    public RagEvalReport evaluate(List<RagEvalCase> cases, int maxK) {
        int finalMaxK = Math.max(1, maxK);
        List<RagEvalCaseResult> caseResults = new ArrayList<>();

        for (RagEvalCase evalCase : cases) {
            caseResults.add(new RagEvalCaseResult(
                    evalCase.getQuery(),
                    normalizeRelevantIds(evalCase.getRelevantDocIds()),
                    evaluateMode("baseline", evalCase, finalMaxK),
                    evaluateMode("optimized", evalCase, finalMaxK),
                    evaluateMode("fallback", evalCase, finalMaxK)
            ));
        }

        return new RagEvalReport(
                cases.size(),
                finalMaxK,
                aggregate("baseline", caseResults.stream().map(RagEvalCaseResult::baseline).toList()),
                aggregate("optimized", caseResults.stream().map(RagEvalCaseResult::optimized).toList()),
                aggregate("fallback", caseResults.stream().map(RagEvalCaseResult::fallback).toList()),
                caseResults
        );
    }

    private List<RagEvalCase> loadDefaultCases() {
        try {
            ClassPathResource resource = new ClassPathResource("rag_eval_cases.json");
            try (InputStream inputStream = resource.getInputStream()) {
                JsonNode root = objectMapper.readTree(inputStream);
                JsonNode casesNode = root.isArray() ? root : root.path("cases");
                if (!casesNode.isArray()) {
                    throw new IllegalStateException("评测集必须是数组，或包含 cases 数组");
                }
                return objectMapper.convertValue(casesNode, new TypeReference<>() {});
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取默认 RAG 评测集失败: rag_eval_cases.json", e);
        }
    }

    private RagEvalModeResult evaluateMode(String mode, RagEvalCase evalCase, int maxK) {
        List<VectorSearchService.SearchResult> results = switch (mode) {
            case "baseline" -> vectorSearchService.searchBaselineSimilarDocuments(evalCase.getQuery(), maxK);
            case "fallback" -> vectorSearchService.searchSimilarDocumentsWithLocalRerank(evalCase.getQuery(), maxK);
            default -> vectorSearchService.searchSimilarDocuments(evalCase.getQuery(), maxK);
        };

        Set<String> relevantIds = normalizeRelevantIds(evalCase.getRelevantDocIds());
        List<RagEvalRetrievedItem> retrievedItems = results.stream()
                .map(this::toRetrievedItem)
                .toList();
        int firstRelevantRank = findFirstRelevantRank(retrievedItems, relevantIds);
        double reciprocalRank = firstRelevantRank > 0 ? 1.0d / firstRelevantRank : 0.0d;

        return new RagEvalModeResult(
                mode,
                firstRelevantRank,
                reciprocalRank,
                hitAt(firstRelevantRank, 1),
                hitAt(firstRelevantRank, 3),
                hitAt(firstRelevantRank, 5),
                retrievedItems
        );
    }

    private RagEvalSummary aggregate(String mode, List<RagEvalModeResult> results) {
        if (results.isEmpty()) {
            return new RagEvalSummary(mode, 0.0d, 0.0d, 0.0d, 0.0d);
        }

        double totalHitAt1 = 0.0d;
        double totalHitAt3 = 0.0d;
        double totalHitAt5 = 0.0d;
        double totalMrr = 0.0d;

        for (RagEvalModeResult result : results) {
            totalHitAt1 += result.hitAt1() ? 1.0d : 0.0d;
            totalHitAt3 += result.hitAt3() ? 1.0d : 0.0d;
            totalHitAt5 += result.hitAt5() ? 1.0d : 0.0d;
            totalMrr += result.reciprocalRank();
        }

        int count = results.size();
        return new RagEvalSummary(
                mode,
                totalHitAt1 / count,
                totalHitAt3 / count,
                totalHitAt5 / count,
                totalMrr / count
        );
    }

    private int findFirstRelevantRank(List<RagEvalRetrievedItem> retrievedItems, Set<String> relevantIds) {
        for (int i = 0; i < retrievedItems.size(); i++) {
            RagEvalRetrievedItem item = retrievedItems.get(i);
            if (matchesAny(item.matchKeys(), relevantIds)) {
                return i + 1;
            }
        }
        return 0;
    }

    private boolean matchesAny(Set<String> matchKeys, Set<String> relevantIds) {
        for (String matchKey : matchKeys) {
            if (relevantIds.contains(normalizeId(matchKey))) {
                return true;
            }
        }
        return false;
    }

    private boolean hitAt(int firstRelevantRank, int k) {
        return firstRelevantRank > 0 && firstRelevantRank <= k;
    }

    private RagEvalRetrievedItem toRetrievedItem(VectorSearchService.SearchResult result) {
        String source = extractJsonLikeValue(result.getMetadata(), "_source");
        String fileName = extractJsonLikeValue(result.getMetadata(), "_file_name");
        String chunkIndex = extractJsonLikeValue(result.getMetadata(), "chunkIndex");
        Set<String> matchKeys = buildMatchKeys(result.getId(), source, fileName, chunkIndex);

        return new RagEvalRetrievedItem(
                result.getId(),
                source,
                fileName,
                chunkIndex,
                result.getScore(),
                result.getRerankScore(),
                result.getMatchedQuery(),
                result.getConfidenceLevel(),
                matchKeys
        );
    }

    private Set<String> buildMatchKeys(String id, String source, String fileName, String chunkIndex) {
        Set<String> keys = new LinkedHashSet<>();
        addKey(keys, id);
        addKey(keys, source);
        addKey(keys, fileName);

        if (!chunkIndex.isBlank()) {
            addKey(keys, source + "#chunk-" + chunkIndex);
            addKey(keys, source + "#" + chunkIndex);
            addKey(keys, fileName + "#chunk-" + chunkIndex);
            addKey(keys, fileName + "#" + chunkIndex);
        }
        return keys;
    }

    private void addKey(Set<String> keys, String value) {
        String normalized = normalizeId(value);
        if (!normalized.isBlank()) {
            keys.add(normalized);
        }
    }

    private Set<String> normalizeRelevantIds(List<String> relevantDocIds) {
        Set<String> ids = new LinkedHashSet<>();
        if (relevantDocIds == null) {
            return ids;
        }
        for (String relevantDocId : relevantDocIds) {
            addKey(ids, relevantDocId);
        }
        return ids;
    }

    private String normalizeId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
    }

    private String extractJsonLikeValue(String metadata, String key) {
        if (metadata == null || metadata.isBlank()) {
            return "";
        }

        try {
            JsonNode value = objectMapper.readTree(metadata).path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText();
            }
        } catch (Exception ignored) {
            // Milvus SDK 的 JSON 展示格式偶尔不是标准 JSON，继续走轻量解析。
        }

        String quotedKey = "\"" + key + "\"";
        int keyIndex = metadata.indexOf(quotedKey);
        if (keyIndex < 0) {
            return "";
        }
        int colonIndex = metadata.indexOf(':', keyIndex + quotedKey.length());
        if (colonIndex < 0) {
            return "";
        }
        int valueStart = colonIndex + 1;
        while (valueStart < metadata.length() && Character.isWhitespace(metadata.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= metadata.length()) {
            return "";
        }
        if (metadata.charAt(valueStart) == '"') {
            int valueEnd = metadata.indexOf('"', valueStart + 1);
            return valueEnd > valueStart ? metadata.substring(valueStart + 1, valueEnd) : "";
        }
        int valueEnd = valueStart;
        while (valueEnd < metadata.length() && metadata.charAt(valueEnd) != ',' && metadata.charAt(valueEnd) != '}') {
            valueEnd++;
        }
        return metadata.substring(valueStart, valueEnd).trim();
    }

    public record RagEvalReport(
            int caseCount,
            int maxK,
            RagEvalSummary baselineSummary,
            RagEvalSummary optimizedSummary,
            RagEvalSummary fallbackSummary,
            List<RagEvalCaseResult> cases
    ) {
    }

    public record RagEvalSummary(
            String mode,
            double hitAt1,
            double hitAt3,
            double hitAt5,
            double mrr
    ) {
    }

    public record RagEvalCaseResult(
            String query,
            Set<String> relevantDocIds,
            RagEvalModeResult baseline,
            RagEvalModeResult optimized,
            RagEvalModeResult fallback
    ) {
    }

    public record RagEvalModeResult(
            String mode,
            int firstRelevantRank,
            double reciprocalRank,
            boolean hitAt1,
            boolean hitAt3,
            boolean hitAt5,
            List<RagEvalRetrievedItem> retrieved
    ) {
    }

    public record RagEvalRetrievedItem(
            String id,
            String source,
            String fileName,
            String chunkIndex,
            float distance,
            float rerankScore,
            String matchedQuery,
            String confidenceLevel,
            Set<String> matchKeys
    ) {
    }
}
