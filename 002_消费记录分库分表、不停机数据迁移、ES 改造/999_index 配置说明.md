# `index` 配置说明

除 `consumeId` 外，其他字段均省略 `index` 配置，使用 Elasticsearch 默认值 `true`，因为这些字段需要参与查询。

例如：

```json
"merchantNo": {
  "type": "keyword"
}
```

等价于：

```json
"merchantNo": {
  "type": "keyword",
  "index": true
}
```

