# 007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

当前版本基线：Java 8、Spring Boot 2.7.18、`rocketmq-spring-boot-starter` 2.3.0、`org.apache.rocketmq:rocketmq-client` 5.2.0、Canal Server 1.1.7、MySQL 8.0.33 和 Elasticsearch 服务端 7.17.15。5.2.0 是 MQ 客户端版本，Broker 版本未确认。

## 文档维护与已确认规则

- 本文说明 Canal Server 读取、解析、发布 RocketMQ 消息和维护消费进度的职责；RocketMQ 消费和 ES 写入见 008。当前采用 `canal.serverMode=rocketMQ`，不再部署独立 Canal-to-MQ 投递组件。
- 项目包含两个独立 MySQL 实例，对应 db0、db1 两个 destination。两个通道分别维护源库、过滤规则、导入前 GTID 基线和消费进度，不共用起点，也不承诺跨库全局顺序。
- 当前部署方式以 [999 使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md:1>) 为参考，保持单机部署方式一致：1 台主机上的 1 个 Canal Server 进程承载 db0、db1 两个 destination。当前不纳入 ZooKeeper 高可用方案，不接入 ZooKeeper，也不配置自动主备切换。
- 当前使用 file-instance.xml 本地位点模板；两个通道分别维护本地持久化进度。单机故障后人工核对并重启恢复，本地文件丢失、损坏或日志断档时暂停复核，不能跳到最新 GTID。
- 当前使用本地 canal.properties 和各 destination 的 instance.properties，不使用 canal-admin、管理库或集中配置管理。
- Canal Server 内置 RocketMQ 生产端负责把事件发布到 topic；消息拆分、发送确认、部分失败、超时重试和位点持久化语义必须按 Canal Server 1.1.7 实际发行包核验。MQ 发送成功不等于 ES 成功，也不能把 MQ 或 ES 成功直接当作 Canal 位点已持久化。
- `batchId` 只用于本次批次追踪，不能充当跨重连、重新分批的稳定事件 ID；消息 `keys` 只用于检索和追踪，不能替代有序发送、顺序消费、重试保序与幂等。已确认顺序性要求是防止同一条消费记录的旧变更覆盖新结果，不在消费记录表新增版本号字段。
- MySQL binlog 保留期目标为 30 天；首次同步使用 006 记录的导入前 GTID 基线，正常恢复优先使用已确认并持久化的消费进度。位点断档或无法核实时暂停复核，不能跳到最新 GTID。
- Canal Server 原生 RocketMQ 模式的消息拆分、发送失败和恢复行为仍需按 1.1.7 实际发行包核验；不能用 TCP 客户端的 `getWithoutAck`、`ack` 或 `rollback` 语义替代原生生产模式的验证。内部 `rollback` 回滚 Canal 批次，不会撤回已发送到 MQ 的消息。
- 配置、流程图和代码均为说明性内容，未表示已经部署、联调或完成故障演练。
- 版本源码核对必须区分 FlatMessage JSON、二进制 Entry 和消费端内部事件契约；源坐标与行序号不能从示例反推为原生消息已提供的字段。发送回调、内存游标和持久化游标也须分别说明。
- 面试口述按 Server 的“读取日志、解析变更、发布消息、维护进度”先总后分，四个分点与整体概括一一对应，再补充单机恢复边界；整体概括中的拓扑只说明一个 Server 进程承载 db0、db1 两个通道。下游索引定位、ES 写入和消费确认在 008 展开；消息 keys、稳定事件 ID、业务顺序键和源日志进度须分别解释，不能混用。

## 面试口述精炼版本

### 整体概括

Canal Server 主要做四件事：**读取日志、解析变更、发布消息、维护进度**。它以 MySQL 复制客户端的方式读取 binlog（二进制日志），解析已提交变更，通过 [内置 RocketMQ 生产端](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）.md:197>) 发布消息；ES 写入由下游自定义服务负责。

项目用一个单机 Server 进程承载 db0、db1 两个通道，各自维护配置与进度；实际发布、位点落盘和恢复仍待验证。

### 读取日志

每个 destination（独立同步通道）读取所属源库的 16 张分表。[公共配置](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）.md:82>)选择 RocketMQ 模式和本地位点模板，各 [instance.properties](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）.md:107>) 绑定源库、复制账号、表过滤与起点。

