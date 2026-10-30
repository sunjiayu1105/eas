const fs=require('fs'),path=require('path'),os=require('os');
const root='D:/000/sources/eas', dir=path.join(root,'002_消费记录分库分表、双写与ES 改造（15）');
const backup=fs.mkdtempSync(path.join(os.tmpdir(),'canal-restructure-'));
const before={};
function walk(d){return fs.readdirSync(d,{withFileTypes:true}).flatMap(e=> e.name==='.git'||e.name==='tmp'?[]:e.isDirectory()?walk(path.join(d,e.name)):e.name.endsWith('.md')?[path.join(d,e.name)]:[])}
for(const p of walk(root))before[p]=fs.readFileSync(p,'utf8');
fs.writeFileSync(path.join(backup,'before.json'),JSON.stringify(before));
const names={},s={}; for(let i=12;i<=26;i++){const n=fs.readdirSync(dir).find(n=>n.startsWith(String(i).padStart(3,'0')+'_'));if(n){names[i]=n;s[i]=before[path.join(dir,n)].replace(/\r\n/g,'\n')}}
const part=(t,a,b)=>t.slice(t.indexOf(a),b?t.indexOf(b,t.indexOf(a)+a.length):undefined);
const link=i=>'['+names[i].slice(4,-3)+'](<./'+names[i]+'>)';
const version='以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。';
const review='已执行 [java-interview-doc-review-loop](C:/Users/Administrator/.codex/skills/java-interview-doc-review-loop/SKILL.md)。本轮按主题归属去重并复核标题、编号、引用和示例；未执行 MySQL、Canal、RocketMQ 或 ES 联调。';
const out={...s};
// 018: one field reference and one representative multi-row example.
const rules=part(s[18],'## 1 ','## 2 ');
const compare=part(s[18],'## 4 ','## 5 ');
const pending=part(s[18],'## 5 ','## 6 ');
const history=part(s[18],'## 6 ');
const example=part(s[18],'```json','```\n').replace(/"version": (\d+)/g,'"version": "$1"');
// Explicit extraction of the first fenced JSON.
let json=JSON.parse(s[18].match(/```json\n([\s\S]*?)\n```/)[1]);
json.table='consume_record_01'; json.gtid=null; json.es=Date.parse('2026-10-27T09:20:00+08:00');json.ts=json.es+12;
for(const r of [...json.data,...json.old]){if(r.version!==undefined)r.version=String(r.version);if(r.status==='处理中')r.status='异常（未确定）'}
out[18]=`# ${names[18].slice(0,-3)}\n\n${version}\n\n${rules}## 2 面试口述精炼版本\n\n**消费端收到一个对象，逐行处理 data**\n\n项目采用 FlatMessage JSON：一条 RocketMQ 消息的 body 是一个 JSON 对象，data 数组可以包含同一张表、同一操作类型的多条行变更。INSERT、UPDATE 取行后值，DELETE 取行前值；UPDATE 的 old 保存变化列旧值。消费端遍历 data，完整行通过校验后才用于 ES 覆盖写入。配置加载和真实消息仍待验证，字段与示例见[详细方案](#3-详细方案)。\n\n## 3 详细方案\n\n### 3.1 消息体、FlatMessage 和行数据\n\nFlatMessage 是 Canal 的 Java 消息对象；FlatMessage JSON 是它序列化后的形式。当前内置生产端在 flatMessage 路径中逐个对象序列化，将 JSON 字节作为 RocketMQ Message 的 body。一次批量发送可以发送多个 Message，但每个 body 仍是一个 FlatMessage 对象，不是多个 FlatMessage 组成的外层数组。\n\n一个对象只有一个 database、table、type；data 内可以有多行，但不会把 INSERT 和 UPDATE 混在同一个对象中。消费端一次回调拿到多少条 RocketMQ 消息，是消费 API 的批量行为，与一个 body 中有多少行不同。\n\n全局配置 conf/canal.properties 要求 canal.mq.flatMessage=true。配置全集见 ${link(14)}；完整处理过程见 ${link(12)}。false 对应 Canal 二进制协议，需要解码 Packet/Messages 和 Entry，不是项目采用的格式。\n\n### 3.2 字段含义\n\n| 字段 | 含义与读取方式 |\n| --- | --- |\n| id | Canal 批次标识；多个转换或拆分对象可能共享它，不能单独用于事件去重 |\n| database、table | 来源库名、表名；不是 ES 索引名 |\n| type | INSERT、UPDATE、DELETE 等操作类型，决定处理分支 |\n| isDdl | 是否为结构变更事件；不能当普通行事件处理 |\n| pkNames | 源表主键列名列表 |\n| data | 行对象数组；INSERT/UPDATE 是行后列，DELETE 是行前列 |\n| old | UPDATE 变化列旧值；不是完整前镜像；其他类型可能为空或 null |\n| sqlType | 列名到 JDBC SQL 类型编号的映射 |\n| mysqlType | 列名到 MySQL 类型名称的映射 |\n| es | 事件执行时间，毫秒时间戳；不是 Elasticsearch 的缩写 |\n| ts | 转换时系统时间，毫秒时间戳 |\n| sql | 事件 SQL 信息，可能为空；不保证有原始 DML SQL |\n| gtid | 来源 Header 中的 GTID；可能为空，不保证每条可用 |\n\n源码中的 data、old 是 List<Map<String,String>>：非空列值通常以字符串输出，SQL NULL 对应 JSON null。消费端需按业务类型转换 version 等字段，不能按字符串字典序比较版本。consume_id、status、version、create_time 是行内的业务列，不是 FlatMessage 固定顶层字段；es、ts 都不能代替原始 create_time。\n\n### 3.3 一条消息包含三行 UPDATE 的示例\n\n以下是合法 JSON 的精简示例。库表名、状态文字、时间和版本均为说明值，不是实测消息；为突出关系仅列四个业务列，省略 id、sql、类型映射等顶层字段。它不能作为完整生产行的字段清单。\n\n\`\`\`json\n${JSON.stringify(json,null,2)}\n\`\`\`\n\n- data[0] 与 old[0] 对应 c-1002：状态从“异常（未确定）”变为“成功”，版本 1 → 2。\n- data[1] 与 old[1] 对应 c-1004：状态从“异常（未确定）”变为“成功”，版本 4 → 5。\n- data[2] 与 old[2] 对应 c-1005：状态从“异常（未确定）”变为“成功”，版本 7 → 8。\n\n消费端遍历 data 中的三行即可处理三个更新；当前完整行覆盖方案不需要用 old 合并字段。old 的配对说明用于理解消息结构，多行、空值和变化列集合仍须实测。分区后如何保留对应关系见 ${link(15)}。\n\n### 3.4 UPDATE 完整行与业务字段契约\n\n${part(s[18],'**项目字段契约：**','### 3.4 ')}\n### 3.5 二进制格式的识别边界\n\n${part(s[18],'**二进制消息：**','**项目字段契约：**')}\n${compare}${pending}${history}\n\n${review}\n\n## 7 参考来源\n\n- [FlatMessage](https://github.com/alibaba/canal/blob/canal-1.1.7/protocol/src/main/java/com/alibaba/otter/canal/protocol/FlatMessage.java)：字段定义及 data、old 列值类型。\n- [MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java)：列值、old 与类型映射的填充。\n- [CanalRocketMQProducer](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/rocketmq-connector/src/main/java/com/alibaba/otter/canal/connector/rocketmq/producer/CanalRocketMQProducer.java)：逐个 FlatMessage 序列化到 Message body。\n`;
// 012 owns the end-to-end walkthrough, with examples explicitly conditional.
let flow=part(s[18],'#### 步骤 1','其中第 5 步');
flow=flow.replace(/#### 步骤 (\d)：/g,'### 3.$1 ')
.replace('UPDATE Entry 携带 `c-1002`、`c-1004`。','本例假设同一 UPDATE Entry 含两条 RowData，分别对应 `c-1002` 和 `c-1004`；实际 Entry 数由源事件决定。')
.replace('`messagePartition(...)` 按行计算队列。','配置按键分区时，`messagePartition(...)` 按行计算分区，并保持行与旧值配对。')
.replace('`sendMessage(...)` 构造并发送 RocketMQ `Message`。','生产端先构造 RocketMQ `Message`，再调用 `sendMessage(...)` 发送。')
.replace('具体是否删除 ES 文档按项目消费逻辑执行。','项目不将其转换为 ES 逐条 Delete；保留删除前定位信息。');
out[12]=`# ${names[12].slice(0,-3)}\n\n${version}\n\n## 1 已确认规则与阅读范围\n\n- 本组文档按“部署配置→源库读取→事件解析→消息转换→分区发送→批次确认→位点恢复→验收”组织。每个技术细节只有一个主要讲解位置，其他文档用链接交接。\n- 面向 Canal 初学者，步骤与数据逐项换行，采用纯 Markdown；保留多条记录、不同操作类型的示例。\n- 单机 Server 承载 db0、db1 两个 destination，固定采用 FlatMessage JSON；配置、源码静态判断和运行验证分开记录。\n- 拆分可调整篇数、名称和编号，冲突的后续编号顺延，标题及链接同步更新；保留参考对比、待验证事项、来源和复核记录。\n\n## 2 阅读顺序与职责导航\n\nNAVIGATION\n\n## 3 用四条记录理解整条链路\n\n本例仅示意同一批次中有 INSERT、UPDATE、DELETE 三类行事件，假设两条 UPDATE 在同一事件中；事务标记暂不展开。Entry 是事件容器，RowChange 是行变更描述，RowData 对应一行前后列值。批次大小和事件数不是固定对应关系。\n\n${flow}\n完整 JSON 及字段解释见 ${link(18)}。发送返回后还要进入 Canal 提交或回滚，见 ${link(21)}；本地位点落盘及重启恢复见 ${link(22)}。这些状态都不等于 ES 已写入。\n\n## 4 待确认或待验证事项\n\n各专题记录自身缺口；完整联调清单见 [监控与验收](<./023_ES改造_Canal Server_监控与验收.md>)。本文只给阅读入口，不把设计链路表述为已部署结果。\n\n## 5 复核记录\n\n${review}\n`;
// Config ownership: public keys in 014, destination routing in 015 (renamed later).
const publicConfig=`### 3.2 RocketMQ 连接与生产任务参数\n\n以下均写在 conf/canal.properties。尖括号是待填写参数，不是可直接运行的配置；线程数、批次大小和重试次数需按部署确认。Java properties 的说明单独写在注释行，不能把行尾注释混入属性值。\n\n\`\`\`properties\n# 项目选择 JSON；运行加载结果待验证\ncanal.mq.flatMessage=true\n# RocketMQ 连接参数\nrocketmq.producer.group=<producer-group>\nrocketmq.namesrv.addr=<namesrv-address>\n# 以下容量与重试值未确认\ncanal.mq.canalBatchSize=<batch-size>\ncanal.mq.canalGetTimeout=<timeout-ms>\ncanal.mq.send.thread.size=<send-threads>\ncanal.mq.build.thread.size=<build-threads>\nrocketmq.retry.times.when.send.failed=<retry-count>\nrocketmq.vip.channel.enabled=false\n\`\`\`\n\n批次大小不等于最终 RocketMQ 消息数。内部发送线程参数也不是下游消费者线程数。路由键和固定 topic 见 ${link(15)}；消息字段见 ${link(18)}；提交回滚见 ${link(21)}。\n\n`;
out[14]=out[14].replace('## 4 ',publicConfig+'## 4 ').replace('RocketMQ 专属参数和消息发送配置集中见','RocketMQ 连接参数见本页 3.2，生产端实现见');
out[14]=out[14].replace('消息格式和 RocketMQ 发送参数不在本页展开','消息字段不在本页展开，RocketMQ 参数见 3.2');
out[13]=out[13].replace('当前项目的 RocketMQ Broker 服务端版本尚未确认，不能用已确认的 `rocketmq-client` 客户端版本 5.2.0 代替。','当前项目规划 RocketMQ Broker 5.2.0；这是设计选择，实际部署与 Canal 内置客户端兼容性仍待验证。');
out[13]=out[13].replace('配置加载、本地位点模板、文件权限和重启恢复仍待实际发行包验证。','单机承载两个 destination 的容量、实例隔离和实际 RocketMQ 组件数量仍待部署核对；位点恢复见 '+link(22)+'。');
// route document: remove source configuration comparisons, retain partition ownership.
out[15]=out[15].replace(/### 2\. MySQL[\s\S]*?(?=### 3\.)/,'').replace('### 3. 过滤、分区与消息路由不同','### 4.1 分区与消息路由');
out[15]=out[15].replace('首次 GTID 基线、过滤范围、复制身份、分区数及队列映射仍需在两个源库分别验证；','分区数、队列列表、三类事件的分区源列取值及队列映射仍需验证；');
out[15]=out[15].replace('## 4 ',`### 3.3 多行 FlatMessage 的拆分结果\n\n分区对每个 FlatMessage 的 data 逐行计算，不把不同 type 的对象合并。以同一 UPDATE 对象中的 c-1002、c-1004 为例，队列结果仅作假设，不代表实际 hash 值：\n\n\`\`\`text\n输入：UPDATE data=[c-1002 新值, c-1004 新值]\n             old=[c-1002 旧值, c-1004 旧值]\n\n若两行落在同一分区：\n消息 A：data=[c-1002 新值, c-1004 新值]\n        old=[c-1002 旧值, c-1004 旧值]\n\n若两行落在不同分区：\n消息 A：data=[c-1002 新值]，old=[c-1002 旧值]\n消息 B：data=[c-1004 新值]，old=[c-1004 旧值]\n\`\`\`\n\n每条消息体仍序列化一个 FlatMessage。拆分由 Canal 生产端完成；RocketMQ 不解析 data 后替业务拆行。多行配对、空值与实际映射需抓取真实消息核验。字段含义只在 ${link(18)} 展开。\n\n## 4 `);
// Entry definition correction and remove duplicate field contract explanation.
out[17]=out[17].replace('RowData：一行或多行的列数据，包含变更前列和变更后列；','RowData：一行的变更前列和变更后列；一个 RowChange 可包含多个 RowData；');
out[17]=out[17].replace(/多行事件可能在后续[\s\S]*?(?=### 3.3)/,'多行事件的源坐标与行身份边界见 '+link(18)+'；事务与特殊事件的处理归 '+link(19)+'。\n\n');
out[17]=out[17].replace('- 多行 INSERT/UPDATE 的 `data`、`old`、`version` 与源行是否正确对应？\n','').replace('- DDL、TRUNCATE、主键变更和未知事件进入 Entry 后由哪个阶段处置？','- 解析结果能否区分 ROWDATA、TRANSACTIONBEGIN、TRANSACTIONEND 和 DDL？处置策略见事务专题。');
// 020: remove duplicate configuration, schema and callbacks; retain actual orchestration.
const source20=part(s[20],'## 7 ');
out[20]=part(s[20],'# ','## 3 ')+`## 2 面试口述精炼版本\n\n**内置生产任务取批次并发送**\n\nCanalMQStarter 为每个 destination 启动后台 worker，取出未确认批次交给 CanalRocketMQProducer。生产端准备 EntryRowData，转换 FlatMessage，按配置分区，再逐个序列化成 RocketMQ 消息并等待发送任务结束。发送结果交给批次回调，可靠性边界见 ${link(21)}；实际发行包和联调仍待验证。\n\n## 3 详细方案\n\n### 3.1 批次获取与内部身份\n\n${part(s[20],'第一步是取数据。','```mermaid')}\nCanal 批次由 batchId 标识，可以包含多个 Entry 和多条 RowData。它不是 MySQL 事务编号，也不是 RocketMQ 消息 ID。worker 是常驻工作线程，不是固定周期定时任务。\n\n### 3.2 方法调用与消息构造\n\n1. CanalRocketMQProducer.send(...) 接收批次，调用 MQMessageUtils.buildMessageData(...) 准备 EntryRowData[]。\n2. JSON 路径调用 messageConverter(...)，按有效事件生成 FlatMessage；不会将整个批次直接拼成一个 JSON 数组。\n3. 配置分区哈希时调用 messagePartition(...)，具体键值和拆行示例见 ${link(15)}。\n4. 对待发送 FlatMessage 调用 JSON.toJSONBytes(flatMessage, JSONWriter.Feature.WriteNulls)，构造 RocketMQ Message。\n5. sendMessage(...) 调用 Producer 发送到目标队列，等待本轮发送任务结束，再交给提交或回滚回调。\n\n字段与行值的定义见 ${link(18)}；完整配置见 ${link(14)}。源码中的批量发送是发送多个 Message，不代表一个 body 中包含多个 FlatMessage。\n\n### 3.3 核心调用示意\n\n以下是方法关系伪代码，用于阅读源码，不是可直接编译的应用代码；实际参数以 Canal 1.1.7 为准。\n\n\`\`\`java\n// worker 持续取尚未确认的批次；callback 的 ack/rollback 见批次确认专题\nMessage batch = canalServer.getWithoutAck(\n    clientIdentity, batchSize, timeout, TimeUnit.MILLISECONDS);\nif (batch.getId() != -1 && hasEntries(batch)) {\n    canalMQProducer.send(destination, batch, callback);\n}\n\n// 下列是生产端内部的转换关系示意，辅助函数省略真实参数\nEntryRowData[] rows = buildMessageData(batch);\nfor (FlatMessage flat : messageConverter(rows, batch.getId())) {\n    // 按配置分区；未配置分区哈希时走默认队列路径\n    for (FlatMessage routed : routeByConfiguration(flat)) {\n        // 一个 FlatMessage 对象对应一个 Message body\n        byte[] body = JSON.toJSONBytes(routed, JSONWriter.Feature.WriteNulls);\n        Message mqMessage = new Message(topic, body);\n        submitSendTask(mqMessage, targetQueue);\n    }\n}\nwaitForPartitionTasks();\n// 回调条件和 SendResult 风险不在此伪代码中简化为可靠落盘\n\`\`\`\n\nCanal Message 与 RocketMQ Message 是不同包中的类型，伪代码为便于阅读省略全限定名。队列选择按分区和实际队列列表映射；批量发送读取队列列表，列表不可用时的退化行为需按实际发行包验证。\n\n## 4 参考文章与项目设计的区别\n\n**投递组件**\n\n参考文章和项目都使用 Canal 内置 RocketMQ 生产端。\n\n**参考文章：** 展示实验环境的发送效果。\n\n**当前项目：** 采用 Canal 1.1.7；官方构建记录的内置 RocketMQ 客户端为 4.8.0，规划 Broker 为 5.2.0，实际加载依赖和兼容性待验证。字段对比见消息格式专题，提交回滚对比见批次确认专题。\n\n## 5 待确认或待验证事项\n\n- 实际发行包是否按上述方法调用链运行，加载的客户端 JAR 是否符合构建记录？\n- 内置客户端 4.8.0 与 Broker 5.2.0 的单条/批量发送、认证及异常路径是否兼容？\n- 发送线程、批次大小、超时和任务等待是否符合部署要求？\n\n## 6 复核记录\n\n${review}\n\n${source20}`;
// 021 owns callbacks only; navigation replaces duplicated routing and transaction mechanics.
out[21]=out[21].replace(/同一个 `consume_id` 是否始终[\s\S]*?(?=### 3.4)/,'队列稳定性与并发消费顺序的区别见 '+link(15)+'。\n\n');
out[21]=out[21].replace(/Canal `batchId` 是内部读取批次标识[\s\S]*?(?=下游暂停)/,'MySQL 事务与消息边界见 '+link(19)+'。这里仅区分发送回调和后续状态：提交 Canal 批次不会等待下游 ES 写入；本地元数据可靠落盘是恢复专题需要验证的另一阶段。\n\n');
out[21]=out[21].replace('- 事务开始/提交标记是否在最终消息中保留？\n','');
// Split recovery from monitoring/acceptance, retain validation detail in new 023.
let monitoring=part(s[22],'### 3.2 监控','### 3.4 ');
monitoring=monitoring.replace('### 3.2','### 3.1').replace('### 3.3','### 3.2')
.replace('分别核对 FlatMessage JSON 和二进制 Entry 的实际封装','按项目采用的 FlatMessage JSON 核对实际封装')
.replace('交给 009 的 ES 写入服务','交给自定义 ES 写入服务');
out[23.5]=`# 023_ES改造_Canal Server_监控与验收\n\n${version}\n\n## 1 已确认规则与适用边界\n\n本文集中记录原恢复专题中的监控和验收要求。全部为待执行检查，不代表已经通过测试；正文设计详见各专题。\n\n## 2 面试口述精炼版本\n\n验收需要同时覆盖两个 destination 的读取、消息转换、发送确认和恢复，不能只看 Dashboard 有消息。还要注入部分发送失败、超时和进程重启，并检查日志及消息留存窗口、重复投递和下游旧版本保护。实际结果仍待演练。\n\n## 3 详细方案\n\n${monitoring}\n## 4 参考文章与项目设计的区别\n\n**参考文章：** 展示实验消息发送和 ES 写入结果。\n\n**当前项目：** 还需要验证两个 destination、版本兼容、故障恢复和留存窗口；演示结果不能代替这些验收项。\n\n## 5 待确认或待验证事项\n\n- 监控指标的采集、告警负责人和阈值是否已经落实？\n- 各验收场景是否有真实配置、输入事件、输出消息、位点和 ES 结果证据？\n\n## 6 复核记录\n\n从原 022 的监控与验收小节拆出。${review}\n\n## 7 参考来源\n\n各项方法和配置来源见 ${link(20)}、${link(21)} 与 ${link(22)}；本页组织验证步骤，不追加运行事实。\n`;
out[22]=out[22].replace('本文是 Canal Server 写入链路的拆分总览；三个独立文档分别说明日志读取与解析、RocketMQ 发布与事务边界、进度确认与单机恢复。设计、实现和验证状态继续按原文边界说明。','本文只说明本地位点、人工重启和主机文件故障恢复。')
.replace(part(s[22],'### 3.2 监控','### 3.4 '),'监控与完整验收清单见 [监控与验收](<./023_ES改造_Canal Server_监控与验收.md>)。\n\n')
.replace('### 3.4 版本核对依据与边界','### 3.2 版本核对依据与边界')
.replace('发送状态风险见 4.3。','发送状态风险见 '+link(21)+'。')
.replace('### 进度与恢复','**进度与恢复**');
out[22]=out[22].replace(/增量校验应保存实际接收的消息和事件[\s\S]*?(?=## 5)/,'消费位点和 ES 处理结果不能代替源日志恢复起点；增量校验具体采用哪些业务进度和事件信息见下方关联文档。\n\n');
out[22]+=`\n## 6 待确认或待验证事项\n\n- 各 destination 的文件路径、刷新时机、权限及 clientId 映射是否与实际发行包一致？\n- 位点损坏或日志过期时能否恢复可靠起点？未确认前应停止恢复。\n\n## 7 参考文章与项目设计的区别\n\n**参考文章：** 演示发送和写入效果。\n\n**当前项目：** 单机本地位点依赖文件和存储恢复，需额外演练，不能据文章承诺自动接管或零丢失。\n\n## 8 复核记录\n\n${review}\n`;
// Preserve reference sections and review records, add navigation and normalize numbered headings.
const mapping={};
const rename={15:'019_ES改造_Canal Server_Topic路由与多行分区',16:'015_ES改造_Canal Server_源库连接与Binlog过滤',17:'016_ES改造_Canal Server_Entry结构与行事件解析',19:'017_ES改造_Canal Server_事务边界与特殊事件',20:'020_ES改造_Canal Server_RocketMQ生产端实现',22:'022_ES改造_Canal Server_本地位点与单机恢复'};
for(const [i,n] of Object.entries(names)){let nn=rename[i]?rename[i]+'.md':n;if(+i>=23)nn=String(+i+1).padStart(3,'0')+n.slice(3);mapping[n]=nn;}
const newName='023_ES改造_Canal Server_监控与验收.md';
const nav=[12,13,14,16,17,19,18,15,20,21,22,23.5];
out[12]=out[12].replace('NAVIGATION',nav.map(i=>'- '+(i===23.5?'[监控与验收](<./'+newName+'>)':link(i))).join('\n'));
function remap(t){for(const [a,b] of Object.entries(mapping))t=t.split(a).join('@@'+a+'@@');for(const[a,b]of Object.entries(mapping))t=t.split('@@'+a+'@@').join(b);return t}
function normalize(t){let counts=[0,0,0],fence=false;return t.split('\n').map(l=>{if(/^\s*(```|~~~)/.test(l)){fence=!fence;return l}if(fence)return l;let m=l.match(/^(#{2,4}) (.*)$/);if(!m)return l;let depth=m[1].length-2;counts[depth]++;for(let j=depth+1;j<3;j++)counts[j]=0;return m[1]+' '+counts.slice(0,depth+1).join('.')+' '+m[2].replace(/^\d+(?:\.\d+)*[. ]*\s*/, '')}).join('\n').replace(/\n{4,}/g,'\n\n\n').trim()+'\n'}
const outputs={};
for(const [key,t] of Object.entries(out)){const i=+key, n=i===23.5?newName:mapping[names[i]];let text=t;if(i<=22){if(!text.includes(review))text=text.replace(/(## \d+ 复核记录\n)/,'$1\n'+review+'\n');if(!text.includes('复核记录'))text+='\n## 6 复核记录\n\n'+review+'\n';text=normalize(text)}text=remap(text);text=text.replace(/^# .*/, '# '+n.slice(0,-3));outputs[path.join(dir,n)]=text;}
// Move only named documents; collision checks happen before any deletion.
for(const [old,n]of Object.entries(mapping)){if(old!==n&&fs.existsSync(path.join(dir,n))&&!Object.keys(mapping).includes(n))throw Error('collision '+n)}
for(const[old,n]of Object.entries(mapping))if(old!==n)fs.unlinkSync(path.join(dir,old));
for(const[p,t]of Object.entries(outputs))fs.writeFileSync(p,t);
// Update incoming file links across current docs, excluding historical tmp snapshots.
for(const[p,t]of Object.entries(before)){if(Object.keys(names).some(i=>path.join(dir,names[i])===p))continue;const changed=remap(t);if(changed!==t)fs.writeFileSync(p,changed)}
fs.writeFileSync(path.join(backup,'mapping.json'),JSON.stringify(mapping,null,2));
fs.writeFileSync(path.join(root,'tmp/canal-restructure-state.json'),JSON.stringify({backup,dir,mapping,names,newName},null,2));
console.log(JSON.stringify({backup,mapping,newName},null,2));
