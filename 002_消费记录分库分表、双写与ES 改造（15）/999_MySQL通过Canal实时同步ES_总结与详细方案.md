# MySQL 通过 Canal 实时同步 Elasticsearch：文章总结与详细方案

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

本文整理用户提供的《利用 canal 进行 MySQL 到 ES 的数据实时同步》文章。原文发表于 2023-11-29，以 ibds2.proof_data_history_202311 表为例，演示 Canal 1.1.7 Deployer、Canal Adapter 以及 Elasticsearch 7 的配置、全量初始化和增量同步。本文保留文章的可复用思路，同时把示例地址、账号、密码和环境结果改为占位或待验证项，不把文章演示结果写成 E 账通已经上线的能力。

## 1. 总结内容

### 1.1 要解决的问题

业务数据写入 MySQL 后，如果查询直接扫描千万级业务表，查询耗时和数据库资源压力会随着数据量增长。文章给出的解决思路是：让 Elasticsearch 保存一份面向查询的副本，业务数据提交后由 Canal 订阅 MySQL 的 binlog（二进制日志），再把行变更转换成 ES 文档。

这条链路是异步的近实时同步，不是 MySQL 事务与 ES 写入的分布式原子事务。MySQL 事务提交、Canal 消费、ES 写入成功、ES 搜索可见和数据校验通过是不同状态，不能用其中一个状态替代其他状态。

### 1.2 核心链路

~~~
MySQL 提交事务并产生 ROW binlog
        ↓
Canal Server 以复制客户端方式读取并解析 binlog
        ↓
Canal Adapter 通过 TCP 获取 Canal 消息，按映射转换数据
        ↓
Elasticsearch 执行文档新增、更新或删除
        ↓
refresh 后文档对搜索请求可见
~~~

Canal Server 负责连接 MySQL、读取和解析日志，不负责 ES 字段映射；Canal Adapter 负责按配置匹配库表和事件、组装 ES 操作并提交；ES 负责按具体索引、文档 _id 和 Mapping 处理请求。Adapter 的 JDBC 数据源用于映射 SQL 需要的业务查询，不等于 binlog 订阅连接。

### 1.3 文章方案的主要结论

1. MySQL 必须开启 binlog，并使用 ROW 格式；复制账号至少需要订阅所需的复制权限。
2. Canal Server 通过 master.address、GTID 或文件位点连接并定位 MySQL 日志；配置中的起点留空不代表自动完成历史全量同步。
3. Canal Adapter 需要先准备 ES 索引，再配置 es7 外部适配器和表到索引的映射文件。
4. 映射文件中的 _id 应使用稳定且唯一的业务主键，避免同一业务记录被重复写成多篇文档。
5. 已有 MySQL 数据时，可以通过 Adapter 的 ETL 接口执行一次全量导入；全量导入之后仍要让增量日志追赶并做数据校验。
6. 文章给出的 120 万行约 86～87 秒只是其测试环境结果，不能作为当前项目的容量承诺或生产 SLA。

### 1.4 与当前项目的版本基线

当前项目固定使用 Java 8、Spring Boot 2.7.18、rocketmq-spring-boot-starter 2.3.0、org.apache.rocketmq:rocketmq-client 5.2.0、MySQL 8.0.33、Elasticsearch 7.17.15、Canal Server/Adapter 1.1.7。文章使用 MySQL 8.0.35、Elasticsearch 7.17.9、Canal 1.1.7 和 JDK 8；其中 Java 版本相同，其他版本不能直接视为兼容。项目已确定 Canal 1.1.7 版本边界，实际发行包、MySQL 驱动、认证方式和 ES 适配器仍必须在联调环境中核对。源码核对链接采用 `canal-1.1.7` 标签。

当前项目主链路见 005～009：一个单机 Canal Server 进程承载 db0、db1，分别维护本地进度，采用原生 RocketMQ 模式发布消息，RocketMQ 消费与 ES 写入服务负责历史索引定位和写入。下文的 Adapter 接入、映射、ETL 和性能结果为文章方案说明，不能作为当前主链路配置。

