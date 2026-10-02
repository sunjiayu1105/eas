from pathlib import Path
import re, json, hashlib

root = Path(r'D:\000_ 找工作_2026\sources\E账通')
doc = root / '003/001/002_消费记录分库分表、双写与ES改造统一方案.md'
readme = doc.with_name('README.md')
original = doc.read_text(encoding='utf-8-sig')
backup = root / 'tmp/consume-doc-before-structure-20260920.md'
assert not backup.exists(), 'Backup already exists; do not overwrite'
backup.write_text(original, encoding='utf-8')
(root / 'tmp/consume-readme-before-structure-20260920.md').write_bytes(readme.read_bytes())

def span(a, b=None):
    start = original.index(f'<a id="{a}"></a>')
    end = original.index(f'<a id="{b}"></a>', start) if b else len(original)
    return original[start:end].strip()

mapping = {
    '1':'1', '1.1':'1', '1.1.1':'1.1', '1.1.2':'1.2',
    '1.1.3':'1.3', '1.1.4':'1.4', '1.1.5':'2.8', '1.1.6':'2.11', '1.1.7':'1.5',
    '1.2':'2', '1.2.1':'2.5', '1.2.2':'2.10', '1.2.3':'2.9',
    '1.2.4':'2.12', '1.2.5':'2.13', '1.2.6':'3', '1.2.7':'4.1',
    '1.2.8':'4.2', '1.2.9':'3.1.5', '1.2.10':'3.1.4', '1.2.11':'2.4',
    '1.3':'4.4', '1.4':'2.8', '1.4.1':'2.8', '1.4.2':'2.9',
    '1.4.3':'2.10', '1.4.4':'2.12',
    '2':'1', '2.1':'1.2', '2.2':'1.7', '2.3':'4.6', '2.4':'4.5',
    **{str(i): f'3.{i-2}' for i in range(3,11)}, '11':'4.3', '12':'3.9'
}
def number(n):
    if n in mapping:
        return mapping[n]
    for key in sorted(mapping, key=len, reverse=True):
        if n.startswith(key+'.'):
            return mapping[key]+n[len(key):]
    return n

def refs(s):
    # Only transform existing text, never apply this to newly written section numbers.
    saved=[]
    def keep(value):
        saved.append(value)
        return '\u0001'+chr(65+len(saved))+'\u0002'
    s = s.replace('第 7、8 章',keep('第 3.5、3.6 节'))
    s = re.sub(r'\[([^\]\n]+)\]\(#(section-[\d-]+)\)',
        lambda m: keep('['+re.sub(r'第 (\d+) 章|(?<![\d.])(\d+(?:\.\d+)+)(?![\d.])',lambda n:('第 '+number(n[1])+(' 章' if '.' not in number(n[1]) else ' 节')) if n[1] else number(n[2]),m[1])+'](#'+m[2]+')'), s)
    s = re.sub(r'第 (\d+) 章', lambda m: keep('第 '+number(m[1])+(' 章' if '.' not in number(m[1]) else ' 节')), s)
    s = re.sub(r'(?<![\d.])(\d+(?:\.\d+)+)(?![\d.])(?= 节)', lambda m:keep(number(m[1])), s)
    for i,value in enumerate(saved,1):
        s=s.replace('\u0001'+chr(65+i)+'\u0002',value)
    return s

def move(a,b=None):
    s=span(a,b)
    # Change numbered headings and their depth in a single pass.
    s=re.sub(r'^(#{2,6}) (\d+(?:\.\d+)*)(?:\.)? (.+)$',
        lambda m:'#'*(number(m[2]).count('.')+2)+' '+number(m[2])+' '+m[3],s,flags=re.M)
    # Headings were already translated; apply references only to non-heading lines.
    s='\n'.join(line if re.match(r'^#{2,6} ',line) else refs(line) for line in s.splitlines())
    return s

def head(level, ident, title, aliases=()):
    return '\n\n'+'\n'.join(f'<a id="{i}"></a>' for i in [ident,*aliases])+'\n\n'+'#'*level+' '+title+'\n\n'

