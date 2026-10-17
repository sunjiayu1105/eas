

# 006_Canal 同步 MySQL binlog 到 ES（MySQL 处理逻辑）.md

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 文档维护与已确认规则

- 本文讨论 MySQL 8.0.33、Canal Server/Adapter 1.1.7、Elasticsearch 7.17.15；Java、Spring Boot、RocketMQ 等版本以当前项目 `AGENTS.md` 为准。
- 两个新库属于两个独立 MySQL 实例，必须分别维护 `server_uuid`、完整 GTID 基线、Canal `destination` 和后续可靠进度；GTID 集合不得合并或跨实例复用。
- 首次同步基线必须在历史导入前、停写并确认在途事务结束后分别采集；历史导入和后续业务写入需保持 `sql_log_bin=ON`，使已提交变更进入 binlog。
- 导入前 GTID 基线只用于首次启动，后续进度必须以 ES 写入、拆分子批次处理和 Canal 消费确认结果为准；不能把最新 GTID 或 Canal 已读取位置直接当作可靠进度。
- 项目资料确认 binlog 保留期目标为 30 天，但实际参数、恢复窗口、Canal 位点衔接和发行包配置仍需按环境核验，本文不得将候选配置写成已部署事实。
- 本轮已确认两个 MySQL 实例继续分别对应 `db0`、`db1`；Canal Server/Adapter HA 只负责订阅任务的运行资格、连接发现和消费恢复，不改变 MySQL 源端归属，也不合并或跨实例复用 GTID。
- 本轮已确认采用参考文章 HA 思路：3 台 Canal Server 接入 3 节点 ZooKeeper，使用本地配置，两个 destination 独立选主；Adapter 使用 TCP 和 ZooKeeper 发现，配置变更后重启，并采用 `retries: 5`、`terminateOnException: true`。参考文章为 Canal 1.1.6 示例，项目按 1.1.7 适配；Adapter 进程数量和自身故障接管方式未由正文确认。
- 本轮已确认恢复验收列为待执行要求：分别核对两个源实例、活动 Server、Adapter 重连、消费位点、binlog 连续性、积压和 ES 重放；5 次尝试耗尽及 404 分支分别验证。未执行部署或故障演练。
- 本文只记录静态核对结果；未连接 MySQL、Canal 或 ES，不据此宣称部署、运行或真实链路验证已完成。

## 面试口述精炼版本

### 整体概括

MySQL 侧主要做好三件事：开启并正确配置 binlog，保证变更能够被 Canal 读取；在历史导入前固定 GTID（Global Transaction Identifier，全局事务标识符）基线，作为首次同步的起点；确认历史导入和后续业务写入都进入 binlog。

在此基础上，两个独立实例分别接入 `db0`、`db1` 两条 Canal 订阅。Canal Server 由 3 台节点和 ZooKeeper 协调运行资格，Adapter 通过 TCP 和 ZooKeeper 找到活动 Server；Server 切换不改变 MySQL 实例与 destination 的对应关系。具体 HA 接入方式和验收边界见下方详细方案，实际恢复仍待验证。

GTID 是已提交事务的唯一标识，通常由来源实例的 `server_uuid` 和事务序号组成，例如 `uuid-A:101`。历史导入前基线，就是开始历史导入前先记录下来的初始状态，保存该实例当时已经执行到的 GTID 集合。这个集合可能包含多个来源 UUID 和多个事务区间，并不是一个单独的最大事务号。
### 配置核心 binlog 参数

两个实例重点配置以下参数：

```ini
[mysqld]
server-id=101
log_bin=mysql-bin
gtid_mode=ON
enforce_gtid_consistency=ON
binlog_format=ROW
binlog_row_image=FULL
```

`log_bin` 表示开启 binlog；`mysql-bin` 是 binlog 文件名前缀，MySQL 会据此生成类似 `mysql-bin.000001` 的日志文件。

`server-id` 用来区分 MySQL 实例，两个实例不能重复；`gtid_mode=ON` 为已提交事务生成 GTID，便于确定同步位置；`enforce_gtid_consistency=ON` 用来限制不适合纳入 GTID 事务管理的操作。

