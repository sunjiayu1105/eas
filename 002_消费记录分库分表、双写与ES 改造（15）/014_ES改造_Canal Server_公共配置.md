# 014_ES改造_Canal Server_公共配置

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 适用规则

沿用 [处理链路总览](<./012_ES改造_Canal Server_整体实现.md>) 的已确认规则；下述内容属于项目设计，未表示已部署或通过运行验证。

## 2 面试口述精炼版本

**说明公共配置文件职责**

Canal Server 的全局配置文件是解压目录下的 conf/canal.properties，负责选择运行模式、加载哪些 destination 和使用哪种位点模板；每个 destination 的源库连接和表过滤写在对应的 conf/<destination>/instance.properties。RocketMQ 连接参数见本页 3.2，生产端实现见 [RocketMQ生产端实现](<./020_ES改造_Canal Server_RocketMQ生产端实现.md>)。这些配置只说明启动入口和参数边界，不能证明参数已被实际加载或消息已成功发送。

## 3 详细方案

### 3.1 公共配置示意

以下配置写在 Canal Server 的全局配置文件 `conf/canal.properties` 中，路径相对于 Canal Server 解压目录。该文件统一配置 Server 的运行模式、要加载的 destination（数据同步通道）、实例使用的位点模板和 RocketMQ 连接参数；每个通道专属的源库连接、表过滤等配置则写在 `conf/<destination>/instance.properties` 中。

这组公共配置先决定 Canal Server 如何运行，再指定它加载哪些同步通道和如何保存读取进度。`canal.serverMode` 选择运行模式，`canal.destinations` 指定同一进程承载的同步通道，`canal.instance.global.spring.xml` 指定通道实例使用的位点模板。消息字段不在本页展开，RocketMQ 参数见 3.2，不能由配置存在推断消息字段和发送结果已经验证。

以下为项目启动模式与通道配置示意，实际加载结果仍待核对：

~~~properties
canal.serverMode=rocketMQ
canal.zkServers=
canal.destinations=db0,db1
canal.auto.reset.latest.pos.mode=false
canal.instance.global.spring.xml=classpath:spring/file-instance.xml
~~~

以上属性键已对照 Canal 1.1.7 的官方配置核对；官方 `canal.properties` 默认选择 `file-instance.xml`，本文沿用这一配置入口，采用本地元数据保存消费进度。实际发行包中的模板、文件路径、刷新时机、目录权限及落盘行为仍需核验；配置文件存在不等于位点已经可靠保存。

`canal.auto.reset.latest.pos.mode=false` 针对找不到已指定 binlog 位置后的自动重置路径；首次无有效检查点、且未正确配置初始基线时，仍不能据此承诺一定拒绝从最新位置初始化。启动前必须核对首次 GTID 基线或既有持久化游标，发现缺失就停止启动并复核。

### 3.2 RocketMQ 连接与生产任务参数

以下均写在 conf/canal.properties。尖括号是待填写参数，不是可直接运行的配置；线程数、批次大小和重试次数需按部署确认。Java properties 的说明单独写在注释行，不能把行尾注释混入属性值。

```properties
# 项目选择 JSON；运行加载结果待验证
canal.mq.flatMessage=true
# RocketMQ 连接参数
rocketmq.producer.group=<producer-group>
rocketmq.namesrv.addr=<namesrv-address>
# 以下容量与重试值未确认
canal.mq.canalBatchSize=<batch-size>
canal.mq.canalGetTimeout=<timeout-ms>
canal.mq.send.thread.size=<send-threads>
canal.mq.build.thread.size=<build-threads>
rocketmq.retry.times.when.send.failed=<retry-count>
rocketmq.vip.channel.enabled=false
```

批次大小不等于最终 RocketMQ 消息数。内部发送线程参数也不是下游消费者线程数。路由键和固定 topic 见 [Topic路由与多行分区](<./019_ES改造_Canal Server_Topic路由与多行分区.md>)；消息字段见 [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>)；提交回滚见 [RocketMQ发布与Canal批次确认](<./021_ES改造_Canal Server_RocketMQ发布与Canal批次确认.md>)。

## 4 参考文章与项目设计的区别

参考文章把 Canal 配置、RocketMQ 集群和 ES 8 实验环境放在一条可运行示例中；当前项目只确认公共配置的设计边界，版本、参数加载和消息服务连接仍需按实际环境核对。

## 5 待确认或待验证事项

全局配置是否实际加载、参数类型是否正确、线程及超时容量是否合适，仍待验证。本地文件权限和恢复效果见恢复专题。

## 6 复核记录

已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本轮按主题归属去重并复核标题、编号、引用和示例；未执行 MySQL、Canal、RocketMQ 或 ES 联调。

## 7 参考来源

- [canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties)：运行模式、MQ 连接和生产任务参数。

## 8 异议与分析

旧 020 示例的 canal.serverMode=tcp 与本页及项目内置 RocketMQ 模式不一致。本轮统一由本页定义 rocketMQ 模式，移除发送实现文档中的重复配置；实际实例仍需核验。旧 properties 示例的行尾注释可能成为属性值的一部分，已改成独立注释行。
