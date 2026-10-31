# Roadmap (Phases 16–17, not started)

Archived from `CLAUDE.md`. When a phase becomes the current one, move its section back into `CLAUDE.md` under "Current phase".

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
