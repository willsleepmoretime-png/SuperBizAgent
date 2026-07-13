package org.example.service;

import java.util.List;

/** 纯向量召回边界，禁止在实现中加入查询改写、关键词加权或 Rerank。 */
public interface DenseRetriever {
    List<VectorSearchService.SearchResult> search(String query, int topK);
}
