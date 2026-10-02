from pathlib import Path
import re
p=Path(r'D:\000_ 找工作_2026\sources\E账通\003\001\002_消费记录分库分表、双写与ES改造统一方案.md')
s=p.read_text(encoding='utf-8')
lines=s.splitlines()
headings=[i+1 for i,l in enumerate(lines) if l.startswith('#') or l=='**全局约束**']
pat=r'\]\(<' + re.escape(p.as_posix()) + r':(\d+)>\)'
def fix(m):
 n=int(m.group(1)); candidates=[h for h in headings if abs(h-n)<=5]
 if not candidates:
  raise ValueError(f'no heading near {n}')
 return '](<'+p.as_posix()+':'+str(min(candidates,key=lambda h:abs(h-n)))+'>)'
s,n=re.subn(pat,fix,s)
assert n==83,n
p.write_text(s,encoding='utf-8')
print({'rewritten':n,'lines':len(lines)})
