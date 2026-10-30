const fs=require('fs'),path=require('path');const state=JSON.parse(fs.readFileSync('tmp/canal-restructure-state.json'));const d=state.dir;
const find=n=>path.join(d,fs.readdirSync(d).find(x=>x.startsWith(n+'_')));
const edit=(n,fn)=>{let p=find(n);fs.writeFileSync(p,fn(fs.readFileSync(p,'utf8')))};
// Update labels as well as targets in the rewritten scope.
for(let i=12;i<=23;i++)edit(String(i).padStart(3,'0'),t=>t.replace(/\[([^\]]+)\]\(<\.\/((?:0\d\d)_([^>]+)\.md)>\)/g,(m,label,file,title)=>'['+title.replace(/^ES改造_Canal Server_/,'')+'](<./'+file+'>)').replace('当前项目 AGENTS.md','当前项目 `AGENTS.md`'));
edit('012',t=>t.replace(/^- \[处理链路总览\].*\n/m,'').replace('MySQL 提交一批业务操作。','以下仅列业务变更，不表示执行顺序；本例假设两条 UPDATE 来自同一行事件。')
.replace('  - UPDATE 拆分时保持 `data[i]` 与 `old[i]` 对应。\n','')
.replace('## 5 复核记录','## 5 复核记录')
.replace('已执行 [java-interview','本轮将原 018 的完整数据链路归入本页，原 012 的总览和导航职责保留。\n\n已执行 [java-interview'));
edit('018',t=>t.replace('源码中的 data、old 是 List<Map<String,String>>','源码中的 `data`、`old` 是 `List<Map<String,String>>`').replace('独立事件 ID是否','独立事件 ID 是否')
.replace('## 6 复核记录','## 6 复核记录\n\n以下保留历史整理记录；其中完整链路现归 012，多行分区现归 019。本页只保留消息格式和字段契约。')
.replace('## 7 参考来源','## 7 参考来源')+
'\n## 8 异议与分析\n\n旧示例把行内 version 写成 JSON 数字，但工作区源码 FlatMessage 的行值类型为字符串，MQMessageUtils 使用 Column.getValue() 填值。本轮改为字符串，并补充消费端类型转换说明。旧示例的月份表名、无效 GTID 占位值和事件时间也已调整为明确的示意值；这些修正不是新增部署事实。\n');
edit('019',t=>t.replace('documented in 016','documented in 015').replace('由 016 统一说明','由 015 统一说明')
.replace('两个 destination 分别填写自己的固定 topic；topic、字段值、分区数和队列列表稳定，才可核对同键是否始终到同一队列。','两个 destination 分别填写自己的固定 topic；分区稳定条件统一见 3.2。')
.replace(/消费端候选 `orderKey`[\s\S]*?(?=当前设计已确定)/,'1.1.7 的 `partitionHash` 按库表规则匹配源列；开启 `canal.mq.database.hash` 时还混入库名。同键稳定映射要求 topic、源列值、分区数、队列列表和映射算法稳定。Tag 只做过滤，不是业务顺序保障。\n\n消费端候选 orderKey 为 destination + database + table + consumeId，用于内部顺序状态，不是原生生产端的 hash 字符串。并发消费可能乱序完成；消费线程和 ES 旧版本保护详见 [RocketMQ 消费模型](<./025_ES改造_RocketMQ_RocketMQ 消费模型.md>)。\n\n')
.replace(/同一 destination 同时只启动[\s\S]*?(?=### 3.3)/,'同一 destination 的唯一读取进程与重放约束见 [本地位点与单机恢复](<./022_ES改造_Canal Server_本地位点与单机恢复.md>)。分区数尚未确定，不能把文章的 8 当作项目参数。\n\n')
.replace(/### 4.1 分区与消息路由[\s\S]*?(?=## 5)/,'### 4.1 分区与消息路由\n\n双方都按业务键选择队列，实际配置各不相同。\n\n**参考文章：** 监听 order_db.orders，以 order_no 为分区键，示例分区数为 8。\n\n**当前项目：** 两个 destination 分别用固定 topic，以 consume_id 为源列；分区数与队列映射待验证，不照搬文章参数。读取范围见 015，消息契约见 018。\n\n')+
'\n## 7 参考来源\n\n- [MQMessageUtils](https://github.com/alibaba/canal/blob/canal-1.1.7/connector/core/src/main/java/com/alibaba/otter/canal/connector/core/producer/MQMessageUtils.java)：按行分区及 data/old 同下标复制。\n- [instance.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/example/instance.properties)：topic、partitionHash 和 partitionsNum 配置入口。\n');
edit('014',t=>t.replace('以下地址、账号、密码和端口都是占位值，不能直接用于生产：','以下为项目启动模式与通道配置示意，实际加载结果仍待核对：')
.replace('配置加载、本地位点模板、文件权限和重启恢复仍待实际发行包验证。','全局配置是否实际加载、参数类型是否正确、线程及超时容量是否合适，仍待验证。本地文件权限和恢复效果见恢复专题。')+
'\n## 7 参考来源\n\n- [canal.properties](https://github.com/alibaba/canal/blob/canal-1.1.7/deployer/src/main/resources/canal.properties)：运行模式、MQ 连接和生产任务参数。\n\n## 8 异议与分析\n\n旧 020 示例的 canal.serverMode=tcp 与本页及项目内置 RocketMQ 模式不一致。本轮统一由本页定义 rocketMQ 模式，移除发送实现文档中的重复配置；实际实例仍需核验。旧 properties 示例的行尾注释可能成为属性值的一部分，已改成独立注释行。\n');
edit('020',t=>t.replace('先解释组件和术语，再说明读取、转换、分区发送及批次确认的处理步骤','按批次获取、对象准备、转换和发送顺序展开，批次确认由 021 负责')
.replace('3.2 方法调用与消息构造\n','3.2 方法调用与消息构造\n')
.replace('源码中的批量发送是','当前项目已确认的格式开关为 canal.mq.flatMessage=true。源码中的批量发送是'));
// New format does not prove row images; keep simulation and runtime evidence distinct.
edit('016',t=>t.replace('### 3.1 Entry 结构','### 3.1 Entry 结构').replace('Entry 是 Canal 在解析阶段使用的事件容器：','Entry 是 Canal 在解析阶段使用的事件容器。对于 ROWDATA 类型，storeValue 可解析为 RowChange；事务开始和结束使用对应的事务结构，不应按 RowChange 解码：'));
// Source references retain provenance without claiming this turn fetched official sources.
for(let i=12;i<=23;i++)edit(String(i).padStart(3,'0'),t=>t.replace(/\n{3,}/g,'\n\n'));
console.log('refinement complete');
