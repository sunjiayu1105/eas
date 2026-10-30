# 049_ES改造_ES_复合游标与轮次分页

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则

- 使用 `update_time + consume_id` 作为复合游标，保证同轮分页连续。
- 每张物理表维护一条进度记录，各轮更新同一条记录，不按轮次新增记录。
- 每页全部成功后保存游标；当前页失败停止本轮，保留该页开始前的进度；空页正常结束本轮。

## 2 面试口述精炼版本

**按复合游标连续翻页。** 由于多条记录可能有相同的 `update_time`，项目按 `update_time`、`consume_id` 排序并把两者作为复合游标。每页全部比较或补偿成功后，更新同一条进度记录的游标，下一页从该位置之后继续。当前页失败就停止本轮，保留此前成功页的进度，下一轮重试；查询到空页则正常结束本轮。详见[分页流程](#31-分页流程)。

## 3 详细方案

### 3.1 分页流程

同轮后续分页使用上一页最后一条记录的复合游标：

```sql
WHERE (
        update_time > :last_update_time
        OR (update_time = :last_update_time
            AND consume_id > :last_consume_id)
    )
    AND update_time <= :scan_end_time
ORDER BY update_time, consume_id
LIMIT :page_size;
```

不能写成 `update_time >= :last_update_time AND consume_id > :last_consume_id`，因为它会漏掉更新时间更晚但 ID 更小的记录。行比较 `(update_time, consume_id) > (:last_update_time, :last_consume_id)` 可表达相同字典序，但执行计划仍待核验。

### 3.2 每轮与每页

每轮开始只执行一次：读取保存的游标并固定本轮 `scan_end_time`；首轮按初始化规则，后续轮次从保存的游标时间回看 10 分钟，确定本轮扫描起点。每页循环处理，成功后更新 `last_update_time`、`last_consume_id`；失败停止本轮并记录异常，不撤销此前成功页；空页结束本轮，保留最后游标并更新状态。进度记录字段包括物理表标识、复合游标、`status`、`last_error`、`updated_at`；`scan_end_time` 是否入表待确认。

## 4 待确认或待验证事项

- 排序字段是否非空，`consume_id` 是否在物理表内唯一？
- 游标更新、状态写入和失败恢复是否具备一致性？
- 重复比较和补偿是否具备幂等性？

## 5 执行记录

- 已执行 [java-interview-doc-split](C:\Users\Administrator\.codex\skills\java-interview-doc-split\SKILL.md)，从 044 拆出复合游标与轮次分页考点。
- 未执行数据库 SQL、服务联调或故障恢复测试。
