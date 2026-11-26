# 020_如何设置Java线程池大小

## 1 说明

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。适用于 Java 8 平台线程。

## 2 问题

一般情况下，如何设置 Java 线程池的大小？

### 2.1 考点

1. 按可用 CPU 核数估算计算密集型任务的起始线程数。
2. 按计算和同步等待的时间估算阻塞任务的起始线程数。

### 2.2 答题范围

用同一份 CPU 预算估算两类任务的核心线程数，说明固定倍数的适用条件，以及压测时如何控制最大线程数和队列。

### 2.3 候选理由

材料提出按任务的等待与计算时间估算并发度。用相同的 CPU 预算比较两类任务，可以考查候选人能否解释线程数为什么不同。公开备考资料中的问题“线程池参数怎么估算？”也考查如何从负载与资源条件确定起点。[JavaGuide《Java 线程池最佳实践》](https://github.com/Snailclimb/JavaGuide/blob/main/docs/java/concurrent/java-thread-pool-best-practices.md)[线程池大小与任务积压原始材料](</D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/080_线程池大小与任务积压原始材料.md:1>)

## 3 面试口述版本

口述内容字符数：258（不计参考链接）

### 3.1 主要做计算的任务

我先估算核心线程数 corePoolSize。计算型任务的起始线程数 ≈ 分给线程池的 CPU 核数。假设扣除其他业务和预留算力后还有 4 核，做哈希计算就从 4 个线程开始测。这里 N 指分给池的核数，N+1 可以作为相邻档位，额外的线程是否有收益要压测。[Zalando 工程文章 · 搜索 blocking coefficient，见等待时间接近零时的估算](<https://engineering.zalando.com/posts/2019/04/how-to-set-an-ideal-thread-pool-size.html>)

### 3.2 同步等待 I/O 的任务

起始线程数 ≈ 可用 CPU 核数 ×（1 + 平均阻塞等待时间 ÷ 平均计算时间）。假设每个任务计算 10 毫秒、等数据库 20 毫秒，就从 4 ×（1 + 20 ÷ 10）= 12 个线程起测。只有等待和计算时间相近时，公式才给出约 2N。只计工作线程被占住的等待时间，排队或异步释放线程后的等待要扣除。最后结合下游连接容量和压测结果定值。[Java 8 ThreadPoolExecutor · Queuing 中的 Bounded queues](<https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadPoolExecutor.html>) · [Zalando · 线程数公式与数据库连接池限制](<https://engineering.zalando.com/posts/2019/04/how-to-set-an-ideal-thread-pool-size.html>)

## 4 详细方案

用同一组有代表性的任务测量每个任务的线程 CPU 时间 C 和执行期间的阻塞等待时间 W。任务进入线程池之前或在工作队列中排队的时间要单独统计。线程平均占用一个核心的比例约为 C / (C + W)，所以候选线程数约为 CPU 预算 × (1 + W / C)。CPU 预算要考虑进程配额、同机其他负载和希望留出的余量。等待时间随并发增长时，重新测量。

每档把 corePoolSize 和 maximumPoolSize 都设为该档线程数，观察有足够任务时实际活跃的线程数。改变线程数时保持队列策略和负载一致。配置弹性池时还要看工作队列。核心线程都忙时，ThreadPoolExecutor 会先尝试入队，入队失败后才尝试扩到最大线程数。使用近似无界的队列时，单独调大 maximumPoolSize 往往起不到扩容作用。[Java 8 ThreadPoolExecutor](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadPoolExecutor.html)

队列过大可能积压任务、占用堆内存并拉长等待。队列较小时更早尝试扩容，队列和线程都达到上限后才触发饱和拒绝。结果由拒绝策略决定，例如抛出异常或让提交方执行。按业务能容忍的排队时间和内存预算选容量，再压测验证。[Java 8 拒绝策略](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadPoolExecutor.html) · [任务队列容量](</D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/024_在线历史迁移_并发限流_任务队列容量.md:1>)

### 4.1 相关追问

[你刚才按 4 核估算，怎么知道这个池能用多少核？](</D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/021_如何设置Java线程池大小_估算可用CPU核数.md:1>)

[算出起始线程数后，怎么判断应该增加线程，还是已经够用？](</D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/022_如何设置Java线程池大小_通过压测确定线程数.md:1>)

## 5 已确认规则

1. 面试口述中的技术结论附上可直接核对的参考来源。
2. 先说明估算公式，再代入示例数值。

## 6 整理进度

记录时间：2026-11-26 23:17

最终状态：已完成
