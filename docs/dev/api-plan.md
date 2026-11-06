# Phase 18 — Add-on API (plan)

Goal (user, 2026-11-05): let the user and other people build add-ons on Dynamic-Economy, and write proper API documentation for them. This file holds the survey of what exists today, a proposed scope, and the decisions the user still has to make. The user answered the open decisions on 2026-11-05 (section 4); section 5 is the design built from them.

Branch: `api` (from `main` at c5af065, v0.4.2 plus cleanup).

## 1. What exists today (survey of 2026-11-05)

A multi-agent survey of `main` at c5af065: one reader per area, each report checked by a second reviewer. Line numbers are from that commit and will drift, so re-check them before relying on one.

**Bottom line.** Data and commands are the supported way to integrate. There is no Java API: no `api` package, no events posted (the mod only listens, `DynamicEconomy.java:64-123`), no IMC, no published artifact (no `maven-publish` in `build.gradle`), and no stability promise.

### Without Java

- **Price files.** `data/<ns>/market/**/*.json` (`MarketCatalog.java:59`, loader `:406-509`).
  - `"required_mods": [...]` skips the whole file quietly when a mod is missing (`:413-432`). NeoForge's `neoforge:conditions` is not read.
  - Files that list the same item merge field by field. `"replace": true` starts the entry over.
  - Only the top pack's copy of a given file ID is read (`:175`), so an add-on that writes `data/dynamic_economy/market/create.json` silently hides the whole built-in Create file. Add-ons must use their own namespace.
  - Unknown keys are ignored without a warning.
- **Classes.** `data/<ns>/market_classes/<name>.json`, allowed fields in `ItemClasses.java:26-28`. A bare class name means `dynamic_economy:`.
- **Pack priority.** Built-in `default_prices` (`Pack.Position.BOTTOM`, `DynamicEconomy.java:179`) < a mod's own jar data (`mod/<modid>` counts as built-in, `MarketCatalog.java:225-227`) < `config/dynamic_economy/catalog/` < world datapacks, OpenLoader and KubeJS. So a mod can ship default prices for its own items, and packs and servers can override them.
- **Commands.**
  - `/market admin set/setprice/setstock/tag/reload/…` need level 2. They validate, roll back a rejected edit, write the audit log and sync clients, so they are the safe way for KubeJS or a mod to change prices or stock.
  - `/market admin export/suggest/import` need level 4.
  - `/market sell/buy` return the quantity traded, which works with `execute store result`.
  - `/bank` has only `team` subcommands. **No command reads, gives or takes money.**
- **Other hooks.**
  - The `-Ddynamiceconomy.skipDefaultPrices=true` launch flag; `/datapack disable "mod/dynamic_economy:default_prices"`.
  - The `dynamic_economy:coin_exchange` recipe serializer (own-bank worlds only).
  - Item IDs: `market_terminal`, `bank_card` and the eight coins.
- **No coin tags.** `Coins.isCoin` hard-codes the coin list (`Coins.java:52-55`).
- **KubeJS** has no bindings: it can only write JSON, edit item tags and run commands.

### Java (all internal, no stability promise)

| Entry point | Notes |
|---|---|
| `MarketService.quote(MinecraftServer, Item)` `:93` | Read-only, thread not checked. Ignores ProgressiveStages hiding (`MarketVisibility.hidden(player, item)` `:23`). |
| `MarketService.trySell/tryBuy(ServerPlayer, Item, int, OptionalLong)` `:124,183` | Best write entry point: thread checked (`:306`), every check before any change, saves its data, records `TradeHistory`. Doesn't sync clients, write the audit log or check access. |
| `WorldBank.of(server)` → `Bank.accountOf(UUID)`/`activeAccount(ServerPlayer)` → `Account.balance/deposit/deduct` | Works with both banks, offline players included. **No thread check** (Numismatics stores accounts in a plain `HashMap`); amounts are `int`; no audit or sync. |
| `BankService.depositAll/withdraw/withdrawCoins` | Safe (thread checked, never drops items). Coins only. |
| `PayoutService.payee` `:24` | Useful. `pay` `:48` can't be used from outside: `OwedPayouts`' constructor is package-private. |
| `ContractService.view/claim/deliver/abandon`, `ExchangeService.post/buyListing/cancel/collect` | Thread checked, every check first. Callers must call `MarketSync.broadcast*`. No way to post a custom contract. |
| `Leaderboard.viewFor`, `MarketCatalog.INSTANCE.get/entries`, `ClientMarketState` getters | Reads. |
| `MarketSync.*`, `MarketMenu.open*`, `AuditLog.record` | Client refresh, opening the screen, audit lines. |

