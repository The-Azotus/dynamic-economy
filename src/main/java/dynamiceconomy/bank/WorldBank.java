package dynamiceconomy.bank;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.data.MarketStockData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which bank the world uses, chosen once and saved with it, so balances never move to another bank on their own when
 * a mod is added or removed. Only an admin switches it ({@link BankSwitch}). A new world takes Numismatics' bank when Numismatics is installed and the mod's own bank
 * otherwise. A world that already had a market before the choice was recorded used Numismatics' bank, since the
 * market needed it then.
 * <p>
 * If the recorded bank can't be reached (Numismatics' bank without Numismatics), the market refuses trades rather
 * than paying into another bank. Server thread only.
 */
public final class WorldBank extends SavedData {
    public static final String ID = "dynamic_economy_bank_choice";

    private BankKind kind;

    private WorldBank(BankKind kind) {
        this.kind = kind;
    }

    private static WorldBank get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(() -> choose(server),
                WorldBank::fromTag, null), ID);
    }

    /** The bank this world uses. Its {@code account} is empty for every player when that bank can't be reached. */
    public static Bank of(MinecraftServer server) {
        return switch (kind(server)) {
            case NUMISMATICS -> NumismaticsBridge.bank().orElse(Unavailable.NUMISMATICS);
            case OWN -> OwnBank.get(server);
        };
    }

    public static BankKind kind(MinecraftServer server) {
        return get(server).kind;
    }

    /** Records a new bank for the world. Only {@link BankSwitch} calls this, after moving every balance across. */
    static void record(MinecraftServer server, BankKind kind) {
        WorldBank data = get(server);
        data.kind = kind;
        data.setDirty();
    }

    /** Whether the world's bank can be reached. */
    public static boolean available(MinecraftServer server) {
        return !(of(server) instanceof Unavailable);
    }

    private static WorldBank choose(MinecraftServer server) {
        boolean hadMarket = Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("data")
                .resolve(MarketStockData.ID + ".dat"));
        BankKind kind = hadMarket || NumismaticsBridge.bank().isPresent() ? BankKind.NUMISMATICS : BankKind.OWN;
        DynamicEconomy.LOGGER.info("This world will use {} bank{}", kind == BankKind.OWN ? "the mod's own" : "Numismatics'",
                hadMarket ? " (its market predates the choice, when only Numismatics' bank existed)" : "");
        WorldBank chosen = new WorldBank(kind);
        chosen.setDirty();
        return chosen;
    }

    private static WorldBank fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        String id = tag.getString("bank");
        return new WorldBank(BankKind.byId(id).orElseGet(() -> {
            // Never guess another bank: that would strand every balance. Numismatics' is the only one older worlds had.
            DynamicEconomy.LOGGER.error("Unknown bank \"{}\" recorded for this world; assuming Numismatics'", id);
            return BankKind.NUMISMATICS;
        }));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString("bank", kind.id());
        return tag;
    }

    /** A bank the world uses but can't reach: no accounts, so every trade is refused. */
    private record Unavailable(BankKind kind, long displayCap) implements Bank {
        static final Unavailable NUMISMATICS = new Unavailable(BankKind.NUMISMATICS, NumismaticsBridge.DISPLAYED_BALANCE_CAP);

        @Override
        public Optional<Account> account(Player player) {
            return Optional.empty();
        }

        @Override
        public Optional<Account> accountOf(UUID player) {
            return Optional.empty();
        }

        @Override
        public Map<UUID, Long> playerBalances() {
            return Map.of();
        }

        @Override
        public boolean canListAccounts() {
            return false;
        }

        @Override
        public List<Denomination> coins() {
            return List.of();
        }
    }
}