title = original.splitlines()[0]
plan = head(2,'plan','1. 改造计划',['section-1','section-1-1','section-2'])
plan += '以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。\n\n'
plan += '**计划范围与验证边界**\n\n消费记录持续增长，既带来单表存储和维护压力，也让后台列表检索占用数据库资源。本次改造覆盖新增、修改、撤销、退货和物理删除：先迁移到分片表，再通过双写和自动校验过渡，最后切换查询与写入。\n\n容量选型按已确认的“经过压测和演练验证”场景前提展开，具体指标和记录尚未提供；这不代表本文代码或生产链路已验证。SQL、依赖和代码片段未在项目环境编译或运行。实施前仍须核实[待补充事项](#section-1-3)。\n'
plan += head(3,'plan-1','1.1 明确目标与上线节奏',['section-1-1-1'])
plan += '存储采用 2 个 MySQL 库、每库 16 张表，共 32 个物理分片；后台列表逐步改查 Elasticsearch（ES）。分 2 轮上线：第 1 轮停写迁移，验收后统一启用双写、同步和校验，列表仍查旧表；第 2 轮满足校验条件后灰度切换查询。全部旧表依赖切完并继续观察通过，才关闭旧表写入。\n'
plan += head(3,'plan-2','1.2 完成分片与配套功能准备',['section-1-1-2','section-2-1'])
plan += '按“库表布局 → 统一路由 → 主键生成 → 新旧记录关联”准备数据模型：`merchantNo` 负责路由，`consumeId` 是新表主键，`serialNo` 关联同一笔新旧记录。完成双写、Canal 同步、2 项校验、进度保存和查询回退能力的开发配置，准备分片表、ES 目标索引及校验资源，并演练迁移、维护窗口和故障恢复。生产启用按后续停写窗口执行。\n\n**阶段出口**：规则、资源、配置和演练结果已核对，实际执行参数已填写，才进入停写迁移。容量、路由和主键分别见[追问 2.1](#question-1)、[2.2](#question-2)、[2.3](#question-3)，维护演练见[2.18](#question-18)。\n'
plan += head(3,'plan-3','1.3 停写迁移，验收后保存增量起点',['section-1-1-3'])
plan += '由运维关闭业务接口流量、消息消费和相关定时任务，停止相关应用；DBA 确认遗留业务事务结束。数据库及迁移工具保持可用。历史导入前，分别保存 2 个新库的完整 GTID 集合，即已执行事务的标识集合，作为 ES 同步与新表、ES 校验的起点。DBA 按统一路由导入并完成历史验收。\n\n验收通过后继续停写，在旧库和 2 个新库保存旧表、新表增量校验的起点；各链路分别维护后续进度。\n\n**阶段出口**：DBA 提供历史验收通过结果，两组基线保存完整。迁移与基线分别见[追问 2.4](#question-4)、[2.5](#question-5)，具体操作见[停写迁移手册](#section-10)。\n'
plan += head(3,'plan-4','1.4 统一上线，恢复双写并持续同步校验',['section-1-1-4'])
plan += '保持业务入口关闭，部署并验证分片写入、业务双写、Canal 到 ES 的同步，以及 2 项独立校验。确认就绪后再开放业务。双写以旧表事务提交结果决定接口成败；新表写入问题交自动校验发现并进入独立修复流程。Canal 读取新库 binlog（记录数据库变更的日志），异步同步 ES。\n\n旧表与新表校验逐条延迟检查，新表与 ES 校验按固定批次检查；发现差异后复核，持续差异交独立流程修复，异常按已保存进度恢复。各链路分别保存进度，运行配置与持久进度分开管理。\n\n**阶段出口**：业务已恢复，列表仍查旧表；ES 可以继续追赶历史数据。双写与同步见[追问 2.6—2.7](#question-6)，校验、修复、恢复和配置见[2.8—2.14](#question-8)。\n'
plan += head(3,'plan-5','1.5 历史覆盖并观察通过后，灰度切换查询',['section-1-1-7'])
plan += '新表与 ES 增量校验从导入前起点覆盖历史导入及后续变更。历史范围全部处理并通过比较，自动校验达到连续 2 周未发现数据差异后，再灰度切换列表查询到 ES。同步、故障恢复与回退能力仍须就绪，不能把运行异常误认为校验通过。\n\n灰度及切换完成后继续保留双写、2 项校验、同步和监控，同时迁移其他旧表依赖。关闭旧表写入前可按开关退回旧表查询。\n\n**阶段出口**：查询已完成切换，所有旧表业务依赖逐步迁完。历史覆盖与灰度回退见[追问 2.15](#question-15)、[2.16](#question-16)。\n'
plan += head(3,'plan-6','1.6 完成最终检查，关闭旧表写入')
plan += '全部旧表业务切换完成后，继续双写和自动校验，达到连续 2 周未发现数据差异，再安排最终短暂停写。等变更采集追到终点、剩余旧表与新表校验全部通过后，保存进度，停止该校验任务，关闭旧表写入并退出旧表查询回退，再恢复新表单写。\n\n**阶段出口**：请求按新表事务提交结果判定成功；Canal 同步和新表与 ES 校验继续运行。旧表不立即删除，后续归档另行安排；若需回退，须先重新同步并验收。具体见[追问 2.17](#question-17)。\n'
plan += move('section-2-2','section-2-3')
# This section is a compact, already-confirmed set of constraints, retained in the plan.

