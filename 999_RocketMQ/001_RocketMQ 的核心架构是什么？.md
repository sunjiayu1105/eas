RocketMQ 核心架构包括 Topic、Queue、NameServer、Producer、Broker、Consumer 和 Consumer Group。

- **Topic**：消息的业务分类。
- **Queue（MessageQueue）**：Topic 下的逻辑分片。一个 Topic 可以包含多个 Queue，以支持消息的并行处理、负载均衡和局部有序。
- **NameServer**：维护 Topic 的路由信息，包括 Topic 分布在哪些 Broker 上，以及各 Broker 上有哪些 MessageQueue。
- **Producer**：负责发送消息。
- **Broker**：负责接收、存储和投递消息。
- **Consumer**：负责消费消息。
- **Consumer Group**：一组订阅相同 Topic、执行相同消费逻辑的 Consumer。