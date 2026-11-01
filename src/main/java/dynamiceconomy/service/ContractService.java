package dynamiceconomy.service;

import com.mojang.authlib.GameProfile;
import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.BankTeam;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.data.ContractBoard.Contract;
import dynamiceconomy.data.ContractBoard.Status;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.service.MarketService.Result;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * The only code that changes the contract board or moves contract money. Money moves only through the world's
 * {@link Bank}, following the market's rules: every check runs before anything changes, on the server thread, and
 * once the first change is made no remaining step can fail.
 * <ul>
 *   <li>Claiming takes the deposit from the account the player trades with and locks the contract to them.</li>
 *   <li>Units are delivered in batches from the main inventory only; they leave the game, never joining market stock.</li>
 *   <li>The last unit pays the reward and returns the deposit. Expiry or abandoning pays only for the units delivered,
 *       and the deposit is lost. Payouts go to the account the deposit came from (a team's while it exists).</li>
 *   <li>A payout is worked out when it's made ({@link ContractPricing#payout}), never above what was offered, so it
 *       stays below the lowest price the market can charge for those units even if the catalog changed meanwhile.</li>
 * </ul>
 */
public final class ContractService {
    private ContractService() {
    }

    public enum Action { CLAIM, DELIVER, ABANDON }

    /**
     * How a contract action went.
     *
     * @param quantity units delivered by this request (for abandoning: units delivered in all)
     * @param amount   the deposit taken when claiming; otherwise what was paid into the bank
     */
    public record Outcome(Action action, Result result, ResourceLocation item, int quantity, long amount) {
        static Outcome fail(Action action, Result result, ResourceLocation item) {
            return new Outcome(action, result, item, 0, 0);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    /** A contract as one player sees it. {@code reward} is what completing it would pay now. */
    public record View(Contract contract, long reward, String holderName, boolean mine) {
    }

    // ---- The board -------------------------------------------------------------------------------------------

    /**
     * Every tick: advances the day count, pays anything owed, and replaces the board when its day has come (or clears
     * it if contracts were turned off). Returns whether the board changed, so open screens can be refreshed.
     */
    public static boolean tick(MinecraftServer server) {
        ContractBoard board = ContractBoard.get(server);
        board.observe(ContractBoard.worldDay(server));
        if (!board.owed().isEmpty()) {
            PayoutService.payOwed(server, board.owed());
        }
        if (!MarketConfig.contractsEnabled()) {
            if (board.expiresDay() < 0) {
                return false;
            }
            // Not the players' doing, so they keep their deposits.
            settleHeld(server, board, true);
            board.clear();
            return true;
        }
        if (board.due()) {
            refresh(server, board, server.overworld().getRandom());
            return true;
        }
        return false;
    }

    /** Ends every held contract as expired, then puts up a new board. */
    public static void refresh(MinecraftServer server, ContractBoard board, RandomSource random) {
        settleHeld(server, board, false);
        board.replace(generate(MarketCatalog.INSTANCE.entries(), MarketStockData.get(server), MarketStockData.now(server), random,
                MarketConfig.boardSize(), MarketConfig.depositFraction(), board::takeId), MarketConfig.refreshDays());
        DynamicEconomy.LOGGER.debug("New contract board on day {}: {} contracts, until day {}", board.day(),
                board.contracts().size(), board.expiresDay());
    }

    /**
     * Up to {@code count} contracts for different items, picked at random in proportion to contract_weight from the
     * items worth offering ({@link ContractPricing#offer}). Each asks for a random size between the item's
     * contract_min_size and contract_max_size; large sizes are rounded down to a multiple of 16.
     */
    public static List<Contract> generate(Collection<CatalogEntry> entries, MarketStockData market, long now, RandomSource random,
                                          int count, double depositFraction, IntSupplier ids) {
        record Candidate(CatalogEntry entry, PriceModel.Inputs inputs, double factor, ContractPricing.Offer smallest) {
        }
        List<Candidate> pool = new ArrayList<>();
        long totalWeight = 0;
        for (CatalogEntry entry : entries) {
            int weight = entry.contract().effectiveWeight();
            if (weight <= 0) {
                continue;
            }
            PriceModel.Inputs inputs = PriceModel.inputs(entry);
            double factor = MarketService.currentFactor(entry, inputs, market, now);
            Optional<ContractPricing.Offer> smallest = ContractPricing.offer(entry, inputs, factor,
                    entry.contract().effectiveMinSize(), depositFraction);
            if (smallest.isPresent()) {
                pool.add(new Candidate(entry, inputs, factor, smallest.get()));
                totalWeight += weight;
            }
        }
        List<Contract> picked = new ArrayList<>();
        while (picked.size() < count && !pool.isEmpty()) {
            long target = (long) (random.nextDouble() * totalWeight);
            int index = 0;
            for (long seen = pool.get(0).entry().contract().effectiveWeight(); seen <= target && index < pool.size() - 1;
                 seen += pool.get(index).entry().contract().effectiveWeight()) {
                index++;
            }
            Candidate chosen = pool.remove(index);
            totalWeight -= chosen.entry().contract().effectiveWeight();
            ContractPricing.Offer offer = ContractPricing.offer(chosen.entry(), chosen.inputs(), chosen.factor(),
                    size(chosen.entry().contract(), random), depositFraction).orElse(chosen.smallest());
            picked.add(new Contract(ids.getAsInt(), chosen.entry().itemId(), offer.units(), offer.unitReward(), offer.reward(),
                    offer.deposit(), 0, Optional.empty(), Optional.empty(), Status.OPEN));
        }
        return picked;
    }

    private static int size(CatalogEntry.ContractTerms terms, RandomSource random) {
        int min = terms.effectiveMinSize();
        int max = terms.effectiveMaxSize();
        int units = min + random.nextInt(max - min + 1);
        return max >= 64 ? Math.max(min, units / 16 * 16) : units;
    }

    /**
     * Ends every held contract: its holder is paid for the units delivered, and keeps the deposit only when
     * {@code refundDeposits}. Each holder is told now, or when they next log in.
     */
    static void settleHeld(MinecraftServer server, ContractBoard board, boolean refundDeposits) {
        for (Contract contract : board.contracts()) {
            if (contract.status() != Status.CLAIMED || contract.holder().isEmpty()) {
                continue;
            }
            long paid = payout(server, contract, contract.delivered());
            long lost = refundDeposits ? 0 : contract.deposit();
            PayoutService.pay(server, board.owed(), "Contract payout", contract.holder().get(), contract.team(),
                    paid + contract.deposit() - lost);
            board.count(paid, lost);
            tell(server, board, new ContractBoard.Notice(contract.holder().get(), contract.item(), contract.delivered(),
                    contract.units(), paid, lost));
        }
    }

    /** Tells the holder how their contract ended: in chat if they're online, otherwise when they next log in. */
    private static void tell(MinecraftServer server, ContractBoard board, ContractBoard.Notice notice) {
        ServerPlayer online = server.getPlayerList().getPlayer(notice.player());
        if (online != null) {
            online.sendSystemMessage(message(server, notice));
        } else {
            board.addNotice(notice);
        }
    }

    /** Sends a player who just logged in what happened to their contracts while they were away. */
    public static void loggedIn(ServerPlayer player) {
        for (ContractBoard.Notice notice : ContractBoard.get(player.server).takeNotices(player.getUUID())) {
            player.sendSystemMessage(message(player.server, notice));
        }
    }

    private static Component message(MinecraftServer server, ContractBoard.Notice notice) {
        Component item = BuiltInRegistries.ITEM.get(notice.item()).getDescription();
        if (notice.lost() == 0) {
            return Component.translatable("message.dynamic_economy.contract.ended_kept", item, notice.delivered(), notice.units(),
                    PayoutService.money(server, notice.paid()));
        }
        return Component.translatable("message.dynamic_economy.contract.ended", item, notice.delivered(), notice.units(),
                PayoutService.money(server, notice.paid()), PayoutService.money(server, notice.lost()));
    }

    /**
     * What delivering {@code delivered} units of the contract pays right now: capped below the lowest price the market
     * could charge for them (if it sells the item at all), and never more than the contract offered.
     */
    public static long payout(MinecraftServer server, Contract contract, int delivered) {
        Optional<BigDecimal> lowest = BuiltInRegistries.ITEM.getOptional(contract.item())
                .flatMap(MarketCatalog.INSTANCE::get)
                .flatMap(entry -> {
                    PriceModel.Inputs inputs = PriceModel.inputs(entry);
                    MarketStockData market = MarketStockData.get(server);
                    double factor = MarketService.currentFactor(entry, inputs, market, MarketStockData.now(server));
                    return ContractPricing.lowestUnitBuyPrice(entry, inputs, factor);
                });
        return Math.min(contract.reward(), ContractPricing.payout(contract.unitReward(), lowest, delivered));
    }

    // ---- Player actions --------------------------------------------------------------------------------------

    public static Outcome claim(ServerPlayer player, int id) {
        return claim(player, ContractBoard.get(player.server), id);
    }

    /** Claims an open contract: takes the deposit and locks it to the player. */
    public static Outcome claim(ServerPlayer player, ContractBoard board, int id) {
        MarketService.requireServerThread(player);
        Optional<Contract> found = current(player, board, id);
        if (found.isEmpty()) {
            return Outcome.fail(Action.CLAIM, Result.CONTRACT_EXPIRED, none());
        }
        Contract contract = found.get();
        Item item = BuiltInRegistries.ITEM.get(contract.item());
        // A locked item answers as an unlisted one, so the reply doesn't reveal it.
        if (MarketVisibility.hidden(player, item)) {
            return Outcome.fail(Action.CLAIM, Result.NOT_LISTED, none());
        }
        if (contract.status() != Status.OPEN) {
            return Outcome.fail(Action.CLAIM, Result.CONTRACT_TAKEN, contract.item());
        }
        if (board.claimedBy(player.getUUID()) >= MarketConfig.maxActiveContracts()) {
            return Outcome.fail(Action.CLAIM, Result.CONTRACT_LIMIT, contract.item());
        }
        if (contract.deposit() > Integer.MAX_VALUE) {
            return Outcome.fail(Action.CLAIM, Result.TOO_LARGE, contract.item());
        }
        Bank bank = WorldBank.of(player.server);
        Optional<Bank.Account> account = bank.activeAccount(player);
        if (account.isEmpty()) {
            return Outcome.fail(Action.CLAIM, Result.BANK_UNAVAILABLE, contract.item());
        }
        // The deduction is itself the funds check: it changes nothing when it returns false.
        if (!account.get().deduct((int) contract.deposit())) {
            return Outcome.fail(Action.CLAIM, Result.NO_FUNDS, contract.item());
        }
        Optional<UUID> team = bank.payingFromTeam(player) ? bank.team(player).map(BankTeam::id) : Optional.empty();
        board.update(contract.claimed(player.getUUID(), team));
        return new Outcome(Action.CLAIM, Result.OK, contract.item(), 0, contract.deposit());
    }

    public static Outcome deliver(ServerPlayer player, int id, int qty) {
        return deliver(player, ContractBoard.get(player.server), id, qty);
    }

    /**
     * Delivers {@code qty} units of a held contract from the main inventory. The last unit pays the reward and
     * returns the deposit.
     */
    public static Outcome deliver(ServerPlayer player, ContractBoard board, int id, int qty) {
        MarketService.requireServerThread(player);
        Optional<Contract> found = current(player, board, id);
        if (found.isEmpty()) {
            return Outcome.fail(Action.DELIVER, Result.CONTRACT_EXPIRED, none());
        }
        Contract contract = found.get();
        Result holding = holding(player, contract);
        if (holding != Result.OK) {
            return Outcome.fail(Action.DELIVER, holding, contract.item());
        }
        Result quantityCheck = MarketService.checkQuantity(qty);
        if (quantityCheck != Result.OK) {
            return Outcome.fail(Action.DELIVER, quantityCheck, contract.item());
        }
        if (qty > contract.remaining()) {
            return Outcome.fail(Action.DELIVER, Result.CONTRACT_FULL, contract.item());
        }
        Item item = BuiltInRegistries.ITEM.get(contract.item());
        Set<ResourceLocation> ignored = MarketConfig.ignoredComponents();
        Predicate<ItemStack> matches = stack -> CatalogEntry.plain(stack, item, ignored);
        NonNullList<ItemStack> items = player.getInventory().items;
        if (MarketService.countMatching(items, matches) < qty) {
            return Outcome.fail(Action.DELIVER, Result.NOT_ENOUGH_ITEMS, contract.item());
        }
        boolean completes = qty == contract.remaining();
        long paid = completes ? payout(player.server, contract, contract.units()) : 0;
        Optional<Bank.Account> account = Optional.empty();
        if (completes) {
            account = PayoutService.payee(player.server, player.getUUID(), contract.team());
            if (account.isEmpty()) {
                return Outcome.fail(Action.DELIVER, Result.BANK_UNAVAILABLE, contract.item());
            }
        }

        // Nothing has changed yet. Everything below always succeeds: the payout fits an int, checked when offered.
        MarketService.removeMatching(items, matches, qty);
        player.getInventory().setChanged();
        board.update(contract.withDelivered(contract.delivered() + qty));
        if (completes) {
            account.get().deposit((int) (paid + contract.deposit()));
            board.count(paid, 0);
        }
        return new Outcome(Action.DELIVER, Result.OK, contract.item(), qty, completes ? paid + contract.deposit() : 0);
    }

    public static Outcome abandon(ServerPlayer player, int id) {
        return abandon(player, ContractBoard.get(player.server), id);
    }

    /** Gives up a held contract: pays for the units delivered so far; the deposit is lost. */
    public static Outcome abandon(ServerPlayer player, ContractBoard board, int id) {
        MarketService.requireServerThread(player);
        Optional<Contract> found = current(player, board, id);
        if (found.isEmpty()) {
            return Outcome.fail(Action.ABANDON, Result.CONTRACT_EXPIRED, none());
        }
        Contract contract = found.get();
        Result holding = holding(player, contract);
        if (holding != Result.OK) {
            return Outcome.fail(Action.ABANDON, holding, contract.item());
        }
        long paid = payout(player.server, contract, contract.delivered());
        Optional<Bank.Account> account = PayoutService.payee(player.server, player.getUUID(), contract.team());
        if (paid > 0 && account.isEmpty()) {
            return Outcome.fail(Action.ABANDON, Result.BANK_UNAVAILABLE, contract.item());
        }

        board.update(contract.abandoned());
        if (paid > 0) {
            account.get().deposit((int) paid);
        }
        board.count(paid, contract.deposit());
        return new Outcome(Action.ABANDON, Result.OK, contract.item(), contract.delivered(), paid);
    }

    /**
     * The contract, if it's on the current board. A board whose day has come counts as gone even before the tick
     * replaces it, so nothing is claimed or delivered after expiry.
     */
    private static Optional<Contract> current(ServerPlayer player, ContractBoard board, int id) {
        board.observe(ContractBoard.worldDay(player.server));
        return board.due() ? Optional.empty() : board.find(id);
    }

    /** OK if the player holds the contract and it's still open to deliveries. */
    private static Result holding(ServerPlayer player, Contract contract) {
        if (contract.holder().isEmpty() || !contract.holder().get().equals(player.getUUID())) {
            return Result.NOT_YOUR_CONTRACT;
        }
        return contract.status() == Status.CLAIMED ? Result.OK : Result.CONTRACT_TAKEN;
    }

    private static ResourceLocation none() {
        return BuiltInRegistries.ITEM.getDefaultKey();
    }

    // ---- Viewing ---------------------------------------------------------------------------------------------

    public static List<View> view(ServerPlayer player) {
        return view(player, ContractBoard.get(player.server));
    }

    /** The board as {@code player} sees it: contracts for items locked for them are left out, unless they hold one. */
    public static List<View> view(ServerPlayer player, ContractBoard board) {
        List<View> views = new ArrayList<>();
        for (Contract contract : board.contracts()) {
            boolean mine = contract.holder().isPresent() && contract.holder().get().equals(player.getUUID());
            if (!mine && MarketVisibility.hidden(player, BuiltInRegistries.ITEM.get(contract.item()))) {
                continue;
            }
            String holder = contract.holder().flatMap(id -> Leaderboard.profile(player.server, id)).map(GameProfile::getName).orElse("");
            views.add(new View(contract, payout(player.server, contract, contract.units()), holder, mine));
        }
        return views;
    }
}