`binlog_format=ROW` 表示采用行格式记录变更：对于 INSERT、UPDATE、DELETE，binlog 记录受影响行的行事件及对应的列值变化，具体包含哪些列值取决于 `binlog_row_image`，而不是只记录一条待执行的 SQL 语句。Canal 可以据此解析库名、表名、事件类型和行数据。

`binlog_row_image=FULL` 表示行事件包含完整列镜像：即使某些列没有发生变化，也会一并记录对应列值，便于下游获得完整行数据并按映射生成 ES 文档。最终仍需结合实际事件核对字段内容。

### 采集历史导入前的 GTID 基线

先暂停业务写入并等待在途事务完成，再分别在两个新库上读取当前已执行的完整 GTID 集合，将它作为各自的历史导入前基线。

### 确认写入进入 binlog

基线确定后再开始历史导入，导入连接和业务写入连接都要保持 `sql_log_bin=ON`。这样历史导入以及业务产生的新增、修改、删除才能进入日志链路。如果某个写入会话关闭了 binlog，数据虽然写进 MySQL，但 Canal 后续无法从 binlog 中补回这部分变更。

实例级配置和实际写入会话都要核对，不能只检查 DBA 查询连接。
## 详细方案

### 1. 先明确同步起点的对象和责任

历史导入和恢复业务写入都要进入新库的 binlog（二进制日志），否则 Canal 无法从导入前追赶到当前。本节回答谁核对日志条件、何时固定首次起点，以及重启何时可以接续。MySQL 保存日志；Canal Server 按 `destination` 连接所属实例并解析事件，其中 `destination` 是 Canal 的独立订阅通道标识，用于区分不同实例的同步链路，不是 MySQL 的 `server-id`；Canal Adapter 消费事件并写入 ES。同步确认、批次重试和独立增量校验分别见[Canal 同步与 ES 写入](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/005_Canal 同步 MySQL binlog 到 ES（总链路）.md:6>)、[批次失败与同步恢复](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/010_批次失败与同步恢复.md:6>)和[Canal 同步与增量校验如何分工](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/011_Canal 同步与增量校验如何分工.md:6>)。

当前工作区没有实际 MySQL 配置文件、业务数据源配置或锁定的 Canal 发行包，因此以下配置是候选模板；不能据此宣称链路已经部署或运行验证通过。

### 1.1 接入 Canal Server 与 Adapter 高可用

本方案把 MySQL 源端和 Canal 高可用分开处理：两个 MySQL 实例仍各自保存 `server_uuid`、GTID 和 binlog；3 台 Canal Server 通过 3 节点 ZooKeeper 协调 `db0`、`db1` 的运行资格，Adapter 再发现对应的活动 Server。一个 destination 的切换不要求另一个同时切换，也不改变 destination 与 MySQL 实例的对应关系。

| 组件 | 当前确认方案 | 内容边界 |
| --- | --- | --- |
| Canal Server | 3 台不同主机，TCP 模式，接入同一 ZooKeeper 集群；本地 `canal.properties` 和 `instance.properties` | ZooKeeper 协调运行资格和位点相关元数据；实际配置加载、旧节点隔离和接管仍待验证 |
| `db0`、`db1` | 两个 destination 独立选主，各自同一时间只有一个活动 Server | 不承诺两个 destination 原子切换或跨库全局顺序 |
| Canal Adapter | TCP 模式，配置 `canal.tcp.zookeeper.hosts`，注释直连 `canal.tcp.server.host`，修改后重启 | 参考文章正文未明确 Adapter 进程数量、进程主备和自身故障接管方式 |
| 失败配置 | `retries: 5`、`terminateOnException: true` | 5 次含首次；异常耗尽后回退并关闭对应任务同步开关；404 只记日志的缺口仍待处理 |

