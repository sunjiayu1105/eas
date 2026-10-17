# 008_Canal 同步 MySQL binlog 到 ES（自定义 ES 消费服务处理逻辑）

以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准：Java 8、Spring Boot 2.7.18、RocketMQ 5.2.0、Canal Server 1.1.7、MySQL 8.0.33 和 Elasticsearch 7.17.15。

## 文档维护与已确认规则

- 当前主链路为 MySQL → Canal Server → RocketMQ → 自定义 ES 消费服务 → Elasticsearch。
- 本文替代原 Canal Adapter 直写 ES 的处理逻辑，说明自定义服务如何消费 RocketMQ、定位索引并处理 ES 增删改。
- ES 每 30 天 rollover；索引在 rollover 后 180 天进入 cold 阶段。具体 ILM、节点标签、迁移和删除参数仍需按 ES 7.17.15 集群核验。
- 写入别名只指向当前可写物理索引。INSERT 可以使用写入别名；UPDATE、DELETE 必须先定位文档所在的具体物理索引，再执行操作。
- 建议使用稳定业务主键作为 ES 文档 _id，并维护 consumeId → 物理索引的定位记录。定位记录是设计建议，尚未在项目中实现或验证。
- RocketMQ 至少一次投递；ES 成功后消费确认失败时可能重复处理。服务必须保证同一事件重复执行不会产生错误结果。
- 不能把本文中的 Java 代码、RocketMQ 参数、索引名称和重试次数写成已经上线的配置。具体连接、认证、消息契约和故障恢复仍需联调。

## 参考文章的吸收与版本边界

参考文档给出了 RocketMQ 消费者、ES REST 客户端、Redis 锁、稳定文档 ID 和 ILM rollover 的示例。008 吸收其中的职责拆分和幂等思路，但将示例代码视为设计参考：参考文档使用 Elasticsearch 8 客户端、Canal 1.1.6、RocketMQ 4.9.4，并以单机演示阈值观察索引滚动；当前项目按 `AGENTS.md` 使用 Elasticsearch 7.17.15、Canal Server/Adapter 1.1.7、RocketMQ 5.2.0，实际客户端依赖和 API 仍需解析验证。

参考文档的 `data_hot`、`data_warm`、`data_cold` 节点标签以及按年龄、文档数和阶段迁移的 ILM 思路可作为当前冷热分层设计的参考；其中 `max_docs=5`、20 秒阶段时间和本地路径只用于演示，不能作为项目生产参数。当前项目的 30 天 rollover、180 天进入 cold 等规则仍需按目标集群配置核验。ES 7.17.15 的 ILM、节点角色、许可能力、REST 认证及历史索引更新行为也需单独验证。

## 面试口述精炼版本

### 整体职责

自定义 ES 消费服务位于 RocketMQ 和 Elasticsearch 之间，负责把 Canal 产生的行变更转换为 ES 操作。它解决两个问题：第一，使用 MQ 解耦 Canal 和 ES；第二，在索引 rollover 后仍能准确找到历史文档所在的物理索引。

服务收到 INSERT 时写当前写入别名；收到 UPDATE 或 DELETE 时，先根据业务主键读取索引定位信息，再访问具体历史索引。所有 Bulk 响应都要逐项判断，失败事件进入重试，超过阈值进入死信并告警。

### 为什么不使用 Canal Adapter

Canal Adapter 适合直接把增量事件写到固定索引或当前写入别名。但当前索引每 30 天 rollover，30 天以后仍然会修改或删除历史数据。写入别名只知道当前写索引，不能自动定位旧文档所在的历史物理索引，直接 UPDATE 可能更新不到，直接写当前索引还可能产生重复文档。

自定义服务可以把 RocketMQ 的重试、死信、顺序键、幂等和索引定位组合在一起，并对 404、定位缺失和 Bulk 单项失败制定统一处理规则。因此当前场景不把 Adapter 作为主写入组件。

如果确认历史数据不会再更新或删除，且只需要向当前索引写入，那么 Adapter 的链路更简单，可以重新评估。

### MQ 消费确认

RocketMQ 消费确认只能在 ES 操作按既定规则成功后执行。ES 写入失败时返回重试；重试耗尽写入死信并暂停或告警，具体策略按业务确认。

RocketMQ 消费确认与 Canal ack 是两个层次：Canal ack 只在消息成功进入 RocketMQ 后执行，RocketMQ ack 则在 ES 处理成功后执行。两层都可能重复，因此必须使用稳定 _id 和幂等逻辑。

### rollover 与冷热分离

ILM 负责创建新物理索引、滚动写入别名以及把旧索引从 hot 移到 warm/cold。rollover 不会把旧文档迁移到新索引，也不会改变文档所在物理索引。索引定位信息必须跟随 INSERT、重建索引和迁移流程更新。

