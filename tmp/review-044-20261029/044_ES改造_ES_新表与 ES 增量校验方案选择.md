# 044_ES改造_ES_新表与 ES 增量校验方案选择

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 主题边界

本文确定 MySQL 新表与 ES 增量校验的扫描入口。Binlog、Canal 和 RocketMQ 负责正常同步，不负责触发校验；校验程序直接按 `update_time` 扫描 MySQL 当前数据。

## 2 已确认规则

- 面试口述优先使用中文表达；涉及 SQL 或代码字段时，在中文名称后用括号补充英文名称，例如“本轮扫描截止时间（`scan_end_time`）”。
- 每轮扫描只处理 5 分钟之前已经更新的数据，并将这个时间点记录为本轮扫描截止时间（`scan_end_time`）。
- 5 分钟时间范围用于给 ES 同步链路预留处理时间，避开刚提交或尚未同步完成的数据。
- “一轮”指一次完整的定时扫描周期，一轮可以包含多次分页查询。
- 使用 `update_time + consume_id` 作为复合游标，保证同一轮分页顺序稳定，避免分页边界漏查。
- 每轮第一页从上一轮游标对应的时间往前回看 10 分钟，用于覆盖事务晚提交导致的漏查。
- 同一轮后续分页直接从上一页最后一条记录继续向后查询，不重复回退 10 分钟。
- 10 分钟重叠范围和复合游标分别解决不同问题：重叠范围覆盖事务晚提交，复合游标保证分页连续。
- 重叠扫描产生的重复数据允许再次比较和补偿，但处理必须安全。
- 面试口述先说明项目采用的方案，再说明不采用其他方案的原因；不同内容分段表达。
- 面试口述先说明每轮 `update_time` 的扫描范围、5 分钟截止和 10 分钟回看，再说明使用 `update_time + consume_id` 复合游标分页。


## 4 方案比较

| 对比项 | Binlog 定位 | `update_time` 扫描 |
|---|---|---|
| 校验对象 | 每条历史变更事件 | 扫描窗口内当前仍存在的记录 |
| 多次变更 | 可能产生多个过时校验任务 | 合并为当前记录的比较 |
| 主要依赖 | 日志位点、过滤和事件连续性 | `update_time` 维护、索引、游标和时间窗口 |
| 当前职责 | MySQL 到 ES 的正常同步 | MySQL 与 ES 当前状态校验 |

## 5 扫描边界

每轮开始时固定本轮扫描截止时间（`scan_end_time`），只读取 `update_time` 不晚于该时间的数据。第一分页的起点回看 10 分钟；同一轮后续分页不再回退，而是使用上一页最后一条记录的复合游标继续向后查询。

第一分页可以表达为：

```sql
WHERE update_time > :round_start_time
  AND update_time <= :scan_end_time
ORDER BY update_time, consume_id
LIMIT :page_size;
```

其中 `round_start_time` 是上一轮累计游标的 `last_update_time` 回退 10 分钟后的时间点。

后续分页表达为：

```sql
WHERE (
        update_time > :last_update_time
        OR (
            update_time = :last_update_time
            AND consume_id > :last_consume_id
        )
    )
    AND update_time <= :scan_end_time
ORDER BY update_time, consume_id
LIMIT :page_size;
```

不能写成下面这种条件：

```sql
WHERE update_time >= :last_update_time
  AND consume_id > :last_consume_id
```

例如上一页最后一条记录是：

```text
update_time = 10:00:00
consume_id = 1002
```

下一页应该读取：

```text
10:00:00，1003 → 应读取
10:01:00，500  → 也应读取
10:00:00，1001 → 不读取
```

因此，复合游标必须按 `update_time` 和 `consume_id` 的先后顺序整体比较。MySQL 也可以使用等价的行比较写法：

```sql
WHERE (update_time, consume_id) > (:last_update_time, :last_consume_id)
```

重叠窗口只用于每轮第一页；同一轮后续分页使用复合游标。重叠扫描产生的重复记录再次比较和补偿即可，不能产生重复的错误副作用。

## 6 选择结论与成立条件

选择 `update_time` 扫描可以直接检查当前仍存在的数据，避免为大量历史 Binlog 变更建立任务。成立前提是所有写入路径可靠维护 `update_time`，并且 `(update_time, consume_id)` 索引、5 分钟安全延迟和 10 分钟重叠窗口能够覆盖实际延迟。10 分钟窗口的实际覆盖能力仍需结合生产延迟数据验证，扫描遗漏还需要通过低频全量对账或重建能力兜底。

## 7 待确认或待验证事项

- `update_time` 的维护路径、精度和索引是否已核实？
- 5 分钟安全延迟和 10 分钟重叠窗口是否经过实际延迟数据验证？
- 第一分页回看窗口与后续分页复合游标的实现是否严格区分？
- 重复扫描时的比较和补偿是否具备幂等性？

## 8 执行记录

- 本轮执行了 [java-interview-doc-review-loop](C:\Users\Administrator\.codex\skills\java-interview-doc-review-loop\SKILL.md)，补回面试口述并检查了与 043、045 的边界一致性。
- 本轮进一步区分了第一分页的 10 分钟重叠范围与同轮后续分页的复合游标。
- 已完成静态章节、链接和 Markdown 格式检查；未运行 Canal、RocketMQ、MySQL 或 ES 服务。
