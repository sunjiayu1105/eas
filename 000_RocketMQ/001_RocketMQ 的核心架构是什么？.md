# 001_RocketMQ 的核心架构是什么？

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准：Java 8、Spring Boot 2.7.18、`rocketmq-spring-boot-starter` 2.3.0、`org.apache.rocketmq:rocketmq-client` 5.2.0。本文说明 RocketMQ 的通用架构原理；没有把本地源码目录中的临时材料或某个部署实例当作生产验证证据。

## 1 问题

RocketMQ 的核心架构由消息路由与存储、消息生产、消息消费以及消费进度管理几个部分组成，它们共同完成消息从发送到消费的完整链路。

## 2 高级 Java 面试核心考点

- 能说明 NameServer、Broker、Producer、Consumer 和 Consumer Group 的职责边界。
- 能区分 Topic、MessageQueue、CommitLog 与 ConsumeQueue 的作用。
- 能解释 Producer 和 Consumer 如何通过路由找到 Broker，以及消费组如何分配队列。
- 能说明消息被拉取、业务处理和消费进度持久化不是同一个状态，并指出重复消费的边界。

## 3 面试口述精炼版本

口述内容字符数：669

**整体组成：**

RocketMQ 可以按“路由、存储、生产、消费”来理解。NameServer 保存 Topic 到 Broker 和 MessageQueue 的路由信息，Producer 和 Consumer 从 NameServer 获取并缓存路由。Producer 按路由把消息发送给 Broker，Broker 负责消息存储和读取。Consumer 从 Broker 获取消息并交给业务代码处理，Consumer Group 决定同一组消费者如何分摊队列。

**消息分类与队列：**

Topic 是消息的业务分类；一个 Topic 可以拆成多个 MessageQueue。MessageQueue 是 Topic 下的逻辑队列，通常由 `Topic + BrokerName + QueueId` 标识。Producer 选择可写队列发送消息，Consumer 在集群消费时通过消费组内的负载均衡分配队列，因此多个 Consumer 可以并行处理同一个 Topic。

**Broker 的存储：**

Broker 通常先把完整消息追加到 CommitLog，再为 Topic 和队列构建 ConsumeQueue 索引。CommitLog 保存消息本体，ConsumeQueue 保存消息在 CommitLog 中的位置、长度等索引信息。Consumer 先依据队列位点读取索引，再由 Broker 根据索引读取消息本体。

**消费状态：**

Consumer 获取消息不等于业务处理成功。业务代码处理成功后，消费进度才应向后推进并按消费模式持久化。网络异常、消费失败或实例重启都可能导致同一消息再次投递，因此业务处理需要具备幂等性。详细的发送、存储、拉取和进度处理过程见[工作流程文档](<D:/000/sources/eas/000_RocketMQ/002_RocketMQ 的工作流程是什么？基于 RocketMQ 4.9.7 说明。.md>)。

## 4 详细方案

### 4.1 组件职责

**NameServer：**维护 Broker 注册信息和 Topic 路由信息，向客户端返回 Topic 对应的 Broker、读写队列数量及权限等元数据。NameServer 不保存业务消息本体；其路由信息由 Broker 注册并由客户端缓存。单个 NameServer 节点之间不负责同步全部路由状态，客户端可以配置多个地址以提高查询可用性；本文未核验具体部署数量。

**Broker：**接收 Producer 的发送请求，负责消息持久化、按 Topic 和队列提供读取服务，并维护与消费进度相关的服务端状态。Broker 的主从复制、刷盘策略和故障切换会影响可靠性与可用性，本文只说明核心职责，不把某种复制或刷盘配置认定为项目现状。

**Producer：**从 NameServer 获取 Topic 路由并缓存，根据发送策略选择 MessageQueue，将消息发送到对应 Broker。选择队列的具体策略会影响负载分布和局部有序；本文不把随机、轮询或按业务键选择中的某一种认定为项目配置。

**Consumer：**从 NameServer 获取路由，向负责的 Broker 拉取消息，再调用业务处理逻辑。常见的推模式是客户端封装了持续拉取和回调，底层仍然需要向 Broker 请求消息；推模式和拉模式的细节见同目录对应文档。

**Consumer Group：**由一组订阅相同 Topic、执行相同消费逻辑的 Consumer 实例组成。在集群消费中，组内实例分摊 MessageQueue；在广播消费中，组内每个实例都独立消费自己的消息副本。消费组是消费进度和队列分配的重要边界，不能把它理解成消息的物理存储位置。

