# Numismatics: Dynamic Economy — build brief

NeoForge 1.21.1 companion mod for Create: Numismatics (NeoForge 21.1.228, Java 21, Create 6.0.10, Numismatics 1.1.0). Adds a **server-owned global market**: players sell items to the server for Numismatics currency, sold items enter one shared persistent stock, and players buy from that stock at a markup. Prices move with supply and demand.

Where to find more (read only when needed):
- `docs/market-design.md` — background and reasoning. This file overrides it.
- `docs/admin-guide.md`, `docs/player-guide.md` — every command, catalog field and config option as built.
- `docs/phase-history.md` — original specs for Phases 0–9.
- `docs/roadmap.md` — specs for Phases 11–16.

## How to work

- Phases run in order. **After each phase: run the tests, then stop and report** (see "Report format"). Wait for approval before the next phase.
- Never rewrite working code just to match this brief's wording. If code and brief disagree, list the difference in your report and ask.
- Update the Status line in every commit, so it is always accurate if a session ends unexpectedly.
- **First task in a new session:** read the code on the current branch, compare it with the current phase, and report gaps or differences. Don't fix them silently.
- The GameTest world (`run-gametest/world`) persists between runs: tests must undo lasting changes (edits, tag rules); compare stock, prices and money counts relatively.

## Status

Phases 0–7 done (v0.2.0 on `main`). Phases 8 (recipe + table block), 9 (item classes) and 10 (built-in price packs) done on branch `phase8`; Phase 10 awaits the user's approval. Phase 10 result: the built-in pack has 55 files (vanilla + 54 mods): hand-set seeds plus 7,519 packaged suggested prices (one commit per mod); 104 GameTests pass; in the user's pack, `suggest`/`diagnose` show only flint and the check-by-hand group in `craft_profits.txt`, and removing a mod caused no issues. Remaining profitable chains all run through pack generators the user will change with KubeJS (list in `pricing-work/safety_net.txt`). Next: Phase 11 GUI rework → 12 ProgressiveStages → 13 Leaderboard → 14 Contracts → 15 Difficulty preset → 16 Player exchange (optional).

Phases 8+ target v0.3.0. Repo: https://github.com/The-Azotus/dynamic-economy (MIT). `main` holds the latest release; start new work on a fresh branch from it. Poker and a random-walk stock market are out of scope.

## Decisions already made (don't revisit)

- Separate companion mod. Don't fork or modify Numismatics or Create: Stock Market; don't copy Stock Market code (AGPL-3.0).
- **All payments go through the player's Numismatics bank account**, both directions. No physical coins.
- One server-wide market. Same prices for every player; team size never affects them.
- Market pays `base × s × (1 − sell_fee)`; charges `ceil(base × s × (1 + markup))`. `s` is the per-item supply-and-demand factor.
- Only items with default components are accepted (no enchanted, damaged or renamed copies).
- Unlimited-stock items: purchases never raise the price, and the buy price never falls below `base × (1 + min_buy_markup)`, which is always above base.
- No per-player quotas. Dumping is limited by price impact and item classes.
- The market holds no balance: sales create money, purchases destroy it, `sell_fee` reduces what is created.

## Transaction rules (non-negotiable)

- Run every transaction on the server main thread (`IPayloadContext.enqueueWork` for packets). Never touch the Numismatics bank from another thread; its storage is an unsynchronized `HashMap`.
- **Every check happens before anything changes.** After the first change, no remaining step may fail.
- Sell order: validate listing → recompute price → compare with client's quote → count matching items in the **main inventory only** → remove exactly `qty` → update stock (unless unlimited) and `setDirty()` → `deposit(total)`.
- Buy order: validate listing → recompute price → compare with client's quote → check stock (skip if unlimited) → simulate inserting into main inventory, reject if it won't fit → `deduct(total, false)`, stop if false → update stock (unless unlimited) and `setDirty()` → insert items.
- **Never drop items on the ground.** Don't use Numismatics' `ItemUtil.givePlayerItem` for market output.
- Totals in `long`. Reject with `TOO_LARGE` above `Integer.MAX_VALUE` (bank methods take `int`). `qty` capped per request (config, default 640).
- Buying N then selling N must never profit (markup + fee + impact). Any new feature that pays out (contracts, presets) must keep this true.
- `compat/NumismaticsBridge` is the **only** class allowed to import `dev.ithundxr.createnumismatics.*`.