首次使用 006 保存的导入前 GTID（全局事务标识）基线，后续重启核对本通道已持久化的消费进度；不能用首次基线或最新 GTID 覆盖已有进度。

### 解析变更

Server 解析库表、主键、事件类型和行数据，再封装消息。INSERT 使用后值，UPDATE 区分前后值，DELETE 使用前值；DDL（修改表结构等数据定义语句）及特殊事件须单独处置。

默认 FlatMessage（扁平 JSON 消息）的 UPDATE `old` 不保证完整前镜像，且缺少完整源坐标；二进制按键拆行也不保留原行序号。稳定事件 ID 所需信息须补齐并验证。

### 发布消息

发布时让同一条消费记录的变更进入稳定队列。`partitionHash` 按真实源字段选择分区，还须核对 topic、分区数与队列映射在重试和重启后保持稳定；下游顺序消费见 008。

一个批次可拆成多个分区消息，部分成功后失败可能重发。按键分区只提供队列路由，消费端仍须处理重复和旧事件，不能据此认定全链路已保序。

### 维护进度

进度分为读取位置、内部确认游标和本地持久化位点。1.1.7 发送路径未抛异常时，回调执行内部 `ack`（确认批次）；异常时执行 `rollback`（回滚批次），已发到 MQ 的消息不会撤回。

内部确认不证明位点已落盘或 ES 已完成。源码没有显式检查 `SEND_OK` 发送状态，回调也不能单独证明可靠交接；发送状态与本地落盘须按发行包验证。

### 单机重启与恢复

单机故障会同时中断两个通道的采集与发布。恢复时分别核对 db0、db1 的来源、配置、本地位点和日志连续性，再人工重启；位点丢失或日志断档时暂停复核，不能跳到最新 GTID。

当前不配置自动主备切换。重启后还须核对发布恢复与积压追赶，下游按 008 处理重复和旧事件；恢复效果仍待故障演练。

## 详细方案

### 参考文章的吸收与边界

本文所说的参考文章是 [999 使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md:1>)。文章展示了 Canal Server 按库表过滤 binlog，并通过内置 RocketMQ 模式解耦 ES 写入的做法。007 采用其单机 Server 部署、Server 内置生产、`partitionHash` 按业务键路由和下游自定义消费思路；topic、队列数、批量大小、发送确认和部署脚本仍需按 Canal Server 1.1.7、实际 Broker 和项目客户端核验。

参考文档是 Canal 1.1.6 与 RocketMQ 4.9.4 的单机演示，没有展示 ZooKeeper 主备高可用部署。当前项目的 Canal Server 同样采用单机方式，并按两个独立 MySQL 实例配置 db0、db1 两个 destination；版本、过滤范围和首次 GTID 基线仍按项目规则核对。示例中的本地 Windows 路径、`canal.mq.partition`、固定 topic 和 Dashboard 启动结果均不代表当前环境已经配置或验证。

单机部署不具备自动主备接管能力。Server 故障后需要人工核对本地进度并重启，详细恢复方案见第 5 节；不能把参考文章的同步演示描述成高可用验证。

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
# 最终格式及稳定源坐标方案尚待确认，不能只切换开关就认为字段齐全
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

#### 3.1 消息分区与同主键顺序

消费端候选 `orderKey` 为 `destination + database + table + consumeId`，用于内部顺序状态；它不是原生生产端直接拼接的 hash 字符串。1.1.7 的 `partitionHash` 按库表规则匹配源列，DELETE 使用删除前列，INSERT/UPDATE 使用变更后列；开启 `canal.mq.database.hash` 时还混入库名。topic、字段值、分区数和队列列表稳定，才可核对同键是否始终到同一队列。消费端使用 ORDERLY（顺序消费）并同步等待 ES 结果；Tag 只做过滤。

上面的正则覆盖 16 张表，旧示例只配置 `consume_record_01` 会让未匹配表走默认分区。`<source-key-column>` 须替换为真实源字段并核对 INSERT、UPDATE、DELETE 均有值，不能把 ES 的驼峰字段直接当作 MySQL 列名。分区数必须明确为正数，并核对与实际 topic 队列列表的映射；不能只写 `partitionHash` 就宣称已实现业务键分散到多个队列。

