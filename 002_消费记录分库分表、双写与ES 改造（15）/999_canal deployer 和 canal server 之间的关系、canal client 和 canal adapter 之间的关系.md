
- **Canal Deployer 与 Canal Server**：Canal Deployer 是 Canal Server 的部署启动包，启动后运行 Canal Server 服务端。Canal Server 负责连接 MySQL、读取和解析 Binlog，并向客户端提供消息。
    
- **Canal Client 与 Canal Adapter**：Canal Client 是“消费 Canal Server 消息的客户端”这一类组件的统称；Canal Adapter 是其中一个具体实现，负责消费消息并转换后写入 ES、Kafka 等下游系统。
    

可以记成：

```
canal.deployer-1.1.7
        └── 启动 Canal Server

Canal Client
        └── Canal Adapter
                └── 写入 Elasticsearch
```

一句话总结：

> **Deployer 是 Server 的部署载体；Adapter 是 Client 的具体实现。**
