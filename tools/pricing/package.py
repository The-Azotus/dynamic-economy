"""Writes suggested prices into the default_prices mod files.

Usage: package.py <market dir> <pricing-work dir> [--commit]
Reads suggested.csv and chain_profits.txt from the pricing-work dir.
Rules (decided): items that convert back into a listed item get can_sell false; ore-stage items get can_buy false;
suggested items that start a complete profitable chain get can_buy false (safety net, suggested items only);
an item that would get both can_sell false and can_buy false is left out; unpriced (under 1 spur) items, Numismatics
cards and book/quest items are left out; seeds are never changed. With --commit, commits each mod's file separately.
Writes safety_net.txt (suggested items blocked, and listed items that start chains) to the pricing-work dir.
"""
import csv
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ore_stage import ore_stage  # noqa: E402

market, work = sys.argv[1], sys.argv[2]
commit = "--commit" in sys.argv
LEFT_OUT_MODS = {"ftbquests", "patchouli", "dynamic_economy", "modonomicon", "guideme"}

rows = list(csv.DictReader(open(os.path.join(work, "suggested.csv"), encoding="utf-8-sig")))
priced = {r["item_id"] for r in rows if r["base_price"]}

# Heads of complete chains (lines without [check]), split by section.
chain_heads = {"listed": {}, "suggested": {}}
section = None
for line in open(os.path.join(work, "chain_profits.txt"), encoding="utf-8"):
    if line.startswith("Listed items"):
        section = "listed"
    elif line.startswith("Suggested items"):
        section = "suggested"
    elif section and " costs " in line and not line.startswith("[check]"):
        m = re.match(r"(\S+) costs ([0-9.]+), processes into ([0-9.]+):(.*)", line.strip())
        if m:
            chain_heads[section][m.group(1)] = (m.group(2), m.group(3), m.group(4).strip())

by_mod = {}
skipped = {"both rules": 0, "excluded": 0}
blocked = []
for r in rows:
    if r["price_source"] != "suggested" or not r["base_price"]:
        continue
    item, mod = r["item_id"], r["mod_id"]
    if mod in LEFT_OUT_MODS or (mod == "numismatics" and item.endswith("card")):
        skipped["excluded"] += 1
        continue
    entry = {"item": item, "base_price": int(r["base_price"]), "class": r["class"]}
    m = re.search(r"converts back into (.+?): list one form only", r["note"])
    back = bool(m) and any(other in priced for other in m.group(1).split())
    no_buy = ore_stage(item) or item in chain_heads["suggested"]
    if back and no_buy:
        skipped["both rules"] += 1
        continue
    if back:
        entry["can_sell"] = False
    if no_buy:
        entry["can_buy"] = False
        if item in chain_heads["suggested"] and not ore_stage(item):
            blocked.append(item)
    by_mod.setdefault(mod, []).append(entry)

with open(os.path.join(work, "safety_net.txt"), "w", encoding="utf-8") as out:
    out.write("Suggested items packaged with can_buy false because buying and processing them profits:\n")
    for item in sorted(blocked):
        cost, value, chain = chain_heads["suggested"][item]
        out.write(f"{item} costs {cost}, processes into {value}: {chain}\n")
    out.write("\nListed (hand-set) items that still start a complete profitable chain; left buyable (decided):\n")
    for item in sorted(chain_heads["listed"]):
        cost, value, chain = chain_heads["listed"][item]
        out.write(f"{item} costs {cost}, processes into {value}: {chain}\n")

total = 0
for mod in sorted(by_mod):
    path = os.path.join(market, mod + ".json")
    nl = "\n"
    if os.path.exists(path):
        raw = open(path, encoding="utf-8", newline="").read()
        nl = "\r\n" if "\r\n" in raw else "\n"
        data = json.loads(raw)
    else:
        data = {"required_mods": [mod], "entries": []} if mod != "minecraft" else {"entries": []}
    entries = {e["item"]: e for e in data["entries"]}
    added = 0
    for e in by_mod[mod]:
        if e["item"] not in entries:  # Seeds keep their hand-set values.
            entries[e["item"]] = e
            added += 1
    data["entries"] = sorted(entries.values(), key=lambda e: e["item"])
    open(path, "w", encoding="utf-8", newline="").write((json.dumps(data, indent=2, ensure_ascii=False) + "\n").replace("\n", nl))
    no_sell = sum(1 for e in by_mod[mod] if e.get("can_sell") is False)
    no_buy = sum(1 for e in by_mod[mod] if e.get("can_buy") is False)
    total += added
    print(f"{mod}: {added} packaged ({no_sell} can_sell false, {no_buy} can_buy false), {len(entries)} total")
    if commit:
        subprocess.run(["git", "add", path], check=True)
        msg = f"Default prices, {mod}: package {added} suggested prices"
        body = (f"{no_sell} convert back into a listed item and can't be sold to the market; "
                f"{no_buy} can't be bought: ore-stage items, or items that buying and processing would profit from.")
        subprocess.run(["git", "commit", "-q", "-m", msg, "-m", body, "-m",
                        "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"], check=True)
print(f"{total} packaged; skipped: {skipped}; safety net blocked {len(blocked)} suggested items; "
      f"{len(chain_heads['listed'])} listed items start chains")
