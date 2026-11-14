# 003_MySQL 表连接算法有哪些

以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准。本文讨论 MySQL 8.0.33 的连接算法；版本差异和执行计划选择不能用未验证的生产结论替代。

## 1 问题

MySQL 常见表连接算法有哪些，如何根据索引、版本和执行计划判断？

## 2 高级 Java 面试核心考点

- 区分 SNLJ、INLJ、BNL、BKA 和 Hash Join 的执行方式。
- 理解外表、内表、被驱动表及连接字段索引。
- 根据 MySQL 版本和 EXPLAIN 结果判断算法，避免绝对化结论。

## 3 面试口述精炼版本

口述内容字符数：245

**先按两类理解**

MySQL JOIN 可以从 Nested-Loop 系列和 Hash Join 两类理解。SNLJ 是外表一行、内表全扫；INLJ 是外表一行、内表走连接字段索引；BNL 是外表攒一批、内表扫描；BKA 是批量版 INLJ，通常结合 MRR 减少随机访问。

**结合版本和计划判断**

MySQL 8.0.18 引入 Hash Join，8.0.20 之后很多原来使用 BNL 的场景可能改用 Hash Join，但 INLJ 仍然常见。具体要看连接顺序、被驱动表索引、`type`、`key`、`rows` 和 `Extra`，不能按 SQL 书写顺序或表大小直接下结论。

详见：[连接算法详细说明](<D:/000/sources/eas/000_MySQL/003_MySQL 表连接算法有哪些.md:109>)。

## 4 详细方案

### MySQL 表连接算法有哪些？

MySQL JOIN 可以分成两大类：

```
Nested-Loop 系列
Hash Join
```

常见可以按 5 种理解：

|   |   |   |   |
|---|---|---|---|
|算法|英文全称|中文名|核心记忆|
|SNJ / SNLJ|Simple Nested-Loop Join|简单嵌套循环连接|外表一行，内表全扫|
|INLJ|Index Nested-Loop Join|索引嵌套循环连接|外表一行，内表走索引|
|BNL / BNLJ|Block Nested-Loop Join|块嵌套循环连接|外表攒一批，内表全扫|
|BKA|Batched Key Access Join|批量键访问连接|外表攒 key，内表批量索引查|
|Hash Join||哈希连接|小表建哈希，大表匹配|

---

#### 1. 版本结论

不能说：

MySQL 8.0 以后 Nested-Loop Join 被 Hash Join 取代了。

应该说：

MySQL 8.0.18 引入 Hash Join。
MySQL 8.0.20 之后，传统 BNL 算法基本不再使用，很多原来使用 BNL 的场景会改由 Hash Join 处理。
但是，如果被驱动表连接字段有合适索引，MySQL 仍然经常使用 INLJ。

面试记忆：

```
有索引：常见 INLJ。

有索引但随机 IO 多：可能用 BKA 优化。

没合适索引：MySQL 8.0 以后可能用 Hash Join。

旧版本或概念题：可能会考 BNL。
```

---

#### 2. 外表和内表

外表、内表是执行计划里的概念，不一定等于 SQL 里的书写顺序。

```
外表 = 驱动表 = 外层循环先扫描的表

内表 = 被驱动表 = 根据外表每一行去匹配的表
```

比如：

```
SELECT *
FROM orders o
JOIN user u ON o.user_id = u.id;
```

如果优化器选择 `orders` 作为外表，`user` 作为内表，逻辑类似：

```
for (Order order : orders) {
    User user = indexLookup(user.id, order.user_id);
}
```

面试记忆：

```
外表负责提供 join key；
内表负责根据 join key 查数据。

外表过滤后越小越好；
内表连接字段最好有索引；
谁是外表、谁是内表，要看 EXPLAIN。
```

补充：

```
INNER JOIN 中，优化器可以调整连接顺序。

LEFT JOIN 中，因为要保留左表结果，连接顺序会受到更多限制。
```

---

### 3. 五种 JOIN 算法

#### 3.1 SNJ：Simple Nested-Loop Join

SNJ 是最原始的嵌套循环连接。

逻辑：

```
外表取一行
内表全表扫一遍
匹配就返回
```

伪代码：

```
for (Row outerRow : outerTable) {
    for (Row innerRow : innerTable) {
        if (match(outerRow, innerRow)) {
            output(outerRow, innerRow);
        }
    }
}
```

面试记忆：

```
SNJ：双层 for 循环，外表一行，内表全扫，性能差。
```

---

#### 3.2 INLJ：Index Nested-Loop Join

