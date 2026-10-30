# 045_ES改造_ES_扫描任务持久化与调度

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则

- 不建立待校验任务表，扫描结果直接进入当前页比较流程。
- 每张物理表独立维护一条扫描进度记录。
- 使用 `update_time + consume_id` 复合游标；每轮固定扫描上限为当前时间减 5 分钟。
- 初次扫描从最早历史数据开始；后续查询回看 10 分钟重叠窗口。
- 当前页全部处理成功后更新游标；任一记录查询或补偿失败时保留原游标。
- DELETE 数据已经不存在于 MySQL 新表，不会被扫描，也不会创建校验任务。

## 2 面试口述精炼版本

每张物理表维护一条独立进度。扫描程序每轮先记录 `scan_end_time = 当前时间 - 5 分钟`，再按 `update_time + consume_id` 排序分页；查询起点回看 10 分钟，避免晚提交和分页边界漏查。当前页全部比较或补偿成功后，才把游标推进到本页最后一条记录；处理中出现失败就保留旧游标，下一轮从重叠范围继续。

## 3 进度记录

每张物理表保存一条记录：

```text
db_name
table_name
last_update_time
last_consume_id
status
last_error
updated_at
```

字段含义：

- `db_name`、`table_name`：定位实际物理表；
- `last_update_time`、`last_consume_id`：复合游标，表示已成功处理到的位置；
- `status`：记录扫描中、正常或异常状态；
- `last_error`：最近一次扫描或处理失败原因；
- `updated_at`：进度记录更新时间。

不保存 `task_id`、任务领取状态、任务重试次数或任务版本，因为本方案不建立待校验任务表。

## 4 扫描流程

```text
读取物理表进度
→ 固定 scan_end_time = 当前时间 - 5 分钟
→ 从 last_update_time 回看 10 分钟
→ 按 update_time、consume_id 分页读取当前仍存在的记录
→ 直接比较 MySQL 记录与 ES 文档，必要时自动补偿
→ 当前页全部成功后更新 last_update_time、last_consume_id
→ 失败则保留原游标，下一轮重试
```

查询条件：

```sql
WHERE
    (
        update_time > :last_update_time - INTERVAL 10 MINUTE
        OR (
            update_time = :last_update_time
            AND consume_id > :last_consume_id
        )
    )
    AND update_time <= :scan_end_time
ORDER BY update_time, consume_id
LIMIT :page_size;
```

首次初始化：

```text
last_update_time = 该表最早历史数据的 update_time - 10 分钟
last_consume_id = 最小值
```

本轮查不到更多数据时保留游标，不置空；下一轮重新计算 `scan_end_time`，从重叠范围继续扫描。

## 5 调度与并发

同一物理表同一时间只允许一个扫描实例执行，可使用分片锁控制。锁只保护扫描进度更新，不能代替 ES 版本控制。当前页处理完成与游标更新应保持一致，避免多个实例同时推进同一条进度。

## 6 待确认或待验证事项

- `(update_time, consume_id)` 索引是否已建立并满足分页性能要求？
- 进度更新与当前页处理失败时，是否能可靠保留原游标？
- 分片锁的 TTL、续期、持有者校验和异常释放如何实现？

## 7 参考文档

- [044_ES改造_ES_新表与 ES 增量校验方案选择](<./044_ES改造_ES_新表与 ES 增量校验方案选择.md>)
- [046_ES改造_ES_增量比较与版本判断](<./046_ES改造_ES_增量比较与版本判断.md>)
- [047_ES改造_ES_自动补偿、并发与恢复](<./047_ES改造_ES_自动补偿、并发与恢复.md>)

## 8 执行记录

- 本轮删除待校验任务持久化模型，改为每张物理表维护一条扫描进度记录。
- 未运行 Canal、RocketMQ、MySQL 或 ES 服务。
