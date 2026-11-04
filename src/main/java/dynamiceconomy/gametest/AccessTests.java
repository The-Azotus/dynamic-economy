package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.MarketAccess;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.BankKind;
import dynamiceconomy.block.MarketBlocks;
import dynamiceconomy.block.MarketTerminalBlock;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.data.ExchangeData;
import dynamiceconomy.data.OwedPayouts;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.compat.CreateRecipes;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.compat.StagesBridge;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.ExchangeService;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.Leaderboard;
import dynamiceconomy.service.PayoutService;
import dynamiceconomy.service.MarketVisibility;
import dynamiceconomy.service.MarketService.Result;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Function;

import static dynamiceconomy.gametest.GameTestSupport.*;

/**
 * Who sees what: access modes and the terminal, stage locks and the leaderboard.
 *
 * Shared setup is in {@link GameTestSupport}.
 */
@GameTestHolder(DynamicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AccessTests {
    private AccessTests() {
    }

    // ---- Access and the terminal -------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void blockOnlyModeKeepsCommandsForOperators(GameTestHelper helper) {
        helper.assertTrue(MarketAccess.commandsAllowed(MarketConfig.AccessMode.COMMAND_AND_BLOCK, false), "everyone, by default");
        helper.assertTrue(!MarketAccess.commandsAllowed(MarketConfig.AccessMode.BLOCK_ONLY, false), "players need the block");
        helper.assertTrue(MarketAccess.commandsAllowed(MarketConfig.AccessMode.BLOCK_ONLY, true), "operators keep commands");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void terminalScreenClosesWhenYouWalkAwayOrItBreaks(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        net.minecraft.core.BlockPos pos = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        helper.setBlock(net.minecraft.core.BlockPos.ZERO, MarketBlocks.MARKET_TERMINAL.get().defaultBlockState());
        MarketMenu menu = MarketMenu.forTerminal(f.player, pos);

        f.player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 3.5);
        helper.assertTrue(menu.stillValid(f.player), "valid next to the terminal");
        f.player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 20.5);
        helper.assertTrue(!menu.stillValid(f.player), "closes 20 blocks away");
        f.player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 3.5);
        helper.setBlock(net.minecraft.core.BlockPos.ZERO, Blocks.AIR);
        helper.assertTrue(!menu.stillValid(f.player), "closes when the terminal is broken");
        helper.assertTrue(MarketMenu.forCommand().stillValid(f.player), "a command-opened screen is valid in the default mode");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void terminalHasRecipeAndFacing(GameTestHelper helper) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(DynamicEconomy.MOD_ID, "market_terminal");
        // Spurs with Numismatics, our Bits without: exactly one of the two recipes loads.
        boolean numismatics = net.neoforged.fml.ModList.get().isLoaded(NumismaticsBridge.MOD_ID);
        var recipes = helper.getLevel().getServer().getRecipeManager();
        helper.assertValueEqual(recipes.byKey(id).isPresent(), numismatics, "the spur recipe loads with Numismatics");
        helper.assertValueEqual(recipes.byKey(id.withSuffix("_from_bits")).isPresent(), !numismatics, "the Bit recipe without it");
        helper.setBlock(net.minecraft.core.BlockPos.ZERO, MarketBlocks.MARKET_TERMINAL.get().defaultBlockState()
                .setValue(MarketTerminalBlock.FACING, net.minecraft.core.Direction.EAST));
        helper.assertBlockProperty(net.minecraft.core.BlockPos.ZERO, MarketTerminalBlock.FACING, net.minecraft.core.Direction.EAST);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void terminalCraftsFromCraftingTableAndSpurs(GameTestHelper helper) {
        if (skippedWithout(helper, NumismaticsBridge.MOD_ID) || skippedWithout(helper, CreateRecipes.MOD_ID)) {
            return;
        }
        Item spur = BuiltInRegistries.ITEM.get(ResourceLocation.parse("numismatics:spur"));
        helper.assertTrue(spur != Items.AIR, "Numismatics spur exists");
        Item t = Items.CRAFTING_TABLE;
        Optional<ItemStack> made = craft(helper, spur, spur, spur, spur, t, spur, spur, spur, spur);
        helper.assertTrue(made.isPresent() && made.get().is(MarketBlocks.MARKET_TERMINAL_ITEM.get()) && made.get().getCount() == 1,
                "a crafting table surrounded by 8 spurs makes one terminal, got " + made);

        Item g = Items.GOLD_INGOT, r = Items.REDSTONE;
        Item cog = BuiltInRegistries.ITEM.get(ResourceLocation.parse("numismatics:cog"));
        Item tube = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:electron_tube"));
        Item casing = BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:brass_casing"));
        Optional<ItemStack> old = craft(helper, g, cog, g, tube, casing, tube, g, r, g);
        helper.assertTrue(old.isEmpty() || !old.get().is(MarketBlocks.MARKET_TERMINAL_ITEM.get()),
                "the old recipe no longer makes a terminal");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void terminalIsATableThatDropsItself(GameTestHelper helper) {
        BlockState state = MarketBlocks.MARKET_TERMINAL.get().defaultBlockState();
        net.minecraft.core.BlockPos pos = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
        helper.setBlock(net.minecraft.core.BlockPos.ZERO, state);
        helper.assertTrue(!state.isCollisionShapeFullBlock(helper.getLevel(), pos), "the table is not a full block");
        helper.assertTrue(!state.canOcclude(), "neighbouring faces still render");
        helper.assertTrue(!state.requiresCorrectToolForDrops(), "breaks by hand");
        helper.assertTrue(state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE), "axes break it faster");
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(state, helper.getLevel(), pos, null);
        helper.assertTrue(drops.size() == 1 && drops.get(0).is(MarketBlocks.MARKET_TERMINAL_ITEM.get()),
                "broken by hand, it drops itself, got " + drops);
        helper.succeed();
    }

    // ---- Stage locks -------------------------------------------------------------------------------------------

    // ProgressiveStages isn't in the test environment; these lock items through MarketVisibility's test hook, which
    // every check consults exactly as it does the real locks.

    @GameTest(template = EMPTY)
    public static void lockedItemIsHiddenAndUntradeableForOnePlayerOnly(GameTestHelper helper) {
        Fixture locked = new Fixture(helper);
        Fixture free = new Fixture(helper);
        locked.setStock(Items.IRON_INGOT, 50);
        locked.deposit(10_000);
        free.deposit(10_000);
        locked.giveMany(Items.IRON_INGOT, 10);
        free.giveMany(Items.IRON_INGOT, 10);
        MarketVisibility.setTestLocks((player, item) -> player == locked.player
                && (item == Items.IRON_INGOT || item == Items.NETHER_STAR));
        try {
            locked.assertRejectedUnchanged(Result.NOT_LISTED, () -> MarketService.trySell(locked.player, Items.IRON_INGOT, 5, ANY_PRICE));
            locked.assertRejectedUnchanged(Result.NOT_LISTED, () -> MarketService.tryBuy(locked.player, Items.IRON_INGOT, 1, ANY_PRICE));
            // A locked item answers as an unlisted one, never with its own reason (the nether star can't be sold).
            locked.assertRejectedUnchanged(Result.NOT_LISTED, () -> MarketService.trySell(locked.player, Items.NETHER_STAR, 1, ANY_PRICE));
            helper.assertTrue(rowIds(MarketSync.catalogFor(locked.player)).stream().noneMatch(id -> id.equals(id(Items.IRON_INGOT))),
                    "locked player's catalog has no iron");
            helper.assertTrue(rowIds(MarketSync.catalogFor(locked.player)).contains(id(Items.DIAMOND)),
                    "locked player still sees unlocked items");

            helper.assertTrue(rowIds(MarketSync.catalogFor(free.player)).contains(id(Items.IRON_INGOT)), "other player sees iron");
            free.assertResult(MarketService.trySell(free.player, Items.IRON_INGOT, 5, ANY_PRICE), Result.OK);
            free.assertResult(MarketService.tryBuy(free.player, Items.IRON_INGOT, 1, ANY_PRICE), Result.OK);
        } finally {
            MarketVisibility.setTestLocks(null);
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void unlockingShowsTheItemAgainAtTheSamePrice(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        f.giveMany(Items.IRON_INGOT, 5);
        MarketPayloads.Row before = MarketSync.catalogFor(f.player).rows().stream()
                .filter(row -> row.item().equals(id(Items.IRON_INGOT))).findFirst().orElseThrow();
        MarketVisibility.setTestLocks((player, item) -> item == Items.IRON_INGOT);
        try {
            helper.assertFalse(rowIds(MarketSync.catalogFor(f.player)).contains(id(Items.IRON_INGOT)), "iron hidden while locked");
        } finally {
            MarketVisibility.setTestLocks(null);
        }
        MarketPayloads.Row after = MarketSync.catalogFor(f.player).rows().stream()
                .filter(row -> row.item().equals(id(Items.IRON_INGOT))).findFirst().orElseThrow();
        helper.assertValueEqual(after, before, "iron row after unlocking (prices are the same for everyone)");
        f.assertResult(MarketService.trySell(f.player, Items.IRON_INGOT, 5, ANY_PRICE), Result.OK);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void withoutProgressiveStagesNothingIsHidden(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        helper.assertFalse(StagesBridge.isAvailable(), "ProgressiveStages is not in the test environment");
        helper.assertFalse(MarketVisibility.active(), "nothing can be hidden");
        helper.assertFalse(StagesBridge.isLocked(f.player, Items.IRON_INGOT), "no item is locked");
        helper.assertValueEqual(MarketSync.catalogFor(f.player).rows().size(), MarketCatalog.INSTANCE.entries().size(),
                "every listed item is sent");
        helper.succeed();
    }

    // ---- Leaderboard -------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void leaderboardRanksRichestFirstAndSkipsWhoItShould(GameTestHelper helper) {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        UUID broke = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        Map<UUID, GameProfile> known = Map.of(alice, new GameProfile(alice, "alice"), bob, new GameProfile(bob, "Bob"),
                carol, new GameProfile(carol, "carol"), broke, new GameProfile(broke, "broke"),
                admin, new GameProfile(admin, "admin"));
        Map<UUID, Long> balances = Map.of(alice, 500L, bob, 900L, carol, 500L, broke, 0L, stranger, 10_000L, admin, 99_999L);

        List<Leaderboard.Entry> ranked = Leaderboard.rank(balances, id -> Optional.ofNullable(known.get(id)),
                profile -> profile.getName().equals("admin"));

        helper.assertValueEqual(ranked.stream().map(Leaderboard.Entry::name).toList(), List.of("Bob", "alice", "carol"),
                "richest first, ties by name; no empty, unknown or excluded accounts");
        helper.assertValueEqual(ranked.get(0).balance(), 900L, "exact balance");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void leaderboardReadsPersonalAccountsAtTheirBankBalance(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        UUID bankerId = UUID.randomUUID();
        // Only Numismatics' bank has shared accounts.
        Optional<Bank.Account> banker = f.bank().kind() == BankKind.NUMISMATICS
                ? NumismaticsBridge.sharedAccountForTest(bankerId) : Optional.empty();
        helper.assertTrue(f.bank().canListAccounts(), "the bank's account list is readable");
        f.deposit(1234);
        banker.ifPresent(account -> account.deposit(999));
        try {
            Map<UUID, Long> balances = f.bank().playerBalances();
            helper.assertValueEqual(balances.get(f.player.getUUID()), (long) f.balance(), "listed balance is the bank balance");
            helper.assertFalse(balances.containsKey(bankerId), "a shared Blaze Banker account is never listed");

            // With only this player known by name, every other account (test leftovers, the banker) stays off.
            Leaderboard.View view = Leaderboard.viewFor(f.player, id -> id.equals(f.player.getUUID())
                    ? Optional.of(f.player.getGameProfile()) : Optional.empty());
            helper.assertValueEqual(view.ranked(), 1, "players ranked");
            helper.assertValueEqual(view.ownRank(), 1, "own rank");
            helper.assertValueEqual(view.top().get(0).id(), f.player.getUUID(), "the player's line");
            helper.assertValueEqual(view.top().get(0).balance(), 1234L, "ranked at the bank balance");
        } finally {
            // The GameTest world persists: leave both accounts empty, which keeps them off every later board.
            f.account().deduct(f.balance());
            banker.ifPresent(account -> account.deduct(Math.toIntExact(account.balance())));
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void leaderboardCountsMoneyTheExchangeHolds(GameTestHelper helper) {
        withExchange(() -> {
            ExchangeData data = new ExchangeData();
            Fixture buyer = new Fixture(helper);
            Fixture seller = new Fixture(helper);
            UUID id = buyer.player.getUUID();
            buyer.deposit(100);
            seller.give(new ItemStack(Items.COBBLESTONE, 5));
            Function<UUID, Optional<GameProfile>> known = uuid -> uuid.equals(id)
                    ? Optional.of(buyer.player.getGameProfile()) : Optional.empty();

            long bid = posted(helper, buyer, data, Items.COBBLESTONE, true, 5, 10).orderId();
            posted(helper, seller, data, Items.COBBLESTONE, false, 50, 5);
            data.owed().owe(new OwedPayouts.Payout(id, Optional.empty(), 7));
            data.owed().owe(new OwedPayouts.Payout(id, Optional.of(UUID.randomUUID()), 1000));
            Predicate<Optional<UUID>> teamTakesIt = Optional::isPresent;
            helper.assertValueEqual(data.heldForPlayers(teamTakesIt), Map.of(id, 57L),
                    "the bid's 50 and the 7 owed; not a sell order's items nor money for a team");
            helper.assertValueEqual(data.heldForPlayers(team -> PayoutService.paysTeam(buyer.player.server, team)), Map.of(id, 1057L),
                    "money for a team that doesn't exist goes to the player, so it counts for them");

            Map<UUID, Long> bank = Map.of(id, (long) buyer.balance());
            List<Leaderboard.Entry> ranked = Leaderboard.rank(Leaderboard.withHeld(bank, data.heldForPlayers(teamTakesIt)), known, p -> false);
            helper.assertValueEqual(ranked.get(0).balance(), 50L + 57L, "the bank's 50 plus what the exchange holds");

            helper.assertValueEqual(ExchangeService.cancel(buyer.player, data, bid).result(), Result.OK, "cancel the bid");
            data.owed().list().forEach(data.owed()::paid);
            bank = Map.of(id, (long) buyer.balance());
            ranked = Leaderboard.rank(Leaderboard.withHeld(bank, data.heldForPlayers(teamTakesIt)), known, p -> false);
            helper.assertValueEqual(ranked.get(0).balance(), 100L, "the same money, now all in the bank");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void leaderboardCountsOwedContractPayouts(GameTestHelper helper) {
        ContractBoard board = new ContractBoard();
        UUID id = UUID.randomUUID();
        board.owed().owe(new OwedPayouts.Payout(id, Optional.empty(), 40));
        board.owed().owe(new OwedPayouts.Payout(id, Optional.of(UUID.randomUUID()), 1000));
        helper.assertValueEqual(board.owed().forPlayers(Optional::isPresent), Map.of(id, 40L), "the player's own 40, not money for a team");
        helper.assertValueEqual(board.owed().forPlayers(team -> PayoutService.paysTeam(helper.getLevel().getServer(), team)),
                Map.of(id, 1040L), "and the 1000 too once its team is gone, as it's paid to the player then");
        helper.assertValueEqual(Leaderboard.withHeld(Map.of(id, 10L), Map.of(id, 5L), board.owed().forPlayers(Optional::isPresent)), Map.of(id, 55L),
                "bank, exchange and owed contract money add up");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void returnedMoneyAndItemsAreToldApart(GameTestHelper helper) {
        withExchange(() -> {
            net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
            ExchangeData data = new ExchangeData();
            Fixture trader = new Fixture(helper);
            trader.deposit(100);
            trader.give(new ItemStack(Items.COBBLESTONE, 4));
            ExchangeService.post(trader.player, data, Items.COBBLESTONE, true, 3, 5, 0);
            ExchangeService.post(trader.player, data, Items.COBBLESTONE, false, 9, 4, 0);
            ExchangeService.closeAll(server, data);
            UUID id = trader.player.getUUID();
            helper.assertValueEqual(data.takeNotices(id), List.of(
                    new ExchangeData.Notice(id, id(Items.COBBLESTONE), ExchangeData.NoticeKind.RETURNED, 0, 15),
                    new ExchangeData.Notice(id, id(Items.COBBLESTONE), ExchangeData.NoticeKind.RETURNED, 4, 0)),
                    "the bid's money and the ask's items, as two notices");
        });
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void expiryIsCheckedOncePerDay(GameTestHelper helper) {
        ExchangeData data = new ExchangeData();
        helper.assertTrue(data.expiryCheckDue(5, 3), "first check");
        helper.assertFalse(data.expiryCheckDue(5, 3), "not again the same day");
        helper.assertTrue(data.expiryCheckDue(5, 2), "again when the setting changes");
        helper.assertTrue(data.expiryCheckDue(6, 2), "and on the next day");
        helper.succeed();
    }

    private static Set<ResourceLocation> rowIds(MarketPayloads.Snapshot snapshot) {
        Set<ResourceLocation> ids = new java.util.HashSet<>();
        snapshot.rows().forEach(row -> ids.add(row.item()));
        return ids;
    }
}