### 已确认规则

- 当前项目的 Canal Server 与 RocketMQ/ES 参考文章保持单机部署方式一致，使用 file-instance.xml 本地位点模板，不配置 ZooKeeper 主备，故障后人工核对并重启恢复。
- 本文整理的 Adapter、ETL、文章版本及性能结果保留为参考方案，不能视为当前主链路的配置或运行结果。

## 2. 详细方案

### 2.1 先确认拓扑、字段和安全边界

**确定同步范围**

文章只演示一个 MySQL 库中的一张表。当前项目有 2 个独立的新库实例，每个实例 16 张物理表，共 32 个分片。当前配置 db0、db1 两个独立 destination，由一个单机 Server 进程承载，再经 Server 原生 RocketMQ 模式和 ES 写入服务写入 ES；Adapter 双链路仅保留作文章方案对照。

**核对字段契约**

文章选择 id、biz_id、biz_type、encrypt_mode、sign_user、tx_hash、from_addr、data、channel_id、block_height、tx_time 等字段。当前项目不能直接复用这份字段清单，应以新表 DDL、业务字段含义和 [013_ES Mapping 设计](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/013_ES Mapping 设计.md:5>) 为准。项目规划使用 consumeId 作为稳定文档标识，tradeAmount 的单位为分，tradeTime 支撑排序和范围查询；完整字段仍需完成核对。

**准备访问策略**

Adapter 或 Server 到 MySQL 开放实际数据库端口；Adapter 到 ES 开放 REST 9200 或经过验证的 Transport 9300；Adapter 到 Server 开放 TCP 11111，必要时按发行包开放 11110、11112。规则应限制到具体源地址、目标地址和端口，不复制文章中的明文密码、宽泛来源地址或默认账号。

| 源端 | 目标端 | 目的和端口 |
|---|---|---|
| Canal Server | 对应 MySQL 实例 | 读取 binlog，开放实际 MySQL 地址和端口 |
| Canal Adapter | 对应 MySQL 实例 | 执行映射 SQL，开放实际 MySQL 地址和端口 |
| Canal Adapter | Canal Server | 接收 Canal 消息，通常使用 11111；11110、11112 是否需要开放以发行包为准 |
| Canal Adapter | Elasticsearch 集群 | 写入 ES，使用已验证的 REST 9200 或 Transport 9300 |

### 2.2 配置 MySQL，使 Canal 能读取行变更

**启用 ROW binlog 和 GTID**

每个新库实例都要单独核对 server_id、binlog、GTID 和行镜像配置。候选配置如下，数值仅为模板：

~~~
[mysqld]
server-id=<每个实例唯一的值>
log_bin=mysql-bin
gtid_mode=ON
enforce_gtid_consistency=ON
binlog_format=ROW
binlog_row_image=FULL
binlog_expire_logs_seconds=<按所有同步和恢复场景计算>
~~~

ROW 记录受影响行的变化；binlog_row_image=FULL 让 INSERT、UPDATE、DELETE 具备校验和删除定位所需的完整行镜像。日志保留时间必须覆盖历史导入、同步追赶、两项校验、重试和故障恢复的最长时间，不能只按日常消费速度设置。

执行变更前后检查实际生效值：

~~~
SHOW VARIABLES LIKE 'server_id';
SHOW VARIABLES LIKE 'log_bin';
SHOW VARIABLES LIKE 'gtid_mode';
SHOW VARIABLES LIKE 'enforce_gtid_consistency';
SHOW VARIABLES LIKE 'binlog_format';
SHOW VARIABLES LIKE 'binlog_row_image';
SHOW VARIABLES LIKE 'binlog_expire_logs_seconds';
~~~

**创建最小权限账号**