questions = [
('为什么选择 2 个库、每库 16 张表？','容量选型先看 5 年总量，再看单表维护和实例峰值负载。按每天 20 万条估算，5 年约 3.65 亿条，分成 32 张表后平均每表约 1141 万条。按已确认的演练前提，这档规模满足查询、维护和迁移要求，2 个独立实例满足峰值承载需求。平均条数不是 MySQL 的通用上限，具体压测指标仍待补充。','plan-2',['section-1-2-10'],[]),
('消费记录怎么分片，如何保证路由一致？','用 merchantNo 作为分片键，按统一哈希和取模规则计算 shardIndex，再映射到 2 个库、每库 16 张表。业务读写、历史迁移和校验使用同一规则，并核对数据实际落在哪个分片。同一商户不会被拆散，所以大商户仍可能形成热点，不能只按平均容量评估。','plan-2',['section-3-1'],[]),
('主键怎么生成，新旧记录怎么关联？','新表主键采用雪花算法加基因后缀：先生成雪花 ID，再拼接固定 2 位分片编号，整个 consumeId 按字符串保存。merchantNo 决定路由，consumeId 标识新表记录并作为 ES 的 _id；历史迁移会重建主键，因此新旧表用全局唯一且不可变的 serialNo 关联。新表主键生成后保持稳定。','plan-2',['section-3-2'],[]),
('历史数据怎么迁，何时恢复业务？','先由运维关闭业务写入、停止相关应用，DBA 确认事务结束，再按统一路由导入历史数据并完成全量验收。数据库和迁移工具保持可用。验收通过后保存增量起点，保持业务停写完成双写、同步及校验的上线验证，再开放业务。恢复后列表仍查旧表，ES 历史数据可以继续追赶。','plan-3',['section-4','section-10'],['section-1-2-11']),
('为什么保存 2 次 GTID 基线？','导入前保存 2 个新库的完整 GTID 集合，给 ES 同步和新表与 ES 校验使用，确保历史导入也在范围内。历史验收后、恢复写入前，再保存旧库和 2 个新库的集合，给旧表与新表增量校验使用；这次新库集合也标记历史范围终点。两组起点用途不同，各链路后续进度独立保存，不能拿最新位置覆盖。','plan-3',['section-4-2'],['section-1-2-1']),
('双写怎么执行，新表失败影响接口吗？','先提交旧表，旧表失败就返回失败并结束；旧表成功后尝试通过原有 ShardingSphere-JDBC 链路写新表。接口是否成功只看旧表提交结果，新表失败甚至完全没有记录，都不改变接口成功判定。两次提交不是原子事务，差异由自动校验发现并交独立流程修复；请求重试的幂等实现仍需核实。','plan-4',['section-5-1'],[]),
('新表怎么同步 ES，删除怎么处理？','业务双写负责旧表到新表，Canal 负责新表到 ES。Canal 从新库导入前基线读取行日志，把新增、修改和物理删除异步同步到目标索引，ES 的 _id 使用 consumeId。删除时从删除前行信息取得 ID，再删除对应文档。同步进度单独保存，接口成功不代表 ES 已经同步完成。','plan-4',['section-5-2','section-5-3'],[]),
('旧表与新表增量校验怎么做？','通过独立 Canal 校验通道监听旧库和 2 个新库，任意一端发生变更，就记录 serialNo、merchantNo 和实际来源位置，追加待校验任务。任务提交成功才确认消费，之后延迟查询两端当前数据。字段一致就完成当前任务，有差异则保留并复核，持续差异进入修复流程。它与新表、ES 校验独立运行。','plan-4',['section-7'],['section-1-1-5','section-1-4','section-1-4-1']),
('怎么避免漏任务、覆盖任务或重复执行冲突？','每次变更都追加独立任务，不按 serialNo 覆盖旧任务；任务落库后才确认 Canal 消费，重放最多增加重复检查。执行器拿到固定锁后领取到期任务，在锁内查询并保存结果，一致时只删除当前任务 ID。失锁就停止，提交前检查所有权；但 Redis 检查与 MySQL 提交不原子，仍需验证失锁边界。','plan-4',['section-7-3-3','section-7-3-4','section-7-3-5','section-7-3-8'],['section-1-2-3','section-1-4-2']),
('3 种校验分别比较哪些字段？','历史迁移验收排除新旧不同主键的直接值，其余字段全部比较，包含版本和时间。旧表与新表增量校验只比规定业务字段，不比不同主键值和创建、更新时间，但仍查主键、重复和路由。新表与 ES 比较全部数据字段及文档 ID。金额、类型、空值和需要比较的时间都按统一口径处理。','plan-3',['section-3-3','section-7-3-6','section-7-3-7'],['section-1-2-2','section-1-4-3']),
('新表与 ES 增量校验怎么做？','每次固定一批变更，等待 ES 同步后，比较新表和 ES 中对应的当前数据。只有目标事务读齐、这一批全部一致，才推进进度并检查下一批；有差异就保留原批次，修复后整批重新检查，不能跳过。它与旧表、新表校验以及 Canal 同步分别记录进度，关闭旧表写入后继续运行。','plan-4',['section-8'],['section-1-1-6']),
('发现差异怎么处理，校验通过能说明什么？','查询失败或超时属于执行异常，不能当作记录缺失；查询成功后对不上才记数据差异。先保留差异并复核，持续不一致由独立流程修复，再复核原任务或原批次。校验读的是两端当前状态，查询不是同一个原子快照，暂时差异可能来自并发更新。一次通过不能证明过去每次写入都成功，也不能代替历史数据库验收。','plan-4',['section-6-2','section-6-3','section-7-3-8','section-8-2'],['section-1-2-4','section-1-4-4']),
('故障后怎样恢复，日志需要保留多久？','旧表与新表校验从可靠消费位点继续采集，已落库任务继续复核；新表与 ES 校验恢复原 GTID 批次，读齐事务并整批重查。不能跳到最新位点，也不能把没查完的任务算通过。日志保留要覆盖历史读取、等待、重试和故障恢复，满足所有链路中最长的需要；出现日志缺口就报警并处理遗漏范围。','plan-4',['section-7-4','section-8-2','section-4-2'],['section-1-2-5']),
('同步起点、运行进度和配置分别放在哪里？','GTID 基线和消费位点属于持久运行状态，按链路和来源实例分别保存，方案建议由 MySQL 控制表管理并动态读取。运行地址、开关和批量参数可由已有 Nacos 管理，但不能让 Nacos 成为基线和位点的唯一存储。控制表结构、实际 Canal 位点格式及接入实现仍需确认。','plan-4',['section-12'],[]),
('怎样确认 ES 已覆盖历史数据？','新表与 ES 校验从导入前起点开始，历史导入和后续变更一起检查。以历史数据库验收后的各新库 GTID 集合作为历史终点，只有实际整批通过的校验进度覆盖这些终点，才算覆盖完成。无需另设 ES 全量扫描；但它只能检查源端日志能定位的记录，无法证明任意额外 ES 文档都不存在。','plan-5',['section-9-1'],[]),
('查询何时切到 ES，灰度失败怎么回退？','历史范围检查完成、自动校验达到连续 2 周未发现数据差异，并确认同步、恢复和回退能力就绪后，逐步将列表查询切到 ES。灰度中观察结果和运行情况，有问题用开关退回旧表。灰度及切换后继续保留双写、校验和同步；旧表查询回退只保留到最终关闭旧表写入之前。','plan-5',['section-9-2','section-9-3','section-9-4'],[]),
('什么时候关闭旧表写入，切换后保留什么？','全部旧表业务切完后继续双写和校验，连续 2 周未发现数据差异，再短暂停写。等变更采集追平、剩余新旧表校验全部通过，保存进度后停止该校验，关闭旧表写入和旧表查询回退，再恢复新表单写。接口改看新表事务结果，Canal 同步与新表、ES 校验继续运行。旧表归档另行安排，不能直接回退到已停写的旧数据。','plan-6',['section-9-5','section-5-4'],[]),
('如何演练容量方案和索引维护窗口？','先约定单表与全部分表的维护窗口及业务指标，再准备接近目标规模、商户分布和生产配置的数据，带相同总业务负载执行同样的索引变更。比较 16 张、32 张表的耗时、业务延迟、锁等待和资源消耗，必要时加入 64 张表。单表变小不等于全部变更更快，实例峰值承载能力还要单独压测。','plan-2',['section-1-2-9'],[]),
]
interview=head(2,'interview','2. 面试追问',['section-1-2'])
interview+='各项追问对应改造计划中的功能。先用精炼口述讲清职责、顺序和边界；需要展开时，再跳到后面的具体实现方案。以下描述方案口径，未验证的实现不得表述为已完成能力。\n'
for i,(q,answer,stage,targets,aliases) in enumerate(questions,1):
    interview+=head(3,f'question-{i}',f'2.{i} {q}',aliases)
    interview+='**精炼口述**\n\n'+answer+'\n\n'
    interview+='**对应计划与实现**\n\n[改造计划](#'+stage+')；'+ '、'.join('[实现 '+number(t.removeprefix('section-').replace('-','.'))+'](#'+t+')' for t in targets)+'。\n'

