# Canal 部署与上线核验

> 物理命名更新：两个实例均使用 eas 库，分表编号为 01～16。下文 consume_db0、consume_db1 和 00～15 是历史模板值，不应复制为当前配置；当前 Server 过滤及 Adapter 数据源名称见 007、008。历史授权和 JDBC 示例同样须按 eas 核对后重新生成。

> 当前核查补充：007 已补充官方文档及 canal-1.1.7 模板依据，采用自动生成 slaveId，并按 default-instance.xml 描述 ZooKeeper 元数据持久化。本文固定 slaveId 和其他历史模板不得直接作为当前部署配置；运行时恢复和容量仍需验证。

> 历史候选方案说明：本文保留原部署模板供参考，不作为当前已确认的 Server 配置入口。项目现已在 007 选定 Canal Admin 集中管理集群、Server 和 db0、db1 的 Instance 配置，Server 本地保留接入 Admin 的引导配置；本文本地配置路径及“版本待锁定”等旧表述应按历史候选内容理解。当前 Server/Adapter 版本为 1.1.7，实际加载和部署仍待验证。此前待定的位点丢失恢复规则不因本次配置管理变更而确定。

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

技术栈版本：

```text
Java：8
Spring Boot：2.7.18
MySQL：8.0.33
Elasticsearch：7.17.15
```

本文是候选部署模板，不是可直接执行的生产配置。MySQL 配置和部署关系按已确认的 GTID、分片及同步规则整理；Canal Adapter 的具体属性名、ES 插件注册名和表映射执行方式，必须在锁定发行包后进一步核对。

## 1. 整体部署链路

ES 同步来源为 2 个独立的新库 MySQL 实例；旧库仍存在，但不在本模板的 ES 同步链路内：

```text
MySQL 实例 0
  └─ consume_db0
      └─ consume_record_00 ~ consume_record_15

MySQL 实例 1
  └─ consume_db1
      └─ consume_record_00 ~ consume_record_15
```

建议分别部署两套同步链路：

```text
MySQL 实例 0
  → Canal Server，destination=db0
  → Canal Adapter，instance=db0
  → Elasticsearch 7.17.15

MySQL 实例 1
  → Canal Server，destination=db1
  → Canal Adapter，instance=db1
  → Elasticsearch 7.17.15
```

数据流转过程：

```text
业务系统
   │
   ▼
MySQL 8.0.33
   │
   │ 记录新增、修改、删除等 binlog
   ▼
Canal Server
   │
   │ TCP 发送变更事件
   ▼
Canal Adapter
   ├─ 按映射需要通过 JDBC 查询 MySQL
   └─ 将数据写入 Elasticsearch
```

## 2. 组件职责

| 组件 | 作用 |
|---|---|
| MySQL | 保存消费记录业务数据 |
| binlog | 记录 MySQL 数据变化 |
| Canal Server | 连接 MySQL，读取并解析 binlog，维护消费进度 |
| destination | 标识一套独立的 MySQL 采集配置和位点 |
| Canal Adapter | 接收 Canal 事件，按照映射规则转换并写入 ES |
| Elasticsearch | 保存同步结果并提供查询能力 |

Adapter 同时涉及多条连接：

```text
Canal Server → MySQL
    读取和解析 binlog

Canal Adapter → Canal Server
    通过 TCP 接收变更事件

Canal Adapter → MySQL
    通过 JDBC 按映射需要查询业务数据

Canal Adapter → Elasticsearch
    写入同步结果
```

`srcDataSources` 是 Adapter 的业务查询数据源，不负责监听 binlog。

## 3. 实例部署规划

以下地址为示例，必须替换成实际地址：

| 主机 | 组件 | 地址或端口 | 对应数据 |
|---|---|---|---|
| `mysql-01` | MySQL 8.0.33 | `192.168.10.11:3306` | `consume_db0` |
| `mysql-02` | MySQL 8.0.33 | `192.168.10.12:3306` | `consume_db1` |
| `canal-01` | Canal Server、Adapter | `192.168.20.11` | `db0` |
| `canal-02` | Canal Server、Adapter | `192.168.20.12` | `db1` |
| ES 集群 | Elasticsearch 7.17.15 | `9200` | 两套 Adapter 共用 |

建议目录：

```text
/opt/canal/server-db0/
/opt/canal/adapter-db0/

/opt/canal/server-db1/
/opt/canal/adapter-db1/
```

