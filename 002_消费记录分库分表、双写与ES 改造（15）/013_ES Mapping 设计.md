TARGET DECK: E账通::002_消费记录分库分表、双写与ES改造

Q: 013_ES Mapping 设计
A:
# 3.4.3 ES Mapping 设计

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 已确认规则

- 本次文件维护仅按用户确认的新名称重命名文档，并同步名称标识及关联引用；不调整正文技术结论。新建的 MySQL、Canal Server、自定义消费服务文档为空白占位文件。

- **自然月索引（已确认设计，待实现验证）**：新增、更新、删除、重试及补偿均根据不可变的原始 `create_time`，按统一业务时区计算 `records-yyyy.MM`，直接操作该物理索引；正常路径不先搜索，也不维护业务 ID 到索引名的持久化映射。业务 ID 作为 `_id`，`records-read` 作为跨月读别名。按自然月分索引，不使用 rollover；历史回放期间暂停相关索引的 ILM cold 迁移，回放完成且新表与 ES 校验通过后恢复 ILM，所属月份下个月 1 日零点起算满 `366d` 后进入 cold。删除事件须携带删除前的创建时间，索引模板统一 Mapping、配置和读别名；首次 Index 写入自动建索引以配置和权限允许为前提。
- 当前 ES 写入由 009 的自定义 MQ 消费服务承担，Adapter 映射不再作为主链路字段配置。
- consumeId 同时用于 ES `_id` 和普通排序字段；设置 `_id` 不能替代写入 `_source.consumeId`。既定字段类型、金额单位和排序规则保持一致，完整字段契约仍待核对。

## 面试口述精炼版本

Mapping 按字段是否参与检索、是否需要排序或聚合来选类型。编号和编码字段使用 `keyword`，用于精确匹配或集合过滤；`consumeId` 使用 `keyword`，不参与普通过滤，只用于次排序和 `search_after`，因此配置 `index: false`、`doc_values: true`；`tradeTime` 使用 `date`，用于时间范围查询和主排序；`tradeAmount` 使用 `long`，金额单位为分，用于金额范围查询和展示；仅展示的卡号、账户、手续费和备注字段关闭索引。

Mapping 使用 `dynamic: "strict"`，写入未声明字段时对应文档写入失败，`_bulk` 请求通常表现为对应批次项失败。查询按 `tradeTime DESC, consumeId DESC` 排序。当前方案不使用 PIT（Point in Time，时间点一致性视图），因此翻页期间数据变化可能导致重复或遗漏；要让 `search_after` 稳定，`consumeId` 必须单值、非空且全局唯一。

**字段契约待确认**

现有详细 Mapping 声明 17 个字段，但完整新表 DDL、`serialNo` 与业务订单号或交易号的关系、版本及其他时间字段均未对齐。保留原字段清单，不擅自增删；校验与查询接入前先完成字段对应核对。完整 JSON 见[999_消费记录 ES Mapping 设计.md](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_消费记录 ES Mapping 设计.md:1>)。

**字段类型与索引策略**

| 字段 | 类型与配置 | 用途 |
|---|---|---|
| 商户编号、证件号、电子账户账号、业务订单号、交易号、交易类型、交易状态、产品类型、渠道编码、错误码 | `keyword` | 精确匹配和集合过滤 |
| `consumeId` | `keyword`，`index: false`，`doc_values: true` | 次排序和 `search_after` |
| `tradeTime` | `date`，`index: true`，`doc_values: true` | 时间范围查询和主排序 |
| `tradeAmount` | `long`，金额单位为分，`index: true`，`doc_values: false` | 金额范围查询和展示 |
| `bindingCardNo`、`transitAccountNo`、`feeAmount`、`tradeRemark` | `index: false`；前 3 项同时关闭 `doc_values`，`text` 备注不配置 `doc_values` | 仅从 `_source` 返回展示 |

**查询与分页约束**

查询排序固定为 `tradeTime DESC, consumeId DESC`。排序字段组合必须能稳定区分记录；因此 `consumeId` 的单值、非空和全局唯一是当前分页方案的前置条件。多索引查询还需保证同一业务记录不会因时区不一致、创建时间被改写或重试误用当前时间而重复存入不同月份索引。`search_after` 依赖上一页最后一条记录的完整排序值，不使用 PIT 时，只能保证单次查询过程中的排序规则一致，不能保证跨页期间数据不变。

**Mapping 验证边界**

`consumeId` 必须作为普通文档字段实际写入，不能仅设置元数据 `_id` 就认为已有次排序值。009 的自定义服务须显式把源主键转换为 `_id`，同时写入 `_source.consumeId`，不新增 esDocId 字段，也不改变既定 keyword、doc_values 或排序规则。原 Adapter SQL 双别名建议属于历史方案，不再要求当前链路生成 32 份映射。验收需检查 `_source.consumeId` 非空、与 `_id` 表达同一业务标识，并验证相同 tradeTime 下的次排序及 search_after 结果。仅在 Mapping 中声明 consumeId 不足以证明数据中已有该字段。

字段清单、金额精度、索引模板、ILM 策略及自定义事件转换仍需结合项目实际配置核对。原始 `create_time` 用于应用计算目标月份，不代表已经增加 ES Mapping 字段；若将其写入 `_source`，须先确认字段契约，否则 `dynamic: "strict"` 会拒绝未声明字段。consumeId 配置 index: false，不应用该字段的 term 查询定位历史索引；正常路径按原始创建时间计算月份；009 在创建时间缺失时的受控修复查询须使用 `_id` 等可检索元数据。本文未连接 Elasticsearch 集群执行写入、查询、`_bulk` 失败和 `search_after` 压测，不能将 Mapping 设计表述为已经完成生产验证。

字段类型和查询结构的基础解释见[026_ES 是什么？.md](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/026_ES 是什么？.md:6>)、[027_ES 中的倒排索引是什么？.md](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/027_ES 中的倒排索引是什么？.md:6>)和[028_什么是 doc_values？主要有什么作用？.md](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/028_什么是 doc_values？主要有什么作用？.md:6>)。
<!--ID: 1790086386035-->



## 问题列表

- 完整字段契约、金额精度、时间字段、dynamic strict和consumeId排序前置条件仍待核对。


**已确认规则**：冷热迁移计龄以所属月份下个月 1 日零点为起点，cold 的 `min_age` 为 `366d`；历史回放、校验及观察验收后才切换正式策略。配置含义、示例及验证边界见 [ES写入链路_ES_历史回放与ILM冷热迁移](<./010_ES写入链路_ES_06_历史回放与ILM冷热迁移.md>)。

