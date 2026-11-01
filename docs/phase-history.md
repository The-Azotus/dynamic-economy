# Phase history (Phases 0–15, done)

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

- Classes live in `data/<namespace>/market_classes/*.json`, loaded by the same reload. A class may set any of: `markup`, `sell_fee`, `can_sell`, `can_buy`, `dynamic`, `unlimited_stock`, `min_buy_markup`, `sell_impact`, `buy_impact`, `recovery`, `floor`, `ceiling`, and later contract settings (Phase 15, Contracts). Same validation as catalog entries.
- Catalog entries, file `defaults`, tag rules and in-game edits accept `"class": "<id>"`. Unknown class: warning, entry skipped.
- Precedence: server config < class < file `defaults` < entry < tag rules < per-item edits.
- Built-in classes (placeholder values, tuned in Phase 10): `common_resource`, `common_processed`, `rare_resource`, `rare_processed`, `rare_loot`, `unique`, `farmable` (crops and mob drops that automated farms flood: cheap, steep sell impact). No equipment class.
- Admin tooling knows classes: CSV `class` column in export/import, `info` shows the class as a field source, `diagnose` flags unknown classes, `/market admin classes` lists them with member counts.

Done when: an entry with a class gets the class's values unless overridden at a higher level; changing a class and reloading reprices all members without touching stock or `s`; the CSV round-trip test still passes with the class column; all earlier GameTests pass.

## Phase 10 — Built-in price packs (done)

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
- Workflow (used for Stage D): Claude writes seeds from `items.csv`; the user runs `suggest` in the pack and puts the files in `pricing-work/`; Claude fixes seeds or the suggester and repeats; then packages per mod with the rules above (`tools/pricing/package.py`, which also writes `pricing-work/safety_net.txt`; `add_seeds.py`, `check_seeds.py` and `ore_stage.py` beside it).

**Known limits** (accepted): generator recipes in a pack (prismarine doubling, prismarine → quartz, coal → black dye, MI gravel → 2 flint, MI mixer stone recipes, Spectrum and Mekanism conversions) profit at any price; only recipe changes close them. Whole-spur rounding leaves small gaps on 1–2-spur items. Both are covered by the safety net and listed for the pack author.

Done when: the full pack loads with no warnings; removing a mod drops its file with no warnings; vanilla-only loads just the vanilla file; round-trip arbitrage tests pass against the packaged catalog. Status: the default-prices GameTest (vanilla + Create + Numismatics) checks the loaded files follow the installed mods, round trips, and no price gaps or complete chain profits; the user confirmed the in-pack `suggest`/`diagnose` results and that removing a mod caused no issues.

## Phase 11 — GUI rework (done)

As built (branch `phase11`). Approved by the user on 2026-10-29 after seeing the layout in game; the remaining manual checks (`@`/`#` search, GUI scales 1–4 and small windows, two clients, Max/All/price-changed) were left for the user to do later.

- The screen sizes to the window, up to 600×400 (`MAX_WIDTH`/`MAX_HEIGHT` in `MarketScreen`), and the list shows as many 18 px rows as fit. Columns: item, stock, buy, sell, have, trend (▲/▼ = price vs. base).
- From 440 px wide the selection panel is a 150 px column right of the list: name, a price chart up to 80 px tall, buy/sell/stock/have/vs. base lines, quantity controls, Buy and Sell. Narrower, it sits under the list as before. Search text and quantity survive a resize.
- Tabs **Market | Contracts | Leaderboard | History**, drawn by the screen. History is the old Trades view (clicking a trade selects it in Market). Contracts and Leaderboard are greyed out with a "Coming in a later version" tooltip until Phases 13–14; Exchange is added when Phase 17 adds its config switch.
- The balance sits beside the tabs; when it doesn't fit, it moves to the end of the search row, abbreviated (`123k`).
- Search: space-separated terms must all match; `@mod` matches a mod's display name or ID, `#tag` a tag's path (`#c:ingots` the full tag ID), `mod:` still filters by namespace.
- Decided with the user: keep the Have column; trend = vs. base; 18 px rows (the screen grows instead); greyed-out tabs.

## Phase 12 — ProgressiveStages hooks (done)

