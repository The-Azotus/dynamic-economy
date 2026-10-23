package dynamiceconomy.compat;

import dynamiceconomy.DynamicEconomy;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import dev.ithundxr.createnumismatics.content.backend.GlobalBankManager;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Optional;

/**
 * The only class allowed to import Numismatics. Numismatics has no stable API and has renamed methods between
 * releases, so everything we call is checked once by reflection at first use. If any of it is missing the bridge
 * reports itself unavailable and the market refuses trades instead of failing halfway through one.
 * <p>
 * Server thread only: the Numismatics bank is an unsynchronized HashMap.
 */
public final class NumismaticsBridge {
    private static final boolean AVAILABLE = probe();

    private NumismaticsBridge() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** The player's bank account, created on first use as Numismatics itself does. Empty if the API is missing. */
    public static Optional<Account> account(Player player) {
        if (!AVAILABLE) {
            return Optional.empty();
        }
        try {
            BankAccount account = Numismatics.BANK.getAccount(player);
            return Optional.ofNullable(account).map(Account::new);
        } catch (LinkageError e) {
            DynamicEconomy.LOGGER.error("Numismatics bank lookup failed", e);
            return Optional.empty();
        }
    }

    private static boolean probe() {
        try {
            Field bank = Numismatics.class.getField("BANK");
            if (!Modifier.isStatic(bank.getModifiers()) || bank.getType() != GlobalBankManager.class) {
                throw new NoSuchFieldException("Numismatics.BANK is not a static GlobalBankManager");
            }
            requireReturn(GlobalBankManager.class.getMethod("getAccount", Player.class).getReturnType(), BankAccount.class);
            requireReturn(BankAccount.class.getMethod("getBalance").getReturnType(), int.class);
            requireReturn(BankAccount.class.getMethod("deposit", int.class).getReturnType(), void.class);
            requireReturn(BankAccount.class.getMethod("deduct", int.class, boolean.class).getReturnType(), boolean.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            DynamicEconomy.LOGGER.error("Numismatics bank API does not match what this mod was built against", e);
            return false;
        }
    }

    private static void requireReturn(Class<?> actual, Class<?> expected) throws NoSuchMethodException {
        if (actual != expected) {
            throw new NoSuchMethodException("Expected return type " + expected + " but found " + actual);
        }
    }

    /** A resolved bank account. All amounts are spurs. */
    public static final class Account {
        private final BankAccount handle;

        private Account(BankAccount handle) {
            this.handle = handle;
        }

        /** The displayed balance. Numismatics keeps anything above ~1.07 billion spurs in a hidden overflow. */
        public int balance() {
            return handle.getBalance();
        }

        /** Cannot fail for a non-negative amount; Numismatics stores overflow rather than rejecting it. */
        public void deposit(int spurs) {
            if (spurs < 0) {
                throw new IllegalArgumentException("Negative deposit: " + spurs);
            }
            handle.deposit(spurs);
        }

        /** All-or-nothing: returns false and changes nothing if the account cannot cover the amount. */
        public boolean deduct(int spurs) {
            if (spurs < 0) {
                throw new IllegalArgumentException("Negative deduction: " + spurs);
            }
            // force=false is essential: force=true empties the account when it cannot cover the amount.
            return handle.deduct(spurs, false);
        }
    }
}
