package dynamiceconomy;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server config ({@code config/dynamic_economy-server.toml}, loaded when a server starts). The pricing values are
 * server-wide defaults; catalog entries and file defaults can
 * override all of them except the recovery interval.
 */
public final class MarketConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue MAX_QUANTITY_PER_REQUEST;
    public static final ModConfigSpec.EnumValue<AccessMode> ACCESS_MODE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> IGNORED_COMPONENTS;
    public static final ModConfigSpec.DoubleValue SELL_IMPACT;
    public static final ModConfigSpec.DoubleValue BUY_IMPACT;
    public static final ModConfigSpec.DoubleValue RECOVERY;
    public static final ModConfigSpec.IntValue INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue FLOOR;
    public static final ModConfigSpec.DoubleValue CEILING;
    public static final ModConfigSpec.BooleanValue LEADERBOARD_ENABLED;
    public static final ModConfigSpec.IntValue LEADERBOARD_SIZE;
    public static final ModConfigSpec.BooleanValue LEADERBOARD_EXCLUDE_OPERATORS;
    public static final ModConfigSpec.BooleanValue CONTRACTS_ENABLED;
    public static final ModConfigSpec.IntValue BOARD_SIZE;
    public static final ModConfigSpec.IntValue REFRESH_DAYS;
    public static final ModConfigSpec.DoubleValue DEPOSIT_FRACTION;
    public static final ModConfigSpec.IntValue MAX_ACTIVE_CONTRACTS;
    public static final ModConfigSpec.IntValue CONTRACT_WEIGHT;
    public static final ModConfigSpec.IntValue CONTRACT_MIN_SIZE;
    public static final ModConfigSpec.IntValue CONTRACT_MAX_SIZE;
    public static final ModConfigSpec.DoubleValue CONTRACT_PREMIUM;

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
        IGNORED_COMPONENTS = builder
                .comment("Item data the market ignores when deciding whether a copy is plain enough to sell. The market",
                        "only buys items as they come fresh (not enchanted, damaged or renamed), but some mods add data to",
                        "every copy a player holds: Relics (and Reliquified Artifacts) adds relics:data, its levels and",
                        "rolled stats, as soon as a relic is in an inventory. Data listed here doesn't stop a sale; it is",
                        "lost with the item, and bought items always come fresh. List data component IDs.")
                .defineListAllowEmpty("ignored_components", List.of("relics:data"),
                        () -> "relics:data", id -> id instanceof String s && ResourceLocation.tryParse(s) != null);

        builder.comment("Supply and demand. Each item has a price factor s, starting at 1.0. These are defaults;",
                "catalog entries may override them (except interval_ticks).").push("pricing");
        SELL_IMPACT = builder
                .comment("Each unit sold multiplies s by (1 - sell_impact).")
                .defineInRange("sell_impact", 0.0005, 0.0, 0.5);
        BUY_IMPACT = builder
                .comment("Each unit bought multiplies s by (1 + buy_impact). Keep it no higher than the smallest markup,",
                        "and no higher than sell_impact / (1 - sell_impact), or buying and reselling can make a profit.",
                        "Don't set it far below sell_impact either, or selling and buying back can. Equal values are safe;",
                        "the server log warns at startup about any item whose settings allow a profitable round trip.")
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

        builder.comment("The Leaderboard tab: the richest players by Numismatics bank balance.").push("leaderboard");
        LEADERBOARD_ENABLED = builder
                .comment("Show the Leaderboard tab. When false the tab is greyed out and no balances are sent.")
                .define("leaderboard_enabled", true);
        LEADERBOARD_SIZE = builder
                .comment("How many players the leaderboard lists.")
                .defineInRange("leaderboard_size", 10, 1, 100);
        LEADERBOARD_EXCLUDE_OPERATORS = builder
                .comment("Leave operators off the leaderboard, for servers where admins hold test money.")
                .define("leaderboard_exclude_operators", false);
        builder.pop();

        builder.comment("The Contracts tab: a board of delivery orders, shared by every player and replaced every few",
                "in-game days. A player claims a contract, paying a deposit, and delivers the items; delivered items",
                "leave the game. Finishing pays the reward and returns the deposit; at expiry or on abandoning, only the",
                "units delivered are paid and the deposit is lost. A contract never pays more per unit than the lowest",
                "price the market could ever sell that item for, so buying from the market to deliver never profits.",
                "The contract_* values are defaults; item classes and catalog entries may override them.").push("contracts");
        CONTRACTS_ENABLED = builder
                .comment("Offer contracts. When false the tab is greyed out, and turning it off settles every claimed contract:",
                        "delivered units are paid and deposits returned.")
                .define("contracts_enabled", true);
        BOARD_SIZE = builder
                .comment("How many contracts each board offers (fewer if not enough items qualify).")
                .defineInRange("board_size", 8, 1, 64);
        REFRESH_DAYS = builder
                .comment("In-game days between boards. Sleeping counts; setting the time backwards doesn't. Every contract",
                        "on a board expires when the next one replaces it.")
                .defineInRange("refresh_days", 5, 1, 365);
        DEPOSIT_FRACTION = builder
                .comment("The deposit taken when claiming, as a fraction of the reward.")
                .defineInRange("deposit_fraction", 0.2, 0.0, 1.0);
        MAX_ACTIVE_CONTRACTS = builder
                .comment("How many contracts one player may hold at once.")
                .defineInRange("max_active_contracts", 1, 1, 64);
        CONTRACT_WEIGHT = builder
                .comment("How likely an item is to be picked for a board, relative to others. 0 keeps it off the board.")
                .defineInRange("contract_weight", 1, 0, 1000);
        CONTRACT_MIN_SIZE = builder
                .comment("Fewest units one contract asks for.")
                .defineInRange("contract_min_size", 16, 1, 100_000);
        CONTRACT_MAX_SIZE = builder
                .comment("Most units one contract asks for. If below contract_min_size, contract_min_size is used.")
                .defineInRange("contract_max_size", 64, 1, 100_000);
        CONTRACT_PREMIUM = builder
                .comment("The reward per unit is base price x (1 + contract_premium), but never as much as the lowest price",
                        "the market could sell the item for. Items where that leaves too little to beat selling aren't offered.")
                .defineInRange("contract_premium", 0.25, 0.0, 100.0);
        builder.pop();
        SPEC = builder.build();
    }

    private MarketConfig() {
    }

    public enum AccessMode { COMMAND_AND_BLOCK, BLOCK_ONLY }

    public static AccessMode accessMode() {
        return SPEC.isLoaded() ? ACCESS_MODE.get() : ACCESS_MODE.getDefault();
    }

    // A server config only loads when a server starts, so each getter falls back to the default before then.

    /** {@link #IGNORED_COMPONENTS} as IDs. Clients get the server's value: NeoForge syncs server configs. */
    public static Set<ResourceLocation> ignoredComponents() {
        Set<ResourceLocation> ids = new HashSet<>();
        for (String id : SPEC.isLoaded() ? IGNORED_COMPONENTS.get() : IGNORED_COMPONENTS.getDefault()) {
            ResourceLocation parsed = ResourceLocation.tryParse(id);
            if (parsed != null) {
                ids.add(parsed);
            }
        }
        return ids;
    }

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

    public static boolean leaderboardEnabled() {
        return SPEC.isLoaded() ? LEADERBOARD_ENABLED.get() : LEADERBOARD_ENABLED.getDefault();
    }

    public static int leaderboardSize() {
        return SPEC.isLoaded() ? LEADERBOARD_SIZE.get() : LEADERBOARD_SIZE.getDefault();
    }

    public static boolean leaderboardExcludeOperators() {
        return SPEC.isLoaded() ? LEADERBOARD_EXCLUDE_OPERATORS.get() : LEADERBOARD_EXCLUDE_OPERATORS.getDefault();
    }

    public static boolean contractsEnabled() {
        return SPEC.isLoaded() ? CONTRACTS_ENABLED.get() : CONTRACTS_ENABLED.getDefault();
    }

    public static int boardSize() {
        return SPEC.isLoaded() ? BOARD_SIZE.get() : BOARD_SIZE.getDefault();
    }

    public static int refreshDays() {
        return SPEC.isLoaded() ? REFRESH_DAYS.get() : REFRESH_DAYS.getDefault();
    }

    public static double depositFraction() {
        return SPEC.isLoaded() ? DEPOSIT_FRACTION.get() : DEPOSIT_FRACTION.getDefault();
    }

    public static int maxActiveContracts() {
        return SPEC.isLoaded() ? MAX_ACTIVE_CONTRACTS.get() : MAX_ACTIVE_CONTRACTS.getDefault();
    }

    public static int contractWeight() {
        return SPEC.isLoaded() ? CONTRACT_WEIGHT.get() : CONTRACT_WEIGHT.getDefault();
    }

    public static int contractMinSize() {
        return SPEC.isLoaded() ? CONTRACT_MIN_SIZE.get() : CONTRACT_MIN_SIZE.getDefault();
    }

    public static int contractMaxSize() {
        return SPEC.isLoaded() ? CONTRACT_MAX_SIZE.get() : CONTRACT_MAX_SIZE.getDefault();
    }

    public static double contractPremium() {
        return SPEC.isLoaded() ? CONTRACT_PREMIUM.get() : CONTRACT_PREMIUM.getDefault();
    }
}
