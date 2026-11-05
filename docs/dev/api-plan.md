# Phase 18 — Add-on API (plan, not started)

Goal (user, 2026-11-05): let the user and other people build add-ons on Dynamic-Economy, and write proper API documentation for them. This file holds the survey of what exists today, a proposed scope, and the decisions the user still has to make. The scope and the open decisions are proposals: confirm them with the user before building.

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

## 4. Open decisions for the user

Ask these before building:

1. **Scope and order.** All of items 1–4, or a subset? Build in steps (money commands → publishing → events → `api` package), each tested and approved?
2. **Publishing.** JitPack, Modrinth Maven, a GitHub Packages Maven, or several? Is or will the mod be on Modrinth or CurseForge? Maven coordinates (`group`, `artifactId`)?
3. **Version and stability.** Next version (0.5.0?); what counts as a breaking change; whether `@ApiStatus.Experimental` is used for a first release.
4. **Money command shape.** `/market admin money …`, `/bank admin …` or something else; permission level; whether `give` to an offline player is allowed (team or own account, owed if the bank can't be reached).
5. **`pay` for offline players.** Should API payments use the existing owed-payout store, or fail when the bank can't be reached?
6. **Events.** Post-change only, or also a cancellable pre-trade event? Event class names and package.
7. **Test hooks and public mutators.** Leave them and mark them `@ApiStatus.Internal`, narrow their visibility where possible, or move the GameTests out of `src/main` (a bigger change)?
8. **Docs.** File name and place for the add-on guide (for example `docs/api.md`); whether to include an example add-on project in the repo.
9. **KubeJS.** Out of scope, or worth a small binding or events plugin later?

## 5. First task in the new session

Per CLAUDE.md: read the code on `api`, re-check the line references above against it, report gaps or differences, then walk the user through section 4 and wait for their answers before building.
