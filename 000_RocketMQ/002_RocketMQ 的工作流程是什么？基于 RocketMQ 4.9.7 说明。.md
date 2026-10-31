# RocketMQ 的工作流程是怎样的？

以下以 **RocketMQ 4.9.x** 的普通消息工作流程为例。

普通消息的主要流程是：

**Broker 注册路由 → Producer 发送消息 → Broker 保存消息 → Consumer 获取消息 → 处理消息 → 维护消费进度**

## 1. Broker 注册路由

Broker 启动后，会向 NameServer 注册，并定期上报：

- Broker 的名称和访问地址；
- Broker 上包含的 Topic，以及每个 Topic 的读队列数、写队列数和读写权限等信息。

NameServer 根据这些信息维护 Topic 与 Broker 之间的路由关系，从而知道每个 Topic 分布在哪些 Broker 上，以及对应的队列配置。

客户端查询路由后，可以根据这些信息得到具体的 MessageQueue。一个 MessageQueue 由 `Topic + BrokerName + QueueId` 标识。

Producer 和 Consumer 都会从 NameServer 获取并缓存 Topic 路由信息。Producer 用于选择发送队列，Consumer 用于确定消费队列。

NameServer 只维护路由，不保存消息内容。

## 2. Producer 发送消息

Producer 发送消息前，先从本地缓存获取 Topic 的路由信息。

如果本地没有可用的路由信息，就向 NameServer 查询并缓存，之后还会定期刷新。

获得路由后，Producer 确定一个目标 MessageQueue，并将消息发送给该队列对应的 Broker。

## 3. Broker 保存消息

Broker 收到消息后，先将完整消息追加到 CommitLog，再由后台线程异步构建 ConsumeQueue 索引。

ConsumeQueue 按照 `Topic + QueueId` 组织，保存消息在 CommitLog 中的位置、大小等索引信息。

可以简单理解为：

- **CommitLog**：保存完整消息；
- **ConsumeQueue**：保存消息索引，用于快速定位消息。

## 4. Consumer 获取消息

Consumer 获取并缓存 Topic 路由信息后，根据消费模式确定当前实例需要消费的 MessageQueue。

Consumer 根据 MessageQueue 中的 BrokerName，从本地缓存的路由信息中找到对应的 Broker 地址，然后向目标 Broker 获取消息。

获取消息时，Consumer 会向 Broker 提供：

- Topic；
- QueueId；
- 开始获取消息的 queue offset。

Broker 根据这些信息查询 ConsumeQueue，再通过其中的索引从 CommitLog 读取完整消息，并返回给 Consumer。

## 5. Consumer 处理消息

Consumer 获取消息后，由业务代码处理消息，例如更新订单状态、保存数据或者发送通知。

## 6. 维护消费进度

Consumer 完成消息处理后，会根据处理结果维护消费进度，记录后续应继续消费的位置。

消息已被 Consumer 获取，不代表业务已经处理成功，因此不能仅根据消息获取位置判断消费是否成功。

由于网络异常、客户端故障或者消费进度未及时保存，消息可能被重复消费，因此业务处理需要保证幂等，避免同一条消息被重复处理时产生错误结果。