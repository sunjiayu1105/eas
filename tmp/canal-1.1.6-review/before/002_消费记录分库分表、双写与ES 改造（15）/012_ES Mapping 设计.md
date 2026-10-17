TARGET DECK: E账通::002_消费记录分库分表、双写与ES改造

Q: 012_ES Mapping 设计
A:
# 3.4.3 ES Mapping 设计

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 面试口述精炼版本

Mapping 按字段是否参与检索、是否需要排序或聚合来选类型。编号和编码字段使用 `keyword`，用于精确匹配或集合过滤；`consumeId` 使用 `keyword`，不参与普通过滤，只用于次排序和 `search_after`，因此配置 `index: false`、`doc_values: true`；`tradeTime` 使用 `date`，用于时间范围查询和主排序；`tradeAmount` 使用 `long`，金额单位为分，用于金额范围查询和展示；仅展示的卡号、账户、手续费和备注字段关闭索引。

Mapping 使用 `dynamic: "strict"`，写入未声明字段时对应文档写入失败，`_bulk` 请求通常表现为对应批次项失败。查询按 `tradeTime DESC, consumeId DESC` 排序。当前方案不使用 PIT（Point in Time，时间点一致性视图），因此翻页期间数据变化可能导致重复或遗漏；要让 `search_after` 稳定，`consumeId` 必须单值、非空且全局唯一。

**字段契约待确认**

现有详细 Mapping 声明 17 个字段，但完整新表 DDL、`serialNo` 与业务订单号或交易号的关系、版本及其他时间字段均未对齐。保留原字段清单，不擅自增删；校验与查询接入前先完成字段对应核对。完整 JSON 见[999_消费记录 ES Mapping 设计.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/999_消费记录 ES Mapping 设计.md:1>)。

**字段类型与索引策略**

| 字段 | 类型与配置 | 用途 |
|---|---|---|
| 商户编号、证件号、电子账户账号、业务订单号、交易号、交易类型、交易状态、产品类型、渠道编码、错误码 | `keyword` | 精确匹配和集合过滤 |
| `consumeId` | `keyword`，`index: false`，`doc_values: true` | 次排序和 `search_after` |
| `tradeTime` | `date`，`index: true`，`doc_values: true` | 时间范围查询和主排序 |
| `tradeAmount` | `long`，金额单位为分，`index: true`，`doc_values: false` | 金额范围查询和展示 |
| `bindingCardNo`、`transitAccountNo`、`feeAmount`、`tradeRemark` | `index: false`；前 3 项同时关闭 `doc_values`，`text` 备注不配置 `doc_values` | 仅从 `_source` 返回展示 |

**查询与分页约束**

查询排序固定为 `tradeTime DESC, consumeId DESC`。排序字段组合必须能稳定区分记录；因此 `consumeId` 的单值、非空和全局唯一是当前分页方案的前置条件。多索引查询还需保证同一业务记录不会因 rollover 重试而重复存入不同索引。`search_after` 依赖上一页最后一条记录的完整排序值，不使用 PIT 时，只能保证单次查询过程中的排序规则一致，不能保证跨页期间数据不变。

**Mapping 验证边界**

`consumeId` 必须作为普通文档字段实际写入，不能仅设置元数据 `_id` 就认为已有次排序值。008 的建议模板通过 `consume_id AS esDocId` 提取 `_id`，另用 `consume_id AS consumeId` 保留排序字段；不新增 ES Mapping 字段 esDocId，不改变既定 keyword、doc_values 或排序规则。依据：[Canal 1.1.7 ES7xTemplate.java](https://github.com/alibaba/canal/blob/canal-1.1.7/client-adapter/es7x/src/main/java/com/alibaba/otter/canal/client/adapter/es7x/support/ES7xTemplate.java)的字段转换方法排除配置为 `_id` 的别名对应的普通字段。双别名解析和写入尚待测试；验收需检查 `_source.consumeId` 非空、与 `_id` 表达同一业务标识，并验证相同 tradeTime 下的次排序及 search_after 结果。仅在 Mapping 中声明 consumeId 不足以证明数据中已有该字段。

字段清单、金额精度、索引模板、ILM 策略及 Adapter 映射文件仍需结合项目实际配置核对。本文未连接 Elasticsearch 集群执行写入、查询、`_bulk` 失败和 `search_after` 压测，不能将 Mapping 设计表述为已经完成生产验证。

字段类型和查询结构的基础解释见[025_ES 是什么？.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/025_ES 是什么？.md:6>)、[026_ES 中的倒排索引是什么？.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/026_ES 中的倒排索引是什么？.md:6>)和[027_什么是 doc_values？主要有什么作用？.md](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/027_什么是 doc_values？主要有什么作用？.md:6>)。
<!--ID: 1790086386035-->
