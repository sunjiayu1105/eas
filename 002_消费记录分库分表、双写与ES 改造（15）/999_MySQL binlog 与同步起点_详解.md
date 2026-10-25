TARGET DECK: E账通::002_消费记录分库分表、双写与ES改造

**已确认规则**：本次按用户确认重命名 Canal Server 处理逻辑和自定义消费服务处理逻辑文档，同步名称标识及关联引用；不调整技术结论。

Q: 999_MySQL binlog 与同步起点_详解
A:

# 3.4.1.1 MySQL 如何为 Canal 同步 ES 提供 binlog 和同步起点？

> 物理命名更新：两个独立分库实例的物理库均为 eas，每个实例包含 consume_record_01～consume_record_16。下文 consume_db0 等名称仅为历史授权示例，不能作为当前库名使用；实际账号授权仍须按最小权限和环境核定。

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

### 文档大纲

- [面试口述精炼版本](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:38>)
  - [核对日志条件，保存导入前基线](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:40>)
  - [提交变更后，行事件可供 Canal 读取](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:46>)
  - [从所属基线追赶，写入 ES](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:50>)
  - [重启时核对日志，从可靠进度接续](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:54>)
- [详细方案](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:58>)
  - [binlog 是什么](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:60>)
  - [ROW 与 FULL 分别控制记录方式和字段范围](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:66>)
  - [Canal 同步 ES 需要哪些信息，如何获取和使用](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:72>)
  - [MySQL 核心配置模板](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:85>)
  - [配置核对 SQL](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:118>)
  - [GTID 基线是实例级事务集合快照](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:141>)
  - [采集导入前 GTID，并区分 ES 同步进度](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:145>)
  - [ES 同步的控制表与 Nacos 配置](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:185>)
  - [Canal 读取 MySQL 所需的账号权限 SQL](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:191>)
  - [Canal Server、RocketMQ 和 ES 写入服务如何使用这些信息](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:215>)
  - [重启时先核对日志与进度](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:221>)
  - [SQL、行与 ROW 事件如何对应](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:225>)
  - [INSERT 行事件记录新增后的行镜像](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:231>)
  - [UPDATE 行事件记录修改前后镜像](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:235>)
  - [DELETE 行事件记录删除前镜像](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:257>)

历史导入与后续新表变更要通过 Canal 同步到 ES，MySQL 必须留下可读的行事件；各新库的导入前基线只负责确定对应 Canal 通道的首次读取起点，通道的可靠进度则负责重启后的连续接续。Canal Server 如何解析这些事件见 Canal Server 原总览（原文档已删除，依据待补充）。

### 面试口述精炼版本

#### 核对日志条件，保存导入前基线

要让历史导入和后续新表变更都能同步到 ES，先确认 2 个新库具备生成并保留可读 binlog（MySQL 二进制变更日志）的条件。DBA 先核对实例已开启 binlog 和 GTID，再分别检查历史导入连接和业务写入连接的会话配置，确认两类连接都使用 `binlog_format=ROW`、`binlog_row_image=FULL` 且 `sql_log_bin=ON`：`ROW` 表示按受影响的行记录变化，`FULL` 表示记录完整字段值；`sql_log_bin=ON` 表示当前连接执行的数据变更会写入 binlog。只有实例和实际写入连接都满足这些条件，相关变更才会形成包含完整字段的可读行事件，后续 Canal 才有数据可消费。详见[配置核对](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:118>)。

配置核对通过后，先停止业务写入，等待在途事务提交或回滚，并确认没有新的业务事务继续产生；然后在历史导入开始前，分别读取 2 个新库的完整 GTID（全局事务标识符）集合，记录来源实例、采集时间和用途，按来源实例各保存 1 份，作为对应 Canal 通道的正式导入前基线；完成基线采集后再开始历史导入。两份基线不能合并：每份只描述所属实例在采集时已经执行到哪里，供该实例的通道确定首次读取范围；详见[基线采集](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:141>)。

#### 提交变更后，行事件可供 Canal 读取

