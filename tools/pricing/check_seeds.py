"""Checks default_prices seed files against the pack's item list: known items, valid classes, no duplicates."""
import csv
import glob
import json
import os
import sys

root = sys.argv[1]
items_csv = sys.argv[2]
known = {row["item_id"] for row in csv.DictReader(open(items_csv, encoding="utf-8-sig"))}
classes = {os.path.basename(p)[:-5] for p in glob.glob(os.path.join(root, "src/main/resources/data/dynamic_economy/market_classes/*.json"))}
seen = {}
problems = 0
total = 0
for path in sorted(glob.glob(os.path.join(root, "src/main/resources/default_prices/data/dynamic_economy/market/*.json"))):
    name = os.path.basename(path)
    data = json.load(open(path, encoding="utf-8"))
    if name != "minecraft.json" and not data.get("required_mods"):
        print(f"{name}: no required_mods"); problems += 1
    for e in data["entries"]:
        total += 1
        item = e["item"]
        if item not in known:
            print(f"{name}: unknown item {item}"); problems += 1
        if e.get("class") and e["class"] not in classes:
            print(f"{name}: unknown class {e['class']} on {item}"); problems += 1
        if not isinstance(e.get("base_price"), int) or e["base_price"] <= 0:
            print(f"{name}: bad price on {item}"); problems += 1
        if item in seen:
            print(f"{name}: {item} also in {seen[item]}"); problems += 1
        seen[item] = name
        mod = item.split(":")[0]
        if name != "minecraft.json" and mod not in data.get("required_mods", []):
            print(f"{name}: {item} is from {mod}, not a required mod"); problems += 1
print(f"{total} entries, {problems} problems")
