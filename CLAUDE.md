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

Phases 0–7 done (v0.2.0 on `main`). Phases 8 (recipe + table block) and 9 (item classes) done on branch `phase8`. **Phase 10 (built-in price packs) in progress on `phase8`: Stage A done** (code, tests, Chipped/Rechiseled filter, premium tied to the smallest markup). Stage B done: price scale confirmed (cobblestone 1, log 8, iron ingot 32, diamond 512, nether star 16,384). Stage C in progress: seeds for vanilla + 24 mods exist (1,365 entries); 9 price gaps fixed (vanilla, ars_elemental, powah); craft-profit findings now sorted into price gaps / check by hand / self-multiplying. Waiting on the user: wither rose and slime ball (Spectrum converts flowers and clay into them), accepting MI's self-multiplying mixer recipes and flint as generators, and an MI recipe reader. Cause of the 628 recipe-loop items: MI hides ingredient amounts from `getIngredients()` (packer reads 9 alloy → block as 1 → block), so pack/unpack pairs loop. Then 11 GUI rework → 12 ProgressiveStages → 13 Leaderboard → 14 Contracts → 15 Difficulty preset → 16 Player exchange (optional).

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

## Current phase: 10 — Built-in price packs (in progress)

The previous session ran out of context partway through this phase. Start by checking what exists against every point below and reporting what is missing, half-done or untested.

**Work in stages.** Each stage ends with tests, a commit, an updated Status line, and a report. Wait for approval before the next stage.
- **Stage A — Code.** `required_mods`, the built-in pack (enabled by default), `/market admin suggest`, and GameTests. No pricing data yet beyond a minimal `vanilla.json` for tests.
- **Stage B — Price scale.** Summarise `items.csv` with scripts (item counts per mod, notable raw materials) and propose a price scale. Stop for confirmation.
- **Stage C — Seeds.** Vanilla first, then one mod per commit.
- **Stage D — Suggest loop and packaging.** One mod per commit.

**Large-data rules** (this phase ran out of context once):
- Export and suggest output files the user provides live in `pricing-work/` at the project root. Never ask for them to be pasted into chat.
- Never read `items.csv`, `suggested.csv` or any large export in full. Use scripts (Python, PowerShell) to filter by `mod_id`, count rows and pull only what you need.
- Generate seed and catalog JSON with a script, not by writing hundreds of entries inline. Don't print generated files back; report counts and a few sample lines.
- If the session is getting long, finish the current commit, update Status with exactly where you stopped, and report rather than starting the next stage.

- A built-in datapack, **enabled by default**, with one catalog file per mod (`vanilla.json`, `create.json`, …).
- New file-level field `"required_mods": ["<modid>", …]`: if any is missing, the file is skipped silently (one debug line, no warnings). Admins can still disable the pack with `/datapack` or override entries in their own files.
- **Recipe-derived pricing** (decided): only raw materials are priced by hand ("seeds"); everything else is derived from recipes.
  - `/market admin suggest [modid]`, permission 4. Treats every live catalog entry as a known price and walks every recipe in the running game (`RecipeManager`, generic `Recipe.getIngredients()` / `getResultItem()`, so modded machine recipes count when they expose items). An item's suggested price is its cheapest recipe: the sum of each ingredient slot's cheapest priced option, minus priced crafting remainders, divided by output count, times a processing premium kept below the smallest markup (buying ingredients, crafting and selling must never profit). Repeats until nothing gets cheaper.
  - Writes `suggested.csv` to a new export folder, in the `items.csv` format (so it imports directly) with `base_price` and a suggested `class` filled in, plus reference columns: the recipe used, its type, depth, and a note. Notes flag items worth under 1 spur (left blank, since prices are integers), recipe types whose inputs may be incomplete (fluids, sequenced assembly), and items with no priced recipe (candidates for new seeds).
  - Suggested class: `rare_processed` if any input is from a rare class, else `common_processed`.
  - Workflow: Claude writes seed files from the pack's `items.csv`; the user runs `suggest` in the pack and sends the CSV; Claude adds seeds for what's missing and repeats; the result is packaged per mod.
- Chipped and Rechiseled (decorative variants) are left out of the market entirely.
- Price scale: proposed by Claude, confirmed by the user before seeds are written.

Done when: the full pack loads with no warnings; removing a mod drops its file with no warnings; vanilla-only loads just the vanilla file; round-trip arbitrage tests pass against the packaged catalog.

## Report format (after every phase)

- What was built, and anything deferred or uncertain
- Test results (passed, failed, manual)
- Any difference between existing code and this brief
- Anything in this brief that turned out to be wrong or unclear
