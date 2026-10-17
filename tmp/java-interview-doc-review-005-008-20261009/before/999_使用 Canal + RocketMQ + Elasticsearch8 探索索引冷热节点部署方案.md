---
title: "使用 Canal + RocketMQ + Elasticsearch8 探索索引冷热节点部署方案"
source: "https://zhuanlan.zhihu.com/p/573736904"
author:
  - "[[xiattong]]"
published:
created: 2026-10-06
description: "上一篇文章搞定了Elasticsearch8 的集群搭建，这篇准备在此基础上玩点好玩的：实现索引切分和冷热部署。 开始之前，先思考为什么要做索引切分和冷热部署呢？我的答案比较简单： 超大索引切分后，便于索引管理，也…"
tags:
  - "clippings"
---
5 人赞同了该文章

目录

上一篇文章搞定了Elasticsearch8 的集群搭建，这篇准备在此基础上玩点好玩的：实现索引切分和冷热部署。

开始之前，先思考为什么要做索引切分和冷热部署呢？我的答案比较简单：

- 超大索引切分后，便于索引管理，也可以提高查询速度；
- 做冷热部署，可以把经常查询的数据（如三个月内的订单数据）部署到热节点（性能强悍、固态硬盘等，成本高），把不经常查询的数据（如半年前的订单数据）部署到冷节点（性能不高但是能用，机械硬盘等，成本低）。这样可以优化资源的利用，最重要的是：省钱！省钱！省钱！

## 技术栈及版本

以下版本根据本文后续的数据库配置、组件启动命令和依赖声明整理；文章未明确的版本保留为“未明确”，不额外推断。当前项目版本按照项目 `AGENTS.md` 项目级规则列出。

| 组件                           | 文章原始方案                                           | 当前项目技术栈                                      |
| ---------------------------- | ------------------------------------------------ | -------------------------------------------- |
| Java/JDK                     | 文章未明确版本                                          | Java 8                                       |
| Spring Boot                  | 文章未明确版本                                          | 2.7.18                                       |
| MySQL                        | 8.0.29                                           | 8.0.33                                       |
| Canal Server/Deployer        | 1.1.6，使用 `canal.deployer-1.1.6.tar.gz`           | Alibaba Canal Server 1.1.7，单机部署，不使用 ZooKeeper 高可用 |
| Canal Adapter                | 文章未明确                                            | Alibaba Canal Adapter 1.1.7，ES 7 Adapter     |
| RocketMQ                     | 4.9.4                                            | `org.apache.rocketmq:rocketmq-client` 5.2.0  |
| RocketMQ Spring Boot Starter | 2.2.2                                            | 2.3.0                                        |
| Elasticsearch 服务端            | 8.4.2                                            | 7.17.15                                      |
| Elasticsearch Java 客户端       | 8.4.2，使用 `co.elastic.clients:elasticsearch-java` | 具体客户端依赖尚未确认，不能直接使用本文 ES 8 客户端                |
| Jackson Databind             | 2.12.3                                           | 未明确                                          |
| Jakarta JSON API             | 2.0.1                                            | 未明确                                          |
| Redis/Jedis                  | 文章未明确版本                                          | 当前项目规则未明确具体版本                                |
| RocketMQ Dashboard           | 文章示例生成 `rocketmq-dashboard-1.0.1-SNAPSHOT.jar`   | 未明确                                          |

文章原始方案与当前项目的主要版本差异为：Canal 1.1.6 → 1.1.7、RocketMQ 4.9.4 → 5.2.0、RocketMQ Spring Boot Starter 2.2.2 → 2.3.0、MySQL 8.0.29 → 8.0.33、Elasticsearch 8.4.2 → 7.17.15。文章中的 ES 8 Java API、配置和启动参数不能直接视为当前项目实现。

## 文档维护与已确认规则

- 本文保留参考文章原始部署、版本、脚本及演示结果；技术栈表的“当前项目”列用于对照，不代表文章原方案。
- 当前项目的 Canal Server 与本文保持单机部署方式一致，一个 Server 进程承载 db0、db1，使用 file-instance.xml 本地位点模板，不配置 ZooKeeper 主备，故障后人工核对并重启恢复；具体方案见 007，实际落盘和恢复仍待验证。

## 一. 数据库准备

> 数据库版本：Mysql 8.0.29

### 1.1 创建一个canal用户并授权

```sql
-- 创建用户 用户名：canal 密码：canal@xiattong
create user 'canal'@'%' identified by 'canal@xiattong';
-- 授权canal用户, *.*表示所有库
grant SELECT, REPLICATION SLAVE, REPLICATION CLIENT on *.* to 'canal'@'%' with grant option;
```

### 1.2 开启binlog

修改Mysql的配置文件，我的系统是Win11,文件位置是：C:\\ProgramData\\MySQL\\MySQL Server 8.0\\my.ini

```
log-bin="XIATTONG-bin"
binlog-format=ROW
```

修改后重启Mysql，然后查看bin-log实付开启

