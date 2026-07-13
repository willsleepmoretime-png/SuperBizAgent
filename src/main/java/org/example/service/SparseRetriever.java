package org.example.service;

import java.util.List;

/** 独立稀疏召回边界，必须在完整 chunk 语料上检索。 */
public interface SparseRetriever {
    List<VectorSearchService.SearchResult> search(String query, int topK);
    int rebuild();
}