## 详细方案

### 一、完整数据流

~~~mermaid
flowchart LR
    S[Canal Server] --> P[Canal-to-MQ 投递组件]
    P --> Q[(RocketMQ)]
    Q --> C[自定义 ES 消费服务]
    C --> D{事件类型}
    D -->|INSERT| W[写入当前写别名]
    D -->|UPDATE/DELETE| L[查询索引定位]
    L --> I[访问具体物理索引]
    W --> E[(Elasticsearch)]
    I --> E
    E --> R{Bulk 逐项成功?}
    R -->|是| A[确认 RocketMQ 消息]
    R -->|否| T[重试 / 死信 / 告警]
~~~

Canal-to-MQ 投递组件和 ES 消费服务可以部署在同一个业务工程中，也可以拆成两个独立服务；部署数量、线程数和容灾方式需要按实际容量与运行环境确定。

### 二、RocketMQ 事件契约

建议每条行变更包含以下字段：

~~~json
{
  "eventId": "db0-12345-0",
  "destination": "db0",
  "database": "eas",
  "table": "consume_record_01",
  "eventType": "UPDATE",
  "consumeId": "business-id",
  "before": {},
  "after": {},
  "binlogPosition": "mysql-bin.000123:456789",
  "transactionId": "uuid:789",
  "eventTime": "2026-01-01T00:00:00Z"
}
~~~

eventId 用于追踪和去重，consumeId 用于业务主键和 RocketMQ 消息键。before、after 的实际字段取决于 MySQL ROW/FULL 事件和 Canal 消息模型，需用真实事件验证。

建议按 destination 分 Topic 或 Tag，并以 consumeId 作为消息键，尽量保证同一业务主键的事件顺序。不能依赖 MQ 保证 db0 和 db1 之间的全局顺序。

参考文档中的“每个 MessageQueue 绑定一个消费者线程”只说明一种顺序消费思路。当前服务应以实际 RocketMQ 5.2.0 消费模型、业务主键分区策略和 ES 写入耗时评估并发度；不能把固定线程绑定、固定分区数或示例 topic 当成已确认配置。

同一 `consumeId` 的事件应在消费端保持有序；如果发生重试、主备切换或跨队列重放，服务还应依据事件携带的 binlog 位点、事务序号或项目确认的版本字段拒绝旧事件覆盖新事件。当前文档只确认需要保留这些顺序信息，具体比较字段和并发实现仍待联调。

### 三、索引和别名设计

建议维护以下逻辑名称：

| 名称 | 作用 |
| --- | --- |
| consume_write | 当前 rollover 写入别名，只允许写入当前物理索引 |
| consume_read | 查询别名，覆盖需要检索的热、温、冷物理索引 |
| consume-000001 等 | 由 ILM/rollover 管理的具体物理索引 |
| consumeId → index | 建议的索引定位记录，用于历史更新和删除 |

定位记录可以由自定义服务在首次 INSERT 成功后写入，并在重建索引、迁移或人工修复时同步更新。定位记录放在何处、是否需要单独表或缓存，属于待确认的实现选择。

### 四、事件处理逻辑

#### 1. INSERT

1. 使用 consumeId 作为 ES 文档 _id。
2. 向 consume_write 写入别名发送 Index 或 Upsert 请求。
3. 从 ES 成功响应中取得实际 `_index`，再记录 consumeId 对应的具体物理索引；不能只保存写入别名。
4. 若写入失败，保持 RocketMQ 消息未确认，按重试策略再次处理。

如果 INSERT 事件重放，稳定 _id 使其覆盖同一文档，而不是创建重复文档；前提是目标索引和请求类型正确。

#### 2. UPDATE

1. 从事件中读取 consumeId。
2. 先查询索引定位记录；定位记录不存在时，按受控策略通过 consume_read 查询 _id。
3. 找到具体物理索引后，向该索引发送 Update 或覆盖写请求。
4. 更新成功后确认消息，并校验定位记录仍指向该物理索引。
5. 定位不到、读别名不可用或返回多个候选时，不得直接写入当前写别名，应进入重试或人工处理。

更新请求还要携带事件顺序所需的版本或位点信息，避免延迟到达的旧 UPDATE 覆盖较新的数据。若业务只保留完整行镜像而没有可比较的版本字段，应先在消费服务中补齐单键串行处理和重放规则，不能用查询到的当前文档版本代替 binlog 事件顺序。

#### 3. DELETE

1. 根据 consumeId 查询定位记录。
2. 对记录所在的具体物理索引发送 Delete。
3. 删除成功或确认文档本来就不存在后，再按业务规则处理定位记录。
4. 具体索引未找到、权限失败或网络异常时进入重试；不能把所有 404 都无条件当成成功。

