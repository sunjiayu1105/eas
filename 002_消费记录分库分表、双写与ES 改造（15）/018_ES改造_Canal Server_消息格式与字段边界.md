# 018_ES改造_Canal Server_消息格式与字段边界

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与适用边界

- 本文只说明 `FlatMessage` JSON、Canal 二进制消息及项目字段契约；日志读取见 [Binlog读取与Entry解析](<./017_ES改造_Canal Server_Binlog读取与Entry解析.md>)，事务边界见 [事务边界与特殊事件](<./019_ES改造_Canal Server_事务边界与特殊事件.md>)。
- 新增、更新传递同次变更的数据和业务 `version`；DELETE 保留删除前定位信息，不转换为 ES 逐条 Delete。设计记录不表示字段已端到端验证。
- 当前项目设计上采用 FlatMessage JSON：要求 `canal.mq.flatMessage=true`，自定义 ES 消费服务按 JSON 解析；实际运行配置、真实消息样例和端到端解析结果仍待验证。
- 本文按 Canal 初学者能够连续理解的顺序编写：先说明消息在 Canal 链路中的位置，再说明格式开关、字段含义、拆分对应关系和项目字段契约。
- 当前项目要求 UPDATE 的 `data` 保证为完整更新后行；只有通过完整性校验的消息，才能按完整文档覆盖写入 ES。该要求的配置和运行效果仍需验证。

## 2 面试口述精炼版本

先把消息链路串起来：Canal 从 MySQL binlog 读取变更，先解析成 Entry，再把行事件转换成下游可以接收的消息。本文重点说明转换后的消息格式和字段边界；Binlog 读取与 Entry 解析见 [Binlog读取与Entry解析](<./017_ES改造_Canal Server_Binlog读取与Entry解析.md>)。

**先区分输入和输出**

Canal 内部处理的输入是 Entry 和 RowData，发送给 RocketMQ 的输出可以是 FlatMessage JSON。二进制消息要先解码 Packet/Messages，再解析 Entry，不能把 JSON 当二进制解析。

**再理解 FlatMessage 字段**

`FlatMessage` 是扁平 JSON：新增、更新从 `data` 取行后值，DELETE 从 `data` 取行前值，UPDATE 的 `old` 按变化列筛选且不是完整前镜像。项目新增、更新还要核对业务 `version` 与数据是否始终配对；字段完整性和拆行结果仍待消息样例验证。

## 3 详细方案

本节按“先看完整流程，再看消息格式，最后看多行拆分”的顺序展开。初学者可以先读 3.1，理解一批变更如何从 MySQL 进入 Canal、变成 FlatMessage、发送到 RocketMQ 并被消费；再读 3.2 和 3.3，确认配置和字段含义；最后读 3.4，理解一条批次消息为什么可能对应多条 RocketMQ 消息。

### 3.1 先理解整体链路和执行方式

先记住一条主线：MySQL 产生 Binlog，Canal Server 持续取出变更批次，生产端把批次转换成 FlatMessage JSON，再发送到 RocketMQ，消费端从 RocketMQ 取出 JSON 后同步到 ES。`Entry`、`RowChange`、`RowData` 是 Canal 内部的数据结构，`FlatMessage` 是发送前组织出的消息对象，FlatMessage JSON 是序列化后的消息格式；它们不是彼此独立的服务。

**整条处理链路**

