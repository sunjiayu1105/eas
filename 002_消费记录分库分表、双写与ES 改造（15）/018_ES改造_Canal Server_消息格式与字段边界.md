# 018_ES改造_Canal Server_消息格式与字段边界

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与适用边界

- 本文只说明项目采用的 `FlatMessage` JSON 及项目字段契约，不展开未采用的二进制方案；日志读取见 [Entry结构与行事件解析](<./016_ES改造_Canal Server_Entry结构与行事件解析.md>)，事务边界见 [事务边界与特殊事件](<./017_ES改造_Canal Server_事务边界与特殊事件.md>)。
- 新增、更新传递同次变更的数据和业务 `version`；DELETE 保留删除前定位信息，不转换为 ES 逐条 Delete。设计记录不表示字段已端到端验证。
- 当前项目设计上采用 FlatMessage JSON：要求 `canal.mq.flatMessage=true`，自定义 ES 消费服务按 JSON 解析；实际运行配置、真实消息样例和端到端解析结果仍待验证。
- 本文按 Canal 初学者能够连续理解的顺序编写：先说明消息在 Canal 链路中的位置，再说明格式开关、字段含义、拆分对应关系和项目字段契约。
- 当前项目要求 UPDATE 的 `data` 保证为完整更新后行；只有通过完整性校验的消息，才能按完整文档覆盖写入 ES。该要求的配置和运行效果仍需验证。
- 当前 ES 同步允许重复处理，通过业务版本控制防止旧数据覆盖新数据，不为避免重复写入而引入独立事件 ID；实际版本校验和冲突处理仍需验证。

## 2 面试口述精炼版本

**消费端收到一个对象，逐行处理 data**

