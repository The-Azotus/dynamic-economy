package dynamiceconomy;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MarketConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MAX_QUANTITY_PER_REQUEST;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        MAX_QUANTITY_PER_REQUEST = builder
                .comment("Largest quantity a single buy or sell request may move. Larger orders are rejected with TOO_LARGE.")
                .defineInRange("max_quantity_per_request", 640, 1, 1_000_000);
        SPEC = builder.build();
    }

    private MarketConfig() {
    }

    /** The config is per-world, so fall back to the default before it has loaded. */
    public static int maxQuantityPerRequest() {
        return SPEC.isLoaded() ? MAX_QUANTITY_PER_REQUEST.get() : MAX_QUANTITY_PER_REQUEST.getDefault();
    }
}
