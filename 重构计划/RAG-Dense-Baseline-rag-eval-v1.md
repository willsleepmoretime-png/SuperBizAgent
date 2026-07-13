# RAG Dense Baseline：rag-eval-v1

## 运行信息

- Run ID：`dense-20260712-025500`
- 运行时间：`2026-07-12T02:55:00.660134500Z`（北京时间约 10:55）
- 数据集：`rag-eval-v1`
- 语料：`aiops-v1`
- 分片器：`heading-800-100-v1`
- 模式：纯 `DENSE`
- TopK：5
- Case：60（有答案 50，无答案 10）
- 数据集 SHA-256：`88f3e8bae80ea891484d9ee0ee1a2e4494a1952e8034fda189f1923bb39495a8`

运行前已按文件名清理五份评测文档的旧索引并重新入库，5/5 文档成功；检查本次 TopK 结果，旧 UUID 主键数量为 0。

## 有答案检索指标

| 指标 | 结果 |
| --- | ---: |
| Hit@1 | 0.8000 |
| Hit@3 | 0.9800 |
| Hit@5 | 0.9800 |
| Recall@5 | 0.9500 |
| Precision@5 | 0.2080 |
| MRR | 0.8800 |
| nDCG@5 | 0.8849 |
| 平均延迟 | 218.32 ms |
| P50 | 165 ms |
| P95 | 751 ms |

有答案指标只使用 50 条 `answerable=true` Case 计算，10 条无答案 Case 不进入 Hit、Recall、Precision、MRR 和 nDCG 的分母。

## 无答案分数分布

当前尚未冻结拒答阈值，本轮只记录 10 条无答案问题的 Top1 L2 distance：

| 指标 | 结果 |
| --- | ---: |
| 最小值 | 0.7533 |
| 平均值 | 0.8603 |
| 最大值 | 1.0510 |

后续应结合开发集中有答案 Case 的 Top1 distance 分布选择阈值，再在测试集报告 No-answer Accuracy、False Accept Rate 和 False Reject Rate。本次结果不能直接把某个 distance 当作正式拒答阈值。

## 基线结论

- Dense 的 Top5 覆盖已经较高，`Hit@5=0.98`。
- `Hit@1=0.80` 与 `MRR=0.88` 表明仍存在首位排序提升空间，后续 Rerank 应重点验证这部分增益。
- `Recall@5=0.95` 低于 Hit@5，说明多 chunk Case 中存在只找回部分正确片段的情况。
- 后续 BM25 的价值不能只看总体 Hit@5，应重点检查命令、告警名、异常术语和 Dense 失败 Case。
- P95 明显高于 P50，需要后续结合 Embedding 调用耗时继续观察。

