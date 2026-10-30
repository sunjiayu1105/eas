# 999_ES改造_Canal Server_canal server 同步 binlog 到 rocketmq_监控与验收

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 1 已确认规则与适用边界

本文集中记录原恢复专题中的监控和验收要求。全部为待执行检查，不代表已经通过测试；正文设计详见各专题。

## 2 面试口述精炼版本

验收需要同时覆盖两个 destination 的读取、消息转换、发送确认和恢复，不能只看 Dashboard 有消息。还要注入部分发送失败、超时和进程重启，并检查日志及消息留存窗口、重复投递和下游旧版本保护。实际结果仍待演练。

## 3 详细方案

### 3.1 监控与异常边界

建议监控单机 Server 及 db0、db1 两个通道：

- Server 进程存活、两个 destination 的连接状态和重启次数；
- binlog 读取延迟、Server 内置生产端到 RocketMQ 的发布延迟和积压；
- RocketMQ 发送失败、重试次数、消息重复和位点恢复；
- Canal Server 本地位点保存状态、文件可读性和存储空间；
- MySQL binlog 剩余保留窗口。
- RocketMQ 最早可读消息与未消费位点的距离、剩余留存窗口及容量；阈值须结合实际 Broker 确认。

日志记录不等于告警已送达，进程启动成功也不等于位点已经连续恢复。涉及本地位点丢失或损坏、binlog 过期、消息格式异常或持续投递失败时，应暂停对应 destination 并人工处理。

### 3.2 Server 侧验收

验收范围包括：

1. 分别验证 db0、db1 的 MySQL 连接、GTID 起点和 16 张表过滤范围。
2. 按项目采用的 FlatMessage JSON 核对实际封装、UPDATE 前值范围、按键拆行后的源行身份及事务标记缺失；在最终格式中验证消息解析、DDL 隔离、字段转换、发送确认和位点推进。
3. 注入部分发送成功、非 SEND_OK、超时结果不明、Broker 重启和 Server 重启，验证来源坐标及版本稳定性、可选事件身份、重放、位点恢复，以及下游并发消费时的旧版本拒绝。
4. 模拟 Server 进程故障及本地位点丢失、损坏或不可读，核对两个通道的中断范围、人工恢复、本地持久化位点与日志连续性、重复投递和积压追赶；无法核对可靠起点时暂停，不能直接跳到最新位置。
5. 分别核对 MySQL binlog 与 RocketMQ 消息留存窗口，覆盖各自读取通道的暂停、修复和追赶；模拟 MQ 未消费消息过期，验证发现缺口后暂停复核，不能靠常规重启跳过。
6. 将代表性 INSERT、UPDATE 事件交给自定义 ES 写入服务，另行核对 DELETE 事件的消息状态、对账和 ILM 删除边界；不能把 DELETE 当作 ES 逐条删除请求验证。

上述内容是待执行验收要求，不代表当前环境已经部署或测试通过。

## 4 参考文章与项目设计的区别

**参考文章：** 展示实验消息发送和 ES 写入结果。

**当前项目：** 还需要验证两个 destination、版本兼容、故障恢复和留存窗口；演示结果不能代替这些验收项。

## 5 待确认或待验证事项

- 监控指标的采集、告警负责人和阈值是否已经落实？
- 各验收场景是否有真实配置、输入事件、输出消息、位点和 ES 结果证据？

## 6 复核记录

从原 022 的监控与验收小节拆出。已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本轮按主题归属去重并复核标题、编号、引用和示例；未执行 MySQL、Canal、RocketMQ 或 ES 联调。

## 7 参考来源

各项方法和配置来源见 [RocketMQ生产端实现](<./999_ES改造_Canal Server_canal server 同步 binlog 到 rocketmq_RocketMQ生产端实现.md>)、[RocketMQ发布与Canal批次确认](<./999_ES改造_Canal Server_canal server 同步 binlog 到 rocketmq_RocketMQ发布与Canal批次确认.md>) 与 [本地位点与单机恢复](<./999_ES改造_Canal Server_canal server 同步 binlog 到 rocketmq_本地位点与单机恢复.md>)；本页组织验证步骤，不追加运行事实。