使用专用 Canal 账号，并按实际连接来源授予最小权限。密码应放在受控的密钥配置中，不写入文档、脚本或日志。文章中的演示密码、slave_skip_errors=1062 和宽泛账号来源不能直接作为生产配置；跳过复制错误会掩盖数据缺口，不能替代失败重试和校验修复。

**保存每个实例的 GTID 基线**

历史导入前，在每个新库实例分别执行并持久化：

~~~
SELECT @@GLOBAL.server_uuid AS server_uuid,
       @@GLOBAL.gtid_executed AS gtid_set;
~~~

每个实例保存一份完整 GTID 集合快照，并记录实例标识、采集时间、对应 destination 和用途。两份集合不能合并成一份；Nacos 可以保存运行地址和开关，但不能成为基线和消费位点的唯一存储。控制表或其他可靠持久化方案需要结合项目实现确认。

### 2.3 部署并配置 Canal Server

**准备发行包和目录**

文章使用 canal.deployer-1.1.7.tar.gz，并将其解压到 /data/canal/Server。该段仅描述文章示例；当前项目采用 Canal 1.1.7，正式部署仍要核对可校验的官方发行包及 Java 8、MySQL 8.0.33 和认证方式。

**配置 instance.properties**

每个 MySQL 实例使用自己的 destination 和配置文件。示例字段如下：

~~~
# 不能与被订阅 MySQL 的 server-id 冲突
canal.instance.mysql.slaveId=<唯一值>

# 项目候选方案使用 GTID；具体发行包行为待联调
canal.instance.gtidon=true

canal.instance.master.address=<MySQL_HOST>:<MySQL_PORT>
canal.instance.dbUsername=<Canal专用账号>
canal.instance.dbPassword=<从密钥配置读取>
canal.instance.connectionCharset=UTF-8

# 正式环境应收窄到实际库表，下面只是文章中的宽范围示例
canal.instance.filter.regex=<数据库名>\\..*
~~~

master.address 是被订阅的 MySQL 地址，不是 Canal 所在机器的地址；master.journal.name、master.position、master.timestamp 和 master.gtid 表示不同的起点方式。它们留空只说明没有在这些字段中显式指定起点，不能推出“从最早日志开始”或“历史数据已全量同步”。启动时应从保存的 GTID 基线或可靠消费位点恢复，并验证发行包对多种起点同时配置时的优先级。

**启动和检查**

启动脚本、服务管理方式和实际目录以发行包及部署平台为准。启动后检查 Server 日志，至少确认：连接到了预期 MySQL、加载了预期 destination、订阅范围正确、起点可读取。日志中出现“找到起始位置”只能证明 Server 找到可读位置，不能证明 ES 已经写入或历史范围已经覆盖。

### 2.4 配置 Canal Adapter，将变更映射到 ES

**配置连接和批次参数**

文章使用 TCP 模式，Adapter 通过 11111 连接 Server，并配置 JDBC 数据源和 es7 外部适配器；这条文章链路没有新增 RocketMQ 传输。可参考下面的结构，具体属性名必须以锁定发行包的示例配置为准。若项目最终选择 RocketMQ 模式，需要另行确定 Topic、消费组、重试和确认边界，不能由本文的 TCP 配置推导。

~~~
server:
  port: <Adapter管理端口>
spring:
  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: GMT+8
    default-property-inclusion: non_null

canal.conf:
  mode: tcp
  flatMessage: true
  syncBatchSize: 1000
  retries: -1
  consumerProperties:
    canal.tcp.server.host: <Canal_Server_HOST>:11111
    canal.tcp.batch.size: 500
  srcDataSources:
    defaultDS:
      url: jdbc:mysql://<MySQL_HOST>:<MySQL_PORT>/<数据库名>?useUnicode=true&characterEncoding=UTF-8
      username: <Adapter只读账号>
      password: <从密钥配置读取>
  canalAdapters:
    - instance: <destination>
      groups:
        - groupId: g1
          outerAdapters:
            - name: es7
              hosts: <ES_HOST>:9200
              properties:
                mode: rest
                cluster.name: <ES集群名>
