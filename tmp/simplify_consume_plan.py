from pathlib import Path
import re

root=Path(r'D:\000_ 找工作_2026\sources\E账通')
p=root/'003/001/002_消费记录分库分表、双写与ES改造统一方案.md'
r=p.with_name('README.md')
s=p.read_text(encoding='utf-8')
backup=root/'tmp/consume-before-simple-plan.md'
assert not backup.exists()
backup.write_text(s,encoding='utf-8')
lines=s.splitlines()
# Translate line links to stable temporary heading references before moving text.
line_ids={}; pending=[]
for i,line in enumerate(lines,1):
    m=re.fullmatch(r'<a id="([^"]+)"></a>',line)
    if m:pending.append(m[1]);continue
    if line.startswith('#'):
        if pending:line_ids[i]=pending[0]
        pending=[]
    elif line.strip():pending=[]
def temp(m):
    assert m[2]==p.as_posix()
    return '['+m[1]+'](#'+line_ids[int(m[3])]+')'
s=re.sub(r'\[([^\]]+)\]\(<([^>]+):(\d+)>\)',temp,s)
oldplan=s[s.index('<a id="plan">'):s.index('<a id="functions">')]
body=s[s.index('<a id="functions">'):]
def number(n):
    if n=='2' or n.startswith('2.'):return '3'+n[1:]
    if n=='3' or n.startswith('3.'):return '4'+n[1:]
    return n
body=re.sub(r'^(#{2,6}) (\d+(?:\.\d+)*)(\.?) ',lambda m:m[1]+' '+number(m[2])+m[3]+' ',body,flags=re.M)
body=re.sub(r'第 ([23](?:\.\d+)*) (章|节)',lambda m:'第 '+number(m[1])+' '+m[2],body)
body=body.replace('第 2.5、2.6 节','第 3.5、3.6 节')
body=re.sub(r'(?<![\d.])([23]\.\d+(?:\.\d+)*)(?![\d.])(?= 节)',lambda m:number(m[1]),body)
body=body.replace('第 1 章改造计划','第 2 章改造计划')
# The detailed sections already cover the removed plan steps; keep old IDs near their destinations.
groups={
 'section-3':['plan-2','section-1-1-2','section-2-1'],
 'section-4':['plan-3','section-1-1-3'],
 'section-5':['plan-4','section-1-1-4'],
 'section-9':['plan-5','section-1-1-7'],
 'section-9-5':['plan-6']
}
for target,aliases in groups.items():
    tag=f'<a id="{target}"></a>'
    body=body.replace(tag,tag+'\n'+'\n'.join(f'<a id="{a}"></a>' for a in aliases),1)
constraints=oldplan[oldplan.index('<a id="section-2-2">'):].strip()
constraints=constraints.replace('### 1.7 全局约束','**全局约束**')
boundary=re.search(r'容量选型按已确认的.*?实施前仍须核实.*?。',oldplan)[0]
intro='''以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。

**功能范围与验证边界**

本次改造覆盖新增、修改、撤销、退货和物理删除。各项功能按“面试精炼口述 → 详细方案”展开，流程、代码、异常处理和验证边界放在对应功能内。

'''+boundary+'\n\n'+constraints+'\n\n'
start=body.index('按照改造计划的执行顺序展开。')
end=body.index('<a id="section-3">',start)
body=body[:start]+intro+body[end:]
plantext='第 1 轮停写迁移，验收后统一启用双写、同步和校验，列表仍查旧表；第 2 轮满足校验条件后灰度切换查询。全部旧表依赖切完并继续观察通过，才关闭旧表写入。'
front='''<a id="reason"></a>

## 1. 改造原因

消费记录持续增长，单表的数据量和索引规模越来越大，存储、查询和维护压力随之增加；后台列表检索也会占用数据库资源。因此，需要通过分库分表分散存储和读写压力，将后台列表检索逐步迁到 ES，并通过双写、同步和校验完成过渡。

<a id="plan"></a>
<a id="section-1"></a>
<a id="section-1-1"></a>
<a id="section-2"></a>
<a id="plan-1"></a>
<a id="section-1-1-1"></a>

## 2. 改造计划

'''+plantext+'\n\n'
body=front+body

