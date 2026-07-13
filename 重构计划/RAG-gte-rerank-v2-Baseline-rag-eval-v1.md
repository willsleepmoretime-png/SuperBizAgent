# Hybrid + gte-rerank-v2：rag-eval-v1

## 运行配置

- Run ID：`hybrid_rerank-20260712-071720-786`
- Dense candidates：20
- BM25 candidates：10
- Weighted RRF：`k=30`、Dense weight=1.0、BM25 weight=0.5
- Rerank candidates：20
- Rerank model：`gte-rerank-v2`
- Final TopK：5
- 失败回退：Weighted RRF
- 实际回退 Case：0

## 四模式对比

| 指标 | Dense | BM25 | Hybrid RRF | Hybrid + Rerank |
| --- | ---: | ---: | ---: | ---: |
| Hit@1 | 0.8000 | 0.6000 | 0.7600 | 0.8600 |
| Hit@3 | 0.9800 | 0.8200 | 0.9400 | 0.9000 |
| Hit@5 | 0.9800 | 0.8600 | 0.9800 | 0.9600 |
| Recall@5 | 0.9500 | 0.8300 | 0.9200 | 0.9200 |
| Precision@5 | 0.2080 | 0.1720 | 0.1960 | 0.1960 |
| MRR | 0.8800 | 0.7067 | 0.8447 | 0.8940 |
| nDCG@5 | 0.8849 | 0.7309 | 0.8488 | 0.8854 |
| 平均延迟 | 189.26 ms | 1.34 ms | 303.04 ms | 873.50 ms |
| P50 | 168 ms | 0 ms | 200 ms | 356 ms |
| P95 | 250 ms | 2 ms | 973 ms | 1213 ms |

## 结论

`gte-rerank-v2` 明显改善首位排序：Hit@1 从 Dense 的 0.80 提高到 0.86，MRR 从 0.88 提高到 0.894；并将 `eval-cpu-008` 从 Dense 未命中提升到第 4。

但它也把两条原本存在于候选集中的正确结果降出最终 Top5：`eval-disk-003`、`eval-slow-009`。因此 Hit@5 从 0.98 降到 0.96，Recall@5 低于 Dense，且 P95 增加到 1213ms。当前配置不能直接替换 Dense 默认链路。

相对 Dense 的主要提升：

- `eval-cpu-008`：未命中 → 第 4。
- `eval-slow-007`：第 3 → 第 1。
- `eval-slow-008`：第 3 → 第 1。
- `eval-cpu-007`、`eval-slow-003`、`eval-disk-010`、`eval-slow-001` 提升到第 1。

主要退化：

- `eval-disk-003`：第 3 → 未进入最终 Top5。
- `eval-slow-009`：第 2 → 未进入最终 Top5。
- `eval-slow-010`：第 2 → 第 5。
- `eval-memory-010`：第 1 → 第 4。

下一轮只能在 dev split 调整 rerankCandidateK 或采用保护策略；不能根据 `eval-slow-009` 这条 test Case 调参。推荐首先在 dev 上比较 rerank candidates=10/15/20，并考虑将 RRF Top1/高置信候选与 Rerank 结果做受控保留，再在 test 上进行一次最终验证。

