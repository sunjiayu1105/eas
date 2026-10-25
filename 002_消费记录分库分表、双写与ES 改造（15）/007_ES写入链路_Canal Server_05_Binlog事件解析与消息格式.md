# 007_ES写入链路_Canal Server_05_Binlog事件解析与消息格式

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与适用边界

- 本次使用 `java-interview-doc-review-loop` 连续复核，仅修改本页，关联文档只读；不执行部署、联调或生产操作。
- 对比内容按主题分点，主题标题单独加粗，各对象分别成段，以加粗对象名称作为标签，段落之间留空行。
- 项目字段契约以现有 [消息字段与事件身份](<./010_ES写入链路_ES_01_消息字段与事件身份.md>) 和 [业务版本与来源顺序](<./010_ES写入链路_ES_02_业务版本与来源顺序.md>) 为依据：新增、更新传递同次变更的数据和业务版本；MySQL DELETE 不转换为 ES 逐条 Delete。以上是设计记录，不表示已实现或完成运行验证。

## 2 面试口述精炼版本

**先解析日志，再封装消息**

Canal Server 把 MySQL binlog（二进制日志）解析为 Entry（事件对象），行变更里包含操作类型和相应的行数据，再按配置封装成 JSON 或二进制消息交给 RocketMQ。解析完成只说明读到了事件，不等于消息发送成功。详见 [读取与解析](#32-读取与解析)。

**消费端按消息格式取值**

FlatMessage 是将事件字段直接组织成 JSON 的消息结构。新增、更新从 `data` 取行后数据，删除从 `data` 取行前数据；UPDATE 的 `old` 不是完整行前数据。二进制消息则要解开封装，再解析 Entry，不能沿用 JSON 的解析方式。项目新增、更新的数据必须与同次变更的业务 `version` 一起传递；DELETE 保留删除前定位信息，不用于 ES 逐条删除。详见 [字段需求](#33-项目所需字段与可选来源信息) 和 [消息格式边界](#34-两种消息格式的字段边界)。

**消息边界不等于事务边界**

JSON 转换会过滤事务开始、结束事件；二进制按键分区也会过滤这些事件，并可能把同一事务的数据分到不同消息。因此不能把一条消息当作完整事务，也不能用消息中的行下标直接代替原始日志行序号。当前只完成文档和源码静态核对，实际字段完整性与拆分后的对应关系仍需消息样例验证。详见 [事务与特殊事件](#35-事务边界与特殊事件)。

## 3 详细方案

### 3.1 配置文件与消息格式开关

以下示例写在 Canal Server 解压目录下的全局配置文件 `conf/canal.properties` 中。官方 1.1.7 配置模板将 `canal.mq.flatMessage` 设为 `true`；项目实际加载值及消费端解析方式仍须联调核对，模板默认值不代表当前部署值。

~~~properties
# true：FlatMessage JSON；false：Canal Packet/Messages 二进制封装。
canal.mq.flatMessage=true
~~~

依据：[Canal 1.1.7 canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties) 的消息格式配置，以及 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java) 的 JSON/二进制发布分支。本次已静态核对，不证明实际发行包使用了该配置。

### 3.2 读取与解析

Server 使用复制账号连接 MySQL，读取 binlog；库表过滤规则限定同步范围。解析结果以 Entry 表示，Header 保存库表、事件类型和日志坐标等头部信息，RowChange 表示行变更内容，其中的 RowData 保存各行的变更前后列值。binlog 还包含事务标记、DDL（数据定义语句，如修改表结构）等事件，不能把所有事件都当成普通行增删改。

Canal 自带的消息发送功能从内部 Server 获取待确认批次，随后按消息格式和分区规则封装并发布。日志读取、事件解析与消息发布是不同阶段；本页只核对字段转换边界，发送确认及重试见 [RocketMQ 消息发布与事务边界](<./007_ES写入链路_Canal Server_07_RocketMQ消息发布与事务边界.md>)，持久化进度及恢复见 [进度确认与单机恢复](<./007_ES写入链路_Canal Server_08_进度确认与单机恢复.md>)。

### 3.3 项目所需字段与可选来源信息

按 [消息字段与事件身份](<./010_ES写入链路_ES_01_消息字段与事件身份.md>)，先区分处理业务记录必须保留的信息与按用途选择的来源信息，不将所有技术字段统一列为必填项：

- **定位与操作信息**：业务主键、操作类型、原始 `create_time`，以及区分来源所需的实例、库表信息。`create_time` 是记录最初创建时间，不能用事件时间或消息生成时间代替。destination（同步通道）的归属需通过约定的来源映射或转换逻辑识别，不能假定 FlatMessage 自带该字段。
- **新增、更新数据**：携带同次变更的行后业务数据及 `version`。仅新分表增加业务版本，旧表不增加；不得在重试时查询新数据却搭配旧版本。所需业务列是否完整仍须核对 binlog 行镜像配置及实际消息。
- **删除前信息**：保留删除前的 `consumeId` 和原始 `create_time`，用于消息契约、对账或状态判断；当前项目不把 MySQL DELETE 转为 ES 逐条 Delete，不为这一分支生成 ES 删除版本。
- **可选来源信息**：独立事件 ID、事务标识、完整 binlog 行坐标应按事件识别或追踪需求确定。独立 `eventId` 是否必要仍待确认，不能将其与业务版本防旧写混为一谈。Canal 批次 ID 只能用于批次追踪，不能保证跨重新分批后仍是同一事件的稳定身份。

若选择日志坐标构造事件 ID，需要来源实例、binlog 文件、事件位置及事件内原始行序号等信息，并在拆分导致信息丢失前保留。RocketMQ 消息 ID、Canal 批次 ID 或拆分后的行下标不能单独替代稳定源事件身份；这里是候选方案的前置条件，不表示已经选定或实现。

### 3.4 两种消息格式的字段边界

**行数据解析**

**FlatMessage JSON：** `data` 使用 INSERT/UPDATE 的行后列、DELETE 的行前列。UPDATE 的 `old` 根据变化列标记筛选旧列值，不是完整行前镜像。1.1.7 的转换方法在一个 Entry 的行循环外建立变化列集合，逐行累积使用，因此也不应将 `old` 解释为严格、独立的逐行变化列清单；多行更新须核对实际输出。

**二进制消息：** 先解码 Canal Packet/Messages，再解析 Entry 的 RowChange/RowData，读取变更前后列值。是否有完整业务列仍取决于源日志与解析结果，二进制封装不会自动补齐源头缺失的数据。

**来源信息**

**FlatMessage JSON：** 类中定义了 `gtid` 字段，转换代码从 Header 赋值，但不保证每条实际消息都有可用 GTID，也不能用 GTID 代替行级身份。原生结构没有 destination、binlog 文件名、事件偏移和原始行序号这些独立字段。`es` 来自事件执行时间，`ts` 是转换时的系统时间，均不等于业务记录的原始 `create_time`。

**二进制消息：** Entry 的 Header 可保留日志文件和偏移。进入按列值或主键分区的拆行分支时，每行被重建成单行 Entry，复制原 Header，但没有新增拆分前的行序号；不能只凭文件和偏移区分同一原始事件内的多行。

**拆分后的对应关系**

**FlatMessage JSON：** 分区代码按 `data` 中的行计算分区，并用同一下标取对应的 `old`。源码体现了这种配对方式，但项目仍须验证多行更新、空值和实际列集合下的行数据、旧值及业务版本是否正确对应。

**二进制消息：** 按键拆行复制对应 RowData，重新分组到目标分区；这不等于每条 MQ 消息只有一行，也不保留可直接当作原始日志行序号的拆分后下标。

依据：本次静态核对 [FlatMessage](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/FlatMessage.java) 的字段定义、[MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java) 的 `messageConverter` 和 `messagePartition`，以及 [CanalMessageSerializerUtil](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/util/CanalMessageSerializerUtil.java) 的封装与解码逻辑。

### 3.5 事务边界与特殊事件

JSON 的 `messageConverter` 跳过事务开始、结束 Entry。二进制进入 `messagePartition` 分区路径时同样过滤这些标记；未走该路径时，还需核对发布端传给序列化器的 `filterTransactionEntry` 参数。切换为二进制不能直接保证事务标记被保留。消息数、Entry 数和 ES 文档数没有固定的一一对应关系，一次拉取批次也不能直接视为一个完整源事务。

DDL、TRUNCATE、主键变更和未知事件应有明确的识别及处理策略，不能不加区分地进入普通行写入流程。源码分区方法将 DDL 放到分区 0，但这不会自动暂停其他分区或完成结构变更协调；项目具体暂停范围、恢复条件及主键变更处理仍待确认，不在这里新增已实现结论。

依据：上述 `MQMessageUtils` 的转换、分区方法和 `CanalRocketMQProducer` 对序列化器的调用。未执行多分区事务、DDL 或恢复实验。

## 4 参考文章与项目设计的区别

参考文章 [使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<./999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md>) 的版本、脚本和演示结果只代表文章环境，不能证明项目已经实现或验证。

**组件版本**

**参考文章：** 使用 Canal 1.1.6 和 Elasticsearch 8.4.2，演示从日志同步到 ES 冷热节点的完整链路。

**当前项目：** 使用 Canal Server 1.1.7 和 Elasticsearch 服务端 7.17.15，消息解析须按 1.1.7 核对，不能直接套用文章的 ES 8 API。ES 写入、索引定位和 ILM 规则由对应专题说明。

**同步范围**

**参考文章：** 以单个 `order_db.orders` 表为例，按 `order_no` 配置消息分区路由。

**当前项目：** 设计接入 `db0`、`db1` 两个同步通道及各自的分表，需要分别识别来源库表、维护 GTID 起点和消费进度，不能直接沿用单表实验的来源范围。

**消息解析方式**

**参考文章：** 消费代码将消息解析为 JSON，从 `data` 读取订单列表，再按 `type` 分派 INSERT、UPDATE、DELETE。

**当前项目：** 还需按 `canal.mq.flatMessage` 的实际取值核对 FlatMessage JSON 或二进制 Entry 的字段边界，区分 INSERT/UPDATE 后值、DELETE 前值，以及 UPDATE 中按变化列集合筛选、并非完整前镜像的 `old`，不能把 JSON 当二进制 Entry 解析。

**业务版本传递**

**参考文章：** `orders` 表已有 `version` 字段，并在更新示例中执行 `version = version + 1`。

**当前项目：** 设计是仅新分表增加业务 `version`，旧表不增加，新增、更新解析行后值中的版本，并确认多行拆分后每行数据与版本仍正确对应；DELETE 保留删除前的主键和原始创建时间，不生成 ES 逐条删除版本。文章示例不能证明这些项目约束已经实现。

**特殊事件处置**

**参考文章：** 消费代码遇到 DDL 或无法识别的消息类型时直接返回。

**当前项目：** 要求明确 DDL、TRUNCATE、主键变更及未知事件的暂停或处置方式，并核对事务标记在分区、拆行后是否保留，不能默认一条消息就是一个完整事务。具体处置和消息边界仍待实现核验。

**验证状态**

**参考文章：** 展示了消息进入 RocketMQ 和写入 ES 的实验效果。

**当前项目：** 本次未取得实际发行包生成的消息样例及对应运行验证记录，字段完整性、拆行后的来源身份、事务标记和发送结果仍需验证，不能用文章演示替代项目证据。

## 5 待确认或待验证事项

- 实际发行包加载的 `canal.mq.flatMessage` 值与消费端解码方式是否一致？
- 实际 binlog 行镜像及消息转换能否保留新增、更新所需的完整业务列？
- 同次变更的数据与业务 `version` 在多行拆分、重试和重投后是否仍正确对应？
- DELETE 的删除前主键和原始 `create_time` 是否完整传递？
- destination 与来源实例如何映射，两个通道是否可明确区分？
- 多行 UPDATE 的 `data`、`old` 列集合及行下标是否符合消费端预期？
- 是否需要独立事件级去重及 `eventId`？
- 如采用坐标事件 ID，如何在拆分前保留原始行序号并验证重放稳定性？
- 实际 JSON/二进制发布路径保留哪些事务标记，消费端是否误把消息当完整事务？
- DDL、TRUNCATE、主键变更及未知事件分别采用什么处置和恢复策略？

## 6 异议与分析

- **不将可选来源字段全部写成必需。** 原文将事务标识、完整行坐标和批次 ID 一并列为内部事件“至少需要”的信息；现有消息契约将独立事件身份视为待确认方案。已按业务定位、版本数据和可选追踪用途区分，避免把候选方案升级为已确认要求。
- **删除前值不等于 ES 删除版本。** 原文强调核对 DELETE 前值中的 `version`，容易混入已不采用的 ES 逐条删除分支。按现有项目文档，DELETE 重点保留删除前定位信息；这不代表要从原生消息中移除其他字段。
- **字段定义不等于字段可用。** 源码确认存在 `gtid` 字段，以及 JSON、二进制各自的转换方式；实际值、完整性和运行效果仍需实际发行包生成消息验证。

## 7 复核记录

已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本次完成两轮修改与一轮最终复查：第一轮整理字段契约、口述及源码依据，第二轮统一多行旧值和验证状态的表述，第三轮未发现剩余可执行修改项。本次仅修改本页，按 `java-interview-doc-review` 和 `self-check` 完成文档复核；源码静态核对不等于项目已实现或运行验证通过。未启动 Canal、MySQL、RocketMQ 或 Elasticsearch，未执行消息样例、联调或恢复测试。
