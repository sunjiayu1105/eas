# 019_不停机数据迁移_在线历史迁移_线程池_Java里面有哪些内置的线程池

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准，使用 Java 8。文中的默认参数来自 Java 8 API 与 OpenJDK 8 源码；示例仅用于说明机制，不代表项目生产配置或压测结果。

## 1 问题

Java 8 常见的线程池有哪些？它们怎样管理线程和任务，分别适合什么场景？

## 2 面试口述精炼版本

口述内容字符数：1466

### 2.1 普通任务

`Executors` 是 Java 提供的工具类，通过静态工厂方法创建不同类型的线程池。下面的方法声明省略 `public static`。

`ExecutorService newFixedThreadPool(int nThreads)`：创建固定线程池，实际对象是 `ThreadPoolExecutor`，核心线程数和最大线程数都为 nThreads，线程按需创建，多出的任务排队等待。使用 LinkedBlockingQueue，默认容量为 Integer.MAX_VALUE，约 21 亿，通常视为无界队列。适合提交规模受控、需要限制同时执行任务数的后台批处理。

`ExecutorService newSingleThreadExecutor()`：创建单线程池，返回包装对象，内部使用 `ThreadPoolExecutor`，核心线程数和最大线程数都为 1，串行处理普通任务。使用 LinkedBlockingQueue，默认容量同样为 Integer.MAX_VALUE，通常视为无界队列。适合需要串行处理的后台工作，例如由同一执行器依次更新内存统计结果。

`ExecutorService newCachedThreadPool()`：创建缓存线程池，实际对象是 `ThreadPoolExecutor`。核心线程数为 0，不保留常驻核心线程；有任务时优先复用空闲线程，没有可用线程就尝试新建，但不超过最大线程数 `Integer.MAX_VALUE`。使用容量为 0 的 SynchronousQueue，不存储任务，而是直接交接给等待任务的线程。线程空闲达到 60 秒后可以回收。适合提交规模受控、执行时间短且到达间隔不固定的异步任务，不宜直接承接不受控的突发请求。

### 2.2 定时任务

`ScheduledExecutorService newScheduledThreadPool(int corePoolSize)`：创建调度线程池，实际对象是 `ScheduledThreadPoolExecutor`，它继承 `ThreadPoolExecutor`，按指定核心线程数执行任务。使用无界延迟队列 DelayedWorkQueue，没有可配置的固定容量上限。适合延迟执行或多个独立周期任务，例如定期刷新不同缓存。

`ScheduledExecutorService newSingleThreadScheduledExecutor()`：创建单线程调度池，返回包装对象，内部使用继承自 `ThreadPoolExecutor` 的 `ScheduledThreadPoolExecutor`，用一个工作线程执行调度任务。同样使用无界 DelayedWorkQueue，没有可配置的固定容量上限。适合需要串行执行的轻量定时工作，例如周期采集本地统计信息；长任务会拖延其他到期任务。

可以通过返回对象调用 `schedule` 等方法，执行延迟或周期任务；任务到期后仍需等待可用线程。

### 2.3 并行计算

`ExecutorService newWorkStealingPool()`：创建工作窃取线程池，实际对象是 `ForkJoinPool`，与 `ThreadPoolExecutor` 互不继承，也不以它为底层。默认目标并行度为 JVM 可用处理器数，适合可拆分为独立子任务的 CPU 计算，例如分块统计数据后汇总结果，不适合直接执行大量阻塞 I/O，也不保证执行顺序。内部使用多个可扩容的工作队列，工厂方法不提供队列容量参数，不能套用普通线程池的单个阻塞队列容量模型。

### 2.4 使用边界

固定池、单线程池和调度池可能积压任务，缓存池可能创建过多线程。选型要匹配任务特点，并控制线程数量和提交规模。

[详细说明](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/019_不停机数据迁移_在线历史迁移_线程池_Java里面有哪些内置的线程池.md:41>)

## 3 详细方案

### 3.1 先分清工厂方法与实现类

`Executors` 是工具类，`newFixedThreadPool` 等是它提供的工厂方法。`ExecutorService` 是任务提交、关闭等操作的接口，不能把这些名称都当成不同的线程池实现类。

- 固定池和缓存池：实际返回 `ThreadPoolExecutor`。单线程池实际返回 `Executors.FinalizableDelegatedExecutorService` 包装对象，内部使用 `ThreadPoolExecutor`，避免调用方将其改成多线程池。
- 调度池：实际返回 `ScheduledThreadPoolExecutor`，它继承 `ThreadPoolExecutor`。单线程调度池实际返回 `Executors.DelegatedScheduledExecutorService` 包装对象，内部使用 `ScheduledThreadPoolExecutor`。
- 工作窃取池：底层使用 `ForkJoinPool`，它与 `ThreadPoolExecutor` 都继承 `AbstractExecutorService`，但两者互不继承，采用不同的任务调度机制。

