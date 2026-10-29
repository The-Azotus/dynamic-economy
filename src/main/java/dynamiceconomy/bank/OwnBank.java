package dynamiceconomy.bank;

import dynamiceconomy.coin.Coin;
import dynamiceconomy.coin.Coins;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * This mod's own bank: one balance per player UUID, in bits, saved with the world. Balances are {@code long}, so
 * unlike Numismatics' bank nothing is ever hidden; single transactions are still {@code int}. An account exists once
 * money is first paid into it, and is forgotten again when emptied.
 * <p>
 * Server thread only: the balances are an unsynchronized map.
 */
public final class OwnBank extends SavedData implements Bank {
    public static final String ID = "dynamic_economy_bank";
    private static final SavedData.Factory<OwnBank> FACTORY = new SavedData.Factory<>(OwnBank::new, OwnBank::fromTag, null);

    private final Map<UUID, Long> balances = new HashMap<>();

    public static OwnBank get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    @Override
    public BankKind kind() {
        return BankKind.OWN;
    }

    @Override
    public Optional<Bank.Account> account(Player player) {
        return Optional.of(account(player.getUUID()));
    }

    /** The account of {@code owner}, a player or (later) a team. Always reachable. */
    public Bank.Account account(UUID owner) {
        return new Account(owner);
    }

    @Override
    public Map<UUID, Long> playerBalances() {
        return Map.copyOf(balances);
    }

    @Override
    public boolean canListAccounts() {
        return true;
    }

    @Override
    public long displayCap() {
        return Long.MAX_VALUE;
    }

    /** Omega down to Bit. */
    @Override
    public List<Denomination> coins() {
        List<Denomination> coins = new ArrayList<>();
        for (Coin coin : Coin.values()) {
            coins.add(0, new Denomination(Coins.item(coin), coin.value()));
        }
        return coins;
    }

    private long balance(UUID owner) {
        return balances.getOrDefault(owner, 0L);
    }

    private void setBalance(UUID owner, long balance) {
        if (balance == 0) {
            balances.remove(owner);
        } else {
            balances.put(owner, balance);
        }
        setDirty();
    }

    public static OwnBank fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        OwnBank bank = new OwnBank();
        CompoundTag accounts = tag.getCompound("accounts");
        for (String key : accounts.getAllKeys()) {
            try {
                long balance = accounts.getLong(key);
                if (balance > 0) {
                    bank.balances.put(UUID.fromString(key), balance);
                }
            } catch (IllegalArgumentException e) {
                // Not a UUID; skip it rather than lose the whole bank.
            }
        }
        return bank;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag accounts = new CompoundTag();
        balances.forEach((owner, balance) -> accounts.putLong(owner.toString(), balance));
        tag.put("accounts", accounts);
        return tag;
    }

    private final class Account implements Bank.Account {
        private final UUID owner;

        private Account(UUID owner) {
            this.owner = owner;
        }

        @Override
        public long balance() {
            return OwnBank.this.balance(owner);
        }

        @Override
        public void deposit(int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Negative deposit: " + amount);
            }
            // Saturates instead of failing: reaching it takes billions of the largest transactions.
            long current = balance();
            setBalance(owner, current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount);
        }

        @Override
        public boolean deduct(int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Negative deduction: " + amount);
            }
            long current = balance();
            if (current < amount) {
                return false;
            }
            setBalance(owner, current - amount);
            return true;
        }
    }
}
