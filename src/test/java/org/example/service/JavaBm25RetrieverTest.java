package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaBm25RetrieverTest {

    @Test
    void buildsAllChunksAndFindsExactAlertName() {
        DocumentChunkConfig config = new DocumentChunkConfig();
        config.setMaxSize(800);
        config.setOverlap(100);
        RagChunkCatalogService catalog = new RagChunkCatalogService(
                new DocumentChunkService(config), "./aiops-docs");
        JavaBm25Retriever retriever = new JavaBm25Retriever(catalog, new BasicMixedLanguageTokenizer());

        assertThat(retriever.rebuild()).isEqualTo(104);
        assertThat(retriever.search("HighCPUUsage 告警触发条件", 5))
                .isNotEmpty()
                .first()
                .extracting(VectorSearchService.SearchResult::getId)
                .isEqualTo("cpu_high_usage.md::CPU使用率过高告警处理方案/告警名称::aea28ea91212");
    }

    @Test
    void tokenizerPreservesEnglishTermsAndCreatesChineseBigrams() {
        assertThat(new BasicMixedLanguageTokenizer().tokenize("CPU 使用率 HighCPUUsage"))
                .contains("cpu", "使用", "用率", "highcpuusage");
    }
}
