package dynamiceconomy.gametest;

import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.api.DynamicEconomyApi;
import dynamiceconomy.api.MarketQuote;
import dynamiceconomy.api.MarketResult;
import dynamiceconomy.api.PaymentResult;
import dynamiceconomy.api.TradeResult;
import dynamiceconomy.api.event.CatalogReloadedEvent;
import dynamiceconomy.api.event.ContractEvent;
import dynamiceconomy.api.event.ExchangeTradeEvent;
import dynamiceconomy.api.event.MarketTradeEvent;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.coin.Coin;
import dynamiceconomy.coin.Coins;
import dynamiceconomy.data.ApiPayments;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.data.ExchangeData;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.data.OwedPayouts;
import dynamiceconomy.service.ContractPricing;
import dynamiceconomy.service.ContractService;
import dynamiceconomy.service.ExchangeService;
import dynamiceconomy.service.Leaderboard;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.MarketService.Result;
import dynamiceconomy.service.MarketVisibility;
import dynamiceconomy.service.PayoutService;
import dynamiceconomy.service.PriceModel;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static dynamiceconomy.gametest.GameTestSupport.*;

/**
 * The add-on API: quotes, trades, payments, visibility rules, the events and {@code /market admin money}.
 *
 * Shared setup is in {@link GameTestSupport}.
 */
