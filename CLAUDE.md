# Numismatics: Dynamic Economy — build brief

A NeoForge 1.21.1 companion mod for Create: Numismatics. It adds a **server-owned global market**: players sell items to the server for Numismatics currency, sold items enter one shared, persistent stock, and players buy from that stock at a markup. Prices move with supply and demand.

Background, verified source findings and the reasoning behind decisions are in `docs/market-design.md`. This file overrides it wherever they differ.

## How to work through this brief

- Phases run in order. Each phase has a "done when" list.
- **After each phase: run the tests, then stop and report** (see "Report format"). Wait for approval before the next phase.
- Never rewrite working code from an earlier phase just to match this brief's wording. If existing code and this brief disagree, list the difference in your report and ask.
- Keep every rule in "Transaction rules" true in every phase.

## Status

| Phase | Status |
| --- | --- |
| 0 — Setup and verification | Done |
| 1 — Server logic via commands | Done |
| 2 — Validation hardening | Done |
| 3 — GUI and sync | Done |
| **3.5 — Unlimited stock** | **Next** |
| 4 — Supply-and-demand pricing | To do |
| 5 — Pack integration and polish | To do |
| 6 — Optional extensions | Only if asked |

**First task in a new session:** read the existing code, compare it with Phases 0–3 below, and report any gaps or differences before starting Phase 3.5. Don't fix gaps silently.

## Decisions already made (don't revisit)

- Separate companion mod. Do **not** fork or modify Numismatics or Create: Stock Market, and do not copy code from Stock Market (it is AGPL-3.0).
- **All payments go through the player's Numismatics bank account**, both directions. No physical coins.
- One server-wide market. Prices are the same for every player; team size never affects them.
- Market pays the player `base × s × (1 − sell_fee)`. Market charges the player `ceil(base × s × (1 + markup))`. `s` is the item's supply-and-demand factor (Phase 4); before Phase 4, `s = 1`.
- Only items with default components are accepted. An enchanted, damaged or renamed copy of a listed item is rejected.
- Catalog entries use exact item IDs. Tags are deferred to Phase 6.
- Some items have **unlimited stock** (Phase 3.5): common resources players can always buy.
- For unlimited-stock items, **purchases never raise the price**, and the buy price **never falls below `base × (1 + min_buy_markup)`**, which is always above base price (Phase 4).

## Transaction rules (non-negotiable)

- Run every transaction on the server main thread (`IPayloadContext.enqueueWork` for packets). Never touch the Numismatics bank from another thread; its storage is an unsynchronized `HashMap`.
- **Every check happens before anything changes.** After the first change, no remaining step may be able to fail.
- Sell order: validate listing → recompute price → compare with the client's quote → count matching items in the **main inventory only** → remove exactly `qty` → update stock (unless unlimited) and `setDirty()` → `deposit(total)`.
- Buy order: validate listing → recompute price → compare with the client's quote → check stock (skip if unlimited) → simulate inserting into the main inventory and reject if it won't fit → `deduct(total, false)`, stop if it returns false → update stock (unless unlimited) and `setDirty()` → insert items.
- **Never drop items on the ground.** Don't use Numismatics' `ItemUtil.givePlayerItem` for market output.
- Do totals in `long`. Reject with `TOO_LARGE` if a total exceeds `Integer.MAX_VALUE`, since the bank methods take `int`. Cap `qty` per request (configurable, default 640).
- `compat/NumismaticsBridge` is the **only** class allowed to import `dev.ithundxr.createnumismatics.*`.

Result enum: `OK, NOT_LISTED, SELL_DISABLED, BUY_DISABLED, NOT_ENOUGH_ITEMS, NO_STOCK, NO_FUNDS, NO_SPACE, PRICE_CHANGED, TOO_LARGE`.

## Catalog format

Files live in `data/<namespace>/market/*.json`, loaded by a reload listener. Prices are integer **spurs**. A file may have a `defaults` block that applies to every entry in that file unless the entry overrides it; use this for per-category settings (one file per category).

```json
{
  "defaults": { "markup": 0.5, "sell_fee": 0.0, "dynamic": true },
  "entries": [
    { "item": "minecraft:cobblestone",  "base_price": 1,    "unlimited_stock": true },
    { "item": "minecraft:oak_log",      "base_price": 2,    "unlimited_stock": true },
    { "item": "minecraft:iron_ingot",   "base_price": 16,   "start_stock": 0 },
    { "item": "minecraft:diamond",      "base_price": 256,  "start_stock": 0 },
    { "item": "minecraft:nether_star",  "base_price": 8192, "start_stock": 0, "can_sell": false }
  ]
}
```

