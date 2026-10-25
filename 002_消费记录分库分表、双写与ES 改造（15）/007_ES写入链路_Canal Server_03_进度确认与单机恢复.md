# 007_ES写入链路_Canal Server_03_进度确认与单机恢复

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。本文是 Canal Server 写入链路的拆分总览；三个独立文档分别说明日志读取与解析、RocketMQ 发布与事务边界、进度确认与单机恢复。设计、实现和验证状态继续按原文边界说明。

## 已确认规则

- 本次按用户确认重命名 Canal Server 处理逻辑和自定义消费服务处理逻辑文档，同步名称标识及关联引用；不调整技术结论。

- 仅新分库分表的消费表增加业务 `version`，旧表不增加；历史导入初始化为 `1`，后续更新在同一条 SQL 中修改业务字段并执行 `version = version + 1`。导入重跑不重置已有版本，MQ 重试沿用消息中的数据与版本；新旧表数据校验排除 `version`，其他口径不变，版本行为单独验证。以上为已确认、待实现验证的设计决策。
- 本文说明 Canal Server 读取、解析、发布 RocketMQ 消息和维护消费进度的职责；RocketMQ 消费和 ES 写入见 009。当前采用 `canal.serverMode=rocketMQ`，不再部署独立 Canal-to-MQ 投递组件。
- 项目包含两个独立 MySQL 实例，对应 db0、db1 两个 destination。两个通道分别维护源库、过滤规则、导入前 GTID 基线和消费进度，不共用起点，也不承诺跨库全局顺序。
- 当前部署方式以 [999 使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md:2>) 为参考，保持单机部署方式一致：1 台主机上的 1 个 Canal Server 进程承载 db0、db1 两个 destination。当前不纳入 ZooKeeper 高可用方案，不接入 ZooKeeper，也不配置自动主备切换。
- 当前使用 file-instance.xml 本地位点模板；两个通道分别维护本地持久化进度。单机故障后人工核对并重启恢复，本地文件丢失、损坏或日志断档时暂停复核，不能跳到最新 GTID。
- 当前使用本地 canal.properties 和各 destination 的 instance.properties，不使用 canal-admin、管理库或集中配置管理。
- Canal Server 内置 RocketMQ 生产端负责把事件发布到 topic；消息拆分、发送确认、部分失败、超时重试和位点持久化语义必须按 Canal Server 1.1.7 实际发行包核验。MQ 发送成功不等于 ES 成功，也不能把 MQ 或 ES 成功直接当作 Canal 位点已持久化。
- `batchId` 只用于本次批次追踪，不能充当跨重连、重新分批的稳定来源版本或事件身份；消息 `keys` 只用于检索和追踪，不能替代队列路由、来源版本与幂等。已确认顺序性要求是防止同一条消费记录的旧变更覆盖新结果，仅新分表增加业务 `version`，旧表不增加；独立 `eventId` 不写成 ES 外部版本控制的必选前提。下游采用 `CONCURRENTLY` 并发消费是已确认、待实现验证的设计决策，不要求同键事件按来源顺序完成；每条事件携带稳定来源版本，由 ES 外部版本控制拒绝旧写入，适用条件与索引、冲突、删除保护边界在 005、008 展开。
- MySQL binlog 保留期目标为 30 天；首次同步使用 006 记录的导入前 GTID 基线，正常恢复优先使用已确认并持久化的消费进度。位点断档或无法核实时暂停复核，不能跳到最新 GTID。
- Canal Server 原生 RocketMQ 模式的消息拆分、发送失败和恢复行为仍需按 1.1.7 实际发行包核验；不能用 TCP 客户端的 `getWithoutAck`、`ack` 或 `rollback` 语义替代原生生产模式的验证。内部 `rollback` 回滚 Canal 批次，不会撤回已发送到 MQ 的消息。
- 配置、流程图和代码均为说明性内容，未表示已经部署、联调或完成故障演练。
- 版本源码核对必须区分 FlatMessage JSON、二进制 Entry 和消费端内部事件契约；源坐标与行序号不能从示例反推为原生消息已提供的字段。发送回调、内存游标和持久化游标也须分别说明。
- 面试口述按 Server 的“读取日志、解析变更、发布消息、维护进度”先总后分，四个分点与整体概括一一对应，再补充单机恢复边界；整体概括中的拓扑只说明一个 Server 进程承载 db0、db1 两个通道。下游索引定位、ES 写入和消费确认在 008 展开；消息 keys、稳定事件 ID、业务顺序键和源日志进度须分别解释，不能混用。