INLJ 是业务中很常见的 JOIN 方式。

逻辑：

```
外表取一行
拿 join key 去内表索引中查
找到匹配行后返回
```

伪代码：

```
for (Row outerRow : outerTable) {
    List<Row> rows = indexLookup(innerIndex, outerRow.joinKey);
    output(rows);
}
```

比如：

```
SELECT *
FROM orders o
JOIN user u ON o.user_id = u.id;
```

如果 `user.id` 有索引，MySQL 可以：

```
扫描 orders
拿到 user_id
去 user.id 索引中查对应用户
```

面试记忆：

```
INLJ：外表驱动，内表走索引。
```

注意：

```
Using index 不等于 INLJ，它表示覆盖索引。

Using index condition 不等于 INLJ，它表示索引条件下推。

判断 INLJ 要看被驱动表是否使用索引，比如 type、key、rows。
```

---

#### 3.3 BNL：Block Nested-Loop Join

BNL 是对普通嵌套循环的优化。

逻辑：

```
外表先攒一批行到 join buffer
然后扫描内表
用内表每一行和 join buffer 里的外表行匹配
```

它比 SNJ 好的地方是：

```
不是外表每一行都扫一遍内表；
而是外表一批行扫一次内表。
```

但问题是：

```
内表仍然要全表扫描；
如果内表很大，成本仍然很高。
```

版本注意：

```
MySQL 8.0.20 之后，传统 BNL 算法基本不再使用；
很多原来使用 BNL 的场景，会由 Hash Join 处理。
```

面试记忆：

```
BNL：外表攒一批进 join buffer，再扫内表匹配。
```

---

#### 3.4 BKA：Batched Key Access Join

BKA 可以理解成：

```
Index Nested-Loop Join 的批量优化版。
```

普通 INLJ 是：

```
外表一行 -> 查一次内表索引
外表一行 -> 再查一次内表索引
外表一行 -> 再查一次内表索引
```

BKA 是：

```
外表先攒一批 join key 到 join buffer
然后把这批 key 批量交给存储引擎
通过内表索引批量查找
```

BKA 通常会配合 MRR。

MRR 的作用是：

```
尽量按 row ID、主键顺序或数据页顺序读取数据，
减少随机 IO。
```

面试记忆：

```
BKA：批量版 INLJ，外表攒 key，内表批量索引查。
```

---

#### 3.5 Hash Join

Hash Join 适合没有合适索引的连接场景，面试中可以重点按等值连接理解，但不要说它只能用于等值连接。

逻辑：

```
选择较小的表作为 build 表
根据 join key 建立哈希表

扫描另一张 probe 表
拿 probe 表的 join key 去哈希表中匹配
```

伪代码：

```
HashMap<Key, List<Row>> hashTable = new HashMap<>();

for (Row smallRow : smallTable) {
    hashTable.put(smallRow.joinKey, smallRow);
}

for (Row bigRow : bigTable) {
    List<Row> rows = hashTable.get(bigRow.joinKey);
    output(rows);
}
```

面试记忆：

```
Hash Join：小表建哈希，大表拿 key 匹配。
```

---

### 4. BKA 查询过程举例

SQL：

```
SELECT o.id, o.amount, u.name
FROM orders o
JOIN user u ON o.user_id = u.id
WHERE o.amount > 100;
```

假设：

```
orders 是外表
user 是内表
user.id 有索引
```

普通 INLJ：

```
order 101，user_id = 3  -> 查 user.id = 3
order 102，user_id = 8  -> 查 user.id = 8
order 103，user_id = 2  -> 查 user.id = 2
```

BKA：

```
先把外表的一批 key 放进 join buffer：

3, 8, 2, 7, 5
```

然后批量交给存储引擎：

```
批量查 user.id 对应的数据
```

可以近似理解成：

```
user.id IN (3, 8, 2, 7, 5)
```

注意，底层不一定真的改写成 `IN`，这里只是方便理解。

BKA 的核心是：

```
把零散的索引访问变成批量索引访问；
把乱跳的数据页访问变成更集中的数据页访问。
```

---

### 5. BKA 为什么能减少随机 IO？

普通 INLJ 是按外表顺序查内表。

比如外表传来的 key 是：

```
10, 3000, 25, 9000, 80
```

它们在内表中对应的数据页可能是：

```
key = 10    -> 数据页 P5
key = 3000  -> 数据页 P800
key = 25    -> 数据页 P6
key = 9000  -> 数据页 P1600
key = 80    -> 数据页 P10
```

普通 INLJ 的访问顺序可能是：

