# 006_Java 中如何使用 Redisson 实现分布式锁

## 1 说明

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准：Java 8、Spring Boot 2.7.18、Redis 服务端 6.2.14、`redisson-spring-boot-starter` 3.17.7。版本基线不代表实际部署或依赖兼容性已验证。

本文回答 Java 接入和使用流程。选型理由见 [为什么使用 Redisson 实现分布式锁](<D:/000/sources/eas/000_Redis/003_为什么使用 Redisson 实现分布式锁.md>)。以下配置和代码均为教学示例，不代表项目已完成接入或运行验证。

## 2 问题

在 Spring Boot 项目中，如何使用 Redisson 实现分布式锁？如何配置客户端、获取锁、执行业务和释放锁，并处理等待、续期与失锁后的业务保护？

## 3 高级 Java 面试核心考点

1. 通过 Starter 配置并注入 RedissonClient，为同一资源使用同一锁名。
2. 只有明确加锁成功才执行业务，由持锁线程在 finally 中解锁，保留业务和解锁异常。
3. 区分等待预算与租期，说明固定租期和看门狗的启用条件。
4. 让同步业务及数据库事务在解锁前结束，并处理旧持有者继续写入的风险。

## 4 面试口述版本

口述内容字符数：1358

在 Java 中使用 Redisson 分布式锁，我会先接入客户端，为同一资源使用相同的锁名，再按“尝试加锁、成功后执行业务、最后释放”的顺序处理。同时明确等待时间和租期，保证业务及事务在解锁前结束，并为锁失效后的旧请求设置写入保护。下面是使用方法，不代表项目已经完成验证。

### 4.1 接入客户端，确定锁名

当前技术栈使用 `redisson-spring-boot-starter` 3.17.7，通过 `spring.redis.*` 配置连接，业务类注入自动配置提供的 `RedissonClient`，不需要再手工创建一个客户端。[依赖与连接配置（第 52 行）](<D:/000/sources/eas/000_Redis/006_Java 中如何使用 Redisson 实现分布式锁.md:52>)

然后调用 `redissonClient.getLock("lock:order:" + orderId)` 取得锁对象。比如订单 `1001` 使用 `lock:order:1001`，不同应用实例处理同一订单时，必须使用同一锁名并访问同一套 Redis。取得对象并不表示已经拿到锁，真正竞争锁要调用 `tryLock`。[Java 调用与异常处理（第 79 行）](<D:/000/sources/eas/000_Redis/006_Java 中如何使用 Redisson 实现分布式锁.md:79>)

### 4.2 明确获取成功后，才执行业务

例如调用 `lock.tryLock(3, TimeUnit.SECONDS)`，给竞争锁设置 3 秒等待预算。只有返回 `true` 才执行受保护的业务；返回 `false` 或发生异常时，不能按加锁成功继续。3 秒是教学示例，不是生产配置，也不是整个方法调用的严格耗时上限；实际需要结合接口超时预算和竞争情况确定。

获取成功后，在 `try` 中完成业务，在 `finally` 中由当前持锁线程调用 `unlock`。解锁由 Redisson 原子校验持有者，不能自行 `DEL` 锁键。解锁失败要保留异常，不能当作已成功释放，也不能因此盲目重做业务。[Java 调用与异常处理（第 79 行）](<D:/000/sources/eas/000_Redis/006_Java 中如何使用 Redisson 实现分布式锁.md:79>)

### 4.3 区分等待时间、固定租期和自动续期

等待时间控制愿意花多久竞争锁，租期控制拿到锁后多久失效。上面的两参数 `tryLock` 没有指定固定租期，成功后会安排看门狗，即自动延长锁有效期。3.17.7 默认超时为 30 秒，约每 10 秒调度续期；续期成功后重新设置过期时间，但不能保证每次都及时成功。

