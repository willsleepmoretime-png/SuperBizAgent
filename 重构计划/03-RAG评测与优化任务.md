# 任务三：RAG 评测与优化

## 1. 任务目标

先固定“问题—正确 chunk”的事实并建立可重复、可视化的 RAG 离线评测体系，再实现少量能够用指标证明价值的检索优化。评测服务不是最后的验收工具，而是从第一阶段开始贯穿分片、召回、融合、重排和生成的研发基础设施。

主线方案：

```text
Dense Recall
+ Java BM25 Recall
→ RRF Fusion
→ gte-rerank-v2
→ TopK Context
```

同时完成检索与生成解耦，让评测系统、普通 RAG、单 Agent 工具和多 Agent ToolExecutor 共用同一个 `RetrievalService`。

实施原则：

```text
固定语料与 Ground Truth
→ 运行 Dense Baseline
→ 增加 BM25 并独立对比
→ 增加 RRF 并对比
→ 增加 Rerank 并对比
→ 调整分片和候选参数
→ 最后进行生成评测
```

每次实验只改变一个主要变量，并保存数据集版本、语料版本、分片配置、检索配置、代码版本和逐 Case 结果。任何优化如果无法由同一份评测集证明收益，或产生无法接受的退化 Case，就不进入默认链路。

## 2. 项目范围

### 必须实现

- 50～80 条 chunk 级评测集。
- Dense、BM25、Hybrid、Hybrid+Rerank 四种模式。
- Java BM25。
- RRF 排名融合。
- `gte-rerank-v2`。
- 删除自定义关键词加权评分。
- Rerank 失败回退 RRF。
- Hit@K、Recall@K、Precision@K、MRR、nDCG、无答案准确率、P50/P95。
- 可视化总览与单 Case 排名对比。
- 保存评测历史。

### 暂不实现

- BGE Reranker 本地服务。
- Elasticsearch/OpenSearch。
- 十万级文档压测。
- 完整 Parent-Child Retrieval。
- 复杂在线 A/B Test。

## 3. 当前问题

- 默认评测集只有 5 条，且只标注文件名。
- 当前 baseline/optimized/fallback 模式无法清晰表达具体策略。
- 当前评测服务处在检索实现之后，不能在每个研发阶段提供统一的指标反馈。
- 自定义评分权重没有数据依据。
- Dense 候选集之外的精确术语无法被关键词规则找回。
- 模型 Rerank 成功后没有可靠阈值。
- TopK 可能包含同一文档的重复相邻 chunk。
- `RagService` 与 `InternalDocsTools` 维护两套 RAG 使用路径。
- 当前分片只记录当前标题和 `chunkIndex`，没有完整 `titlePath`；重新分片后 `文件名#chunk-N` 容易失效。
- 当前评测集没有 `answerable`、graded relevance、数据集版本和人工标注说明。

## 4. 统一 RetrievalService

```java
public enum RetrievalMode {
    DENSE,
    BM25,
    HYBRID,
    HYBRID_RERANK
}
```

```java
public interface RetrievalService {
    RetrievalResult retrieve(RetrievalRequest request);
}
```

```java
public record RetrievalRequest(
    String query,
    RetrievalMode mode,
    int topK,
    int denseCandidateK,
    int bm25CandidateK,
    RetrievalFilter filter
) {}
```

```java
public record RetrievalCandidate(
    String chunkId,
    String documentId,
    String fileName,
    String titlePath,
    String content,
    Integer denseRank,
    Float denseDistance,
    Integer bm25Rank,
    Double bm25Score,
    Double rrfScore,
    Integer rerankRank,
    Double rerankScore
) {}
```

使用方：

```text
RAG Eval → RetrievalService
AnswerGenerator → RetrievalService
InternalDocsTool → RetrievalService
ToolExecutor → RetrievalService
```

## 5. 四种评测模式

### DENSE

```text
Query → Embedding → Milvus → TopK
```

### BM25

```text
Query → Analyzer → Java BM25 → TopK
```

### HYBRID

```text
Dense TopN + BM25 TopN → RRF → TopK
```

