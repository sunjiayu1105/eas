## 一、阿里云文章的整体方案

参考文章：[使用 Zookeeper 搭建 Canal 主从集群实现高可用](https://developer.aliyun.com/article/1082111)


文章的核心链路是：

```
MySQL
  │ Binlog
  ▼
Canal Deployer 主从集群
  │ ZooKeeper 选主、注册、保存状态
  ▼
Canal Adapter 多节点
  │ TCP 订阅 Canal Server
  ▼
Elasticsearch 7.x
```

文章将 Canal 分为两部分：

- **Canal Server/Deployer**：从 MySQL 读取 Binlog；
- **Canal Client/Adapter**：从 Canal Server 获取消息，并写入 ES。

文章认为 Canal 的高可用本质是主从模式：

- 同一个 Canal Instance 同时只能由一个 Server 节点读取 Binlog；
- 其他 Server 节点处于 Standby；
- 主节点宕机后，ZooKeeper 协调备用节点接管；
- Adapter 可以多个节点同时运行，由 ZooKeeper 和 Canal Client 机制进行消息分发。

文章的环境和部署过程如下：

1. 启动 ZooKeeper；
2. 准备两个 Canal Deployer 节点；
3. 两个节点配置相同的 destination；
4. `canal.properties` 配置 `canal.zkServers`；
5. `instance.properties` 配置 MySQL 地址、账号、密码和 Binlog 起点；
6. 启动两个 Deployer，但同一时间只有一个节点真正工作；
7. 部署两个 Canal Adapter 节点；
8. Adapter 使用 TCP 模式连接 Canal Server；
9. Adapter 通过 `zookeeperHosts` 查找当前可用的 Canal Server；
10. 通过 `application.yml` 配置 ES 7 Adapter；
11. 通过 `user.yml` 配置 MySQL 表到 ES 索引的映射；
12. 停止工作中的 Deployer，验证备用 Deployer 接管；
13. 停止一个 Adapter，验证另一个 Adapter 仍能同步数据。

文章中使用的主要版本是：

|组件|文章版本|
|---|---|
|MySQL|MySQL 8.0|
|Canal Deployer|1.1.6|
|Canal Adapter|1.1.6|
|Elasticsearch|ES 7.x|
|Java|文章写明 Canal 1.1.6 使用 JDK 11+|
|ZooKeeper|使用 ZooKeeper，演示环境为单节点|

因此，文章架构与当前项目接近，但版本并不完全一致。

---

## 二、MySQL Binlog 方案对比

### 相同点

阿里云文章和项目 `006` 文档都采用以下思路：

- MySQL 开启 Binlog；
- Canal 通过复制账号模拟 MySQL 从库读取 Binlog；
- Binlog 用于记录历史导入和后续业务写入；
- Canal Server 从 MySQL 获取增量事件；
- 最终由 Adapter 将数据同步到 ES。

两者都强调 MySQL 必须先具备可读取的 Binlog 条件，否则 Canal 无法同步数据。

### 不同点

文章主要关注“如何搭建 Canal 集群”，MySQL 部分比较简略，重点是查询 Binlog 文件和位置：

```
SHOW BINARY LOGS;
```

项目 `006` 文档则进一步定义了更严格的同步起点和恢复规则：

- 使用 MySQL 8.0.33；
- 开启 GTID；
- 设置 `binlog_format=ROW`；
- 设置 `binlog_row_image=FULL`；
- Binlog 保留 30 天；
- 两个 MySQL 实例分别维护自己的：
    - `server_uuid`
    - GTID 基线
    - destination
    - 消费进度；
- 历史导入前先固定 GTID 基线；
- 导入和业务写入连接必须保持 `sql_log_bin=ON`；
- 不能用最新 GTID 覆盖历史导入前基线；
- 已确认的 ES 处理进度才能推动后续消费进度。

因此，项目方案比文章增加了以下内容：

```
Binlog 文件/位置方案
        ↓
GTID 基线方案
        ↓
按实例隔离同步起点
        ↓
按 ES 成功处理结果推进可靠进度
```

文章中的 Binlog 起点更接近演示型配置，项目文档则考虑了历史导入、断点恢复、日志过期和多 MySQL 实例隔离。

---

## 三、Canal Server 部署方案对比

### 相同点

文章和项目 `007` 的 Canal Server 方案基本一致：

- Canal Server 多节点部署；
- 多个节点连接同一个 ZooKeeper；
- 多个节点配置相同的 destination；
- 同一个 destination 同一时间只允许一个节点工作；
- 其他节点作为备用节点；
- ZooKeeper 负责节点注册、存活检测和主节点选举；
- 工作节点故障后，备用节点接管；
- 新节点从已保存的消费进度继续读取；
- Canal Server 负责读取、解析和投递 Binlog，不负责 ES 文档转换。

典型拓扑都是：

```
Canal Server 1
       │
       ├── ZooKeeper
       │
Canal Server 2
       │
       └── 同一个 destination
```

### 不同点

文章使用一个 `cluster` destination 进行演示：

```
canal.destinations=cluster
```

项目 `007` 则明确要求两个 MySQL 实例分别使用两个 destination：

```
canal.destinations=db0,db1
```

项目方案是：

```
MySQL 实例 A → destination=db0
MySQL 实例 B → destination=db1
```

每个 destination 都有独立的：

- MySQL 地址；
- 复制账号；
- `slaveId`；
- GTID 起点；
- 过滤规则；
- 消费位点。

项目文档还增加了文章没有展开的边界：

- 初始 GTID 只用于首次启动或没有可恢复位点的情况；
- 正常重启优先使用已持久化确认位点；
- 主备切换期间可能存在短暂中断和积压；
- Binlog 保留期已确认是 30 天；
- Binlog 断档、位点恢复失败、DDL、TRUNCATE 和未知事件不能静默跳过；
- 具体位点存储方式和配置键名仍需按 Canal Server 1.1.7 发行包确认。

### Canal Server 对比表

| 对比项         | 阿里云文章             | 项目文档                 |
| ----------- | ----------------- | -------------------- |
| Server 节点   | 两个 Deployer       | 多节点主备                |
| destination | 一个 `cluster`      | 两个：`db0`、`db1`       |
| ZooKeeper   | 负责主备协调            | 负责主备协调               |
| 工作模式        | 同一 Instance 单节点工作 | 同一 destination 单节点工作 |
| 起点          | Binlog 文件、位置、时间戳  | GTID 基线 + 已确认消费位点    |
| MySQL 实例    | 主要演示单实例           | 两个独立实例               |
| 恢复规则        | 文章未深入展开           | 明确区分首次起点和可靠消费进度      |
| 日志保留        | 未明确               | 已确认 30 天             |
| 异常边界        | 介绍较少              | 明确要求告警、暂停和人工处理       |

---

## 四、Canal Adapter 处理方案对比

### 相同点

文章和项目 `008` 对 Adapter 的职责定义基本一致：

- Adapter 通过 TCP 连接 Canal Server；
- Adapter 根据 destination 或 instance 接收对应任务的数据；
- Adapter 根据表映射规则转换消息；
- Adapter 将 INSERT、UPDATE、DELETE 转成 ES 操作；
- Adapter 使用 ES 7 Adapter；
- Adapter 通过 `_bulk` 批量写入 ES；
- Adapter 节点可以部署多个；
- 停止一个 Adapter 后，其他 Adapter 继续处理同步任务。

文章中使用：

```
mode: tcp
zookeeperHosts: zk:2181
```

并配置：

```
canalAdapters:
  - instance: cluster
    groups:
      - groupId: g1
```

项目 `008` 则分别配置两个任务：

```
canalAdapters:
  - instance: db0
  - instance: db1
```

### 不同点

文章重点是“Adapter 节点可以多个同时运行”，但没有详细描述每批消息如何确认。

项目 `008` 对 Adapter 的处理链路定义得更细：

```
拉取 Canal 批次
  ↓
匹配库表和事件类型
  ↓
生成 ES 操作
  ↓
按顺序拆分 Bulk 子批次
  ↓
逐项检查 Bulk 响应
  ↓
所有子项成功
  ↓
ack Canal 批次
```

项目明确规定：

- HTTP 200 不等于 Bulk 中每一项都成功；
- 必须逐项检查 ES 响应；
- 任意子项失败都不能 ack；
- ES 写入成功但 ack 失败时，可能发生重复投递；
- 不能宣称 exactly-once；
- 必须使用稳定业务主键保证幂等；
- `consumeId` 作为 ES 文档 `_id`；
- rollover 后历史更新和删除仍需定位原具体索引；
- `dataSourceKey` 仅用于映射时回查 MySQL，不负责获取增量消息；
- `name: es7` 是 Adapter 适配器名称，不是 ES 服务端插件。

文章的映射配置比较简单，例如：

```
dataSourceKey: canalDs
outerAdapterKey: es
destination: cluster
groupId:
```

项目方案则更强调配置之间的对应关系：

```
Server destination=db0
        ↓
Adapter instance=db0
        ↓
映射 destination=db0
        ↓
dataSourceKey=ds0
        ↓
ES 目标索引
```

### Canal Adapter 对比表

| 对比项        | 阿里云文章       | 项目文档                              |
| ---------- | ----------- | --------------------------------- |
| 连接模式       | TCP         | TCP                               |
| Server 发现  | ZooKeeper   | ZooKeeper                         |
| Adapter 节点 | 两个同时运行      | 候选多节点部署                           |
| 映射任务       | `cluster`   | `db0`、`db1`                       |
| ES 适配器     | `es7`       | `es7`                             |
| 批量写入       | `_bulk`     | `_bulk`                           |
| 响应处理       | 主要验证同步结果    | 逐项检查每个 Bulk item                  |
| ack 规则     | 文章未细化       | 所有子批次成功后才 ack                     |
| 幂等设计       | 未重点说明       | 使用 `consumeId` 作为 `_id`           |
| 失败重放       | 未深入展开       | 明确允许重复投递并要求幂等                     |
| rollover   | 未涉及         | 明确要求定位原具体索引                       |
| 回查数据源      | 有 `canalDs` | 明确区分 `instance` 和 `dataSourceKey` |

---

## 五、Canal Server 部署方案的异同

### 共同点

两套方案都采用：

```
多台 Canal Server
        ↓
共享 ZooKeeper
        ↓
同一 destination 主备
        ↓
一个工作节点，其他备用
```

共同目标是：

- 避免单个 Canal Server 故障导致同步中断；
- 避免多个 Server 同时读取同一个 MySQL Binlog；
- 保证同一个 Instance 的消费顺序；
- 通过 ZooKeeper 进行节点协调和故障接管。

### 主要差异

项目方案比文章更细化：

1. 文章使用一个 destination 演示；
2. 项目按两个 MySQL 实例拆成 `db0` 和 `db1`；
3. 每个 destination 独立维护 GTID 和消费进度；
4. 项目明确 Binlog 保留 30 天；
5. 项目增加 Binlog 断档和位点恢复边界；
6. 项目要求稳定业务主键保证重复投递幂等；
7. 项目强调主备切换不是零中断；
8. 项目要求以 Canal Server 1.1.7 实际发行包核对配置键名和位点机制。

---

## 六、Canal Adapter 部署方案的异同

### 共同点

文章和项目都认可 Adapter 可以多节点部署：

```
Adapter 1 ─┐
            ├── 连接 Canal Server
Adapter 2 ─┘
```

共同特点：

- 多个 Adapter 节点可同时运行；
- 节点连接 ZooKeeper 或活动 Canal Server；
- 一个 Adapter 节点故障后，其他节点仍可能继续处理；
- Adapter 多节点主要解决消费处理层的可用性；
- ES 集群仍需要单独保证高可用。

### 主要差异

文章将 Adapter 多节点描述为可以直接同时提供服务，由 ZooKeeper 分发任务。

项目 `008` 对 Adapter 高可用更谨慎，采用的是候选主备方案：

- 两台主机部署相同版本 Adapter；
- 正常只有一个节点运行；
- 故障时先确认旧节点停止或完成隔离；
- 再启动备用节点；
- 备用节点使用相同订阅身份恢复；
- 防止两个 Adapter 同时消费导致重复处理。

也就是说，两者在“Adapter 可以多节点部署”这一点上相同，但对运行模型的描述不同：

|项目|阿里云文章|项目文档|
|---|---|---|
|Adapter 节点|多节点同时运行|候选主备部署|
|故障处理|其他节点继续服务|先隔离旧主，再启动备节点|
|ZooKeeper 作用|参与任务分发|可发现活动 Server，但不能单独证明 Adapter 自动主备|
|双主风险|文章未重点讨论|明确要求防止双主|
|消费身份|文章未深入说明|要求确认订阅身份和位点恢复|
|HA 结论|偏向“多节点服务”|明确标注为待验证候选方案|

这里需要特别区分两个概念：

- **Canal Server 高可用**：ZooKeeper 主备选举，同一个 destination 同时只有一个 Server 工作；
- **Canal Adapter 高可用**：多个 Adapter 节点能否安全同时消费，取决于 group、订阅身份、位点和版本实现，不能仅凭配置中写了 ZooKeeper 就认定已经具备自动主备能力。

---

## 七、综合结论

这篇阿里云文章和项目文档的总体架构是一致的：

```
MySQL Binlog
  → Canal Server
  → Canal Adapter
  → Elasticsearch
```

共同的核心方案是：

- MySQL 开启 Binlog；
- Canal Server 负责读取和解析；
- ZooKeeper 协调 Canal Server 主备；
- Canal Adapter 负责转换并写 ES；
- ES 使用 `es7` 适配器；
- Adapter 可多节点部署；
- 通过故障切换保持同步能力。

项目方案比文章进一步补充了生产化要求：

- 技术版本固定到 MySQL 8.0.33、Canal 1.1.7、ES 7.17.15；
- Java 固定为 Java 8；
- 两个 MySQL 实例对应 `db0`、`db1` 两个 destination；
- 使用 GTID 作为初始同步基线；
- Binlog 保留 30 天；
- 区分初始 GTID 和后续可靠消费位点；
- ES Bulk 必须逐项检查；
- 所有 ES 子批次成功后才能 ack；
- 通过稳定业务主键处理重复投递；
- 对 Adapter 高可用采用更谨慎的主备接管方案；
- 明确配置键名、位点机制和自动接管能力仍需以 Canal 1.1.7 发行包和故障演练为准。

最终建议采用下面的部署模型：

```
MySQL A ── Binlog ── Canal Server db0 主备 ──┐
                                             ├── Canal Adapter ── ES 集群
MySQL B ── Binlog ── Canal Server db1 主备 ──┘
```

其中：

- Canal Server：采用 ZooKeeper 主备高可用；
- Canal Adapter：至少部署两个节点，但要明确消费组、订阅身份、位点恢复和双主隔离策略；
- Elasticsearch：单独部署 ES 7.17.15 集群；
- MySQL：两个实例分别维护自己的 GTID、destination 和消费进度。
