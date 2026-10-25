# 007_ES写入链路_Canal Server_07_RocketMQ消息发布与事务边界

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。本文是 Canal Server 写入链路的拆分总览；三个独立文档分别说明日志读取与解析、RocketMQ 发布与事务边界、进度确认与单机恢复。设计、实现和验证状态继续按原文边界说明。

## 适用规则

本页沿用 Canal Server 原总览（原文档已删除，依据待补充） 的“已确认规则”；本页仅展开当前知识点直接需要的处理过程、边界和待验证事项。

## 面试口述精炼版本

### 发布与事务边界

Canal Server 由内置 RocketMQ 生产端按源字段分区发布消息。一个 Canal 拉取批次可能拆成多条 MQ 消息，不能把 MQ 消息或 batchId 直接当作完整 MySQL 事务；部分发送、重试和乱序需要依靠来源信息、业务版本和幂等规则处理。发送确认和 Broker 持久化仍待验证。

## 详细方案

#### 3.1 消息分区与来源顺序

消费端候选 `orderKey` 为 `destination + database + table + consumeId`，用于内部顺序状态；它不是原生生产端直接拼接的 hash 字符串。1.1.7 的 `partitionHash` 按库表规则匹配源列，DELETE 使用删除前列，INSERT/UPDATE 使用变更后列；开启 `canal.mq.database.hash` 时还混入库名。topic、字段值、分区数和队列列表稳定，才可核对同键是否始终到同一队列。消费端采用 `CONCURRENTLY`（并发消费），单次回调仍同步等待当前消息内的 ES 结果；同键消息可能并发或乱序完成，由 ES 按来源版本拒绝旧写入。Tag 只做过滤。

上面的正则覆盖 16 张表，旧示例只配置 `consume_record_01` 会让未匹配表走默认分区。`<source-key-column>` 须替换为真实源字段并核对 INSERT、UPDATE、DELETE 均有值，不能把 ES 的驼峰字段直接当作 MySQL 列名。分区数必须明确为正数，并核对与实际 topic 队列列表的映射；不能只写 `partitionHash` 就宣称已实现业务键分散到多个队列。

同一 destination 同时只启动一个 Server 读取和发布，不能在旧进程仍运行时另起重复进程。原生生产端的队列映射、topic 队列数量、重试和重启恢复都需验证路由与来源信息保持稳定。重放或并发处理时，消费端仍须携带稳定来源版本拒绝旧写入；业务版本只比较同一记录的新旧，记录迁移时须保持版本连续性。

分区数、队列选择、并发消费线程和 topic/tag 的实际配置属于待确认实现项；不能直接照搬参考文档的固定分区下标或自动获取分区数示例。

### 4. 从 binlog 到 RocketMQ

#### 4.1 读取和解析

Server 通过复制账号以复制客户端方式连接 MySQL，读取 binlog 并解析已提交的数据变更。先根据库表过滤规则确定同步范围，再识别新增、更新、删除及对应行数据；binlog 还包含事务标记、DDL（数据定义语句，如修改表结构）等事件，不能把所有事件都当作行增删改。

Canal 使用 Entry 表示解析事件：Header 保存库表、事件类型和日志坐标等头部信息，行变更内容由 RowChange/RowData 表示。原生 MQ 工作线程随后从嵌入式 Server 获取待确认批次，按消息格式和分区规则封装并发布；日志解析和 MQ 发布是前后两个处理阶段，读到事件不表示已发布成功。

下游内部事件契约至少需要以下信息，原生消息能否提供须按后面的格式边界核对：

- destination、库名和表名；
- INSERT、UPDATE、DELETE 事件类型；
- 主键、变更前后行数据和事件时间；
- 事务标识、binlog 文件与事件偏移、源行序号、Canal batchId；
- 可用于消息幂等和追踪的来源信息。

上面列的是内部处理所需信息，不代表每种原生封装都带有全部字段。消息格式必须先明确：