项目采用 FlatMessage JSON：一条 RocketMQ 消息的 body 是一个 JSON 对象，data 数组可以包含同一张表、同一操作类型的多条行变更。INSERT、UPDATE 取行后值，DELETE 取行前值；UPDATE 的 old 保存变化列旧值。消费端遍历 data，完整行通过校验后才用于 ES 覆盖写入。配置加载和真实消息仍待验证，字段与示例见[详细方案](#3-详细方案)。

## 3 详细方案

### 3.1 消息体、FlatMessage 和行数据

FlatMessage 是 Canal 的 Java 消息对象；FlatMessage JSON 是它序列化后的形式。当前内置生产端在 flatMessage 路径中逐个对象序列化，将 JSON 字节作为 RocketMQ Message 的 body。一次批量发送可以发送多个 Message，但每个 body 仍是一个 FlatMessage 对象，不是多个 FlatMessage 组成的外层数组。

一个对象只有一个 database、table、type；data 内可以有多行，但不会把 INSERT 和 UPDATE 混在同一个对象中。消费端一次回调拿到多少条 RocketMQ 消息，是消费 API 的批量行为，与一个 body 中有多少行不同。

全局配置 `conf/canal.properties` 中的 `canal.mq.flatMessage` 必须为 `true`，否则消息格式与当前 JSON 消费方式不匹配。配置全集见 [公共配置](<./014_ES改造_Canal Server_公共配置.md>)；完整处理过程见 [处理链路总览](<./012_ES改造_Canal Server_处理链路总览.md>)。

### 3.2 字段含义

**顶层字段：** 下表列出的都是 Canal `FlatMessage` 定义的字段，序列化后位于 JSON 对象的最外层，用来描述消息来源、操作类型和行数据，例如 `database`、`table`、`type`、`data`、`old`。

| 字段 | 含义与读取方式 |
| --- | --- |
| id | Canal 批次标识；多个转换或拆分对象可能共享它，不能单独用于事件去重 |
| database、table | 来源库名、表名；不是 ES 索引名 |
| type | INSERT、UPDATE、DELETE 等操作类型，决定处理分支 |
| isDdl | 是否为结构变更事件；不能当普通行事件处理 |
| pkNames | 源表主键列名列表 |
| data | 行对象数组；INSERT/UPDATE 是行后列，DELETE 是行前列 |
| old | UPDATE 变化列旧值；不是完整前镜像；其他类型可能为空或 null |
| sqlType | 列名到 JDBC SQL 类型编号的映射 |
| mysqlType | 列名到 MySQL 类型名称的映射 |
| es | 事件执行时间，毫秒时间戳；不是 Elasticsearch 的缩写 |
| ts | 转换时系统时间，毫秒时间戳 |
| sql | 事件 SQL 信息，可能为空；不保证有原始 DML SQL |
| gtid | 来源 Header 中的 GTID；可能为空，不保证每条可用 |

**行内业务字段：** `data`、`old` 中的行对象使用源表列名作为字段名，例如 `consume_id`、`status`、`version`、`create_time`。这些字段由源表结构和本次事件提供的列决定，不属于 FlatMessage 固定定义的顶层字段。顶层的 `es`、`ts` 都不能代替行内原始 `create_time`。

**字段值与示例边界：** 定义了字段，不代表每条消息中都有有效值。例如 `old` 主要用于 UPDATE 的变化列旧值，`gtid` 可能为空；文档中的精简 JSON 示例也可能省略部分顶层字段，不能据此判断实际消息没有这些字段。

**行值类型：** 源码中的 `data`、`old` 是 `List<Map<String,String>>`：非空列值通常以字符串输出，SQL NULL 对应 JSON null。消费端需按业务类型转换 `version` 等字段，不能按字符串字典序比较版本。

### 3.3 一条消息包含三行 UPDATE 的示例

以下是合法 JSON 的精简示例。库表名、状态文字、时间和版本均为说明值，不是实测消息；为突出关系仅列四个业务列，省略 id、sql、类型映射等顶层字段。它不能作为完整生产行的字段清单。

```json
{
  "database": "eas",
  "table": "consume_record_01",
  "type": "UPDATE",
  "isDdl": false,
  "pkNames": [
    "consume_id"
  ],
  "es": 1793064000000,
  "ts": 1793064000012,
  "gtid": null,
  "data": [
    {
      "consume_id": "c-1002",
      "status": "成功",
      "version": "2",
      "create_time": "2026-10-27 09:00:00"
    },
    {
      "consume_id": "c-1004",
      "status": "成功",
      "version": "5",
      "create_time": "2026-10-27 09:05:00"
    },
    {
      "consume_id": "c-1005",
      "status": "成功",
      "version": "8",
      "create_time": "2026-10-27 09:10:00"
    }
  ],
  "old": [
    {
      "status": "异常（未确定）",
      "version": "1"
    },
    {
      "status": "异常（未确定）",
      "version": "4"
    },
    {
      "status": "异常（未确定）",
      "version": "7"
    }
  ]
}
```

- data[0] 与 old[0] 对应 c-1002：状态从“异常（未确定）”变为“成功”，版本 1 → 2。
- data[1] 与 old[1] 对应 c-1004：状态从“异常（未确定）”变为“成功”，版本 4 → 5。
- data[2] 与 old[2] 对应 c-1005：状态从“异常（未确定）”变为“成功”，版本 7 → 8。

消费端遍历 data 中的三行即可处理三个更新；当前完整行覆盖方案不需要用 old 合并字段。old 的配对说明用于理解消息结构，多行、空值和变化列集合仍须实测。分区后如何保留对应关系见 [Topic路由与多行分区](<./019_ES改造_Canal Server_Topic路由与多行分区.md>)。

### 3.4 UPDATE 完整行与业务字段契约

**项目字段契约：** 业务主键、操作类型、原始 `create_time`、来源映射和必要业务列按用途传递。新增、更新携带同次变更的行后数据及 `version`；DELETE 保留删除前的 `consume_id`（业务代码中可映射为 `consumeId`）、原始 `create_time` 等定位信息。

**UPDATE 完整行约束：** 源库应使用 `binlog_row_image=FULL`，使 UPDATE 的 Binlog 行事件包含完整的更新后列；Canal 转换时不能再过滤 ES 所需字段。生产端发送前还要按表定义检查 `data` 是否包含 `consume_id`、`create_time`、业务字段和 `version` 等必要列，缺少字段时不能直接按完整文档覆盖写入，应进入重试或异常处理流程。`old` 只记录变化字段的旧值，不能用来补齐缺失的完整行。

完整性满足后，消费端可以读取 `data` 的完整行，按业务主键和 `version` 判断新旧，再用完整行覆盖写入 ES；不需要依赖 `old` 合并字段。

**重复消息与版本控制：** 独立事件 ID 用于识别同一次源数据变更，常用于事件级去重和追踪。当前 ES 同步允许重复请求，不为避免重复写入而增加这个字段。这里 `consume_id` 标识消费记录，`version` 标识该记录的业务版本；每次有效更新应递增版本，数据与版本必须来自同次变更。重复投递必须携带相同版本、相同操作和内容，并定位到同一个物理索引、同一个 ES 文档 ID。

- **旧版本晚到：** 例如 ES 已是版本 3，再收到版本 2，应通过 ES 写入时的版本校验拒绝覆盖。
- **相同版本重复到达：** 例如版本 3 收到两次，具体是拒绝重复写入还是允许相同内容覆盖，取决于实际版本策略；两种情况下都必须保证同一版本对应相同内容。
- **冲突与开销：** 已核实为重复或过期的版本冲突按幂等规则处理，避免无意义重试；其他异常不能直接忽略。重复消息仍会增加解析、网络和 ES 请求开销，但在上述前提成立时不会改变最终文档状态。

这些规则只保证 ES 文档状态，不代表通知、计数等额外动作只执行一次。具体实现与验证归 [重复消息幂等](<./040_ES改造_ES_重复消息幂等.md>)，本页只明确消息必须携带的身份、版本和内容约束。

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
- 重复投递是否保持相同业务版本、操作、内容及物理索引和文档 ID？
- 实际 ES 版本策略是否正确处理旧版本与相同版本，并区分可忽略的重复、过期冲突和需要处理的异常？

## 6 复核记录

以下保留历史整理记录；其中完整链路现归 012，多行分区现归 019。本页只保留消息格式和字段契约。

本轮执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)，将整条处理链路示例扩展为四条消费记录和三种操作类型，并复核了表格中的 Entry、FlatMessage、RocketMQ 消息及 ES 处理结果对应关系；未执行真实 Canal、RocketMQ 或 ES 联调。

