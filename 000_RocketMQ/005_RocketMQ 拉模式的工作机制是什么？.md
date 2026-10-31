基于spring boot 集成 rocketmq-spring-boot-starter  2.2.3 说明。
RocketMQ 的拉模式是：消费者按照自己的节奏，主动向 Broker 请求指定 Topic/队列、指定消费位点的一批消息；拿到消息后由业务线程处理，并根据处理结果推进和持久化消费位点。

已确认无误