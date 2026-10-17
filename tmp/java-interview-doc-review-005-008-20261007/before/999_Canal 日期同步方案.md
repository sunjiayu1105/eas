# Canal 日期同步方案

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

> 当前链路对照：005～008 已采用 TCP 投递组件、RocketMQ 和自定义 ES 消费服务。下文 Adapter 的时区与 JDBC 连接说明保留为历史方案；当前服务须在行事件转换与 ES 序列化过程中核对相同时间点，不能认为历史 Adapter 配置仍是当前接入配置。

**目标：各环节表示同一个时间点**

MySQL、Canal Adapter 和查询接口要对同一交易时间使用一致的业务时区。最终写入 ES 的日期应能明确表示时间点，例如带偏移的 ISO 8601 字符串或毫秒时间戳；仅设置 JVM 时区不能证明整条链路正确。

## 1. 先确认数据库类型

`TIMESTAMP` 会涉及 MySQL 会话时区转换；`DATETIME` 表示不自带时区的日期时间，两者不能仅因客户端显示相同而按相同方式处理。新表 `tradeTime` 的真实类型、精度及历史值采用的时区仍需 DDL 和数据样例确认。

UTC 是协调世界时，偏移为 `+00:00`。项目使用的 `Asia/Shanghai` 在当前业务日期示例中为 `UTC+08:00`。日期被 ES 的 `date` 解析后按时间点索引；`epoch_millis` 是 Unix epoch 起算的毫秒值，本身不需要再加 8 小时。

## 2. 沿写入链路核对转换

```text
MySQL tradeTime（先核对 TIMESTAMP / DATETIME）
→ Canal 解析行事件
→ Adapter 按字段类型与业务时区取得正确时间点
→ 输出带偏移的 ISO 8601 字符串或毫秒时间戳
→ ES 按 Mapping 解析
```

业务含义为上海时间的 `2026-09-06 14:00:00`，可表示为：

```text
2026-09-06T14:00:00+08:00
```

这是期望的转换示例，不能据此认定 Adapter 已自动输出这种格式。带正确偏移的值不得再次手动加减 8 小时。

## 3. 核对 JVM、JDBC 和数据库会话

**参数配置在实际执行转换的进程**

若由 Adapter 转换，检查 Adapter JVM 参数：

```text
-Duser.timezone=Asia/Shanghai
```

只有 Canal Server 也执行相关转换时，才把其 JVM 配置纳入该环节。JDBC 查询映射还需核对驱动版本及连接参数，例如：

```text
jdbc:mysql://127.0.0.1:3306/test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
```

`serverTimezone` 的作用与 Connector/J 版本有关，不能单凭此参数认定 MySQL 会话时区已被设置。实际驱动版本未提供，需要检查其会话时区选项与配置。

**在实际连接上查看会话值**

```sql
SELECT @@global.time_zone,
       @@session.time_zone,
       @@system_time_zone;
```

`@@session.time_zone` 只代表执行 SQL 的当前连接，必须核对 Adapter 实际使用的连接，不能用另一个客户端连接的结果替代。

## 4. 对照 Mapping 与查询输入验收

当前日期格式为 `strict_date_optional_time||epoch_millis`，不应假设带空格的原始 MySQL 日期文本会按预期接受。检查实际发往 ES 的 JSON，以及 ES 解析后的排序值，再与数据库的业务时间点对照。

查询接口接收不带时区的日期文本时，也要按同一业务时区解释，再构造范围条件。边界时刻、日期跨天和毫秒精度必须核对；具体测试值与业务日期范围由项目确定。

## 5. 验证边界

本次清理了 Anki 导出头、包裹引号及 HTML 转义，恢复为普通 Markdown；未连接数据库或运行 Adapter。实际字段类型、JDBC 驱动、转换代码、发往 ES 的 JSON 与查询解析链路仍需联调。Mapping 详见[消费记录 ES Mapping 设计](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_消费记录 ES Mapping 设计.md:1>)。
