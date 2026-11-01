package dynamiceconomy.bank;

import dynamiceconomy.coin.Coin;
import dynamiceconomy.coin.Coins;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * This mod's own bank: one balance per player UUID and one per team, in bits, saved with the world. Balances are
 * {@code long}, so unlike Numismatics' bank nothing is ever hidden; single transactions are still {@code int}. An
 * account exists once money is first paid into it, and is forgotten again when emptied.
 * <p>
 * A player in a team chooses which account they use: their own or the team's (see {@link #activeAccount}). A team's
 * balance is never lost: when the team ends, {@link #closeTeam} pays it to a player.
 * <p>
 * Server thread only: the balances are unsynchronized maps.
 */
public final class OwnBank extends SavedData implements Bank {
    public static final String ID = "dynamic_economy_bank";
    private static final SavedData.Factory<OwnBank> FACTORY = new SavedData.Factory<>(OwnBank::new, OwnBank::fromTag, null);

    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, Long> teamBalances = new HashMap<>();
    /** Players who chose to use their team's account. */
    private final Set<UUID> payFromTeam = new HashSet<>();

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

    /** The personal account of {@code player}. Always reachable. */
    public Bank.Account account(UUID player) {
        return new Account(balances, player);
    }

    /** The shared account of the team {@code team}. */
    public Bank.Account teamAccount(UUID team) {
        return new Account(teamBalances, team);
    }

    @Override
    public Optional<Bank.Account> accountOf(UUID player) {
        return Optional.of(account(player));
    }

    @Override
    public Optional<Bank.Account> teamAccountOf(UUID team) {
        return Optional.of(teamAccount(team));
    }

    @Override
    public Optional<Bank.Account> activeAccount(ServerPlayer player) {
        Optional<BankTeam> team = payingFromTeam(player) ? team(player) : Optional.empty();
        return Optional.of(team.map(t -> teamAccount(t.id())).orElseGet(() -> account(player.getUUID())));
    }

    @Override
    public Optional<BankTeam> team(ServerPlayer player) {
        return BankTeams.teamOf(player.server, player.getUUID());
    }

    /** Whether the player chose their team's account and is in a team. */
    @Override
    public boolean payingFromTeam(ServerPlayer player) {
        return payFromTeam.contains(player.getUUID()) && team(player).isPresent();
    }

    @Override
    public void payFromTeam(ServerPlayer player, boolean team) {
        if (team ? payFromTeam.add(player.getUUID()) : payFromTeam.remove(player.getUUID())) {
            setDirty();
        }
    }

    /**
     * Ends a team's account: pays its whole balance into {@code heir}'s personal account and forgets it. Returns what
     * was paid. Can't fail, so no money is lost when a team ends.
     */
    public long closeTeam(UUID team, UUID heir) {
        long balance = teamBalances.getOrDefault(team, 0L);
        if (balance > 0) {
            Account to = new Account(balances, heir);
            to.add(balance);
            new Account(teamBalances, team).set(0);
        }
        return balance;
    }

    /** Takes a player's whole personal balance out of this bank and returns it, when moving it to another bank. */
    public long drain(UUID player) {
        long balance = balances.getOrDefault(player, 0L);
        new Account(balances, player).set(0);
        return balance;
    }

    /** Adds to a player's personal balance, when moving money in from another bank. Can't fail. */
    public void credit(UUID player, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative credit: " + amount);
        }
        new Account(balances, player).add(amount);
    }

    /** Personal accounts only: teams aren't players, so they stay off the leaderboard. */
    @Override
    public Map<UUID, Long> playerBalances() {
        return Map.copyOf(balances);
    }

    public Map<UUID, Long> teamBalances() {
        return Map.copyOf(teamBalances);
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

    public static OwnBank fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        OwnBank bank = new OwnBank();
        readBalances(tag.getCompound("accounts"), bank.balances);
        readBalances(tag.getCompound("teams"), bank.teamBalances);
        for (Tag id : tag.getList("pay_from_team", Tag.TAG_INT_ARRAY)) {
            bank.payFromTeam.add(NbtUtils.loadUUID(id));
        }
        return bank;
    }

    private static void readBalances(CompoundTag accounts, Map<UUID, Long> into) {
        for (String key : accounts.getAllKeys()) {
            try {
                long balance = accounts.getLong(key);
                if (balance > 0) {
                    into.put(UUID.fromString(key), balance);
                }
            } catch (IllegalArgumentException e) {
                // Not a UUID; skip it rather than lose the whole bank.
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("accounts", writeBalances(balances));
        tag.put("teams", writeBalances(teamBalances));
        ListTag choices = new ListTag();
        payFromTeam.forEach(id -> choices.add(NbtUtils.createUUID(id)));
        tag.put("pay_from_team", choices);
        return tag;
    }

    private static CompoundTag writeBalances(Map<UUID, Long> balances) {
        CompoundTag accounts = new CompoundTag();
        balances.forEach((owner, balance) -> accounts.putLong(owner.toString(), balance));
        return accounts;
    }

    private final class Account implements Bank.Account {
        private final Map<UUID, Long> map;
        private final UUID owner;

        private Account(Map<UUID, Long> map, UUID owner) {
            this.map = map;
            this.owner = owner;
        }

        @Override
        public long balance() {
            return map.getOrDefault(owner, 0L);
        }

        @Override
        public void deposit(int amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Negative deposit: " + amount);
            }
            add(amount);
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
            set(current - amount);
            return true;
        }

        /** Saturates instead of failing: reaching it takes billions of the largest transactions. */
        void add(long amount) {
            long current = balance();
            set(current > Long.MAX_VALUE - amount ? Long.MAX_VALUE : current + amount);
        }

        void set(long balance) {
            if (balance == 0) {
                map.remove(owner);
            } else {
                map.put(owner, balance);
            }
            setDirty();
        }
    }
}
