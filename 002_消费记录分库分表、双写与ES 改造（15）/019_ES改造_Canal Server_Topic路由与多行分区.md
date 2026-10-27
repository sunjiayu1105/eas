# 019_ES改造_Canal Server_Topic路由与多行分区

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 适用规则

沿用 [处理链路总览](<./012_ES改造_Canal Server_处理链路总览.md>) 的已确认规则；下述内容属于项目设计，未表示已部署或通过运行验证。

## 2 面试口述精炼版本

每个 destination（同步通道）分别配置固定 topic 和消息路由；源库连接、表过滤和 GTID 起点见 [源库连接与Binlog过滤](<./015_ES改造_Canal Server_源库连接与Binlog过滤.md>)。两个 topic 共用同一消息契约和消费逻辑。按 `consume_id` 选择队列，但同键入队不等于并发消费按顺序完成。分区数和队列映射仍待验证。

## 3 详细方案

### 3.1 destination 配置示意

~~~properties
# source connection, GTID and filter settings are documented in 015
canal.mq.topic=<db0-topic>
canal.mq.partitionHash=eas\\.consume_record_(0[1-9]|1[0-6]):consume_id
canal.mq.partitionsNum=<positive-partition-count>
~~~

两个 destination 分别填写自己的固定 topic；分区稳定条件统一见 3.2。源库地址、GTID、过滤正则和复制身份由 015 统一说明，本文不重复展开。

### 3.2 消息分区与来源顺序

1.1.7 的 `partitionHash` 按库表规则匹配源列；开启 `canal.mq.database.hash` 时还混入库名。同键稳定映射要求 topic、源列值、分区数、队列列表和映射算法稳定。Tag 只做过滤，不是业务顺序保障。

消费端候选 orderKey 为 destination + database + table + consumeId，用于内部顺序状态，不是原生生产端的 hash 字符串。并发消费可能乱序完成；消费线程和 ES 旧版本保护详见 [RocketMQ 消费模型](<./025_ES改造_RocketMQ_RocketMQ 消费模型.md>)。

当前设计已确定使用 MySQL 源列 `consume_id`，INSERT、UPDATE 取变更后值，DELETE 取删除前值；仍须核对三类事件均有值，不能把 ES 的驼峰字段直接当作 MySQL 列名。分区数必须明确为正数，并核对与实际 topic 队列列表的映射；不能只写 `partitionHash` 就宣称已实现业务键分散到多个队列。

同一 destination 的唯一读取进程与重放约束见 [本地位点与单机恢复](<./022_ES改造_Canal Server_本地位点与单机恢复.md>)。分区数尚未确定，不能把文章的 8 当作项目参数。

### 3.3 多行 FlatMessage 的拆分结果

分区对每个 FlatMessage 的 data 逐行计算，不把不同 type 的对象合并。以同一 UPDATE 对象中的 c-1002、c-1004 为例，队列结果仅作假设，不代表实际 hash 值：

```text
输入：UPDATE data=[c-1002 新值, c-1004 新值]
             old=[c-1002 旧值, c-1004 旧值]

若两行落在同一分区：
消息 A：data=[c-1002 新值, c-1004 新值]
        old=[c-1002 旧值, c-1004 旧值]

若两行落在不同分区：
消息 A：data=[c-1002 新值]，old=[c-1002 旧值]
消息 B：data=[c-1004 新值]，old=[c-1004 旧值]
```

每条消息体仍序列化一个 FlatMessage。拆分由 Canal 生产端完成；RocketMQ 不解析 data 后替业务拆行。多行配对、空值与实际映射需抓取真实消息核验。字段含义只在 [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>) 展开。

## 4 参考文章与项目设计的区别

参考文章 [使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<./999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md>) 的版本、脚本和演示结果只代表文章环境，不能证明项目已经实现或验证。

### 4.1 分区与消息路由

双方都按业务键选择队列，实际配置各不相同。

**参考文章：** 监听 order_db.orders，以 order_no 为分区键，示例分区数为 8。

**当前项目：** 两个 destination 分别用固定 topic，以 consume_id 为源列；分区数与队列映射待验证，不照搬文章参数。读取范围见 015，消息契约见 018。

## 5 待确认或待验证事项

分区数、队列列表、三类事件的分区源列取值及队列映射仍需验证；topic 按 destination 分开、消息契约和消费逻辑共用、`partitionHash` 源列为 `consume_id` 已确定。

## 6 复核记录

已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本轮按主题归属去重并复核标题、编号、引用和示例；未执行 MySQL、Canal、RocketMQ 或 ES 联调。

## 7 参考来源

- [MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java)：按行分区及 data/old 同下标复制。
- [instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties)：topic、partitionHash 和 partitionsNum 配置入口。
