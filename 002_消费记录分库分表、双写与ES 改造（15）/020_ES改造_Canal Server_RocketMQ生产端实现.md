# 020_ES改造_Canal Server_RocketMQ生产端实现

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与适用边界

- 本文只说明 Canal Server 将 binlog 事件转换并发送到 RocketMQ 的核心实现；发布结果、Canal 批次提交与回滚影响见 [RocketMQ发布与Canal批次确认](<./021_ES改造_Canal Server_RocketMQ发布与Canal批次确认.md>)。
- 当前项目采用 Canal Server 内置 RocketMQ 生产端，不另设独立 TCP 投递组件；本文描述的是 Canal 1.1.7 源码路径，未表示实际发行包已部署或验证。
- Canal 1.1.7 官方构建资料声明使用 RocketMQ 客户端 `4.8.0`；该结论用于记录官方构建口径，不据此断言当前运行实例的 JAR 已加载。
- 当前项目规划使用 RocketMQ Broker `5.2.0`；这是已确认的设计选择，实际部署和兼容性仍需验证。
- 当前项目设计上采用 FlatMessage JSON：要求 `canal.mq.flatMessage=true`，自定义 ES 消费服务按 JSON 解析；实际运行配置、真实消息样例和端到端解析结果仍待验证。
- 两个 destination 分别使用固定 topic；两个 topic 共用同一消息契约和消费逻辑。
- `partitionHash` 源列确定为新表的 `consume_id`；INSERT 和 UPDATE 使用变更后的值，DELETE 使用删除前的值。
- 本文的“3 详细方案”按 Canal 初学者能够连续理解的顺序编写：按批次获取、对象准备、转换和发送顺序展开，批次确认由 021 负责；示例代码和配置仍需与实际发行包、部署配置分别核对。

## 2 面试口述精炼版本

**内置生产任务取批次并发送**

CanalMQStarter 为每个 destination 启动后台 worker，取出未确认批次交给 CanalRocketMQProducer。生产端准备 EntryRowData，转换 FlatMessage，按配置分区，再逐个序列化成 RocketMQ 消息并等待发送任务结束。发送结果交给批次回调，可靠性边界见 [RocketMQ发布与Canal批次确认](<./021_ES改造_Canal Server_RocketMQ发布与Canal批次确认.md>)；实际发行包和联调仍待验证。

## 3 详细方案

### 3.1 批次获取与内部身份

第一步是取数据。CanalMQStarter 为每个 destination 创建一个内部生产任务，使用内部 `clientId` 调用 `getWithoutAck`，从嵌入式 Canal Server 取出一批“尚未确认”的变更。`getWithoutAck` 的含义是“先取出来处理，处理成功后再确认”；这个 `clientId` 是 Canal 内部消费游标，不是 MySQL 复制使用的 `slaveId`，也不是 RocketMQ 消费者身份。

Canal 批次由 batchId 标识，可以包含多个 Entry 和多条 RowData。它不是 MySQL 事务编号，也不是 RocketMQ 消息 ID。worker 是常驻工作线程，不是固定周期定时任务。

### 3.2 方法调用与消息构造

1. CanalRocketMQProducer.send(...) 接收批次，调用 MQMessageUtils.buildMessageData(...) 准备 EntryRowData[]。
2. JSON 路径调用 messageConverter(...)，按有效事件生成 FlatMessage；不会将整个批次直接拼成一个 JSON 数组。
3. 配置分区哈希时调用 messagePartition(...)，具体键值和拆行示例见 [Topic路由与多行分区](<./019_ES改造_Canal Server_Topic路由与多行分区.md>)。
4. 对待发送 FlatMessage 调用 JSON.toJSONBytes(flatMessage, JSONWriter.Feature.WriteNulls)，构造 RocketMQ Message。
5. sendMessage(...) 调用 Producer 发送到目标队列，等待本轮发送任务结束，再交给提交或回滚回调。

字段与行值的定义见 [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>)；完整配置见 [公共配置](<./014_ES改造_Canal Server_公共配置.md>)。当前项目已确认的格式开关为 canal.mq.flatMessage=true。源码中的批量发送是发送多个 Message，不代表一个 body 中包含多个 FlatMessage。

### 3.3 核心调用示意

以下是方法关系伪代码，用于阅读源码，不是可直接编译的应用代码；实际参数以 Canal 1.1.7 为准。

