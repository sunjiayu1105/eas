# 011_Java中如何使用Redisson实现分布式锁

## 1 说明

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准：Java 8、Spring Boot 2.7.18、Redis 服务端 6.2.14、`redisson-spring-boot-starter` 3.17.7。版本基线不代表实际部署或依赖兼容性已验证。

前一篇见 [010 Redisson的等待时间与租期怎么选](<D:/000/sources/eas/000_Redis/010_Redisson的等待时间与租期怎么选.md>)。

直接追问见 [012 Java中如何使用Redisson实现分布式锁_为什么要等数据库事务结束再解锁](<D:/000/sources/eas/000_Redis/012_Java中如何使用Redisson实现分布式锁_为什么要等数据库事务结束再解锁.md>)。

## 2 问题

Java 中怎样用 Redisson 获取锁、执行业务和释放锁？

## 3 高级 Java 面试核心考点

1. 锁名与明确获取成功的条件
2. 同步业务及持锁线程释放
3. 业务失败与解锁失败的区分

## 4 面试口述版本

口述内容字符数：197

先让各实例使用同一套 Redis，并给同一资源使用相同的锁名。用 `getLock` 取得 `RLock`，再调用 `tryLock`。只有返回 `true` 才在 `try` 里完成业务，返回 `false` 或抛出异常就不进入业务。业务结束后，由持锁线程在 `finally` 里调用 `unlock`，Redisson 会原子校验持有者。解锁失败要保留异常，不能据此直接重做业务。如果锁保护数据库事务，要等事务提交或回滚后再释放。[详细说明（第 29 行）](<D:/000/sources/eas/000_Redis/011_Java中如何使用Redisson实现分布式锁.md:29>)

## 5 详细方案

### 5.1 锁名与明确获取成功的条件

完成上述接入后，业务类使用注入的 `RedissonClient` 获取锁。下面是教学示例，展示同步调用的锁边界，不代表项目已经实现或运行验证。`Runnable` 中的业务必须在当前线程完成，不能只提交异步任务后立即返回，否则会在任务结束前释放锁。若保护数据库事务，解锁必须晚于事务结束。

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

事务边界见 [012 Java中如何使用Redisson实现分布式锁_为什么要等数据库事务结束再解锁](<D:/000/sources/eas/000_Redis/012_Java中如何使用Redisson实现分布式锁_为什么要等数据库事务结束再解锁.md>)，失锁后写入端的校验见 [007 如何防止失去Redis锁的旧请求破坏数据](<D:/000/sources/eas/000_Redis/007_如何防止失去Redis锁的旧请求破坏数据.md>)。

## 6 已确认规则

1. 按理由、结论、实现的依赖顺序编排，保留理解当前回答所需的前提和边界。
2. 面试口述尽量控制在 300 字符以内，详细示例、源码与待验证事项随主题迁移。
3. 保留 Java 示例及获取失败、等待中断、解锁失败的处理。

## 7 待确认或待验证事项

1. **待验证：** 本文 Java 示例在项目中的编译和 Redis 连接结果。
2. **待验证：** 多实例竞争时，仅明确获取成功的请求执行业务。
3. **待验证：** 已失去锁的旧持有者无法删除新持有者的锁，释放异常能够正确上报。
4. **待验证：** Codex 阅读器中的实际跳转效果。

## 8 异议与分析

获取锁、业务完成与成功解锁是不同结果。源码说明与教学示例不代表项目已验证。

复核完成时间：2026-11-18 20:43。已执行 [$java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。
