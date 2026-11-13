# 003_创建 ThreadPoolExecutor 有哪些核心参数

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准，使用 Java 8。

## 1 问题

创建 ThreadPoolExecutor 有哪些核心参数？

## 2 面试口述精炼版本

口述内容字符数：565

**ThreadPoolExecutor 是什么**

`ThreadPoolExecutor` 是 Java 提供的线程池实现类，实现了 `ExecutorService` 接口，负责按需创建工作线程、复用线程执行任务，并根据核心线程数、最大线程数和空闲超时配置控制线程数量与回收，同时处理任务排队和拒绝。下面将七个参数分成五组说明。

**线程数量**

corePoolSize 是核心线程数，maximumPoolSize 是池内允许的最大线程数。默认在提交任务时按需创建线程：线程数未达到核心线程数时，优先创建线程；达到核心线程数后，优先将任务放入队列；队列满了，再尝试增加线程，但不超过最大线程数；仍无法接收任务时，执行拒绝策略。

**空闲回收**

keepAliveTime 是空闲等待时长，unit 是时间单位，默认用于回收超过核心数量的空闲线程，不是任务执行超时。

**任务排队**

workQueue 保存等待执行的任务，队列类型和容量会影响排队与线程扩容。

**线程工厂：创建工作线程**

threadFactory 是线程工厂。线程池需要新增工作线程时，通过它创建线程对象。可以在工厂中统一设置线程名称等属性，方便识别线程和排查问题。

**拒绝策略：处理无法接收的任务**

handler 是拒绝策略。当线程池已关闭，或者队列无法接收任务且无法继续新增工作线程时，由它决定如何处理新提交的任务，例如抛出异常。

[详细说明](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/016_不停机数据迁移_在线历史迁移_线程池_创建 ThreadPoolExecutor 有哪些核心参数.md:39>)

## 3 详细方案

### 3.1 完整构造方法：七个参数各管一件事

ThreadPoolExecutor 的完整构造方法包含七个参数，分别控制线程数量、空闲回收、任务排队、线程创建和拒绝处理。下面是构造方法签名；构造方法没有返回类型。

```java
public ThreadPoolExecutor(
        int corePoolSize,                 // 核心线程数
        int maximumPoolSize,              // 最大线程数
        long keepAliveTime,               // 空闲等待时长
        TimeUnit unit,                    // 时长的单位
        BlockingQueue<Runnable> workQueue, // 等待任务的队列
        ThreadFactory threadFactory,      // 创建工作线程的工厂
        RejectedExecutionHandler handler  // 拒绝处理策略
)
```

还有参数更少的构造方法，省略线程工厂或拒绝策略时，分别使用默认线程工厂和 `AbortPolicy`。