```sql
-- 查询结果为 "ON",则表示开启了
show variables like 'log_bin';
-- 查询结果 value = ROW
show variables like 'binlog_format';
```

在Mysql中创建一个库和表，会在后面使用到

```sql
-- 创建 order_db 库
CREATE DATABASE order_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建 orders 表
USE order_db;

CREATE TABLE \`orders\` (
  \`id\` bigint(20) unsigned auto_increment NOT NULL COMMENT '主键',
  \`order_no\` varchar(32) NOT NULL DEFAULT '' COMMENT '订单号',
  \`seller_id\` bigint(20) NOT NULL DEFAULT '0' COMMENT '商家id',
  \`user_id\` bigint(20) NOT NULL DEFAULT '0' COMMENT '用户id',
  \`amount\` int(11) NOT NULL DEFAULT '0' COMMENT '订单金额',
  \`status\` int(11) NOT NULL DEFAULT '0' COMMENT '状态',
  \`order_at\` datetime NOT NULL COMMENT '下单时间',
  \`version\` bigint(20) NOT NULL DEFAULT '0' COMMENT '版本号',
  \`is_deleted\` int(11) NOT NULL DEFAULT '0' COMMENT '删除标识',
  PRIMARY KEY (\`id\`) /*T![clustered_index] CLUSTERED */,
  UNIQUE INDEX \`unique_order_no\` (\`order_no\` ASC) VISIBLE,
  KEY \`idex_order_no_user_id\` (\`order_no\`,\`user_id\`),
  KEY \`idx_order_at\` (\`order_at\`),
  KEY \`idx_user_id_seller_id_store_id\` (\`user_id\`,\`seller_id\`),
  KEY \`idx_order_seller\` (\`seller_id\`,\`order_at\`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';
```

## 二. 配置 Canal

