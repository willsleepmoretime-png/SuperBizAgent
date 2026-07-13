package org.example.controller;

import org.example.service.DenseRetriever;
import org.example.service.RagPromptBuilder;
import org.example.service.VectorSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 仅用于开发环境检查实际 RAG Prompt，不调用生成模型。 */
@RestController
@RequestMapping("/api/rag/debug/prompt")
public class RagPromptDebugController {

    private final DenseRetriever denseRetriever;
    private final RagPromptBuilder promptBuilder;

    public RagPromptDebugController(DenseRetriever denseRetriever, RagPromptBuilder promptBuilder) {
        this.denseRetriever = denseRetriever;
        this.promptBuilder = promptBuilder;
    }

    @GetMapping
    public ResponseEntity<PromptPreview> preview(
            @RequestParam String question,
            @RequestParam(defaultValue = "3") int topK) {
        int finalTopK = Math.max(1, Math.min(topK, 10));
        List<VectorSearchService.SearchResult> retrieved = denseRetriever.search(question, finalTopK);
        RagPromptBuilder.RagPrompt prompt = promptBuilder.build(question, retrieved);
        return ResponseEntity.ok(new PromptPreview(
                question,
                "DENSE",
                finalTopK,
                prompt.systemPrompt(),
                prompt.userPrompt(),
                prompt.context(),
                prompt.references()
        ));
    }

    public record PromptPreview(
            String question,
            String retrievalMode,
            int topK,
            String systemPrompt,
            String userPrompt,
            String context,
            List<RagPromptBuilder.CitationReference> references
    ) {}
}