1. **MySQL 产生 Binlog**：业务事务提交后，MySQL 把 INSERT、UPDATE、DELETE 等行变化写入 Binlog。此时输出是 Binlog 二进制事件，输入方是 Canal Server。
2. **Canal worker 拉取批次**：CanalMQStarter 为每个 destination 启动一个常驻 worker。worker 调用 `getWithoutAck(...)` 从 Canal Server 取得一个批次，批次中可能包含多个 Entry 和多条 RowData；取得的批次成为生产端的输入。
3. **准备 Entry 数据**：`CanalRocketMQProducer.send(...)` 接收这个批次，并调用 `MQMessageUtils.buildMessageData(...)` 把批次整理成 `EntryRowData[]`，为后续读取 Entry、RowChange 和 RowData 做准备。
4. **组装 FlatMessage**：当 `canal.mq.flatMessage=true` 时，生产端调用 `MQMessageUtils.messageConverter(...)`。该方法按事件创建 FlatMessage，写入事件元数据，并把多条 RowData 组织到 `data`；UPDATE 同时组织变化字段对应的 `old`。输出是一个或多个 FlatMessage 对象。
5. **按行分区**：如果配置了分区哈希，生产端调用 `MQMessageUtils.messagePartition(...)`，按 `data` 中的行计算目标分区；同一行的 `data[i]` 和 `old[i]` 会放入同一个分区消息。输出是分区后的 FlatMessage 对象；没有分区配置时则沿用默认队列路径。
6. **序列化并发送**：生产端调用 `JSON.toJSONBytes(...)` 把 FlatMessage 序列化为 JSON 字节，构造 RocketMQ `Message`，再调用 `sendMessage(...)` 发送到指定 topic 和队列。输出是 RocketMQ 中保存的消息。
7. **RocketMQ 保存和投递**：Broker 持久化消息，消费者按消费组读取消息并维护消费进度。RocketMQ 只负责保存和投递消息，不解析 FlatMessage 的 `data` 数组，也不替消费端补齐字段。
8. **消费端同步 ES**：消费端读取消息体中的 FlatMessage JSON，解析 `type`、`data` 和必要业务字段；对于 UPDATE，使用完整的 `data` 行按业务主键和 `version` 覆盖写入 ES。消费端的具体线程模型和方法名仍需结合项目代码确认。

**用流程表确认每一步的交接**

| 步骤 | 执行者 | 什么时候执行 | 拿到什么 | 具体做什么 | 本例中的数据变化 | 产出什么 | 交给谁 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1. 产生变化 | MySQL | 业务事务提交时 | INSERT、UPDATE、DELETE 行变化 | 将行变化写入 Binlog | `c-1001` 执行 INSERT；`c-1002`、`c-1004` 执行 UPDATE，status 从“异常（未确定）”变为“成功”；`c-1003` 执行 DELETE | Binlog 二进制事件 | Canal Server |
| 2. 拉取批次 | `CanalMQStarter` 的 worker | 服务启动后持续循环；不是固定周期定时任务 | Canal 中未确认的一批事件 | 调用 `getWithoutAck(...)` 拉取批次；无数据时继续等待 | 拉到包含至少三个 Entry 的批次：INSERT、UPDATE、DELETE；UPDATE Entry 内有 `c-1002`、`c-1004` 两条行变化 | Canal 批次，可能包含多个 Entry 和 RowData | `CanalRocketMQProducer.send(...)` |
| 3. 准备 Entry | Canal 生产端 | 拉到有效批次后 | Canal 批次 | 调用 `send(...)` 选择发送路径，再调用 `buildMessageData(...)` 准备 `EntryRowData[]` | 保留三个 Entry 及其各自的 `RowChange`：INSERT、UPDATE、DELETE；每个 Entry 再携带对应的 RowData | 包含 `Entry`、`RowChange`、`RowData` 的中间数据 | `messageConverter(...)` |
| 4. 组装 FlatMessage | `MQMessageUtils.messageConverter(...)` | 每个有效批次进入 FlatMessage 路径时 | `EntryRowData[]` | 按事件类型创建 FlatMessage，把 RowData 组织到 `data`；UPDATE 组织变化字段对应的 `old` | 生成一个 INSERT FlatMessage、一个包含两行的 UPDATE FlatMessage 和一个 DELETE FlatMessage；UPDATE 的 `data` 是两个更新后状态，`old` 是对应的两个更新前状态 | 一个或多个 FlatMessage 对象 | 分区处理或直接序列化 |
| 5. 分区并序列化 | `messagePartition(...)`、`JSON.toJSONBytes(...)` | 配置分区哈希时先分区；发送前序列化 | FlatMessage 对象 | 按行计算目标队列，保持 UPDATE 的 `data[i]` 与 `old[i]` 对应，再序列化为 JSON 字节 | 分别得到 INSERT、UPDATE、DELETE 三类 JSON；UPDATE 内的两行可能进入同一条消息，也可能按分区拆成多条 | FlatMessage JSON 字节 | Canal RocketMQ 生产端 |
| 6. 构造并发送消息 | Canal RocketMQ 生产端、`sendMessage(...)` | 当前批次 JSON 字节准备完成后 | FlatMessage JSON 字节 | 构造 RocketMQ `Message`，调用 `sendMessage(...)` 写入指定 topic 和队列 | 发送 INSERT、UPDATE、DELETE 三类消息；UPDATE 是否一条或多条取决于分区结果 | Broker 中保存的 RocketMQ 消息 | RocketMQ Broker |
| 7. 保存和投递 | RocketMQ Broker | 收到生产端消息后 | RocketMQ 消息 | 持久化消息，按消费组提供读取并维护消费进度；不解析业务 JSON | 消费端可以分别读到 INSERT、UPDATE、DELETE 三类 JSON；UPDATE 可能是一条或多条消息 | 消费端可读取的消息 | RocketMQ 消费端 |
| 8. 同步 ES | 项目消费端 | 消费端读取消息后 | FlatMessage JSON | 解析 `type`、`data` 和业务字段；按 INSERT、UPDATE、DELETE 分派处理 | 根据 `type` 新增 `c-1001`，更新 `c-1002`、`c-1004`，处理 `c-1003` 的删除事件；是否删除 ES 文档由项目消费逻辑决定 | ES 中的新增、更新或删除结果 | ES |

