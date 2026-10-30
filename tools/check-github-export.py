#!/usr/bin/env python3
"""Check a source export without running cloud operations or reading user stores."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('folder', nargs='?', default=None)
parser.add_argument('--source-root')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
folder = Path(args.folder).resolve() if args.folder else (root/'github-ready/Priperi').resolve()
manifest = json.loads((folder/'EXPORT-MANIFEST.json').read_text())
spec=importlib.util.spec_from_file_location('export_rules', root/'tools/prepare-github.py')
rules=importlib.util.module_from_spec(spec);spec.loader.exec_module(rules)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
expected={'EXPORT-MANIFEST.json','docs/FILE-INDEX.md','EXPORT-REPORT.json'}
for entry in manifest['files']:
    rel=entry['path'];file=folder/rel;guide=folder/entry['guide']
    assert file.is_file() and not file.is_symlink(),rel
    assert sha(file)==entry['sha256'],'Payload mismatch: '+rel
    assert guide.is_file() and sha(guide)==entry['guideSha256'],'Missing/changed note: '+rel
    rules.assert_clean(rel,file.read_bytes())
    expected.update({rel,entry['guide']})
    if file.suffix=='.json' or file.name.endswith('.json.example'):
        json.loads(file.read_text())
    if file.suffix=='.xml':
        ET.parse(file)
    if file.suffix=='.py':
        compile(file.read_text(),str(file),'exec')
actual={str(p.relative_to(folder)) for p in folder.rglob('*') if p.is_file()}
assert not actual-expected,'Unexpected files: '+str(sorted(actual-expected))
assert len(manifest['files'])==manifest['payloadFileCount']==manifest['fileGuideCount']
assert (folder/'priperi/gradlew').stat().st_mode & 0o111,'Wrapper must be executable'
assert len(list((folder/'backend/supabase/migrations').glob('*.sql')))==17
assert set(rules.JAVA)=={p.stem for p in (folder/'priperi/app/src/main/java/com/priperi').glob('*.java')},'App file descriptions incomplete'
# All ordinary Markdown links must resolve within the exported folder.
for file in folder.rglob('*.md'):
    for target in re.findall(r'\]\(([^)]+)\)',file.read_text()):
        if re.match(r'[a-zA-Z][a-zA-Z0-9+.-]*:',target) or target.startswith('#'):
            continue
        target=target.split('#')[0].split('?')[0].strip('<>')
        if not target:
            continue
        # Templates are interpreted at their generated destination, not tools/.
        base=(folder if file.name=='README.md' else folder/'docs') if file.is_relative_to(folder/'tools/github-content') else file.parent
        resolved=(base/target).resolve()
        assert resolved.is_relative_to(folder),'Link leaves export: '+str(file.relative_to(folder))
        assert resolved.exists(),'Broken link in '+str(file.relative_to(folder))+': '+target
if args.source_root:
    source=Path(args.source_root).resolve()
    inventory={e['path']:e['sha256'] for e in manifest['sourceInventory']}
    found=set()
    for name in (*rules.ROOT_FILES,*rules.COMPONENTS):
        base=source/name
        for p in ([base] if base.is_file() else base.rglob('*')):
            if p.is_file():
                # rules.ROOT is this checker source's repository.
                if rules.allowed(p):
                    rel=str(p.relative_to(source));found.add(rel)
                    assert sha(p)==inventory.get(rel),'Source inventory mismatch: '+rel
    assert found==set(inventory),'Source file omitted'
report={'payloadFiles':manifest['payloadFileCount'],'perFileNotes':manifest['fileGuideCount'],'migrations':17,'payloadAndGuideHashes':'passed','sourceInventory':'passed' if args.source_root else 'not compared with original workspace','selectedCredentialPatterns':'passed','jsonXmlPythonSyntax':'passed','markdownLinks':'passed','userDataScope':'No user stores or raw evidence included; source tests use synthetic fixtures. Pattern checks are not a guarantee for arbitrary newly added content.'}
(folder/'EXPORT-REPORT.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report))