Dense 和 BM25 必须分别在完整 chunk 语料上独立召回。BM25 不能只对 Dense 候选重新打分，否则无法补回 Dense 漏掉的精确术语，不构成真正的多路召回。

初始参数建议：

```text
denseCandidateK = 20
bm25CandidateK  = 20
rrfK            = 60
rerankCandidateK = 20
finalTopK       = 5
```

融合时按稳定 `chunkId` 去重，并在候选对象中保留 `denseRank`、`bm25Rank` 和两路原始分数，使评测页面能够解释正确 chunk 是由哪一路找回的。

### HYBRID_RERANK

```text
Dense TopN + BM25 TopN → RRF → gte-rerank-v2 → TopK
```

降级顺序：

```text
HYBRID_RERANK
→ Rerank 失败：使用 HYBRID/RRF
→ BM25 失败：使用 DENSE
```

不再回退到自定义 `vector + keyword hits` 评分。

## 6. Java BM25

选择 Java 轻量 BM25 的原因：

- 当前文档规模适合简历演示。
- 不引入 Elasticsearch。
- 方便展示 BM25 命中和排名。
- 与当前 Spring Boot 项目集成简单。

需要实现：

- 中文和英文基础分词接口。
- 文档长度、平均长度和 IDF 统计。
- 索引重建。
- chunkId 到文档内容映射。
- BM25 TopN 查询。

建议接口：

```java
public interface SparseRetriever {
    List<RetrievalCandidate> search(String query, int topK);
    void rebuild(List<IndexableChunk> chunks);
}
```

简历项目可先使用轻量中文切词；分词器应封装接口，便于后续替换。

## 7. RRF 融合

标准公式：

```text
RRFScore(d) = Σ 1 / (k + rank_i(d))
```

初始使用 `k=60`：

```java
double rrfScore = 0.0;
if (denseRank != null) {
    rrfScore += 1.0 / (60 + denseRank);
}
if (bm25Rank != null) {
    rrfScore += 1.0 / (60 + bm25Rank);
}
```

RRF 只依赖排名，避免直接混合 L2 距离和 BM25 分数。

## 8. Rerank 选型

主线继续使用：

```text
gte-rerank-v2
```

原因：

- 当前已经接入。
- 不需要增加 Python 推理服务和 GPU 环境。
- 可以集中完成评测、BM25 和可视化。
- 简历价值主要来自可量化的 Rerank 增益，而不是本地部署模型。

统一接口：

```java
public interface RerankService {
    List<RetrievalCandidate> rerank(
        String query,
        List<RetrievalCandidate> candidates,
        int topK
    );
}
```

BGE Reranker 只作为后续可插拔实现，不进入当前主线。

## 9. 评测集

### 9.1 先固定 Ground Truth

评测集要固定的不是“当前检索系统返回了什么”，而是以下人工事实：

```text
query
+ relevant chunkId 及相关度
+ 为什么这些 chunk 能回答问题
```

正确流程：

```text
冻结语料版本和分片配置
→ 导出全部 chunk 清单
→ 从原始文档整理知识点
→ 根据真实用户表达编写 query
→ 在完整 chunk 清单中人工选择正确 chunk
→ 人工复核
→ 发布并冻结评测集版本
→ 才运行 Dense、BM25、Hybrid、Rerank
```

禁止使用以下流程：

```text
先运行 Dense
→ 只在 Dense TopK 中选择“正确答案”
```

否则 Dense 没有召回的正确 chunk 永远不会被标注，也无法证明 BM25 是否补回了 Dense 的漏召回。

### 9.2 可标注的 chunk 清单

分片完成后应导出完整 chunk 目录，标注人员可以脱离检索结果浏览和搜索所有 chunk：

```json
{
  "chunkId": "cpu_high_usage.md::排查步骤/步骤3-分析CPU消耗进程::7ab31e2c",
  "documentId": "cpu_high_usage.md",
  "fileName": "cpu_high_usage.md",
  "titlePath": "排查步骤/步骤3-分析CPU消耗进程",
  "chunkIndex": 4,
  "contentHash": "7ab31e2c",
  "content": "查看日志中的进程信息，重点关注……"
}
```