| Field | Default | Meaning |
| --- | --- | --- |
| `item` | required | Exact item ID |
| `base_price` | required | Spurs, integer > 0 |
| `markup` | 0.5 | Added on top when the market sells |
| `sell_fee` | 0.0 | Fraction kept back from payouts (money sink). Phase 4 |
| `can_sell` / `can_buy` | true | Whether players may sell to / buy from the market |
| `start_stock` | 0 | Applied only the first time an item appears in saved stock |
| `unlimited_stock` | false | Phase 3.5 |
| `dynamic` | true | Whether `s` moves for this item. Phase 4 |
| `min_buy_markup` | same as `markup` | Unlimited-stock items only: the lowest markup the buy price can fall to. Must be > 0 and ≤ `markup`; checked only when set explicitly (the default may be 0 if `markup` is 0). Phase 4 |
| `sell_impact`, `buy_impact`, `recovery`, `floor`, `ceiling` | from server config | Phase 4 overrides |

Prices are placeholders, not balanced values.

---

## Phase 0 — Setup and verification (done)

- NeoForge 1.21.1 project (ModDevGradle), NeoForge 21.1.228, Java 21, Create 6.0.10, Numismatics 1.1.0.
- Verified against the released Numismatics 1.1.0 jar: `Numismatics.BANK`, `GlobalBankManager.getAccount(Player)` / `getAccount(UUID)`, `BankAccount.getBalance()`, `deposit(int)`, `deduct(int, boolean)`, `Coin` (SPUR = 1 … SUN = 4096).

## Phase 1 — Server logic via commands (done)

Classes: `DynamicEconomy` (entry point), `catalog/MarketCatalog`, `catalog/CatalogEntry`, `data/MarketStockData` (`SavedData`, id `dynamic_economy_market`, stock kept separate from the catalog), `compat/NumismaticsBridge`, `service/MarketService` (`trySell`, `tryBuy`, `quote`), `service/PriceModel`, `command/MarketCommand`.

Done when: selling and buying change stock and balance by exactly the expected amounts; stock survives a restart; `/market admin reload` changes prices without touching stock.

## Phase 2 — Validation hardening (done)

Component checks, quote checks, quantity caps, `long` overflow guards.

Done when each of these is rejected with the right result and changes nothing: unlisted item, selling more than held, enchanted/damaged copy, too little money, buying beyond stock, buying a 0-stock item, full inventory, oversized order, `can_sell: false`, stale quote. Automated as NeoForge GameTests where practical.

## Phase 3 — GUI and sync (done)

- `MarketMenu` (no item slots) and `MarketScreen`: searchable, scrollable catalog list showing item, stock, buy price, sell price; quantity selection; buy and sell buttons; clear messages for every rejection result.
- `/market` (permission 0) opens it. Admin commands at permission 2: `setstock`, `reload`.
- Packets: client to server `BuyRequest` and `SellRequest(item, qty, quotedTotal)`; server to client full snapshot on open and per-item deltas to players with the screen open.
- Optional market terminal block.

Done when: two clients see stock change within one tick of each other's trades; spam-clicking 100 times leaves totals that reconcile.

## Phase 3.5 — Unlimited stock (next)

Some items are common resources the market always sells, no matter how many have been sold to it.

- Catalog: `"unlimited_stock": true`. When true, `start_stock` is ignored.
- Represent stock as "unlimited" explicitly (e.g. an `isUnlimited()` flag or `OptionalLong`), **not** as `-1` or `Long.MAX_VALUE`, so the value can't leak into arithmetic.
- **Buying:** never fails with `NO_STOCK`. All other checks (funds, space, quote, caps) still apply.
- **Selling:** allowed if `can_sell` is true. The player is paid normally; the items are removed from the game and nothing is added to stock.
- **Saved stock:** while an item is unlimited, leave its stored stock value untouched. If the catalog later makes it finite again, the old value comes back.
- **Pricing:** unlimited items have their own Phase 4 rules (see "Unlimited-stock items" in Phase 4). In this phase, prices are fixed.
- **GUI and packets:** show "Unlimited" (or ∞) instead of a number. Snapshot and delta packets carry the unlimited flag.
- **Admin:** `/market admin setstock` on an unlimited item is rejected with a message explaining the item is unlimited in the catalog.

