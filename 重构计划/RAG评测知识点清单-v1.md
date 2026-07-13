# RAG 评测知识点清单 v1

语料版本：`aiops-v1`  
分片版本：`heading-800-100-v1`  
状态：待人工复核

以下 40 个知识点从 5 份运维文档中整理，每个知识点绑定当前分片器生成的稳定 `chunkId`。人工复核时应检查：问题能否仅凭目标 chunk 回答、是否遗漏必要 chunk、相关度是否合理。

| ID | 文档 | 知识点 | 目标 chunkId |
| --- | --- | --- | --- |
| KP-CPU-01 | CPU | HighCPUUsage 告警触发条件 | `cpu_high_usage.md::CPU使用率过高告警处理方案/告警名称::aea28ea91212` |
| KP-CPU-02 | CPU | 系统日志查询参数 | `cpu_high_usage.md::CPU使用率过高告警处理方案/排查步骤/步骤2: 查询系统日志::c89ad54c5799` |
| KP-CPU-03 | CPU | 高 CPU 进程分析要点 | `cpu_high_usage.md::CPU使用率过高告警处理方案/排查步骤/步骤3: 分析CPU消耗进程::9b76e7404920` |
| KP-CPU-04 | CPU | 死循环或无限递归特征及处理 | `cpu_high_usage.md::CPU使用率过高告警处理方案/常见原因分析/原因1: 死循环或无限递归::69935fe1a5ea` |
| KP-CPU-05 | CPU | 流量突增导致 CPU 高 | `cpu_high_usage.md::CPU使用率过高告警处理方案/常见原因分析/原因2: 流量突增::677194099d43` |
| KP-CPU-06 | CPU | 定时任务重叠导致周期性 CPU 高 | `cpu_high_usage.md::CPU使用率过高告警处理方案/常见原因分析/原因3: 定时任务重叠执行::b8e8493843d8` |
| KP-CPU-07 | CPU | CPU 告警五分钟内处置 | `cpu_high_usage.md::CPU使用率过高告警处理方案/紧急处理措施/立即操作（5分钟内）::128476f4fb03` |
| KP-CPU-08 | CPU | CPU 恢复验证 | `cpu_high_usage.md::CPU使用率过高告警处理方案/验证步骤::a6dbdd05cbb6` |
| KP-DISK-01 | 磁盘 | HighDiskUsage 告警触发条件 | `disk_high_usage.md::磁盘使用率过高告警处理方案/告警名称::0590dd99a3a3` |
| KP-DISK-02 | 磁盘 | 磁盘监控日志查询 | `disk_high_usage.md::磁盘使用率过高告警处理方案/排查步骤/步骤2: 查询系统磁盘使用情况::d71eba8a6d42` |
| KP-DISK-03 | 磁盘 | 日志文件过大处理 | `disk_high_usage.md::磁盘使用率过高告警处理方案/常见原因分析/原因1: 日志文件过大::fa218e77e6f3` |
| KP-DISK-04 | 磁盘 | 临时文件堆积处理 | `disk_high_usage.md::磁盘使用率过高告警处理方案/常见原因分析/原因2: 临时文件堆积::e237e89102d2` |
| KP-DISK-05 | 磁盘 | Docker 镜像和容器占用 | `disk_high_usage.md::磁盘使用率过高告警处理方案/常见原因分析/原因6: Docker镜像和容器占用::c13f0bac257f` |
| KP-DISK-06 | 磁盘 | 查看磁盘、大目录、大文件和 inode 命令 | `disk_high_usage.md::磁盘使用率过高告警处理方案/常用命令/查看磁盘使用情况::44ffa2fc33b4` |
| KP-DISK-07 | 磁盘 | 常用磁盘清理命令 | `disk_high_usage.md::磁盘使用率过高告警处理方案/常用命令/清理命令::914b7592572b` |
| KP-DISK-08 | 磁盘 | 磁盘清理后验证 | `disk_high_usage.md::磁盘使用率过高告警处理方案/验证步骤::73a937e1d720` |
| KP-MEM-01 | 内存 | HighMemoryUsage 告警触发条件 | `memory_high_usage.md::内存使用率过高告警处理方案/告警名称::5f2e29232b97` |
| KP-MEM-02 | 内存 | 内存监控日志查询 | `memory_high_usage.md::内存使用率过高告警处理方案/排查步骤/步骤2: 查询系统监控日志::e57ef7c3a29f` |
| KP-MEM-03 | 内存 | 内存泄漏特征与处理 | `memory_high_usage.md::内存使用率过高告警处理方案/常见原因分析/原因1: 内存泄漏::bfba61d270df` |
| KP-MEM-04 | 内存 | 缓存配置不当导致内存高 | `memory_high_usage.md::内存使用率过高告警处理方案/常见原因分析/原因3: 缓存配置不当::c12aff62b402` |
| KP-MEM-05 | 内存 | JVM 参数配置不合理 | `memory_high_usage.md::内存使用率过高告警处理方案/常见原因分析/原因5: JVM参数配置不合理::f14eef971cb4` |
| KP-MEM-06 | 内存 | 内存告警立即处置 | `memory_high_usage.md::内存使用率过高告警处理方案/紧急处理措施/立即操作（5分钟内）::c4e523c204a4` |
| KP-MEM-07 | 内存 | 生成堆转储命令 | `memory_high_usage.md::内存使用率过高告警处理方案/相关工具命令/生成堆转储文件::fd11708b60ca` |
| KP-MEM-08 | 内存 | 查看 GC 日志 | `memory_high_usage.md::内存使用率过高告警处理方案/相关工具命令/查看GC日志::ded99e937aea` |
| KP-SVC-01 | 服务不可用 | ServiceUnavailable 触发条件 | `service_unavailable.md::服务不可用告警处理方案/告警名称::a1c3402e1263` |
| KP-SVC-02 | 服务不可用 | 服务状态日志查询 | `service_unavailable.md::服务不可用告警处理方案/排查步骤/步骤2: 查询服务状态日志::93b412701c21` |
| KP-SVC-03 | 服务不可用 | 数据库连接失败处置 | `service_unavailable.md::服务不可用告警处理方案/常见原因分析/原因2: 数据库连接失败::72719cc9b122` |
| KP-SVC-04 | 服务不可用 | 依赖服务故障降级 | `service_unavailable.md::服务不可用告警处理方案/常见原因分析/原因3: 依赖服务故障::e088513b0290` |
| KP-SVC-05 | 服务不可用 | 网络故障检查和流量切换 | `service_unavailable.md::服务不可用告警处理方案/常见原因分析/原因6: 网络故障::8dddf8689774` |
| KP-SVC-06 | 服务不可用 | 故障发生后第一分钟止损 | `service_unavailable.md::服务不可用告警处理方案/紧急处理流程/第一时间（1分钟内）::2f1165a49f26` |
| KP-SVC-07 | 服务不可用 | 服务恢复验证 | `service_unavailable.md::服务不可用告警处理方案/验证步骤::e18f6d3708e4` |
| KP-SVC-08 | 服务不可用 | 15/30/60 分钟升级机制 | `service_unavailable.md::服务不可用告警处理方案/升级机制::63748992f460` |
| KP-SLOW-01 | 慢响应 | SlowResponse 触发条件 | `slow_response.md::服务响应时间过长告警处理方案/告警名称::59a9e80988ee` |
| KP-SLOW-02 | 慢响应 | 应用性能日志查询 | `slow_response.md::服务响应时间过长告警处理方案/排查步骤/步骤2: 查询应用性能日志::8c8ef53ac9d2` |
| KP-SLOW-03 | 慢响应 | 数据库慢查询分析与优化 | `slow_response.md::服务响应时间过长告警处理方案/常见原因分析/原因1: 数据库慢查询::4c48b0d2b0c3` |
| KP-SLOW-04 | 慢响应 | 外部 API 超时处置 | `slow_response.md::服务响应时间过长告警处理方案/常见原因分析/原因2: 外部API调用超时::45e11b302e71` |
| KP-SLOW-05 | 慢响应 | 缓存失效或缓存穿透 | `slow_response.md::服务响应时间过长告警处理方案/常见原因分析/原因4: 缓存失效或缓存穿透::8d27ef21e6c5` |
| KP-SLOW-06 | 慢响应 | 慢响应五分钟内措施 | `slow_response.md::服务响应时间过长告警处理方案/紧急处理措施/立即操作（5分钟内）::145e6546b425` |
| KP-SLOW-07 | 慢响应 | 响应恢复验证 | `slow_response.md::服务响应时间过长告警处理方案/验证步骤::1fc6a069713a` |
| KP-SLOW-08 | 慢响应 | 关键性能监控指标 | `slow_response.md::服务响应时间过长告警处理方案/相关监控指标::aca71c9ea803` |