- Soft dependency on ProgressiveStages **3.1.1**. Verify its API against the released jar before use (as in Phase 0). `compat/StagesBridge` is the only class allowed to import it; without the mod, nothing changes.
- An item locked for a player is **hidden** from that player: snapshot, deltas, tooltips, contracts and exchange. Catalog sync becomes per-player.
- The server enforces it too: buying or selling a locked item is rejected as `NOT_LISTED` (doesn't reveal locked items).
- Prices stay the same for everyone; only visibility differs. Stage changes re-sync the player's market.

Done when: a locked item is invisible and untradeable for one player while another trades it; unlocking a stage shows it without reopening the game; works with the mod absent.

Decided with the user (2026-10-29): allow ProgressiveStages `[3.1.1,4)`; if its lock API fails, fail open (hide nothing, log an error); a past trade of a now-locked item stays in History. Verified against the real 3.1.1 jar: item, tag and whole-mod (`mod:create`) locks are all detected, and a locked listed item is left out of that player's catalog.

As built (branch `phase12`): `compat/StagesBridge` asks ProgressiveStages' `LockRegistry.isItemBlockedFor(player, item)` (missing stage; ignores creative, category switches and structure locks), probed by reflection once. `service/MarketVisibility` wraps it and has a GameTest hook (`setTestLocks`). `MarketSync` sends each player only what they can see (one shared broadcast when nothing can be hidden); stage events mark catalogs stale, resent next tick. Trading a hidden item returns `NOT_LISTED` before any other check; `/market price|stock` hide it from players. Approved 2026-10-29; the two-player and in-pack checks were left to the user.

## Phase 13 — Leaderboard (done)

- Top N **player** bank balances with exact amounts, read via `NumismaticsBridge`. Team and shared accounts are excluded; only accounts belonging to a known player profile count (verify how Numismatics distinguishes them).
- Offline players shown by name from the profile cache.
- Config: `leaderboard_enabled`, `leaderboard_size`, `leaderboard_exclude_operators`.
- Read on the main thread; refreshed when the tab opens, not every tick.

Done when: balances match `/market` bank values; non-player accounts never appear.

As built (branch `phase13`), approved 2026-10-29: `NumismaticsBridge.playerBalances()` lists accounts of type `PLAYER` (Numismatics' other type is `BLAZE_BANKER`, the shared accounts; there are no team accounts; sub-accounts are spending permissions, not money), probed separately so a mismatch only empties the board. `service/Leaderboard` ranks balances > 0 of players with a known profile (online, else the server's profile cache), richest first, ties by name; balances are the displayed ones (capped values show with "+"). Config `leaderboard.leaderboard_enabled` (true), `leaderboard_size` (10, 1–100), `leaderboard_exclude_operators` (false). The tab sends `LeaderboardRequest` on open (20-tick cooldown per player); the snapshot carries the enabled flag; network protocol 8. The GameTest server has no profile cache, so `viewFor(viewer, profiles)` takes a lookup for tests. The in-game look was left for the user to check.

## Phase 14 — Standalone currency (done)

Approved by the user on 2026-10-30, after these additions at their request: click a coin in the Bank tab to withdraw exactly that coin (Shift: a stack, or as many as the balance covers; protocol 11); the `ignored_components` server config (default `relics:data`) so relics can be sold; the enchanted book and goat horn dropped from the built-in prices; the user's own coin and Bank Card textures. 132 GameTests pass in every setup. Left to the user in game: the Bank tab and card screens, creative-tab hiding, FTB party trading and payout, a bank switch on a copy of their world.

Make the mod work without Create and Numismatics, with its own coins and bank, while still using Numismatics' bank when it's installed. Prices don't change: 1 bit = 1 spur. Big phase, so it's built in steps; **stop and report after each step**.

1. **Rename and optional Create.** Display name `Dynamic-Economy` everywhere (`gradle.properties` `mod_name`, README, docs, lang); mod id `dynamic_economy` and the repo stay, so worlds and datapacks keep working. Create becomes an optional dependency: `compat/CreateRecipes` loads only when Create is (nested `Present` class, as in `StagesBridge`); without Create, `suggest` skips Create's chance outputs and sequenced assembly.
2. **Bank interface and own bank.** One `Bank` interface (balance, `deposit`, all-or-nothing `deduct`, list player balances); two versions: Numismatics' (through `NumismaticsBridge`) and our own (balances saved with the world, keyed by player UUID). MarketService, MarketSync, Leaderboard and the startup code use only the interface. Numismatics becomes optional. The world records which bank it uses and never switches on its own.
3. **Coins.** Eight items, Bit to Omega (values above), original textures. Recipes: 8 of one ⇄ 1 of the next. Hidden and uncraftable while Numismatics' bank is in use.
4. **Bank tab and remote access.** A **Bank** tab in the market screen: deposit every coin from the main inventory, or withdraw an amount as the fewest coins (only if it all fits in the main inventory; never drop items). With Numismatics' bank it moves Numismatics coins. A carried item opens the Bank tab from anywhere.
5. **Team accounts** (own bank only; Numismatics has none), as the answers to question 1 decide.
6. **Switching and finishing.** Admin command to move a world from one bank to the other (moves balances, warns about loose coins); Market Terminal recipe with a mod-loaded condition (spurs with Numismatics, our coins without); docs for both setups.

Done when: the mod loads and the market works with no Create or Numismatics installed, and with each alone; GameTests pass on both banks (a Gradle run without Numismatics and Create is needed, since the dev runtime has both); deposit then withdraw returns exactly the same value; withdraw never drops items; coins can't be listed in the market; buy-then-sell still never profits.

Started 2026-10-29 on branch `phase14` (from `phase13`). Gaps found in the code at the start: nothing stops a coin from being listed (no guard exists yet; add it by step 3); the terminal recipe hard-codes `numismatics:spur`; GameTests call `NumismaticsBridge` directly; the dev runtime always has Create and Numismatics (`implementation`).

Decided with the user on 2026-10-29:

1. **Team accounts:** a team is an FTB Teams party when FTB Teams is installed, otherwise our own `/bank team` commands. Every member can deposit and withdraw. A "pay from: Me / Team" choice in the Bank tab decides which account market purchases charge and sales pay into.
2. **Switching banks:** as recommended. Coins are always registered, but hidden and uncraftable while Numismatics' bank is in use; the world remembers its bank; only an admin command switches. The switch moves every player's balance across (both directions) and warns about loose coins. Recipe conditions run before the world's saved data loads, so they can't see the bank: coin recipes check it when crafted (to verify in step 2).
3. **Names:** keep Crown and Sun; the docs note that Numismatics' coins of the same name are worth less.
4. **Unit label:** follows the bank: "spurs" with Numismatics' bank, "bits" with our own.
5. **Remote access:** a **Bank Card** (shapeless: paper + gold ingot + a Chip); it opens only the Bank tab. Its recipe with Numismatics' bank is decided in step 4.
6. **Balances:** our bank stores `long`; each transaction stays capped at `Integer.MAX_VALUE`.
7. **Coin art:** placeholder textures made by Claude; the user may replace them later.
8. **Terminal recipe without Numismatics:** 8 Bits around a crafting table.

Step 1 (rename, optional Create): done 2026-10-29, 109 GameTests pass.

Step 2 (bank interface, own bank): done 2026-10-29. `bank/Bank` (interface), `bank/OwnBank` (long balances by UUID, saved with the world), `bank/WorldBank` (records the world's bank on first start: a world whose market predates the record, or a new world with Numismatics, takes Numismatics'; otherwise ours). Unit label follows the bank ("spurs"/"bits"; sent in the snapshot with the bank's display cap). `gradlew runGameTestServer -PwithoutMods=numismatics` (Create only) or `-PwithoutMods=create` (neither) runs the tests in their own world folder. 113 GameTests pass in all three setups. Recipes load before the world's saved data (checked in the log), so coin recipes must check the bank when crafted. Until step 3 the terminal recipe loaded only with Numismatics.

Step 3 (coins): done 2026-10-29. `coin/Coin` (values), `coin/Coins` (items, `coin_exchange` recipe serializer, `isCoin`, `inUse`), `coin/CoinRecipe` (shapeless; matches only while the world uses our bank, checked at craft time). 14 exchange recipes (8 ⇄ 1). Textures drawn by the user (2026-10-30; replaced the placeholders). Coins appear in the Ingredients creative tab only with our bank (client knows the bank from the snapshot). `MarketCatalog.decode` refuses every coin, ours and Numismatics' six (by ID), so files, CSV import, item edits and tag rules can't list one. Terminal: spurs with Numismatics, 8 Bits without (`market_terminal_from_bits.json`, done early from step 6). 117 GameTests pass in all three setups. Not done: JEI/EMI still show coins and their recipes under Numismatics' bank (no JEI dependency yet).

Step 4 (Bank tab, Bank Card): done 2026-10-29. `service/BankService` (deposit every plain coin of the bank's set from the main inventory, paid in `int` parts; withdraw an amount as the fewest coins, only if all fit, else NO_SPACE; same check-then-change order as trades). `Bank.coins()` gives each bank's coins (Numismatics' read from its `Coin` enum by `NumismaticsBridge`); `bank/CoinMath` is shared by server and client. Bank tab in `MarketScreen` (coin list, deposit button, withdraw box with a preview of the coins or why not). Bank Card (`coin/BankCardItem`) opens a bank-only `MarketMenu` (only the Bank tab; valid while carried; trade packets ignored). Card recipe: paper + gold ingot + Chip, or + Numismatics Bevel (8 spurs) when Numismatics is installed. Protocol version 9 (also covers step 2's payload changes, which hadn't bumped it). 123 GameTests pass in all three setups; the screen itself is unchecked in game.

Step 5 (team accounts): done 2026-10-29. FTB Teams 2101.1.11 (the user's pack) is an optional dependency, on the dev runtime unless `-PwithoutMods=ftbteams`; `compat/FtbTeamsBridge` is the only class importing it (party teams only; probed by reflection). Without it, `/bank team create|invite|join|leave|kick|info` (`command/BankCommand`, `bank/OwnTeams`; owner invites and kicks, ownership passes on when the owner leaves). `bank/BankTeams` picks the source. `OwnBank` keeps team balances apart from players' (teams stay off the leaderboard) and each player's "pay from team" choice; `Bank.activeAccount` is what trades, deposits and withdrawals use. A team's balance is never lost: the last member leaving gets it; a deleted FTB party's goes to its owner. Bank tab: "Using: my account / team X" button for team members. Protocol 10. 127 GameTests pass in four setups (all mods; no Numismatics; no Create/Numismatics; none of the three). Not covered by GameTests: trading with an FTB party's account and the payout when a party is deleted (needs real players).

Step 6 (switching, finishing): done 2026-10-29. `/market admin bank` (info) and `/market admin bank switch <own|numismatics> [confirm]` (`command/AdminBankCommands`, `bank/BankSwitch`): preview first; every account looked up before money moves; balances written to the audit log; Numismatics' hidden overflow drained; teams paid to owners before moving to Numismatics' bank (a gone team's balance stays in ours); warns about loose coins. The terminal recipe was done in step 3. JEI: the user chose to leave coins visible there (2026-10-29); the docs say so. Docs: README, player guide (Bank tab, coins, teams, recipes), admin guide (new "Banks and coins" section, commands, troubleshooting). 130 GameTests pass in all setups.

## Phase 15 — Contracts (done)

Approved by the user on 2026-10-31: the Contracts tab works in game. 143 GameTests pass (142 without Numismatics and FTB Teams). Left to the user: two players claiming the same contract, team payouts with real players. Built: `service/ContractPricing` (offer and cap), `data/ContractBoard` (saved board, day count, owed payouts, login notices), `service/ContractService`, `command/ContractCommands`, the Contracts tab (protocol 12), the `[contracts]` server config and four `contract_*` catalog fields; `Bank` gained `accountOf` and `teamAccountOf` to pay offline players. Choices made while building: setting the time backwards doesn't count as days passing; with `doDaylightCycle` off the board only changes by `/market admin contracts refresh`; turning `contracts_enabled` off returns deposits; an abandoned contract isn't reopened; a finished or abandoned contract answers `CONTRACT_TAKEN`. With equal weights rare classes seldom appear (about 19 rare items against 1,600 others in the full pack); raise their `contract_weight` if wanted.

- A global board, regenerated every **5 in-game days by day count** (sleeping counts). Generated from the catalog using class settings: large orders for common classes, small ones for rare, single items for `unique`. Number of contracts configurable.
- Flow: a player **claims** a contract, which locks it to them. Claiming takes a **deposit** (fraction of the reward, configurable). They deliver in batches from the **main inventory only**. Delivered items **disappear** (not added to stock).
- Completion pays the reward and returns the deposit. At expiry, an incomplete contract pays for units delivered and the deposit is lost. A player may abandon, with the same outcome. Max active contracts per player configurable (default 1).
- Integrity: per-unit reward stays below the lowest buy price the market can reach for that item, so buying from the market to fill a contract never profits. Transaction rules apply (checks before changes, `long` totals, `TOO_LARGE`).
- New Result values for contract actions (e.g. `CONTRACT_TAKEN`, `CONTRACT_EXPIRED`); list them in the report.

Done when: two players can't both claim one contract; partial and full delivery pay exactly as specified; expiry and refresh happen on the right day, including after sleeping and a restart; buy-from-market-and-deliver never profits (GameTest).

Branched as `phase15` from `main` (v0.3.0). The user's answers to the open questions (2026-10-30):

1. **Reward cap:** a payout never reaches what buying the delivered units could ever cost (the lowest buy price: `base × (1 + min_buy_markup)` for unlimited items, `base × (1 + markup)` for fixed-price ones, `base × floor × (1 + markup)` otherwise; none for `can_buy: false`), less 1 bit. An item is offered only if that still beats selling at the normal price. `unique` items: option (a), never offered (`contract_weight: 0` on the class), not a raised floor.
2. **Defaults:** 8 contracts per board, deposit 20% of the reward (rounded up), reward `base × 1.25` per unit, sizes 64–512 common / 4–16 rare / 1 `unique`; `unlimited_stock` items allowed, `can_sell: false` items excluded.
3. **Fields:** `contract_weight` (0 opts out), `contract_premium`, and the size as two fields, `contract_min_size` / `contract_max_size` (instead of one `[min, max]` field, so the CSV and `/market admin set` handle them like every other field). Settable at every catalog layer.
4. **Visibility:** hidden and unclaimable (answers `NOT_LISTED`) when locked; a held contract stays visible and deliverable after its stage is lost.
5. **Timing:** one shared lifetime: every contract expires when the board is replaced. An offline holder is paid for delivered units and told at next login. Payouts go to the account the deposit came from (team account while the team exists, else the player's own).

## Phase 16 — Difficulty preset (dropped)

Dropped by the user on 2026-10-31 before it was started: a difficulty option wasn't needed. The spec, kept for reference:

- Server config `difficulty = EASY | NORMAL | HARD | CUSTOM`. Presets apply **multipliers on top of** catalog and class values: payout, added `sell_fee`, impact, recovery, contract reward. `CUSTOM` exposes each multiplier. `NORMAL` changes nothing.
- After multipliers, the anti-arbitrage invariant must still hold; invalid combinations are clamped with a warning.

Done when: each preset changes prices as documented; round-trip arbitrage tests pass under every preset.

## Phase 17 — Player exchange (done)

Approved by the user on 2026-11-01 after checking the Exchange tab in their pack. 162 GameTests pass with Numismatics' bank and with the own bank. A two-player check on a local dev server (2026-10-31) passed: order book, listings, offline players, a restart mid-order, two players spamming at once, and a listing bought through the Exchange tab. Three small things noticed then were fixed on 2026-11-01 (protocol 15): cancelling a listing now says "listing"; messages and notices about a listing name the item as listed (its custom name); `/market admin exchange` names offline owners from the server's profile cache.

The feature wanted most after the required ones. Players post buy and sell orders at their own prices.

- Sell orders move items into escrow; buy orders move money into escrow (`deduct`). Orders match automatically, price-time priority, partial fills.
- Proceeds and items go to a **collection box** in the Exchange tab (works for offline players; never drop items).
- Config: `exchange_enabled` (default false), `exchange_tax` (fraction taken from the seller, destroyed: a money sink), max open orders per player, order expiry.
- Plain (default-component) items trade on the order book. Items with components (enchanted, damaged, renamed) are traded through listings (decision 7).
- All transaction rules apply. Cancelling returns escrow exactly.

Done when: matched trades move exactly the right items and money with tax applied; cancelling and expiry return escrow; nothing is lost or duplicated across a restart mid-order; spam-clicking reconciles.

Branch: `phase17` (from `phase15`). Decided by the user on 2026-10-31:

1. **Items:** any default-component item, listed in the catalog or not (same `ignored_components` rule; changed by the user on 2026-11-01: the book takes only fresh copies, and items carrying ignored data such as levelled relics are listed instead, so their data is never lost). Coins never. Items locked for a player (Phase 12) are hidden from them and rejected as `NOT_LISTED`.
2. **Money:** a seller's proceeds (less tax) and returned buy escrow go straight to the bank account the order was paid from (team or own, as contracts do; owed if the bank can't be reached). Only items go to the collection box.
3. **Matching price:** a trade fills at the waiting order's price; a bid above the best ask pays the ask and keeps the difference.
4. **Defaults:** `exchange_tax` 0.05, max 10 open orders per player, order expiry in in-game days by the contracts' day count with default 0 (= never), largest order 6,400 units.
5. **Screen:** an Exchange tab (item search, order book with best asks and bids, your orders, collection box) plus an Exchange button in the Market tab's item panel.
6. **Collection box:** unlimited stacks kept with the world; "Take all" moves items only into free main-inventory space.
7. **Listings** (user, 2026-10-31): items with their own components are listed, not put on the book: one stack's worth of one exact item at a fixed price each; buyers take all or part at that price into their collection box; the seller pays `exchange_tax`. Listings share `max_open_orders` with orders. Containers (shulker boxes, bundles) only when empty. Coins and locked items never.
8. **Leaderboard** (user, 2026-10-31): a player's total includes money held by their open buy orders and payments owed to them (not team accounts').
