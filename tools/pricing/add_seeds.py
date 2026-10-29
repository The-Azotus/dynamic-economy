"""Adds seed entries to a default_prices mod file.

Usage: add_seeds.py <market dir> <mod file name> <spec file>
Spec lines: "<item id> <price> <class>"; blank lines and # comments ignored.
Existing entries for the same item are replaced. Creates the file (with required_mods) if missing.
"""
import json
import os
import sys

market, name, spec = sys.argv[1], sys.argv[2], sys.argv[3]
path = os.path.join(market, name + ".json")
nl = "\n"
if os.path.exists(path):
    raw = open(path, encoding="utf-8", newline="").read()
    nl = "\r\n" if "\r\n" in raw else "\n"
    data = json.loads(raw)
else:
    data = {"required_mods": [name], "entries": []} if name != "minecraft" else {"entries": []}
entries = {e["item"]: e for e in data["entries"]}
added = replaced = 0
for line in open(spec, encoding="utf-8"):
    line = line.split("#", 1)[0].strip()
    if not line:
        continue
    item, price, cls = line.split()
    if ":" not in item:
        item = name + ":" + item
    if item in entries:
        replaced += 1
    else:
        added += 1
    entries[item] = {"item": item, "base_price": int(price), "class": cls}
data["entries"] = sorted(entries.values(), key=lambda e: e["item"])
open(path, "w", encoding="utf-8", newline="").write((json.dumps(data, indent=2, ensure_ascii=False) + "\n").replace("\n", nl))
print(f"{name}: {added} added, {replaced} replaced, {len(entries)} total")