本轮补充四条消费记录在 MySQL、Binlog、Canal 批次、FlatMessage、RocketMQ 和 ES 阶段的具体数据示例；示例不代表真实运行消息，未执行端到端联调。

本轮删除原“用流程表确认每一步的交接”段落，改为按“输入数据→处理动作→处理后数据→下一步输入”重新组织 3.1，便于 Canal 初学者连续理解数据如何变化。

本轮将“整条处理链路”和“按数据变化跟踪同一批数据”合并为一个章节标题，删除重复的抽象步骤列表，保留统一流程表和对应 FlatMessage JSON 示例。

本轮将原流程表改为纯 Markdown 的分步骤列表，移除 HTML 换行标签，并让每个步骤、每条记录和每个处理结果单独换行展示，便于初学者逐项跟踪数据变化。

本轮补充 RocketMQ 消费端消息格式总结，说明一个 RocketMQ 消息与一个 FlatMessage JSON 的对应关系、一个 FlatMessage 中多条 `data` 行变更的情况，以及核心字段含义；未执行真实 RocketMQ 消费联调。

本轮补充包含三条 UPDATE 行变更的 FlatMessage JSON，并说明 `data[i]` 与 `old[i]` 的逐条对应关系；示例字段和时间值不代表真实运行消息。

已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本轮按主题归属去重并复核标题、编号、引用和示例；未执行 MySQL、Canal、RocketMQ 或 ES 联调。

## 7 参考来源

- [FlatMessage](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/FlatMessage.java)：字段定义及 data、old 列值类型。
- [MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java)：列值、old 与类型映射的填充。
- [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)：逐个 FlatMessage 序列化到 Message body。

## 8 异议与分析

旧示例把行内 version 写成 JSON 数字，但工作区源码 FlatMessage 的行值类型为字符串，MQMessageUtils 使用 Column.getValue() 填值。本轮改为字符串，并补充消费端类型转换说明。旧示例的月份表名、无效 GTID 占位值和事件时间也已调整为明确的示意值；这些修正不是新增部署事实。
