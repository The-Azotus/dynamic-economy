# Dynamic-Economy — build brief

NeoForge 1.21.1 mod (NeoForge 21.1.228, Java 21; optional: Create 6.0.10, Numismatics 1.1.0). Adds a **server-owned global market**: players sell items to the server for currency paid into their bank account, sold items enter one shared persistent stock, and players buy from that stock at a markup. Prices move with supply and demand. Since Phase 14 it is named Dynamic-Economy and has its own coins and bank, using Numismatics' bank when that mod is installed; Create and Numismatics are optional.

Where to find more (read only when needed):
- `docs/market-design.md` — background and reasoning. This file overrides it.
- `docs/admin-guide.md`, `docs/player-guide.md` — every command, catalog field and config option as built.
- `docs/phase-history.md` — specs for finished Phases 0–15 (Phase 10 includes how `suggest` and the built-in prices work).

## How to work

- Phases run in order. **After each phase: run the tests, then stop and report** (see "Report format"). Wait for approval before the next phase.
- Never rewrite working code just to match this brief's wording. If code and brief disagree, list the difference in your report and ask.
- Update the Status line in every commit, so it is always accurate if a session ends unexpectedly.
- **First task in a new session:** read the code on the current branch, compare it with the current phase, and report gaps or differences. Don't fix them silently.
- The GameTest world (`run-gametest/world`) persists between runs: tests must undo lasting changes (edits, tag rules); compare stock, prices and money counts relatively.

## Status

Phases 0–7 released as v0.2.0; Phases 8–14 released as **v0.3.0** on `main` (2026-10-30, tag `v0.3.0`). Phases 8 (recipe + table block), 9 (item classes) and 10 (built-in price packs) done on branch `phase8`; Phase 10 approved by the user on 2026-10-28 (full pack loads with no warnings). Phase 10 result: the built-in pack has 55 files (vanilla + 54 mods): hand-set seeds plus 7,519 packaged suggested prices (one commit per mod); 104 GameTests pass; in the user's pack, `suggest`/`diagnose` show only flint and the check-by-hand group in `craft_profits.txt`, and removing a mod caused no issues. Remaining profitable chains all run through pack generators the user will change with KubeJS (list in `pricing-work/safety_net.txt`). Phase 11 (GUI rework) done on branch `phase11`, approved 2026-10-29 (some manual checks left to the user). Phase 12 (ProgressiveStages) done on branch `phase12`, approved 2026-10-29 (two-player and in-pack checks left to the user). Phase 13 (Leaderboard) done on branch `phase13`, approved 2026-10-29 (in-game look not checked yet). Phase 14 (Standalone currency) done on branch `phase14`, approved 2026-10-30 (in-game checks left to the user; details in `docs/phase-history.md`). All branches pushed to GitHub 2026-10-30. Phase 15 (Contracts) done on branch `phase15` (from `main` at v0.3.0), approved 2026-10-31 (Contracts tab checked in game; two-player checks left to the user; details in `docs/phase-history.md`). Phase 16 (Difficulty preset) dropped by the user on 2026-10-31. Phase 17 (Player exchange) done on branch `phase17` (from `phase15`), approved 2026-11-01 (checked in the user's pack and two-player on a dev server; details in `docs/phase-history.md`). It was the last planned phase; **next: release v0.4.0** when the user asks.

Phases 15+ target v0.4.0. Repo: https://github.com/The-Azotus/dynamic-economy (MIT). `main` holds the latest release; start new work on a fresh branch from it. Poker and a random-walk stock market are out of scope.

## Decisions already made (don't revisit)

- Separate mod. Don't fork or modify Numismatics or Create: Stock Market; don't copy Stock Market code (AGPL-3.0) or Numismatics' textures.
- From Phase 14: named **Dynamic-Economy** (mod id stays `dynamic_economy`). Create and Numismatics are optional. With Numismatics installed the mod uses Numismatics' bank; otherwise its own coins and bank.
- **All market payments go through the player's bank account**, both directions. The market never pays out or takes physical coins; coins only move in and out of the bank through deposit and withdraw.
- Own coins (value in bits): Bit 1, Chip 8, Stack 64, Mark 512, Crown 4,096, Sun 32,768, Divine 262,144, Omega 2,097,152. **1 bit = 1 Numismatics spur**, so prices and price files mean the same under either bank.
- One server-wide market. Same prices for every player; team size never affects them.
- Market pays `base × s × (1 − sell_fee)`; charges `ceil(base × s × (1 + markup))`. `s` is the per-item supply-and-demand factor.
- Only items with default components are accepted (no enchanted, damaged or renamed copies). Exception (user, 2026-10-30): data types in the `ignored_components` server config (default `relics:data`, which Relics adds to every held relic) don't stop a sale; bought items still come fresh and never join such a stack.
- Unlimited-stock items: purchases never raise the price, and the buy price never falls below `base × (1 + min_buy_markup)`, which is always above base.
- No difficulty presets (user, 2026-10-31: Phase 16 dropped). Admins tune the market through classes, catalog fields and the server config.
- No per-player quotas. Dumping is limited by price impact and item classes.
- The market holds no balance: sales create money, purchases destroy it, `sell_fee` reduces what is created.
- Contracts (user, 2026-10-30): a contract never pays more than buying the delivered units from the market could ever cost, less 1 bit, so only items whose buy price can't fall far (unlimited, fixed-price, `can_buy: false`) are worth offering. Items with `can_sell: false` are never offered; the `unique` class has `contract_weight: 0`.

