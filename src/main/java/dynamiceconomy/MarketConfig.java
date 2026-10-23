package dynamiceconomy;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config (per world). The pricing values are server-wide defaults; catalog entries and file defaults can
 * override all of them except the recovery interval.
 */
public final class MarketConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MAX_QUANTITY_PER_REQUEST;
    public static final ModConfigSpec.EnumValue<AccessMode> ACCESS_MODE;
    public static final ModConfigSpec.DoubleValue SELL_IMPACT;
    public static final ModConfigSpec.DoubleValue BUY_IMPACT;
    public static final ModConfigSpec.DoubleValue RECOVERY;
    public static final ModConfigSpec.IntValue INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue FLOOR;
    public static final ModConfigSpec.DoubleValue CEILING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        MAX_QUANTITY_PER_REQUEST = builder
                .comment("Largest quantity a single buy or sell request may move. Larger orders are rejected with TOO_LARGE.")
                .defineInRange("max_quantity_per_request", 640, 1, 1_000_000);
        ACCESS_MODE = builder
                .comment("How players reach the market.",
                        "COMMAND_AND_BLOCK: the /market command or a Market Terminal block.",
                        "BLOCK_ONLY: only a Market Terminal. Players without operator permission (level 2) can't use",
                        "any /market command; operators and the console always can.")
                .defineEnum("access_mode", AccessMode.COMMAND_AND_BLOCK);

        builder.comment("Supply and demand. Each item has a price factor s, starting at 1.0. These are defaults;",
                "catalog entries may override them (except interval_ticks).").push("pricing");
        SELL_IMPACT = builder
                .comment("Each unit sold multiplies s by (1 - sell_impact).")
                .defineInRange("sell_impact", 0.0005, 0.0, 0.5);
        BUY_IMPACT = builder
                .comment("Each unit bought multiplies s by (1 + buy_impact). Keep it no higher than the smallest markup,",
                        "and no higher than sell_impact / (1 - sell_impact), or buying and reselling can make a profit.")
                .defineInRange("buy_impact", 0.0005, 0.0, 0.5);
        RECOVERY = builder
                .comment("Fraction of the distance back to 1.0 that s recovers at each interval step.")
                .defineInRange("recovery", 0.05, 0.0, 1.0);
        INTERVAL_TICKS = builder
                .comment("Game ticks per recovery step (20 ticks = 1 second). Steps happen server-wide at multiples",
                        "of this value, so prices only change when someone trades or at a step.")
                .defineInRange("interval_ticks", 1200, 1, 1_728_000);
        FLOOR = builder
                .comment("Lowest s can fall to from selling.")
                .defineInRange("floor", 0.2, 0.001, 1.0);
        CEILING = builder
                .comment("Highest s can rise to from buying.")
                .defineInRange("ceiling", 5.0, 1.0, 1000.0);
        builder.pop();
        SPEC = builder.build();
    }

    private MarketConfig() {
    }

    public enum AccessMode { COMMAND_AND_BLOCK, BLOCK_ONLY }

    public static AccessMode accessMode() {
        return SPEC.isLoaded() ? ACCESS_MODE.get() : ACCESS_MODE.getDefault();
    }

    // The config is per-world, so each getter falls back to the default before it has loaded.

    public static int maxQuantityPerRequest() {
        return SPEC.isLoaded() ? MAX_QUANTITY_PER_REQUEST.get() : MAX_QUANTITY_PER_REQUEST.getDefault();
    }

    public static double sellImpact() {
        return SPEC.isLoaded() ? SELL_IMPACT.get() : SELL_IMPACT.getDefault();
    }

    public static double buyImpact() {
        return SPEC.isLoaded() ? BUY_IMPACT.get() : BUY_IMPACT.getDefault();
    }

    public static double recovery() {
        return SPEC.isLoaded() ? RECOVERY.get() : RECOVERY.getDefault();
    }

    public static int intervalTicks() {
        return SPEC.isLoaded() ? INTERVAL_TICKS.get() : INTERVAL_TICKS.getDefault();
    }

    public static double floor() {
        return SPEC.isLoaded() ? FLOOR.get() : FLOOR.getDefault();
    }

    public static double ceiling() {
        return SPEC.isLoaded() ? CEILING.get() : CEILING.getDefault();
    }
}
