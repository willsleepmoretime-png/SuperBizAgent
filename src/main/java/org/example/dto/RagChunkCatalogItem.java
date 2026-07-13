package org.example.dto;

/**
 * 用于人工构建 RAG Ground Truth 的可标注 chunk。
 */
public record RagChunkCatalogItem(
        String chunkId,
        String documentId,
        String fileName,
        String titlePath,
        String title,
        int chunkIndex,
        int startIndex,
        int endIndex,
        String contentHash,
        String content
) {
}
