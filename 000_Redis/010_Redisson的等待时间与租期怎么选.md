# 010_Redisson的等待时间与租期怎么选

## 1 说明

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准：Java 8、Spring Boot 2.7.18、Redis 服务端 6.2.14、`redisson-spring-boot-starter` 3.17.7。版本基线不代表实际部署或依赖兼容性已验证。

前一篇见 [009 Spring Boot如何接入Redisson](<D:/000/sources/eas/000_Redis/009_Spring Boot如何接入Redisson.md>)。

接下来读 [011 Java中如何使用Redisson实现分布式锁](<D:/000/sources/eas/000_Redis/011_Java中如何使用Redisson实现分布式锁.md>)。

## 2 问题

Redisson 的等待时间、固定租期和看门狗怎么选？

## 3 高级 Java 面试核心考点

1. 竞争预算与锁有效期
2. 固定租期和看门狗的启用条件
3. 参数取值依据

## 4 面试口述版本

口述内容字符数：192

等待时间是愿意为抢锁花多久，应结合接口超时和竞争情况来定。租期是锁的有效时间。业务耗时可估算时，可以指定正数固定租期，并为线程暂停和网络延迟留余量，此时不启用看门狗。未传固定租期的 `tryLock` 成功后会安排自动续期。3.17.7 的默认超时是 30 秒，约每 10 秒调度续期。续期可能因停顿或断连失败。业务如果一直卡住，客户端也可能持续续期，因此还要有业务超时和取消机制。[详细说明（第 29 行）](<D:/000/sources/eas/000_Redis/010_Redisson的等待时间与租期怎么选.md:29>)

## 5 详细方案

### 5.1 竞争预算与锁有效期

Redisson 3.17.7 的普通 `RLock` 使用 Redis Hash 保存客户端标识与线程 ID 对应的重入计数。Lua 原子判断持有者并更新计数和过期时间；同一持有者可重入，获取几次就需要对应释放几次，计数归零后才删除锁并发布通知。

`tryLock(waitTime, unit)` 未提供固定租期，获取成功后会安排看门狗续期。`lockWatchdogTimeout` 默认是 `30000` 毫秒，即 30 秒；源码按内部租期的三分之一安排续期任务，默认约 10 秒调度一次。续期脚本确认持有者仍存在后，将过期时间重新设置为租期长度，并继续安排下一次任务。调度延迟和通信失败都可能使续期赶不上过期，不能承诺“业务没结束，锁就绝不会过期”。

使用 `tryLock(waitTime, leaseTime, unit)` 并指定正数 `leaseTime` 时，租期到期后锁会失效，不启用上述看门狗续期。因此不能把固定租期与自动续期当成默认同时存在的保障。续期可以降低业务执行期间因固定过期时间到期而失锁的风险，但如果业务线程一直卡住而客户端仍能续期，也可能长期占用锁，仍需业务超时和取消机制配合。

普通 `RLock` 在竞争失败后可订阅解锁消息，并结合剩余等待时间、锁的剩余存活时间等待和重试；通知到达不等于已获得锁，还要重新竞争。它不是公平锁，不保证请求按到达顺序获得锁。不能把原材料的“等待队列”概括理解为普通锁具备先到先得的保证。

依据：[RedissonLock 3.17.7](https://github.com/redisson/redisson/blob/redisson-3.17.7/redisson/src/main/java/org/redisson/RedissonLock.java) 的获取、订阅和解锁实现；[RedissonBaseLock 3.17.7](https://github.com/redisson/redisson/blob/redisson-3.17.7/redisson/src/main/java/org/redisson/RedissonBaseLock.java) 的续期调度及解锁异常；[Config 3.17.7](https://github.com/redisson/redisson/blob/redisson-3.17.7/redisson/src/main/java/org/redisson/config/Config.java) 的默认超时及适用条件。

选择等待时间时，需要同时考虑接口响应预算和竞争情况。Java 示例使用 3 秒演示有限等待，实际值需测量确认。

## 6 已确认规则

1. 按理由、结论、实现的依赖顺序编排，保留理解当前回答所需的前提和边界。
2. 面试口述尽量控制在 300 字符以内，详细示例、源码与待验证事项随主题迁移。
3. 区分等待预算、固定租期和自动续期，并标明数值依据。

## 7 待确认或待验证事项

1. **待确认：** 具体业务采用固定租期还是看门狗，以及相应的过期或续期超时配置和耗时依据。
2. **待确认：** 具体业务允许的锁等待时间；本文的 3 秒仅为示例。
3. **待验证：** Codex 阅读器中的实际跳转效果。

## 8 异议与分析

普通 RLock 的通知和重试不保证先到先得。显式正数租期不会启用这里描述的看门狗续期路径。

复核完成时间：2026-11-18 20:43。已执行 [$java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。