如果调用 `tryLock(waitTime, leaseTime, unit)` 并指定正数 `leaseTime`，就使用固定租期，不启用这条看门狗续期路径。固定租期需要根据业务耗时、线程暂停和网络延迟留出余量；当前没有项目测量数据，实际数值待确认。普通 `RLock` 可等待重试，但不保证先到先得。[看门狗与等待机制（第 132 行）](<D:/000/sources/eas/000_Redis/006_Java 中如何使用 Redisson 实现分布式锁.md:132>)

### 4.4 先完成业务，再释放锁

受保护的业务必须在释放锁前完成，不能只提交异步任务就立刻解锁。如果保护数据库事务，也要等事务提交或回滚后再释放，不能业务方法返回了、事务还没有提交就解锁。这样，正常情况下后一个请求不会在前一个请求的事务尚未结束时因为提前解锁而进入。[Java 调用与异常处理（第 79 行）](<D:/000/sources/eas/000_Redis/006_Java 中如何使用 Redisson 实现分布式锁.md:79>)

不过，锁过期不会自动停止旧请求。例如 A 长时间暂停后失去锁，B 拿到锁开始业务；A 恢复后仍可能继续写入。看门狗续期失败或 Redis 主从切换也可能使锁保护失效。因此写入端还要按业务约束校验：例如只允许版本未变化的数据被修改，拒绝基于旧版本的更新。唯一约束只能阻止重复记录；若采用递增令牌 fencing token，还必须由存储端拒绝比已接受令牌更旧的写入。这些保护方式应按业务选择，并非项目已经全部采用。[失锁后的写入保护（第 144 行）](<D:/000/sources/eas/000_Redis/006_Java 中如何使用 Redisson 实现分布式锁.md:144>)

## 5 详细方案

### 5.1 引入依赖并配置客户端

项目基线使用 `redisson-spring-boot-starter` 3.17.7，Spring Boot 为 2.7.18。在已有 Spring Boot 工程中添加：

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.17.7</version>
</dependency>
```

该 Starter 默认引入 `redisson-spring-data-27`，对应 Spring Data Redis 2.7 系列。按项目规则使用 Boot 的依赖管理，不主动覆盖 Netty、Jackson 版本，也不另行重复声明 Redisson 核心包或手工创建客户端。实际依赖冲突仍须用项目最终依赖树和启动结果核验。

下面只是单节点连接配置的结构示例，不代表项目拓扑。通过环境变量填入实际地址和端口；认证、TLS、哨兵或集群配置必须按实际部署另行确认，不能用这段示例代替。

```yaml
spring:
  redis:
    host: ${REDIS_HOST}
    port: ${REDIS_PORT}
```

`REDIS_HOST` 是服务地址；`REDIS_PORT` 是实际监听端口的整数值。项目值尚未确认，因此不填写猜测值。Starter 根据配置提供 `RedissonClient`，业务类通过构造方法注入。后面的 `LockedExecutor` 用 `@Component` 声明为组件，并需放在 Spring Boot 的组件扫描范围内，才能由 Spring 创建对象并完成构造方法注入。

依据：[Starter 3.17.7 官方 POM](https://repo.maven.apache.org/maven2/org/redisson/redisson-spring-boot-starter/3.17.7/redisson-spring-boot-starter-3.17.7.pom)、[RedissonAutoConfiguration 3.17.7](https://github.com/redisson/redisson/blob/redisson-3.17.7/redisson-spring-boot-starter/src/main/java/org/redisson/spring/starter/RedissonAutoConfiguration.java)。自动配置使用 `spring.redis.*`，并在没有现成 `RedissonClient` Bean 时创建客户端。

### 5.2 获取锁、执行业务并释放

完成上述接入后，业务类使用注入的 `RedissonClient` 获取锁。下面是教学示例，展示同步调用的锁边界，不代表项目已经实现或运行验证。`Runnable` 中的业务必须在当前线程完成，不能只提交异步任务后立即返回，否则会在任务结束前释放锁。若保护数据库事务，应让事务在 `action.run()` 返回前完成提交或回滚；锁的外层存在尚未结束的事务时，可能出现先解锁、后提交的问题。

```java
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Component
public final class LockedExecutor {
    private final RedissonClient redissonClient;