## 面试口述精炼版本

### 进度与恢复

读取位置、Canal 批次确认、本地持久化位点、RocketMQ 消费位点和 ES 处理状态分别表示不同阶段。单机故障后要分别核对两个 destination 的位点、binlog 连续性和 MQ 积压；无法确认可靠起点时暂停，不能直接跳到最新 GTID。恢复效果仍需演练验证。

## 详细方案

### 5. 本地位点与单机恢复

#### 5.1 本地持久化进度

本文采用 `file-instance.xml` 本地位点模板，按 destination 分别保存可恢复进度。原生生产端的提交回调通过内部 `ack` 更新消费游标，本地元数据落盘是另一个阶段；读取位置、内存游标和本地持久化游标不能混为一谈。具体文件路径、刷新时机与 destination/clientId 的对应关系须按 Canal 1.1.7 实际发行包核验。

发送状态风险见 4.3。消息已进入 MQ、ES 已写入或本地文件存在，都不能单独证明 Canal 已可靠保存恢复进度；故障前已处理但尚未落盘的部分可能重放，具体范围需联调验证，下游必须处理重复和旧事件。

#### 5.2 人工重启恢复

单机部署没有备用节点自动接管。Server 进程故障时，db0、db1 的采集和发布都会中断；MQ 中已有消息能否继续被下游消费，须另行核对 RocketMQ 和 ES 写入服务状态。恢复的预期流程如下：

1. 确认原 Server 进程已停止，避免同一 destination 被两个进程同时读取和发布。
2. 保留并分别核对 db0、db1 的本地持久化位点、源实例身份和配置，不删除元数据文件来强制重跑。
3. 检查两个源库在对应恢复位置之后的 binlog 是否仍可读且连续；位点缺失、文件损坏或日志断档时暂停复核。
4. 人工重启 Server，从各通道可核对的进度继续读取，并恢复原生 RocketMQ 发布；首次 GTID 基线只用于首次初始化，不能覆盖已有进度。
5. 核对两个通道的发布恢复、MQ 积压追赶和 ES 实际结果，下游按稳定来源版本与原物理索引处理重复和旧事件，独立事件去重按需采用。

#### 5.3 主机与文件故障边界

本地位点依赖保存文件的主机和存储。主机或磁盘故障导致位点不可读时，需要先核对能否恢复原进度及必要元数据；不能在另一台主机直接按最新 GTID 启动，也不能默认退回导入前基线。备份、文件恢复和人工迁移步骤仍需实现依据与演练，当前方案不承诺自动切换、零中断或零丢失。

### 6. 监控与异常边界

建议监控单机 Server 及 db0、db1 两个通道：

- Server 进程存活、两个 destination 的连接状态和重启次数；
- binlog 读取延迟、Server 内置生产端到 RocketMQ 的发布延迟和积压；
- RocketMQ 发送失败、重试次数、消息重复和位点恢复；
- Canal Server 本地位点保存状态、文件可读性和存储空间；
- MySQL binlog 剩余保留窗口。
- RocketMQ 最早可读消息与未消费位点的距离、剩余留存窗口及容量；阈值须结合实际 Broker 确认。

日志记录不等于告警已送达，进程启动成功也不等于位点已经连续恢复。涉及本地位点丢失或损坏、binlog 过期、消息格式异常或持续投递失败时，应暂停对应 destination 并人工处理。

### 7. Server 侧验收

验收范围包括：

