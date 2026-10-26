# 018_ES改造_Canal Server_消息格式与字段边界

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与适用边界

- 本文只说明 `FlatMessage` JSON、Canal 二进制消息及项目字段契约；日志读取见 [Binlog读取与Entry解析](<./017_ES改造_Canal Server_Binlog读取与Entry解析.md>)，事务边界见 [事务边界与特殊事件](<./019_ES改造_Canal Server_事务边界与特殊事件.md>)。
- 新增、更新传递同次变更的数据和业务 `version`；DELETE 保留删除前定位信息，不转换为 ES 逐条 Delete。设计记录不表示字段已端到端验证。
- 当前项目设计上采用 FlatMessage JSON：要求 `canal.mq.flatMessage=true`，自定义 ES 消费服务按 JSON 解析；实际运行配置、真实消息样例和端到端解析结果仍待验证。
- 本文按 Canal 初学者能够连续理解的顺序编写：先说明消息在 Canal 链路中的位置，再说明格式开关、字段含义、拆分对应关系和项目字段契约。

## 2 面试口述精炼版本

先把消息链路串起来：Canal 从 MySQL binlog 读取变更，先解析成 Entry，再把行事件转换成下游可以接收的消息。本文重点说明转换后的消息格式和字段边界；Binlog 读取与 Entry 解析见 [Binlog读取与Entry解析](<./017_ES改造_Canal Server_Binlog读取与Entry解析.md>)。

**先区分输入和输出**

Canal 内部处理的输入是 Entry 和 RowData，发送给 RocketMQ 的输出可以是 FlatMessage JSON。二进制消息要先解码 Packet/Messages，再解析 Entry，不能把 JSON 当二进制解析。

**再理解 FlatMessage 字段**

`FlatMessage` 是扁平 JSON：新增、更新从 `data` 取行后值，DELETE 从 `data` 取行前值，UPDATE 的 `old` 按变化列筛选且不是完整前镜像。项目新增、更新还要核对业务 `version` 与数据是否始终配对；字段完整性和拆行结果仍待消息样例验证。

## 3 详细方案

### 3.1 先建立消息链路概念

可以按“Binlog 事件 → Canal Entry → FlatMessage JSON → RocketMQ 消费端”的顺序理解本章。Binlog 事件先由 Canal 解析成 Entry 和 RowData；消息转换阶段再根据事件类型组织 `data`、`old` 等字段；RocketMQ 消费端最后按 JSON 读取这些字段。本文只展开中间的消息格式和字段边界，不重复 [Binlog读取与Entry解析](<./017_ES改造_Canal Server_Binlog读取与Entry解析.md>) 中的读取细节，也不展开 RocketMQ 发布确认。

### 3.2 配置文件与格式开关

以下示例写在 Canal Server 解压目录下的全局配置文件 `conf/canal.properties` 中。当前项目设计要求 `canal.mq.flatMessage=true`；实际运行实例是否加载该值，以及消费端是否按约定解析，仍须核对。

~~~properties
canal.mq.flatMessage=true
~~~

依据：[Canal 1.1.7 canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties) 和 [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)。源码静态核对不等于实际发行包使用该配置。

### 3.3 字段与转换边界

**FlatMessage JSON：** `data` 使用 INSERT/UPDATE 的行后列、DELETE 的行前列。UPDATE 的 `old` 根据变化列集合筛选旧列值，不是完整行前镜像；多行更新须核对实际输出。类中定义 `gtid` 字段并从 Header 赋值，但不保证每条消息都有可用 GTID；`es` 是事件执行时间，`ts` 是转换时系统时间，均不等于业务记录的原始 `create_time`。

这里要区分三个层次：Canal 先组织 `FlatMessage` 对象，再将对象序列化为 FlatMessage JSON，最后把 JSON 作为 RocketMQ 消息体发送：

```text
FlatMessage 对象 → FlatMessage JSON → RocketMQ 消息体
```

下面的 JSON 只展示 `type`、`data`、`old` 的字段关系，是精简示例，不代表当前运行实例最终发送的完整消息；数据库、表名、主键、时间和 `gtid` 等字段是否存在，仍需通过实际消息样例核对。

例如，同一条消费记录的三类事件可以这样理解：

**INSERT：** `data` 是新插入的行，`old` 通常为空。

```json
{
  "type": "INSERT",
  "data": [{"consume_id": "c-1001", "amount": "10.00", "version": 1}],
  "old": []
}
```

**UPDATE：** `data` 是更新后的行，`old` 只列出发生变化字段的旧值。

```json
{
  "type": "UPDATE",
  "data": [{"consume_id": "c-1001", "amount": "12.00", "version": 2}],
  "old": [{"amount": "10.00", "version": 1}]
}
```

