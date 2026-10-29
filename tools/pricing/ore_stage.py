"""The ore-stage rule (decided): raw ores, raw ore blocks, crushed ores, Mekanism crystals/shards/clumps/dirty
dusts, metal dusts and clumps, and Spectrum pure metals are never sold by the market (can_buy false), because ore
processing multiplies them. Importable: ore_stage(item_id) -> bool."""
import re
METALS = ("iron gold copper tin lead silver nickel aluminum aluminium uranium osmium zinc platinum tungsten titanium antimony "
          "iridium chromium manganese bauxite desh ostrum calorite electrum constantan steel bronze invar brass cobalt "
          "quicksilver netherite necoium draconium monazite cadmium fluxite adamant biosteel duratium energite").split()
M = "|".join(METALS)
PATTERNS = [re.compile(p) for p in (
    rf"^raw_({M})(_block)?$", rf"^raw_block_({M})$", rf"^crushed_raw_({M})$", rf"^(crystal|shard|clump|dirty_dust)_({M})$",
    rf"^dust_({M})$", rf"^({M})_dust$", rf"^({M})_tiny_dust$", rf"^small_({M})_dust$", rf"^({M})_clump$",
    rf"^small_({M})_clump$", rf"^({M})_crushed_dust$", rf"^pure_({M})$")]


def ore_stage(item_id):
    path = item_id.split(":", 1)[1]
    return any(p.match(path) for p in PATTERNS)


if __name__ == "__main__":
    import glob, json, os, sys
    market = sys.argv[1]
    changed = 0
    for path in glob.glob(os.path.join(market, "*.json")):
        raw = open(path, encoding="utf-8", newline="").read()
        nl = "\r\n" if "\r\n" in raw else "\n"
        data = json.loads(raw)
        touched = False
        for e in data["entries"]:
            if ore_stage(e["item"]) and e.get("can_buy", True):
                e["can_buy"] = False
                touched = True
                changed += 1
        if touched:
            open(path, "w", encoding="utf-8", newline="").write((json.dumps(data, indent=2, ensure_ascii=False) + "\n").replace("\n", nl))
            print(os.path.basename(path))
    print(changed, "seed entries set to can_buy false")