**本例的具体数据变化**

下面用一批消费记录说明每个阶段的数据长什么样。字段值是为了帮助理解而编写的示例，不代表当前环境中已经抓取到的真实 Binlog 或 RocketMQ 消息。

| 阶段 | `c-1001` | `c-1002` | `c-1003` | `c-1004` |
| --- | --- | --- | --- | --- |
| MySQL 业务变化 | INSERT：`status=成功`，`version=1` | UPDATE：`status` 从“异常（未确定）”改为“成功”，`version` 从 `1` 变为 `2` | DELETE：删除前为 `status=成功`，`version=3` | UPDATE：`status` 从“异常（未确定）”改为“成功”，`version` 从 `4` 变为 `5` |
| Binlog / Canal 批次 | 一个 INSERT `RowData` | 一个 UPDATE `RowData`，包含更新前后行 | 一个 DELETE `RowData` | 一个 UPDATE `RowData`，包含更新前后行 |
| FlatMessage `type` | `INSERT` | `UPDATE` | `DELETE` | `UPDATE` |
| 消费端对 ES 的动作 | 新增文档 | 用更新后的完整行覆盖文档 | 根据删除前的 `consume_id` 处理删除事件；本文不将其确定为 ES 逐条 Delete | 用更新后的完整行覆盖文档 |

Canal 生产端会按事件类型组装 FlatMessage。为了展示 `data` 和 `old` 的含义，概念上的消息体可以写成下面三类 JSON：

```json
{
  "type": "INSERT",
  "data": [
    {"consume_id": "c-1001", "status": "成功", "version": 1}
  ],
  "old": []
}
```

```json
{
  "type": "UPDATE",
  "data": [
    {"consume_id": "c-1002", "status": "成功", "version": 2},
    {"consume_id": "c-1004", "status": "成功", "version": 5}
  ],
  "old": [
    {"status": "异常（未确定）", "version": 1},
    {"status": "异常（未确定）", "version": 4}
  ]
}
```

```json
{
  "type": "DELETE",
  "data": [
    {"consume_id": "c-1003", "status": "成功", "version": 3}
  ],
  "old": []
}
```

这三段 JSON 会分别作为 RocketMQ 消息体发送；如果 UPDATE 的两行被分到不同队列，第二段 JSON 可能按分区拆成两条消息，但每条消息仍须保留对应的 `data` 和 `old`。RocketMQ Broker 保存并投递这些消息，不会修改 JSON 内的字段。消费端读到消息后，按 `type` 分派：新增 `c-1001`、更新 `c-1002` 和 `c-1004`、处理 `c-1003` 的删除事件。以上只是数据流转示例，实际消息是否包含这些字段以及 DELETE 后如何影响 ES，仍需结合运行配置、消费代码和真实消息验证。


上述方法定位来自工作区保存的 Canal 1.1.7 源码快照；对应的官方源码入口见 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java) 和 [MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java)。源码静态核对能说明方法之间的调用和字段组装方式，不能替代当前部署实例的运行验证。

**最后看一批数据如何完成流转**

每个环节的输出会成为下一个环节的输入：MySQL 先生成 Binlog，Canal Server 将 Binlog 解析为 `Entry`，转换阶段再生成 FlatMessage JSON，RocketMQ 生产端把 JSON 写入指定 topic 的某个队列，Broker 持久化消息并按消费组进度提供读取，消费端取出消息后解析 JSON 并执行 ES 同步。本文只展开消息格式和字段边界，不重复 [Binlog读取与Entry解析](<./017_ES改造_Canal Server_Binlog读取与Entry解析.md>) 中的读取细节，也不展开 RocketMQ 发布确认。

