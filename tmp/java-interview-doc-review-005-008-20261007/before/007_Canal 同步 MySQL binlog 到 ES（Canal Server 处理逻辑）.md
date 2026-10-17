# 007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准：Java 8、Spring Boot 2.7.18、`rocketmq-spring-boot-starter` 2.3.0、`org.apache.rocketmq:rocketmq-client` 5.2.0、Canal Server 1.1.7、MySQL 8.0.33 和 Elasticsearch 服务端 7.17.15。5.2.0 是 MQ 客户端版本，Broker 版本未确认。

## 文档维护与已确认规则

- 本文说明 Canal Server 读取、解析、提供 TCP 批次和维护消费进度的职责，以及自定义 Canal-to-MQ 投递组件的交接边界；RocketMQ 消费和 ES 写入见 008。`canal.serverMode=tcp` 时，向 MQ 发消息的是投递组件。
- 项目包含两个独立 MySQL 实例，对应 db0、db1 两个 destination。两个通道分别维护源库、过滤规则、导入前 GTID 基线和消费进度，不共用起点，也不承诺跨库全局顺序。
- 已确认部署规模为 3 台不同主机上的 Canal Server，并接入 3 节点 ZooKeeper 集群。ZooKeeper 按 destination 协调运行资格；一个 destination 的切换不强制另一个同时切换。
- 当前使用本地 canal.properties 和各 destination 的 instance.properties，不使用 canal-admin、管理库或集中配置管理。
- 投递组件须在一个 Canal 批次的全部行事件取得明确的 MQ 发送成功结果后，才向 Server ack；部分失败和超时结果不明均不能提前确认。MQ 发送成功不等于 ES 成功，客户端 ack 返回也不证明 Server 已处理或位点已持久化。
- `batchId` 只用于本次批次追踪，不能充当跨重连、重新分批的稳定事件 ID；消息 `keys` 只用于检索和追踪，不能替代有序发送、顺序消费、重试保序与幂等。
- MySQL binlog 保留期目标为 30 天；首次同步使用 006 记录的导入前 GTID 基线，正常恢复优先使用已确认并持久化的消费进度。位点断档或无法核实时暂停复核，不能跳到最新 GTID。
- 配置、流程图和代码均为说明性内容，未表示已经部署、联调或完成故障演练。

## 参考文章的吸收与边界

参考文档展示了 Canal 按库表过滤 binlog，并通过 MQ 解耦 ES 写入的做法。007 吸收其职责拆分和同主键保序思路，但当前选择 TCP 模式，由自定义投递组件发 MQ；不照搬 Server 内置 MQ 模式的配置。topic、队列数、批量大小和部署脚本仍需按客户端 5.2.0、实际 Broker 和 Canal Server 1.1.7 核验。

参考文档是 Canal 1.1.6 与 RocketMQ 4.9.4 的单机演示，未覆盖当前项目的两个独立 destination、3 台 Server、3 节点 ZooKeeper 和按 destination 独立接管。示例中的本地 Windows 路径、`canal.mq.partition`、固定 topic 和 Dashboard 启动结果均不代表当前环境已经配置或验证。

## 面试口述精炼版本

### 整体职责

Canal Server 的职责是把 MySQL 已提交事务的 binlog 可靠地变成可消费消息。它不负责 Elasticsearch Mapping，也不直接承担 ES 写入。

项目为两个独立 MySQL 实例配置 db0、db1 两个 destination。Server 通过 ZooKeeper 竞争 destination 的运行资格，取得资格后连接对应 MySQL，读取和解析 binlog，按批次交给 Canal-to-MQ 投递组件。投递组件把事件发送到 RocketMQ，发送成功后才向 Server ack。

### 配置与启动

3 台 Server 使用相同版本和公共配置，每台配置自己的可达注册地址，并接入同一个 ZooKeeper 集群。每个 destination 的 instance.properties 配置 MySQL 地址、复制账号、过滤规则和 GTID 起点；复制身份按发行包生成并核对，不能把同一固定 slaveId 复制给所有候选节点。

db0、db1 的 GTID 起点和消费进度必须分开维护。Server 运行资格、位点和故障接管按 destination 独立处理。

### 消息处理和确认

