# 007_ES写入链路_Canal Server_01_日志读取与变更解析

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。本文是 Canal Server 写入链路的拆分总览；三个独立文档分别说明日志读取与解析、RocketMQ 发布与事务边界、进度确认与单机恢复。设计、实现和验证状态继续按原文边界说明。


## 适用规则

本页沿用 [Canal Server 总览](<./007_ES写入链路_Canal Server_Canal Server 处理逻辑.md>) 的“已确认规则”；本页仅展开当前知识点直接需要的处理过程、边界和待验证事项。

## 面试口述精炼版本

### 读取与解析

Canal Server 以 MySQL 复制客户端读取各 destination 的 binlog，按库表规则筛选事件，再把 INSERT、UPDATE、DELETE 和事务、DDL 等事件解析为 Entry。INSERT/UPDATE 使用后值，DELETE 使用前值；消息格式能提供的坐标和前镜像范围仍需按实际发行包核对。

## 详细方案

### 1. 部署拓扑

~~~mermaid
flowchart LR
    DB0[(MySQL db0)] --> S[单机 Canal Server]
    DB1[(MySQL db1)] --> S
    S -->|内置 RocketMQ 生产端| MQ[(RocketMQ)]
    MQ --> C[RocketMQ 消费与 ES 写入服务]
~~~

图中是 1 台主机上的 1 个 Server 进程，同时加载 db0、db1 两个 destination。两个通道分别维护连接、过滤规则和本地进度，但共享该进程的故障影响；不配置 ZooKeeper 或自动主备切换。单机承载两个通道的容量与重启恢复仍待验证。

### 2. 公共配置示意

以下地址、账号、密码和端口都是占位值，不能直接用于生产：

~~~properties
canal.serverMode=rocketMQ
# 单机部署，ZooKeeper 地址保持为空
canal.zkServers=
canal.destinations=db0,db1
canal.auto.reset.latest.pos.mode=false
canal.instance.global.spring.xml=classpath:spring/file-instance.xml

# 格式必须与消费端一致；官方默认 canal.mq.flatMessage=true
# 最终格式和业务版本传递须验证；可选坐标事件 ID 仍需补齐源信息
# canal.mq.flatMessage=false

# 以下键名已按 1.1.7 源码核对，实际加载仍待验证
rocketmq.namesrv.addr=<nameserver-address>
rocketmq.producer.group=<producer-group>
~~~

以上属性键已对照 Canal 1.1.7 的官方配置核对；官方 `canal.properties` 默认选择 `file-instance.xml`，本文沿用这一配置入口，采用本地元数据保存消费进度。实际发行包中的模板、文件路径、刷新时机、目录权限及落盘行为仍需核验；配置文件存在不等于位点已经可靠保存。

`canal.auto.reset.latest.pos.mode=false` 针对找不到已指定 binlog 位置后的自动重置路径；首次无有效检查点、且未正确配置初始基线时，仍不能据此承诺一定拒绝从最新位置初始化。启动前必须核对首次 GTID 基线或既有持久化游标，发现缺失就停止启动并复核。

### 3. destination 配置示意

~~~properties
# db0/instance.properties
canal.instance.master.address=<mysql-db0>:3306
canal.instance.dbUsername=<canal-user>
canal.instance.dbPassword=<managed-secret>
canal.instance.filter.regex=eas\\.consume_record_(0[1-9]|1[0-6])
canal.instance.gtidon=true
canal.instance.master.gtid=<db0-import-before-gtid>
canal.mq.topic=<db0-topic>
# 源字段名须以真实 DDL 核对，不直接使用 ES 字段 consumeId
canal.mq.partitionHash=eas\\.consume_record_(0[1-9]|1[0-6]):<source-key-column>
canal.mq.partitionsNum=<positive-partition-count>
# 未手工指定时使用发行包的自动生成路径；启动后核对实际复制身份
# canal.instance.mysql.slaveId=0
~~~

db1 使用自己的 MySQL 地址和导入前完整 GTID 集合；不能复制 db0 的 GTID。同一个 Server 分别加载两个 destination 的配置，并核对自动生成的复制身份不与源 MySQL 或同一复制拓扑中的其他复制客户端冲突；如改用手工值，应在同一复制拓扑内规划不同身份。`slaveId` 是 Canal 复制客户端使用的 server-id，区别于源 MySQL 实例自身的 `server-id`；它也不是 destination 名称或 MQ 内部 clientId。

