# 007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）

以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准：Java 8、Spring Boot 2.7.18、RocketMQ 5.2.0、Canal Server 1.1.7、MySQL 8.0.33 和 Elasticsearch 7.17.15。

## 文档维护与已确认规则

- 本文只说明 Canal Server 从 MySQL binlog 读取、解析、维护消费进度并向 RocketMQ 投递消息的职责；RocketMQ 消费和 ES 写入见 008。
- 项目包含两个独立 MySQL 实例，对应 db0、db1 两个 destination。两个通道分别维护源库、过滤规则、导入前 GTID 基线和消费进度，不共用起点，也不承诺跨库全局顺序。
- 已确认部署规模为 3 台不同主机上的 Canal Server，并接入 3 节点 ZooKeeper 集群。ZooKeeper 按 destination 协调运行资格；一个 destination 的切换不强制另一个同时切换。
- 当前使用本地 canal.properties 和各 destination 的 instance.properties，不使用 canal-admin、管理库或集中配置管理。
- Server 投递 RocketMQ 成功后才确认对应 Canal 批次。RocketMQ 成功不等于 ES 成功；ES 消费服务的重试、死信和幂等由 008 负责。
- MySQL binlog 保留期目标为 30 天；首次同步使用 006 记录的导入前 GTID 基线，正常恢复优先使用已确认并持久化的消费进度。位点断档或无法核实时暂停复核，不能跳到最新 GTID。
- 配置、流程图和代码均为说明性内容，未表示已经部署、联调或完成故障演练。

## 参考文章的吸收与边界

参考文档展示了 Canal 按库表过滤 binlog、按业务主键设置 RocketMQ 消息键并由消费者处理 ES 的做法。007 吸收其“Server 负责读取解析、MQ 负责缓冲、同一业务主键尽量保持队列顺序”的分层原则；消息 topic、分区数、队列索引、批量大小和启动脚本仍需按 RocketMQ 5.2.0 与 Canal Server 1.1.7 的实际发行包核验。

参考文档是 Canal 1.1.6 与 RocketMQ 4.9.4 的单机演示，未覆盖当前项目的两个独立 destination、3 台 Server、3 节点 ZooKeeper 和按 destination 独立接管。示例中的本地 Windows 路径、`canal.mq.partition`、固定 topic 和 Dashboard 启动结果均不代表当前环境已经配置或验证。

## 面试口述精炼版本

### 整体职责

Canal Server 的职责是把 MySQL 已提交事务的 binlog 可靠地变成可消费消息。它不负责 Elasticsearch Mapping，也不直接承担 ES 写入。

项目为两个独立 MySQL 实例配置 db0、db1 两个 destination。Server 通过 ZooKeeper 竞争 destination 的运行资格，取得资格后连接对应 MySQL，读取和解析 binlog，按批次交给 Canal-to-MQ 投递组件。投递组件把事件发送到 RocketMQ，发送成功后才向 Server ack。

### 配置与启动

3 台 Server 使用相同版本和公共配置，每台配置自己的可达注册地址，并接入同一个 ZooKeeper 集群。每个 destination 的 instance.properties 配置 MySQL 地址、复制账号、过滤规则、GTID 起点和 slaveId。

db0、db1 的 GTID 起点和消费进度必须分开维护。Server 运行资格、位点和故障接管按 destination 独立处理。

### 消息处理和确认

Server 将事件整理成包含库名、表名、事件类型、行数据、事务顺序和批次编号的 Canal 消息。Canal-to-MQ 投递组件拉取消息后，先把消息转换成 RocketMQ 事件并发送；只有发送成功才 ack 原 Canal 批次。

如果 RocketMQ 发送失败，应重试或回退，不能先 ack 再补发，否则可能造成消息丢失。这个 ack 只表示消息已经进入 RocketMQ，不代表 ES 已经写成功。

### 高可用与恢复

Server 运行期间通常先在内存中推进消费位置，再按配置周期将元数据和位点持久化到 ZooKeeper。活动 Server 故障后，ZooKeeper 释放对应 destination 的运行资格，备用 Server 取得资格后读取持久化位点，重新连接 MySQL 并继续读取。

恢复过程中可能出现重复投递，因此下游必须使用稳定消息键和幂等处理。位点无法恢复、binlog 已断档或批次状态无法核实时，必须暂停人工核对，不能自动跳到最新位置。

## 详细方案

### 1. 部署拓扑

~~~mermaid
flowchart LR
    DB0[(MySQL db0)] --> S[Canal Server 集群]
    DB1[(MySQL db1)] --> S
    Z[ZooKeeper 3 节点] -.协调 destination 运行资格.-> S
    S -->|TCP 批次| P[Canal-to-MQ 投递组件]
    P -->|发送成功后 ack| MQ[(RocketMQ)]
    MQ --> C[自定义 ES 消费服务]
~~~

图中 Server 集群表示 3 台候选节点；同一 destination 同一时间只应有一个活动节点，两个 destination 可以由同一台或不同 Server 运行。

### 2. 公共配置示意

以下地址、账号、密码和端口都是占位值，不能直接用于生产：

~~~properties
canal.serverMode=tcp
canal.zkServers=zk1:2181,zk2:2181,zk3:2181
canal.destinations=db0,db1
canal.autoResetLatestPosMode=false
canal.instance.global.spring.xml.classpath=spring/default-instance.xml
~~~