Server 提供的一个 Message 包含多个 Entry，行事件 Entry 还可能包含多行 RowData。投递组件逐行转换事件，保留稳定来源坐标和顺序信息；以一致的顺序键选择 MQ 队列，按源顺序发送。全部发送结果达到成功标准后，才 ack 原 Canal 批次。

发生部分发送失败或超时结果不明时，暂停本通道后续投递并重试，重放可能再次发送成功项。发送 API 没抛异常不等于成功，还须检查 SendStatus；消息 keys 不会自动保序或去重。这个 ack 只确认 Canal 到 MQ 的交接，不代表 ES 已经写成功。

### 高可用与恢复

本文选用 default-instance.xml 组装内存与 ZooKeeper 元数据管理器。Server 处理消费 ack 后先推进内存游标，再周期持久化；临时 ZooKeeper 断连不等于立即释放资格，须区分会话过期、运行节点删除和主动释放。备用 Server 取得资格并隔离旧活动节点后，再核对持久化位点和源日志并接续。

恢复过程中可能重复投递，因此下游必须使用稳定事件 ID、原物理索引和顺序保护。`canal.auto.reset.latest.pos.mode=false` 限制找不到既定日志位点时自动跳到最新，但不能代替启动前的基线与检查点核对。

## 详细方案

### 1. 部署拓扑

~~~mermaid
flowchart LR
    DB0[(MySQL db0)] --> S[Canal Server 集群]
    DB1[(MySQL db1)] --> S
    Z[ZooKeeper 3 节点] -.协调 destination 运行资格.-> S
    S -->|TCP 批次| P[Canal-to-MQ 投递组件]
    P -->|发送全部行事件| MQ[(RocketMQ)]
    P -->|全部发送成功后 ack 批次| S
    MQ --> C[自定义 ES 消费服务]
~~~

图中 Server 集群表示 3 台候选节点；同一 destination 同一时间只应有一个活动节点，两个 destination 可以由同一台或不同 Server 运行。

### 2. 公共配置示意

以下地址、账号、密码和端口都是占位值，不能直接用于生产：

~~~properties
canal.serverMode=tcp
canal.zkServers=zk1:2181,zk2:2181,zk3:2181
canal.destinations=db0,db1
canal.auto.reset.latest.pos.mode=false
canal.instance.global.spring.xml=classpath:spring/default-instance.xml
~~~

以上属性键已对照 Canal 1.1.7 的官方配置与 Spring 模板核对；实际加载路径、地址和生效结果尚待部署验证。官方默认启用的是 file-instance.xml，本文为了与 ZooKeeper 位点说明一致，显式选用 default-instance.xml，其中的 PeriodMixedMetaManager 组合内存与 ZooKeeper 元数据管理。仅配置 zkServers 不能证明消费位点已经按此机制持久化。

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
# 未手工指定时使用发行包的自动生成路径；启动后核对实际复制身份
# canal.instance.mysql.slaveId=0
~~~

db1 使用自己的 MySQL 地址和导入前完整 GTID 集合；不能复制 db0 的 GTID。每个 Server 均须部署两个 destination 的一致配置，并核对自动生成的复制身份不与连接同一 MySQL 的其他复制客户端冲突；如改用手工值，应为可能连接同一源库的客户端规划不同身份。`slaveId` 不是 MySQL 的 `server-id`，也不是 destination 名称。

Java properties 文件中的 `\\.` 解析后是正则的 `\.`。上述库表过滤候选须分别验证 01、09、10、16 命中，00、17 和其他表不命中；Server 与客户端订阅过滤都要核对。导入前 GTID 仅用于首次初始化，不能以编辑 instance.properties 的方式覆盖已有检查点或跳过异常。

#### 3.1 消息分区与同主键顺序

候选顺序键为 `destination + database + table + consumeId`，前提是同一记录的源路由稳定。RocketMQ 的 `keys` 属性用于检索和追踪，不参与默认队列选择，也不自动去重。生产端须调用 RocketMQTemplate.syncSendOrderly 并传入该 hashKey，或显式使用 MessageQueueSelector；消费端须使用 ORDERLY 模式并等待本键 ES 处理完成。Tag 只做过滤，不是顺序队列。

每个 destination 的投递应有单活动实例和明确的互斥、隔离规则，按 binlog 源顺序发送，前一事件失败时不能继续发送同键后续事件。顺序队列映射、topic 队列数量和发送选择器需保持稳定；扩缩容、自动重试换队列、补发及故障切换都需验证不能破坏顺序。跨 destination、跨队列重放仍须由消费端拒绝旧事件，不能仅靠 MQ 顺序消费。