impl=head(2,'implementation','3. 具体实现方案',['section-1-2-6'])
impl+='按数据模型、迁移、写入同步、校验和切换的执行链路展开。SQL、配置和实现细节集中在本章；各项功能的精炼口述见[面试追问](#interview)。\n'
impl+=move('section-3','section-4')+'\n\n'
impl+=move('section-1-2-10','section-1-2-11')+'\n\n'
impl+=move('section-1-2-9','section-1-2-10')+'\n\n'
for n in range(4,11):
    impl+=move(f'section-{n}',f'section-{n+1}')+'\n\n'
impl+=move('section-12')
# Existing chapter-relative wording must follow the new subsection structure.
impl=impl.replace('本章','本节').replace('第 3.5、3.6 节再展开','第 3.5、3.6 节再展开')
# Repair the known replacement typo without changing the deployment condition.
impl=impl.replace('若应用与校验旧表与新表增量校验任务起部署','若应用与校验任务一起部署')

supp=head(2,'supplement','4. 补充')
supp+='收录不作为改造功能单独追问的基础知识，以及待确认事项、技术栈版本和文档维护约定。基础知识用于解释方案，不代表新增业务功能。\n\n'
supp+=move('section-1-2-7','section-1-2-8')+'\n\n'
supp+=move('section-1-2-8','section-1-2-9')+'\n\n'
supp+=move('section-11','section-12')+'\n\n'
supp+=move('section-1-3','section-1-4')+'\n\n'
supp+=move('section-2-4','section-3')+'\n\n'
supp+=head(3,'section-2-3','4.6 文档维护规则')
supp+='维护时先更新第 3 章的具体规则，再同步第 1 章改造计划、第 2 章精炼口述和开头大纲。通用知识及验证限制放在第 4 章。章节移动时同步编号和链接；保留旧锚点作为兼容入口，不新增已不存在的来源文件。用户确认的内容同步记录到同目录 README。\n'