第一版可以通过 `/api/rag/chunks` 导出 JSON；后续标注页面应支持输入 query、搜索完整 chunk 池、查看原文上下文、勾选 chunk、设置相关度并填写说明。

### 9.3 稳定 chunkId

必须标注到稳定 chunkId，不使用 Milvus 内部 ID，也不将 `文件名#chunk-N` 作为长期事实 ID。建议：

```text
chunkId = documentId + "::" + headingPath + "::" + contentHash
```

其中：

- `documentId`：知识库内稳定的文档标识，当前可先使用规范化文件名。
- `headingPath`：完整 Markdown 标题路径，而不是只有最后一级标题。
- `contentHash`：chunk 内容规范化后计算 SHA-256，截取前 8～12 位。
- `chunkIndex`：仅用于展示和排查，不作为稳定标注 ID。

内容规范化至少统一换行、连续空白并执行 `trim`。重新分片或内容变化时，必须运行评测集校验器；失效 ID 应使评测直接失败，不能被静默计为“检索未命中”。

### 9.4 先整理知识点，再编写问题

标注人员先从原始文档整理“知识点—答案 chunk”，再将知识点改写为用户问题。例如 CPU 文档可以拆为：

| 知识点 | 正确章节 |
| --- | --- |
| `HighCPUUsage` 的触发条件 | 告警名称 |
| CPU 高时使用什么日志查询参数 | 排查步骤/步骤2 |
| 如何分析高 CPU 进程 | 排查步骤/步骤3 |
| 单进程接近 100% 的可能原因 | 原因1：死循环或无限递归 |
| CPU 周期性升高的可能原因 | 原因3：定时任务重叠执行 |
| CPU 恢复后的验证方式 | 验证步骤 |

问题应覆盖直接表达、口语表达、精确术语、症状描述、多部分问题和无答案问题，不能只是机械替换几个字。

### 9.5 数量与类型

第一版建议固定 60 条，其中 40 条作为开发集，用于调参数和阈值；20 条作为测试集，用于里程碑对比，避免反复针对全部 Case 调参。

| 类型 | 建议数量 |
| --- | ---: |
| 直接运维问题 | 15 |
| 同义词和口语表达 | 10 |
| 错误码、异常类名、命令等精确术语 | 10 |
| 多 chunk 问题 | 8 |
| 无答案问题 | 10 |
| 相似主题混淆问题 | 7 |
| 合计 | 60 |

### 9.6 Case 格式

```json
{
  "id": "eval-001",
  "query": "CPU 周期性升高，而且总在固定时间出现，可能是什么原因？",
  "answerable": true,
  "relevantChunks": {
    "cpu_high_usage.md::常见原因分析/原因3-定时任务重叠执行::a183def2": 3
  },
  "tags": ["cpu", "symptom-query", "cause-analysis"],
  "split": "dev",
  "notes": "该 chunk 明确描述周期性升高、固定时间点出现和定时任务执行记录"
}
```

相关度统一为：

| 分值 | 含义 |
| ---: | --- |
| 3 | 核心答案，可独立回答问题，或是多 chunk 答案不可缺少的部分 |
| 2 | 重要补充，直接相关但不能独立完整回答 |
| 1 | 背景相关，对回答有帮助但不是答案核心 |
| 0 | 不相关，不写入 `relevantChunks` |

判定原则：只阅读被标注为 3 分的 chunk，应该能够回答问题核心。不能因为 chunk 与 query 来自同一文档就将其标为相关。

### 9.7 多 chunk Case

只有当完整回答确实需要多个片段时才标多个 chunk：

```json
{
  "id": "eval-service-008",
  "query": "服务不可用时如何快速恢复，恢复后又要验证什么？",
  "answerable": true,
  "relevantChunks": {
    "service_unavailable.md::紧急处理流程::4ca9d013": 3,
    "service_unavailable.md::验证步骤::a182ce91": 3
  },
  "tags": ["service-unavailable", "multi-chunk"],
  "split": "test",
  "notes": "完整答案必须同时覆盖恢复操作和恢复后验证"
}
```