上表用四条记录执行三种不同操作的例子贯穿整个流程：一条 INSERT、两条 UPDATE 和一条 DELETE。这样可以同时看到不同 `type` 如何从同一批次中产生，并在消费端分别落到新增、更新和删除处理。JSON 字段是否完整、消息是否拆分以及端到端同步效果，仍需通过运行配置和真实消息验证。

### 3.2 再看配置开关：决定发送什么格式

以下示例写在 Canal Server 解压目录下的全局配置文件 `conf/canal.properties` 中。当前项目设计要求 `canal.mq.flatMessage=true`；实际运行实例是否加载该值，以及消费端是否按约定解析，仍须核对。

~~~properties
canal.mq.flatMessage=true
~~~

依据：[Canal 1.1.7 canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties) 和 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)。源码静态核对不等于实际发行包使用该配置。

### 3.3 再看 FlatMessage：字段如何形成

**FlatMessage JSON：** `data` 使用 INSERT/UPDATE 的行后列、DELETE 的行前列。UPDATE 的 `old` 根据变化列集合筛选旧列值，不是完整行前镜像；多行更新须核对实际输出。类中定义 `gtid` 字段并从 Header 赋值，但不保证每条消息都有可用 GTID；`es` 是事件执行时间，`ts` 是转换时系统时间，均不等于业务记录的原始 `create_time`。

这里要区分三个层次：Canal 先组织 `FlatMessage` 对象，再将对象序列化为 FlatMessage JSON，最后把 JSON 作为 RocketMQ 消息体发送：

```text
FlatMessage 对象 → FlatMessage JSON → RocketMQ 消息体
```

下面的 JSON 只展示 `type`、`data`、`old` 的字段关系，是精简示例，不代表当前运行实例最终发送的完整消息；数据库、表名、主键、时间和 `gtid` 等字段是否存在，仍需通过实际消息样例核对。

例如，同一张消费记录表中多条记录的三类事件可以这样理解：

**INSERT：** `data` 是新插入的行，`old` 通常为空。

```json
{
  "type": "INSERT",
  "data": [
    {"consume_id": "c-1001", "status": "成功", "version": 2},
    {"consume_id": "c-1002", "status": "成功", "version": 3}
  ],
  "old": []
}
```

**UPDATE：** `data` 是更新后的行，`old` 只列出发生变化字段的旧值。

```json
{
  "type": "UPDATE",
  "data": [
    {"consume_id": "c-1001", "status": "成功", "version": 2},
    {"consume_id": "c-1002", "status": "成功", "version": 3}
  ],
  "old": [
    {"status": "异常（未确定）", "version": 1},
    {"status": "异常（未确定）", "version": 2}
  ]
}
```

**DELETE：** `data` 保留删除前的行，用于定位和记录删除事件，`old` 通常为空。

```json
{
  "type": "DELETE",
  "data": [
    {"consume_id": "c-1001", "status": "成功", "version": 2},
    {"consume_id": "c-1002", "status": "成功", "version": 3}
  ],
  "old": []
}
```

上面是字段含义示例，不代表当前运行实例的完整消息样例；多行 UPDATE、空值字段、未变化字段以及 `gtid`、`es`、`ts` 的实际输出仍需结合运行配置和真实消息核对。

**二进制消息：** 先解码 Canal Packet/Messages，再解析 Entry 的 RowChange/RowData。Entry Header 可保留日志文件和偏移；按键分区拆行时复制原 Header，但不新增拆分前的原始行序号，不能只凭文件和偏移区分同一原始事件内的多行。

**项目字段契约：** 业务主键、操作类型、原始 `create_time`、来源映射和必要业务列按用途传递。新增、更新携带同次变更的行后数据及 `version`；DELETE 保留删除前的 `consume_id`（业务代码中可映射为 `consumeId`）、原始 `create_time` 等定位信息。独立事件 ID是否必要仍待确认，MQ messageId、Canal batchId 和拆分后下标不能单独作为稳定源事件身份。

