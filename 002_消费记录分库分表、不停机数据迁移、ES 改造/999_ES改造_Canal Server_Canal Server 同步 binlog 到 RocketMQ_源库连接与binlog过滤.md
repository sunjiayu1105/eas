# 999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_源库连接与binlog过滤

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与适用边界

- 本文只说明 Canal Server 如何连接两个 MySQL 源库、确定首次读取起点并按库表过滤 Binlog；Entry 结构见 [Entry结构与行事件解析](<./999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_Entry结构与行事件解析.md>)。
- 项目采用一个 Canal Server 进程承载 `db0`、`db1` 两个 destination；每个 destination 独立维护源库连接、过滤规则、GTID 起点和本地进度。
- 本文记录设计和 Canal 1.1.7 源码静态核对，不表示实际配置已经加载或恢复流程已经演练。

## 2 面试口述精炼版本

**建立读取范围和起点**

Canal Server 使用复制账号分别连接 `db0`、`db1` 的 MySQL 实例，每个 destination 按自己的库表过滤规则读取 Binlog。首次初始化使用导入前的 GTID 基线，已有本地进度时继续从可核对位点读取，不能用最新 GTID 覆盖既有进度。过滤命中和起点恢复仍需运行验证。

## 3 详细方案

### 3.1 destination 连接与首次起点

每个 destination 的 `instance.properties` 独立配置 MySQL 地址、复制账号、GTID 开关和首次初始化基线：

```properties
canal.instance.master.address=<mysql-db0>:3306
canal.instance.dbUsername=<canal-user>
canal.instance.dbPassword=<managed-secret>
canal.instance.gtidon=true
canal.instance.master.gtid=<db0-import-before-gtid>
```

`db1` 必须替换为自己的地址和导入前完整 GTID 集合，不能复制 `db0` 的 GTID。首次基线只用于没有可恢复进度的初始化；已有检查点时，不能通过编辑配置覆盖既有位点，也不能在位点异常时直接跳到最新位置。

`slaveId` 是 Canal 复制客户端使用的 server-id，区别于 MySQL 源实例的 `server-id`、destination 名称和 MQ 内部 `clientId`。复制账号、权限、GTID 连续性和身份冲突仍需按两个源库分别核对。

### 3.2 库表过滤

当前候选过滤范围为两个 destination 各自的 `eas.consume_record_(01-16)` 分表。Java properties 中的 `\\.` 经过解析后表示正则中的字面句点：

```properties
canal.instance.filter.regex=eas\\.consume_record_(0[1-9]|1[0-6])
canal.instance.filter.black.regex=mysql\\.slave_.*
```

这组规则应命中 `consume_record_01`、`09`、`10`、`16`，不命中 `00`、`17` 和其他表。实际库名、表名和两个源库映射仍需结合部署配置验证。

Canal 原生 MQ 工作线程从嵌入式 Server 获取批次时使用空过滤表达式订阅，表范围由 Server 侧 instance 配置限定；这里不再重复添加独立 TCP 客户端订阅过滤。

### 3.3 读取起点与后续阶段

读取起点决定 Canal 从源日志的哪个位置开始产生 Entry，但读取到 Binlog 不等于已经完成 Entry 解析、消息发布或 Canal 批次确认。处理顺序是：

1. destination 建立源库连接并加载过滤范围；
2. Canal 从首次 GTID 基线或已有本地位点继续读取；
3. 读取结果交给 Entry 解析，见 [Entry结构与行事件解析](<./999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_Entry结构与行事件解析.md>)；
4. 消息格式和字段边界由 [消息格式与字段边界](<./999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_消息格式与字段边界.md>) 说明；
5. 内置生产端发送和批次确认分别见 [RocketMQ生产端实现](<./999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_RocketMQ生产端实现.md>) 与 [RocketMQ发布与Canal批次确认](<./999_ES改造_Canal Server_Canal Server 同步 binlog 到 RocketMQ_RocketMQ发布与Canal批次确认.md>)。

## 4 参考文章与项目设计的区别

**参考文章：** 以单个 `order_db.orders` 表和固定 binlog 文件/position 为实验起点。

**当前项目：** 使用 MySQL 8.0.33、Canal Server 1.1.7，配置 `db0`、`db1` 两个 destination，并按导入前 GTID 基线和各自本地进度读取；文章的账号、表名、位点和实验结果不能直接作为项目配置或验证依据。

## 5 待确认或待验证事项

- 两个 destination 的真实复制账号、权限、源库映射和 GTID 基线是否分别正确？
- 过滤正则是否只命中各自应同步的 16 张分表？
- 首次 GTID 基线、已有本地位点和位点异常时的恢复行为是否符合实际发行包？

## 6 复核记录

已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本轮按主题归属去重并复核标题、编号、引用和示例；未执行 MySQL、Canal、RocketMQ 或 ES 联调。

本文按 [java-interview-doc-review-loop](C:\Users\Administrator\.codex\skills\java-interview-doc-review-loop\SKILL.md) 整理，已将“日志读取与变更解析”拆成读取/过滤职责；Entry、消息格式、发送确认和恢复内容分别保留在后续专题文档中。未执行 Canal、MySQL、RocketMQ 或 ES 联调。

## 7 参考来源

- [Canal 1.1.7 instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties)：支持本文关于源库连接、GTID 和库表过滤配置键的说明。
- [MysqlEventParser（Canal 1.1.7）](https://github.com/alibaba/canal/blob/canal-1.1.7/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/mysql/MysqlEventParser.java)：支持本文关于复制客户端读取 Binlog 的源码边界说明。