Result enum: `OK, NOT_LISTED, SELL_DISABLED, BUY_DISABLED, NOT_ENOUGH_ITEMS, NO_STOCK, NO_FUNDS, NO_SPACE, PRICE_CHANGED, TOO_LARGE`.

## Catalog format

Files in `data/<namespace>/market/*.json`; classes in `data/<namespace>/market_classes/*.json`. Prices are integer **spurs**. A file's `defaults` block applies to every entry in it unless overridden.

```json
{
  "defaults": { "markup": 0.5, "sell_fee": 0.0, "dynamic": true },
  "entries": [
    { "item": "minecraft:cobblestone", "base_price": 1,    "unlimited_stock": true },
    { "item": "minecraft:diamond",     "base_price": 256,  "class": "rare_resource" },
    { "item": "minecraft:nether_star", "base_price": 8192, "can_sell": false }
  ]
}
```

Fields: `item` (exact ID), `class`, `base_price` (> 0; ≤ 0 is skipped), `markup` (0.5), `sell_fee` (0.0), `can_sell`/`can_buy` (true), `start_stock` (0, first appearance only), `unlimited_stock` (false), `dynamic` (true), `min_buy_markup` (= `markup`; must be > 0 and ≤ `markup` when set), `sell_impact`, `buy_impact`, `recovery`, `floor`, `ceiling` (server config). Full reference: `docs/admin-guide.md`.

Precedence: server config < class < file `defaults` < entry < tag rules (creation order) < per-item in-game edits.

## Current phase: 10 — Built-in price packs (done, awaiting approval)

As built. Full detail for admins: `docs/admin-guide.md` (built-in prices, `suggest`, the check files).

**Stages** (each ended with tests, a commit and a report): A code; B price scale (cobblestone 1, log 8, iron ingot 32, diamond 512, nether star 16,384); C seeds, one mod per commit; D suggest loop and packaging, one mod per commit.

**Large-data rules** (still apply to any pricing work):
- Export and suggest output files the user provides live in `pricing-work/` (gitignored). Never ask for them to be pasted into chat.
- Never read `items.csv`, `suggested.csv` or any large export in full. Use scripts to filter, count and pull only what you need.
- Generate seed and catalog JSON with a script. Don't print generated files back; report counts and a few sample lines.

**Built-in pack**
- `src/main/resources/default_prices/data/dynamic_economy/market/<modid>.json`, **enabled by default**; one file per mod, `minecraft.json` for vanilla.
- File-level `"required_mods": [...]`: if any is missing, the file is skipped silently (one debug line). Admins can disable the pack with `/datapack` or override entries in their own files.
- Chipped and Rechiseled are left out of the market entirely.
- Packaging rules (decided): items that convert back into a listed item (storage blocks, nuggets, dusts, variants) get `can_sell: false`; ore-stage items (raw ores and blocks, crushed ores, Mekanism crystals/shards/clumps/dirty dusts, metal dusts, Spectrum pure metals) get `can_buy: false`; **safety net**: suggested items that still start a complete profitable chain get `can_buy: false` (suggested items only; hand-set items that start chains stay buyable and are listed for the user); an item that would get both is left out; Numismatics cards and guide/quest items are left out. Seeds are never overwritten by packaging.
- Class markups (rare-tier audit, decided): `rare_resource` 1.0, `rare_processed` 1.0, `rare_loot` 1.5, `unique` 2.0.
- Seed decisions: ProjectE left as priced (user balances it with KubeJS and ProgressiveStages); wither rose stays; slime ball 2 (Create crafts it from dough + lime dye); enchanted golden apple 4096 (Create: New Age assembles it from gold blocks); milk bucket 112 with `can_sell: false`; IE electrum stays 24.

