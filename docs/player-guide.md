# Player guide

The market is run by the server. You can sell it items for money and buy items from what everyone has sold. Everything is paid through your Numismatics bank account, so you don't need coins in your inventory.

## Opening the market

- **Market Terminal.** Right-click one. Terminals look like a brass box with a screen, and anyone can craft one (see [Crafting a terminal](#crafting-a-terminal)). If you walk more than about 8 blocks away, the market closes.
- **`/market`.** Opens the market from anywhere, if your server allows it. If you see *"The market is only available at a Market Terminal on this server"*, find a terminal instead.

## The market screen

| Part | What it shows |
| --- | --- |
| **Bank** (top right) | Your Numismatics bank balance, in spurs. Numismatics only shows up to 1,073,741,823; above that you'll see a **+**, and the rest is still there to spend. |
| **Search box** | Type part of an item's name to filter the list. Type a mod's name followed by `:` (for example `create:`) to see only that mod's items. |
| **Sort button** | Click to cycle the order: **Name**, **Cheapest** (lowest buy price first), **Best pay** (highest sell price first), **Stock** (most first). |
| **Filter button** | Click to cycle what's shown: **All**, **Buyable** (in stock and for sale), **Sellable** (the market buys it), **Held** (you have some it accepts). |
| **Stock** | How many the market has. **∞** means it never runs out. **0** in red means it's sold out. |
| **Sell** | What the market pays you for one. Cheap items show decimals, such as **0.99**. **–** means the market doesn't buy this item. |
| **Buy** | What the market charges you for one. **–** means the market doesn't sell this item. |
| **Have** | How many you could sell right now. |
| **▲ / ▼** | The price is above (▲) or below (▼) normal. Hover over the row for the exact percentage. |

The sort and filter you pick stay the same the next time you open the market, until you quit the game.

**Trades** (top right) swaps the list for your own last 50 trades, newest first: what you bought or sold, how many, the total, and how long ago. Click a trade to go back to the market with that item selected; **Items** returns without one. Only you see your trades.

Click an item to select it. Set a quantity with the box and the **−** / **+** buttons; hold Shift to change it by 16. Or use:

- **Max**: sets the quantity to the most you can buy in one order. It counts your bank balance, the market's stock, the room in your inventory and the order limit, and allows for the price rising as you buy.
- **All**: sets the quantity to every copy you hold, up to the order limit.

These only fill in the quantity; you still click **Buy** or **Sell**. The text beside them says what limited the amount, for example *"All you can afford"* or *"640 of 1,000 (limit)"*. Otherwise it shows the order limit, and turns red if the quantity is over it.

The **Buy** and **Sell** buttons show the total for that quantity; hover over one for the average price per item. They're greyed out if you can't afford the order, don't hold enough, don't have room, or the market is out of stock.

Next to the selected item, a small line shows its price over the last hour or so; the dotted line is the normal price. Hover over it for the low, the high and the current price.

The line under the buttons tells you how your last trade went.

## Selling from anywhere: tooltips

Hover over any item, in your inventory, a chest or a recipe viewer, to see what the market pays for it:

> Market pays 16 spurs each (318 for all 20)

Cheap items show decimals, such as *"Market pays 0.99 spurs each"*. Selling a whole stack still pays the exact total, rounded once.

If an item has no such line, the market doesn't buy it. To turn the line off, set `show_sell_price_in_tooltips = false` in `config/dynamic_economy-client.toml`.

## How prices move

- **Selling lowers the price** and **buying raises it**, a little with every item. A big order slides the price as it goes, so 640 items sell for less than 640 × the price shown for one.
- Splitting an order into smaller ones never gets you a better total than one big order.
- **Prices recover.** Every minute or so, each price moves part of the way back toward normal.
- Buying something and selling it straight back always loses a little money, so there's no free profit.

Money is counted in **spurs**: 1 bevel = 8 spurs, 1 sprocket = 16, 1 cog = 64, 1 crown = 512, 1 sun = 4096.

## What the market accepts

- **Only plain items.** Enchanted, damaged or renamed copies don't count. If you hold 10 iron ingots and 5 of them are renamed, you can sell 5.
- **Only your main inventory** (hotbar and backpack rows). Armour, offhand and modded slots aren't checked.
- **Only orders that fit.** If you buy more than fits in your inventory, the purchase is refused. Nothing is ever dropped on the ground.

## Messages

| Message | Meaning |
| --- | --- |
| *Bought N × item for X spurs* / *Sold N × item for X spurs* | Done. |
| *Price changed from X to Y spurs. Click again to confirm* | Someone else traded, or prices recovered, just as you clicked. Nothing was bought or sold. The button now shows the new total. |
| *You don't have that many (enchanted, damaged or renamed copies don't count)* | You hold fewer plain copies than you tried to sell. |
| *The market doesn't have that many in stock* | Try a smaller quantity. |
| *Your bank account can't cover that* | Not enough money for this order. |
| *Not enough room in your inventory* | Make space and try again. |
| *The market does not buy this item* / *The market does not sell this item* | That direction is switched off for this item. |
| *That item is not on the market* | It was taken off the market while you had the screen open. |
| *That order is too large* | Orders are capped (640 by default). Split it up. |
| *The bank is unavailable right now* | The server's Numismatics version doesn't match; tell an admin. |

## Crafting a terminal

```
Gold ingot     Cog (coin)      Gold ingot
Electron tube  Brass casing    Electron tube
Gold ingot     Redstone        Gold ingot
```

The cog is the Numismatics coin; the electron tube and brass casing are from Create. Your server may use a different recipe; check a recipe viewer if this one doesn't work. Break the terminal with a pickaxe to pick it up again.
