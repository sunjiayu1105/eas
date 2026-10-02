from pathlib import Path
import re
p=Path('D:/000_ 找工作_2026/sources/E账通/003/001/002_消费记录分库分表、双写与ES改造统一方案.md')
s=p.read_text(encoding='utf-8')
head, tail = s.split('<a id="reason"></a>', 1)
lines=head.splitlines()
new=[]
for line in lines:
    if line.startswith('- [2. 改造计划]'):
        continue
    if line.startswith('- [1. 改造原因]'):
        line=line.replace('[1. 改造原因]', '[1. 改造原因与计划]')
    if re.match(r'^\s*- \[3\.', line):
        line=line.replace('[3.', '[2.', 1)
    elif re.match(r'^- \[4\.', line):
        line=line.replace('[4.', '[3.', 1)
    new.append(line)
s='\n'.join(new)+'\n<a id="reason"></a>'+tail
s=s.replace('同步该功能开头的面试精炼口述、第 2 章改造计划和文档大纲。', '同步该功能开头的面试精炼口述、本章改造计划和文档大纲。')
p.write_text(s, encoding='utf-8')
print('ok')
