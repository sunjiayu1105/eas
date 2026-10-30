# 012_ES改造_Canal Server_整体实现

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与阅读范围

- 本组文档按“部署配置→源库读取→事件解析→消息转换→分区发送→批次确认→位点恢复→验收”组织。每个技术细节只有一个主要讲解位置，其他文档用链接交接。
- 面向 Canal 初学者，步骤与数据逐项换行，采用纯 Markdown；保留多条记录、不同操作类型的示例。
- 单机 Server 承载 db0、db1 两个 destination，固定采用 FlatMessage JSON；配置、源码静态判断和运行验证分开记录。
- 拆分可调整篇数、名称和编号，冲突的后续编号顺延，标题及链接同步更新；保留参考对比、待验证事项、来源和复核记录。

## 2 阅读顺序与职责导航

- [部署拓扑](<./013_ES改造_Canal Server_部署拓扑.md>)
- [公共配置](<./014_ES改造_Canal Server_公共配置.md>)
- [源库连接与Binlog过滤](<./015_ES改造_Canal Server_源库连接与Binlog过滤.md>)
- [Entry结构与行事件解析](<./016_ES改造_Canal Server_Entry结构与行事件解析.md>)
- [事务边界与特殊事件](<./017_ES改造_Canal Server_事务边界与特殊事件.md>)
- [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>)
- [Topic路由与多行分区](<./019_ES改造_Canal Server_Topic路由与多行分区.md>)
- [RocketMQ生产端实现](<./020_ES改造_Canal Server_RocketMQ生产端实现.md>)
- [RocketMQ发布与Canal批次确认](<./021_ES改造_Canal Server_RocketMQ发布与Canal批次确认.md>)
- [本地位点与单机恢复](<./022_ES改造_Canal Server_本地位点与单机恢复.md>)
- [监控与验收](<./023_ES改造_Canal Server_监控与验收.md>)

## 3 用四条记录理解整条链路

本例仅示意同一批次中有 INSERT、UPDATE、DELETE 三类行事件，假设两条 UPDATE 在同一事件中；事务标记暂不展开。Entry 是事件容器，RowChange 是行变更描述，RowData 对应一行前后列值。批次大小和事件数不是固定对应关系。

### 3.1 MySQL 提交业务变化

- **输入数据：**
  - `c-1001`：不存在。
  - `c-1002`：`status=异常（未确定）`。
  - `c-1003`：已存在。
  - `c-1004`：`status=异常（未确定）`。
- **执行动作：** 以下仅列业务变更，不表示执行顺序；本例假设两条 UPDATE 来自同一行事件。
  - INSERT `c-1001`。
  - UPDATE `c-1002` 的 status 为“成功”。
  - DELETE `c-1003`。
  - UPDATE `c-1004` 的 status 为“成功”。
- **处理后数据：** 形成四条行变化：一条 INSERT、两条 UPDATE、一条 DELETE，并写入 Binlog。
- **下一步输入：** Canal Server 读取到 Binlog 事件。

### 3.2 Binlog 解析为 Canal 事件

- **输入数据：** Binlog 二进制事件。
- **执行动作：** Canal Server 解析事件，形成 `Entry`、`RowChange`、`RowData`。
- **处理后数据：**
  - INSERT Entry 携带 `c-1001`。
  - 本例假设同一 UPDATE Entry 含两条 RowData，分别对应 `c-1002` 和 `c-1004`；实际 Entry 数由源事件决定。
  - DELETE Entry 携带 `c-1003`。
- **下一步输入：** `CanalMQStarter` 的 worker 可拉取的批次。

### 3.3 worker 拉取批次

- **输入数据：** 包含上述 Entry 的 Canal 未确认批次。
- **执行动作：** `CanalMQStarter` 的常驻 worker 循环调用 `getWithoutAck(...)`，不是固定周期定时任务。
- **处理后数据：** 得到一个 `batchId` 和对应的 Entry 列表；业务行数据尚未变成 RocketMQ JSON。
- **下一步输入：** `CanalRocketMQProducer.send(...)` 收到批次。

