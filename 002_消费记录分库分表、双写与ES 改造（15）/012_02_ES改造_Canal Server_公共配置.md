# 012_02_ES改造_Canal Server_公共配置

以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准。

## 1 适用规则

沿用 Canal Server 原总览（原文档已删除，依据待补充） 的已确认规则；下述内容属于项目设计，未表示已部署或通过运行验证。



## 2 面试口述精炼版本

**说明公共配置文件职责**

Canal Server 的全局配置文件是解压目录下的 conf/canal.properties，负责选择运行模式、加载哪些 destination、使用哪种位点模板，以及配置 RocketMQ 连接参数；每个 destination 的源库连接和表过滤写在对应的 conf/<destination>/instance.properties。这些配置只说明启动入口和参数边界，不能证明参数已被实际加载或消息已成功发送。

## 3 详细方案

### 3.2 公共配置示意

以下配置写在 Canal Server 的全局配置文件 `conf/canal.properties` 中，路径相对于 Canal Server 解压目录。该文件统一配置 Server 的运行模式、要加载的 destination（数据同步通道）、实例使用的位点模板和 RocketMQ 连接参数；每个通道专属的源库连接、表过滤等配置则写在 `conf/<destination>/instance.properties` 中。

这组公共配置先决定 Canal Server 如何运行，再指定它加载哪些同步通道和如何保存读取进度。`canal.serverMode` 选择消息发布模式，`canal.destinations` 指定同一进程承载的同步通道，`canal.instance.global.spring.xml` 指定通道实例使用的位点模板，RocketMQ 参数用于连接消息服务。消息格式只约束生产端与消费端的解析契约，不能由配置存在推断消息字段和发送结果已经验证。

以下地址、账号、密码和端口都是占位值，不能直接用于生产：

~~~properties
canal.serverMode=rocketMQ
canal.zkServers=
canal.destinations=db0,db1
canal.auto.reset.latest.pos.mode=false
canal.instance.global.spring.xml=classpath:spring/file-instance.xml


rocketmq.namesrv.addr=<nameserver-address>
rocketmq.producer.group=<producer-group>
~~~

以上属性键已对照 Canal 1.1.7 的官方配置核对；官方 `canal.properties` 默认选择 `file-instance.xml`，本文沿用这一配置入口，采用本地元数据保存消费进度。实际发行包中的模板、文件路径、刷新时机、目录权限及落盘行为仍需核验；配置文件存在不等于位点已经可靠保存。

`canal.auto.reset.latest.pos.mode=false` 针对找不到已指定 binlog 位置后的自动重置路径；首次无有效检查点、且未正确配置初始基线时，仍不能据此承诺一定拒绝从最新位置初始化。启动前必须核对首次 GTID 基线或既有持久化游标，发现缺失就停止启动并复核。

## 4 参考文章与项目设计的区别

参考文章把 Canal 配置、RocketMQ 集群和 ES 8 实验环境放在一条可运行示例中；当前项目只确认公共配置的设计边界，版本、参数加载和消息服务连接仍需按实际环境核对。

## 5 待确认或待验证事项

配置加载、本地位点模板、文件权限和重启恢复仍待实际发行包验证。
