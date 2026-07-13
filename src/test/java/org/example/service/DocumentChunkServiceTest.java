package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkServiceTest {

    private final DocumentChunkService service = new DocumentChunkService(chunkConfig());

    @Test
    void recordsFullTitlePathAndIgnoresHeadingsInsideCodeFence() {
        String markdown = """
                # Runbook
                ## Commands
                ```bash
                # this is a shell comment
                df -h
                ```
                ### Verify
                done
                """;

        List<DocumentChunk> chunks = service.chunkDocument(markdown, "runbook.md");

        assertThat(chunks).extracting(DocumentChunk::getTitlePath)
                .containsExactly("Runbook/Commands", "Runbook/Commands/Verify");
        assertThat(chunks.get(0).getContent()).contains("# this is a shell comment");
    }

    @Test
    void stableChunkIdDoesNotDependOnAbsoluteFilePath() {
        String markdown = "# Runbook\n\n## Verify\n\ndone";

        String first = service.chunkDocument(markdown, "C:/one/runbook.md").get(0).getChunkId();
        String second = service.chunkDocument(markdown, "D:/two/runbook.md").get(0).getChunkId();

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("runbook.md::Runbook/Verify::");
    }

    @Test
    void chunksFrozenCorpusWithoutTreatingShellCommentsAsHeadings() throws Exception {
        Path corpus = Path.of("aiops-docs");
        java.util.ArrayList<DocumentChunk> allChunks = new java.util.ArrayList<>();
        try (var files = Files.list(corpus)) {
            files.filter(path -> path.toString().endsWith(".md"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            String content = Files.readString(path, StandardCharsets.UTF_8);
                            allChunks.addAll(service.chunkDocument(content, path.toString()));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        assertThat(allChunks).hasSize(104);
        assertThat(allChunks).extracting(DocumentChunk::getTitlePath)
                .noneMatch(path -> path.contains("查看磁盘使用率") || path.contains("清空文件内容"));
    }

    private static DocumentChunkConfig chunkConfig() {
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setMaxSize(800);
        config.setOverlap(100);
        return config;
    }
}