    public LockedExecutor(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public boolean execute(String resourceId, Runnable action)
            throws InterruptedException {
        RLock lock = redissonClient.getLock("lock:demo:" + resourceId);
        // 示例：竞争锁的等待预算为 3 秒；不指定固定租期，使用看门狗续期。
        if (!lock.tryLock(3, TimeUnit.SECONDS)) {
            return false; // 未确认获取成功，不执行 action。
        }
        Throwable failure = null;
        try {
            action.run(); // 业务需自行实现数据库条件校验等必要保护。
            return true;
        } catch (RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                lock.unlock(); // 必须由当前持锁线程释放。
            } catch (RuntimeException | Error unlockFailure) {
                if (failure != null) {
                    failure.addSuppressed(unlockFailure);
                } else {
                    throw unlockFailure;
                }
            }
        }
    }
}
```

竞争锁的等待预算 `3` 秒仅为演示可中断的有限等待，依据是展示“成功、竞争失败、异常”三个分支，不是生产建议值；实际应结合接口超时预算和锁竞争情况确认。该参数不是整个调用的严格耗时上限，Redis 命令执行、网络超时及重试还受客户端配置影响。`InterruptedException` 向调用方传播，由上层决定取消策略；如果上层捕获但不继续抛出，应恢复线程中断标记。加锁命令异常可能向上抛出；3.17.7 的订阅等待分支还会将部分异常处理为获取失败。因此，只有 `tryLock` 明确返回 `true` 才执行业务，不能把返回 `false` 一律解释为其他线程正常持锁。

示例没有先调用 `isLocked()`：键存在不能证明当前线程持有锁。`isHeldByCurrentThread()` 也只是一次检查，检查后仍可能过期，不能替代解锁脚本的原子校验。直接调用 `unlock()` 时，Redisson 会检查持有者；若已失去锁，会报告 `IllegalMonitorStateException`。示例保留这类失败，不能因为 `action.run()` 返回就把整个操作报告为正常完成。

`getLock` 的参数决定锁名，本身只取得客户端侧锁对象，不代表已经执行了加锁。示例的 `lock:demo:` 是演示前缀；实际所有竞争同一资源的入口必须使用同一个锁名，并连接同一套 Redis。

### 5.3 看门狗、重入与等待机制

Redisson 3.17.7 的普通 `RLock` 使用 Redis Hash 保存客户端标识与线程 ID 对应的重入计数。Lua 原子判断持有者并更新计数和过期时间；同一持有者可重入，获取几次就需要对应释放几次，计数归零后才删除锁并发布通知。

`tryLock(waitTime, unit)` 未提供固定租期，获取成功后会安排看门狗续期。`lockWatchdogTimeout` 默认是 `30000` 毫秒，即 30 秒；源码按内部租期的三分之一安排续期任务，默认约 10 秒调度一次。续期脚本确认持有者仍存在后，将过期时间重新设置为租期长度，并继续安排下一次任务。调度延迟和通信失败都可能使续期赶不上过期，不能承诺“业务没结束，锁就绝不会过期”。

使用 `tryLock(waitTime, leaseTime, unit)` 并指定正数 `leaseTime` 时，租期到期后锁会失效，不启用上述看门狗续期。因此不能把固定租期与自动续期当成默认同时存在的保障。续期可以降低业务执行期间因固定过期时间到期而失锁的风险，但如果业务线程一直卡住而客户端仍能续期，也可能长期占用锁，仍需业务超时和取消机制配合。

普通 `RLock` 在竞争失败后可订阅解锁消息，并结合剩余等待时间、锁的剩余存活时间等待和重试；通知到达不等于已获得锁，还要重新竞争。它不是公平锁，不保证请求按到达顺序获得锁。不能把原材料的“等待队列”概括理解为普通锁具备先到先得的保证。

依据：[RedissonLock 3.17.7](https://github.com/redisson/redisson/blob/redisson-3.17.7/redisson/src/main/java/org/redisson/RedissonLock.java) 的获取、订阅和解锁实现；[RedissonBaseLock 3.17.7](https://github.com/redisson/redisson/blob/redisson-3.17.7/redisson/src/main/java/org/redisson/RedissonBaseLock.java) 的续期调度及解锁异常；[Config 3.17.7](https://github.com/redisson/redisson/blob/redisson-3.17.7/redisson/src/main/java/org/redisson/config/Config.java) 的默认超时及适用条件。

### 5.4 项目方案与示例的边界

本项目 `AGENTS.md` 将 Redisson 3.17.7 列为批次启动锁的客户端基线，但关联的 [015 批次启动实现方案（原 013）](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/015_不停机数据迁移_在线历史迁移_批次状态控制_启动互斥与初始化事务_实现方案.md>) 仍记载“客户端及版本尚未确认”，并选用 15 分钟固定过期时间。两处客户端状态记载不一致；本组文档按当前 `AGENTS.md` 说明版本，不将此处看门狗示例写成批次启动的已采用实现，也不改变该方案的固定过期时间。关联文档不在本次修改范围内。

手写 Redis 锁的命令与原始资料见 [手写 Redis 锁的加锁与解锁](<D:/000/sources/eas/000_Redis/004_为什么使用 Redisson 实现分布式锁_手写 Redis 锁的加锁与解锁.md>)。本文 Java 示例是按客户端 API 整理的教学代码。

## 6 已确认规则

1. 对本组文档执行连续复核，修正有依据的问题，并记录尚未确认或验证的内容。
2. 准确区分调用 Redisson 提供的分布式锁与自行实现锁机制，按技术依据复核表述。
3. 面试口述应有条理，保留初学者需要的解释、技术条件和待验证事项。
4. 说明失锁后分布式锁的互斥边界，以及锁协调与业务写入校验各自的职责。
5. 保留 003 作为选型主文档，拆出 004 手写 Redis 锁与 005 锁失效后的业务写入保护，原 Java 使用篇顺延至 006。

## 7 待确认或待验证事项

1. **待确认：** 项目实际 Redis 服务端版本和部署拓扑；文档版本基线不代表实际部署。
2. **待确认：** 项目的 Redis 连接配置。
3. **待验证：** 项目最终依赖树及应用启动兼容性；源码核验不能代替项目验证。
4. **待确认：** 具体业务采用固定租期还是看门狗，以及相应的过期或续期超时配置和耗时依据。
5. **待确认：** 具体业务允许的锁等待时间；本文的 3 秒仅为示例。
6. **待验证：** 本文 Java 示例在项目中的编译和 Redis 连接结果。
7. **待验证：** 多实例竞争时，仅明确获取成功的请求执行业务。
8. **待验证：** 已失去锁的旧持有者无法删除新持有者的锁，释放异常能够正确上报。
9. **待确认：** 015 方案（原 013）与 `AGENTS.md` 对客户端状态的记载不一致，需由该方案维护时对齐客户端及实际实现；不能从本文示例推断项目已切换续期策略。
10. 写入保护、锁到期和故障切换的验证事项见 [待确认或待验证事项（第 60 行）](<D:/000/sources/eas/000_Redis/005_为什么使用 Redisson 实现分布式锁_锁失效后的业务写入保护.md:60>)。
11. **待验证：** Codex 阅读器中的实际跳转效果。

## 8 异议与分析

1. 普通 RLock 的等待重试不等于公平排队；正数固定租期与看门狗续期也不是默认同时生效。本文按 3.17.7 的实现说明这些条件，不能沿用没有条件的概括。
2. 获取锁成功、业务执行完成、释放锁成功是不同结果。不能把示例返回路径或源码核验当成项目的实际验证结果。

复核完成时间：2026-11-18 20:12。已执行 [$java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。