实际属性名、加载路径和生效结果必须以 Canal Server 1.1.7 发行包为准。配置 canal.autoResetLatestPosMode=false 表示位点缺失时不应无条件跳到最新位置；发生位点问题时应进入人工恢复流程。

### 3. destination 配置示意

~~~properties
# db0/instance.properties
canal.instance.master.address=<mysql-db0>:3306
canal.instance.dbUsername=<canal-user>
canal.instance.dbPassword=<managed-secret>
canal.instance.filter.regex=eas\\.consume_record_(0[1-9]|1[0-6])
canal.instance.gtidon=true
canal.instance.master.gtid=<db0-import-before-gtid>
canal.instance.mysql.slaveId=1001
~~~

db1 使用对应的 MySQL 地址、GTID 基线和复制身份；不能复制 db0 的 GTID。过滤范围应覆盖两个实例各自的 16 张物理表，实际正则和属性层级需按发行包核验。

#### 3.1 消息分区与同主键顺序

参考文档建议以“库名.表名:业务主键”作为散列键，让同一业务记录尽量进入同一个 RocketMQ MessageQueue。当前方案可将 `destination`、库名、表名和 `consumeId` 组合成消息键，并在消费端按该键保持单键有序处理。该策略只约束同一业务主键在单个 topic/队列范围内的相对顺序，不提供 `db0` 与 `db1` 之间的全局顺序，也不等于 RocketMQ 或 Canal 已经完成顺序语义验证。

分区数、队列选择、并发消费线程和 topic/tag 的实际配置属于待确认实现项；不能直接照搬参考文档的固定分区下标或自动获取分区数示例。

### 4. 从 binlog 到 RocketMQ

#### 4.1 读取和解析

Server 通过复制账号以从库方式连接 MySQL，读取已经提交的 binlog。解析结果至少应保留：

- destination、库名和表名；
- INSERT、UPDATE、DELETE 事件类型；
- 主键、变更前后行数据和事件时间；
- 事务标识、事件顺序、Canal batchId；
- 可用于消息幂等和追踪的来源信息。

具体字段取决于 Canal 消息模型、MySQL 表结构和真实事件，不能把示例字段当成已验证格式。

#### 4.2 RocketMQ 消息封装

Canal-to-MQ 投递组件建议把每条行变更封装为统一事件，并把原始 batchId 和事件顺序放入消息头或消息体，便于追踪和重放：

~~~json
{
  "eventId": "<destination>-<batchId>-<rowIndex>",
  "destination": "db0",
  "database": "eas",
  "table": "consume_record_01",
  "eventType": "UPDATE",
  "consumeId": "<business-id>",
  "before": {},
  "after": {},
  "binlogPosition": "<source-position>",
  "transactionId": "<transaction-id>"
}
~~~

这只是建议的消息契约，尚未在项目中实现。消息键建议使用 consumeId，以便同一业务主键尽量落到同一顺序队列；不能因此宣称两个数据库之间存在全局顺序。

#### 4.3 投递成功后 ack

伪代码表达项目要求的顺序：

~~~java
Message message = connector.getWithoutAck(batchSize);
if (message.isEmpty()) {
    return;
}

try {
    List<RocketmqEvent> events = convert(message.getEntries());
    rocketMqProducer.send(events);       // 发送成功才允许确认
    connector.ack(message.getId());      // 最后确认 Canal 批次
} catch (Exception ex) {
    connector.rollback(message.getId()); // 不确认，等待重试或人工处理
    recordPublishFailure(message.getId(), ex);
}
~~~

RocketMQ 发送成功后，消息仍可能在 ES 消费阶段失败，因此 ES 消费服务还需要独立的重试和死信机制。Canal ack 不应与 ES ack 混为一谈。

### 5. 位点、ZooKeeper 和主备

Canal Server 1.1.7 的实例配置会组装内存位点和 ZooKeeper 元数据管理组件。运行时先推进内存中的消费位置，再按刷新周期把可恢复元数据写入 ZooKeeper。周期参数、实际 Bean 和发行包默认值必须以项目使用的发行包核对。

主备切换的预期流程如下：

1. 活动 Server 连接断开，ZooKeeper 释放该 destination 的运行资格。
2. 备用 Server 竞争并取得同一 destination 的资格。
3. 备用 Server 读取持久化的消费进度，检查对应 MySQL binlog 是否仍可读。
4. 备用 Server 从可恢复位置继续读取，并向 Canal-to-MQ 投递。
5. 下游按消息键幂等处理可能的重复事件。

取得运行资格不代表已经实现零丢失或零中断。必须验证旧节点隔离、位点连续性、binlog 保留、RocketMQ 投递和积压追赶。

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
2. 验证 Canal 消息能够被投递组件读取，并在 RocketMQ 发送成功后才 ack。
3. 注入 RocketMQ 发送失败，确认 Canal 批次不被提前确认。
4. 模拟单个 destination 的 Server 故障，核对 ZooKeeper 接管、位点恢复、重复投递和积压追赶。
5. 核对 MySQL binlog 保留窗口能覆盖暂停、修复和追赶时间。
6. 将代表性 INSERT、UPDATE、DELETE 事件交给 008 的 ES 消费服务，验证端到端结果。

上述内容是待执行验收要求，不代表当前环境已经部署或测试通过。