## Transaction rules (non-negotiable)

- Run every transaction on the server main thread (`IPayloadContext.enqueueWork` for packets). Never touch the Numismatics bank from another thread; its storage is an unsynchronized `HashMap`.
- **Every check happens before anything changes.** After the first change, no remaining step may fail.
- Sell order: validate listing → recompute price → compare with client's quote → count matching items in the **main inventory only** → remove exactly `qty` → update stock (unless unlimited) and `setDirty()` → `deposit(total)`.
- Buy order: validate listing → recompute price → compare with client's quote → check stock (skip if unlimited) → simulate inserting into main inventory, reject if it won't fit → `deduct(total, false)`, stop if false → update stock (unless unlimited) and `setDirty()` → insert items.
- **Never drop items on the ground.** Don't use Numismatics' `ItemUtil.givePlayerItem` for market output.
- Totals in `long`. Reject with `TOO_LARGE` above `Integer.MAX_VALUE` (bank methods take `int`). `qty` capped per request (config, default 640).
- Buying N then selling N must never profit (markup + fee + impact). Any new feature that pays out (contracts, presets) must keep this true.
- `compat/NumismaticsBridge` is the **only** class allowed to import `dev.ithundxr.createnumismatics.*`; `compat/CreateRecipes` the only one allowed to import Create; `compat/FtbTeamsBridge` the only one allowed to import FTB Teams. Each touches its mod only from a nested `Present` class.
- From Phase 14, everything that moves money goes through the bank interface, never a bank directly. The own bank follows the same rules (server main thread, all-or-nothing `deduct`).
- Coin items (ours and Numismatics') can never be listed in the market; selling them would create money from nothing.

Result enum: `OK, NOT_LISTED, SELL_DISABLED, BUY_DISABLED, NOT_ENOUGH_ITEMS, NO_STOCK, NO_FUNDS, NO_SPACE, PRICE_CHANGED, TOO_LARGE`, plus `INVALID_QUANTITY`, `BANK_UNAVAILABLE` (earlier phases) for contracts, `CONTRACT_TAKEN, CONTRACT_EXPIRED, CONTRACT_LIMIT, NOT_YOUR_CONTRACT, CONTRACT_FULL`, and for the exchange, `EXCHANGE_CLOSED, ORDER_LIMIT, ORDER_GONE, NOT_YOUR_ORDER, INVALID_PRICE, OWN_LISTING`.

## Catalog format

Files in `data/<namespace>/market/*.json`; classes in `data/<namespace>/market_classes/*.json`. Prices are integer **spurs** (bits from Phase 14; the same unit). A file's `defaults` block applies to every entry in it unless overridden.

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

Fields: `item` (exact ID), `class`, `base_price` (> 0; ≤ 0 is skipped), `markup` (0.5), `sell_fee` (0.0), `can_sell`/`can_buy` (true), `start_stock` (0, first appearance only), `unlimited_stock` (false), `dynamic` (true), `min_buy_markup` (= `markup`; must be > 0 and ≤ `markup` when set), `sell_impact`, `buy_impact`, `recovery`, `floor`, `ceiling`, `contract_weight`, `contract_min_size`, `contract_max_size`, `contract_premium` (server config). Full reference: `docs/admin-guide.md`.

Precedence: server config < class < file `defaults` < entry < tag rules (creation order) < per-item in-game edits.

## Current phase: none

Phase 17 was the last planned phase. Next: release v0.4.0 (Phases 15 and 17) when the user asks. Phase 17's spec and decisions are in `docs/phase-history.md`.

## Report format (after every phase)

- What was built, and anything deferred or uncertain
- Test results (passed, failed, manual)
- Any difference between existing code and this brief
- Anything in this brief that turned out to be wrong or unclear
