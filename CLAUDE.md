# Dynamic-Economy — build brief

NeoForge 1.21.1 mod (NeoForge 21.1.228, Java 21; optional: Create 6.0.10, Numismatics 1.1.0). Adds a **server-owned global market**: players sell items to the server for currency paid into their bank account, sold items enter one shared persistent stock, and players buy from that stock at a markup. Prices move with supply and demand. Until Phase 14 is done the mod still requires Create and Numismatics and is named "Numismatics: Dynamic Economy"; Phase 14 renames it and gives it its own coins and bank, using Numismatics' bank when that mod is installed.

Where to find more (read only when needed):
- `docs/market-design.md` — background and reasoning. This file overrides it.
- `docs/admin-guide.md`, `docs/player-guide.md` — every command, catalog field and config option as built.
- `docs/phase-history.md` — specs for finished Phases 0–13 (Phase 10 includes how `suggest` and the built-in prices work).
- `docs/roadmap.md` — specs for Phases 15–17.

## How to work

- Phases run in order. **After each phase: run the tests, then stop and report** (see "Report format"). Wait for approval before the next phase.
- Never rewrite working code just to match this brief's wording. If code and brief disagree, list the difference in your report and ask.
- Update the Status line in every commit, so it is always accurate if a session ends unexpectedly.
- **First task in a new session:** read the code on the current branch, compare it with the current phase, and report gaps or differences. Don't fix them silently.
- The GameTest world (`run-gametest/world`) persists between runs: tests must undo lasting changes (edits, tag rules); compare stock, prices and money counts relatively.

## Status

Phases 0–7 done (v0.2.0 on `main`). Phases 8 (recipe + table block), 9 (item classes) and 10 (built-in price packs) done on branch `phase8`; Phase 10 approved by the user on 2026-10-28 (full pack loads with no warnings). Phase 10 result: the built-in pack has 55 files (vanilla + 54 mods): hand-set seeds plus 7,519 packaged suggested prices (one commit per mod); 104 GameTests pass; in the user's pack, `suggest`/`diagnose` show only flint and the check-by-hand group in `craft_profits.txt`, and removing a mod caused no issues. Remaining profitable chains all run through pack generators the user will change with KubeJS (list in `pricing-work/safety_net.txt`). Phase 11 (GUI rework) done on branch `phase11`, approved 2026-10-29 (some manual checks left to the user). Phase 12 (ProgressiveStages) done on branch `phase12`, approved 2026-10-29 (two-player and in-pack checks left to the user). Phase 13 (Leaderboard) done on branch `phase13`, approved 2026-10-29 (in-game look not checked yet). **Phase 14 (Standalone currency) is next, on branch `phase14`** (from `phase13`; `main` is still v0.2.0): open questions answered 2026-10-29; all six steps done 2026-10-29, plus click-a-coin withdrawal in the Bank tab (user request; protocol 11, 131 GameTests); waiting for the user's approval (in-game checks of the screens left to the user). It was put ahead of Contracts on 2026-10-29 so that Contracts and the Exchange are built on the final bank. Then 15 Contracts → 16 Difficulty preset → 17 Player exchange (optional).

Phases 8+ target v0.3.0. Repo: https://github.com/The-Azotus/dynamic-economy (MIT). `main` holds the latest release; start new work on a fresh branch from it. Poker and a random-walk stock market are out of scope.

## Decisions already made (don't revisit)

