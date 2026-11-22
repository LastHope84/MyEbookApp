import base64, gzip, hashlib, json, pathlib, subprocess, zlib
root=pathlib.Path('.')
data=json.loads(gzip.decompress(base64.b64decode(''.join(p.read_text() for p in sorted(root.glob('journal-patch-*.b64'))))))
patch=base64.b64decode(data['patch'])
if hashlib.sha256(patch).hexdigest() != 'f6d4c14396340e0d43f92f5b81d926da12f5fa52d30e8b79e1588ce466784657': raise SystemExit('Patch checksum mismatch')
subprocess.run(['git','apply','--check','-'],input=patch,check=True)
subprocess.run(['git','apply','-'],input=patch,check=True)
paths=json.loads((root/'tests/folder_overlay_paths.json').read_text())
obj={name:(root/name).read_text() for name in paths}
raw=json.dumps(obj,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()
encoded=base64.b64encode(zlib.compress(raw,9)).decode()
d=root/'.folder-preview-input'
for path in d.glob('*.part'): path.unlink()
for i in range(0,len(encoded),6000): (d/f'{i//6000:03}.part').write_text(encoded[i:i+6000])
d=root/'.translation-build-input'
for entry in json.loads((d/'manifest.json').read_text()):
    encoded=base64.b64encode(zlib.compress((root/entry['path']).read_bytes(),9)).decode()
    chunks=[encoded[i:i+6000] for i in range(0,len(encoded),6000)]
    if len(chunks)!=len(entry['parts']): raise SystemExit('Translation part count mismatch')
    for name, chunk in zip(entry['parts'],chunks): (d/name).write_text(chunk)
(root/'journal-snapshot-manifest.json').write_text(json.dumps(data['manifest'],sort_keys=True))
