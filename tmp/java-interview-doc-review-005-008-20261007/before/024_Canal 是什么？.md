TARGET DECK: E账通::002_消费记录分库分表、双写与ES改造

Q: 024_Canal 是什么？
A:

# 4.3 Canal 是什么？

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准：Java 8、Spring Boot 2.7.18、rocketmq-spring-boot-starter 2.3.0、org.apache.rocketmq:rocketmq-client 5.2.0、Canal Server 1.1.7、MySQL 8.0.33 和 Elasticsearch 7.17.15。

**职责：解析 MySQL 的数据变更**

Canal Server 模拟 MySQL 复制客户端，读取 binlog（二进制变更日志），将新增、修改和删除解析为下游可消费的事件。真正向 ES 写入的是 Canal Adapter 或自定义消费者，不是 MySQL 事务本身。

**项目链路：新表变更异步同步到 ES**

```text
新表事务提交 → MySQL 记录 binlog
→ Canal Server 解析并提供事件
→ Canal-to-MQ 投递组件通过 TCP 拉取并发送
→ RocketMQ 缓冲与解耦
→ 自定义 ES 消费服务转换并写入 ES
→ ES 刷新后可被搜索
```

**边界：接口成功不等于 ES 已可查询**

这是异步同步链路，可能出现延迟、重试和失败。双写期间应用先提交旧表，再按统一分片规则尝试提交新表；Canal 不负责旧表与新表之间的业务写入，只消费新表已提交的变更并同步到 ES。独立校验通道也消费变更，但它只收集检查对象，不承担 ES 同步。
本问题是[005_Canal 同步 MySQL binlog 到 ES（总链路）.md](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/005_Canal 同步 MySQL binlog 到 ES（总链路）.md:1>)中组件职责和确认边界的基础延伸；业务双写边界见[004_业务双写.md](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/004_业务双写.md:6>)。
<!--ID: 1790086385517-->
