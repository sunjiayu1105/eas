# 消费记录 ES Mapping 设计

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。版本：Java 8、Spring Boot 2.7.18、Elasticsearch 服务端 7.17.15。

## 1. 面试精炼版

消费记录的 Mapping 设计，按照“字段类型、是否参与检索、是否需要排序或聚合”的顺序确定配置：

- 编号和编码字段使用 `keyword`，例如商户编号、证件号、电子账户账号、业务订单号、交易号、交易类型、交易状态、产品类型、渠道编码和错误码，用于精确匹配或集合过滤，建立倒排索引。
- `consumeId` 使用 `keyword`，不参与普通过滤，仅用于次排序和 `search_after`，因此配置 `index: false`、`doc_values: true`，建立 Doc Values。
- `tradeTime` 使用 `date`，用于时间范围查询和主排序，因此配置 `index: true`、`doc_values: true`，建立 BKD 点值索引和 Doc Values。
- `tradeAmount` 使用 `long`，金额单位为分，用于金额范围查询和展示，因此配置 `index: true`、`doc_values: false`，建立 BKD 点值索引。
- `bindingCardNo`、`transitAccountNo`、`feeAmount` 仅用于展示，关闭 `index` 和 `doc_values`；`tradeRemark` 为 `text`，关闭 `index`，不配置 `doc_values`。字段从 `_source` 返回。
- Mapping 使用 `dynamic: "strict"`。写入文档包含未声明字段时，该文档写入会失败；在 `_bulk` 请求中，通常表现为对应批次项失败。
- 查询按照 `tradeTime DESC, consumeId DESC` 排序。要保证 `search_after` 分页稳定，完整排序组合必须唯一；当前方案通过要求 `consumeId` 单值、非空且全局唯一来实现。
- 当前方案不使用 PIT，因此翻页期间数据发生变化时，可能出现重复或遗漏。

## 2. 设计目标与数据边界

ES 负责消费记录列表中的：

- 多条件过滤；
- 交易时间和金额范围查询；
- 结果排序；
- `search_after` 游标分页。

MySQL 仍然是交易权威数据源。单笔详情、资金确认和最终交易状态，应以 MySQL 数据为准。

当前草案的 Mapping 声明 17 个字段，这只是现有 JSON 的字段数量，不等于已经确认新表只有 17 个字段。`merchantName`、`customerName`、`mobile` 由 MySQL 主数据补充；`dataVersion` 未在当前 Mapping 中声明。

**完整字段清单待确认**

原方案还提及 `serialNo`、`version`、创建和更新时间，接口包含支付方式及客户定位需求。需核对新表 DDL、自定义事件转换输出和比较清单（原 Adapter 字段别名仅属历史方案），再判断是补充 Mapping 还是调整字段契约。当前保留 JSON 原样，不把尚未确认的字段删减或扩充为事实。

## 3. Mapping 配置的设计方法

每个字段依次从三个方面确定配置：

1. 根据业务含义和数据形态确定 `type`；
2. 根据是否参与过滤确定 `index`；
3. 根据是否参与排序、聚合或脚本读取确定 `doc_values`。

主要规则如下：

- `keyword` 适合编号、编码和需要完整匹配的字符串；
- `date` 适合日期范围查询和时间排序；
- `long` 适合以最小货币单位存储的整数金额；如果 MySQL 金额为元，写入前应按已确认精度准确转换为分，不能直接截断小数；
- `index: true` 建立搜索索引；
- `index: false` 不建立普通搜索索引；
- `doc_values` 主要用于排序、聚合和脚本读取；
- `_source` 未关闭时，即使字段未建立索引，也可以返回字段原值；
- `date` 和数值字段的索引底层使用 Lucene BKD 点值结构，适合范围查询。

## 4. 字段分类与配置