完成上述核对后，历史导入或新表写入的事务提交成功，Canal 才能读取对应的已提交行事件；事务表中回滚的修改不会作为已提交事件同步，实际表引擎仍须核对。“行镜像”是一行数据变更前或变更后的字段值。在 `ROW`、`FULL` 前提下，[INSERT](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:231>) 记录新增后的行镜像，[UPDATE](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:235>) 记录修改前后行镜像，[DELETE](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:257>) 记录删除前行镜像。

#### 从所属基线追赶，写入 ES

2 个新库各规划独立的 Canal `destination`（订阅通道标识）。首次订阅计划让对应的 Canal Server 以所属导入前基线初始化读取位置，消费历史导入及后续新表变更；Server 通过原生 RocketMQ 模式发布消息，并维护自身消费进度；RocketMQ 消费与 ES 写入服务另行推进 MQ 消费位点与 ES 处理状态。基线只保存一次起点，不保存事件本身，也不随消费进度更新；从基线衔接 Canal 内部位点的实际方式仍待验证。详见[通道使用方式](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:191>)。

#### 重启时核对日志，从可靠进度接续

通道重启时，先确认所属新库所需的 binlog 仍可读、本通道已确认的可靠进度可信，再从该进度接续；只有确认通道从未推进过，才按导入前基线首次初始化。接续所需日志已过期、进度丢失或无法核实处理状态时应暂停并人工复核，不能拿 MySQL 最新 GTID 跳过缺口，也不能用新的快照覆盖原有基线。详见[重启核对条件](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_MySQL binlog 与同步起点_详解.md:215>)。

### 详细方案

#### binlog 是什么

`binlog`（binary log，二进制日志）是 MySQL 记录数据库变化的日志。建表、修改表结构、数据增删改及事务提交等信息都可以记录在其中。本项目关注消费记录表的 INSERT、UPDATE、DELETE。Canal 读取已提交的行事件，将它们作为后续同步到 ES 的输入。

事务执行期间，MySQL 先在事务内部处理数据变更；事务提交成功后，相关 binlog 事件才作为已提交变更交给下游读取。如果事务回滚，这次修改就不生效，也没有对应的已提交行事件可供 Canal 同步。

#### ROW 与 FULL 分别控制记录方式和字段范围

`binlog_format=ROW` 表示对数据增删改按受影响的行记录变化；建表、改表等仍有其他类型的事件。`binlog_row_image` 决定行事件保留哪些字段值，设为 `FULL` 时会记录完整的行数据。“行镜像”就是一行数据在变更前或变更后的字段值，不是整张表的备份。

以下示例以项目 MySQL 8.0.33、`binlog_format=ROW` 和 `binlog_row_image=FULL` 为前提。SQL、表名、列名和事件文本仅作示意，未连接数据库执行；实际 binlog 是二进制事件，不能直接当作 SQL 脚本执行。若使用其他行镜像配置，是否包含同步到 ES 所需字段必须另行核实。

#### Canal 同步 ES 需要哪些信息，如何获取和使用

MySQL 自己保存 binlog 文件，不需要把每条行变更另存到配置或控制表。需要分清可发布的配置、不可覆盖的基线和随处理结果推进的进度：

