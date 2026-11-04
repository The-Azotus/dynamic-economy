package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.bank.BankKind;
import dynamiceconomy.bank.BankTeam;
import dynamiceconomy.bank.BankTeams;
import dynamiceconomy.bank.OwnTeams;
import dynamiceconomy.bank.OwnBank;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.data.OwedPayouts;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.catalog.ItemClasses;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.service.ContractPricing;
import dynamiceconomy.service.ContractService;
import dynamiceconomy.service.PayoutService;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.MarketVisibility;
import dynamiceconomy.service.MarketService.Result;
import dynamiceconomy.service.PriceModel;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

import static dynamiceconomy.gametest.GameTestSupport.*;

/**
 * The contract board.
 *
 * Shared setup is in {@link GameTestSupport}.
 */
@GameTestHolder(DynamicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ContractTests {
    private ContractTests() {
    }

    // ---- Contracts ---------------------------------------------------------------------------------------------

    /**
     * An open contract for {@code units} of a listed item, offering {@code unitReward} each before the cap, with its
     * promised reward worked out as a board would. Tests use boards of their own, never the world's.
     */
    private static ContractBoard.Contract openContract(GameTestHelper helper, ContractBoard board, Item item, int units,
                                                      BigDecimal unitReward, long deposit) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        CatalogEntry entry = MarketCatalog.INSTANCE.get(item).orElseThrow();
        PriceModel.Inputs inputs = PriceModel.inputs(entry);
        double factor = MarketService.currentFactor(entry, inputs, MarketStockData.get(server), MarketStockData.now(server));
        long reward = ContractPricing.payout(unitReward, ContractPricing.lowestUnitBuyPrice(entry, inputs, factor), units);
        return new ContractBoard.Contract(board.takeId(), id(item), units, unitReward, reward, deposit, 0, Optional.empty(),
                Optional.empty(), ContractBoard.Status.OPEN);
    }

    @GameTest(template = EMPTY)
    public static void onlyOnePlayerCanClaimAContract(GameTestHelper helper) {
        Fixture first = new Fixture(helper);
        Fixture second = new Fixture(helper);
        first.deposit(1000);
        second.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(
                openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32),
                openContract(helper, b, Items.COBBLESTONE, 64, new BigDecimal("1.25"), 16)));
        int logs = board.contracts().get(0).id();
        int cobble = board.contracts().get(1).id();

        ContractService.Outcome claimed = ContractService.claim(first.player, board, logs);
        helper.assertValueEqual(claimed.result(), Result.OK, "first claim");
        helper.assertValueEqual(claimed.amount(), 32L, "the deposit taken");
        helper.assertValueEqual(first.balance(), 1000 - 32, "first player's balance after the deposit");
        helper.assertValueEqual(contract(board, logs).holder(), Optional.of(first.player.getUUID()), "locked to the first player");

        helper.assertValueEqual(ContractService.claim(second.player, board, logs).result(), Result.CONTRACT_TAKEN, "second claim");
        helper.assertValueEqual(second.balance(), 1000, "a refused claim takes nothing");
        helper.assertValueEqual(ContractService.claim(first.player, board, logs).result(), Result.CONTRACT_TAKEN, "claiming it again");
        helper.assertValueEqual(first.balance(), 1000 - 32, "and that takes nothing either");
        helper.assertValueEqual(ContractService.deliver(second.player, board, logs, 1).result(), Result.NOT_YOUR_CONTRACT, "others can't deliver");
        helper.assertValueEqual(ContractService.abandon(second.player, board, logs).result(), Result.NOT_YOUR_CONTRACT, "or abandon it");

        // One active contract each by default.
        helper.assertValueEqual(ContractService.claim(first.player, board, cobble).result(), Result.CONTRACT_LIMIT, "over the limit");
        helper.assertValueEqual(first.balance(), 1000 - 32, "a refused claim takes nothing");
        helper.assertValueEqual(ContractService.claim(second.player, board, cobble).result(), Result.OK, "the other contract is still open");
        helper.assertValueEqual(ContractService.claim(first.player, board, 999_999).result(), Result.CONTRACT_EXPIRED, "an unknown contract");

        Fixture broke = new Fixture(helper);
        ContractBoard other = board(helper, b -> List.of(openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32)));
        helper.assertValueEqual(ContractService.claim(broke.player, other, other.contracts().get(0).id()).result(), Result.NO_FUNDS,
                "no money for the deposit");
        helper.assertValueEqual(other.contracts().get(0).status(), ContractBoard.Status.OPEN, "and it stays open");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void partialAndFullDeliveryPayExactly(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.deposit(1000);
        // Oak logs: base 2, unlimited, markup 0.5, so the market never sells one for less than 3. 2.5 each is below that.
        ContractBoard board = board(helper, b -> List.of(openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32)));
        int id = board.contracts().get(0).id();
        helper.assertValueEqual(board.contracts().get(0).reward(), 160L, "64 × 2.5");
        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 1).result(), Result.NOT_YOUR_CONTRACT, "claim first");
        helper.assertValueEqual(ContractService.claim(f.player, board, id).result(), Result.OK, "claimed");

        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 1).result(), Result.NOT_ENOUGH_ITEMS, "none held");
        f.player.getInventory().offhand.set(0, new ItemStack(Items.OAK_LOG, 64));
        ItemStack renamed = new ItemStack(Items.OAK_LOG, 64);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Special"));
        f.give(renamed);
        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 1).result(), Result.NOT_ENOUGH_ITEMS,
                "offhand and renamed copies don't count");
        f.giveMany(Items.OAK_LOG, 64);
        long stockBefore = f.stock(Items.OAK_LOG);

        ContractService.Outcome part = ContractService.deliver(f.player, board, id, 40);
        helper.assertValueEqual(part.result(), Result.OK, "first batch");
        helper.assertValueEqual(part.amount(), 0L, "a batch pays nothing until the last unit");
        helper.assertValueEqual(f.balance(), 1000 - 32, "balance after the first batch");
        helper.assertValueEqual(f.count(Items.OAK_LOG), 24L, "40 handed over");
        helper.assertValueEqual(contract(board, id).delivered(), 40, "counted");
        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 25).result(), Result.CONTRACT_FULL, "more than is left");
        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 0).result(), Result.INVALID_QUANTITY, "nothing");
        helper.assertValueEqual(f.count(Items.OAK_LOG), 24L, "refusals take nothing");

        ContractService.Outcome last = ContractService.deliver(f.player, board, id, 24);
        helper.assertValueEqual(last.result(), Result.OK, "last batch");
        helper.assertValueEqual(last.amount(), 160L + 32, "the reward plus the deposit back");
        helper.assertValueEqual(f.balance(), 1000 + 160, "net: exactly the reward");
        helper.assertValueEqual(f.count(Items.OAK_LOG), 0L, "every plain log in the main inventory went");
        helper.assertTrue(f.player.getInventory().offhand.get(0).getCount() == 64, "the offhand is untouched");
        helper.assertValueEqual(f.stock(Items.OAK_LOG), stockBefore, "delivered items don't join market stock");
        helper.assertValueEqual(contract(board, id).status(), ContractBoard.Status.COMPLETED, "done");
        helper.assertValueEqual(board.rewardsPaid(), 160L, "counted as paid out");
        helper.assertValueEqual(ContractService.deliver(f.player, board, id, 1).result(), Result.CONTRACT_TAKEN, "nothing more to deliver");
        helper.assertValueEqual(ContractService.abandon(f.player, board, id).result(), Result.CONTRACT_TAKEN, "or abandon");
        f.player.getInventory().offhand.set(0, ItemStack.EMPTY);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void abandoningOrExpiryPaysForDeliveredUnitsAndKeepsTheDeposit(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        Fixture quitter = new Fixture(helper);
        quitter.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32)));
        int id = board.contracts().get(0).id();
        ContractService.claim(quitter.player, board, id);
        quitter.giveMany(Items.OAK_LOG, 10);
        ContractService.deliver(quitter.player, board, id, 10);
        ContractService.Outcome gaveUp = ContractService.abandon(quitter.player, board, id);
        helper.assertValueEqual(gaveUp.result(), Result.OK, "abandoned");
        helper.assertValueEqual(gaveUp.amount(), 25L, "10 × 2.5");
        helper.assertValueEqual(quitter.balance(), 1000 - 32 + 25, "paid for 10; the deposit is lost");
        helper.assertValueEqual(contract(board, id).status(), ContractBoard.Status.ABANDONED, "closed");
        helper.assertValueEqual(board.depositsLost(), 32L, "the deposit counted as kept");
        helper.assertValueEqual(ContractService.claim(quitter.player, board, id).result(), Result.CONTRACT_TAKEN, "can't be claimed again");

        Fixture late = new Fixture(helper);
        late.deposit(1000);
        ContractBoard expiring = board(helper, b -> List.of(openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32)));
        int lateId = expiring.contracts().get(0).id();
        ContractService.claim(late.player, expiring, lateId);
        late.giveMany(Items.OAK_LOG, 10);
        ContractService.deliver(late.player, expiring, lateId, 10);
        expiring.observe(ContractBoard.worldDay(server) + 5);
        helper.assertTrue(expiring.due(), "five days later the board is due");
        ContractService.refresh(server, expiring, net.minecraft.util.RandomSource.create(7));
        helper.assertValueEqual(late.balance(), 1000 - 32 + 25, "paid for 10 at expiry, offline or not; the deposit is lost");
        List<ContractBoard.Notice> notices = expiring.takeNotices(late.player.getUUID());
        helper.assertValueEqual(notices, List.of(new ContractBoard.Notice(late.player.getUUID(), id(Items.OAK_LOG), 10, 64, 25, 32)),
                "an offline holder is told when they next log in");
        helper.assertValueEqual(expiring.takeNotices(late.player.getUUID()), List.of(), "once");
        helper.assertTrue(expiring.find(lateId).isEmpty(), "the old contract is gone");
        helper.assertValueEqual(ContractService.deliver(late.player, expiring, lateId, 1).result(), Result.CONTRACT_EXPIRED, "and can't be delivered to");
        helper.assertValueEqual(expiring.claimedBy(late.player.getUUID()), 0, "nothing held on the new board");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void boardExpiresOnTheRightDayAfterSleepingAndARestart(GameTestHelper helper) {
        // The day count, fed by hand: sleeping jumps days, setting the time back doesn't count.
        ContractBoard board = new ContractBoard();
        board.observe(10);
        board.replace(List.of(), 5);
        helper.assertValueEqual(board.expiresDay(), 15L, "boards turn over at multiples of 5");
        helper.assertValueEqual(board.daysLeft(), 5L, "5 days left");
        board.observe(12);
        helper.assertValueEqual(board.day(), 12L, "a night slept through counts");
        board.observe(3);
        helper.assertValueEqual(board.day(), 12L, "setting the time back doesn't");
        board.observe(4);
        helper.assertValueEqual(board.day(), 13L, "days after that count again");
        helper.assertFalse(board.due(), "not due on day 13");
        ContractBoard restarted = ContractBoard.fromTag(board.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(restarted.day(), 13L, "the day count survives a restart");
        helper.assertValueEqual(restarted.expiresDay(), 15L, "as does the expiry");
        restarted.observe(5);
        helper.assertFalse(restarted.due(), "day 14");
        restarted.observe(6);
        helper.assertTrue(restarted.due(), "due on day 15");

        // The same with the world's clock, as sleeping moves it; put back before the tick ends.
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        net.minecraft.server.level.ServerLevel overworld = server.overworld();
        long time = overworld.getDayTime();
        Fixture f = new Fixture(helper);
        f.deposit(1000);
        ContractBoard live = board(helper, b -> List.of(
                openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32),
                openContract(helper, b, Items.COBBLESTONE, 64, new BigDecimal("1.25"), 16)));
        long left = live.daysLeft();
        try {
            overworld.setDayTime(time + (left - 1) * 24000L);
            helper.assertValueEqual(ContractService.claim(f.player, live, live.contracts().get(0).id()).result(), Result.OK,
                    "the day before expiry");
            overworld.setDayTime(time + left * 24000L);
            helper.assertValueEqual(ContractService.claim(f.player, live, live.contracts().get(1).id()).result(), Result.CONTRACT_EXPIRED,
                    "on the expiry day, even before the board is replaced");
            helper.assertValueEqual(ContractService.abandon(f.player, live, live.contracts().get(0).id()).result(), Result.CONTRACT_EXPIRED,
                    "the held one too");
        } finally {
            overworld.setDayTime(time);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void buyingFromTheMarketToFillAContractNeverProfits(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        // Unlimited: logs never cost less than 3 each. A contract asking 11 × base each is capped just below.
        Fixture logs = new Fixture(helper);
        logs.deposit(10_000);
        ContractBoard logBoard = board(helper, b -> List.of(openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal(22), 10)));
        int logId = logBoard.contracts().get(0).id();
        ContractService.claim(logs.player, logBoard, logId);
        logs.assertResult(MarketService.tryBuy(logs.player, Items.OAK_LOG, 64, ANY_PRICE), Result.OK);
        helper.assertValueEqual(ContractService.deliver(logs.player, logBoard, logId, 64).result(), Result.OK, "logs delivered");
        helper.assertTrue(logs.balance() < 10_000, "buying logs to fill a contract lost money: " + logs.balance());

        // Finite and dynamic: iron at its price floor, the cheapest it can ever be bought.
        Fixture iron = new Fixture(helper);
        iron.deposit(100_000);
        long stock = iron.stock(Items.IRON_INGOT);
        CatalogEntry ironEntry = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();
        iron.setStock(Items.IRON_INGOT, 1000);
        iron.market.setFactor(id(Items.IRON_INGOT), PriceModel.inputs(ironEntry).floor(), MarketStockData.now(server));
        try {
            ContractBoard ironBoard = board(helper, b -> List.of(openContract(helper, b, Items.IRON_INGOT, 50, new BigDecimal(176), 10)));
            int ironId = ironBoard.contracts().get(0).id();
            ContractService.claim(iron.player, ironBoard, ironId);
            iron.assertResult(MarketService.tryBuy(iron.player, Items.IRON_INGOT, 50, ANY_PRICE), Result.OK);
            helper.assertValueEqual(ContractService.deliver(iron.player, ironBoard, ironId, 50).result(), Result.OK, "iron delivered");
            helper.assertTrue(iron.balance() < 100_000, "buying iron at its floor to fill a contract lost money: " + iron.balance());
        } finally {
            iron.setStock(Items.IRON_INGOT, stock);
            iron.market.resetAllFactors();
        }

        // A fixed price: the cap sits one bit under the market's price.
        withFixedPrice(Items.IRON_INGOT, () -> {
            Fixture fixed = new Fixture(helper);
            fixed.deposit(100_000);
            fixed.setStock(Items.IRON_INGOT, 1000);
            try {
                ContractBoard fixedBoard = board(helper, b -> List.of(openContract(helper, b, Items.IRON_INGOT, 50, new BigDecimal(176), 10)));
                int fixedId = fixedBoard.contracts().get(0).id();
                ContractService.claim(fixed.player, fixedBoard, fixedId);
                fixed.assertResult(MarketService.tryBuy(fixed.player, Items.IRON_INGOT, 50, ANY_PRICE), Result.OK);
                helper.assertValueEqual(ContractService.deliver(fixed.player, fixedBoard, fixedId, 50).result(), Result.OK, "delivered");
                helper.assertValueEqual(fixed.balance(), 100_000 - 1, "one bit short of breaking even");
            } finally {
                fixed.setStock(Items.IRON_INGOT, stock);
            }
        });

        // Every shape of item, every factor it can trade at, every order size: the payout is below the purchase.
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(15);
        for (int i = 0; i < 400; i++) {
            boolean unlimited = random.nextBoolean();
            boolean dynamic = random.nextInt(4) != 0;
            double markup = random.nextInt(300) / 100.0;
            Optional<Double> minBuyMarkup = unlimited && markup > 0 && random.nextBoolean()
                    ? Optional.of(Math.max(0.01, markup * random.nextDouble())) : Optional.empty();
            double floor = 0.01 + random.nextDouble() * 0.99;
            double ceiling = 1 + random.nextDouble() * 9;
            int base = 1 + random.nextInt(random.nextBoolean() ? 10 : 5000);
            CatalogEntry entry = new CatalogEntry(Items.IRON_INGOT, id(Items.IRON_INGOT), base, markup, 0, true, true, unlimited,
                    0.0, dynamic, minBuyMarkup, new CatalogEntry.Overrides(Optional.of(0.003), Optional.of(0.003), Optional.empty(),
                    Optional.of(floor), Optional.of(ceiling)), Optional.empty());
            PriceModel.Inputs in = PriceModel.inputs(entry);
            double stored = floor + random.nextDouble() * (ceiling - floor);
            double factor = PriceModel.effectiveFactor(in, stored);
            Optional<BigDecimal> lowest = ContractPricing.lowestUnitBuyPrice(entry, in, factor);
            BigDecimal greedy = BigDecimal.valueOf(base).multiply(BigDecimal.valueOf(100));
            for (int units : List.of(1, 2, 7, 64, 333)) {
                long paid = ContractPricing.payout(greedy, lowest, units);
                long cost = PriceModel.buy(in, factor, units).total();
                if (paid >= cost) {
                    helper.fail("contract pays %d for %d units that cost %d to buy (%s, factor %s)".formatted(paid, units, cost, in, factor));
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void boardsOfferOnlyItemsWorthAContract(GameTestHelper helper) {
        CatalogEntry logs = MarketCatalog.INSTANCE.get(Items.OAK_LOG).orElseThrow();
        CatalogEntry cobble = MarketCatalog.INSTANCE.get(Items.COBBLESTONE).orElseThrow()
                .withContract(new CatalogEntry.ContractTerms(Optional.of(0), Optional.empty(), Optional.empty(), Optional.empty()));
        CatalogEntry star = MarketCatalog.INSTANCE.get(Items.NETHER_STAR).orElseThrow();
        CatalogEntry iron = MarketCatalog.INSTANCE.get(Items.IRON_INGOT).orElseThrow();
        CatalogEntry diamond = new CatalogEntry(Items.DIAMOND, id(Items.DIAMOND), 256, 0.5, 0, true, false, false, 0.0, true,
                Optional.empty(), CatalogEntry.Overrides.NONE, Optional.empty())
                .withContract(new CatalogEntry.ContractTerms(Optional.of(5), Optional.of(4), Optional.of(16), Optional.of(0.5)));
        List<CatalogEntry> entries = List.of(logs, cobble, star, iron, diamond);
        MarketStockData market = MarketStockData.get(helper.getLevel().getServer());
        market.resetAllFactors();
        long now = MarketStockData.now(helper.getLevel().getServer());

        java.util.Set<ResourceLocation> seen = new java.util.HashSet<>();
        int[] ids = {1};
        for (int seed = 0; seed < 40; seed++) {
            List<ContractBoard.Contract> picked = ContractService.generate(entries, market, now,
                    net.minecraft.util.RandomSource.create(seed), 8, 0.2, () -> ids[0]++);
            helper.assertValueEqual(picked.stream().map(ContractBoard.Contract::item).distinct().count(), (long) picked.size(),
                    "no item twice on one board");
            for (ContractBoard.Contract c : picked) {
                seen.add(c.item());
                CatalogEntry entry = entries.stream().filter(e -> e.itemId().equals(c.item())).findFirst().orElseThrow();
                PriceModel.Inputs in = PriceModel.inputs(entry);
                helper.assertTrue(c.reward() > PriceModel.sell(in, 1.0, c.units()).total(), "pays more than selling: " + c);
                helper.assertValueEqual(c.deposit(), ContractPricing.deposit(c.reward(), 0.2), "deposit is a fifth of the reward");
                helper.assertTrue(c.reward() + c.deposit() <= Integer.MAX_VALUE, "fits one payment");
                helper.assertTrue(c.units() >= entry.contract().effectiveMinSize() && c.units() <= entry.contract().effectiveMaxSize(),
                        "size within the item's range: " + c);
                helper.assertValueEqual(c.status(), ContractBoard.Status.OPEN, "starts open");
            }
        }
        helper.assertValueEqual(seen, java.util.Set.of(id(Items.OAK_LOG), id(Items.DIAMOND)),
                "only logs (unlimited) and the diamond the market doesn't sell are worth a contract");
        helper.assertTrue(ContractPricing.whyNotOffered(cobble, PriceModel.inputs(cobble), 1.0, 0.2).orElse("").contains("contract_weight is 0"),
                "weight 0 is the reason given");
        helper.assertTrue(ContractPricing.whyNotOffered(star, PriceModel.inputs(star), 1.0, 0.2).orElse("").contains("can_sell"),
                "so is can_sell false");
        helper.assertTrue(ContractPricing.whyNotOffered(iron, PriceModel.inputs(iron), 1.0, 0.2).orElse("").contains("as little as"),
                "and a buy price that can fall too low");

        CatalogEntry unique = loadOne("""
                { "entries": [ { "item": "minecraft:nether_star", "base_price": 8192, "class": "unique" } ] }""")
                .entries().get(Items.NETHER_STAR);
        helper.assertValueEqual(unique.contract().effectiveWeight(), 0, "the unique class is kept off the board");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void lockedItemsContractsAreHiddenButAHeldOneStaysDeliverable(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(
                openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32),
                openContract(helper, b, Items.COBBLESTONE, 64, new BigDecimal("1.25"), 16)));
        int logs = board.contracts().get(0).id();
        MarketVisibility.setTestLocks((player, item) -> player == f.player && item == Items.OAK_LOG);
        try {
            helper.assertValueEqual(ContractService.view(f.player, board).stream().map(v -> v.contract().item()).toList(),
                    List.of(id(Items.COBBLESTONE)), "the locked item's contract is hidden");
            helper.assertValueEqual(ContractService.claim(f.player, board, logs).result(), Result.NOT_LISTED, "and can't be claimed");
            helper.assertValueEqual(f.balance(), 1000, "nothing taken");
        } finally {
            MarketVisibility.setTestLocks(null);
        }
        helper.assertValueEqual(ContractService.claim(f.player, board, logs).result(), Result.OK, "claimed while unlocked");
        f.giveMany(Items.OAK_LOG, 64);
        MarketVisibility.setTestLocks((player, item) -> player == f.player && item == Items.OAK_LOG);
        try {
            helper.assertTrue(ContractService.view(f.player, board).stream().anyMatch(v -> v.contract().id() == logs && v.mine()),
                    "a held contract stays in view after the stage is lost");
            helper.assertValueEqual(ContractService.deliver(f.player, board, logs, 64).result(), Result.OK, "and can be delivered");
        } finally {
            MarketVisibility.setTestLocks(null);
        }
        helper.assertValueEqual(f.balance(), 1000 + 160, "paid in full");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void contractSettingsLoadFromClassesAndEntries(GameTestHelper helper) {
        for (String field : List.of("contract_weight", "contract_min_size", "contract_max_size", "contract_premium")) {
            helper.assertTrue(ItemClasses.FIELDS.contains(field), "classes may set " + field);
        }
        MarketCatalog.Loaded loaded = loadOne("""
                { "entries": [
                  { "item": "minecraft:oak_log", "base_price": 2, "contract_weight": 3, "contract_min_size": 8,
                    "contract_max_size": 32, "contract_premium": 0.5 },
                  { "item": "minecraft:cobblestone", "base_price": 1, "contract_min_size": 9, "contract_max_size": 8 },
                  { "item": "minecraft:diamond", "base_price": 1, "contract_weight": -1 },
                  { "item": "minecraft:iron_ingot", "base_price": 16, "class": "farmable" }
                ] }""");
        CatalogEntry logs = loaded.entries().get(Items.OAK_LOG);
        helper.assertValueEqual(logs.contract(), new CatalogEntry.ContractTerms(Optional.of(3), Optional.of(8), Optional.of(32),
                Optional.of(0.5)), "entry fields");
        helper.assertValueEqual(ContractPricing.unitReward(logs), new BigDecimal("3.0"), "2 × 1.5 per unit");
        helper.assertFalse(loaded.entries().containsKey(Items.COBBLESTONE), "min above max is skipped");
        helper.assertFalse(loaded.entries().containsKey(Items.DIAMOND), "a negative weight is skipped");
        helper.assertTrue(loaded.problems().stream().anyMatch(p -> p.message().contains("contract_min_size")), "and reported");
        CatalogEntry iron = loaded.entries().get(Items.IRON_INGOT);
        helper.assertValueEqual(iron.contract().effectiveMinSize(), 64, "the class's size");
        helper.assertValueEqual(iron.contract().effectiveMaxSize(), 512, "both ends");
        helper.assertValueEqual(iron.contract().effectivePremium(), MarketConfig.contractPremium(), "and the config's premium");
        JsonObject json = dynamiceconomy.catalog.CatalogJson.toJson(logs);
        helper.assertValueEqual(json.get("contract_premium").getAsDouble(), 0.5, "written back out");
        helper.assertValueEqual(MarketCatalog.decode(json).result().map(CatalogEntry::contract), Optional.of(logs.contract()),
                "and read back the same");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void contractBoardSurvivesASave(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.deposit(1000);
        ContractBoard board = board(helper, b -> List.of(
                openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32),
                openContract(helper, b, Items.COBBLESTONE, 48, new BigDecimal("1.25"), 12)));
        ContractService.claim(f.player, board, board.contracts().get(0).id());
        f.giveMany(Items.OAK_LOG, 5);
        ContractService.deliver(f.player, board, board.contracts().get(0).id(), 5);
        board.addNotice(new ContractBoard.Notice(f.player.getUUID(), id(Items.IRON_INGOT), 3, 10, 30, 6));
        board.owed().owe(new OwedPayouts.Payout(f.player.getUUID(), Optional.of(UUID.randomUUID()), 77));
        ContractBoard loaded = ContractBoard.fromTag(board.save(new CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.contracts(), board.contracts(), "contracts, holders and progress");
        helper.assertValueEqual(loaded.owed().list(), board.owed().list(), "money owed");
        helper.assertValueEqual(loaded.takeNotices(f.player.getUUID()), board.takeNotices(f.player.getUUID()), "notices");
        helper.assertTrue(loaded.takeId() > board.contracts().get(1).id(), "IDs never repeat");
        helper.succeed();
    }

    /** After a payment fails, owed money waits before it's tried again, rather than asking the bank every tick. */
    @GameTest(template = EMPTY)
    public static void owedPayoutsWaitBeforeTryingAgain(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        Fixture f = new Fixture(helper);
        ContractBoard board = new ContractBoard();
        board.owed().owe(new OwedPayouts.Payout(f.player.getUUID(), Optional.empty(), 25));
        long tick = server.getTickCount();
        PayoutService.payOwedForTesting(server, board.owed(), (player, team) -> Optional.empty());
        helper.assertValueEqual(board.owed().size(), 1, "the bank couldn't be reached, so it's still owed");
        helper.assertTrue(!board.owed().retryDue(tick) && board.owed().retryDue(tick + OwedPayouts.RETRY_TICKS),
                "and the failure set the wait");
        PayoutService.payOwed(server, board.owed());
        helper.assertValueEqual(board.owed().size(), 1, "not tried again yet, though the bank is back");
        helper.assertValueEqual(f.balance(), 0, "nothing paid");
        board.owed().retryNowForTesting();
        PayoutService.payOwed(server, board.owed());
        helper.assertTrue(board.owed().isEmpty(), "paid once it's due");
        helper.assertValueEqual(f.balance(), 25, "into the player's account");
        helper.succeed();
    }

    /** A contract saved with a reward per unit that can't be read keeps working; one with an unknown status is dropped. */
    @GameTest(template = EMPTY)
    public static void unreadableSavedContractsAreRecoveredOrReported(GameTestHelper helper) {
        // 100 for 3 units doesn't divide evenly, so a reward per unit rounded the wrong way would pay 99.
        ContractBoard board = board(helper, b -> List.of(
                new ContractBoard.Contract(b.takeId(), id(Items.OAK_LOG), 3, new BigDecimal("40"), 100, 10, 0, Optional.empty(),
                        Optional.empty(), ContractBoard.Status.OPEN),
                openContract(helper, b, Items.COBBLESTONE, 48, new BigDecimal("1.25"), 12)));
        CompoundTag saved = board.save(new CompoundTag(), helper.getLevel().registryAccess());
        net.minecraft.nbt.ListTag list = saved.getList("contracts", net.minecraft.nbt.Tag.TAG_COMPOUND);
        list.getCompound(0).putString("unit_reward", "not a number");
        list.getCompound(1).putString("status", "from_a_newer_version");
        ContractBoard loaded = ContractBoard.fromTag(saved, helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.contracts().size(), 1, "only the contract with an unknown status is dropped");
        ContractBoard.Contract kept = loaded.contracts().get(0);
        ContractBoard.Contract original = board.contracts().get(0);
        helper.assertValueEqual(kept.id(), original.id(), "the one with the unreadable reward per unit is kept");
        helper.assertValueEqual(ContractPricing.payout(kept.unitReward(), Optional.empty(), kept.units()), original.reward(),
                "completing it pays the whole reward it offered");
        helper.assertValueEqual(ContractPricing.payout(kept.unitReward(), Optional.empty(), 1), 33L, "one unit pays a third, rounded down");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void contractMoneyGoesBackToTheTeamThatPaidTheDeposit(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        if (WorldBank.kind(server) != BankKind.OWN || BankTeams.fromFtbTeams()) {
            // Team accounts need the mod's own bank and its own teams: the run without Numismatics and FTB Teams.
            DynamicEconomy.LOGGER.info("GameTest skipped: it needs the mod's own bank and teams");
            helper.succeed();
            return;
        }
        Fixture f = new Fixture(helper);
        UUID player = f.player.getUUID();
        OwnTeams teams = OwnTeams.get(server);
        BankTeam team = teams.create("c" + UUID.randomUUID().toString().substring(0, 8), player);
        OwnBank bank = OwnBank.get(server);
        bank.teamAccount(team.id()).deposit(1000);
        bank.payFromTeam(f.player, true);
        try {
            ContractBoard board = board(helper, b -> List.of(
                    openContract(helper, b, Items.OAK_LOG, 64, new BigDecimal("2.5"), 32),
                    openContract(helper, b, Items.COBBLESTONE, 64, new BigDecimal("1.25"), 16)));
            int logs = board.contracts().get(0).id();
            helper.assertValueEqual(ContractService.claim(f.player, board, logs).result(), Result.OK, "claimed with the team's money");
            helper.assertValueEqual(bank.teamAccount(team.id()).balance(), 1000L - 32, "the team paid the deposit");
            f.giveMany(Items.OAK_LOG, 64);
            ContractService.deliver(f.player, board, logs, 64);
            helper.assertValueEqual(bank.teamAccount(team.id()).balance(), 1000L + 160, "the reward and deposit went to the team");
            helper.assertValueEqual(f.balance(), 0, "not to the player");

            int cobble = board.contracts().get(1).id();
            ContractService.claim(f.player, board, cobble);
            f.giveMany(Items.COBBLESTONE, 10);
            ContractService.deliver(f.player, board, cobble, 10);
            bank.payFromTeam(f.player, false);
            helper.assertTrue(teams.remove(player).isPresent(), "the team ends");
            helper.assertValueEqual(ContractService.abandon(f.player, board, cobble).amount(), 12L, "10 × 1.25");
            helper.assertValueEqual(f.balance(), 12, "with the team gone, the player is paid");
        } finally {
            bank.payFromTeam(f.player, false);
            teams.remove(player);
            bank.closeTeam(team.id(), player);
            bank.drain(player);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void builtInPricesPutUnlimitedAndUnbuyableItemsOnTheBoardButNoUniqueOnes(GameTestHelper helper) {
        java.util.Map<String, Integer> offered = new java.util.TreeMap<>();
        int total = 0;
        for (CatalogEntry entry : defaultPrices().entries().values()) {
            PriceModel.Inputs in = PriceModel.inputs(entry);
            if (ContractPricing.whyNotOffered(entry, in, 1.0, MarketConfig.depositFraction()).isPresent()) {
                continue;
            }
            String itemClass = entry.itemClass().map(ItemClasses::shortName).orElse("none");
            helper.assertFalse(itemClass.equals("unique"), "unique items are never offered: " + entry.itemId());
            helper.assertTrue(entry.unlimitedStock() || !entry.canBuy() || !entry.dynamic(),
                    "only items whose buy price can't fall far are worth a contract: " + entry.itemId());
            offered.merge(itemClass, 1, Integer::sum);
            total++;
        }
        DynamicEconomy.LOGGER.info("Built-in prices loaded here: {} items can appear on a contract board, by class {}", total, offered);
        helper.assertTrue(total > 0, "some built-in items can be offered");
        helper.succeed();
    }
}