分区数、队列选择、并发消费线程和 topic/tag 的实际配置属于待确认实现项；不能直接照搬参考文档的固定分区下标或自动获取分区数示例。

### 4. 从 binlog 到 RocketMQ

#### 4.1 读取和解析

Server 通过复制账号以从库方式连接 MySQL，读取已经提交的 binlog。解析结果至少应保留：

- destination、库名和表名；
- INSERT、UPDATE、DELETE 事件类型；
- 主键、变更前后行数据和事件时间；
- 事务标识、binlog 文件与事件偏移、源行序号、Canal batchId；
- 可用于消息幂等和追踪的来源信息。

具体字段取决于 Canal 消息模型、MySQL 表结构和真实事件。转换组件应逐个解析 Message 的 Entry 和 ROWDATA 的 RowData，不能把 batchSize 当成行数。事务开始、结束标记用于维护上下文，不生成普通 ES 文档；一批不一定对应完整事务。DDL、TRUNCATE、主键变更及未知事件须有明确的暂停或处置规则，不能在过滤后静默 ack。跨批次事务上下文与字段完整性仍需验证。

#### 4.2 RocketMQ 消息封装

建议使用下面的统一事件契约，008 沿用相同字段。这是待实现方案，不代表当前 Canal API 已直接提供所有字段：

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

eventId 标识重复事件，orderKey 选择顺序队列，consumeId 作为 ES `_id`；三者用途不同。顺序比较不能按 GTID 字符串或事件时间大小直接判断，需定义同一来源内文件序号、事件偏移与行序号的比较规则；多来源或路由变化的规则另行确认。

#### 4.3 投递成功后 ack

以下是 Java 8 核心流程示例，使用 Canal 1.1.7 Message API 与 RocketMQ Spring 2.3.0 有序发送 API；RowEvent 和转换函数是待实现的项目抽象，非可直接运行的完整类：

~~~java
Message message = connector.getWithoutAck(batchSize);
long batchId = message.getId();
if (batchId == -1) { // Canal 无可消费批次
    return;
}

try {
    List<CanalEntry.Entry> entries = new ArrayList<>();
    if (message.isRaw()) {
        for (com.google.protobuf.ByteString raw : message.getRawEntries()) {
            entries.add(CanalEntry.Entry.parseFrom(raw));
        }
    } else {
        entries.addAll(message.getEntries());
    }
    if (entries.isEmpty()) {
        throw new IllegalStateException("非空批次缺少 Entry，暂停核对");
    }
    // 遍历全部 Entry / RowData；未知事件不得静默丢弃
    List<RowEvent> events = convertEntriesOrFail(entries);
    for (RowEvent event : events) {
        org.springframework.messaging.Message<RowEvent> mqMessage =
            MessageBuilder.withPayload(event)
                .setHeader(RocketMQHeaders.KEYS, event.getEventId())
                .build();
        SendResult result = rocketMQTemplate.syncSendOrderly(
            topic, mqMessage, event.getOrderKey());
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("MQ 发送未达到成功标准");
        }
    }
    connector.ack(batchId); // 全部发送成功后再确认；返回不证明持久化
} catch (Exception ex) {
    try {
        connector.rollback(); // 回退未确认消费状态，不撤销已发 MQ 的事件
    } catch (Exception rollbackFailure) {
        ex.addSuppressed(rollbackFailure);
    }
    throw new IllegalStateException("停止本通道后续投递，待恢复", ex);
}
~~~

示例使用 `com.alibaba.otter.canal.protocol.Message/CanalEntry`、`org.apache.rocketmq.client.producer.SendResult/SendStatus`、`org.apache.rocketmq.spring.core.RocketMQTemplate`、`org.apache.rocketmq.spring.support.RocketMQHeaders` 和 Spring MessageBuilder，避免将 Canal Message 与 Spring Message 混淆。连接、订阅、重连、互斥和事务转换未在示例中实现。

