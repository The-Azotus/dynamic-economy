package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.data.ExchangeData;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.service.ExchangeService;
import dynamiceconomy.service.BankService;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.MarketService.Result;
import dynamiceconomy.service.MarketService.TradeResult;
import dynamiceconomy.service.PriceModel;
import dynamiceconomy.service.PriceSuggestion;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;

/**
 * What the GameTest classes share: the fixture (a fake player with an inventory and bank account), constants and
 * helpers used by more than one test class. Tests run synchronously inside one tick, so tests sharing the global
 * stock and catalog cannot interleave. They rely on the example catalog pack, which the GameTest server enables
 * like every available pack. Run them with {@code gradlew runGameTestServer}.
 */
final class GameTestSupport {
    private GameTestSupport() {
    }

    static final String EMPTY = "empty";

    static final OptionalLong ANY_PRICE = OptionalLong.empty();

    // ---- Shared helpers ----------------------------------------------------------------------------------------

    /** Runs {@code body} with an item's supply/demand turned off, so it trades at fixed base prices. */
    static void withFixedPrice(Item item, Runnable body) {
        CatalogEntry entry = MarketCatalog.INSTANCE.get(item).orElseThrow();
        withEntry(entry.withPricing(entry.sellFee(), false, entry.minBuyMarkup(), entry.overrides()), body);
    }

    /** Runs {@code body} with one listing replaced, then puts the real catalog back. */
    static void withEntry(CatalogEntry entry, Runnable body) {
        Map<Item, CatalogEntry> original = swapEntry(entry);
        try {
            body.run();
        } finally {
            MarketCatalog.INSTANCE.replaceForTesting(original);
        }
    }

    /** Replaces one listing in the live catalog and returns the whole previous catalog to restore. */
    static Map<Item, CatalogEntry> swapEntry(CatalogEntry entry) {
        Map<Item, CatalogEntry> swapped = new java.util.LinkedHashMap<>();
        MarketCatalog.INSTANCE.entries().forEach(e -> swapped.put(e.item(), e));
        swapped.put(entry.item(), entry);
        return MarketCatalog.INSTANCE.replaceForTesting(swapped);
    }

    static PriceModel.Inputs inputs(int base, double markup, double sellFee, boolean unlimited) {
        return new PriceModel.Inputs(base, markup, sellFee, markup, unlimited, true, MarketConfig.sellImpact(),
                MarketConfig.buyImpact(), MarketConfig.floor(), MarketConfig.ceiling(), MarketConfig.recovery());
    }

    /** The catalog files as loaded in the test world (which has no admin edits of its own). */
    static Map<Item, CatalogEntry> files() {
        Map<Item, CatalogEntry> files = new java.util.LinkedHashMap<>();
        MarketCatalog.INSTANCE.entries().forEach(e -> files.put(e.item(), e));
        return files;
    }