该 Case 在 TopK 只找到一个正确 chunk 时，`Hit@K=1`，但 `Recall@K=0.5`，因此评测不能只计算 Hit@K。

### 9.8 无答案 Case

无答案必须表示完整知识库确实没有答案，而不是当前检索没有找到：

```json
{
  "id": "eval-na-001",
  "query": "Kafka ISR 持续收缩应该如何处理？",
  "answerable": false,
  "relevantChunks": {},
  "tags": ["no-answer", "kafka"],
  "split": "test",
  "notes": "当前语料不包含 Kafka 运维文档"
}
```

无答案集同时包括完全无关问题和“关键词相似但知识库无法回答”的困难负样本，用于测试系统是否凭相似词强行返回结果。

### 9.9 标注与复核

单人标注至少进行两轮：第一轮填写，第二轮只查看 `query + 被选中的 chunk 内容`，检查是否真的足以回答、是否漏标必要 chunk、是否将同主题误认为相关。多人参与时由 A 标注、B 独立复核，不一致 Case 单独讨论。

评测集文件增加顶层元数据：

```json
{
  "datasetVersion": "rag-eval-v1",
  "corpusVersion": "aiops-v1",
  "chunkerVersion": "heading-800-100-v1",
  "createdAt": "2026-07-12",
  "cases": []
}
```

每次运行前校验：Case ID 唯一、query 非空、`answerable=true` 至少有一个相关 chunk、`answerable=false` 没有相关 chunk、相关度只能为 1/2/3、所有 chunkId 存在、语料与数据集版本匹配。

## 10. 评测指标

### 检索指标

- Hit@1、Hit@3、Hit@5。
- Recall@K。
- Precision@K。
- MRR。
- nDCG@5。
- 无答案准确率/误召回率。
- 平均延迟、P50、P95。

无答案 Case 不使用普通 Hit@K 判定。系统必须定义统一的 `NO_RELIABLE_RESULT` 条件，并在开发集上选择阈值，在固定测试集上报告：

- No-answer Accuracy：无答案问题被正确拒绝的比例。
- False Accept Rate：无答案问题却返回知识库内容的比例。
- False Reject Rate：有答案问题却被拒绝的比例。
- Answerable Recall：有答案问题被正确接受的比例。

阈值必须属于评测运行配置，不能针对单个 Case 临时调整。

### 生成指标（第二阶段）

- 引用正确率。
- Answer Faithfulness。
- 无答案识别率。
- 输入/输出/总 Token。
- 端到端延迟。

评测结果必须保存运行时间、代码版本、配置和数据集版本，避免指标不可复现。

## 11. 可视化页面

### 总览

| 模式 | Hit@1 | Hit@3 | MRR | nDCG@5 | No Answer | P95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Dense | 运行后填充 |  |  |  |  |  |
| BM25 | 运行后填充 |  |  |  |  |  |
| Hybrid | 运行后填充 |  |  |  |  |  |
| Hybrid + Rerank | 运行后填充 |  |  |  |  |  |

### 单 Case

分别展示四种模式的 TopK，并显示：

- 排名和是否相关。
- chunkId、文件名和标题路径。
- Dense rank/distance。
- BM25 rank/score。
- RRF score。
- Rerank rank/score。
- 内容预览。

### 历史趋势

- 选择两次评测运行进行比较。
- 显示指标升降和退化 Case。
- 展示总耗时和调用成本。

## 12. 入库小范围修正

虽然生产级版本管理不是主线，仍应完成以下低成本修正：

- `resolvedPath.startsWith(uploadDir)` 路径检查。
- 索引失败时向用户返回 `INDEX_FAILED`。
- 文件 checksum，未变化时跳过重复索引。
- 使用批量 Embedding。
- 索引完成后检查实际 chunk 数量。
- start/end offset 基于原始内容计算。

Markdown AST、Parent-Child 和完整 activeVersion 可以写入后续规划，不作为本轮评测阻塞项。

