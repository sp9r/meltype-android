"""Summarize Meltype's on-device usage log to find what to improve.

The log holds typed text, so it stays out of Git. Pull it to a local folder first:

    adb shell run-as io.github.sp9r.meltype cat files/glide_eval_log.jsonl > usage.jsonl
    python tools/log_report.py usage.jsonl

Reports: event counts, commits kept as proposed vs. fixed, each fix (what Meltype proposed,
what was committed, which actions), language-mode switches, and words typed in English.
"""
import collections
import json
import sys


def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError:
                pass
    return rows


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    rows = load(sys.argv[1])
    print(f"events: {len(rows)}")
    for k, v in collections.Counter(r.get("type") for r in rows).most_common():
        print(f"  {k}: {v}")

    commits = [r for r in rows if r.get("type") == "commit" and "auto" in r]
    ja = [r for r in commits if not r.get("en")]
    fixed = [r for r in ja if r.get("auto") != r.get("committed") or "lang:" in r.get("actions", "")]
    if ja:
        kept = len(ja) - len(fixed)
        print(f"\nJapanese-mode commits: {len(ja)}  kept as proposed: {kept} ({kept * 100 // len(ja)}%)")
    print(f"commits by trigger: {dict(collections.Counter(r.get('via') for r in commits))}")

    print("\n== fixes (proposed -> committed, actions) ==")
    for r in fixed[-200:]:
        print(f"  {r.get('auto')}  ->  {r.get('committed')}   [{r.get('actions')}]  raw={r.get('rawInput')}")

    print("\n== clause language flips (often an English word misread as Japanese or the reverse) ==")
    flips = collections.Counter()
    for r in commits:
        for a in r.get("actions", "").split():
            if a.startswith("lang:"):
                flips[a[5:]] += 1
    for k, v in flips.most_common(50):
        print(f"  {v:3d}  {k}")

    print("\n== mode switches (text on screen at the switch) ==")
    for r in [r for r in rows if r.get("type") == "mode"][-50:]:
        print(f"  {r.get('detail')}  '{r.get('preview')}'")

    print("\n== picks per clause (reading -> chosen) ==")
    picks = collections.Counter()
    for r in commits:
        for a in r.get("actions", "").split():
            if a.startswith(("pick[", "cycle:")):
                picks[a] += 1
    for k, v in picks.most_common(50):
        print(f"  {v:3d}  {k}")


if __name__ == "__main__":
    main()
