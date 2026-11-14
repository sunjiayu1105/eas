# 014_不停机数据迁移_在线历史迁移_批次状态控制_启动互斥与初始化事务_SELECT_FOR_UPDATE如何加锁与释放锁

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。本文说明 MySQL 8.0.33 的 InnoDB 锁定读取机制；SQL 为教学示例，表名、字段名和状态值仅作示意，未执行数据库并发实验。

直接主文档：[启动互斥与初始化事务](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/013_不停机数据迁移_在线历史迁移_批次状态控制_启动互斥与初始化事务.md>)。

## 1 问题

SELECT ... FOR UPDATE 有什么作用，怎样在批次启动时正确使用？

## 2 高级 Java 面试核心考点

- 锁定读取与事务边界：读取记录时加排他锁，检查和更新必须在同一事务中。
- 并发访问与锁范围：区分锁定读取和快照读取，理解索引与查询条件对锁范围的影响。
- 锁释放与业务状态配合：区分事务结束和语句失败，理解释放行锁后如何阻止重复启动。

## 3 面试口述精炼版本

口述内容字符数：525

**先锁住记录，再检查和更新**

SELECT ... FOR UPDATE 是锁定读取。在 InnoDB 中，按主键查到已有批次记录后，会对记录加排他锁，也就是阻止其他事务同时修改或取得冲突的锁。在同一事务、同一连接中，先通过 SELECT ... FOR UPDATE 查询并锁住批次记录，再由应用代码判断批次状态是否允许启动，允许时才更新状态；锁随事务提交或完整回滚释放。查询完就结束事务，保护不了后面的更新。

详见：[事务内的锁定读取（第 41 行）](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/014_不停机数据迁移_在线历史迁移_批次状态控制_启动互斥与初始化事务_SELECT_FOR_UPDATE如何加锁与释放锁.md:41>)。

**区分会等待的操作和普通查询**

其他事务对同一记录执行 FOR UPDATE、更新或删除，默认会等待锁释放；读已提交、可重复读隔离级别下，普通快照查询通常仍可读取已提交版本。锁范围取决于索引、条件和隔离级别，不能把所有 FOR UPDATE 都理解成只锁一行。

详见：[哪些操作等待，以及锁住多大范围（第 83 行）](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/014_不停机数据迁移_在线历史迁移_批次状态控制_启动互斥与初始化事务_SELECT_FOR_UPDATE如何加锁与释放锁.md:83>)。

**锁和状态一起防止重复启动**

请求 A 加锁检查后，把批次改为运行中并提交；请求 B 随后拿到锁，读到运行中就退出，所以不必持锁到迁移结束。锁等待超时通常只回滚当前语句，应用应结束整个启动事务；连接异常时先核实数据库是否已收尾，不能把客户端报错当成锁已释放。

详见：[正常结束与异常情况下如何释放锁（第 97 行）](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/014_不停机数据迁移_在线历史迁移_批次状态控制_启动互斥与初始化事务_SELECT_FOR_UPDATE如何加锁与释放锁.md:97>)、[为什么提交后释放锁仍能防重（第 112 行）](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/014_不停机数据迁移_在线历史迁移_批次状态控制_启动互斥与初始化事务_SELECT_FOR_UPDATE如何加锁与释放锁.md:112>)。

## 4 详细方案

### 4.1 事务内的锁定读取

`FOR UPDATE` 不会修改数据，而是在读取时申请排他锁，为后续检查和更新提供互斥保护。锁定读取读取当前记录；如果记录正被其他未结束的事务修改，需要等待冲突锁释放，再读取并判断结果，不能拿先前普通查询的快照结果替代这次状态检查。

下面只演示已暂停批次的状态检查和更新。前提是上次执行已安全收尾、异常已修复，批次控制记录已存在且 `id` 是主键；完整启动校验和首次初始化由主文档负责。状态更新与完整任务清单必须一起提交或回滚，原因及失败示例见 [为什么状态更新与任务初始化必须在同一事务中](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/015_不停机数据迁移_在线历史迁移_批次状态控制_启动互斥与初始化事务_为什么状态更新与任务初始化必须在同一事务中.md>)。

