from pathlib import Path
import re
p=Path(r'D:\000_ 找工作_2026\sources\E账通\003\001\002_消费记录分库分表、双写与ES改造统一方案.md')
s=p.read_text(encoding='utf-8'); lines=s.splitlines()
bad=[]
for n in re.findall(r'\.md:(\d+)>',s):
 i=int(n)-1
 if not (lines[i].startswith('#') or lines[i]=='**全局约束**'): bad.append((n,lines[i]))
print({'links':len(re.findall(r'\.md:\d+>',s)),'bad_targets':bad,'double_dot':'..' in s,'oral':s.count('**面试精炼口述**'),'code_blocks':len(re.findall(r'```[^\n]*\n.*?```',s,re.S))})
