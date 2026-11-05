# Plan: market list and tooltip tweaks (v0.4.2)

Two client-side display changes the user asked for on 2026-11-04. They change nothing about trading, prices or saved data. Work on branch `ui-tweaks`, which was made from `main` at v0.4.1.

The user made these decisions on 2026-11-04. Don't revisit them:

1. **Out-of-stock items are always hidden** from the Market tab's **All** and **Buyable** views. They still appear under **Sellable** and **Held**, so players can sell into an empty market, which is how stock grows. The filter still starts on **All**.
2. **The item tooltip says "Value: …".** One item: `Value: 32 bits`. A stack of more than one: `Value: 32 bits (636 for 20)`.
3. **The grey trend line is dropped** ("Price at N% of normal").

## Tweak 1: hide out-of-stock items

"Out of stock" means `!row.hasStock(1)`. That is a tracked stock of 0; unlimited items always have stock. The rule applies whether or not the market sells the item. So a sell-only item (`can_buy: false`) with 0 stock is hidden from **All** too, and shows there once someone has sold it one.

**`service/MarketListing.java`**
- In `shows`, change `case ALL -> true` to `case ALL -> row.hasStock(1)`. `CAN_BUY`, `CAN_SELL` and `HELD` stay as they are.
- Update the javadoc of `shows` and of the class. Hiding is still display only.
- Put any new decision logic here, not in the client, so GameTests can check it (that is why the class sits in `service/`).

**`client/MarketTab.java`**
- **List.** `rebuildList` already goes through `MarketListing.shows`, so nothing changes there. The list rebuilds whenever `ClientMarketState.version()` changes (`MarketScreen.containerTick`), so rows disappear when they sell out and come back when stocked, without reopening the screen.
- **Selection.** No change. `selectedRow()` reads `ClientMarketState.rows()`, not `visible`. After a player buys the last unit, the panel, chart and Sell button stay on that item, even though its row has left the list.
- **Empty list.** The message at about line 346 picks between `screen.dynamic_economy.empty` (nothing listed) and `no_matches`. With hidden rows, "No items match your search" would be wrong. Use:
  1. Nothing is listed at all: `empty` (unchanged).
  2. Rows are hidden only for having no stock, with an empty search: a new `screen.dynamic_economy.none_in_stock`, "Nothing is in stock. Sellable and Held show items to sell".
  3. The search matches rows, but all of them are hidden for having no stock: a new `screen.dynamic_economy.no_matches_in_stock`, "Nothing in stock matches. Sellable and Held show the rest".
  4. Otherwise: `no_matches` (unchanged).

  To choose between these, count in `rebuildList` the rows that match the search but fail only the stock rule. A small `MarketListing` helper for "hidden only for stock" keeps that testable.

**`src/main/resources/assets/dynamic_economy/lang/en_us.json`**
- Add the two new keys above.
- `screen.dynamic_economy.filter.all.tooltip`: "Showing every item in stock. Click to show only items you can buy".
- Check that `filter.held.tooltip` ("…Click to show everything") still reads right. It should now say "Click to show items in stock".

**Tests: `MarketTradeTests.listSortsAndFiltersWithoutTouchingTheMarket`.** Its fixture rows:
- `apple`: buyable and sellable, tracked stock 0.
- `bread`: sellable only, stock 50.
- `cake`: buyable only, unlimited stock.

Expected results:
- **All** by name: `[bread, cake]` (apple is hidden). Cheapest: `[cake, bread]`. Best payout: `[bread, cake]`. Stock: `[cake, bread]`.
- **Buyable**: `[cake]` (unchanged). **Sellable**: `[apple, bread]` (unchanged).
- **Held**: give apple a held count and expect `[apple, bread]`, which shows a sold-out item stays reachable for selling.
- If you add the "hidden only for stock" helper, add a test for it.

## Tweak 2: "Value" tooltip

**`client/SellPriceTooltip.java`**
- Keep the same checks: no line when the tooltip setting is off, inside the market screen, for an item that isn't listed, can't be sold or isn't plain, or for a hidden item (those aren't in `ClientMarketState.rows()`).
- The line still shows when the market has 0 stock, because the value doesn't depend on stock. Players can still see the value of items the list hides.
- One item: `Component.translatable("tooltip.dynamic_economy.sell_price", each)`, where `each` is `PriceFormat.money(PriceFormat.unitPrice(row.exactSellPrice(), RoundingMode.FLOOR))` as now.
- A stack of more than one: `sell_price_stack` with arguments `(each, PriceFormat.amount(row.sellTotal(count)), count)`. The total is a plain number with no unit, so it reads "Value: 32 bits (636 for 20)".
- Remove the trend line, along with `GREY` and any import left unused. Keep `GOLD`.
- Update the class javadoc.

**`lang/en_us.json`**
- `tooltip.dynamic_economy.sell_price`: "Value: %s"
- `tooltip.dynamic_economy.sell_price_stack`: "Value: %s (%s for %s)"
- Delete `tooltip.dynamic_economy.sell_price_trend`.

**Keep:**
- **The unit follows the bank.** `PriceFormat.money` gives "bits" with the mod's own bank and "spurs" with Numismatics, so it says "Value: 32 spurs" there. Don't hard-code "bits".
- **Cheap items still show decimals,** such as "Value: 0.99 bits".
- **The config key `show_sell_price_in_tooltips` keeps its name,** so players' saved settings still work. Only its comment changes, to "Show an item's market value in its tooltip, wherever the item appears."

## Docs

- `docs/player-guide.md`:
  - Filter row in the Market tab table: All now means "every item in stock".
  - Note that sold-out items are under Sellable and Held.
  - The **Stock** row's "0 in red" now only appears under those two filters.
  - "Selling from anywhere: tooltips": change the example to `Value: 32 bits (636 for 20)` and the decimal example to "Value: 0.99 bits". Drop any mention of the trend line.
- Grep `docs/` and `README.md` for "Market pays" and "of normal" to catch other mentions. The admin guide's "Cheap items get coarse" paragraph still holds.
- `CLAUDE.md`: update the Status line in every commit, as usual.

## Finishing

1. `./gradlew build`, then GameTests with both banks (`runGameTestServer`, then again with `-PwithoutMods=numismatics`). See the memory note on running Gradle for `JAVA_HOME`.
2. Review the changes against `docs/dev/known-issues.md`: fix findings that meet the release bar, and list the rest there.
3. Report to the user, who checks these in game:
   - The market opens with no 0-stock rows in All.
   - A sold-out item appears under Sellable and Held, and can be sold from there.
   - Buying the last unit keeps the item selected.
   - The empty-list messages.
   - Tooltips on a single item, a stack, a cheap item, an item at 0 stock, and with Numismatics' bank.
4. After the user approves: merge into `main`, set `mod_version=0.4.2`, tag `v0.4.2` and push, if the user asks for it.