| 信息 | 如何获取 | 存放位置 | 何时由谁使用 |
|---|---|---|---|
| MySQL 的 `server-id`、`log_bin`、GTID 模式、`binlog_format=ROW`、`binlog_row_image=FULL` 和日志保留期 | 核对实际加载的 `mysqld` 配置与实例 `GLOBAL` 值；导入和业务写入连接还要查 `SESSION` 值及 `sql_log_bin` | 2 个新库 MySQL 实例配置；核对结果留存，不能用 DBA 的会话值代替业务会话 | DBA 在历史导入、恢复写入前确认产生日志的条件；Canal Server 随后连接实例读取 binlog |
| ES 同步的导入前基线（2 个新库各 1 份） | 停写后、历史导入前，分别读取 `@@GLOBAL.server_uuid`、完整 `@@GLOBAL.gtid_executed` 和采集时间 | 建议按来源实例和 ES 同步用途保存到 MySQL 控制表，不可覆盖 | 对应 Canal Server 首次从所属新库的导入前基线追赶历史导入和后续新表变更 |
| 每个 ES 同步通道的可靠进度 | Canal 发布进度、MQ 消费位点与 ES 结果分开取得，不能直接取当前 gtid_executed | 2 个 destination 独立维护，Canal 游标与处理日志的实际持久化及衔接仍待验证 | 恢复对应层的进度，不覆盖导入前基线，不把 ES 未追平当作 Canal 任意回退的依据 |
| MySQL 地址、复制账号、`destination`（订阅通道标识）及表过滤 | 根据实际实例拓扑、授权和物理库表名核定；首次起点来自所属新库的导入前基线 | 本地 `canal.properties` 和各 destination 的 `instance.properties`；不使用 Canal Admin 和管理库，凭据安全注入方式待核对 | Server 启动所属订阅并读取行事件；2 个新库不能共用起点和进度 |
| Server 地址、destination、消息契约和 ES 字段与索引路由 | 根据原生 RocketMQ 通道、实际 DDL、007 消息契约和 008 索引规则核对 | Server RocketMQ 配置和 ES 写入服务分别配置，接入待验证 | Server 原生生产端发 MQ；ES 写入端定位并写物理索引，不把导入前 GTID 当作 MQ 消费进度 |
| ES 同步开关、批量大小等运行参数 | 根据同步链路拓扑和实际负载核定 | 候选方案由 Nacos 发布；接入与生效方式待验证，不作为基线和进度的唯一存储 | 启停或调整 ES 同步通道时读取；发布配置不代表消费进度已经推进 |

#### MySQL 核心配置模板

当前工作区没有实际服务器配置文件，下面是按项目 MySQL 8.0.33 规则整理的 `mysqld` 配置模板。实际文件可能位于 `/etc/my.cnf`、`/etc/mysql/my.cnf` 或发行版的 `mysqld.cnf`；修改前须确认 `mysqld` 实际加载的文件。被 Canal 订阅的 2 个新库实例分别核对配置，`server-id` 在复制拓扑内须唯一；模板中的 `101` 仅为示例值。

```ini
[mysqld]

# 每个被订阅的 MySQL 实例使用不同的值；不能都配置成 101。
server-id=101

# 开启二进制日志。Canal 只能读取已经写入 binlog 的已提交变更。
log_bin=mysql-bin

# 启用 GTID；ES 同步的导入前基线和已确认进度仍须另行保存。
gtid_mode=ON
enforce_gtid_consistency=ON

# 让 INSERT、UPDATE、DELETE 按受影响的行记录变化；DDL 等仍有其他事件。
binlog_format=ROW

# 保留 UPDATE 的修改前、修改后镜像，以及 DELETE 的删除前镜像。
# ES 映射所需字段及删除定位字段 consumeId 仍需用实际事件核对。
binlog_row_image=FULL

# 30 天（2592000 秒）只是候选保留期，不是已核定的生产配置。
# 投产前须按 ES 同步链路的停读、追赶时间和实际 binlog 占用核算。
binlog_expire_logs_seconds=2592000
```

2 个新库须分别核对：ES 同步通道最长停读、故障修复、追赶时间加安全余量应小于 30 天；磁盘空间应覆盖实测 ROW/FULL 日志增量和历史导入峰值。持续监控同步进度及最早可读日志，不能指望 MySQL 因下游滞后而自动延长保留期。

上述配置只描述 MySQL 产生日志的前置条件，不包含 Canal Server 的 `canal.properties`、`instance.properties` 或 Java 消费端代码；Server 配置和消息解析职责见 Canal Server 原总览（原文档已删除，依据待补充），ES 写入端见 [026_ES改造_自定义消费服务_自定义 ES 消费服务处理逻辑](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/026_ES改造_自定义消费服务_自定义 ES 消费服务处理逻辑.md:1>)。配置文件路径、`server-id`、日志保留秒数和重启结果均待实际环境核实，不能把模板视为已部署配置。

#### 配置核对 SQL

DBA 在 Canal 订阅的 2 个新库分别核对实例的 `GLOBAL` 配置，并保存核对结果。`SESSION` 只反映执行查询的连接：导入会话和实际新表写入连接须分别核对，DBA 自己的连接不能代替业务连接。此处读到的 `gtid_executed` 仅反映核对当时的已执行事务集合，不能代替在历史导入前采集的 ES 同步起点。

