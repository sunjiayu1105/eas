# 019_ES改造_Canal Server_事务边界与特殊事件

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与适用边界

- 本文只说明事务标记、消息边界和 DDL 等特殊事件；日志解析见 [Binlog读取与Entry解析](<./017_ES改造_Canal Server_Binlog读取与Entry解析.md>)，字段格式见 [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>)。
- 一条 MQ 消息不直接等同于完整源事务；特殊事件的具体暂停、恢复和隔离策略仍待确认。

## 2 面试口述精炼版本

**区分消息边界和事务边界**

JSON 转换会跳过事务开始、结束事件；二进制进入按键分区路径时也会过滤这些标记，并可能把同一事务拆到不同消息。因此不能把一条消息或一次拉取批次当作完整事务，也不能用拆分后行下标代替原始日志行序号。DDL、TRUNCATE、主键变更和未知事件要单独处置，具体策略和恢复条件仍待确认。

## 3 详细方案

### 3.1 事务标记与消息边界

JSON 的 `messageConverter` 跳过事务开始、结束 Entry。二进制进入 `messagePartition` 分区路径时同样过滤这些标记；未走该路径时，还需核对发布端传给序列化器的 `filterTransactionEntry` 参数。切换为二进制不能直接保证事务标记被保留。

消息数、Entry 数和 ES 文档数没有固定的一一对应关系；一次拉取批次也不能直接视为一个完整源事务。同一事务的数据可能因按键分区进入不同消息，消息中的拆分下标也不是原始日志行序号。

依据：[MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java) 和 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)。本次未执行多分区事务实验。

### 3.2 特殊事件

DDL、TRUNCATE、主键变更和未知事件不能不加区分地进入普通行写入流程。源码分区方法将 DDL 放到分区 0，但这不会自动暂停其他分区或完成结构变更协调；项目具体暂停范围、恢复条件及主键变更处理仍待确认。

## 4 参考文章与项目设计的区别

**特殊事件处置**

**参考文章：** 消费代码遇到 DDL 或无法识别的消息类型时直接返回。

**当前项目：** 需要明确 DDL、TRUNCATE、主键变更和未知事件的识别、暂停或恢复策略，不能默认一条消息就是完整事务。

**验证状态**

**参考文章：** 展示了消息进入 RocketMQ 和写入 ES 的实验效果。

**当前项目：** 本次拆分只完成文档和源码静态核对，实际消息样例、事务标记、特殊事件和发送结果仍需验证，不能用文章演示替代项目证据。

## 5 待确认或待验证事项

- 实际 JSON/二进制发布路径保留哪些事务标记？
- 消费端是否会误把一条消息当作完整事务？
- DDL、TRUNCATE、主键变更及未知事件分别采用什么处置和恢复策略？
- 多分区结构变更如何隔离并协调恢复？

## 6 复核记录

本文由 [java-interview-doc-split](C:/Users/Administrator/.codex/skills/java-interview-doc-split/SKILL.md) 拆分生成；未执行 Canal、MySQL、RocketMQ 或 Elasticsearch 联调、故障演练或恢复测试。