```sql
START TRANSACTION;

SELECT status
FROM migration_batch
WHERE id = 1
FOR UPDATE;

-- 应用读取结果：无记录或状态不允许启动时，ROLLBACK 并返回。
-- 仅在查询结果为 PAUSED，且其他启动检查通过时，继续执行：
UPDATE migration_batch
SET status = 'RUNNING'
WHERE id = 1 AND status = 'PAUSED';

-- 应用核对更新行数为 1；否则 ROLLBACK 并返回失败。
COMMIT;
```

示例中的 `id = 1` 是教学数据，`PAUSED` 和 `RUNNING` 分别示意已暂停和运行中。应用必须执行注释中的分支，不能不检查结果就连续执行全部 SQL。确认提交成功后才能提交迁移任务；提交结果未知时先核实，不贸然启动。

**行锁与条件更新的分工**

当前示例主要由悲观锁保护：前面的 `SELECT ... FOR UPDATE` 已在同一事务中锁住批次记录，其他事务修改这条记录时需要等待，因此读取状态、检查状态和更新状态不会被其他事务的冲突修改穿插。不能仅因 UPDATE 带有状态条件，就把整套方案称为乐观锁方案。

`AND status = 'PAUSED'` 是额外的状态校验：只有记录当前仍为已暂停，才允许改为运行中，并检查影响行数是否为 `1`。在前面的锁定检查正确执行、事务未结束的情况下，其他事务不能在检查和更新之间把这条记录改走；这个条件主要明确允许的状态转换，并配合影响行数检查防止误更新。

如果去掉前面的锁定查询，直接执行这条条件 UPDATE，再根据影响行数判断是否取得启动资格，可以将其理解为基于状态的 CAS（比较并更新）或乐观并发控制。例如两个请求都尝试把已暂停改为运行中，先成功更新并提交的请求影响行数为 `1`，在状态没有再次恢复为已暂停的前提下，后一个请求重新检查条件时已不满足，影响行数为 `0`；只有更新成功且确认事务提交后才能继续提交迁移任务。这里的 UPDATE 在 InnoDB 内部仍会加锁，不是完全无锁。

条件更新只保证这次状态转换的原子性，不能自动保证任务清单完整，也不能替代其他启动校验。首次初始化若要采用这种方式，任务清单、扫描边界和状态更新仍须在同一事务中完成，并重新设计和核验执行顺序；本项目继续沿用前述行锁方案，不在这里切换实现。

锁定查询、检查和更新必须处于同一数据库事务、使用同一连接。可以显式开启事务，也可以由应用事务管理器管理；不能在自动提交模式下单独查询后，再另开事务更新。行锁在事务提交或回滚时释放，不能把一次 SELECT 返回当作释放点，也不能靠方法名推断事务已生效。

例如 A、B 都先用普通查询读到已暂停，再各自提交迁移任务，即使各自最后都执行了 UPDATE，也可能重复启动。把读取和检查放进同一个锁定事务，才使这段检查与状态更新串行执行。

依据：[MySQL 8.0 Locking Reads](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html) 中关于锁定读取、事务与锁释放的说明；原站点此前访问返回 HTTP 403，本轮已通过 [Oracle 官方镜像](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/innodb-locking-reads.html) 核验事务内锁定读取与锁释放说明；仍无项目运行证据。

### 4.2 哪些操作等待，以及锁住多大范围

在本方案按主键等值查到已有记录的前提下，重点保护该记录对应的索引记录，不能理解为锁住整张表。InnoDB 的行锁实际作用于索引记录；它还会使用表级意向锁来协调加锁，但这不等于阻塞整张表的所有读写。

- 其他事务对同一记录执行 `SELECT ... FOR UPDATE`、`UPDATE` 或 `DELETE` 时，要取得冲突的锁，默认等待当前事务释放锁。等待期间可能发生锁等待超时或死锁，不能承诺一定获得锁。
- 在读已提交或可重复读隔离级别下，普通非锁定 `SELECT` 通常通过多版本并发控制（MVCC）读取可见的已提交版本，不必等待这把记录锁释放。查询能返回，不代表已经拿到启动资格；仍须经过锁定检查。可串行化隔离级别下不能直接套用普通查询不加锁的结论。
- 主键或完整唯一索引等值命中已有记录，与范围扫描、非唯一索引查询、查询不到记录的情况不同。后几种情况的锁范围要结合隔离级别与实际扫描路径判断；例如可重复读下的范围锁定读取通常可能包含间隙锁，影响范围内的插入。缺少合适索引还可能扩大扫描和加锁范围。

