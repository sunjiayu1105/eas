# 007_Canal 同步 MySQL binlog 到 ES（Canal Server 处理逻辑）

以下内容按照当前项目 AGENTS.md 项目级规则中规定的技术栈版本为准。

## 文档维护与已确认规则

以下规则是当前文档已经确认的维护约束，后续修改本文件时必须先核对：

- 面试口述精炼版本只保留当前题目在面试中需要回答的核心内容，使用自然、易懂、适合连续口述的表达。
- 面试口述精炼版本必须位于详细方案之前。
- 详细方案按“先总后分”组织：每个主要知识点先说明核心结论、目标或边界，再展开配置、流程和异常处理。
- Canal Server 部分只描述 MySQL binlog 读取、变更整理、消息交接、消费确认、位点恢复和高可用边界，不在面试口述中展开内部实现类名。
- 当前技术版本已确认：Alibaba Canal Server 1.1.7、Alibaba Canal Adapter 1.1.7（ES 7 Adapter）。
- 当前部署方案已确认：两台 Canal Server 分别部署于不同主机，接入 Canal Admin 中的同一集群，均具备承载 db0、db1 的配置。ZooKeeper 按 destination 协调主备，同一 destination 同一时间只允许一个节点工作；允许两个任务同机运行，不强制绑定分布或整体切换。
- Server 采用 Canal Admin 集中管理：在 Admin 中维护集群公共配置及 db0、db1 的 Instance 配置，节点本地保留接入 Admin 所需的引导配置；不再将手工维护的本地 Instance 文件作为项目配置入口。
- 两个承载分库分表数据的独立 MySQL 实例彼此不是主从关系，分别使用 db0、db1 两个 destination；各自的源库、过滤规则和导入前 GTID 基线配置在 Admin 对应的 Instance 中，不能跨实例复用。
- MySQL binlog 保留期已确认 30 天；文档中不得再写成未确定的候选期限。
- 两个 MySQL 分库实例的物理库名均为 eas，各自包含 consume_record_01～consume_record_16，共 32 张物理分表；db0、db1 是任务名，不是物理库名。
- 官方文档与 canal-1.1.7 标签模板的静态核对结果见第 8、9 节；资料证明机制或属性存在，不等于项目已部署验证。共享 Instance 配置不再固定示例 slaveId，采用官方自动生成机制并核验实际复制身份。
- 当前选用 default-instance.xml，其元数据配置包含 ZooKeeper 持久化组件；不能再用本地位点刷盘概括本方案。“已运行通道位点丢失后是否允许退回初始 GTID”仍待定，不作为自动恢复操作授权。
- 重启、主备切换或重复投递时，消费端须结合稳定业务主键、原具体索引、操作语义和事件顺序实现幂等；仅有稳定主键不足以保证，相关边界见 008、009、010。
- 未经实际发行包或环境核对的配置键名、位点存储方式、高可用切换行为和部署结果，必须标注为待确认，不能写成已验证事实。
- 修改本文件后，要同步检查面试口述、详细方案、版本、部署模式、位点规则、保留期和链接是否前后一致。
- 监控采用 Prometheus 采集、Grafana 展示的方案，按 Server 节点和 destination 分别观察；指标来源与项目核验建议见第 10 节。组件版本、实际部署、采集周期、告警阈值及通知渠道尚未确认，不得写成已上线。
- 异常检测、告警、暂停与人工处置是项目要求，不等于 Canal 默认已实现完整流程；官方模板支持来源及待核验边界见第 7 节。

## 面试口述精炼版本

### 读取原理与职责

Canal Server 通过 MySQL 复制协议模拟从库，读取 binlog（二进制日志），把已提交事务的行变更解析成包含库表、事件类型和行数据的消息，再按批次提供给 Adapter。Server 负责读取、解析和维护消费进度，ES 文档转换与写入由 Adapter 完成。

### 两个分库如何订阅

项目有两个独立 MySQL 分库实例，分别对应 db0、db1 两个 destination，也就是独立订阅通道。每个通道配置自己的源库、表过滤和导入前 GTID 基线；GTID 是全局事务标识，基线记录首次同步前已经执行的事务范围。两个通道独立维护进度，不能共用起点，也没有跨实例的全局事件顺序。

