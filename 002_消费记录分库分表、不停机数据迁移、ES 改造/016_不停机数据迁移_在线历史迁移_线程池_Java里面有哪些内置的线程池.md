# 016_不停机数据迁移_在线历史迁移_线程池_Java里面有哪些内置的线程池

## 1 问题

Java 8 里面有哪些常见的内置线程池？

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准，使用 Java 8。示例用于解释机制，不代表生产配置或测试结果。

## 2 面试口述精炼版本

口述内容字符数：245

**常见工厂方法**

Executors 提供固定线程池、单线程执行器、缓存线程池、定时线程池和单线程定时执行器；Java 8 还提供工作窃取线程池。

**按特点区分**

固定池控制线程数，但默认队列无界；单线程顺序执行；缓存池按需扩张；定时池执行延迟或周期任务；工作窃取池利用多个工作队列分摊任务，不保证提交顺序。

**实现与配置**

ThreadPoolExecutor 是可配置的实现类，ScheduledThreadPoolExecutor 支持调度，ForkJoinPool 支撑工作窃取。类型名称不能代替资源上限和队列配置。

## 3 详细方案

### 3.1 常见类型与示例参数

以下均为 Java 8 API 配置示例，不是项目生产值。

| 工厂方法 | 线程或并行度示例 | 主要特点 |
| --- | --- | --- |
| newFixedThreadPool(2) | 固定 2 个线程 | 共享无界 LinkedBlockingQueue，积压需关注 |
| newSingleThreadExecutor() | 1 个线程 | 任务顺序执行，默认无界队列 |
| newCachedThreadPool() | 核心 0，最大 Integer.MAX_VALUE | SynchronousQueue 不存储任务；空闲线程默认 60 秒回收，缺少实用的线程上限保护 |
| newScheduledThreadPool(2) | 核心 2 | 延迟或周期任务，无界延迟队列；不能靠调大最大线程数提高并发 |
| newSingleThreadScheduledExecutor() | 1 个线程 | 单线程延迟或周期调度 |
| newWorkStealingPool(2) | 目标并行度 2 | 使用 ForkJoinPool，工作窃取；目标并行度不是数据库访问的严格上限 |

### 3.2 示例：不同工作交给不同执行器

1. 要顺序处理不能并发的任务，可考虑单线程执行器；仍需限制积压。

2. 要定时触发扫描，可用定时执行器；耗时迁移交给独立工作池，避免触发线程被长任务占用。

3. 要严格控制迁移执行数与等待量，可显式构造 ThreadPoolExecutor，配置固定 2 线程、有界队列 2 和拒绝反馈；这里只是机制示例。

### 3.3 依据与分工

[Executors Java 8 API](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Executors.html)、[ThreadPoolExecutor API](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadPoolExecutor.html)、[ForkJoinPool API](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ForkJoinPool.html) 为 API 查阅入口；本次未联网复核。项目为什么选择显式构造，见 [019 线程池类型选择](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/019_不停机数据迁移_在线历史迁移_线程池设计_线程池类型选择.md>)。

## 4 已确认规则

- 本篇作为通用线程池文档，独立回答“Java 8 里面有哪些常见的内置线程池？”。

## 5 待确认或待验证事项

- 示例未运行，实际项目配置和性能效果需验证。

## 6 复核记录

2026-11-12 10:37：已执行 [java-interview-doc-review](C:/Users/Administrator/.codex/skills/java-interview-doc-review/SKILL.md)。
