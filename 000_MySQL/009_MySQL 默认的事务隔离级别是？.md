# 009_MySQL 默认的事务隔离级别是？

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准，本文讨论 MySQL 8.0.33 的 InnoDB 引擎。已核对 MySQL 8.0 官方资料；SQL 为教学示例，未执行数据库并发实验，也未读取生产配置。

## 1 问题

MySQL InnoDB 默认的事务隔离级别是什么，普通查询与更新操作分别遵循什么读取规则？

## 2 高级 Java 面试核心考点

- InnoDB 的默认隔离级别及“默认值”与实际配置的区别。
- 可重复读下的快照建立时机、MVCC 和事务自身写入的可见性。
- 快照读与当前读的区别，以及可重复读对幻读的保护边界。

## 3 面试口述精炼版本

口述内容字符数：481

**默认是可重复读**

MySQL InnoDB 默认的事务隔离级别是 REPEATABLE READ，也就是可重复读，简称 RR，可以避免脏读和不可重复读。但默认值可以被修改，实际使用时还要看配置。

**普通查询看快照，更新看当前记录**

RR 下，普通 SELECT 通常在事务第一次一致性读时建立快照，后续一致性读复用它。InnoDB 通过 MVCC，也就是多版本并发控制，判断哪些历史版本可见，因此不会因为其他事务后续提交就改变原来的读取结果；当前事务自己的修改仍然可见。

SELECT ... FOR UPDATE、UPDATE 和 DELETE 不沿用这个旧快照，而是按当前记录状态加锁、判断和处理，遇到冲突锁可能等待。因此，可重复读不代表更新操作也按照第一次查询的旧值执行。

**幻读要区分读取方式**

按 SQL 标准，RR 仍允许幻读；InnoDB 的快照读让其他事务后来提交的新增记录不可见，范围锁定读取则通过记录锁和间隙锁限制向受保护范围插入。两种机制的前提不同，不能说 RR 下任意两次查询结果都相同。

详见：[默认值与实际配置](<D:/000/sources/eas/000_MySQL/009_MySQL 默认的事务隔离级别是？.md:37>)、[快照何时建立，为什么可以重复读取](<D:/000/sources/eas/000_MySQL/009_MySQL 默认的事务隔离级别是？.md:54>)、[UPDATE 为什么不会按旧年龄更新](<D:/000/sources/eas/000_MySQL/009_MySQL 默认的事务隔离级别是？.md:69>)、[幻读保护为什么有边界](<D:/000/sources/eas/000_MySQL/009_MySQL 默认的事务隔离级别是？.md:112>)。

## 4 详细方案

### 4.1 默认值与实际配置

InnoDB 默认使用 REPEATABLE READ；实例配置、会话设置及单次事务设置可能改变实际使用的隔离级别。“默认 RR”不等于项目中的所有事务当前都运行在 RR。

```sql
-- 当前会话的默认隔离级别
SELECT @@SESSION.transaction_isolation;
-- 全局默认隔离级别，通常作为新连接的初始设置
SELECT @@GLOBAL.transaction_isolation;
```

`SELECT @@transaction_isolation;` 等价于查询会话变量。返回 `REPEATABLE-READ` 说明所查询变量的值是 RR，不能把会话值和全局值混为一谈。修改全局值不会自动改变已有会话的设置。

如果使用不带 SESSION 或 GLOBAL 的 `SET TRANSACTION ISOLATION LEVEL ...` 单独指定下一事务，不能仅凭上述默认变量判断该事务的实际级别，还需核对事务启动前的设置。这是配置排查边界，不代表本项目已经采用该设置。

依据：[Transaction Isolation Levels](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/innodb-transaction-isolation-levels.html) 支持 InnoDB 默认 RR 的结论；变量与单次事务设置的作用域见 [SET TRANSACTION](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/set-transaction.html)。

### 4.2 快照何时建立，为什么可以重复读取

普通 `BEGIN` 或 `START TRANSACTION` 通常不会立即固定一致性读快照；RR 下，事务第一次普通一致性读建立快照，后续一致性读复用。使用 `START TRANSACTION WITH CONSISTENT SNAPSHOT` 可以在 RR 下显式于事务开始时建立一致性快照。

MVCC 利用记录的事务信息、undo 历史版本和 ReadView 判断可见性。ReadView 是版本可见性的判断依据，不是为每个事务复制整张表。

例如事务 A 第一次普通查询看到余额 100；事务 B 改为 200 并提交；A 在同一事务再次普通查询，且自己没有修改这条记录时，仍看到 100。B 尚未提交的修改也不会被 A 这样读到，分别体现了避免不可重复读和脏读。

需要保留两个前提：

- 多次查询必须属于同一个事务；自动提交下，两个独立 SELECT 通常是两个事务，不能要求它们复用同一快照。
- 当前事务自己的写入仍然可见。A 自己修改余额后，后续普通查询可以看到自己的修改，因此不能保证任何情况下结果都不变。

依据：[Consistent Nonlocking Reads](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/innodb-consistent-read.html) 说明快照建立时机、事务自身修改可见及一致性读与更新操作的区别。

### 4.3 UPDATE 为什么不会按旧年龄更新

