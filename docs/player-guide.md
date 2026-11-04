# Player guide

The market is run by the server. You can sell it items for money and buy items from what everyone has sold. Everything is paid through your bank account, so you don't need coins in your inventory.

Which bank depends on the server. Normally the market has its **own bank** and coins, and money is counted in **bits**. On servers with Create: Numismatics, it uses your **Numismatics bank account** instead, and money is counted in **spurs**. 1 bit is worth exactly 1 spur, so prices are the same either way. The screen always shows the right unit; this guide says bits.

## Opening the market

- **Market Terminal.** Right-click one. Terminals look like a wooden table with piles of coins on it, and anyone can craft one (see [Crafting a terminal](#crafting-a-terminal)). If you walk more than about 8 blocks away, the market closes.
- **`/market`.** Opens the market from anywhere, if your server allows it. If you see *"The market is only available at a Market Terminal on this server"*, find a terminal instead.

## The market screen

The screen fills most of the window, and the list shows as many items as fit. On a wide window the selected item's panel is to the right of the list; on a narrow one it's below it.

Tabs along the top: **Market** (the item list), **Bank** (coins in and out, see [The Bank tab](#the-bank-tab)), **Contracts** (delivery orders, see [The Contracts tab](#the-contracts-tab)), **Exchange** (trading with other players, see [The Exchange tab](#the-exchange-tab)), **Leaderboard** (the richest players) and **History** (your own trades).

| Part | What it shows |
| --- | --- |
| **Bank** (top right) | The balance of the account you're using, in bits (spurs with Numismatics). Numismatics only shows up to 1,073,741,823; above that you'll see a **+**, and the rest is still there to spend. On a narrow screen it sits at the end of the search row and large amounts are shortened, such as **123k**. |
| **Search box** | Type part of an item's name to filter the list. **`@create`** shows only one mod's items (by its name or ID, so `@farmer` finds Farmer's Delight). **`#ingots`** shows items with a matching tag; `#c:ingots` matches that tag's full ID. Combine words to narrow it down: `@create #plates`, or `iron ingot` (both words must match). A mod ID followed by `:`, such as `create:`, still works too. |
| **Sort button** | Click to cycle the order: **Name**, **Cheapest** (lowest buy price first), **Best pay** (highest sell price first), **Stock** (most first). |
| **Filter button** | Click to cycle what's shown: **All**, **Buyable** (in stock and for sale), **Sellable** (the market buys it), **Held** (you have some it accepts). |
| **Stock** | How many the market has. **∞** means it never runs out. **0** in red means it's sold out. |
| **Buy** | What the market charges you for one. Cheap items show decimals, such as **0.99**. **–** means the market doesn't sell this item. |
| **Sell** | What the market pays you for one. **–** means the market doesn't buy this item. |
| **Have** | How many you could sell right now. |
| **▲ / ▼** (last column) | The price is above (▲) or below (▼) normal. Hover over the row for the exact percentage. |

The sort and filter you pick stay the same the next time you open the market, until you quit the game.

The **Leaderboard** tab lists the server's richest players by bank balance (plus money held in their open exchange buy orders, and exchange or contract money still owed to them), with your own line highlighted and your rank above the list (*You: #4 of 12*). It counts personal bank accounts only, not shared Blaze Banker or team accounts, and updates each time you open the tab. If the tab is greyed out, the server has turned it off.

The **History** tab lists your own last 50 trades, newest first: what you bought or sold, how many, the total, and how long ago. Click a trade to go back to the Market tab with that item selected. Only you see your trades.

Click an item to select it. Set a quantity with the box and the **−** / **+** buttons; hold Shift to change it by 16. Or use:

- **Max**: sets the quantity to the most you can buy in one order. It counts your bank balance, the market's stock, the room in your inventory and the order limit, and allows for the price rising as you buy.
- **All**: sets the quantity to every copy you hold, up to the order limit.

These only fill in the quantity; you still click **Buy** or **Sell**. The text beside them says what limited the amount, for example *"All you can afford"* or *"640 of 1,000 (limit)"*. Otherwise it shows the order limit, and turns red if the quantity is over it.

The **Buy** and **Sell** buttons show the total for that quantity; hover over one for the average price per item. They're greyed out if you can't afford the order, don't hold enough, don't have room, or the market is out of stock.

Next to the selected item, a small line shows its price over the last hour or so; the dotted line is the normal price. Hover over it for the low, the high and the current price. On a wide screen the panel also lists the item's buy and sell price, stock, how many you have, and its price as a percentage of normal (**Vs. base**).

The line along the bottom of the screen tells you how your last trade went.

## The Bank tab

The Bank tab moves money between coins and your bank account. The market itself never pays or takes coins: it only uses the account.

- **Deposit coins** pays every coin in your inventory into the bank. Only your main inventory counts (not armour or the offhand), and renamed coins are left alone. The button shows what your coins are worth.
- **Withdraw** takes an amount out as the fewest coins. Type the amount, and the line below shows the coins you'll get, such as *"As 1 × Crown, 3 × Mark, 7 × Bit"*, or why it can't happen. If the coins wouldn't all fit in your inventory, nothing is withdrawn; nothing is ever dropped on the ground.
- The list shows each coin, what it's worth, and how many you carry. **Click a coin** to withdraw one of exactly that coin; **Shift-click** for a stack of it, or as many as your balance covers. No amount to type. The same room rule applies.

With Numismatics' bank, the tab moves Numismatics' coins. With the market's own bank, it moves the market's coins.

**Bank Card.** Right-click a Bank Card to open the Bank tab anywhere, without a terminal. It opens only the Bank tab; buy and sell at a terminal. Recipe: a paper, a gold ingot and a Chip (or a Numismatics Bevel, on servers with Numismatics), in any arrangement.

**Team accounts** (the market's own bank only). If you're in a team, a **Using: my account / Using: team …** button appears in the Bank tab. The account you choose pays for what you buy, receives what you sell, and is where deposits and withdrawals go. Every member can use the team account. On servers with FTB Teams, your FTB Teams party is your team. Otherwise:

| Command | What it does |
| --- | --- |
| `/bank team create <name>` | Starts a team, with you as its owner. Names are one word: letters, digits, `_` and `-`. |
| `/bank team invite <player>` | Owner only: invites an online player. |
| `/bank team join <name>` | Joins a team that invited you. |
| `/bank team leave` | Leaves your team. If the owner leaves, the longest-standing member becomes owner. The last member to leave gets everything left in the team account. |
| `/bank team kick <player>` | Owner only: removes a member. |
| `/bank team` | Shows your team, its members and its balance. |

## Coins

The market's own coins (only on servers without Numismatics' bank):

| Coin | Worth | Coin | Worth |
| --- | --- | --- | --- |
| Bit | 1 bit | Crown | 4,096 bits |
| Chip | 8 bits | Sun | 32,768 bits |
| Stack | 64 bits | Divine | 262,144 bits |
| Mark | 512 bits | Omega | 2,097,152 bits |

Each is worth 8 of the one before. Put 8 of a coin in a crafting grid to make one of the next, or one coin to break it back into 8.

Numismatics' coins (on servers with Numismatics): spur 1, bevel 8, sprocket 16, cog 64, crown 512, sun 4,096 spurs. **Numismatics' Crown and Sun are worth less than the market's coins of the same name**, which is easy to mix up in guides; a server only uses one set.

On a server using Numismatics' bank, recipe viewers such as JEI may still list the market's coins, but they can't be crafted there.

## Selling from anywhere: tooltips

Hover over any item, in your inventory, a chest or a recipe viewer, to see what the market pays for it:

> Market pays 32 bits each (636 for all 20)

Cheap items show decimals, such as *"Market pays 0.99 bits each"*. Selling a whole stack still pays the exact total, rounded once.

If an item has no such line, the market doesn't buy it. To turn the line off, set `show_sell_price_in_tooltips = false` in `config/dynamic_economy-client.toml`.

## The Contracts tab

The server puts up a **board** of contracts: orders for a number of one item, such as *256 × Oak Log*, each with a reward. Everyone sees the same board, and a new one replaces it every few in-game days (5 by default; nights you sleep through count). The heading says how long the current one has left, and how many contracts you hold out of the most you may (1 by default).

1. **Claim** a contract to lock it to you. This takes a **deposit** (a fifth of the reward by default) from the account you're using. Nobody else can claim it while you hold it.
2. **Deliver** the items from your main inventory, in as many batches as you like: set the quantity and click **Deliver**. **All** fills in what you hold, up to what's still wanted. Delivered items are used up; they don't go to the market.
3. The **last unit pays the reward** and returns the deposit.

If the board is replaced before you finish, or you **Abandon** the contract (click twice), you're paid for the units you delivered and the deposit is lost. If that happens while you're offline, the money still goes to your bank and you get a message when you next log in. If you claimed with your team's account, the payment goes back to the team (or to you, if the team has ended).

Each row shows the item and amount, the reward, and **Open**, who holds it, your progress (*120/256*), **Done** or **Abandoned**. Click one to see its reward, the reward per unit, the deposit, progress and how many you have.

A contract always pays less per unit than the market could ever sell you that item for, so buying from the market to fill one loses money. It pays more than selling the same items to the market at their normal price, so if you gather or farm the items yourself, a contract is the better deal. Contracts for items you haven't unlocked (ProgressiveStages) don't show; one you've already claimed stays yours even if you lose the stage.

`/market contracts` lists the board in chat; `/market contracts claim <id>`, `deliver <id> <amount>` and `abandon <id>` do the same as the buttons, where the server allows `/market`.

## The Exchange tab

Where the server turns it on, the **Exchange** tab lets you trade with other players at prices you choose. The button at the top of the toolbar switches the list between **Items**, **Listings**, **List an item**, **My orders** and your **Box**.

**Items** lists the items that have orders, then the items you carry, with the cheapest seller's price (**Ask**), the highest buyer's price (**Bid**) and how many you have. Type in the search box to find any item at all, listed on the market or not. The ⇄ button on the Market tab's item panel opens the selected item here.

Click an item to see its **book**: what sellers ask above the line (cheapest nearest the line), what buyers bid below it (highest nearest), and how many units at each price. Your own share is marked with a *. Click a price to copy it into the **Price each** box. Then set a quantity and press:

- **Buy N for X**: holds X from the account you're using until the order fills. It buys straight away from any seller asking your price or less, cheapest first, and pays *their* price; whatever you saved comes straight back. The rest waits on the book for sellers.
- **Sell N for X**: takes N from your main inventory (no enchanted, damaged or renamed copies) and holds them. It sells straight away to any buyer bidding your price or more, highest first, at *their* price. The rest waits on the book for buyers.

When a sale goes through, the money goes to your bank less the exchange tax (5% by default, rounded up to a whole bit once per order, however many pieces it sells in); the button's tooltip shows what you'd get. Items you buy go to your **collection box**. You're paid and your box fills even while you're offline; you get a message when you next log in. Your own orders never trade with each other. You may have up to 10 open orders by default.

**Listings** are for items the book can't take: enchanted, damaged, renamed and other one-of-a-kind items (potions, enchanted books, tipped arrows, levelled relics too). Only fresh copies go on the book, exactly as the market sells them. A seller puts up one stack's worth of one exact item at a fixed price each, and buyers take it at that price; nothing is matched or haggled. The list shows each listing's price and how many are left, with a * on your own. Hover over one, or click it, to see the item in full: enchantments, durability, name. Set how many you want and press **Buy N for X**: the money is taken now and the items go to your **collection box**, exactly as listed.

**List an item** shows what in your main inventory can be listed. Click one, set a **Price each** and how many (up to one stack; **All** fills in what you have, up to a stack), and press **List N for X**. The items are taken from your inventory and held until bought. Each sale is paid into your bank less the exchange tax, as with sell orders; the note under the quantity shows the tax if all of it sells. Containers such as shulker boxes and bundles can be listed only when empty. Listings count toward your open orders.

**My orders** lists your open orders and listings with their price and how much has filled or sold. Click one, then **Cancel order** to get back what it still holds: a buy order's money to your bank, the items of a sell order or listing to your box. If the server makes orders expire, the panel shows the days left; an expired order or listing is returned the same way.

**Box** lists what waits for you. Click a line to take that item out, or **Take all**. Only what fits in your free inventory space moves; the rest stays as long as you like.

`/market exchange` lists your orders, listings and box in chat; `/market exchange book <item>`, `buy <item> <amount> <price>`, `sell <item> <amount> <price>`, `cancel <id>` and `collect [item]` do the same as the screen, where the server allows `/market`. `/market exchange list <amount> <price>` lists the item in your selected hotbar slot, `listings` shows every listing with its number, and `buylisting <number> <amount>` buys from one at its price.

## How prices move

- **Selling lowers the price** and **buying raises it**, a little with every item. A big order slides the price as it goes, so 640 items sell for less than 640 × the price shown for one.
- Splitting an order into smaller ones never gets you a better total than one big order.
- **Prices recover.** Every minute or so, each price moves part of the way back toward normal.
- Buying something and selling it straight back always loses a little money, so there's no free profit.

Money is counted in **bits**, or **spurs** on servers with Numismatics (1 bit = 1 spur); see [Coins](#coins).

## What the market accepts

- **Only plain items.** Enchanted, damaged or renamed copies don't count. If you hold 10 iron ingots and 5 of them are renamed, you can sell 5. Some mod data is let through by the server (relics' levels, for example); it's lost when you sell, and what you buy always comes fresh.
- **Only your main inventory** (hotbar and backpack rows). Armour, offhand and modded slots aren't checked.
- **Only orders that fit.** If you buy more than fits in your inventory, the purchase is refused. Nothing is ever dropped on the ground.
- **Only items you've unlocked.** On servers with ProgressiveStages, items locked for you don't appear in the market until you reach their stage. Prices are the same for everyone.

## Messages

| Message | Meaning |
| --- | --- |
| *Bought N × item for X* / *Sold N × item for X* | Done. |
| *Price changed from X to Y. Click again to confirm* | Someone else traded, or prices recovered, just as you clicked. Nothing was bought or sold. The button now shows the new total. |
| *You don't have that many (enchanted, damaged or renamed copies don't count)* | You hold fewer plain copies than you tried to sell. |
| *The market doesn't have that many in stock* | Try a smaller quantity. |
| *Your bank account can't cover that* | Not enough money for this order. |
| *Not enough room in your inventory* | Make space and try again. |
| *The market does not buy this item* / *The market does not sell this item* | That direction is switched off for this item. |
| *That item is not on the market* | It was taken off the market while you had the screen open. |
| *That order is too large* | Orders are capped (640 by default). Split it up. |
| *The bank is unavailable right now* | The server's bank can't be reached, usually because the world uses Numismatics' bank and Numismatics is missing or a different version. Tell an admin. |
| *Deposited X* / *Withdrew X* | Done. |
| *You carry no coins to deposit* | No plain coins of this server's set in your main inventory. |
| *Not enough money in the bank* | The account you're using can't cover the withdrawal. |
| *Not enough room in your inventory for the coins* | Make space, or withdraw less. |
| *Claimed the item contract; X deposit taken* | Done; now deliver. |
| *Finished the item contract: paid X* | The reward and your deposit are in the bank. |
| *Someone else has claimed that contract, or it's finished* | Pick another one. |
| *That contract has expired; the board has been replaced* | The board changed while you had the screen open. |
| *You already hold as many contracts as you may* | Finish or abandon one first. |
| *That's more than the contract still wants* | Deliver fewer. |
| *Posted an order for N item* | Nothing matched yet; it waits on the book. See it under **My orders**. |
| *All N item traded at once for X* | Done; bought items are in your box, sale money in your bank. |
| *The exchange is turned off on this server* | Ask an admin. Your box still works. |
| *You already have as many open orders as you may* | Cancel one, or wait for one to fill. |
| *That order is no longer open* | It filled or was cancelled while you had the screen open. |
| *That item can't be traded on the exchange* | Coins can't be, and neither can items you haven't unlocked. Fresh items go on the book, not in a listing; full shulker boxes and bundles can't be listed. |
| *That's your own listing* | Cancel it under **My orders** instead. |
| *Enter a price of at least 1* | Type a price each in the Price box. |

## Crafting a terminal

```
Coin  Coin            Coin
Coin  Crafting table  Coin
Coin  Coin            Coin
```

The coins are 8 Bits, or 8 Numismatics Spurs on servers with Numismatics. Your server may use a different recipe; check a recipe viewer if this one doesn't work. Break the terminal (by hand or with an axe) to pick it up again.
