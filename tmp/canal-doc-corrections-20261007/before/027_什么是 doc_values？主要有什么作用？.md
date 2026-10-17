TARGET DECK: E账通::002_消费记录分库分表、双写与ES改造

Q: 027_什么是 doc_values？主要有什么作用？
A:

# 4.6 什么是 `doc_values`？主要有什么作用？

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。版本：Elasticsearch 服务端 7.17.15。

**按文档读取字段值**

`doc_values` 是 Elasticsearch 在 Lucene Segment（索引段，是 Lucene 保存索引数据的基本单元）中为字段建立的列式磁盘数据结构。它按字段组织文档值，并依据 Segment 内部文档编号读取，主要用于排序、聚合和脚本读取字段值。

| 数据结构 | 主要作用 |
|---|---|
| 倒排索引 | 根据字段值定位匹配文档，常用于 `keyword` 精确检索和 `text` 全文检索 |
| BKD 点值索引 | 通常用于 `date`、数值字段的范围查询 |
| `doc_values` | 根据文档编号读取字段值，主要用于排序、聚合和脚本 |
| `_source` | 保存并返回原始 JSON 文档 |

**一次查询中，各结构分工不同**

例如，按交易时间范围查询状态为 `90` 的消费记录，并按交易时间排序：

1. `tradeStatus` 是启用 `index` 的 `keyword` 字段，精确条件通常通过倒排索引筛选。
2. `tradeTime` 是启用 `index` 的 `date` 字段，范围条件通常通过 BKD 点值索引过滤。
3. 排序时，根据匹配文档的内部编号，从 `tradeTime` 的 `doc_values` 中读取排序值。
4. 命中文档进入 fetch 阶段后，在请求返回 `_source` 且 `_source` 已启用时读取原始文档内容。

**版本边界：不要把列存储当成通用检索索引**

对于 `term`、全文检索、日期或数值范围等常规查询，仍主要依赖倒排索引或 BKD 点值索引。`doc_values` 主要服务于排序、聚合和脚本读取；个别存在性查询或脚本过滤场景也可能读取它，不能把它当作通用的检索索引。

静态依据为 [ES 7.17.15 doc_values 文档](https://github.com/elastic/elasticsearch/blob/v7.17.15/docs/reference/mapping/params/doc-values.asciidoc) 及该版本字段查询实现。项目中 `consumeId` 关闭 `index`，用于排序而不承担普通 `term` 过滤；按记录定位时还需使用 `_id` 对应 API 并确定具体索引。未执行线上查询或 Profile（查询执行分析）。
本问题是[012_ES Mapping 设计.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/012_ES Mapping 设计.md:5>)中排序、聚合和 `consumeId` 字段配置的基础延伸，也用于[019_消费记录列表接口处理逻辑.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/019_消费记录列表接口处理逻辑.md:6>)的查询解释。
<!--ID: 1790086385713-->
