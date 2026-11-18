# 008_为什么使用Redisson实现分布式锁

## 1 说明

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准：Java 8、Spring Boot 2.7.18、Redis 服务端 6.2.14、`redisson-spring-boot-starter` 3.17.7。版本基线不代表实际部署或依赖兼容性已验证。

前一篇见 [007 如何防止失去Redis锁的旧请求破坏数据](<D:/000/sources/eas/000_Redis/007_如何防止失去Redis锁的旧请求破坏数据.md>)。

接下来读 [009 Spring Boot如何接入Redisson](<D:/000/sources/eas/000_Redis/009_Spring Boot如何接入Redisson.md>)。

选型依据见 [003 为什么多实例部署时Java本地锁不够用](<D:/000/sources/eas/000_Redis/003_为什么多实例部署时Java本地锁不够用.md>)、[004 手写Redis锁如何原子加锁](<D:/000/sources/eas/000_Redis/004_手写Redis锁如何原子加锁.md>)、[005 手写Redis锁如何安全解锁](<D:/000/sources/eas/000_Redis/005_手写Redis锁如何安全解锁.md>)、[006 Redis锁失效后为什么旧请求还能写入](<D:/000/sources/eas/000_Redis/006_Redis锁失效后为什么旧请求还能写入.md>)、[007 如何防止失去Redis锁的旧请求破坏数据](<D:/000/sources/eas/000_Redis/007_如何防止失去Redis锁的旧请求破坏数据.md>)。

## 2 问题

为什么选择 Redisson 实现分布式锁？

## 3 高级 Java 面试核心考点

1. 手写 Redis 锁与 Redisson 的维护职责
2. 使用 Redisson 的条件、收益与限制

## 4 面试口述版本

口述内容字符数：175

手写 Redis 锁要自己做好原子加锁和持有者校验。如果业务还要支持同一持有者重复加锁、等待重试或自动续期，这些机制也得自己维护。Redisson 的 `RLock` 已提供这些能力，业务仍需管理连接、依赖版本和锁参数，并在写入端处理失锁风险。如果项目已经使用 Redis，确实需要跨进程协调，并能承担这些接入成本，我会优先选择 Redisson。[详细说明（第 30 行）](<D:/000/sources/eas/000_Redis/008_为什么使用Redisson实现分布式锁.md:30>)

## 5 详细方案

### 5.1 手写 Redis 锁与 Redisson 的维护职责

Java 本地线程锁的协调范围在同一进程内；部署多个应用实例时，若要协调同一资源，需要共同的协调机制。手写 Redis 锁与 Redisson 都通过 Redis 协调不同进程对资源的访问，都必须考虑持有者、有效期和失锁后的业务保护。比较的重点是锁机制由谁实现和维护。

**手写字符串锁：** 应用使用 `SET NX PX` 和 Lua 释放脚本实现基础互斥。基础字符串锁不提供重入；若业务还需要等待重试和续期，需要另外实现对应机制，并验证异常和并发边界。

**Redisson：** 应用使用现成的 `RLock` 接口。3.17.7 普通锁用客户端标识和线程 ID 标识持有者，在 Hash 中记录重入计数，由 Lua 原子处理获取、续期和释放；它还实现了解锁通知订阅和等待重试。收益是减少应用自行实现这些通用机制的工作，而不是免除业务验证。代价是增加客户端依赖、配置和版本维护，并需要理解等待、固定租期与看门狗的差异。

具体参数与内部处理见 [010 Redisson的等待时间与租期怎么选](<D:/000/sources/eas/000_Redis/010_Redisson的等待时间与租期怎么选.md>)。选型对比所依据的实现见 [RedissonLock 3.17.7](https://github.com/redisson/redisson/blob/redisson-3.17.7/redisson/src/main/java/org/redisson/RedissonLock.java)、[RedissonBaseLock 3.17.7](https://github.com/redisson/redisson/blob/redisson-3.17.7/redisson/src/main/java/org/redisson/RedissonBaseLock.java)、[Config 3.17.7](https://github.com/redisson/redisson/blob/redisson-3.17.7/redisson/src/main/java/org/redisson/config/Config.java)。

### 5.2 选型条件与业务边界

选择 Redisson 的前提是业务需要基于 Redis 协调多个进程，并愿意承担客户端接入和维护成本。它提供的续期可以降低固定过期时间到期而失锁的风险，但不能保证续期永不失败，也不能终止失去锁的旧请求。

原子解锁只保护锁键。旧请求如果仍在执行，写入端需要按业务约束校验，具体见 [007 如何防止失去Redis锁的旧请求破坏数据](<D:/000/sources/eas/000_Redis/007_如何防止失去Redis锁的旧请求破坏数据.md>)。Redis 异步复制下的故障切换也可能丢失锁记录，因此部署拓扑和故障行为必须验证。

依据：[Redis 官方分布式锁说明](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/)。本项目的版本基线已确认，但实际部署、依赖兼容性和故障行为尚未验证。

## 6 已确认规则

1. 按理由、结论、实现的依赖顺序编排，保留理解当前回答所需的前提和边界。
2. 面试口述尽量控制在 300 字符以内，详细示例、源码与待验证事项随主题迁移。
3. 明确 Redisson 已提供锁机制，应用调用相应接口。
4. 先说明实现成本和适用条件，再给选型结论。

## 7 待确认或待验证事项

1. **待确认：** 项目实际 Redis 服务端版本和部署拓扑；文档版本基线不代表实际部署。
2. 依赖兼容性见 [009 Spring Boot如何接入Redisson](<D:/000/sources/eas/000_Redis/009_Spring Boot如何接入Redisson.md>)，故障行为见 [006 Redis锁失效后为什么旧请求还能写入](<D:/000/sources/eas/000_Redis/006_Redis锁失效后为什么旧请求还能写入.md>)。
3. **待验证：** Codex 阅读器中的实际跳转效果。

## 8 异议与分析

旧表述容易把调用 Redisson 与自行实现字符串锁混为一谈。普通 RLock 由 Redisson 使用 Hash 和 Lua 实现，业务通过 API 调用。

复核完成时间：2026-11-18 20:43。已执行 [$java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。
