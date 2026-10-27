# Numismatics: Dynamic Economy

A server-wide market for [Create: Numismatics](https://modrinth.com/mod/numismatics). Players sell items to the server for coins and buy them back from one shared stock, and prices rise and fall with supply and demand.

- **One market for the whole server.** Everything players sell goes into a shared stock that everyone buys from, at the same prices for every player.
- **Bank payments.** All money moves through each player's Numismatics bank account, so there are no coins to carry.
- **Prices that move.** Selling an item lowers its price and buying raises it. Prices drift back toward normal over time.
- **Item classes.** Groups of items share pricing behaviour: common resources stay stable, while rare items swing hard and recover slowly, so dumping a pile of something valuable doesn't pay.
- **Market Terminal block.** Players open the market from a craftable terminal, or with `/market` if the server allows it.
- **Prices in tooltips.** Any item the market buys shows what it pays, wherever the item appears.
- **Easy to price a whole modpack.** Export every item to a spreadsheet, fill in prices, and import it back. Admins can also edit prices in game, one item at a time or for a whole tag such as "all logs".

| You are | Read |
| --- | --- |
| A player | [Player guide](docs/player-guide.md) |
| A server admin | [Admin guide](docs/admin-guide.md) |

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1.228 or newer
- [Create](https://modrinth.com/mod/create) 6.0.10
- [Create: Numismatics](https://modrinth.com/mod/numismatics) 1.1.x

Install the mod on the **server and every client**. Clients need it for the market screen and the tooltips.

## Quick start

**Players:** right-click a **Market Terminal**, or type `/market` if your server allows it. Click an item, choose a quantity, then press **Buy** or **Sell**. Everything is paid through your Numismatics bank account. See the [player guide](docs/player-guide.md).

**Admins:**

1. Install the mod and start the server. The market starts empty. To try it out first, `/datapack enable "mod/dynamic_economy:example_catalog"` lists five example items at placeholder prices; `/datapack disable` with the same name takes them off again.
2. Price your pack. From the server console, run `/market admin export`, fill in `base_price` in the exported `items.csv`, then run `/market admin import "<path to the csv>"`.
3. Tune it in game. For example, `/market admin setprice minecraft:diamond 300` prices one item, and `/market admin tag minecraft:logs setprice 4` prices every log.
4. To make terminals the only way in, set `access_mode = "BLOCK_ONLY"` in `config/dynamic_economy-server.toml`.

The [admin guide](docs/admin-guide.md) covers every command, catalog field and setting.

## Building from source

Install a Java 21 JDK and run `gradlew build`; the jar is written to `build/libs/`. `gradlew runGameTestServer` runs the automated tests.

Released versions are tagged in git (for example `v0.2.0`).

## Licence

MIT; see [LICENSE](LICENSE).