本模板建议 2 套 Server 和 2 套 Adapter，分别承载两个新库的 ES 同步；这是部署候选，不表示进程拓扑已确定。独立校验通道另按校验章节配置，不能复用 ES 同步的消费确认。也可以由一个 Canal Server 进程加载 `db0,db1` 两个 destination，但任一进程故障会同时影响两条链路。

## 4. MySQL 8.0.33 配置

常见 MySQL 配置文件：

```text
/etc/my.cnf
/etc/mysql/my.cnf
/etc/mysql/mysql.conf.d/mysqld.cnf
```

实际修改前，必须确认 `mysqld` 实际加载的文件。

MySQL 实例 0：

```ini
[mysqld]

# 在相关复制拓扑内保持唯一
server-id=101

# 开启 binlog
log_bin=mysql-bin

# 与项目历史基线和恢复规则一致
gtid_mode=ON
enforce_gtid_consistency=ON

# 使用行级日志
binlog_format=ROW

# 保留完整行镜像
binlog_row_image=FULL

# 604800 秒为 7 天示例值
binlog_expire_logs_seconds=604800
```

MySQL 实例 1：

```ini
[mysqld]

# 不能与实例 0 相同
server-id=102

log_bin=mysql-bin

# 与项目历史基线和恢复规则一致
gtid_mode=ON
enforce_gtid_consistency=ON
binlog_format=ROW
binlog_row_image=FULL
binlog_expire_logs_seconds=604800
```

参数含义：

- `server-id`：MySQL 在复制拓扑中的标识；
- `log_bin`：开启二进制日志；
- `gtid_mode=ON`、`enforce_gtid_consistency=ON`：启用项目所需 GTID 模式与一致性约束；
- `binlog_format=ROW`：记录具体行变化；
- `binlog_row_image=FULL`：保留完整行镜像；
- `binlog_expire_logs_seconds`：控制 binlog 保留时间。

binlog 保留时间应覆盖：

```text
历史导入、同步启动及追赶所需日志
两项校验各自未完成范围及重试所需日志
故障恢复所需日志
→ 按所有用途中的最长保留需求设置，并留足磁盘余量
```

检查实际配置：

```sql
SHOW VARIABLES LIKE 'server_id';
SHOW VARIABLES LIKE 'log_bin';
SHOW VARIABLES LIKE 'gtid_mode';
SHOW VARIABLES LIKE 'enforce_gtid_consistency';
SHOW VARIABLES LIKE 'binlog_format';
SHOW VARIABLES LIKE 'binlog_row_image';
SHOW VARIABLES LIKE 'binlog_expire_logs_seconds';
```

## 5. MySQL 账号权限

账号来源地址必须填写 Canal 进程连接 MySQL 时的实际来源地址。

假设：

```text
Canal Server 0、Adapter 0：192.168.20.11
Canal Server 1、Adapter 1：192.168.20.12
```

实例 0 上的 Canal Server 账号：

```sql
CREATE USER 'canal_repl'@'192.168.20.11'
IDENTIFIED BY '替换为实际密码';

GRANT REPLICATION SLAVE,
      REPLICATION CLIENT
ON *.* TO 'canal_repl'@'192.168.20.11';

GRANT SELECT ON consume_db0.*
TO 'canal_repl'@'192.168.20.11';
```

`REPLICATION SLAVE` 是这里申请的复制读取权限；不要仅因主从术语更名就擅自替换 GRANT 中的权限标识。实际最小权限和账号来源仍需按 MySQL 8.0.33 环境验证。

实例 0 上的 Adapter 查询账号：

```sql
CREATE USER 'canal_query'@'192.168.20.11'
IDENTIFIED BY '替换为实际密码';

GRANT SELECT ON consume_db0.*
TO 'canal_query'@'192.168.20.11';
```

实例 1 将对应内容修改为：

```text
来源地址：192.168.20.12
数据库：consume_db1
```

两个账号职责不同：

```text
canal_repl：读取 binlog
canal_query：查询业务表
```

如果 Canal Server 和 Adapter 分开部署，两个账号的来源地址也必须分别调整。

## 6. Canal 下载和安装准备

需要准备：

- JDK 8；
- Canal Deployer；
- Canal Adapter；
- MySQL 客户端；
- Linux 服务管理工具。

Canal Server 和 Adapter 应使用同一发行版本。Canal 1.1.8 可以作为候选版本：

```text
canal.deployer-1.1.8.tar.gz
canal.adapter-1.1.8.tar.gz
```

正式部署前必须锁定：

- Server 和 Adapter 的版本；
- 下载包校验值；
- 包内目录结构；
- Adapter 配置格式；
- ES 插件实际注册名；
- 运行时依赖。

