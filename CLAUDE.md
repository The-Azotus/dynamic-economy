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
| 3.5 — Unlimited stock | Done |
| 4 — Supply-and-demand pricing | Done |
| 5 — Pack integration and polish | Done |
| 6 — Terminal, access, admin editing, tooltips | Done, released as v0.2.0 |
| 7 — Integrity, usability, admin tooling | Done, released as v0.2.0 |
| 8 — Recipe and table block | Done (on `phase8`) |
| 9 — Item classes | Done (on `phase8`) |
| 10 — Built-in price packs | Not started (needs the pack's `items.csv`) |
| 11 — GUI rework | Not started |
| 12 — ProgressiveStages hooks | Not started |
| 13 — Leaderboard | Not started |
| 14 — Contracts | Not started |
| 15 — Difficulty preset | Not started |
| 16 — Player exchange (optional toggle) | Not started |

Phases 8+ target v0.3.0 on a branch from `main`. Poker and a random-walk stock market are **out of scope** for this mod (better as a separate companion mod, if ever).

Branches: `main` holds the latest release (`v0.2.0`) and is pushed to https://github.com/The-Azotus/dynamic-economy. `explore` (Phase 6) and `phase7` are merged, local-only history with their own worktree folders (`git worktree list`). Start new work on a fresh branch from `main`. Licence: MIT.

**First task in a new session:** read the existing code on the current branch, compare it with the in-progress phase below, and report any gaps or differences before continuing. Don't fix gaps silently.

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
| `class` | none | Item class (Phase 9) whose values fill the fields no layer sets; `rare_resource` means `dynamic_economy:rare_resource` |
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

## Phase 3.5 — Unlimited stock (done)

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

## Phase 4 — Supply-and-demand pricing (done)

Each item has a factor `s`, starting at 1.0 and persisted in `MarketStockData` with the game time it was last updated.

- **Sales push `s` down, purchases push it up.** Apply the change **per unit** inside an order, so a 640-item order can't all trade at the pre-order price: for each unit sold, `s = max(floor, s × (1 − sell_impact))`; for each unit bought, `s = min(ceiling, s × (1 + buy_impact))`. Value each unit at the `s` it trades at, add up the exact (unrounded) values, then round the order total once: down for payouts, up for charges. The quote the client sends must be this same total. (Decided: rounding each unit separately made cheap items pay 0 after the first sale.)
- **Recovery toward 1.0** is computed lazily from elapsed game time when an item is read, not with a ticking task: `s = 1 + (s − 1) × (1 − recovery)^steps`, where `steps` is the number of `interval_ticks` boundaries of overworld game time crossed since the last update (server-wide boundaries, so prices only change at a step or on a trade, and open screens are refreshed at each step). Overworld game time pauses while the server is stopped. (Decided: continuous recovery made large-order quotes go stale between click and processing.)
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
- [ ] Splitting an order never pays more (selling) or costs less (buying) than the same amount in one order, and leaves `s` in the same place.
- [ ] Dumping 10,000 units lowers `s` but never below `floor`; buying raises it but never above `ceiling`.
- [ ] After a configured interval with no trades, `s` has recovered by the expected amount; stopping the server doesn't count as elapsed time.
- [ ] Buying N then immediately selling N never makes a profit (markup plus fee plus impact must prevent round-trip arbitrage). Add a GameTest for this with the default config.
- [ ] Two players trading the same item on the same tick get prices consistent with sequential execution.
- [ ] Buying 10,000 units of an unlimited-stock item leaves `s` and its buy price unchanged.
- [ ] After heavy selling of an unlimited-stock item, its buy price is still at least `base × (1 + min_buy_markup)` and above base price; with default config it hasn't changed at all.
- [ ] Round-trip arbitrage on an unlimited-stock item never makes a profit.

## Phase 5 — Pack integration and polish (done)

- **Item export command.** Don't ask for the modlist: build an in-game exporter that reads the live item registry (`BuiltInRegistries.ITEM`), so it works in any modpack.
  - `/market admin export [modid]`, permission 4 (server owner or console), since it writes files to disk. With `modid`, export only that mod's items.
  - Writes to `config/dynamic_economy/exports/<yyyy-MM-dd_HH-mm-ss>/`, never into the live catalog folder, and never overwrites an earlier export.
  - `items.csv`: one row per item with `item_id, mod_id, display_name, max_stack_size, in_catalog, base_price, unlimited_stock, markup, min_buy_markup, sell_fee, can_sell, can_buy, start_stock, dynamic`. Existing catalog values are filled in; anything unset is left blank. Skip `minecraft:air`.
  - `templates/<modid>.json`: one catalog file per mod, in the catalog format above, containing only items **not** already in the catalog, each with `base_price: 0`.
  - The catalog loader must skip entries with `base_price` ≤ 0 and log one summary warning ("N unpriced entries skipped"), so a half-filled template can be dropped in safely.
  - Reply in chat with the export path and counts (total items, already priced, new).
- **CSV import command.** Pricing happens in a spreadsheet, so the edited CSV must turn back into catalog files.
  - `/market admin import <path-to-csv> [preview]`, permission 4. Paths are relative to `config/dynamic_economy/` and must stay inside it (reject `..` and absolute paths).
  - Parse robustly: header row in any column order, unknown columns ignored, quoted fields (display names can contain commas), a UTF-8 BOM, and comma **or** semicolon delimiters (some spreadsheet locales save with semicolons).
  - Per row: skip rows with a blank `base_price`. Reject, and list in the report, rows whose item ID isn't in the registry, whose price isn't a positive integer, or whose values break catalog rules (e.g. bad `min_buy_markup`). Blank optional columns fall back to catalog defaults.
  - `preview` validates and reports counts (to write, skipped blank, rejected with reasons) without writing anything.
  - Otherwise write one catalog file per mod, `imported_<modid>.json`, into a world datapack at `<world>/datapacks/dynamic_economy_catalog/data/dynamic_economy/market/`, creating the pack and its `pack.mcmeta` if missing. Before replacing an existing file, copy it to `<name>.json.bak`.
  - If the pack was just created, enable it, or tell the admin the exact `/datapack enable` command if enabling isn't possible at runtime. Then reload the catalog the same way `/market admin reload` does.
  - Importing never changes saved stock or price factors `s`.
  - Round-trip test: export → import the unchanged CSV produces catalog entries identical to what was exported.
- Hand-editing the JSON templates stays supported. The CSV route is optional.
- Config validation: log clear warnings for unknown item IDs, negative prices, `floor > ceiling`, etc., and skip bad entries rather than crashing.
- A per-item price history view in the GUI (a simple line of recent prices), stored compactly in `SavedData` with a cap on entries. Must be original code; don't copy Stock Market's.
- Write `docs/catalog-guide.md` explaining every catalog field and config option for the server admin.

Done when: the export command runs in the full pack and its counts match the item registry; a priced CSV imports, reloads and shows correct prices in the GUI; the round-trip test passes; the pack's real catalog loads with no warnings, and a playtest server runs a session without errors in the log.

## Phase 6 — Terminal, access, admin editing, tooltips (done, v0.2.0)

Asked for after v0.1.0. Work happens on the `explore` branch (worktree `..\Economy Mod (explore)`); `main` holds v0.1.0 and only gets fixes.

- **Market Terminal block.** Craftable (Create brass casing, electron tubes, Numismatics cog, gold, redstone; datapack-overridable recipe). Right-click opens the market; the screen closes beyond 8 blocks or if the block breaks.
- **Access mode.** Server config `access_mode`: `COMMAND_AND_BLOCK` (default) or `BLOCK_ONLY`. In block-only mode, players without operator permission can't use any `/market` command (plain `/market` explains where to go); operators and the console keep everything.
- **In-game catalog editing** (permission 2): `setprice`, `set <field>`, `unset`, `remove` (delist, stock kept), `clearstock`, `revert`, `info`. Saved with the world (`dynamic_economy_overrides`), checked with the loader's own rules, never touching stock or `s`.
- **Live tag rules** (decided): `/market admin tag <tag> setprice|set|unset|remove|revert|info`, `/market admin tags`. A rule applies to every item in the tag at each build, including items added to the tag later. Precedence: catalog files < tag rules (in creation order) < per-item edits. A rule without a price never lists unpriced items. Stock and `s` stay per item ID.
- **Sell price in tooltips.** Every item the market buys shows "Market pays X spurs" in its tooltip everywhere; nothing for unlisted, `can_sell: false` or modified copies. Client config `show_sell_price_in_tooltips`. The catalog is sent to every player on join; changes go to everyone.
- **Docs.** `README.md` (front page), `docs/player-guide.md`, `docs/admin-guide.md` (was `catalog-guide.md`).

Done when: terminal opens the market and closes at range; block-only mode stops `/market` for non-ops but terminals work; every admin edit command works, persists across restart and refuses rule-breaking edits; tag rules price all members with item edits winning; tooltips show correct sell prices; docs cover all of it; all GameTests pass.

Still optional, only if asked: physical coin payment via `CoinItem.extract`; accepting held Numismatics bank cards; tag entries inside catalog JSON files.

## Phase 7 — Integrity, usability, admin tooling (done, v0.2.0)

Priorities, in order: economic correctness, then player usability, then admin observability and recovery. Work in the stages below; each stage is one commit, ends with tests and a report, and waits for approval.

**Out of scope:** selling from Create machines or other inventories, automated-trading policies or quotas, progression or quest systems, physical coins or bank cards, player-specific pricing, replacing the catalog architecture, GUI redesign beyond identified problems.

**Decided:**
- Admin actions go to an append-only, size-capped JSONL file in the world folder; recent player trades (capped per player) go in world save data.
- The 5-item test catalog becomes a built-in datapack that is off by default (`mod/dynamic_economy:example_catalog`, "feature" pack source). The GameTest server enables every pack, so the tests still use it.
- Max Buy and Sell All only fill the quantity box; the trade still goes through the normal buttons and checks.
- No quote reservations: a stale price is still rejected with `PRICE_CHANGED` and the refreshed total.
- The market holds no balance. Sales create money, purchases destroy it, `sell_fee` reduces what is created. Statistics must say this, not call purchases "revenue".

**Stage 1 — Economic integrity.** Boundary, overflow, sell-then-buy and anti-exploit-boundary tests; a test that every rejection changes nothing; constant-time pricing once `s` stops moving (exactly the same totals); single-pass held counts; drop fully recovered factors; lifetime money counters and `/market admin stats`; check reload with tag rules for spurious warnings.

**Stage 2 — Player usability.** Server order limit sent to the client; inventory room in player state; Sell All / Max Buy; sort and filters; average unit price, % of base and limits in the selection; decimal unit prices for cheap items; clearer stale-price message.

**Stage 3 — Catalog diagnostics.** Per-field source in `info`; `/market admin diagnose`; `preview` for tag rules and price resets; reset by tag; `prune` saved data for removed items; the optional example datapack.

**Stage 4 — Audit and recovery.** Admin audit log with rotation and `/market admin audit`; per-player trade history in the screen; `backup` / `restore` of stock, prices and edits with validation, preview and confirm. An automatic backup is taken before `restore` and `prune`, not before `import` (an import changes only catalog files, which it already keeps as `.json.bak`).

The GameTest world in `run-gametest/world` persists between runs, so tests must undo any lasting change they make (edits, tag rules); stock, prices and money counts are reset or compared relatively.

Done when: every stage's tests pass alongside all earlier GameTests; saves from earlier versions load; docs cover each new command and the money model; manual checks (GUI layout, two clients, example pack on a real server) are listed in the report as manual.

## Phase 8 — Recipe and table block

- Default recipe: shaped, a crafting table in the centre surrounded by 8 `numismatics:spur`. Still datapack-overridable.
- Market Terminal becomes a table with piles of coins on it: a hand-written JSON block model (editable later in Blockbench), simple original textures, horizontal facing on placement, matching collision and outline shapes, no full-block occlusion. Everything else about the terminal (opening, 8-block range, close on break) is unchanged.

Done when: the new recipe crafts the terminal and the old one no longer does; the block places facing the player and looks right from all sides; existing placed terminals in old worlds still load and work.

## Phase 9 — Item classes

A class is a named set of pricing defaults, so groups of items behave alike (rare items volatile and slow to recover, common ones stable). It is the main anti-exploit tool: **no per-player quotas** (decided); dumping is limited by price impact.

- Classes live in `data/<namespace>/market_classes/*.json`, loaded by the same reload. A class may set any of: `markup`, `sell_fee`, `can_sell`, `can_buy`, `dynamic`, `unlimited_stock`, `min_buy_markup`, `sell_impact`, `buy_impact`, `recovery`, `floor`, `ceiling`, and later contract settings (Phase 14). Same validation as catalog entries.
- Catalog entries, file `defaults`, tag rules and in-game edits accept `"class": "<id>"`. Unknown class: warning, entry skipped.
- Precedence: server config < class < file `defaults` < entry < tag rules < per-item edits.
- Built-in classes (placeholder values, tuned in Phase 10): `common_resource`, `common_processed`, `rare_resource`, `rare_processed`, `rare_loot`, `unique`, `farmable` (crops and mob drops that automated farms flood: cheap, steep sell impact). No equipment class.
- Admin tooling knows classes: CSV `class` column in export/import, `info` shows the class as a field source, `diagnose` flags unknown classes, `/market admin classes` lists them with member counts.

Done when: an entry with a class gets the class's values unless overridden at a higher level; changing a class and reloading reprices all members without touching stock or `s`; the CSV round-trip test still passes with the class column; all earlier GameTests pass.

## Phase 10 — Built-in price packs

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

## Phase 11 — GUI rework

- The screen sizes to the window; the list shows as many rows as fit (not a fixed 7). Compact rows with columns: item, stock, buy, sell, trend.
- The selected-item panel moves beside the list on wide windows, below it on narrow ones.
- Tabs: **Market | Contracts | Leaderboard | History**, plus **Exchange** when enabled. Later phases fill their tabs.
- All existing functions (search, sort, filters, Max Buy, Sell All, price history, rejection messages) still work.

Done when: usable at GUI scales 1–4 and small windows; no regressions in Phase 3/7 behaviour (manual checks listed).

## Phase 12 — ProgressiveStages hooks

- Soft dependency on ProgressiveStages **3.1.1**. Verify its API against the released jar before use (as in Phase 0). `compat/StagesBridge` is the only class allowed to import it; without the mod, nothing changes.
- An item locked for a player is **hidden** from that player: snapshot, deltas, tooltips, contracts and exchange. Catalog sync becomes per-player.
- The server enforces it too: buying or selling a locked item is rejected as `NOT_LISTED` (doesn't reveal locked items).
- Prices stay the same for everyone; only visibility differs. Stage changes re-sync the player's market.

Done when: a locked item is invisible and untradeable for one player while another trades it; unlocking a stage shows it without reopening the game; works with the mod absent.

## Phase 13 — Leaderboard

- Top N **player** bank balances with exact amounts, read via `NumismaticsBridge`. Team and shared accounts are excluded; only accounts belonging to a known player profile count (verify how Numismatics distinguishes them).
- Offline players shown by name from the profile cache.
- Config: `leaderboard_enabled`, `leaderboard_size`, `leaderboard_exclude_operators`.
- Read on the main thread; refreshed when the tab opens, not every tick.

Done when: balances match `/market` bank values; non-player accounts never appear.

## Phase 14 — Contracts

- A global board, regenerated every **5 in-game days by day count** (sleeping counts). Generated from the catalog using class settings: large orders for common classes, small ones for rare, single items for `unique`. Number of contracts configurable.
- Flow: a player **claims** a contract, which locks it to them. Claiming takes a **deposit** (fraction of the reward, configurable). They deliver in batches from the **main inventory only**. Delivered items **disappear** (not added to stock).
- Completion pays the reward and returns the deposit. At expiry, an incomplete contract pays for units delivered and the deposit is lost. A player may abandon, with the same outcome. Max active contracts per player configurable (default 1).
- Integrity: per-unit reward stays below the lowest buy price the market can reach for that item, so buying from the market to fill a contract never profits. Transaction rules apply (checks before changes, `long` totals, `TOO_LARGE`).
- New Result values for contract actions (e.g. `CONTRACT_TAKEN`, `CONTRACT_EXPIRED`); list them in the report.

Done when: two players can't both claim one contract; partial and full delivery pay exactly as specified; expiry and refresh happen on the right day, including after sleeping and a restart; buy-from-market-and-deliver never profits (GameTest).

## Phase 15 — Difficulty preset

- Server config `difficulty = EASY | NORMAL | HARD | CUSTOM`. Presets apply **multipliers on top of** catalog and class values: payout, added `sell_fee`, impact, recovery, contract reward. `CUSTOM` exposes each multiplier. `NORMAL` changes nothing.
- After multipliers, the anti-arbitrage invariant must still hold; invalid combinations are clamped with a warning.

Done when: each preset changes prices as documented; round-trip arbitrage tests pass under every preset.

## Phase 16 — Player exchange (optional, config toggle)

The feature wanted most after the required ones. Players post buy and sell orders at their own prices.

- Sell orders move items into escrow; buy orders move money into escrow (`deduct`). Orders match automatically, price-time priority, partial fills.
- Proceeds and items go to a **collection box** in the Exchange tab (works for offline players; never drop items).
- Config: `exchange_enabled` (default false), `exchange_tax` (fraction taken from the seller, destroyed: a money sink), max open orders per player, order expiry.
- v1 trades default-component items only (same rule as the market). Items with components (enchanted gear) are a later step.
- All transaction rules apply. Cancelling returns escrow exactly.

Done when: matched trades move exactly the right items and money with tax applied; cancelling and expiry return escrow; nothing is lost or duplicated across a restart mid-order; spam-clicking reconciles.

## Report format (after every phase)

- What was built, and anything deferred or uncertain
- Test results (which passed, which failed, which are manual)
- Any difference between existing code and this brief
- Anything in this brief that turned out to be wrong or unclear
