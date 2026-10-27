# Phase history (Phases 0–9, done)

Archived from `CLAUDE.md`. These are the original specs for finished phases. The code and the guides in `docs/` are now the source of truth; read this only when you need the reasoning behind how something was built.

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
