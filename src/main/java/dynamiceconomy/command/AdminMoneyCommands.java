package dynamiceconomy.command;

import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.BankSwitch;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketSync;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /market admin money}: a player's balance, and giving or taking money. Giving creates money and taking
 * destroys it, outside the market and its prices, so these are an admin power and every change is audited. They act
 * on the player's own account (never a team's), online or not. If the bank can't be reached they fail; nothing is
 * owed.
 */
final class AdminMoneyCommands {
    private AdminMoneyCommands() {
    }

    static void addTo(LiteralArgumentBuilder<CommandSourceStack> admin) {
        admin.then(Commands.literal("money")
                .then(Commands.literal("balance")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .executes(AdminMoneyCommands::balance)))
                .then(Commands.literal("give")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> move(ctx, true)))))
                .then(Commands.literal("take")
                        .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> move(ctx, false))))));
    }

    /** Returns the balance, capped at {@code Integer.MAX_VALUE}, so {@code execute store} can use it. */
    private static int balance(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Optional<GameProfile> profile = onePlayer(ctx);
        if (profile.isEmpty()) {
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();
        Optional<Bank.Account> account = account(ctx, profile.get());
        if (account.isEmpty()) {
            return 0;
        }
        long balance = account.get().balance();
        ctx.getSource().sendSuccess(() -> Component.literal("%s has %s".formatted(profile.get().getName(),
                money(server, balance))), false);
        return (int) Math.min(balance, Integer.MAX_VALUE);
    }

    /** Gives ({@code give}) or takes money; returns the amount moved. Taking is all or nothing. */
    private static int move(CommandContext<CommandSourceStack> ctx, boolean give) throws CommandSyntaxException {
        Optional<GameProfile> profile = onePlayer(ctx);
        if (profile.isEmpty()) {
            return 0;
        }
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        MinecraftServer server = ctx.getSource().getServer();
        String name = profile.get().getName();
        Optional<Bank.Account> account = account(ctx, profile.get());
        if (account.isEmpty()) {
            return 0;
        }
        long before = account.get().balance();
        if (give) {
            account.get().deposit(amount);
        } else if (!account.get().deduct(amount)) {
            // deduct is the funds check: it changed nothing.
            ctx.getSource().sendFailure(Component.literal("%s has only %s".formatted(name, money(server, before))));
            return 0;
        }
        long after = account.get().balance();
        AuditLog.record(ctx.getSource(), give ? "money give" : "money take", name + " (" + profile.get().getId() + ")", "ok",
                new JsonPrimitive(before), new JsonPrimitive(after));

        // An open market screen shows the new balance straight away.
        ServerPlayer online = server.getPlayerList().getPlayer(profile.get().getId());
        if (online != null && online.containerMenu instanceof MarketMenu) {
            MarketSync.sendPlayerState(online);
        }
        String moved = amount(amount) + " " + WorldBank.kind(server).unit();
        String done = give ? "Gave %s %s".formatted(name, moved) : "Took %s from %s".formatted(moved, name);
        ctx.getSource().sendSuccess(() -> Component.literal(done + "; they now have " + money(server, after)), true);
        return amount;
    }

    /** The one player named, or empty after saying a selector named several. */
    private static Optional<GameProfile> onePlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.size() != 1) {
            ctx.getSource().sendFailure(Component.literal("Name one player at a time"));
            return Optional.empty();
        }
        return Optional.of(profiles.iterator().next());
    }

    /** The player's own account, or empty after saying the bank can't be reached. */
    private static Optional<Bank.Account> account(CommandContext<CommandSourceStack> ctx, GameProfile profile) {
        MinecraftServer server = ctx.getSource().getServer();
        Optional<Bank.Account> account = WorldBank.of(server).accountOf(profile.getId());
        if (account.isEmpty()) {
            String bank = BankSwitch.describe(WorldBank.kind(server));
            ctx.getSource().sendFailure(Component.literal(Character.toUpperCase(bank.charAt(0)) + bank.substring(1)
                    + " can't be reached, so nothing was changed"));
        }
        return account;
    }

    /** E.g. "1,024 bits"; "at least" when Numismatics may be hiding more above what it shows. */
    private static String money(MinecraftServer server, long balance) {
        Bank bank = WorldBank.of(server);
        return (balance >= bank.displayCap() ? "at least " : "") + amount(balance) + " " + bank.kind().unit();
    }

    private static String amount(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
