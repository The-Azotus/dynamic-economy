package dynamiceconomy.bank;

import net.minecraft.world.entity.player.Player;

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

    /** The player's account, created on first use. Empty if the bank can't be reached. */
    Optional<Account> account(Player player);

    /** Every personal account's balance, by owner UUID. Empty if the accounts can't be listed. */
    Map<UUID, Long> playerBalances();

    /** Whether {@link #playerBalances()} works. */
    boolean canListAccounts();

    /**
     * The largest balance this bank shows. A balance at this value may hide more, so it means "at least this much".
     * {@link Long#MAX_VALUE} when every balance is shown in full.
     */
    long displayCap();

    /** One player's account. Single transactions are {@code int}, as both banks take; balances may be larger. */
    interface Account {
        long balance();

        /** Cannot fail for a non-negative amount. */
        void deposit(int amount);

        /** All-or-nothing: returns false and changes nothing if the account cannot cover the amount. */
        boolean deduct(int amount);
    }
}
