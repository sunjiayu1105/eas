from pathlib import Path
import re
p=Path(r'D:\000_ 找工作_2026\sources\E账通\003\001\002_消费记录分库分表、双写与ES改造统一方案.md')
r=p.with_name('README.md')
s=p.read_text(encoding='utf-8'); readme=r.read_text(encoding='utf-8')
s=s.replace('- [1. 改造原因与计划]', '- [1. 改造原因]',1)
s=s.replace('- [2. 功能详解]', '- [2. 改造计划]\n- [3. 功能详解]',1)
s=s.replace('- [3. 补充]', '- [4. 补充]',1)
s=s.replace('## 1. 改造原因与计划\n','## 1. 改造原因\n',1)
s=s.replace('### 改造计划\n\n第 1 轮','## 2. 改造计划\n\n第 1 轮',1)
lines=[]
for line in s.splitlines():
    if re.match(r'^#+\s+2\.',line): line=re.sub(r'^(#+\s+)2\.',r'\g<1>3.',line)
    elif re.match(r'^#+\s+3\.',line): line=re.sub(r'^(#+\s+)3\.',r'\g<1>4.',line)
    lines.append(line)
s='\n'.join(lines)+'\n'
s=s.replace('第 2.', '第 __TWO_DOT__.').replace('第 3.', '第 __THREE_DOT__.')
s=s.replace('第 2 章','第 __TWO_CHAPTER__').replace('第 3 章','第 __THREE_CHAPTER__')
s=s.replace('__TWO_DOT__','3.').replace('__THREE_DOT__','4.').replace('__TWO_CHAPTER__','3 章').replace('__THREE_CHAPTER__','4 章')
s=s.replace('本章改造计划和文档大纲','第 2 章改造计划和文档大纲')
raw=s.splitlines(); headings=[i+1 for i,l in enumerate(raw) if l.startswith('#') or l=='**全局约束**']
def fix(m):
    n=int(m.group(1)); return '](<'+p.as_posix()+':'+str(min(headings,key=lambda x:abs(x-n)))+'>)'
s=re.sub(r'\]\(<' + re.escape(p.as_posix()) + r':(\d+)>\)',fix,s)
readme=readme.replace('用户确认按“文档大纲 → 第 1 章改造原因与计划 → 第 2 章功能详解 → 第 3 章补充”组织统一方案。','用户确认按“文档大纲 → 第 1 章改造原因 → 第 2 章改造计划 → 第 3 章功能详解 → 第 4 章补充”组织统一方案。')
readme=readme.replace('- 改造原因与改造计划合并为第 1 章：先说明现状压力和改造目标，再紧接已确认的两轮改造计划；功能详解和补充章节分别调整为第 2 章和第 3 章。','- 改造原因和改造计划恢复为两个独立章节：第 1 章说明现状压力和改造目标，第 2 章保留已确认的两轮改造计划；功能详解和补充章节恢复为第 3 章和第 4 章。')
readme=readme.replace('第 2 章按业务链路分为 8 个专题','第 3 章按业务链路分为 8 个专题').replace('历史迁移专题的 2.2.5','历史迁移专题的 3.2.5').replace('动态进度与配置见 2.8','动态进度与配置见 3.8').replace('归入第 3 章补充','归入第 4 章补充')
p.write_text(s,encoding='utf-8'); r.write_text(readme,encoding='utf-8')
print(len(raw),len(re.findall(r'\.md:\d+>',s)))