```java
// worker 持续取尚未确认的批次；callback 的 ack/rollback 见批次确认专题
Message batch = canalServer.getWithoutAck(
    clientIdentity, batchSize, timeout, TimeUnit.MILLISECONDS);
if (batch.getId() != -1 && hasEntries(batch)) {
    canalMQProducer.send(destination, batch, callback);
}

// 下列是生产端内部的转换关系示意，辅助函数省略真实参数
EntryRowData[] rows = buildMessageData(batch);
for (FlatMessage flat : messageConverter(rows, batch.getId())) {
    // 按配置分区；未配置分区哈希时走默认队列路径
    for (FlatMessage routed : routeByConfiguration(flat)) {
        // 一个 FlatMessage 对象对应一个 Message body
        byte[] body = JSON.toJSONBytes(routed, JSONWriter.Feature.WriteNulls);
        Message mqMessage = new Message(topic, body);
        submitSendTask(mqMessage, targetQueue);
    }
}
waitForPartitionTasks();
// 回调条件和 SendResult 风险不在此伪代码中简化为可靠落盘
```

Canal Message 与 RocketMQ Message 是不同包中的类型，伪代码为便于阅读省略全限定名。队列选择按分区和实际队列列表映射；批量发送读取队列列表，列表不可用时的退化行为需按实际发行包验证。

## 4 参考文章与项目设计的区别

**投递组件**

参考文章和项目都使用 Canal 内置 RocketMQ 生产端。

**参考文章：** 展示实验环境的发送效果。

**当前项目：** 采用 Canal 1.1.7；官方构建记录的内置 RocketMQ 客户端为 4.8.0，规划 Broker 为 5.2.0，实际加载依赖和兼容性待验证。字段对比见消息格式专题，提交回滚对比见批次确认专题。

## 5 待确认或待验证事项

- 实际发行包是否按上述方法调用链运行，加载的客户端 JAR 是否符合构建记录？
- 内置客户端 4.8.0 与 Broker 5.2.0 的单条/批量发送、认证及异常路径是否兼容？
- 发送线程、批次大小、超时和任务等待是否符合部署要求？

## 6 复核记录

本轮按 [java-interview-doc-review-loop](C:\Users\Administrator\.codex\skills\java-interview-doc-review-loop\SKILL.md) 复核了面向 Canal 初学者的讲解顺序，新增角色说明和“四步链路”概览，并在配置与代码前补充了阅读提示。技术结论、来源和未验证边界未改变；未执行 Canal、RocketMQ 或 ES 联调。

本轮修正了第 3 章新增小节的连续编号，将角色说明和后续步骤统一为 `3.1`～`3.7`。

本轮补充了 CanalMQStarter、Canal 批次和 CanalRocketMQProducer 三者的传递关系，以及发送完成后的确认/回滚回调关系；未改变原有技术结论。


已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本轮按主题归属去重并复核标题、编号、引用和示例；未执行 MySQL、Canal、RocketMQ 或 ES 联调。

## 7 参考来源

### 7.1 Canal Server 内置生产链路

- [CanalMQStarter（Canal 1.1.7）](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/CanalMQStarter.java)：支持本文关于 destination 内部生产任务、内部 clientId、`getWithoutAck` 获取批次以及提交、回滚回调的说明。
- [CanalRocketMQProducer（Canal 1.1.7）](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)：支持本文关于消息格式转换、分区发送、RocketMQ Producer 调用以及发送完成后触发回调的说明。
- [MQMessageUtils（Canal 1.1.7）](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java)：支持本文关于 FlatMessage 转换、按分区拆分和消息封装边界的说明。

### 7.2 配置与消息格式

- [Canal 1.1.7 canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties)：支持本文关于 `canal.mq.flatMessage`、RocketMQ 连接参数和 Canal Server 全局配置入口的说明。
- [Canal 1.1.7 instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties)：支持本文关于 destination 实例配置与 MQ topic、分区相关参数的来源核对。
- [Canal 1.1.7 官方构建 POM（工作区保存副本）](<../tmp/canal-doc-corrections-20261007/sources/canal-pom.xml>)：其中声明 Canal 版本为 `1.1.7`、`rocketmq_version` 为 `4.8.0`，用于确认官方构建口径；不据此断言当前运行实例的 JAR 已加载。
- [工作区 Canal 配置模板](<../tmp/canal-doc-corrections-20261007/sources/canal.properties>)：其中将 `canal.mq.flatMessage` 设为 `true`，用于确认模板默认口径；该文件不是当前运行实例的实际加载配置。
- [FlatMessage（Canal 1.1.7）](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/FlatMessage.java)：支持本文关于 FlatMessage JSON 字段模型的说明；字段是否在当前部署中实际输出，仍需运行验证。
- [CanalMessageSerializerUtil（Canal 1.1.7）](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/util/CanalMessageSerializerUtil.java)：支持本文关于 Canal 二进制消息序列化边界的说明。

### 7.3 参考文章与验证边界

- [使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<./999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md>)：用于核对参考文章中的 Canal、RocketMQ 实验环境和消息发送示例；文章版本、脚本、参数和演示结果不代表当前项目已经部署或验证。

上述来源用于支持源码和文章的静态核对，不等于当前项目已经完成发行包验证、Broker 持久化验证或 Canal、RocketMQ、ES 联调。
