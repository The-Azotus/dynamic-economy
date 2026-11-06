# Architecture

A map of the code for contributors. For what the mod does, read the [admin guide](admin-guide.md) and the [player guide](player-guide.md) first: the code follows them closely, and their terms (catalog entry, class, price factor, contract, listing) are the ones used here. If you're writing a separate mod that uses this one, read the [add-on guide](api.md) instead.

## Building and testing

Java 21 and NeoForge 21.1 for Minecraft 1.21.1. `gradlew build` builds the jar; `gradlew runGameTestServer` runs the GameTests (`src/main/java/dynamiceconomy/gametest`). The optional mods are on the dev runtime by default; `-PwithoutMods=numismatics`, `create` or `ftbteams` (comma-separated) leaves them out, each in its own test world. Run the tests with and without Numismatics before sending a change that touches money: the two banks are different code.

The test world persists between runs, so tests undo what they change (edits, tag rules, config values) and compare stock, prices and money relatively. Tests that need their own state use their own `ExchangeData` or `ContractBoard` rather than the world's.

**Writing a test.** Put it in the feature's class under `gametest`. Each class is marked `@GameTestHolder(DynamicEconomy.MOD_ID)` and `@PrefixGameTestTemplate(false)` and imports `GameTestSupport.*`; each test is a static `@GameTest(template = EMPTY)` method that ends with `helper.succeed()`. From `GameTestSupport`:

- `new Fixture(helper)` gives a fresh player with an empty inventory and an empty bank account.
- `withEntry(entry, body)` swaps one listing while `body` runs, then puts the catalog back; `withFixedPrice(item, body)` does the same with supply and demand turned off.
- A test that writes files makes its own folder with `Files.createTempDirectory` and removes it with `deleteTree` in a `finally`.

The tests expect the GameTest server's setup: the small example catalog, without the built-in prices (`runGameTestServer` sets this up), so run them with `gradlew runGameTestServer`.

**Publishing.** `maven-publish` publishes the mod jar with a sources jar and a javadoc jar; the javadoc covers only the `api` packages, with doclint on, so a broken link or bad HTML in their javadoc fails `gradlew javadoc`. JitPack builds each release tag (`jitpack.yml`) as `com.github.The-Azotus:dynamic-economy:<tag>`. `gradlew publishToMavenLocal` publishes the same coordinates with `mod_version`, to try an add-on against local changes.

## Packages

All code is under `dynamiceconomy`.

