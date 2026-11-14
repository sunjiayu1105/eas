有一点**不够严谨**。  
图片里的核心结论 **“MySQL 默认事务隔离级别是 REPEATABLE READ” 是对的**，MySQL 官方文档也说明 InnoDB 默认隔离级别是 **REPEATABLE READ**。([MySQL Developer Zone](https://dev.mysql.com/doc/refman/8.1/en/innodb-transaction-isolation-levels.html?utm_source=chatgpt.com))

但下面这句话需要修正：

“事务开始后，其他事务的修改对当前事务不可见。”

这句话**说得太绝对**。更准确地说：

在 MySQL InnoDB 的 REPEATABLE READ 下，**普通 SELECT 是快照读**，同一个事务内多次普通查询通常看到的是同一个快照，所以其他事务后提交的修改对当前事务的普通查询不可见。  
但如果是 `SELECT ... FOR UPDATE`、`UPDATE`、`DELETE` 这类**当前读**，读的是最新已提交数据，并且会加锁。

---

## 修正版：面试推荐回答

MySQL InnoDB 默认的事务隔离级别是 **REPEATABLE READ，也就是可重复读**。

可以用下面的 SQL 查看：

```
SELECT @@transaction_isolation;
```

或者查看全局默认值：

```
SELECT @@GLOBAL.transaction_isolation;
```

如果结果是：

```
REPEATABLE-READ
```

就说明当前默认隔离级别是可重复读。

可重复读的核心作用是：**在同一个事务中，多次普通查询同一批数据，结果保持一致**。

比如事务 A 第一次查某个用户余额是 100，这时事务 B 把余额改成 200 并提交了。如果事务 A 继续用普通 `SELECT` 查询，在 MySQL InnoDB 的可重复读隔离级别下，它通常还是看到 100，而不是 200。

原因是 InnoDB 使用了 **MVCC，多版本并发控制**。可以理解为数据库给事务保存了一张“快照照片”，事务后续普通查询主要看这张照片，而不是每次都看最新数据。

所以 REPEATABLE READ 可以避免：

1. **脏读**：不会读到别人还没提交的数据；
2. **不可重复读**：同一个事务里，多次读同一行数据，结果不会前后变化。

但是这里有一个面试高频追问点：**幻读**。

按照 SQL 标准，REPEATABLE READ 仍然可能出现幻读。幻读指的是：同一个事务里，两次范围查询，第二次多出来了一些之前没有的记录。

不过 MySQL InnoDB 的实现更强一些。在默认的 REPEATABLE READ 下，普通 `SELECT` 通过 MVCC 快照读保证前后查询一致；而 `SELECT ... FOR UPDATE`、`UPDATE`、`DELETE` 这类当前读，会通过 **Next-Key Lock，也就是记录锁加间隙锁**，防止其他事务在范围内插入新数据。因此 InnoDB 在很多场景下也能避免幻读。官方文档也提到，InnoDB 在 REPEATABLE READ 下会对搜索和索引扫描使用 next-key locks 来防止 phantom rows。([MySQL Developer Zone](https://dev.mysql.com/doc/refman/8.2/en/innodb-locking.html?utm_source=chatgpt.com))

所以面试中不要简单说：

REPEATABLE READ 一定会幻读。

也不要绝对说：

REPEATABLE READ 完全不会幻读。

最稳妥的说法是：

按 SQL 标准，可重复读仍可能出现幻读；但 MySQL InnoDB 默认的 REPEATABLE READ 通过 MVCC 和 Next-Key Lock，在大多数场景下可以避免幻读。

---

## 原答案应该改成这样

MySQL 默认的事务隔离级别是 **REPEATABLE READ，可重复读**。

查看方式：

```
SELECT @@transaction_isolation;
```

或者：

```
SELECT @@GLOBAL.transaction_isolation;
```

需要注意，`@@GLOBAL.transaction_isolation` 查看的是**全局默认隔离级别**，不一定代表当前会话已经使用的隔离级别。更推荐面试时说：

```
SELECT @@transaction_isolation;
```

查看当前会话的事务隔离级别。

REPEATABLE READ 可以保证同一个事务中多次普通查询结果一致，避免脏读和不可重复读。MySQL InnoDB 通过 MVCC 实现普通快照读的一致性，通过 Next-Key Lock 处理当前读中的范围插入问题，因此在大多数场景下也可以避免幻读。它兼顾了一致性和并发性能，所以是 MySQL InnoDB 的默认隔离级别。

---

## 最适合背的版本

面试可以这样答：

MySQL InnoDB 默认的事务隔离级别是 REPEATABLE READ，也就是可重复读。它可以避免脏读和不可重复读。

按 SQL 标准来说，可重复读仍可能出现幻读，但 MySQL InnoDB 通过 MVCC 和 Next-Key Lock，在大多数场景下也能避免幻读。  
在 InnoDB 中，普通 SELECT 通过 MVCC 实现快照读，同一个事务内多次查询通常看到的是同一个数据版本，所以结果保持一致。  
不过要注意，普通 SELECT 是快照读，`SELECT ... FOR UPDATE`、`UPDATE`、`DELETE` 这类是当前读，当前读会读取最新已提交数据并加锁。

---

## 最后一句口诀

**MySQL 默认 RR；普通查询看快照；当前读看最新并加锁；RR 防脏读、防不可重复读；InnoDB 额外用 Next-Key Lock 防幻读。**

# 如何理解“如果隔离级别是可重复读，普通 SELECT 是快照读，SELECT ... FOR UPDATE、UPDATE、DELETE 这类是当前读，当前读会读取最新已提交数据并加锁。”?

以下情况会不会发生？

mysql使用默认隔离级别

数据： id=1, name=zhangsan, age=30

事务b开始执行

事务a: update age = 25 where id =1 ,提交事务

事务b： update name=wangwu where age >= 30 ，事务b并没有修改到id=1的记录对吗？因为隔离级别是可重复读，update操作会读取最新已提交数据