来源：[OpenJDK 8 ThreadPoolExecutor 源码](https://github.com/openjdk/jdk8u/blob/master/jdk/src/share/classes/java/util/concurrent/ThreadPoolExecutor.java)，构造方法及参数检查见该源码；行为说明参见 [ThreadPoolExecutor Java 8 API](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadPoolExecutor.html)。

### 3.2 corePoolSize 与 maximumPoolSize：控制线程数量

**corePoolSize：核心线程数**

提交任务时，当前工作线程数不足核心数，就尝试创建线程执行任务。默认不会在构造时一次性建好全部核心线程，而是随任务提交按需创建；也可以主动预启动。默认情况下，核心数量以内的线程不会仅因空闲而退出。

例如核心数为 2 时，刚创建线程池的工作线程数可以为 0。因此，“核心线程数就是最小线程数”不够准确。

**maximumPoolSize：最大线程数**

它限制池内工作线程的数量。达到核心数后通常先入队，只有入队失败时，才尝试在最大数以内增加线程。最大数为 4，不代表提交第 3 个任务就一定创建第 3 条线程，还要看队列能否接收任务。

### 3.3 keepAliveTime 与 unit：控制空闲线程回收

`keepAliveTime` 与 `unit` 合在一起才是完整的时长，例如 `60L` 和 `TimeUnit.SECONDS` 表示 60 秒。

默认只对超过核心数量的空闲线程应用这个超时。若开启 `allowCoreThreadTimeOut(true)`，核心线程也可以因空闲超时退出，此时空闲时长必须大于 0。

例如核心数 2、最大数 4、空闲时长 60 秒，线程池扩容后又持续空闲，默认可以回落到 2 条线程。这里的 2、4 和 60 秒都是教学示例，不是生产值。正在执行任务的线程不会因为任务运行超过 60 秒就被该参数终止。

### 3.4 workQueue：保存待执行任务，并影响扩容

队列存放的是等待执行的任务对象，不是线程。选队列时既要看类型，也要看容量。

- `ArrayBlockingQueue<>(2)`：有界队列，最多排队 2 个任务；2 是示例容量。
- `LinkedBlockingQueue<>(2)`：也可以显式设置容量；无参构造的容量是 `Integer.MAX_VALUE`，通常视为无界队列。
- `SynchronousQueue`：不存储排队任务，容量为 0，需要直接交接给等待任务的线程。

有界队列满后，线程池才尝试扩容；使用通常不会满的无界队列时，任务容易积压，最大线程数通常无法发挥扩容作用。使用 `SynchronousQueue` 时，交接失败就会尝试增加线程。

### 3.5 threadFactory：决定工作线程怎样创建

线程工厂负责创建工作线程，可以统一设置名称、是否为守护线程及未捕获异常处理器等属性；它不负责决定池里需要创建多少条线程。

例如将线程命名为 `pool-demo-1`、`pool-demo-2`，出现异常或查看线程堆栈时，就能辨认任务所属的线程池。

### 3.6 handler：决定拒绝任务时怎样处理

线程池关闭，或队列无法接收且无法继续增加工作线程时，会调用拒绝策略。Java 8 提供四种常用策略：

- `AbortPolicy`：抛出 `RejectedExecutionException`，这是默认策略。
- `CallerRunsPolicy`：线程池未关闭时，由提交任务的线程直接执行；已经关闭则丢弃该任务。
- `DiscardPolicy`：直接丢弃被拒绝的任务，不抛拒绝异常。
- `DiscardOldestPolicy`：线程池未关闭时，移除队头等待任务，再尝试提交当前任务；已经关闭则丢弃当前任务。

例如池内 4 条线程都忙、队列也满时，使用 `AbortPolicy` 会让提交方收到异常；使用 `CallerRunsPolicy` 则可能让提交线程也参与执行。因此，最大线程数限制的是池内工作线程，不能据此认定整个系统最多只有 4 个任务执行者。

### 3.7 用一个例子串起七个参数

以下使用核心数 2、最大数 4、队列容量 2、空闲时长 60 秒，全部为教学示例。为展示扩容，核心数与最大数刻意不同，不替代迁移方案的固定线程数配置。

```java
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// 默认工厂先创建线程，再统一设置便于排查问题的名称。
AtomicInteger sequence = new AtomicInteger();
ThreadFactory baseFactory = Executors.defaultThreadFactory();
ThreadFactory namedFactory = task -> {
    Thread thread = baseFactory.newThread(task);
    thread.setName("pool-demo-" + sequence.incrementAndGet());
    return thread;
};

ThreadPoolExecutor executor = new ThreadPoolExecutor(
        2, 4,
        60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(2),
        namedFactory,
        new ThreadPoolExecutor.AbortPolicy());

try {
    executor.execute(() -> System.out.println(
            Thread.currentThread().getName()));
} finally {
    executor.shutdown(); // 停止接收新任务，已接收任务继续执行。
}
```

**任务逐个提交时会发生什么**

下面是独立的时序说明，不是上面单任务代码的运行结果。假设线程池正常运行、线程创建成功，而且前面的任务一直未完成：

1. 提交 A、B：创建两条工作线程，各执行一个任务。
2. 提交 C、D：核心数已经达到 2，这两个任务进入容量为 2 的队列。
3. 提交 E、F：队列已满，再创建两条线程分别执行 E、F，池内线程数达到 4。
4. 提交 G：队列满且线程数已达上限，`AbortPolicy` 抛出异常。
5. 任务全部处理完后，超过核心数量的线程空闲达到 60 秒，可被回收。

E、F 可能先于排队的 C、D 执行，所以不能把线程池理解成严格按提交顺序执行所有任务。

### 3.8 参数合法性与取值边界

构造时必须满足：`corePoolSize >= 0`、`maximumPoolSize > 0`、`maximumPoolSize >= corePoolSize`、`keepAliveTime >= 0`；时间单位、队列、线程工厂和拒绝策略不能为 `null`。

本篇回答参数是什么、各自控制什么。真实线程数、队列容量和回收时长应结合任务类型、资源预算与压测确定，不能把截图中的“CPU 核数 + 1”或“30—300 秒”直接当成通用标准。

## 4 已确认规则

- 口述中分别说明线程工厂的创建职责，以及拒绝策略的触发条件和处理职责。

- 口述中说明任务提交时创建线程、排队、扩容与拒绝的先后关系，帮助理解核心线程数和最大线程数。
- 口述中将管理工作线程具体说明为按需创建、复用、数量控制和空闲回收。
- 本文直接围绕“创建 ThreadPoolExecutor 有哪些核心参数？”展开，不再解释为什么选择它作为例子。
- 口述版先介绍 ThreadPoolExecutor 的作用，再说明七个核心参数。
- 将用户提供图片中的七参数内容整理进文档，并核对技术表述。
- 口述版能独立理解；参数示例与生产配置分开说明。
## 5 待确认或待验证事项

- 示例尚未编译运行，也未做性能测试；不能据此确定项目参数或性能效果。
- 文档链接只做静态定位检查，未验证 Codex 阅读器的实际点击效果。

## 6 异议与分析

**核心线程数不等于创建时已经存在的最少线程数**

截图将 corePoolSize 解释为“最小的线程池”，容易让人误以为构造后就有这些线程。默认核心线程按需创建；开启核心线程超时后，也可以因空闲而退出。

**先排队，再尝试扩容**

截图中的示意图容易被理解成先用满最大线程数再排队。实际应结合正文流程理解：达到核心数后先尝试入队，入队失败才尝试扩容。不是所有任务都会依次经历每个步骤。

## 7 复核记录

2026-11-12 20:28：已执行 [$java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。
