from pathlib import Path
import re
p=Path(r'D:\000_ 找工作_2026\sources\E账通\003\001\002_消费记录分库分表、双写与ES改造统一方案.md')
s=p.read_text(encoding='utf-8'); head,tail=s.split('<a id="reason"></a>',1)
head=re.sub(r'^(\s*- \[)2\.(\d+)',r'\g<1>3.\2',head,flags=re.M)
head=head.replace('[3.1 分库分表与数据模型]','[3.1 分库分表与数据模型]',1)
p.write_text(head+'<a id="reason"></a>'+tail,encoding='utf-8')