- Separate mod. Don't fork or modify Numismatics or Create: Stock Market; don't copy Stock Market code (AGPL-3.0) or Numismatics' textures.
- From Phase 14: named **Dynamic-Economy** (mod id stays `dynamic_economy`). Create and Numismatics are optional. With Numismatics installed the mod uses Numismatics' bank; otherwise its own coins and bank.
- **All market payments go through the player's bank account**, both directions. The market never pays out or takes physical coins; coins only move in and out of the bank through deposit and withdraw.
- Own coins (value in bits): Bit 1, Chip 8, Stack 64, Mark 512, Crown 4,096, Sun 32,768, Divine 262,144, Omega 2,097,152. **1 bit = 1 Numismatics spur**, so prices and price files mean the same under either bank.
- One server-wide market. Same prices for every player; team size never affects them.
- Market pays `base × s × (1 − sell_fee)`; charges `ceil(base × s × (1 + markup))`. `s` is the per-item supply-and-demand factor.
- Only items with default components are accepted (no enchanted, damaged or renamed copies).
- Unlimited-stock items: purchases never raise the price, and the buy price never falls below `base × (1 + min_buy_markup)`, which is always above base.
- No per-player quotas. Dumping is limited by price impact and item classes.
- The market holds no balance: sales create money, purchases destroy it, `sell_fee` reduces what is created.

## Transaction rules (non-negotiable)

