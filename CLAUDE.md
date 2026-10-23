# Numismatics: Dynamic Economy — build brief

A NeoForge 1.21.1 companion mod for Create: Numismatics. It adds a **server-owned global market**: players sell items to the server for Numismatics currency, sold items enter one shared, persistent stock, and players buy from that stock at a markup. Prices will later move with supply and demand.

Full background, verified source findings and the reasoning behind every decision are in `docs/market-design.md`. Read it before starting. This file overrides it wherever they differ.

## Current task: Phase 0 and Phase 1 only

Do Phase 0, then Phase 1, then **stop and report**. Do not start the GUI, networking, dynamic pricing or anything else in later phases until I approve.

### Phase 0 — setup and verification
1. Set up a NeoForge 1.21.1 project (ModDevGradle), NeoForge 21.1.228, Java 21.
2. Add dependencies:
   - Numismatics: `maven.modrinth:numismatics:1.1.0+neoforge-mc1.21.1` via `https://api.modrinth.com/maven` (coordinate taken from Create: Stock Market's build, believed correct).
   - Create 6.0.10 for NeoForge 1.21.1 from `https://maven.createmod.net`. **Check the exact coordinates** on Create's developer wiki; they are not verified.
3. Verify against the **released Numismatics 1.1.0 jar** (decompile it, don't trust the design doc alone) that these exist with these signatures:
   - `dev.ithundxr.createnumismatics.Numismatics.BANK` (static `GlobalBankManager`)
   - `GlobalBankManager.getAccount(Player)` and `getAccount(UUID)`
   - `BankAccount.getBalance()`, `deposit(int)`, `deduct(int, boolean)`
   - `Coin` enum with `SPUR`=1 … `SUN`=4096 spurs
   Report any difference before writing code that depends on it.
4. Confirm a dev client and dev server launch with Create and Numismatics loaded.

### Phase 1 — server logic, driven by commands (no GUI)
Build these, in package `dynamiceconomy`:

| Class | Job |
| --- | --- |
| `DynamicEconomy` | `@Mod` entry point, registers everything below |
| `catalog/MarketCatalog`, `catalog/CatalogEntry` | Codec-backed JSON loaded by a `SimpleJsonResourceReloadListener` from `data/<namespace>/market/*.json` |
| `data/MarketStockData` | `SavedData` in overworld storage, id `dynamic_economy_market`; `Map<ResourceLocation, Long>` stock. Separate from the catalog so editing prices never wipes stock |
| `compat/NumismaticsBridge` | **The only class allowed to import `dev.ithundxr.createnumismatics.*`.** Wrap calls and catch `LinkageError` |
| `service/MarketService` | The only code that changes stock or money. `trySell`, `tryBuy`, `quote`; returns a result enum |
| `service/PriceModel` | Phase 1: fixed prices. Design the API so a supply/demand factor can be added later without changing callers |
| `command/MarketCommand` | Test commands, below |

Result enum: `OK, NOT_LISTED, SELL_DISABLED, BUY_DISABLED, NOT_ENOUGH_ITEMS, NO_STOCK, NO_FUNDS, NO_SPACE, PRICE_CHANGED, TOO_LARGE`.

Temporary commands (Phase 1 only):
- `/market sell <item> <qty>` and `/market buy <item> <qty>` — permission 0
- `/market stock [item]` and `/market price <item>` — permission 0
- `/market admin setstock <item> <qty>` and `/market admin reload` — permission 2

## Catalog format

One file can hold many entries. Prices are in **spurs** (integer). Use these as test data in `src/main/resources/data/dynamic_economy/market/test_catalog.json`; the numbers are placeholders, not balanced prices.

```json
{
  "entries": [
    { "item": "minecraft:iron_ingot",   "base_price": 16,  "markup": 0.5, "start_stock": 0,  "can_sell": true,  "can_buy": true },
    { "item": "minecraft:diamond",      "base_price": 256, "markup": 0.5, "start_stock": 0,  "can_sell": true,  "can_buy": true },
    { "item": "minecraft:oak_log",      "base_price": 2,   "markup": 0.5, "start_stock": 64, "can_sell": true,  "can_buy": true },
    { "item": "minecraft:nether_star",  "base_price": 8192, "markup": 0.5, "start_stock": 0, "can_sell": false, "can_buy": true }
  ]
}
```

- `markup` defaults to 0.5 if omitted. `start_stock` applies only the first time an item appears in `MarketStockData`.
- Exact item IDs only. **No tags or component matching yet** (see Decisions).

## Decisions already made (don't revisit)

- Separate companion mod. Do **not** fork or modify Numismatics or Create: Stock Market, and do not copy code from Stock Market (it is AGPL-3.0).
- **All payments go through the player's Numismatics bank account**, both directions. No physical coins.
- One server-wide market. Prices are the same for every player; team size never affects them.
- Market pays the player `base × s`; market charges the player `ceil(base × s × (1 + markup))`. In Phase 1, `s = 1`.
- Only items with default components are accepted. An enchanted, damaged or renamed copy of a listed item is rejected (compare with `ItemStack.isSameItemSameComponents` against a fresh default stack).

## Transaction rules (non-negotiable)

- Run every transaction on the server main thread. Never touch the Numismatics bank from another thread; its storage is an unsynchronized `HashMap`.
- **Every check happens before anything changes.** After the first change, no remaining step may be able to fail.
- Sell order: validate listing → recompute price → count matching items in the **main inventory only** → remove exactly `qty` → `stock += qty` and `setDirty()` → `deposit(total)`.
- Buy order: validate listing → recompute price → check `stock >= qty` → simulate inserting into the main inventory and reject if it won't fit → `deduct(total, false)`, stop if it returns false → `stock -= qty` and `setDirty()` → insert items.
- **Never drop items on the ground.** Don't use Numismatics' `ItemUtil.givePlayerItem` for market output.
- Do totals in `long`. Reject with `TOO_LARGE` if a total exceeds `Integer.MAX_VALUE`, since the bank methods take `int`. Cap `qty` per request (configurable, default 640).

## Phase 1 is done when

- [ ] Selling 10 iron ingots raises stock by 10 and the balance by exactly 160 spurs.
- [ ] Buying 5 iron ingots lowers stock by 5 and the balance by exactly `ceil(16 × 1.5) × 5` = 120 spurs.
- [ ] Stock survives a clean server restart.
- [ ] Each of these is rejected with the right result and changes nothing: unlisted item, selling more than held, enchanted/damaged copy, buying with too little money, buying beyond stock, buying a 0-stock item, buying into a full inventory, an oversized order, `can_sell: false` on nether star.
- [ ] `/market admin reload` picks up a price edit without a restart and without touching stock.
- [ ] Automate the rejection cases as NeoForge GameTests where practical.

## When you stop, report

- What you verified in the Numismatics jar and any differences from the design doc
- What was built, and what you deferred or weren't sure about
- Test results
- Anything in this brief that turned out to be wrong

## Out of scope for now

GUI and menus, networking and sync, the market terminal block, supply-and-demand pricing, fees, tag-based catalog entries, physical coin payment, Fabric support.
