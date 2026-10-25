# 012_ES改造_Canal Server_整体实现

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

本文将 Canal Server 从部署配置到 RocketMQ 投递、进度恢复的 10 个专题合并为一条可连续阅读的实现链路。文中明确区分项目设计、源码或配置静态核对、以及尚未完成的运行验证；原专题的参考文章对比、依据和待确认事项均保留在对应小节。

## 1 总体处理链路

Canal Server 单机进程加载两个 destination，分别连接 db0、db1 的 MySQL 源库。每个 destination 按过滤规则读取 Binlog，解析 Entry 和事务边界，转换为 Canal RocketMQ 消息，按分区键发布到 RocketMQ；消费端再依据消息中的来源信息和业务版本写入 ES。消息发布成功后确认 Canal 批次并持久化位点，进程重启时从本地位点继续读取。

本合并文档的阅读顺序为：部署与公共配置 → destination 过滤和分区路由 → Binlog 读取与消息转换 → 事务和特殊事件 → RocketMQ 发布与确认 → 本地位点和单机恢复。

## 2 合并后的详细实现

### 2.1 部署拓扑


以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准。

#### 1 适用规则

沿用 Canal Server 原总览（原文档已删除，依据待补充） 的已确认规则；下述内容属于项目设计，未表示已部署或通过运行验证。

#### 2 面试口述精炼版本

项目采用一个 Canal Server 进程承载 db0、db1 两个 destination。两个通道共享进程故障影响，不配置 ZooKeeper 自动切换；部署容量、重启恢复和实际拓扑仍待验证。

#### 3 详细方案

##### 3.1 部署拓扑

~~~mermaid
flowchart LR
    DB0[(MySQL db0)] --> S[单机 Canal Server]
    DB1[(MySQL db1)] --> S
    S -->|内置 RocketMQ 生产端| MQ[(RocketMQ)]
    MQ --> C[RocketMQ 消费与 ES 写入服务]
~~~

图中是 1 台主机上的 1 个 Server 进程，同时加载 db0、db1 两个 destination。两个通道分别维护连接、过滤规则和本地进度，但共享该进程的故障影响；不配置 ZooKeeper 或自动主备切换。单机承载两个通道的容量与重启恢复仍待验证。

#### 4 参考文章与项目设计的区别

- **数据库接入范围**：参考文章以单个 `order_db.orders` 表的实验为主；当前项目设计由一个 Canal Server 进程承载 `db0`、`db1` 两个 destination（数据同步通道），分别连接两个独立的 MySQL 实例。
- **RocketMQ 版本**：参考文章使用 RocketMQ 4.9.4；当前项目的 RocketMQ Broker 服务端版本尚未确认，不能用已确认的 `rocketmq-client` 客户端版本 5.2.0 代替。
- **RocketMQ 部署组件**：参考文章配置 NameServer（名称服务）、两个 Broker（消息存储与投递服务）和 Dashboard（管理控制台）；当前项目中这些组件的实际部署情况和实例数量仍待确认，不能直接沿用文章配置作为项目实现。

#### 5 待确认或待验证事项

配置加载、本地位点模板、文件权限和重启恢复仍待实际发行包验证。


---

### 2.2 公共配置


以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准。

#### 1 适用规则

沿用 Canal Server 原总览（原文档已删除，依据待补充） 的已确认规则；下述内容属于项目设计，未表示已部署或通过运行验证。



#### 3 详细方案

##### 3.2 公共配置示意

以下配置写在 Canal Server 的全局配置文件 `conf/canal.properties` 中，路径相对于 Canal Server 解压目录。该文件统一配置 Server 的运行模式、要加载的 destination（数据同步通道）、实例使用的位点模板和 RocketMQ 连接参数；每个通道专属的源库连接、表过滤等配置则写在 `conf/<destination>/instance.properties` 中。

这组公共配置先决定 Canal Server 如何运行，再指定它加载哪些同步通道和如何保存读取进度。`canal.serverMode` 选择消息发布模式，`canal.destinations` 指定同一进程承载的同步通道，`canal.instance.global.spring.xml` 指定通道实例使用的位点模板，RocketMQ 参数用于连接消息服务。消息格式只约束生产端与消费端的解析契约，不能由配置存在推断消息字段和发送结果已经验证。

以下地址、账号、密码和端口都是占位值，不能直接用于生产：

~~~properties
canal.serverMode=rocketMQ
canal.zkServers=
canal.destinations=db0,db1
canal.auto.reset.latest.pos.mode=false
canal.instance.global.spring.xml=classpath:spring/file-instance.xml


rocketmq.namesrv.addr=<nameserver-address>
rocketmq.producer.group=<producer-group>
~~~

以上属性键已对照 Canal 1.1.7 的官方配置核对；官方 `canal.properties` 默认选择 `file-instance.xml`，本文沿用这一配置入口，采用本地元数据保存消费进度。实际发行包中的模板、文件路径、刷新时机、目录权限及落盘行为仍需核验；配置文件存在不等于位点已经可靠保存。

`canal.auto.reset.latest.pos.mode=false` 针对找不到已指定 binlog 位置后的自动重置路径；首次无有效检查点、且未正确配置初始基线时，仍不能据此承诺一定拒绝从最新位置初始化。启动前必须核对首次 GTID 基线或既有持久化游标，发现缺失就停止启动并复核。

#### 4 参考文章与项目设计的区别

参考文章把 Canal 配置、RocketMQ 集群和 ES 8 实验环境放在一条可运行示例中；当前项目只确认公共配置的设计边界，版本、参数加载和消息服务连接仍需按实际环境核对。

