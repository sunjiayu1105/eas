from pathlib import Path
import re, json

root=Path(r'D:\000_ 找工作_2026\sources\E账通')
p=root/'003/001/002_消费记录分库分表、双写与ES改造统一方案.md'
readme=p.with_name('README.md')
src=p.read_text(encoding='utf-8-sig')
backup=root/'tmp/consume-before-function-regroup.md'
assert not backup.exists(), 'Do not overwrite prior backup'
backup.write_text(src,encoding='utf-8')
(root/'tmp/consume-readme-before-function-regroup.md').write_bytes(readme.read_bytes())

def between(a,b=None):
    begin=src.index(f'<a id="{a}"></a>')
    end=src.index(f'<a id="{b}"></a>',begin) if b else len(src)
    return src[begin:end].strip()

def num(old):
    if old=='3':return '2'
    if old=='4':return '3'
    if old=='3.8' or old.startswith('3.8.'):
        return '2.2.5'+old[len('3.8'):]
    if old=='3.9' or old.startswith('3.9.'):
        return '2.8'+old[len('3.9'):]
    if old.startswith('3.'):return '2'+old[1:]
    if old.startswith('4.'):return '3'+old[1:]
    return old

def renumber(chunk):
    chunk=re.sub(r'^(#{2,6}) (\d+(?:\.\d+)*)(?:\.)? (.+)$',
        lambda m:'#'*(len(num(m[2]).split('.'))+1)+' '+num(m[2])+('.' if '.' not in num(m[2]) else '')+' '+m[3],chunk,flags=re.M)
    # Non-heading references are changed in one pass, so new numbers cannot be reinterpreted.
    lines=[]
    for line in chunk.splitlines():
        if not line.startswith('#'):
            line=line.replace('第 3.5、3.6 节','第 2.5、2.6 节')
            line=re.sub(r'第 ([34](?:\.\d+)*) (章|节)|(?<![\d.])([34]\.\d+(?:\.\d+)*)(?![\d.])(?= 节)',
                lambda m:('第 '+num(m[1])+' '+m[2]) if m[1] else num(m[3]),line)
        lines.append(line)
    return '\n'.join(lines)

# Retain all 18 answers, now next to their corresponding detailed implementation.
targets={1:'section-1-2-10',2:'section-3-1',3:'section-3-2',4:'section-4',
         5:'section-4-2',6:'section-5-1',7:'section-5-2',8:'section-7',
         9:'section-7-3-3',10:'section-3-3',11:'section-8',12:'section-6',
         13:'section-7-4',14:'section-12',15:'section-9-1',16:'section-9-3',
         17:'section-9-5',18:'section-1-2-9'}
answers={};aliases={}
for n in range(1,19):
    chunk=between(f'question-{n}',f'question-{n+1}' if n<18 else 'implementation')
    answers[n]=re.search(r'\*\*精炼口述\*\*\n\n(.*?)\n\n\*\*对应计划与实现\*\*',chunk,re.S)[1]
    aliases[n]=re.findall(r'<a id="([^"]+)"',chunk)

plan=between('plan','interview')
plan=plan.replace('容量、路由和主键分别见[追问 2.1](#question-1)、[2.2](#question-2)、[2.3](#question-3)，维护演练见[2.18](#question-18)。',
                  '容量、路由、主键和维护演练见[分片与数据模型](#section-3)，各项内容均先给精炼口述，再展开详细方案。')
plan=plan.replace('迁移与基线分别见[追问 2.4](#question-4)、[2.5](#question-5)，具体操作见[停写迁移手册](#section-10)。',
                  '迁移与基线见[历史迁移](#section-4)，具体操作见该专题内的[停写迁移手册](#section-10)。')
plan=plan.replace('双写与同步见[追问 2.6—2.7](#question-6)，校验、修复、恢复和配置见[2.8—2.14](#question-8)。',
                  '详细说明见[双写与同步](#section-5)、[差异处理](#section-6)、[旧表与新表校验](#section-7)、[新表与 ES 校验](#section-8)和[进度与配置](#section-12)。')
plan=plan.replace('历史覆盖与灰度回退见[追问 2.15](#question-15)、[2.16](#question-16)。',
                  '历史覆盖、灰度和回退见[查询切换专题](#section-9)。')
plan=plan.replace('具体见[追问 2.17](#question-17)。','具体见[关闭旧表写入](#section-9-5)。')

