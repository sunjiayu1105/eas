const fs=require('fs');
const file='D:/000/sources/eas/002_消费记录分库分表、双写与ES 改造（15）/018_ES改造_Canal Server_消息格式与字段边界.md';
let t=fs.readFileSync(file,'utf8').replace(/\r\n/g,'\n');
fs.writeFileSync('D:/000/sources/eas/tmp/beginner-018-before.txt',t);
const a=t.indexOf('### 3.2 '),b=t.indexOf('### 3.3 '),c=t.indexOf('### 3.4 ');
const fields=t.slice(a,b).replace('### 3.2 字段含义','### 3.3 对照示例理解字段');
const example=t.slice(b,c).replace('### 3.3 一条消息包含三行 UPDATE 的示例','### 3.2 先看一条包含三行 UPDATE 的消息');
t=t.slice(0,a)+example+fields+t.slice(c);
t=t.replace('先说明消息在 Canal 链路中的位置，再说明格式开关、字段含义、拆分对应关系和项目字段契约。','先建立消息与行数据的关系，再看多行示例、解释字段和操作类型，最后说明完整行与重复处理的前提；首次出现的术语就地解释。');
t=t.replace('### 3.1 消息体、FlatMessage 和行数据','### 3.1 先分清消息、JSON 对象和行数据');
t=t.replace('FlatMessage 是 Canal 的 Java 消息对象；FlatMessage JSON 是它序列化后的形式。当前内置生产端在 flatMessage 路径中逐个对象序列化，将 JSON 字节作为 RocketMQ Message 的 body。一次批量发送可以发送多个 Message，但每个 body 仍是一个 FlatMessage 对象，不是多个 FlatMessage 组成的外层数组。',
'Canal 将 MySQL 的行变化整理成 Java 对象 `FlatMessage`，再把对象转成 JSON 字节，这个过程叫序列化。JSON 字节放进 RocketMQ 消息的 `body`（消息体，即承载业务内容的部分），消费端收到后再解析为对象。\n\n先记住这三个层次：\n\n1. 一条 RocketMQ 消息的 `body` 对应一个 FlatMessage JSON 对象。\n2. 这个对象中的 `data` 是数组，也就是一组行对象。\n3. `data` 的每个元素代表一条记录在本次事件中的行数据；所以一条消息可以包含多条行变更。\n\n例如，一个 UPDATE 消息的 `data` 有三行，消费端就要处理三条记录的更新。一次批量发送可以发送多条 RocketMQ 消息，但不会把它们的 FlatMessage 合成一个消息体中的外层数组。');
t=t.replace('以下是合法 JSON 的精简示例。','假设三条消费记录的状态都由“异常（未确定）”改为“成功”，转换后进入同一条 UPDATE 消息。`type` 表示操作类型，`data` 保存更新后的行，`old` 保存变化字段的旧值；先用这三个字段读下面的例子。\n\n以下是合法 JSON 的精简示例。');
t=t.replace('- data[0] 与 old[0]', '数组下标从 0 开始，`data[0]` 就是第一条行数据。按示例逐条对照：\n\n- data[0] 与 old[0]');
t=t.replace('消费端遍历 data 中的三行即可处理三个更新；','消费端遍历（依次读取）`data` 中的三行，即可处理三个更新；');
t=t.replace('| id | Canal 批次标识；','| id | Canal 一次读取的一组事件的批次标识；');
t=t.replace('| isDdl | 是否为结构变更事件；','| isDdl | 是否为 DDL（建表、改表等结构变更）事件；');
t=t.replace('| gtid | 来源 Header 中的 GTID；可能为空，不保证每条可用 |','| gtid | 来源事件头中的 GTID（MySQL 全局事务标识）；可能为空，不能当作每条行变更的唯一 ID |');
t=t.replace('### 3.4 UPDATE 完整行与业务字段契约',`### 3.4 三种操作分别提供什么行数据

“行前值”是操作执行前的列值，“行后值”是操作执行后的列值。一个 FlatMessage 只有一个操作类型；多条同类行变化可以放在同一个对象中，不同操作类型分别形成对象。

| 操作类型 | data 中的每一行 | old 的用途 | 当前项目如何使用 |
| --- | --- | --- | --- |
| INSERT（新增） | 新增后的行数据 | 不用于提供新增前数据 | 读取新增记录及业务版本，进入 ES 写入流程 |
| UPDATE（更新） | 更新后的行数据 | 保存变化字段的旧值，不是完整旧行 | 完整性校验通过后，使用更新后的行覆盖写入 ES |
| DELETE（删除） | 删除前的行数据 | 不用于提供删除后的数据 | 保留删除前定位信息，不转换为 ES 逐条 Delete |

这里说明的是字段取值方向，是否包含全部必要列还要检查源库配置和实际消息，不能看到 UPDATE 就默认 data 一定完整。

### 3.5 为什么 UPDATE 要提供完整行`);
t=t.replace('**项目字段契约：**','字段契约就是生产端与消费端约定“消息必须携带哪些字段，以及这些字段如何解释”。\n\n**项目字段契约：**');
t=t.replace('**UPDATE 完整行约束：**','**完整行的含义：** 即使只更新 `status`，用于完整文档覆盖写入的 `data` 也应保留 `consume_id`、原始 `create_time` 及其他必要业务列，而不能只剩变化字段。上面的四列示例只是展示结构，不是完整生产字段清单。\n\n**UPDATE 完整行约束：**');
t=t.replace('**重复消息与版本控制：**','### 3.6 重复收到消息时，为什么仍要携带版本\n\n**重复消息与版本控制：**');
fs.writeFileSync(file,t);
const oral=t.split('## 2 面试口述精炼版本')[1].split('## 3 详细方案')[0];
const json=t.match(/```json\n([\s\S]*?)\n```/)[1];JSON.parse(json);
console.log('oral raw non-whitespace chars:',oral.replace(/\s/g,'').length,'JSON valid');