来源：[OpenJDK 8 Executors 源码](https://github.com/openjdk/jdk8u/blob/master/jdk/src/share/classes/java/util/concurrent/Executors.java)。以下分别说明六个常用工厂方法；不是 Java 所有执行器实现的完整清单。

### 3.2 固定线程池：限制同时执行的任务数

`Executors.newFixedThreadPool(nThreads)` 的核心线程数和最大线程数都等于调用方指定的 `nThreads`，线程按需创建。默认使用 `LinkedBlockingQueue`，容量为 `Integer.MAX_VALUE`，通常称为无界队列。

线程池正常运行时，工作线程数不足核心数，就尝试创建线程执行新任务；达到核心数后，新任务进入队列，再由工作线程取出执行。多线程执行不保证任务的完成顺序。

**示例：最多同时执行 2 个任务**

1. 创建 `newFixedThreadPool(2)`，2 是示例线程数。
2. 提交 A、B，两条工作线程分别执行，假设此时都未完成。
3. 提交 C，C 进入队列等待。
4. 某条工作线程完成当前任务后，取出 C 继续执行。

固定线程数只限制执行并发，不能限制排队任务占用的内存。持续提交快于处理时，任务可能大量积压。默认拒绝策略是 `AbortPolicy`，会抛出 `RejectedExecutionException`；线程池关闭后也会拒绝新任务，不能说只有队列满才会拒绝。

来源：[ThreadPoolExecutor Java 8 API：线程数、队列与拒绝策略](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadPoolExecutor.html)；默认构造参数见 3.1 的源码。

### 3.3 单线程池：串行处理普通任务

`Executors.newSingleThreadExecutor()` 底层核心线程数和最大线程数都是 1，使用容量为 `Integer.MAX_VALUE` 的 `LinkedBlockingQueue`。一次只执行一个任务，适合需要串行处理的后台工作；工作线程意外终止时，会按需补充线程处理后续任务。

**示例：串行更新同一份内存统计结果**

1. 创建单线程池，依次提交 A、B、C。
2. A 执行结束后执行 B，B 结束后执行 C。
3. 只有这些任务访问统计结果时，它们之间不会并发修改；如果其他线程也访问，仍需考虑同步和可见性。

任务积压时仍可能耗尽内存。这里的串行是该执行器内部的保证，不代表它能直接替代 Swing 等框架要求的专用事件线程。

来源：3.1 的 `newSingleThreadExecutor` 源码及说明。

### 3.4 缓存线程池：直接交接任务，必要时增加线程

`Executors.newCachedThreadPool()` 的默认核心线程数为 0、最大线程数为 `Integer.MAX_VALUE`，空闲线程超过 60 秒会被回收。它使用 `SynchronousQueue`，这个队列容量为 0，不保存等待执行的任务。

**交接过程**

线程池先尝试将任务直接交给正在等待任务的工作线程；交接失败，就尝试在最大线程数限制内创建线程。无法接收时按拒绝策略处理。`execute` 在这里调用的是非阻塞 `offer`，不会等待队列出现空位；不能与直接调用 `SynchronousQueue.put` 时等待接收方的行为混淆。

**示例：复用与扩容**

1. 工作线程 A 正在等待接收任务，新任务与它交接成功，由 A 执行。
2. A 尚未完成，又提交一个任务，此时没有其他线程能够接收。
3. 线程池尝试新建线程 B 执行任务，而不是把任务存入队列。
4. 线程完成任务后继续等待；空闲超过 60 秒后可被回收。

适合已控制提交规模的短任务。最大线程数虽然是有限整数，但通常远超机器承载能力，不能因为任务短就直接用它承接不受控的突发请求。

来源：[SynchronousQueue Java 8 API：零容量与 offer](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/SynchronousQueue.html)、3.2 的线程池 API 及 3.1 的默认构造源码。

### 3.5 调度线程池：按时间执行任务

`Executors.newScheduledThreadPool(corePoolSize)` 支持延迟任务和周期任务，核心线程数由调用方指定。底层使用无界的 `DelayedWorkQueue`；虽然最大线程数参数为 `Integer.MAX_VALUE`，执行任务主要由核心线程数决定，调大最大线程数没有实际扩容效果。默认配置不会因空闲而回收核心线程。

`Executors.newSingleThreadScheduledExecutor()` 使用一个工作线程，适合需要串行执行的轻量级定时工作。长任务会占住唯一的线程，使其他已到期任务等待。

**示例：到期时间优先于提交先后**

1. 创建单线程调度池，先提交 A，设置延迟 10 秒。
2. 紧接着提交 B，设置延迟 1 秒。
3. 如果工作线程可用，B 先到期并执行，A 到期后再执行。
4. 如果线程还在执行其他任务，到期任务只能等待；到期不等于保证立即开始。

同一个周期任务的连续执行不会重叠，但不同任务可以在多线程调度池中并发执行。单线程调度池同样不能保证与外部线程共享的数据天然安全。

来源：[ScheduledThreadPoolExecutor Java 8 API：延迟执行、周期执行与线程数](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledThreadPoolExecutor.html)。示例中的 10 秒、1 秒是说明调度顺序的示例值。

### 3.6 工作窃取池：让空闲线程分担待执行任务

`Executors.newWorkStealingPool(parallelism)` 使用 `ForkJoinPool`。无参版本将目标并行度设为 `Runtime.getRuntime().availableProcessors()`，即 JVM 可用处理器数，不应直接理解成机器的物理核心数。

工作窃取指空闲工作线程从其他工作队列中获取待执行任务，减少部分线程忙、部分线程闲的情况。适合能够拆分为多个计算任务的工作，不保证任务按提交顺序执行，也不保证所有任务都比普通线程池更快。

**线程数边界**

目标并行度不是严格的总线程数上限；池可能为部分阻塞或等待补充线程。Java 8 的实现线程数上限为 32767，但这不是建议配置值。不要套用 `ThreadPoolExecutor` 的核心线程数、最大线程数配置方式。

**示例：分担计算任务**

1. 创建目标并行度为 2 的工作窃取池，2 是示例值。
2. 一个计算工作被拆成多个独立子任务，其中一条工作线程的队列还有待执行任务。
3. 另一条线程完成手头工作后，可以获取待执行任务继续计算。
4. 子任务的完成顺序不固定，需要结果时再进行汇总。

普通数据库或网络阻塞不一定能被池自动识别并补偿，因此不应只凭“工作窃取”就选择它执行大量阻塞 I/O。

来源：[ForkJoinPool Java 8 API：工作窃取、目标并行度和实现限制](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ForkJoinPool.html)；工厂默认参数见 3.1 的源码。

### 3.7 从类型认识过渡到项目选型

先判断任务是否需要串行、定时或并行计算，再确定线程数、排队容量和拒绝处理。工厂方法提供的是默认组合，不代表默认组合适合当前项目。

例如提交速度长期高于处理速度时，固定池会堆积任务，缓存池会倾向于增加线程。历史迁移需要显式控制数据库压力，具体选择与有界队列方案由 [线程池类型选择](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/023_不停机数据迁移_在线历史迁移_线程池设计_线程池类型选择.md:29>) 展开，本篇不将示例参数写成项目实际配置。

## 4 已确认规则

- 面试口述中逐项说明线程池的适用场景，并保留提交规模、任务耗时或串行要求等必要条件。

- 口述中说明各线程池的队列类型及容量；工作窃取池单独说明其多队列机制和容量配置边界。

- 口述中每个工厂方法先用中文说明创建的线程池类型，再说明返回对象和实现关系。

- 口述版逐项说明所创建线程池与 ThreadPoolExecutor 的关系，区分直接创建、包装、继承和独立实现。
- 使用 `java-interview-doc-review-loop` 对本文进行连续整理与复核。
- 涉及线程池时说明线程数或取值规则，并区分默认参数、示例值和项目实际配置。
- 面试口述应能脱离其他文档编号独立理解。
- 面试口述优先展示工厂方法的声明返回接口类型、参数和使用特点。
- 保留口述版现有主题结构；口述说明与 ThreadPoolExecutor 的必要关系，包装类全名等细节放入详细方案，区分声明返回类型与实际对象类型。

## 5 待确认或待验证事项

- 本次按 Java 8 行为复核说明，未联网重新核验 API 与 OpenJDK 源码；来源链接保留供查阅。未执行 Java 示例或性能测试，不能据此认定项目运行效果。
- 本地链接完成静态定位检查，未在 Codex 阅读器中实际点击验证。

## 6 异议与分析

**拒绝不只发生在队列满时**

固定池关闭后也会拒绝新任务，不能将拒绝条件限定为队列已满，详见 3.2。

**单线程调度不保证按提交顺序执行，也不自动解决所有线程安全问题**

定时任务先看执行时间是否到达；串行执行只约束该执行器内部，不能约束外部访问线程。已在 3.3、3.5 分别说明。

**默认容量大不等于没有上限**

默认整数上限与实际资源限制是不同概念，目标并行度也不是严格的总线程数上限，依据见 3.2、3.4、3.6。

**工厂方法并非一律不适合生产**

团队编码规范与 Java API 能力应分开说明。本篇介绍工厂方法的默认配置及资源风险，不将编码规范表述成 Java 本身禁止调用。

## 7 复核记录

2026-11-12 22:28：已执行 [$java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。
