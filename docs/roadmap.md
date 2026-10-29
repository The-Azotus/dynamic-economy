# Roadmap (Phases 15–17, not started)

Archived from `CLAUDE.md`. When a phase becomes the current one, move its section back into `CLAUDE.md` under "Current phase".

## Phase 15 — Contracts

- A global board, regenerated every **5 in-game days by day count** (sleeping counts). Generated from the catalog using class settings: large orders for common classes, small ones for rare, single items for `unique`. Number of contracts configurable.
- Flow: a player **claims** a contract, which locks it to them. Claiming takes a **deposit** (fraction of the reward, configurable). They deliver in batches from the **main inventory only**. Delivered items **disappear** (not added to stock).
- Completion pays the reward and returns the deposit. At expiry, an incomplete contract pays for units delivered and the deposit is lost. A player may abandon, with the same outcome. Max active contracts per player configurable (default 1).
- Integrity: per-unit reward stays below the lowest buy price the market can reach for that item, so buying from the market to fill a contract never profits. Transaction rules apply (checks before changes, `long` totals, `TOO_LARGE`).
- New Result values for contract actions (e.g. `CONTRACT_TAKEN`, `CONTRACT_EXPIRED`); list them in the report.

Done when: two players can't both claim one contract; partial and full delivery pay exactly as specified; expiry and refresh happen on the right day, including after sleeping and a restart; buy-from-market-and-deliver never profits (GameTest).

Moved behind Phase 14 on 2026-10-29. Branch it as `phase15` from `phase14`. First: read the code, then ask the user these before building (none is decided). Deposits and rewards go through the bank interface from Phase 14.

1. **The reward cap conflicts with selling.** The lowest buy price an item can reach is `base × floor × (1 + markup)`, which with defaults (floor 0.2, markup 0.5) is 0.3 × base, below what simply selling pays (base × s). Capping rewards there makes contracts worse than selling. Options to put to the user: cap against the buy price at claim time and stop the item's price falling below that while the contract is open; exclude contract items from market buying while claimed; only offer contracts for `can_buy: false` items (none can be bought, so any reward is safe); or accept a lower-but-still-useful premium (e.g. reward ≤ min over the contract's life). Whatever is chosen, buy-from-market-and-deliver must never profit (GameTest).
2. **Defaults:** number of contracts on the board, deposit fraction, contract size per class (common / rare / `unique`), reward premium, and whether items with `unlimited_stock` or `can_sell: false` can appear.
3. **Class settings:** Phase 9 reserved per-class contract settings. Which fields (e.g. `contract_weight`, `contract_size`, `contract_premium`, or opting a class out)?
4. **Visibility (Phase 12):** a contract for an item locked for a player is hidden from them; confirm they also can't claim it, and that a claimed contract stays deliverable if their stage is revoked.
5. **Board timing:** "every 5 in-game days by day count" — confirm contracts expire at the same refresh (one shared lifetime), and what a player sees for a claimed contract that expired while offline (payout for delivered units goes to their bank either way).

## Phase 16 — Difficulty preset

- Server config `difficulty = EASY | NORMAL | HARD | CUSTOM`. Presets apply **multipliers on top of** catalog and class values: payout, added `sell_fee`, impact, recovery, contract reward. `CUSTOM` exposes each multiplier. `NORMAL` changes nothing.
- After multipliers, the anti-arbitrage invariant must still hold; invalid combinations are clamped with a warning.

Done when: each preset changes prices as documented; round-trip arbitrage tests pass under every preset.

## Phase 17 — Player exchange (optional, config toggle)

The feature wanted most after the required ones. Players post buy and sell orders at their own prices.

- Sell orders move items into escrow; buy orders move money into escrow (`deduct`). Orders match automatically, price-time priority, partial fills.
- Proceeds and items go to a **collection box** in the Exchange tab (works for offline players; never drop items).
- Config: `exchange_enabled` (default false), `exchange_tax` (fraction taken from the seller, destroyed: a money sink), max open orders per player, order expiry.
- v1 trades default-component items only (same rule as the market). Items with components (enchanted gear) are a later step.
- All transaction rules apply. Cancelling returns escrow exactly.

Done when: matched trades move exactly the right items and money with tax applied; cancelling and expiry return escrow; nothing is lost or duplicated across a restart mid-order; spam-clicking reconciles.