同一 destination 同时只启动一个 Server 读取和发布，不能在旧进程仍运行时另起重复进程。原生生产端的顺序队列映射、topic 队列数量、重试和重启恢复都需验证不能破坏同键顺序。跨 destination、跨队列重放仍须由消费端拒绝旧事件，不能仅靠 MQ 顺序消费。

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

默认 JSON 不能按 Entry 二进制解析；切换为二进制也不能自动满足下面的稳定事件 ID 契约。若继续使用“源坐标 + 原始行序号”，须在信息丢失前可靠补齐并传递；若改用其他身份规则，须另行确认并覆盖多行、重复、重新分批与重放。单靠消费端不能恢复已经丢失的信息。

消息数、Entry 数和 ES 文档数各不相同。事务标记只有实际保留时才能用于上下文，不能把分区后的消息当作完整源事务。DDL、TRUNCATE、主键变更及未知事件须明确暂停或处置；涉及多个分区的 DDL 还须协调隔离，不能只处理队列 0 就让其他队列继续使用旧结构。

#### 4.2 RocketMQ 消息封装

下面是 ES 写入服务所需的统一内部事件契约，008 沿用相同字段。源信息必须由上游封装与消费端共同满足；默认 JSON 与二进制按键拆行均存在上述字段缺口，不能仅靠消费端解析实现。这是待实现方案，不代表当前 Canal 消息已直接提供所有字段：

