package dynamiceconomy.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.BankKind;
import dynamiceconomy.bank.BankSwitch;
import dynamiceconomy.bank.BankTeams;
import dynamiceconomy.bank.OwnBank;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.network.MarketSync;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /market admin bank}: which bank the world uses, and switching it. A switch moves every balance to the other
 * bank; without {@code confirm} it only says what it would do.
 */
final class AdminBankCommands {
    private AdminBankCommands() {
    }

    static void addTo(LiteralArgumentBuilder<CommandSourceStack> admin) {
        LiteralArgumentBuilder<CommandSourceStack> switchTo = Commands.literal("switch");
        for (BankKind kind : BankKind.values()) {
            switchTo.then(Commands.literal(kind.id())
                    .executes(ctx -> switchBank(ctx, kind, false))
                    .then(Commands.literal("confirm").executes(ctx -> switchBank(ctx, kind, true))));
        }
        admin.then(Commands.literal("bank")
                .executes(AdminBankCommands::info)
                .then(switchTo));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        Bank bank = WorldBank.of(server);
        List<String> lines = new ArrayList<>();
        lines.add("This world uses %s%s.".formatted(BankSwitch.describe(bank.kind()),
                WorldBank.available(server) ? "" : ", which can't be reached: the market refuses every trade"));
        long total = bank.playerBalances().values().stream().mapToLong(Long::longValue).sum();
        lines.add("  %d player account(s) hold %s %s%s".formatted(bank.playerBalances().size(), amount(total), bank.kind().unit(),
                bank.kind() == BankKind.NUMISMATICS ? " (as displayed)" : ""));
        if (bank.kind() == BankKind.OWN) {
            OwnBank own = OwnBank.get(server);
            long teams = own.teamBalances().values().stream().mapToLong(Long::longValue).sum();
            lines.add("  %d team account(s) hold %s bits; teams come from %s".formatted(own.teamBalances().size(), amount(teams),
                    BankTeams.fromFtbTeams() ? "FTB Teams" : "/bank team"));
        }
        lines.add(NumismaticsBridge.bank().isPresent()
                ? "Numismatics is installed. /market admin bank switch %s moves every balance to %s".formatted(
                        other(bank.kind()).id(), BankSwitch.describe(other(bank.kind())))
                : "Numismatics isn't installed, so there's no other bank to switch to");
        lines.forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    private static int switchBank(CommandContext<CommandSourceStack> ctx, BankKind to, boolean confirm) {
        MinecraftServer server = ctx.getSource().getServer();
        BankSwitch.Result planned = BankSwitch.plan(server, to);
        if (planned.plan().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(planned.problem()));
            return 0;
        }
        BankSwitch.Plan plan = planned.plan().get();
        if (!confirm) {
            describe(plan).forEach(line -> ctx.getSource().sendSuccess(() -> Component.literal(line), false));
            ctx.getSource().sendSuccess(() -> Component.literal("/market admin bank switch %s confirm does it. Every balance is "
                    .formatted(to.id()) + "written to the audit log first"), false);
            return 1;
        }

        // The record comes first, so even a switch that stops halfway can be traced.
        JsonObject before = new JsonObject();
        plan.balances().forEach((player, balance) -> before.addProperty(player.toString(), balance));
        plan.teams().forEach((team, balance) -> before.addProperty("team " + team.id() + " (" + team.name() + ", owner "
                + team.owner() + ")", balance));
        AuditLog.record(ctx.getSource(), "bank switch", BankSwitch.describe(plan.from()) + " to " + BankSwitch.describe(to),
                "starting", before, null);
        BankSwitch.Result done = BankSwitch.switchTo(server, to);
        if (done.plan().isEmpty()) {
            AuditLog.record(ctx.getSource(), "bank switch", BankSwitch.describe(to), "refused: " + done.problem(), null, null);
            ctx.getSource().sendFailure(Component.literal(done.problem()));
            return 0;
        }
        AuditLog.record(ctx.getSource(), "bank switch", BankSwitch.describe(to), "ok: moved %s across %d account(s)"
                .formatted(amount(plan.total()), plan.balances().size() + plan.teams().size()), null, null);

        // Every player learns the new unit and coins; open screens get their new balance.
        MarketSync.broadcastSnapshot(server);
        MarketSync.viewers(server).forEach(MarketSync::sendFullState);
        ctx.getSource().sendSuccess(() -> Component.literal("This world now uses %s: moved %s across %d account(s). %s".formatted(
                BankSwitch.describe(to), amount(plan.total()), plan.balances().size() + plan.teams().size(),
                "Players see the new coins in the creative menu after rejoining")), true);
        if (plan.coinCarriers() > 0) {
            ctx.getSource().sendSuccess(() -> Component.literal(coinWarning(plan)), true);
        }
        return 1;
    }

    private static List<String> describe(BankSwitch.Plan plan) {
        List<String> lines = new ArrayList<>();
        lines.add("Switching from %s to %s would:".formatted(BankSwitch.describe(plan.from()), BankSwitch.describe(plan.to())));
        if (!plan.teams().isEmpty()) {
            long teams = plan.teams().values().stream().mapToLong(Long::longValue).sum();
            lines.add("  first pay %d team account(s), %s bits, to each team's owner: Numismatics' bank has no team accounts"
                    .formatted(plan.teams().size(), amount(teams)));
        }
        long players = plan.balances().values().stream().mapToLong(Long::longValue).sum();
        lines.add("  move %d player balance(s), %s%s bits (= spurs), to the same players' accounts in %s".formatted(
                plan.balances().size(), plan.from() == BankKind.NUMISMATICS ? "at least " : "", amount(players),
                BankSwitch.describe(plan.to())));
        if (!plan.lostTeams().isEmpty()) {
            long lost = plan.lostTeams().values().stream().mapToLong(Long::longValue).sum();
            lines.add("  leave %d team account(s), %s bits, whose teams no longer exist, in the mod's bank until a switch back"
                    .formatted(plan.lostTeams().size(), amount(lost)));
        }
        lines.add(coinWarning(plan));
        return lines;
    }

    private static String coinWarning(BankSwitch.Plan plan) {
        String coins = plan.from() == BankKind.OWN ? "the mod's coins (Bit to Omega)" : "Numismatics' coins";
        String after = plan.from() == BankKind.OWN
                ? "they become uncraftable and the Bank tab no longer takes them"
                : "they stay Numismatics items, but the Bank tab no longer takes them";
        return ("Coins don't move: %s are worth nothing to the bank afterwards; %s. %d online player(s) carry %s worth of them "
                + "now. Have players deposit coins first; coins in chests or with offline players aren't counted").formatted(
                coins, after, plan.coinCarriers(), amount(plan.carriedCoins()));
    }

    private static BankKind other(BankKind kind) {
        return kind == BankKind.OWN ? BankKind.NUMISMATICS : BankKind.OWN;
    }

    private static String amount(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