**Hazards.**

- Public mutators that skip every rule: `MarketStockData.setStock/setFactor/recordTrade`, `ContractBoard.update`, `ExchangeData.addToBox` (creates items), `OwedPayouts.owe` (creates money), `MarketOverrides.setItemField`, `MarketCatalog.setOverrides`, `OwnBank.get/credit`, `NumismaticsBridge.bank`, `BankSwitch.switchTo`.
- Mutable state leaks out of `MarketStockData.history()`, `ExchangeData.boxes()` and live listing `ItemStack`s.
- Test hooks ship in the jar (`gametest` is in `src/main`): `MarketCatalog.replaceForTesting` (could list coins), `MarketVisibility.setTestLocks`, `PayoutService.payOwedForTesting`, `OwedPayouts.retryNowForTesting`, `NumismaticsBridge.sharedAccountForTest`, `MarketMenu.forCommand`.
- `main` and the `v0.4.2` tag both say 0.4.2, but `main` removed the public `CatalogEntry.isFresh`.

### Missing for add-on support

- No events for trades, price changes, contracts, exchange fills or catalog reloads.
- No money commands, and no safe pay/charge call.
- No hook for hiding items from players (only ProgressiveStages can).
- No custom banks: `BankKind` is a closed enum, sent over the network by ordinal (`MarketPayloads.java:147-148`).
- No player-aware quote, and no trades that don't come from a player's inventory (containers, automation).
- No custom contracts.
- Nothing to compile against, and no add-on docs (`docs/architecture.md` is written for contributors).
- With `maven-publish` and no explicit `artifactId`, the coordinates would default to `dynamiceconomy:dynamic-economy` (from `settings.gradle:11`).

## 2. Proposed scope, ranked by value for effort

1. **Money commands.** `/market admin money balance|give|take <player> <amount>`, level 2.
   - Go through `WorldBank.of(server)`. `deduct` is the only check `take` needs.
   - Write `AuditLog.record`, call `MarketSync.sendPlayerState`, and return the amount or balance so `execute store` works.
   - This is an admin power that creates money, not a market payout.
2. **Something to compile against.**
   - `maven-publish` with `java { withSourcesJar(); withJavadocJar() }` and an explicit `artifactId`.
   - JitPack (`jitpack.yml` with `jdk: openjdk21`), or Modrinth's Maven once the mod has a Modrinth project.
   - Bump the version whenever the API changes.
3. **Events posted after each change** on `NeoForge.EVENT_BUS`, each fired as the last statement so a listener that throws can't leave half a transaction:

   | Event | Where to fire it |
   |---|---|
   | `MarketTradeEvent` | after `MarketService.java:173` (sell) and `:239` (buy) |
   | Contract events (claimed, delivered, completed, abandoned, expired) | `ContractService.java:258-263`, `:314-315`, `:344-346`; `settleHeld` `:172-187` |
   | Exchange fill and listing sold | `ExchangeService.match` `:267-283`; `:402-403` |
   | Catalog reloaded | end of `DynamicEconomy.catalogReloaded` `:135-141` |

   - A cancellable pre-trade event needs a new `Result` value with a lang key. Only add it if someone asks.
   - Skip a balance-change event: it would miss Numismatics' own blocks.
4. **A `dynamiceconomy.api` package.** A thin facade that adds the thread check, client sync and `long`→`int` range handling; everything else marked `@ApiStatus.Internal`.
   - Player-aware `quote`.
   - `sell`/`buy` wrapping `trySell`/`tryBuy` plus sync.
   - `balance`, `charge` (every check first), and `pay` (owed if offline, with an audit line).
   - `addVisibilityRule(id, BiPredicate<ServerPlayer, Item>)` plus `refreshVisibility`.
   - `isCoin`, money formatting, and the world's bank kind.
