# Dynamic-Economy

A server-wide market for Minecraft. Players sell items to the server for money and buy them back from one shared stock, and prices rise and fall with supply and demand. It brings its own coins and bank, or uses [Create: Numismatics](https://modrinth.com/mod/numismatics)' bank when that mod is installed.

- **One market for the whole server.** Everything players sell goes into a shared stock that everyone buys from, at the same prices for every player.
- **Bank payments.** All money moves through each player's bank account, so there are no coins to carry. Coins go in and out of the bank in the market screen's Bank tab, or anywhere with a Bank Card.
- **Its own coins, or Numismatics'.** Without Numismatics, the mod has eight coins, from the Bit up to the Omega. With Numismatics, it uses Numismatics' bank and coins instead. Prices mean the same either way: 1 bit = 1 spur.
- **Team accounts.** Players in a team (an FTB Teams party, or the mod's own `/bank team`) can share one account.
- **Prices that move.** Selling an item lowers its price and buying raises it. Prices drift back toward normal over time.
- **Item classes.** Groups of items share pricing behaviour: common resources stay stable, while rare items swing hard and recover slowly, so dumping a pile of something valuable doesn't pay.
- **Contracts.** A shared board of delivery jobs that refreshes every few in-game days: claim one, deliver the items, get paid.
- **Player exchange** (off by default). Players trade with each other at their own prices: buy and sell orders that match automatically, plus fixed-price listings for enchanted, damaged or renamed items. Bought items wait in a collection box, and a small tax on each sale takes money out of the economy.
- **Leaderboard.** The richest players by bank balance, counting money they have tied up in the exchange.
- **Market Terminal block.** Players open the market from a craftable terminal, or with `/market` if the server allows it.
- **Prices in tooltips.** Any item the market buys shows what it pays, wherever the item appears.
- **Easy to price a whole modpack.** Export every item to a spreadsheet, fill in prices, and import it back. Admins can also edit prices in game, one item at a time or for a whole tag such as "all logs".

| You are | Read |
| --- | --- |
| A player | [Player guide](docs/player-guide.md) |
| A server admin | [Admin guide](docs/admin-guide.md) |

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1.228 or newer
- Nothing else. Optional:
  - [Create: Numismatics](https://modrinth.com/mod/numismatics) 1.1.x (with [Create](https://modrinth.com/mod/create) 6.0.x): the market uses its bank and coins.
  - [Create](https://modrinth.com/mod/create) 6.0.x alone: price suggestions read its machine recipes more closely.
  - [FTB Teams](https://www.curseforge.com/minecraft/mc-mods/ftb-teams-forge) 2101.1.x: its parties share team accounts (with the mod's own bank).
  - ProgressiveStages 3.x: items a player hasn't unlocked are hidden from them.

Install the mod on the **server and every client**. Clients need it for the market screen and the tooltips.

## Quick start

**Players:** right-click a **Market Terminal**, or type `/market` if your server allows it. Click an item, choose a quantity, then press **Buy** or **Sell**. Everything is paid through your bank account; the **Bank** tab turns coins into money and back. See the [player guide](docs/player-guide.md).

**Admins:**

1. Install the mod and start the server. Built-in prices for vanilla and many popular mods are on by default (each mod's file loads only if that mod is installed); your own files, imports and edits always override them. To start from an empty market instead, `/datapack disable "mod/dynamic_economy:default_prices"`.
2. Check the bank: a new world uses Numismatics' bank when Numismatics is installed, otherwise the mod's own. `/market admin bank` says which, and can switch it.
3. Price your pack. From the server console, run `/market admin export`, fill in `base_price` in the exported `items.csv`, then run `/market admin import "<path to the csv>"`. In a big pack, run `/market admin suggest` too: it prices crafted items from their recipes, and its `suggested.csv` imports the same way (see [Pricing a whole modpack](docs/admin-guide.md#pricing-a-whole-modpack)). Or write catalog files by hand: files in `config/dynamic_economy/catalog/market/` apply to every world, and an entry only needs the fields it changes, such as `{ "item": "minecraft:diamond", "base_price": 600 }`.
4. Tune it in game. For example, `/market admin setprice minecraft:diamond 300` prices one item, and `/market admin tag minecraft:logs setprice 4` prices every log.
5. To make terminals the only way in, set `access_mode = "BLOCK_ONLY"` in `config/dynamic_economy-server.toml`.

The [admin guide](docs/admin-guide.md) covers every command, catalog field and setting.

## Building from source

Install a Java 21 JDK and run `gradlew build`; the jar is written to `build/libs/`. `gradlew runGameTestServer` runs the automated tests. Create, Numismatics and FTB Teams are optional: add `-PwithoutMods=numismatics` to run without Numismatics, `-PwithoutMods=create` to run without both Create and Numismatics (Numismatics needs Create), or `-PwithoutMods=ftbteams` to run without FTB Teams; combine them with commas. Each such run uses its own test world, since a world remembers which bank it uses.

Released versions are tagged in git (for example `v0.4.0`). To find your way around the code, start with [docs/architecture.md](docs/architecture.md).

## Licence

MIT; see [LICENSE](LICENSE).