#### 5 待确认或待验证事项

配置加载、本地位点模板、文件权限和重启恢复仍待实际发行包验证。


---

### 2.3 destination 过滤与分区路由


以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准。

#### 1 适用规则

沿用 Canal Server 原总览（原文档已删除，依据待补充） 的已确认规则；下述内容属于项目设计，未表示已部署或通过运行验证。

#### 2 面试口述精炼版本

每个 destination（同步通道）分别配置源库连接、表过滤、GTID 起点和消息路由。首次使用导入前事务标识基线，后续按已保存进度接续；按真实源列选择队列，但同键入队不等于并发消费按顺序完成。过滤范围、源列和队列映射仍待验证。

#### 3 详细方案

##### 3.1 destination 配置示意

~~~properties
canal.instance.master.address=<mysql-db0>:3306
canal.instance.dbUsername=<canal-user>
canal.instance.dbPassword=<managed-secret>
canal.instance.filter.regex=eas\\.consume_record_(0[1-9]|1[0-6])
canal.instance.gtidon=true
canal.instance.master.gtid=<db0-import-before-gtid>
canal.mq.topic=<db0-topic>
canal.mq.partitionHash=eas\\.consume_record_(0[1-9]|1[0-6]):<source-key-column>
canal.mq.partitionsNum=<positive-partition-count>
~~~

db1 使用自己的 MySQL 地址和导入前完整 GTID 集合；不能复制 db0 的 GTID。同一个 Server 分别加载两个 destination 的配置，并核对自动生成的复制身份不与源 MySQL 或同一复制拓扑中的其他复制客户端冲突；如改用手工值，应在同一复制拓扑内规划不同身份。`slaveId` 是 Canal 复制客户端使用的 server-id，区别于源 MySQL 实例自身的 `server-id`；它也不是 destination 名称或 MQ 内部 clientId。

Java properties 文件中的 `\\.` 解析后是正则的 `\.`。上述库表过滤候选须分别验证 01、09、10、16 命中，00、17 和其他表不命中。1.1.7 原生 MQ 工作线程使用空过滤表达式订阅嵌入式 Server，本文的表范围由 Server 侧过滤配置限定；不额外配置独立 TCP 客户端的订阅过滤。导入前 GTID 仅用于首次初始化，不能以编辑 instance.properties 的方式覆盖已有检查点或跳过异常。

##### 3.2 消息分区与来源顺序

消费端候选 `orderKey` 为 `destination + database + table + consumeId`，用于内部顺序状态；它不是原生生产端直接拼接的 hash 字符串。1.1.7 的 `partitionHash` 按库表规则匹配源列，DELETE 使用删除前列，INSERT/UPDATE 使用变更后列；开启 `canal.mq.database.hash` 时还混入库名。topic、字段值、分区数和队列列表稳定，才可核对同键是否始终到同一队列。消费端采用 `CONCURRENTLY`（并发消费），单次回调仍同步等待当前消息内的 ES 结果；同键消息可能并发或乱序完成，由 ES 按来源版本拒绝旧写入。Tag 只做过滤。

上面的正则覆盖 16 张表，旧示例只配置 `consume_record_01` 会让未匹配表走默认分区。`<source-key-column>` 须替换为真实源字段并核对 INSERT、UPDATE、DELETE 均有值，不能把 ES 的驼峰字段直接当作 MySQL 列名。分区数必须明确为正数，并核对与实际 topic 队列列表的映射；不能只写 `partitionHash` 就宣称已实现业务键分散到多个队列。

同一 destination 同时只启动一个 Server 读取和发布，不能在旧进程仍运行时另起重复进程。原生生产端的队列映射、topic 队列数量、重试和重启恢复都需验证路由与来源信息保持稳定。重放或并发处理时，消费端仍须携带稳定来源版本拒绝旧写入；业务版本只比较同一记录的新旧，记录迁移时须保持版本连续性。

分区数、队列选择、并发消费线程和 topic/tag 的实际配置属于待确认实现项；不能直接照搬参考文档的固定分区下标或自动获取分区数示例。

#### 4 参考文章与项目设计的区别

参考文章 [使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<./999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md>) 的版本、脚本和演示结果只代表文章环境，不能证明项目已经实现或验证。

##### 2. MySQL 与 Canal 版本及起点不同

- 文章使用 MySQL 8.0.29、Canal 1.1.6，并在单个实例配置文件中填写 binlog 文件名和 position；当前项目按 MySQL 8.0.33、Canal Server 1.1.7 说明，两个 destination 分别维护源库、过滤规则、导入前 GTID 基线和本地进度。
- 文章示例通过 `canal.instance.master.journal.name` 和 `canal.instance.master.position` 从固定日志位置启动；当前项目首次同步使用导入前 GTID 基线，已有可核对位点不能被基线或最新 GTID 覆盖，具体落盘和恢复行为仍待实际发行包验证。
- 文章的数据库用户、密码、库表和 `order_db.orders` 示例不能直接用于当前项目；当前项目的复制账号、真实源列、权限和 binlog 连续性仍需按两个源库分别核对。

##### 3. 过滤、分区与消息路由不同

- 文章监听 `order_db\.orders.*`，以 `order_no` 作为 `partitionHash`，并示例使用 8 个分区和动态获取队列数；当前项目按 `eas\.consume_record_(0[1-9]|1[0-6])` 的 16 张分表配置候选范围，每个 destination 使用自己的 topic、源字段和正数分区数，是否稳定路由仍待验证。
- 文章的分区示例用于说明同一订单进入同一队列；当前项目不能直接把 ES 字段名或 `consumeId` 当作 `partitionHash` 源列，必须以真实 MySQL DDL 核对 INSERT、UPDATE、DELETE 的取值，并分别核对两个 destination 的队列映射。
- 文章示例把配置和运行结果放在同一实验链路中；当前项目采用 Server 内置 RocketMQ 生产端，不另设独立 TCP 投递组件，MQ 工作线程的订阅、批次确认和重试边界仍按 Canal 1.1.7 发行包核验。