### 3.4 准备转换数据

- **输入数据：** Canal 批次中的 Entry、每个 Entry 的 RowChange、每个 RowChange 的 RowData。
- **执行动作：** `send(...)` 调用 `buildMessageData(...)`，整理成 `EntryRowData[]`。
- **处理后数据：**
  - INSERT 行数据：`c-1001`。
  - UPDATE 行数据：`c-1002`、`c-1004`。
  - DELETE 行数据：`c-1003`。
- **下一步输入：** `messageConverter(...)` 收到 `EntryRowData[]`。

### 3.5 组装 FlatMessage

- **输入数据：** `EntryRowData[]`。
- **执行动作：** `MQMessageUtils.messageConverter(...)` 按事件类型创建 FlatMessage。
  - UPDATE 的更新后行放入 `data`。
  - UPDATE 的变化字段旧值放入 `old`。
- **处理后数据：**
  - INSERT FlatMessage：包含 `c-1001`。
  - UPDATE FlatMessage：包含 `c-1002`、`c-1004` 两行。
  - DELETE FlatMessage：包含 `c-1003`。
- **下一步输入：** 分区方法或序列化步骤收到 FlatMessage 对象。

### 3.6 分区并序列化

- **输入数据：** FlatMessage 对象。
- **执行动作：**
  - 配置按键分区时，`messagePartition(...)` 按行计算分区，并保持行与旧值配对。
  - `JSON.toJSONBytes(...)` 序列化。
- **处理后数据：**
  - INSERT JSON 字节。
  - UPDATE JSON 字节。
  - DELETE JSON 字节。
  - UPDATE 可能是一条消息，也可能拆成多条消息。
- **下一步输入：** Canal RocketMQ 生产端构造 `Message`。

### 3.7 写入 RocketMQ

- **输入数据：** JSON 字节、topic、目标队列。
- **执行动作：** 生产端先构造 RocketMQ `Message`，再调用 `sendMessage(...)` 发送。
- **处理后数据：** RocketMQ 消息体仍是对应 FlatMessage JSON；Broker 不解析或修改 `data` 数组。
- **下一步输入：** RocketMQ Broker 保存消息，并向消费组投递。

### 3.8 消费并同步 ES

- **输入数据：** 消费端取到的 FlatMessage JSON。
- **执行动作：** 消费端解析 `type`、`data` 和业务字段，按 INSERT、UPDATE、DELETE 分派。
- **处理后数据：**
  - INSERT：新增 `c-1001`。
  - UPDATE：用完整行更新 `c-1002`。
  - UPDATE：用完整行更新 `c-1004`。
  - DELETE：处理 `c-1003` 删除事件，项目不将其转换为 ES 逐条 Delete；保留删除前定位信息。
- **最终输出：** ES 同步结果。

完整 JSON 及字段解释见 [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>)。发送返回后还要进入 Canal 提交或回滚，见 [RocketMQ发布与Canal批次确认](<./021_ES改造_Canal Server_RocketMQ发布与Canal批次确认.md>)；本地位点落盘及重启恢复见 [本地位点与单机恢复](<./022_ES改造_Canal Server_本地位点与单机恢复.md>)。这些状态都不等于 ES 已写入。

## 4 待确认或待验证事项

各专题记录自身缺口；完整联调清单见 [监控与验收](<./023_ES改造_Canal Server_监控与验收.md>)。本文只给阅读入口，不把设计链路表述为已部署结果。

## 5 复核记录

本轮将原 018 的完整数据链路归入本页，原 012 的总览和导航职责保留。

已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本轮按主题归属去重并复核标题、编号、引用和示例；未执行 MySQL、Canal、RocketMQ 或 ES 联调。
