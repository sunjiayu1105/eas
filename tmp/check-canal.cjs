const fs=require('fs'),p=require('path'),cp=require('child_process');
const root='D:/000/sources/eas',s=JSON.parse(fs.readFileSync(p.join(root,'tmp/canal-restructure-state.json'))),before=JSON.parse(fs.readFileSync(p.join(s.backup,'before.json')));
const norm=t=>t.replace(/\r\n/g,'\n'), remap=t=>{for(const [a,b]of Object.entries(s.mapping))t=t.split(a).join(b);return t};
const names=[...Object.values(s.mapping),s.newName],errors=[],broken=[];let json=0;
for(const n of names){const f=p.join(s.dir,n),t=norm(fs.readFileSync(f,'utf8'));if(t.split('\n')[0]!=='# '+n.slice(0,-3))errors.push('H1 '+n);
let fenced=false,nums=[];for(const l of t.split('\n')){if(l.startsWith('```')){fenced=!fenced;continue}if(fenced)continue;const m=l.match(/^(#{2,}) (\d+(?:\.\d+)*) /);if(m){let a=m[2].split('.').map(Number),lev=m[1].length-2;if(a.length!==lev+1||a[lev]!==((nums[lev]||0)+1)||a.slice(0,-1).some((v,i)=>v!==nums[i]))errors.push('heading '+n+' '+l);nums=a;}}
if(fenced)errors.push('fence '+n);for(const m of t.matchAll(/```json\n([\s\S]*?)\n```/g)){try{JSON.parse(m[1]);json++}catch(e){errors.push('JSON '+n+' '+e.message)}}
for(const m of t.matchAll(/\]\(<([^>]+)>\)/g)){let target=m[1].split('#')[0];if(target.startsWith('.')){let f2=p.resolve(p.dirname(f),target);if(!fs.existsSync(f2))broken.push({file:n,target})}}
if(/<br\s*\/?\s*>|`r`n/.test(t))errors.push('format '+n);
}
for(let i=23;i<=26;i++){let old=s.names[i],n=s.mapping[old],expected=remap(before[p.join(s.dir,old)]).replace('# '+old.slice(0,-3),'# '+n.slice(0,-3));if(norm(expected)!==norm(fs.readFileSync(p.join(s.dir,n),'utf8')))errors.push('out-of-scope body '+n)}
const outside=[];for(const [f,t]of Object.entries(before)){if(p.dirname(f)===s.dir&&Object.keys(s.mapping).includes(p.basename(f)))continue;if(fs.existsSync(f)){let now=fs.readFileSync(f,'utf8');if(norm(now)!==norm(t)){outside.push(p.relative(root,f));if(norm(now)!==norm(remap(t)))errors.push('outside nonlink '+f)}}}
const git=(...a)=>cp.execFileSync('git',['-c','safe.directory='+root,...a],{cwd:root,encoding:'utf8'});
console.log(JSON.stringify({files:names.length,json,errors,broken,outside,staged:git('diff','--cached','--name-only'),branch:git('branch','--show-current').trim(),remote:git('remote','get-url','origin').trim()},null,2));
