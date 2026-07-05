# SuperBizAgent

SuperBizAgent 是一个基于 Spring Boot、Spring AI Alibaba、DashScope、Milvus 和 RAG 构建的企业级智能排障 Agent 项目。

项目目标不是做一个简单聊天机器人，而是把企业运维排障中的 **告警查询、日志分析、内部知识库检索、根因分析、修复建议生成** 串联成一个可自动执行的 Agent 流程。

## 项目背景

传统监控系统通常只能告诉我们“哪里出问题了”，例如 CPU 使用率过高、JVM 内存异常、服务 P99 响应时间过长、接口 5xx 错误增多。但真正排障时，工程师还需要手动切换 Prometheus、日志平台和内部文档，结合经验判断根因。

SuperBizAgent 尝试将这套流程 Agent 化，让系统能够自动完成：

```text
告警理解 -> 知识库检索 -> 日志/指标查询 -> 根因分析 -> 修复建议 -> 告警分析报告
```

## 核心能力

- RAG 知识库问答：支持上传 Markdown / TXT 文档，自动分片、向量化并写入 Milvus。
- 独立重排模型：Milvus 粗召回后接入 DashScope `gte-rerank-v2` 进行语义精排。
- Rerank 失败兜底：重排模型不可用时，自动降级为本地轻量重排策略。
- 多 Agent 排障：基于 Supervisor、Planner/Replanner、Executor 实现 Plan-Execute-Replan 闭环。
- 工具调用：支持内部文档检索、Prometheus 告警查询、日志查询、时间工具等。
- 工具 Router：根据用户意图只挂载相关工具，减少无效工具调用和 token 消耗。
- 会话记忆：保留最近对话窗口，并对较早历史进行摘要压缩。
- SSE 流式输出：普通问答和 AIOps 报告均支持流式返回。
- RAG 调试接口：支持对比 baseline 向量检索与 query expansion + rerank 后的检索结果。


## 技术栈

| 模块 | 技术 |
| --- | --- |
| 开发语言 | Java 17 |
| 后端框架 | Spring Boot 3.2.0 |
| Agent 框架 | Spring AI Alibaba Agent Framework |
| 大模型服务 | Alibaba Cloud DashScope |
| 向量化模型 | `text-embedding-v4` |
| 重排模型 | `gte-rerank-v2` |
| 向量数据库 | Milvus 2.6.10 |
| 流式输出 | Server-Sent Events / `SseEmitter` |
| 构建工具 | Maven |

## 核心模块



## RAG 链路设计

### 文档入库流程

```text
上传 .txt / .md 文件
-> FileUploadController 保存文件
-> VectorIndexService 读取文件内容
-> DocumentChunkService 按 Markdown 标题和段落分片
-> VectorEmbeddingService 调用 DashScope text-embedding-v4
-> VectorIndexService 写入 Milvus
```

分片策略：

- 优先按 Markdown 标题切分，保留文档结构。
- 对长章节继续按段落切分，避免破坏语义。
- 在 chunk 内容中补充标题上下文，例如 `标题: CPU 使用率过高排查`。
- 使用 overlap 缓解跨 chunk 信息断裂。

### 检索流程

```text
用户问题 / Agent 工具调用
-> 运维关键词 query expansion
-> Milvus 召回 candidate-k 个候选 chunk
-> DashScope gte-rerank-v2 语义重排
-> 取 top-k 个高相关 chunk
-> 返回给 Agent 生成最终回答
```

默认配置：

```yaml
rag:
  top-k: 3
  candidate-k: 24
  max-distance: 0
  rerank:
    enabled: true
    model: "gte-rerank-v2"
    return-documents: true
```

如果 `gte-rerank-v2` 调用失败、超时或返回为空，系统会自动降级到本地轻量重排：

```text
rerankScore = vectorScore + contentHits * 0.08 + metadataHits * 0.15
vectorScore = 1 / (1 + L2 distance)
```

## AIOps 多 Agent 设计

AIOps 排障流程由 `AiOpsService` 负责，核心是三个角色：

| Agent | 职责 |
| --- | --- |
| Supervisor Agent | 控制流程，决定调用 Planner、Executor 或结束 |
| Planner / Replanner Agent | 拆解告警任务，根据执行反馈动态调整计划 |
| Executor Agent | 执行 Planner 给出的第一步，调用工具并返回证据 |

执行流程：

```text
POST /api/ai_ops
-> Supervisor 启动编排
-> Planner 分析告警并制定排障计划
-> Executor 执行第一步工具调用
-> Planner 根据工具结果重新规划
-> 多轮迭代直到 FINISH
-> 输出 Markdown 告警分析报告
```

为了减少 Agent 幻觉，Prompt 中明确要求所有结论必须基于工具返回结果。工具失败或返回空时，需要记录失败原因；同一方向连续失败时，必须停止并在最终报告中说明无法完成的原因，禁止编造未查询到的日志、指标或根因。

## 工具 Router

项目根据用户问题进行工具路由，避免所有问题都挂载全部工具。

| 路由 | 场景 | 工具 |
| --- | --- | --- |
| `CHAT_ONLY` | 普通聊天 | 不挂载运维工具 |
| `DATETIME` | 时间日期问题 | 时间工具 |
| `DOCS_RAG` | 文档、流程、SOP、排障方案 | 内部文档工具 |
| `OBSERVABILITY` | 告警、指标、日志 | Prometheus / 日志工具 |
| `AIOPS_PARTIAL` | 文档 + 告警或日志 | 部分排障工具 |
| `AIOPS_FULL` | 文档 + 指标 + 日志 | 完整排障工具 |

这样可以减少无效工具调用，降低 token 消耗，并提升 Agent 执行稳定性。