~~~

原文示例使用 hosts: ...:9300 和 mode: transport。Elasticsearch 7.17.15 是否采用该方式，取决于 Adapter 发行包、客户端依赖和集群是否开放 Transport 端口；在未完成联调前，不能把 9200 REST 或 9300 Transport 任一方式写成项目已确定配置。

canal.tcp.batch.size、syncBatchSize 和映射文件的 commitBatch 表示不同层面的批处理候选值，不能直接相加得出 ES 文档数。Adapter 是否把一个 Canal Message 拆成多少条 _bulk 子请求、何时确认原始批次，需要通过发行包源码或测试日志核对。

**编写映射文件**

文章创建 conf/es7/proof_202311.yml，把 Canal 的 example destination 和 g1 分组映射到 proof_data_history_202311 索引：

~~~
dataSourceKey: defaultDS
destination: <destination>
groupId: g1
esMapping:
  _index: proof_data_history_202311
  _id: id
  upsert: true
  sql: >-
    SELECT id, biz_id, biz_type, encrypt_mode, sign_user, tx_hash,
           from_addr, data, channel_id, block_height, tx_time
    FROM proof_data_history_202311
  commitBatch: 3000
~~~

这里的 id 只是文章示例。当前项目要使用经过确认的业务主键，例如 consumeId，并让新增、更新、删除都定位同一个具体索引中的同一文档。若使用 rollover 写别名，不能假设当前别名会自动找到历史索引中的旧文档；更新、删除和重试必须保留具体索引定位信息。

映射 SQL、表名、字段类型、是否需要 JDBC 回查以及删除事件的字段来源都要结合实际 DDL 核对。_type: _doc 是旧式类型写法，在 Elasticsearch 7 中不应作为新的业务类型层级使用；是否需要保留该字段由 Adapter 版本决定。

### 2.5 先创建 ES 索引，再启动 Adapter

**创建索引和 Mapping**

文章建议在 Adapter 启动前创建 proof_data_history_202311 索引，并为 id、业务编码、区块高度和时间字段配置类型。创建索引前应确认：

- 项目字段是否用 keyword 进行精确匹配，是否需要 text 分词；
- 金额是否按“分”使用 long，避免把金额默认映射成 double；
- 时间字段是否统一时区和格式；
- dynamic: strict 下是否会因为漏配字段而使 _bulk 某一项失败；
- rollover、别名、删除和重试是否能定位原具体索引。

当前项目 Mapping 仍以 [013_ES Mapping 设计](<D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/013_ES Mapping 设计.md:5>) 为准。索引创建成功后，再启动 ES 写入服务并检查原生 RocketMQ 订阅、消息解析和索引路由是否按候选配置生效。

### 2.6 执行历史全量初始化

**确定全量导入的前置条件**

如果 ES 索引为空或历史数据尚未覆盖，先确认索引和 Mapping 已创建、Adapter JDBC 数据源可查询、SQL 字段与 Mapping 一致，并确认 MySQL binlog 保留期足够覆盖全量导入和后续追赶。历史导入前保存的 GTID 基线不能被全量导入过程中的最新位点覆盖。

**调用 ETL 接口**

文章使用以下接口导入：

~~~
curl -X POST http://<Adapter_HOST>:<管理端口>/etl/es7/proof_202311.yml
~~~

该接口是文章示例中的一次性全量读取，不等于自动建立可靠的增量消费起点。实际执行时要记录开始时间、结束时间、返回条数、失败信息和对应 Mapping 文件版本。全量导入完成后，仍需让 Adapter 从原始 GTID 基线追赶导入期间产生的变更，再执行数量、关键字段和删除数据校验。

文章记录 1,200,013 行分别耗时约 86 秒和 87 秒。该结果受硬件、网络、JDBC、ES refresh、分片数量和批次参数影响，只能作为文章环境的参考，不能用于承诺当前项目的同步延迟或迁移窗口。

