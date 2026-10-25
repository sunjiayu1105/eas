# 007_ES写入链路_Canal Server_05_Binlog事件解析与消息格式

以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准。

## 1 适用规则

沿用 Canal Server 原总览（原文档已删除，依据待补充） 的已确认规则；下述内容属于项目设计，未表示已部署或通过运行验证。

## 2 面试口述精炼版本

Canal 把日志解析为 Entry 事件，再按选定格式发布。FlatMessage 是扁平 JSON，UPDATE 的 old 只含变化列；二进制消息保留更多头部信息，但按键拆行后仍可能缺失原始行序号和事务标记。不能把单条消息当完整事务，字段与边界仍需实际消息验证。

## 3 详细方案

### 3.1 消息格式开关

以下开关位于 canal.properties；这里集中维护其含义。

~~~properties
canal.mq.flatMessage=true
# true 使用 FlatMessage JSON；false 使用 Canal 二进制 Entry。
~~~

### 3.2 读取和解析

Server 通过复制账号以复制客户端方式连接 MySQL，读取 binlog 并解析已提交的数据变更。先根据库表过滤规则确定同步范围，再识别新增、更新、删除及对应行数据；binlog 还包含事务标记、DDL（数据定义语句，如修改表结构）等事件，不能把所有事件都当作行增删改。

Canal 使用 Entry 表示解析事件：Header 保存库表、事件类型和日志坐标等头部信息，行变更内容由 RowChange/RowData 表示。原生 MQ 工作线程随后从嵌入式 Server 获取待确认批次，按消息格式和分区规则封装并发布；日志解析和 MQ 发布是前后两个处理阶段，读到事件不表示已发布成功。

下游内部事件契约至少需要以下信息，原生消息能否提供须按后面的格式边界核对：

- destination、库名和表名；
- INSERT、UPDATE、DELETE 事件类型；
- 主键、变更前后行数据和事件时间；
- 事务标识、binlog 文件与事件偏移、源行序号、Canal batchId；
- 可用于消息幂等和追踪的来源信息。

上面列的是内部处理所需信息，不代表每种原生封装都带有全部字段。消息格式必须先明确：

| 原生格式 | 解析与字段边界 |
| --- | --- |
| `canal.mq.flatMessage=true`，官方默认 | JSON `FlatMessage`，按 `data`/`old` 解析；`data` 为 INSERT/UPDATE 的后值或 DELETE 的前值，UPDATE 的 `old` 只收集被标记为变化的列，不是完整前镜像。带 `gtid`，但没有 binlog 文件、偏移和原始行序号。 |
| `canal.mq.flatMessage=false` | 使用 Canal Packet/Messages 二进制封装，解析 Entry 与 RowChange/RowData；Header 可保留日志文件和偏移。启用按键分区时，源码将每行重建为单行 Entry，保留 Header，但不附加拆分前行序号，并过滤事务开始/结束 Entry。 |

默认 JSON 不能按 Entry 二进制解析。业务版本来自新表行字段，须核对 INSERT/UPDATE 后值与 DELETE 前值中的 `version`，并验证多行拆分后数据和版本仍正确对应。若另用源坐标构造事件 ID，仍须在信息丢失前补齐原始行序号等字段；MQ messageId、batchId 或拆分后下标不能代替稳定源身份。

消息数、Entry 数和 ES 文档数各不相同。事务标记只有实际保留时才能用于上下文，不能把分区后的消息当作完整源事务。DDL、TRUNCATE、主键变更及未知事件须明确暂停或处置；涉及多个分区的 DDL 还须协调隔离，不能只处理队列 0 就让其他队列继续使用旧结构。

## 4 参考文章与项目设计的区别

参考文章 [使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<./999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md>) 的版本、脚本和演示结果只代表文章环境，不能证明项目已经实现或验证。

- **组件版本**：参考文章使用 Canal 1.1.6 和 Elasticsearch 8.4.2，演示从日志同步到 ES 冷热节点的完整链路；当前项目使用 Canal Server 1.1.7 和 Elasticsearch 服务端 7.17.15，消息解析须按 1.1.7 核对，不能直接套用文章的 ES 8 API。ES 写入、索引定位和 ILM 规则由对应专题说明。
- **同步范围**：参考文章以单个 `order_db.orders` 表为例，按 `order_no` 配置消息分区路由；当前项目设计接入 `db0`、`db1` 两个同步通道及各自的分表，需要分别识别来源库表、维护 GTID 起点和消费进度，不能直接沿用单表实验的来源范围。
- **消息解析方式**：参考文章的消费代码将消息解析为 JSON，从 `data` 读取订单列表，再按 `type` 分派 INSERT、UPDATE、DELETE；当前项目还需按 `canal.mq.flatMessage` 的实际取值核对 FlatMessage JSON 或二进制 Entry 的字段边界，区分 INSERT/UPDATE 后值、DELETE 前值，以及 UPDATE 中只包含变化列的 `old`，不能把 JSON 当二进制 Entry 解析。
- **业务版本传递**：参考文章的 `orders` 表已有 `version` 字段，并在更新示例中执行 `version = version + 1`；当前项目的设计是仅新分表增加业务 `version`，旧表不增加，解析时须核对 INSERT/UPDATE 后值与 DELETE 前值中的版本，并确认多行拆分后每行数据与版本仍正确对应。文章示例不能证明这些项目约束已经实现。
- **特殊事件处置**：参考文章的消费代码遇到 DDL 或无法识别的消息类型时直接返回；当前项目要求明确 DDL、TRUNCATE、主键变更及未知事件的暂停或处置方式，并核对事务标记在分区、拆行后是否保留，不能默认一条消息就是一个完整事务。具体处置和消息边界仍待实现核验。
- **验证状态**：参考文章展示了消息进入 RocketMQ 和写入 ES 的实验效果；当前项目尚未用实际发行包生成消息样例，完整源坐标、原始行序号、事务边界和发送结果是否可靠传递仍待验证，不能用文章演示替代项目验证。

## 5 待确认或待验证事项

行镜像、FlatMessage 与二进制字段、事务事件和拆行后的源行身份仍需实际消息样例确认。