**参考来源与适配边界：** Server 的 3 节点、ZooKeeper 选主和每台 Server 添加集群配置，参考 [`999_Canal 同步 MySQL binlog 到 ES_参考文章.md`](999_Canal%20同步%20MySQL%20binlog%20到%20ES_%E5%8F%82%E8%80%83%E6%96%87%E7%AB%A0.md)“HA 模式 / canal-deployer 部署”；Adapter 的 TCP、ZooKeeper 地址、注释直连、`retries: 5` 和重启步骤，参考同文“canal-adapter 部署”。文章为 Canal 1.1.6 示例，项目技术栈按 `AGENTS.md` 的 Canal 1.1.7 执行；本地配置模式沿用 007，ES 处理和 ack 细节由 008 负责。

```yaml
# Adapter 接入配置示意；实际发行包加载和地址须核对
canal.conf:
  mode: tcp
  terminateOnException: true
  retries: 5
  consumerProperties:
    # canal.tcp.server.host:
    canal.tcp.zookeeper.hosts: zk1:2181,zk2:2181,zk3:2181
```

该配置片段只表达已确认的接入建议，不表示 Adapter 已部署或两个任务已完成故障切换。`retries` 的循环语义、rollback、同步开关和 404 分支依据 008 的 Canal 1.1.7 源码核对；项目要求下游处理成功后才确认批次，不等同于原生默认保证。

### 2. 配置两个新库的 binlog 和 GTID

#### 实例级候选配置

目的是让两个新库都留下可按 GTID 定位、包含 ES 所需行字段的已提交变更。DBA 分别核对实际加载的 `mysqld` 配置和实例 `GLOBAL` 值；常见配置文件包括 `/etc/my.cnf`、`/etc/mysql/my.cnf` 或发行版的 `mysqld.cnf`，实际加载路径、取值和变更方式仍须由 DBA 按环境核定。以下是一份实例的候选模板，另一实例须使用不同的 `server-id`，不能直接套用。

```ini
[mysqld]
server-id=101
log_bin=mysql-bin
gtid_mode=ON
enforce_gtid_consistency=ON
binlog_format=ROW
binlog_row_image=FULL
binlog_expire_logs_seconds=2592000
```

| 配置项 | 作用与边界 |
| --- | --- |
| `server-id=101` | MySQL 实例在复制拓扑中的标识；`101` 仅为示例，两个实例不能重复。它不是 Canal 的 `destination`。 |
| `log_bin=mysql-bin` | 启用 binlog；日志名前缀是候选值，须核对实际加载值及日志能否读取。 |
| `gtid_mode=ON`、`enforce_gtid_consistency=ON` | 启用 GTID 并约束事务与 GTID 兼容；现有实例如何调整须由 DBA 评估，不能仅凭模板切换。 |
| `binlog_format=ROW` | 数据增删改按行记录；这不表示所有 binlog 事件都是行事件。 |
| `binlog_row_image=FULL` | 保留完整行镜像；仍须从实际事件核对 ES 映射需要的字段。 |
| `binlog_expire_logs_seconds=2592000` | 控制日志自动过期清理；项目已确认 binlog 保留期为 30 天；仍须结合最慢读取链路与磁盘空间核对该期限是否覆盖恢复窗口。 |

#### GTID 如何确定同步起点

GTID 是 Global Transaction Identifier，即全局事务标识符。在 MySQL 8.0.33 中，它通常由来源实例的 `server_uuid` 和事务编号组成，例如：

```text
uuid-A:101
```

这表示来源实例 `uuid-A` 的第 101 个事务。GTID 可以唯一标识一个已提交事务，因此 Canal 可以据此判断事务来自哪个 MySQL 实例、是否已经位于同步起点之前，以及重启后应该从哪个事务范围继续读取。

例如新库 A 的导入前基线是：

```text
uuid-A:1-120
```

这表示 A 实例在采集时已经执行到第 120 个事务。Canal 后续应继续处理第 121 个及之后的事务，不能简单跳到采集时的最新 GTID。GTID 集合由多个来源 UUID 和事务区间组成，不是单个最大的事务编号。

这也解释了为什么两个新库要分别保存各自的 GTID 基线：每个 Canal 通道都要从所属实例的基线继续读取并独立维护进度，不能合并两个实例的集合。

