

# 006_Canal 同步 MySQL binlog 到 ES（MySQL 处理逻辑）.md

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 文档维护与已确认规则

以下规则是当前文档已经确认的维护约束，后续修改本文件时必须先核对：

- 面试口述精炼版本只保留 MySQL binlog 配置、GTID 起点和恢复边界的面试核心内容，并位于详细方案之前。
- 详细方案按“先总后分”组织，每个主要知识点先说明核心结论、目标或边界，再展开配置、流程和异常处理。
- 当前技术版本以项目 AGENTS.md 为准：MySQL 8.0.33、Canal Server 1.1.7、Canal Adapter 1.1.7。
- 两个独立 MySQL 实例分别维护自己的 server_uuid、GTID 基线、destination 和消费进度，不能合并或跨实例复用。
- MySQL binlog 保留期已确认为 30 天。
- 未经实际环境或发行包核对的配置键名、权限、位点衔接和运行结果必须标注为待确认。
- 文档修改后要检查面试口述、详细方案、GTID 起点、位点恢复、保留期和关联链接的一致性。
- 面试口述精炼版本以 MySQL 处理逻辑为主，按“记录变更、确定 GTID 起点、保证日志可恢复”的顺序递进说明。
- `binlog_format=ROW` 用于按受影响的行记录增删改；`binlog_row_image=FULL` 用于保留完整行镜像，便于 Canal 获取同步所需字段。
- GTID（Global Transaction Identifier）解释为“实例 UUID 加事务区间”的全局事务标识符；示例 `uuid-A:1-120` 表示来源实例 `uuid-A` 的第 1 到第 120 个事务已执行完成。GTID 是集合，两个独立实例必须分别保存，不能合并或跨实例复用。
- 历史导入前采集各新库完整 GTID 集合；启动 Canal 同步 ES 前，将对应集合配置到对应 Canal 通道的起始位点，具体配置键名以 Canal Server 1.1.7 实际发行包为准。
- 面试口述中不重复展开 `binlog_format`、`binlog_row_image` 的会话核对，也不要求记录 `server_uuid`、采集时间和 destination；会话级 `sql_log_bin=ON` 仍需保留为导入和业务写入的必要条件。



## 面试口述精炼版本

### 先说整体思路

这部分我主要回答三个问题：MySQL 怎么把变更记录下来，Canal 从哪里开始读，以及出现故障后怎么保证不丢数据。项目使用 MySQL 8.0.33，两个新库是两个独立实例，所以每个实例都要单独维护自己的 binlog、GTID 基线和 Canal 通道。

### 1. 先保证变更能被 Canal 读取

两个实例开启 binlog 和 GTID，并把 `binlog_format` 设为 `ROW`，让 binlog 记录具体哪些行发生了增删改；把 `binlog_row_image` 设为 `FULL`，保留完整行镜像，便于 Canal 获取写入 ES 所需的字段。历史导入和业务写入使用的会话还要保持 `sql_log_bin=ON`，否则写入不会进入 binlog，Canal 后续无法补读。这些是候选配置，实际参数需要由 DBA 按环境核对。

### 2. 在历史导入前确定 GTID 起点

正式导入前先停写，等待在途事务结束，再分别在两个实例采集完整的 `@@GLOBAL.gtid_executed`，作为各自 Canal 通道的首次读取基线。GTID 是全局事务标识符，由实例 UUID 和事务区间组成，例如 `uuid-A:1-120` 表示实例 A 已执行完第 1 到第 120 个事务。两个实例的 GTID 集合必须分开保存，不能合并，也不能跨实例复用。

采集基线后再进行历史导入，并确保导入连接的 `sql_log_bin=ON`，这样导入产生的变更会落在基线之后。启动 Canal 时，把对应实例的基线交给对应通道；具体配置键名和 GTID 接入方式要以 Canal Server 1.1.7 的实际发行包为准。

### 3. 故障恢复看可靠进度和日志连续性

导入前基线只负责第一次启动，后续恢复要以已经完成 ES 写入并得到消费确认的可靠进度为准。重启前先确认所属实例仍保留所需 binlog，再核对批次结果、下游确认和 Canal 内部位点；位点丢失、日志过期或连续性无法证明时暂停复核，不能直接跳到 MySQL 最新 GTID。项目确认 binlog 保留期为 30 天，但仍要结合故障处理、积压追赶和安全余量核对是否覆盖恢复窗口。

Canal 的具体位点持久化、确认接口和恢复操作仍需按 Canal Server/Adapter 1.1.7 的实际发行包和环境联调验证。

## 详细方案

### 1. 先明确同步起点的对象和责任

历史导入和恢复业务写入都要进入新库的 binlog（二进制日志），否则 Canal 无法从导入前追赶到当前。本节回答谁核对日志条件、何时固定首次起点，以及重启何时可以接续。MySQL 保存日志；Canal Server 按 `destination` 连接所属实例并解析事件，其中 `destination` 是 Canal 的独立订阅通道标识，用于区分不同实例的同步链路，不是 MySQL 的 `server-id`；Canal Adapter 消费事件并写入 ES。同步确认、批次重试和独立增量校验分别见[Canal 同步与 ES 写入](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/005_Canal 同步 MySQL binlog 到 ES（总链路）.md:6>)、[批次失败与同步恢复](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/010_批次失败与同步恢复.md:6>)和[Canal 同步与增量校验如何分工](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/011_Canal 同步与增量校验如何分工.md:6>)。

当前工作区没有实际 MySQL 配置文件、业务数据源配置或锁定的 Canal 发行包，因此以下配置是候选模板；不能据此宣称链路已经部署或运行验证通过。

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

### 6. 已核实内容与待确认事项

**先说结论：** 已确认的项目规则与待核对的环境事实必须分开记录，不能把候选配置写成已验证结果。

已完成的静态核对：本节的导入前基线时点、历史验收后启动同步、批次确认及独立校验边界，与当前目录的改造计划及 `005`、`007`、`010`、`014`、`015` 章节一致；技术栈版本以项目 `AGENTS.md` 为准。

待确认：实际 `mysqld` 加载路径和参数值、两个新库的 `server-id` 与 `server_uuid`、业务及导入连接的会话变量、已确认的 30 天 binlog 保留期是否实际生效并覆盖恢复窗口、复制账号最小权限、Canal 发行包的 GTID 配置属性、基线与内部位点的衔接、Adapter 的批量确认和进度持久化方式。

本次只对照项目资料进行静态核对与文字整理；MySQL 官方资料访问未成功，外部技术细节尚未逐项对照官方文档。未连接 MySQL、启动 Canal 或 ES，也未执行历史导入与真实链路联调，不能据此宣称生产运行或测试通过。













