from pathlib import Path
import re
p=Path(r'D:\000_ 找工作_2026\sources\E账通\003\001\002_消费记录分库分表、双写与ES改造统一方案.md')
s=p.read_text(encoding='utf-8')
lines=s.splitlines()
plan='第 1 轮停写迁移，验收后统一启用双写、同步和校验，列表仍查旧表；第 2 轮满足校验条件后灰度切换查询。全部旧表依赖切完并继续观察通过，才关闭旧表写入。'
print('plan_ok', plan in s, 'oral', s.count('**面试精炼口述**'), 'code', len(re.findall(r'```[^\n]*\n.*?```',s,re.S)))
for m in re.finditer(r'\.md:(\d+)>',s):
 n=int(m.group(1));
 if not lines[n-1].startswith('#') and lines[n-1] != '**全局约束**': print('bad_target',n,lines[n-1])