- Run every transaction on the server main thread (`IPayloadContext.enqueueWork` for packets). Never touch the Numismatics bank from another thread; its storage is an unsynchronized `HashMap`.
- **Every check happens before anything changes.** After the first change, no remaining step may fail.
- Sell order: validate listing → recompute price → compare with client's quote → count matching items in the **main inventory only** → remove exactly `qty` → update stock (unless unlimited) and `setDirty()` → `deposit(total)`.
- Buy order: validate listing → recompute price → compare with client's quote → check stock (skip if unlimited) → simulate inserting into main inventory, reject if it won't fit → `deduct(total, false)`, stop if false → update stock (unless unlimited) and `setDirty()` → insert items.
- **Never drop items on the ground.** Don't use Numismatics' `ItemUtil.givePlayerItem` for market output.
- Totals in `long`. Reject with `TOO_LARGE` above `Integer.MAX_VALUE` (bank methods take `int`). `qty` capped per request (config, default 640).
- Buying N then selling N must never profit (markup + fee + impact). Any new feature that pays out (contracts, presets) must keep this true.
- `compat/NumismaticsBridge` is the **only** class allowed to import `dev.ithundxr.createnumismatics.*`; `compat/CreateRecipes` the only one allowed to import Create; `compat/FtbTeamsBridge` the only one allowed to import FTB Teams. Each touches its mod only from a nested `Present` class.
- From Phase 14, everything that moves money goes through the bank interface, never a bank directly. The own bank follows the same rules (server main thread, all-or-nothing `deduct`).
- Coin items (ours and Numismatics') can never be listed in the market; selling them would create money from nothing.

Result enum: `OK, NOT_LISTED, SELL_DISABLED, BUY_DISABLED, NOT_ENOUGH_ITEMS, NO_STOCK, NO_FUNDS, NO_SPACE, PRICE_CHANGED, TOO_LARGE`.

## Catalog format

Files in `data/<namespace>/market/*.json`; classes in `data/<namespace>/market_classes/*.json`. Prices are integer **spurs** (bits from Phase 14; the same unit). A file's `defaults` block applies to every entry in it unless overridden.

```json
{
  "defaults": { "markup": 0.5, "sell_fee": 0.0, "dynamic": true },
  "entries": [
    { "item": "minecraft:cobblestone", "base_price": 1,    "unlimited_stock": true },
    { "item": "minecraft:diamond",     "base_price": 256,  "class": "rare_resource" },
    { "item": "minecraft:nether_star", "base_price": 8192, "can_sell": false }
  ]
}
```

Fields: `item` (exact ID), `class`, `base_price` (> 0; ≤ 0 is skipped), `markup` (0.5), `sell_fee` (0.0), `can_sell`/`can_buy` (true), `start_stock` (0, first appearance only), `unlimited_stock` (false), `dynamic` (true), `min_buy_markup` (= `markup`; must be > 0 and ≤ `markup` when set), `sell_impact`, `buy_impact`, `recovery`, `floor`, `ceiling` (server config). Full reference: `docs/admin-guide.md`.

Precedence: server config < class < file `defaults` < entry < tag rules (creation order) < per-item in-game edits.

## Current phase: 14 — Standalone currency

Make the mod work without Create and Numismatics, with its own coins and bank, while still using Numismatics' bank when it's installed. Prices don't change: 1 bit = 1 spur. Big phase, so it's built in steps; **stop and report after each step**.

1. **Rename and optional Create.** Display name `Dynamic-Economy` everywhere (`gradle.properties` `mod_name`, README, docs, lang); mod id `dynamic_economy` and the repo stay, so worlds and datapacks keep working. Create becomes an optional dependency: `compat/CreateRecipes` loads only when Create is (nested `Present` class, as in `StagesBridge`); without Create, `suggest` skips Create's chance outputs and sequenced assembly.
2. **Bank interface and own bank.** One `Bank` interface (balance, `deposit`, all-or-nothing `deduct`, list player balances); two versions: Numismatics' (through `NumismaticsBridge`) and our own (balances saved with the world, keyed by player UUID). MarketService, MarketSync, Leaderboard and the startup code use only the interface. Numismatics becomes optional. The world records which bank it uses and never switches on its own.
3. **Coins.** Eight items, Bit to Omega (values above), original textures. Recipes: 8 of one ⇄ 1 of the next. Hidden and uncraftable while Numismatics' bank is in use.
4. **Bank tab and remote access.** A **Bank** tab in the market screen: deposit every coin from the main inventory, or withdraw an amount as the fewest coins (only if it all fits in the main inventory; never drop items). With Numismatics' bank it moves Numismatics coins. A carried item opens the Bank tab from anywhere.
5. **Team accounts** (own bank only; Numismatics has none), as the answers to question 1 decide.
6. **Switching and finishing.** Admin command to move a world from one bank to the other (moves balances, warns about loose coins); Market Terminal recipe with a mod-loaded condition (spurs with Numismatics, our coins without); docs for both setups.

Done when: the mod loads and the market works with no Create or Numismatics installed, and with each alone; GameTests pass on both banks (a Gradle run without Numismatics and Create is needed, since the dev runtime has both); deposit then withdraw returns exactly the same value; withdraw never drops items; coins can't be listed in the market; buy-then-sell still never profits.

Started 2026-10-29 on branch `phase14` (from `phase13`). Gaps found in the code at the start: nothing stops a coin from being listed (no guard exists yet; add it by step 3); the terminal recipe hard-codes `numismatics:spur`; GameTests call `NumismaticsBridge` directly; the dev runtime always has Create and Numismatics (`implementation`).

Decided with the user on 2026-10-29:

1. **Team accounts:** a team is an FTB Teams party when FTB Teams is installed, otherwise our own `/bank team` commands. Every member can deposit and withdraw. A "pay from: Me / Team" choice in the Bank tab decides which account market purchases charge and sales pay into.
2. **Switching banks:** as recommended. Coins are always registered, but hidden and uncraftable while Numismatics' bank is in use; the world remembers its bank; only an admin command switches. The switch moves every player's balance across (both directions) and warns about loose coins. Recipe conditions run before the world's saved data loads, so they can't see the bank: coin recipes check it when crafted (to verify in step 2).
3. **Names:** keep Crown and Sun; the docs note that Numismatics' coins of the same name are worth less.
4. **Unit label:** follows the bank: "spurs" with Numismatics' bank, "bits" with our own.
5. **Remote access:** a **Bank Card** (shapeless: paper + gold ingot + a Chip); it opens only the Bank tab. Its recipe with Numismatics' bank is decided in step 4.
6. **Balances:** our bank stores `long`; each transaction stays capped at `Integer.MAX_VALUE`.
7. **Coin art:** placeholder textures made by Claude; the user may replace them later.
8. **Terminal recipe without Numismatics:** 8 Bits around a crafting table.

Step 1 (rename, optional Create): done 2026-10-29, 109 GameTests pass.

Step 2 (bank interface, own bank): done 2026-10-29. `bank/Bank` (interface), `bank/OwnBank` (long balances by UUID, saved with the world), `bank/WorldBank` (records the world's bank on first start: a world whose market predates the record, or a new world with Numismatics, takes Numismatics'; otherwise ours). Unit label follows the bank ("spurs"/"bits"; sent in the snapshot with the bank's display cap). `gradlew runGameTestServer -PwithoutMods=numismatics` (Create only) or `-PwithoutMods=create` (neither) runs the tests in their own world folder. 113 GameTests pass in all three setups. Recipes load before the world's saved data (checked in the log), so coin recipes must check the bank when crafted. Until step 3 the terminal recipe loaded only with Numismatics.

Step 3 (coins): done 2026-10-29. `coin/Coin` (values), `coin/Coins` (items, `coin_exchange` recipe serializer, `isCoin`, `inUse`), `coin/CoinRecipe` (shapeless; matches only while the world uses our bank, checked at craft time). 14 exchange recipes (8 ⇄ 1). Placeholder textures drawn by `tools/coin_textures.py`. Coins appear in the Ingredients creative tab only with our bank (client knows the bank from the snapshot). `MarketCatalog.decode` refuses every coin, ours and Numismatics' six (by ID), so files, CSV import, item edits and tag rules can't list one. Terminal: spurs with Numismatics, 8 Bits without (`market_terminal_from_bits.json`, done early from step 6). 117 GameTests pass in all three setups. Not done: JEI/EMI still show coins and their recipes under Numismatics' bank (no JEI dependency yet).

Step 4 (Bank tab, Bank Card): done 2026-10-29. `service/BankService` (deposit every plain coin of the bank's set from the main inventory, paid in `int` parts; withdraw an amount as the fewest coins, only if all fit, else NO_SPACE; same check-then-change order as trades). `Bank.coins()` gives each bank's coins (Numismatics' read from its `Coin` enum by `NumismaticsBridge`); `bank/CoinMath` is shared by server and client. Bank tab in `MarketScreen` (coin list, deposit button, withdraw box with a preview of the coins or why not). Bank Card (`coin/BankCardItem`) opens a bank-only `MarketMenu` (only the Bank tab; valid while carried; trade packets ignored). Card recipe: paper + gold ingot + Chip, or + Numismatics Bevel (8 spurs) when Numismatics is installed. Protocol version 9 (also covers step 2's payload changes, which hadn't bumped it). 123 GameTests pass in all three setups; the screen itself is unchecked in game.

Step 5 (team accounts): done 2026-10-29. FTB Teams 2101.1.11 (the user's pack) is an optional dependency, on the dev runtime unless `-PwithoutMods=ftbteams`; `compat/FtbTeamsBridge` is the only class importing it (party teams only; probed by reflection). Without it, `/bank team create|invite|join|leave|kick|info` (`command/BankCommand`, `bank/OwnTeams`; owner invites and kicks, ownership passes on when the owner leaves). `bank/BankTeams` picks the source. `OwnBank` keeps team balances apart from players' (teams stay off the leaderboard) and each player's "pay from team" choice; `Bank.activeAccount` is what trades, deposits and withdrawals use. A team's balance is never lost: the last member leaving gets it; a deleted FTB party's goes to its owner. Bank tab: "Using: my account / team X" button for team members. Protocol 10. 127 GameTests pass in four setups (all mods; no Numismatics; no Create/Numismatics; none of the three). Not covered by GameTests: trading with an FTB party's account and the payout when a party is deleted (needs real players).

Step 6 (switching, finishing): done 2026-10-29. `/market admin bank` (info) and `/market admin bank switch <own|numismatics> [confirm]` (`command/AdminBankCommands`, `bank/BankSwitch`): preview first; every account looked up before money moves; balances written to the audit log; Numismatics' hidden overflow drained; teams paid to owners before moving to Numismatics' bank (a gone team's balance stays in ours); warns about loose coins. The terminal recipe was done in step 3. JEI: the user chose to leave coins visible there (2026-10-29); the docs say so. Docs: README, player guide (Bank tab, coins, teams, recipes), admin guide (new "Banks and coins" section, commands, troubleshooting). 130 GameTests pass in all setups.

## Report format (after every phase)

- What was built, and anything deferred or uncertain
- Test results (passed, failed, manual)
- Any difference between existing code and this brief
- Anything in this brief that turned out to be wrong or unclear
