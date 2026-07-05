# RAG 改版理解与七天计划

## 项目定位

这是一个基于 Spring Boot 3.2、Spring AI Alibaba、DashScope 和 Milvus 的企业智能助手项目，核心能力分成两块：

- 普通问答：`ChatController` 接收 `/api/chat` 和 `/api/chat_stream` 请求，通过 `ReactAgent` 自动调用工具。
- AIOps：`/api/ai_ops` 走多 Agent 运维分析流程，调用监控、日志和内部文档工具。

当前 RAG 主要不是直接由 `RagService` 暴露接口使用，而是作为 Agent 工具接入：

1. `FileUploadController` 上传 `.txt/.md` 文件。
2. `VectorIndexService` 读取文件、分片、生成 embedding，并写入 Milvus。
3. `VectorSearchService` 把用户查询向量化，在 Milvus 中召回相似 chunk。
4. `InternalDocsTools.queryInternalDocs` 把检索结果返回给 `ReactAgent`。
5. `ReactAgent` 根据工具结果组织最终回答。

## 当前 RAG 问题

- 检索链路偏简单：只做向量 topK，没有候选召回、相似度阈值、重排或查询改写。
- 工具返回格式偏原始：Agent 看到的是搜索结果对象 JSON，不利于稳定引用来源。
- 参数分散：`topK`、`model` 等配置散落在不同类中。
- 分片只支持纯文本和 Markdown，缺少文档类型扩展、索引状态、失败重试。
- 缺少评测集：不知道改版前后准确率、召回率和幻觉率有没有变好。

## 已完成的第一阶段改动

- 新增 `RagProperties`，统一管理 `rag.top-k`、`rag.candidate-k`、`rag.max-distance`、`rag.model`。
- `VectorSearchService` 改为先召回候选，再按 L2 距离阈值过滤，最后截断为 topK。
- `InternalDocsTools` 改为结构化返回 `source/fileName/chunkIndex/totalChunks/title/distance/content`，方便 Agent 引用来源。
- `application.yml` 新增 `candidate-k` 和 `max-distance` 配置，默认不启用距离过滤，避免破坏现有行为。

## 七天改版节奏

### Day 1：跑通与理解

- 修复本地 Maven 仓库权限，保证 `mvn -DskipTests compile` 可运行。
- 启动 Milvus：`docker compose -f vector-database.yml up -d`。
- 配置 `DASHSCOPE_API_KEY`，完成上传、索引、问答全链路 smoke test。
- 整理 20 个业务问题作为最小 RAG 评测集。

### Day 2：检索质量增强

- 调 `candidate-k` 和 `max-distance`，确定默认阈值。
- 增加 query normalization：去空白、处理多轮追问、保留关键上下文。
- 给检索结果加入来源引用格式，要求回答中引用 `[文件名#chunk]`。

### Day 3：分片与索引增强

- 优化 Markdown 分片：标题层级进入 metadata。
- 增加批量 embedding，减少索引耗时。
- 给上传接口返回索引成功/失败的明确状态，不再只在日志中记录失败。

### Day 4：重排与上下文组装

- 增加轻量重排：优先相同文件相邻 chunk、标题匹配、关键词覆盖。
- 控制上下文长度，避免把低价值 chunk 塞给模型。
- 对无结果和低置信度场景输出明确兜底文案。

### Day 5：接口与前端体验

- 在 SSE 中输出检索阶段事件，例如 retrieved sources。
- 前端展示引用来源、距离分数和文件名。
- 增加 `/api/rag/search` 调试接口，便于不经过 Agent 直接看召回结果。

### Day 6：评测与稳定性

- 跑最小评测集，记录命中率、答案可用率、无依据回答数量。
- 增加日志字段：query、topK、candidateK、distance、source。
- 处理 Milvus/DashScope 异常时的可读错误。

### Day 7：收尾交付

- 补 README 的运行说明和 RAG 参数说明。
- 固化一套默认配置。
- 输出改版前后对比和剩余风险。

## 风险点

- 当前 embedding 维度常量是 `1024`，必须和 `dashscope.embedding.model` 实际输出一致，否则 Milvus 写入会失败。
- Maven 全局配置指向 `D:\Maven\repository`，当前环境没有写权限，编译验证会失败。
- 如果启用 `max-distance`，需要用真实数据校准阈值，不能拍脑袋设默认值。
- `RagService` 目前不是主问答入口，主链路改版重点应放在 `InternalDocsTools` 和 `VectorSearchService`。
