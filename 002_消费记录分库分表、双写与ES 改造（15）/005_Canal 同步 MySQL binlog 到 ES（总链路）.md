TARGET DECK: E账通::002_消费记录分库分表、双写与ES改造

Q: 005_Canal 同步与 ES 写入
A:

# 3.4 Canal 同步与 ES 写入

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 3.4.1 Canal 同步边界

本节回答新表提交后的变更如何进入 ES、各组件如何交接，以及什么条件允许确认 Canal 消费。本链路的输入是新表已提交变更，来源可以是历史导入，也可以是恢复业务后的双写；Canal Server 读取并解析行日志，Canal Adapter 转换为 ES 操作并检查结果。项目要求对应 ES 操作全部按既定规则处理成功后，Canal Adapter 才向 Canal Server 确认该批消息已处理，让消费进度向前推进；任一操作失败，则不确认该批，留待重试。搜索可见和两项独立校验属于后续状态，不作为本链路的消费确认条件。

### 面试口述精炼版本

#### 从新表提交变更开始同步

业务双写（见 [004_业务双写.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/004_业务双写.md:6>)）负责按规则写入旧表和新表；本链路只消费新表事务提交后产生的 ROW 格式 binlog（按受影响的行记录变化）。历史导入前，DBA 先保存 2 个新库实例各自的导入前基线；历史验收通过后，Canal 从对应基线追赶导入事件，并继续读取恢复业务后的新表变更，异步写入 ES。基线的时点与用途、MySQL 日志条件见 [006_Canal 同步 MySQL binlog 到 ES（MySQL 处理逻辑）.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/006_Canal 同步 MySQL binlog 到 ES（MySQL 处理逻辑）.md:6>)；ROW 行事件和行镜像示例见 [999_MySQL binlog 与同步起点_详解.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:229>)。第 1 轮同步期间，后台列表仍查旧表。

#### 按组件顺序传递变更

MySQL 提交事务并产生行变更后，Canal Server 读取并解析 binlog；Canal Adapter 连接 Canal Server，按批次获取 Canal 消息。一次获取的消息包含若干 `Entry`，行变更 `Entry` 还可能包含多行 `RowData`。Canal Adapter 根据事件类型和映射规则生成 ES 的写入、更新或删除操作，提交给目标索引；`batchSize` 只是消息批次边界的候选参数，不等于 ES 文档数，Adapter 最终的聚合、拆分和重试行为仍待发行包核实。

#### 检查每项结果后确认批次

Canal Adapter 可以把多项 ES 操作组装成 `_bulk`，也可以拆成多个 ES 子批次。HTTP 请求成功不代表每个操作都成功；项目要求只有本批及其子批次的每项操作都按既定规则处理成功，Canal Adapter 才向 Canal Server 确认原批次已处理，让消费进度向前推进。任一操作失败，都不确认该批，留待重试。这里的确认只表示本同步链路已完成规定处理，不代表 ES 已搜索可见，也不代表两项独立校验已经通过；这 3 个状态必须分别记录。具体确认 API、位点持久化和重试行为仍待发行包与环境联调。

上述“失败不确认”是项目要求，不是原生默认保证。Canal Adapter 1.1.7 在有限重试耗尽且 terminateOnException=false 时仍会 ack，该属性默认是 false；ES7 对状态为 NOT_FOUND 的失败子项仅记录日志，不抛异常。008 已纳入 terminateOnException: true 的配置建议，但它无法解决未抛异常的 404 分支。项目重试次数和 404 处理机制仍待定，实际部署及恢复行为待验证。

**修改支持来源：** [1.1.7 AdapterProcessor.java](https://github.com/alibaba/canal/blob/canal-1.1.7/client-adapter/launcher/src/main/java/com/alibaba/otter/canal/adapter/launcher/loader/AdapterProcessor.java)、[CanalClientConfig.java](https://github.com/alibaba/canal/blob/canal-1.1.7/client-adapter/common/src/main/java/com/alibaba/otter/canal/client/adapter/support/CanalClientConfig.java)与 [ESConnection.java](https://github.com/alibaba/canal/blob/canal-1.1.7/client-adapter/es7x/src/main/java/com/alibaba/otter/canal/client/adapter/es7x/support/ESConnection.java)。以上是源码静态核对，未完成发行包故障测试。

#### rollover 后定位原具体索引

本方案采用 rollover：每 30 天创建并切换新写索引，索引在 rollover 后 180 天进入 Cold 阶段。写别名只指向当前写索引，不会自动查找历史索引中的同一文档。更新、撤销、退货、删除和重试必须保留或计算原具体索引；稳定 `_id` 不能单独保证跨索引定位和重放幂等。实际迁移仍受 ILM 调度和失败重试影响，需以任务状态和分片分配结果核实。索引定位与操作语义见 [009_ES 文档如何写入与检索.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/009_ES 文档如何写入与检索.md:6>)。

### 详细方案：按问题阅读

1. [006_Canal 同步 MySQL binlog 到 ES（MySQL 处理逻辑）.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/006_Canal 同步 MySQL binlog 到 ES（MySQL 处理逻辑）.md:6>)
2. [007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）.md:6>)
3. [008_Canal 同步 MySQL binlog 到 ES（Canal Adapter 处理逻辑）.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/008_Canal 同步 MySQL binlog 到 ES（Canal Adapter 处理逻辑）.md:49>)
4. [009_ES 文档如何写入与检索.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/009_ES 文档如何写入与检索.md:6>)
5. [010_批次失败与同步恢复.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/010_批次失败与同步恢复.md:6>)
6. [011_Canal 同步与增量校验如何分工.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/011_Canal 同步与增量校验如何分工.md:6>)
7. [017_消费业务 ES 冷热分离与索引生命周期管理.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/017_消费业务 ES 冷热分离与索引生命周期管理.md:6>)

同步与独立校验的职责边界见 [011_Canal 同步与增量校验如何分工.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/011_Canal 同步与增量校验如何分工.md:6>)；字段类型、索引策略和分页约束见 [012_ES Mapping 设计.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/012_ES Mapping 设计.md:5>)。以上是文档与候选配置的静态方案；Canal 发行包、真实 DDL、Adapter 的批量与确认行为，以及 MySQL 到 ES 的链路尚未完成联调，不能据此宣称已具备生产运行能力。

### 基础问题延伸

Canal 的组件职责见[024_Canal 是什么？.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/024_Canal 是什么？.md:6>)；ES 的查询副本边界见[025_ES 是什么？.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/025_ES 是什么？.md:6>)；倒排索引和 `doc_values` 的底层概念分别见[026_ES 中的倒排索引是什么？.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/026_ES 中的倒排索引是什么？.md:6>)和[027_什么是 doc_values？主要有什么作用？.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/027_什么是 doc_values？主要有什么作用？.md:6>)。
<!--ID: 1790086386060-->

