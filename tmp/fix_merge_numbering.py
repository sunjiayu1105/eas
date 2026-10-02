from pathlib import Path
import re

p = Path('D:/000_ 找工作_2026/sources/E账通/003/001/002_消费记录分库分表、双写与ES改造统一方案.md')
r = p.with_name('README.md')
s = p.read_text(encoding='utf-8')
readme = r.read_text(encoding='utf-8')
(Path('D:/000_ 找工作_2026/sources/E账通/tmp') / 'consume-before-merge-numbering.md').write_text(s, encoding='utf-8')

def amap(text):
    out, pending = {}, []
    for n, line in enumerate(text.splitlines(), 1):
        m = re.fullmatch(r'<a id="([^"]+)"></a>', line)
        if m: pending.append(m.group(1))
        elif line.startswith('#') or line == '**全局约束**':
            for a in pending: out[a] = n
            pending = []
    return out

old = amap(s)
rev = {n:a for a,n in old.items()}
pat = r'\]\(<' + re.escape(p.as_posix()) + r':(\d+)>\)'
s, count = re.subn(pat, lambda m: '](#' + rev[int(m.group(1))] + ')', s)
assert count == 83, count

s = s.replace('- [1. 改造原因](#reason)\n- [2. 改造计划](#plan)', '- [1. 改造原因与计划](#reason)')
s = s.replace('## 1. 改造原因\n', '## 1. 改造原因与计划\n', 1)
old_block = '''<a id="plan"></a>
### 改造计划

<a id="section-1-1"></a>
<a id="section-2"></a>
<a id="plan-1"></a>
<a id="section-1-1-1"></a>

## 2. 改造计划
'''
new_block = '''<a id="plan"></a>
<a id="section-1-1"></a>
<a id="section-2"></a>
<a id="plan-1"></a>
<a id="section-1-1-1"></a>

### 改造计划
'''
assert old_block in s
s = s.replace(old_block, new_block, 1)

# 合并后，功能详解和补充章节整体前移一级；同步更新正文中的章节引用。
lines = []
for line in s.splitlines():
    if re.match(r'^#+\s+[34]\.', line):
        line = re.sub(r'^(#+\s+)3\.', r'\g<1>2.', line)
        line = re.sub(r'^(#+\s+)4\.', r'\g<1>3.', line)
    line = re.sub(r'第 3\.', '第 2.', line)
    line = re.sub(r'第 4\.', '第 3.', line)
    line = line.replace('第 3 章', '第 2 章').replace('第 4 章', '第 3 章')
    lines.append(line)
s = '\n'.join(lines) + '\n'

new = amap(s)
s = re.sub(r'\]\(#([^\)]+)\)', lambda m: '](<' + p.as_posix() + ':' + str(new[m.group(1)]) + '>)', s)

readme = readme.replace('用户确认按“文档大纲 → 第 1 章改造原因 → 第 2 章改造计划 → 第 3 章功能详解 → 第 4 章补充”组织统一方案。', '用户确认按“文档大纲 → 第 1 章改造原因与计划 → 第 2 章功能详解 → 第 3 章补充”组织统一方案。')
readme = readme.replace('- 改造原因与计划合并为第 1 章：先说明现状压力和改造目标，再紧接已确认的两轮改造计划；具体校验和切换条件放在后续功能详解章节。', '- 改造原因与改造计划合并为第 1 章：先说明现状压力和改造目标，再紧接已确认的两轮改造计划；功能详解和补充章节分别调整为第 2 章和第 3 章。')
r.write_text(readme, encoding='utf-8')
p.write_text(s, encoding='utf-8')
print({'links': count, 'lines': len(s.splitlines()), 'anchors': len(new)})