```sql
SELECT
    @@GLOBAL.server_uuid AS server_uuid,                         -- 区分来源实例；不能跨实例复用
    @@GLOBAL.server_id AS server_id,                             -- 检查复制拓扑内是否唯一
    @@GLOBAL.log_bin AS log_bin,                                 -- 必须为 ON
    @@GLOBAL.gtid_mode AS gtid_mode,                             -- 必须为 ON
    @@GLOBAL.enforce_gtid_consistency AS enforce_gtid_consistency,-- 必须为 ON
    @@GLOBAL.binlog_format AS global_binlog_format,              -- 实例默认格式
    @@SESSION.binlog_format AS session_binlog_format,            -- 当前连接实际格式，必须为 ROW
    @@GLOBAL.binlog_row_image AS global_binlog_row_image,        -- 实例默认行镜像
    @@SESSION.binlog_row_image AS session_binlog_row_image,      -- 实际写入连接须使用 FULL
    @@SESSION.sql_log_bin AS session_sql_log_bin,                -- 实际写入连接须保持 ON
    @@GLOBAL.gtid_executed AS gtid_set;                           -- 仅供核对；同步起点须在导入前另行采集
```

只有实例的 `log_bin=ON`、`gtid_mode=ON`、`enforce_gtid_consistency=ON`，实际写入会话使用 `ROW`、`FULL` 且 `sql_log_bin=ON`，并核对了下游所需字段后，才能进入历史导入或恢复写入。若导入会话的 `sql_log_bin=OFF`，其变更不会进入 binlog，DBA 须保持停写并按变更流程处理，不能用导入后的最新位点补回缺失事件。

须结合真实 DDL、ES 映射和行事件核对同步所需字段：物理删除时应能从新表 DELETE 前镜像取得 `consumeId`，以定位 ES 文档。实例参数和会话值正确，也不能代替字段核对。

#### GTID 基线是实例级事务集合快照

MySQL 用来源 UUID 和事务编号标识已执行事务；多个事务及多个来源 UUID 的编号区间组成 GTID set（GTID 集合）。一份基线是某个实例在规定时点的完整集合快照，不是单个最大事务号。2 个新库实例须各存一份，不能合并。按当前规划，每个 ES 同步 `destination` 使用所属新库的导入前基线，实际绑定仍待结合 Canal 发行包和环境验证。

#### 采集导入前 GTID，并区分 ES 同步进度

配置核对通过、停写后且历史导入前，DBA 在 2 个新库实例分别读取完整 GTID 集合作为导入前基线，各保存 1 份，不能合并；每个实例执行一次：

```sql
SELECT
    NOW(6) AS captured_at,                 -- 与停写和导入记录核对采集先后
    @@GLOBAL.server_uuid AS server_uuid,   -- 绑定到具体新库实例
    @@GLOBAL.gtid_executed AS gtid_set;    -- 保存完整集合，供对应链路初始化
```

两份导入前基线按实例和 ES 同步用途记录 `server_uuid`、采集时间、对应 `destination` 和完整 `gtid_set`。例如，两条同步通道分别保存以下记录；UUID、时间、通道和集合仅为格式示意：

```json
[
  {
    "instanceId": "consume-new-db-01",
    "serverUuid": "3e11fa47-71ca-11e1-9e33-c80aa9429562",
    "capturedAt": "2026-09-25T10:00:00+08:00",
    "destination": "consume-new-db-01",
    "purpose": "canal-es-sync",
    "gtidSet": "3e11fa47-71ca-11e1-9e33-c80aa9429562:1-128,7c22ab58-82db-22f2-af44-d91bb053a733:1-64"
  },
  {
    "instanceId": "consume-new-db-02",
    "serverUuid": "4f22ab58-82db-22f2-af44-d91bb053a733",
    "capturedAt": "2026-09-25T10:00:03+08:00",
    "destination": "consume-new-db-02",
    "purpose": "canal-es-sync",
    "gtidSet": "4f22ab58-82db-22f2-af44-d91bb053a733:1-96,8d33bc69-93ec-33a3-bf55-e02cc164b844:1-32"
  }
]
```

