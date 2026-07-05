package org.example.service;

import java.util.List;

/**
 * RAG 候选文档重排服务。
 */
public interface RerankService {

    List<VectorSearchService.SearchResult> rerank(
            String query,
            List<VectorSearchService.SearchResult> candidates,
            int topK
    );
}