| 原生格式 | 解析与字段边界 |
| --- | --- |
| `canal.mq.flatMessage=true`，官方默认 | JSON `FlatMessage`，按 `data`/`old` 解析；`data` 为 INSERT/UPDATE 的后值或 DELETE 的前值，UPDATE 的 `old` 只收集被标记为变化的列，不是完整前镜像。带 `gtid`，但没有 binlog 文件、偏移和原始行序号。 |
| `canal.mq.flatMessage=false` | 使用 Canal Packet/Messages 二进制封装，解析 Entry 与 RowChange/RowData；Header 可保留日志文件和偏移。启用按键分区时，源码将每行重建为单行 Entry，保留 Header，但不附加拆分前行序号，并过滤事务开始/结束 Entry。 |

默认 JSON 不能按 Entry 二进制解析。业务版本来自新表行字段，须核对 INSERT/UPDATE 后值与 DELETE 前值中的 `version`，并验证多行拆分后数据和版本仍正确对应。若另用源坐标构造事件 ID，仍须在信息丢失前补齐原始行序号等字段；MQ messageId、batchId 或拆分后下标不能代替稳定源身份。

消息数、Entry 数和 ES 文档数各不相同。事务标记只有实际保留时才能用于上下文，不能把分区后的消息当作完整源事务。DDL、TRUNCATE、主键变更及未知事件须明确暂停或处置；涉及多个分区的 DDL 还须协调隔离，不能只处理队列 0 就让其他队列继续使用旧结构。

#### 4.2 RocketMQ 消息封装

下面是统一内部事件的候选契约，008 沿用相同字段。`sourceVersion` 是内部字段名，本例取自 UPDATE 后值 `after.version`；`101` 仅为示例值，不是 binlog 坐标编码结果。`eventId` 与 `source` 是可选的坐标追踪示例，采用时须补齐原生消息缺失的字段；不采用坐标事件 ID 时可省略这两项。示例不代表原生 Canal 消息已直接提供全部字段：

~~~json
{
  "eventId": "<destination>|<instanceUuid>|<binlogFile>|<eventOffset>|<rowOrdinal>",
  "sourceVersion": 101,
  "destination": "db0",
  "database": "eas",
  "table": "consume_record_01",
  "eventType": "UPDATE",
  "consumeId": "<business-id>",
  "orderKey": "db0|eas|consume_record_01|<business-id>",
  "before": {},
  "after": { "version": 101 },
  "source": {
    "instanceUuid": "<source-instance-uuid>",
    "binlogFile": "mysql-bin.000123",
    "eventOffset": 456789,
    "rowOrdinal": 0,
    "gtid": "<transaction-gtid>"
  },
  "canalBatchId": 12345,
  "eventTime": "<source-event-time>"
}
~~~

INSERT/UPDATE 的业务版本按新表字段 `version` 规则传递，重放时保持不变；DELETE 事件保留删除前信息，但不触发 ES 逐条 Delete，ES 删除由 ILM 按索引保留策略管理。业务版本按同一记录比较，不要求跨记录或跨库全局递增；源库迁移和数据重建须保留版本连续性，不能重置后沿用旧文档身份。binlog 坐标用于日志追踪或可选事件身份，batchId 只用于批次追踪，GTID 标识事务。ILM 策略、每半小时自动校验与兜底补偿由下游实现并验证。

业务版本用于判断同一记录的新旧。INSERT/UPDATE 需要去重时，可用“业务主键 + 业务版本”；若使用源坐标事件 ID，则另行补齐来源实例、文件、事件位置与原始行序号。DELETE 事件身份用于消息契约、对账或状态判断，不与 ES 逐条删除版本绑定。orderKey 是内部状态键，consumeId 作为 ES `_id`，队列由 partitionHash 的真实源字段决定；版本维护、冲突与恢复见 009。

#### 4.3 原生 RocketMQ 投递与确认边界

