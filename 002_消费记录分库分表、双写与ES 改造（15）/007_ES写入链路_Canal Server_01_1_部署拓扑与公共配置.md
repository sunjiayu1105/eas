# 007_ES写入链路_Canal Server_01_1_部署拓扑与公共配置

以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准。

## 1 适用规则

沿用 [Canal Server 总览](<./007_ES写入链路_Canal Server_Canal Server 处理逻辑.md>) 的已确认规则；下述内容属于项目设计，未表示已部署或通过运行验证。

## 2 面试口述精炼版本

项目采用一个 Canal Server 进程承载 db0、db1 两个同步通道。公共配置选择 RocketMQ 发布模式、加载通道并使用本地位点模板，不配置 ZooKeeper 自动切换。两个通道共享进程故障影响，实际配置加载、位点落盘和恢复仍待验证。

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

### 3.2 公共配置示意

这组公共配置先决定 Canal Server 如何运行，再指定它加载哪些同步通道和如何保存读取进度。`canal.serverMode` 选择消息发布模式，`canal.destinations` 指定同一进程承载的同步通道，`canal.instance.global.spring.xml` 指定通道实例使用的位点模板，RocketMQ 参数用于连接消息服务。消息格式只约束生产端与消费端的解析契约，不能由配置存在推断消息字段和发送结果已经验证。

以下地址、账号、密码和端口都是占位值，不能直接用于生产：

~~~properties
canal.serverMode=rocketMQ
# 单机部署，ZooKeeper 地址保持为空
canal.zkServers=
canal.destinations=db0,db1
canal.auto.reset.latest.pos.mode=false
canal.instance.global.spring.xml=classpath:spring/file-instance.xml

# 消息格式配置见 01_3_Binlog事件解析与消息格式.md。

# 以下键名已按 1.1.7 源码核对，实际加载仍待验证
rocketmq.namesrv.addr=<nameserver-address>
rocketmq.producer.group=<producer-group>
~~~

以上属性键已对照 Canal 1.1.7 的官方配置核对；官方 `canal.properties` 默认选择 `file-instance.xml`，本文沿用这一配置入口，采用本地元数据保存消费进度。实际发行包中的模板、文件路径、刷新时机、目录权限及落盘行为仍需核验；配置文件存在不等于位点已经可靠保存。

`canal.auto.reset.latest.pos.mode=false` 针对找不到已指定 binlog 位置后的自动重置路径；首次无有效检查点、且未正确配置初始基线时，仍不能据此承诺一定拒绝从最新位置初始化。启动前必须核对首次 GTID 基线或既有持久化游标，发现缺失就停止启动并复核。

## 4 参考文章与项目设计的区别

参考文章 [使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案](<./999_使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案.md>) 的版本、脚本和演示结果只代表文章环境，不能证明项目已经实现或验证。

### 1. 部署拓扑不同

- 文章围绕一个 `order_db.orders` 示例展开，并配置 RocketMQ 4.9.4 的 NameServer、两个 Broker 和 Dashboard；当前项目是 1 个 Canal Server 进程承载 db0、db1 两个 destination，不配置 ZooKeeper 高可用，RocketMQ Broker 版本也尚未确认。
- 文章把 Canal、RocketMQ 和 ES 8 冷热节点部署在实验环境中，包含可运行的启动命令和效果演示；当前文档只记录项目设计和配置边界，未把部署、联调或故障恢复写成已完成事实。

## 5 待确认或待验证事项

配置加载、本地位点模板、文件权限和重启恢复仍待实际发行包验证。