#### 5 待确认或待验证事项

首次 GTID 基线、过滤范围、复制身份、源字段及队列映射仍需在两个源库分别验证。


---

### 2.4 Binlog 读取与 Entry 解析


以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

#### 1 已确认规则与适用边界

- 本文只说明 Canal Server 读取 binlog、过滤范围和 Entry 解析；消息格式见 [05_2 消息格式与字段边界](<#25-消息格式与字段边界>)，事务与特殊事件见 [05_3 事务边界与特殊事件](<#26-事务边界与特殊事件>)。
- 项目设计以 `db0`、`db1` 两个 destination 及各自的分表为范围，未表示已经部署或完成运行验证。

#### 2 面试口述精炼版本

**读取日志并解析事件**

Canal Server 使用复制账号读取 MySQL binlog，先按库表过滤范围，再把事件解析为 Entry。Entry 的 Header 保存库表、事件类型和日志坐标，RowChange、RowData 保存行变更内容；读到事件只说明完成解析，不代表已经发送到 RocketMQ。配置、字段完整性和恢复效果仍待验证。

#### 3 详细方案

##### 3.1 读取与解析

Server 使用复制账号连接 MySQL，读取 binlog；库表过滤规则限定同步范围。解析结果以 Entry 表示，Header 保存库表、事件类型和日志坐标等头部信息，RowChange 表示行变更内容，RowData 保存各行的变更前后列值。binlog 还包含事务标记、DDL（数据定义语句，如修改表结构）等事件，不能把所有事件都当作普通行增删改。

依据：[MysqlEventParser](https://github.com/alibaba/canal/blob/canal-1.1.7/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/mysql/MysqlEventParser.java)、[CanalEntry](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/CanalEntry.java)。本次只完成 1.1.7 源码静态核对，不表示项目运行行为已经验证。

Canal 自带的消息发送功能从内部 Server 获取待确认批次，随后按消息格式和分区规则封装并发布。日志读取、事件解析与消息发布是不同阶段；发送确认及重试见 [07 RocketMQ消息发布与事务边界](<#29-rocketmq-消息发布与事务边界>)，持久化进度及恢复见 [08 进度确认与单机恢复](<#210-进度确认与单机恢复>)。

##### 3.2 项目范围与字段前提

项目接入 `db0`、`db1` 两个同步通道及各自分表，需要分别识别来源库表、维护 GTID 起点和消费进度。业务主键、操作类型、原始 `create_time` 和必要来源映射用于定位；destination 归属需通过约定的来源映射或转换逻辑识别，不能假定原生 FlatMessage 自带该字段。

新增、更新的行后业务数据与 `version` 必须来自同次变更；仅新分表增加业务版本，旧表不增加。MySQL DELETE 保留删除前的 `consumeId` 和原始 `create_time`，用于消息契约、对账或状态判断，不转为 ES 逐条 Delete。完整业务列是否传递仍须核对实际消息。

#### 4 参考文章与项目设计的区别

**同步范围**

**参考文章：** 以单个 `order_db.orders` 表为例，按 `order_no` 配置消息分区路由。

**当前项目：** 设计接入 `db0`、`db1` 两个同步通道及各自的分表，需要分别维护来源范围、GTID 起点和消费进度。

**组件版本**

**参考文章：** 使用 Canal 1.1.6，并把单表实验与 RocketMQ、ES 8 链路一起演示。

**当前项目：** 使用 Canal Server 1.1.7，Entry 解析边界按 1.1.7 核对；文章实验不能证明项目已部署或已验证。

#### 5 待确认或待验证事项

- 两个 destination 的真实复制账号、过滤范围、来源映射和 GTID 基线是否分别正确？
- 实际消息是否保留新增、更新所需的完整业务列、`version` 及 DELETE 前定位信息？
- 事件解析、消息发布和本地进度之间的确认边界是否符合实际发行包行为？

#### 6 复核记录

已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md) 一轮，并按 `java-interview-doc-review` 和 `self-check` 完成最终复核；本次未执行 Canal、MySQL、RocketMQ 或 Elasticsearch 联调。


---

### 2.5 消息格式与字段边界


以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

#### 1 已确认规则与适用边界

- 本文只说明 `FlatMessage` JSON、Canal 二进制消息及项目字段契约；日志读取见 [05_1 Binlog读取与Entry解析](<#24-binlog-读取与-entry-解析>)，事务边界见 [05_3 事务边界与特殊事件](<#26-事务边界与特殊事件>)。
- 新增、更新传递同次变更的数据和业务 `version`；DELETE 保留删除前定位信息，不转换为 ES 逐条 Delete。设计记录不表示字段已端到端验证。

#### 2 面试口述精炼版本

**区分 JSON 和二进制消息**

`FlatMessage` 是扁平 JSON：新增、更新从 `data` 取行后值，DELETE 从 `data` 取行前值，UPDATE 的 `old` 按变化列筛选且不是完整前镜像。二进制消息要先解码 Packet/Messages，再解析 Entry，不能把 JSON 当二进制解析。项目新增、更新还要核对业务 `version` 与数据是否始终配对；字段完整性和拆行结果仍待消息样例验证。

#### 3 详细方案

##### 3.1 配置文件与格式开关

以下示例写在 Canal Server 解压目录下的全局配置文件 `conf/canal.properties` 中。官方 1.1.7 模板将 `canal.mq.flatMessage` 设为 `true`；项目实际加载值和消费端解码方式仍须核对。

~~~properties
canal.mq.flatMessage=true
~~~

依据：[Canal 1.1.7 canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties) 和 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)。源码静态核对不等于实际发行包使用该配置。

##### 3.2 字段与转换边界

**FlatMessage JSON：** `data` 使用 INSERT/UPDATE 的行后列、DELETE 的行前列。UPDATE 的 `old` 根据变化列集合筛选旧列值，不是完整行前镜像；多行更新须核对实际输出。类中定义 `gtid` 字段并从 Header 赋值，但不保证每条消息都有可用 GTID；`es` 是事件执行时间，`ts` 是转换时系统时间，均不等于业务记录的原始 `create_time`。

**二进制消息：** 先解码 Canal Packet/Messages，再解析 Entry 的 RowChange/RowData。Entry Header 可保留日志文件和偏移；按键分区拆行时复制原 Header，但不新增拆分前的原始行序号，不能只凭文件和偏移区分同一原始事件内的多行。

**项目字段契约：** 业务主键、操作类型、原始 `create_time`、来源映射和必要业务列按用途传递。新增、更新携带同次变更的行后数据及 `version`；DELETE 保留删除前的 `consumeId`、原始 `create_time` 等定位信息。独立事件 ID是否必要仍待确认，MQ messageId、Canal batchId 和拆分后下标不能单独作为稳定源事件身份。

##### 3.3 拆分后的对应关系

分区代码按 `data` 中的行计算分区，并用同一下标取对应的 `old`；二进制按键分区复制对应 RowData 后重新分组。源码体现了配对方式，但项目仍须验证多行更新、空值、列集合和业务版本在拆分后的对应关系。

#### 4 参考文章与项目设计的区别

**消息解析方式**

**参考文章：** 消费代码把消息解析为 JSON，从 `data` 读取订单列表，再按 `type` 分派 INSERT、UPDATE、DELETE。

**当前项目：** 需按 `canal.mq.flatMessage` 实际取值核对 JSON 或二进制 Entry，区分行后值、DELETE 行前值和 UPDATE 的变化列旧值。

**业务版本传递**

**参考文章：** `orders` 表有 `version` 字段，更新示例执行 `version = version + 1`。

**当前项目：** 仅新分表增加业务 `version`，旧表不增加；新增、更新须保持数据与版本来自同次变更，DELETE 不生成 ES 逐条删除版本。

#### 5 待确认或待验证事项

- 实际加载的 `canal.mq.flatMessage` 值与消费端解码方式是否一致？
- 多行 UPDATE 的 `data`、`old`、空值、列集合和业务版本是否正确对应？
- 是否需要独立事件级去重及 `eventId`？如采用坐标事件 ID，如何在拆分前保留原始行序号？

#### 6 复核记录

本文由 [java-interview-doc-split](C:/Users/Administrator/.codex/skills/java-interview-doc-split/SKILL.md) 拆分生成；已静态核对 Canal 1.1.7 源码，未执行实际消息样例和联调。


---

### 2.6 事务边界与特殊事件


以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

#### 1 已确认规则与适用边界

- 本文只说明事务标记、消息边界和 DDL 等特殊事件；日志解析见 [05_1 Binlog读取与Entry解析](<#24-binlog-读取与-entry-解析>)，字段格式见 [05_2 消息格式与字段边界](<#25-消息格式与字段边界>)。
- 一条 MQ 消息不直接等同于完整源事务；特殊事件的具体暂停、恢复和隔离策略仍待确认。

#### 2 面试口述精炼版本

**区分消息边界和事务边界**

JSON 转换会跳过事务开始、结束事件；二进制进入按键分区路径时也会过滤这些标记，并可能把同一事务拆到不同消息。因此不能把一条消息或一次拉取批次当作完整事务，也不能用拆分后行下标代替原始日志行序号。DDL、TRUNCATE、主键变更和未知事件要单独处置，具体策略和恢复条件仍待确认。

#### 3 详细方案

##### 3.1 事务标记与消息边界

JSON 的 `messageConverter` 跳过事务开始、结束 Entry。二进制进入 `messagePartition` 分区路径时同样过滤这些标记；未走该路径时，还需核对发布端传给序列化器的 `filterTransactionEntry` 参数。切换为二进制不能直接保证事务标记被保留。

消息数、Entry 数和 ES 文档数没有固定的一一对应关系；一次拉取批次也不能直接视为一个完整源事务。同一事务的数据可能因按键分区进入不同消息，消息中的拆分下标也不是原始日志行序号。

依据：[MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java) 和 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)。本次未执行多分区事务实验。

##### 3.2 特殊事件

DDL、TRUNCATE、主键变更和未知事件不能不加区分地进入普通行写入流程。源码分区方法将 DDL 放到分区 0，但这不会自动暂停其他分区或完成结构变更协调；项目具体暂停范围、恢复条件及主键变更处理仍待确认。

#### 4 参考文章与项目设计的区别

**特殊事件处置**

**参考文章：** 消费代码遇到 DDL 或无法识别的消息类型时直接返回。

**当前项目：** 需要明确 DDL、TRUNCATE、主键变更和未知事件的识别、暂停或恢复策略，不能默认一条消息就是完整事务。

**验证状态**

**参考文章：** 展示了消息进入 RocketMQ 和写入 ES 的实验效果。

**当前项目：** 本次拆分只完成文档和源码静态核对，实际消息样例、事务标记、特殊事件和发送结果仍需验证，不能用文章演示替代项目证据。

#### 5 待确认或待验证事项

- 实际 JSON/二进制发布路径保留哪些事务标记？
- 消费端是否会误把一条消息当作完整事务？
- DDL、TRUNCATE、主键变更及未知事件分别采用什么处置和恢复策略？
- 多分区结构变更如何隔离并协调恢复？

#### 6 复核记录

本文由 [java-interview-doc-split](C:/Users/Administrator/.codex/skills/java-interview-doc-split/SKILL.md) 拆分生成；未执行 Canal、MySQL、RocketMQ 或 Elasticsearch 联调、故障演练或恢复测试。


---

### 2.7 Binlog 发送到 RocketMQ 核心实现


以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

#### 1 已确认规则与适用边界

- 本文只说明 Canal Server 将 binlog 事件转换并发送到 RocketMQ 的核心实现；事务边界、提交与回滚影响见 [07 RocketMQ消息发布与事务边界](<#29-rocketmq-消息发布与事务边界>)。
- 当前项目采用 Canal Server 内置 RocketMQ 生产端，不另设独立 TCP 投递组件；本文描述的是 Canal 1.1.7 源码路径，未表示实际发行包已部署或验证。

#### 2 面试口述精炼版本

**Canal Server 负责读取批次并发送消息**

CanalMQStarter 为 destination 建立内部 MQ 生产任务，从 Server 获取未确认批次；CanalRocketMQProducer 再按 `flatMessage` 配置转换为 JSON 或二进制消息，按分区规则拆分并调用 RocketMQ Producer 发送。所有分区发送任务完成后，成功路径提交 Canal 批次，异常路径回滚；但 `SendResult` 的非 `SEND_OK` 处理仍需核验。

#### 3 详细方案

##### 3.1 获取 Canal 批次

CanalMQStarter 为每个 destination 创建内部生产端，并使用内部 clientId 从嵌入式 Server 调用 `getWithoutAck` 获取待确认批次。这个 clientId 属于 Canal 内部消费游标，不是 MySQL 复制使用的 slaveId，也不是 RocketMQ 消费者身份。

##### 3.2 转换消息格式

CanalRocketMQProducer 按 `canal.mq.flatMessage` 选择转换路径：

- `true`：调用消息转换逻辑生成 FlatMessage JSON，再序列化为 RocketMQ 消息体；`data`、`old`、事件类型和来源字段的边界见 [05_2 消息格式与字段边界](<#25-消息格式与字段边界>)。
- `false`：保留 Canal Entry，按 Canal Packet/Messages 二进制格式封装，再交给 RocketMQ Producer 发送。

格式开关位于 Canal Server 解压目录下的 `conf/canal.properties`。模板默认值不等于当前部署值，实际加载结果和消费端解码方式仍需核对。

##### 3.3 分区和消息发送

生产端先根据 topic、分区数和 `partitionHash` 计算目标队列。启用按键分区时，源码可能按行重建 Entry 或重新组织 FlatMessage；同一源记录的路由稳定性取决于真实源列、哈希配置和队列映射，不能直接套用参考文章的固定分区数。

每个分区形成一个待发送消息或消息集合，生产端调用 RocketMQ Producer 的同步发送方法，并等待本轮分区发送任务结束。部分分区已成功、其他分区失败时，进入 MQ 的消息不会因 Canal 回滚而自动撤回，后续可能重复发送。

##### 3.4 提交、回滚与发送状态

无异常完成本轮发送后，回调提交 Canal 内部批次；发生异常时，回调回滚内部批次。提交只表示 Canal 内部批次确认，不等于 ES 已写入，也不等于所有消息已经完成下游消费。

源码路径会取得 RocketMQ `SendResult`，但当前核对到的发送分支主要依赖异常判断和日志记录，是否显式处置 `getSendStatus() != SEND_OK` 需要结合实际发行包进一步验证。因此不能把回调直接表述为“所有消息已可靠落盘”。

#### 4 参考文章与项目设计的区别

**投递组件**

**参考文章：** 以实验环境部署 Canal、RocketMQ NameServer、Broker 和 Dashboard，并展示消息进入 RocketMQ 的效果。

**当前项目：** 由 Canal Server 内置 RocketMQ 生产端直接发布，不另设独立 TCP 投递组件；实际 Broker 版本和部署实例仍待确认。

**消息转换**

**参考文章：** 消费端示例直接把消息解析为 JSON，并按订单示例处理 `data` 和 `type`。

**当前项目：** 生产端可按 `flatMessage` 选择 FlatMessage JSON 或 Canal 二进制 Entry；消费端必须与实际配置和封装格式匹配，不能把文章 JSON 示例当作项目实际消息契约。

**批次确认**

**参考文章：** 展示发送和写入效果，但没有证明 Canal 内部批次确认、RocketMQ Broker 持久化和 ES 写入属于同一确认阶段。

**当前项目：** 发送任务完成后才触发 Canal 批次提交或回滚；MQ 发送、Broker 持久化、下游消费和 ES 写入分别表示不同状态，部分成功和结果不明时可能重复投递。

#### 5 待确认或待验证事项

- 实际发行包使用的 Canal 内置 RocketMQ 客户端版本和 Broker 版本是什么？
- `canal.mq.flatMessage` 的实际加载值与消费端解码方式是否一致？
- topic、分区数、`partitionHash` 源列和队列映射是否稳定？
- `SendResult` 非 `SEND_OK` 且未抛异常时，生产端是否仍提交 Canal 批次？
- 部分分区成功、超时或结果不明时，重复消息和恢复顺序是否符合预期？

#### 6 复核记录

本文依据 Canal 1.1.7 的 [CanalMQStarter](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/CanalMQStarter.java)、[CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java) 和 [MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java) 静态核对；未执行 Canal、RocketMQ 或 ES 联调。


---

### 2.8 日志读取与变更解析


以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准。

#### 1 已确认规则

本专题按公共配置、destination 配置、事件解析与消息格式拆分；详细方案和参考文章对比分别维护在对应子文档。既有项目规则见 Canal Server 原总览（原文档已删除，依据待补充）。

#### 2 面试口述精炼版本

Canal Server 通过公共配置加载同步通道，各通道独立连接源库、过滤表并选择读取起点。日志解析为事件后，按消息格式和路由规则发布到 RocketMQ。配置加载、字段完整性、位点落盘和恢复仍待验证。

#### 3 专题导航

- [部署拓扑](<#21-部署拓扑>)
- [公共配置](<#22-公共配置>)
- [destination 过滤与分区路由](<#23-destination-过滤与分区路由>)
- [Binlog 读取与 Entry 解析](<#24-binlog-读取与-entry-解析>)、[消息格式与字段边界](<#25-消息格式与字段边界>)、[事务边界与特殊事件](<#26-事务边界与特殊事件>)
- [Binlog 发送到 RocketMQ 核心实现](<#27-binlog-发送到-rocketmq-核心实现>)

#### 4 验证边界

这里只完成文档拆分；参考文章对比和待验证事项已随主题迁移，未执行 Canal、RocketMQ 或 ES 联调。


---

### 2.9 RocketMQ 消息发布与事务边界


以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。本文是 Canal Server 写入链路的拆分总览；三个独立文档分别说明日志读取与解析、RocketMQ 发布与事务边界、进度确认与单机恢复。设计、实现和验证状态继续按原文边界说明。

#### 适用规则

本页沿用 Canal Server 原总览（原文档已删除，依据待补充） 的“已确认规则”；本页仅展开当前知识点直接需要的处理过程、边界和待验证事项。

#### 面试口述精炼版本

##### 发布与事务边界

Canal Server 由内置 RocketMQ 生产端按源字段分区发布消息。一个 Canal 拉取批次可能拆成多条 MQ 消息，不能把 MQ 消息或 batchId 直接当作完整 MySQL 事务；部分发送、重试和乱序需要依靠来源信息、业务版本和幂等规则处理。发送确认和 Broker 持久化仍待验证。

#### 详细方案

###### 3.1 消息分区与来源顺序

消费端候选 `orderKey` 为 `destination + database + table + consumeId`，用于内部顺序状态；它不是原生生产端直接拼接的 hash 字符串。1.1.7 的 `partitionHash` 按库表规则匹配源列，DELETE 使用删除前列，INSERT/UPDATE 使用变更后列；开启 `canal.mq.database.hash` 时还混入库名。topic、字段值、分区数和队列列表稳定，才可核对同键是否始终到同一队列。消费端采用 `CONCURRENTLY`（并发消费），单次回调仍同步等待当前消息内的 ES 结果；同键消息可能并发或乱序完成，由 ES 按来源版本拒绝旧写入。Tag 只做过滤。

上面的正则覆盖 16 张表，旧示例只配置 `consume_record_01` 会让未匹配表走默认分区。`<source-key-column>` 须替换为真实源字段并核对 INSERT、UPDATE、DELETE 均有值，不能把 ES 的驼峰字段直接当作 MySQL 列名。分区数必须明确为正数，并核对与实际 topic 队列列表的映射；不能只写 `partitionHash` 就宣称已实现业务键分散到多个队列。

同一 destination 同时只启动一个 Server 读取和发布，不能在旧进程仍运行时另起重复进程。原生生产端的队列映射、topic 队列数量、重试和重启恢复都需验证路由与来源信息保持稳定。重放或并发处理时，消费端仍须携带稳定来源版本拒绝旧写入；业务版本只比较同一记录的新旧，记录迁移时须保持版本连续性。

分区数、队列选择、并发消费线程和 topic/tag 的实际配置属于待确认实现项；不能直接照搬参考文档的固定分区下标或自动获取分区数示例。

##### 4. 从 binlog 到 RocketMQ

###### 4.1 读取和解析

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

###### 4.2 RocketMQ 消息封装

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

###### 4.3 原生 RocketMQ 投递与确认边界

当前由 Canal Server 的内置生产端发布，无独立 TCP 投递组件。按 1.1.7 源码，[CanalMQStarter](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/CanalMQStarter.java) 为每个 destination 使用内部 `clientId=1001`，从嵌入式 Server 调用 `getWithoutAck`；[CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java) 等待本轮分区发送任务结束，无异常时 `callback.commit()` 调用内部 `ack`，异常时 `callback.rollback()` 调用内部 `rollback`。这些是原生模式内部路径，不能套用独立 TCP 客户端的连接和确认假设；此 clientId 也不是 MySQL 复制 slaveId。

**发送状态存在必须核验的风险：** 单条和批量 `sendMessage` 都取得 `SendResult`，但只记录调试日志，没有显式判断 `getSendStatus()==SEND_OK`。因此，发送调用返回非 `SEND_OK` 且没有抛异常时，仍可能走提交回调；不能把回调描述为“已验证所有消息可靠落盘”。须核对实际发行包、其内置 RocketMQ 客户端版本、Broker 刷盘复制与异常状态处理，必要修正属于待确认实施项。项目业务消费端的 client 5.2.0 不能自动证明 Server 内置依赖也是该版本。

一个发布批次可拆为多个分区消息，某些分区发送成功后其他分区异常，会回滚 Canal 内部批次并可能重发已成功消息；这不会撤回已经进入 MQ 的消息，超时也可能是结果不明。稳定事件身份、重复处理与旧事件拒绝必须覆盖这些情形。

RocketMQ 生产成功、Broker 持久化、Canal 位点持久化、MQ 消费确认和 ES 写入是不同阶段。不能因为某条消息已经出现在 Dashboard，或 ES 已经写入，就反推出 Canal 位点已经可靠持久化。重启恢复可能重复投递，ES 写入服务须保留稳定来源版本，更新和删除按原始 `create_time` 计算具体月份索引，并按幂等规则处理；

**可靠交接后的留存边界：** Canal 不等待 ES 完成才推进内部消费游标。事件交给 MQ 后，MQ 留存与容量须覆盖下游暂停、修复和追赶；消费落后不能当作消息不会过期的保证。若所需消息已过期，不能因源库仍有 binlog 就认定正常重启能补回，须先复核 Canal 位点、MQ 缺口和受控重放范围。Broker 版本、留存参数及这类恢复流程尚未验证。

###### 4.4 Canal 批次与 MySQL 事务边界

Canal 一次拉取并等待确认的一组事件属于内部批次，`batchId` 只用于追踪这次批次，不等于 MySQL 事务编号，也不等于 RocketMQ 消息批次。一个 MySQL 事务可以包含多个行事件，也可能在分区或逐行拆分后形成多条 MQ 消息；一条 MQ 消息是否只包含一个事务，必须结合实际封装实现确认。

如果下游需要按事务范围恢复或校验，应保存实际收到的消息及其中的 Binlog 事件，并依据消息中实际保留的事务开始、提交标记或其他可靠边界组装处理范围。事务边界没有确认完整前，不能把单条消息或一次确认直接当作整笔事务已经保存。

分区或逐行拆分还可能使一笔事务的事件分散到多个队列。下游必须按消息实际携带的来源信息处理重复和乱序，不能仅凭消息顺序推断数据库事务顺序。

#### 待确认事项

Binlog 到 RocketMQ 的转换、分区和发送实现见 [05_4 Binlog发送到RocketMQ核心实现](<#27-binlog-发送到-rocketmq-核心实现>)；本页继续说明发送后的事务、确认与恢复边界。

- topic、分区数、队列映射、消息格式和 `partitionHash` 源列是否稳定，仍需结合实际配置验证。
- 事务开始/提交事件是否保留、部分发送失败后的恢复顺序和非 `SEND_OK` 处理仍需运行验证。

#### 关联文档

- [进度确认与单机恢复](<#210-进度确认与单机恢复>)
- [RocketMQ 消费模型](<./023_ES改造_RocketMQ_RocketMQ 消费模型.md>)


---

### 2.10 进度确认与单机恢复


以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。本文是 Canal Server 写入链路的拆分总览；三个独立文档分别说明日志读取与解析、RocketMQ 发布与事务边界、进度确认与单机恢复。设计、实现和验证状态继续按原文边界说明。

#### 适用规则

本页沿用 Canal Server 原总览（原文档已删除，依据待补充） 的“已确认规则”；本页仅展开当前知识点直接需要的处理过程、边界和待验证事项。


#### 面试口述精炼版本

##### 进度与恢复

读取位置、Canal 批次确认、本地持久化位点、RocketMQ 消费位点和 ES 处理状态分别表示不同阶段。单机故障后要分别核对两个 destination 的位点、binlog 连续性和 MQ 积压；无法确认可靠起点时暂停，不能直接跳到最新 GTID。恢复效果仍需演练验证。

#### 详细方案

##### 5. 本地位点与单机恢复

###### 5.1 本地持久化进度

本文采用 `file-instance.xml` 本地位点模板，按 destination 分别保存可恢复进度。原生生产端的提交回调通过内部 `ack` 更新消费游标，本地元数据落盘是另一个阶段；读取位置、内存游标和本地持久化游标不能混为一谈。具体文件路径、刷新时机与 destination/clientId 的对应关系须按 Canal 1.1.7 实际发行包核验。

发送状态风险见 4.3。消息已进入 MQ、ES 已写入或本地文件存在，都不能单独证明 Canal 已可靠保存恢复进度；故障前已处理但尚未落盘的部分可能重放，具体范围需联调验证，下游必须处理重复和旧事件。

###### 5.2 人工重启恢复

单机部署没有备用节点自动接管。Server 进程故障时，db0、db1 的采集和发布都会中断；MQ 中已有消息能否继续被下游消费，须另行核对 RocketMQ 和 ES 写入服务状态。恢复的预期流程如下：

1. 确认原 Server 进程已停止，避免同一 destination 被两个进程同时读取和发布。
2. 保留并分别核对 db0、db1 的本地持久化位点、源实例身份和配置，不删除元数据文件来强制重跑。
3. 检查两个源库在对应恢复位置之后的 binlog 是否仍可读且连续；位点缺失、文件损坏或日志断档时暂停复核。
4. 人工重启 Server，从各通道可核对的进度继续读取，并恢复原生 RocketMQ 发布；首次 GTID 基线只用于首次初始化，不能覆盖已有进度。
5. 核对两个通道的发布恢复、MQ 积压追赶和 ES 实际结果，下游按稳定来源版本与原物理索引处理重复和旧事件，独立事件去重按需采用。

###### 5.3 主机与文件故障边界

本地位点依赖保存文件的主机和存储。主机或磁盘故障导致位点不可读时，需要先核对能否恢复原进度及必要元数据；不能在另一台主机直接按最新 GTID 启动，也不能默认退回导入前基线。备份、文件恢复和人工迁移步骤仍需实现依据与演练，当前方案不承诺自动切换、零中断或零丢失。

##### 6. 监控与异常边界

建议监控单机 Server 及 db0、db1 两个通道：

- Server 进程存活、两个 destination 的连接状态和重启次数；
- binlog 读取延迟、Server 内置生产端到 RocketMQ 的发布延迟和积压；
- RocketMQ 发送失败、重试次数、消息重复和位点恢复；
- Canal Server 本地位点保存状态、文件可读性和存储空间；
- MySQL binlog 剩余保留窗口。
- RocketMQ 最早可读消息与未消费位点的距离、剩余留存窗口及容量；阈值须结合实际 Broker 确认。

日志记录不等于告警已送达，进程启动成功也不等于位点已经连续恢复。涉及本地位点丢失或损坏、binlog 过期、消息格式异常或持续投递失败时，应暂停对应 destination 并人工处理。

##### 7. Server 侧验收

验收范围包括：

1. 分别验证 db0、db1 的 MySQL 连接、GTID 起点和 16 张表过滤范围。
2. 分别核对 FlatMessage JSON 和二进制 Entry 的实际封装、UPDATE 前值范围、按键拆行后的源行身份及事务标记缺失；在最终格式中验证消息解析、DDL 隔离、字段转换、发送确认和位点推进。
3. 注入部分发送成功、非 SEND_OK、超时结果不明、Broker 重启和 Server 重启，验证来源坐标及版本稳定性、可选事件身份、重放、位点恢复，以及下游并发消费时的旧版本拒绝。
4. 模拟 Server 进程故障及本地位点丢失、损坏或不可读，核对两个通道的中断范围、人工恢复、本地持久化位点与日志连续性、重复投递和积压追赶；无法核对可靠起点时暂停，不能直接跳到最新位置。
5. 分别核对 MySQL binlog 与 RocketMQ 消息留存窗口，覆盖各自读取通道的暂停、修复和追赶；模拟 MQ 未消费消息过期，验证发现缺口后暂停复核，不能靠常规重启跳过。
6. 将代表性 INSERT、UPDATE 事件交给 009 的 ES 写入服务，另行核对 DELETE 事件的消息状态、对账和 ILM 删除边界；不能把 DELETE 当作 ES 逐条删除请求验证。

上述内容是待执行验收要求，不代表当前环境已经部署或测试通过。

##### 8. 版本核对依据与边界

- [Canal 1.1.7 canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties)及 [instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties)：已静态核对配置键、GTID 属性和默认 `file-instance.xml` 入口；[file-instance.xml](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/spring/file-instance.xml) 的本地落盘与恢复行为仍须按实际发行包核验。
- [Canal 1.1.7 Message](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/Message.java)、[CanalEntry](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/CanalEntry.java)及 [MysqlEventParser](https://github.com/alibaba/canal/blob/canal-1.1.7/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/mysql/MysqlEventParser.java)：消息模型、行事件解析和 slaveId 生成路径；原生 RocketMQ 生产路径仍需结合发行包核验。
- [CanalMQStarter](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/CanalMQStarter.java)及 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)：内部 clientId、空过滤表达式订阅、提交/回滚回调、分区发送及 SendResult 状态检查缺口。
- [FlatMessage](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/FlatMessage.java)、[MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java)及 [CanalMessageSerializerUtil](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/util/CanalMessageSerializerUtil.java)：JSON 字段、二进制封装、UPDATE old 范围、hash 字段及拆行后信息边界。
- [RocketMQ Spring 2.3.0 RocketMQTemplate](https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.0/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/core/RocketMQTemplate.java)及 [rocketmq-client 5.2.0 SendStatus](https://github.com/apache/rocketmq/blob/rocketmq-all-5.2.0/client/src/main/java/org/apache/rocketmq/client/producer/SendStatus.java)：有序发送签名和结果状态。

以上为已有官方版本源码的静态核对及待核验模板入口，未验证实际发行包、依赖树、MySQL 8.0.33 认证、本地位点落盘或单机重启恢复。

#### 进度与增量校验边界

需要分别记录以下进度，不能用其中一类替代另一类：

| 进度 | 含义 | 不能替代 |
| --- | --- | --- |
| MySQL Binlog 位置 | Server 读取到的源日志范围 | MQ 发送成功或 ES 写入成功 |
| Canal 内部批次确认 | Server 对读取批次的确认状态 | RocketMQ Broker 持久化 |
| RocketMQ 消费位点 | 消费者已确认的消息范围 | ES 文档处理结果 |
| ES 处理状态 | 下游对具体写入结果的记录 | Canal 源日志已经安全交接 |

增量校验应保存实际接收的消息和事件，不应假设一条 RocketMQ 消息就是一笔完整 MySQL 事务。需要按事务校验时，先确认消息是否保留事务边界，或由消费端根据可靠事件重组；重组失败应保留异常。校验进度按实际保存的消息、事件范围和完成状态推进，不能直接使用 Canal `batchId` 代替可恢复的业务进度。

发生部分发送、重试或重复消息时，校验与 ES 写入都需要依赖业务版本、来源信息和幂等规则处理，不能把消息重复直接判断为事务丢失或自动回滚。

#### 关联文档

- [RocketMQ 消息发布与事务边界](<#29-rocketmq-消息发布与事务边界>)
- [增量校验流程与差异处理](<./045_ES改造_ES_增量校验流程与差异处理.md>)


---