~~~json
{
  "eventId": "<destination>|<sourceUuid>|<binlogFile>|<eventOffset>|<rowOrdinal>",
  "destination": "db0",
  "database": "eas",
  "table": "consume_record_01",
  "eventType": "UPDATE",
  "consumeId": "<business-id>",
  "orderKey": "db0|eas|consume_record_01|<business-id>",
  "before": {},
  "after": {},
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

稳定 eventId 取自来源实例和 binlog 行事件坐标；rowOrdinal 是该源行事件内的行序号，不能使用 MQ 分批后的列表下标。batchId 在重连或重新分批时可能变化，只留作追踪。若改用 GTID 标识，也需补齐事务内事件与行序号，单个 GTID 不能唯一标识每一行。发生源库替换、日志重置或重新生成 binlog 时，必须复核来源身份与坐标规则，不能假定文件名永不复用。

eventId 标识重复事件，orderKey 用于消费端隔离同键并维护顺序状态，consumeId 作为 ES `_id`；原生生产队列由 partitionHash 的真实源字段决定，三者用途不同。顺序比较不能按 GTID 字符串或事件时间大小直接判断，需定义同一来源内文件序号、事件偏移与行序号的比较规则；多来源或路由变化的规则另行确认。顺序控制使用 binlog 来源位置，不在消费记录表新增版本号字段；消费端需可靠保存同一记录已处理的来源位置，拒绝旧变更覆盖新结果，相关实现与验收见 008。

#### 4.3 原生 RocketMQ 投递与确认边界

当前由 Canal Server 的内置生产端发布，无独立 TCP 投递组件。按 1.1.7 源码，[CanalMQStarter](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/CanalMQStarter.java) 为每个 destination 使用内部 `clientId=1001`，从嵌入式 Server 调用 `getWithoutAck`；[CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java) 等待本轮分区发送任务结束，无异常时 `callback.commit()` 调用内部 `ack`，异常时 `callback.rollback()` 调用内部 `rollback`。这些是原生模式内部路径，不能套用独立 TCP 客户端的连接和确认假设；此 clientId 也不是 MySQL 复制 slaveId。

**发送状态存在必须核验的风险：** 单条和批量 `sendMessage` 都取得 `SendResult`，但只记录调试日志，没有显式判断 `getSendStatus()==SEND_OK`。因此，发送调用返回非 `SEND_OK` 且没有抛异常时，仍可能走提交回调；不能把回调描述为“已验证所有消息可靠落盘”。须核对实际发行包、其内置 RocketMQ 客户端版本、Broker 刷盘复制与异常状态处理，必要修正属于待确认实施项。项目业务消费端的 client 5.2.0 不能自动证明 Server 内置依赖也是该版本。

一个发布批次可拆为多个分区消息，某些分区发送成功后其他分区异常，会回滚 Canal 内部批次并可能重发已成功消息；这不会撤回已经进入 MQ 的消息，超时也可能是结果不明。稳定事件身份、重复处理与旧事件拒绝必须覆盖这些情形。

RocketMQ 生产成功、Broker 持久化、Canal 位点持久化、MQ 消费确认和 ES 写入是不同阶段。不能因为某条消息已经出现在 Dashboard，或 ES 已经写入，就反推出 Canal 位点已经可靠持久化。重启恢复可能重复投递，ES 写入服务必须依赖稳定事件 ID、来源顺序、原物理索引和幂等处理。

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
5. 核对两个通道的发布恢复、MQ 积压追赶和 ES 实际结果，下游按稳定事件 ID、来源顺序与原物理索引处理重复和旧事件。

#### 5.3 主机与文件故障边界

本地位点依赖保存文件的主机和存储。主机或磁盘故障导致位点不可读时，需要先核对能否恢复原进度及必要元数据；不能在另一台主机直接按最新 GTID 启动，也不能默认退回导入前基线。备份、文件恢复和人工迁移步骤仍需实现依据与演练，当前方案不承诺自动切换、零中断或零丢失。

### 6. 监控与异常边界

建议监控单机 Server 及 db0、db1 两个通道：

- Server 进程存活、两个 destination 的连接状态和重启次数；
- binlog 读取延迟、Server 内置生产端到 RocketMQ 的发布延迟和积压；
- RocketMQ 发送失败、重试次数、消息重复和位点恢复；
- Canal Server 本地位点保存状态、文件可读性和存储空间；
- MySQL binlog 剩余保留窗口。

日志记录不等于告警已送达，进程启动成功也不等于位点已经连续恢复。涉及本地位点丢失或损坏、binlog 过期、消息格式异常或持续投递失败时，应暂停对应 destination 并人工处理。

### 7. Server 侧验收

验收范围包括：

1. 分别验证 db0、db1 的 MySQL 连接、GTID 起点和 16 张表过滤范围。
2. 分别核对 FlatMessage JSON 和二进制 Entry 的实际封装、UPDATE 前值范围、按键拆行后的源行身份及事务标记缺失；在最终格式中验证消息解析、DDL 隔离、字段转换、发送确认和位点推进。
3. 注入部分发送成功、非 SEND_OK、超时结果不明、Broker 重启和 Server 重启，验证稳定 eventId、重放、位点恢复和消费端保序。
4. 模拟 Server 进程故障及本地位点丢失、损坏或不可读，核对两个通道的中断范围、人工恢复、本地持久化位点与日志连续性、重复投递和积压追赶；无法核对可靠起点时暂停，不能直接跳到最新位置。
5. 核对 MySQL binlog 保留窗口能覆盖暂停、修复和追赶时间。
6. 将代表性 INSERT、UPDATE、DELETE 事件交给 008 的 ES 写入服务，验证端到端结果。

上述内容是待执行验收要求，不代表当前环境已经部署或测试通过。

### 8. 版本核对依据与边界

- [Canal 1.1.7 canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties)及 [instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties)：已静态核对配置键、GTID 属性和默认 `file-instance.xml` 入口；[file-instance.xml](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/spring/file-instance.xml) 的本地落盘与恢复行为仍须按实际发行包核验。
- [Canal 1.1.7 Message](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/Message.java)、[CanalEntry](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/CanalEntry.java)及 [MysqlEventParser](https://github.com/alibaba/canal/blob/canal-1.1.7/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/mysql/MysqlEventParser.java)：消息模型、行事件解析和 slaveId 生成路径；原生 RocketMQ 生产路径仍需结合发行包核验。
- [CanalMQStarter](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/CanalMQStarter.java)及 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)：内部 clientId、空过滤表达式订阅、提交/回滚回调、分区发送及 SendResult 状态检查缺口。
- [FlatMessage](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/FlatMessage.java)、[MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java)及 [CanalMessageSerializerUtil](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/util/CanalMessageSerializerUtil.java)：JSON 字段、二进制封装、UPDATE old 范围、hash 字段及拆行后信息边界。
- [RocketMQ Spring 2.3.0 RocketMQTemplate](https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.0/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/core/RocketMQTemplate.java)及 [rocketmq-client 5.2.0 SendStatus](https://github.com/apache/rocketmq/blob/rocketmq-all-5.2.0/client/src/main/java/org/apache/rocketmq/client/producer/SendStatus.java)：有序发送签名和结果状态。

以上为已有官方版本源码的静态核对及待核验模板入口，未验证实际发行包、依赖树、MySQL 8.0.33 认证、本地位点落盘或单机重启恢复。