| 字段 | 类型 | `index` | `doc_values` | 用途 |
| --- | --- | --- | --- | --- |
| `consumeId` | `keyword` | `false` | `true` | 次排序、`search_after` |
| `tradeTime` | `date` | `true` | `true` | 时间过滤、主排序 |
| `tradeAmount` | `long` | `true` | `false` | 金额范围过滤、展示 |
| `merchantNo` | `keyword` | `true` | `false` | 商户精确过滤 |
| `idCardNo` | `keyword` | `true` | `false` | 证件号精确过滤 |
| `electronicAccountNo` | `keyword` | `true` | `false` | 电子账户精确过滤 |
| `businessOrderNo` | `keyword` | `true` | `false` | 业务订单精确过滤 |
| `tradeNo` | `keyword` | `true` | `false` | 交易号精确过滤 |
| `tradeType` | `keyword` | `true` | `false` | 交易类型集合过滤 |
| `tradeStatus` | `keyword` | `true` | `false` | 交易状态集合过滤 |
| `productType` | `keyword` | `true` | `false` | 产品类型集合过滤 |
| `channelCode` | `keyword` | `true` | `false` | 渠道编码过滤 |
| `errorCode` | `keyword` | `true` | `false` | 错误码精确过滤 |
| `bindingCardNo` | `keyword` | `false` | `false` | 展示 |
| `transitAccountNo` | `keyword` | `false` | `false` | 展示 |
| `feeAmount` | `long` | `false` | `false` | 展示 |
| `tradeRemark` | `text` | `false` | 不配置 | 展示 |

`keyword` 字段保存完整字符串，不进行分词，适合 `term` 和 `terms` 查询。电子账户账号、银行卡号等即使只包含数字，也应按照字符串处理，以保留原始格式。

## 5. 时间字段处理链路

MySQL `tradeTime` 的字段类型仍需 DDL 确认；以下是原材料中的日期文本示例，不代表 `DATETIME` 与 `TIMESTAMP` 的存储和时区语义相同：

```text
yyyy-MM-dd HH:mm:ss
```

Mapping 声明为：

```text
strict_date_optional_time||epoch_millis
```

该格式支持 Elasticsearch 能识别的严格日期或日期时间格式，以及毫秒时间戳。常见的 ISO 8601 示例为：

```text
2026-09-06T14:00:00+08:00
```

需要注意：

- 当前 Mapping 不能保证直接接收 MySQL 原始日期字符串；
- Canal Adapter 是否已将原始值转换为 ISO 8601 字符串或毫秒时间戳，仍需通过实际配置和发往 ES 的 JSON 核验；
- Canal 和查询服务对无时区时间的解释必须使用同一业务时区；
- 如果写入值已经包含正确的时区偏移，不应再次手动增加或减少 8 小时；
- 查询接口接收 `yyyy-MM-dd HH:mm:ss` 时，应先按统一业务时区转换，再构造 ES 时间范围条件。

## 6. 排序与 `search_after` 分页

查询统一使用：

```text
tradeTime DESC, consumeId DESC
```

其中：

- `tradeTime` 是主排序字段；
- `consumeId` 是次排序字段，用于区分交易时间相同的记录；
- `consumeId` 配置 `doc_values: true`，以支持排序取值；
- `search_after` 要求完整排序组合具有稳定的唯一顺序；
- 当前方案要求 `consumeId` 单值、非空且全局唯一，以保证该排序组合唯一。

使用 `search_after` 时：

1. 每页使用相同的查询条件；
2. 每页使用相同的排序字段和排序方向；
3. 下一页传入上一页最后一条记录返回的完整 `sort` 数组；
4. 不自行调整 `sort` 数组中的顺序或格式；
5. `search_after` 用于连续翻页，不适合直接跳转到任意页。

`consumeId` 是 `keyword`，排序遵循字符串规则，不能直接当作数值大小或生成时间顺序。它在这里用于打破同一交易时间下的并列；多索引查询还需避免相同业务记录跨索引重复。

当前方案不使用 PIT，因此不保证跨页期间的固定数据视图。如果翻页期间发生新增、更新或删除，后续页面可能出现重复或遗漏。

## 7. 完整 ES Mapping 配置

