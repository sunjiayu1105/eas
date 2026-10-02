from pathlib import Path
import re

root = Path('D:/000_ 找工作_2026/sources/E账通')
p = root / '003/001/002_消费记录分库分表、双写与ES改造统一方案.md'
r = p.with_name('README.md')
old = p.read_text(encoding='utf-8')
readme = r.read_text(encoding='utf-8')
backup = root / 'tmp/consume-before-correctness-optimization.md'
assert not backup.exists() or backup.read_text(encoding='utf-8') == old
backup.write_text(old, encoding='utf-8')
(root / 'tmp/readme-before-correctness-optimization.md').write_text(readme, encoding='utf-8')

def targets(s):
    result, pending = {}, []
    for n, line in enumerate(s.splitlines(), 1):
        a = re.fullmatch(r'<a id="([^"]+)"></a>', line)
        if a:
            pending.append(a[1])
        elif line.startswith('#') or line == '**全局约束**':
            for key in pending:
                result[key] = n
            pending = []
    return result

mapping = targets(old)
reverse = {n: key for key, n in mapping.items()}
pattern = r'\]\(<' + re.escape(p.as_posix()) + r':(\d+)>\)'
s, link_count = re.subn(pattern, lambda m: '](#' + reverse[int(m[1])] + ')', old)
assert link_count == 84, link_count

start = s.index('<a id="section-4-3"></a>')
end = s.index('<a id="section-4-4"></a>', start)
aliases = '\n'.join('<a id="section-4-3-%d"></a>' % i for i in range(1, 7))
s = s[:start] + '''<a id="section-4-3"></a>
''' + aliases + '''

#### 3.2.3 历史数据库验收范围与职责

**验收范围：覆盖本批历史数据及全部分片**

历史验收覆盖旧库消费记录表与新库全部 32 个物理分片。迁移前确认目标表结构、完整字段映射、分片和主键规则，并完成资源与停服耗时演练。DBA 登记批次编号、规则版本、源表范围、目标分片清单及明确的数据边界，验收结果必须覆盖同一批范围。

**职责分工：DBA 执行验收，后端提供规则**

DBA 负责全量数据校验，保存验收报告、差异明细和批次状态。Java 后端提供并确认字段映射、身份、分片和版本规则，不承担该项 DBA 校验；业务负责人依据验收结果决定是否放行。本节不展开 DBA 的具体 SQL、扫描算法和执行步骤。

**通过条件：数据完整、字段一致、身份与路由正确**

- **记录完整**：全部分片完成验收；不存在缺失、额外或重复记录，不能仅凭总数或金额汇总相等判定通过。
- **字段一致**：按 `serialNo` 关联同一记录，排除旧表 `id` 与新表 `consumeId` 的直接值比较，其余字段全部比较，包含 `version` 和时间。字段类型、金额、时间精度、时区、枚举和空值按统一规则处理，区分 `NULL` 与空字符串。恢复业务后的增量校验另按规定业务字段清单执行，不能混用比较范围。
- **身份与路由正确**：`serialNo` 唯一且与记录一一对应；`consumeId` 非空、唯一、迁移后稳定，其固定 2 位后缀、`merchantNo` 算出的期望分片和实际库表一致。
- **结果完整**：全部范围和分片有明确结果，无未解决的数据差异、主键异常或执行异常；DBA 提供可复核的验收报告后，按下一节判定批次状态。

**保持停写：未通过就不进入后续上线**

验收期间保持业务停写。若仍有写入、导入未结束、范围发生变化或结果不完整，不能判为通过。校验只读业务数据，修复由独立流程执行；修复后由 DBA 重新验收，取得 `PASS` 后才继续保存增量起点及上线验证。

''' + s[end:]

def replace_once(a, b):
    global s
    assert s.count(a) == 1, (a, s.count(a))
    s = s.replace(a, b)