functions='''<a id="functions"></a>
<a id="interview"></a>
<a id="section-1-2"></a>
<a id="implementation"></a>
<a id="section-1-2-6"></a>

## 2. 功能详解

按照改造计划的执行顺序展开。每项功能先给“面试精炼口述”，再给“详细方案”；具体流程、代码、异常处理和验证边界都放在对应功能内，不再分成追问章和实现章。

'''
functions+=renumber(between('section-3','section-4'))+'\n\n'
functions+=renumber(between('section-4','section-5'))+'\n\n'
manual=renumber(between('section-10','section-12'))
manual=re.sub(r'^##### (步骤 \d+：)',r'###### \1',manual,flags=re.M)
functions+=manual+'\n\n'
for a,b in [('section-5','section-6'),('section-6','section-7'),('section-7','section-8'),('section-8','section-9'),('section-9','section-10'),('section-12','supplement')]:
    functions+=renumber(between(a,b))+'\n\n'

# Add each oral answer immediately after its corresponding heading, retaining old alias IDs.
for n,target in targets.items():
    start=functions.index(f'<a id="{target}"></a>')
    heading=re.search(r'^#{3,6} .+$',functions[start:],re.M)
    assert heading, target
    pos=start+heading.end()
    insert='\n\n**面试精炼口述**\n\n'+answers[n]+'\n\n**详细方案**'
    functions=functions[:pos]+insert+functions[pos:]
    tag=f'<a id="{target}"></a>'
    alias_tags='\n'.join(f'<a id="{x}"></a>' for x in aliases[n])
    functions=functions.replace(tag,tag+'\n'+alias_tags,1)

main_answers={
 'section-3':'消费记录按商户号路由到 2 个库、每库 16 张表。新表主键采用“雪花算法 + 基因法”，固定 2 位后缀携带分片编号；新旧表通过业务编号关联。迁移、业务读写和校验必须使用相同路由、身份及字段比较口径，容量选型还要结合商户倾斜和演练结果。',
 'section-5':'恢复业务后先提交旧表，再尝试写新表；接口成败只看旧表提交结果，新表失败交自动校验发现并进入修复流程。新表提交产生的日志由 Canal 异步同步到 ES，文档 ID 使用新表主键。业务双写与 ES 同步各有职责，不能把这条链路描述成一个原子事务。',
 'section-9':'先确认 ES 校验覆盖历史范围，自动校验达到连续 2 周未发现数据差异，再灰度切换列表查询。切换后继续双写和校验；所有旧表业务切完并继续观察通过，才短暂停写，完成最终检查，关闭旧表写入。旧表停写后退出查询回退，ES 同步和新表、ES 校验继续运行。',
 'section-10':'首次迁移由运维负责停写和应用停启，DBA 负责事务检查、保存基线、导入及历史验收。验收通过后保持业务停写，上线并验证双写、同步和校验，再恢复业务。每一步保存结果；遇到实例未停完、事务未结束、基线缺失或验收失败，就停在当前步骤处理。'
}
for target,answer in main_answers.items():
    start=functions.index(f'<a id="{target}"></a>')
    h=re.search(r'^#{3,6} .+$',functions[start:],re.M)
    pos=start+h.end()
    functions=functions[:pos]+'\n\n**面试精炼口述**\n\n'+answer+'\n\n**详细方案**'+functions[pos:]

supp=renumber(between('supplement'))
supp=supp.replace('收录不作为改造功能单独追问的基础知识','收录不属于改造功能本身的基础知识')
maintenance='''维护时先更新第 2 章对应功能的详细方案，再同步该功能开头的面试精炼口述、第 1 章改造计划和文档大纲。其他知识点及验证限制放在第 3 章。

大纲和正文导航使用当前文档的绝对路径与行号定位。正文增删、章节移动或文件改名后，须重新生成全部导航链接并核对目标标题；仅检查 HTML 锚点名称不能证明阅读器可跳转。用户确认的结构和业务规则同步记录到同目录 README。'''
supp=re.sub(r'(### 3\.6 文档维护规则\n\n).*',lambda m:m[1]+maintenance,supp,flags=re.S)
body=plan+'\n\n'+functions.strip()+'\n\n'+supp

# Fix numeric link labels by looking at their actual target heading, not by blind replacement.
def get_targets(text):
    pending=[]; found={}
    for index,line in enumerate(text.splitlines(),1):
        a=re.fullmatch(r'<a id="([^"]+)"></a>',line)
        if a: pending.append(a[1]);continue
        h=re.match(r'^(#{2,6}) (\d+(?:\.\d+)*)(?:\.)? (.+)$',line)
        if h:
            for ident in pending: found[ident]=(index,h[2],h[3],len(h[1]))
            pending=[]
        elif line.strip():pending=[]
    return found

body=re.sub(r'\n{3,}','\n\n',body)
info=get_targets(body)
def label_fix(m):
    label,a=m[1],m[2]
    if a in info:
        label=re.sub(r'(?<![\d.])\d+(?:\.\d+)+(?![\d.])',info[a][1],label)
    return '['+label+'](#'+a+')'