本项目设计只允许按主键锁定预先存在的批次控制记录。查不到记录时拒绝启动，不能误以为“没有返回行就不需要互斥”。生产表的索引、事务隔离级别与执行计划仍需核实。

本例使用默认等待行为，不使用 `NOWAIT` 或 `SKIP LOCKED`；后者会改变冲突时的处理方式，不能把跳过批次控制记录当作允许启动。

依据：[InnoDB Locking](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html)、[Consistent Nonlocking Reads](https://dev.mysql.com/doc/refman/8.0/en/innodb-consistent-read.html)。以上是机制说明，未验证项目的实际锁范围。

### 4.3 正常结束与异常情况下如何释放锁

本方案按主键锁定已有批次记录，在同一事务中完成检查和状态更新。事务提交或完整回滚后，释放持有的记录锁；查询返回、方法返回或客户端超时，都不能单独证明数据库事务已结束。

- **正常提交或完整回滚** ：数据库完成事务处理后释放锁。回滚到保存点不是结束整个事务，不能按完整回滚理解锁的释放。
- **锁等待超时** ：InnoDB 默认通常只回滚当前超时语句，事务此前持有的锁可能仍在。本方案让异常向外传播，由事务管理器结束并回滚整个启动事务，不捕获后继续提交迁移任务。实际行为还需核对 `innodb_rollback_on_timeout` 等配置。
- **死锁** ：InnoDB 会选择一个事务回滚。应用处理异常并结束本次启动，不使用先前查询结果继续迁移；已有设计不自动重试。
- **进程宕机或连接异常** ：数据库确认连接终止后，回滚未提交事务并释放锁；断连检测与回滚可能需要时间。客户端收到异常时，应先核实数据库中的事务和操作是否已经结束，不能立刻恢复迁移。

例如事务先锁住批次记录，随后等待其他锁发生超时，如果应用只捕获异常却不结束事务，批次记录的锁仍可能被占用。因此“某条 SQL 失败”与“整个事务回滚并释放锁”必须分开判断。具体锁等待、事务与数据库请求超时参数仍待确认。

本节针对本方案已锁住的批次记录，不能推广为所有扫描记录的锁都只能在提交时释放；读已提交下，不匹配条件的记录锁可能提前释放。通用机制与账户示例见 [SELECT FOR UPDATE 如何加锁与释放锁](<D:/000/sources/eas/000_MySQL/010_SELECT_FOR_UPDATE如何加锁与释放锁.md>)。

本次已核对 [Oracle 官方镜像的 InnoDB Error Handling](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/innodb-error-handling.html)：锁等待超时默认回滚当前语句，死锁回滚被选中的整个事务。该资料核验不代表项目故障处理已实现或通过实验。

### 4.4 为什么提交后释放锁仍能防重

1. A 开启事务，锁定批次记录，读取到允许启动的状态。
2. B 对同一记录执行锁定读取，因为与 A 的锁冲突而等待。
3. A 完成检查与必要初始化，把批次改为运行中并提交，锁随事务结束释放。
4. B 获得锁后读到运行中，拒绝启动并结束事务，不再提交迁移任务。

这解决了两个请求都通过启动检查的问题。行锁只负责保护本次检查与状态转换；持久化的运行中状态负责拦截后续请求。所有启动入口必须遵守同一协议，不能绕过检查；也不能在旧执行仍未结束时把状态改回已暂停。

如果 A 回滚，B 获得锁后按回滚后的实际状态重新检查，并不保证一定拒绝 B。若运行中状态已经提交，它不会因进程宕机自动恢复；仍按人工故障恢复规则核实后修复。

完整初始化、提交未知与恢复流程见 [启动互斥与初始化事务](<D:/000/sources/eas/002_消费记录分库分表、不停机数据迁移、ES 改造/013_不停机数据迁移_在线历史迁移_批次状态控制_启动互斥与初始化事务.md>)。事务异常与释放边界见 4.3；完整初始化与人工恢复仍以主文档及其关联专题为准。

### 4.5 核心实现代码：事务内检查并更新状态

以下按 Java 8、Spring Boot 2.7.18 展示已暂停批次的恢复入口，不处理首次初始化。前提是运维已确认上次执行安全结束、未知结果和失败任务已修复、原任务清单完整；PAUSED 不能代替这些检查。完整启动实现见直接主文档。

构造器让 JDBC 和事务管理器使用同一 DataSource；Spring 将事务连接绑定到当前线程，JdbcTemplate 在回调内复用该连接。以下是教学实现，不表示项目已经采用这套接线。

```java
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class PausedBatchStarter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Dispatcher dispatcher;

    public PausedBatchStarter(DataSource dataSource, Dispatcher dispatcher) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.dispatcher = java.util.Objects.requireNonNull(dispatcher);
    }

    public void resume(long batchId) {
        // 禁止加入外层事务，否则 execute 返回时可能尚未真正提交。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("恢复入口不能处于外层事务中");
        }
        tx.execute(transaction -> {
            // 第一步：查询并加锁，不是普通快照查询。
            List<String> states = jdbc.query(
                    "SELECT status FROM migration_batch WHERE id = ? FOR UPDATE",
                    (rs, rowNum) -> rs.getString(1), batchId);
            if (states.size() != 1) {
                throw new IllegalStateException("批次控制记录必须预先存在");
            }
            // 第二步：应用代码检查状态，此时事务尚未结束，锁仍持有。
            if (!"PAUSED".equals(states.get(0))) {
                throw new IllegalStateException("仅允许恢复已安全暂停的批次");
            }
            // 第三步：沿用原任务清单和进度，只改变批次状态。
            int changed = jdbc.update(
                    "UPDATE migration_batch SET status = 'RUNNING' "
                            + "WHERE id = ? AND status = 'PAUSED'", batchId);
            if (changed != 1) {
                throw new IllegalStateException("批次状态更新失败");
            }
            return null;
        }); // 回调正常完成后提交；运行时异常向外传播并触发回滚。

        // execute 正常返回才确认提交；提交异常时不会执行本行。
        dispatcher.dispatchCommittedBatch(batchId);
    }

    public interface Dispatcher {
        void dispatchCommittedBatch(long batchId);
    }
}
```

**检查状态发生在哪里**

`jdbc.query` 查询并锁住记录；`if (!"PAUSED".equals(...))` 才是应用侧状态检查；`jdbc.update` 执行状态更新。三个步骤在同一线程、同一事务连接中执行。状态不允许时抛出异常，回滚并释放锁；不能捕获后继续调度，也不能把查询或更新移到异步线程。

**提交后交接的边界**

Dispatcher 是业务接口，需接入主文档约定的调度器，复查停止信号并处理提交失败及安全收尾，不是空实现。提交响应丢失时不自动重试，应告警并核实实际结果；状态已提交但调度前宕机时，按人工恢复流程处理，不能直接改回已暂停。本示例没有实现首次初始化、告警及调度器，也未验证实际 DataSource 接线、事务超时和数据库并发行为。

## 5 已确认规则

- 作为启动互斥与初始化事务的直接分文档，专门解释 SELECT ... FOR UPDATE。
- 沿用已有方案：使用数据库行锁与批次状态，不增加 Redis 锁。
- 明确行锁、条件更新与乐观并发控制的区别，保留现有 SQL 和行锁方案。
- 只锁定预先存在的批次记录，初始化与状态更新在同一事务中，确认提交后执行迁移。
- 分文档补充聚焦 SELECT ... FOR UPDATE 的 Java 实现骨架，不重复主文档的完整初始化代码。

## 6 待确认或待验证事项

- 生产批次控制表的 InnoDB 引擎、主键及启动事务实际隔离级别。
- 锁定读取、检查与更新是否使用同一事务和连接，以及事务管理是否生效。
- 两个并发启动请求的等待、读取状态与拒绝重复启动行为。
- 回滚、锁等待超时、死锁和提交响应丢失时的处理；实际超时配置值。
- 索引锁范围与快照读取的其余来源仍待逐项核验；本轮仅核验 Locking Reads 和 Error Handling 的相关说明。
- SQL 示例、项目锁范围与连接异常后的锁释放尚未通过数据库实验验证。

## 7 复核记录

2026-11-14 19:02：已执行 [$java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。