`server_uuid` 不是 MySQL 配置中的 `server-id`。`server-id` 通常是复制拓扑中的整数实例标识，例如 `101`；`server_uuid` 是 MySQL 实例的 UUID，也是 GTID 中标识事务来源的部分。例如新库 A 可以是 `server-id=101`、`server_uuid=uuid-A`，新库 B 可以是 `server-id=102`、`server_uuid=uuid-B`，两者分别保存 `uuid-A:1-120` 和 `uuid-B:1-80` 的基线。保存 `server_uuid` 是为了把 GTID 基线绑定到准确的来源实例，恢复时核对当前连接的实例和 GTID 是否对应；不能只凭 `server-id` 判断，也不能跨实例复用基线。

`enforce_gtid_consistency=ON` 用于限制无法可靠纳入 GTID 事务边界的写法。例如在同一个事务中混合更新事务表和非事务表，或者在事务中执行某些临时表操作，可能被 MySQL 拒绝。这里是配置机制的示例，不代表当前项目使用了非事务表或临时表。

`binlog_format=ROW` 表示数据变更按行记录，而不是只记录原始 SQL。例如某条记录的金额从 `10` 更新为 `11`，binlog 会记录受影响行的变化信息，Canal 可以据此解析出具体库表、事件类型和行数据，再转换成 ES 的更新操作。具体字段是否完整，还要结合 `binlog_row_image=FULL` 和实际事件核对。

这些配置只建立日志前提，不能代替 Canal 起点配置或 ES 写入确认。Canal Server 的连接和事件解析见[007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）.md:6>)。

#### 核对实际写入会话

实例级默认值不等于实际写入连接的值。JDBC 本身通常不会把 `sql_log_bin` 设为 `OFF`；在没有执行连接初始化 SQL 的情况下，MySQL 新建会话通常保持 `sql_log_bin=ON`。导入连接和新表写入连接仍须分别核对 `SESSION` 级 `binlog_format`、`binlog_row_image` 和 `sql_log_bin`，DBA 自己的查询连接不能代替它们。若导入工具或连接初始化语句将会话 `sql_log_bin` 设为 `OFF`，导入行不会进入 binlog，不能事后用最新 GTID 补回缺失事件。

DBA 在每个实例核对实例值，并协调在实际写入连接中核对会话值；下方 SQL 须在被核对的连接执行，结果只代表采集时刻，不能代替历史导入前的基线：

```sql
SELECT
    @@GLOBAL.server_uuid AS server_uuid,
    @@GLOBAL.server_id AS server_id,
    @@GLOBAL.log_bin AS global_log_bin,
    @@GLOBAL.gtid_mode AS global_gtid_mode,
    @@GLOBAL.enforce_gtid_consistency AS global_gtid_consistency,
    @@GLOBAL.binlog_format AS global_binlog_format,
    @@GLOBAL.binlog_row_image AS global_binlog_row_image,
    @@SESSION.binlog_format AS session_binlog_format,
    @@SESSION.binlog_row_image AS session_binlog_row_image,
    @@SESSION.sql_log_bin AS session_sql_log_bin,
    @@GLOBAL.gtid_executed AS current_gtid_set;
```

只有实例日志和 GTID 条件成立、实际写入会话为 `ROW`、`FULL`、`sql_log_bin=ON`，才能进入历史导入或恢复业务写入；后续还须用实际行事件核对 ES 映射所需字段，才能确认同步链路可用。事务回滚不形成可供 Canal 同步的已提交行事件。

### 3. 设置日志保留期并授予最小权限

**先说结论：** 两个新库分别保留 30 天 binlog，并按最慢消费、故障处理和追赶时间核对磁盘与恢复窗口。

#### 核验 30 天保留期的落实与恢复窗口

`binlog_expire_logs_seconds` 控制自动过期清理。两个新库的 binlog 保留期已确认为 30 天；DBA 分别核对实际配置是否生效，并按最早未消费事件到追赶完成的时间，考虑历史导入、停读、故障修复、积压和安全余量，核验磁盘空间及该保留期能否覆盖恢复窗口。如发现不足，应提出调整建议，经确认后再修改期限。一个通道追平，不代表其他通道或校验任务已经不再需要日志。手动清理等操作也不能删除同步或校验仍需读取的日志。