## 13. 代码任务清单

任务顺序按“评测先行、逐阶段证明收益”调整如下。

### R0：冻结语料与实验元数据

- 固定 `corpusVersion`、分片参数、Embedding 模型和评测集版本。
- 每次运行记录代码版本、配置、运行时间和逐 Case 结果。
- 明确开发集与测试集，测试集不用于日常调参。

### R1：稳定 chunkId 与可标注 chunk 清单

- 分片器增加完整 `titlePath`。
- 使用 `documentId + titlePath + contentHash` 生成稳定 chunkId。
- 增加完整 chunk 清单导出能力。
- 增加评测集结构与失效 chunkId 校验器。

### R2：固定 50～80 条 Ground Truth

- 从原始文档整理知识点，不从当前检索 TopK 反推答案。
- 第一版整理 60 条，覆盖直接、口语、精确术语、多 chunk、混淆和无答案。
- 支持 graded relevance、`answerable`、`tags`、`split` 和人工说明。
- 完成第二轮人工复核并发布 `rag-eval-v1`。

### R3：评测服务与 Dense Baseline

- 将评测模式改为 DENSE、BM25、HYBRID、HYBRID_RERANK。
- 计算 Hit@K、Recall、Precision、MRR、nDCG 和无答案指标。
- 统计平均延迟、P50、P95。
- 保存运行级配置、汇总结果和逐 Case 结果，支持导出 JSON。
- 使用冻结评测集运行并保存当前 Dense baseline。

### R4：统一检索接口

- 新建 `RetrievalService`。
- 重构 `VectorSearchService` 为 Dense Retriever。
- `InternalDocsTools` 与 AnswerGenerator 共用 RetrievalService。
- 移除未接入或重复的 RAG 生成路径。

### R5：独立 BM25 召回

- 实现 `SparseRetriever`。
- BM25 在完整 chunk 语料上建索引和独立召回，不能依赖 Dense 候选。
- 单独运行 Dense 与 BM25，对比精确术语和口语 Case。

### R6：RRF 多路融合

- 合并 Dense TopN 与 BM25 TopN，并按稳定 chunkId 去重。
- 实现标准 RRF。
- 删除自定义关键词加权。
- 单独运行 HYBRID，保存指标变化和退化 Case。

### R7：Rerank

- 保留 `gte-rerank-v2` 实现。
- Rerank 失败回退 RRF。
- 增加最低相关阈值或无可靠结果判断。
- 对比 HYBRID 与 HYBRID_RERANK 的 MRR、Hit@1、nDCG@5、P95 和调用成本。

### R8：参数实验

- 在同一评测集上逐项调整 chunkSize、overlap、candidateK、RRF k、finalTopK 和无答案阈值。
- 每次只改变一个主要变量。
- 优化必须同时报告总体收益和退化 Case，不能只保留最好的单个数字。

### R9：可视化

- 总览对比。
- 单 Case 四模式排名对比。
- 历史趋势和退化 Case。

### R10：生成评测

- 检索与生成彻底分离。
- 增加 Citation ID。
- 统计引用正确率、无答案识别和 Token。

## 14. 验收标准

- 四种模式使用同一评测集独立运行。
- 评测标注到 chunk，不再只标注文件名。
- Dense 与 BM25 召回结果可单独查看。
- Hybrid 使用 RRF，不使用自定义关键词权重。
- Rerank 使用 `gte-rerank-v2`，失败时使用 RRF 结果。
- 评测结果包含指标、延迟、配置和数据集版本。
- 可视化能够解释正确 chunk 是在哪个阶段被找回或提升排名的。
- 简历中的提升数字可以由保存的评测运行复现。

## 15. 简历表述模板

> 构建面向运维知识库的 RAG 离线评测平台，整理 chunk 级标注数据集，实现 Dense、Java BM25、RRF Hybrid Search 与 `gte-rerank-v2` Cross-Encoder Rerank；通过 Hit@K、MRR、nDCG、无答案准确率及 P95 延迟对各检索策略进行可视化对比，并将检索层与 Agent/生成层解耦。
