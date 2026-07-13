package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.DocumentChunkConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RagEvalDatasetTest {

    @Test
    void draftContainsFiftyAnswerableAndTenNoAnswerCasesWithValidChunkIds() throws Exception {
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setMaxSize(800);
        config.setOverlap(100);
        RagChunkCatalogService catalogService = new RagChunkCatalogService(
                new DocumentChunkService(config), "./aiops-docs");
        Set<String> validChunkIds = new HashSet<>();
        catalogService.exportAll().chunks().forEach(chunk -> validChunkIds.add(chunk.chunkId()));

        JsonNode root = new ObjectMapper().readTree(
                Files.readString(Path.of("src/main/resources/rag_eval_cases.json")));
        JsonNode cases = root.path("cases");
        int answerable = 0;
        int noAnswer = 0;
        Set<String> caseIds = new HashSet<>();

        for (JsonNode item : cases) {
            assertThat(caseIds.add(item.path("id").asText())).isTrue();
            assertThat(item.path("query").asText()).isNotBlank();
            JsonNode relevantChunks = item.path("relevantChunks");
            if (item.path("answerable").asBoolean()) {
                answerable++;
                assertThat(relevantChunks.size()).isGreaterThan(0);
            } else {
                noAnswer++;
                assertThat(relevantChunks.size()).isZero();
            }
            relevantChunks.fieldNames().forEachRemaining(chunkId ->
                    assertThat(validChunkIds).as("unknown chunkId in %s", item.path("id").asText())
                            .contains(chunkId));
            relevantChunks.elements().forEachRemaining(relevance ->
                    assertThat(relevance.asInt()).isBetween(1, 3));
        }

        assertThat(cases).hasSize(60);
        assertThat(answerable).isEqualTo(50);
        assertThat(noAnswer).isEqualTo(10);
        assertThat(root.path("datasetVersion").asText()).isEqualTo("rag-eval-v1");
        assertThat(root.path("status").asText()).isEqualTo("frozen");
    }
}