| Package | What's in it |
| --- | --- |
| *(root)* | `DynamicEconomy` (entry point and event wiring), `MarketConfig` (the server config), `MarketAccess` (who may use `/market`). |
| `api`, `api.event` | The add-on API, the only public surface: `DynamicEconomyApi` (static methods), its result and value types (`MarketQuote`, `TradeResult`, `PaymentResult`, `MarketResult`, `BankType`) and the events. See [The add-on API](#the-add-on-api). |
| `catalog` | What's listed and how it's priced. `MarketCatalog` loads catalog files and classes from datapacks and `config/dynamic_economy/catalog/`, each file changing only the fields it sets on lower files' entries; `ItemClasses` the class files; `CatalogOverlay` lays tag rules and item edits on top; `CatalogEntry` is one listed item; `CatalogJson` writes an entry out as catalog JSON and lays one layer's fields over another's (the loader's merge, the overlay and `/market admin info` all go through it); `FieldSource` records where each value came from (for `/market admin info`). |
| `service` | The rules. `MarketService` (market trades), `ContractService` + `ContractPricing` (contracts), `ExchangeService` (player exchange), `BankService` (coins in and out of the bank), `PayoutService` (paying players who may be offline). `PriceModel` is the pricing maths, shared with the client. `PriceSuggestion` (`/market admin suggest`), `MarketDiagnostics` (`/market admin diagnose`), `Leaderboard`, `MarketVisibility` (stage locks and add-ons' visibility rules), `ApiEvents` (posts the add-on events), and two small helpers for the screen, `MarketListing` and `OrderMath`. A refused trade returns a `MarketService.Result`; each value needs a `screen.dynamic_economy.result.<value in lower case>` line in `assets/dynamic_economy/lang/en_us.json`, or the screen shows the raw key. |
| `data` | Everything saved with the world, as `SavedData`: `MarketStockData` (stock, price factors, history, money counts), `MarketOverrides` (tag rules and item edits), `ContractBoard`, `ExchangeData`, `TradeHistory`, `OwedPayouts`, `ApiPayments` (add-on payments owed while the bank can't be reached). Plus files outside the save: `AuditLog` and `MarketBackup`. |
| `bank` | Where money lives. `Bank` is the interface everything uses; `WorldBank.of(server)` gives the world's bank, either `OwnBank` or Numismatics' (through `compat/NumismaticsBridge`). Teams (`BankTeam`, `BankTeams`, `OwnTeams`), bank switching (`BankSwitch`) and `CoinMath`. |
| `coin` | The mod's own coin items, the Bank Card and coin recipes. |
| `block`, `menu` | The Market Terminal block and the menu the market screen runs in. |
| `command` | Every command. Most parse arguments and call a service. Some write saved data directly: admin `setstock`, `clearstock` and `prune` change stock (`prune` also deletes factors, history and edits), `resetprice` resets price factors, catalog edits change `MarketOverrides`, `restore` goes through `MarketBackup.apply`, and the own bank's team commands change `OwnTeams`. `AdminMoneyCommands` (`/market admin money`) moves money through `Bank` directly. |
| `network` | `MarketPayloads` (every packet), `MarketNetwork` (registration and handlers), `MarketSync` (what each player is sent, and when). |
| `client` | The market screen, tooltips and the client's copy of market state. `MarketScreen` is the frame: tab strip, search, quantity row, layout, scrolling and shared drawing helpers. Each tab extends `ScreenTab` (`MarketTab`, `BankTab`, `ContractsTab`, `ExchangeTab`, `LeaderboardTab`, `HistoryTab`), and the screen hands events to the open one. `ClientMarketState` holds what the server last sent; `PriceFormat` writes numbers; `SellPriceTooltip` adds prices to item tooltips. |
| `io` | Spreadsheet export and import (`CatalogExport`, `CatalogImport`, `CatalogColumns`, `Csv`). |
| `compat` | Everything that touches an optional mod: Numismatics, Create, FTB Teams, ProgressiveStages, and other mods' machine recipes for `suggest`. |
| `gametest` | The GameTests, one class per feature, with shared setup in `GameTestSupport`. |

Every package except `api` and `api.event` is marked `@ApiStatus.Internal` in its `package-info.java`: add-ons mustn't use it, and it may change in any release. A new package gets a `package-info.java` with the same annotation.

## Rules every change follows

These keep money safe. Each service class repeats the ones it relies on at its top.

- **Server thread only.** Every trade, payout and bank call runs on the server main thread (packets go through `IPayloadContext.enqueueWork`). Numismatics' bank isn't thread-safe.
- **Check everything, then change.** A transaction makes every check before its first change, and after the first change no step may fail.
  - Sale (`MarketService.trySell`): check the listing, recompute the price, compare it with the client's quote, count matching items in the main inventory; then remove exactly `qty`, update stock (unless unlimited) and deposit.
  - Purchase (`tryBuy`): check the listing, recompute the price, compare the quote, check stock (unless unlimited) and inventory space; then `deduct` (stop if it returns false), update stock and insert the items.
  - A new check goes before the first change: above the `Nothing has changed yet` comment in `trySell`, and before `deduct` in `tryBuy`.
- **Money only through `Bank`.** Nothing moves money except through the `Bank` interface; payments are bank deposits and deductions, never coins handed out. `deduct` is all-or-nothing.
- **No profit from a round trip.** Buying N then selling N back must never pay, and nothing that pays out (contracts, the exchange) may break that. `MarketDiagnostics` and the startup check warn about settings that would.
- **Totals in `long`.** Anything above `Integer.MAX_VALUE` is refused as `TOO_LARGE`, since bank methods take `int`.
- **Never drop items.** Space is checked before items are given; output never lands on the ground.
- **Optional mods stay behind a bridge.** Only `compat/NumismaticsBridge` imports Numismatics, only `compat/CreateRecipes` imports Create, only `compat/FtbTeamsBridge` imports FTB Teams, and only `compat/StagesBridge` imports ProgressiveStages. Each touches its mod only from a nested `Present` class, so the mod loads without them.
- **Coins are never market items.** Selling one would create money from nothing.
- **Events after the change.** An add-on event is posted only once its whole change is complete, through `ApiEvents`. See [The add-on API](#the-add-on-api).

## How a trade flows

1. The player presses Buy in the screen. The client works out the total with `PriceModel` (the same code the server runs) and sends it as a quote in a `MarketPayloads` packet.
2. `MarketNetwork` queues the handler on the server thread; `MarketSync` checks the player's menu is still valid and calls `MarketService.tryBuy`.
3. `MarketService` checks the listing, recomputes the price and compares it with the quote (a mismatch is `PRICE_CHANGED`), checks stock and inventory space, deducts the money, updates stock, then inserts the items.
4. `MarketSync` sends the result to the player, and the new stock and price to everyone with the screen open.

What the screen knows about each item (whether it can be sold and bought, its stock, its `PriceModel.Inputs` and its factor) comes from `MarketPayloads.Row`, built in `MarketSync.row`. `MarketTab` uses it to enable Buy and Sell, and `SellPriceTooltip` for prices. A new rule that refuses a trade must reach the client through `Row` too, so the button greys out instead of failing on every click. A new pricing value is often easiest to add to `PriceModel.Inputs`, whose network codec is written by hand.

Contracts and the exchange follow the same shape through `ContractService` and `ExchangeService`.

## The add-on API

`dynamiceconomy.api` is what other mods compile against; [api.md](api.md) is its guide for them. It's a thin facade: `DynamicEconomyApi` calls the same services the screen and commands use, and adds only what an outside caller can't do for itself:

- a server-thread check on every method that reads or changes the market or the bank (`IllegalStateException` otherwise);
- the client sync the network code would otherwise do: after `sell` or `buy`, `MarketSync.broadcastRow` for the item and the player's state for an open screen; after `pay` or `charge`, the player's state; after a visibility change, a catalog resend;
- for `pay` and `charge`: the amount checks (`INVALID_AMOUNT`, `TOO_LARGE`), an audit line (actor `api:<reason>`), and for `pay`, owing the money in `ApiPayments` through `PayoutService.pay` when the bank can't be reached. `DynamicEconomy`'s server tick pays what's owed, and `Leaderboard` counts it.

The API has its own types, so internal ones can change without breaking add-ons. `MarketResult` maps from `MarketService.Result` by name: a new internal `Result` value needs a matching `MarketResult` value (and a changelog line), or mapping it throws. `MarketVisibility` holds add-ons' rules next to ProgressiveStages' locks; rules may be added from any thread, so they're in a concurrent map, and a rule that throws is removed.

**Events.** The events are in `api.event`, and `ApiEvents.post` puts them on `NeoForge.EVENT_BUS`, logging and ignoring a listener that throws. They're posted where the change happens, not in the facade, so trades from the screen and commands post them too:

| Event | Posted in |
| --- | --- |
| `MarketTradeEvent` | `MarketService.trySell` and `tryBuy`, after the deposit or the items are inserted |
| `ContractEvent.Claimed`, `Delivered`, `Completed`, `Abandoned` | `ContractService.claim`, `deliver` and `abandon`, after the money moved |
| `ContractEvent.Expired` | `ContractService.settleHeld` collects them; `refresh` and `tick` post them once the board is replaced or cleared |
| `ExchangeTradeEvent` | `ExchangeService.match` collects one per fill, and `post` posts them once the incoming order is on the book; `buyListing` posts after the units are in the buyer's box |
| `CatalogReloadedEvent` | the end of `DynamicEconomy.catalogReloaded`, after clients are sent the new catalog |

The rule: **an event is posted only after its whole change is complete**, as the last step. A listener may call back into the market, so it must never see half a transaction. When one action makes several changes (a board replacement, an order filling against several others), collect the events and post them with `ApiEvents.postAll` after the last change. A new event follows the same pattern, is never cancellable, and is posted only for actions that succeeded.

**Changing the API.** The `api` packages are `@ApiStatus.Experimental` until 1.0; they may change in a 0.x minor release, but never silently. For any change to a public type or method in them, or to when an event fires:

1. Update the javadoc. `gradlew javadoc` must pass (doclint is on).
2. Update [api.md](api.md): the section that describes it, and a line in its changelog under the version that ships it.
3. Raise `mod_version`: a minor version (0.5 to 0.6) for a change that can break add-ons, at least a patch version otherwise. Releases are tagged `v<version>`, which is what JitPack publishes.

Keep internal types out of the API's signatures, and keep every check before the first change, as the services do.

## Adding a catalog field

The list of catalog fields is written out by hand in several places, and a field missing from one of them is dropped without an error. For a new field:

1. `CatalogEntry`: add it to the record, its `with…` copies, and `Raw` (`Raw.CODEC` and `resolve`). A record codec takes at most 16 fields and `Raw.CODEC` already has 15, so after one more, put new fields in their own `MapCodec`, parsed beside `Raw` the way `ContractTerms` is in `MarketCatalog.decodeWithoutClass`.
2. `CatalogJson.toJson`: write the field out.
3. `ItemClasses.FIELDS`, so classes can set it.
4. `CatalogColumns.OPTIONAL_FIELDS` (the import and the export's header) and the `values` map in `CatalogExport.row` (the export's cells).
5. `AdminCatalogCommands`: `FIELDS` (for `set`, with the same range as the codec), `INFO_FIELDS`, and `describeField`, which needs the value and label to show when nothing sets the field. A field with a server-wide default gets it from `MarketConfig` (read in `PriceModel.inputs` or `ContractTerms.effective…`) and goes in the `"server config"` case; otherwise its built-in default is the `optionalFieldOf` default in the codec.
6. `MarketCatalog.PAIRED_FIELDS`, only if a rule compares it with another field (like `min_buy_markup` ≤ `markup`).
7. The rule itself goes in the service, following the order above, and anything the screen needs goes in `MarketPayloads.Row` or `PriceModel.Inputs`.
8. The GameTests that call the `CatalogEntry` constructor directly (`grep "new CatalogEntry("`).
9. The [Catalog fields](admin-guide.md#catalog-fields) and [Spreadsheet format](admin-guide.md#spreadsheet-format) tables in the admin guide.

## Design notes

[CLAUDE.md](../CLAUDE.md) records the design decisions and the project's status (it's also the brief Claude Code works from). [dev/phase-history.md](dev/phase-history.md) has the original spec for each feature, and [dev/market-design.md](dev/market-design.md) the investigation the mod started from; both are older than the code, which wins where they differ.
