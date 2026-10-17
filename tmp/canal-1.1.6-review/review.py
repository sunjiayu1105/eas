from pathlib import Path
import hashlib
import json
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

ROOT = Path(r'D:\000\sources\eas')
DOCS = ROOT / '002_消费记录分库分表、双写与ES 改造（15）'
AUDIT = ROOT / 'tmp' / 'canal-1.1.6-review'
SOURCE = AUDIT / 'source' / 'canal-canal-1.1.6-hotfix-1'

if sys.argv[1] == 'snapshot':
    manifest = {}
    for path in [ROOT / 'AGENTS.md', *DOCS.glob('*.md')]:
        data = path.read_bytes()
        relative = str(path.relative_to(ROOT))
        saved = AUDIT / 'before' / relative
        if saved.exists():
            raise RuntimeError(f'Snapshot already exists: {saved}')
        saved.parent.mkdir(parents=True, exist_ok=True)
        saved.write_bytes(data)
        manifest[relative] = hashlib.sha256(data).hexdigest()
    (AUDIT / 'before-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'Preserved current contents of {len(manifest)} documents.')
elif sys.argv[1] == 'read':
    for prefix in sys.argv[2:]:
        for path in DOCS.glob(prefix + '*.md'):
            print('\nFILE:', path.name)
            print(path.read_text(encoding='utf-8-sig'))
elif sys.argv[1] == 'source':
    specifications = [
        ('AdapterProcessor.java', [(130, 162), (168, 244)]),
        ('CanalTCPConsumer.java', [(35, 78)]),
        ('TCPConstants.java', [(1, 70)]),
        ('ESSyncConfig.java', [(145, 195)]),
        ('ES7xTemplate.java', [(85, 126), (350, 407), (425, 486)]),
        ('ESConnection.java', [(459, 501)]),
        ('SimpleCanalConnector.java', [(66, 123), (280, 345), (430, 461)]),
        ('CanalServerWithEmbedded.java', [(360, 460)]),
    ]
    for name, spans in specifications:
        matches = [p for p in SOURCE.rglob(name) if '/src/main/' in p.as_posix()]
        if name == 'ESConnection.java':
            matches = [p for p in matches if '/es7x/' in p.as_posix()]
        for path in matches:
            print('\nSOURCE:', path.relative_to(SOURCE))
            lines = path.read_text(encoding='utf-8-sig').splitlines()
            for start, end in spans:
                for number in range(start, min(end, len(lines)) + 1):
                    print(f'{number}:{lines[number - 1]}')
