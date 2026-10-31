package dynamiceconomy.bank;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where players' money lives: Numismatics' bank, or this mod's own. Everything that moves money goes through this
 * interface, never a bank directly; {@link WorldBank#of} gives the one the world uses. Amounts are bits (= spurs).
 * <p>
 * Server thread only: neither bank is synchronized.
 */
public interface Bank {
    BankKind kind();

    /** The player's own account, created on first use. Empty if the bank can't be reached. */
    Optional<Account> account(Player player);

    /** The own account of the player {@code player}, online or not, created on first use. Empty if unreachable. */
    Optional<Account> accountOf(UUID player);

    /**
     * The account of the team {@code team}, for paying a player's team while they're offline. Empty if this bank has
     * no team accounts; the caller checks the team still exists.
     */
    default Optional<Account> teamAccountOf(UUID team) {
        return Optional.empty();
    }

    /**
     * The account the player trades and banks with: their team's when they chose it and are in a team, otherwise their
     * own. Only the mod's own bank has team accounts.
     */
    default Optional<Account> activeAccount(ServerPlayer player) {
        return account(player);
    }

    /** The player's team, if this bank has team accounts and they're in one. */
    default Optional<BankTeam> team(ServerPlayer player) {
        return Optional.empty();
    }

    /** Whether {@link #activeAccount} is the team's. */
    default boolean payingFromTeam(ServerPlayer player) {
        return false;
    }

    /** Chooses the team's account ({@code true}) or the player's own. Remembered; ignored without team accounts. */
    default void payFromTeam(ServerPlayer player, boolean team) {
    }

    /** Every personal account's balance, by owner UUID. Empty if the accounts can't be listed. */
    Map<UUID, Long> playerBalances();

    /** Whether {@link #playerBalances()} works. */
    boolean canListAccounts();

    /**
     * The largest balance this bank shows. A balance at this value may hide more, so it means "at least this much".
     * {@link Long#MAX_VALUE} when every balance is shown in full.
     */
    long displayCap();

    /** The coins this bank takes in and pays out, largest first; empty if they can't be found. */
    List<Denomination> coins();

    /** One player's account. Single transactions are {@code int}, as both banks take; balances may be larger. */
    interface Account {
        long balance();

        /** Cannot fail for a non-negative amount. */
        void deposit(int amount);

        /** All-or-nothing: returns false and changes nothing if the account cannot cover the amount. */
        boolean deduct(int amount);
    }
}