```
P5 -> P800 -> P6 -> P1600 -> P10
```

这就是随机 IO。

BKA 会先攒一批 key，再配合 MRR 尽量按主键或数据页顺序读取。

优化后可能变成：

```
P5 -> P6 -> P10 -> P800 -> P1600
```

这样性能更好，原因是：

```
1. IO 等待次数减少
2. 磁盘来回跳减少
3. 更容易批量读
4. 更容易触发预读
5. 同一个数据页更容易连续复用
6. Buffer Pool 命中率可能更高
```

面试重点：

```
BKA 提高性能，不是因为少查了逻辑数据；
而是因为让内表数据页读取更集中，减少随机 IO 等待。
```

---

### 6. Hash Join 查询过程举例

SQL：

```
SELECT o.id, o.amount, u.name
FROM orders o
JOIN user u ON o.user_id = u.id;
```

假设：

```
user 表比较小
orders 表比较大
user.id 没有合适索引
```

这时 MySQL 可能选择 Hash Join。

第一步：Build 阶段，小表建哈希表。

```
user 表：

1 -> 张三
2 -> 李四
3 -> 王五
```

建立哈希表：

```
hashMap = {
  1 -> 张三,
  2 -> 李四,
  3 -> 王五
}
```

第二步：Probe 阶段，大表匹配。

```
order 101，user_id = 1
去 hashMap 找 key = 1
找到张三
输出 order 101 + 张三

order 102，user_id = 3
去 hashMap 找 key = 3
找到王五
输出 order 102 + 王五

order 103，user_id = 8
去 hashMap 找 key = 8
没找到
不输出
```

Hash Join 完整流程：

```
1. 选择较小的表作为 build 表
2. 扫描 build 表，根据 join key 建立哈希表
3. 扫描另一张 probe 表
4. 用 probe 表的 join key 去哈希表中查找，匹配成功就返回结果
```

面试记忆：

```
Hash Join = 小表建哈希，大表来匹配。

Build 阶段：扫描小表，按 join key 建 hashMap。

Probe 阶段：扫描大表，用 join key 去 hashMap 里找。

适用场景：没有合适索引的连接。

核心优势：避免外表每一行都去内表全表扫描。
```

---

### 7. BKA 和 BNL 的区别

|   |   |   |   |
|---|---|---|---|
|算法|内表是否用索引|join buffer 里主要放什么|核心逻辑|
|BNL|不用索引|外表一批行|扫描内表，和 buffer 中外表行匹配|
|BKA|用索引|外表一批行和 join key|批量拿 key 去内表索引中查|

最短记忆：

```
BNL：没索引，外表攒一批行，内表全表扫。

BKA：有索引，外表攒一批 key，内表批量索引查。
```

---

### 8. EXPLAIN 怎么判断？

重点看：

```
type
key
rows
Extra
```

常见判断：

|   |   |
|---|---|
|EXPLAIN 现象|含义|
|被驱动表 `key` 有值|通常说明被驱动表用了索引，可能是 INLJ|
|`Using join buffer (Block Nested Loop)`|使用 BNL，主要是旧版本或特定版本|
|`Using join buffer (Batched Key Access)`|使用 BKA|
|`Using join buffer (hash join)`|使用 Hash Join|
|`Using index`|覆盖索引，不等于 INLJ|
|`Using index condition`|索引条件下推，不等于 INLJ|
|`Using MRR`|使用 Multi-Range Read 优化|

`EXPLAIN` 官方文档也把 `Using join buffer (Block Nested Loop)`、`Using join buffer (Batched Key Access)` 和 `Using join buffer (hash join)` 分别作为 join buffer 的不同标识。([MySQL Developer Zone](https://dev.mysql.com/doc/en/explain-output.html?utm_source=chatgpt.com))

补充：

```
如果想更清楚地看 Hash Join，可以使用 EXPLAIN FORMAT=TREE。
```

---



## 5 已确认规则

- 本文按 MySQL 8.0.33 语义整理，版本差异和优化器选择保留不确定边界。
- BKA、Hash Join 是否采用必须结合执行计划，不能写成必然行为。

## 6 待确认或待验证事项

- 目标 SQL 的连接顺序、索引、数据量和执行计划。
- BKA、Hash Join 在实际版本和负载下的选择及收益。

## 7 异议与分析

Hash Join 不是对全部 Nested-Loop 算法的替代；BNL 的版本变化不能推导为 INLJ 不再使用。

## 8 复核记录

2026-11-14 22:20：已执行 [-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。
