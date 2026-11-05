# Known issues and the release bar

Code reviews always find something, and fixing every finding adds new code for the next review to find fault with. This file sets which findings must be fixed before a release, and lists those that were looked at and accepted, so they aren't raised and fixed again.

## The release bar

**Must fix before a release:**

- Money or items created, destroyed, duplicated or lost incorrectly: a profit loop, a double payment, paying without receiving.
- A broken transaction rule from `CLAUDE.md`.
- Saved data lost or corrupted across save, load or restart.
- A server crash or uncaught exception from an ordinary player action or a common admin command.
- A client packet that makes the server do something the player isn't allowed to.
- Wrong results in normal play or administration: a wrong price, a wrong payee, or a feature that doesn't do what the docs say in its main use.
- A client crash in a normal screen, or docs that are wrong about a common action.

**Can wait, and goes in the list below:** style and naming, comments, log wording, performance that doesn't stall a server, misuse by a hypothetical future caller, and edge cases that need a broken config, a hand-edited save or unusual datapack ordering. One exception: if such an edge case creates money or loses a player's items, it must be fixed.

A finding that doesn't meet the bar is added below with one line on why it's acceptable, rather than fixed.

## Accepted

Last full review: 2026-11-04, the whole codebase by area plus every money and item path. Every finding was checked by a second reviewer.

### Market

- **A 1-bit item sold below base pays 0 for one unit.** Sales round down once per order, which is a design decision, and the button shows the 0 total before the sale.
- **The client counts room in stacks that carry ignored components** (such as `relics:data`); the server doesn't. The worst case is a `NO_SPACE` reply, and nothing is lost.
- **A client total can go stale when an item's price recovers to exactly base.** The server replies `PRICE_CHANGED` and resends the row, so the next click works.
- **The Market tab's empty-list message can overstate under Buyable or for unsellable items** (v0.4.2 review). Buyable says "Nothing is in stock" when only sell-only items have stock, and a search matching only sold-out items the market doesn't buy says "No items match". Wording only; such items can't be traded at 0 stock anyway, and the texts are the ones the plan set.

### Catalog and import

- **A refused admin edit seeds stock** (0 with the built-in data) for the items it would have listed, before it is undone.
- **`/market admin tag <t> unset <field>` reports success when there was no such rule.**
- **A `base_price` above the int range typed straight into a JSON file wraps around** (Gson's `intValue`). The result is either refused or a wrong but valid price; buying still costs more than selling. The CSV import refuses such values.
- **Re-importing drops a row for an item an earlier import listed** and that was since taken off with `/market admin remove`. This is documented in the admin guide.
- **Packs that sit below a built-in pack are also below the config folder.** That happens when an admin moves one there, or when a mod adds its optional pack at the bottom. Nothing is logged. An earlier warning for this gave false alarms and was removed. See `MarketCatalog.configOrder`.

### Bank, teams and payouts

- **Own-team balances can't be reached while FTB Teams is installed.** If FTB Teams is added to a world whose own teams hold money, those balances come back once FTB Teams is removed. They are never destroyed.
- **The `…ForTesting` methods are public** (`PayoutService.payOwedForTesting`, `OwedPayouts.retryNowForTesting`, `MarketCatalog.replaceForTesting`). The GameTests live in another package, and Java has no narrower visibility that reaches them.
- **Unreadable saved contracts are logged, never refunded automatically** (user, 2026-11-03).

### Exchange

- **Sell orders saved before v0.4.0 rebuild their takings as price × units filled.** No public release had that format.
- **Very large exchange packets aren't split.** This would take about 1,000 open orders, or a box with hundreds of different 8 KB parcels.

### Add-on API (Phase 18 review, 2026-11-05)

Six reviewers by area, each finding checked by a second; all minor. Factual errors in `docs/api.md`, the admin guide, the README and the API javadoc were corrected, since the docs are the phase's deliverable. These were accepted:

- **Balances read through the API show Numismatics' display cap** (1,073,741,823). Documented in the javadoc and `docs/api.md`; there is no `balanceCap` helper. Only balances above about 1.07 billion are affected, and no money moves wrongly.
- **`money give`/`take` audit lines record the displayed balance**, so at Numismatics' cap the before and after values can match. Audit detail only; the money moves correctly and the chat reply says "at least".
- **Every API `pay` and `charge` writes an audit line**, so a very chatty add-on rotates older admin lines out of the 5 MB log sooner. By design; `docs/api.md` asks add-ons to batch small rewards.
- **`ApiEvents` catches only `RuntimeException` and `LinkageError`.** A listener that sneaks out a checked exception or throws another `Error` reaches the caller. That needs a broken add-on.
- **A visibility rule that throws is removed, but catalogs aren't resent at once.** The items it hid come back on the next catalog send (documented). That needs a broken add-on, and nothing is traded wrongly.
- **`/market admin money` takes any name, as vanilla `/op` does.** A misspelled name gets an unused account of its own. This is documented, and the audit log shows it.
- **The sources jar carries `neoforge.mods.toml`**, as Gradle's default sources jar does. The README says which jar goes in the mods folder.
- **Test weaknesses:**
  - The event tests check ordering after the call returns, not when each event fires.
  - `adminMoneyCommandsNeedPermission` relies on a selector failing.
  - Audit-line counts in the GameTests (here and in `AdminTests`) can fail once a test world's audit log rotates past five files; delete the test world.

## Not checked by the review

These need a live game, or another mod's source:

- FTB Teams firing its "deleted" event when a party's last member leaves.
- How Numismatics handles balances near its int limit at runtime.
- How the client screens look and behave, beyond reading the code. In-game checks are listed in the `CLAUDE.md` Status line.