@GameTestHolder(DynamicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ApiTests {
    private ApiTests() {
    }

    // ---- Event recorder ----------------------------------------------------------------------------------------

    /**
     * One recorder for every API event, put on the event bus once per server run (listeners can't be taken off a
     * lambda again, so it stays and records only while a test asks it to).
     */
    private static boolean listening;
    private static boolean recording;
    /** While true, the recorder throws after recording, as a broken add-on would. */
    private static boolean throwing;
    private static final List<Event> recorded = new ArrayList<>();

    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        on(MarketTradeEvent.class);
        on(ContractEvent.Claimed.class);
        on(ContractEvent.Delivered.class);
        on(ContractEvent.Completed.class);
        on(ContractEvent.Abandoned.class);
        on(ContractEvent.Expired.class);
        on(ExchangeTradeEvent.class);
        on(CatalogReloadedEvent.class);
    }

    private static <T extends Event> void on(Class<T> type) {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, type, event -> {
            if (recording) {
                recorded.add(event);
                if (throwing) {
                    throw new IllegalStateException("A broken add-on listener (GameTest)");
                }
            }
        });
    }

    /** The API events posted while {@code body} runs, in order. */
    private static List<Event> eventsDuring(Runnable body) {
        listen();
        recorded.clear();
        recording = true;
        try {
            body.run();
        } finally {
            recording = false;
        }
        List<Event> events = List.copyOf(recorded);
        recorded.clear();
        return events;
    }

    private static <T extends Event> List<T> only(List<Event> events, Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath("api_test", path);
    }

    /** The API's trade result as the market's, so {@link Fixture#assertRejectedUnchanged} can check it. */
    private static MarketService.TradeResult internal(TradeResult result) {
        return new MarketService.TradeResult(Result.valueOf(result.result().name()), result.quantity(), result.unitPrice(),
                result.total());
    }

    /** An item the market doesn't list in the test world. */
    private static Item unlisted() {
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR && MarketCatalog.INSTANCE.get(item).isEmpty() && !Coins.isCoin(item)) {
                return item;
            }
        }
        throw new IllegalStateException("every item is listed");
    }

    private static int auditSize(MinecraftServer server) {
        return AuditLog.recent(AuditLog.file(server), 100_000).size();
    }

    private static JsonObject newestAudit(MinecraftServer server) {
        return AuditLog.recent(AuditLog.file(server), 1).get(0);
    }

    /** Asserts that a payment request was refused with {@code expected} and changed no balance and no audit line. */
    private static void assertPaymentRefused(GameTestHelper helper, Fixture f, MarketResult expected, Supplier<PaymentResult> request) {
        MinecraftServer server = f.player.server;
        int balance = f.balance();
        int lines = auditSize(server);
        PaymentResult result = request.get();
        helper.assertValueEqual(result.result(), expected, "payment result");
        helper.assertFalse(result.ok(), "not ok");
        helper.assertFalse(result.owed(), "nothing owed");
        helper.assertValueEqual(f.balance(), balance, "balance after " + expected);
        helper.assertValueEqual(auditSize(server), lines, "no audit line for a refused " + expected);
    }

    /** Runs {@code body} with a visibility rule in place, always removing it again. */
    private static void withRule(ResourceLocation id, java.util.function.BiPredicate<ServerPlayer, Item> hides, Runnable body) {
        DynamicEconomyApi.addVisibilityRule(id, hides);
        try {
            body.run();
        } finally {
            DynamicEconomyApi.removeVisibilityRule(id);
        }
    }

    // ---- Quotes ------------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void apiQuotesMatchTheMarket(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Fixture f = new Fixture(helper);
        Fixture other = new Fixture(helper);
        MarketService.Quote market = f.quote(Items.IRON_INGOT);
        MarketQuote quote = DynamicEconomyApi.quote(server, Items.IRON_INGOT).orElseThrow();
        helper.assertValueEqual(quote.item(), Items.IRON_INGOT, "item");
        helper.assertValueEqual(quote.basePrice(), (long) market.entry().basePrice(), "base price");
        helper.assertValueEqual(quote.sellPrice(), market.sellPrice(), "sell price");
        helper.assertValueEqual(quote.buyPrice(), market.buyPrice(), "buy price");
        helper.assertValueEqual(quote.stock(), market.stock(), "stock");
        helper.assertValueEqual(quote.canSell(), market.entry().canSell(), "can sell");
        helper.assertValueEqual(quote.canBuy(), market.entry().canBuy(), "can buy");
        helper.assertValueEqual(quote.factor(), market.factor(), "factor");
        helper.assertValueEqual(DynamicEconomyApi.quote(f.player, Items.IRON_INGOT), Optional.of(quote), "the player sees the same terms");

        CatalogEntry logs = MarketCatalog.INSTANCE.get(Items.OAK_LOG).orElseThrow();
        helper.assertTrue(logs.unlimitedStock(), "oak logs are unlimited in the example catalog");
        helper.assertValueEqual(DynamicEconomyApi.quote(server, Items.OAK_LOG).orElseThrow().stock(), OptionalLong.empty(),
                "unlimited stock is empty");

        Item unlisted = unlisted();
        helper.assertValueEqual(DynamicEconomyApi.quote(server, unlisted), Optional.empty(), "an unlisted item has no quote: " + key(unlisted));
        helper.assertValueEqual(DynamicEconomyApi.quote(f.player, unlisted), Optional.empty(), "for anyone");

        withRule(rl("hide_iron"), (player, item) -> player == f.player && item == Items.IRON_INGOT, () -> {
            helper.assertTrue(MarketVisibility.active(), "a rule makes visibility per player");
            helper.assertValueEqual(DynamicEconomyApi.quote(f.player, Items.IRON_INGOT), Optional.empty(), "hidden from this player");
            helper.assertTrue(DynamicEconomyApi.isHidden(f.player, Items.IRON_INGOT), "isHidden says so");
            helper.assertTrue(DynamicEconomyApi.quote(other.player, Items.IRON_INGOT).isPresent(), "others still see it");
            helper.assertFalse(DynamicEconomyApi.isHidden(other.player, Items.IRON_INGOT), "not hidden from others");
            helper.assertTrue(DynamicEconomyApi.quote(server, Items.IRON_INGOT).isPresent(), "the server-wide quote ignores visibility");
            helper.assertTrue(DynamicEconomyApi.listedItems(server).contains(Items.IRON_INGOT), "and so does the item list");
        });
        helper.assertTrue(DynamicEconomyApi.quote(f.player, Items.IRON_INGOT).isPresent(), "shown again once the rule is gone");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void apiHelpersAnswerLikeTheMod(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        List<Item> listed = DynamicEconomyApi.listedItems(server);
        helper.assertValueEqual(Set.copyOf(listed), MarketCatalog.INSTANCE.entries().stream().map(CatalogEntry::item).collect(Collectors.toSet()),
                "every listed item");
        helper.assertValueEqual(listed.size(), MarketCatalog.INSTANCE.entries().size(), "once each");
        helper.assertFalse(listed.stream().anyMatch(DynamicEconomyApi::isCoin), "never a coin");
        helper.assertTrue(DynamicEconomyApi.isCoin(Coins.item(Coin.BIT)), "a bit is a coin");
        helper.assertFalse(DynamicEconomyApi.isCoin(Items.IRON_INGOT), "iron isn't");
        helper.assertValueEqual(DynamicEconomyApi.bankType(server).name(), WorldBank.kind(server).name(), "the world's bank");
        helper.assertTrue(DynamicEconomyApi.bankAvailable(server), "reachable in the test world");
        helper.assertValueEqual(DynamicEconomyApi.formatMoney(server, 1234), PayoutService.money(server, 1234), "money as chat shows it");
        helper.succeed();
    }

    // ---- Trades ------------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void apiSellAndBuyTradeAndPostOneEventEach(GameTestHelper helper) {
        withFixedPrice(Items.IRON_INGOT, () -> {
            MinecraftServer server = helper.getLevel().getServer();
            Fixture f = new Fixture(helper);
            long stock = f.stock(Items.IRON_INGOT);
            try {
                f.give(new ItemStack(Items.IRON_INGOT, 10));
                MarketQuote quote = DynamicEconomyApi.quote(server, Items.IRON_INGOT).orElseThrow();
                TradeResult[] sold = new TradeResult[1];
                List<Event> events = eventsDuring(() -> sold[0] = DynamicEconomyApi.sell(f.player, Items.IRON_INGOT, 10));
                helper.assertTrue(sold[0].ok(), "sold: " + sold[0]);
                helper.assertValueEqual(sold[0], new TradeResult(MarketResult.OK, 10, quote.sellPrice(), 10 * quote.sellPrice()),
                        "ten at the quoted (fixed) price");
                helper.assertValueEqual(f.balance(), (int) sold[0].total(), "paid into the account");
                helper.assertValueEqual(f.count(Items.IRON_INGOT), 0L, "taken from the inventory");
                helper.assertValueEqual(f.stock(Items.IRON_INGOT), stock + 10, "added to stock");
                helper.assertValueEqual(events.size(), 1, "one event: " + events);
                MarketTradeEvent sale = (MarketTradeEvent) events.get(0);
                helper.assertTrue(sale.getPlayer() == f.player && sale.getServer() == server, "the player and server");
                helper.assertValueEqual(sale.getItem(), Items.IRON_INGOT, "event item");
                helper.assertFalse(sale.isBuy(), "a sale");
                helper.assertValueEqual(sale.getQuantity(), 10, "event quantity");
                helper.assertValueEqual(sale.getUnitPrice(), sold[0].unitPrice(), "event unit price");
                helper.assertValueEqual(sale.getTotal(), sold[0].total(), "event total");

                int balance = f.balance();
                TradeResult[] bought = new TradeResult[1];
                events = eventsDuring(() -> bought[0] = DynamicEconomyApi.buy(f.player, Items.IRON_INGOT, 5));
                helper.assertValueEqual(bought[0], new TradeResult(MarketResult.OK, 5, quote.buyPrice(), 5 * quote.buyPrice()),
                        "five at the quoted (fixed) price");
                helper.assertValueEqual(f.balance(), balance - (int) bought[0].total(), "charged");
                helper.assertValueEqual(f.count(Items.IRON_INGOT), 5L, "in the inventory");
                helper.assertValueEqual(f.stock(Items.IRON_INGOT), stock + 5, "taken from stock");
                helper.assertValueEqual(events.size(), 1, "one event: " + events);
                MarketTradeEvent purchase = (MarketTradeEvent) events.get(0);
                helper.assertTrue(purchase.isBuy() && purchase.getQuantity() == 5 && purchase.getTotal() == bought[0].total()
                        && purchase.getUnitPrice() == bought[0].unitPrice() && purchase.getPlayer() == f.player, "the purchase's event");

                // The screen and commands trade through MarketService, which posts the same event.
                events = eventsDuring(() -> f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 5, ANY_PRICE), Result.OK));
                helper.assertValueEqual(only(events, MarketTradeEvent.class).size(), 1, "a market-screen trade posts one too");
            } finally {
                f.setStock(Items.IRON_INGOT, stock);
            }
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void apiTradeRefusalsChangeNothingAndPostNoEvent(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        long stock = f.stock(Items.IRON_INGOT);
        try {
            f.setStock(Items.IRON_INGOT, 3);
            f.give(new ItemStack(Items.IRON_INGOT, 2));
            int max = MarketConfig.maxQuantityPerRequest();
            List<Event> events = eventsDuring(() -> {
                f.assertRejectedUnchanged(Result.NOT_ENOUGH_ITEMS, () -> internal(DynamicEconomyApi.sell(f.player, Items.IRON_INGOT, 5)));
                f.assertRejectedUnchanged(Result.NO_FUNDS, () -> internal(DynamicEconomyApi.buy(f.player, Items.IRON_INGOT, 1)));
                f.assertRejectedUnchanged(Result.NO_STOCK, () -> internal(DynamicEconomyApi.buy(f.player, Items.IRON_INGOT, 4)));
                f.assertRejectedUnchanged(Result.INVALID_QUANTITY, () -> internal(DynamicEconomyApi.sell(f.player, Items.IRON_INGOT, 0)));
                f.assertRejectedUnchanged(Result.INVALID_QUANTITY, () -> internal(DynamicEconomyApi.buy(f.player, Items.IRON_INGOT, -1)));
                f.assertRejectedUnchanged(Result.TOO_LARGE, () -> internal(DynamicEconomyApi.buy(f.player, Items.IRON_INGOT, max + 1)));
                f.assertRejectedUnchanged(Result.SELL_DISABLED, () -> internal(DynamicEconomyApi.sell(f.player, Items.NETHER_STAR, 1)));
                f.assertRejectedUnchanged(Result.NOT_LISTED, () -> internal(DynamicEconomyApi.sell(f.player, unlisted(), 1)));
                f.assertRejectedUnchanged(Result.NOT_LISTED, () -> internal(DynamicEconomyApi.buy(f.player, unlisted(), 1)));
                // An item a rule hides answers exactly as an unlisted one.
                f.deposit(10_000);
                withRule(rl("hide_iron_trades"), (player, item) -> player == f.player && item == Items.IRON_INGOT, () -> {
                    f.assertRejectedUnchanged(Result.NOT_LISTED, () -> internal(DynamicEconomyApi.sell(f.player, Items.IRON_INGOT, 1)));
                    f.assertRejectedUnchanged(Result.NOT_LISTED, () -> internal(DynamicEconomyApi.buy(f.player, Items.IRON_INGOT, 1)));
                });
            });
            helper.assertValueEqual(events, List.of(), "a refused trade posts no event");
            TradeResult refused = DynamicEconomyApi.sell(f.player, Items.IRON_INGOT, 0);
            helper.assertTrue(!refused.ok() && refused.quantity() == 0, "nothing traded: " + refused);
            f.assertResult(internal(DynamicEconomyApi.sell(f.player, Items.IRON_INGOT, 2)), Result.OK);
        } finally {
            f.setStock(Items.IRON_INGOT, stock);
        }
        helper.succeed();
    }

    /** A listener that throws is logged and ignored: the trade it heard about stands. */
    @GameTest(template = EMPTY)
    public static void aThrowingListenerCantUndoATrade(GameTestHelper helper) {
        withFixedPrice(Items.IRON_INGOT, () -> {
            Fixture f = new Fixture(helper);
            long stock = f.stock(Items.IRON_INGOT);
            f.give(new ItemStack(Items.IRON_INGOT, 4));
            TradeResult[] sold = new TradeResult[1];
            throwing = true;
            List<Event> events;
            try {
                events = eventsDuring(() -> sold[0] = DynamicEconomyApi.sell(f.player, Items.IRON_INGOT, 4));
            } finally {
                throwing = false;
                f.setStock(Items.IRON_INGOT, stock);
            }
            helper.assertTrue(sold[0].ok(), "the sale went through: " + sold[0]);
            helper.assertValueEqual(events.size(), 1, "the listener heard it once");
            helper.assertValueEqual(f.balance(), (int) sold[0].total(), "paid");
            helper.assertValueEqual(f.count(Items.IRON_INGOT), 0L, "the items went");
        });
        helper.succeed();
    }

    /** The API's results are the market's by name, so every result a trade can give has an API value. */
    @GameTest(template = EMPTY)
    public static void everyTradeResultHasAnApiResult(GameTestHelper helper) {
        List<Result> trades = List.of(Result.OK, Result.NOT_LISTED, Result.SELL_DISABLED, Result.BUY_DISABLED, Result.NOT_ENOUGH_ITEMS,
                Result.NO_STOCK, Result.NO_FUNDS, Result.NO_SPACE, Result.PRICE_CHANGED, Result.TOO_LARGE, Result.INVALID_QUANTITY,
                Result.BANK_UNAVAILABLE);
        for (Result result : trades) {
            try {
                MarketResult.valueOf(result.name());
            } catch (IllegalArgumentException e) {
                helper.fail("no MarketResult for " + result);
            }
        }
        Set<String> api = Arrays.stream(MarketResult.values()).map(Enum::name).collect(Collectors.toSet());
        Set<String> expected = trades.stream().map(Enum::name).collect(Collectors.toSet());
        expected.add("INVALID_AMOUNT");
        helper.assertValueEqual(api, expected, "the API's results: the trade results plus INVALID_AMOUNT");
        helper.succeed();
    }

    // ---- Money -------------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void apiPayAndChargeMoveMoneyAndAuditEachOnce(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Fixture f = new Fixture(helper);
        UUID id = f.player.getUUID();
        ResourceLocation reward = rl("reward");
        ResourceLocation fee = rl("fee");
        try {
            int lines = auditSize(server);
            PaymentResult paid = DynamicEconomyApi.pay(f.player, 500, reward);
            helper.assertValueEqual(paid, new PaymentResult(MarketResult.OK, 500, false), "paid now, not owed");
            helper.assertTrue(paid.ok(), "ok");
            helper.assertValueEqual(f.balance(), 500, "into the account");
            helper.assertValueEqual(DynamicEconomyApi.balance(f.player), OptionalLong.of(500), "the account the player trades with");
            helper.assertValueEqual(DynamicEconomyApi.balance(server, id), OptionalLong.of(500), "their own account");
            helper.assertValueEqual(auditSize(server), lines + 1, "one audit line");
            JsonObject payLine = newestAudit(server);
            helper.assertValueEqual(payLine.get("actor").getAsString(), "api:api_test:reward", "actor");
            helper.assertValueEqual(payLine.get("action").getAsString(), "api pay", "action");
            helper.assertTrue(payLine.get("target").getAsString().contains(id.toString()), "target: " + payLine);
            helper.assertValueEqual(payLine.get("result").getAsString(), "ok", "result");
            helper.assertValueEqual(payLine.get("after").getAsLong(), 500L, "amount");

            PaymentResult charged = DynamicEconomyApi.charge(f.player, 200, fee);
            helper.assertValueEqual(charged, new PaymentResult(MarketResult.OK, 200, false), "charged");
            helper.assertValueEqual(f.balance(), 300, "taken from the account");
            helper.assertValueEqual(auditSize(server), lines + 2, "one more audit line");
            JsonObject chargeLine = newestAudit(server);
            helper.assertValueEqual(chargeLine.get("actor").getAsString(), "api:api_test:fee", "actor");
            helper.assertValueEqual(chargeLine.get("action").getAsString(), "api charge", "action");
            helper.assertValueEqual(chargeLine.get("after").getAsLong(), 200L, "amount");

            assertPaymentRefused(helper, f, MarketResult.NO_FUNDS, () -> DynamicEconomyApi.charge(f.player, 301, fee));
            assertPaymentRefused(helper, f, MarketResult.NO_FUNDS, () -> DynamicEconomyApi.charge(server, id, 301, fee));
            assertPaymentRefused(helper, f, MarketResult.INVALID_AMOUNT, () -> DynamicEconomyApi.charge(f.player, 0, fee));
            assertPaymentRefused(helper, f, MarketResult.INVALID_AMOUNT, () -> DynamicEconomyApi.charge(server, id, -5, fee));
            assertPaymentRefused(helper, f, MarketResult.INVALID_AMOUNT, () -> DynamicEconomyApi.pay(f.player, 0, reward));
            assertPaymentRefused(helper, f, MarketResult.INVALID_AMOUNT, () -> DynamicEconomyApi.pay(server, id, -7, reward));
            long tooMuch = Integer.MAX_VALUE + 1L;
            assertPaymentRefused(helper, f, MarketResult.TOO_LARGE, () -> DynamicEconomyApi.charge(f.player, tooMuch, fee));
            assertPaymentRefused(helper, f, MarketResult.TOO_LARGE, () -> DynamicEconomyApi.charge(server, id, tooMuch, fee));
            assertPaymentRefused(helper, f, MarketResult.TOO_LARGE, () -> DynamicEconomyApi.pay(f.player, tooMuch, reward));
            assertPaymentRefused(helper, f, MarketResult.TOO_LARGE, () -> DynamicEconomyApi.pay(server, id, tooMuch, reward));

            // By UUID: the player's own account, online or not.
            helper.assertValueEqual(DynamicEconomyApi.pay(server, id, 50, reward), new PaymentResult(MarketResult.OK, 50, false), "paid by UUID");
            helper.assertValueEqual(f.balance(), 350, "into the player's account");
            helper.assertValueEqual(DynamicEconomyApi.charge(server, id, 350, fee), new PaymentResult(MarketResult.OK, 350, false),
                    "charged by UUID, down to zero");
            helper.assertValueEqual(f.balance(), 0, "all of it");
            helper.assertValueEqual(DynamicEconomyApi.balance(server, id), OptionalLong.of(0), "empty");
            helper.assertValueEqual(auditSize(server), lines + 4, "one audit line per payment or charge that went through");
            helper.assertValueEqual(newestAudit(server).get("action").getAsString(), "api charge", "the last one");
        } finally {
            f.account().deduct(f.balance());
        }
        helper.succeed();
    }

    /** Owed API payments wait, are paid once the bank is back, survive a restart and count on the leaderboard. */
    @GameTest(template = EMPTY)
    public static void owedApiPaymentsArePaidLaterAndCountOnTheLeaderboard(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Fixture f = new Fixture(helper);
        UUID id = f.player.getUUID();

        ApiPayments payments = new ApiPayments();
        payments.owed().owe(new OwedPayouts.Payout(id, Optional.empty(), 25));
        helper.assertTrue(payments.isDirty(), "owing marks the data to save");
        ApiPayments loaded = ApiPayments.fromTag(payments.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.owed().list(), payments.owed().list(), "owed money survives a restart");

        long tick = server.getTickCount();
        PayoutService.payOwedForTesting(server, payments.owed(), (player, team) -> Optional.empty());
        helper.assertValueEqual(payments.owed().size(), 1, "the bank couldn't be reached, so it's still owed");
        helper.assertTrue(!payments.owed().retryDue(tick) && payments.owed().retryDue(tick + OwedPayouts.RETRY_TICKS), "it waits");
        PayoutService.payOwed(server, payments.owed());
        helper.assertValueEqual(f.balance(), 0, "not tried again before the wait is over");
        payments.owed().retryNowForTesting();
        PayoutService.payOwed(server, payments.owed());
        helper.assertTrue(payments.owed().isEmpty(), "paid once due");
        helper.assertValueEqual(f.balance(), 25, "into the player's account");

        // The world's owed API payments count on the leaderboard. Taken off again before the tick pays them.
        OwedPayouts world = ApiPayments.get(server).owed();
        OwedPayouts.Payout owed = new OwedPayouts.Payout(id, Optional.empty(), 70);
        OwedPayouts.Payout forTeam = new OwedPayouts.Payout(id, Optional.of(UUID.randomUUID()), 1000);
        world.owe(owed);
        try {
            Leaderboard.View view = Leaderboard.viewFor(f.player, uuid -> uuid.equals(id)
                    ? Optional.of(f.player.getGameProfile()) : Optional.empty());
            helper.assertValueEqual(view.ownRank(), 1, "ranked");
            helper.assertValueEqual(view.top().get(0).balance(), 25L + 70L, "the bank's 25 plus the 70 owed");
            helper.assertValueEqual(world.forPlayers(Optional::isPresent).get(id), 70L, "the player's own money");
            world.owe(forTeam);
            helper.assertValueEqual(world.forPlayers(team -> PayoutService.paysTeam(server, team)).get(id), 1070L,
                    "money for a team that's gone is paid to the player, so it counts for them");
        } finally {
            world.paid(owed);
            world.paid(forTeam);
            f.account().deduct(f.balance());
        }
        helper.succeed();
    }

    // ---- Threads -----------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void apiRefusesCallsOffTheServerThread(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Fixture f = new Fixture(helper);
        f.deposit(100);
        f.give(new ItemStack(Items.IRON_INGOT, 1));
        UUID id = f.player.getUUID();
        ResourceLocation reason = rl("thread");
        List<String> threw = new ArrayList<>();
        List<String> answered = new ArrayList<>();
        ResourceLocation ruleId = rl("from_another_thread");
        List<Runnable> calls = List.of(
                () -> DynamicEconomyApi.quote(server, Items.IRON_INGOT),
                () -> DynamicEconomyApi.quote(f.player, Items.IRON_INGOT),
                () -> DynamicEconomyApi.listedItems(server),
                () -> DynamicEconomyApi.sell(f.player, Items.IRON_INGOT, 1),
                () -> DynamicEconomyApi.buy(f.player, Items.IRON_INGOT, 1),
                () -> DynamicEconomyApi.balance(f.player),
                () -> DynamicEconomyApi.balance(server, id),
                () -> DynamicEconomyApi.charge(f.player, 1, reason),
                () -> DynamicEconomyApi.charge(server, id, 1, reason),
                () -> DynamicEconomyApi.pay(f.player, 1, reason),
                () -> DynamicEconomyApi.pay(server, id, 1, reason),
                () -> DynamicEconomyApi.bankType(server),
                () -> DynamicEconomyApi.bankAvailable(server),
                () -> DynamicEconomyApi.formatMoney(server, 1),
                () -> DynamicEconomyApi.isHidden(f.player, Items.IRON_INGOT),
                () -> DynamicEconomyApi.refreshVisibility(f.player));
        Thread thread = new Thread(() -> {
            for (int i = 0; i < calls.size(); i++) {
                try {
                    calls.get(i).run();
                    answered.add("call " + i);
                } catch (IllegalStateException e) {
                    threw.add("call " + i);
                }
            }
            // These may be called from any thread.
            DynamicEconomyApi.isCoin(Items.IRON_INGOT);
            DynamicEconomyApi.addVisibilityRule(ruleId, (player, item) -> false);
            DynamicEconomyApi.removeVisibilityRule(ruleId);
        }, "dynamic-economy-api-test");
        try {
            thread.start();
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            DynamicEconomyApi.removeVisibilityRule(ruleId);
        }
        helper.assertFalse(thread.isAlive(), "the other thread finished");
        helper.assertValueEqual(answered, List.of(), "every server-thread-only call refused off the thread");
        helper.assertValueEqual(threw.size(), calls.size(), "each with IllegalStateException");
        helper.assertValueEqual(f.balance(), 100, "nothing paid or charged");
        helper.assertValueEqual(f.count(Items.IRON_INGOT), 1L, "nothing traded");
        helper.succeed();
    }

    // ---- Visibility rules --------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void visibilityRulesReplaceAndRemove(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        ResourceLocation id = rl("replaced");
        helper.assertFalse(DynamicEconomyApi.removeVisibilityRule(rl("never_added")), "an unknown rule isn't removed");
        try {
            DynamicEconomyApi.addVisibilityRule(id, (player, item) -> item == Items.IRON_INGOT);
            helper.assertTrue(DynamicEconomyApi.isHidden(f.player, Items.IRON_INGOT), "the rule hides iron");
            DynamicEconomyApi.addVisibilityRule(id, (player, item) -> item == Items.GOLD_INGOT);
            helper.assertFalse(DynamicEconomyApi.isHidden(f.player, Items.IRON_INGOT), "replaced by the same ID");
            helper.assertTrue(DynamicEconomyApi.isHidden(f.player, Items.GOLD_INGOT), "the new rule hides gold");
            helper.assertTrue(DynamicEconomyApi.removeVisibilityRule(id), "removed");
            helper.assertFalse(DynamicEconomyApi.removeVisibilityRule(id), "once");
            helper.assertFalse(DynamicEconomyApi.isHidden(f.player, Items.GOLD_INGOT), "gold shown again");
        } finally {
            DynamicEconomyApi.removeVisibilityRule(id);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void aVisibilityRuleThatThrowsIsRemoved(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        ResourceLocation broken = rl("broken");
        ResourceLocation working = rl("working");
        try {
            DynamicEconomyApi.addVisibilityRule(working, (player, item) -> item == Items.GOLD_INGOT);
            DynamicEconomyApi.addVisibilityRule(broken, (player, item) -> {
                if (item == Items.IRON_INGOT) {
                    throw new IllegalStateException("A broken visibility rule (GameTest)");
                }
                return item == Items.DIAMOND;
            });
            helper.assertTrue(DynamicEconomyApi.isHidden(f.player, Items.DIAMOND), "the rule works until it throws");
            helper.assertFalse(DynamicEconomyApi.isHidden(f.player, Items.IRON_INGOT), "a rule that throws hides nothing");
            helper.assertFalse(DynamicEconomyApi.removeVisibilityRule(broken), "it was removed");
            helper.assertFalse(DynamicEconomyApi.isHidden(f.player, Items.DIAMOND), "so what it hid is shown again");
            helper.assertTrue(DynamicEconomyApi.quote(f.player, Items.IRON_INGOT).isPresent(), "and quoted");
            helper.assertTrue(DynamicEconomyApi.isHidden(f.player, Items.GOLD_INGOT), "other rules stay");
        } finally {
            DynamicEconomyApi.removeVisibilityRule(broken);
            DynamicEconomyApi.removeVisibilityRule(working);
        }
        helper.succeed();
    }

    // ---- Contract events ---------------------------------------------------------------------------------------

    /** An open contract as ContractTests makes them, on a board of the test's own. */
    private static ContractBoard.Contract openContract(GameTestHelper helper, ContractBoard board, Item item, int units,
                                                      BigDecimal unitReward, long deposit) {
        MinecraftServer server = helper.getLevel().getServer();
        CatalogEntry entry = MarketCatalog.INSTANCE.get(item).orElseThrow();
        PriceModel.Inputs inputs = PriceModel.inputs(entry);
        double factor = MarketService.currentFactor(entry, inputs, MarketStockData.get(server), MarketStockData.now(server));
        long reward = ContractPricing.payout(unitReward, ContractPricing.lowestUnitBuyPrice(entry, inputs, factor), units);
        return new ContractBoard.Contract(board.takeId(), id(item), units, unitReward, reward, deposit, 0, Optional.empty(),
                Optional.empty(), ContractBoard.Status.OPEN);
    }

    private static void assertContractEvent(GameTestHelper helper, ContractEvent event, ContractBoard.Contract contract, UUID holder,
                                            int delivered) {
        helper.assertTrue(event.getServer() == helper.getLevel().getServer(), "the server");
        helper.assertValueEqual(event.getContractId(), contract.id(), "contract ID");
        helper.assertValueEqual(event.getHolder(), holder, "holder");
        helper.assertValueEqual(event.getItem(), contract.item(), "item");
        helper.assertValueEqual(event.getUnits(), contract.units(), "units");
        helper.assertValueEqual(event.getDelivered(), delivered, "delivered so far");
    }

    @GameTest(template = EMPTY)
    public static void contractActionsPostTheirEvents(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        UUID holder = f.player.getUUID();
        f.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32)));
        ContractBoard.Contract logs = board.contracts().get(0);

        List<Event> events = eventsDuring(() -> helper.assertValueEqual(ContractService.claim(f.player, board, logs.id()).result(),
                Result.OK, "claimed"));
        helper.assertValueEqual(events.size(), 1, "one event: " + events);
        ContractEvent.Claimed claimed = (ContractEvent.Claimed) events.get(0);
        assertContractEvent(helper, claimed, logs, holder, 0);
        helper.assertValueEqual(claimed.getDeposit(), 32L, "the deposit");

        f.giveMany(Items.OAK_LOG, 64);
        events = eventsDuring(() -> helper.assertValueEqual(ContractService.deliver(f.player, board, logs.id(), 40).result(),
                Result.OK, "first batch"));
        helper.assertValueEqual(events.size(), 1, "a batch posts one event: " + events);
        ContractEvent.Delivered first = (ContractEvent.Delivered) events.get(0);
        assertContractEvent(helper, first, logs, holder, 40);
        helper.assertValueEqual(first.getQuantity(), 40, "this batch");

        events = eventsDuring(() -> {
            helper.assertValueEqual(ContractService.deliver(f.player, board, logs.id(), 25).result(), Result.CONTRACT_FULL, "too many");
            helper.assertValueEqual(ContractService.abandon(new Fixture(helper).player, board, logs.id()).result(), Result.NOT_YOUR_CONTRACT,
                    "someone else's");
        });
        helper.assertValueEqual(events, List.of(), "refused actions post nothing");

        events = eventsDuring(() -> helper.assertValueEqual(ContractService.deliver(f.player, board, logs.id(), 24).result(),
                Result.OK, "last batch"));
        helper.assertValueEqual(events.size(), 2, "the last batch, then the completion: " + events);
        ContractEvent.Delivered last = (ContractEvent.Delivered) events.get(0);
        assertContractEvent(helper, last, logs, holder, 64);
        helper.assertValueEqual(last.getQuantity(), 24, "this batch");
        ContractEvent.Completed completed = (ContractEvent.Completed) events.get(1);
        assertContractEvent(helper, completed, logs, holder, 64);
        helper.assertValueEqual(completed.getReward(), 160L, "the reward");
        helper.assertValueEqual(completed.getDeposit(), 32L, "the deposit back");
        helper.assertValueEqual(f.balance(), 1000 + 160, "and the money matches");

        // Abandoned: paid for what was delivered, the deposit lost.
        Fixture quitter = new Fixture(helper);
        quitter.deposit(1000);
        ContractBoard other = board(helper, b -> List.of(openContract(helper, b, Items.COBBLESTONE, 64, new BigDecimal("1.25"), 16)));
        ContractBoard.Contract cobble = other.contracts().get(0);
        ContractService.claim(quitter.player, other, cobble.id());
        quitter.giveMany(Items.COBBLESTONE, 10);
        ContractService.deliver(quitter.player, other, cobble.id(), 10);
        events = eventsDuring(() -> helper.assertValueEqual(ContractService.abandon(quitter.player, other, cobble.id()).result(),
                Result.OK, "abandoned"));
        helper.assertValueEqual(events.size(), 1, "one event: " + events);
        ContractEvent.Abandoned abandoned = (ContractEvent.Abandoned) events.get(0);
        assertContractEvent(helper, abandoned, cobble, quitter.player.getUUID(), 10);
        helper.assertValueEqual(abandoned.getPaid(), 12L, "10 × 1.25");
        helper.assertValueEqual(abandoned.getDepositLost(), 16L, "the deposit lost");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void boardReplacementPostsExpiredForEachHeldContract(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Fixture late = new Fixture(helper);
        Fixture idle = new Fixture(helper);
        late.deposit(1000);
        idle.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(
                openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32),
                openContract(helper, b, Items.COBBLESTONE, 64, new BigDecimal("1.25"), 16),
                openContract(helper, b, Items.OAK_LOG, 32, new BigDecimal("2.5"), 8)));
        ContractBoard.Contract logs = board.contracts().get(0);
        ContractBoard.Contract cobble = board.contracts().get(1);
        ContractService.claim(late.player, board, logs.id());
        late.giveMany(Items.OAK_LOG, 10);
        ContractService.deliver(late.player, board, logs.id(), 10);
        ContractService.claim(idle.player, board, cobble.id());
        board.observe(ContractBoard.worldDay(server) + 5);
        helper.assertTrue(board.due(), "five days later the board is due");

        List<Event> events = eventsDuring(() -> ContractService.refresh(server, board, net.minecraft.util.RandomSource.create(7)));
        List<ContractEvent.Expired> expired = only(events, ContractEvent.Expired.class);
        helper.assertValueEqual(expired.size(), 2, "one per held contract, none for the open one: " + events);
        helper.assertValueEqual(events.size(), 2, "and nothing else");
        ContractEvent.Expired first = expired.stream().filter(e -> e.getContractId() == logs.id()).findFirst().orElseThrow();
        assertContractEvent(helper, first, logs, late.player.getUUID(), 10);
        helper.assertValueEqual(first.getPaid(), 25L, "paid for 10");
        helper.assertValueEqual(first.getDepositLost(), 32L, "the deposit lost");
        helper.assertFalse(first.isDepositReturned(), "a board replacement keeps the deposit");
        ContractEvent.Expired second = expired.stream().filter(e -> e.getContractId() == cobble.id()).findFirst().orElseThrow();
        assertContractEvent(helper, second, cobble, idle.player.getUUID(), 0);
        helper.assertValueEqual(second.getPaid(), 0L, "nothing delivered, nothing paid");
        helper.assertValueEqual(second.getDepositLost(), 16L, "the deposit lost");
        helper.assertTrue(board.find(logs.id()).isEmpty(), "posted once the new board is up");
        helper.assertValueEqual(late.balance(), 1000 - 32 + 25, "the money matches the event");
        helper.succeed();
    }

    // ---- Exchange events ---------------------------------------------------------------------------------------

    private static ItemStack named(String name, int count) {
        ItemStack stack = new ItemStack(Items.COBBLESTONE, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    @GameTest(template = EMPTY)
    public static void exchangeTradesPostOneEventPerFill(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            Fixture seller = new Fixture(helper);
            Fixture buyer = new Fixture(helper);
            seller.give(new ItemStack(Items.COBBLESTONE, 10));
            buyer.deposit(1000);

            List<Event> events = eventsDuring(() -> posted(helper, seller, data, Items.COBBLESTONE, false, 5, 10));
            helper.assertValueEqual(events, List.of(), "an order that waits trades nothing");
            events = eventsDuring(() -> posted(helper, buyer, data, Items.COBBLESTONE, true, 7, 4));
            helper.assertValueEqual(events.size(), 1, "one fill: " + events);
            ExchangeTradeEvent fill = (ExchangeTradeEvent) events.get(0);
            helper.assertTrue(fill.getServer() == helper.getLevel().getServer(), "the server");
            helper.assertValueEqual(fill.getBuyer(), buyer.player.getUUID(), "buyer");
            helper.assertValueEqual(fill.getSeller(), seller.player.getUUID(), "seller");
            helper.assertTrue(ItemStack.matches(fill.getItem(), new ItemStack(Items.COBBLESTONE)), "one fresh cobblestone: " + fill.getItem());
            helper.assertValueEqual(fill.getQuantity(), 4, "quantity");
            helper.assertValueEqual(fill.getUnitPrice(), 5L, "the waiting order's price");
            helper.assertValueEqual(fill.getValue(), 20L, "value");
            helper.assertValueEqual(fill.getTax(), 1L, "5% of 20, rounded up");
            helper.assertFalse(fill.isFromListing(), "two orders");
            fill.getItem().setCount(64);
            helper.assertValueEqual(fill.getItem().getCount(), 1, "each call gives a new copy");

            // A sell order filling two waiting bids posts two events.
            Fixture other = new Fixture(helper);
            other.deposit(1000);
            posted(helper, other, data, Items.COBBLESTONE, true, 3, 2);
            posted(helper, buyer, data, Items.COBBLESTONE, true, 4, 1);
            Fixture second = new Fixture(helper);
            second.give(new ItemStack(Items.COBBLESTONE, 3));
            events = eventsDuring(() -> posted(helper, second, data, Items.COBBLESTONE, false, 3, 3));
            List<ExchangeTradeEvent> fills = only(events, ExchangeTradeEvent.class);
            helper.assertValueEqual(fills.size(), 2, "one per waiting order filled: " + events);
            helper.assertValueEqual(fills.get(0).getBuyer(), buyer.player.getUUID(), "the best bid first");
            helper.assertValueEqual(fills.get(0).getUnitPrice(), 4L, "at its price");
            helper.assertValueEqual(fills.get(1).getBuyer(), other.player.getUUID(), "then the next");
            helper.assertValueEqual(fills.get(1).getQuantity(), 2, "for the rest");
            helper.assertTrue(fills.stream().allMatch(e -> e.getSeller().equals(second.player.getUUID())), "all sold by the incoming order");
            helper.assertValueEqual(fills.stream().mapToLong(ExchangeTradeEvent::getTax).sum(), ExchangeService.tax(4 + 6, 0.05),
                    "the tax, rounded once on the whole order");

            // Buying from a listing.
            Fixture lister = new Fixture(helper);
            ItemStack stone = named("Event stone", 8);
            lister.give(stone.copy());
            ExchangeService.Outcome listed = ExchangeService.list(lister.player, data, 0, lister.player.getInventory().items.get(0).copy(),
                    10, 8, 0);
            helper.assertValueEqual(listed.result(), Result.OK, "listed");
            events = eventsDuring(() -> helper.assertValueEqual(ExchangeService.buyListing(buyer.player, data, listed.orderId(), 3, 10).result(),
                    Result.OK, "bought from the listing"));
            helper.assertValueEqual(events.size(), 1, "one purchase: " + events);
            ExchangeTradeEvent purchase = (ExchangeTradeEvent) events.get(0);
            helper.assertTrue(purchase.isFromListing(), "from a listing");
            helper.assertValueEqual(purchase.getBuyer(), buyer.player.getUUID(), "buyer");
            helper.assertValueEqual(purchase.getSeller(), lister.player.getUUID(), "seller");
            helper.assertValueEqual(purchase.getQuantity(), 3, "quantity");
            helper.assertValueEqual(purchase.getUnitPrice(), 10L, "the listing's price");
            helper.assertValueEqual(purchase.getValue(), 30L, "value");
            helper.assertValueEqual(purchase.getTax(), ExchangeService.tax(30, 0.05), "tax");
            helper.assertTrue(ItemStack.isSameItemSameComponents(purchase.getItem(), stone) && purchase.getItem().getCount() == 1,
                    "the listed item with its name: " + purchase.getItem());

            events = eventsDuring(() -> helper.assertValueEqual(ExchangeService.buyListing(lister.player, data, listed.orderId(), 1, 10).result(),
                    Result.OWN_LISTING, "refused"));
            helper.assertValueEqual(events, List.of(), "a refused purchase posts nothing");
        });
        helper.succeed();
    }

    // ---- Catalog event -----------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void catalogRebuildPostsCatalogReloaded(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        List<Event> events = eventsDuring(() -> DynamicEconomy.catalogReloaded(server));
        List<CatalogReloadedEvent> reloaded = only(events, CatalogReloadedEvent.class);
        helper.assertValueEqual(reloaded.size(), 1, "one event per rebuild: " + events);
        helper.assertTrue(reloaded.get(0).getServer() == server, "for this server");
        helper.assertTrue(MarketCatalog.INSTANCE.get(Items.IRON_INGOT).isPresent(), "posted with the catalog live");
        helper.succeed();
    }

    /** ContractEvent's javadoc says listening for the class itself hears every contract event. */
    @GameTest(template = EMPTY)
    public static void listeningForEveryContractEventAtOnce(GameTestHelper helper) {
        try {
            registerContractEventListener();
        } catch (IllegalArgumentException e) {
            helper.fail("ContractEvent can't be listened for as a whole: " + e.getMessage());
        }
        helper.succeed();
    }

    private static boolean contractListener;

    private static void registerContractEventListener() {
        if (!contractListener) {
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ContractEvent.class, event -> {
            });
            contractListener = true;
        }
    }

    // ---- /market admin money -----------------------------------------------------------------------------------

    /**
     * Runs a command at permission 2 (a game master, as the admin commands need) as {@code as}, so {@code @s} names
     * that player. Returns the command's result; {@code output} gets what it printed.
     */
    private static int commandAs(GameTestHelper helper, ServerPlayer as, List<String> output, String command) throws CommandSyntaxException {
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withSource(capture).withPermission(2).withEntity(as);
        return server.getCommands().getDispatcher().execute(command, source);
    }

    @GameTest(template = EMPTY)
    public static void adminMoneyCommandsShowGiveAndTake(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Fixture f = new Fixture(helper);
        String target = f.player.getGameProfile().getName() + " (" + f.player.getUUID() + ")";
        List<String> output = new ArrayList<>();
        try {
            f.deposit(40);
            helper.assertValueEqual(commandAs(helper, f.player, output, "market admin money balance @s[type=player]"), 40, "the balance is returned");
            helper.assertTrue(output.stream().anyMatch(line -> line.contains("40")), "and shown: " + output);

            int lines = auditSize(server);
            helper.assertValueEqual(commandAs(helper, f.player, output, "market admin money give @s[type=player] 100"), 100, "the amount given");
            helper.assertValueEqual(f.balance(), 140, "given");
            helper.assertValueEqual(auditSize(server), lines + 1, "one audit line");
            JsonObject give = newestAudit(server);
            helper.assertValueEqual(give.get("action").getAsString(), "money give", "action");
            helper.assertValueEqual(give.get("target").getAsString(), target, "target");
            helper.assertValueEqual(give.get("result").getAsString(), "ok", "result");
            helper.assertValueEqual(give.get("before").getAsLong(), 40L, "before");
            helper.assertValueEqual(give.get("after").getAsLong(), 140L, "after");

            helper.assertValueEqual(commandAs(helper, f.player, output, "market admin money take @s[type=player] 90"), 90, "the amount taken");
            helper.assertValueEqual(f.balance(), 50, "taken");
            JsonObject take = newestAudit(server);
            helper.assertValueEqual(take.get("action").getAsString(), "money take", "action");
            helper.assertTrue(take.get("before").getAsLong() == 140 && take.get("after").getAsLong() == 50, "before and after: " + take);
            helper.assertValueEqual(auditSize(server), lines + 2, "one more audit line");

            output.clear();
            helper.assertValueEqual(commandAs(helper, f.player, output, "market admin money take @s[type=player] 51"), 0, "can't take more than there is");
            helper.assertValueEqual(f.balance(), 50, "nothing taken");
            helper.assertValueEqual(auditSize(server), lines + 2, "nothing logged");
            helper.assertTrue(output.stream().anyMatch(line -> line.contains("only")), "says why: " + output);

            boolean refused = false;
            try {
                commandAs(helper, f.player, output, "market admin money give @s[type=player] 0");
            } catch (CommandSyntaxException e) {
                refused = true;
            }
            helper.assertTrue(refused, "an amount below 1 isn't accepted");
            helper.assertValueEqual(f.balance(), 50, "nothing given");

            helper.assertValueEqual(commandAs(helper, f.player, output, "market admin money take @s[type=player] 50"), 50, "down to zero");
            helper.assertValueEqual(commandAs(helper, f.player, output, "market admin money balance @s[type=player]"), 0, "empty");
        } catch (CommandSyntaxException e) {
            helper.fail("a command was rejected: " + e.getMessage());
        } finally {
            f.account().deduct(f.balance());
        }
        helper.succeed();
    }

    /** Below permission 2 the money commands don't exist. */
    @GameTest(template = EMPTY)
    public static void adminMoneyCommandsNeedPermission(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Fixture f = new Fixture(helper);
        CommandSourceStack source = server.createCommandSourceStack().withPermission(0).withEntity(f.player);
        boolean refused = false;
        try {
            server.getCommands().getDispatcher().execute("market admin money give @s[type=player] 100", source);
        } catch (CommandSyntaxException e) {
            refused = true;
        }
        helper.assertTrue(refused, "refused below permission 2");
        helper.assertValueEqual(f.balance(), 0, "nothing given");
        helper.succeed();
    }
}