replace_once('将后台列表检索逐步迁到 ES，并通过', '将后台列表检索逐步迁到搜索引擎 Elasticsearch（ES），并通过')
replace_once('迁移和校验不改 ShardingSphere-JDBC 新表写入代码。', '迁移和校验不改 ShardingSphere-JDBC 新表写入代码。ShardingSphere-JDBC 是应用侧的分片组件，负责按配置把 SQL 路由到对应库表。')
replace_once('**明确 Canal 职责**：ES 同步通道', '**明确 Canal 职责**：Canal 读取 MySQL 的 binlog（二进制变更日志），为同步和校验提供数据变更事件。ES 同步通道')
replace_once('**上线前准备**\n\n| 准备项', '**上线前准备**\n\nGTID 是 MySQL 用来唯一标识已提交事务的全局事务标识；本文保存完整 GTID 集合作为日志基线，以明确各条链路从哪些事务之后开始读取。\n\n| 准备项')
replace_once('| 旧表与新表增量校验任务的校验 CDC |', '| 旧表与新表增量校验任务的校验 CDC（变更数据捕获，即从日志收集数据变更） |')
replace_once('**两边不会一起成功或一起回滚**', '**两次提交不保证原子性**')
replace_once('| 链路 | 负责的工作 | 进度归属 |', '| 链路 | 负责的工作 | 状态或进度 |')
replace_once('| 业务双写 | 旧表提交后，通过 ShardingSphere-JDBC 写入新表 | 业务事务与失败记录 |', '| 业务双写 | 旧表提交后，通过 ShardingSphere-JDBC 写入新表 | 接口结果只看旧表提交；新表失败时尽量记录 |')
replace_once('**区分进度与校验结果**', '**失败记录不作为校验前提**\n\n应用仍能运行时尽量记录新表写入失败；新库宕机或应用同时异常时，记录也可能失败。自动校验独立收集数据库变更，不依赖失败记录发现差异。\n\n**区分进度与校验结果**')
replace_once('以下为项目统一技术栈版本；本方案不新增或依赖 RocketMQ 相关组件，也不另行指定 ShardingSphere-JDBC 或 Canal 版本。', '以下为项目统一技术栈版本。本次迁移、同步和校验方案不新增 RocketMQ 消息传输链路；已有业务 RocketMQ 消费者仍属于停写和恢复的控制范围。本文不另行指定 ShardingSphere-JDBC 或 Canal 版本。')

new_mapping = targets(s)
s = re.sub(r'\]\(#([^\)]+)\)', lambda m: '](<' + p.as_posix() + ':' + str(new_mapping[m[1]]) + '>)', s)
assert re.findall(r'```[^\n]*\n.*?```', old, re.S) == re.findall(r'```[^\n]*\n.*?```', s, re.S)
assert s.count('**面试精炼口述**') == old.count('**面试精炼口述**') == 22
plan = '第 1 轮停写迁移，验收后统一启用双写、同步和校验，列表仍查旧表；第 2 轮满足校验条件后灰度切换查询。全部旧表依赖切完并继续观察通过，才关闭旧表写入。'
assert s.split('## 2. 改造计划\n\n')[1].split('<a ')[0].strip() == plan
anchors = re.findall(r'<a id="([^"]+)"></a>', s)
assert len(anchors) == len(set(anchors))
assert set(anchors) == set(re.findall(r'<a id="([^"]+)"></a>', old))
links = re.findall(pattern, s)
assert len(links) == link_count - 1  # 合并 DBA 细节时去除该段内 1 条重复引用。
for n in links:
    assert s.splitlines()[int(n)-1].startswith(('#', '**全局约束**'))
assert '两边不会一起成功' not in s
assert not re.search(r'3\.2\.3\.[1-6]', s)
addition = '''## 本轮优化记录（2026-09-20）

- 继续执行已确认的历史验收边界：3.2.3 仅保留范围、DBA 与后端职责、通过条件及保持停写的要求；不展开 DBA 的 SQL、扫描算法和具体校验步骤。保留全部分片、完整字段、身份与路由约束，原细分锚点兼容指向本节。
- 修正“两边不会一起成功或一起回滚”的误导表述，改为“两次提交不保证原子性”；双写接口结果仍只取决于旧表提交，失败记录为尽力保存，自动校验不依赖该记录。
- 补充首次出现的 ES、ShardingSphere-JDBC、Canal、binlog、GTID 和 CDC 的就地解释；明确本次方案不新增 RocketMQ 传输链路，已有业务消费者仍纳入停写与恢复控制。
- 保留已确认的精简改造计划、功能开头的面试口述及原有代码块；同步重算正文导航行号。检查范围为文本与链接目标，未执行数据库操作、编译或阅读器点击验收。

'''
readme = readme.replace('## 历史确认记录\n', addition + '## 历史确认记录\n', 1)
p.write_text(s, encoding='utf-8')
r.write_text(readme, encoding='utf-8')
print({'links_checked': len(links), 'oral_summaries': 22, 'unchanged_code_blocks': len(re.findall(r'```[^\n]*\n.*?```', s, re.S)), 'lines_before': len(old.splitlines()), 'lines_after': len(s.splitlines()), 'backup': str(backup)})
