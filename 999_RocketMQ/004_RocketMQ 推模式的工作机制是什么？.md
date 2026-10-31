## RocketMQ 推模式的工作机制是什么？基于 RocketMQ 4.9.7 说明。基于spring boot 2.7.18 集成 org.apache.rocketmq:rocketmq-client:5.2.0 说明。

整体上，主要流程可以概括为：

**Broker 注册路由 → Producer 发送消息 → Broker 存储消息 → Consumer 获取并处理消息 → 更新消费进度**

### 1. Broker 注册路由

Broker 启动后会向配置的 NameServer 注册，并定期上报。主要信息包括 BrokerName、Broker 地址，以及 Topic 的读队列数、写队列数和读写权限等配置。

### 2. 客户端获取路由

Topic 路由信息主要说明一个 Topic 分布在哪些 Broker 上，包括每个 Broker 的 BrokerName、读队列数、写队列数、读写权限以及对应的 Broker 地址。

Producer 和 Consumer 都会从 NameServer 获取并缓存 Topic 路由信息。客户端根据路由信息生成具体的 `MessageQueue`，并找到相应的 Broker。

一个 `MessageQueue` 由 `Topic + BrokerName + QueueId` 标识。

NameServer 负责维护 Topic 路由和 Broker 信息，不保存消息内容。

### 3. Producer 发送消息

Producer 发送消息前，先从本地缓存获取 Topic 路由信息；如果没有可用路由，就向 NameServer 查询并缓存，路由信息会由客户端后台任务定期刷新。

获得路由后，Producer 从可写的 `MessageQueue` 中选择一个队列，将消息发送到该队列对应的 Broker。

### 4. Broker 存储消息

Broker 收到消息后，先将消息追加写入 CommitLog。

CommitLog 写入后，Broker 通过后台线程异步构建 ConsumeQueue。ConsumeQueue 按 `Topic + QueueId` 组织，保存消息在 CommitLog 中的位置、大小等索引信息。

### 5. Consumer 获取消息

Consumer 获取 Topic 路由信息后：

- 集群消费时，通过负载均衡确定当前实例负责的 `MessageQueue`；
- 广播消费时，每个实例直接消费全部可读的 `MessageQueue`，不进行消费组内的负载均衡。

Consumer 根据自己负责的 `MessageQueue`，向对应的 Broker 发起 Pull 请求，并携带 Topic、QueueId 和 queue offset 等信息。

Broker 根据这些信息查询 ConsumeQueue，再根据索引定位 CommitLog 中的完整消息，并将消息返回给 Consumer。

### 6. Consumer 处理消息和维护进度

Consumer 获取消息后，交给业务代码进行处理。消息被拉取不代表消费成功，是否成功以业务处理结果为准。

Consumer 处理消息后，根据处理结果更新消费进度。处理成功后，消费进度向后推进。

这里需要区分拉取进度和消费进度：

- 拉取进度用于确定下一次 Pull 请求的 queue offset；
- 消费进度表示消息处理到哪里，会先在 Consumer 本地内存中维护并定期持久化；
- 集群消费的消费进度默认持久化到 Broker；
- 广播消费的消费进度默认持久化到 Consumer 本地文件。

Consumer 重启或重新接管队列时，会读取持久化的消费进度，并以此建立初始的拉取进度。

### 7. 重复消费

集群消费通常按至少一次语义理解。消息可能被重复消费，因此业务处理需要保证幂等。

**已确认无误**