body=re.sub(r'\[([^\]\n]+)\]\(#([^\)]+)\)',label_fix,body)

# Headings up to the direct subfunctions are listed, with deeper SQL steps remaining in body.
toc=['<a id="section-outline"></a>','','## 文档大纲','']
pending=[]
for line in body.splitlines():
    a=re.fullmatch(r'<a id="([^"]+)"></a>',line)
    if a:pending.append(a[1]);continue
    h=re.match(r'^(#{2,4}) (\d+(?:\.\d+)*)(?:\.)? (.+)$',line)
    if h and pending:
        # Main chapters and subchapters, plus directly narrated subfunctions.
        selected=len(h[1])<=3 or pending[0] in targets.values() or pending[0]=='section-10'
        if selected:
            label=h[2]+('.' if len(h[1])==2 else '')+' '+h[3]
            toc.append('  '*(len(h[1])-2)+'- ['+label+'](#'+pending[0]+')')
        pending=[]
    elif line.strip():pending=[]

draft=src.splitlines()[0]+'\n\n'+'\n'.join(toc)+'\n\n'+body.rstrip()+'\n'
info=get_targets(draft)
info['section-outline']=(5,'','文档大纲',2)
absolute=p.as_posix()
def local_link(m):
    label,a=m[1],m[2]
    assert a in info, a
    return f'[{label}](<{absolute}:{info[a][0]}>)'
out=re.sub(r'\[([^\]\n]+)\]\(#([^\)]+)\)',local_link,draft)
assert out.count('\n')==draft.count('\n')

old_ids=set(re.findall(r'<a id="([^"]+)"',src)); new_ids=re.findall(r'<a id="([^"]+)"',out)
assert old_ids.issubset(new_ids),old_ids-set(new_ids)
assert len(new_ids)==len(set(new_ids))
assert sorted(re.findall(r'^```[^\n]*\n.*?^```',src,re.M|re.S))==sorted(re.findall(r'^```[^\n]*\n.*?^```',out,re.M|re.S))
for h in re.finditer(r'^### 2\.\d+ .+\n\n([^\n]+)',out,re.M):
    assert h[1]=='**面试精炼口述**',h[0]
lines=out.splitlines()
for path,line in re.findall(r'\]\(<(.+?):(\d+)>\)',out):
    assert path==absolute and lines[int(line)-1].startswith('#'),(path,line)
assert not re.search(r'\]\(#',out)
p.write_text(out,encoding='utf-8')

r=readme.read_text(encoding='utf-8-sig')
start=r.index('## 当前文档结构'); end=r.index('## 历史确认记录',start)
current='''## 当前文档结构（2026-09-20 最新确认）

用户确认按“文档大纲 → 第 1 章改造计划 → 第 2 章功能详解 → 第 3 章补充”组织统一方案。每项功能开头必须先给面试精炼口述，再展开详细方案；不再把面试追问与具体实现拆为独立章节。

- 第 2 章按业务链路分为 8 个专题：分片与数据模型、历史迁移、业务双写与 Canal 同步、增量校验与差异处理、旧表与新表增量校验、新表与 ES 增量校验、ES 验收与查询切换、动态同步起点与配置存放。
- 原 18 项追问的口述内容归入对应功能或子功能开头，详细流程、SQL、代码、异常处理和验证边界紧随其后。
- 停写迁移操作手册并入历史迁移专题的 2.2.5，仍由运维和 DBA 执行；动态进度与配置见 2.8。
- 索引原理、表变大后的性能影响、ROW 日志示例、待确认事项与技术栈版本归入第 3 章补充。
- 大纲面向 Codex 阅读器，改用绝对文件路径加目标标题行号，正文导航同步更新，不再依赖自定义 HTML id 进行点击跳转。旧锚点仅保留为兼容标记；正文增删或文件改名后须重新生成链接。
- 已核对链接文件路径和标题行号。当前工具可请求 Codex 打开指定文件及行，但不能操作原生文档阅读器进行点击验收；因此尚未确认阅读器中的实际点击效果，不将静态校验表述为点击验证通过。
- 本节取代下方历史记录中的章节位置和编号要求；已确认的业务规则、字段职责、切换条件和验证边界继续有效。保留原有代码片段，不声称已编译或运行验证。

'''
r=r[:start]+current+r[end:]
readme.write_text(r,encoding='utf-8')
report={'main_functions':8,'oral_answers_relocated':18,'oral_sections_total':out.count('**面试精炼口述**'),'code_blocks_preserved':17,'old_anchors_preserved':len(old_ids),'links_checked':len(re.findall(r'\]\(<.+?:\d+>\)',out)),'actual_reader_click_test':'not available','function_start_line':info['functions'][0],'plan_line':info['plan'][0]}
(root/'tmp/consume-function-regroup-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
