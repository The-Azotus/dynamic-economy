# Add-on guide

This guide is for mod developers writing an add-on that uses Dynamic-Economy, and for pack makers who connect other mods to it with data files and commands. It assumes you know NeoForge modding, but nothing about this mod's code.

For what the market does, read the [admin guide](admin-guide.md) first. This guide links to it rather than repeating it.

## What you can do

There are two ways to integrate:

- **Without Java.** Ship price files and item classes as data, and drive the market with commands (from functions, command blocks or KubeJS). This works in any version and needs no build setup. See [Without Java: data and commands](#without-java-data-and-commands).
- **With the Java API.** Read prices, trade for a player, read, take and pay money, hide items from players, and listen for trades, contracts, exchange fills and catalog reloads. See [The Java API](#the-java-api) and [Events](#events).

The Java API runs on the server only. A client-only mod can't use it.

### Stability

- The API is the packages `dynamiceconomy.api` and `dynamiceconomy.api.event`. Both are marked `@ApiStatus.Experimental` until 1.0: they may still change in a 0.x minor release (0.5 to 0.6, say). Every change is listed in this file's [changelog](#changelog).
- Everything else in the jar is internal. Those packages are marked `@ApiStatus.Internal` and may change in any release, a patch release included. Don't call them, even when a class is public: public there means "used by another package of this mod", not "supported".
- Data files and commands are described in the [admin guide](admin-guide.md), which is kept up to date with each release.

## Depending on Dynamic-Economy

The mod is published on [JitPack](https://jitpack.io/#The-Azotus/dynamic-economy) from its git tags. The coordinates are `com.github.The-Azotus:dynamic-economy:<tag>`, and tags start with `v`. **v0.5.0** is the first version with the API.

A sources jar and a javadoc jar are published with it, so your IDE shows the javadoc and the source. The javadoc covers only the API packages.

### Gradle

Compile against the mod, and put it on the dev runtime so it runs in your dev client and server. The `localRuntime` configuration below (the NeoForge MDK template has it) keeps the mod out of your published POM, so your users don't inherit it as a runtime dependency.

Groovy (`build.gradle`):

```groovy
repositories {
    exclusiveContent {
        forRepository { maven { url = 'https://jitpack.io' } }
        filter { includeGroup 'com.github.The-Azotus' }
    }
}

configurations {
    localRuntime
    runtimeClasspath.extendsFrom localRuntime
}

dependencies {
    compileOnly 'com.github.The-Azotus:dynamic-economy:v0.5.0'
    localRuntime 'com.github.The-Azotus:dynamic-economy:v0.5.0'
}
```

Kotlin (`build.gradle.kts`):

```kotlin
repositories {
    exclusiveContent {
        forRepository { maven("https://jitpack.io") }
        filter { includeGroup("com.github.The-Azotus") }
    }
}

val localRuntime: Configuration by configurations.creating
configurations.runtimeClasspath { extendsFrom(localRuntime) }

dependencies {
    compileOnly("com.github.The-Azotus:dynamic-economy:v0.5.0")
    localRuntime("com.github.The-Azotus:dynamic-economy:v0.5.0")
}
```

- The `exclusiveContent` block sends only that group to JitPack, so Gradle doesn't ask JitPack for every other dependency.
- JitPack builds a tag the first time anyone asks for it. That can take a few minutes, and the first Gradle run may time out; run it again.
- The mod's own optional dependencies (Create, Numismatics, FTB Teams, ProgressiveStages) aren't pulled in. In your dev runs the mod uses its own bank. To test with Numismatics' bank, add Numismatics and Create to your dev runtime yourself.
- To try your add-on against unreleased changes, clone the mod and run `gradlew publishToMavenLocal`. That publishes `com.github.The-Azotus:dynamic-economy:<mod_version>` (no `v`, such as `0.5.0`) to your local Maven. Add `mavenLocal()` inside the `exclusiveContent` block, since that block keeps the group away from every other repository: `forRepositories(maven { url = 'https://jitpack.io' }, mavenLocal())` in place of `forRepository { ... }` (Kotlin: `forRepositories(maven("https://jitpack.io"), mavenLocal())`).

### neoforge.mods.toml

Declare the dependency in your mod's `META-INF/neoforge.mods.toml`, replacing `yourmod` with your mod ID:

```toml
[[dependencies.yourmod]]
    modId="dynamic_economy"
    type="required"        # or "optional"
    versionRange="[0.5,)"
    ordering="AFTER"
    side="BOTH"
```

- Dynamic-Economy must be installed on the server and on every client, so use `side="BOTH"`.
- Since the API may change in a 0.x minor release, you can cap the range at the next minor version, such as `[0.5,0.6)`, and raise it once you've checked the changelog.

### An optional dependency

With `type="optional"`, your mod must load without Dynamic-Economy. Java loads a class's referenced types when the class is first used, so:

1. Check `ModList.get().isLoaded("dynamic_economy")` before touching anything of the mod's.
2. Keep every reference to `dynamiceconomy.api` types (calls, event types, lambdas) in a class of its own that is loaded only after that check passes.
3. Register your event listeners from that class with `NeoForge.EVENT_BUS.addListener`, not with `@EventBusSubscriber`: NeoForge loads a subscriber class whether or not the mod is there.

The [worked example](#worked-example) is set up this way.

## Without Java: data and commands

### Price files

A mod can ship market prices for its own items in its jar, and a pack maker can ship them in any datapack. The format is in the admin guide's [Catalog files](admin-guide.md#catalog-files) and [Catalog fields](admin-guide.md#catalog-fields).

Put the file in **your own namespace**, such as `data/yourmod/market/yourmod.json`:

```json
{
  "required_mods": ["yourmod"],
  "entries": [
    { "item": "yourmod:copper_gear", "base_price": 40,    "class": "common_processed" },
    { "item": "yourmod:star_core",   "base_price": 20000, "class": "unique", "can_sell": false }
  ]
}
```

- **Never use `data/dynamic_economy/market/<name>.json`** with the name of a built-in file (such as `minecraft.json` or `create.json`). Only the highest pack's copy of a file ID is read, so your file would hide the whole built-in file, and every item it leaves out would lose its built-in price. See [Changing a built-in price](admin-guide.md#changing-a-built-in-price).
- `"required_mods"` skips the whole file quietly when any of the listed mods isn't installed. Use it for prices of another mod's items. NeoForge's `neoforge:conditions` isn't read in these files.
- Prices are in bits. The built-in scale is roughly cobblestone 1, a log 8, an iron ingot 32, a diamond 512, a nether star 16,384 (see [Pricing items](admin-guide.md#pricing-items)).
- Give each entry a [class](admin-guide.md#item-classes) rather than tuning numbers yourself. The built-in classes all pass the market's round-trip check.
- Coins can never be listed. An entry for one is refused.
- Check your file in game: `/market admin info <item>` shows each value and the file it came from, and `/market admin diagnose` lists skipped entries and price gaps, such as an item that sells for more than its ingredients cost to buy.

### Classes

A class file goes in `data/<namespace>/market_classes/<name>.json`, and entries refer to it as `<namespace>:<name>`, such as `"class": "yourmod:gears"`. A bare name means `dynamic_economy:`. The fields are in [Item classes](admin-guide.md#item-classes). A class replaces a class of the same ID whole, so don't put a file in `data/dynamic_economy/market_classes/`: it would replace a built-in class for every item that uses it.

### Where your files sit in the priority order

When several files list one item, each higher file changes only the fields it sets. From lowest to highest:

1. Dynamic-Economy's built-in prices.
2. **Mods' own jar data**, yours included. A mod's jar counts as built-in.
3. The [config folder](admin-guide.md#the-config-folder-prices-for-every-world), `config/dynamic_economy/catalog/`, which modpacks use for pack-wide prices.
4. Every other datapack: the world's datapacks, and packs from OpenLoader, KubeJS and the like.

So prices in your jar beat the built-in ones for the same item, and pack makers and server admins can still change any of them without touching your jar. In-game edits and tag rules beat every file.

### Commands

The full list is in the admin guide's [All commands](admin-guide.md#all-commands). These are the useful ones for scripts:

| Command | Permission | Use |
| --- | --- | --- |
| `/market sell <item> <qty>`, `/market buy <item> <qty>` | everyone* | Trades for the player running it, at the current price. Returns the quantity traded, 0 on failure. |
| `/market admin money balance <player>` | 2 | Returns the player's balance, capped at 2,147,483,647. |
| `/market admin money give <player> <amount>`, `take <player> <amount>` | 2 | Gives or takes money; returns the amount moved, 0 on failure. See [Giving and taking money](admin-guide.md#giving-and-taking-money). |
| `/market admin setprice`, `set`, `unset`, `tag …` | 2 | Changes prices and fields with every check, an audit line and an update to every client. The safe way for a script to change prices. See [Editing prices in game](admin-guide.md#editing-prices-in-game). |
| `/market admin setstock <item> <qty>` | 2 | Sets an item's stock. |
| `/market admin reload` | 2 | Re-reads the catalog files after a script writes them. |

\* Operators only when the server's `access_mode` is `BLOCK_ONLY`. Functions and command blocks run at level 2, so they can use them in either mode.

The trade commands need a player as the source, so run them with `execute as`. Each command's return value works with `execute store`:

```mcfunction
execute as @p run market sell minecraft:wheat 64
execute as @p store result score @s sold run market sell minecraft:wheat 64
execute store result score @p balance run market admin money balance @p
market admin money give Steve 500
```

KubeJS has no bindings for this mod. It can write price files, change item tags (which [tag rules](admin-guide.md#tag-rules-pricing-many-items-at-once) follow) and run the commands above.

## The Java API

Everything is in `dynamiceconomy.api.DynamicEconomyApi`, as static methods. The javadoc has the details; this section gives the shape and the gotchas.

### Concepts

- **Bits.** Every amount and price is a whole number of bits. A world keeps its money in one of two banks: the mod's own (bits), or Create: Numismatics' (spurs) when that mod is installed. 1 bit = 1 spur, so an amount means the same in both, and your code doesn't need to care which. `bankType` says which one, and `formatMoney` writes an amount in the right unit.
- **Int-sized requests.** Both banks take at most 2,147,483,647 (`Integer.MAX_VALUE`) in one deposit or deduction. Amounts are `long`, but one call moves at most that much; more is refused as `TOO_LARGE`. Balances may be larger with the mod's own bank. With Numismatics' bank a balance reads at most 1,073,741,823, the most Numismatics shows: a balance at that value means at least that much, and the rest can still be spent.
- **The server thread.** Every method that reads or changes the market or the bank must run on the server thread, and throws `IllegalStateException` otherwise. Neither bank is thread-safe. From a packet handler, use `IPayloadContext.enqueueWork`; from another thread, `server.execute`. The few methods that may run on any thread say so below.
- **Every check before any change.** A call either does everything or changes nothing. Only `MarketResult.OK` changed anything; every other result left the market, the bank and the player's inventory as they were.
- **Results, not exceptions.** Trades return a `TradeResult`, payments a `PaymentResult`, each with a `MarketResult` and `ok()`. Exceptions are only for programming errors: the wrong thread or a null argument.
- **Accounts.** Methods that take a `ServerPlayer` use the account the player trades with: their team's account when they chose to pay from it in the Bank tab, otherwise their own. Methods that take a `MinecraftServer` and a `UUID` use the player's own account, online or not. Team accounts exist only with the mod's own bank.

### Market

| Method | Returns |
| --- | --- |
| `Optional<MarketQuote> quote(MinecraftServer server, Item item)` | The item's terms right now, as every player would trade it. Empty if the item isn't listed. Includes items hidden from some players. |
| `Optional<MarketQuote> quote(ServerPlayer player, Item item)` | The same, but also empty if the item is hidden from this player. |
| `List<Item> listedItems(MinecraftServer server)` | Every listed item, hidden ones included, in no order. Unmodifiable, and a snapshot: ask again after a [`CatalogReloadedEvent`](#events). |
| `TradeResult sell(ServerPlayer player, Item item, int quantity)` | Sells from the player's inventory to the market. |
| `TradeResult buy(ServerPlayer player, Item item, int quantity)` | Buys from the market into the player's inventory. |

A `MarketQuote` holds:

| Field | Meaning |
| --- | --- |
| `item` | The item. |
| `basePrice` | Its base price from the catalog. |
| `sellPrice` | What the market pays for the next unit. |
| `buyPrice` | What the market charges for the next unit. |
| `stock` | How many the market holds, as an `OptionalLong`; empty for unlimited stock. |
| `canSell`, `canBuy` | Whether players may sell it to the market, and buy it from the market. |
| `factor` | The supply-and-demand factor: 1.0 is the base price, lower after players sell, higher after they buy. |

Prices are the same for every player. They're for **one** unit: every unit in an order moves the price, so an order's total isn't the unit price times the quantity. Use the total in the `TradeResult`.

`sell` and `buy` work exactly as the market screen does: the same checks, price movement, stock, trade history and [`MarketTradeEvent`](#events). Afterwards the item's new price goes out to every player, and the player's open market screen updates.

- They trade at the current price. There's no quote to confirm, so `PRICE_CHANGED` never comes back.
- Only the player's **main inventory** counts: the hotbar and the 27 storage slots. Armour, the offhand and modded slots are ignored.
- Only **plain copies** sell: no enchanted, damaged or renamed ones (apart from data the server's `ignored_components` setting ignores). Bought items always come plain.
- `buy` checks that every item fits before taking money. Items are never dropped on the ground.
- One call moves at most the server's `max_quantity_per_request` (640 by default); more is `TOO_LARGE`. Split larger amounts into several calls.
- The server's `access_mode` isn't checked: your add-on decides who may trade, and where.
- An item hidden from the player answers `NOT_LISTED`, the same as an unlisted one.

`TradeResult` holds `result`, `quantity` (the units traded; 0 unless OK), `unitPrice` (what the first unit traded at, or would have) and `total` (what the whole order paid or cost, or would have). On `PRICE_CHANGED`, `NO_FUNDS`, `NO_STOCK`, `NO_SPACE` and `NOT_ENOUGH_ITEMS`, `total` is what the order would have cost or paid. On `TOO_LARGE` after pricing, `unitPrice` is set and `total` is 0. On `BANK_UNAVAILABLE`, and when a trade fails before it is priced, both are 0.

### Money

| Method | Does |
| --- | --- |
| `OptionalLong balance(ServerPlayer player)` | The balance of the account the player trades with. |
| `OptionalLong balance(MinecraftServer server, UUID player)` | The balance of a player's own account, online or not. A player with no account yet gets an empty one. |
| `PaymentResult charge(ServerPlayer player, long amount, ResourceLocation reason)` | Takes money from the account the player trades with, all or nothing. |
| `PaymentResult charge(MinecraftServer server, UUID player, long amount, ResourceLocation reason)` | Takes money from a player's own account, online or not. |
| `PaymentResult pay(ServerPlayer player, long amount, ResourceLocation reason)` | Pays into the account the player trades with. |
| `PaymentResult pay(MinecraftServer server, UUID player, long amount, ResourceLocation reason)` | Pays into a player's own account, online or not. |

- `balance` is empty when the bank can't be reached. With Numismatics' bank it is the balance Numismatics shows, at most 1,073,741,823; at that value, read it as "at least".
- `amount` must be from 1 to 2,147,483,647: zero or less is `INVALID_AMOUNT`, more is `TOO_LARGE`.
- `reason` names the payment in your namespace, such as `yourmod:quest_reward`. It shows in the audit log.
- `charge` returns `NO_FUNDS` when the account can't cover the amount, and `BANK_UNAVAILABLE` when the bank can't be reached.
- `pay` never fails for a valid amount: if the bank can't be reached, the money is owed (see [Owed payments](#owed-payments)).
- An online player's open market screen shows the new balance. Neither method sends the player a message: tell them yourself.
- Each successful `pay` or `charge` writes one line to the server's [audit log](admin-guide.md#audit-log): actor `api:<reason>`, action `api pay` or `api charge`, the player (and team account, if it was one), the amount, and `ok` or `owed: bank unreachable`. The log is size-capped and shared with admin actions, so pay many small rewards in one call rather than one per event.
- These don't count in `/market admin stats`, which counts the market's own trades. `sell` and `buy` do count.

`PaymentResult` holds `result`, `amount` (the amount asked for) and `owed` (true when a payment is owed rather than paid; always false for a charge). `ok()` is true when the money moved, or is owed.

#### The money rule

`pay` **creates** money and `charge` **destroys** it. The market itself has no balance: sales create money and purchases destroy it, and its markup, fees and price impact make sure that buying N items and selling them straight back never makes a profit.

So **anything that swaps items for money must use `sell`**, never take the items and call `pay`. A shop block, a trade-in quest or a "sell everything" button that pays its own price would let players buy items from the market and turn them in for more, creating money without end. `sell` keeps the market's price impact and fees.

Use `pay` for rewards that cost the player nothing the market sells: a finished quest, a daily login bonus, a boss kill. Use `charge` for fees and services: a teleport, a claim, a repair.

#### Owed payments

The bank can only be unreachable in a world set to Numismatics' bank with Numismatics missing (or a Numismatics version whose bank API this mod doesn't recognise). The mod's own bank is always reachable. When `pay` can't reach the bank:

- The money is owed instead, and the result's `owed()` is true.
- Owed money is saved with the world (`dynamic_economy_api_payments`), so a restart doesn't lose it.
- It's paid on the server tick as soon as the bank is back, tried again at most every 10 seconds after a failure.
- A payment meant for a team account goes to that team's account if the team still exists, otherwise to the player's own.
- Meanwhile it counts on the leaderboard, unless it's going to a team account (team accounts aren't ranked).

`charge` is never owed: it's refused with `BANK_UNAVAILABLE`.

### Visibility

Add-ons can hide items from players, as ProgressiveStages does with its locks (see [ProgressiveStages](admin-guide.md#progressivestages-hiding-locked-items)).

| Method | Thread | Does |
| --- | --- | --- |
| `void addVisibilityRule(ResourceLocation id, BiPredicate<ServerPlayer, Item> hides)` | any | Adds a rule, replacing any rule with the same ID. |
| `boolean removeVisibilityRule(ResourceLocation id)` | any | Removes it; returns whether there was one. |
| `void refreshVisibility(@Nullable ServerPlayer player)` | server | Call when a rule's answer changed for this player, or for anyone (`null`). |
| `boolean isHidden(ServerPlayer player, Item item)` | server | Whether the item is hidden from the player, by ProgressiveStages or any rule. |

- `hides` returns true for an item the player must not see. An item is hidden when any rule, or ProgressiveStages, hides it.
- A hidden item is left out of everything that player is sent: the market list, item tooltips, contracts and the player exchange. Trading it answers `NOT_LISTED`, as if it weren't listed, so the reply doesn't reveal it.
- Prices and stock stay shared. A hidden item still moves in price when others trade it.
- Admin commands and the server console see every item.
- **Keep the rule cheap and free of side effects.** It runs on the server thread for every listed item each time a player's catalog is sent, and on every trade. Check the cheap condition (is this one of my items?) first.
- A rule that throws is removed and logged. The items it hid show again the next time each player's catalog is sent (on rejoining, a reload, or any rule change or `refreshVisibility` call).
- Rules aren't saved. Add them while your mod loads (the mod constructor is fine) or when the server starts.
- After `addVisibilityRule`, `removeVisibilityRule` or `refreshVisibility`, catalogs are sent again on the next tick, so many calls in one tick send once. `refreshVisibility` does nothing when no server is running.

### Helpers

| Method | Thread | Returns |
| --- | --- | --- |
| `Component formatMoney(MinecraftServer server, long amount)` | server | The amount as chat shows it, in the world bank's unit, such as "1,234 bits". |
| `BankType bankType(MinecraftServer server)` | server | `OWN` or `NUMISMATICS`. A world picks its bank once and keeps it unless an admin switches it. |
| `boolean bankAvailable(MinecraftServer server)` | server | Whether the bank can be reached. When it can't, trades and charges are refused and payments are owed. |
| `boolean isCoin(Item item)` | any | Whether the item is one of this mod's coins or one of Numismatics'. Coins are never listed. |

### Results

`MarketResult` is the API's own enum. Only `OK` changed anything.

| Value | From | Means |
| --- | --- | --- |
| `OK` | all | Done. For `pay`, possibly owed: check `owed()`. |
| `NOT_LISTED` | `sell`, `buy` | The item isn't listed, or is hidden from this player. |
| `SELL_DISABLED` | `sell` | The market doesn't buy this item (`can_sell: false`). |
| `BUY_DISABLED` | `buy` | The market doesn't sell this item (`can_buy: false`). |
| `NOT_ENOUGH_ITEMS` | `sell` | The player's main inventory holds fewer plain copies than asked. |
| `NO_STOCK` | `buy` | The market holds fewer than asked. |
| `NO_FUNDS` | `buy`, `charge` | The account can't cover it. |
| `NO_SPACE` | `buy` | The items wouldn't fit in the player's main inventory. |
| `PRICE_CHANGED` | never, from the API | The price differs from one the player was shown. The API trades at the current price. |
| `TOO_LARGE` | `sell`, `buy`, `pay`, `charge` | A quantity above `max_quantity_per_request`, or a total or amount above 2,147,483,647 bits. |
| `INVALID_QUANTITY` | `sell`, `buy` | A quantity of zero or less. |
| `BANK_UNAVAILABLE` | `sell`, `buy`, `charge` | The bank can't be reached. |
| `INVALID_AMOUNT` | `pay`, `charge` | An amount of zero or less. |

Handle the values you care about and treat the rest as "didn't happen": a later version may add values.

## Events

Dynamic-Economy posts these on `NeoForge.EVENT_BUS` (the game bus), in the package `dynamiceconomy.api.event`. Prices and amounts are in bits.

| Event | Fires | Fields (getters) |
| --- | --- | --- |
| `MarketTradeEvent` | After every market trade, from the screen, a command or the API. | `getServer`, `getPlayer` (`ServerPlayer`), `getItem` (`Item`), `isBuy` (false for a sale), `getQuantity`, `getUnitPrice` (the first unit's price), `getTotal` (what changed hands) |
| `ContractEvent.Claimed` | A player claimed a contract and paid its deposit. | common fields, plus `getDeposit` |
| `ContractEvent.Delivered` | The holder delivered units, the last delivery included. | common fields, plus `getQuantity` (this delivery) |
| `ContractEvent.Completed` | The last unit arrived; reward paid and deposit returned. Posted after its `Delivered`. | common fields, plus `getReward` (without the deposit), `getDeposit` |
| `ContractEvent.Abandoned` | The holder gave it up: delivered units paid for, deposit lost. | common fields, plus `getPaid`, `getDepositLost` |
| `ContractEvent.Expired` | A held contract ended unfinished: its board was replaced (time ran out, or an admin refreshed it) or contracts were turned off. Delivered units are paid for. | common fields, plus `getPaid`, `getDepositLost` (0 when returned), `isDepositReturned` (true when contracts were turned off) |
| `ExchangeTradeEvent` | Two players traded on the player exchange: once per fill of an order (an order that fills against three others posts three), and once per purchase from a listing. | `getServer`, `getBuyer`, `getSeller` (UUIDs), `getItem` (one unit as an `ItemStack`, a new copy each call; a listing's keeps its components), `getQuantity`, `getUnitPrice`, `getValue` (what the buyer paid), `getTax` (destroyed), `isFromListing` |
| `CatalogReloadedEvent` | The catalog was rebuilt: at server start, after `/reload` or `/market admin reload`, after an import or a restore of a backup's edits, and after every in-game admin edit. | `getServer` |

The common contract fields are `getServer`, `getContractId` (as `/market contracts` shows it), `getHolder` (UUID; may be offline), `getItem` (a `ResourceLocation`, since a contract can outlive the mod that adds its item), `getUnits` (asked for in all) and `getDelivered` (delivered in all, counting this action). Listen for `ContractEvent` itself to hear all five.

Rules for all of them:

- **Posted after the change is complete.** Money and items have moved and the saved data is updated. When one action changes several things (a board replacement ending several contracts, an order filling against several others), its events are collected and posted together after the last change, never in the middle.
- **Never cancellable.** To stop a trade before it happens, hide the item with a [visibility rule](#visibility), or decide in your own code whether to call the API.
- **Only for actions that succeeded**, and always on the server thread, so a listener may call the API.
- **A listener that throws is logged and ignored**, so it can't undo or interrupt market work. Listeners after it on the same event may then not run.
- **`CatalogReloadedEvent` can fire more than once for one admin action** (an edit the catalog rules refuse is applied, then undone). Treat it as "the catalog may have changed", and re-read anything you cached from it, such as `listedItems`. Don't cache quotes: prices also move with trades, admin resets and recovery over time, which don't all post an event, so call `quote` when you need a price.
- A `MarketTradeEvent` listener that trades through the API posts another `MarketTradeEvent`. Guard against looping.
- With an exchange sale, the seller has been paid `getValue() − getTax()`, or is owed it if the bank couldn't be reached. The bought units are in the buyer's collection box. Either player may be offline.
- When a contract expires and the bank can't be reached, its payment is owed and paid later; the event is posted either way.

To subscribe, add a listener:

```java
NeoForge.EVENT_BUS.addListener((MarketTradeEvent event) -> { /* ... */ });
```

or, with a required dependency, a subscriber class on the game bus:

```java
@EventBusSubscriber(modid = "yourmod")
public final class MarketListeners {
    @SubscribeEvent
    public static void onContractDone(ContractEvent.Completed event) {
        // ...
    }
}
```

## Worked example

A quest mod with an optional dependency on Dynamic-Economy. It pays quest rewards, shows an item's price, sells turned-in wheat for a player, hides nether items until the player has been to the Nether, and counts wheat sold for a quest. `QuestProgress` stands for the quest mod's own code.

The mod class checks for Dynamic-Economy and touches nothing of it:

```java
package com.example.questcoins;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

@Mod(QuestCoins.MOD_ID)
public final class QuestCoins {
    public static final String MOD_ID = "questcoins";
    /** Check this before calling anything in EconomyHooks. */
    public static final boolean ECONOMY_LOADED = ModList.get().isLoaded("dynamic_economy");

    public QuestCoins(IEventBus modBus) {
        if (ECONOMY_LOADED) {
            EconomyHooks.register();
        }
    }
}
```

Every reference to the API is in one class, loaded only when the mod is there. The rest of the quest code calls it only behind the same check, for example `if (QuestCoins.ECONOMY_LOADED) EconomyHooks.rewardQuest(player, 100);`:

```java
package com.example.questcoins;

import com.mojang.logging.LogUtils;
import dynamiceconomy.api.DynamicEconomyApi;
import dynamiceconomy.api.MarketQuote;
import dynamiceconomy.api.PaymentResult;
import dynamiceconomy.api.TradeResult;
import dynamiceconomy.api.event.MarketTradeEvent;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.Set;

/** Everything that touches Dynamic-Economy. Loaded only when it is installed. */
final class EconomyHooks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation QUEST_REWARD = id("quest_reward");
    private static final ResourceLocation NETHER_LOCK = id("nether_lock");
    private static final ResourceLocation ENTER_NETHER = ResourceLocation.withDefaultNamespace("story/enter_the_nether");
    private static final Set<Item> NETHER_ITEMS = Set.of(Items.BLAZE_ROD, Items.QUARTZ, Items.NETHERITE_SCRAP);

    private EconomyHooks() {
    }

    static void register() {
        // Safe while mods load: rules may be added from any thread.
        DynamicEconomyApi.addVisibilityRule(NETHER_LOCK, EconomyHooks::hidesNetherItem);
        NeoForge.EVENT_BUS.addListener(EconomyHooks::onAdvancement);
        NeoForge.EVENT_BUS.addListener(EconomyHooks::onMarketTrade);
    }

    /** Runs very often, so the cheap check comes first. */
    private static boolean hidesNetherItem(ServerPlayer player, Item item) {
        if (!NETHER_ITEMS.contains(item)) {
            return false;
        }
        AdvancementHolder nether = player.server.getAdvancements().get(ENTER_NETHER);
        return nether != null && !player.getAdvancements().getOrStartProgress(nether).isDone();
    }

    /** The rule's answer changed for this player: send them their catalog again. */
    private static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getAdvancement().id().equals(ENTER_NETHER) && event.getEntity() instanceof ServerPlayer player) {
            DynamicEconomyApi.refreshVisibility(player);
        }
    }

    /** Called by the quest code, on the server thread, when a player finishes a quest. */
    static void rewardQuest(ServerPlayer player, long reward) {
        // A reward costs the player nothing the market sells, so paying it is fine (see "The money rule").
        PaymentResult result = DynamicEconomyApi.pay(player, reward, QUEST_REWARD);
        if (!result.ok()) {
            LOGGER.warn("Quest reward {} for {} refused: {}", reward, player.getGameProfile().getName(), result.result());
            return;
        }
        Component money = DynamicEconomyApi.formatMoney(player.server, reward);
        player.sendSystemMessage(result.owed()
                ? Component.literal("Your reward of ").append(money).append(" will be paid when the bank is back")
                : Component.literal("Quest reward: ").append(money));
    }

    /** "Blaze Rod sells for 24 bits", or empty if the market doesn't buy it from this player. */
    static Optional<Component> priceLine(ServerPlayer player, Item item) {
        return DynamicEconomyApi.quote(player, item)
                .filter(MarketQuote::canSell)
                .map(quote -> Component.empty()
                        .append(item.getDescription())
                        .append(" sells for ")
                        .append(DynamicEconomyApi.formatMoney(player.server, quote.sellPrice())));
    }

    /** A turn-in quest: the wheat is sold to the market, never bought from the player with pay(). */
    static void turnInWheat(ServerPlayer player, int quantity) {
        TradeResult result = DynamicEconomyApi.sell(player, Items.WHEAT, quantity);
        switch (result.result()) {
            case OK -> player.sendSystemMessage(Component.literal("Sold " + result.quantity() + " wheat for ")
                    .append(DynamicEconomyApi.formatMoney(player.server, result.total())));
            case NOT_ENOUGH_ITEMS -> player.sendSystemMessage(Component.literal("You don't have that much wheat"));
            default -> player.sendSystemMessage(Component.literal("The market won't take wheat right now"));
        }
    }

    /** Counts wheat sold any way, screen, command or API, toward a quest. */
    private static void onMarketTrade(MarketTradeEvent event) {
        if (!event.isBuy() && event.getItem() == Items.WHEAT) {
            QuestProgress.add(event.getPlayer(), "sell_wheat", event.getQuantity());
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(QuestCoins.MOD_ID, path);
    }
}
```

Its prices ship in its jar at `data/questcoins/market/questcoins.json`, as in [Price files](#price-files).

## Changelog

- **0.5.0:** first version of the API.
