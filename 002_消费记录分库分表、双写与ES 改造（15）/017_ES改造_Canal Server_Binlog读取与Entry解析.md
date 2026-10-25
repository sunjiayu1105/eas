# 017_ES改造_Canal Server_Binlog读取与Entry解析

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与适用边界

- 本文只说明 Canal Server 读取 binlog、过滤范围和 Entry 解析；消息格式见 [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>)，事务与特殊事件见 [事务边界与特殊事件](<./019_ES改造_Canal Server_事务边界与特殊事件.md>)。
- 项目设计以 `db0`、`db1` 两个 destination 及各自的分表为范围，未表示已经部署或完成运行验证。

## 2 面试口述精炼版本

**读取日志并解析事件**

Canal Server 使用复制账号读取 MySQL binlog，先按库表过滤范围，再把事件解析为 Entry。Entry 的 Header 保存库表、事件类型和日志坐标，RowChange、RowData 保存行变更内容；读到事件只说明完成解析，不代表已经发送到 RocketMQ。配置、字段完整性和恢复效果仍待验证。

## 3 详细方案

### 3.1 读取与解析

Server 使用复制账号连接 MySQL，读取 binlog；库表过滤规则限定同步范围。解析结果以 Entry 表示，Header 保存库表、事件类型和日志坐标等头部信息，RowChange 表示行变更内容，RowData 保存各行的变更前后列值。binlog 还包含事务标记、DDL（数据定义语句，如修改表结构）等事件，不能把所有事件都当作普通行增删改。

依据：[MysqlEventParser](https://github.com/alibaba/canal/blob/canal-1.1.7/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/mysql/MysqlEventParser.java)、[CanalEntry](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/CanalEntry.java)。本次只完成 1.1.7 源码静态核对，不表示项目运行行为已经验证。

Canal 自带的消息发送功能从内部 Server 获取待确认批次，随后按消息格式和分区规则封装并发布。日志读取、事件解析与消息发布是不同阶段；发送确认及重试见 [07 RocketMQ消息发布与事务边界](<./021_ES改造_Canal Server_RocketMQ消息发布与事务边界.md>)，持久化进度及恢复见 [08 进度确认与单机恢复](<./022_ES改造_Canal Server_进度确认与单机恢复.md>)。

### 3.2 项目范围与字段前提

项目接入 `db0`、`db1` 两个同步通道及各自分表，需要分别识别来源库表、维护 GTID 起点和消费进度。业务主键、操作类型、原始 `create_time` 和必要来源映射用于定位；destination 归属需通过约定的来源映射或转换逻辑识别，不能假定原生 FlatMessage 自带该字段。

新增、更新的行后业务数据与 `version` 必须来自同次变更；仅新分表增加业务版本，旧表不增加。MySQL DELETE 保留删除前的 `consumeId` 和原始 `create_time`，用于消息契约、对账或状态判断，不转为 ES 逐条 Delete。完整业务列是否传递仍须核对实际消息。

## 4 参考文章与项目设计的区别

**同步范围**

**参考文章：** 以单个 `order_db.orders` 表为例，按 `order_no` 配置消息分区路由。

**当前项目：** 设计接入 `db0`、`db1` 两个同步通道及各自的分表，需要分别维护来源范围、GTID 起点和消费进度。

**组件版本**

**参考文章：** 使用 Canal 1.1.6，并把单表实验与 RocketMQ、ES 8 链路一起演示。

**当前项目：** 使用 Canal Server 1.1.7，Entry 解析边界按 1.1.7 核对；文章实验不能证明项目已部署或已验证。

## 5 待确认或待验证事项

- 两个 destination 的真实复制账号、过滤范围、来源映射和 GTID 基线是否分别正确？
- 实际消息是否保留新增、更新所需的完整业务列、`version` 及 DELETE 前定位信息？
- 事件解析、消息发布和本地进度之间的确认边界是否符合实际发行包行为？

## 6 复核记录

已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md) 一轮，并按 `java-interview-doc-review` 和 `self-check` 完成最终复核；本次未执行 Canal、MySQL、RocketMQ 或 Elasticsearch 联调。
