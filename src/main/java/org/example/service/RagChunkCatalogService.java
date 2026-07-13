package org.example.service;

import org.example.dto.DocumentChunk;
import org.example.dto.RagChunkCatalogItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.net.URISyntaxException;

/**
 * 从冻结语料重新执行统一分片，并导出供人工标注使用的完整 chunk 清单。
 */
@Service
public class RagChunkCatalogService {

    private final DocumentChunkService documentChunkService;
    private final Path corpusPath;

    public RagChunkCatalogService(
            DocumentChunkService documentChunkService,
            @Value("${rag.eval.corpus-path:./aiops-docs}") String corpusPath) {
        this.documentChunkService = documentChunkService;
        this.corpusPath = resolveCorpusPath(corpusPath);
    }

    private Path resolveCorpusPath(String configuredPath) {
        Path configured = Paths.get(configuredPath).normalize();
        if (configured.isAbsolute()) {
            return configured;
        }

        Path workingDirectoryCandidate = configured.toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectoryCandidate)) {
            return workingDirectoryCandidate;
        }

        // IDE 可能把工作目录设置为仓库父目录。开发态下从 target/classes
        // 反推项目根目录，使同一个相对配置在两种启动方式下都有效。
        try {
            Path codeLocation = Paths.get(RagChunkCatalogService.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path projectRoot = Files.isDirectory(codeLocation)
                    ? codeLocation.getParent().getParent()
                    : codeLocation.getParent().getParent();
            Path projectCandidate = projectRoot.resolve(configured).normalize();
            if (Files.isDirectory(projectCandidate)) {
                return projectCandidate;
            }
        } catch (URISyntaxException | NullPointerException ignored) {
            // 保留最初候选路径，exportAll 会返回包含绝对路径的明确错误信息。
        }

        return workingDirectoryCandidate;
    }

    public RagChunkCatalog exportAll() {
        if (!Files.isDirectory(corpusPath)) {
            throw new IllegalStateException("RAG 评测语料目录不存在: " + corpusPath);
        }

        List<RagChunkCatalogItem> items = new ArrayList<>();
        try (var paths = Files.list(corpusPath)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedDocument)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();

            for (Path file : files) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                List<DocumentChunk> chunks = documentChunkService.chunkDocument(content, file.toString());
                for (DocumentChunk chunk : chunks) {
                    items.add(toCatalogItem(file, chunk));
                }
            }
            return new RagChunkCatalog(corpusPath.toString(), files.size(), items.size(), items);
        } catch (IOException e) {
            throw new IllegalStateException("导出 RAG chunk 清单失败: " + corpusPath, e);
        }
    }

    public String getCorpusPath() {
        return corpusPath.toString();
    }

    private boolean isSupportedDocument(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".txt");
    }

    private RagChunkCatalogItem toCatalogItem(Path file, DocumentChunk chunk) {
        return new RagChunkCatalogItem(
                chunk.getChunkId(),
                chunk.getDocumentId(),
                file.getFileName().toString(),
                chunk.getTitlePath(),
                chunk.getTitle(),
                chunk.getChunkIndex(),
                chunk.getStartIndex(),
                chunk.getEndIndex(),
                chunk.getContentHash(),
                chunk.getContent()
        );
    }

    public record RagChunkCatalog(
            String corpusPath,
            int documentCount,
            int chunkCount,
            List<RagChunkCatalogItem> chunks
    ) {
    }
}
