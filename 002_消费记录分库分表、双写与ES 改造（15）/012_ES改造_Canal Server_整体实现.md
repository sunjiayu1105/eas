# 012_ES改造_Canal Server_整体实现

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

本文是 Canal Server 写入链路的总览，详细口述版本、实现依据、参考文章对比和待验证事项已按独立考点拆分到下列 10 个文档。每个拆分文档只回答一个明确问题，设计、实现和验证状态继续分别说明。

## 1 总体处理链路

Canal Server 单机进程加载两个 destination，分别连接 db0、db1 的 MySQL 源库。每个 destination 按过滤规则读取 Binlog，解析 Entry 和事务边界，转换为 Canal RocketMQ 消息，按分区键发布到 RocketMQ；消费端再依据来源信息和业务版本写入 ES。消息发布成功后确认 Canal 批次并持久化位点，进程重启时从本地位点继续读取。

## 2 拆分后的考点导航

### 1. 部署拓扑

- [部署拓扑](<./012_01_ES改造_Canal Server_部署拓扑.md>)

### 2. 公共配置

- [公共配置](<./012_02_ES改造_Canal Server_公共配置.md>)

### 3. destination 过滤与分区路由

- [destination 过滤与分区路由](<./012_03_ES改造_Canal Server_destination过滤与分区路由.md>)

### 4. Binlog 读取与 Entry 解析

- [Binlog 读取与 Entry 解析](<./012_04_ES改造_Canal Server_Binlog读取与Entry解析.md>)

### 5. 消息格式与字段边界

- [消息格式与字段边界](<./012_05_ES改造_Canal Server_消息格式与字段边界.md>)

### 6. 事务边界与特殊事件

- [事务边界与特殊事件](<./012_06_ES改造_Canal Server_事务边界与特殊事件.md>)

### 7. Binlog 发送到 RocketMQ 核心实现

- [Binlog 发送到 RocketMQ 核心实现](<./012_07_ES改造_Canal Server_Binlog发送到RocketMQ核心实现.md>)

### 8. 日志读取与变更解析

- [日志读取与变更解析](<./012_08_ES改造_Canal Server_日志读取与变更解析.md>)

### 9. RocketMQ 消息发布与事务边界

- [RocketMQ 消息发布与事务边界](<./012_09_ES改造_Canal Server_RocketMQ消息发布与事务边界.md>)

### 10. 进度确认与单机恢复

- [进度确认与单机恢复](<./012_10_ES改造_Canal Server_进度确认与单机恢复.md>)

## 3 拆分边界与验证说明

拆分只调整文档组织，不改变已确认的 Canal Server 1.1.7、MySQL 8.0.33、RocketMQ 和单机部署设计。每个文档的运行效果、发行包行为、位点落盘、消息可靠性和故障恢复仍以原待验证事项为准；本次未执行 Canal、MySQL、RocketMQ 或 Elasticsearch 联调。
