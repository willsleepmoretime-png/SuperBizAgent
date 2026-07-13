# RAG Hybrid + RRF Baseline：rag-eval-v1

## 运行配置

- Run ID：`hybrid_rrf-20260712-065713`
- 模式：`HYBRID_RRF`
- Dense candidates：20
- BM25 candidates：20
- RRF k：60
- Final TopK：5
- 融合公式：`Σ 1 / (k + rank)`

Dense 与 BM25 在完整语料上独立召回，按稳定 chunkId 去重。没有直接混合 L2 distance 与 BM25 score。

## 三模式对比

| 指标 | Dense | BM25 | Hybrid+RRF |
| --- | ---: | ---: | ---: |
| Hit@1 | 0.8000 | 0.6000 | 0.7600 |
| Hit@3 | 0.9800 | 0.8200 | 0.9400 |
| Hit@5 | 0.9800 | 0.8600 | 0.9800 |
| Recall@5 | 0.9500 | 0.8300 | 0.9200 |
| Precision@5 | 0.2080 | 0.1720 | 0.1960 |
| MRR | 0.8800 | 0.7067 | 0.8447 |
| nDCG@5 | 0.8849 | 0.7309 | 0.8488 |
| 平均延迟 | 189.26 ms | 1.34 ms | 303.04 ms |
| P95 | 250 ms | 2 ms | 973 ms |

## 结论

标准等权 RRF 保持了 Dense 的 Hit@5，并将 Dense 唯一漏召回的 `eval-cpu-008` 找回到第 5；另外 4 条 Case 排名提升。但总体 Hit@1、Recall@5、MRR 和 nDCG 均低于 Dense，暂时不能替换 Dense 作为默认模式。

主要提升：

- `eval-cpu-008`：未命中 → 第 5。
- `eval-cpu-007`：第 2 → 第 1。
- `eval-disk-010`：第 2 → 第 1。
- `eval-slow-007`：第 3 → 第 2。
- `eval-slow-008`：第 3 → 第 2。

主要退化：

- `eval-disk-003`：第 3 → 未命中。
- `eval-slow-009`：第 2 → 第 5。
- 多个 Dense Rank1 被拉到 Rank2/3。

这说明当前 BM25 排名质量不足以与 Dense 等权融合。后续不应直接上线该配置；应只在 dev split 上尝试候选数、RRF k 或带权 RRF，并在固定 test split 上做一次最终确认。另一条更稳妥的路径是保留较大的 Hybrid 候选集，在其后使用 Cross-Encoder Rerank 决定最终 Top5。