    static ResourceLocation key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    /** What a 3x3 crafting grid makes, if anything. */
    static Optional<ItemStack> craft(GameTestHelper helper, Item... grid) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Item item : grid) {
            stacks.add(new ItemStack(item));
        }
        CraftingInput input = CraftingInput.of(3, 3, stacks);
        return helper.getLevel().getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
                .map(r -> r.value().assemble(input, helper.getLevel().registryAccess()));
    }

    static MarketCatalog.Loaded loadOne(String json) {
        return MarketCatalog.load(List.of(new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath("test", "classes"), 0,
                "test", JsonParser.parseString(json))));
    }

    static CatalogEntry seed(Item item, int price, String itemClass) {
        JsonObject json = new JsonObject();
        json.addProperty("item", key(item).toString());
        json.addProperty("base_price", price);
        if (itemClass != null) {
            json.addProperty("class", itemClass);
        }
        return MarketCatalog.decode(json).result().orElseThrow();
    }

    static PriceSuggestion.Path path(Item output, int count, boolean fuel, Item... inputs) {
        List<PriceSuggestion.Slot> slots = new ArrayList<>();
        for (Item input : inputs) {
            slots.add(new PriceSuggestion.Slot(List.of(input), 1));
        }
        return new PriceSuggestion.Path(ResourceLocation.fromNamespaceAndPath("test", key(output).getPath()), "minecraft:crafting",
                output, count, slots, fuel);
    }

    /** The built-in price files, read straight from the mod (the GameTest server leaves the pack itself off). */
    static MarketCatalog.Loaded defaultPrices() {
        return MarketCatalog.load(defaultPriceFiles());
    }

    static List<MarketCatalog.SourceFile> defaultPriceFiles() {
        try {
            Path folder = net.neoforged.fml.ModList.get().getModFileById(DynamicEconomy.MOD_ID).getFile()
                    .findResource("default_prices", "data", DynamicEconomy.MOD_ID, "market");
            List<MarketCatalog.SourceFile> files = new ArrayList<>();
            try (var paths = Files.list(folder)) {
                for (Path file : paths.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    String name = file.getFileName().toString().replace(".json", "");
                    files.add(new MarketCatalog.SourceFile(ResourceLocation.fromNamespaceAndPath(DynamicEconomy.MOD_ID, name), 0,
                            "default_prices", JsonParser.parseString(Files.readString(file))));
                }
            }
            return files;
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** The most a round trip in either order makes (negative: it loses), over a few starting factors and sizes. */
    static long bestRoundTripProfit(PriceModel.Inputs in) {
        long best = Long.MIN_VALUE;
        for (double start : List.of(in.floor(), 1.0, in.ceiling())) {
            for (int qty : List.of(1, 64, 640, 3000)) {
                PriceModel.Order bought = PriceModel.buy(in, start, qty);
                PriceModel.Order resold = PriceModel.sell(in, bought.factorAfter(), qty);
                PriceModel.Order sold = PriceModel.sell(in, start, qty);
                PriceModel.Order boughtBack = PriceModel.buy(in, sold.factorAfter(), qty);
                best = Math.max(best, Math.max(resold.total() - bought.total(), sold.total() - boughtBack.total()));
            }
        }
        return best;
    }

    static JsonElement number(double value) {
        return new com.google.gson.JsonPrimitive(value);
    }

    /** Runs a command at permission 4, the way an operator or the console would, and returns what it printed. */
    static List<String> runCommand(GameTestHelper helper, String command) {
        List<String> output = new ArrayList<>();
        net.minecraft.commands.CommandSource capture = new net.minecraft.commands.CommandSource() {
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
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        try {
            server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack().withSource(capture).withPermission(4));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new GameTestAssertException("/" + command + " was rejected: " + e.getMessage());
        }
        return output;
    }

    static ResourceLocation id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    /** A board of its own holding these contracts, replaced in 5 days. */
    static ContractBoard board(GameTestHelper helper, java.util.function.Function<ContractBoard, List<ContractBoard.Contract>> contracts) {
        ContractBoard board = new ContractBoard();
        board.observe(ContractBoard.worldDay(helper.getLevel().getServer()));
        board.replace(contracts.apply(board), 5);
        return board;
    }

    static ContractBoard.Contract contract(ContractBoard board, int id) {
        return board.find(id).orElseThrow();
    }

    /** Ends a test that needs {@code mod} as passed when it isn't installed, noting it in the log. */
    static boolean skippedWithout(GameTestHelper helper, String mod) {
        if (net.neoforged.fml.ModList.get().isLoaded(mod)) {
            return false;
        }
        DynamicEconomy.LOGGER.info("GameTest skipped: it needs {}, which isn't installed", mod);
        helper.succeed();
        return true;
    }

    /**
     * Runs {@code body} with the exchange on, a 5% tax, 10 open orders each, no expiry and orders of up to 6,400, then
     * puts the config back. Tests use exchanges of their own ({@code new ExchangeData()}), never the world's.
     */
    static void withExchange(Runnable body) {
        boolean enabled = MarketConfig.EXCHANGE_ENABLED.get();
        double tax = MarketConfig.EXCHANGE_TAX.get();
        int orders = MarketConfig.MAX_OPEN_ORDERS.get();
        int expiry = MarketConfig.ORDER_EXPIRY_DAYS.get();
        int size = MarketConfig.MAX_ORDER_SIZE.get();
        MarketConfig.EXCHANGE_ENABLED.set(true);
        MarketConfig.EXCHANGE_TAX.set(0.05);
        MarketConfig.MAX_OPEN_ORDERS.set(10);
        MarketConfig.ORDER_EXPIRY_DAYS.set(0);
        MarketConfig.MAX_ORDER_SIZE.set(6400);
        try {
            body.run();
        } finally {
            MarketConfig.EXCHANGE_ENABLED.set(enabled);
            MarketConfig.EXCHANGE_TAX.set(tax);
            MarketConfig.MAX_OPEN_ORDERS.set(orders);
            MarketConfig.ORDER_EXPIRY_DAYS.set(expiry);
            MarketConfig.MAX_ORDER_SIZE.set(size);
        }
    }

    static ExchangeService.Outcome post(Fixture f, ExchangeData data, Item item, boolean buy, long price, int qty) {
        return ExchangeService.post(f.player, data, item, buy, price, qty, 0);
    }

    static ExchangeService.Outcome posted(GameTestHelper helper, Fixture f, ExchangeData data, Item item, boolean buy, long price, int qty) {
        ExchangeService.Outcome outcome = post(f, data, item, buy, price, qty);
        helper.assertValueEqual(outcome.result(), Result.OK, "posting " + (buy ? "buy " : "sell ") + qty + " × " + id(item) + " at " + price);
        return outcome;
    }

    // ---- Fixture -----------------------------------------------------------------------------------------------

    /**
     * A fresh player with an empty inventory and a new, empty account in the world's bank (Numismatics' starter coins
     * default to 0).
     */
    static final class Fixture {
        final GameTestHelper helper;
        final ServerPlayer player;
        final MarketStockData market;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            this.player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    new GameProfile(UUID.randomUUID(), "market-test"), ClientInformation.createDefault());
            this.market = MarketStockData.get(helper.getLevel().getServer());
            helper.assertTrue(WorldBank.available(player.server), "the world's bank must be reachable");
            // Every test starts from base prices; tests share the market but each runs within a single tick.
            market.resetAllFactors();
            int starting = balance();
            if (starting > 0) {
                account().deduct(starting);
            }
        }

        Bank bank() {
            return WorldBank.of(player.server);
        }

        Bank.Account account() {
            return bank().account(player).orElseThrow();
        }

        /** Test balances stay well within an int. */
        int balance() {
            return Math.toIntExact(account().balance());
        }

        void deposit(int spurs) {
            account().deposit(spurs);
        }

        void give(ItemStack stack) {
            NonNullList<ItemStack> items = player.getInventory().items;
            for (int slot = 0; slot < items.size(); slot++) {
                if (items.get(slot).isEmpty()) {
                    items.set(slot, stack);
                    return;
                }
            }
            throw new IllegalStateException("Test inventory is full");
        }

        void giveMany(Item item, int count) {
            for (int left = count; left > 0; left -= 64) {
                give(new ItemStack(item, Math.min(64, left)));
            }
        }

        void clearInventory() {
            player.getInventory().items.clear();
        }

        double factor(Item item) {
            CatalogEntry entry = MarketCatalog.INSTANCE.get(item).orElseThrow();
            return MarketService.currentFactor(entry, PriceModel.inputs(entry), market, MarketStockData.now(player.server));
        }

        MarketService.Quote quote(Item item) {
            return MarketService.quote(player.server, item).orElseThrow();
        }

        long count(Item item) {
            return player.getInventory().items.stream()
                    .filter(s -> ItemStack.isSameItemSameComponents(s, new ItemStack(item)))
                    .mapToLong(ItemStack::getCount).sum();
        }

        long stock(Item item) {
            return market.getStock(BuiltInRegistries.ITEM.getKey(item));
        }

        void setStock(Item item, long amount) {
            market.setStock(BuiltInRegistries.ITEM.getKey(item), amount);
        }

        void assertResult(TradeResult result, Result expected) {
            helper.assertValueEqual(result.result(), expected, "trade result");
        }

        /** A rejected trade must leave balance, inventory, stock, price factors, history and money counts as they were. */
        /** A bank request that must be refused and change nothing: balance, main inventory or offhand. */
        void assertRejectedUnchanged(BankService.Result expected, java.util.function.Supplier<BankService.Outcome> request) {
            List<ItemStack> before = new ArrayList<>();
            player.getInventory().items.forEach(s -> before.add(s.copy()));
            ItemStack offhandBefore = player.getInventory().offhand.get(0).copy();
            int balanceBefore = balance();
            BankService.Outcome outcome = request.get();
            helper.assertValueEqual(outcome.result(), expected, "bank result");
            helper.assertValueEqual(outcome.amount(), 0L, "nothing moved");
            helper.assertValueEqual(balance(), balanceBefore, "balance after " + expected);
            NonNullList<ItemStack> after = player.getInventory().items;
            for (int slot = 0; slot < after.size(); slot++) {
                helper.assertTrue(ItemStack.matches(after.get(slot), before.get(slot)), "inventory slot " + slot + " changed after " + expected);
            }
            helper.assertTrue(ItemStack.matches(player.getInventory().offhand.get(0), offhandBefore), "offhand changed after " + expected);
        }

        void assertRejectedUnchanged(Result expected, java.util.function.Supplier<TradeResult> trade) {
            List<ItemStack> inventoryBefore = new ArrayList<>();
            player.getInventory().items.forEach(s -> inventoryBefore.add(s.copy()));
            Map<ResourceLocation, Long> stockBefore = Map.copyOf(market.view());
            Map<ResourceLocation, MarketStockData.Factor> factorsBefore = Map.copyOf(market.factorsView());
            Map<ResourceLocation, MarketStockData.History> historyBefore = Map.copyOf(market.historyView());
            Map<ResourceLocation, MarketStockData.Totals> itemTotalsBefore = Map.copyOf(market.itemTotalsView());
            MarketStockData.Totals totalsBefore = market.totals();
            int balanceBefore = balance();

            assertResult(trade.get(), expected);

            helper.assertValueEqual(balance(), balanceBefore, "balance after " + expected);
            helper.assertValueEqual(Map.copyOf(market.view()), stockBefore, "stock after " + expected);
            helper.assertValueEqual(Map.copyOf(market.factorsView()), factorsBefore, "price factors after " + expected);
            helper.assertValueEqual(Map.copyOf(market.historyView()), historyBefore, "price history after " + expected);
            helper.assertValueEqual(Map.copyOf(market.itemTotalsView()), itemTotalsBefore, "item money counts after " + expected);
            helper.assertValueEqual(market.totals(), totalsBefore, "money counts after " + expected);
            NonNullList<ItemStack> after = player.getInventory().items;
            for (int slot = 0; slot < after.size(); slot++) {
                helper.assertTrue(ItemStack.matches(after.get(slot), inventoryBefore.get(slot)),
                        "inventory slot " + slot + " changed after " + expected);
            }
        }
    }
}