#### 配置 Canal 复制账号

Canal Server 的复制账号候选需要 `REPLICATION SLAVE`（读取 binlog）和 `REPLICATION CLIENT`（查询复制元信息）。DBA 按选定发行包核实是否还需读取表结构的 `SELECT`、最小库表范围和账号来源地址；若 Adapter 需要 JDBC 回查业务表，另用查询账号并限定实际表范围。凭据由部署环境管理，不在文档中放真实值。授权后还须验证通道确实能从所属实例读取事件；授权成功本身不等于链路可用。此处不提供可直接执行的授权 SQL，因为发行包需求、来源地址和库表范围均未确认。

### 4. 在历史导入前采集 GTID 基线

**先说结论：** GTID 基线必须在历史导入前、停写并确认日志条件后分别采集，作为两个 destination 的首次同步起点。

#### 固定采集时点

首次起点必须早于历史导入，否则导入事件可能落在起点之前。按[改造计划](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/002_改造计划.md:10>)，停写迁移和历史验收完成后才统一上线同步链路，确认链路就绪后再恢复业务。基线采集顺序是：

1. 暂停会产生新表变更的业务写入，等待在途事务结束。
2. 确认两个新库均没有新的业务写入，并核对实例及后续导入、业务写入会话的日志条件。
3. 在新库 A、新库 B 上分别采集完整 `@@GLOBAL.gtid_executed`，同时记录 `server_uuid`、采集时间和所属 `destination`。
4. 保存两份基线及采集记录后，才开始历史数据导入；导入连接保持 `sql_log_bin=ON`，使已提交的导入变更进入 binlog。

每个实例执行一次以下查询，结果仅作为该实例的导入前基线：

```sql
SELECT
    NOW(6) AS captured_at,
    @@GLOBAL.server_uuid AS server_uuid,
    @@GLOBAL.gtid_executed AS gtid_set;
```

基线记录至少要能关联来源实例的 `server_uuid`、采集时间、Canal `destination` 和完整 `gtid_set`。GTID 集合可能包含多个来源 UUID 和事务区间，不是单个最大事务号；新库 A、新库 B 分别保存，不能合并或跨实例复用。还须把采集记录与停写、导入开始时间核对；查询本身不能证明采集期间没有写入。

#### 用基线覆盖历史导入和后续变更

历史导入产生的已提交行事件，其 GTID 位于导入前基线之后。历史验收通过后，对应 Canal 通道按计划从所属实例的导入前基线读取，先追赶导入事件，再继续读取恢复业务后的新表变更。启动前须核对 `destination` 与实例、基线的对应关系，以及所需日志仍可读；能读到导入事件并完成下游处理，才可判断这段同步链路就绪。如何把 GTID 集合交给 Canal、如何与已有内部位点衔接，仍须按锁定的发行包和部署方式验证。

### 5. 区分导入前基线与同步可靠进度

**先说结论：** 导入前 GTID 基线只负责首次起点，后续可靠进度由下游处理成功和消费确认推进。

#### 基线只保存首次起点

导入前基线是某个 MySQL 实例在固定时点的完整 GTID 集合快照，采集后不随同步推进而改写。不能用 Canal 启动时的最新 GTID、当前 `@@GLOBAL.gtid_executed` 或新的快照覆盖它，否则可能跳过已经导入但尚未写入 ES 的变更。

#### 可靠进度由消费结果推进

Canal Server 读取并解析已提交的 binlog，Canal Adapter 按映射生成 ES 写入、更新或删除操作。只有本批及拆分出的 ES 子批次每项都按既定规则处理成功，并完成 Canal 消费确认后，才能把该批纳入下游可靠进度。Server 已采集到的位置不能直接充当这个进度；HTTP 请求成功、ES 搜索可见或增量校验通过，也不能单独代替消费确认。具体确认 API、重试策略、位点持久化及两类位置如何衔接仍待发行包与环境联调，写入职责见[Canal 同步 MySQL binlog 到 ES（Canal Adapter 处理逻辑）](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/008_Canal 同步 MySQL binlog 到 ES（Canal Adapter 处理逻辑）.md:47>)。

