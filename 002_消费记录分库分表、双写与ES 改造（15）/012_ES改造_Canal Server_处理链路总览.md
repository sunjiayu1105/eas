# 012_ES改造_Canal Server_处理链路总览

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

本文只负责说明 Canal Server 从源库读取 Binlog 到 RocketMQ 发布、批次确认和单机恢复的整体链路。具体实现、配置和边界分别放在下列专题文档中；本页不重复展开各专题的代码和待验证细节。

## 1 总体处理链路

Canal Server 单机进程加载两个 destination，分别连接 db0、db1 的 MySQL 源库。每个 destination 按过滤规则读取 Binlog，解析 Entry 和事务边界，转换为 Canal RocketMQ 消息，按分区键发布到 RocketMQ；消费端再依据来源信息和业务版本写入 ES。消息发布成功后确认 Canal 批次并持久化位点，进程重启时从本地位点继续读取。

## 2 拆分后的考点导航

### 1. 部署拓扑

- [部署拓扑](<./013_ES改造_Canal Server_部署拓扑.md>)

### 2. 公共配置

- [公共配置](<./014_ES改造_Canal Server_公共配置.md>)

### 3. destination 过滤与分区路由

- [destination 过滤与分区路由](<./015_ES改造_Canal Server_destination过滤与分区路由.md>)

### 4. Binlog 读取与过滤范围

- [Binlog 读取与过滤范围](<./016_ES改造_Canal Server_Binlog读取与过滤范围.md>)

### 5. Binlog 读取与 Entry 解析

- [Binlog 读取与 Entry 解析](<./017_ES改造_Canal Server_Binlog读取与Entry解析.md>)

### 6. 消息格式与字段边界

- [消息格式与字段边界](<./018_ES改造_Canal Server_消息格式与字段边界.md>)

### 7. 事务边界与特殊事件

- [事务边界与特殊事件](<./019_ES改造_Canal Server_事务边界与特殊事件.md>)

### 8. Binlog 发送到 RocketMQ 核心实现

- [Binlog 发送到 RocketMQ 核心实现](<./020_ES改造_Canal Server_Binlog发送到RocketMQ核心实现.md>)

### 9. RocketMQ 发布与 Canal 批次确认

- [RocketMQ 发布与 Canal 批次确认](<./021_ES改造_Canal Server_RocketMQ发布与Canal批次确认.md>)

### 10. 进度确认与单机恢复

- [进度确认与单机恢复](<./022_ES改造_Canal Server_进度确认与单机恢复.md>)
## 3 拆分边界与验证说明

拆分只调整文档组织，不改变已确认的 Canal Server 1.1.7、MySQL 8.0.33、RocketMQ 和单机部署设计。每个文档的运行效果、发行包行为、位点落盘、消息可靠性和故障恢复仍以原待验证事项为准；本次未执行 Canal、MySQL、RocketMQ 或 Elasticsearch 联调。

## 4 本轮梳理记录

本轮按“部署拓扑→公共配置→destination 读取与过滤→Entry 解析→消息格式→事务与特殊事件→RocketMQ 发送→批次确认→单机恢复”的处理顺序重新划分 012～022 的职责，移除了重复的读取、格式和发布说明，并同步修正了文档名称、章节编号和交叉链接。该结构调整属于文档整理，不代表相关功能已经实现或验证。
