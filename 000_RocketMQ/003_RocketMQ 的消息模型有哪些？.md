基于spring boot 2.7.18 集成 org.apache.rocketmq:rocketmq-client:5.2.0 说明。
# RocketMQ 的消息模型有哪些？

这里的“消息模型”是指消息如何分配给 Consumer，主要有集群消费和广播消费两种。

## 集群消费（默认）

同一个 `ConsumerGroup` 中的 Consumer 会分摊 Topic 的 `MessageQueue`，因此一条消息通常由组内一个 Consumer 处理。

Consumer 增减时会重新分配队列。如果 Consumer 数量超过队列数量，部分 Consumer 可能空闲。

不同 `ConsumerGroup` 相互独立，各自消费所订阅 Topic 中的消息。

适用于每条消息只需要一个 Consumer 处理的场景，例如发短信、发优惠券和订单处理。

RocketMQ 通常采用至少一次投递语义，失败重试或队列重新分配可能造成重复消费，因此业务需要保证幂等。

## 广播消费

同一个 `ConsumerGroup` 中的每个 Consumer 都会独立消费订阅到的消息，不会分摊 `MessageQueue`。

增加 Consumer 不会分担消费任务，而是让更多 Consumer 分别执行一次。

适用于每个 Consumer 都需要处理消息的场景，例如配置刷新、缓存刷新和数据预热。

广播模式下，各 Consumer 独立维护消费进度，并且不支持集群模式的 Broker 端失败重试，业务需要自行实现重试或补偿。

## 核心配置

以下示例基于：

```xml
<dependency>
    <groupid>org.apache.rocketmq</groupid>
    <artifactid>rocketmq-spring-boot-starter</artifactid>
    <version>2.3.0</version>
</dependency>
```

该版本对应 Spring Boot `2.7.18` 和 RocketMQ Client `5.2.0`。

```yaml
rocketmq:
  name-server: 127.0.0.1:9876
```

### 集群消费

```java
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "order-topic",
        consumerGroup = "order-consumer-group",
        messageModel = MessageModel.CLUSTERING
)
public class OrderClusterListener implements RocketMQListener<string> {

    @Override
    public void onMessage(String message) {
        // 业务处理
    }
}
```

`CLUSTERING` 是默认值，因此也可以省略：

```java
@RocketMQMessageListener(
        topic = "order-topic",
        consumerGroup = "order-consumer-group"
)
```

### 广播消费

```java
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "order-topic",
        consumerGroup = "order-consumer-group",
        messageModel = MessageModel.BROADCASTING
)
public class OrderBroadcastListener implements RocketMQListener<string> {

    @Override
    public void onMessage(String message) {
        // 每个 Consumer 都会执行
    }
}
```

## 核心差异

```java
MessageModel.CLUSTERING   // 同组 Consumer 分摊 MessageQueue
MessageModel.BROADCASTING // 同组 Consumer 各自独立消费
```

Starter 使用的枚举是：

```java
org.apache.rocketmq.spring.annotation.MessageModel
```

同一个 `ConsumerGroup` 下的 Consumer 应保持相同的 Topic、订阅规则和消息模型。

文中的 Consumer 指运行中的消费者程序实例，不一定对应一台物理机器。

已确认无误</string></string>