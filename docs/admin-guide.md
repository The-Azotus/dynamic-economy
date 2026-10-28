# Admin guide

This guide is for server admins running the Numismatics: Dynamic Economy market. It covers:

- how players reach the market
- how to price items, from a spreadsheet, catalog files or in-game commands
- item classes, which give groups of items shared pricing behaviour
- how prices move, every catalog field and config option, and every command

For what players see, read the [player guide](player-guide.md).

## How the market works

The market is owned by the server. Players sell items to it and buy items from it, and all money moves through their Numismatics bank accounts. Every player sees the same prices and the same stock.

- **Stock.** Items players sell go into one shared stock, and players buy from that stock. Items marked *unlimited* are always in stock instead.
- **Prices.** Each item has a base price. The market pays a little less than the going rate and charges a markup on top.
- **Supply and demand.** Selling an item pushes its price down and buying pushes it up. Prices drift back toward base over time.

**Where prices come from**, lowest to highest priority:

1. **Catalog files**: JSON in datapacks, including what the spreadsheet import writes.
2. **Tag rules**: in-game rules such as "every log costs 4 spurs".
3. **Item edits**: in-game changes to one item.

A higher layer only changes the fields it sets. Pricing one item by hand always beats a tag rule. In-game edits are saved with the world and never touch stock or current prices.

## Letting players in

Players reach the market in two ways:

- **Market Terminal:** a block that opens the market when right-clicked. The screen closes if the player walks more than about 8 blocks away or the terminal is broken.
- **`/market`:** opens it from anywhere.

Choose which is allowed with `access_mode` in `config/dynamic_economy-server.toml`:

| `access_mode` | Players | Operators and console |
| --- | --- | --- |
| `COMMAND_AND_BLOCK` (default) | Terminals and every player `/market` command | Everything |
| `BLOCK_ONLY` | Terminals only. Plain `/market` tells them to find a terminal; the other player commands are hidden. | Everything |

The setting takes effect as soon as you save the file, with no restart. A player who opened the market by command is closed out if you switch to `BLOCK_ONLY` while they have it open.

**Terminals** are craftable:

```
Spur  Spur            Spur
Spur  Crafting table  Spur
Spur  Spur            Spur
```

To change or remove the recipe, override `data/dynamic_economy/recipe/market_terminal.json` in a datapack. To hand them out yourself, use `/give @p dynamic_economy:market_terminal`. Terminals are wooden tables: they break by hand, faster with an axe, and always drop themselves.

## Pricing items

There are three ways to price items, and you can mix them:

