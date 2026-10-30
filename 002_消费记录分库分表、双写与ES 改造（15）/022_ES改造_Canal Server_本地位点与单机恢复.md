# 022_ES改造_Canal Server_本地位点与单机恢复

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。本文只说明本地位点、人工重启和主机文件故障恢复。

## 1 适用规则

本页沿用 [处理链路总览](<./012_ES改造_Canal Server_整体实现.md>) 的“已确认规则”；本页仅展开当前知识点直接需要的处理过程、边界和待验证事项。

## 2 面试口述精炼版本

**进度与恢复**

读取位置、Canal 批次确认、本地持久化位点、RocketMQ 消费位点和 ES 处理状态分别表示不同阶段。单机故障后要分别核对两个 destination 的位点、binlog 连续性和 MQ 积压；无法确认可靠起点时暂停，不能直接跳到最新 GTID。恢复效果仍需演练验证。

## 3 详细方案

### 3.1 本地位点与单机恢复

#### 3.1.1 本地持久化进度

本文采用 `file-instance.xml` 本地位点模板，按 destination 分别保存可恢复进度。原生生产端的提交回调通过内部 `ack` 更新消费游标，本地元数据落盘是另一个阶段；读取位置、内存游标和本地持久化游标不能混为一谈。具体文件路径、刷新时机与 destination/clientId 的对应关系须按 Canal 1.1.7 实际发行包核验。

发送状态风险见 [RocketMQ发布与Canal批次确认](<./021_ES改造_Canal Server_RocketMQ发布与Canal批次确认.md>)。消息已进入 MQ、ES 已写入或本地文件存在，都不能单独证明 Canal 已可靠保存恢复进度；故障前已处理但尚未落盘的部分可能重放，具体范围需联调验证，下游必须处理重复和旧事件。

#### 3.1.2 人工重启恢复

单机部署没有备用节点自动接管。Server 进程故障时，db0、db1 的采集和发布都会中断；MQ 中已有消息能否继续被下游消费，须另行核对 RocketMQ 和 ES 写入服务状态。恢复的预期流程如下：

1. 确认原 Server 进程已停止，避免同一 destination 被两个进程同时读取和发布。
2. 保留并分别核对 db0、db1 的本地持久化位点、源实例身份和配置，不删除元数据文件来强制重跑。
3. 检查两个源库在对应恢复位置之后的 binlog 是否仍可读且连续；位点缺失、文件损坏或日志断档时暂停复核。
4. 人工重启 Server，从各通道可核对的进度继续读取，并恢复原生 RocketMQ 发布；首次 GTID 基线只用于首次初始化，不能覆盖已有进度。
5. 核对两个通道的发布恢复、MQ 积压追赶和 ES 实际结果，下游按稳定来源版本与原物理索引处理重复和旧事件，独立事件去重按需采用。

#### 3.1.3 主机与文件故障边界

本地位点依赖保存文件的主机和存储。主机或磁盘故障导致位点不可读时，需要先核对能否恢复原进度及必要元数据；不能在另一台主机直接按最新 GTID 启动，也不能默认退回导入前基线。备份、文件恢复和人工迁移步骤仍需实现依据与演练，当前方案不承诺自动切换、零中断或零丢失。

监控与完整验收清单见 [监控与验收](<./023_ES改造_Canal Server_监控与验收.md>)。

### 3.2 版本核对依据与边界

- [Canal 1.1.7 canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties)及 [instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties)：已静态核对配置键、GTID 属性和默认 `file-instance.xml` 入口；[file-instance.xml](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/spring/file-instance.xml) 的本地落盘与恢复行为仍须按实际发行包核验。
- [Canal 1.1.7 Message](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/Message.java)、[CanalEntry](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/CanalEntry.java)及 [MysqlEventParser](https://github.com/alibaba/canal/blob/canal-1.1.7/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/mysql/MysqlEventParser.java)：消息模型、行事件解析和 slaveId 生成路径；原生 RocketMQ 生产路径仍需结合发行包核验。
- [CanalMQStarter](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/CanalMQStarter.java)及 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)：内部 clientId、空过滤表达式订阅、提交/回滚回调、分区发送及 SendResult 状态检查缺口。
- [FlatMessage](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/FlatMessage.java)、[MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java)及 [CanalMessageSerializerUtil](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/util/CanalMessageSerializerUtil.java)：JSON 字段、二进制封装、UPDATE old 范围、hash 字段及拆行后信息边界。
- [RocketMQ Spring 2.3.0 RocketMQTemplate](https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.0/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/core/RocketMQTemplate.java)及 [rocketmq-client 5.2.0 SendStatus](https://github.com/apache/rocketmq/blob/rocketmq-all-5.2.0/client/src/main/java/org/apache/rocketmq/client/producer/SendStatus.java)：有序发送签名和结果状态。

以上为已有官方版本源码的静态核对及待核验模板入口，未验证实际发行包、依赖树、MySQL 8.0.33 认证、本地位点落盘或单机重启恢复。

## 4 进度与增量校验边界

需要分别记录以下进度，不能用其中一类替代另一类：

| 进度 | 含义 | 不能替代 |
| --- | --- | --- |
| MySQL Binlog 位置 | Server 读取到的源日志范围 | MQ 发送成功或 ES 写入成功 |
| Canal 内部批次确认 | Server 对读取批次的确认状态 | RocketMQ Broker 持久化 |
| RocketMQ 消费位点 | 消费者已确认的消息范围 | ES 文档处理结果 |
| ES 处理状态 | 下游对具体写入结果的记录 | Canal 源日志已经安全交接 |

消费位点和 ES 处理结果不能代替源日志恢复起点；增量校验具体采用哪些业务进度和事件信息见下方关联文档。

## 5 关联文档

- [RocketMQ发布与Canal批次确认](<./021_ES改造_Canal Server_RocketMQ发布与Canal批次确认.md>)
- [999_ES改造_ES_新表与 ES 增量校验_增量比较与版本判断](<./999_ES改造_ES_新表与 ES 增量校验_增量比较与版本判断.md>)

## 6 待确认或待验证事项

- 各 destination 的文件路径、刷新时机、权限及 clientId 映射是否与实际发行包一致？
- 位点损坏或日志过期时能否恢复可靠起点？未确认前应停止恢复。

## 7 参考文章与项目设计的区别

**参考文章：** 演示发送和写入效果。

**当前项目：** 单机本地位点依赖文件和存储恢复，需额外演练，不能据文章承诺自动接管或零丢失。

## 8 复核记录

已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本轮按主题归属去重并复核标题、编号、引用和示例；未执行 MySQL、Canal、RocketMQ 或 ES 联调。