**UPDATE 完整行约束：** 源库应使用 `binlog_row_image=FULL`，使 UPDATE 的 Binlog 行事件包含完整的更新后列；Canal 转换时不能再过滤 ES 所需字段。生产端发送前还要按表定义检查 `data` 是否包含 `consume_id`、`create_time`、业务字段和 `version` 等必要列，缺少字段时不能直接按完整文档覆盖写入，应进入重试或异常处理流程。`old` 只记录变化字段的旧值，不能用来补齐缺失的完整行。

完整性满足后，消费端可以读取 `data` 的完整行，按业务主键和 `version` 判断新旧，再用完整行覆盖写入 ES；不需要依赖 `old` 合并字段。

### 3.4 最后看多行 FlatMessage：如何分区和发送

FlatMessage 的 `data` 可以包含多条行变更。生产端先按 `data` 中的行计算分区，再决定这些行进入一个队列还是多个队列；RocketMQ 只保存和分发消息，不解析 JSON 内的业务数组。

假设转换后的 FlatMessage JSON 如下，`data` 中有两条 UPDATE 行，`old` 按同一下标保存对应的旧值：

```json
{
  "type": "UPDATE",
  "data": [
    {"consume_id": "c-1001", "status": "成功"},
    {"consume_id": "c-1002", "status": "成功"}
  ],
  "old": [
    {"status": "异常（未确定）"},
    {"status": "异常（未确定）"}
  ]
}
```

如果两行被计算到同一个队列，可以把上面的完整 JSON 作为一条 RocketMQ 消息发送。Broker 保存一条消息，消费端取到后解析 `data` 数组并循环处理两行；`data[0]` 必须继续和 `old[0]` 配对，`data[1]` 必须继续和 `old[1]` 配对。

如果多条记录被计算到不同队列，生产端可以按分区结果拆成多条 RocketMQ 消息。每条消息可以继续携带同一队列中的多条行变更，消费端分别处理这些消息，但每条消息仍要保留行后值与旧值的对应关系。

RocketMQ 的处理边界是：生产端把消息写入指定 topic 的队列，Broker 持久化消息并维护队列中的位置，消费者按消费组读取消息并提交消费进度。RocketMQ 不会自动把一条 JSON 内的 `data` 数组拆成多条业务记录，也不会替业务代码合并或补齐字段。是否拆分、如何计算分区键以及一条消息最终包含几行，由 Canal 生产端的转换和分区逻辑决定；实际发行包、项目配置和真实消息仍需验证。

分区代码按 `data` 中的行计算分区，并用同一下标取对应的 `old`；二进制按键分区复制对应 RowData 后重新分组。源码体现了配对方式，但项目仍须验证多行更新、空值、列集合和业务版本在拆分后的对应关系。

这里的“同一下标”是指同一条多行 UPDATE 消息中，`data[i]` 和 `old[i]` 描述同一行的更新后值和更新前旧值。例如：

```json
{
  "type": "UPDATE",
  "data": [
    {"consume_id": "c-1001", "status": "成功"},
    {"consume_id": "c-1002", "status": "成功"}
  ],
  "old": [
    {"status": "异常（未确定）"},
    {"status": "异常（未确定）"}
  ]
}
```

上例中，`data[0]` 与 `old[0]` 表示 `c-1001` 从“异常（未确定）”变为“成功”，`data[1]` 与 `old[1]` 表示 `c-1002` 发生同样的状态变化。如果按 `consume_id` 分区，拆分到不同队列时也必须分别保留这两组对应关系，不能先单独排序或重新组合 `data`、`old`。INSERT 和 DELETE 通常没有需要配对的 `old`；多行 UPDATE 的数组长度、空值表示和实际拆分结果仍需结合运行消息样例核对。

同一份多行 FlatMessage，可能因为分区结果不同而形成不同的 RocketMQ 消息形态。下面的队列名仅用于说明，不代表项目已经确定的队列映射。

**场景一：多行变更被拆成多条消息**

如果四条记录被计算到两个队列，生产端可以分别发送两条消息，每条消息仍保留多条行变更及其对应的 `old`：

```json
{
  "type": "UPDATE",
  "data": [
    {"consume_id": "c-1001", "status": "成功"},
    {"consume_id": "c-1003", "status": "成功"}
  ],
  "old": [
    {"status": "异常（未确定）"},
    {"status": "异常（未确定）"}
  ]
}
```

```json
{
  "type": "UPDATE",
  "data": [
    {"consume_id": "c-1002", "status": "成功"},
    {"consume_id": "c-1004", "status": "成功"}
  ],
  "old": [
    {"status": "异常（未确定）"},
    {"status": "异常（未确定）"}
  ]
}
```

