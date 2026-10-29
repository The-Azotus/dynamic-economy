package dynamiceconomy.compat;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.BankKind;
import dev.ithundxr.createnumismatics.Numismatics;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import dev.ithundxr.createnumismatics.content.backend.GlobalBankManager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only class allowed to import Numismatics, an optional dependency. Without it there is no Numismatics bank and
 * nothing here touches its classes: they're only referenced from {@link Present}, which loads only when it does.
 * <p>
 * Numismatics has no stable API and has renamed methods between releases, so everything we call is checked once by
 * reflection at first use. If any of it is missing the bank reports itself unavailable and the market refuses trades
 * instead of failing halfway through one.
 * <p>
 * Server thread only: the Numismatics bank is an unsynchronized HashMap.
 */
public final class NumismaticsBridge {
    public static final String MOD_ID = "numismatics";
    /** The largest balance Numismatics shows; anything above it is kept in a hidden overflow that can still be spent. */
    public static final long DISPLAYED_BALANCE_CAP = 1_073_741_823;
    private static final boolean AVAILABLE = ModList.get().isLoaded(MOD_ID) && Present.probe();
    /** Listing accounts (for the leaderboard) is checked separately, so a mismatch there never stops trading. */
    private static final boolean ACCOUNTS_LISTABLE = AVAILABLE && Present.probeAccountList();

    private NumismaticsBridge() {
    }

    /** Numismatics' bank, if Numismatics is installed and its bank API matches. */
    public static Optional<Bank> bank() {
        return AVAILABLE ? Optional.of(NumismaticsBank.INSTANCE) : Optional.empty();
    }

    /** GameTests only: a shared (Blaze Banker) account, which must never count as a player's. */
    public static Optional<Bank.Account> sharedAccountForTest(UUID id) {
        return AVAILABLE ? Optional.of(Present.sharedAccount(id)) : Optional.empty();
    }

    private static final class NumismaticsBank implements Bank {
        static final NumismaticsBank INSTANCE = new NumismaticsBank();

        @Override
        public BankKind kind() {
            return BankKind.NUMISMATICS;
        }

        /** The player's bank account, created on first use as Numismatics itself does. */
        @Override
        public Optional<Account> account(Player player) {
            try {
                return Present.account(player);
            } catch (LinkageError e) {
                DynamicEconomy.LOGGER.error("Numismatics bank lookup failed", e);
                return Optional.empty();
            }
        }

        /** Shared Blaze Banker accounts are left out. Balances are what Numismatics displays. */
        @Override
        public Map<UUID, Long> playerBalances() {
            if (!ACCOUNTS_LISTABLE) {
                return Map.of();
            }
            try {
                return Present.playerBalances();
            } catch (LinkageError | RuntimeException e) {
                DynamicEconomy.LOGGER.error("Numismatics account list could not be read", e);
                return Map.of();
            }
        }

        @Override
        public boolean canListAccounts() {
            return ACCOUNTS_LISTABLE;
        }

        @Override
        public long displayCap() {
            return DISPLAYED_BALANCE_CAP;
        }
    }

    /** Everything that touches Numismatics classes. */
    private static final class Present {
        static boolean probe() {
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

        static boolean probeAccountList() {
            try {
                requireReturn(GlobalBankManager.class.getField("accounts").getType(), Map.class);
                requireReturn(BankAccount.class.getField("id").getType(), UUID.class);
                requireReturn(BankAccount.class.getField("type").getType(), BankAccount.Type.class);
                BankAccount.Type.valueOf("PLAYER");
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                DynamicEconomy.LOGGER.error("Numismatics account list does not match what this mod was built against; "
                        + "the leaderboard will be empty", e);
                return false;
            }
        }

        static Optional<Bank.Account> account(Player player) {
            return Optional.ofNullable(Numismatics.BANK.getAccount(player)).map(Account::new);
        }

        static Map<UUID, Long> playerBalances() {
            Map<UUID, Long> balances = new HashMap<>();
            for (BankAccount account : Numismatics.BANK.accounts.values()) {
                if (account.type == BankAccount.Type.PLAYER) {
                    balances.put(account.id, (long) account.getBalance());
                }
            }
            return balances;
        }

        static Bank.Account sharedAccount(UUID id) {
            return new Account(Numismatics.BANK.getOrCreateAccount(id, BankAccount.Type.BLAZE_BANKER));
        }

        private static void requireReturn(Class<?> actual, Class<?> expected) throws NoSuchMethodException {
            if (actual != expected) {
                throw new NoSuchMethodException("Expected return type " + expected + " but found " + actual);
            }
        }

        /** A resolved Numismatics account. All amounts are spurs. */
        static final class Account implements Bank.Account {
            private final BankAccount handle;

            private Account(BankAccount handle) {
                this.handle = handle;
            }

            /** The displayed balance. Numismatics keeps anything above {@link NumismaticsBridge#DISPLAYED_BALANCE_CAP} in a hidden overflow. */
            @Override
            public long balance() {
                return handle.getBalance();
            }

            /** Cannot fail for a non-negative amount; Numismatics stores overflow rather than rejecting it. */
            @Override
            public void deposit(int spurs) {
                if (spurs < 0) {
                    throw new IllegalArgumentException("Negative deposit: " + spurs);
                }
                handle.deposit(spurs);
            }

            @Override
            public boolean deduct(int spurs) {
                if (spurs < 0) {
                    throw new IllegalArgumentException("Negative deduction: " + spurs);
                }
                // force=false is essential: force=true empties the account when it cannot cover the amount.
                return handle.deduct(spurs, false);
            }
        }
    }
}