> 下载地址： [Releases · alibaba/canal (github.com)](https://link.zhihu.com/?target=https%3A//github.com/alibaba/canal/releases)  
> 版本：v1.1.6 Latest [canal.deployer-1.1.6.tar.gz](https://link.zhihu.com/?target=https%3A//github.com/alibaba/canal/releases/download/canal-1.1.6/canal.deployer-1.1.6.tar.gz)

下载后解压，先修改配置文件conf\\example\\instance.properties，这个配置文件中需要指定binlog日志名称和偏移量，可以先在Mysql中查询

```sql
-- 查看当前正在写入的binlog文件
-- 我的查询结果 File:XIATTONG-bin.000053  Position: 157 
show master status;
```

配置conf\\instance.properties，仅展示有变动的部分

```
# position info
canal.instance.master.address=127.0.0.1:3306
# binlog日志名称
canal.instance.master.journal.name=XIATTONG-bin.000053
# mysql主库链接时起始的binlog偏移量
canal.instance.master.position=157

# username/password
# 配置mysql用户和密码
canal.instance.dbUsername=canal
canal.instance.dbPassword=canal@xiattong
canal.instance.connectionCharset = UTF-8

# table regex
# .*\\..*表示监听所有表
# canal.instance.filter.regex=.*\\..*
# order_db\\orders.*  表示监听 order_db 库中 以orders开头的表
canal.instance.filter.regex=order_db\\.orders.*

# mq里的topic名
canal.mq.topic=orders_to_es

# mq里的动态topic规则
# 针对库名或者表名发送动态topic
#canal.mq.dynamicTopic=mytest,.*,mytest.user,mytest\\..*,.*\\..*

# 单队列模式的分区下标，默认1
# canal.mq.partition=0

# 散列模式的分区数
canal.mq.partitionsNum=8

# 动态获取MQ服务端的分区数
# 如果设置为true之后会自动根据topic获取分区数替换canal.mq.partitionsNum的定义,目前主要适用于RocketMQ
canal.mq.enableDynamicQueuePartition=true

# mq里的动态队列分区数,比如针对不同topic配置不同partitionsNum
#canal.mq.dynamicTopicPartitionNum=test.*:4,mycanal:6

# 散列规则定义
# 库名.表名 : 唯一主键，比如mytest.person:id
# 配置成按照订单号路由，可以保证同一个订单号的数据会被投递到同一个 message queue 中
canal.mq.partitionHash=order_db.orders:order_no
```

配置 conf\\canal.properties，仅展示有变动的部分

```
# tcp, kafka, rocketMQ, rabbitMQ, pulsarMQ
# 消息队列选用 rocketMq 
canal.serverMode = rocketMQ

##################################################
#########     RocketMQ         #############
##################################################
rocketmq.producer.group = MYSQL-ES
rocketmq.enable.message.trace = false
rocketmq.customized.trace.topic = orders_to_es
rocketmq.namespace =
rocketmq.namesrv.addr = 127.0.0.1:9876
rocketmq.retry.times.when.send.failed = 0
rocketmq.vip.channel.enabled = false
rocketmq.tag = orders
```

## 三. 配置RocketMQ

### 3.1 配置NameServer和Broker

> 下载地址： [archive.apache.org/dist](https://link.zhihu.com/?target=https%3A//archive.apache.org/dist/rocketmq/4.9.4/rocketmq-all-4.9.4-bin-release.zip)

解压后，配置环境变量

```
变量名：ROCKETMQ_HOME     变量值：D:\Developer\rocketmq-all-4.9.4
```

为避免我的PC内存不够用，把 NameServer 和 Broker 的运行虚拟机内存调小一点

```powershell
# 文件位置：rocketmq-all-4.9.4\bin\runserver.cmd，只展示修改的部分
rem set "JAVA_OPT=%JAVA_OPT% -server -Xms2g -Xmx2g -Xmn1g -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=320m"
set "JAVA_OPT=%JAVA_OPT% -server -Xms256m -Xmx256m"

# 文件位置：rocketmq-all-4.9.4\bin\runbroker.cmd，只展示修改的部分
rem set "JAVA_OPT=%JAVA_OPT% -server -Xms2g -Xmx2g"
set "JAVA_OPT=%JAVA_OPT% -server -Xms256m -Xmx256m"
```

配置一个包含两个Broker 主节点的集群

```
# 配置 broker-a, 文件位置 rocketmq-all-4.9.4\conf\broker-a.conf
brokerClusterName = rocketmq-cluster
brokerName = broker-a
brokerId = 0
deleteWhen = 04
fileReservedTime = 48
brokerRole = ASYNC_MASTER
flushDiskType = ASYNC_FLUSH
listenPort=10910

#消息存储根路径
storePathRootDir=D:/Developer/rocketmq-all-4.9.4/store/a
#commitLog文件的存储路径
storePathCommitLog=D:/Developer/rocketmq-all-4.9.4/store/a/commitlog
#consume queue文件的存储路径
storePathConsumeQueue=D:/Developer/rocketmq-all-4.9.4/store/a/consumequeue
#消息索引文件的存储路径
storePathIndex=D:/Developer/rocketmq-all-4.9.4/store/a/index
#checkpoint文件的存储路径
storeCheckpoint=D:/Developer/rocketmq-all-4.9.4/store/a/checkpoint
#abort文件的存储路径
abortFile=D:/Developer/rocketmq-all-4.9.4/store/a/abort

# 配置 broker-b, 文件位置 rocketmq-all-4.9.4\conf\broker-b.conf
brokerClusterName = rocketmq-cluster
brokerName = broker-b
brokerId = 0
deleteWhen = 04
fileReservedTime = 48
brokerRole = ASYNC_MASTER
flushDiskType = ASYNC_FLUSH
listenPort=10920

#消息存储根路径
storePathRootDir=D:/Developer/rocketmq-all-4.9.4/store/b
#commitLog文件的存储路径
storePathCommitLog=D:/Developer/rocketmq-all-4.9.4/store/b/commitlog
#consume queue文件的存储路径
storePathConsumeQueue=D:/Developer/rocketmq-all-4.9.4/store/b/consumequeue
#消息索引文件的存储路径
storePathIndex=D:/Developer/rocketmq-all-4.9.4/store/b/index
#checkpoint文件的存储路径
storeCheckpoint=D:/Developer/rocketmq-all-4.9.4/store/b/checkpoint
#abort文件的存储路径
abortFile=D:/Developer/rocketmq-all-4.9.4/store/b/abort
```

### 3.2 配置RocketMQ Dashboard

> 下载地址： [github.com/apache/rocke](https://link.zhihu.com/?target=https%3A//github.com/apache/rocketmq-dashboard)

下载下来的源码，需要打成jar包，打包时会有很多报错，提前做以下设置避免：

```powershell
# 设置淘宝镜像
$ yarn config set registry 'https://registry.npm.taobao.org'

# 检查源码的 \rocketmq-dashboard\pom.xml 配置文件
# 把里面的 nodeVersion 和 yarnVersion 配置成和本机安装的 node.js 和 yarn 版本一致
    <configuration>
        <nodeVersion>v16.17.1</nodeVersion>
        <yarnVersion>v1.22.19</yarnVersion>
    </configuration>

# 然后使用mvn 命令打包
mvn clean package -Dmaven.test.skip=true
```

打包完成后，会在 rocketmq-dashboard\\target 目录下生成 rocketmq-dashboard-1.0.1-SNAPSHOT.jar，可以把这个 jar 包拷贝到自己喜欢地位置。 然后就可以依次启动 RocketMQ 了：

```powershell
# 启动 NameServer
D:\Developer\rocketmq-all-4.9.4\bin>start mqnamesrv.cmd

# 启动 broker-a
D:\Developer\rocketmq-all-4.9.4\bin>start mqbroker.cmd -n 127.0.0.1:9876 -c D:\Developer\rocketmq-all-4.9.4\conf\broker-a.conf

# 启动 broker-b
D:\Developer\rocketmq-all-4.9.4\bin>start mqbroker.cmd -n 127.0.0.1:9876 -c D:\Developer\rocketmq-all-4.9.4\conf\broker-b.conf

# 启动 Dashboard
D:\Developer\rocketmq-all-4.9.4\bin>cd ..
D:\Developer\rocketmq-all-4.9.4>java -jar rocketmq-dashboard-1.0.1-SNAPSHOT.jar
```

接着我们可以验证下 Canal 能否正常监听到 Mysql 的 binlog，并正确的发送到 RocketMQ。运行 canal-1.1.6\\bin\\startup.bat，在数据库中插入和修改数据

```sql
-- 插入
insert into orders(order_no, seller_id, user_id, amount, order_at) values ('D00001', 1000, '15855165619', 100, now());
insert into orders(order_no, seller_id, user_id, amount, order_at) values ('D00002', 1000, '15855165619', 100, now());
-- 修改
update orders set amount = amount + 1, version = version + 1 where id = 1;
update orders set amount = amount + 1, version = version + 1 where id = 2;
```

打开 RocketMQ Dashboard 管理页面：127.0.0.1:8080，就可以看到canle推送的消息了

![](https://pic1.zhimg.com/v2-a591649ce91ba6891576c91ca331bda4_1440w.jpg)

至此Mysql的binlog已经成功地到达了RocketMQ，接下来就可以编码消费消息写入Elasticsearch了。

## 四. 实现数据到 ES 的同步

首先，有个关键的问题需要思考：如何保证 Mysql 数据和Elasticsearch数据的一致性？

以订单数据为例，假如mysql中一笔订单的状态由 0（未支付）变更为1（已支付），再由1变更为2（已完成），当这两条 update 语句对应的内容写入到 es 时，应该也是按照先0到1，再1到2的顺序，否则es和Mysql 就会出现数据不一致！

- 方案一：利用 MQ 的顺序消息

可以先阅读官方文档对顺序一致性的描述：

> Canal: [github.com/alibaba/cana](https://link.zhihu.com/?target=https%3A//github.com/alibaba/canal/wiki/Canal-Kafka-RocketMQ-QuickStart)  
>   
> RocketMQ: [github.com/apache/rocke](https://link.zhihu.com/?target=https%3A//github.com/apache/rocketmq/blob/master/docs/cn/RocketMQ_Example.md%232-%25E9%25A1%25BA%25E5%25BA%258F%25E6%25B6%2588%25E6%2581%25AF%25E6%25A0%25B7%25E4%25BE%258B)

这是可以做到的，首先我们在 conf\\ [instance.properties](https://link.zhihu.com/?target=http%3A//instance.properties/) 中配置了

```
# 散列规则定义
# 库名.表名 : 唯一主键，比如mytest.person:id
# 配置成按照订单号路由，可以保证同一个订单号的数据会被投递到同一个 message queue 中
canal.mq.partitionHash=order_db.orders:order_no
```

这可以保证，相同订单号的数据都会被投递到 RocketMQ 的同一个 Message Queue 中，然后我们只要给每个Message Queue 绑定一个单独的消费者线程就可以了。

但这种方案有个缺点，不便于扩展。假如双十一来临，订单量大增，我们需要扩展Message Queue，扩展后原来的订单可能会被路由到别的Message Queue中，这就很麻烦了！当然，我们可以关闭 canal（生产者） ，等原来的Message Queue中的数据全部消费完后再扩展，但这会造成服务不可用，显然行不通！

- 方案二：利用分布式锁和数据版本号

这个方案不需要关注消息的顺序性，在 [业务代码](https://zhida.zhihu.com/search?content_id=215630059&content_type=Article&match_order=1&q=%E4%B8%9A%E5%8A%A1%E4%BB%A3%E7%A0%81&zhida_source=entity) 中给同一笔订单的操作加上分布式锁，再把待处理数据的版本号和es中已存在的同笔订单版本号进行对比，如果待处理数据的版本号高，则写入 es，否则就丢弃。

这样业务代码虽然复杂了些，都后期扩展很方便，队列的设计也会变得简单很多，后面就采用这个方案。

### 4.1 ES 配置

在 ES 中实现冷热节点部署需要用到索引生命周期管理ILM(Manage the index lifecycle)

> [ILM: Manage the index lifecycle](https://link.zhihu.com/?target=https%3A//www.elastic.co/guide/en/elasticsearch/reference/8.4/index-lifecycle-management.html%23index-lifecycle-management)

配置ES集群冷热节点。修改es集群中每个节点的配置文件，给每个节点指定不同的角色。配置后，重启es集群。

```
## 节点一：elasticsearch-9201/config/elasticsearch.yml  配置成热节点：data_hot
node.roles: [ master, data_content, data_hot, ingest, remote_cluster_client]

## 节点二：elasticsearch-9202/config/elasticsearch.yml 配置成温节点：data_warm
node.roles: [ master, data_content, data_warm, ingest, remote_cluster_client]

## 节点三：elasticsearch-9201/config/elasticsearch.yml 配置成冷节点：data_cold
node.roles: [ master, data_content, data_cold, ingest, remote_cluster_client]
```

配置索引生命周期策略和订单索引模板

```json
## 配置索引生命周期策略
## 为了便于延时，hot节点滚动策略中配置了max_docs=5, 即文档数超过5个就会触发滚动
## 其他各节点配置了文档保存时间为20s，也是为了更快的展示滚动效果
PUT _ilm/policy/order-lifecycle-policy
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_age": "180d",
            "max_primary_shard_size": "50gb",
            "max_docs": 5
          },
          "set_priority": {
            "priority": 100
          }
        },
        "min_age": "0s"
      },
      "warm": {
        "min_age": "40s",
        "actions": {
          "set_priority": {
            "priority": 50
          },
          "forcemerge": {
            "max_num_segments": 1
          },
          "allocate": {
            "number_of_replicas": 0
          }
        }
      },
      "cold": {
        "min_age": "60s",
        "actions": {
          "set_priority": {
            "priority": 0
          },
          "allocate": {
            "number_of_replicas": 0
          }
        }
      },
      "delete": {
        "min_age": "80s",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}

## 配置索引模板并关联索引生命周期策略
PUT _index_template/orders-template
{
  "index_patterns": ["orders-*"],
  "template": {
    "settings": {
      "number_of_shards": 1,
      "number_of_replicas": 0,
      "index.routing.allocation.include._tier_preference": "data_hot",
      "index.lifecycle.name": "order-lifecycle-policy",
      "index.lifecycle.rollover_alias": "orders"
    },
    "mappings": {
      "_source": {
        "enabled": true
      },
      "properties": {
        "id": {
          "type": "long"
        },
        "orderNo": {
          "type": "text",
          "fields": {
            "keyword": {
              "type": "keyword"
            }
          }
        },
        "sellerId": {
          "type": "long"
        },
        "userId": {
          "type": "long"
        },
        "amount": {
          "type": "double"
        },
        "status": {
          "type": "integer"
        },
        "orderAt": {
          "type": "date",
          "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||epoch_millis"
        },
        "version": {
          "type": "long"
        },
        "isDeleted": {
          "type": "integer"
        }
      }
    }
  }
}

## 配置一个初始索引，注意声明的别名和“index.lifecycle.rollover_alias”配置值保持一致
PUT orders-0000001
{
  "aliases": {
    "orders": {
      "is_write_index": true
    }
  }
}

# 设置索引生命周期滚动策略的周期检查时间
# 默认是10分钟，检查索引是否符合策略标准，满足后则触发索引滚动
# 因为默认时间太长，测试时为了尽快看到滚动效果，设置成1秒
PUT _cluster/settings
{
  "persistent": {
    "indices.lifecycle.poll_interval": "1s"
  }
}
```

### 4.2 编写业务代码

导入Elastic 、RocketMQ 、Redis 依赖：

```xml
<!-- Elastic start -->
<dependency>
    <groupId>co.elastic.clients</groupId>
    <artifactId>elasticsearch-java</artifactId>
    <version>8.4.2</version>
</dependency>

<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.12.3</version>
</dependency>

<dependency>
    <groupId>jakarta.json</groupId>
    <artifactId>jakarta.json-api</artifactId>
    <version>2.0.1</version>
</dependency>
<!-- Elastic end -->

<!-- RocketMQ start -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>2.2.2</version>
</dependency>
<!-- RocketMQ end -->

<!-- Redis start -->
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
</dependency>
<!-- Redis end -->
```

关键配置，application.properties

```
## ES 配置
es.host=127.0.0.1
es.port=9201
es.username=elastic
es.password=rlF=_=7wFIrdNj6Zh9Z2
es.connectionTimeout=1500
es.socketTimeout=3000
es.ssl=true
es.certPath=D:/es-cluster/8.4.2/elasticsearch-9201/config/certs/http_ca.crt

## RocketMQ 配置
# NameServer地址
rocketmq.name-server=127.0.0.1:9876

## Redis 配置
redis.host=127.0.0.1
redis.port=6379
redis.password=
redis.db=0
redis.minIdle=10
redis.maxIdle=20
redis.maxTotal=20
redis.maxWaitMills=6000
redis.timeout=3000
```

ElasticsearchClient 配置代码

```java
package xiattong.demo.es.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/**
 * @author ：xiattong
 * @description：
 * @version: $
 * @date ：Created in 2022/10/17 0:25
 * @modified By：
 */
@Configuration
public class ESRestConfig {

    @Value("${es.connectionTimeout}")
    private int connectTimeOut;
    @Value("${es.socketTimeout}")
    private int socketTimeOut;

    @Value("${es.host}")
    private String esHost;

    @Value("${es.port}")
    private Integer esPort;

    @Value("${es.ssl}")
    private Boolean isSSL;

    @Value("${es.certPath}")
    private String certPath;

    @Value("${es.username}")
    private String username;

    @Value("${es.password}")
    private String password;

    private int maxConnectNum = 100;
    private int maxConnectPerRoute = 100;
    private int connectionRequestTimeOut = 5000;

    private void setRequestTimeOutConfig(RestClientBuilder builder) {
        builder.setRequestConfigCallback(builder1 -> {
            builder1.setConnectTimeout(connectTimeOut);
            builder1.setSocketTimeout(socketTimeOut);
            builder1.setConnectionRequestTimeout(connectionRequestTimeOut);
            return builder1;
        });
    }

    /**
     * 权限设置
     * @param builder
     */
    private void setCredentialConfig(RestClientBuilder builder) {
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            if (isSSL) {
                try {
                    Path caCertificatePath = Paths.get(certPath);
                    CertificateFactory factory = CertificateFactory.getInstance("X.509");
                    Certificate trustedCa;
                    try (InputStream is = Files.newInputStream(caCertificatePath)) {
                        trustedCa = factory.generateCertificate(is);
                    }

                    KeyStore trustStore = KeyStore.getInstance("pkcs12");
                    trustStore.load(null, null);
                    trustStore.setCertificateEntry("ca", trustedCa);
                    SSLContextBuilder sslContextBuilder = SSLContexts.custom().loadTrustMaterial(trustStore, null);
                    final SSLContext sslContext = sslContextBuilder.build();

                    CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    httpClientBuilder.setSSLContext(sslContext);
                    httpClientBuilder.setMaxConnTotal(maxConnectNum);
                    httpClientBuilder.setMaxConnPerRoute(maxConnectPerRoute);
                } catch (Exception e) {
                    System.out.printf("配置ES加密集群连接数错误！%s", e);
                    e.printStackTrace();
                }
            } else {
                httpClientBuilder.setMaxConnTotal(maxConnectNum);
                httpClientBuilder.setMaxConnPerRoute(maxConnectPerRoute);
            }
            return httpClientBuilder;
        });
    }

    @Bean
    public ElasticsearchClient esClient() {
        RestClientBuilder builder = RestClient.builder( new HttpHost(esHost, esPort, isSSL ? "https" : "http"));
        // 超时设置
        setRequestTimeOutConfig(builder);
        // 权限配置
        setCredentialConfig(builder);
        ElasticsearchTransport transport = new RestClientTransport(builder.build(), new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
```

RocketMQ 消费者代码

```java
package xiattong.demo.rocketmq;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.annotation.SelectorType;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;
import xiattong.demo.es.ElasticMessageHandler;

import javax.annotation.Resource;

/**
 * @author ：xiattong
 * @description：消息消费者
 * @version: $
 * @date ：Created in 2022/10/15 23:53
 * @modified By：
 */
@Component
@RocketMQMessageListener(consumerGroup = "orders-db-consumer-group", topic = "orders_to_es",
        selectorType = SelectorType.TAG, selectorExpression = "orders")
public class MqConsumer implements RocketMQListener<String> {

    @Resource
    private ElasticMessageHandler elasticMessageHandler;

    @Override
    public void onMessage(String message) {
        try{
            System.out.println(Thread.currentThread().getName()+ "message:" + message);
            elasticMessageHandler.ordersMessageHandle(message);
        }catch(Exception ex){
            ex.printStackTrace();
            System.out.println("消息消费失败了！:"+ ex);
            //抛出异常后，ConsumeConcurrentlyStatus.RECONSUME_LATER
            throw new RuntimeException(ex);
        }

    }
}
```

Redis 配置代码

```java
package xiattong.demo.redis;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @author ：xiattong
 * @description：Redis配置属性
 * @version: $
 * @date ：Created in 2022/10/20 1:39
 * @modified By：
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "redis")
public class RedisProperty {

    /**
     * 主机
     */
    private String host;

    /**
     * 端口
     */
    private Integer port;

    /**
     * 密码
     */
    private String password;

    /**
     * db
     */
    private Integer db;

    /**
     * 超时时间
     */
    private Integer timeout;

    /**
     * 最小空闲连接数
     */
    private Integer minIdle;

    /**
     * 最大空闲连接
     */
    private Integer maxIdle;

    /**
     * 最大连接数
     */
    private Integer maxTotal;

    /**
     * 最大等待时间
     */
    private Long maxWaitMills;
}
```
```java
package xiattong.demo.redis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * @author ：xiattong
 * @description：Redis配置类
 * @version: $
 * @date ：Created in 2022/10/20 1:39
 * @modified By：
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisProperty.class)
public class RedisConfig {

    @Bean
    @Primary
    public JedisPool jedisPool(@Autowired RedisProperty redisProperty) {
        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();

        jedisPoolConfig.setMaxIdle(redisProperty.getMaxIdle());
        jedisPoolConfig.setMaxWaitMillis(redisProperty.getMaxWaitMills());
        jedisPoolConfig.setMaxTotal(redisProperty.getMaxTotal());
        jedisPoolConfig.setMinIdle(redisProperty.getMinIdle());

        JedisPool jedisPool;
        if (StringUtils.isEmpty(redisProperty.getPassword())) {
            jedisPool = new JedisPool(jedisPoolConfig, redisProperty.getHost(), redisProperty.getPort(), redisProperty.getTimeout());
        } else {
            jedisPool = new JedisPool(jedisPoolConfig, redisProperty.getHost(), redisProperty.getPort(), redisProperty.getTimeout(), redisProperty.getPassword());
        }
        return jedisPool;
    }

    @Bean
    public RedisClient RedisClient(@Qualifier("jedisPool") JedisPool jedisPool) {
        return new RedisClient(jedisPool);
    }
}
```
```java
package xiattong.demo.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.Optional;

/**
 * @author ：xiattong
 * @description：RedisClient
 * @version: $
 * @date ：Created in 2022/10/20 1:26
 * @modified By：
 */
public class RedisClient {

    private JedisPool jedisPool;

    public RedisClient(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    /**
     * 分布式锁-获取锁
     * @param key    指定锁
     * @param expire 锁的过期时间，单位秒
     * @return 是否锁定及锁定token
     * @see Optional#isPresent() true表明获取到锁
     * @see Optional#get() 获取到token，用于解锁
     */
    public Optional<String> tryLock(String key, long expire) {
        String token = String.valueOf(System.nanoTime());
        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
            SetParams setParams = new SetParams();
            setParams.ex((int) expire);
            setParams.nx();
            boolean locked = "OK".equals(jedis.set(key, token, setParams));
            if (locked) {
                return Optional.of(token);
            }

        } catch (Exception e) {
            System.out.printf("error when execute redis command, key=%s, error:%s", key, e);
            throw new RuntimeException(e);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
        return Optional.empty();
    }

    private static String UNLOCK_SCRIPT = "local token = redis.call('get',KEYS[1]) if token == ARGV[1] then return redis.call('del',KEYS[1]) elseif token == false then return 1 else return 0 end";

    /**
     * 解除指定的锁定
     *
     * @param key   指定锁
     * @param token 锁定的token
     * @return
     */
    public boolean unlock(String key, String token) {
        Jedis jedis = null;
        try {
            jedis = jedisPool.getResource();
            Long num = (Long) jedis.eval(UNLOCK_SCRIPT, 1, key, token);
            return num > 0;
        } catch (Exception e) {
            System.out.printf("error when execute redis command, key=%s, error:%s", key, e);
            throw new RuntimeException(e);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }
}
```

ES 索引处理代码

```java
package xiattong.demo.es;

/**
 * ES消息类型枚举
 */
public enum ElasticMessageTypeEnum {

    INSERT("INSERT"),
    UPDATE("UPDATE"),
    DELETE("DELETE");

    ElasticMessageTypeEnum(String type) {
        this.type = type;
    }

    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /**
     * @param type
     * @return
     */
    public static ElasticMessageTypeEnum getType(String type) {
        for (ElasticMessageTypeEnum typeEnum : ElasticMessageTypeEnum.values()) {
            if (typeEnum.getType().equals(type)) {
                return typeEnum;
            }
        }
        return null;
    }
}
```
```java
package xiattong.demo.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.OpType;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import xiattong.demo.es.order.model.OrderDto;
import xiattong.demo.redis.RedisClient;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * @author ：xiattong
 * @description：ES消息处理
 * @version: $
 * @date ：Created in 2022/10/16 21:53
 * @modified By：
 */
@Component
public class ElasticMessageHandler {

    @Resource
    private ElasticsearchClient esClient;

    @Resource
    private RedisClient redisClient;

    /**
     * 超时时间 lock
     */
    public static final int LOCK_TIMEOUT = 300;

    /**
     * 对订单消息进行消费
     * @param message
     * @throws RuntimeException
     */
    public void ordersMessageHandle(String message) throws IOException{
        if (StringUtils.isEmpty(message)) {
            return;
        }
        JSONObject msgObj = JSONObject.parseObject(message);
        // 检查 ddl
        if (Boolean.TRUE.equals(msgObj.get("isDdl"))) {
            return;
        }
        // 检查消息类型
        Object typeObj = msgObj.get("type");
        if (Objects.isNull(typeObj) || ElasticMessageTypeEnum.getType(typeObj.toString()) == null) {
            return;
        }
        // 消息处理策略
        doHandler(ElasticMessageTypeEnum.getType(typeObj.toString()), msgObj);
     }

    /**
     * 消息处理策略
     * @param type
     * @param msgObj
     */
    private void doHandler(ElasticMessageTypeEnum type, JSONObject msgObj) throws IOException{
        // 解析出订单数据
        List<OrderDto> orderList = msgObj.getObject("data", new TypeReference<List<OrderDto>>(){});
        if (CollectionUtils.isEmpty(orderList)) {
            return;
        }
        switch (type) {
            case INSERT:
                insertProcess(orderList);
                break;
            case UPDATE:
                updateProcess(orderList);
                break;
            case DELETE:
                deleteProcess(orderList);
                break;
        }
    }

    /**
     * 新增数据
     * @param orderList
     */
    private void insertProcess(List<OrderDto> orderList) {
        // 新增数据不需要做版本校验，直接写入es
        for (OrderDto orderDto : orderList) {
            Optional<String> lock = Optional.empty();
            try {
                // 给订单加锁
                lock = redisClient.tryLock(orderDto.getOrderNo(), LOCK_TIMEOUT);
                if (lock.isPresent()) {
                    IndexRequest<OrderDto> request = IndexRequest.of(i -> i
                            .index("orders")
                            .requireAlias(Boolean.TRUE)
                            .id(orderDto.getId().toString())
                            .opType(OpType.Create)
                            .document(orderDto));

                    IndexResponse response = esClient.index(request);
                    System.out.printf("订单写入ES成功！orderNo:{}, version:{}", orderDto.getOrderNo(), response.version());
                }
            } catch (Exception e) {
                e.printStackTrace();
                // 抛出异常后，消息会重发
                throw new RuntimeException(e);
            } finally {
                if (lock.isPresent()) {
                    redisClient.unlock(orderDto.getOrderNo(), lock.get());
                }
            }
        }
    }

    /**
     * 更新数据
     * @param orderList
     */
    private void updateProcess(List<OrderDto> orderList) {
        for (OrderDto orderDto : orderList) {
            Optional<String> lock = Optional.empty();
            try {
                // 给订单加锁
                lock = redisClient.tryLock(orderDto.getOrderNo(), LOCK_TIMEOUT);
                if (lock.isPresent()) {
                    // 更新需要先去ES中查询数据并比对版本号
                    SearchResponse<OrderDto> searchResponse = esClient.search(s -> s
                                    .index("orders")
                                    .query(q -> q.match(t -> t.field("orderNo").query(orderDto.getOrderNo()))),
                            OrderDto.class
                    );
                    List<Hit<OrderDto>> hits = searchResponse.hits().hits();
                    if (CollectionUtils.isNotEmpty(hits)) {
                        for (Hit<OrderDto> hit : hits) {
                            OrderDto oldOrder = hit.source();
                            // 检查版本，新数据版本低时直接丢弃
                            if (oldOrder.getVersion() >= orderDto.getVersion()) {
                                continue;
                            }
                        }
                    }

                    // 先删除
                    DeleteByQueryRequest deleteRequest = DeleteByQueryRequest.of(i -> i
                            .index("orders")
                            .query(q -> q.match(t -> t.field("orderNo").query(orderDto.getOrderNo()))));
                    esClient.deleteByQuery(deleteRequest);

                    // 再写入
                    IndexRequest<OrderDto> indexRequest = IndexRequest.of(i -> i
                            .index("orders")
                            .requireAlias(Boolean.TRUE)
                            .id(orderDto.getId().toString())
                            .opType(OpType.Create)
                            .document(orderDto));

                    IndexResponse indexResponse = esClient.index(indexRequest);
                    System.out.printf("更新ES成功！orderNo:{}, version:{}", orderDto.getOrderNo(), indexResponse.version());
                }
            } catch (Exception e) {
                e.printStackTrace();
                // 抛出异常后，消息会重发
                throw new RuntimeException(e);
            } finally {
                if (lock.isPresent()) {
                    redisClient.unlock(orderDto.getOrderNo(), lock.get());
                }
            }
        }
    }

    /**
     * 删除数据
     * @param orderList
     */
    private void deleteProcess(List<OrderDto> orderList) {
        for (OrderDto orderDto : orderList) {
            Optional<String> lock = Optional.empty();
            try {
                // 给订单加锁
                lock = redisClient.tryLock(orderDto.getOrderNo(), LOCK_TIMEOUT);
                if (lock.isPresent()) {
                    DeleteRequest request = DeleteRequest.of(i -> i.index("orders")
                            .id(orderDto.getId().toString()));
                    DeleteResponse response = esClient.delete(request);
                    System.out.printf("删除ES中订单成功！orderNo:{}, version:{}", orderDto.getOrderNo(), response.version());
                }
            } catch (Exception e) {
                e.printStackTrace();
                // 抛出异常后，消息会重发
                throw new RuntimeException(e);
            } finally {
                if (lock.isPresent()) {
                    redisClient.unlock(orderDto.getOrderNo(), lock.get());
                }
            }
        }
    }
}
```

### 4.3 效果演示

确认 Canal、RocketMQ、Reids、ES集群服务、Kibana 服务都已开启，并运行业务代码，然后在MySql 中插入数据，查看索引滚动效果：当索引orders-000004的文档数超过5条后，新的文档会自动写到新的索引orders-000005中，同时orders-000004会按照索引生命周期配置的策略自动滚动，先后在data-warm节点（es-9202）和data-cold（es-9203）节点保存20秒后被删除。

![](https://pic1.zhimg.com/v2-a4e5233ce8b5bf8a3daf489eb9e8b746_1440w.jpg)

![](https://picx.zhimg.com/v2-eeeba8823659dd1d037eb880a51deb5d_1440w.jpg)

![](https://picx.zhimg.com/v2-bce8d1a169eab7f64c31b1b6a5d7ee63_1440w.jpg)

![](https://picx.zhimg.com/v2-5707cd5bc277aa0f2682d41ef8ca87c7_1440w.jpg)

## 五. 总结

这篇文章写了ES索引生命周期管理ILM的应用，讨论了如何保持MySql和ES的数据一致性。在实现过程中用了一些中间件: Canal、RocketMQ、Reids。整个实现思路不算复杂。

欢迎大家吐槽~

编辑于 2022-10-23 16:37

赞同 5
