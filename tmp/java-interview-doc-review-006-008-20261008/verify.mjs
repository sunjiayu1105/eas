import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const reviewDir = path.dirname(fileURLToPath(import.meta.url));
const workspace = path.resolve(reviewDir, '../..');
const docDir = path.join(workspace, '002_消费记录分库分表、双写与ES 改造（15）');
const read = filename => fs.readFileSync(filename, 'utf8').replace(/\r\n/g, '\n');
const names = fs.readdirSync(docDir).filter(name => name.endsWith('.md'));
const targetNames = ['006_', '007_', '008_'].map(prefix => {
  const found = names.filter(name => name.startsWith(prefix));
  assert.equal(found.length, 1, `Expected one document for ${prefix}`);
  return found[0];
});
const expectedPrefixes = ['005_', '006_', '007_', '008_', '010_', '030_',
  '999_Canal Server、', '999_掘金文章', 'README.md'];
const changed = names.filter(name =>
  read(path.join(docDir, name)) !== read(path.join(reviewDir, 'before', name)));
assert.equal(changed.length, expectedPrefixes.length);
for (const name of changed) {
  assert(expectedPrefixes.some(prefix => name.startsWith(prefix)), `Unexpected change: ${name}`);
}

const oralLengths = {};
let jsonCount = 0;
for (const name of targetNames) {
  const text = read(path.join(docDir, name));
  const rules = text.indexOf('## 文档维护与已确认规则');
  const oral = text.indexOf('## 面试口述精炼版本');
  const detail = text.indexOf('## 详细方案');
  assert(rules > 0 && oral > rules && detail > oral, `Section order: ${name}`);
  assert.equal(text.slice(rules, oral).match(/^## /gm).length, 1);
  const oralText = text.slice(oral, detail);
  assert.equal(oralText.match(/^### /gm).length, 5, `Oral points: ${name}`);
  oralLengths[name] = oralText.length;
  assert(text.includes('以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。'));
  assert(text.includes('未连接') || text.includes('未验证'));

  let fence = null;
  let body = [];
  for (const line of text.split('\n')) {
    const boundary = line.match(/^(`{3,}|~{3,})(\w*)\s*$/);
    if (boundary && !fence) {
      fence = { marker: boundary[1], language: boundary[2] };
      body = [];
    } else if (boundary && fence && boundary[1] === fence.marker) {
      if (fence.language === 'json') {
        JSON.parse(body.join('\n'));
        jsonCount++;
      }
      fence = null;
    } else if (fence) {
      body.push(line);
    }
  }
  assert.equal(fence, null, `Unclosed code fence: ${name}`);
}

const contracts = targetNames.slice(1).map(name => {
  const text = read(path.join(docDir, name));
  return JSON.parse(text.match(/(?:```|~~~)json\n([\s\S]*?)\n(?:```|~~~)/)[1]);
});
assert.deepEqual(contracts[0], contracts[1], '007/008 event contracts differ');

let referenceCount = 0;
for (const name of names) {
  const text = read(path.join(docDir, name));
  for (const match of text.matchAll(/\]\(<(D:\/[^>]+\.md):(\d+)>\)/g)) {
    const filename = path.basename(match[1]);
    if (!targetNames.includes(filename)) continue;
    const lines = read(match[1]).split('\n');
    const target = lines[Number(match[2]) - 1];
    assert(target && /^#{1,6} /.test(target), `Invalid reference in ${name}: ${match[0]}`);
    referenceCount++;
  }
}

const sourceDir = path.join(reviewDir, 'sources');
const producer = read(path.join(sourceDir, 'CanalRocketMQProducer.java'));
const starter = read(path.join(sourceDir, 'CanalMQStarter.java'));
const utils = read(path.join(sourceDir, 'MQMessageUtils.java'));
const flat = read(path.join(sourceDir, 'FlatMessage.java'));
assert(producer.includes('callback.commit()') && producer.includes('callback.rollback()'));
assert(!producer.includes('getSendStatus('), 'Recheck send-status finding');
assert(starter.includes('(short) 1001') && starter.includes('canalServer.ack(clientIdentity, batchId)'));
assert(utils.includes('rowChangeBuilder.clearRowDatas()') && utils.includes('rowChangeBuilder.addRowDatas(rowData)'));
assert(!flat.includes('logfileName') && !flat.includes('rowOrdinal'));

const hashes = Object.fromEntries(fs.readdirSync(sourceDir).map(name => [name,
  crypto.createHash('sha256').update(fs.readFileSync(path.join(sourceDir, name))).digest('hex')]));
const report = { changed, oralLengths, jsonCount, referenceCount, sourceHashes: hashes,
  runtimeValidation: 'Not performed; document structure and version source review only.' };
fs.writeFileSync(path.join(reviewDir, 'verification.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