不展开具体安装命令，只要求安装后确认类似目录：

```text
/opt/canal/server-db0/
├── bin/
├── conf/
└── lib/

/opt/canal/adapter-db0/
├── bin/
├── conf/
└── lib/
```

实例 1 使用对应的：

```text
/opt/canal/server-db1/
/opt/canal/adapter-db1/
```

**安装完成条件**

Canal 应使用独立系统账号运行，不建议使用 root。配置、日志和位点目录必须具有必要的读写权限。

## 7. Canal Server 配置

实例 0 主配置文件：

```text
/opt/canal/server-db0/conf/canal.properties
```

```properties
# Canal Server 节点编号
canal.id=1

# Adapter 连接的 TCP 端口
canal.port=11111

# 使用 TCP 模式
canal.serverMode=tcp

# 使用 Netty 网络服务
canal.withoutNetty=false

# 当前 Server 加载 db0
canal.destinations=db0

# 使用 Spring 方式加载实例
canal.instance.global.mode=spring

# 必须确认发行包中存在该文件
canal.instance.global.spring.xml=classpath:spring/file-instance.xml
```

实例配置文件：

```text
/opt/canal/server-db0/conf/db0/instance.properties
```

```properties
# Canal 模拟 MySQL 从库时使用的标识
canal.instance.mysql.slaveId=1001

# MySQL 实例 0
canal.instance.master.address=192.168.10.11:3306

# binlog 采集账号
canal.instance.dbUsername=canal_repl
canal.instance.dbPassword=替换为实际密码

canal.instance.connectionCharset=UTF-8

# 当前项目按已保存的 GTID 基线启动，不能从部署时的最新位点开始
canal.instance.gtidon=true
canal.instance.master.gtid=替换为该新库历史导入前保存的完整GTID集合

# 保存表结构元数据
canal.instance.tsdb.enable=true

# 监听 16 张分表
canal.instance.filter.regex=consume_db0\\.consume_record_(0[0-9]|1[0-5])

canal.instance.filter.black.regex=
```

实例 1 需要调整：

```text
canal.id=2
canal.destinations=db1
canal.instance.mysql.slaveId=1002
canal.instance.master.address=192.168.10.12:3306
canal.instance.filter.regex=consume_db1\\.consume_record_(0[0-9]|1[0-5])
```

`canal.id` 是 Canal 节点标识；MySQL 的 `server-id` 与 Canal 模拟复制客户端的 `slaveId` 要在相关复制拓扑中避免冲突，包括其他校验消费者。

**首次启动与恢复分开**

`canal.instance.master.gtid` 使用导入前保存的完整集合，不能取部署时最新值。已有持久化位点可能影响启动位置，必须在锁定发行包中核对优先级与恢复流程，不能删除位点文件来强行重置。候选属性名已对照 [Canal 1.1.8 instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.8/deployer/src/main/resources/example/instance.properties)；项目版本仍待锁定。

## 8. Canal Adapter 配置

