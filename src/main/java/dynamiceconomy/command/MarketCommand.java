package dynamiceconomy.command;

import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.service.MarketService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * Phase 1 test commands. These go away or change once the market screen exists.
 * Brigadier runs commands on the server thread, which is what {@link MarketService} requires.
 */
public final class MarketCommand {
    private MarketCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal("market")
                .then(Commands.literal("sell")
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .executes(ctx -> trade(ctx, true)))))
                .then(Commands.literal("buy")
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .then(Commands.argument("qty", IntegerArgumentType.integer(1))
                                        .executes(ctx -> trade(ctx, false)))))
                .then(Commands.literal("stock")
                        .executes(MarketCommand::listStock)
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .executes(MarketCommand::showStock)))
                .then(Commands.literal("price")
                        .then(Commands.argument("item", ItemArgument.item(context))
                                .executes(MarketCommand::showPrice)))
                .then(Commands.literal("admin")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("setstock")
                                .then(Commands.argument("item", ItemArgument.item(context))
                                        .then(Commands.argument("qty", LongArgumentType.longArg(0))
                                                .executes(MarketCommand::setStock))))
                        .then(Commands.literal("reload")
                                .executes(MarketCommand::reload))));
    }

    private static Item item(CommandContext<CommandSourceStack> ctx) {
        return ItemArgument.getItem(ctx, "item").getItem();
    }

    private static ResourceLocation id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private static int trade(CommandContext<CommandSourceStack> ctx, boolean selling) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Item item = item(ctx);
        int qty = IntegerArgumentType.getInteger(ctx, "qty");
        MarketService.TradeResult result = selling
                ? MarketService.trySell(player, item, qty, OptionalLong.empty())
                : MarketService.tryBuy(player, item, qty, OptionalLong.empty());

        if (result.ok()) {
            long stock = MarketStockData.get(player.server).getStock(id(item));
            String verb = selling ? "Sold" : "Bought";
            String direction = selling ? "received" : "paid";
            ctx.getSource().sendSuccess(() -> Component.literal("%s %d × %s for %d spurs (%d each); you %s %d spurs. Market stock: %d"
                    .formatted(verb, result.quantity(), id(item), result.total(), result.unitPrice(), direction, result.total(), stock)), false);
            return result.quantity();
        }
        ctx.getSource().sendFailure(Component.literal("%s failed: %s".formatted(selling ? "Sell" : "Buy", result.result())));
        return 0;
    }

    private static int listStock(CommandContext<CommandSourceStack> ctx) {
        MarketStockData market = MarketStockData.get(ctx.getSource().getServer());
        if (MarketCatalog.INSTANCE.entries().isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("The market catalog is empty"));
            return 0;
        }
        for (CatalogEntry entry : MarketCatalog.INSTANCE.entries()) {
            long stock = market.getStock(entry.itemId());
            ctx.getSource().sendSuccess(() -> Component.literal("%s: %d".formatted(entry.itemId(), stock)), false);
        }
        return MarketCatalog.INSTANCE.entries().size();
    }

    private static int showStock(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        long stock = MarketStockData.get(ctx.getSource().getServer()).getStock(id(item));
        String listed = MarketCatalog.INSTANCE.get(item).isPresent() ? "" : " (not listed)";
        ctx.getSource().sendSuccess(() -> Component.literal("%s: %d%s".formatted(id(item), stock, listed)), false);
        return (int) Math.min(stock, Integer.MAX_VALUE);
    }

    private static int showPrice(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        Optional<MarketService.Quote> quote = MarketService.quote(ctx.getSource().getServer(), item);
        if (quote.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(id(item) + " is not listed"));
            return 0;
        }
        MarketService.Quote q = quote.get();
        String sell = q.entry().canSell() ? q.sellPrice() + " spurs" : "disabled";
        String buy = q.entry().canBuy() ? q.buyPrice() + " spurs" : "disabled";
        ctx.getSource().sendSuccess(() -> Component.literal("%s — market pays %s, market charges %s, stock %d"
                .formatted(id(item), sell, buy, q.stock())), false);
        return 1;
    }

    private static int setStock(CommandContext<CommandSourceStack> ctx) {
        Item item = item(ctx);
        long qty = LongArgumentType.getLong(ctx, "qty");
        MarketStockData.get(ctx.getSource().getServer()).setStock(id(item), qty);
        String listed = MarketCatalog.INSTANCE.get(item).isPresent() ? "" : " (warning: not listed in the catalog)";
        ctx.getSource().sendSuccess(() -> Component.literal("Set %s stock to %d%s".formatted(id(item), qty, listed)), true);
        return 1;
    }

    /**
     * Re-reads the market JSON from the packs already loaded, without a full /reload. Edits to existing files are
     * picked up; a newly added datapack still needs /reload. Stock is never touched apart from seeding new items.
     */
    private static int reload(CommandContext<CommandSourceStack> ctx) {
        var server = ctx.getSource().getServer();
        int count = MarketCatalog.INSTANCE.reloadNow(server.getResourceManager());
        MarketStockData.get(server).seed(MarketCatalog.INSTANCE.entries());
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded market catalog: %d entries".formatted(count)), true);
        return count;
    }
}