body=(plan+interview+impl+supp).strip()
# Generate navigation from the actual resulting headings, so no hand-maintained duplicates.
outline=['<a id="section-outline"></a>','', '## 文档大纲','']
last_anchor=None
for line in body.splitlines():
    m=re.fullmatch(r'<a id="([^"]+)"></a>',line)
    if m: last_anchor=m[1]
    m=re.match(r'^(#{2,3}) (\d+(?:\.\d+)?)(?:\s)(.+)$',line)
    if m:
        indent='  ' if len(m[1])==3 else ''
        outline.append(f'{indent}- [{m[2]} {m[3]}](#{last_anchor})')

result=title+'\n\n'+'\n'.join(outline)+'\n\n'+body+'\n'

# Exact preservation checks for moved technical chapters (apart from reviewed textual navigation changes).
old_code=re.findall(r'^```[^\n]*\n.*?^```',original,re.M|re.S)
new_code=re.findall(r'^```[^\n]*\n.*?^```',result,re.M|re.S)
assert sorted(old_code)==sorted(new_code), 'Code block content was lost or changed'
old_ids=set(re.findall(r'<a id="([^"]+)"',original))
ids=re.findall(r'<a id="([^"]+)"',result)
assert len(ids)==len(set(ids)), 'Duplicate anchor'
assert not old_ids-set(ids), f'Lost original anchors: {old_ids-set(ids)}'
links=re.findall(r'\]\(#([^\)]+)\)',result)
assert not set(links)-set(ids), 'Broken link'
assert len(questions)==result.count('**精炼口述**')
assert result.index('## 文档大纲')<result.index('## 1. 改造计划')<result.index('## 2. 面试追问')<result.index('## 3. 具体实现方案')<result.index('## 4. 补充')

