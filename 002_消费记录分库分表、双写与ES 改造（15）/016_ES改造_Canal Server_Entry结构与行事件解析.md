# 016_ES改造_Canal Server_Entry结构与行事件解析

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与适用边界

- 本文只说明 Binlog 事件进入 Canal 后如何表示为 Entry、RowChange 和 RowData，以及普通行事件与事务/DDL 事件的解析边界；读取连接和过滤范围见 [源库连接与Binlog过滤](<./015_ES改造_Canal Server_源库连接与Binlog过滤.md>)。
- 消息格式、字段契约和 DELETE 前值见 [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>)；事务和特殊事件处置见 [事务边界与特殊事件](<./017_ES改造_Canal Server_事务边界与特殊事件.md>)。
- 本文依据 Canal 1.1.7 源码静态核对，不表示实际发行包已经部署或解析结果已经验证。

## 2 面试口述精炼版本

**把 Binlog 事件解析成 Entry**

Canal 先把读取到的 Binlog 事件表示为 Entry。Entry 的 Header 保存库表、事件类型和日志坐标，RowChange 描述行变更，RowData 保存行的前后列值。事务标记、DDL 等特殊事件不能直接当作普通增删改处理；解析完成也不等于消息已经发送到 RocketMQ。

## 3 详细方案

### 3.1 Entry 结构

Entry 是 Canal 在解析阶段使用的事件容器。对于 ROWDATA 类型，storeValue 可解析为 RowChange；事务开始和结束使用对应的事务结构，不应按 RowChange 解码：

- Header：库名、表名、事件类型、日志文件和偏移等来源信息；
- RowChange：行变更类型及相关事件属性；
- RowData：一行的变更前列和变更后列；一个 RowChange 可包含多个 RowData；
- 事务或 DDL 事件：可能不包含普通行数据，必须按事件类型单独识别。

依据：[CanalEntry（Canal 1.1.7）](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/CanalEntry.java)。这部分只说明解析对象，不把 Entry 直接等同于 RocketMQ 消息或 ES 文档。

### 3.2 行事件与字段前提

INSERT、UPDATE、DELETE 的行数据会进入后续消息转换。新增和更新需要保留同次变更的行后数据及业务 `version`；DELETE 需要保留删除前的 `consume_id`、原始 `create_time` 等定位信息，具体字段输出由 [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>) 定义。

多行事件的源坐标与行身份边界见 [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>)；事务与特殊事件的处理归 [事务边界与特殊事件](<./017_ES改造_Canal Server_事务边界与特殊事件.md>)。

### 3.3 解析后的交接

Entry 解析完成后才进入消息格式转换和分区发送，顺序如下：

1. 读取和过滤由 [源库连接与Binlog过滤](<./015_ES改造_Canal Server_源库连接与Binlog过滤.md>) 负责；
2. 本文将事件解析为 Entry、RowChange 和 RowData；
3. FlatMessage JSON 和二进制封装的字段边界由 [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>) 负责；
4. Canal 内置生产端转换、分区和发送由 [RocketMQ生产端实现](<./020_ES改造_Canal Server_RocketMQ生产端实现.md>) 负责。

## 4 参考文章与项目设计的区别

**参考文章：** 以单个 `order_db.orders` 表为例，直接在消费端按订单 JSON 的 `type` 和 `data` 处理。

**当前项目：** 先由 Canal 1.1.7 将源事件解析成 Entry，再依据已确认的 FlatMessage JSON 契约转换和发布；文章示例不能证明项目的 Entry 字段、特殊事件和多行拆分行为已经验证。

## 5 待确认或待验证事项

- 实际发行包是否保留每类 Entry 的事务标记、DDL 信息和完整来源坐标？
- 解析结果能否区分 ROWDATA、TRANSACTIONBEGIN、TRANSACTIONEND 和 DDL？处置策略见事务专题。

## 6 复核记录

已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本轮按主题归属去重并复核标题、编号、引用和示例；未执行 MySQL、Canal、RocketMQ 或 ES 联调。

本文按 [java-interview-doc-review-loop](C:\Users\Administrator\.codex\skills\java-interview-doc-review-loop\SKILL.md) 整理，已移除读取起点、过滤配置和 RocketMQ 发布细节，只保留 Entry 解析及其交接边界。未执行 Canal、MySQL、RocketMQ 或 ES 联调。

## 7 参考来源

- [CanalEntry（Canal 1.1.7）](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/CanalEntry.java)：支持本文关于 Entry、Header、RowChange 和 RowData 的说明。
- [MysqlEventParser（Canal 1.1.7）](https://github.com/alibaba/canal/blob/canal-1.1.7/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/mysql/MysqlEventParser.java)：支持本文关于 MySQL 事件解析阶段的说明。
