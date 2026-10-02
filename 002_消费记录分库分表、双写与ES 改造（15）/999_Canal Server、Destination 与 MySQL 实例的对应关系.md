再次检查后，整体内容没有原则性错误。需要保留的两个边界如下：

1. `server-id` 并不是要求所有独立 MySQL 实例在任何环境中都绝对不能重复，而是要求在同一复制拓扑中唯一。生产环境建议不同设置。
2. Canal `slaveId` 主要用于标识 Canal 模拟的从库连接。不同 destination 建议使用不同值，便于识别并避免未来复制拓扑变化造成冲突，但不能简单表述为所有独立 MySQL 实例之间都强制全局唯一。

下面是已按上述边界修订后的版本。

# Canal Server、Destination 与 MySQL 实例的对应关系

> 物理命名已确认：两个独立实例的库名均为 eas，每个实例包含 consume_record_01～consume_record_16。下文 consume_db0、consume_db1 及宽泛表名正则仅保留为旧示例，不是当前物理命名；当前过滤配置以 007 为准，db0、db1 仍是任务名。

> 复制身份示例范围：本文后续 1001、1002 等固定 slaveId 仅用于解释复制身份区别，不作为当前集群配置。007 已按 Canal 1.1.7 官方模板改为保留自动生成机制，实际身份及冲突仍需核验；不能把这里的固定值直接放入集群共享 Instance 配置。官方依据见 007 第 9 节。

> 当前配置入口说明：项目已在 007 中选定 Canal Admin 集中管理。本文后续本地目录和文件示例仅用于理解公共配置与 Instance 配置的逻辑归属，不再作为当前部署入口；实际在 Admin 中管理集群和 db0、db1，Server 本地保留接入 Admin 的引导配置。Server、destination 与两个分库实例的对应关系不变。

以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

## 一、核心结论

一个 Canal Server 进程可以加载多个 destination：

```properties
canal.destinations=db0,db1
```

通常可以按以下关系理解：

```text
一个 destination
    ↔ 一个需要独立维护 binlog 位点的 MySQL 数据源
```

因此：

- 同一个 MySQL 实例中的多个数据库，可以由一个 destination 统一监听；
- 两个独立的 MySQL 实例，通常应配置两个 destination；
- 一个 Canal Server 进程可以同时加载多个 destination；
- 一个 Canal Adapter 进程也可以同时配置多个对应实例。

单个 Server 可同时承载两个任务，下面仅展示该节点内的逻辑关系；当前完整部署按 007 使用两台不同主机上的 Server，接入 Admin 中的同一集群，两台均具备承载这两个任务的配置：

```text
一个 Canal Server 进程
  ├── destination=db0 → MySQL 实例 0
  └── destination=db1 → MySQL 实例 1

一个 Canal Adapter 进程
  ├── instance=db0
  └── instance=db1
```

图中是**一个 Server 进程内的两个 destination**，不是完整集群只有一个进程，也不是一个 destination 同时监听两个独立的 MySQL 实例。完整集群由 ZooKeeper 按 destination 分别协调运行资格，两个任务允许同机或分布运行，不强制绑定切换；单节点承接两个任务的容量仍需验证。

## 二、为什么两个独立 MySQL 实例需要两个 destination

### 1. 连接地址不同

```properties
# db0
canal.instance.master.address=192.168.10.11:3306

# db1
canal.instance.master.address=192.168.10.12:3306
```

两个独立 MySQL 实例具有不同的连接地址，需要分别配置。

### 2. binlog 位点相互独立

每个 MySQL 实例都有自己的：

```text
binlog 文件
position
GTID 或事务位点
```

Canal 必须分别保存和恢复两个数据源的消费进度，不能用一套位点同时表示两个实例的同步状态。

### 3. 复制身份需要区分

`server-id` 需要在同一复制拓扑中保持唯一。即使两个 MySQL 实例当前完全独立，也建议设置不同值：

```ini
# MySQL 实例 0
server-id=101

# MySQL 实例 1
server-id=102
```

