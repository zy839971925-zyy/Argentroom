#!/usr/bin/env python3
"""Drop `import com.argentroom.app.<pkg>.*` lines whose package exports no name the file uses.
Conservative: a name counts as used when it appears as a token anywhere in the file."""
import re, pathlib, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import restructure as r
ROOT = r.ROOT; BASE = r.BASE
SRC = ROOT / "app/src/main/java/com/argentroom/app"
exports = {}
for p in SRC.rglob("*.kt"):
    t = p.read_text()
    m = re.search(r"^package ([\w.]+)", t, re.M)
    if not m: continue
    names = r.top_level_symbols(t)
    jvm = re.search(r'@file:JvmName\("(\w+)"\)', t)
    names.add(jvm.group(1) if jvm else p.stem + "Kt")
    exports.setdefault(m.group(1), set()).update(names)
exports.setdefault(BASE, set()).add("R")
star = re.compile(rf"^import ({re.escape(BASE)}(?:\.\w+)?)\.\*\s*\n", re.M)
removed = 0
for p in (ROOT / "app/src").rglob("*.kt"):
    t = p.read_text()
    body_tokens = set(re.findall(r"\b\w+\b", star.sub("", t)))
    def repl(m):
        global removed
        pkg = m.group(1)
        if pkg not in exports: return m.group(0)
        if exports[pkg] & body_tokens: return m.group(0)
        removed += 1
        return ""
    n = star.sub(repl, t)
    n = re.sub(r"(^package [\w.]+\n)\n\n+", r"\1\n", n, flags=re.M)
    if n != t: p.write_text(n)
print("removed", removed)
