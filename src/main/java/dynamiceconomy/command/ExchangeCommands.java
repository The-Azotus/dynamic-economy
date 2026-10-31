package dynamiceconomy.command;

import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dynamiceconomy.MarketAccess;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.data.ExchangeData;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.ExchangeService;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /market exchange [book|buy|sell|cancel|collect]}: the Exchange tab as commands, for scripting and testing
 * without the screen. {@code /market admin exchange [cancel <id>]} shows the whole exchange and its money counts, or
 * closes any order, returning what it holds.
 */
final class ExchangeCommands {
    private ExchangeCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> player(CommandBuildContext context) {
        return Commands.literal("exchange")
                .requires(MarketAccess::commandsAllowed)
                .executes(ExchangeCommands::list)
                .then(Commands.literal("book")
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .executes(ExchangeCommands::book)))
                .then(Commands.literal("buy")
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                                .executes(ctx -> post(ctx, true))))))
                .then(Commands.literal("sell")
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("price", LongArgumentType.longArg(1))
                                                .executes(ctx -> post(ctx, false))))))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> report(ctx, ExchangeService.cancel(ctx.getSource().getPlayerOrException(),
                                        LongArgumentType.getLong(ctx, "id"))))))
                .then(Commands.literal("collect")
                        .executes(ctx -> report(ctx, ExchangeService.collect(ctx.getSource().getPlayerOrException(), Optional.empty())))
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .executes(ctx -> report(ctx, ExchangeService.collect(ctx.getSource().getPlayerOrException(),
                                        Optional.of(BuiltInRegistries.ITEM.getKey(item(ctx))))))));
    }

    static void addTo(LiteralArgumentBuilder<CommandSourceStack> admin) {
        admin.then(Commands.literal("exchange")
                .executes(ExchangeCommands::adminInfo)
                .then(Commands.literal("cancel")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ExchangeCommands::adminCancel))));
    }

    private static Item item(CommandContext<CommandSourceStack> ctx) {
        return ItemArgument.getItem(ctx, "item").getItem();
    }

    private static int post(CommandContext<CommandSourceStack> ctx, boolean buy) throws CommandSyntaxException {
        return report(ctx, ExchangeService.post(ctx.getSource().getPlayerOrException(), item(ctx), buy,
                LongArgumentType.getLong(ctx, "price"), IntegerArgumentType.getInteger(ctx, "qty")));
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ExchangeData data = ExchangeData.get(player.server);
        String unit = MarketCommand.unit(ctx.getSource());
        List<ExchangeData.Order> orders = ExchangeService.ordersOf(player.getUUID(), data);
        ctx.getSource().sendSuccess(() -> Component.literal("Exchange (%s): your open orders, %d of %d:".formatted(
                MarketConfig.exchangeEnabled() ? "on" : "off", orders.size(), MarketConfig.maxOpenOrders())), false);
        for (ExchangeData.Order o : orders) {
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d: %s %d × %s at %d %s each, %d filled".formatted(o.id(),
                    o.buy() ? "buy" : "sell", o.quantity(), o.item(), o.price(), unit, o.filled())), false);
        }
        if (orders.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  none"), false);
        }
        Map<ResourceLocation, Long> box = data.box(player.getUUID());
        if (!box.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("Collection box: " + box.entrySet().stream()
                    .map(e -> e.getValue() + " × " + e.getKey()).reduce((a, b) -> a + ", " + b).orElse("")), false);
        }
        return orders.size();
    }

    private static int book(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item(ctx));
        ExchangeService.Book book = ExchangeService.book(player, ExchangeData.get(player.server), id, MarketPayloads.ExchangeBook.DEPTH);
        String unit = MarketCommand.unit(ctx.getSource());
        ctx.getSource().sendSuccess(() -> Component.literal("Exchange book for " + id + ":"), false);
        for (ExchangeService.Level level : book.asks().reversed()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  sell %d at %d %s".formatted(level.quantity(), level.price(), unit)), false);
        }
        for (ExchangeService.Level level : book.bids()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  buy %d at %d %s".formatted(level.quantity(), level.price(), unit)), false);
        }
        if (book.asks().isEmpty() && book.bids().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  no orders"), false);
        }
        return book.asks().size() + book.bids().size();
    }

    private static int report(CommandContext<CommandSourceStack> ctx, ExchangeService.Outcome outcome) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!outcome.ok()) {
            ctx.getSource().sendFailure(Component.literal("Exchange %s failed: %s".formatted(outcome.action().name().toLowerCase(Locale.ROOT),
                    outcome.result())));
            return 0;
        }
        String unit = MarketCommand.unit(ctx.getSource());
        String message = switch (outcome.action()) {
            case POST -> "Posted an order for %d × %s: %d filled now (%d %s)%s".formatted(outcome.quantity(), outcome.item(),
                    outcome.filled(), outcome.amount(), unit, outcome.orderId() > 0 ? "; the rest is order #" + outcome.orderId() : "");
            case CANCEL -> outcome.amount() > 0
                    ? "Cancelled order #%d; %d %s returned".formatted(outcome.orderId(), outcome.amount(), unit)
                    : "Cancelled order #%d; %d × %s went to your collection box".formatted(outcome.orderId(), outcome.quantity(), outcome.item());
            case COLLECT -> "Took %d item(s) out of your collection box".formatted(outcome.quantity());
        };
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
        if (outcome.action() != ExchangeService.Action.COLLECT) {
            MarketSync.broadcastExchange(player.server);
        }
        MarketSync.sendPlayerState(player);
        return 1;
    }

    private static int adminInfo(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ExchangeData data = ExchangeData.get(server);
        String unit = MarketCommand.unit(ctx.getSource());
        long money = data.orders().stream().mapToLong(ExchangeData.Order::heldMoney).sum();
        long items = data.orders().stream().filter(o -> !o.buy()).mapToLong(ExchangeData.Order::remaining).sum();
        long boxed = data.boxes().values().stream().flatMap(b -> b.values().stream()).mapToLong(Long::longValue).sum();
        ctx.getSource().sendSuccess(() -> Component.literal(("Exchange is %s: %d open order(s) holding %d %s and %d item(s); %d item(s) "
                + "in %d collection box(es). Traded so far: %d %s; tax destroyed: %d %s").formatted(
                MarketConfig.exchangeEnabled() ? "on" : "off", data.orders().size(), money, unit, items, boxed, data.boxes().size(),
                data.volume(), unit, data.taxCollected(), unit)), false);
        for (ExchangeData.Order o : data.orders()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  #%d %s %d/%d × %s at %d, by %s".formatted(o.id(), o.buy() ? "buy" : "sell",
                    o.filled(), o.quantity(), o.item(), o.price(), name(server, o.owner()))), false);
        }
        if (!data.owed().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  %d payment(s) wait for the bank to be reachable".formatted(data.owed().size())), false);
        }
        return data.orders().size();
    }

    private static int adminCancel(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ExchangeData data = ExchangeData.get(server);
        long id = LongArgumentType.getLong(ctx, "id");
        Optional<ExchangeData.Order> order = data.find(id);
        if (order.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No open exchange order #" + id));
            return 0;
        }
        ExchangeData.Order o = order.get();
        ExchangeService.close(server, data, o);
        MarketSync.broadcastExchange(server);
        AuditLog.record(ctx.getSource(), "exchange cancel", "#" + id, "ok", new JsonPrimitive(o.remaining()), null);
        ctx.getSource().sendSuccess(() -> Component.literal("Closed order #%d (%s %d × %s at %d by %s); what it held went back to its owner"
                .formatted(id, o.buy() ? "buy" : "sell", o.remaining(), o.item(), o.price(), name(server, o.owner()))), true);
        return 1;
    }

    private static String name(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        return online != null ? online.getGameProfile().getName() : id.toString();
    }
}