### 消费确认与重复处理

Server 读到日志不代表 ES 写入成功。项目要求 Adapter 检查全部 ES 操作成功后，再发送 ack，也就是批次消费确认，Server 据此推进消费进度。但 Server 无法检查 ES 结果，原生 Adapter 1.1.7 的部分 404 失败和重试耗尽分支可能继续确认，需要补齐处理。即使 ES 已写成功，确认尚未可靠保存时发生故障，也可能重复投递；消费端要结合稳定业务主键、原具体索引和事件顺序保证幂等，也就是重复处理不能破坏正确结果。

### 主备如何切换

方案采用两台不同主机上的 Server，Canal Admin 集中管理配置，ZooKeeper 按 destination 协调主备。同一通道同一时间只有一个活动节点；db0、db1 可以同机运行，也可以分布运行，分别接管，不要求一起切换。因此单台 Server 要按能够承接两个任务的负载规划。故障后备用节点接管，Adapter 重连，期间可能中断并产生积压。

### 从哪里恢复、何时暂停

导入前 GTID 基线用于首次初始化，正常重启和切换优先使用已持久化的消费确认进度。所选模板通过 ZooKeeper 持久化元数据，但确认与周期持久化存在时间差，恢复时仍要核对位点和日志连续性。MySQL binlog 已确定保留 30 天，需要覆盖故障处理和追赶窗口；日志断档或位点无法恢复时应暂停复核，不能跳到最新 GTID。DDL、TRUNCATE 和未知事件也要按项目要求告警、暂停并人工处理，具体检测和处置机制仍待验证。

以上是项目方案及恢复要求，实际配置加载、主备切换和故障恢复尚需联调验证，不能据此承诺零中断或端到端不丢不重。

## 详细方案

### 1. 通过 Canal Admin 集中管理和加载配置

**先说结论：** 参考文章采用 Canal Admin 管理集群、Server 和 Instance，Server 通过本地引导配置接入 Admin；两个 destination 的源库、初始 GTID 和过滤规则分别在 Admin 对应的 Instance 配置中维护。以下是选定的部署方案与候选参数，不代表已经完成安装或验证。

#### 1.1 准备 Admin 及管理数据库

准备 Canal Admin 和独立用途的管理数据库，使用对应发行包的 `canal_manager.sql` 初始化管理库表。管理数据库保存配置和节点信息，不替代两个业务分库，也不等同于消费位点存储。DBA 负责初始化所需权限及运行账号授权，运行凭据由部署环境管理，不沿用文章的默认账号密码。

