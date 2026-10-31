# `nextId()` 实现逻辑

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

**职责：只生成基础雪花 ID**

`nextId()` 返回基础 `snowflakeId`，不处理商户号或分片路由。外层根据既定路由计算 `shardIndex`，拼接固定 2 位后缀，最终 `consumeId` 按字符串保存。本文是 Java 8 核心示例，不代表生产生成器已接入。

## 1. 位布局与容量

Java `long` 是 64 位有符号整数，本示例保持最高位为 0：

```text
[1 位符号位][41 位相对时间][10 位 workerId][12 位 sequence]
第 62～22 位：相对 epochMillis 的毫秒差
第 21～12 位：workerId，范围 0～1023
第 11～0 位：sequence，范围 0～4095
```

41 位毫秒差约可覆盖 69.7 年，同一节点每毫秒最多分配 4096 个序列值。该数值是编码容量，不是实测吞吐。

## 2. 初始化与唯一性前提

**统一起点，隔离节点编号**

所有节点使用一致且固定的 `epochMillis`，同一生成体系内同时运行的节点不能复用 `workerId`。同一 JVM 本示例只创建 1 个生成器，多业务共享时也共享该节点的序列容量。

**重启后的时间重叠仍需处理**

线程锁只保护当前对象，不能解决不同进程使用相同节点编号，或重启后时间回到此前已使用区间的问题。节点编号分配、回收和重启保护策略未提供，保留待确认。

## 3. 按时间分配序列，再组合 ID

1. **读取时间**：获取当前毫秒，检查是否早于上次成功生成的时间。
2. **分配序列**：新毫秒从 0 开始；同毫秒序列递增。
3. **序列耗尽**：达到 4095 后等待下一毫秒；等待中若发现回拨，抛出异常。
4. **检查时间范围**：毫秒差必须在 `0～2^41-1`，左移不会自动限制位宽。
5. **提交状态并拼接**：校验通过才保存时间和序列，再组合 3 个不重叠位段。

## 4. Java 8 核心代码

**完整类：先验证，再更新生成器状态**

示例采用遇到回拨直接抛异常的策略；调用端告警和业务响应未在此实现。序列耗尽时使用短暂休眠等待，休眠可能超过 1 毫秒，因此不承诺峰值吞吐。

```java
public final class ConsumeIdGenerator {
    private static final int WORKER_ID_SHIFT = 12;
    private static final int TIMESTAMP_SHIFT = 22;
    private static final long MAX_WORKER_ID = (1L << 10) - 1;
    private static final long SEQUENCE_MASK = (1L << 12) - 1;
    private static final long MAX_TIMESTAMP = (1L << 41) - 1;

    private final long epochMillis;
    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public ConsumeIdGenerator(long epochMillis, long workerId) {
        if (epochMillis < 0 || epochMillis > System.currentTimeMillis()) {
            throw new IllegalArgumentException("epochMillis 必须是已到达的非负时间");
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 0～1023 之间");
        }
        this.epochMillis = epochMillis;
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        checkClock(now);
        long nextSequence = 0L;

        if (now == lastTimestamp) {
            nextSequence = (sequence + 1) & SEQUENCE_MASK;
            if (nextSequence == 0L) {
                now = waitNextMillis();
            }
        }

        long timestampPart = now - epochMillis;
        if (timestampPart < 0 || timestampPart > MAX_TIMESTAMP) {
            throw new IllegalStateException("timestampPart 超出 41 位范围");
        }

        // 校验通过才提交状态，异常不会提前消耗或重置序列。
        lastTimestamp = now;
        sequence = nextSequence;
        return (timestampPart << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private void checkClock(long now) {
        if (now < lastTimestamp) {
            throw new IllegalStateException("系统时间回拨，停止生成 ID");
        }
    }

    private long waitNextMillis() {
        while (true) {
            long now = System.currentTimeMillis();
            checkClock(now);
            if (now > lastTimestamp) {
                return now;
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待下一毫秒时被中断", e);
            }
        }
    }
}
```

## 5. 用数值检查位运算

**掩码：保留低 12 位**

`SEQUENCE_MASK = 4095`，所以 `4095 + 1 = 4096`，而 `4096 & 4095 = 0`。这时序列已耗尽，必须等到新毫秒，不能继续复用本毫秒的 0。

**左移：给低位字段预留空间**

`timestampPart << 22` 为节点号和序列预留 22 位，`workerId << 12` 为序列预留 12 位。例如 `3 << 2` 相当于将二进制 `0011` 移为 `1100`。按位或 `|` 合并互不重叠的位段。

假定当前时间比起点晚 100 毫秒，节点号为 3，序列为 7：

```text
(100L << 22) | (3L << 12) | 7L
= 419430400 + 12288 + 7
= 419442695
```

使用 `long` 运算，避免把较大的实际时间差当作 `int` 左移。

## 6. 基础 ID 与分片后缀分工

**路由必须沿用现有算法**

商户哈希、取模和库表映射必须使用迁移、写入、查询共用的规则。原材料中的 `Math.floorMod(merchantNo.hashCode(), 32)` 只能作为示意，当前没有依据证明它就是项目实现，因此不能用它替换既有路由。

**固定后缀不等于已确认基础 ID 长度**

已确认的是固定 2 位分片后缀和字符串存储。若基础 ID 采用补齐 19 位的格式，下例为 21 位；是否补零及数据库字段长度仍需核对，不能把示例当成额外业务约定：

```text
基础 ID：0000000000419442695
分片号：07
consumeId：000000000041944269507
```

## 7. 验证边界

本次已提取完整类，使用本地 JDK 8（1.8.0_151）编译通过；未进行多进程、时钟故障或负载测试。代码只覆盖单生成器的线程串行化、位范围、回拨检测、序列耗尽等待和中断处理。分布式节点分配、重启保护、分片映射和生产性能不在示例验证范围内。最终接入仍按[主键与数据模型](<D:/000_ 找工作_2026/sources/E账通/002_消费记录分库分表、双写与ES 改造（15）/003_分库分表.md:44>)执行。



## 问题列表

- 文中存在指向旧工作区路径的引用，当前路径下无法直接打开；需改为当前目录的相对链接或确认外部资料是否应纳入项目。

