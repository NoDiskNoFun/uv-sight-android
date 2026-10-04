#!/usr/bin/env python3
"""Language packs: build the English template from the code, keep the other packs in step, report.

    python3 tools/lang_check.py            # rewrite lang_en.txt, add missing lines to the other packs, print the report
    python3 tools/lang_check.py --check    # report only (for CI); never fails the build

A pack is app/src/main/assets/lang/lang_<code>.txt with lines "English text = Translation".
Keys are collected from every tr("...") in the Kotlin sources, grouped by source file.
"""
import re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LANG = ROOT / "app" / "src" / "main" / "assets" / "lang"
SOURCES = [ROOT / "app" / "src" / "main" / "java", ROOT / "core" / "src" / "main" / "kotlin"]
KEY_RE = re.compile(r'\btr\(\s*"((?:[^"\\]|\\.)*)"')
PH_RE = re.compile(r"\{[A-Za-z0-9_]+\}")

def esc(s): return s.replace("\n", "\\n")

def collect():
    """[(file, [keys in order])] with duplicates removed across files."""
    seen = set(); groups = []
    for src in SOURCES:
        for f in sorted(src.rglob("*.kt")):
            keys = []
            for m in KEY_RE.finditer(f.read_text()):
                k = m.group(1).replace('\\"', '"').replace("\\n", "\n").replace("\\$", "$")
                if " = " in k: print(f"WARNING: text contains the separator ' = ', it cannot be translated: {k}")
                if k and k not in seen: seen.add(k); keys.append(k)
            if keys: groups.append((f.relative_to(ROOT).as_posix(), keys))
    return groups

def read_pack(p):
    name = None; table = {}
    for raw in p.read_text(encoding="utf-8").splitlines():
        line = raw.rstrip()
        if line.startswith("# name"): name = line.split("=", 1)[1].strip() if "=" in line else None
        if not line or line.startswith("#"): continue
        i = line.find(" = ")
        if i <= 0: continue
        table[line[:i].strip().replace("\\n", "\n")] = line[i + 3:].strip().replace("\\n", "\n")
    return name, table

def write_pack(p, name, groups, table, english):
    lines = [f"# name = {name}"]
    if english:
        lines.append("# The English pack is the template: copy it to lang_<code>.txt, put the language's name above,")
        lines.append("# and fill in the right side of every line. {placeholders} stay as they are, \\n is a line break.")
        lines.append("# An empty right side falls back to English.")
    for (f, keys) in groups:
        lines.append(""); lines.append(f"# --- {f}")
        for k in keys:
            v = k if english else table.get(k.strip(), "")
            lines.append(f"{esc(k)} = {esc(v)}")
    p.write_text("\n".join(lines) + "\n", encoding="utf-8")

def main():
    check = "--check" in sys.argv
    groups = collect()
    keys = [k for _, ks in groups for k in ks]
    LANG.mkdir(parents=True, exist_ok=True)
    if not check: write_pack(LANG / "lang_en.txt", "English", groups, {}, True)
    report = [f"Language packs: {len(keys)} texts in the English template"]
    for p in sorted(LANG.glob("lang_*.txt")):
        code = p.stem.removeprefix("lang_")
        if code == "en": continue
        name, table = read_pack(p)
        tk = {k.strip(): k for k in keys}
        done = sum(1 for k in keys if table.get(k.strip()))
        missing = [k for k in keys if not table.get(k.strip())]
        stale = [k for k in table if k not in tk]
        bad = [k for k in keys if table.get(k.strip()) and set(PH_RE.findall(k)) != set(PH_RE.findall(table[k.strip()]))]
        for k in bad: table[k] = table[k.strip()]
        report.append(f"- {name or code} ({code}): {done} of {len(keys)} translated, {len(missing)} missing" + (f", {len(stale)} no longer used" if stale else "") + (f", {len(bad)} with placeholder mismatch" if bad else ""))
        for k in bad: report.append(f"    placeholders differ: {k} = {table[k]}")
        if not check: write_pack(p, name or code, groups, table, False)
    print("\n".join(report))

if __name__ == "__main__":
    main()
