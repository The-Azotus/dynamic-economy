# Trying Dynamic Economy 0.1.0 in your modpack

This is the Phase 0–5 version (`v0.1.0`). Everything here works in your pack without touching the source.

> Later versions differ: from Phase 7 the five test items are an optional datapack that starts off. See the [README](../README.md) and the [admin guide](admin-guide.md).

## Before you start

- **Back up the world.** The mod adds `world/data/dynamic_economy_market.dat` (stock, prices, price history) and, after an import, a world datapack at `world/datapacks/dynamic_economy_catalog/`.
- **Check versions.** You need NeoForge 21.1.228 or newer for Minecraft 1.21.1, Create 6.0.10, and Numismatics 1.1.x. The mod refuses to load with other Create or Numismatics versions.

## Install

1. Put `dynamic_economy-0.1.0.jar` in the `mods/` folder of the **server and every client**. Clients need it too, for the market screen.
2. Start the server. The log should say `Loaded 5 market catalog entries from 1 files` and show no warnings from `DynamicEconomy`.

## First look

- Run `/market` in game. The built-in test catalog lists five items at placeholder prices: cobblestone and oak log (unlimited stock), iron, diamond, and nether star (buy-only).
- Sell and buy a few things, and check that your Numismatics bank balance changes by the totals the buttons showed.
- Sell a stack of iron, and watch the ▼ arrow appear and the price drop. It recovers 5% of the way back each minute.

## Price your own items

1. From the **server console**, run `/market admin export`. It writes `config/dynamic_economy/exports/<date-time>/items.csv`, listing every item in the pack.
2. Open `items.csv` in a spreadsheet. Put a price (in spurs) in `base_price` for anything you want on the market, and leave the rest blank. You can also change `markup`, `unlimited_stock` and the other columns.
3. Save it somewhere under `config/dynamic_economy/`.
4. Check it: `/market admin import "exports/<date-time>/items.csv" preview`. This writes nothing and lists any rows it would reject.
5. Import it: run the same command without `preview`. The prices go live straight away.

`docs/admin-guide.md` explains every column, the pricing maths and the config file (`config/dynamic_economy-server.toml`).

To take the five test items off the market, run `/market admin remove <item>` for each (their stock is kept). Setting `can_buy` and `can_sell` to `false` stops trading but still shows them in the list.

## Worth noting while you play

These help with tuning or the next round of fixes:

- Prices that feel too cheap, too dear, or move too fast or too slowly.
- Being told "The price just changed" over and over.
- Any warnings or errors from `DynamicEconomy` in the server log.
- Anything in the market screen that looks wrong at your GUI scale or with your other mods.

## Removing it

Delete the jar from server and clients, and delete `world/datapacks/dynamic_economy_catalog/`. Player bank balances live in Numismatics, so money already earned stays.

## Building from source

1. Install a Java 21 JDK, such as Eclipse Temurin 21.
2. Clone the repo, then check out the tested version: `git checkout v0.1.0`.
3. Run `gradlew build` (on Windows, `gradlew.bat build`). The jar is `build/libs/dynamic_economy-0.1.0.jar`.

To run the automated tests: `gradlew runGameTestServer`. All 46 should pass.
