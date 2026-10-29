package dynamiceconomy.coin;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.bank.BankKind;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.client.ClientMarketState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The mod's coins. They are always registered, so none vanish if Numismatics is added to a world later, but they are
 * only in use (in the creative menu, and craftable) while the world uses the mod's own bank.
 * <p>
 * Coins, ours and Numismatics', can never be listed in the market: selling one would create money from nothing.
 */
public final class Coins {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DynamicEconomy.MOD_ID);
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, DynamicEconomy.MOD_ID);

    private static final Map<Coin, DeferredItem<CoinItem>> ITEMS_BY_COIN = new EnumMap<>(Coin.class);

    static {
        for (Coin coin : Coin.values()) {
            ITEMS_BY_COIN.put(coin, ITEMS.register(coin.id(), () -> new CoinItem(coin, new Item.Properties())));
        }
    }

    public static final DeferredHolder<RecipeSerializer<?>, CoinRecipe.Serializer> COIN_RECIPE =
            SERIALIZERS.register("coin_exchange", CoinRecipe.Serializer::new);

    /**
     * Numismatics' coins, by ID so they're known without Numismatics' classes. Item tags can't be used: the catalog
     * loads before tags are bound.
     */
    private static final Set<ResourceLocation> NUMISMATICS_COINS = Set.of("spur", "bevel", "sprocket", "cog", "crown", "sun")
            .stream().map(path -> ResourceLocation.fromNamespaceAndPath("numismatics", path))
            .collect(Collectors.toUnmodifiableSet());

    private Coins() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        SERIALIZERS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            // Client side: the bank is the one the server last said. Tabs are rebuilt on joining a world.
            if (event.getTabKey() == CreativeModeTabs.INGREDIENTS && ClientMarketState.bank() == BankKind.OWN) {
                ITEMS_BY_COIN.values().forEach(event::accept);
            }
        });
    }

    public static CoinItem item(Coin coin) {
        return ITEMS_BY_COIN.get(coin).get();
    }

    public static Map<Coin, DeferredItem<CoinItem>> items() {
        return Collections.unmodifiableMap(ITEMS_BY_COIN);
    }

    /** Whether {@code item} is money: one of our coins or one of Numismatics'. */
    public static boolean isCoin(Item item) {
        return item instanceof CoinItem || NUMISMATICS_COINS.contains(BuiltInRegistries.ITEM.getKey(item));
    }

    /**
     * Whether our coins are in use in {@code level}'s world: only with the mod's own bank. On the client, the bank the
     * server last said. Without a level, not.
     */
    public static boolean inUse(@Nullable Level level) {
        if (level instanceof ServerLevel server) {
            return WorldBank.kind(server.getServer()) == BankKind.OWN;
        }
        return level != null && ClientMarketState.bank() == BankKind.OWN;
    }
}
