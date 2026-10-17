# 005_Canal 同步 MySQL binlog 到 ES（总链路）

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

当前版本基线：Java 8、Spring Boot 2.7.18、`rocketmq-spring-boot-starter` 2.3.0、`org.apache.rocketmq:rocketmq-client` 5.2.0、Canal Server 1.1.7、MySQL 8.0.33 和 Elasticsearch 服务端 7.17.15。5.2.0 是 MQ 客户端版本，Broker 版本未确认。

## 文档维护与已确认规则

1. 项目采用 `MySQL → Canal Server（RocketMQ 模式）→ RocketMQ → 自定义 RocketMQ 消费与 ES 写入服务 → Elasticsearch` 的异步链路。Canal Server 内置 RocketMQ 生产端负责把 Canal 事件发布到 MQ；Canal Adapter 不再作为当前主链路的 ES 写入组件。
2. 项目包含两个独立 MySQL 实例，分别对应 `db0`、`db1` 两个 Canal destination。两个 destination 分别维护源库、过滤规则、导入前 GTID 基线和消费进度，不共用同步起点，也不承诺跨库全局顺序。
3. 两个实例均包含 `consume_record_01`～`consume_record_16`，共 32 张物理分表。Server 侧按库表过滤，并通过 `canal.mq.partitionHash` 按稳定业务键选择 RocketMQ 队列；自定义服务按库名、表名和事件类型路由，不再配置 32 份 Canal Adapter 映射。
4. 首次同步使用历史导入前分别采集的 GTID 基线；基线必须在暂停写入并等待在途事务结束后采集。
5. 历史导入和后续业务写入保持 `sql_log_bin=ON`，确保变更进入 binlog。导入前 GTID 基线只用于首次启动；后续 Canal Server 使用自身的 RocketMQ 投递和消费进度恢复，MQ 消费与 ES 处理进度分别核对。
6. MySQL binlog 保留期目标为 30 天；实际参数是否生效、磁盘空间是否足够以及是否覆盖恢复窗口，仍需按环境核验。
7. 当前方案采用本地配置文件，不使用 canal-admin、管理库或集中配置管理。3 台 Canal Server 通过本地 `canal.properties` 和各 destination 的 `instance.properties` 加载配置，并接入 3 节点 ZooKeeper 集群。
8. ZooKeeper 按 destination 独立协调 Server 运行资格。`db0`、`db1` 可以运行在不同 Server 上，一个 destination 的切换不强制另一个同时切换。
9. Canal Server 内置 RocketMQ 生产端负责把 Canal 事件发布到 topic；原生模式下的消息拆分、发送确认、失败重试和位点持久化必须按 Canal Server 1.1.7 实际发行包核验，不能把 MQ 发送成功或 ES 写入成功当作位点持久化证明。
10. ES 每 30 天 rollover，索引在 rollover 后 180 天进入 cold 阶段；历史索引仍可能被更新或删除。新建文档与 INSERT 重放须区分，重放、UPDATE 和 DELETE 必须命中原具体物理索引。
11. 自定义服务使用稳定业务主键作为 ES `_id`。相同 `_id` 只在同一具体索引内唯一，索引定位、稳定事件身份、事件顺序和重放规则共同决定幂等；具体存储与恢复机制不得表述为已实现能力。
12. 当前方案不承诺端到端 exactly-once。RocketMQ 至少一次投递、ES 写入成功后消费确认失败等场景都可能导致重复处理，必须依赖稳定 `_id`、事件顺序和幂等写入。
13. 文档中的配置、伪代码和验收步骤均为说明性内容，不能表述为已经部署、联调或完成故障演练。技术版本兼容性仍以实际发行包、依赖解析和联调结果为准。
14. “面试口述精炼版本”只概括整体流程和组件职责。源库拓扑与首次起点在 006 展开，Canal Server 位点与主备接管在 007 展开，MQ 消费与 ES 处理边界在 008 展开。

## 文档定位与分文档入口

本文是总链路文档，说明 MySQL 产生 binlog 后，如何经过 Canal Server、RocketMQ 和自定义 RocketMQ 消费与 ES 写入服务，最终写入 Elasticsearch。具体实现细节分别维护在以下分文档中；末项为参考文章，仅用于方案对照：

| 分文档或参考文章 | 负责内容或参考范围 |
| --- | --- |
| [006 MySQL 处理逻辑](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/006_Canal 同步 MySQL binlog 到 ES（MySQL 处理逻辑）.md:1>) | binlog 参数、GTID 基线、写入会话、日志保留期和 MySQL 侧恢复起点 |
| [007 Canal Server 处理逻辑](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）.md:1>) | destination、Server 配置、binlog 读取解析、ZooKeeper 主备、位点管理和 RocketMQ 投递边界 |
| [008 RocketMQ 消费与 ES 写入服务处理逻辑](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/008_Canal 同步 MySQL binlog 到 ES（自定义 ES 消费服务处理逻辑）.md:1>) | RocketMQ 消费、索引定位、ES 增删改、rollover、冷热分离、重试和死信处理 |
| [999 使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md:1>) | 参考文章：Canal、RocketMQ、自定义消费者与 ES 索引切分、rollover 和冷热节点部署思路；文章版本、脚本、参数和演示结果不作为当前项目事实 |

## 参考文章的吸收与项目适配

参考文章展示了 `MySQL binlog → Canal Server（RocketMQ 模式）→ RocketMQ → 业务消费者 → Elasticsearch` 的完整演示。当前项目采用其 Canal Server 内置 RocketMQ 投递和根据业务键将消息分配到固定 RocketMQ 队列的思路，同时保留项目自己的稳定事件 ID、历史索引定位、幂等和失败处理规则；文章脚本、示例参数或演示结果不作为当前项目事实。

参考文档使用 Canal 1.1.6、RocketMQ 4.9.4 和 Elasticsearch 8 的示例；当前项目按 `AGENTS.md` 规定使用 Canal Server 1.1.7、rocketmq-client 5.2.0 和 Elasticsearch 7.17.15。参考文档中的 `max_docs=5`、20 秒生命周期等演示阈值只用于观察滚动效果，不能替代当前项目的 30 天 rollover、180 天进入 cold 阶段等规则。版本兼容、发行包加载、认证和端到端联调仍需按环境核验。

## 面试口述精炼版本

### 整体链路

这套方案的目标，是把 MySQL 新分表中已提交的数据变更，通过异步链路同步到 Elasticsearch。整体分为三步：**捕获变更、交给消息队列、消费并写入 ES**。

首先，MySQL 通过 binlog，也就是二进制日志，记录数据变更。Canal Server 读取并解析这些已提交的变更，识别出哪张表、哪条记录发生了新增、更新或删除。

解析完成后，Canal 通过内置的 RocketMQ 生产端发布消息。RocketMQ 在这里负责缓冲和解耦：下游处理不过来时，消息先在 MQ 中排队等待，这是缓冲；Canal 发布消息不必等待 ES 写入完成，下游服务独立消费，这是解耦。

最后，自定义 RocketMQ 消费与 ES 写入服务从 MQ 中消费消息，根据事件类型和对应的物理索引，执行 ES 的新增、更新或删除操作，完成这条同步链路。
