# 005_Canal 同步 MySQL binlog 到 ES（总链路）

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

当前版本基线：Java 8、Spring Boot 2.7.18、`rocketmq-spring-boot-starter` 2.3.0、`org.apache.rocketmq:rocketmq-client` 5.2.0、Canal Server 1.1.7、MySQL 8.0.33 和 Elasticsearch 服务端 7.17.15。5.2.0 是 MQ 客户端版本，Broker 版本未确认。

## 文档维护与已确认规则

1. **链路职责**：`MySQL → Canal Server（RocketMQ 模式）→ RocketMQ → 自定义 RocketMQ 消费与 ES 写入服务 → Elasticsearch`。Server 内置生产端发布事件到 topic；Canal Adapter 不参与主链路，不配置 32 份映射。
2. **源库与路由**：两个独立 MySQL 实例各含 `consume_record_01`～`consume_record_16`，共 32 张表，分别对应 db0、db1 destination。各通道独立维护源库、过滤、导入前 GTID 基线和进度，起点不共用，不承诺跨库全局顺序。Server 按库表过滤，以 `canal.mq.partitionHash` 按稳定业务键选队列；自定义服务按库、表和事件类型路由。
3. **首次起点与日志**：停写并等待在途事务结束后，分别采集历史导入前 GTID 基线，仅用于首次启动；历史导入及后续写入保持 `sql_log_bin=ON`。binlog 目标保留 30 天，实际参数、磁盘空间和恢复窗口仍需核验。
4. **单机部署与恢复**：与参考文章一致，1 台主机的 1 个 Server 进程承载 db0、db1，以本地 `canal.properties`、各通道 `instance.properties` 和 `file-instance.xml` 维护配置与位点；不使用 ZooKeeper、canal-admin、管理库、集中配置管理或自动主备切换。进程故障同时中断两通道采集与发布，人工核对本地位点和日志连续性后重启恢复。
5. **投递与进度**：后续恢复依据 Canal 的 RocketMQ 投递与消费进度，MQ 消费、ES 处理分别核对；MQ 或 ES 成功不证明 Canal 位点已持久化。消息拆分、发送确认、失败重试及位点持久化须按 1.1.7 发行包核验；生产源码未显式检查 `SendResult` 是否为 `SEND_OK`，内部提交回调不能单独证明可靠交接。
6. **索引与幂等**：ES 每 30 天 rollover，索引在 rollover 后 180 天进入 cold，历史索引仍可能被更新或删除。首次 INSERT 与重放须区分，INSERT 重放、UPDATE、DELETE 均命中原物理索引。稳定业务主键作为 `_id`，仅在同一索引内唯一；幂等还依赖索引定位、稳定事件身份、顺序和重放规则。至少一次投递、ES 成功后消费确认失败均可能重复处理，不承诺端到端 exactly-once。
7. **消息契约**：原生消息与内部事件契约分开核对。默认 FlatMessage JSON 缺少完整源坐标，二进制按键拆行不保留原行序号；稳定事件身份须补齐并验证，依据与边界见 007、008。
8. **文档维护**：配置、伪代码和验收步骤仅作说明，存储与恢复机制不记为已实现，部署、联调和故障演练不记为已完成；版本兼容性以发行包、依赖解析及联调为准。口述版位于版本说明及已确认规则之后、详细方案之前，按“捕获变更、交给消息队列、消费并写入 ES”展开；006 说明源库与首次起点，007 说明 Server、本地位点与单机恢复，008 说明 MQ 消费与 ES 处理。

## 面试口述精炼版本

### 整体概括

这套方案把 MySQL 新分表中已提交的增删改，异步同步到 Elasticsearch，目标是最终一致：允许短暂延迟，处理完成后数据追平。链路分为三步：**捕获变更、交给消息队列、消费并写入 ES**。

完整链路是 `MySQL → Canal Server → RocketMQ → 自定义 RocketMQ 消费与 ES 写入服务 → Elasticsearch`。端到端效果仍待联调验证。

