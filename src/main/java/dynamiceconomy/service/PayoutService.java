package dynamiceconomy.service;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.BankTeams;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.data.OwedPayouts;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Pays contract and exchange money to a player who may be offline: into the account it belongs to, or owed until the
 * bank can be reached again. Server thread only.
 */
public final class PayoutService {
    private PayoutService() {
    }

    /** The team's account while the team exists and the bank has team accounts, otherwise the player's own. */
    public static Optional<Bank.Account> payee(MinecraftServer server, UUID player, Optional<UUID> team) {
        Optional<Bank.Account> teamAccount = teamAccount(server, team);
        return teamAccount.isPresent() ? teamAccount : WorldBank.of(server).accountOf(player);
    }

    /**
     * Whether money for {@code team} goes to that team's account rather than the player's own, as {@link #payee}
     * decides: the leaderboard counts only what goes to players' own accounts.
     */
    public static boolean paysTeam(MinecraftServer server, Optional<UUID> team) {
        return teamAccount(server, team).isPresent();
    }

    private static Optional<Bank.Account> teamAccount(MinecraftServer server, Optional<UUID> team) {
        if (team.isEmpty() || BankTeams.byId(server, team.get()).isEmpty()) {
            return Optional.empty();
        }
        return WorldBank.of(server).teamAccountOf(team.get());
    }

    /**
     * Pays {@code amount} into the account {@link #payee} picks, or records it in {@code owed} if the bank can't be
     * reached. {@code what} names the payment in the log. The amount fits an int: the caller has checked.
     */
    public static void pay(MinecraftServer server, OwedPayouts owed, String what, UUID player, Optional<UUID> team, long amount) {
        if (amount <= 0) {
            return;
        }
        Optional<Bank.Account> account = payee(server, player, team);
        if (account.isPresent()) {
            account.get().deposit((int) amount);
        } else {
            DynamicEconomy.LOGGER.warn("{} of {} to {} is owed: the bank can't be reached", what, amount, player);
            owed.owe(new OwedPayouts.Payout(player, team, amount));
        }
    }

    /** How long to wait after a payment fails before trying again: 10 seconds, so a bank that's down isn't asked every tick. */
    public static final int RETRY_TICKS = 200;

    /** Pays whatever of {@code owed} the bank can take now, unless a payment failed less than {@link #RETRY_TICKS} ago. */
    public static void payOwed(MinecraftServer server, OwedPayouts owed) {
        payOwed(server, owed, (player, team) -> payee(server, player, team));
    }

    /** As {@link #payOwed(MinecraftServer, OwedPayouts)}, finding each account with {@code payee} (tests make it fail). */
    public static void payOwed(MinecraftServer server, OwedPayouts owed, BiFunction<UUID, Optional<UUID>, Optional<Bank.Account>> payee) {
        long tick = server.getTickCount();
        if (!owed.retryDue(tick)) {
            return;
        }
        boolean failed = false;
        for (OwedPayouts.Payout payout : owed.list()) {
            Optional<Bank.Account> account = payee.apply(payout.player(), payout.team());
            if (account.isPresent()) {
                account.get().deposit((int) payout.amount());
                owed.paid(payout);
            } else {
                failed = true;
            }
        }
        if (failed) {
            owed.retryAt(tick + RETRY_TICKS);
        }
    }

    /** An amount in the world bank's unit, for chat messages. */
    public static Component money(MinecraftServer server, long amount) {
        return Component.translatable("money.dynamic_economy." + WorldBank.kind(server).unit(), String.format("%,d", amount));
    }
}
