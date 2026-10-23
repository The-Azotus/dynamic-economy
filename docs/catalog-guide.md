# Market catalog guide

This guide is for server admins setting up the Numismatics: Dynamic Economy market. It covers what can be listed, how prices are worked out, every catalog field and config option, and the commands for pricing a whole modpack from a spreadsheet.

## How the market works

The market is owned by the server. Players sell items to it for spurs and buy items from it, all through their Numismatics bank account. Every player sees the same prices and the same stock.

- **Stock.** Items players sell go into one shared stock, and players buy from that stock. Items marked *unlimited* are always in stock instead.
- **Prices.** Each item has a base price. The market pays a little less than the going rate and charges a markup on top.
- **Supply and demand.** Selling an item pushes its price down and buying pushes it up. Prices drift back toward the base price over time.

Players open the market with `/market`.

## Pricing a modpack: the quick route

1. Run `/market admin export` from the server console or as an owner (permission 4). It writes a folder like `config/dynamic_economy/exports/2026-10-23_09-30-00/` with:
   - `items.csv`: every item in the game, one row each. Items already in the catalog have their current values filled in.
   - `templates/<modid>.json`: one catalog file per mod, listing only items that aren't in the catalog yet, each with `base_price: 0`.
2. Open `items.csv` in a spreadsheet. Fill in `base_price` for anything you want on the market, and change any other column you like. Leave `base_price` blank for items you don't want listed.
3. Save it, keeping it inside `config/dynamic_economy/`. Either CSV encoding works, UTF-8 or Excel's default.
4. Check it: `/market admin import "exports/2026-10-23_09-30-00/items.csv" preview`. This writes nothing; it tells you how many entries would be written, how many rows were skipped, and why any rows were rejected.
5. Import it: run the same command without `preview`.

Importing writes one file per mod, `imported_<modid>.json`, into a world datapack at `<world>/datapacks/dynamic_economy_catalog/`. The first import creates and enables that datapack. Later imports back up any file they replace as `<name>.json.bak`, then reload the catalog straight away. Importing never changes stock or current prices.

Put the path in quotes, because it contains slashes. To export only one mod, use `/market admin export <modid>`.

You can also skip the spreadsheet: copy a template from `templates/` into a datapack, fill in the prices, and reload. Entries still at `base_price: 0` are skipped with a single summary warning, so a half-finished template is safe to load.

## Catalog files

Catalog files live in any datapack at `data/<namespace>/market/<name>.json`. You can use as many files as you like; one file per category or per mod keeps things tidy.

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

- `entries` is required: a list of items to sell or buy.
- `defaults` is optional. Any field in it applies to every entry in that file unless the entry sets the field itself. It can't contain `item`.

**When two files list the same item**, the file from the higher-priority datapack wins, as with any other datapack override. World datapacks such as the imported one override the mod's built-in files. Within a single datapack, the file whose name sorts last wins, and the server log warns about it.

**After editing files**, run `/market admin reload` to reload just the market catalog, or `/reload` to reload all datapacks; use `/reload` after adding a new datapack. Neither changes stock.

**Bad entries are skipped, not fatal.** An unknown item, a value out of range or a missing field skips only that entry, with a warning in the server log that names the file and entry number. A file without an `entries` list, or with a `defaults` that isn't an object, is skipped entirely.

## Catalog fields

Prices are whole numbers of **spurs**, the smallest Numismatics coin (1 bevel = 8 spurs, 1 sprocket = 16, 1 cog = 64, 1 crown = 512, 1 sun = 4096).

| Field | Default | What it does |
| --- | --- | --- |
| `item` | *required* | The exact item ID, such as `minecraft:iron_ingot`. Enchanted, damaged or renamed copies of the item are never accepted. |
| `base_price` | *required* | What the market pays for one unit at normal supply, in spurs. Must be at least 1; 0 or below means "not priced yet" and the entry is skipped. |
| `markup` | `0.5` | Added on top when the market sells. `0.5` means buyers pay 150% of base. 0 to 1000. |
| `sell_fee` | `0.0` | Fraction kept back from what the market pays, such as `0.1` for 10%. Nobody receives it; it's a money sink. 0 to 0.99. |
| `can_sell` | `true` | Whether players may sell this item to the market. |
| `can_buy` | `true` | Whether players may buy this item from the market. |
| `start_stock` | `0` | Stock the market starts with, given only the first time the item appears. Later edits don't change existing stock; use `/market admin setstock`. Ignored for unlimited items. |
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

**Every unit in an order moves the price.** Each unit sold multiplies `s` by `1 − sell_impact`, never going below `floor`. Each unit bought multiplies it by `1 + buy_impact`, never above `ceiling`. A 640-item order therefore trades at a sliding price, not all at the price shown for one unit. Each unit is valued at the price it trades at, and the order's exact total is then rounded once: down when the market pays, up when it charges. Splitting an order into smaller ones never earns more or costs less than one large order. Buying unlimited items never moves their price.