| Way | Best for |
| --- | --- |
| [Spreadsheet export and import](#pricing-from-a-spreadsheet) | Pricing a whole modpack at once. |
| [Catalog files](#catalog-files) | Keeping prices in a datapack you version or share. |
| [In-game commands](#editing-prices-in-game) | Quick changes, and whole tags such as "all logs". |

**Built-in prices.** The mod ships prices for vanilla and a set of popular mods in a datapack that is **on** by default. It has one price file per mod, and each file loads only if its mod is installed, so it works in any modpack. It sits below every other datapack, so your own catalog files, imports and edits always override it. To run your own prices from scratch, turn it off with `/datapack disable "mod/dynamic_economy:default_prices"`.

The built-in prices cover raw materials: ores, logs, crops, mob drops and loot. Run [`/market admin suggest`](#suggesting-prices-from-recipes) to price the crafted items in your pack from them. The scale is roughly: cobblestone 1 spur, a log 8, an iron ingot 32, a diamond 512, a nether star 16,384.

The mod also includes a small **example catalog**: cobblestone and oak log (unlimited stock), iron, diamond and nether star, at placeholder prices. It's a datapack that is **off** until you enable it:

- `/datapack enable "mod/dynamic_economy:example_catalog"` turns it on; `/datapack disable "mod/dynamic_economy:example_catalog"` turns it off.
- Its file, `data/dynamic_economy/market/example_catalog.json`, is a handy starting point for your own catalog.

**Upgrading from an earlier version:** before Phase 7 these five items were always on. After upgrading they disappear from the market unless you enable the example pack. Their saved stock and price history are kept, so enabling the pack (or listing them some other way) brings them back as they were.

### Pricing from a spreadsheet

1. Run `/market admin export` from the server console or as a level-4 operator. It writes a folder like `config/dynamic_economy/exports/2026-10-23_09-30-00/` containing:
   - `items.csv`: every item in the game, one row each. Listed items have the values their catalog files, tag rules and in-game edits set. A value that comes from the item's [class](#item-classes) or a built-in default is left blank, so importing the sheet later doesn't pin it: change the `class` column and the class's values follow.
   - `templates/<modid>.json`: one catalog file per mod, listing only unlisted items, each at `base_price: 0`.
2. Open `items.csv` in a spreadsheet. Fill in `base_price` for anything you want on the market, and change any other column you like. Leave `base_price` blank for items you don't want.
3. Save it somewhere inside `config/dynamic_economy/`. Either CSV encoding works, UTF-8 or Excel's default.
4. Check it: `/market admin import "exports/2026-10-23_09-30-00/items.csv" preview`. This writes nothing. It reports how many entries would be written, how many rows were skipped and why any were rejected.
5. Import it: run the same command without `preview`.

The import writes one file per mod, `imported_<modid>.json`, into a world datapack at `<world>/datapacks/dynamic_economy_catalog/`. The first import creates and enables that datapack. Later imports back up each file they replace as `<name>.json.bak`, then reload the catalog straight away. Importing never changes stock or current prices.

Put the path in quotes, since it contains slashes. To export one mod only, use `/market admin export <modid>`.

Items that also have an in-game edit keep that edit, because edits beat catalog files. The import lists any such items; use `/market admin revert <item>` to let the imported value through.

The [spreadsheet format](#spreadsheet-format) section has the details on columns and what the importer accepts.

### Suggesting prices from recipes

In a big modpack, you only need to price the raw materials (ores, logs, crops, mob drops, loot) yourself. The market can work out the rest from the game's recipes:

1. Price the raw materials, by any of the ways above.
2. Run `/market admin suggest` (or `/market admin suggest <modid>` for one mod) from the console or as a level-4 operator. It writes `suggested.csv` to a new folder in `config/dynamic_economy/exports/`. Nothing on the market changes.
3. Review the file in a spreadsheet, then import it like any other CSV.

For each unlisted item, `suggest` finds its cheapest recipe and adds up its ingredients. For each ingredient slot it takes the cheapest priced item that fits, subtracts anything the recipe gives back (the empty bucket from a milk bucket), adds fuel for furnace recipes, divides by how many the recipe makes, and adds 10% for the processing step. It repeats until nothing gets cheaper, so long crafting chains are priced from the bottom up. It reads every recipe type that lists its items, including modded machines such as Create's presses and mills. Create's machine recipes are read more closely: their chance outputs count at their average. Modern Industrialization, Immersive Engineering, Mekanism and Oritech keep their machine recipes' inputs, amounts or outputs outside the usual place, so `suggest` reads them directly, leaving out catalysts the machine never uses up (such as Immersive Engineering's press molds). Only a machine's main output is priced; byproducts aren't subtracted, which errs towards a higher price. Energy and machine fuel aren't priced, and recipes that use up fluids or Mekanism chemicals are marked for review. Every recipe type `suggest` couldn't use is listed, with the reason, in `skipped_recipes.txt` next to `suggested.csv`. Recipe types that may hide some of their inputs (see the craft-profit groups below) only price items that no fully listed recipe makes, so a ritual that leaves its main ingredient out can't undercut the ordinary recipe. The item's suggested class is `rare_processed` if its rare ingredients are worth at least half its cost, or at least as much as the cheapest rare item in the catalog; otherwise `common_processed`. A trace of rare material deep in a long chain doesn't make a cheap item rare.

Prices are rounded down and the 10% is less than any built-in markup, so buying the ingredients from the market, crafting and selling the result never pays. If your catalog gives any item a markup (or `min_buy_markup`) below 10%, `suggest` uses that smaller figure instead and says so in its reply.

Items from Chipped and Rechiseled (decorative block variants, thousands each) are left out of the market: `suggest` never prices them and leaves them out of `suggested.csv`. You can still list one by hand.

`suggested.csv` has the same columns as `items.csv`, with the suggested `base_price` and `class` filled in, plus these columns for reference (the importer ignores them):

| Column | Meaning |
| --- | --- |
| `price_source` | `catalog` (already listed), `suggested`, or blank (no price could be worked out) |
| `exact_cost` | The worked-out cost before rounding down |
| `recipe`, `recipe_type` | The recipe the price came from |
| `depth` | How many crafting steps from a priced item |
| `note` | Anything worth checking; see below |

`/market admin diagnose` also reads the recipes, and warns about any listed item whose ingredients can be bought from the market for less than the item sells for. A player could buy the ingredients, craft and sell at a profit forever. The fix is to raise the ingredients' prices, lower the item's, or set `can_sell` false on it. `suggest` writes the same list to `craft_profits.txt`, in three groups:

- **Price gaps** (warnings): fix them as above.
- **Check by hand** (notes): recipe types that may take fluids, extra items or ingredient amounts the check can't see, such as Spectrum's fusion shrine. These are often false alarms.
- **Recipes that make more of their own ingredient** (notes), such as a machine turning 1 cobblestone into 2: no price can stop these, since any price makes 2 worth more than 1. They're generators, and sell impact limits what they earn.

`suggest` also writes `chain_profits.txt`, a check that follows several recipes in a row, as if every suggestion had been imported. It lists items that can be bought and processed into more than they cost, with the chain of recipes, for example a stone that crushes into ore which smelts into an ingot. The first item in a chain is usually priced too low for what it processes into: raise it, or set `can_buy` false so the market doesn't sell it. Listed items come first; suggested items often follow from them and clear when the listed item is fixed. Chains marked `[check]` include a recipe type that may hide inputs.

Notes to look out for:

- **Worth under 1 spur:** prices are whole spurs, so these are left blank. Most such items (sticks, buttons) don't need to be on the market.
- **This recipe type may leave out fluids or ingredient amounts:** some machines take fluids, or keep amounts where the market can't see them, so the cost may be too low.
- **Converts back into …:** the item and its ingredient turn into each other, like iron blocks and ingots. If both are listed, a player whose ingots have dropped in price can craft blocks and sell those at full price instead. List only one form, or set `can_sell` false on the other.
- **Price kept falling through a recipe loop:** a set of recipes makes the item out of less than itself, so the suggestion is too low. Price it by hand. This should be rare: an item is never priced from ingredients that were themselves priced from it, so a loop such as one plank sawn into a door and the door sawn back into two planks is cut where it closes. The loop itself still works in game, so consider removing one of its recipes.
- **No recipe makes it** and **every recipe for it needs an unpriced item:** price these by hand if they should be listed, then run `suggest` again.

### Catalog files

Catalog files live in any datapack at `data/<namespace>/market/<name>.json`:

```json
{
  "defaults": { "markup": 0.5, "sell_fee": 0.0 },
  "entries": [
    { "item": "minecraft:cobblestone", "base_price": 1,    "unlimited_stock": true },
    { "item": "minecraft:iron_ingot",  "base_price": 16 },
    { "item": "minecraft:diamond",     "base_price": 256, "markup": 0.25 },
    { "item": "minecraft:nether_star", "base_price": 8192, "can_sell": false }
  ]
}
```

- `entries` is required: the items to list.
- `defaults` is optional. Its fields apply to every entry in that file unless the entry sets the field itself. It can't contain `item`.
- `required_mods` is optional: a list of mod IDs, such as `["create"]`. If any of them isn't installed, the whole file is skipped without a warning. Use it for a price file that belongs to one mod.
- **Duplicates:** if two files list the same item, the higher-priority datapack wins. Within one datapack, the file whose name sorts last wins, and the log warns about it.
- **Reloading:** after editing files, run `/market admin reload` to reload just the market, or `/reload` to reload all datapacks (use `/reload` after adding a new datapack).
- **Bad entries are skipped, not fatal.** An unknown item, an out-of-range value or a missing field skips only that entry, with a log warning naming the file and entry. Entries with `base_price` 0 or below are skipped with one summary warning, so a half-filled template is safe to load.

You can also start from a file in `templates/` from an export.

### Item classes

A class is a named set of catalog settings shared by a group of items, so similar items behave alike without repeating the same numbers on every entry. Give an entry a class with `"class"`:

```json
{
  "entries": [
    { "item": "minecraft:cobblestone", "base_price": 1,    "class": "common_resource" },
    { "item": "minecraft:diamond",     "base_price": 256,  "class": "rare_resource" },
    { "item": "minecraft:nether_star", "base_price": 8192, "class": "unique", "can_sell": false }
  ]
}
```

A class fills in only the fields that nothing else sets. From weakest to strongest: server config, class, the file's `defaults`, the entry itself, tag rules, in-game edits. In the example, the nether star keeps its own `can_sell: false` and takes everything else from `unique`. `class` itself can be set at any of those levels, for example once in a file's `defaults`, or for a whole tag with `/market admin tag <tag> set class rare_resource`.

Classes are the main protection against someone finding a way to get a lot of a valuable item. Rare classes move their price a lot per unit sold and recover slowly, so dumping a big pile pays far less than its base price. There are no per-player limits: the price does the limiting.

The mod ships these classes. The values are starting points, not balanced numbers:

| Class | For | Stock | Markup | Impact per unit | Recovery per step | Floor – ceiling |
| --- | --- | --- | --- | --- | --- | --- |
| `common_resource` | Raw materials gathered in bulk: stone, logs, sand | unlimited | 0.5 | 0.0002 | 0.1 | 0.3 – 2 |
| `common_processed` | Common items made from those: planks, glass, ingots | normal | 0.4 | 0.0005 | 0.08 | 0.25 – 3 |
| `farmable` | Crops and mob drops that farms produce endlessly | unlimited | 0.5 | 0.001 | 0.05 | 0.1 – 2 |
| `rare_resource` | Scarce raw materials: diamonds, ancient debris | normal | 1.0 | 0.005 | 0.02 | 0.15 – 5 |
| `rare_processed` | Valuable made items: netherite, precision mechanisms | normal | 1.0 | 0.003 | 0.03 | 0.15 – 5 |
| `rare_loot` | Found, not made: music discs, enchanted books, totems | normal | 1.5 | 0.02 | 0.01 | 0.1 – 8 |
| `unique` | One-of-a-kind: nether stars, dragon eggs | normal | 2.0 | 0.08 | 0.005 | 0.1 – 6 |

"Impact per unit" is both `sell_impact` and `buy_impact`. For a sense of scale, at the default 1-minute recovery step:

- Selling 1,000 cobblestone lowers its sell price to about 82%, and it's back to normal within about half an hour.
- Selling 100 diamonds lowers theirs to about 61%, and it takes about 35 minutes to recover even half the drop.
- Selling 10 nether stars lowers theirs to about 43%, and half the drop takes over 2 hours to come back.

See [Supply and demand](#supply-and-demand) for the formulas.

**Making your own.** A class is a file in any datapack at `data/<namespace>/market_classes/<name>.json`. It may set any catalog field except `item`, `base_price` and `class`, plus an optional `description`:

```json
{
  "description": "Create machine parts",
  "markup": 0.6,
  "sell_impact": 0.002,
  "buy_impact": 0.002,
  "recovery": 0.04
}
```

Refer to it as `<namespace>:<name>`. A name without a namespace, such as `rare_resource`, means `dynamic_economy:rare_resource`. To change a built-in class, put a file at the same path, such as `data/dynamic_economy/market_classes/rare_resource.json`, in your own datapack. It replaces the whole class.

- **Checks:** class values must pass the same rules as catalog entries. A class with a bad value is skipped, and so is every entry naming it. Unknown fields in a class file are ignored with a warning. All of these show in `/market admin diagnose`.
- **Reloading:** `/market admin reload` re-reads classes along with the catalog files, and every member is repriced. Stock and price factors are never touched.
- **Keeping it fair:** keep `sell_impact` and `buy_impact` equal, and below `markup`. Even equal impacts, if large, can let a player sell a batch and buy it back for less; `/market admin diagnose` warns about any item where that's possible. All the built-in classes pass.
- `/market admin classes` lists every class, its values and how many listed items use it.

### Editing prices in game

These commands need operator level 2. Items can be typed with tab completion, such as `minecraft:iron_ingot`.

| Command | What it does |
| --- | --- |
| `/market admin setprice <item> <price>` | Sets the base price. Also lists an item that isn't on the market yet. |
| `/market admin set <item> <field> <value>` | Sets any [catalog field](#catalog-fields), such as `set minecraft:diamond markup 0.25`, `set minecraft:dirt can_buy false` or `set minecraft:diamond class rare_loot`. |
| `/market admin unset <item> <field>` | Drops one of your edits, so the catalog files decide that field again. |
| `/market admin remove <item>` | Takes the item off the market. Its stock is kept for if you list it again. |
| `/market admin revert <item>` | Drops all your edits for the item, back to what the catalog files and tag rules say. |
| `/market admin clearstock <item>` | Empties the item's stock; it stays listed. |
| `/market admin info <item>` | For a listed item, every field's value and where it comes from: a catalog file (and whether from the entry itself or the file's `defaults`), a tag rule, your edit, the item's class, the server config, or the built-in default. It also shows any lower-layer values a field overrides. For an unlisted item, it shows what each layer says. |

For example, `info minecraft:oak_log` might show `base_price = 9 [item edit; overrides 4 from tag rule #minecraft:logs, then 2 from file dynamic_economy:example_catalog]` and `markup = 0.5 [file dynamic_economy:example_catalog defaults]`.

Every edit is checked before it's saved. If it would break a rule, it's refused with the reason and nothing changes; for example, a `min_buy_markup` above the `markup`, or a field set on an item that has no price.

### Tag rules: pricing many items at once

A tag rule sets fields for every item in an item tag. It's **live**: when a mod adds items to the tag later, they follow the rule too. Common tags include `minecraft:logs`, `minecraft:planks`, `minecraft:wool`, `c:ingots` and `c:ores`; tab completion lists them all.

| Command | What it does |
| --- | --- |
| `/market admin tag <tag> setprice <price>` | Every item in the tag gets this base price. Unlisted members are listed with the default settings. |
| `/market admin tag <tag> set <field> <value>` | Sets a field for the tag's members. Without a price, it only changes members that are already listed. |
| `/market admin tag <tag> unset <field>` | Drops one field from the rule. |
| `/market admin tag <tag> remove` | Takes every member off the market, except items with their own edits. |
| `/market admin tag <tag> revert` | Deletes the rule. |
| `/market admin tag <tag> info` | Shows the tag's size, how many members are listed, and its rule. |
| `/market admin tags` | Lists every tag rule in the order they apply. |

**Example:** `/market admin tag minecraft:logs setprice 4` prices every log at 4 spurs. `/market admin setprice minecraft:birch_log 9` then makes birch the exception. Oak logs keep `unlimited_stock: true` from the catalog file, because the rule only sets the price.

Where rules overlap, rules created later win. Item edits always win over rules.

**Preview first.** A tag can hold dozens of items, so `setprice`, `set`, `remove` and `revert` all accept `preview` at the end. For example, `/market admin tag minecraft:logs setprice 4 preview` says how many of the tag's items would be newly listed, taken off, changed or unaffected, with examples (removals and changes first). It also says whether the change would be refused. Nothing is saved.

## Checking the catalog

`/market admin diagnose` looks over the catalog, your edits and the saved market data, and lists what it finds, ten per page (`/market admin diagnose 2` for the next page). It never changes anything.

| Level | Means | Examples |
| --- | --- | --- |
| **Error** | Something was left out of the market. | A catalog entry skipped (unknown item, bad value); an in-game edit that can't be applied. |
| **Warning** | Very likely a mistake. | A round trip that makes a profit; an item that sells for more than its ingredients cost to buy; an item that can be bought but never sold, with 0 stock, so it can never be bought; an item stuck at its price floor or ceiling; a tag rule matching no items; an edit that sets the same value as the layer below it. |
| **Note** | Possibly deliberate; worth a look. | Unlimited items that can be sold (farms can sell them forever); items listed but neither buyable nor sellable; tag rules covering more than 64 items; unpriced template entries; saved data for items that no longer exist. |

**Removed mods.** When you remove a mod, the market keeps its items' saved stock, prices, history and edits. `/market admin prune` lists those items, and `/market admin prune confirm` deletes their data. The overall money counts are kept. A backup of stock, prices and edits is taken automatically first (see [Backups](#backups-and-restoring)).

**Price resets.** `/market admin resetprice all preview` shows how many items would go back to base price, the furthest-moved first. `/market admin resetprice tag <tag>` resets only that tag's items, and also takes `preview`.

## Audit log

Every admin change to the market is logged to `<world>/dynamic_economy/audit.log`, one JSON line each. Each line records when, who (player name and UUID, or `Server` for the console), the action, its target, the result, and the old and new values where that makes sense. Logged actions:

- catalog edits and tag-rule changes, including ones refused for breaking a rule
- `setstock`, `clearstock`, `resetprice`, `reload`, `export`, `import` (with the files written), `prune` (with the items removed), `backup` and `restore`

Previews and read-only commands (`info`, `diagnose`, `stats`) aren't logged.

`/market admin audit` shows the newest ten in chat; `/market admin audit 2` shows the next ten. The file is on the server's disk, out of players' reach. When it reaches 1 MB it's renamed `audit.log.1` (older ones become `.2`, `.3`, `.4`), and at most five files are kept.

## Backups and restoring

`/market admin backup` saves the market's **stock**, **price factors** and **in-game edits** (item edits and tag rules) to `<world>/dynamic_economy/backups/<date-time>.json`. `/market admin backups` lists them, newest first.

Backups don't include catalog files (an import keeps `.json.bak` copies of those), price history or money counts. A world backup still covers everything.

To restore, name a backup and which part to restore: `stock`, `prices`, `edits` or `all`:

1. `/market admin restore <name> all` checks the backup and says what restoring would change. It also says how long ago the backup was taken, and how many admin actions have been logged since. Nothing is changed.
2. `/market admin restore <name> all confirm` restores it. The current state is backed up first (as `<date-time>_auto-restore`), so you can undo the restore by restoring that.

A damaged backup (unreadable, or with a negative stock, a missing field, and so on) is refused as a whole, with the reasons. Nothing is partly restored. Restored prices are as they were when the backup was taken. They then start recovering from the moment of the restore, rather than catching up on the time in between.

Automatic backups (taken before a restore or a prune) are kept up to the newest 20; older automatic ones are deleted. Backups you make yourself are never deleted.

## Catalog fields

Prices are whole numbers of **spurs**, the smallest Numismatics coin (1 bevel = 8 spurs, 1 sprocket = 16, 1 cog = 64, 1 crown = 512, 1 sun = 4096). These fields are used in catalog files, spreadsheet columns and `/market admin set`.

| Field | Default | What it does |
| --- | --- | --- |
| `item` | *required* | The exact item ID, such as `minecraft:iron_ingot`. Enchanted, damaged or renamed copies are never accepted. |
| `class` | none | An [item class](#item-classes) that fills in the fields nothing else sets, such as `rare_resource`. |
| `base_price` | *required* | What the market pays for one unit at normal supply, in spurs. At least 1; 0 or below means "not priced yet". |
| `markup` | `0.5` | Added on top when the market sells. `0.5` means buyers pay 150% of base. 0 to 1000. |
| `sell_fee` | `0.0` | Fraction kept back from what the market pays, such as `0.1` for 10%. Nobody receives it; it's a money sink. 0 to 0.99. |
| `can_sell` | `true` | Whether players may sell this item to the market. |
| `can_buy` | `true` | Whether players may buy this item from the market. |
| `start_stock` | `0` | Stock the market starts with, given only the first time the item appears. Later changes don't affect existing stock; use `/market admin setstock`. Ignored for unlimited items. |
| `unlimited_stock` | `false` | The market always has this item to sell, and items sold to it simply disappear. Good for common resources. |
| `dynamic` | `true` | Whether supply and demand move this item's price. `false` keeps it fixed at base. |
| `min_buy_markup` | same as `markup` | Unlimited items only: the lowest markup the buy price can fall to as players sell the item. If you set it, it must be above 0 and no higher than `markup`. |
| `sell_impact` | server config | How much each unit sold lowers the price. See [Supply and demand](#supply-and-demand). |
| `buy_impact` | server config | How much each unit bought raises the price. |
| `recovery` | server config | How quickly the price returns to base. |
| `floor` | server config | The lowest the price factor can fall, 0.001 to 1. |
| `ceiling` | server config | The highest the price factor can rise, 1 to 1000. |

## Supply and demand

Each item has a **price factor**, written `s`, that starts at 1.0. The two prices at any moment are:

- **Market pays:** `base_price × s × (1 − sell_fee)`
- **Market charges:** `base_price × s × (1 + markup)`

Unlimited items charge `base_price × max(s × (1 + markup), 1 + min_buy_markup)` instead, so their buy price never drops below that minimum. By default the minimum equals the normal price, so it stays fixed.

**Every unit in an order moves the price.** Each unit sold multiplies `s` by `1 − sell_impact`, never going below `floor`. Each unit bought multiplies it by `1 + buy_impact`, never above `ceiling`. Each unit is valued at the price it trades at, and the order's exact total is then rounded once: down when the market pays, up when it charges. Splitting an order never earns more or costs less than one large order. Buying unlimited items never moves their price.

**Prices recover in steps.** Every `interval_ticks` of game time (1 minute by default), every item's factor moves `recovery` of the way back to 1.0. Steps happen at the same moment for every item. Game time stops while the server is stopped, so downtime doesn't count.

**Example (default settings):** iron has `base_price: 16` and `markup: 0.5`. Before any trades, the market pays 16 and charges 24. Selling 640 ingots in one order pays about 8,765 spurs in total, not 10,240, and leaves `s` at about 0.73: the market then pays 11 and charges 18. After an hour without trades, iron is back to about 99% of base.

**Keeping it fair.** At startup and after every reload, the server log warns about any item whose settings would let a player make money from a round trip in either order: buying and immediately selling back, or selling and buying back. To stay safe:

- keep `buy_impact` no higher than `markup`, and no higher than `sell_impact / (1 − sell_impact)`;
- don't set `buy_impact` far below `sell_impact`. Otherwise a player can sell a large amount, crashing the price, and buy the same items back at the bottom for less than they were paid. For example, the default settings with `buy_impact = 0` let 3,000 iron be sold and bought back at a profit.

Equal impacts, as in the default config, are safe.

## Where the money goes

The market has **no balance of its own**. It's a source and a sink, not a bank:

- When a player **sells**, the payout is **new money** added to the economy.
- When a player **buys**, what they pay **leaves the economy**. Nobody receives it.
- `sell_fee` doesn't collect anything: it just means less new money is created per sale.
- Admin commands (`setstock`, `clearstock`, `resetprice`, catalog edits) never create or remove money.

`/market admin stats` shows the lifetime totals: money paid to sellers (created), money paid by buyers (removed), items moved each way, and the net amount the market has added to (or removed from) the economy, plus the five busiest items. `/market admin stats <item>` shows one item. Counting starts when a world first runs a version with this command; earlier trades aren't included. A steadily growing net means players are mostly selling, so money is piling up. Raise markups, add a `sell_fee`, or lower base prices of farmable items to slow it.

**Cheap items get coarse.** Payments are whole spurs, so an item with a `base_price` of 1 or 2 has little room to move. A single unit sold below normal price rounds down to 0, though bulk orders still pay because the total is rounded only once. The screen and tooltips show such prices with decimals (e.g. *0.99*) so players can see what's happening. Give common items a base of 8 or 16 if you want their movements to show.

## Tooltips

Every player's item tooltips show what the market pays for items it buys, for example *"Market pays 16 spurs each (318 for all 20)"*. Players can turn this off with `show_sell_price_in_tooltips = false` in their own `config/dynamic_economy-client.toml`. Items the market doesn't buy show nothing.

## Server config

The file is `config/dynamic_economy-server.toml`, created on first start. Changes apply when you save it. Catalog entries can override everything in `[pricing]` except `interval_ticks`.

| Option | Default | Range | What it does |
| --- | --- | --- | --- |
| `max_quantity_per_request` | 640 | 1 – 1,000,000 | Largest quantity one buy or sell can move. |
| `access_mode` | `COMMAND_AND_BLOCK` | `COMMAND_AND_BLOCK`, `BLOCK_ONLY` | How players reach the market; see [Letting players in](#letting-players-in). |
| `pricing.sell_impact` | 0.0005 | 0 – 0.5 | Each unit sold multiplies the factor by `1 − sell_impact`. At the default, selling 640 drops a price to about 73%. |
| `pricing.buy_impact` | 0.0005 | 0 – 0.5 | Each unit bought multiplies the factor by `1 + buy_impact`. Keep it close to `sell_impact`; see [Keeping it fair](#supply-and-demand). |
| `pricing.recovery` | 0.05 | 0 – 1 | Fraction of the gap back to 1.0 closed at each step. |
| `pricing.interval_ticks` | 1200 | 1 – 1,728,000 | Game ticks between recovery steps. 20 ticks is 1 second. |
| `pricing.floor` | 0.2 | 0.001 – 1 | Lowest factor selling can reach. |
| `pricing.ceiling` | 5.0 | 1 – 1000 | Highest factor buying can reach. |

## All commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/market` | everyone* | Opens the market. |
| `/market stock [item]`, `/market price <item>` | everyone* | Shows stock, or an item's prices and price factor. |
| `/market sell <item> <qty>`, `/market buy <item> <qty>` | everyone* | Trades without the screen, at whatever the price is when the command runs. Unlike the screen, there's no "price changed, confirm again" step. Use `BLOCK_ONLY` if you don't want players trading this way. |
| `/market admin setstock <item> <qty>` | 2 | Sets an item's stock. Refused for unlimited items. |
| `/market admin clearstock <item>` | 2 | Empties an item's stock. |
| `/market admin resetprice <item>`, `all [preview]` or `tag <tag> [preview]` | 2 | Puts price factors back to 1.0. |
| `/market admin setprice`, `set`, `unset`, `remove`, `revert`, `info` | 2 | In-game editing; see [Editing prices in game](#editing-prices-in-game). |
| `/market admin diagnose [page]` | 2 | Lists catalog problems and things worth checking; see [Checking the catalog](#checking-the-catalog). |
| `/market admin prune [confirm]` | 2 | Lists, then deletes, saved data for items that no longer exist (backing up first). |
| `/market admin backup`, `/market admin backups` | 2 | Saves, or lists, backups of stock, prices and edits; see [Backups](#backups-and-restoring). |
| `/market admin restore <name> <stock\|prices\|edits\|all> [confirm]` | 2 | Shows what restoring would change, then restores with `confirm`. |
| `/market admin audit [page]` | 2 | The latest admin actions from the [audit log](#audit-log). |
| `/market admin tag <tag> ...`, `/market admin tags` | 2 | Tag rules; see [Tag rules](#tag-rules-pricing-many-items-at-once). |
| `/market admin classes` | 2 | Lists the [item classes](#item-classes), their values and how many listed items use each. |
| `/market admin reload` | 2 | Re-reads the catalog files. Stock and in-game edits are untouched. |
| `/market admin stats [item]` | 2 | Money created and removed by the market, and items moved; see [Where the money goes](#where-the-money-goes). |
| `/market admin export [modid]` | 4 | Writes `items.csv` and templates to `config/dynamic_economy/exports/<timestamp>/`. |
| `/market admin import "<path>" [preview]` | 4 | Imports a priced CSV. The path is relative to `config/dynamic_economy/`. |
| `/market admin suggest [modid]` | 4 | Works out prices for unlisted items from their recipes and writes `suggested.csv`; see [Suggesting prices from recipes](#suggesting-prices-from-recipes). |

\* Operators only when `access_mode` is `BLOCK_ONLY`. Permission 4 means the server console or a level-4 operator, since those commands read and write files on the server.

## Spreadsheet format

`items.csv` columns, in export order:

| Column | Used on import? | Notes |
| --- | --- | --- |
| `item_id` | Yes, required | The item to list. |
| `mod_id`, `display_name`, `max_stack_size`, `in_catalog` | No | For your reference while pricing. |
| `base_price` | Yes, required | Whole number, 1 or more. Blank means "don't import this row". |
| `class` | Yes | An [item class](#item-classes), such as `rare_resource`. Blank means none. |
| `unlimited_stock`, `markup`, `min_buy_markup`, `sell_fee`, `can_sell`, `can_buy`, `start_stock`, `dynamic` | Yes | Blank means "use the class's value, or else the default". |
| `sell_impact`, `buy_impact`, `recovery`, `floor`, `ceiling` | Yes | Per-item overrides; blank means "use the class's value, or else the server config". |

The importer is forgiving about how the file was saved:

- **Columns:** any order; extra columns are ignored; header names aren't case-sensitive.
- **Delimiters:** commas or semicolons, detected from the header row.
- **Decimals:** a decimal comma works, so `0,5` reads as 0.5.
- **True/false columns:** `true`/`false`, `yes`/`no` or `1`/`0`.
- **Quoting and encoding:** quoted fields may contain commas or line breaks; UTF-8 (with or without a byte-order mark) and Excel's Windows encoding are both read.

**Rows are rejected,** with a reason and line number, when:

- the item ID isn't a real item,
- `base_price` isn't a whole number of 1 or more,
- a value is out of range or unreadable,
- `min_buy_markup` breaks its rule, or
- the same item appears twice.

The rest of the file still imports. The first 15 reasons appear in chat, and all of them in the server log.

## Troubleshooting

| You see | Meaning |
| --- | --- |
| `N unpriced market entries (base_price 0 or below) skipped` | Template entries still waiting for a price. Harmless. |
| `Market file … entry N: unknown item …; skipped` | The item doesn't exist, usually because its mod isn't installed. |
| `Market file … entry N: Value … outside of range …; skipped` | A field's value is out of range; see [Catalog fields](#catalog-fields). |
| `Market file … overrides … listed earlier in … of the same pack` | Two files in one datapack list the same item; the later name wins. |
| `Market admin edit not applied: …` | A saved in-game edit no longer fits, for example because a catalog file changed. Check `/market admin info <item>`. |
| `Market item …: a round trip may make a profit` | The item's impacts, markup and fee allow a profit from buying then selling back, or selling then buying back; see [Supply and demand](#supply-and-demand). |
| The market is empty after upgrading | The example items are now an optional datapack; see [Pricing items](#pricing-items). |
| `Numismatics bank API not found; the market will refuse all trades` | The installed Numismatics version isn't compatible. This mod was built against 1.1.0. |
| A player sees "The market is only available at a Market Terminal" | `access_mode` is `BLOCK_ONLY`. |
