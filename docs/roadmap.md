# Roadmap (Phases 12–16, not started)

Archived from `CLAUDE.md`. When a phase becomes the current one, move its section back into `CLAUDE.md` under "Current phase".

## Phase 12 — ProgressiveStages hooks

- Soft dependency on ProgressiveStages **3.1.1**. Verify its API against the released jar before use (as in Phase 0). `compat/StagesBridge` is the only class allowed to import it; without the mod, nothing changes.
- An item locked for a player is **hidden** from that player: snapshot, deltas, tooltips, contracts and exchange. Catalog sync becomes per-player.
- The server enforces it too: buying or selling a locked item is rejected as `NOT_LISTED` (doesn't reveal locked items).
- Prices stay the same for everyone; only visibility differs. Stage changes re-sync the player's market.

Done when: a locked item is invisible and untradeable for one player while another trades it; unlocking a stage shows it without reopening the game; works with the mod absent.

## Phase 13 — Leaderboard

- Top N **player** bank balances with exact amounts, read via `NumismaticsBridge`. Team and shared accounts are excluded; only accounts belonging to a known player profile count (verify how Numismatics distinguishes them).
- Offline players shown by name from the profile cache.
- Config: `leaderboard_enabled`, `leaderboard_size`, `leaderboard_exclude_operators`.
- Read on the main thread; refreshed when the tab opens, not every tick.

Done when: balances match `/market` bank values; non-player accounts never appear.

## Phase 14 — Contracts

- A global board, regenerated every **5 in-game days by day count** (sleeping counts). Generated from the catalog using class settings: large orders for common classes, small ones for rare, single items for `unique`. Number of contracts configurable.
- Flow: a player **claims** a contract, which locks it to them. Claiming takes a **deposit** (fraction of the reward, configurable). They deliver in batches from the **main inventory only**. Delivered items **disappear** (not added to stock).
- Completion pays the reward and returns the deposit. At expiry, an incomplete contract pays for units delivered and the deposit is lost. A player may abandon, with the same outcome. Max active contracts per player configurable (default 1).
- Integrity: per-unit reward stays below the lowest buy price the market can reach for that item, so buying from the market to fill a contract never profits. Transaction rules apply (checks before changes, `long` totals, `TOO_LARGE`).
- New Result values for contract actions (e.g. `CONTRACT_TAKEN`, `CONTRACT_EXPIRED`); list them in the report.

Done when: two players can't both claim one contract; partial and full delivery pay exactly as specified; expiry and refresh happen on the right day, including after sleeping and a restart; buy-from-market-and-deliver never profits (GameTest).

## Phase 15 — Difficulty preset

- Server config `difficulty = EASY | NORMAL | HARD | CUSTOM`. Presets apply **multipliers on top of** catalog and class values: payout, added `sell_fee`, impact, recovery, contract reward. `CUSTOM` exposes each multiplier. `NORMAL` changes nothing.
- After multipliers, the anti-arbitrage invariant must still hold; invalid combinations are clamped with a warning.

Done when: each preset changes prices as documented; round-trip arbitrage tests pass under every preset.

## Phase 16 — Player exchange (optional, config toggle)

The feature wanted most after the required ones. Players post buy and sell orders at their own prices.

- Sell orders move items into escrow; buy orders move money into escrow (`deduct`). Orders match automatically, price-time priority, partial fills.
- Proceeds and items go to a **collection box** in the Exchange tab (works for offline players; never drop items).
- Config: `exchange_enabled` (default false), `exchange_tax` (fraction taken from the seller, destroyed: a money sink), max open orders per player, order expiry.
- v1 trades default-component items only (same rule as the market). Items with components (enchanted gear) are a later step.
- All transaction rules apply. Cancelling returns escrow exactly.

Done when: matched trades move exactly the right items and money with tax applied; cancelling and expiry return escrow; nothing is lost or duplicated across a restart mid-order; spam-clicking reconciles.
