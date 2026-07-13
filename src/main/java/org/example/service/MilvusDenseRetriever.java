package org.example.service;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MilvusDenseRetriever implements DenseRetriever {

    private final VectorSearchService vectorSearchService;

    public MilvusDenseRetriever(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    @Override
    public List<VectorSearchService.SearchResult> search(String query, int topK) {
        return vectorSearchService.searchBaselineSimilarDocuments(query, topK);
    }
}