**场景二：多行变更仍由一条消息发送**

如果 `c-1001` 和 `c-1002` 被计算到同一个队列，生产端可以保留一条包含两条行变更的消息，消息中的 `data` 和 `old` 仍按下标对应：

```json
{
  "type": "UPDATE",
  "data": [
    {"consume_id": "c-1001", "status": "成功"},
    {"consume_id": "c-1002", "status": "成功"}
  ],
  "old": [
    {"status": "异常（未确定）"},
    {"status": "异常（未确定）"}
  ]
}
```

这两个场景都属于 FlatMessage JSON 作为 RocketMQ 消息体发送的示意；实际生产端是否按行拆分、按队列合并，以及一条消息最终包含多少行，仍需结合 Canal 发行包、项目配置和真实消息样例核对。

## 4 参考文章与项目设计的区别

**消息解析方式**

**参考文章：** 消费代码把消息解析为 JSON，从 `data` 读取订单列表，再按 `type` 分派 INSERT、UPDATE、DELETE。

参考文章中对应的消费者代码见 [使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<./999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md>) 第 1038 行附近，核心逻辑如下：

```java
public void ordersMessageHandle(String message) throws IOException {
    if (StringUtils.isEmpty(message)) {
        return;
    }

    JSONObject msgObj = JSONObject.parseObject(message);

    // 忽略 DDL 消息
    if (Boolean.TRUE.equals(msgObj.get("isDdl"))) {
        return;
    }

    // 读取消息类型并分派
    Object typeObj = msgObj.get("type");
    if (Objects.isNull(typeObj)
            || ElasticMessageTypeEnum.getType(typeObj.toString()) == null) {
        return;
    }

    doHandler(ElasticMessageTypeEnum.getType(typeObj.toString()), msgObj);
}

private void doHandler(ElasticMessageTypeEnum type,
                       JSONObject msgObj) throws IOException {
    // 将 data 解析成订单列表
    List<OrderDto> orderList = msgObj.getObject(
            "data", new TypeReference<List<OrderDto>>() {});

    if (CollectionUtils.isEmpty(orderList)) {
        return;
    }

    switch (type) {
        case INSERT:
            insertProcess(orderList);
            break;
        case UPDATE:
            updateProcess(orderList);
            break;
        case DELETE:
            deleteProcess(orderList);
            break;
    }
}
```

文章后续的 `insertProcess`、`updateProcess` 和 `deleteProcess` 都接收 `orderList`，并通过循环逐条处理订单。因此，参考文章能够证明 `data` 可以包含多条数据；但该代码没有解析或使用 `old`，不能证明多行 UPDATE 的 `data[i]` 与 `old[i]` 配对规则。

**当前项目：** 设计上固定采用 FlatMessage JSON，按约定区分行后值、DELETE 行前值和 UPDATE 的变化列旧值；运行实例配置和真实消息字段仍须核对。

**业务版本传递**

**参考文章：** `orders` 表有 `version` 字段，更新示例执行 `version = version + 1`。

**当前项目：** 仅新分表增加业务 `version`，旧表不增加；新增、更新须保持数据与版本来自同次变更，DELETE 不生成 ES 逐条删除版本。

## 5 待确认或待验证事项

- 运行实例是否实际加载 `canal.mq.flatMessage=true`，以及真实消息字段是否满足 JSON 消费契约？
- 源库是否实际使用 `binlog_row_image=FULL`，并且 Canal 输出的 UPDATE `data` 是否包含 ES 所需的完整字段？
- 生产端是否已对 UPDATE `data` 执行完整性校验，缺少必要字段时是否阻止完整文档覆盖写入？
- 多行 UPDATE 的 `data`、`old`、空值、列集合和业务版本是否正确对应？
- 是否需要独立事件级去重及 `eventId`？如采用坐标事件 ID，如何在拆分前保留原始行序号？

## 6 复核记录

本轮执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)，将整条处理链路示例扩展为四条消费记录和三种操作类型，并复核了表格中的 Entry、FlatMessage、RocketMQ 消息及 ES 处理结果对应关系；未执行真实 Canal、RocketMQ 或 ES 联调。

本轮补充四条消费记录在 MySQL、Binlog、Canal 批次、FlatMessage、RocketMQ 和 ES 阶段的具体数据示例；示例不代表真实运行消息，未执行端到端联调。