Canal 的 `slaveId` 也建议分别设置：

```properties
# db0
canal.instance.mysql.slaveId=1001

# db1
canal.instance.mysql.slaveId=1002
```

这样便于识别不同数据源，并避免后续建立主备或复制关系时出现身份冲突。

### 4. 两个实例之间没有天然的全局事务顺序

每个 MySQL 实例内部都有自己的事务顺序，但两个独立实例之间没有天然的全局 binlog 顺序。

因此，Canal 可以分别读取两个实例的 binlog，但不能简单地将它们合并成一个具有严格全局顺序的事件流。

### 5. 监听范围可能不同

```properties
# db0
canal.instance.filter.regex=consume_db0\\.consume_record_.*

# db1
canal.instance.filter.regex=consume_db1\\.consume_record_.*
```

两个 destination 可以分别限定自己的数据库和表范围。

## 三、什么时候可以只使用一个 destination

如果多个数据库属于同一个 MySQL 实例，并共用：

- 同一个连接地址；
- 同一套 binlog；
- 同一套消费位点；
- 同一个复制身份体系；

则可以由一个 destination 统一监听：

```properties
canal.instance.filter.regex=db_a\\..*,db_b\\..*
```

因此：

```text
同一 MySQL 实例的多个数据库
    → 可以使用一个 destination

两个独立的 MySQL 实例
    → 通常需要两个 destination
```

## 四、Canal Server 配置关系

```properties
# /opt/canal/server/conf/canal.properties
canal.destinations=db0,db1
```

目录结构：

```text
/opt/canal/server/conf/
├── canal.properties
├── db0/
│   └── instance.properties
└── db1/
    └── instance.properties
```

`db0/instance.properties`：

```properties
canal.instance.master.address=192.168.10.11:3306
canal.instance.mysql.slaveId=1001
canal.instance.filter.regex=consume_db0\\.consume_record_.*
```

`db1/instance.properties`：

```properties
canal.instance.master.address=192.168.10.12:3306
canal.instance.mysql.slaveId=1002
canal.instance.filter.regex=consume_db1\\.consume_record_.*
```

两个 destination 还应分别配置 MySQL 账号、字符集、位点模式、表过滤规则和位点恢复信息。

## 五、Canal Adapter 配置关系

一个 Adapter 进程可以配置多个实例：

```yaml
canalAdapters:
  - instance: db0
    groups:
      - groupId: g1
        outerAdapters:
          - name: es7

  - instance: db1
    groups:
      - groupId: g1
        outerAdapters:
          - name: es7
```

对应关系必须一致：

```text
Canal Server 的 destination=db0
    ↔ Adapter 的 instance=db0

Canal Server 的 destination=db1
    ↔ Adapter 的 instance=db1
```

具体配置格式仍应以实际使用的 Canal Adapter 发行版本为准。

## 六、部署方式选择

### 一个 Server、一个 Adapter

```text
Canal Server
  ├── destination=db0
  └── destination=db1

Canal Adapter
  ├── instance=db0
  └── instance=db1
```

部署简单、运维成本低，但任一进程故障都可能同时影响两条同步链路。

### 两套 Server 和 Adapter

```text
Canal Server 0 → destination=db0 → Adapter 0
Canal Server 1 → destination=db1 → Adapter 1
```

故障隔离能力更强，但部署、发布和运维成本更高。

## 七、VIP、代理和主备地址的边界

如果通过 VIP 或代理连接 MySQL：

```text
Canal → VIP 或代理 → 当前 MySQL 主库
```

在代理能够正确路由到当前主库的前提下，Canal 仍然是从一个当前可用的 MySQL 主库读取 binlog。VIP 或代理只能提供稳定访问地址，不能把两个独立 MySQL 实例合并成一个数据源。

因此，最终关系是：

```text
一个 Canal Server 进程
  ├── destination=db0 → MySQL 实例 0
  └── destination=db1 → MySQL 实例 1
```

而不是：

```text
一个 destination → MySQL 实例 0 + MySQL 实例 1
```
