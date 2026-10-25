# 012_01_ES改造_Canal Server_部署拓扑

以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准。

## 1 适用规则

沿用 Canal Server 原总览（原文档已删除，依据待补充） 的已确认规则；下述内容属于项目设计，未表示已部署或通过运行验证。

## 2 面试口述精炼版本

项目采用一个 Canal Server 进程承载 db0、db1 两个 destination。两个通道共享进程故障影响，不配置 ZooKeeper 自动切换；部署容量、重启恢复和实际拓扑仍待验证。

## 3 详细方案

### 3.1 部署拓扑

~~~mermaid
flowchart LR
    DB0[(MySQL db0)] --> S[单机 Canal Server]
    DB1[(MySQL db1)] --> S
    S -->|内置 RocketMQ 生产端| MQ[(RocketMQ)]
    MQ --> C[RocketMQ 消费与 ES 写入服务]
~~~

图中是 1 台主机上的 1 个 Server 进程，同时加载 db0、db1 两个 destination。两个通道分别维护连接、过滤规则和本地进度，但共享该进程的故障影响；不配置 ZooKeeper 或自动主备切换。单机承载两个通道的容量与重启恢复仍待验证。

## 4 参考文章与项目设计的区别

- **数据库接入范围**：参考文章以单个 `order_db.orders` 表的实验为主；当前项目设计由一个 Canal Server 进程承载 `db0`、`db1` 两个 destination（数据同步通道），分别连接两个独立的 MySQL 实例。
- **RocketMQ 版本**：参考文章使用 RocketMQ 4.9.4；当前项目的 RocketMQ Broker 服务端版本尚未确认，不能用已确认的 `rocketmq-client` 客户端版本 5.2.0 代替。
- **RocketMQ 部署组件**：参考文章配置 NameServer（名称服务）、两个 Broker（消息存储与投递服务）和 Dashboard（管理控制台）；当前项目中这些组件的实际部署情况和实例数量仍待确认，不能直接沿用文章配置作为项目实现。

## 5 待确认或待验证事项

配置加载、本地位点模板、文件权限和重启恢复仍待实际发行包验证。