**Prices recover in steps.** Every `interval_ticks` of game time (1 minute by default), every item's factor moves `recovery` of the way back to 1.0. Steps happen at the same moment for every item, and open market screens refresh at each step. Game time stops while the server is stopped, so downtime doesn't count.

**In the market screen,** ▼ means an item is cheaper than base and ▲ dearer; hovering shows the exact percentage. The line next to a selected item shows its recent price history. If a price moves between a player clicking and the server processing the order, the order is refused with the new total, and the player clicks again to accept it.

### Worked example (default settings)

Iron has `base_price: 16` and the default `markup: 0.5`.

- **Before any trades:** the market pays 16 and charges 24 per ingot.
- **Selling 640 ingots in one order** pays about 8,765 spurs in total, not 640 × 16 = 10,240, because each ingot sold lowers the price. Afterwards `s` is about 0.73, so the market pays 11 and charges 18 per ingot.
- **Recovery:** each minute, 5% of the remaining gap closes; after an hour, iron is back at about 99% of base.

### Keeping it fair

The server warns at startup about any item whose settings would let a player buy and immediately sell back at a profit. To stay safe:

- Keep `buy_impact` no higher than `markup`.
- Keep `buy_impact` no higher than `sell_impact / (1 − sell_impact)`. Setting the two impacts equal is always safe.

**Cheap items get coarse.** Prices are whole spurs, so an item with a `base_price` of 1 or 2 has little room to move. A single unit sold at a factor below 1 rounds down to 0, though larger orders still pay because the total is rounded only once. Give common items a higher base, such as 8 or 16 spurs, if you want their price movements to show.

## Server config

Settings live in `config/dynamic_economy-server.toml`, created on first start. Catalog entries can override everything in `[pricing]` except `interval_ticks`.

| Option | Default | Range | What it does |
| --- | --- | --- | --- |
| `max_quantity_per_request` | 640 | 1 – 1,000,000 | Largest quantity one buy or sell can move. Larger orders are refused. |
| `pricing.sell_impact` | 0.0005 | 0 – 0.5 | Each unit sold multiplies the factor by `1 − sell_impact`. At the default, selling 640 drops a price to about 73%. |
| `pricing.buy_impact` | 0.0005 | 0 – 0.5 | Each unit bought multiplies the factor by `1 + buy_impact`. |
| `pricing.recovery` | 0.05 | 0 – 1 | Fraction of the gap back to 1.0 closed at each step. 0 means prices never recover; 1 means they reset at every step. |
| `pricing.interval_ticks` | 1200 | 1 – 1,728,000 | Game ticks between recovery steps. 20 ticks is 1 second. |
| `pricing.floor` | 0.2 | 0.001 – 1 | Lowest factor selling can reach. |
| `pricing.ceiling` | 5.0 | 1 – 1000 | Highest factor buying can reach. |

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/market` | everyone | Opens the market screen. |
| `/market stock [item]` | everyone | Shows market stock. |
| `/market price <item>` | everyone | Shows an item's current prices, stock and price factor. |
| `/market sell <item> <qty>`, `/market buy <item> <qty>` | everyone | Trades without the screen, at the current price. |
| `/market admin setstock <item> <qty>` | 2 | Sets an item's stock. Refused for unlimited items. |
| `/market admin resetprice <item>` or `all` | 2 | Puts an item's price factor (or every item's) back to 1.0. |
| `/market admin reload` | 2 | Re-reads the market catalog files without touching stock. |
| `/market admin export [modid]` | 4 | Writes `items.csv` and per-mod templates to `config/dynamic_economy/exports/<timestamp>/`. |
| `/market admin import "<path>" [preview]` | 4 | Imports a priced CSV. The path is relative to `config/dynamic_economy/`. |

Permission 4 means the server console or a level-4 operator, since those commands read and write files on the server.

## Spreadsheet format

`items.csv` columns, in the order they're exported:

| Column | Used on import? | Notes |
| --- | --- | --- |
| `item_id` | Yes, required | The item to list. |
| `mod_id`, `display_name`, `max_stack_size`, `in_catalog` | No | For your reference while pricing. |
| `base_price` | Yes, required | Whole number, 1 or more. Blank means "don't import this row". |
| `unlimited_stock`, `markup`, `min_buy_markup`, `sell_fee`, `can_sell`, `can_buy`, `start_stock`, `dynamic` | Yes | Blank means "use the default". |
| `sell_impact`, `buy_impact`, `recovery`, `floor`, `ceiling` | Yes | Per-item overrides; blank means "use the server config". |

The importer is forgiving about how the file was saved:

- **Columns:** any order; extra columns you add are ignored; header names aren't case-sensitive.
- **Delimiters:** commas or semicolons, detected from the header row. Some spreadsheet locales save with semicolons.
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
| `Market item …: buying and reselling it may make a profit` | The item's impacts, markup and fee allow a buy-then-sell profit; see [Keeping it fair](#keeping-it-fair). |
| `Numismatics bank API not found; the market will refuse all trades` | The installed Numismatics version isn't compatible. This mod was built against 1.1.0. |
| Players get "The price just changed" | Someone else traded, or a recovery step happened, between their click and the server processing it. They click again. |