### 2.7 运行增量同步

**按事务顺序处理 ROW 事件**

业务事务提交后，MySQL 产生 INSERT、UPDATE 或 DELETE 的 ROW 事件。Canal Server 按 binlog 顺序解析为消息；TCP 客户端一次获取的 Message 可能包含多个 Entry，一个行变更 Entry 也可能包含多行 RowData。Adapter 按库表和事件类型匹配映射，组装 ES index、update 或 delete 操作。

**按稳定 _id 写入 ES**

稳定 _id 能让同一具体索引内的重复写入覆盖同一文档，但不能单独保证重放幂等。还必须保持事件顺序、记录具体索引、正确处理更新后的字段和删除前镜像。ES 返回成功只代表请求处理结果；refresh 之后才可由普通搜索看到，数据是否与 MySQL 一致还要由独立校验判断。

**确认批次前处理完所有子项**

如果 Adapter 组装 _bulk 请求，应逐项检查响应。一个子项失败时，不能因为同一批次其他子项成功就确认原 Canal 批次；需要保留失败事件、具体索引、文档 _id 和错误原因，按已验证的重试或人工修复流程处理。文章未给出部分失败、重放和死信实现，因此这些能力不能视为已完成。

### 2.8 异常恢复和运行监控

**Canal Server 异常**：检查 MySQL 连接、binlog 是否仍在保留期、GTID 或文件位点是否可恢复。恢复时使用本链路可靠保存的消费进度，不用恢复时的最新 GTID 覆盖历史基线。

**Adapter 异常**：检查 TCP 连接、JDBC 查询、映射文件、ES 认证和批量响应。映射字段缺失、dynamic: strict 拒绝未知字段、ES 单项失败都应进入可追踪的失败处理，不得静默丢弃。

**ES 异常**：检查索引是否存在、写别名和具体索引是否正确、Mapping 是否兼容、集群是否拒绝请求。若发生 rollover，更新和删除不能只重试当前写别名，需要依据事件或业务数据找到原文档所在的具体索引。

**监控指标**：至少记录各 destination 的消费位点、binlog 延迟、批次成功和失败数量、ES _bulk 单项失败、重试数量、JDBC 慢查询、索引拒绝和校验差异。监控恢复后仍要通过校验任务证明数据已追平。

### 2.9 验收顺序

| 阶段 | 操作 | 通过条件 |
|---|---|---|
| 配置核验 | 检查 MySQL、Server、Adapter、ES 地址和凭据 | 连接对象与预期实例一致，敏感信息未写入文档和日志 |
| 索引准备 | 创建索引、Mapping 和别名 | Mapping 与已确认字段契约一致，索引可写 |
| 全量初始化 | 执行 ETL，记录条数和失败项 | 全量范围明确，失败项可追踪，数量和抽样字段符合预期 |
| 增量新增 | 插入一条测试数据 | 指定具体索引出现对应 _id，搜索可见且字段正确 |
| 增量修改 | 修改可检索字段和展示字段 | 同一 _id 文档更新，旧值不再返回 |
| 增量删除 | 删除源表记录 | ES 中对应文档按项目约定删除或标记，不能只验证 MySQL 已无数据 |
| 重启恢复 | 在存在未追平事件时重启 Server 或 Adapter | 从可靠位点继续，未出现不可解释的缺口或重复 |
| 部分失败 | 人为制造单项 ES 或网络失败 | 原批次不被错误确认，失败项可重试并最终校验通过 |
| 业务验收 | 运行新表与 ES 增量校验 | 固定一批、等待同步、比较，整批一致后才继续 |

当前项目还需要独立运行“旧表与新表增量校验”。文章的 ES 增删改演示只能验证示例链路，不能替代两项校验、历史范围验收、回退开关和生产查询切换验收。

### 2.10 可选使用 Canal Admin