这里的“独立用途”是职责区分，不代表项目已决定新增一台 MySQL 主机。初始化脚本和 `application.yml` 的用途已有[官方 QuickStart](https://github.com/alibaba/canal/wiki/Canal-Admin-QuickStart)依据，具体部署位置仍待确定。

在 Admin 的 `conf/application.yml` 中配置管理库连接及管理服务参数，启动后验证管理页面和数据库连接。Admin 建议采用 1.1.7，与 Server/Adapter 保持同版；其最终发行包、驱动、认证和部署地址仍待核对，不能将文章的旧版本配置直接视为当前可执行模板。

#### 1.2 创建集群及共享配置

在 Admin 创建集群，填写实际 ZooKeeper 集群地址，并维护集群主配置。参考文章的关键属性如下，具体加载方式和属性有效性须按 1.1.7 发行包核验：

```properties
canal.serverMode=tcp
canal.zkServers=zk1:2181,zk2:2181,zk3:2181
canal.instance.global.spring.xml=classpath:spring/default-instance.xml
```

公共配置负责 TCP 模式、ZooKeeper 连接和实例加载相关参数。db0、db1 通过 Admin 创建并关联集群；实际任务发现、加载及配置更新行为需要验证，不能同时假定另有一套手工本地 destination 列表控制运行任务。

#### 1.3 创建 Server 并接入 Admin

在两台不同主机上分别部署 Canal Server，并在 Admin 中创建对应的两个 Server 节点、关联同一集群，分别填写实际节点地址、管理端口、TCP 端口和监控端口。两个节点均具备承载 db0、db1 的配置。参考文章的常见端口为 11110、11111、11112，最终以发行包和环境为准。对外注册地址必须能被相关组件访问，不能将 `0.0.0.0` 当作客户端连接目标。

各 Server 节点在 `conf/canal_local.properties` 中维护接入 Admin 所需的引导配置。[1.1.7 官方模板](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal_local.properties)已确认包含下列属性；值均须按环境替换，不能直接执行：

```properties
canal.register.ip=<当前节点可达IP>
canal.admin.manager=admin-host:8089
canal.admin.port=11110
canal.admin.user=<管理认证用户名>
canal.admin.passwd=<按官方认证规则生成的密文>
# 若选用自动注册，须填写 Admin 中已经确认的目标集群名
canal.admin.register.auto=true
canal.admin.register.cluster=<目标集群名>
canal.admin.register.name=<当前节点唯一名称>
```

节点可在 Admin 中手工维护，也可按上例自动注册，实际部署前需确定注册方式，避免重复登记。官方指南说明自动注册未指定集群名时默认注册为单机模式；因此必须核对两节点实际归属同一目标集群。管理认证涉及 Server 与 Admin 双向请求，密文配置应与 Admin 中相应原文凭据匹配，不能把登录网页的示例密码直接当作管理接口凭据。

[官方 ServerGuide](https://github.com/alibaba/canal/wiki/Canal-Admin-ServerGuide)明确支持 `sh bin/startup.sh local`，用于选择最小引导配置接入 Admin；本文未执行该命令。这里的 `local` 不表示继续手工维护原方案的本地 Instance 文件作为配置入口；运行时是否生成或缓存本地文件，以实际加载机制为准。参数和机制有官方依据，实际安装包的脚本、网络及认证仍需核验。

#### 1.4 创建 db0、db1 两个 Instance

在 Admin 分别创建 db0、db1，关联上述集群并载入 Instance 模板。两个任务分别配置所属 MySQL 地址、复制账号、复制身份、过滤规则和导入前 GTID 集合。

Admin 中 db0 的 Instance 属性候选示例：

    canal.instance.master.address=192.168.10.11:3306
    canal.instance.dbUsername=canal_repl
    canal.instance.dbPassword=${CANAL_MYSQL_PASSWORD}
    # 保持官方自动生成机制，不在集群共享配置中固定为 1001
    # canal.instance.mysql.slaveId=0
    canal.instance.gtidon=true
    canal.instance.master.gtid=<db0导入前采集的GTID集合>
    canal.instance.filter.regex=^eas\\.consume_record_(0[1-9]|1[0-6])$

Admin 中 db1 的 Instance 使用另一个分库实例自己的地址和初始 GTID，不能复制 db0 的基线。两个 MySQL 彼此不是主从关系，但物理库名都为 eas，分表范围都为 consume_record_01～consume_record_16，因此两个 Instance 使用相同过滤表达式，由各自连接地址区分数据源。

上述为 properties 文本写法，双反斜杠经属性加载后使正则匹配库表间的字面点号；`0[1-9]|1[0-6]` 限定 01～16，首尾锚点避免匹配备份后缀等其他表。Admin 中的转义、配置加载和实际事件过滤效果仍须联调验证。Server 过滤涵盖全部分表，不代表 Adapter 的单表 SQL 映射会自动涵盖全部分表，008 的映射覆盖方式需另行核对。

[1.1.7 Instance 模板](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties)明确标注 v1.0.26+ 自动生成 slaveId，并将该属性保留为注释；官方 ServerGuide 说明这一机制用于保障 HA 模式下复制身份唯一性。因此本方案去掉原示例固定的 `1001`，部署时仍需核对生成的复制身份及实际拓扑中是否冲突。

同一模板已确认包含 `canal.instance.gtidon` 和 `canal.instance.master.gtid`，default-instance.xml 也读取这两个属性，不能再将其键名笼统列为未知。这里的 `gtidon=true` 是项目启用 GTID 的选择，并非模板默认值。物理库名和分表范围已确认，地址、GTID 集合和凭据仍为项目待填值；`${CANAL_MYSQL_PASSWORD}` 仅作占位示意，不保证环境变量自动展开，注入方式须核验。GTID 与已有位点的优先级、事务连续性和导入数据完整性仍需实现核对及联调。

#### 1.5 验证加载与职责边界

核对 Admin 中的集群与节点关联、两个 Instance 实际生效的源库和基线，以及 Server 日志中的加载结果。同一 destination 在多个 Server 间由 ZooKeeper 协调运行资格；Admin 的配置管理不能代替主备选举，也不能证明消费位点已跨节点共享。

Instance 的实际启用仍遵循 006：先保存导入前基线，完成历史导入及验收后再按计划启动同步。配置创建与任务启用应区分，核对 Admin 的创建、启停和自动加载行为，避免提前启动或以最新 GTID 覆盖导入前基线。Admin 不可用时的运行、重启及配置恢复行为，以及管理库备份恢复方式仍待验证。

### 2. 读取 MySQL binlog

**先说结论：** 每个 destination 使用自己的复制身份连接对应 MySQL，从初始 GTID 或已保存位点继续读取 binlog。

Canal Server 模拟 MySQL 从库，通过复制账号和面向 MySQL 复制的 slaveId 连接 MySQL；该身份与 Adapter 连接 Canal 时使用的 clientId 是两套不同的身份。MySQL 使用 ROW 格式和 FULL 行镜像，使 Server 能够获得具体的行变化和较完整的字段内容。实际字段仍需结合真实事件验证。

### 3. 整理变更消息

**先说结论：** Canal Server 将 binlog 中已提交事务的行变更整理成 Canal 消息，再交给 Canal Adapter。

Server 识别 INSERT、UPDATE、DELETE 等数据变化，并保留库名、表名、事件类型、执行时间、位点和行数据等信息。同一个已提交事务中的多条变更按原有顺序组织；一个批次可以包含多个事务。项目要求 DDL、TRUNCATE 或无法识别的事件不能按普通行变更静默处理，应记录并告警或暂停；这不是对发行包默认自动处置行为的断言，支持来源和实现边界见第 7 节。

### 4. 向 Canal Adapter 提供消息

**先说结论：** Server 按批次向 Adapter 提供消息，并根据消费端的 ack 推进消费进度；下游是否真正成功由消费端负责判断。

项目要求 Adapter 完成下游处理后再确认批次，失败则不确认。未确认批次在回退或恢复时可能重新投递；已经被确认的失败批次，不能指望 Server 自动识别并重新投递。ack 只表示消费端发出了确认，不证明 ES 全部成功，也不代表搜索可见。

上述“失败不确认”是项目要求，不是原生默认保证。Canal Adapter 1.1.7 在有限重试耗尽且 terminateOnException=false 时仍会 ack，该属性默认是 false；ES7 对状态为 NOT_FOUND 的失败子项仅记录日志，不抛异常。008 已纳入 terminateOnException: true 的配置建议，但它无法解决未抛异常的 404 分支。项目重试次数和 404 处理机制仍待定，实际部署及恢复行为待验证。

**修改支持来源：** [1.1.7 AdapterProcessor.java](https://github.com/alibaba/canal/blob/canal-1.1.7/client-adapter/launcher/src/main/java/com/alibaba/otter/canal/adapter/launcher/loader/AdapterProcessor.java)、[CanalClientConfig.java](https://github.com/alibaba/canal/blob/canal-1.1.7/client-adapter/common/src/main/java/com/alibaba/otter/canal/client/adapter/support/CanalClientConfig.java)与 [ESConnection.java](https://github.com/alibaba/canal/blob/canal-1.1.7/client-adapter/es7x/src/main/java/com/alibaba/otter/canal/client/adapter/es7x/support/ESConnection.java)。以上是源码静态核对，未完成发行包故障测试。

客户端 ack 方法返回、Server 处理确认并更新消费游标、消费进度完成持久化，是不同阶段。SimpleCanalConnector.ack() 写出确认报文，该方法没有等待 Server 确认响应；因此方法返回不能证明 Server 已处理或位点已持久化。Server 的 ack 根据批次更新游标并确认事件存储进度，持久化仍受 007 所述元数据管理与周期刷新机制约束。ES 写入成功而消费确认未可靠保存时，故障恢复仍可能重复处理。

**修改支持来源：** [Canal 1.1.7 SimpleCanalConnector.java](https://github.com/alibaba/canal/blob/canal-1.1.7/client/src/main/java/com/alibaba/otter/canal/client/impl/SimpleCanalConnector.java)的连接回退开关、connect、processActiveEnter、ack 和 rollback，以及 [CanalServerWithEmbedded.java](https://github.com/alibaba/canal/blob/canal-1.1.7/server/src/main/java/com/alibaba/otter/canal/server/embedded/CanalServerWithEmbedded.java)的 ack 与两种 rollback 实现。以上是源码静态核对，不是实际恢复演练结果。

### 5. 位点持久化与恢复

**先说结论：** 导入前 GTID 负责首次初始化，正常重启和主备切换优先使用已持久化的确认进度。原方案中“没有可恢复位点时使用初始 GTID”的适用范围仍待用户确认；本次只标明待定状态，不将其确认为自动恢复规则。

首次启动时，db0、db1 分别使用 Admin 中对应 Instance 配置的初始 GTID，实际配置加载结果须核对。运行过程中，Canal 持久化确认消费后的位点；重启时优先从已保存位点恢复，不覆盖已有消费进度。

本方案选用的 [1.1.7 default-instance.xml](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/spring/default-instance.xml)明确配置 `PeriodMixedMetaManager`，内部引用 `ZooKeeperMetaManager`；周期参数为 `canal.zookeeper.flush.period`，模板默认值为 1000。解析位置管理还引用了 `FailbackLogPositionManager`、`MemoryLogPositionManager` 和基于元数据管理器的 `MetaLogPositionManager`。这些是模板静态事实，说明本方案包含 ZooKeeper 元数据持久化，不能继续主要用“本地位点定期刷盘”解释恢复机制，也不能把 Admin 管理库当作消费位点库。

消费确认、周期持久化、重新投递之间存在边界，仍需验证故障时能恢复到哪个位置，并按可能重复投递设计幂等。ZooKeeper 选举成功只代表取得运行资格，不等于已证明连续恢复；两节点实际加载同一目标配置、能读取对应元数据及所需 binlog，都是接管核验内容。固定模板不代表部署中必然生效，不能据此宣称切换无丢失或零中断。

### 6. ZooKeeper 主备高可用边界

**先说结论：** 两台不同主机上的 Server 接入 Admin 中的同一集群，共同为 db0、db1 提供主备候选节点。ZooKeeper 按 destination 分别协调，同一 destination 同一时间只允许一个节点工作；故障后由另一节点接管对应任务，位点恢复行为仍须验证。

db0、db1 是两个独立订阅任务，分别连接两个 MySQL 分库实例。两个任务可以同时在 Server A 上运行、Server B 为其备用，也可以分别运行在 A、B 上；不强制两个任务绑定在同一节点或同时完成切换。这里的 A、B 只是节点代称，不表示永久固定的主、备角色。

容量应按任意一台 Server 能承接两个任务的总负载规划，核对 binlog 峰值、解析 CPU、缓冲区内存和故障后的积压追赶能力。两台是已选定的部署规模，是否满足实际负载仍待测试，不能写成已验证容量。

ZooKeeper 负责节点存活检测和工作节点选举。主节点故障后，备用节点获得工作资格，Canal Adapter 重新连接当前工作节点并继续消费。切换期间可能出现短暂同步中断，恢复速度取决于故障检测、节点接管、位点恢复和积压追赶。

项目已确认 MySQL binlog 保留 30 天。这个期限需要覆盖故障发现、人工处理、主备切换和积压追赶窗口；该方案降低了单节点故障影响，但不等于完全没有中断，也不代表当前已经完成真实集群部署和演练验证。

### 7. 异常处理边界

**先说结论：** 项目要求影响位点连续性或事件含义的异常必须告警或暂停，不能跳过后继续消费。这是项目规则，不代表 Canal 1.1.7 默认会对所有相关异常自动暂停或发送外部通知。

重点包括 binlog 断档、位点恢复失败、过滤规则不匹配、DDL、TRUNCATE、未知事件和主备切换失败。处理时应先确认日志完整性、实例身份、位点和过滤范围，再决定恢复、补数或人工介入。

**支持来源及证据边界：** [Canal 1.1.7 default-instance.xml](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/spring/default-instance.xml)配置了 `LogAlarmHandler`，并包含 `filterQueryDdl`、`filterTableError` 等过滤属性；这能证明模板声明了日志告警处理器与相关配置入口，不能据此认定所有异常都会触发该处理器，更不能证明自动暂停、通知送达及人工恢复闭环已经实现。原参考文章也未给出上述完整异常处置流程。

实际部署前需分别核验：各类异常由 Server、Adapter 或监控系统中的哪一层发现；过滤配置是否影响事件交付；日志如何被采集并触发外部告警；暂停作用于哪个 destination、何时生效以及如何恢复。日志记录不等于通知已经发送，配置过滤不等于暂停消费，暂停进程也不能代替位点连续性核对。具体实现与演练结果仍待确认，不在本文编造自动处置能力或已通过的测试。

### 8. 相对原方案的调整及证据边界

| 调整项 | 原方案 | 当前方案与依据性质 |
| --- | --- | --- |
| 配置入口 | 本地手工维护公共与 Instance 配置 | Admin 集中管理，官方明确支持；运行时本地缓存行为仍需核验 |
| 部署规模 | 多节点，数量未固定 | 两台、不同主机，是用户已确认的项目选择，不是官方强制数量 |
| 任务分布 | 未展开 | 单 Server 支持多个 Instance，集群可接管及调整分布，有官方机制依据；不保证两任务绑定切换 |
| 部署流程 | 主要给出配置文件示例 | 补充管理库、集群、节点接入、Instance 创建及加载检查，有官方指南依据 |
| GTID 来源 | 本地 Instance 文件 | Admin 对应 Instance；导入前基线及两个独立通道的含义不变 |
| 复制身份 | 固定 slaveId=1001 的候选示例 | 按官方模板保留自动生成机制，实际身份仍待核验 |
| 位点说明 | 本地刷盘的通用描述 | 改为所选模板的 ZooKeeper 元数据组件及持久化边界，实际恢复仍待验证 |
| 容量要求 | 未展开 | 单节点能够承接两任务总负载是故障容量规划要求，不是已通过测试的结论 |
| 监控与上线核验 | 仅提到监控端口 | 补充 Prometheus/Grafana 接入、指标分类和项目核验建议；逐项标明来源，实际部署与阈值待确认 |
| 异常处置 | 易将项目要求理解为默认自动能力 | 区分项目规则与模板提供的日志告警、过滤入口；检测、暂停、通知及恢复流程仍待验证，来源见第 7 节 |

项目保留两个分库实例、db0/db1 各自的导入前基线、Server/Adapter 1.1.7 和 30 天日志规则。Adapter 进程主备候选方案不因 Server 引入 Admin 改变；位点丢失后的初始 GTID 使用范围继续待定。

### 9. 网上参考资料与核验状态

| 资料 | 支持的结论 | 证据边界 |
| --- | --- | --- |
| [原参考文章：Canal高可用架构部署](https://mp.weixin.qq.com/s/QwvmxqxXirjf-J6mqYY44Q) | Admin、Server、Adapter、ZooKeeper 的总体部署思路 | 旧版本示例，不作为 1.1.7 全部行为的证明 |
| [官方 Canal Admin Guide](https://github.com/alibaba/canal/wiki/Canal-Admin-Guide) | 一个 Server 多个 Instance；集群共享配置；Instance 绑定集群；其他节点接管；调整任务分布 | 官方通用指南，不代表当前环境已验证 |
| [官方 Canal Admin ServerGuide](https://github.com/alibaba/canal/wiki/Canal-Admin-ServerGuide) | 最小引导配置、双向管理认证、自动注册集群、local 启动、自动生成复制身份 | Wiki 不锁定发布版本，属性须结合版本模板 |
| [官方 Canal Admin QuickStart](https://github.com/alibaba/canal/wiki/Canal-Admin-QuickStart) | application.yml、管理库与 canal_manager.sql 初始化 | 示例版本较旧，不照搬驱动或默认凭据 |
| [1.1.7 canal_local.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal_local.properties) | 注册 IP、Admin 地址、端口、认证、自动注册、集群及节点名属性存在 | 参数值和实际连通性待验证 |
| [1.1.7 instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties) | 自动生成 slaveId 的模板说明、gtidon 和 master.gtid 属性 | 不单独证明起点优先级及历史回放完整性 |
| [1.1.7 default-instance.xml](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/spring/default-instance.xml) | GTID 属性被读取；ZooKeeper 元数据组件和位点管理组件配置 | 不等同于实际安装包已加载该模板或恢复演练通过 |
| [官方 Prometheus QuickStart](https://github.com/alibaba/canal/wiki/Prometheus-QuickStart) | 指标端口配置、Prometheus 采集、Grafana 看板及延迟、吞吐、积压等指标 | 通用 Wiki 较旧，不照搬旧 Grafana 版本或默认凭据；实际 1.1.7 暴露名称、标签及看板兼容性待核验 |

以上官方页面和版本模板已进行静态阅读核对。尚未执行安装、启动、数据库连接、GTID 回放或主备故障演练。仍需确认 Admin 最终包版本与部署位置、注册方式及凭据、ZooKeeper 环境、实际加载配置、消费确认与持久化衔接、已有位点恢复优先级、历史数据完整性和单节点容量。新增官方依据不能将这些待验证事项变成已完成事实。

### 10. 监控与上线核验

**先说结论：** 采用 Prometheus 采集 Canal Server 指标、Grafana 展示的方案，分别观察两台 Server 和 db0、db1 的延迟、吞吐、积压及请求异常。监控用于发现同步异常，不能单独证明 ES 数据完整正确；当前仅确认方案，未部署或验证监控链路。

**支持来源：** [原文章“监控”部分](https://mp.weixin.qq.com/s/QwvmxqxXirjf-J6mqYY44Q)与[官方 Prometheus QuickStart](https://github.com/alibaba/canal/wiki/Prometheus-QuickStart)均介绍这一组合。以下明确区分官方指标说明与基于项目拓扑提出的核验建议。

#### 10.1 采集和展示

官方指南使用 `canal.metrics.pull.port` 配置指标端口，示例为 11112；Prometheus 添加 Canal 抓取目标，Grafana 配置 Prometheus 数据源并导入 Canal Instance 看板，可通过 destination 选择任务。

项目接入建议：将两台 Server 的实际可达地址都加入采集目标，以抓取目标区分节点，以 destination 区分 db0、db1。不能只监控初始活动节点，否则切换后可能丢失观察范围。备用节点没有某个活动任务的业务指标，不应直接等同故障，应结合节点存活、Admin 任务状态及切换结果判断。该建议基于本项目双节点部署，不是官方给定的告警规则。

**支持来源：** [官方指南 Quick start](https://github.com/alibaba/canal/wiki/Prometheus-QuickStart#quick-start)说明采集端口与看板接入；[官方 Admin Guide](https://github.com/alibaba/canal/wiki/Canal-Admin-Guide)说明集群任务接管和运行实例查看。实际指标路径、端口可达性、标签、看板文件及版本兼容性仍需按安装包核验，不照搬文档中的旧 Grafana 版本和默认密码。

#### 10.2 重点指标及含义

下表指标名称与含义来自[官方原始指标列表及应用说明](https://github.com/alibaba/canal/wiki/Prometheus-QuickStart)，原文章也列出了相应展示类别。它们是选型依据，不代表已确认当前环境输出了全部指标。

| 关注点 | 官方指标或展示类别 | 项目观察重点与边界 |
| --- | --- | --- |
| 任务状态 | `canal_instance`、`canal_instance_subscriptions` | 结合实际标签与 Admin 状态确认任务及订阅；不要仅以某条时间序列推断唯一主节点 |
| 同步延迟 | `canal_instance_traffic_delay`、`canal_instance_put_delay`、`canal_instance_get_delay`、`canal_instance_ack_delay` | 区分采集、入缓冲、拉取、确认阶段；官方说明延迟准确度依赖 MySQL 与 Server 时钟同步，不能把这些指标直接等同 ES 搜索可见延迟 |
| 吞吐量 | `canal_instance_transactions`、`canal_instance_put_rows`、`canal_instance_get_rows`、`canal_instance_ack_rows` | 按时间窗口观察计数变化速率，不把累计计数直接当 TPS；结合业务写入判断低吞吐是否异常 |
| 事件积压 | `canal_instance_store_produce_seq` 与 `canal_instance_store_consume_seq` 的差值 | 官方用于观察 Event Store 中未 ack 的事件数量；不是 MySQL 全部未读取 binlog 的数量 |
| 缓冲内存 | `canal_instance_store_produce_mem` 与 `canal_instance_store_consume_mem` 的差值 | 官方用于观察内存模式下未 ack 事件占用，不等于整个 JVM 内存；即时性受抓取周期影响 |
| 线程阻塞 | `canal_instance_sink_blocking_time`、`canal_instance_publish_blocking_time` | 结合延迟和积压判断解析或消费瓶颈；publish 指标的相关解释适用于并行解析模式 |
| 网络流量 | `canal_instance_received_binlog_bytes`、`canal_instance_client_bytes` | 分别观察读取 binlog 和向客户端发送的速率 |
| 客户端异常 | `canal_instance_client_request_error`、`canal_instance_client_request_latency` | 观察失败请求与响应耗时，结合日志定位原因 |
| 空批次与请求量 | `canal_instance_client_empty_batches`、`canal_instance_client_packets` | 结合业务流量、GET/ACK 请求判断；业务无写入时空批次不能直接算故障 |

两节点切换可能改变时间序列归属并使进程内计数重置，查询和看板应保留节点维度、处理计数重置，避免把跨节点计数直接相减。此项是依据双节点运行方式提出的项目查询设计建议，具体表达式需按实际标签验证。

#### 10.3 上线前核验建议

以下是待执行的项目验收建议，不是官方承诺，也不是已经完成的测试：

1. 确认两台 Server 均可被采集，分别查看 db0、db1 的活动状态、吞吐、延迟和积压；结合 Admin 和日志检查每个任务的运行节点。
2. 在测试环境针对两个实例的 eas 库核验 consume_record_01～consume_record_16 的订阅覆盖，并验证代表性新增、修改、删除最终反映到 ES；不能仅凭 Server 计数增长判断成功。
3. 在受控故障演练中观察任务接管、客户端重连、指标归属变化和积压追赶，并核对恢复后的目标数据；记录恢复时间，不预设零中断。
4. 按业务峰值核验单节点承接两个任务的能力，结合延迟、积压、阻塞及主机/JVM 资源观察容量，不把“两台部署”当成容量已足够的证明。
5. 模拟消费失败，核对 Adapter 错误、ES 逐项结果和 Canal 确认行为。Server 的 ack 指标表示确认相关进度，不能单独证明 ES 处理逻辑正确，更不能证明搜索立即可见。

**支持来源与推导边界：** 第 1、3 项基于[官方 Admin Guide 的集群接管机制](https://github.com/alibaba/canal/wiki/Canal-Admin-Guide)及上述监控指南；第 2 项来自用户已确认的两个 eas 库、各 16 张分表；第 4 项来自本项目单节点故障容量要求，官方指标仅提供观察手段；第 5 项沿用本文第 4 节及 008 的逐项成功后确认要求。上述组合是项目核验设计，不是原文章已经验证本项目的结论。

#### 10.4 待确认的监控配置

Prometheus/Grafana 版本、部署位置、采集周期、指标保留期、看板兼容性、告警阈值、持续时间和通知渠道均待确认。建议关注采集不可达、任务异常退出、延迟或积压持续上升、客户端错误增加等情况，但阈值必须结合实际业务基线和恢复目标确定，不编造固定秒数或事件数。

**支持来源：** 官方指南提供指标及使用场景，没有给出本项目的业务阈值或告警承诺；因此这些内容明确保留为待确认。本次仅完成官方资料静态核对和文档整理，未安装监控组件、采集生产指标、发送通知或执行故障演练。