### 捕获变更

MySQL 提供 binlog（二进制日志），Canal Server 读取并解析变更。首次同步从历史导入前保存的 GTID（全局事务标识）集合开始，覆盖导入与后续变更。

两个独立 MySQL 实例由一个 Server 进程通过 db0、db1 分别读取，各自维护起点与进度。日志条件见 006，Server 处理与单机恢复见 007。

### 交给消息队列

Canal 内置生产端把变更发布到 RocketMQ，由 MQ（消息队列）缓冲消息，使采集与 ES 写入分开进行。发布结果与 Canal 位点落盘分别核对；部分发送成功后重试，或重启恢复，都可能重复投递。

### 消费并写入 ES

自定义服务解析消息、检查来源顺序、定位物理索引，再执行 ES 增删改。历史更新、删除和 INSERT 重放均须命中原索引，拒绝旧变更覆盖新结果；具体见 008。

ES 操作及必要状态保存满足完成规则后，才允许 MQ 消费成功。MySQL、Canal、MQ、ES 各层进度分别核对，恢复须处理重复和旧事件，不承诺每个事件恰好处理一次。

## 详细方案

### 文档定位与分文档入口

本文是总链路文档，说明 MySQL 产生 binlog 后，如何经过 Canal Server、RocketMQ 和自定义 RocketMQ 消费与 ES 写入服务，最终写入 Elasticsearch。具体实现细节分别维护在以下分文档中；末项为参考文章，仅用于方案对照：

| 分文档或参考文章 | 负责内容或参考范围 |
| --- | --- |
| [006 MySQL 处理逻辑](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/006_Canal 同步 MySQL binlog 到 ES（MySQL 处理逻辑）.md:1>) | binlog 参数、GTID 基线、日志保留期和 MySQL 侧恢复起点 |
| [007 Canal Server 处理逻辑](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）.md:1>) | destination、单机 Server 配置、binlog 读取解析、本地位点、重启恢复和 RocketMQ 投递边界 |
| [008 RocketMQ 消费与 ES 写入服务处理逻辑](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/008_Canal 同步 MySQL binlog 到 ES（自定义 ES 消费服务处理逻辑）.md:1>) | RocketMQ 消费、索引定位、ES 增删改、rollover、冷热分离、重试和死信处理 |
| [999 使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md:1>) | 参考文章：Canal、RocketMQ、自定义消费者与 ES 索引切分、rollover 和冷热节点部署思路；文章版本、脚本、参数和演示结果不作为当前项目事实 |

### 参考文章的吸收与项目适配

参考文章展示了 `MySQL binlog → Canal Server（RocketMQ 模式）→ RocketMQ → 业务消费者 → Elasticsearch` 的完整演示。当前项目采用其 Canal Server 内置 RocketMQ 投递和根据业务键将消息分配到固定 RocketMQ 队列的思路，同时保留项目自己的稳定事件 ID、历史索引定位、幂等和失败处理规则；文章脚本、示例参数或演示结果不作为当前项目事实。

Canal Server 的部署方式也与参考文章保持一致，采用单机 Server，不增加 ZooKeeper 主备高可用方案。当前仍按两个独立 MySQL 实例配置 db0、db1 两个 destination，各自维护本地进度；单机故障后的人工重启与恢复边界见 007。

参考文档使用 Canal 1.1.6、RocketMQ 4.9.4 和 Elasticsearch 8 的示例；当前项目按 `AGENTS.md` 规定使用 Canal Server 1.1.7、rocketmq-client 5.2.0 和 Elasticsearch 7.17.15。参考文档中的 `max_docs=5`、20 秒生命周期等演示阈值只用于观察滚动效果，不能替代当前项目的 30 天 rollover、180 天进入 cold 阶段等规则。版本兼容、发行包加载、认证和端到端联调仍需按环境核验。