**`/market admin suggest [modid]`** (permission 4). Prices every unlisted item from recipes, treating every live catalog entry as known. Changes nothing in the market.
- Reads recipes with the generic methods, plus readers that recover inputs, amounts and outputs where those hide them: Create (chance outputs at their average; sequenced assembly: starting item plus each step's additions per loop, output at the success chance), Modern Industrialization, Immersive Engineering, Mekanism and Oritech (`compat/MachineRecipes`, by reflection; one warning per mod if a version renames something). Inputs a machine uses up only sometimes count at their average.
- An item's price is its cheapest recipe: each slot's cheapest priced option (identical crafting-grid slots merged), minus priced crafting remainders, plus furnace fuel, divided by output count. Costs add up raw through the chain; the 10% premium (kept below the smallest markup) is applied once at the end. An item is never priced from ingredients priced from it (loop guard).
- **Complete vs. may-hide-inputs** (decided): recipes using fluids or chemicals, and recipe types that may hide inputs, only price items no complete recipe can; what they price can still lower a complete recipe that uses it. Energy conversions are treated the same way, as accepted generators: Oritech's particle collider, MI's quarry, Mekanism's sawmill, Create's haunting (`PriceSuggestion.ENERGY_CONVERSION_TYPES`; haunting is left out of `COMPLETE_TYPES`).
- **Raise**: a suggestion is raised to what it can be processed into (through complete recipes, at most 3 steps), divided by its class markup, so buying and processing it doesn't profit (9 nuggets can't cost less than an ingot). The raise is capped at the item's cost to make from bought ingredients; above that it profits at any price, so it keeps its cost and is reported instead. Raised prices become floors and everything is priced once more (two rounds only, or generator loops raise each other every round). Items worth under 1 spur stay unpriced. A partly used input (quarry drill) is credited one run; a tool the grid hands back (a knife) isn't credited.
- Suggested class: `rare_processed` if rare inputs are worth at least half the cost or one unit of the cheapest rare item, else `common_processed`.
- Writes to a new export folder: `suggested.csv` (the `items.csv` format plus reference columns: price source, exact cost, recipe, type, depth, notes for under-1-spur, raised, may-hide-inputs, converts-back, no recipe), `craft_profits.txt` (one-step profits: price gaps / check by hand / self-multiplying), `chain_profits.txt` (multi-step buy-process-sell profits, at most 6 steps, complete chains found first, suggestions valued as if imported), `chain_details.txt` (every step with amounts and prices: the biggest raises, the top chains, and every complete chain from a listed item) and `skipped_recipes.txt`.
- Workflow (used for Stage D): Claude writes seeds from `items.csv`; the user runs `suggest` in the pack and puts the files in `pricing-work/`; Claude fixes seeds or the suggester and repeats; then packages per mod with the rules above (scratchpad `package.py`, which also writes `pricing-work/safety_net.txt`).

**Known limits** (accepted): generator recipes in a pack (prismarine doubling, prismarine → quartz, coal → black dye, MI gravel → 2 flint, MI mixer stone recipes, Spectrum and Mekanism conversions) profit at any price; only recipe changes close them. Whole-spur rounding leaves small gaps on 1–2-spur items. Both are covered by the safety net and listed for the pack author.

Done when: the full pack loads with no warnings; removing a mod drops its file with no warnings; vanilla-only loads just the vanilla file; round-trip arbitrage tests pass against the packaged catalog. Status: the default-prices GameTest (vanilla + Create + Numismatics) checks the loaded files follow the installed mods, round trips, and no price gaps or complete chain profits; the user confirmed the in-pack `suggest`/`diagnose` results and that removing a mod caused no issues.

## Report format (after every phase)

- What was built, and anything deferred or uncertain
- Test results (passed, failed, manual)
- Any difference between existing code and this brief
- Anything in this brief that turned out to be wrong or unclear