非 SEND_OK 状态包括 FLUSH_DISK_TIMEOUT、FLUSH_SLAVE_TIMEOUT、SLAVE_NOT_AVAILABLE；它们与网络超时都可能已经造成部分落地，因此不能简单认定“未发送”，也不能直接 ack。SEND_OK 的持久性取决于 Broker 刷盘和复制配置，尚不能宣称已经满足项目的故障不丢失目标。回退不撤销已发送消息，重放依赖稳定事件 ID 与消费者顺序保护。Canal ack 与 MQ 消费确认须分开。

### 5. 位点、ZooKeeper 和主备

本文指定的 default-instance.xml 在源码中组装 PeriodMixedMetaManager 和 ZooKeeperMetaManager。Server 处理 ack 后更新消费游标，再由元数据管理器周期刷新可恢复进度；读取位点、内存游标与持久化游标不是同一阶段。实际 Bean、刷新周期、destination 与 clientId 对应关系仍须在发行包中确认，不能把配置文件存在当作恢复成功。

主备切换的预期流程如下：

1. 活动 Server 故障；核对 ZooKeeper 会话过期、运行节点删除或主动释放，临时断连不等于立即切换。
2. 备用 Server 竞争并取得同一 destination 的资格。
3. 备用 Server 读取持久化的消费进度，检查对应 MySQL binlog 是否仍可读。
4. 备用 Server 从可恢复位置继续读取；投递组件重连、恢复订阅，并从未确认范围继续拉取和发送。
5. 下游按稳定事件 ID、来源顺序和原物理索引处理重复事件。

取得运行资格不代表已经实现零丢失或零中断。必须验证旧节点隔离、投递组件单活动身份、位点连续性、binlog 保留、MQ 留存和积压追赶。ES 是否写入由 MQ 消费端另行核对，不作为上游 ack 的前提。

### 6. 监控与异常边界

建议分别监控每台 Server 以及 db0、db1：

- destination 活动节点、连接状态和切换次数；
- binlog 读取延迟、Server 到 RocketMQ 的投递延迟和积压；
- RocketMQ 发送失败、重试次数和失败批次；
- Canal ack/rollback 数量和 ZooKeeper 位点刷新状态；
- MySQL binlog 剩余保留窗口。

日志记录不等于告警已送达；ZooKeeper 选主成功也不等于位点已经连续恢复。涉及位点断档、binlog 过期、消息格式异常或持续投递失败时，应暂停对应 destination 并人工处理。

### 7. Server 侧验收

验收范围包括：

1. 分别验证 db0、db1 的 MySQL 连接、GTID 起点和 16 张表过滤范围。
2. 验证 raw/已解析消息、多个 Entry 和多行 RowData、事务标记与异常事件处理；全部 MQ 发送明确成功后才 ack。
3. 注入部分发送成功、非 SEND_OK、超时结果不明、ack/rollback 连接中断，验证稳定 eventId、重放、投递暂停和消费端保序。
4. 模拟单个 destination 的 Server 故障，核对 ZooKeeper 接管、位点恢复、重复投递和积压追赶。
5. 核对 MySQL binlog 保留窗口能覆盖暂停、修复和追赶时间。
6. 将代表性 INSERT、UPDATE、DELETE 事件交给 008 的 ES 消费服务，验证端到端结果。

上述内容是待执行验收要求，不代表当前环境已经部署或测试通过。

### 8. 版本核对依据与边界

- [Canal 1.1.7 canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties)、[default-instance.xml](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/spring/default-instance.xml)及 [instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties)：配置键、GTID 属性及元数据管理器。
- [Canal 1.1.7 Message](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/Message.java)、[SimpleCanalConnector](https://github.com/alibaba/canal/blob/canal-1.1.7/client/src/main/java/com/alibaba/otter/canal/client/impl/SimpleCanalConnector.java)及 [MysqlEventParser](https://github.com/alibaba/canal/blob/canal-1.1.7/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/mysql/MysqlEventParser.java)：消息读取、ack 写出和 slaveId 生成路径。
- [RocketMQ Spring 2.3.0 RocketMQTemplate](https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.0/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/core/RocketMQTemplate.java)及 [rocketmq-client 5.2.0 SendStatus](https://github.com/apache/rocketmq/blob/rocketmq-all-5.2.0/client/src/main/java/org/apache/rocketmq/client/producer/SendStatus.java)：有序发送签名和结果状态。

以上为官方版本源码静态核对，未验证实际发行包、依赖树、MySQL 8.0.33 认证、GTID 恢复或 ZooKeeper 故障切换。