这些示例值不能代替每个实例在历史导入前读取的实际值；采集时间须与停写、导入记录核对，不能单独证明采集顺序。保存基线不表示对应 binlog 过期后仍可重放。

随后，DBA 向新分片表导入消费记录。实例启用 binlog、导入会话使用 `ROW` 且 `sql_log_bin=ON` 时，新增行产生 INSERT 行事件；Canal 从导入前基线读取这些事件。导入前采集和历史导入的执行记录见[999_历史迁移与数据库验收.md](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/999_历史迁移与数据库验收.md:6>)。

ES 同步启动后，两个 destination 分别核对 Canal 到 MQ 的持久化交接游标、MQ 消费位点与 ES 处理结果，不能直接取 MySQL 当前 `@@GLOBAL.gtid_executed`，也不能覆盖各自的导入前基线。

#### ES 同步的控制表与 Nacos 配置

候选方案按 ES 同步通道和来源新库在 MySQL 控制表中保存不可覆盖的导入前基线快照与当前可靠进度，并记录状态、版本、更新时间和变更审计。首次从所属新库的导入前基线初始化，之后只推进本通道已确认的进度，不把 MySQL 当前 GTID 集合当作进度。

候选方案可用 Nacos 管理 ES 同步通道的运行地址、开关和批量大小，但不能把 Nacos 当作导入前基线和进度的唯一存储。控制表结构、读取逻辑、Canal 内部位点格式及衔接方式仍需结合项目代码和锁定的 Canal 发行包验证；创建表或发布配置不会让 Canal 自动动态读取，也不代表该方案已上线。

#### Canal 读取 MySQL 所需的账号权限 SQL

Canal Server 连接 MySQL 需要复制读取和必要的表结构查询权限。下方授权 SQL 保留为历史候选示例，当前最小库表范围按 006 核验；自定义消费服务是否需要额外回查不得沿用 Adapter 的配置推定。以下是 MySQL 端的候选模板；`<CANAL_SERVER_HOST>`、库名和密码必须替换为实际值，不能使用 `%` 和示例密码直接上线。

```sql
-- 在被订阅的每个 MySQL 实例分别执行；来源地址应是 Canal Server 的实际网络地址。
CREATE USER 'canal_repl'@'<CANAL_SERVER_HOST>'
IDENTIFIED BY '<REPLACE_WITH_SECRET>';

-- 复制客户端读取 binlog 和实例复制元数据所需的候选权限。
GRANT REPLICATION SLAVE,
      REPLICATION CLIENT
ON *.* TO 'canal_repl'@'<CANAL_SERVER_HOST>';

-- Canal 读取表结构和行字段时使用；库名按当前实例实际名称替换。
GRANT SELECT ON consume_db0.*
TO 'canal_repl'@'<CANAL_SERVER_HOST>';

-- 核对授权结果；不要只看 CREATE USER 是否成功。
SHOW GRANTS FOR 'canal_repl'@'<CANAL_SERVER_HOST>';
```

账号来源地址、复制权限和 `SELECT` 范围仍需按 MySQL 8.0.33 实际环境做最小权限核验；权限授予成功也不代表 Canal 已能从正确 GTID 基线开始消费。账号密码应由密钥管理或部署系统注入，不能写入仓库或提交到文档中的真实值。

#### Canal Server、RocketMQ 和 ES 写入服务如何使用这些信息

历史验收通过、复制账号与配置就绪后，首次启动须通过 Canal 支持的起点配置或适配代码，把所属新库的导入前基线提供给对应 Server 通道；候选配置使用 `canal.instance.gtidon=true` 和 `canal.instance.master.gtid`。先验证 `destination` 与实例的绑定，以及该基线和已有内部位点的优先关系；确认能从导入前基线读取历史导入事件后，再继续追赶新表变更。应用启动和同步追赶不等于开放业务写入，恢复业务仍按第 1 轮上线条件执行。Canal Server 按原生 RocketMQ 模式发布消息，自定义消费端按 009 的来源顺序、物理索引和字段契约写 ES。