以下结构已对照 [Canal 1.1.8 application.yml](https://github.com/alibaba/canal/blob/canal-1.1.8/client-adapter/launcher/src/main/resources/application.yml) 的 TCP 连接字段；项目尚未锁定并运行验证发行包，仍需核对：

- 候选版本示例的 `canal.tcp.server.host` 使用 `host:port`，确认目标包保持相同语义；
- 候选示例存在 `canal.tcp.username` 和 `canal.tcp.password`，确认实际认证配置；
- `retries` 的具体语义；
- 官方候选示例中出现 `es`，草案为 `es7`；下面以占位符保留，插件注册名必须与实际安装包一致。

实例 0：

```yaml
server:
  port: 8081

canal.conf:
  mode: tcp
  syncBatchSize: 500

  # 仅作为模板值，必须核对发行包的重试语义
  retries: -1

  timeout: 30000

  consumerProperties:
    # 仓库草案使用 host:port 形式。
    # 此形式已核对候选 1.1.8 示例，实际包仍需验证。
    canal.tcp.server.host: 192.168.20.11:11111
    canal.tcp.batch.size: 500
    canal.tcp.username:
    canal.tcp.password:

  srcDataSources:
    consumeDS:
      # Adapter 通过 JDBC 查询 MySQL
      url: "jdbc:mysql://192.168.10.11:3306/consume_db0?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
      username: canal_query
      password: "${CANAL_QUERY_PASSWORD}"

  canalAdapters:
    - instance: db0
      groups:
        - groupId: g1
          outerAdapters:
            # 仅作模板值，必须核对实际插件注册名
            - name: <实际ES插件注册名>
              hosts: es-hot-01:9200,es-hot-02:9200
              properties:
                mode: rest
                security.auth: "${ES_AUTH}"
```

实例 1 修改：

```text
Canal Server 地址：192.168.20.12:11111
MySQL 地址：192.168.10.12:3306
数据库：consume_db1
instance：db1
```

如果两个 Adapter 部署在同一台主机，管理端口必须不同。

## 9. 表映射文件

`application.yml` 只定义连接关系，还需要为业务表配置映射文件。

示例路径：

```text
/opt/canal/adapter-db0/conf/<实际ES插件配置目录>/consume_record_00.yml
```

目录名和插件名称必须以实际发行包为准。

基础结构：

```yaml
dataSourceKey: consumeDS
destination: db0
groupId: g1

esMapping:
  # 基础联调时可以使用具体索引
  _index: consume-000001

  # 业务主键
  _id: consumeId

  upsert: true
  commitBatch: 500

  sql: >
    SELECT
      c.consume_id AS consumeId,
      c.trade_time AS tradeTime,
      c.trade_amount AS tradeAmount,
      c.trade_status AS tradeStatus
    FROM consume_record_00 c
```

该 SQL 仅展示 4 个字段，不能直接当作完整业务映射。应对照新表完整字段清单及 Mapping 补齐后再联调。

这里的 SQL 是映射示例，不能仅凭没有 `WHERE` 就断定 Adapter 每次都会查询整张表。实际行为取决于 Adapter 版本和映射实现。

正式配置前必须核对：

- 主键字段配置；
- 新增、更新、删除事件的处理方式；
- SQL 是否用于全量 ETL 或增量映射；
- 是否需要配置 `pk`；
- 是否支持特定占位符。

不得自行添加未经验证的：

```sql
WHERE consume_id = {id}
```

为其余 15 张表分别建立映射，并根据真实 DDL 补齐字段。

如果使用 ES rollover，不能仅把 `_index` 改成写别名就认为更新、删除和重试已经正确。生产同步端还需要能够定位原文档所在的具体索引。

## 10. 时区处理

建议 Adapter JVM 使用：

```text
-Duser.timezone=Asia/Shanghai
```

JDBC 连接使用：

```text
serverTimezone=Asia/Shanghai
```

例如：

```text
jdbc:mysql://192.168.10.11:3306/consume_db0
?useUnicode=true
&characterEncoding=UTF-8
&serverTimezone=Asia/Shanghai
```

这些参数不能单独保证最终写入 ES 的格式，必须检查实际 JSON，例如：

```json
{
  "consumeId": "字符串形式的消费记录主键",
  "tradeTime": "2026-09-12T14:00:00+08:00"
}
```

## 11. 主备部署

如选择人工主备接管方案，可为每条链路准备停止状态的备用实例；这不是已经验证的自动高可用能力：

```text
db0：
  Canal Server 主实例
  Canal Adapter 主实例
  Canal Server 备用实例
  Canal Adapter 备用实例

db1：
  Canal Server 主实例
  Canal Adapter 主实例
  Canal Server 备用实例
  Canal Adapter 备用实例
```

备用实例需要同步：

```text
Server 配置
Adapter 配置
表映射文件
账号配置
Canal 位点和表结构元数据
日志及目录权限
```

接管时：

```text
确认原活动实例已停止
        ↓
核对最后消费进度
        ↓
接管必要的位点和元数据
        ↓
启动备用 Canal Server
        ↓
检查 MySQL 连接和 destination
        ↓
启动备用 Adapter
        ↓
检查消费状态和错误日志
```

该人工主备方案中，同一条 ES 同步链路应避免主备同时写入；独立校验通道另有消费职责，不能被这条限制误删。增加第二个进程本身不能自动形成可靠的高可用方案。

当前内容可以作为部署架构和配置说明。正式上线前必须完成：

1. 锁定 Canal Server 和 Adapter 版本；
2. 核对 Adapter TCP 属性；
3. 核对 ES 插件实际注册名；
4. 核对表映射格式；
5. 验证失败重试、位点确认和 Bulk 部分失败处理；
6. 验证生产环境所需的具体索引定位逻辑。

以上 Canal Adapter 相关配置尚未在实际发行包、Linux、MySQL 8.0.33 和 Elasticsearch 7.17.15 环境中运行验证，不能描述为已经验证通过的最终生产配置。
