package dynamiceconomy.gametest;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.BankKind;
import dynamiceconomy.bank.BankSwitch;
import dynamiceconomy.bank.BankTeam;
import dynamiceconomy.bank.BankTeams;
import dynamiceconomy.bank.OwnTeams;
import dynamiceconomy.bank.CoinMath;
import dynamiceconomy.bank.Denomination;
import dynamiceconomy.bank.OwnBank;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.block.MarketBlocks;
import dynamiceconomy.catalog.CatalogOverlay;
import dynamiceconomy.coin.Coin;
import dynamiceconomy.coin.Coins;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.compat.FtbTeamsBridge;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.BankService;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.MarketService.Result;
import dynamiceconomy.service.MarketService.TradeResult;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

import static dynamiceconomy.gametest.GameTestSupport.*;

/**
 * Money: coins, the Bank tab, team accounts and switching banks.
 *
 * Shared setup is in {@link GameTestSupport}.
 */
@GameTestHolder(DynamicEconomy.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BankTests {
    private BankTests() {
    }

    // ---- Coins -------------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void coinsAreWorthEightOfTheOneBelow(GameTestHelper helper) {
        helper.assertValueEqual(Coin.BIT.value(), 1, "a Bit is one bit, the same as a spur");
        for (Coin coin : Coin.values()) {
            coin.next().ifPresent(next -> helper.assertValueEqual(next.value(), coin.value() * 8, next + " is 8 " + coin));
            helper.assertValueEqual(Coins.item(coin).coin(), coin, "registered as " + coin.id());
            helper.assertTrue(Coins.isCoin(Coins.item(coin)), coin + " is a coin");
        }
        helper.assertValueEqual(Coin.CROWN.value(), 4_096, "Crown");
        helper.assertValueEqual(Coin.SUN.value(), 32_768, "Sun");
        helper.assertValueEqual(Coin.OMEGA.value(), 2_097_152, "Omega");
        helper.assertFalse(Coins.isCoin(Items.GOLD_NUGGET), "other items aren't coins");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void coinsCanNeverBeListed(GameTestHelper helper) {
        List<String> ids = new ArrayList<>(List.of("dynamic_economy:bit", "dynamic_economy:omega"));
        if (net.neoforged.fml.ModList.get().isLoaded(NumismaticsBridge.MOD_ID)) {
            ids.addAll(List.of("numismatics:spur", "numismatics:sun"));
        }
        for (String id : ids) {
            MarketCatalog.Loaded loaded = loadOne("{ \"entries\": [ { \"item\": \"" + id + "\", \"base_price\": 1 } ] }");
            helper.assertTrue(loaded.entries().isEmpty(), id + " isn't listed from a file");
            helper.assertTrue(loaded.problems().size() == 1 && loaded.problems().get(0).message().contains("coin"),
                    "and the file says why: " + loaded.problems());
        }

        // Nor by an admin edit or a tag rule that prices it.
        Item bit = Coins.item(Coin.BIT);
        MarketOverrides edits = new MarketOverrides();
        edits.setItemField(key(bit), "base_price", number(1));
        ResourceLocation coinTag = ResourceLocation.fromNamespaceAndPath("test", "coins");
        edits.setTagField(coinTag, "base_price", number(5));
        CatalogOverlay.Result result = CatalogOverlay.apply(Map.of(), Map.of(), edits.snapshot(),
                tag -> tag.equals(coinTag) ? List.of(Coins.item(Coin.CHIP)) : CatalogOverlay.registryTag(tag));
        helper.assertTrue(result.entries().isEmpty(), "edits list no coin: " + result.entries().keySet());
        helper.assertTrue(result.problems().containsKey(key(bit)) && result.problems().containsKey(key(Coins.item(Coin.CHIP))),
                "and each is reported: " + result.problems());

        Fixture f = new Fixture(helper);
        f.give(new ItemStack(bit, 8));
        f.assertRejectedUnchanged(Result.NOT_LISTED, () -> MarketService.trySell(f.player, bit, 8, ANY_PRICE));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void coinsChangeEightForOneOnlyWithTheModsBank(GameTestHelper helper) {
        boolean own = WorldBank.kind(helper.getLevel().getServer()) == BankKind.OWN;
        helper.assertValueEqual(Coins.inUse(helper.getLevel()), own, "coins are in use only with our bank");
        for (Coin coin : Coin.values()) {
            if (coin.next().isEmpty()) {
                continue;
            }
            Item small = Coins.item(coin);
            Item big = Coins.item(coin.next().get());
            Optional<ItemStack> up = craft(helper, small, small, small, small, small, small, small, small, Items.AIR);
            Optional<ItemStack> down = craft(helper, big, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR, Items.AIR);
            if (own) {
                helper.assertTrue(up.isPresent() && up.get().is(big) && up.get().getCount() == 1, "8 " + coin + " make one " + big + ": " + up);
                helper.assertTrue(down.isPresent() && down.get().is(small) && down.get().getCount() == 8, "and back into 8: " + down);
            } else {
                helper.assertTrue(up.isEmpty() && down.isEmpty(), "uncraftable with Numismatics' bank: " + up + ", " + down);
            }
        }
        // Seven coins of one kind make nothing: the exchange never rounds.
        Item bit = Coins.item(Coin.BIT);
        helper.assertTrue(craft(helper, bit, bit, bit, bit, bit, bit, bit, Items.AIR, Items.AIR).isEmpty(), "7 Bits make nothing");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void terminalCraftsFromBitsWithoutNumismatics(GameTestHelper helper) {
        if (net.neoforged.fml.ModList.get().isLoaded(NumismaticsBridge.MOD_ID)) {
            DynamicEconomy.LOGGER.info("GameTest skipped: Numismatics is installed, so the terminal takes spurs");
            helper.succeed();
            return;
        }
        Item b = Coins.item(Coin.BIT);
        Optional<ItemStack> made = craft(helper, b, b, b, b, Items.CRAFTING_TABLE, b, b, b, b);
        helper.assertTrue(made.isPresent() && made.get().is(MarketBlocks.MARKET_TERMINAL_ITEM.get()) && made.get().getCount() == 1,
                "a crafting table surrounded by 8 Bits makes one terminal, got " + made);
        helper.succeed();
    }

    // ---- Bank tab ----------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void withdrawalsUseTheFewestCoins(GameTestHelper helper) {
        List<Denomination> ours = new OwnBank().coins();
        helper.assertValueEqual(ours.get(0).item(), Coins.item(Coin.OMEGA), "ours run from Omega");
        helper.assertValueEqual(ours.get(ours.size() - 1).item(), Coins.item(Coin.BIT), "down to Bit");
        // Numismatics' values, on stand-in items: only the values matter here.
        List<Denomination> numismatics = List.of(new Denomination(Items.STONE, 4096), new Denomination(Items.DIRT, 512),
                new Denomination(Items.SAND, 64), new Denomination(Items.GRAVEL, 16), new Denomination(Items.CLAY, 8),
                new Denomination(Items.FLINT, 1));
        for (List<Denomination> coins : List.of(ours, numismatics)) {
            int limit = 20_000;
            int[] fewest = fewestCoins(coins, limit);
            for (int amount = 0; amount <= limit; amount++) {
                long[] counts = CoinMath.breakdown(coins, amount);
                helper.assertValueEqual(CoinMath.value(coins, counts), (long) amount, "exactly " + amount);
                helper.assertValueEqual(java.util.Arrays.stream(counts).sum(), (long) fewest[amount], "fewest coins for " + amount);
            }
        }
        long[] mixed = CoinMath.breakdown(ours, 4_096 + 3 * 512 + 7);
        helper.assertValueEqual(java.util.Arrays.toString(mixed), "[0, 0, 0, 1, 3, 0, 0, 7]", "a Crown, 3 Marks and 7 Bits");
        helper.succeed();
    }

    /** The fewest coins for every amount up to {@code limit}, worked out the slow, sure way. */
    private static int[] fewestCoins(List<Denomination> coins, int limit) {
        int[] fewest = new int[limit + 1];
        for (int amount = 1; amount <= limit; amount++) {
            fewest[amount] = Integer.MAX_VALUE;
            for (Denomination coin : coins) {
                if (coin.value() <= amount && fewest[amount - coin.value()] + 1 < fewest[amount]) {
                    fewest[amount] = fewest[amount - coin.value()] + 1;
                }
            }
        }
        return fewest;
    }

    @GameTest(template = EMPTY)
    public static void depositThenWithdrawReturnsTheSameValue(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        List<Denomination> coins = f.bank().coins();
        helper.assertFalse(coins.isEmpty(), "the world's bank has coins");
        // A few of every coin, worth well within a test balance.
        long carried = 0;
        for (int i = 0; i < coins.size(); i++) {
            int count = coins.size() - i;
            f.give(new ItemStack(coins.get(i).item(), count));
            carried += (long) count * coins.get(i).value();
        }
        int before = f.balance();
        BankService.Outcome in = BankService.depositAll(f.player);
        helper.assertTrue(in.ok() && in.amount() == carried, "deposited what was carried: " + in);
        helper.assertValueEqual((long) f.balance(), before + carried, "balance");
        helper.assertValueEqual(coinValue(f, coins), 0L, "no coins left");

        BankService.Outcome out = BankService.withdraw(f.player, carried);
        helper.assertTrue(out.ok() && out.amount() == carried, "withdrew it all: " + out);
        helper.assertValueEqual(f.balance(), before, "balance back where it was");
        helper.assertValueEqual(coinValue(f, coins), carried, "and exactly the same value in coins");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void depositTakesOnlyPlainCoinsFromTheMainInventory(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Denomination smallest = smallestCoin(f);
        ItemStack renamed = new ItemStack(smallest.item(), 5);
        renamed.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("keepsake"));
        f.give(renamed);
        f.player.getInventory().offhand.set(0, new ItemStack(smallest.item(), 7));
        f.assertRejectedUnchanged(BankService.Result.NO_COINS, () -> BankService.depositAll(f.player));
        f.give(new ItemStack(smallest.item(), 3));
        BankService.Outcome outcome = BankService.depositAll(f.player);
        helper.assertTrue(outcome.ok() && outcome.amount() == 3L * smallest.value(), "only the 3 plain coins: " + outcome);
        helper.assertTrue(f.player.getInventory().items.stream().anyMatch(stack -> stack.is(smallest.item())
                && stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME) && stack.getCount() == 5), "the renamed coins stay");
        helper.assertValueEqual(f.player.getInventory().offhand.get(0).getCount(), 7, "and so does the offhand");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void withdrawRefusesWhatItCantDoAndChangesNothing(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Denomination smallest = smallestCoin(f);
        f.assertRejectedUnchanged(BankService.Result.NO_FUNDS, () -> BankService.withdraw(f.player, 10));
        f.assertRejectedUnchanged(BankService.Result.INVALID_AMOUNT, () -> BankService.withdraw(f.player, 0));
        f.assertRejectedUnchanged(BankService.Result.TOO_LARGE, () -> BankService.withdraw(f.player, Integer.MAX_VALUE + 1L));

        f.deposit(1000);
        // A full inventory: the coins wouldn't fit, so nothing is taken and nothing is dropped.
        f.giveMany(Items.COBBLESTONE, 36 * 64);
        f.assertRejectedUnchanged(BankService.Result.NO_SPACE, () -> BankService.withdraw(f.player, 9));
        // One free slot fits one kind of coin, but not two.
        f.player.getInventory().items.set(0, ItemStack.EMPTY);
        f.assertRejectedUnchanged(BankService.Result.NO_SPACE, () -> BankService.withdraw(f.player, smallest.value() + coinAbove(f, smallest).value()));
        BankService.Outcome one = BankService.withdraw(f.player, smallest.value());
        helper.assertTrue(one.ok(), "one coin fits the free slot: " + one);
        // A part-filled stack of that coin is room too, even with no slot free.
        BankService.Outcome topUp = BankService.withdraw(f.player, smallest.value());
        helper.assertTrue(topUp.ok() && f.count(smallest.item()) == 2, "tops up the stack: " + topUp);
        helper.assertValueEqual(f.balance(), 1000 - 2 * smallest.value(), "paid for both");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void withdrawingOneCoinGivesExactlyThatCoin(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        Denomination smallest = smallestCoin(f);
        Denomination above = coinAbove(f, smallest);
        f.assertRejectedUnchanged(BankService.Result.NO_FUNDS, () -> BankService.withdrawCoins(f.player, smallest.item(), 1));
        f.assertRejectedUnchanged(BankService.Result.INVALID_AMOUNT, () -> BankService.withdrawCoins(f.player, smallest.item(), 0));
        f.assertRejectedUnchanged(BankService.Result.INVALID_AMOUNT, () -> BankService.withdrawCoins(f.player, Items.DIAMOND, 1));
        f.assertRejectedUnchanged(BankService.Result.TOO_LARGE, () -> BankService.withdrawCoins(f.player, above.item(), Integer.MAX_VALUE));

        // Enough small coins to make the one above: the fewest-coins withdrawal would hand that over instead.
        int count = above.value() / smallest.value();
        f.deposit(above.value());
        BankService.Outcome out = BankService.withdrawCoins(f.player, smallest.item(), count);
        helper.assertTrue(out.ok() && out.amount() == above.value(), "paid what the coins are worth: " + out);
        helper.assertValueEqual(f.count(smallest.item()), (long) count, "exactly that many of that coin");
        helper.assertValueEqual(f.count(above.item()), 0L, "none of the coin above");
        helper.assertValueEqual(f.balance(), 0, "the balance paid for them");

        // No room: nothing is taken and nothing is dropped.
        f.deposit(above.value());
        f.player.getInventory().items.clear();
        f.giveMany(Items.COBBLESTONE, 36 * 64);
        f.assertRejectedUnchanged(BankService.Result.NO_SPACE, () -> BankService.withdrawCoins(f.player, above.item(), 1));
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankCardOpensOnlyTheBank(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        MarketMenu menu = MarketMenu.forBankCard();
        helper.assertTrue(menu.bankOnly(), "bank-only");
        helper.assertFalse(menu.stillValid(f.player), "not without a card");
        f.give(new ItemStack(Coins.BANK_CARD.get()));
        helper.assertTrue(menu.stillValid(f.player), "valid while the player carries a card");

        // A trade sent from a Bank Card's screen is ignored, however it's dressed up.
        f.setStock(Items.IRON_INGOT, 5);
        f.deposit(10_000);
        f.player.containerMenu = menu;
        try {
            MarketSync.handleTradeRequest(new MarketPayloads.TradeRequest(true, key(Items.IRON_INGOT), 1,
                    f.quote(Items.IRON_INGOT).buyPrice()), f.player);
        } finally {
            f.player.containerMenu = f.player.inventoryMenu;
        }
        helper.assertValueEqual(f.balance(), 10_000, "nothing was paid");
        helper.assertValueEqual(f.count(Items.IRON_INGOT), 0L, "nothing was bought");
        helper.assertFalse(MarketMenu.forCommand().bankOnly(), "the full market isn't bank-only");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankCardCraftsFromACoinWorthEight(GameTestHelper helper) {
        boolean own = WorldBank.kind(helper.getLevel().getServer()) == BankKind.OWN;
        Item chip = Coins.item(Coin.CHIP);
        Optional<ItemStack> fromChip = craft(helper, Items.PAPER, Items.GOLD_INGOT, chip, Items.AIR, Items.AIR, Items.AIR,
                Items.AIR, Items.AIR, Items.AIR);
        helper.assertTrue(fromChip.isPresent() && fromChip.get().is(Coins.BANK_CARD.get()), "paper, gold and a Chip: " + fromChip);
        if (net.neoforged.fml.ModList.get().isLoaded(NumismaticsBridge.MOD_ID)) {
            Item bevel = BuiltInRegistries.ITEM.get(ResourceLocation.parse("numismatics:bevel"));
            Optional<ItemStack> fromBevel = craft(helper, Items.PAPER, Items.GOLD_INGOT, bevel, Items.AIR, Items.AIR, Items.AIR,
                    Items.AIR, Items.AIR, Items.AIR);
            helper.assertTrue(fromBevel.isPresent() && fromBevel.get().is(Coins.BANK_CARD.get()), "or a Bevel: " + fromBevel);
        }
        helper.assertTrue(own || net.neoforged.fml.ModList.get().isLoaded(NumismaticsBridge.MOD_ID),
                "with Numismatics' bank, Numismatics is there to give a Bevel");
        helper.succeed();
    }

    private static Denomination smallestCoin(Fixture f) {
        List<Denomination> coins = f.bank().coins();
        return coins.get(coins.size() - 1);
    }

    private static Denomination coinAbove(Fixture f, Denomination coin) {
        List<Denomination> coins = f.bank().coins();
        return coins.get(coins.indexOf(coin) - 1);
    }

    /** What the plain coins of {@code coins} in the main inventory are worth. */
    private static long coinValue(Fixture f, List<Denomination> coins) {
        long total = 0;
        for (ItemStack stack : f.player.getInventory().items) {
            for (Denomination coin : coins) {
                if (BankService.isPlain(stack, coin)) {
                    total += (long) stack.getCount() * coin.value();
                }
            }
        }
        return total;
    }

    // ---- Team accounts -----------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void teamAccountsAreSeparateAndNeverLoseMoney(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        UUID team = UUID.randomUUID();
        UUID heir = UUID.randomUUID();
        bank.account(heir).deposit(5);
        bank.teamAccount(team).deposit(1000);
        helper.assertValueEqual(bank.teamAccount(team).balance(), 1000L, "the team's balance");
        helper.assertFalse(bank.playerBalances().containsKey(team), "a team isn't a player, so it's off the leaderboard");
        helper.assertValueEqual(bank.account(team).balance(), 0L, "and its ID has no personal account");

        helper.assertValueEqual(bank.closeTeam(team, heir), 1000L, "closing pays out the whole balance");
        helper.assertValueEqual(bank.account(heir).balance(), 1005L, "to the heir's own account");
        helper.assertTrue(bank.teamBalances().isEmpty(), "and the team's account is gone");
        helper.assertValueEqual(bank.closeTeam(team, heir), 0L, "closing an empty team pays nothing");

        // Saved with the world, choices included.
        bank.teamAccount(team).deposit(7);
        OwnBank loaded = OwnBank.fromTag(bank.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.teamBalances(), bank.teamBalances(), "team balances survive a save");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownTeamsKeepTheirMembers(GameTestHelper helper) {
        OwnTeams teams = new OwnTeams();
        UUID owner = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        helper.assertTrue(OwnTeams.validName("Gold_Diggers-2") && !OwnTeams.validName("two words") && !OwnTeams.validName(""),
                "names are one word");
        BankTeam team = teams.create("Miners", owner);
        helper.assertTrue(teams.byName("miners").isPresent(), "names match ignoring case");
        helper.assertFalse(teams.invited(second, team.id()), "not invited yet");
        teams.invite(team.id(), second);
        teams.join(team.id(), second);
        helper.assertValueEqual(teams.teamOf(second).map(BankTeam::id), Optional.of(team.id()), "joined");
        helper.assertValueEqual(teams.teamOf(owner).orElseThrow().members(), Set.of(owner, second), "both are members");

        OwnTeams loaded = OwnTeams.fromTag(teams.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.teamOf(second), teams.teamOf(second), "saved with the world");

        helper.assertTrue(teams.remove(owner).isEmpty(), "the owner leaving doesn't end the team");
        helper.assertValueEqual(teams.teamOf(second).orElseThrow().owner(), second, "the next member becomes owner");
        BankTeam ended = teams.remove(second).orElseThrow();
        helper.assertValueEqual(ended.id(), team.id(), "the last member leaving ends it");
        helper.assertValueEqual(ended.owner(), second, "and they're the one paid its balance");
        helper.assertTrue(teams.byName("Miners").isEmpty(), "the team is gone");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ftbTeamsProvidesTheTeamsWhenInstalled(GameTestHelper helper) {
        boolean installed = net.neoforged.fml.ModList.get().isLoaded(FtbTeamsBridge.MOD_ID);
        helper.assertValueEqual(FtbTeamsBridge.isAvailable(), installed, "its API matches 2101.1.11 when installed");
        helper.assertValueEqual(BankTeams.fromFtbTeams(), installed, "and then its parties are the teams");
        // A player in no party has only FTB Teams' personal team, which isn't a shared account.
        Fixture f = new Fixture(helper);
        helper.assertTrue(FtbTeamsBridge.teamOf(f.player.getUUID()).isEmpty(), "no party, no team");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void teamMembersShareOneAccount(GameTestHelper helper) {
        Fixture a = new Fixture(helper);
        Fixture b = new Fixture(helper);
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        String name = "t" + UUID.randomUUID().toString().substring(0, 8);
        List<String> created = runCommandAs(helper, a.player, "bank team create " + name);
        if (WorldBank.kind(server) != BankKind.OWN) {
            helper.assertValueEqual(created, List.of("command.dynamic_economy.team.numismatics"), "no teams with Numismatics' bank");
            helper.succeed();
            return;
        }
        if (BankTeams.fromFtbTeams()) {
            helper.assertValueEqual(created, List.of("command.dynamic_economy.team.ftb"), "FTB Teams parties are the teams");
            helper.succeed();
            return;
        }
        helper.assertValueEqual(created, List.of("command.dynamic_economy.team.created"), "created");
        helper.assertValueEqual(runCommandAs(helper, a.player, "bank team create other"), List.of("command.dynamic_economy.team.already_in"),
                "one team at a time");
        helper.assertValueEqual(runCommandAs(helper, b.player, "bank team join " + name), List.of("command.dynamic_economy.team.not_invited"),
                "joining needs an invitation");
        OwnTeams teams = OwnTeams.get(server);
        BankTeam team = teams.byName(name).orElseThrow();
        teams.invite(team.id(), b.player.getUUID());
        helper.assertValueEqual(runCommandAs(helper, b.player, "bank team join " + name), List.of("command.dynamic_economy.team.joined"), "joined");

        OwnBank bank = OwnBank.get(server);
        bank.teamAccount(team.id()).deposit(1000);
        a.setStock(Items.IRON_INGOT, 5);
        try {
            // A uses the team's account: a purchase is paid from it, and A's own money is untouched.
            bank.payFromTeam(a.player, true);
            helper.assertTrue(bank.payingFromTeam(a.player), "A pays from the team");
            TradeResult bought = MarketService.tryBuy(a.player, Items.IRON_INGOT, 1, ANY_PRICE);
            a.assertResult(bought, Result.OK);
            helper.assertValueEqual(bank.teamAccount(team.id()).balance(), 1000 - bought.total(), "the team paid");
            helper.assertValueEqual(a.balance(), 0, "not A");
            // B didn't choose it: B's withdrawal comes from B's own account, which is empty.
            b.assertRejectedUnchanged(BankService.Result.NO_FUNDS, () -> BankService.withdraw(b.player, 1));
            bank.payFromTeam(b.player, true);
            BankService.Outcome withdrawn = BankService.withdraw(b.player, 8);
            helper.assertTrue(withdrawn.ok(), "B can withdraw from the team once chosen: " + withdrawn);
        } finally {
            bank.payFromTeam(a.player, false);
            bank.payFromTeam(b.player, false);
        }

        long left = bank.teamAccount(team.id()).balance();
        helper.assertValueEqual(runCommandAs(helper, a.player, "bank team leave"), List.of("command.dynamic_economy.team.left"), "A leaves");
        helper.assertFalse(bank.payingFromTeam(a.player), "out of the team, A is back on their own account");
        helper.assertValueEqual(runCommandAs(helper, b.player, "bank team leave"), List.of("command.dynamic_economy.team.ended"),
                "B, the last member, ends it");
        helper.assertValueEqual((long) b.balance(), left, "and receives everything the team had");
        helper.assertFalse(bank.teamBalances().containsKey(team.id()), "nothing is left behind");
        helper.succeed();
    }

    /** The first translation key in a message: failures arrive wrapped in a red, empty component. */
    private static Optional<String> translationKey(Component message) {
        if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents key) {
            return Optional.of(key.getKey());
        }
        return message.getSiblings().stream().map(BankTests::translationKey).flatMap(Optional::stream).findFirst();
    }

    /** Runs a command as {@code player}, returning its messages as translation keys, so tests don't depend on wording. */
    private static List<String> runCommandAs(GameTestHelper helper, ServerPlayer player, String command) {
        List<String> output = new ArrayList<>();
        net.minecraft.commands.CommandSource capture = new net.minecraft.commands.CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(translationKey(message).orElse(message.getString()));
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
            server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack().withSource(capture).withEntity(player));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new GameTestAssertException("/" + command + " was rejected: " + e.getMessage());
        }
        return output;
    }

    // ---- Switching banks ---------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void drainingTakesWhatADisplayCapHides(GameTestHelper helper) {
        // A stand-in for a Numismatics account: it shows at most the cap but can pay out everything.
        long cap = NumismaticsBridge.DISPLAYED_BALANCE_CAP;
        for (long start : new long[] {0, 7, cap - 1, cap, cap + 1, Integer.MAX_VALUE, 5_000_000_000L}) {
            long[] total = {start};
            Bank.Account account = new Bank.Account() {
                @Override
                public long balance() {
                    return Math.min(total[0], cap);
                }

                @Override
                public void deposit(int amount) {
                    total[0] += amount;
                }

                @Override
                public boolean deduct(int amount) {
                    if (amount > total[0]) {
                        return false;
                    }
                    total[0] -= amount;
                    return true;
                }
            };
            long[] paid = {0};
            long moved = BankSwitch.drain(account, cap, amount -> paid[0] += amount);
            helper.assertValueEqual(moved, start, "all of " + start + " is taken");
            helper.assertValueEqual(paid[0], start, "and all of it is paid on");
            helper.assertValueEqual(total[0], 0L, "leaving nothing behind");
        }
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void switchingBanksMovesEveryBalanceAndBack(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        BankKind start = WorldBank.kind(server);
        if (NumismaticsBridge.bank().isEmpty()) {
            BankSwitch.Result refused = BankSwitch.plan(server, BankKind.NUMISMATICS);
            helper.assertTrue(refused.plan().isEmpty() && refused.problem().contains("Numismatics isn't installed"),
                    "no switch without Numismatics: " + refused.problem());
            helper.assertValueEqual(WorldBank.kind(server), start, "and the bank stays");
            helper.succeed();
            return;
        }
        helper.assertValueEqual(start, BankKind.NUMISMATICS, "the dev world uses Numismatics' bank");
        helper.assertTrue(BankSwitch.plan(server, BankKind.NUMISMATICS).plan().isEmpty(), "no switch to the bank in use");

        Fixture rich = new Fixture(helper);
        Fixture modest = new Fixture(helper);
        // More than Numismatics displays: part of it sits in its hidden overflow.
        rich.deposit(Integer.MAX_VALUE);
        rich.deposit(Integer.MAX_VALUE);
        modest.deposit(1234);
        long richTotal = 2L * Integer.MAX_VALUE;
        OwnBank own = OwnBank.get(server);
        UUID goneTeam = UUID.randomUUID();
        try {
            BankSwitch.Plan plan = BankSwitch.plan(server, BankKind.OWN).plan().orElseThrow();
            helper.assertValueEqual(plan.balances().get(modest.player.getUUID()), 1234L, "the plan lists each balance");
            helper.assertTrue(BankSwitch.switchTo(server, BankKind.OWN).plan().isPresent(), "switched");
            helper.assertValueEqual(WorldBank.kind(server), BankKind.OWN, "the world records the new bank");
            helper.assertValueEqual(own.account(rich.player.getUUID()).balance(), richTotal, "every bit moved, the hidden part too");
            helper.assertValueEqual(own.account(modest.player.getUUID()).balance(), 1234L, "each to the same player");
            helper.assertValueEqual(NumismaticsBridge.accountById(rich.player.getUUID()).orElseThrow().balance(), 0L,
                    "nothing left behind in Numismatics' bank");

            // A team account whose team is gone can't go to Numismatics' bank: it stays put rather than vanish.
            own.teamAccount(goneTeam).deposit(50);
            BankSwitch.Plan back = BankSwitch.plan(server, BankKind.NUMISMATICS).plan().orElseThrow();
            helper.assertValueEqual(back.lostTeams().get(goneTeam), 50L, "the lost team is reported");
        } finally {
            if (WorldBank.kind(server) != BankKind.NUMISMATICS) {
                helper.assertTrue(BankSwitch.switchTo(server, BankKind.NUMISMATICS).plan().isPresent(), "switched back");
            }
        }
        helper.assertValueEqual(WorldBank.kind(server), BankKind.NUMISMATICS, "back on Numismatics' bank");
        helper.assertValueEqual(own.teamBalances().get(goneTeam), 50L, "the lost team's balance waited in the mod's bank");
        helper.assertValueEqual(own.account(rich.player.getUUID()).balance(), 0L, "the players' balances moved back");
        Bank.Account richAccount = rich.account();
        helper.assertTrue(richAccount.deduct(Integer.MAX_VALUE) && richAccount.deduct(Integer.MAX_VALUE)
                && richAccount.balance() == 0, "all of it, overflow included");
        helper.assertValueEqual(modest.balance(), 1234, "to the same players");
        own.closeTeam(goneTeam, modest.player.getUUID());
        own.drain(modest.player.getUUID());
        modest.account().deduct(1234);
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void bankAdminCommandShowsBeforeItSwitches(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        BankKind start = WorldBank.kind(server);
        List<String> info = runCommand(helper, "market admin bank");
        helper.assertTrue(info.get(0).startsWith("This world uses " + BankSwitch.describe(start)), "says which bank: " + info);
        BankKind other = start == BankKind.OWN ? BankKind.NUMISMATICS : BankKind.OWN;
        List<String> preview = runCommand(helper, "market admin bank switch " + other.id());
        if (NumismaticsBridge.bank().isPresent()) {
            helper.assertTrue(preview.get(0).startsWith("Switching from"), "a preview first: " + preview);
            helper.assertTrue(preview.stream().anyMatch(line -> line.startsWith("Coins don't move")), "with the coin warning");
        } else {
            helper.assertTrue(preview.get(0).contains("Numismatics isn't installed"), "nothing to switch to: " + preview);
        }
        helper.assertValueEqual(WorldBank.kind(server), start, "a preview changes nothing");
        helper.succeed();
    }

    // ---- Banks -------------------------------------------------------------------------------------------------

    @GameTest(template = EMPTY)
    public static void worldUsesTheBankItsModsSuggest(GameTestHelper helper) {
        // The dev world had a market before banks were recorded, so it uses Numismatics'; a fresh world without
        // Numismatics (the run without optional mods) uses ours.
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        BankKind expected = NumismaticsBridge.bank().isPresent() ? BankKind.NUMISMATICS : BankKind.OWN;
        helper.assertValueEqual(WorldBank.kind(server), expected, "the world's bank");
        helper.assertValueEqual(WorldBank.of(server).kind(), expected, "and the bank used is that one");
        helper.assertTrue(WorldBank.available(server), "and it can be reached");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownBankDeductsAllOrNothing(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        UUID owner = UUID.randomUUID();
        Bank.Account account = bank.account(owner);
        helper.assertValueEqual(account.balance(), 0L, "a new account is empty");
        helper.assertFalse(bank.playerBalances().containsKey(owner), "and not listed");
        account.deposit(100);
        helper.assertFalse(account.deduct(101), "can't deduct more than the balance");
        helper.assertValueEqual(account.balance(), 100L, "and a failed deduction changes nothing");
        helper.assertTrue(account.deduct(100), "can deduct exactly the balance");
        helper.assertValueEqual(account.balance(), 0L, "leaving nothing");
        helper.assertFalse(bank.playerBalances().containsKey(owner), "an emptied account is forgotten");
        helper.assertTrue(account.deduct(0), "deducting nothing always works");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownBankHoldsMoreThanAnInt(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        UUID owner = UUID.randomUUID();
        Bank.Account account = bank.account(owner);
        for (int i = 0; i < 3; i++) {
            account.deposit(Integer.MAX_VALUE);
        }
        long expected = 3L * Integer.MAX_VALUE;
        helper.assertValueEqual(account.balance(), expected, "balances are longs");
        helper.assertValueEqual(bank.displayCap(), Long.MAX_VALUE, "and all of it is shown");
        helper.assertTrue(account.deduct(Integer.MAX_VALUE), "spent a transaction at a time");
        helper.assertValueEqual(account.balance(), expected - Integer.MAX_VALUE, "exactly");

        // Saved and loaded back with the world.
        OwnBank loaded = OwnBank.fromTag(bank.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()),
                helper.getLevel().registryAccess());
        helper.assertValueEqual(loaded.account(owner).balance(), expected - Integer.MAX_VALUE, "the balance survives a save");
        helper.assertValueEqual(loaded.playerBalances(), bank.playerBalances(), "as does every account");
        helper.succeed();
    }

    @GameTest(template = EMPTY)
    public static void ownBankSavesOnlyWhenChanged(GameTestHelper helper) {
        OwnBank bank = new OwnBank();
        Bank.Account account = bank.account(UUID.randomUUID());
        account.balance();
        account.deduct(5);
        helper.assertFalse(bank.isDirty(), "reading or a refused deduction changes nothing to save");
        account.deposit(5);
        helper.assertTrue(bank.isDirty(), "a deposit is saved");
        helper.succeed();
    }
}