1. 分别验证 db0、db1 的 MySQL 连接、GTID 起点和 16 张表过滤范围。
2. 分别核对 FlatMessage JSON 和二进制 Entry 的实际封装、UPDATE 前值范围、按键拆行后的源行身份及事务标记缺失；在最终格式中验证消息解析、DDL 隔离、字段转换、发送确认和位点推进。
3. 注入部分发送成功、非 SEND_OK、超时结果不明、Broker 重启和 Server 重启，验证来源坐标及版本稳定性、可选事件身份、重放、位点恢复，以及下游并发消费时的旧版本拒绝。
4. 模拟 Server 进程故障及本地位点丢失、损坏或不可读，核对两个通道的中断范围、人工恢复、本地持久化位点与日志连续性、重复投递和积压追赶；无法核对可靠起点时暂停，不能直接跳到最新位置。
5. 分别核对 MySQL binlog 与 RocketMQ 消息留存窗口，覆盖各自读取通道的暂停、修复和追赶；模拟 MQ 未消费消息过期，验证发现缺口后暂停复核，不能靠常规重启跳过。
6. 将代表性 INSERT、UPDATE 事件交给 009 的 ES 写入服务，另行核对 DELETE 事件的消息状态、对账和 ILM 删除边界；不能把 DELETE 当作 ES 逐条删除请求验证。

上述内容是待执行验收要求，不代表当前环境已经部署或测试通过。

### 8. 版本核对依据与边界

- [Canal 1.1.7 canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties)及 [instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties)：已静态核对配置键、GTID 属性和默认 `file-instance.xml` 入口；[file-instance.xml](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/spring/file-instance.xml) 的本地落盘与恢复行为仍须按实际发行包核验。
- [Canal 1.1.7 Message](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/Message.java)、[CanalEntry](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/CanalEntry.java)及 [MysqlEventParser](https://github.com/alibaba/canal/blob/canal-1.1.7/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/mysql/MysqlEventParser.java)：消息模型、行事件解析和 slaveId 生成路径；原生 RocketMQ 生产路径仍需结合发行包核验。
- [CanalMQStarter](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/CanalMQStarter.java)及 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)：内部 clientId、空过滤表达式订阅、提交/回滚回调、分区发送及 SendResult 状态检查缺口。
- [FlatMessage](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/FlatMessage.java)、[MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java)及 [CanalMessageSerializerUtil](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/util/CanalMessageSerializerUtil.java)：JSON 字段、二进制封装、UPDATE old 范围、hash 字段及拆行后信息边界。
- [RocketMQ Spring 2.3.0 RocketMQTemplate](https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.0/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/core/RocketMQTemplate.java)及 [rocketmq-client 5.2.0 SendStatus](https://github.com/apache/rocketmq/blob/rocketmq-all-5.2.0/client/src/main/java/org/apache/rocketmq/client/producer/SendStatus.java)：有序发送签名和结果状态。

以上为已有官方版本源码的静态核对及待核验模板入口，未验证实际发行包、依赖树、MySQL 8.0.33 认证、本地位点落盘或单机重启恢复。

## 进度与增量校验边界

需要分别记录以下进度，不能用其中一类替代另一类：

| 进度 | 含义 | 不能替代 |
| --- | --- | --- |
| MySQL Binlog 位置 | Server 读取到的源日志范围 | MQ 发送成功或 ES 写入成功 |
| Canal 内部批次确认 | Server 对读取批次的确认状态 | RocketMQ Broker 持久化 |
| RocketMQ 消费位点 | 消费者已确认的消息范围 | ES 文档处理结果 |
| ES 处理状态 | 下游对具体写入结果的记录 | Canal 源日志已经安全交接 |

增量校验应保存实际接收的消息和事件，不应假设一条 RocketMQ 消息就是一笔完整 MySQL 事务。需要按事务校验时，先确认消息是否保留事务边界，或由消费端根据可靠事件重组；重组失败应保留异常。校验进度按实际保存的消息、事件范围和完成状态推进，不能直接使用 Canal `batchId` 代替可恢复的业务进度。

发生部分发送、重试或重复消息时，校验与 ES 写入都需要依赖业务版本、来源信息和幂等规则处理，不能把消息重复直接判断为事务丢失或自动回滚。

## 关联文档

- [Canal Server 总览](<./007_ES写入链路_Canal Server_Canal Server 处理逻辑.md>)
- [RocketMQ 消息发布与事务边界](<./007_ES写入链路_Canal Server_02_RocketMQ消息发布与事务边界.md>)
- [增量校验流程与差异处理](<./010_ES写入链路_ES_12_3_增量校验流程与差异处理.md>)