```json
{
  "mappings": {
    "dynamic": "strict",
    "properties": {
      "consumeId": {
        "type": "keyword",
        "index": false,
        "doc_values": true
      },
      "tradeTime": {
        "type": "date",
        "format": "strict_date_optional_time||epoch_millis",
        "index": true,
        "doc_values": true
      },
      "tradeAmount": {
        "type": "long",
        "index": true,
        "doc_values": false
      },
      "merchantNo": {
        "type": "keyword",
        "index": true,
        "doc_values": false
      },
      "idCardNo": {
        "type": "keyword",
        "index": true,
        "doc_values": false
      },
      "electronicAccountNo": {
        "type": "keyword",
        "index": true,
        "doc_values": false
      },
      "businessOrderNo": {
        "type": "keyword",
        "index": true,
        "doc_values": false
      },
      "tradeNo": {
        "type": "keyword",
        "index": true,
        "doc_values": false
      },
      "tradeType": {
        "type": "keyword",
        "index": true,
        "doc_values": false
      },
      "tradeStatus": {
        "type": "keyword",
        "index": true,
        "doc_values": false
      },
      "productType": {
        "type": "keyword",
        "index": true,
        "doc_values": false
      },
      "channelCode": {
        "type": "keyword",
        "index": true,
        "doc_values": false
      },
      "errorCode": {
        "type": "keyword",
        "index": true,
        "doc_values": false
      },
      "bindingCardNo": {
        "type": "keyword",
        "index": false,
        "doc_values": false
      },
      "transitAccountNo": {
        "type": "keyword",
        "index": false,
        "doc_values": false
      },
      "feeAmount": {
        "type": "long",
        "index": false,
        "doc_values": false
      },
      "tradeRemark": {
        "type": "text",
        "index": false
      }
    }
  }
}
```

## 8. 数据安全边界

`idCardNo`、`electronicAccountNo`、`bindingCardNo` 和 `transitAccountNo` 应按照项目安全要求进行敏感数据管理。当前 Mapping 会将部分字段建立索引，并从 `_source` 返回原值。

Mapping 本身不能自动解决以下问题：

- 敏感字段是否脱敏或加密；
- 哪些角色可以查询和返回原值；
- 是否需要访问审计；
- 是否需要限制日志、缓存和接口中的敏感信息暴露。

这些内容应结合项目安全要求单独确定。

## 9. 配置边界与核验事项

该 Mapping 包含 17 个字段：

- 12 个字段设置 `index: true`；
- `consumeId` 设置 `index: false`、`doc_values: true`；
- `tradeTime` 设置 `index: true`、`doc_values: true`；
- `tradeAmount` 设置 `index: true`、`doc_values: false`；
- 4 个展示字段设置 `index: false`；
- `tradeRemark` 使用 `text`，不配置 `doc_values`；
- 显式配置 `doc_values: true` 的字段共 2 个，即 `consumeId` 和 `tradeTime`；
- `dynamic: "strict"` 禁止未声明字段自动加入 Mapping。

**上线前核验**

上线前应继续核验：

- Canal 实际发送的字段集合；
- `tradeTime` 的实际 JSON 格式、时区偏移和解析结果；
- `consumeId` 的单值、非空和全局唯一性；
- 查询代码中的排序数组和 `search_after` 数组；
- 线上索引实际 Mapping 是否与本文一致；
- 敏感字段的脱敏、访问控制和审计策略。

**静态依据与运行限制**

`doc_values` 职责参考 [ES 7.17.15 官方文档源码](https://github.com/elastic/elasticsearch/blob/v7.17.15/docs/reference/mapping/params/doc-values.asciidoc)。`index: false` 的 `consumeId` 不应承担普通 `term` 过滤，相关索引检查见 [7.17.15 TermBasedFieldType](https://github.com/elastic/elasticsearch/blob/v7.17.15/server/src/main/java/org/elasticsearch/index/mapper/TermBasedFieldType.java)；按文档 ID 定位应使用 `_id` 对应的 API，并处理具体索引范围。

核验结论仅限于文档层面的 Mapping JSON、字段数量、配置统计、排序规则、分页条件和 `dynamic: "strict"` 语义检查。Canal 实际配置、查询代码和线上索引仍需在运行环境中进一步验证。



## 问题列表

- 文档中的待确认、示例或未验证内容需继续与当前项目技术栈和主链路核对；不能把参考材料、演示参数或静态推导写成已部署或已验收事实。
