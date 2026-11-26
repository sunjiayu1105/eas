# 021_如何设置Java线程池大小_估算可用CPU核数

## 1 说明

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。适用于 Java 8 平台线程。 [上一级问题](</D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/020_如何设置Java线程池大小.md:1>)

## 2 问题

你刚才按 4 核估算，怎么知道这个池能用多少核？

### 2.1 考点

1. 根据高峰期的 CPU 用量估算能分给线程池的算力。

### 2.2 答题范围

说明测量对象、观察窗口、平均占用核数的算法和预留余量的依据。

### 2.3 候选理由

主问题以 4 核为例，这道追问考查候选人怎样从实际负载估出公式的输入值。

## 3 面试口述版本

口述内容字符数：300（不计参考链接）

以 Kubernetes 为例，我先查容器的 `resources.limits.cpu`，看 CPU 上限。没设限额时，再核实实例可用的算力。Prometheus 采集容器累计 CPU 时间，我用 Grafana 查看同一容器在几个生产高峰的用量曲线。[Kubernetes 容器资源配置](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/) · [cAdvisor 容器 CPU 指标](https://github.com/google/cadvisor/blob/master/docs/storage/prometheus.md)

平均占用核数 ≈ CPU 时间增量 ÷ 观察时长。用 Prometheus 的 `rate` 查 `container_cpu_usage_seconds_total` 就能得到这个速率。如果线程池已在运行，先扣除池内线程同期的 CPU 用量。假设其他业务在 10 秒内用了 30 秒 CPU 时间，平均就占 3 核。[Prometheus rate 函数](https://prometheus.io/docs/prometheus/latest/querying/functions/#rate)

线程池可用核数 ≈ 实例可用核数 − 高峰期其他业务占用核数 − 预留核数。假设可用 8 核，根据高峰波动预留 1 核，就从 8 − 3 − 1 = 4 核估算，再用高峰负载压测，核对原有业务的响应时间。

## 4 已确认规则

1. 面试口述中的技术结论附上可直接核对的参考来源。
2. 先说明估算公式，再代入示例数值。

## 5 整理进度

记录时间：2026-11-26 23:17

最终状态：已完成
