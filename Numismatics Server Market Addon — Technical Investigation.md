# Numismatics Server Market Addon — Technical Investigation

Oct 22, 2026 · @Patrick Tervo

Recommendation: build a separate companion mod (Option B) that depends on Numismatics only, not on Create: Stock Market. Complexity is medium.

The deciding finding: Create: Stock Market contains no transaction code at all. It is a read-only dashboard that indexes player vendors and draws charts. Nothing in it buys, sells, holds stock or moves money, so forking it saves little and inherits an AGPL-3.0 license. Numismatics, by contrast, exposes a usable bank backend (`GlobalBankManager`, `BankAccount.deposit` / `deduct`) that an addon can call directly.

Scope of verification: both repositories were cloned into a temporary directory (`/tmp/investigation`) and the source was read directly. Numismatics was read at branch `1.21.1/dev`, commit `e57f663` (2026-08-29). Stock Market was read at `master`, commit `38d42a4` (2026-10-04). Nothing was built, run or installed, and no files in your modpack were touched. Claims marked **unverified** below are inferences or rely on outside knowledge.

## 1. Verified repository URLs and project identities

Both URLs are valid and match the intended mods, confirmed from their build metadata rather than names alone.

| Repository | Identity evidence | Branch read | Notes |
| --- | --- | --- | --- |
| [Deokma/create-stockmarket](https://github.com/Deokma/create-stockmarket) | `gradle.properties`: `mod_id = stockmarket`, `mod_name = Create: Stock Market`, `mod_version = 1.1.1`, author Deokma | `master` @ `38d42a4` | README still says v0.4.2 and is stale. Branches: `fix/1.1.1-community-bugs`, `fix/issue-2-price-display`, `fix/issue-5-translations`, `l10n_master` (all merged into master) |
| [Layers-of-Railways/CreateNumismatics](https://github.com/Layers-of-Railways/CreateNumismatics) | `gradle.properties`: `maven_group = dev.ithundxr.createnumismatics`, `curseforge_id = 889722`, `modrinth_id = Jdbbtt0i` | `1.21.1/dev` @ `e57f663` | Default branch is `1.20.1/dev`, which is the wrong target. The 1.21.1 lines are `1.21.1/dev` (1.1.0) and `1.21.1/1.0.x` (1.0.20) |

No git tags or GitHub releases were readable, and the GitHub API was rate-limited, so I could not confirm which commit the published 1.1.0 jar was built from. **Unverified:** that `1.21.1/dev` matches the released 1.1.0 jar exactly. Verify by decompiling the jar from Modrinth and diffing `VendorBlockEntity` and `BankAccount`.

## 2. Supported versions, dependencies and licenses

Both projects target Minecraft 1.21.1 on NeoForge 21.1.228 and Create 6.0.10, so neither blocks your pack.

| Item | Numismatics (`1.21.1/dev`) | Stock Market (`master`) |
| --- | --- | --- |
| Mod version | 1.1.0 | 1.1.1 |
| Minecraft | 1.21.1 | 1.21.1 (`[1.21.1,1.22)`) |
| NeoForge | 21.1.228 (build) | 21.1.228 (build); requires `[21.1.206,)` |
| Create | 6.0.10-280 | 6.0.10-281 (build); requires `[6.0.9,6.1.0)` |
| Other deps | Ponder 1.0.82, Flywheel 1.0.6, Registrate MC1.21-1.3.0+67; optional CC: Tweaked 1.120.2 (enabled), Carry On, Create Crafts & Additions, Sable (disabled) | Numismatics `1.1.0+neoforge-mc1.21.1` from Modrinth Maven (optional, `[1.0.19,)`); Tradeworks 1.0.7, Xaero's Minimap, JEI/REI (optional) |
| Loaders | `enabled_platforms = neoforge` on this branch | Architectury: common + neoforge + fabric |
| License file | LGPL-3.0 | AGPL-3.0 |

License obligations (not legal advice):

- **Numismatics, LGPL-3.0.** An addon that only calls its classes can use any license you like. A fork of Numismatics itself must stay LGPL-3.0 and publish its modified source.
- **Stock Market, AGPL-3.0, with a conflict.** The `LICENSE` file was MPL-2.0 in commit `94cfcdd` (2026-05-11), then replaced with AGPL-3.0 in `ae1bffc` (2026-05-15). But `gradle.properties` still declares `mod_license = MPL 2.0`, which is what the mod jar reports. Treat it as AGPL-3.0 until the author clarifies. A fork, or any addon that copies its code, would have to be AGPL-3.0, including the network clause: players on your server would be entitled to the source. Publishing the fork on GitHub satisfies this.
- **Create** is outside this investigation's scope. **Unverified:** it is MIT-licensed, which imposes no meaningful obligation on an addon.

## 3. Create: Stock Market architecture

Stock Market is an observer, not a market: it indexes other people's shops and displays them, and never executes a trade. It is about 1,650 lines of common code plus a NeoForge module.

**Vendor discovery (verified).** [`VendorIndexer.indexVendor`](https://github.com/Deokma/create-stockmarket/blob/38d42a4cfbb62bacd159bf8ad9a45e0c4c540279/neoforge/src/main/java/by/deokma/stockmarket/neoforge/compat/VendorIndexer.java#L49-L87) runs on chunk load and block place/break. It checks `instanceof VendorBlockEntity`, reads the owner, traded item, `getMode()` and `getTotalPrice()`, and writes a `ShopEntry` into `ShopSavedData`, keyed by `"<dimension>|x,y,z"`.

**Fragility evidence (verified).** [`NumismaticsVendorAccess`](https://github.com/Deokma/create-stockmarket/blob/38d42a4cfbb62bacd159bf8ad9a45e0c4c540279/neoforge/src/main/java/by/deokma/stockmarket/neoforge/compat/NumismaticsVendorAccess.java#L16-L48) resolves vendor methods by reflection. Its comment says Numismatics 1.1.0 renamed `getSellingItem()` to `getDisplayItem()` / `getFilterItem()`, and the direct call crashed servers with `NoSuchMethodError` when vendor chunks loaded. Any addon touching Numismatics internals carries the same risk.

**Sales tracking is inferred, not hooked (verified).** [`VendorTransactionTracker`](https://github.com/Deokma/create-stockmarket/blob/38d42a4cfbb62bacd159bf8ad9a45e0c4c540279/neoforge/src/main/java/by/deokma/stockmarket/neoforge/compat/VendorTransactionTracker.java#L58-L118) snapshots a vendor's stock on `PlayerInteractEvent.RightClickBlock`, then diffs it on the next `ServerTickEvent.Post`. Numismatics fires no transaction event, which is why it has to guess.

**Persistence (verified).** `ShopSavedData`, `PriceHistorySavedData` and `TradeStatsSavedData` all extend vanilla `SavedData`, stored in `server.overworld().getDataStorage()` via `computeIfAbsent`. Price history is a snapshot every 10 minutes, 144 entries per item.

**UI and networking (verified).** `ShopListScreen` (1,008 lines) and `StockMarketScreen` extend plain `Screen`, not `AbstractContainerMenu`. [`NetworkHandler`](https://github.com/Deokma/create-stockmarket/blob/38d42a4cfbb62bacd159bf8ad9a45e0c4c540279/neoforge/src/main/java/by/deokma/stockmarket/neoforge/network/NetworkHandler.java#L20-L70) registers only request packets (client to server) and data or open-screen packets (server to client). There is no packet that changes server state. The `/shoplist` command (`ShopListCommand`) opens the screen through an injected sender, a pattern your `/market` command could copy conceptually.

What is reusable: the general patterns (SavedData, request/response packets, a sortable list screen). Copying the actual code brings the AGPL license with it. Nothing reusable exists for stock, payments or validation.

## 4. Numismatics currency and vendor findings

Numismatics has no formal API package or transaction events, but its bank backend is simple, server-side and callable from an addon. Paths below are relative to `common/src/main/java/dev/ithundxr/createnumismatics/` at commit `e57f663`.

**Currency representation (verified).** All value is counted in spurs, the base unit. [`Coin`](https://github.com/Layers-of-Railways/CreateNumismatics/blob/e57f66350e81e01d3e462a3dc06f2a0967619213/common/src/main/java/dev/ithundxr/createnumismatics/content/backend/Coin.java#L54-L59) defines SPUR 1, BEVEL 8, SPROCKET 16, COG 64, CROWN 512, SUN 4096. Physical coins are `CoinItem` stacks; `CoinItem.extract(...)` removes coins from an inventory, simulating first.

**Bank storage (verified).** `Numismatics.BANK` is a static [`GlobalBankManager`](https://github.com/Layers-of-Railways/CreateNumismatics/blob/e57f66350e81e01d3e462a3dc06f2a0967619213/common/src/main/java/dev/ithundxr/createnumismatics/content/backend/GlobalBankManager.java#L84-L119) holding `Map<UUID, BankAccount>`. It persists through [`BankSavedData`](https://github.com/Layers-of-Railways/CreateNumismatics/blob/e57f66350e81e01d3e462a3dc06f2a0967619213/common/src/main/java/dev/ithundxr/createnumismatics/content/backend/BankSavedData.java#L63-L67), key `numismatics_bank` in overworld data storage. `getAccount(Player)` creates the account on first use and grants the configured starter coins. `getAccount(UUID)` logs an error if called from the render thread.

**Transfers and validation (verified).** In [`BankAccount`](https://github.com/Layers-of-Railways/CreateNumismatics/blob/e57f66350e81e01d3e462a3dc06f2a0967619213/common/src/main/java/dev/ithundxr/createnumismatics/content/backend/BankAccount.java#L201-L298), `deposit(int spurs)` rejects negatives and spills anything above `MAX_STANDARD_BALANCE` (`Integer.MAX_VALUE >> 1`, about 1.07 billion spurs) into a `long additionalBalance`. `deduct(int spurs, boolean force)` returns `false` without changing anything if the total is insufficient. Both call `markDirty()`. There is no locking; safety comes from running on the server thread.

**Payment sources (verified).** [`IDeductable.get`](https://github.com/Layers-of-Railways/CreateNumismatics/blob/e57f66350e81e01d3e462a3dc06f2a0967619213/common/src/main/java/dev/ithundxr/createnumismatics/content/backend/IDeductable.java#L44-L153) turns a held bank card or authorized card into a deductable account, checking binding and authorization. Without a card, vendors fall back to coins in the inventory.

**Vendor transactions (verified).** [`VendorBlockEntity.tryTransaction`](https://github.com/Layers-of-Railways/CreateNumismatics/blob/e57f66350e81e01d3e462a3dc06f2a0967619213/common/src/main/java/dev/ithundxr/createnumismatics/content/vendor/VendorBlockEntity.java#L863-L1045) dispatches on `Mode`. `trySellTo` (L870) charges the player through `SliderStylePriceBehaviour.deduct`, then hands over items. `tryBuyFrom` (L963) checks the held stack, vendor space and vendor funds via `deductFromSelf`, then shrinks the hand stack, stores the item and pays physical coins via `pay(player)`. Payment comes from the vendor before items move, so it is effectively atomic on the server thread.

**Creative Vendor (verified).** There is no separate class. It is a `VendorBlock` with `isCreativeVendor = true`, registered as `CREATIVE_VENDOR` in `NumismaticsBlocks`. In code it short-circuits: `hasStock()` and `getSpace()` always succeed, it never needs funds, and `addBoughtItem` returns early, so items sold to it are deleted. It is an infinite, stockless server shop, the opposite of the finite shared stock you want.

**Payouts can drop on the ground (verified).** `pay(player)` and `ItemUtil.givePlayerItem` (L145) drop items at the player's feet when the inventory is full. That is not a dupe, but it violates your "fail cleanly if the inventory cannot accept" rule.

**Reusing the vendor GUI (verified, negative).** `VendorMenu extends MenuBase<VendorBlockEntity>`, so the screen requires a real vendor block entity. A `/market` command cannot open it without a block in the world, and its layout serves one item per block, not a catalog.

**Useful extras (verified).** CC: Tweaked integration is enabled in this build. `VendorPeripheral` exposes `setTotalPrice(spurs)` and `setCoinAmount`, so a ComputerCraft script can reprice ordinary vendors. Admin commands (`/numismatics pay`, `deduct`, `view`) require permission level 2.

## 5. Recommended approach

Option B wins: a standalone mod that depends on Numismatics, talks to it through one bridge class, and does not depend on Stock Market. It is the only option where you own all the transaction code without inheriting someone else's release schedule or license.

| Criterion | A: Fork Stock Market | B: Companion mod | C: Modify Numismatics |
| --- | --- | --- | --- |
| Development effort | Medium-high. Stock, transactions, menus and validation are all new; you also carry 3,000+ lines of dashboard code | Medium. Same new code, nothing to carry | High. `VendorBlockEntity` is 1,155 lines built around one item per block; reworking it into a catalog is invasive |
| Compatibility risk | Medium. Inherits its reflection shims, Tradeworks and Xaero hooks | Low-medium. One bridge class touches about 5 Numismatics methods | High. Your fork replaces Numismatics, so every other addon that expects upstream may break |
| GUI and transaction reuse | Screens and packet patterns only; zero transaction code exists | Can reuse Numismatics bank calls and coin rendering; UI is new | Full reuse of vendor code, but it is the wrong shape |
| Maintenance burden | You track two upstreams (Stock Market and Numismatics) | You track Numismatics' bank API only, which has been stable | You must rebase on every Numismatics release by hand |
| 1.21.1 NeoForge support | Yes | Yes | Yes |
| Licensing | AGPL-3.0 (with an MPL/AGPL metadata conflict); source must be offered to server players | Your choice of license; no obligations from linking to LGPL | LGPL-3.0; modified source must be published |

Why not Option A: its value is the dashboard, and you can still install Stock Market alongside your addon for player-vendor charts. Why not Option C: the Creative Vendor is the closest built-in feature, but its infinite stock is hard-coded across 11 `isCreativeVendor()` branches.

Two cheaper alternatives worth knowing, both short of your requirements:

- **No code:** admin-owned regular vendors (not creative) already have finite stock and real payments, but each block has its own separate inventory. Stock is not shared.
- **Scripted:** CC: Tweaked can reprice those vendors through `VendorPeripheral`. That gives dynamic prices but still not shared stock.

The addon also fits your stated plan of supply-and-demand pricing with transaction fees: the fee is one more term in the price function in section 6.

## 6. Proposed architecture and transaction flow

All market state lives in one server-side `SavedData`; clients only see snapshots and send requests, and every transaction runs on the server thread so two players can never interleave.

**Components (proposed, not yet built):**

| Component | Responsibility |
| --- | --- |
| `MarketCatalog` | Item list, base price (spurs), markup, flags, per-category overrides. Loaded from a datapack JSON with a reload listener, so prices change without restarting |
| `MarketStockData extends SavedData` | `Map<ResourceLocation, Long>` of stock plus dynamic-pricing state. Stored as `moneypack_market` in overworld storage, the same mechanism Numismatics and Stock Market use. Kept separate from the catalog so a price edit never wipes stock |
| `MarketService` | The only code that mutates stock or money. Pure server-side, holds the validation and ordering rules below |
| `NumismaticsBridge` | The only class importing Numismatics: `Numismatics.BANK.getAccount(Player)`, `BankAccount.deposit(int)`, `deduct(int, boolean)`, `getBalance()`. Isolates future API breaks to one file |
| `MarketMenu` + `MarketScreen` | A menu with no item slots, opened by block or `/market`. Lists catalog rows with stock, buy price, sell price |
| Packets | Client to server: `BuyRequest(item, qty, quotedPrice)`, `SellRequest(item, qty, quotedPrice)`. Server to client: full snapshot on open, per-item deltas to players with the screen open |

**Sell flow (player sells to market):**

1. Packet handler runs on the main thread (`IPayloadContext.enqueueWork`). Reject if the item is not in the catalog or selling is disabled for it.
2. Recompute the unit price server-side. Reject if it differs from `quotedPrice`, which prevents paying a stale price after someone else moved it.
3. Count matching stacks in the player's main inventory. Reject if fewer than `qty`.
4. Remove exactly `qty` items. Removal cannot partially fail because the count was just checked on the same tick.
5. `stock += qty`, mark `MarketStockData` dirty.
6. `bankAccount.deposit(qty × price)`. Deposit cannot fail; overflow goes into `additionalBalance`.
7. Send the result and the stock delta.

**Buy flow (player buys from market):**

1. Main thread. Reject if not in the catalog or buying is disabled.
2. Recompute the price; reject on mismatch with the quote.
3. Reject if `stock < qty`.
4. Simulate inserting `qty` items into the player inventory. Reject if they will not fit, so nothing ever drops on the ground.
5. `bankAccount.deduct(total, false)`. If it returns `false`, stop; nothing has changed yet.
6. `stock -= qty`, mark dirty, insert the items.
7. Send the result and the stock delta.

In both flows every check runs before anything changes, and no step after the first change can fail. Compute totals in `long` and reject anything above `Integer.MAX_VALUE`, because `deposit` and `deduct` take `int`.

**Pricing (all in spurs):**

- One price per item for the whole server; team size never affects market prices.
- Market pays the player: `base × s`, where `s` is the item's supply-and-demand factor.
- Market charges the player: `ceil(base × s × (1 + markup))`, markup 0.5 by default.
- `s` starts at 1.0. Player sales push it down (supply), player purchases push it up (demand), and it drifts back toward 1.0 on a timer, within a configurable floor and ceiling.
- A transaction fee is one more multiplier on either side.
- MVP uses fixed prices with `s = 1`; supply and demand is a later phase.

**Payment choice:** bank account only (decided). Physical coins would need `CoinItem.extract` plus change-making, and payouts can overflow the inventory. Coin support can come later.

## 7. Likely source files and classes

Under Option B, no existing file in either repository changes; everything below is new code in your own mod, plus a read-only dependency on Numismatics.

**New files (proposed package `moneypack.market`):**

| File | Purpose |
| --- | --- |
| `MoneyPackMarket.java` | `@Mod` entry point; registers menus, packets, commands, reload listener |
| `catalog/MarketCatalog.java`, `catalog/CatalogEntry.java` | Codec-backed JSON under `data/<ns>/market/*.json`; `SimpleJsonResourceReloadListener` |
| `data/MarketStockData.java` | `SavedData` with `save` / `load`; stock map and pricing state |
| `service/MarketService.java` | `trySell`, `tryBuy`, `quote`; returns a result enum (OK, NOT\_LISTED, NO\_STOCK, NO\_FUNDS, NO\_SPACE, PRICE\_CHANGED) |
| `service/PriceModel.java` | Fixed pricing now; supply decay and recovery later |
| `compat/NumismaticsBridge.java` | Sole importer of `dev.ithundxr.createnumismatics.*` |
| `menu/MarketMenu.java`, `client/MarketScreen.java` | Slotless menu and catalog list screen |
| `network/*.java` | Buy/Sell request, snapshot and delta payloads, via NeoForge `RegisterPayloadHandlersEvent` |
| `command/MarketCommand.java` | `/market` (permission 0) opens the menu; `/market admin setstock`, `setprice`, `reload` (permission 2) |
| `block/MarketTerminalBlock.java` | Optional, Phase 3 |

**Numismatics classes the bridge calls (all verified to exist at `e57f663`):**

- `dev.ithundxr.createnumismatics.Numismatics.BANK` (static `GlobalBankManager`)
- `GlobalBankManager.getAccount(Player)` and `getAccount(UUID)`
- `BankAccount.getBalance()`, `deposit(int)`, `deduct(int, boolean)`
- `Coin` (for display and conversions only)
- Later, optionally: `CoinItem.extract(...)` for physical-coin payment and `IDeductable.get(...)` to accept held cards

**If you chose Option C instead,** the change would center on `content/vendor/VendorBlockEntity.java` (`trySellTo` L870, `tryBuyFrom` L963, `hasStock` L802, `getSpace` L826, `addBoughtItem` L1047), plus `VendorBlock`, `VendorMenu`, `VendorScreen` and `NumismaticsBlocks` for a new block variant.

## 8. Technical risks and unresolved questions

The biggest risk is depending on Numismatics internals that carry no stability promise; the second is item-matching rules, which decide whether players can exploit the market.

**Risks:**

| Risk | Evidence | Mitigation |
| --- | --- | --- |
| Numismatics renames bank methods | Happened to vendor methods in 1.1.0 (Stock Market's `NumismaticsVendorAccess`) | One bridge class; catch `LinkageError`; pin a version range in `neoforge.mods.toml` |
| Bank accessed off the main thread | `GlobalBankManager` uses an unsynchronized `HashMap`; no locks in `BankAccount` | Always `enqueueWork` in packet handlers; never touch the bank from async code |
| Exploits via item components | Enchanted, damaged or renamed items share an item ID | MVP: only accept stacks with default components (`isSameItemSameComponents` against a fresh stack) |
| Dupes from desynced inventories | Mods with custom slots (backpacks, Curios) | Only read and write the main inventory; count and remove on the same tick |
| Integer limits | `deposit` / `deduct` take `int` spurs; standard cap about 1.07 billion | `long` math, reject oversized orders, cap quantity per request |
| Infinite money loops | Craft or farm an item cheaply, sell it endlessly | Per-item caps and supply-based price decay (Phase 4); review catalog against farmable items |
| Account side effect | `getAccount(Player)` creates an account and grants starter coins | Harmless, but players get starter money on first market use if they never opened a bank |
| Create integration | Numismatics mixes into Create's `StockTickerInteractionHandler` and `ShoppingListItem` | Your mod does not touch those, so no direct conflict expected (**unverified** at runtime) |

**Unresolved questions:**

- [ ] Does the released Numismatics 1.1.0 jar match `1.21.1/dev` @ `e57f663`? Verify by diffing the decompiled jar.
- [ ] Is the Stock Market license AGPL-3.0 or MPL-2.0? Only matters if you copy its code; ask the author via a GitHub issue.
- [ ] Should catalog entries accept tags or components (e.g. any log, potions)? **Partly decided:** tags for some items, impacts still to investigate. The main impact: a tag can pool *selling* (any log counts), but stock must still be held per item ID, or a buyer receives an arbitrary member of the tag. Also open: whether one tag shares one price and one supply/demand factor. MVP uses exact item IDs; tag-based selling comes in a later phase.
- [x] Should sales pay into the bank account or as coins? **Decided: bank account.** Purchases are assumed to deduct from the bank account as well.
- [x] How should team pricing interact with the market? **Decided: it doesn't.** The market is one server-wide market, and prices depend only on supply and demand.

## 9. Estimated complexity

Medium overall: the money side is easy because Numismatics already provides the bank, and the work concentrates in the GUI and in getting validation right.

| Part | Rating | Reason |
| --- | --- | --- |
| Bank integration | Low | Four public methods, all verified; deposit cannot fail and deduct is all-or-nothing |
| Persistent shared stock | Low | Vanilla `SavedData`, the same pattern both mods use |
| Transaction service and validation | Medium | Straightforward logic, but the exploit cases (components, overflow, stale quotes) need care and tests |
| Catalog and config | Low-medium | Codec plus reload listener; pricing many modded items is a content problem, not a code problem |
| GUI and sync | Medium-high | A scrollable, searchable catalog screen with live stock is the largest single piece; nothing reusable exists |
| Dynamic pricing | Medium | Simple math, but balancing it needs playtesting |

Rough size for the MVP (Phases 1–3 below): about 1,500–2,500 lines of Java. That is an estimate, not a measurement.

## 10. Phased implementation and testing plan

Build the server logic first and test it through commands before any GUI exists, so the risky part is proven early. Each phase ends with a gate you can check.

1. **Phase 0, setup.** NeoForge 1.21.1 MDK, NeoForge 21.1.228, Create 6.0.10, Numismatics 1.1.0 from Modrinth Maven. Gate: dev client launches with all three loaded.
2. **Phase 1, core with commands only.** `MarketCatalog`, `MarketStockData`, `NumismaticsBridge`, `MarketService`. Temporary `/market sell <item> <qty>` and `/market buy <item> <qty>`. Gate: stock survives a restart, and balances change by exactly the expected spurs.
3. **Phase 2, validation hardening.** Component checks, quote checks, quantity caps, `long` overflow guards. Gate: every rejection case in the test list below returns the right result and changes nothing.
4. **Phase 3, GUI and sync.** `MarketMenu`, `MarketScreen`, packets, real `/market`, optional terminal block. Gate: two clients see stock change within one tick of each other's trades.
5. **Phase 4, dynamic pricing.** Supply-and-demand factor (sales lower it, purchases raise it), floors, ceilings, recovery, fees, per-category overrides. Gate: a scripted dump of 10,000 items lowers the price and it recovers on schedule.
6. **Phase 5, pack integration.** Price the catalog for your modlist; optionally add a Stock Market-style history view of your own. Gate: a playtest session with friends.

**Test cases (Phase 1–3):**

- [ ] Sell an unlisted item: rejected, inventory and balance unchanged.
- [ ] Sell more than held: rejected.
- [ ] Sell an enchanted or damaged copy of a listed item: rejected.
- [ ] Buy with insufficient funds: rejected, stock unchanged.
- [ ] Buy more than stock, including from a rare item with zero stock: rejected.
- [ ] Buy with a full inventory: rejected, no item drops.
- [ ] Price changed between screen open and click: PRICE\_CHANGED, nothing moves.
- [ ] Two players buy the last item on the same tick: exactly one succeeds.
- [ ] Spam-click buy and sell 100 times: totals reconcile (stock change equals net items; money change equals net price).
- [ ] Kill the server process (not a clean stop) right after trades: stock and bank agree after restart. Note: both `SavedData` files save on the same autosave, but a crash between them is possible; **unverified** how often this matters in practice.
- [ ] Order large enough to exceed `int` spurs: rejected.
- [ ] Datapack reload while players have the screen open: no crash, prices refresh.

Automate the first nine with NeoForge GameTests using a mock player; run the last three by hand on a dedicated test server.

No files have been changed and nothing was implemented. The temporary clones in `/tmp/investigation` are outside your project and disappear with the sandbox.