文章可额外部署 Canal Admin，初始化管理库后通过 Web 界面管理 Server 节点、destination 和配置。Admin 是配置和运维入口，不承担 MySQL 到 ES 的数据写入；Admin 不可用时，已部署的 Server 和 Adapter 是否继续运行取决于具体发行包和本地配置。启用 Admin 前要核对管理库、账号、权限、注册方式和高可用策略，不能沿用文章中的默认账号密码。

## 3. 文章方案与当前项目的差异

| 项目 | 文章示例 | 当前项目处理方式 |
|---|---|---|
| 数据来源 | 单个 ibds2 库的一张表 | 2 个独立新库实例，每库 16 张表；destination 和链路需按实例规划 |
| MySQL | 8.0.35 | 8.0.33；需核对 GTID、ROW、完整行镜像和日志保留 |
| Elasticsearch | 7.17.9 | 7.17.15；Mapping、别名、rollover 和 _id 规则以项目文档为准 |
| Canal | 1.1.7 | Server/Adapter 版本边界已确定，主链路使用 Server 原生 RocketMQ 模式；实际发行包和认证待验证 |
| 连接方式 | Adapter TCP 消费，ES 示例使用 Transport 9300 | 当前由 Canal Server 原生 RocketMQ 生产端发布消息；自定义 RocketMQ 消费与 ES 写入服务按 ES7 REST API 编写示例，实际客户端与连接待验证 |
| 文档 ID | SQL 中的 id | 规划使用 consumeId；必须保持同一具体索引内稳定定位 |
| 全量初始化 | /etl/es7/proof_202311.yml | 当前按历史导入前 GTID 基线追赶导入事件，不把文章的 Adapter ETL 接口作为主链路步骤 |
| 校验 | 文章只验证增删改查询 | 项目另有旧表与新表、新表与 ES 两项独立校验 |
| 性能结论 | 120 万行约 86～87 秒 | 仅为文章环境结果，当前项目需按实际硬件、索引和批次重新压测 |

## 4. 落地前待确认事项

1. 核对 Canal Server 1.1.7 发行包原生 RocketMQ 模式、自定义消费服务的依赖树、MQ Broker 和 ES 客户端；文章 Adapter 发行包另作参考验证。
2. 按两个独立 MySQL 实例、单机 Server 和两个 destination 的拓扑，验证本地位点持久化、进程故障影响及人工重启恢复；文件丢失或日志断档时暂停复核。
3. 根据新表 DDL 完成字段清单、consumeId、serialNo、金额单位、时间格式和删除事件字段的 Mapping 契约。
4. 确定索引命名、写别名、rollover 和历史索引更新/删除的定位方案。
5. 核对原生 RocketMQ 消息格式、发布结果、稳定事件 ID、顺序消费、ES Bulk item 和各阶段确认；Adapter syncBatchSize/commitBatch 仅属文章参数。
6. 演练网络中断、ES 部分失败、JDBC 查询失败、进程重启、binlog 保留不足和重复消费，并把结果写入上线验收记录。
7. 补齐密钥管理、最小权限、审计日志、监控告警和失败事件保存方案。

## 5. 结论和验证边界

文章可以作为“ROW binlog → Canal Server → Canal Adapter → Elasticsearch”链路的配置入门和全量初始化参考，但不能直接作为当前项目的生产实施方案。文中示例地址、账号、密码、版本、Transport 连接、批次值和 120 万行耗时均不能原样套用。

本文仅完成原文的静态梳理、技术事实校正和项目适配分析，未连接 MySQL、Canal 或 Elasticsearch，未执行全量 ETL、增量消费、重启恢复、_bulk 失败重试和压测。因此，本文不表示项目链路已经运行验证，也不表示版本兼容、数据一致性或生产性能已经验收。



## 问题列表

- 文档中的待确认、示例或未验证内容需继续与当前项目技术栈和主链路核对；不能把参考材料、演示参数或静态推导写成已部署或已验收事实。
