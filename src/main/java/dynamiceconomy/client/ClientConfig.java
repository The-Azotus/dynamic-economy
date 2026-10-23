package dynamiceconomy.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-player settings, in {@code config/dynamic_economy-client.toml}. */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_SELL_PRICE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SHOW_SELL_PRICE = builder
                .comment("Show what the market pays for an item in its tooltip, wherever the item appears.")
                .define("show_sell_price_in_tooltips", true);
        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    public static boolean showSellPrice() {
        return !SPEC.isLoaded() || SHOW_SELL_PRICE.get();
    }
}
