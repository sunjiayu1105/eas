from pathlib import Path
import re
p=Path(r'D:\000_ 找工作_2026\sources\E账通\003\001\002_消费记录分库分表、双写与ES改造统一方案.md')
s=p.read_text(encoding='utf-8'); lines=s.splitlines()
links=re.findall(r'\.md:(\d+)>',s)
bad=[]
for n in links:
 i=int(n)-1
 if i<0 or i>=len(lines) or not (lines[i].startswith('#') or lines[i]=='**全局约束**'): bad.append((n,lines[i] if 0<=i<len(lines) else 'OUT'))
print({'lines':len(lines),'links':len(links),'bad_targets':bad,'double_dot':'..' in s,'oral':s.count('**面试精炼口述**'),'code_blocks':len(re.findall(r'```[^\n]*\n.*?```',s,re.S))})
