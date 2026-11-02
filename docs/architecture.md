# Architecture

A map of the code for contributors. For what the mod does, read the [admin guide](admin-guide.md) and the [player guide](player-guide.md) first: the code follows them closely, and their terms (catalog entry, class, price factor, contract, listing) are the ones used here.

## Building and testing

Java 21 and NeoForge 21.1 for Minecraft 1.21.1. `gradlew build` builds the jar; `gradlew runGameTestServer` runs the GameTests (`src/main/java/dynamiceconomy/gametest`). The optional mods are on the dev runtime by default; `-PwithoutMods=numismatics`, `create` or `ftbteams` (comma-separated) leaves them out, each in its own test world. Run the tests with and without Numismatics before sending a change that touches money: the two banks are different code.

The test world persists between runs, so tests undo what they change (edits, tag rules, config values) and compare stock, prices and money relatively. Tests that need their own state use their own `ExchangeData` or `ContractBoard` rather than the world's.

## Packages

All code is under `dynamiceconomy`.

| Package | What's in it |
| --- | --- |
| *(root)* | `DynamicEconomy` (entry point and event wiring), `MarketConfig` (the server config), `MarketAccess` (who may use `/market`). |
| `catalog` | What's listed and how it's priced. `MarketCatalog` loads catalog files and classes from datapacks; `ItemClasses` the class files; `CatalogOverlay` lays tag rules and item edits on top; `CatalogEntry` is one listed item; `FieldSource` records where each value came from (for `/market admin info`). |
| `service` | The rules. `MarketService` (market trades), `ContractService` + `ContractPricing` (contracts), `ExchangeService` (player exchange), `BankService` (coins in and out of the bank), `PayoutService` (paying players who may be offline). `PriceModel` is the pricing maths, shared with the client. `PriceSuggestion` (`/market admin suggest`), `MarketDiagnostics` (`/market admin diagnose`), `Leaderboard`, `MarketVisibility` (stage locks), and two small helpers for the screen, `MarketListing` and `OrderMath`. |
| `data` | Everything saved with the world, as `SavedData`: `MarketStockData` (stock, price factors, history, money counts), `MarketOverrides` (tag rules and item edits), `ContractBoard`, `ExchangeData`, `TradeHistory`, `OwedPayouts`. Plus files outside the save: `AuditLog` and `MarketBackup`. |
| `bank` | Where money lives. `Bank` is the interface everything uses; `WorldBank.of(server)` gives the world's bank, either `OwnBank` or Numismatics' (through `compat/NumismaticsBridge`). Teams (`BankTeam`, `BankTeams`, `OwnTeams`), bank switching (`BankSwitch`) and `CoinMath`. |
| `coin` | The mod's own coin items, the Bank Card and coin recipes. |
| `block`, `menu` | The Market Terminal block and the menu the market screen runs in. |
| `command` | Every command. They parse arguments and call a service; they don't change state themselves. |
| `network` | `MarketPayloads` (every packet), `MarketNetwork` (registration and handlers), `MarketSync` (what each player is sent, and when). |
| `client` | The market screen, tooltips and the client's copy of market state. `MarketScreen` is the frame: tab strip, search, quantity row, layout, scrolling and shared drawing helpers. Each tab extends `ScreenTab` (`MarketTab`, `BankTab`, `ContractsTab`, `ExchangeTab`, `LeaderboardTab`, `HistoryTab`), and the screen hands events to the open one. `ClientMarketState` holds what the server last sent; `PriceFormat` writes numbers; `SellPriceTooltip` adds prices to item tooltips. |
| `io` | Spreadsheet export and import (`CatalogExport`, `CatalogImport`, `CatalogColumns`, `Csv`). |
| `compat` | Everything that touches an optional mod: Numismatics, Create, FTB Teams, ProgressiveStages, and other mods' machine recipes for `suggest`. |
| `gametest` | The GameTests, one class per feature, with shared setup in `GameTestSupport`. |

## Rules every change follows

These keep money safe. Each service class repeats the ones it relies on at its top.

- **Server thread only.** Every trade, payout and bank call runs on the server main thread (packets go through `IPayloadContext.enqueueWork`). Numismatics' bank isn't thread-safe.
- **Check everything, then change.** A transaction makes every check before its first change, and after the first change no step may fail. The order for a market sale and purchase is spelled out in `MarketService`.
- **Money only through `Bank`.** Nothing moves money except through the `Bank` interface; payments are bank deposits and deductions, never coins handed out. `deduct` is all-or-nothing.
- **No profit from a round trip.** Buying N then selling N back must never pay, and nothing that pays out (contracts, the exchange) may break that. `MarketDiagnostics` and the startup check warn about settings that would.
- **Totals in `long`.** Anything above `Integer.MAX_VALUE` is refused as `TOO_LARGE`, since bank methods take `int`.
- **Never drop items.** Space is checked before items are given; output never lands on the ground.
- **Optional mods stay behind a bridge.** Only `compat/NumismaticsBridge` imports Numismatics, only `compat/CreateRecipes` imports Create, only `compat/FtbTeamsBridge` imports FTB Teams, and only `compat/StagesBridge` imports ProgressiveStages. Each touches its mod only from a nested `Present` class, so the mod loads without them.
- **Coins are never market items.** Selling one would create money from nothing.

## How a trade flows

1. The player presses Buy in the screen. The client works out the total with `PriceModel` (the same code the server runs) and sends it as a quote in a `MarketPayloads` packet.
2. `MarketNetwork` queues the handler on the server thread; `MarketSync` checks the player's menu is still valid and calls `MarketService.tryBuy`.
3. `MarketService` checks the listing, recomputes the price and compares it with the quote (a mismatch is `PRICE_CHANGED`), checks stock and inventory space, deducts the money, updates stock, then inserts the items.
4. `MarketSync` sends the result to the player, and the new stock and price to everyone with the screen open.

Contracts and the exchange follow the same shape through `ContractService` and `ExchangeService`.

## Design notes

[CLAUDE.md](../CLAUDE.md) records the design decisions and the project's status (it's also the brief Claude Code works from). [dev/phase-history.md](dev/phase-history.md) has the original spec for each feature, and [dev/market-design.md](dev/market-design.md) the investigation the mod started from; both are older than the code, which wins where they differ.