def targets(text):
    out={};pending=[]
    for i,line in enumerate(text.splitlines(),1):
        m=re.fullmatch(r'<a id="([^"]+)"></a>',line)
        if m:pending.append(m[1]);continue
        if line.startswith('#') or line=='**全局约束**':
            for a in pending:out[a]=(i,line)
            pending=[]
        elif line.strip():pending=[]
    return out
dest=targets(body)
def labels(m):
    label,a=m[1],m[2]
    h=re.match(r'^#+ (\d+(?:\.\d+)*)(?:\.)? ',dest[a][1])
    if h:label=re.sub(r'(?<![\d.])\d+(?:\.\d+)+(?![\d.])',h[1],label)
    return '['+label+'](#'+a+')'
body=re.sub(r'\[([^\]]+)\]\(#([^\)]+)\)',labels,body)
# Retain the same outline granularity as before, excluding the removed plan subsections.
oldtoc=s[:s.index('<a id="plan">')]
listed=set(re.findall(r'\]\(#([^\)]+)\)',oldtoc))
listed.add('reason')
toc=['<a id="section-outline"></a>','','## 文档大纲','']
pending=[]
for line in body.splitlines():
    m=re.fullmatch(r'<a id="([^"]+)"></a>',line)
    if m:pending.append(m[1]);continue
    h=re.match(r'^(#{2,4}) (\d+(?:\.\d+)*)(?:\.)? (.+)$',line)
    if h:
        if pending and any(a in listed for a in pending):
            toc.append('  '*(len(h[1])-2)+'- ['+h[2]+('.' if len(h[1])==2 else '')+' '+h[3]+'](#'+pending[0]+')')
        pending=[]
    elif line.strip():pending=[]
draft=s.splitlines()[0]+'\n\n'+'\n'.join(toc)+'\n\n'+body.rstrip()+'\n'
dest=targets(draft)
dest['section-outline']=(5,'## 文档大纲')
out=re.sub(r'\[([^\]]+)\]\(#([^\)]+)\)',lambda m:f'[{m[1]}](<{p.as_posix()}:{dest[m[2]][0]}>)',draft)
assert out.count('\n')==draft.count('\n')
original=backup.read_text(encoding='utf-8')
assert sorted(re.findall(r'^```[^\n]*\n.*?^```',original,re.M|re.S))==sorted(re.findall(r'^```[^\n]*\n.*?^```',out,re.M|re.S))
assert re.search(r'## 2\. 改造计划\n\n(.*?)\n\n<a id="functions">',out,re.S)[1]==plantext
assert set(re.findall(r'<a id="([^"]+)"',original)).issubset(re.findall(r'<a id="([^"]+)"',out))
p.write_text(out,encoding='utf-8')

rm=r.read_text(encoding='utf-8')
start=rm.index('## 当前文档结构');end=rm.index('## 历史确认记录')
current=rm[start:end]
current=current.replace('文档大纲 → 第 1 章改造计划 → 第 2 章功能详解 → 第 3 章补充','文档大纲 → 第 1 章改造原因 → 第 2 章改造计划 → 第 3 章功能详解 → 第 4 章补充')
current=current.replace('- 第 2 章按业务链路','- 第 3 章按业务链路').replace('专题的 2.2.5','专题的 3.2.5').replace('配置见 2.8','配置见 3.8').replace('归入第 3 章补充','归入第 4 章补充')
current=current.replace('- 原 18 项追问', '- 用户进一步确认：改造原因先说明单表增长及后台检索压力；改造计划仅保留以下原话，不再展开准备、迁移、上线、切换和全局约束。这些内容统一归入功能详解。\n\n> '+plantext+'\n\n- 原 18 项追问')
r.write_text(rm[:start]+current+rm[end:],encoding='utf-8')
print('Updated reasons, exact requested plan, detail placement, README and all line links.')