5. **Later, only if asked.** Selling from containers or `IItemHandler`; custom contracts (payout still capped below the market buy price, as `ContractPricing` does); third-party banks (needs `BankKind` and the network format changed, so don't promise it).

**Rules the API must keep** (CLAUDE.md transaction rules):

- Server main thread only.
- Every check before any change.
- Money moves only through the `Bank` interface.
- Coins are never listable.
- Buying N then selling N never profits. Document that `pay` creates money: add-ons that swap items for money must go through `sell`.

## 3. API documentation (deliverable)

- **A guide for add-on authors**, separate from `docs/architecture.md` (which is for contributors). It covers:
  - how to depend on the mod (repository and coordinates, NeoForge `[[dependencies]]` entry, optional versus required);
  - data-only integration (price files in your own namespace, `required_mods`, classes, pack priority, commands);
  - the Java API (each method, thread rules, results, events, and what is internal);
  - the version and stability policy, and a short worked example add-on.
- **Javadoc** on every public type and method in the `api` package, published as a javadoc jar.
- Links from `README.md` and `docs/admin-guide.md`; datapack details shared with the admin guide rather than duplicated.

## 4. Decisions (user, 2026-11-05)

1. **Scope.** All of items 1–4, built in one go and reported once at the end.
2. **Publishing.** JitPack: `com.github.The-Azotus:dynamic-economy:<tag>`, with sources and javadoc jars. Modrinth Maven can be documented once the mod is listed there.
3. **Version.** The first API release is 0.5.0. The `api` package is `@ApiStatus.Experimental`: it may still change in a 0.x minor release, and every change is listed in the add-on guide's changelog. It becomes stable at 1.0.
4. **Money commands.** `/market admin money balance|give|take <player> [<amount>]`, level 2 like the other admin edits. They work on a player's own account, online or not. If the bank can't be reached the command fails; nothing is owed.
5. **`pay` when the bank can't be reached:** the money is owed, as contract payouts are. It is paid once the bank is back, and it counts on the leaderboard meanwhile.
6. **Events.** Only after a change, never cancellable. Package `dynamiceconomy.api.event`.
7. **Internals.** Every package except `api` gets `@ApiStatus.Internal` (on its `package-info.java`). Visibility is narrowed where the GameTests allow it. GameTests stay in `src/main`.
8. **Docs.** `docs/api.md` plus javadoc on the `api` package. No example project. KubeJS stays out of scope.

## 5. Design

Everything in `dynamiceconomy.api` keeps the transaction rules in CLAUDE.md. Every method that touches the market or the bank checks that it runs on the server thread and throws `IllegalStateException` otherwise.

### `DynamicEconomyApi` (static methods)

| Method | Behaviour |
|---|---|
| `Optional<MarketQuote> quote(MinecraftServer, Item)` | The item's market terms for everyone; ignores visibility. |
| `Optional<MarketQuote> quote(ServerPlayer, Item)` | The same, but empty if the item is hidden from the player. |
| `List<Item> listedItems(MinecraftServer)` | Every listed item, for every player. |
| `TradeResult sell(ServerPlayer, Item, int)` / `buy(...)` | `MarketService.trySell/tryBuy` at the current price, then the item's row goes out to every player and the player's balance to their open screen. Access mode (block-only) isn't checked: the add-on decides who may trade. |
| `OptionalLong balance(ServerPlayer)` | The account the player trades with (their team's when they chose it). Empty if the bank can't be reached. |
| `OptionalLong balance(MinecraftServer, UUID)` | A player's own account, online or not. |
| `PaymentResult charge(ServerPlayer, long, ResourceLocation reason)` | Takes money from the account the player trades with: all or nothing. |
| `PaymentResult charge(MinecraftServer, UUID, long, ResourceLocation reason)` | The same from a player's own account, online or not. |
| `PaymentResult pay(ServerPlayer, long, ResourceLocation reason)` | Pays into the account the player trades with (owed to that team while it exists, if the bank can't be reached). |
| `PaymentResult pay(MinecraftServer, UUID, long, ResourceLocation reason)` | Pays into a player's own account, or owes it. |
| `Component formatMoney(MinecraftServer, long)`, `BankType bankType(MinecraftServer)`, `boolean bankAvailable(MinecraftServer)`, `boolean isCoin(Item)` | Helpers. |
| `addVisibilityRule(ResourceLocation id, BiPredicate<ServerPlayer, Item> hides)`, `removeVisibilityRule(ResourceLocation)` | Thread-safe; may be called while mods load. A rule that throws is removed and logged. Catalogs are resent on the next tick when a server is running. |
| `refreshVisibility(@Nullable ServerPlayer)`, `isHidden(ServerPlayer, Item)` | Call `refreshVisibility` when a rule's answer changes (null: anyone). |

- Amounts are bits, `1 ≤ amount ≤ Integer.MAX_VALUE`. Otherwise the result is `INVALID_AMOUNT` or `TOO_LARGE`.
- `pay` and `charge` write one audit line each (actor `api:<reason>`). An online player's open market screen gets the new balance.
- `pay` creates money. The guide says so: swapping items for money goes through `sell`, so buying N then selling N can't profit.
- Owed API payments are kept in their own saved data (`ApiPayments`, `dynamic_economy_api_payments`), paid on the server tick with the existing retry wait, and counted on the leaderboard.

### Types

- `MarketQuote(Item item, long basePrice, long sellPrice, long buyPrice, OptionalLong stock, boolean canSell, boolean canBuy, double factor)`. Prices are for the next single unit; `stock` is empty for unlimited items.
- `TradeResult(MarketResult result, int quantity, long unitPrice, long total)` and `PaymentResult(MarketResult result, long amount, boolean owed)`, each with `ok()`.
- `MarketResult`: the API's own enum: `OK, NOT_LISTED, SELL_DISABLED, BUY_DISABLED, NOT_ENOUGH_ITEMS, NO_STOCK, NO_FUNDS, NO_SPACE, PRICE_CHANGED, TOO_LARGE, INVALID_QUANTITY, BANK_UNAVAILABLE, INVALID_AMOUNT`. It is mapped from `MarketService.Result` by name, so internal values can change without breaking it.
- `BankType { OWN, NUMISMATICS }`.

### Events (`dynamiceconomy.api.event`, on `NeoForge.EVENT_BUS`, not cancellable)

| Event | Fired |
|---|---|
| `MarketTradeEvent` (server, player, item, buy?, quantity, unit price, total) | After every market trade: screen, command or API. |
| `ContractEvent.Claimed / Delivered / Completed / Abandoned / Expired` (server, contract ID, holder UUID, item ID, units, delivered so far, plus amounts) | After each action. `Expired` covers board replacement and contracts being turned off, with the deposit lost or returned. |
| `ExchangeTradeEvent` (server, buyer, seller, item stack (one unit), quantity, unit price, value, tax, from a listing?) | Once per fill of an order, and per purchase from a listing. |
| `CatalogReloadedEvent` (server) | At the end of every catalog rebuild. |

- Every event is posted after its change is complete. When one action changes several things (a board replacement, an order filling against several others), its events are collected and posted after the last change, never in the middle.
- A listener that throws is logged and ignored, so it can't undo or interrupt market work.

### Commands

- `/market admin money balance <player>` returns the balance (capped at `Integer.MAX_VALUE`) for `execute store`.
- `give <player> <amount>` and `take <player> <amount>` return the amount moved. They act on a player's own account, online or not (`<player>` is a game profile), and on one player at a time.
- Each writes an audit line with the balance before and after, and refreshes an open market screen.

### Build

- `maven-publish` with `withSourcesJar()` and `withJavadocJar()`. The javadoc covers only `dynamiceconomy.api` and its subpackages.
- `jitpack.yml` uses JDK 21. Published coordinates: `com.github.The-Azotus:dynamic-economy:<tag>`.
- `mod_version` goes to 0.5.0 at release, as before.
