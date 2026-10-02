from pathlib import Path
import re

p = Path('D:/000_ 找工作_2026/sources/E账通/003/001/002_消费记录分库分表、双写与ES改造统一方案.md')
r = p.with_name('README.md')
s = p.read_text(encoding='utf-8')
readme = r.read_text(encoding='utf-8')

def anchor_map(text):
    out, pending = {}, []
    for n, line in enumerate(text.splitlines(), 1):
        m = re.fullmatch(r'<a id="([^"]+)"></a>', line)
        if m:
            pending.append(m.group(1))
        elif line.startswith('#') or line == '**全局约束**':
            for a in pending:
                out[a] = n
            pending = []
    return out

old_map = anchor_map(s)
rev = {n: a for a, n in old_map.items()}
abs_pat = r'\]\(<' + re.escape(p.as_posix()) + r':(\d+)>\)'
s, count = re.subn(abs_pat, lambda m: '](#' + rev[int(m.group(1))] + ')', s)
assert count == 83, count

s = s.replace('- [1. 改造原因](#reason)\n- [2. 改造计划](#plan)', '- [1. 改造原因与计划](#reason)')
s = s.replace('## 1. 改造原因\n', '## 1. 改造原因与计划\n', 1)
s = s.replace('<a id="plan"></a>\n<a id="section-1"></a>', '<a id="plan"></a>\n### 改造计划\n', 1)
s = s.replace('具体执行阶段见第 2 章改造计划，校验和切换条件见第 3 章功能详解。', '具体执行阶段见本章“改造计划”，校验和切换条件见第 3 章功能详解。', 1)
new_map = anchor_map(s)
s = re.sub(r'\]\(#([^\)]+)\)', lambda m: '](<' + p.as_posix() + ':' + str(new_map[m.group(1)]) + '>)', s)

readme = readme.replace('用户确认按“文档大纲 → 第 1 章改造原因 → 第 2 章改造计划 → 第 3 章功能详解 → 第 4 章补充”组织统一方案。', '用户确认按“文档大纲 → 第 1 章改造原因与计划 → 第 2 章功能详解 → 第 3 章补充”组织统一方案。')
readme = readme.replace('- 改造原因只说明问题、目标和必要边界：通过分库分表分散数据库压力，将适合检索的列表查询迁移到 Elasticsearch（ES），并保证迁移期间新旧数据能够核对、ES 数据能够验证；具体执行阶段放在第 2 章，校验和切换条件放在第 3 章。', '- 改造原因与计划合并为第 1 章：先说明现状压力和改造目标，再紧接已确认的两轮改造计划；具体校验和切换条件放在后续功能详解章节。')
p.write_text(s, encoding='utf-8')
r.write_text(readme, encoding='utf-8')
print({'links': count, 'lines': len(s.splitlines()), 'anchors': len(new_map)})
