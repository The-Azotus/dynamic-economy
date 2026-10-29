# Numismatics: Dynamic Economy — build brief

NeoForge 1.21.1 companion mod for Create: Numismatics (NeoForge 21.1.228, Java 21, Create 6.0.10, Numismatics 1.1.0). Adds a **server-owned global market**: players sell items to the server for Numismatics currency, sold items enter one shared persistent stock, and players buy from that stock at a markup. Prices move with supply and demand.

Where to find more (read only when needed):
- `docs/market-design.md` — background and reasoning. This file overrides it.
- `docs/admin-guide.md`, `docs/player-guide.md` — every command, catalog field and config option as built.
- `docs/phase-history.md` — specs for finished Phases 0–13 (Phase 10 includes how `suggest` and the built-in prices work).
- `docs/roadmap.md` — specs for Phases 15–16.

## How to work

- Phases run in order. **After each phase: run the tests, then stop and report** (see "Report format"). Wait for approval before the next phase.
- Never rewrite working code just to match this brief's wording. If code and brief disagree, list the difference in your report and ask.
- Update the Status line in every commit, so it is always accurate if a session ends unexpectedly.
- **First task in a new session:** read the code on the current branch, compare it with the current phase, and report gaps or differences. Don't fix them silently.
- The GameTest world (`run-gametest/world`) persists between runs: tests must undo lasting changes (edits, tag rules); compare stock, prices and money counts relatively.

## Status

Phases 0–7 done (v0.2.0 on `main`). Phases 8 (recipe + table block), 9 (item classes) and 10 (built-in price packs) done on branch `phase8`; Phase 10 approved by the user on 2026-10-28 (full pack loads with no warnings). Phase 10 result: the built-in pack has 55 files (vanilla + 54 mods): hand-set seeds plus 7,519 packaged suggested prices (one commit per mod); 104 GameTests pass; in the user's pack, `suggest`/`diagnose` show only flint and the check-by-hand group in `craft_profits.txt`, and removing a mod caused no issues. Remaining profitable chains all run through pack generators the user will change with KubeJS (list in `pricing-work/safety_net.txt`). Phase 11 (GUI rework) done on branch `phase11`, approved 2026-10-29 (some manual checks left to the user). Phase 12 (ProgressiveStages) done on branch `phase12`, approved 2026-10-29 (two-player and in-pack checks left to the user). Phase 13 (Leaderboard) done on branch `phase13`, approved 2026-10-29 (in-game look not checked yet). **Phase 14 (Contracts) is next, on branch `phase14`** (from `phase13`; `main` is still v0.2.0): not started; the open questions under Current phase come first. Then 15 Difficulty preset → 16 Player exchange (optional).

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

## Current phase: 14 — Contracts

- A global board, regenerated every **5 in-game days by day count** (sleeping counts). Generated from the catalog using class settings: large orders for common classes, small ones for rare, single items for `unique`. Number of contracts configurable.
- Flow: a player **claims** a contract, which locks it to them. Claiming takes a **deposit** (fraction of the reward, configurable). They deliver in batches from the **main inventory only**. Delivered items **disappear** (not added to stock).
- Completion pays the reward and returns the deposit. At expiry, an incomplete contract pays for units delivered and the deposit is lost. A player may abandon, with the same outcome. Max active contracts per player configurable (default 1).
- Integrity: per-unit reward stays below the lowest buy price the market can reach for that item, so buying from the market to fill a contract never profits. Transaction rules apply (checks before changes, `long` totals, `TOO_LARGE`).
- New Result values for contract actions (e.g. `CONTRACT_TAKEN`, `CONTRACT_EXPIRED`); list them in the report.

Done when: two players can't both claim one contract; partial and full delivery pay exactly as specified; expiry and refresh happen on the right day, including after sleeping and a restart; buy-from-market-and-deliver never profits (GameTest).

Not started. Branch `phase14` exists (from `phase13`). First: read the code, then ask the user these before building (none is decided):

1. **The reward cap conflicts with selling.** The lowest buy price an item can reach is `base × floor × (1 + markup)`, which with defaults (floor 0.2, markup 0.5) is 0.3 × base, below what simply selling pays (base × s). Capping rewards there makes contracts worse than selling. Options to put to the user: cap against the buy price at claim time and stop the item's price falling below that while the contract is open; exclude contract items from market buying while claimed; only offer contracts for `can_buy: false` items (none can be bought, so any reward is safe); or accept a lower-but-still-useful premium (e.g. reward ≤ min over the contract's life). Whatever is chosen, buy-from-market-and-deliver must never profit (GameTest).
2. **Defaults:** number of contracts on the board, deposit fraction, contract size per class (common / rare / `unique`), reward premium, and whether items with `unlimited_stock` or `can_sell: false` can appear.
3. **Class settings:** Phase 9 reserved per-class contract settings. Which fields (e.g. `contract_weight`, `contract_size`, `contract_premium`, or opting a class out)?
4. **Visibility (Phase 12):** a contract for an item locked for a player is hidden from them; confirm they also can't claim it, and that a claimed contract stays deliverable if their stage is revoked.
5. **Board timing:** "every 5 in-game days by day count" — confirm contracts expire at the same refresh (one shared lifetime), and what a player sees for a claimed contract that expired while offline (payout for delivered units goes to their bank either way).

## Report format (after every phase)

- What was built, and anything deferred or uncertain
- Test results (passed, failed, manual)
- Any difference between existing code and this brief
- Anything in this brief that turned out to be wrong or unclear
