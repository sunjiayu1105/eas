# 999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_RocketMQ发布与Canal批次确认

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与适用边界

- 本文只说明消息进入 RocketMQ 后的发布结果、Canal 批次确认、回滚和可靠性边界；读取与过滤见 [源库连接与Binlog过滤](<./999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_源库连接与binlog过滤.md>)，消息格式见 [消息格式与字段边界](<./999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_消息格式与字段边界.md>)，发送实现见 [RocketMQ生产端实现](<./999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_RocketMQ生产端实现.md>)。
- 两个 destination 使用固定 topic，共用同一消息契约和消费逻辑；`partitionHash` 源列为 `consume_id`。
- 本文描述 Canal 1.1.7 源码静态核对和设计边界，不表示 Broker 持久化、批次确认或故障恢复已经运行验证。

## 2 面试口述精炼版本

**发布结果与批次确认分开判断**

Canal 内置生产端把一个读取批次拆成一条或多条 RocketMQ 消息，等待本轮发送任务结束后再回调 Canal。无异常时进入提交路径，异常时回滚；但 `SendResult` 非 `SEND_OK` 且没有抛异常的处理仍需核验。Canal 批次确认、RocketMQ 持久化、下游消费和 ES 写入是不同阶段，不能把其中一个阶段的结果当成整条链路成功。

## 3 详细方案

### 3.1 发布批次与 Canal 回调

`CanalMQStarter` 为每个 destination 使用内部 `clientId` 调用 `getWithoutAck` 获取批次，把批次交给 `CanalRocketMQProducer`。生产端完成消息转换和分区发送后，通过回调通知 Server：

```java
Message message = canalServer.getWithoutAck(clientIdentity, batchSize, timeout,
    TimeUnit.MILLISECONDS);
long batchId = message.getId();

canalMQProducer.send(destination, message, new Callback() {
    @Override
    public void commit() {
        // 仅确认 Canal 已处理这个内部批次，不等于 ES 已写入
        canalServer.ack(clientIdentity, batchId);
    }

    @Override
    public void rollback() {
        // 允许 Canal 后续重新读取这个批次；已进入 MQ 的消息不会自动撤回
        canalServer.rollback(clientIdentity, batchId);
    }
});
```

### 3.2 发送结果与提交条件

源码路径中，生产端等待分区发送任务结束后，主要根据是否抛出异常决定提交或回滚。单条和批量发送都取得 `SendResult`，但当前静态核对到的分支只记录结果，没有显式判断 `getSendStatus() != SEND_OK`；因此下列情况仍需用实际发行包和运行测试确认：

- 发送调用返回非 `SEND_OK` 且没有抛异常时，是否仍进入提交回调；
- Broker 持久化、复制和结果不明时，生产端如何区分失败与已发送；
- 发送重试是否产生重复消息，重复消息如何由消费端幂等处理。

不能把 `callback.commit()` 表述为“所有消息已可靠落盘”，也不能把 Dashboard 中出现消息表述为 Canal 位点已经可靠持久化。

### 3.3 部分成功、超时与顺序

一个 Canal 批次可能拆成多个分区任务。部分分区已经成功、其他分区异常时，Canal 批次可能回滚，但已经进入 RocketMQ 的消息不会自动撤回，后续重试可能重复发送；超时还可能造成结果不明。消费端需要依靠稳定来源信息、业务版本和幂等规则处理重复及旧事件。

队列稳定性与并发消费顺序的区别见 [Topic路由与多行分区](<./999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_Topic路由与多行分区.md>)。

### 3.4 事务和阶段边界

MySQL 事务与消息边界见 [事务边界与特殊事件](<./999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_事务边界与特殊事件.md>)。这里仅区分发送回调和后续状态：提交 Canal 批次不会等待下游 ES 写入；本地元数据可靠落盘是恢复专题需要验证的另一阶段。

下游暂停或 MQ 消息过期时，不能因为源库仍保留 Binlog 就直接断言可以正常补回；应先核对 Canal 位点、MQ 缺口和受控重放范围。详细恢复流程见 [本地位点与单机恢复](<./999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_本地位点与单机恢复.md>)。

## 4 参考文章与项目设计的区别

**参考文章：** 主要展示消息发送到 RocketMQ 和写入 ES 的实验结果，没有展开 Canal 批次提交、异常回滚、非 `SEND_OK` 和部分发送成功后的处理边界。

**当前项目：** 明确区分消息发布、Broker 持久化、Canal 批次确认、MQ 消费和 ES 写入阶段；这些阶段的实际依赖、结果不明恢复和重复消息处理仍待联调验证。

## 5 待确认或待验证事项

- 非 `SEND_OK` 且无异常时是否仍提交 Canal 批次？
- 部分分区成功、超时或结果不明时，重试和恢复顺序是否符合预期？
- Broker 持久化、消息留存和消费位点是否覆盖下游暂停与追赶窗口？

## 6 复核记录

已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本轮按主题归属去重并复核标题、编号、引用和示例；未执行 MySQL、Canal、RocketMQ 或 ES 联调。

本文按 [java-interview-doc-review-loop](C:\Users\Administrator\.codex\skills\java-interview-doc-review-loop\SKILL.md) 整理，已移除与 017、018、020 重复的读取、消息格式和分区实现细节，只保留发布结果、Canal 回调和阶段边界。未执行 Canal、RocketMQ 或 ES 联调。

## 7 参考来源

- [CanalMQStarter（Canal 1.1.7）](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/CanalMQStarter.java)：支持本文关于 `getWithoutAck`、`ack` 和 `rollback` 回调的说明。
- [CanalRocketMQProducer（Canal 1.1.7）](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)：支持本文关于分区任务等待、`SendResult` 获取和异常处理边界的说明。
