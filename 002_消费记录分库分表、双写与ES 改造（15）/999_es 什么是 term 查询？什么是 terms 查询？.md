# 什么是 term 查询？什么是 terms 查询？

## 面试精炼版本

**核验说明：** 已依据 Elasticsearch 7.17.15 的 `term`、`terms`、`match`、`keyword`、`text` 和 `_source` 规则核对；未在实际集群执行示例。

`term` 和 `terms` 查询会将查询请求中提供的值，直接与字段建立索引后的词项进行匹配，不会自动对查询值进行全文分析：

1. `term` 只提供一个查询值。例如查询 `status = "PAID"`，实际是用查询值 `"PAID"` 去匹配 `status` 字段的索引词项。
2. `terms` 提供多个查询值。例如查询 `status` 为 `"PAID"` 或 `"CLOSED"`，实际是分别用这两个查询值匹配索引词项，命中任意一个即可。

字段类型决定文档字段值如何建立索引：

- `keyword` 字段通常把整个字段值建立为一个索引词项。因此，原始值为 `"PAID"` 时，索引词项通常也是 `"PAID"`，适合使用 `term` 或 `terms` 做完整值过滤。
- `text` 字段会先用分析器处理字段值。例如原始值 `"Hello World"` 经默认 `standard` 分析器后，通常形成 `hello` 和 `world` 两个索引词项。此时，`term` 查询中的查询值不会自动经过同样的分析；只有当查询值本身是索引词项，例如 `"hello"`，才可能匹配。

因此，自然语言字段通常使用 `match`。`match` 会先分析查询文本，再用分析后的查询词项匹配索引词项。`match` 的 `operator` 默认是 `OR`，所以多个查询词默认命中任意一个即可。`term.case_insensitive` 在 Elasticsearch 7.17.15 中可用于让 `term` 查询进行大小写不敏感匹配，默认值为 `false`。