### 4.2 路由与发送链路

Broker 启动后向 NameServer 注册自身地址、BrokerName 以及 Topic 的队列和权限信息。Producer 首次发送某个 Topic 时，从本地路由缓存查找；没有可用信息时向 NameServer 查询，并在客户端侧缓存和刷新。之后 Producer 根据可写队列选择一个 MessageQueue，把消息请求发送到该队列所属 Broker。

路由解决的是“消息应该发到哪个 Broker 和哪个队列”，不负责保存消息内容。路由缓存失效、Broker 地址变化或队列权限变化时，客户端需要重新拉取路由；这些行为的具体刷新间隔取决于客户端实现和配置，本文未将其写成项目确认值。

### 4.3 存储与读取链路

Broker 接收到消息后，把消息本体追加写入 CommitLog。随后由消息分发逻辑为对应 Topic 和 MessageQueue 构建 ConsumeQueue 条目。ConsumeQueue 记录消息在 CommitLog 中的物理偏移和长度等定位信息，Consumer 按逻辑队列位点读取索引，Broker 再依据索引返回消息本体。

因此，MessageQueue 是面向消息路由和消费分配的逻辑队列，CommitLog 是消息本体的物理顺序存储，ConsumeQueue 是从逻辑队列到物理存储的索引。三者职责不同，不能把 ConsumeQueue 说成完整消息存储，也不能把 Topic 直接等同于一份物理文件。

### 4.4 消费组与进度

集群消费时，Consumer Group 内的队列分配机制决定某个实例当前负责哪些 MessageQueue；实例增减或故障后可能触发重新分配。广播消费时，每个实例独立消费订阅到的队列，不通过组内分摊实现并行。

Consumer 的拉取位点用于确定下一次从队列的哪个位置请求消息，业务消费进度用于表示消息处理到哪里。消息已经返回给 Consumer，只能说明拉取成功，不能证明业务处理成功。进度的本地维护、持久化位置和失败重试方式与消费模式和客户端实现有关；本文不把同目录其他文档中的版本性描述扩展为当前项目已验证行为。

### 4.5 依据与验证边界

本节依据同目录 002—005 文档中已经记录的架构概念，并参考项目内 `.tmp-rmq-src` 中可见的 `MessageQueue`、`RebalanceImpl`、`MQClientInstance` 等客户端类名进行静态交叉检查。临时源码目录的来源、精确版本和构建状态未在本轮确认，因此它只能支持术语和职责的静态核对，不能证明当前项目运行时配置或生产行为。

本轮未启动 NameServer、Broker、Producer 或 Consumer，也未执行消息发送、队列重平衡、故障恢复和重复消费测试。因此，本文的运行状态、吞吐量、延迟、重试次数和部署规模均未验证。

## 5 已确认规则

- 本文技术版本按照当前项目 `AGENTS.md` 规定的 Java 8、Spring Boot 2.7.18、`rocketmq-spring-boot-starter` 2.3.0 和 RocketMQ Client 5.2.0 说明。

## 6 待确认或待验证事项

- 当前项目实际使用的是 RocketMQ 5.2.0 客户端还是其他客户端版本，需要结合构建文件确认。
- 当前环境的 NameServer、Broker 数量、主从关系、刷盘方式和持久化配置尚未核验。
- 当前业务使用集群消费还是广播消费，以及消费进度的实际持久化方式尚未核验。
- 本文引用的本地临时源码目录的精确版本和来源尚未核验。
- 同目录 002—005 文档中存在 4.9.7、4.9.x、5.2.0 和 2.2.3 等版本表述，需分别复核后再统一，不在本文件中替代性修改其他文档。

## 7 异议与分析

- 原文把 Queue、MessageQueue 和 Topic 并列为同一层级组件，容易让读者误以为 Queue 是独立于 MessageQueue 的另一种运行时组件。本文统一使用 RocketMQ 对外可见的 `MessageQueue`，并说明它是 Topic 下的逻辑队列。
- 原文只列出组件名称，没有说明路由、存储、消费分配和进度之间的关系，无法独立回答“架构如何协作”。本文补充了最小完整链路，但没有把未核验的部署参数写成事实。

复核记录：2026-11-14 16:52，已执行 [$java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。
