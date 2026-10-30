const fs=require('fs'),p=require('path'),cp=require('child_process');
const root='D:/000/sources/eas',s=JSON.parse(fs.readFileSync(p.join(root,'tmp/canal-restructure-state.json'))),before=JSON.parse(fs.readFileSync(p.join(s.backup,'before.json')));
const norm=t=>t.replace(/\r\n/g,'\n'),rel=f=>p.relative(root,f).replace(/\\/g,'/');
const git=(a,input,env=process.env)=>cp.execFileSync('git',['-c','safe.directory='+root,...a],{cwd:root,encoding:'utf8',input,env});
const tracked=new Set(git(['ls-tree','-rz','--name-only','HEAD']).split('\0'));
const map=t=>{for(const[a,b]of Object.entries(s.mapping))t=t.split(a).join(b);return t};
const planned={},remove=[],preexisting=[],localOnly=[];
for(const[a,b]of Object.entries(s.mapping)){
 const old=p.join(s.dir,a),dest=p.join(s.dir,b),r=rel(old);let now=norm(fs.readFileSync(dest,'utf8'));
 if(!tracked.has(r))throw Error('untracked core '+r);
 const head=norm(git(['show','HEAD:'+r])),base=norm(before[old]);
 if(head!==base){preexisting.push(r);if(!a.startsWith('020_'))throw Error('unexpected prior core change '+r);
 const hist=head.match(/## 6 复核记录\n([\s\S]*?)(?=\n## 7 )/);if(!hist)throw Error('history missing');
 now=now.replace('## 6 复核记录\n','## 6 复核记录\n'+hist[1]+'\n');}
 planned[rel(dest)]=now;if(a!==b)remove.push(r);
}
planned[rel(p.join(s.dir,s.newName))]=norm(fs.readFileSync(p.join(s.dir,s.newName),'utf8'));
for(const[f,t]of Object.entries(before)){
 if(p.dirname(f)===s.dir&&Object.keys(s.mapping).includes(p.basename(f)))continue;
 if(!fs.existsSync(f)||norm(fs.readFileSync(f,'utf8'))===norm(t))continue;
 if(norm(fs.readFileSync(f,'utf8'))!==norm(map(t)))throw Error('nonlink outside change '+f);
 const r=rel(f);if(!tracked.has(r)){localOnly.push(r);continue}
 const head=norm(git(['show','HEAD:'+r])),next=map(head);if(next!==head)planned[r]=next;
}
const info={preexisting,localOnly,planned:Object.keys(planned),remove};
fs.writeFileSync(p.join(root,'tmp/canal-stage-plan.json'),JSON.stringify(info,null,2));
console.log(JSON.stringify(info,null,2));
if(process.argv.includes('--stage')){
 if(git(['diff','--cached','--name-only']).trim())throw Error('normal index not empty');
 const index=p.join(s.backup,'review.index'),env={...process.env,GIT_INDEX_FILE:index};
 git(['read-tree','HEAD'],undefined,env);
 for(const r of remove)git(['update-index','--force-remove','--',r],undefined,env);
 for(const[r,t]of Object.entries(planned)){const hash=git(['hash-object','-w','--stdin'],t,env).trim();git(['update-index','--add','--cacheinfo','100644',hash,r],undefined,env)}
 git(['diff','--cached','--check'],undefined,env);
 fs.writeFileSync(p.join(root,'tmp/canal-stage-index.txt'),index);
 console.log(git(['diff','--cached','--stat'],undefined,env));
}
