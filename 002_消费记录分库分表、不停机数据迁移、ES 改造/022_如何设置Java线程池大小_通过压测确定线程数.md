# 022_如何设置Java线程池大小_通过压测确定线程数

## 1 说明

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。适用于 Java 8 平台线程。 [上一级问题](</D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/020_如何设置Java线程池大小.md:1>)

## 2 问题

算出起始线程数后，怎么判断应该增加线程，还是已经够用？

### 2.1 考点

1. 判断下游连接数是否限制有效并发。
2. 用相同负载下的完成量、积压和响应时间选择线程数。

### 2.2 答题范围

用相同的高峰负载比较三档线程数，再说明怎样逐档加压找容量拐点，以及什么条件下可以在线调整线程数。

### 2.3 候选理由

主问题给出压测起点，这道追问考查候选人能否从实测指标判断继续加线程是否有收益。公开备考资料中的“动态调参能解决什么问题？”同样要求根据完成速率、CPU 和下游指标验证调参效果。[JavaGuide《Java 线程池最佳实践》](https://github.com/Snailclimb/JavaGuide/blob/main/docs/java/concurrent/java-thread-pool-best-practices.md)

## 3 面试口述版本

口述内容字符数：238（不计参考链接）

我先按生产高峰单实例的请求比例、到达速率和持续时间构造负载，核对实际提交给池的任务数。压测环境的 CPU 和连接配额贴近生产，每轮只改线程数。[Grafana k6 · Constant arrival rate，每次迭代发一个请求即可固定请求到达速率](<https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/>)

假设每秒发 300 个请求，要求至少 95% 在 100 毫秒内返回。8 个线程每秒只完成 260 个，队列持续增长。12 和 16 个都每秒完成 300 个，队列稳定，第 95 百分位耗时分别为 80 和 95 毫秒。假设均无错误或拒绝，我选已达标的 12 个。[Grafana k6 · Thresholds，用第 95 百分位响应时间判断是否达标](<https://grafana.com/docs/k6/latest/using-k6/thresholds/>)

要找容量拐点，我会对每档线程数逐级提高到达速率。若成功吞吐量趋平，排队时间、慢请求耗时或拒绝数开始上升，就检查 CPU 和下游连接。连接用满时，加线程可能只会加长等待。[k6 分阶段调整到达速率](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/ramping-arrival-rate/) · [Zalando · 线程数公式与数据库连接池限制](<https://engineering.zalando.com/posts/2019/04/how-to-set-an-ideal-thread-pool-size.html>)

## 4 详细方案

同一输入负载下，两档都完成每秒 300 个请求，只能判断它们都接住了这档流量。要测余量，对每档线程数施加同一组递增的到达速率，每一级保持足够时间，记录实际发出量、成功完成量、排队时间、响应时间和拒绝数。先确认压测端能发出目标流量，再比较每档线程数能持续满足业务目标的最高负载。若加线程后成功吞吐量几乎不增、尾延迟反而变差，可取拐点前满足目标的较小档位。[k6 分阶段调整到达速率](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/ramping-arrival-rate/)

高低峰差异明显、下游仍有余量且压测证明扩容有收益时，可以用配置中心在线调整核心数和最大线程数。调高前核对连接上限，调后观察队列、拒绝和下游延迟，恶化则恢复原配置。低峰回调需要人工或自动策略发布配置，超出新上限的工作线程空闲后才退出。DynamicTp 是 Dromara 社区的可选方案，支持 Nacos 等配置中心、指标监控和告警。项目接入情况待确认。[DynamicTp 官方仓库](https://github.com/dromara/dynamic-tp) · [Java 8 ThreadPoolExecutor](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadPoolExecutor.html)

## 5 已确认规则

1. 面试口述中的技术结论附上可直接核对的参考来源。
2. 整理线程池相关面试问题时，参考同一目录下的《[ref_ Java 线程池核心线程数的配置.md](<ref_ Java 线程池核心线程数的配置.md>)》。

## 6 整理进度

记录时间：2026-11-26 23:17

最终状态：已完成
