package org.example.service;

import org.example.dto.RagChunkCatalogItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 适合当前小规模知识库的内存 BM25 实现。 */
@Service
public class JavaBm25Retriever implements SparseRetriever {

    private static final double K1 = 1.2d;
    private static final double B = 0.75d;

    private final RagChunkCatalogService catalogService;
    private final BasicMixedLanguageTokenizer tokenizer;
    private final AtomicReference<IndexSnapshot> snapshot = new AtomicReference<>(IndexSnapshot.empty());

    public JavaBm25Retriever(RagChunkCatalogService catalogService, BasicMixedLanguageTokenizer tokenizer) {
        this.catalogService = catalogService;
        this.tokenizer = tokenizer;
    }

    @Override
    public synchronized int rebuild() {
        List<RagChunkCatalogItem> chunks = catalogService.exportAll().chunks();
        Map<String, DocumentStats> documents = new LinkedHashMap<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        long totalLength = 0;

        for (RagChunkCatalogItem chunk : chunks) {
            List<String> tokens = tokenizer.tokenize(chunk.content());
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String token : tokens) {
                termFrequency.merge(token, 1, Integer::sum);
            }
            termFrequency.keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
            documents.put(chunk.chunkId(), new DocumentStats(chunk, tokens.size(), termFrequency));
            totalLength += tokens.size();
        }

        double averageLength = chunks.isEmpty() ? 0.0d : (double) totalLength / chunks.size();
        snapshot.set(new IndexSnapshot(Map.copyOf(documents), Map.copyOf(documentFrequency), averageLength));
        return chunks.size();
    }

    @Override
    public List<VectorSearchService.SearchResult> search(String query, int topK) {
        IndexSnapshot current = snapshot.get();
        if (current.documents().isEmpty()) {
            rebuild();
            current = snapshot.get();
        }
        if (current.documents().isEmpty() || topK <= 0) {
            return List.of();
        }

        Map<String, Integer> queryTerms = new HashMap<>();
        tokenizer.tokenize(query).forEach(term -> queryTerms.merge(term, 1, Integer::sum));
        List<ScoredDocument> scored = new ArrayList<>();

        for (DocumentStats document : current.documents().values()) {
            double score = 0.0d;
            for (Map.Entry<String, Integer> queryTerm : queryTerms.entrySet()) {
                int tf = document.termFrequency().getOrDefault(queryTerm.getKey(), 0);
                if (tf == 0) {
                    continue;
                }
                int df = current.documentFrequency().getOrDefault(queryTerm.getKey(), 0);
                double idf = Math.log(1.0d + (current.documents().size() - df + 0.5d) / (df + 0.5d));
                double lengthNorm = 1.0d - B + B * document.length() / current.averageLength();
                double termScore = idf * (tf * (K1 + 1.0d)) / (tf + K1 * lengthNorm);
                score += termScore * queryTerm.getValue();
            }
            if (score > 0.0d) {
                scored.add(new ScoredDocument(document.chunk(), score));
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed()
                        .thenComparing(item -> item.chunk().chunkId()))
                .limit(topK)
                .map(this::toSearchResult)
                .toList();
    }

    private VectorSearchService.SearchResult toSearchResult(ScoredDocument item) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(item.chunk().chunkId());
        result.setContent(item.chunk().content());
        result.setScore((float) item.score());
        result.setMetadata("{\"_file_name\":\"" + escapeJson(item.chunk().fileName())
                + "\",\"titlePath\":\"" + escapeJson(item.chunk().titlePath()) + "\"}");
        return result;
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record DocumentStats(RagChunkCatalogItem chunk, int length, Map<String, Integer> termFrequency) {}
    private record ScoredDocument(RagChunkCatalogItem chunk, double score) {}
    private record IndexSnapshot(Map<String, DocumentStats> documents, Map<String, Integer> documentFrequency,
                                 double averageLength) {
        static IndexSnapshot empty() {
            return new IndexSnapshot(Map.of(), Map.of(), 0.0d);
        }
    }
}
