# 007_ES写入链路_Canal Server_09_Canal Server 处理逻辑

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

### 整体概括

Canal Server 负责把 MySQL binlog 转换为 RocketMQ 消息，再交给下游写入 ES。面试时可按三个独立问题回答：先说明日志读取与变更解析，再说明消息发布、分区和事务边界，最后说明批次确认、位点持久化与单机恢复。当前方案采用一个 Server 进程承载 db0、db1 两个 destination；实际发布、位点落盘和恢复效果仍待验证。

## 详细方案

### 拆分后的知识点

- [日志读取与变更解析](<./007_ES写入链路_Canal Server_06_日志读取与变更解析.md>)：说明复制连接、过滤范围、GTID 起点、Entry 解析和行镜像边界。
- [RocketMQ 消息发布与事务边界](<./007_ES写入链路_Canal Server_07_RocketMQ消息发布与事务边界.md>)：说明分区、消息格式、批次拆分、事务边界和发送确认。
- [进度确认与单机恢复](<./007_ES写入链路_Canal Server_08_进度确认与单机恢复.md>)：说明内部确认、本地位点、重启恢复、监控和验收边界。

### 8. 版本核对依据与边界

- [Canal 1.1.7 canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties)及 [instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties)：已静态核对配置键、GTID 属性和默认 `file-instance.xml` 入口；[file-instance.xml](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/spring/file-instance.xml) 的本地落盘与恢复行为仍须按实际发行包核验。
- [Canal 1.1.7 Message](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/Message.java)、[CanalEntry](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/CanalEntry.java)及 [MysqlEventParser](https://github.com/alibaba/canal/blob/canal-1.1.7/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/mysql/MysqlEventParser.java)：消息模型、行事件解析和 slaveId 生成路径；原生 RocketMQ 生产路径仍需结合发行包核验。
- [CanalMQStarter](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/CanalMQStarter.java)及 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)：内部 clientId、空过滤表达式订阅、提交/回滚回调、分区发送及 SendResult 状态检查缺口。
- [FlatMessage](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/FlatMessage.java)、[MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java)及 [CanalMessageSerializerUtil](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/util/CanalMessageSerializerUtil.java)：JSON 字段、二进制封装、UPDATE old 范围、hash 字段及拆行后信息边界。
- [RocketMQ Spring 2.3.0 RocketMQTemplate](https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.0/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/core/RocketMQTemplate.java)及 [rocketmq-client 5.2.0 SendStatus](https://github.com/apache/rocketmq/blob/rocketmq-all-5.2.0/client/src/main/java/org/apache/rocketmq/client/producer/SendStatus.java)：有序发送签名和结果状态。

以上为已有官方版本源码的静态核对及待核验模板入口，未验证实际发行包、依赖树、MySQL 8.0.33 认证、本地位点落盘或单机重启恢复。

## 待确认与异议分析

- Canal Server 1.1.7 实际发行包使用的 RocketMQ 消息格式、默认 `flatMessage` 值和字段集合仍需用实际消息样例确认。
- 一次 Canal 内部拉取批次如何拆分为 RocketMQ 消息、是否可能跨 MySQL 事务或拆分单个事务，仍需结合实际发行包和运行日志核验。
- 事务开始/提交事件在当前消息格式中是否保留、消费端能否可靠重组事务范围，仍待验证。
- `partitionHash` 的源列、分区数、队列映射以及 INSERT、UPDATE、DELETE 的取值是否稳定，仍待验证。
- 发送回调、部分发送失败、重试、Canal 位点落盘和 Broker 持久化之间的恢复顺序，仍未完成故障演练。
- **不应把 RocketMQ 消息直接称为完整事务批次。** Canal 拉取批次、RocketMQ 发送消息、MySQL 事务和校验任务批次由不同组件和处理阶段产生，不能仅凭消息边界推断 MySQL 事务边界。
- **发送确认不能直接证明事务已经保存。** Canal 内部批次确认、RocketMQ 发送结果、Broker 持久化和下游消费确认分别表示不同状态；部分发送成功后发生异常时可能产生重复消息，回滚也不能撤回已发送消息。

## 问题列表

- 三个拆分文档的实际发行包行为、RocketMQ 发送结果、位点落盘和单机恢复仍未完成运行验证。

## 关联文档

- [Canal Server 总览](<./007_ES写入链路_Canal Server.md>)
- [RocketMQ 消费模型](<./008_ES写入链路_RocketMQ_RocketMQ 消费模型.md>)
- [自定义 ES 消费服务处理逻辑](<./009_ES写入链路_自定义消费服务_自定义 ES 消费服务处理逻辑.md>)