doc.write_text(result,encoding='utf-8')

r=readme.read_text(encoding='utf-8-sig')
# Record how the newly approved organization supersedes historical location requirements.
notice='''## 当前文档结构（2026-09-20 确认）

用户确认按以下顺序重组统一方案：文档大纲 → 第 1 章改造计划 → 第 2 章面试追问 → 第 3 章具体实现方案 → 第 4 章补充。原完整口述稿融入改造计划；计划涉及的功能均提供追问、精炼口述及实现跳转。通用索引知识、ROW 日志示例和验证限制归入补充。

- 本节取代下方历史记录中的章节位置、编号及“第 1 章完整口述稿”等结构要求；业务规则、字段职责、切换条件和验证边界继续有效。
- 容量选型与维护演练的精炼回答见 2.1、2.18，具体依据和 SQL 见 3.1.4、3.1.5；改造计划只保留布局结论。
- 两项校验口述见 2.8—2.13，实现见 3.4—3.6；不恢复独立的重复总结节。
- 停写迁移操作手册保留在同一文档的 3.8，动态起点与配置见 3.9；历史验收仍由 DBA 负责，具体实现内容均置于口述之后。
- 查询切换与最终关闭旧表写入分别见计划 1.5、1.6，追问 2.15—2.17，实现 3.7。
- 大纲根据实际标题生成，保留原有锚点兼容既有引用；来源列继续删除。SQL 和代码仅搬移，未新增业务实现或宣称运行验证通过。

'''
r=r.replace('# 已确认的文档要求\n','# 已确认的文档要求\n\n'+notice,1)
# Historical records are explicitly retained as history, not silently rewritten into new approvals.
r=r.replace('## 容量规划说明','## 历史确认记录\n\n以下章节编号和位置为当时记录，现行位置以开头“当前文档结构”为准。\n\n### 容量规划说明',1)
readme.write_text(r.rstrip()+'\n',encoding='utf-8')

audit={'original_lines':len(original.splitlines()),'new_lines':len(result.splitlines()),'questions':len(questions),'preserved_code_blocks':len(old_code),'original_anchors_preserved':len(old_ids),'total_anchors':len(ids),'broken_links':0,'backup':str(backup)}
(root/'tmp/consume-doc-restructure-audit.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(audit,ensure_ascii=False,indent=2))
