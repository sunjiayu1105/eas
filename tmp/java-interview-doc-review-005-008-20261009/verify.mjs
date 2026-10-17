import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const reviewDir = path.dirname(fileURLToPath(import.meta.url));
const workspace = path.resolve(reviewDir, '../..');
const docDir = path.join(workspace, '002_消费记录分库分表、双写与ES 改造（15）');
const names = fs.readdirSync(docDir).filter(name => name.endsWith('.md'));
const read = filename => fs.readFileSync(filename, 'utf8').replace(/\r\n/g, '\n');
const before = new Map(names.map(name => [name, read(path.join(reviewDir, 'before', name))]));
const current = new Map(names.map(name => [name, read(path.join(docDir, name))]));
const changedNames = new Set(names.filter(name => before.get(name) !== current.get(name)));
const primaryNames = ['005_', '006_', '007_', '008_'].map(prefix => {
  const matches = names.filter(name => name.startsWith(prefix));
  assert.equal(matches.length, 1);
  return matches[0];
});

function headings(text) {
  const result = [];
  let fence = null;
  text.split('\n').forEach((line, index) => {
    const marker = line.match(/^(`{3,}|~{3,})/);
    if (marker) {
      if (!fence) fence = marker[1];
      else if (marker[1] === fence) fence = null;
    } else if (!fence && /^#{1,6} /.test(line)) {
      result.push({ text: line, line: index + 1 });
    }
  });
  return result;
}

// Repair navigation by matching the original target heading, preserving its meaning.
const previousReport = path.join(reviewDir, 'verification.json');
const repaired = !process.argv.includes('--repair-links') && fs.existsSync(previousReport)
  ? JSON.parse(fs.readFileSync(previousReport, 'utf8')).repaired : [];
if (process.argv.includes('--repair-links')) {
  for (const name of names) {
    const original = fs.readFileSync(path.join(docDir, name), 'utf8');
    const updated = original.replace(/\]\(<([^>]+\.md):(\d+)>\)/g, (match, filename, line) => {
      const targetName = path.posix.basename(filename.replace(/\\/g, '/'));
      if (!changedNames.has(targetName)) return match;
      const oldHeading = headings(before.get(targetName)).find(item => item.line === Number(line));
      assert(oldHeading, `Original link is not a heading: ${name}: ${match}`);
      const headingText = oldHeading.text.replace('同步链路故障后仍有 binlog 可追赶', '采集故障后仍有 binlog 可追赶');
      const matches = headings(current.get(targetName)).filter(item => item.text === headingText);
      assert.equal(matches.length, 1, `Ambiguous heading: ${targetName}: ${headingText}`);
      const targetPath = path.join(docDir, targetName).replace(/\\/g, '/');
      const replacement = `](<${targetPath}:${matches[0].line}>)`;
      if (replacement !== match) repaired.push({ document: name, target: targetName, from: Number(line), to: matches[0].line });
      return replacement;
    });
    if (updated !== original) fs.writeFileSync(path.join(docDir, name), updated);
    current.set(name, updated.replace(/\r\n/g, '\n'));
  }
}

const changed = names.filter(name => before.get(name) !== current.get(name));
const contentPrefixes = ['005_', '006_', '007_', '008_', '009_', '010_', '030_', 'README.md'];
for (const name of changed) {
  if (contentPrefixes.some(prefix => name.startsWith(prefix))) continue;
  const stripLineNumbers = text => text.replace(/\]\(<([^>]+\.md):\d+>\)/g, '](<$1>)');
  assert.equal(stripLineNumbers(current.get(name)), stripLineNumbers(before.get(name)), `Unexpected content change: ${name}`);
}

const oralLengths = {};
const contracts = [];
let jsonBlocks = 0;
for (const name of primaryNames) {
  const text = current.get(name);
  const rules = text.indexOf('## 文档维护与已确认规则');
  const oral = text.indexOf('## 面试口述精炼版本');
  const detail = text.indexOf('## 详细方案');
  assert(rules > 0 && oral > rules && detail > oral, `Section order: ${name}`);
  assert.equal(text.match(/^## 面试口述精炼版本$/gm).length, 1);
  const oralText = text.slice(oral, detail);
  const oldText = before.get(name);
  oralLengths[name] = {
    before: oldText.slice(oldText.indexOf('## 面试口述精炼版本'), oldText.indexOf('## 详细方案')).length,
    after: oralText.length,
    headings: headings(oralText).filter(item => item.text.startsWith('### ')).map(item => item.text.slice(4))
  };
  assert(text.includes('以下内容按照当前项目 `AGENTS.md` 项目级规则中规定的技术栈版本为准。'));
}

for (const name of changed) {
  let fence = null;
  let body = [];
  for (const line of current.get(name).split('\n')) {
    const marker = line.match(/^(`{3,}|~{3,})(\w*)\s*$/);
    if (marker && !fence) {
      fence = { marker: marker[1], language: marker[2] };
      body = [];
    } else if (marker && fence && marker[1] === fence.marker) {
      if (fence.language === 'json') {
        const parsed = JSON.parse(body.join('\n'));
        jsonBlocks++;
        if (primaryNames.includes(name) && parsed.eventId) contracts.push(parsed);
      }
      fence = null;
    } else if (fence) {
      body.push(line);
    }
  }
  assert.equal(fence, null, `Unclosed fence: ${name}`);
}
assert.equal(contracts.length, 2);
assert.deepEqual(contracts[0], contracts[1], '007/008 contracts differ');

let checkedReferences = 0;
for (const name of names) {
  for (const match of current.get(name).matchAll(/\]\(<([^>]+\.md):(\d+)>\)/g)) {
    const targetName = path.posix.basename(match[1].replace(/\\/g, '/'));
    if (!changed.includes(targetName)) continue;
    assert(fs.existsSync(match[1]), `Missing target in ${name}: ${match[1]}`);
    assert(headings(current.get(targetName)).some(item => item.line === Number(match[2])), `Invalid heading in ${name}: ${match[0]}`);
    checkedReferences++;
  }
}

const report = { changed, repaired, oralLengths, jsonBlocks, checkedReferences,
  validationScope: 'Document structure, event contract and navigation only. No application compilation, service connection, synchronization or fault injection.' };
fs.writeFileSync(path.join(reviewDir, 'verification.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
