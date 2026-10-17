# 请求体中 search_after 中的字段是如何定义的？

在 Elasticsearch 中，`search_after` 不负责定义排序字段，排序字段通过 `sort` 指定。注释 1

本项目按 `tradeTime` 降序、`consumeId` 升序排列：

```json
"sort": [
  { "tradeTime": { "order": "desc", "format": "epoch_millis" } },
  { "consumeId": { "order": "asc" } }
]
```

查询下一页时，将上一页最后一条记录返回的 `sort` 数组作为 `search_after`：

```json
"search_after": [
  1788823200000,
  "C10086"
]
```

`search_after` 中的值必须与 `sort` 中的字段顺序一致，后续请求的查询条件和排序规则也必须保持不变。`consumeId` 使用 `keyword` 类型，设置 `index: false`、`doc_values: true`，仅用于排序和 `search_after` 分页，不用于精确查询。

本方案不使用 PIT，因为不要求跨页快照一致性，可以减少 PIT 的管理和资源开销；翻页期间数据发生变化时，仍可能出现重复或漏读。注释 2

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准：Spring Boot 2.7.18、Elasticsearch 服务端 7.17.15。

