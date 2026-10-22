#!/usr/bin/env python3
"""Fails if any Kotlin `external fun` has no exported Java_* symbol in every ABI's .so inside the APK.
Usage: check_jni.py <apk> <llvm-nm> <src-dir>..."""
import re, sys, subprocess, zipfile, tempfile, os, pathlib
apk, nm, srcs = sys.argv[1], sys.argv[2], sys.argv[3:]
def mangle(s): return s.replace('_', '_1').replace('.', '_').replace('/', '_')
need = {}  # jni symbol -> "Class.method"
for d in srcs:
    for f in pathlib.Path(d).rglob('*.kt'):
        t = f.read_text(encoding='utf-8')
        pkg = re.search(r'^package\s+([\w.]+)', t, re.M); pkg = pkg.group(1) if pkg else ''
        for m in re.finditer(r'\bexternal\s+fun\s+(\w+)', t):
            # owning class = last `class X` declared before the method
            cls = re.findall(r'\bclass\s+(\w+)', t[:m.start()])
            if not cls: continue
            fq = f'{pkg}.{cls[-1]}'
            need[f'Java_{mangle(fq)}_{mangle(m.group(1))}'] = f'{fq}.{m.group(1)}'
if not need: sys.exit('check_jni: no external funs found (wrong src dir?)')
bad = 0
with zipfile.ZipFile(apk) as z, tempfile.TemporaryDirectory() as tmp:
    abis = {}
    for n in z.namelist():
        if n.startswith('lib/') and n.endswith('.so'):
            abis.setdefault(n.split('/')[1], []).append(n)
    abis = {a: l for a, l in abis.items() if any(x.endswith('/libai-chat.so') for x in l)}
    if not abis: sys.exit('check_jni: libai-chat.so not found in APK')
    for abi, libs in sorted(abis.items()):
        syms = set()
        for n in libs:
            p = z.extract(n, tmp)
            out = subprocess.run([nm, '-D', '--defined-only', p], capture_output=True, text=True).stdout
            syms |= {l.split()[-1] for l in out.splitlines() if 'Java_' in l}
        miss = [v for k, v in need.items() if not any(s == k or s.startswith(k + '__') for s in syms)]
        print(f'{abi}: {len(need) - len(miss)}/{len(need)} native methods have symbols')
        for v in miss: print(f'  MISSING: {v}'); bad += 1
sys.exit(1 if bad else 0)