### 五、Bulk、重试和死信

自定义服务必须检查 HTTP 层和 Bulk item 层：

~~~java
BulkResponse response = esClient.bulk(bulkRequest);

if (response.hasFailures()) {
    List<EsEvent> retryable = collectRetryableItems(response);
    List<EsEvent> deadLetters = collectNonRetryableItems(response);

    if (!retryable.isEmpty()) {
        enqueueRetry(retryable);
        return; // 不确认原消息，等待可重试项完成
    }
    if (!deadLetters.isEmpty()) {
        persistDeadLetters(deadLetters);
        ackOnlyWhenDeadLetterIsDurable();
        return; // 终止项是否确认由项目死信策略决定
    }
}
ackRocketMqMessage();
~~~

上面是项目期望的处理顺序示意，不是已实现代码。建议区分网络超时、429、5xx、映射错误、索引不存在和定位缺失，并分别设置重试、告警或死信策略。可重试项不能与已落入死信的终止项混在同一次无限重试中；重试次数、退避时间、死信确认时点和死信保留期需在联调和容量评估后确定。

已成功写入的 Bulk item 不会自动回滚，重试必须依赖稳定 _id 和幂等请求。RocketMQ ack 应放在所有需要处理的 item 都成功或按明确业务规则完成后。

### 六、历史索引定位策略

推荐优先维护定位记录，因为每次 UPDATE、DELETE 都扫描所有历史索引会增加查询开销，并且在 cold 阶段可能带来更高延迟。

定位缺失时可以进行一次受控的 consume_read 查询：

- 查询命中一个具体索引：补写定位记录，再执行更新或删除；
- 查询没有命中：判断是延迟、数据丢失还是业务上本来不存在，进入重试或死信；
- 查询命中多个索引：先按业务规则处理重复文档，不能随机选择一个索引。

索引定位记录本身也需要幂等、备份和修复机制。它是历史更新能力的组成部分，不能只依赖当前写入别名。

### 七、ILM rollover 与冷热分离

索引模板和 ILM 策略应保证：

1. 当前写入别名始终只有一个可写物理索引；
2. 达到 30 天或其他确认的 rollover 条件后创建下一个物理索引；
3. 旧索引从 hot 进入 warm/cold 时，read 别名和定位记录仍可访问；
4. rollover 不删除或重写旧文档；
5. 进入 cold 的索引仍可能接收历史 UPDATE、DELETE，消费服务必须能访问其具体索引；
6. 超过保留期删除索引前，先确认业务不再需要历史更新和删除。

ES 7.17.15 的 ILM、数据层属性和 cold 节点能力必须结合实际许可、集群拓扑和发行配置验证，本文不把文章示例直接视为生产配置。

#### 7.1 ES 客户端与连接适配

参考文档中的 `co.elastic.clients.elasticsearch.ElasticsearchClient` 代码属于 Elasticsearch 8 Java API Client 示例，与当前项目的 Elasticsearch 7.17.15 版本边界不一致。当前实现应先锁定与 ES 7.17.15 匹配的 REST 客户端、序列化方式、TLS/认证和连接池参数，再实现 Bulk、Update、Delete 及别名操作；在依赖解析和联调完成前，不能直接复制参考文档的 import、请求构造或响应类型。

### 八、监控与验收

重点监控：

- RocketMQ 消费延迟、堆积、重试次数和死信数量；
- ES Bulk 成功率、item 失败分类、429/5xx、索引不存在和定位缺失；
- 当前写别名指向、rollover 次数、各阶段索引数量和节点磁盘水位；
- 定位记录命中率、修复数量和重复文档数量；
- UPDATE、DELETE 在 hot、warm、cold 索引上的处理延迟。

验收至少覆盖：

1. 当前写入别名 INSERT 和重复 INSERT；
2. rollover 前后的 UPDATE、DELETE；
3. 进入 cold 阶段后的历史 UPDATE、DELETE；
4. 定位记录缺失、多个命中和真实不存在；
5. ES 429、5xx、网络超时、Mapping 错误和索引不存在；
6. ES 写入成功但 RocketMQ ack 失败后的重复消费；
7. 死信恢复、索引定位修复和最终数据一致性。

上述配置和验收项均未在当前环境执行，不能据此宣称端到端同步已经通过。

## 本轮结论

当前场景推荐保留 Canal Server，增加 RocketMQ 和自定义 ES 消费服务，放弃 Canal Adapter 作为主写入组件。核心原因是 rollover 后仍要修改和删除历史数据，而写入别名无法自动完成历史物理索引定位；自定义服务可以把索引定位、重试、死信、幂等和冷热索引访问统一起来。

如果后续确认历史索引只读、不会再发生更新和删除，或者接受通过离线修复处理历史数据，再重新评估 Canal Adapter。