Java properties 文件中的 `\\.` 解析后是正则的 `\.`。上述库表过滤候选须分别验证 01、09、10、16 命中，00、17 和其他表不命中。1.1.7 原生 MQ 工作线程使用空过滤表达式订阅嵌入式 Server，本文的表范围由 Server 侧过滤配置限定；不额外配置独立 TCP 客户端的订阅过滤。导入前 GTID 仅用于首次初始化，不能以编辑 instance.properties 的方式覆盖已有检查点或跳过异常。

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

## 参考文章与项目实现的区别

对照文章 [使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<./999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md>) 时，文章中的命令、版本、示例数据和演示结果只代表文章原始实验环境，不能直接视为当前项目已经采用或验证的实现。

### 1. 部署拓扑不同

- 文章围绕一个 `order_db.orders` 示例展开，并配置 RocketMQ 4.9.4 的 NameServer、两个 Broker 和 Dashboard；当前项目是 1 个 Canal Server 进程承载 db0、db1 两个 destination，不配置 ZooKeeper 高可用，RocketMQ Broker 版本也尚未确认。
- 文章把 Canal、RocketMQ 和 ES 8 冷热节点部署在实验环境中，包含可运行的启动命令和效果演示；当前文档只记录项目设计和配置边界，未把部署、联调或故障恢复写成已完成事实。

### 2. MySQL 与 Canal 版本及起点不同

- 文章使用 MySQL 8.0.29、Canal 1.1.6，并在单个实例配置文件中填写 binlog 文件名和 position；当前项目按 MySQL 8.0.33、Canal Server 1.1.7 说明，两个 destination 分别维护源库、过滤规则、导入前 GTID 基线和本地进度。
- 文章示例通过 `canal.instance.master.journal.name` 和 `canal.instance.master.position` 从固定日志位置启动；当前项目首次同步使用导入前 GTID 基线，已有可核对位点不能被基线或最新 GTID 覆盖，具体落盘和恢复行为仍待实际发行包验证。
- 文章的数据库用户、密码、库表和 `order_db.orders` 示例不能直接用于当前项目；当前项目的复制账号、真实源列、权限和 binlog 连续性仍需按两个源库分别核对。

### 3. 过滤、分区与消息路由不同

- 文章监听 `order_db\.orders.*`，以 `order_no` 作为 `partitionHash`，并示例使用 8 个分区和动态获取队列数；当前项目按 `eas\.consume_record_(0[1-9]|1[0-6])` 的 16 张分表配置候选范围，每个 destination 使用自己的 topic、源字段和正数分区数，是否稳定路由仍待验证。
- 文章的分区示例用于说明同一订单进入同一队列；当前项目不能直接把 ES 字段名或 `consumeId` 当作 `partitionHash` 源列，必须以真实 MySQL DDL 核对 INSERT、UPDATE、DELETE 的取值，并分别核对两个 destination 的队列映射。
- 文章示例把配置和运行结果放在同一实验链路中；当前项目采用 Server 内置 RocketMQ 生产端，不另设独立 TCP 投递组件，MQ 工作线程的订阅、批次确认和重试边界仍按 Canal 1.1.7 发行包核验。

### 4. Binlog、消息格式与 ES 版本不同

- 文章示例重点展示数据从 Canal 进入 RocketMQ、再写入 ES 8，并使用 ES 8.4.2 客户端和冷热节点 ILM；当前项目的 ES 服务端是 7.17.15，ES 写入、索引定位和 ILM 规则由其他文档负责，本页只说明 Canal 侧读取和解析，不能套用文章的 ES 8 API、Mapping 或冷热节点参数。
- 文章示例没有提供当前项目所需的 16 张分表、业务 `version`、GTID 基线和来源版本边界；当前项目需要区分 INSERT/UPDATE 后值、DELETE 前值、事务与 DDL 事件，并核对 FlatMessage JSON 或二进制 Entry 实际保留的字段。
- 文章中的“消息已发送并展示效果”属于文章实验结果；当前项目尚未用实际发行包生成消息样例，不能据此确认完整源坐标、原始行序号、事务边界或发送结果已经可靠传递。

## 待确认事项

- 首次 GTID 基线、过滤正则、复制身份及实际行镜像是否与配置一致，仍需在两个源库分别验证。
- FlatMessage JSON 和二进制 Entry 的字段边界、事务事件和拆行后的源行身份仍需用实际消息样例确认。

## 关联文档

- [Canal Server 总览](<./007_ES写入链路_Canal Server_Canal Server 处理逻辑.md>)
- [RocketMQ 消息发布与事务边界](<./007_ES写入链路_Canal Server_02_RocketMQ消息发布与事务边界.md>)