**DELETE：** `data` 保留删除前的行，用于定位和记录删除事件，`old` 通常为空。

```json
{
  "type": "DELETE",
  "data": [{"consume_id": "c-1001", "amount": "12.00", "version": 2}],
  "old": []
}
```

上面是字段含义示例，不代表当前运行实例的完整消息样例；多行 UPDATE、空值字段、未变化字段以及 `gtid`、`es`、`ts` 的实际输出仍需结合运行配置和真实消息核对。

**二进制消息：** 先解码 Canal Packet/Messages，再解析 Entry 的 RowChange/RowData。Entry Header 可保留日志文件和偏移；按键分区拆行时复制原 Header，但不新增拆分前的原始行序号，不能只凭文件和偏移区分同一原始事件内的多行。

**项目字段契约：** 业务主键、操作类型、原始 `create_time`、来源映射和必要业务列按用途传递。新增、更新携带同次变更的行后数据及 `version`；DELETE 保留删除前的 `consume_id`（业务代码中可映射为 `consumeId`）、原始 `create_time` 等定位信息。独立事件 ID是否必要仍待确认，MQ messageId、Canal batchId 和拆分后下标不能单独作为稳定源事件身份。

### 3.4 拆分后的对应关系

分区代码按 `data` 中的行计算分区，并用同一下标取对应的 `old`；二进制按键分区复制对应 RowData 后重新分组。源码体现了配对方式，但项目仍须验证多行更新、空值、列集合和业务版本在拆分后的对应关系。

这里的“同一下标”是指同一条多行 UPDATE 消息中，`data[i]` 和 `old[i]` 描述同一行的更新后值和更新前旧值。例如：

```json
{
  "type": "UPDATE",
  "data": [
    {"consume_id": "c-1001", "amount": "12.00"},
    {"consume_id": "c-1002", "amount": "22.00"}
  ],
  "old": [
    {"amount": "10.00"},
    {"amount": "20.00"}
  ]
}
```

上例中，`data[0]` 与 `old[0]` 表示 `c-1001` 从 `10.00` 更新为 `12.00`，`data[1]` 与 `old[1]` 表示 `c-1002` 从 `20.00` 更新为 `22.00`。如果按 `consume_id` 分区，拆分到不同队列时也必须分别保留这两组对应关系，不能先单独排序或重新组合 `data`、`old`。INSERT 和 DELETE 通常没有需要配对的 `old`；多行 UPDATE 的数组长度、空值表示和实际拆分结果仍需结合运行消息样例核对。

## 4 参考文章与项目设计的区别

**消息解析方式**

**参考文章：** 消费代码把消息解析为 JSON，从 `data` 读取订单列表，再按 `type` 分派 INSERT、UPDATE、DELETE。

**当前项目：** 设计上固定采用 FlatMessage JSON，按约定区分行后值、DELETE 行前值和 UPDATE 的变化列旧值；运行实例配置和真实消息字段仍须核对。

**业务版本传递**

**参考文章：** `orders` 表有 `version` 字段，更新示例执行 `version = version + 1`。

**当前项目：** 仅新分表增加业务 `version`，旧表不增加；新增、更新须保持数据与版本来自同次变更，DELETE 不生成 ES 逐条删除版本。

## 5 待确认或待验证事项

- 运行实例是否实际加载 `canal.mq.flatMessage=true`，以及真实消息字段是否满足 JSON 消费契约？
- 多行 UPDATE 的 `data`、`old`、空值、列集合和业务版本是否正确对应？
- 是否需要独立事件级去重及 `eventId`？如采用坐标事件 ID，如何在拆分前保留原始行序号？

## 6 复核记录

本文由 [java-interview-doc-split](C:/Users/Administrator/.codex/skills/java-interview-doc-split/SKILL.md) 拆分生成；已静态核对 Canal 1.1.7 源码，未执行实际消息样例和联调。

本轮根据用户确认，将当前项目消息格式设计收敛为 FlatMessage JSON；运行配置、消息样例和端到端解析仍未验证。

本轮已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)，补充并复核了 INSERT、UPDATE、DELETE 的 FlatMessage JSON 字段含义示例。

本轮再次执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)，按 Canal 初学者的连续理解顺序调整了口述版和详细方案，并将该编写规则记录到“已确认规则”。

本轮继续执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)，在拆分对应关系中补充多行 UPDATE 的 `data[i]` 与 `old[i]` 示例及分区后的保持规则。

本轮再次执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)，补充 FlatMessage 对象、FlatMessage JSON 与 RocketMQ 消息体之间的关系，并明确示例的精简范围。
