package dynamiceconomy.command;

import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dynamiceconomy.MarketAccess;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.ContractService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * {@code /market contracts [claim|deliver|abandon]}: the Contracts tab as commands, for scripting and testing without
 * the screen. {@code /market admin contracts [refresh]} shows the whole board and its money counts, or replaces it now.
 */
final class ContractCommands {
    private ContractCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> player() {
        return Commands.literal("contracts")
                .requires(MarketAccess::commandsAllowed)
                .executes(ContractCommands::list)
                .then(Commands.literal("claim")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> report(ctx, ContractService.claim(ctx.getSource().getPlayerOrException(), id(ctx))))))
                .then(Commands.literal("deliver")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .executes(ctx -> report(ctx, ContractService.deliver(ctx.getSource().getPlayerOrException(), id(ctx),
                                                IntegerArgumentType.getInteger(ctx, "qty")))))))
                .then(Commands.literal("abandon")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> report(ctx, ContractService.abandon(ctx.getSource().getPlayerOrException(), id(ctx))))));
    }

    static void addTo(LiteralArgumentBuilder<CommandSourceStack> admin) {
        admin.then(Commands.literal("contracts")
                .executes(ContractCommands::adminInfo)
                .then(Commands.literal("refresh").executes(ContractCommands::refresh)));
    }

    private static int id(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "id");
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!MarketConfig.contractsEnabled()) {
            ctx.getSource().sendFailure(Component.literal("Contracts are turned off on this server"));
            return 0;
        }
        ContractBoard board = ContractBoard.get(player.server);
        List<ContractService.View> views = ContractService.view(player, board);
        String unit = MarketCommand.unit(ctx.getSource());
        ctx.getSource().sendSuccess(() -> Component.literal("Contracts (new board in %d day(s)); you hold %d of %d:".formatted(
                board.daysLeft(), board.claimedBy(player.getUUID()), MarketConfig.maxActiveContracts())), false);
        for (ContractService.View view : views) {
            ContractBoard.Contract c = view.contract();
            String state = switch (c.status()) {
                case OPEN -> "open";
                case CLAIMED -> view.mine() ? "yours, %d/%d delivered".formatted(c.delivered(), c.units()) : "claimed by " + view.holderName();
                case COMPLETED -> "done";
                case ABANDONED -> "abandoned";
            };
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d: %d × %s pays %d %s, deposit %d (%s)".formatted(c.id(), c.units(),
                    c.item(), view.reward(), unit, c.deposit(), state)), false);
        }
        if (views.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  none right now"), false);
        }
        return views.size();
    }

    private static int report(CommandContext<CommandSourceStack> ctx, ContractService.Outcome outcome) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!outcome.ok()) {
            ctx.getSource().sendFailure(Component.literal("Contract %s failed: %s".formatted(outcome.action().name().toLowerCase(Locale.ROOT),
                    outcome.result())));
            return 0;
        }
        String unit = MarketCommand.unit(ctx.getSource());
        String message = switch (outcome.action()) {
            case CLAIM -> "Claimed the %s contract; %d %s deposit taken".formatted(outcome.item(), outcome.amount(), unit);
            case DELIVER -> outcome.amount() > 0
                    ? "Delivered %d and finished the %s contract: paid %d %s, deposit included".formatted(outcome.quantity(), outcome.item(),
                    outcome.amount(), unit)
                    : "Delivered %d × %s".formatted(outcome.quantity(), outcome.item());
            case ABANDON -> "Abandoned the %s contract: paid %d %s for %d delivered; the deposit is lost".formatted(outcome.item(),
                    outcome.amount(), unit, outcome.quantity());
        };
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        MarketSync.broadcastContracts(player.server);
        MarketSync.sendPlayerState(player);
        return 1;
    }

    private static int adminInfo(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ContractBoard board = ContractBoard.get(server);
        String unit = MarketCommand.unit(ctx.getSource());
        ctx.getSource().sendSuccess(() -> Component.literal(("Contract board: day %d, %d contract(s), replaced on day %d. Contracts are %s. "
                + "Paid out so far: %d %s; deposits kept: %d %s").formatted(board.day(), board.contracts().size(), board.expiresDay(),
                MarketConfig.contractsEnabled() ? "on" : "off", board.rewardsPaid(), unit, board.depositsLost(), unit)), false);
        for (ContractBoard.Contract c : board.contracts()) {
            String holder = c.holder().map(id -> {
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                return online != null ? online.getGameProfile().getName() : id.toString();
            }).orElse("-");
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d %s: %d/%d × %s, pays %d (offered %d), deposit %d, holder %s".formatted(
                    c.id(), c.status().name().toLowerCase(Locale.ROOT), c.delivered(), c.units(), c.item(),
                    ContractService.payout(server, c, c.units()), c.reward(), c.deposit(), holder)), false);
        }
        if (!board.owed().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  %d payout(s) wait for the bank to be reachable".formatted(board.owed().size())), false);
        }
        return board.contracts().size();
    }

    private static int refresh(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        if (!MarketConfig.contractsEnabled()) {
            ctx.getSource().sendFailure(Component.literal("Contracts are turned off (contracts_enabled in the server config)"));
            return 0;
        }
        ContractBoard board = ContractBoard.get(server);
        int before = board.contracts().size();
        ContractService.refresh(server, board, server.overworld().getRandom());
        MarketSync.broadcastContracts(server);
        AuditLog.record(ctx.getSource(), "contracts refresh", "board", "ok", new JsonPrimitive(before),
                new JsonPrimitive(board.contracts().size()));
        String items = board.contracts().stream().map(c -> BuiltInRegistries.ITEM.get(c.item()).getDescription().getString())
                .reduce((a, b) -> a + ", " + b).orElse("nothing qualifies");
        ctx.getSource().sendSuccess(() -> Component.literal("Replaced the contract board (held contracts were settled as expired). "
                + "New board until day %d: %s".formatted(board.expiresDay(), items)), true);
        return board.contracts().size();
    }
}