Done when:
- [ ] Buying an unlimited item with 0 stored stock succeeds repeatedly; stored stock never changes.
- [ ] Selling an unlimited item pays the player and doesn't change stored stock.
- [ ] Funds, space, quote and size checks still reject correctly for unlimited items.
- [ ] Switching an item from finite → unlimited → finite in the catalog (with `/market admin reload`) restores its previous stock.
- [ ] The GUI shows "Unlimited" on both a fresh open and after a reload.
- [ ] GameTests cover the cases above.

## Phase 4 — Supply-and-demand pricing (to do)

Each item has a factor `s`, starting at 1.0 and persisted in `MarketStockData` with the game time it was last updated.

- **Sales push `s` down, purchases push it up.** Apply the change **per unit** inside an order, so a 640-item order can't all trade at the pre-order price: for each unit sold, `s = max(floor, s × (1 − sell_impact))`; for each unit bought, `s = min(ceiling, s × (1 + buy_impact))`. Price each unit at the `s` it trades at, and sum. The quote the client sends must be this same total.
- **Recovery toward 1.0** is computed lazily from elapsed game time when an item is read, not with a ticking task: `s = 1 + (s − 1) × (1 − recovery)^(elapsed_ticks / interval_ticks)`. Use overworld game time so recovery pauses while the server is stopped.
- `sell_fee` reduces payouts and is not paid to anyone (money sink).
- `"dynamic": false` keeps `s` fixed at 1.0.
- **Unlimited-stock items** (common resources):
  - Purchases never change `s`, so `s` can only fall (from sales) and recover back up to 1.0. It never goes above 1.0.
  - Buy price is `ceil(base × max(s × (1 + markup), 1 + min_buy_markup))`. With the default `min_buy_markup = markup`, the buy price stays fixed at `ceil(base × (1 + markup))`; a lower `min_buy_markup` lets it fall with supply but never to or below base price.
  - Sell price follows the normal rule, so it drops as players dump an item and recovers over time.
  - Reject catalog entries where `min_buy_markup` is ≤ 0 or greater than `markup`, with a logged warning.
- Server config holds global defaults for `sell_impact`, `buy_impact`, `recovery`, `interval_ticks`, `floor`, `ceiling`; catalog entries and file `defaults` can override them.
- GUI shows the current price and a small up/down indicator versus 1.0. Because prices move, `PRICE_CHANGED` must be handled gracefully: refresh the quote and let the player confirm again.
- Admin: `/market admin resetprice <item|all>` sets `s` back to 1.0.

Done when:
- [ ] Selling one unit at a time and selling the same amount in one order produce the same total payout.
- [ ] Dumping 10,000 units lowers `s` but never below `floor`; buying raises it but never above `ceiling`.
- [ ] After a configured interval with no trades, `s` has recovered by the expected amount; stopping the server doesn't count as elapsed time.
- [ ] Buying N then immediately selling N never makes a profit (markup plus fee plus impact must prevent round-trip arbitrage). Add a GameTest for this with the default config.
- [ ] Two players trading the same item on the same tick get prices consistent with sequential execution.
- [ ] Buying 10,000 units of an unlimited-stock item leaves `s` and its buy price unchanged.
- [ ] After heavy selling of an unlimited-stock item, its buy price is still at least `base × (1 + min_buy_markup)` and above base price; with default config it hasn't changed at all.
- [ ] Round-trip arbitrage on an unlimited-stock item never makes a profit.

## Phase 5 — Pack integration and polish (to do)

- Write a catalog-generation helper (command or script) that exports every item ID in the loaded modlist to a template file, so prices can be filled in by hand.
- Config validation: log clear warnings for unknown item IDs, negative prices, `floor > ceiling`, etc., and skip bad entries rather than crashing.
- A per-item price history view in the GUI (a simple line of recent prices), stored compactly in `SavedData` with a cap on entries. Must be original code; don't copy Stock Market's.
- Write `docs/catalog-guide.md` explaining every catalog field and config option for the server admin.

Done when: the pack's real catalog loads with no warnings, and a playtest server runs a session without errors in the log.

## Phase 6 — Optional extensions (only if asked)

- **Tag-based catalog entries.** A tag may pool *selling* (any log counts), but stock and buying must stay per item ID, or buyers get an arbitrary member of the tag. Investigate whether a tag shares one price and one `s`, and report options before building.
- Physical coin payment via `CoinItem.extract`.
- Accepting held Numismatics bank cards.

## Report format (after every phase)

- What was built, and anything deferred or uncertain
- Test results (which passed, which failed, which are manual)
- Any difference between existing code and this brief
- Anything in this brief that turned out to be wrong or unclear
