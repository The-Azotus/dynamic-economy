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

/**
 * Pays contract and exchange money to a player who may be offline: into the account it belongs to, or owed until the
 * bank can be reached again. Server thread only.
 */
public final class PayoutService {
    private PayoutService() {
    }

    /** The team's account while the team exists and the bank has team accounts, otherwise the player's own. */
    public static Optional<Bank.Account> payee(MinecraftServer server, UUID player, Optional<UUID> team) {
        Bank bank = WorldBank.of(server);
        if (team.isPresent() && BankTeams.byId(server, team.get()).isPresent()) {
            Optional<Bank.Account> teamAccount = bank.teamAccountOf(team.get());
            if (teamAccount.isPresent()) {
                return teamAccount;
            }
        }
        return bank.accountOf(player);
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

    /** Pays whatever of {@code owed} the bank can take now. */
    public static void payOwed(MinecraftServer server, OwedPayouts owed) {
        for (OwedPayouts.Payout payout : owed.list()) {
            Optional<Bank.Account> account = payee(server, payout.player(), payout.team());
            if (account.isPresent()) {
                account.get().deposit((int) payout.amount());
                owed.paid(payout);
            }
        }
    }

    /** An amount in the world bank's unit, for chat messages. */
    public static Component money(MinecraftServer server, long amount) {
        return Component.translatable("money.dynamic_economy." + WorldBank.kind(server).unit(), String.format("%,d", amount));
    }
}
