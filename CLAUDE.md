# Dynamic-Economy — build brief

> **For contributors:** this file is the working brief for [Claude Code](https://claude.com/claude-code), which reads it automatically, and records the project's design decisions and status. Human contributors will find a shorter overview in [docs/architecture.md](docs/architecture.md); the transaction rules below apply to everyone.

NeoForge 1.21.1 mod (NeoForge 21.1.228, Java 21; optional: Create 6.0.10, Numismatics 1.1.0). Adds a **server-owned global market**: players sell items to the server for currency paid into their bank account, sold items enter one shared persistent stock, and players buy from that stock at a markup. Prices move with supply and demand. Since Phase 14 it is named Dynamic-Economy and has its own coins and bank, using Numismatics' bank when that mod is installed; Create and Numismatics are optional.

Where to find more (read only when needed):
- `docs/dev/market-design.md` — background and reasoning. This file overrides it.
- `docs/admin-guide.md`, `docs/player-guide.md` — every command, catalog field and config option as built.
- `docs/dev/phase-history.md` — specs for finished Phases 0–15 (Phase 10 includes how `suggest` and the built-in prices work).
- `docs/dev/known-issues.md` — the release bar for review findings, and findings already accepted.

## How to work

- Phases run in order. **After each phase: run the tests, then stop and report** (see "Report format"). Wait for approval before the next phase.
- Never rewrite working code just to match this brief's wording. If code and brief disagree, list the difference in your report and ask.
- Update the Status line in every commit, so it is always accurate if a session ends unexpectedly.
- **First task in a new session:** read the code on the current branch, compare it with the current phase, and report gaps or differences. Don't fix them silently.
- Review findings: fix those that meet the release bar in `docs/dev/known-issues.md`; add the rest to its Accepted list with one line on why, instead of fixing them. Don't raise accepted items again.
- The GameTest world (`run-gametest/world`) persists between runs: tests must undo lasting changes (edits, tag rules); compare stock, prices and money counts relatively.

## Status

Phases 0–7 released as v0.2.0; Phases 8–14 released as **v0.3.0** on `main` (2026-10-30, tag `v0.3.0`). Phases 8 (recipe + table block), 9 (item classes) and 10 (built-in price packs) done on branch `phase8`; Phase 10 approved by the user on 2026-10-28 (full pack loads with no warnings). Phase 10 result: the built-in pack has 55 files (vanilla + 54 mods): hand-set seeds plus 7,519 packaged suggested prices (one commit per mod); 104 GameTests pass; in the user's pack, `suggest`/`diagnose` show only flint and the check-by-hand group in `craft_profits.txt`, and removing a mod caused no issues. Remaining profitable chains all run through pack generators the user will change with KubeJS (list in `pricing-work/safety_net.txt`). Phase 11 (GUI rework) done on branch `phase11`, approved 2026-10-29 (some manual checks left to the user). Phase 12 (ProgressiveStages) done on branch `phase12`, approved 2026-10-29 (two-player and in-pack checks left to the user). Phase 13 (Leaderboard) done on branch `phase13`, approved 2026-10-29 (in-game look not checked yet). Phase 14 (Standalone currency) done on branch `phase14`, approved 2026-10-30 (in-game checks left to the user; details in `docs/dev/phase-history.md`). All branches pushed to GitHub 2026-10-30. Phase 15 (Contracts) done on branch `phase15` (from `main` at v0.3.0), approved 2026-10-31 (Contracts tab checked in game; two-player checks left to the user; details in `docs/dev/phase-history.md`). Phase 16 (Difficulty preset) dropped by the user on 2026-10-31. Phase 17 (Player exchange) done on branch `phase17` (from `phase15`), approved 2026-11-01 (checked in the user's pack and two-player on a dev server; three small message fixes after approval, 162 GameTests pass with both banks; details in `docs/dev/phase-history.md`). It was the last planned phase. 2026-11-01: `phase17` merged into `main` (Phases 15 and 17) and `mod_version` set to 0.4.0; fixes from a code review committed on `main` the same day (exchange book takes only fresh copies, items with any data are listed; shared owed-payout code; owed contract payouts count on the leaderboard, deposits don't; 166 GameTests pass with both banks). Then a release cleanup on branch `release-cleanup` (from `main`): docs say bits and lose Phase/upgrade wording, examples on the built-in price scale, tuning table and "Changing a built-in price" added, dev notes moved to `docs/dev/`, new `docs/architecture.md`, GameTests split into nine feature classes plus `GameTestSupport`, `MarketScreen` split into one class per tab (needs clicking through in game). Then on branch `catalog-merge` (from `release-cleanup`, 2026-11-02): catalog files merge field by field, with `"replace": true` to start over, and a config-folder catalog for every world (see Catalog format); fixes from a code review of that (folder order, import warning, conflict hint, `config preview`, test temp folders cleaned up); 171 GameTests pass with both banks. Then a docs pass from newcomer test-reads (admin guide: whole-modpack pricing steps, layer details, class values, pitfalls; architecture.md: adding a catalog field, trade check order, writing a test), checked against the code. Then (2026-11-03, user picked these from the test-reads; a server-wide `sell_fee` was not wanted): importing `suggested.csv` leaves out unchanged `catalog` rows, and `/market admin classes` values click to copy as a class file; 172 GameTests pass with both banks. 2026-11-03: `catalog-merge` (with `release-cleanup`) merged into `main`. Then on branch `review-fixes` (from `main`, merged into `main` 2026-11-03): fixes from a code review (exchange tax rounded up once per order or listing on its whole takings, `Order.soldValue`; config folder always just above the built-in packs, user's choice; leaderboard counts money for a team that's gone; unreadable saved contracts recovered or logged, never refunded automatically, user's choice; owed payouts retried every 10 s, not every tick; offline holder names in `/market admin contracts`; tab indentation); a review of those fixes found three more, fixed; 175 GameTests pass with both banks. Two older commits (ab2fb25, af7f58a) didn't update this line; the user chose not to rewrite history. **Released as v0.4.0** on `main` (2026-11-03, tag `v0.4.0`; Phases 15 and 17 plus the cleanup, catalog merging and review fixes), pushed with `phase15` and `phase17`. Then branch `review-fixes-2` (from `main`, 2026-11-04): fixes from a second code review (config folder order: javadoc and admin guide corrected, NeoForge puts a new built-in prices pack at the bottom, no code change; own teams looked up by ID in their map; owed-payout retry wait set only by a failure, test hooks named `ForTesting`, accepted as public since tests live in another package); a third review's findings were about a below-folder warning added in the second round, so it was removed. Then a whole-codebase release review (2026-11-04; one reviewer per area plus every money/item path, each finding checked by a second reviewer) found no blockers and one major problem: the Exchange tab read a stale client copy of the inventory (vanilla doesn't sync it while a slotless menu is open). Fixed: `MarketSync.syncInventory` sends changed main-inventory slots with each player-state check (needs an in-game check: collect from the Box, then sell from Items without reopening). Also fixed: a `NaN` `min_buy_markup` in JSON or CSV is refused; FTB team names are capped at 64 characters for `PlayerState`. Findings below the bar are in `docs/dev/known-issues.md`; 175 GameTests pass with both banks. **Released as v0.4.1** on `main` (2026-11-04, tag `v0.4.1`; `review-fixes-2` merged), pushed; the in-game inventory-sync check is still left to the user.

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
    { "item": "minecraft:diamond",     "base_price": 512,  "class": "rare_resource" },
    { "item": "minecraft:nether_star", "base_price": 16384, "can_sell": false }
  ]
}
```

Fields: `item` (exact ID), `class`, `base_price` (> 0; ≤ 0 is skipped), `markup` (0.5), `sell_fee` (0.0), `can_sell`/`can_buy` (true), `start_stock` (0, first appearance only), `unlimited_stock` (false), `dynamic` (true), `min_buy_markup` (= `markup`; must be > 0 and ≤ `markup` when set), `sell_impact`, `buy_impact`, `recovery`, `floor`, `ceiling`, `contract_weight`, `contract_min_size`, `contract_max_size`, `contract_premium` (server config). Full reference: `docs/admin-guide.md`.

Precedence: server config < class < file `defaults` < entry < tag rules (creation order) < per-item in-game edits.

Several files listing one item (user, 2026-11-02): applied in priority order (built-in packs < `config/dynamic_economy/catalog/` < every other pack; user, 2026-11-03: the folder sits just above the highest built-in pack (the built-in prices, `vanilla`, `mod_data`, each mod's own `mod/<modid>` pack, where the built-in classes are), so world datapacks, the example catalog and other mods' packs (OpenLoader, KubeJS) all beat it, see `MarketCatalog.configOrder`; within one pack or the folder, by file ID), each changing only the fields it sets (its `defaults` count as set). `"replace": true` (entry or `defaults`) starts the entry over; the CSV import writes its files that way, and after reloading lists rows a higher file still changes. Importing `suggested.csv` leaves out `catalog` rows that still match the live entry, unless a value comes from the `imported_<modid>` file being rewritten (`CatalogImport.alreadyListed`). A change that fails the rules is skipped as a whole and the lower entry stays (never drop a lower field silently); the message names the conflicting lower value when the change is valid on its own. The config folder holds `market/` (IDs `config:<path>`) and `market_classes/` (IDs `dynamic_economy:<path>`, replacing built-in classes of that name; classes still replace whole). `/market admin import "<path>" config` writes there.

## Current phase: none

Phase 17 was the last planned phase. v0.4.1 is released on `main` (tag `v0.4.1`, pushed 2026-11-04). Start any new work on a fresh branch from `main`. Phase 17's spec and decisions are in `docs/dev/phase-history.md`.

## Report format (after every phase)

- What was built, and anything deferred or uncertain
- Test results (passed, failed, manual)
- Any difference between existing code and this brief
- Anything in this brief that turned out to be wrong or unclear