当前由 Canal Server 的内置生产端发布，无独立 TCP 投递组件。按 1.1.7 源码，[CanalMQStarter](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/CanalMQStarter.java) 为每个 destination 使用内部 `clientId=1001`，从嵌入式 Server 调用 `getWithoutAck`；[CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java) 等待本轮分区发送任务结束，无异常时 `callback.commit()` 调用内部 `ack`，异常时 `callback.rollback()` 调用内部 `rollback`。这些是原生模式内部路径，不能套用独立 TCP 客户端的连接和确认假设；此 clientId 也不是 MySQL 复制 slaveId。

**发送状态存在必须核验的风险：** 单条和批量 `sendMessage` 都取得 `SendResult`，但只记录调试日志，没有显式判断 `getSendStatus()==SEND_OK`。因此，发送调用返回非 `SEND_OK` 且没有抛异常时，仍可能走提交回调；不能把回调描述为“已验证所有消息可靠落盘”。须核对实际发行包、其内置 RocketMQ 客户端版本、Broker 刷盘复制与异常状态处理，必要修正属于待确认实施项。项目业务消费端的 client 5.2.0 不能自动证明 Server 内置依赖也是该版本。

一个发布批次可拆为多个分区消息，某些分区发送成功后其他分区异常，会回滚 Canal 内部批次并可能重发已成功消息；这不会撤回已经进入 MQ 的消息，超时也可能是结果不明。稳定事件身份、重复处理与旧事件拒绝必须覆盖这些情形。

RocketMQ 生产成功、Broker 持久化、Canal 位点持久化、MQ 消费确认和 ES 写入是不同阶段。不能因为某条消息已经出现在 Dashboard，或 ES 已经写入，就反推出 Canal 位点已经可靠持久化。重启恢复可能重复投递，ES 写入服务须保留稳定来源版本，更新和删除按原始 `create_time` 计算具体月份索引，并按幂等规则处理；

**可靠交接后的留存边界：** Canal 不等待 ES 完成才推进内部消费游标。事件交给 MQ 后，MQ 留存与容量须覆盖下游暂停、修复和追赶；消费落后不能当作消息不会过期的保证。若所需消息已过期，不能因源库仍有 binlog 就认定正常重启能补回，须先复核 Canal 位点、MQ 缺口和受控重放范围。Broker 版本、留存参数及这类恢复流程尚未验证。

#### 4.4 Canal 批次与 MySQL 事务边界

Canal 一次拉取并等待确认的一组事件属于内部批次，`batchId` 只用于追踪这次批次，不等于 MySQL 事务编号，也不等于 RocketMQ 消息批次。一个 MySQL 事务可以包含多个行事件，也可能在分区或逐行拆分后形成多条 MQ 消息；一条 MQ 消息是否只包含一个事务，必须结合实际封装实现确认。

如果下游需要按事务范围恢复或校验，应保存实际收到的消息及其中的 Binlog 事件，并依据消息中实际保留的事务开始、提交标记或其他可靠边界组装处理范围。事务边界没有确认完整前，不能把单条消息或一次确认直接当作整笔事务已经保存。

分区或逐行拆分还可能使一笔事务的事件分散到多个队列。下游必须按消息实际携带的来源信息处理重复和乱序，不能仅凭消息顺序推断数据库事务顺序。

## 待确认事项

Binlog 到 RocketMQ 的转换、分区和发送实现见 [05_4 Binlog发送到RocketMQ核心实现](<./007_ES写入链路_Canal Server_05_4_Binlog发送到RocketMQ核心实现.md>)；本页继续说明发送后的事务、确认与恢复边界。

- topic、分区数、队列映射、消息格式和 `partitionHash` 源列是否稳定，仍需结合实际配置验证。
- 事务开始/提交事件是否保留、部分发送失败后的恢复顺序和非 `SEND_OK` 处理仍需运行验证。

## 关联文档

- [进度确认与单机恢复](<./007_ES写入链路_Canal Server_08_进度确认与单机恢复.md>)
- [RocketMQ 消费模型](<./008_ES写入链路_RocketMQ_RocketMQ 消费模型.md>)