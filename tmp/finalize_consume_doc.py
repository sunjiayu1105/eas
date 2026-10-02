from pathlib import Path
import re, json
root=Path(r'D:\000_ 找工作_2026\sources\E账通')
p=root/'003/001/002_消费记录分库分表、双写与ES改造统一方案.md'
s=p.read_text(encoding='utf-8')
s=s.replace('容量依据及验证边界见 [1.2 分库分表](#section-1-1-2)', '容量依据及验证边界见 [3.1.4 容量选型](#section-1-2-10)')
s=s.replace('普通单条异常按 7.3.5.3 隔离后继续往下处理','普通单条异常按 [3.5.3.5.3](#section-7-3-5-3) 隔离后继续往下处理')
s=s.replace('不能假定旧表与新表增量校验任务定还在','不能假定当前待校验任务一定还在')
s=re.sub(r'^#### (步骤 [1-7]：)',r'##### \1',s,flags=re.M)
s=s.replace('作为 ES 同步与新表、ES 校验的起点','作为 ES 同步和新表与 ES 校验的起点')
s=s.replace('保存旧表、新表增量校验的起点','保存旧表与新表增量校验的起点')
s=s.replace('它与新表、ES 校验独立运行','它与新表与 ES 增量校验任务独立运行')
s=s.replace('它与旧表、新表校验以及 Canal 同步分别记录进度','它与旧表与新表增量校验任务、Canal 同步分别记录进度')
s=s.replace('Canal 同步与新表、ES 校验继续运行','Canal 同步与新表与 ES 增量校验任务继续运行')
s=s.replace('差异次数加一，平台报警','差异次数加 1，平台报警')
# Format identifiers only in newly written interview answers.
a=s.index('<a id="interview">'); b=s.index('<a id="implementation">')
q=s[a:b]
q=re.sub(r'(?<![\w`])(merchantNo|shardIndex|consumeId|serialNo|_id)(?![\w`])',r'`\1`',q)
s=s[:a]+q+s[b:]

# Regenerate a real nested outline including the 4 parent chapters.
body=s[s.index('<a id="plan"></a>'):]
body=re.sub(r'\n{3,}','\n\n',body)
body=re.sub(r'(?<!\n)\n(<a id=)',r'\n\n\1',body)
outline=['<a id="section-outline"></a>','','## 文档大纲','']
anchors=[]
for line in body.splitlines():
    m=re.fullmatch(r'<a id="([^"]+)"></a>',line)
    if m: anchors.append(m[1]); continue
    h=re.match(r'^(#{2,6}) (\d+(?:\.\d+)*)(?:\.)? (.+)$',line)
    if h:
        if len(h[1])<=3:
            label=h[2]+('.' if len(h[1])==2 else '')+' '+h[3]
            outline.append(('  ' if len(h[1])==3 else '')+'- ['+label+'](#'+anchors[0]+')')
        anchors=[]
    elif line.strip():
        anchors=[]
s=s.splitlines()[0]+'\n\n'+'\n'.join(outline)+'\n\n'+body.rstrip()+'\n'

original=(root/'tmp/consume-doc-before-structure-20260920.md').read_text(encoding='utf-8')
ids=re.findall(r'<a id="([^"]+)"',s)
oldids=set(re.findall(r'<a id="([^"]+)"',original))
assert len(ids)==len(set(ids))
assert oldids.issubset(ids)
assert set(re.findall(r'\]\(#([^\)]+)\)',s)).issubset(ids)
assert sorted(re.findall(r'^```[^\n]*\n.*?^```',s,re.M|re.S))==sorted(re.findall(r'^```[^\n]*\n.*?^```',original,re.M|re.S))
assert len(re.findall(r'^- \[[1-4]\. ',s,re.M))==4
assert s.count('**精炼口述**')==18
# Every numbered heading agrees with its depth; no missing or duplicated sibling numbers.
nums=re.findall(r'^(#{2,6}) (\d+(?:\.\d+)*)(?:\.)? ',body,re.M)
assert len(nums)==len(set(n for _,n in nums))
for hashes,n in nums:
    assert len(hashes)==len(n.split('.'))+1,(hashes,n)
siblings={}
for hashes,n in nums:
    parent,_,last=n.rpartition('.')
    siblings.setdefault(parent,[]).append(int(last or n))
for parent,values in siblings.items():
    assert values==list(range(1,len(values)+1)),(parent,values)
# All explicit numbered navigation labels resolve to that numbered heading.
anchor_heading={}; pending=[]
for line in body.splitlines():
    m=re.fullmatch(r'<a id="([^"]+)"></a>',line)
    if m: pending.append(m[1]); continue
    m=re.match(r'^#{2,6} (\d+(?:\.\d+)*)(?:\.)? ',line)
    if m:
        for a in pending: anchor_heading[a]=m[1]
        pending=[]
    elif line.strip(): pending=[]
errors=[]
for label,a in re.findall(r'\[([^\]\n]+)\]\(#([^\)]+)\)',s):
    m=re.search(r'(?<![\d.])(\d+(?:\.\d+)+)(?![\d.])',label)
    if m and a in anchor_heading and m[1]!=anchor_heading[a]:
        errors.append((label,a,anchor_heading[a]))
assert not errors,errors
p.write_text(s,encoding='utf-8')
print(json.dumps({'top_level_chapters':4,'questions':18,'code_blocks_preserved':17,'old_anchors_preserved':116,'total_anchors':len(ids),'broken_links':0,'heading_and_link_labels':'passed','outline':'4 parent chapters with subsections'},ensure_ascii=False))