Canal Server 原生 RocketMQ 模式的发布结果、Broker 持久化和 Canal 位点持久化需分别核对。ES 写入端检查 Bulk item 与处理日志后再完成 MQ 消费，不能用 ES 成功反推 Canal 位点已持久化；失败不能取最新 GTID 跳过缺口。从控制表装载导入前基线、把可靠进度与 Canal 内部位点衔接，或在线更新运行配置，都需要额外接入和验证。映射和失败恢复分别见 [026_ES改造_自定义消费服务_自定义 ES 消费服务处理逻辑](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/026_ES改造_自定义消费服务_自定义 ES 消费服务处理逻辑.md:1>)、[011_批次失败与同步恢复.md](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/011_批次失败与同步恢复.md:6>)。

#### 重启时先核对日志与进度

ES 同步通道重启时，分别核对所属新库的基线、Canal 持久化交接游标、MQ 留存与消费位点、ES 处理结果，确认相应日志仍可读，再从对应层的可靠进度恢复。只有确认通道从未推进过可靠进度，才按导入前基线首次初始化；若已推进的进度丢失，或无法确认日志连续性、事务完整性、批次状态，应暂停并人工复核，不能用最新位点跳过缺口。控制表与 Canal 内部位点如何保持一致仍待实现验证。

#### SQL、行与 ROW 事件如何对应

一个事务可执行 1 条或多条 SQL；一条 INSERT、UPDATE 或 DELETE 可实际变化 0 行、1 行或多行。没有行变化，就没有这条 SQL 对应的 ROW 行变更事件；有变化时，一个事件可记录多行，同一条 SQL 的变化也可能分散在多个事件中。因此，不能按 SQL 条数或实际变化行数推算 ROW 行变更事件数。这里的“行变更事件”不包括事务边界等其他 binlog 事件。

例如，一条 UPDATE 实际修改同一张表的 1,000 行，MySQL 可能用 3 个 `Update_rows` 事件依次记录 400、400、200 行的修改前后数据。这只是“一条 SQL 对应多个事件”的示意；实际拆分还与每行数据大小等因素有关，并不固定按这个行数划分。

#### INSERT 行事件记录新增后的行镜像

INSERT 行事件记录新增后的行数据，没有修改前镜像。例如，`INSERT INTO consume_record (consume_id, amount) VALUES ('1001', 100.00), ('1002', 200.00);` 一次新增 2 行，同一个 `Write_rows` 事件可能包含这 2 行的新增后数据，也可能分散在多个 `Write_rows` 事件中。

#### UPDATE 行事件记录修改前后镜像

假设某条消费记录的金额从 `100.00` 改为 `120.00`：

```sql
UPDATE consume_record
SET amount = 120.00
WHERE consume_id = '1001';
```

对应的 UPDATE 行事件可以理解为：

```text
操作：UPDATE
表：consume_record

修改前：consume_id=1001，amount=100.00，其他列……
修改后：consume_id=1001，amount=120.00，其他列……
```

`binlog_row_image=FULL` 表示 UPDATE 事件包含完整的修改前、修改后行镜像；“其他列……”只是省略展示，不表示 FULL 模式只记录主键和金额。

#### DELETE 行事件记录删除前镜像

DELETE 行事件记录删除前的数据，没有删除后镜像。在 `FULL` 配置下，应验证能从删除前镜像取得 `consumeId`，供 RocketMQ 消费与 ES 写入服务定位原物理索引并维护删除后的来源顺序。删除后再查询新表可能已经取不到该值，因此不能依赖查询补回。

本问题是[006_ES改造_整体链路_Canal 同步 MySQL binlog 到 ES（总链路）.md](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/006_ES改造_整体链路_Canal 同步 MySQL binlog 到 ES（总链路）.md:1>)中 MySQL 日志来源、同步起点与进度的延伸说明。

**已确认规则**：本次按用户确认重命名总链路、MySQL 处理逻辑及 ES 文档如何写入与检索三份文档，同步名称标识和关联引用；不调整技术结论。



## 问题列表

- 文档中的待确认、示例或未验证内容需继续与当前项目技术栈和主链路核对；不能把参考材料、演示参数或静态推导写成已部署或已验收事实。
