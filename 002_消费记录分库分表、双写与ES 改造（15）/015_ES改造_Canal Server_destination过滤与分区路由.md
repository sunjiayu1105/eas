# 015_ES改造_Canal Server_destination过滤与分区路由

以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准。

## 1 适用规则

沿用 [处理链路总览](<./012_ES改造_Canal Server_处理链路总览.md>) 的已确认规则；下述内容属于项目设计，未表示已部署或通过运行验证。

## 2 面试口述精炼版本

每个 destination（同步通道）分别配置固定 topic 和消息路由；源库连接、表过滤和 GTID 起点见 [Binlog读取与过滤范围](<./016_ES改造_Canal Server_Binlog读取与过滤范围.md>)。两个 topic 共用同一消息契约和消费逻辑。按 `consume_id` 选择队列，但同键入队不等于并发消费按顺序完成。分区数和队列映射仍待验证。

## 3 详细方案

### 3.1 destination 配置示意

~~~properties
# source connection, GTID and filter settings are documented in 016
canal.mq.topic=<db0-topic>
canal.mq.partitionHash=eas\\.consume_record_(0[1-9]|1[0-6]):consume_id
canal.mq.partitionsNum=<positive-partition-count>
~~~

两个 destination 分别填写自己的固定 topic；topic、字段值、分区数和队列列表稳定，才可核对同键是否始终到同一队列。源库地址、GTID、过滤正则和复制身份由 016 统一说明，本文不重复展开。

### 3.2 消息分区与来源顺序

消费端候选 `orderKey` 为 `destination + database + table + consumeId`，用于内部顺序状态；它不是原生生产端直接拼接的 hash 字符串。1.1.7 的 `partitionHash` 按库表规则匹配源列，DELETE 使用删除前列，INSERT/UPDATE 使用变更后列；开启 `canal.mq.database.hash` 时还混入库名。topic、字段值、分区数和队列列表稳定，才可核对同键是否始终到同一队列。消费端采用 `CONCURRENTLY`（并发消费），单次回调仍同步等待当前消息内的 ES 结果；同键消息可能并发或乱序完成，由 ES 按来源版本拒绝旧写入。Tag 只做过滤。

当前设计已确定使用 MySQL 源列 `consume_id`，INSERT、UPDATE 取变更后值，DELETE 取删除前值；仍须核对三类事件均有值，不能把 ES 的驼峰字段直接当作 MySQL 列名。分区数必须明确为正数，并核对与实际 topic 队列列表的映射；不能只写 `partitionHash` 就宣称已实现业务键分散到多个队列。

同一 destination 同时只启动一个 Server 读取和发布，不能在旧进程仍运行时另起重复进程。原生生产端的队列映射、topic 队列数量、重试和重启恢复都需验证路由与来源信息保持稳定。重放或并发处理时，消费端仍须携带稳定来源版本拒绝旧写入；业务版本只比较同一记录的新旧，记录迁移时须保持版本连续性。

分区数、队列选择、并发消费线程和 topic/tag 的实际配置属于待确认实现项；不能直接照搬参考文档的固定分区下标或自动获取分区数示例。

## 4 参考文章与项目设计的区别

参考文章 [使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<./999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md>) 的版本、脚本和演示结果只代表文章环境，不能证明项目已经实现或验证。

### 2. MySQL 与 Canal 版本及起点不同

- 文章使用 MySQL 8.0.29、Canal 1.1.6，并在单个实例配置文件中填写 binlog 文件名和 position；当前项目按 MySQL 8.0.33、Canal Server 1.1.7 说明，两个 destination 分别维护源库、过滤规则、导入前 GTID 基线和本地进度。
- 文章示例通过 `canal.instance.master.journal.name` 和 `canal.instance.master.position` 从固定日志位置启动；当前项目首次同步使用导入前 GTID 基线，已有可核对位点不能被基线或最新 GTID 覆盖，具体落盘和恢复行为仍待实际发行包验证。
- 文章的数据库用户、密码、库表和 `order_db.orders` 示例不能直接用于当前项目；当前项目的复制账号、真实源列、权限和 binlog 连续性仍需按两个源库分别核对。

### 3. 过滤、分区与消息路由不同

- 文章监听 `order_db\.orders.*`，以 `order_no` 作为 `partitionHash`，并示例使用 8 个分区和动态获取队列数；当前项目按 `eas\.consume_record_(0[1-9]|1[0-6])` 的 16 张分表配置候选范围，每个 destination 使用自己的固定 topic，两个 topic 共用同一消息契约和消费逻辑，`partitionHash` 源列确定为 `consume_id`，正数分区数和稳定路由仍待验证。
- 文章的分区示例用于说明同一订单进入同一队列；当前项目已确定使用 MySQL 源列 `consume_id`，但仍须分别核对两个 destination 的队列映射及 INSERT、UPDATE、DELETE 的实际取值。
- 文章示例把配置和运行结果放在同一实验链路中；当前项目采用 Server 内置 RocketMQ 生产端，不另设独立 TCP 投递组件，MQ 工作线程的订阅、批次确认和重试边界仍按 Canal 1.1.7 发行包核验。

## 5 待确认或待验证事项

首次 GTID 基线、过滤范围、复制身份、分区数及队列映射仍需在两个源库分别验证；topic 按 destination 分开、消息契约和消费逻辑共用、`partitionHash` 源列为 `consume_id` 已确定。