SELECT ... FOR UPDATE 属于锁定读取；UPDATE、DELETE 也需要读取当前记录、加锁并判断操作条件，面试中通常统称为“当前读”。它们不会直接使用先前普通查询的旧快照。遇到其他事务持有冲突锁时，可能等待，获得锁后按实际记录状态判断；当前事务自己的修改同样可见。

以年龄更新为例：初始记录为 `id=1, name='zhangsan', age=30`。B 先开启事务，A 随后把年龄改为 25 并提交，B 再执行 `UPDATE ... WHERE age >= 30`。假设期间没有其他写入，B 不会更新 id=1，因为该记录当前年龄已经不满足条件。

即使 B 在 A 提交前已通过普通 SELECT 看到了 age=30，之后的 UPDATE 也不会沿用这个旧值。下面给出这种更容易混淆的教学场景，两个会话按标注顺序执行。

前提：独立测试库中存在 InnoDB 表 `isolation_demo(id 主键, name, age)`，只有初始记录 `(1, 'zhangsan', 30)`；两个会话均使用 RR，没有其他并发写入。

**会话 B：先建立快照**

```sql
START TRANSACTION;
SELECT age FROM isolation_demo WHERE id = 1;
-- 预期为 30，保持事务未结束。
```

**会话 A：修改年龄并提交**

```sql
START TRANSACTION;
UPDATE isolation_demo SET age = 25 WHERE id = 1;
COMMIT;
```

**会话 B：继续读取和更新**

```sql
SELECT age FROM isolation_demo WHERE id = 1;
-- 预期仍为 30：普通查询复用之前的快照。

UPDATE isolation_demo SET name = 'wangwu' WHERE age >= 30;
-- 预期不更新 id=1：当前年龄为 25，不满足条件。
SELECT ROW_COUNT();
-- 紧接 UPDATE 读取影响行数；在测试表只有该记录的前提下，预期为 0。
ROLLBACK;
```

如果 B 起初仅执行 BEGIN，没有普通一致性读，那么 A 提交后 B 才第一次普通查询，预期就会读到 25。这里要区分“事务先开始”和“快照已建立”。

上述为机制推导的预期结果，未实际执行。依据为 4.2 引用的官方一致性读文档：一致性读快照不直接决定 UPDATE、DELETE 可以处理哪些记录。

### 4.4 幻读保护为什么有边界

幻读关注同一事务重复执行相同条件查询时，匹配的记录集合是否受到其他事务影响而变化。新增、删除或修改条件字段都可能改变匹配集合，不能仅按行数判断。

**快照读：通过版本可见性保持一致**

A 先普通查询建立快照，B 随后插入一条匹配记录并提交。A 继续按同一快照普通查询且没有自己的相关写入时，不会看到 B 新增的记录。这里没有阻止 B 插入，只是该版本对原快照不可见。

**范围锁定读取：阻止向受保护间隙插入**

RR 下，范围锁定读取通常使用间隙锁或 Next-Key Lock（临键锁，即索引记录锁与其前方间隙锁的组合），阻止其他事务向扫描范围中受保护的间隙插入记录。锁保护发生在取得锁之后，实际锁范围依赖索引、条件和扫描路径。

并非所有当前读都用临键锁：使用唯一索引完整等值条件命中已有记录时，通常只锁该记录，不锁其前方间隙。

**混合读取：不能拼成一个不变保证**

A 先普通查询，B 插入并提交，A 再执行 FOR UPDATE，可能看到旧快照中没有的记录，因为第一次普通查询没有建立范围锁，两次采用的读取规则也不同。再加上事务自身修改可见，不能把“MVCC 与临键锁”解释为所有查询结果永远一致。

依据：[Transaction Isolation Levels](https://docs.oracle.com/cd/E17952_01/mysql-8.0-en/innodb-transaction-isolation-levels.html) 说明唯一索引命中与范围扫描的加锁差异，并提示 RR 下混合锁定与非锁定操作涉及不同数据视图。锁的释放和异常边界另见 [SELECT FOR UPDATE 如何加锁与释放锁](D:/000/sources/eas/000_MySQL/010_SELECT_FOR_UPDATE如何加锁与释放锁.md)。

## 5 已确认规则

- 使用 java-interview-doc-review-loop 整理本文并连续复核。
- 本次不执行数据库实验，区分官方资料核对与实际运行验证。

## 6 待确认或待验证事项

- 当前项目实例、会话及应用事务的实际隔离级别设置尚未读取。
- 4.3 的双会话 SQL 及 4.4 的范围加锁行为尚未在 MySQL 8.0.33 上执行验证。
- 文档导航仅检查路径和行号，尚未在阅读器中实际点击验证。

## 7 异议与分析

- 原文已指出“事务开始后其他修改都不可见”过于绝对，但没有明确快照建立时机，仍有“多次普通查询结果保持一致”的宽泛表述：已补充首次一致性读及事务自身写入可见的边界。
- 原文把所有当前读归于 Next-Key Lock，并用“大多数场景”概括幻读保护：已按快照可见性、范围锁及混合读取分别说明，补充唯一索引命中已有记录的例外。
- 原文把“兼顾一致性和并发性能”直接写成选择 RR 作为默认值的原因：本次没有核实这一历史因果，删除该推断，仅保留官方明确记载的默认值。
- 原文引用 MySQL 8.1、8.2 资料：已替换为 MySQL 8.0 资料，以符合项目版本范围。

## 8 复核记录

2026-11-14 17:53：已执行 [$java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。
