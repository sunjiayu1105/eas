const fs=require('fs'),cp=require('child_process'),path=require('path'),os=require('os');
const root='D:/000/sources/eas',file='002_消费记录分库分表、双写与ES 改造（15）/018_ES改造_Canal Server_消息格式与字段边界.md';
const git=(args,input,env)=>cp.execFileSync('git',['-c','safe.directory='+root,...args],{cwd:root,input,encoding:'utf8',env});
const now=fs.readFileSync(path.join(root,file),'utf8').replace(/\r\n/g,'\n'),head=git(['show','HEAD:'+file],undefined,process.env).replace(/\r\n/g,'\n');
const hs=head.indexOf('## 6 复核记录\n'),he=head.indexOf('## 7 参考来源\n'),ns=now.indexOf('## 6 复核记录\n'),ne=now.indexOf('## 7 参考来源\n');
if([hs,he,ns,ne].some(x=>x<0))throw Error('review anchors missing');
const newReview=now.slice(ns,ne).trim();
const staged=now.slice(0,ns)+head.slice(hs,he).trimEnd()+'\n\n'+newReview+'\n\n'+now.slice(ne);
const index=path.join(os.tmpdir(),'review-018-logical.index'),env={...process.env,GIT_INDEX_FILE:index};
try{fs.rmSync(index,{force:true});fs.rmSync(index+'.lock',{force:true});git(['read-tree','HEAD'],undefined,env);const hash=git(['hash-object','-w','--stdin'],staged,env).trim();git(['update-index','--add','--cacheinfo','100644',hash,file],undefined,env);git(['diff','--cached','--check'],undefined,env);const tree=git(['write-tree'],undefined,env).trim(),parent=git(['rev-parse','HEAD'],undefined,env).trim(),commit=git(['commit-tree',tree,'-p',parent,'-m','docs: improve FlatMessage explanation flow'],undefined,env).trim();git(['update-ref','refs/heads/master',commit,parent]);console.log(commit);}finally{fs.rmSync(index,{force:true});fs.rmSync(index+'.lock',{force:true});}