#### 重启先核对日志和连续性

通道重启时按以下条件处理：

1. 确认所属新库仍保留从已确认进度继续读取所需的 binlog。
2. 核对该通道的下游可靠进度、批次处理结果和 Canal 内部位点，明确哪些事务已完成处理。
3. 已有可靠进度时按已确认范围接续；只有确认通道从未推进过，才按导入前基线首次初始化。实际如何设置 Canal 内部位点仍待联调。
4. 日志已过期、进度丢失、批次状态无法核实或日志连续性无法证明时暂停人工复核，不能跳到 MySQL 最新 GTID。

该恢复边界只说明何时允许继续，不能替代具体发行包的位点存储和故障演练。

### 5.1 Canal 高可用恢复验收

下面是本轮确认的待执行验收要求，不是参考文章或当前项目已经通过的测试：

| 检查项 | 核验重点 |
| --- | --- |
| 源实例与任务对应 | 分别核对 `db0`、`db1` 的 `server_uuid`、MySQL 地址、destination 和活动 Server；不能用一个实例的 GTID 或位点替代另一个 |
| Server/Adapter 重连 | 模拟单个 destination 的 Server 切换，检查 ZooKeeper 重新选主、Adapter TCP 重连和同步开关状态；一个任务恢复不代表另一个也恢复 |
| 位点和日志连续性 | 核对可恢复的消费进度、对应 binlog 是否连续可读；ack 方法返回不等于位点已持久化，缺失时暂停复核 |
| 积压追赶 | 分别观察两个 destination 的积压是否持续下降，记录恢复时间，不预设零中断 |
| ES 结果与重放 | 核对新增、更新、删除及 ES 已写入但确认未可靠保存时的重复重放结果；详细 ES 逐项检查由 008 负责 |
| 异常耗尽 | 注入会传递到消费循环的异常，核对最多 5 次尝试（含首次）、rollback 和对应 destination 同步开关是否关闭；不把它表述为自动停止 Adapter 进程 |
| 404 分支 | 单独核对 `NOT_FOUND` 失败子项是否只记日志、是否错误确认批次及后续处置；该缺口不能由 `retries: 5` 自动解决 |

**验收来源：** Server/ZooKeeper 故障转移和 Adapter 接入思路参考 [`999_Canal 同步 MySQL binlog 到 ES_参考文章.md`](999_Canal%20同步%20MySQL%20binlog%20到%20ES_%E5%8F%82%E8%80%83%E6%96%87%E7%AB%A0.md) 的 HA 章节；MySQL 源实例、GTID 和 binlog 连续性沿用本文规则；重试、rollback、同步开关和 404 行为依据 007、008、010 中引用的 Canal 1.1.7 源码。以上检查尚未执行。

### 6. 已核实内容与待确认事项

**先说结论：** 已确认的项目规则与待核对的环境事实必须分开记录，不能把候选配置写成已验证结果。

已完成的静态核对：本节的导入前基线时点、历史验收后启动同步、批次确认及独立校验边界，与当前目录的改造计划及 `005`、`007`、`010`、`014`、`015` 章节一致；本轮新增的 Server/Adapter HA 接入、独立 destination 选主和恢复验收边界与 007、008、010 保持一致；技术栈版本以项目 `AGENTS.md` 为准。

待确认：实际 `mysqld` 加载路径和参数值、两个新库的 `server-id` 与 `server_uuid`、业务及导入连接的会话变量、已确认的 30 天 binlog 保留期是否实际生效并覆盖恢复窗口、复制账号最小权限、Canal 发行包的 GTID 配置属性、基线与内部位点的衔接、3 台 Server 与 ZooKeeper 的实际接管、Adapter 配置加载和重连、Adapter 的批量确认和进度持久化方式。

本次只对照项目资料进行静态核对与文字整理；MySQL 官方资料访问未成功，外部技术细节尚未逐项对照官方文档。未连接 MySQL、启动 Canal 或 ES，也未执行历史导入与真实链路联调，不能据此宣称生产运行或测试通过。
