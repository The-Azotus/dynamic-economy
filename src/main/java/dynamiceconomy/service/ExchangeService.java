package dynamiceconomy.service;

import dynamiceconomy.MarketConfig;
import dynamiceconomy.bank.Bank;
import dynamiceconomy.bank.BankTeam;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.coin.Coins;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.data.ExchangeData;
import dynamiceconomy.data.ExchangeData.Notice;
import dynamiceconomy.data.ExchangeData.Listing;
import dynamiceconomy.data.ExchangeData.NoticeKind;
import dynamiceconomy.data.ExchangeData.Order;
import dynamiceconomy.data.ExchangeData.Parcel;
import dynamiceconomy.service.MarketService.Result;
import io.netty.buffer.Unpooled;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The only code that changes the player exchange. Money moves only through the world's {@link Bank}, following the
 * market's rules: every check runs before anything changes, on the server thread, and once the first change is made
 * no remaining step can fail.
 * <ul>
 *   <li>Posting a sell order takes its items from the main inventory; posting a buy order takes {@code price × qty}
 *       from the account the player trades with. Either then matches against the other side of the book at once.</li>
 *   <li>A trade fills at the waiting order's price, best price first, then oldest first. A player's orders never
 *       trade with each other. A buyer who bid above the price paid gets the difference back straight away.</li>
 *   <li>The seller is paid into the account their order belongs to, less {@code exchange_tax}, which is destroyed.
 *       Bought items go to the buyer's collection box.</li>
 *   <li>Cancelling, expiry or turning the exchange off returns exactly what an order still holds: a buy order's money
 *       to its account, a sell order's items to the collection box.</li>
 *   <li>Only fresh copies go on the book, exactly as the market hands them out. Items with any components of their
 *       own (enchanted, damaged, renamed, a levelled relic and so on, even data the market's sale check ignores)
 *       are <em>listed</em> instead, so they reach the buyer exactly as they were: one stack's worth of one exact item at a fixed price each, which other players buy
 *       from, all or part, into their collection box. The seller pays the same tax. Listings and orders share
 *       {@code max_open_orders}, and are cancelled, expired and closed the same way.</li>
 * </ul>
 * The exchange creates no money: every bit paid to a seller was taken from a buyer, and the tax only removes some.
 */
public final class ExchangeService {
    private ExchangeService() {
    }

    public enum Action { POST, CANCEL, COLLECT, LIST, BUY_LISTING }

    /**
     * The most bytes one listed item may take to send. Every listing goes to every player browsing them, so an item
     * carrying a great deal of data (a long written book, say) is refused.
     */
    public static final int MAX_LISTING_BYTES = 8192;

    /**
     * How an exchange request went.
     *
     * @param quantity units ordered (posting), listed, bought from a listing, returned (cancelling) or taken out of the
     *                 box (collecting)
     * @param filled   units that traded straight away when posting
     * @param amount   posting: money paid for the units bought or received for those sold, after tax; buying from a
     *                 listing: what it cost; cancelling: a buy order's money returned
     * @param orderId  the order left open on the book after posting (0 if it filled completely), or the listing
     * @param listed   for listing, buying from or cancelling a listing: its item, components and all, so messages can
     *                 name it as listed; empty otherwise
     */
    public record Outcome(Action action, Result result, ResourceLocation item, int quantity, int filled, long amount, long orderId,
                          ItemStack listed) {
        public Outcome(Action action, Result result, ResourceLocation item, int quantity, int filled, long amount, long orderId) {
            this(action, result, item, quantity, filled, amount, orderId, ItemStack.EMPTY);
        }

        static Outcome fail(Action action, Result result, ResourceLocation item) {
            return new Outcome(action, result, item, 0, 0, 0, 0);
        }

        public boolean ok() {
            return result == Result.OK;
        }
    }

    /** One price on one side of an item's book: units on offer in all, and how many of them are the viewer's. */
    public record Level(long price, long quantity, long mine) {
    }

    /** An item's book as one player sees it, best prices first, at most {@code depth} prices a side. */
    public record Book(ResourceLocation item, List<Level> asks, List<Level> bids) {
    }

    /** The best price on each side of an item's book, and how many units are on that side in all; 0 when empty. */
    public record Summary(ResourceLocation item, long bestAsk, long askUnits, long bestBid, long bidUnits) {
    }

    // ---- Rules shared with the screen ------------------------------------------------------------------------

    /** Whether an item can be traded on the exchange at all: anything but air and money. */
    public static boolean tradeable(Item item) {
        return item != Items.AIR && !Coins.isCoin(item);
    }

    /**
     * Whether a stack can be listed: an item that can be traded, carrying components of its own (fresh copies go on the
     * book), and not a container with anything inside, whose contents a buyer couldn't fully see.
     */
    public static boolean listable(ItemStack stack) {
        if (stack.isEmpty() || !tradeable(stack.getItem()) || fresh(stack.getItem()).test(stack)) {
            return false;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents != null && contents.nonEmptyStream().findAny().isPresent()) {
            return false;
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        return bundle == null || bundle.isEmpty();
    }

    /** How many bytes {@code stack} takes to send. */
    public static int encodedSize(ItemStack stack, RegistryAccess registries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            ItemStack.STREAM_CODEC.encode(buf, stack);
            return buf.readableBytes();
        } finally {
            buf.release();
        }
    }

    /** The tax on a sale worth {@code value}: {@code exchange_tax} of it, rounded up to a whole bit. */
    public static long tax(long value, double rate) {
        if (rate <= 0 || value <= 0) {
            return 0;
        }
        return Math.min(value, BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.CEILING).longValueExact());
    }

    /**
     * The tax on one more piece of a sale, worth {@code value}, after {@code before} of the same order or listing has
     * already sold: what the tax on the whole so far has grown by. However a sale is split, its pieces' taxes add up
     * to {@link #tax} of its total, rounded up once.
     */
    public static long taxOnMore(long before, long value, double rate) {
        return tax(before + value, rate) - tax(before, rate);
    }

    /** The contracts' day count today, which order expiry uses. */
    public static long today(MinecraftServer server) {
        ContractBoard board = ContractBoard.get(server);
        board.observe(ContractBoard.worldDay(server));
        return board.day();
    }

    // ---- Posting ---------------------------------------------------------------------------------------------

    public static Outcome post(ServerPlayer player, Item item, boolean buy, long price, int qty) {
        return post(player, ExchangeData.get(player.server), item, buy, price, qty, today(player.server));
    }

    /**
     * Posts an order: a sell order's items or a buy order's money are taken, then it trades with whatever on the
     * other side of the book it crosses. What's left stays on the book.
     */
    public static Outcome post(ServerPlayer player, ExchangeData data, Item item, boolean buy, long price, int qty, long day) {
        MarketService.requireServerThread(player);
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (!MarketConfig.exchangeEnabled()) {
            return Outcome.fail(Action.POST, Result.EXCHANGE_CLOSED, id);
        }
        // A locked item answers as an unlisted one, so the reply doesn't reveal it.
        if (!tradeable(item) || MarketVisibility.hidden(player, item)) {
            return Outcome.fail(Action.POST, Result.NOT_LISTED, id);
        }
        if (qty <= 0) {
            return Outcome.fail(Action.POST, Result.INVALID_QUANTITY, id);
        }
        if (price <= 0) {
            return Outcome.fail(Action.POST, Result.INVALID_PRICE, id);
        }
        OptionalLong total = MarketService.checkedTotal(price, qty);
        if (qty > MarketConfig.maxOrderSize() || total.isEmpty()) {
            return Outcome.fail(Action.POST, Result.TOO_LARGE, id);
        }
        if (data.openOrders(player.getUUID()) >= MarketConfig.maxOpenOrders()) {
            return Outcome.fail(Action.POST, Result.ORDER_LIMIT, id);
        }
        Bank bank = WorldBank.of(player.server);
        Optional<Bank.Account> account = bank.activeAccount(player);
        if (account.isEmpty()) {
            return Outcome.fail(Action.POST, Result.BANK_UNAVAILABLE, id);
        }
        Optional<UUID> team = bank.payingFromTeam(player) ? bank.team(player).map(BankTeam::id) : Optional.empty();
        NonNullList<ItemStack> items = player.getInventory().items;
        Predicate<ItemStack> matches = fresh(item);
        if (buy) {
            // The deduction is itself the funds check: it changes nothing when it returns false.
            if (!account.get().deduct((int) total.getAsLong())) {
                return Outcome.fail(Action.POST, Result.NO_FUNDS, id);
            }
        } else {
            if (MarketService.countMatching(items, matches) < qty) {
                return Outcome.fail(Action.POST, Result.NOT_ENOUGH_ITEMS, id);
            }
            MarketService.removeMatching(items, matches, qty);
            player.getInventory().setChanged();
        }

        // The order's money or items are held now. Everything below always succeeds: every payment is one fill of
        // one order, which fits an int because that order's whole total did.
        Order order = new Order(data.takeId(), player.getUUID(), team, id, buy, price, qty, 0, 0, day);
        Fill fill = match(player.server, data, order, account.get());
        if (fill.order().remaining() > 0) {
            data.add(fill.order());
        }
        return new Outcome(Action.POST, Result.OK, id, qty, fill.order().filled(), fill.amount(),
                fill.order().remaining() > 0 ? order.id() : 0);
    }

    /** What matching left of an incoming order, and the money it paid (buying) or received after tax (selling). */
    private record Fill(Order order, long amount) {
    }

    /**
     * Trades an incoming order against the other side of its item's book while their prices cross. The incoming
     * order's money or items are already held; {@code account} is where its own money goes.
     */
    private static Fill match(MinecraftServer server, ExchangeData data, Order incoming, Bank.Account account) {
        double rate = MarketConfig.exchangeTax();
        int filled = 0;
        long amount = 0;
        long sold = 0;
        for (Order resting : data.side(incoming.item(), !incoming.buy())) {
            if (filled == incoming.quantity()) {
                break;
            }
            if (incoming.buy() ? resting.price() > incoming.price() : resting.price() < incoming.price()) {
                break;
            }
            if (resting.owner().equals(incoming.owner())) {
                continue;
            }
            int units = Math.min(incoming.quantity() - filled, resting.remaining());
            long value = resting.price() * units;
            // The seller's whole sale so far is taxed: the resting order's, or this incoming one's.
            long tax = taxOnMore(incoming.buy() ? resting.soldValue() : sold, value, rate);
            data.update(resting.withFill(units, value));
            data.count(value, tax);
            if (incoming.buy()) {
                // The resting seller is paid; the buyer's units wait in their box.
                pay(server, data, resting.owner(), resting.team(), value - tax);
                tell(server, data, new Notice(resting.owner(), resting.item(), NoticeKind.SOLD, units, value - tax));
                data.addToBox(incoming.owner(), incoming.item(), units);
                amount += value;
            } else {
                // The resting buyer's held money pays the seller; their units wait in their box.
                account.deposit((int) (value - tax));
                data.addToBox(resting.owner(), resting.item(), units);
                tell(server, data, new Notice(resting.owner(), resting.item(), NoticeKind.BOUGHT, units, value));
                amount += value - tax;
                sold += value;
            }
            filled += units;
        }
        if (incoming.buy()) {
            // Units bought below the bid: the difference goes straight back.
            long refund = incoming.price() * filled - amount;
            if (refund > 0) {
                account.deposit((int) refund);
            }
        }
        return new Fill(incoming.withFill(filled, sold), amount);
    }

    // ---- Listings --------------------------------------------------------------------------------------------

    public static Outcome list(ServerPlayer player, int slot, ItemStack expected, long price, int qty) {
        return list(player, ExchangeData.get(player.server), slot, expected, price, qty, today(player.server));
    }

    /**
     * Lists {@code qty} units of the item in main-inventory {@code slot} at {@code price} each. {@code expected} is the
     * item the player saw there; if the slot now holds something else, nothing is listed. The units are taken from
     * that slot first, then from other stacks of exactly the same item.
     */
    public static Outcome list(ServerPlayer player, ExchangeData data, int slot, ItemStack expected, long price, int qty, long day) {
        MarketService.requireServerThread(player);
        NonNullList<ItemStack> items = player.getInventory().items;
        ItemStack held = slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey((held.isEmpty() ? expected : held).getItem());
        if (!MarketConfig.exchangeEnabled()) {
            return Outcome.fail(Action.LIST, Result.EXCHANGE_CLOSED, id);
        }
        if (held.isEmpty() || !ItemStack.isSameItemSameComponents(held, expected)) {
            return Outcome.fail(Action.LIST, Result.NOT_ENOUGH_ITEMS, id);
        }
        if (!listable(held) || MarketVisibility.hidden(player, held.getItem())) {
            return Outcome.fail(Action.LIST, Result.NOT_LISTED, id);
        }
        if (qty <= 0) {
            return Outcome.fail(Action.LIST, Result.INVALID_QUANTITY, id);
        }
        if (price <= 0) {
            return Outcome.fail(Action.LIST, Result.INVALID_PRICE, id);
        }
        // One stack's worth at most, and a total one bank payment can carry.
        if (qty > held.getMaxStackSize() || MarketService.checkedTotal(price, qty).isEmpty()
                || encodedSize(held.copyWithCount(1), player.server.registryAccess()) > MAX_LISTING_BYTES) {
            return Outcome.fail(Action.LIST, Result.TOO_LARGE, id);
        }
        if (data.openOrders(player.getUUID()) >= MarketConfig.maxOpenOrders()) {
            return Outcome.fail(Action.LIST, Result.ORDER_LIMIT, id);
        }
        Bank bank = WorldBank.of(player.server);
        if (bank.activeAccount(player).isEmpty()) {
            return Outcome.fail(Action.LIST, Result.BANK_UNAVAILABLE, id);
        }
        Optional<UUID> team = bank.payingFromTeam(player) ? bank.team(player).map(BankTeam::id) : Optional.empty();
        ItemStack template = held.copyWithCount(1);
        Predicate<ItemStack> same = stack -> ItemStack.isSameItemSameComponents(stack, template);
        if (MarketService.countMatching(items, same) < qty) {
            return Outcome.fail(Action.LIST, Result.NOT_ENOUGH_ITEMS, id);
        }

        int fromSlot = Math.min(qty, held.getCount());
        held.shrink(fromSlot);
        if (held.isEmpty()) {
            items.set(slot, ItemStack.EMPTY);
        }
        MarketService.removeMatching(items, same, qty - fromSlot);
        player.getInventory().setChanged();
        Listing listing = new Listing(data.takeId(), player.getUUID(), team, template, price, qty, 0, day);
        data.add(listing);
        return new Outcome(Action.LIST, Result.OK, id, qty, 0, 0, listing.id(), template);
    }

    public static Outcome buyListing(ServerPlayer player, long id, int qty, long price) {
        return buyListing(player, ExchangeData.get(player.server), id, qty, price);
    }

    /**
     * Buys {@code qty} units of listing {@code id} at {@code price} each, the price the buyer saw. The units go to the
     * buyer's collection box; the seller is paid less tax.
     */
    public static Outcome buyListing(ServerPlayer player, ExchangeData data, long id, int qty, long price) {
        MarketService.requireServerThread(player);
        if (!MarketConfig.exchangeEnabled()) {
            return Outcome.fail(Action.BUY_LISTING, Result.EXCHANGE_CLOSED, none());
        }
        Optional<Listing> found = data.findListing(id);
        if (found.isEmpty()) {
            return Outcome.fail(Action.BUY_LISTING, Result.ORDER_GONE, none());
        }
        Listing listing = found.get();
        if (MarketVisibility.hidden(player, listing.stack().getItem())) {
            return Outcome.fail(Action.BUY_LISTING, Result.NOT_LISTED, listing.item());
        }
        if (listing.owner().equals(player.getUUID())) {
            return Outcome.fail(Action.BUY_LISTING, Result.OWN_LISTING, listing.item());
        }
        if (qty <= 0) {
            return Outcome.fail(Action.BUY_LISTING, Result.INVALID_QUANTITY, listing.item());
        }
        if (qty > listing.remaining()) {
            return Outcome.fail(Action.BUY_LISTING, Result.NO_STOCK, listing.item());
        }
        if (price != listing.price()) {
            return Outcome.fail(Action.BUY_LISTING, Result.PRICE_CHANGED, listing.item());
        }
        // The listing's whole total fit an int when it was posted, so part of it does too.
        long value = listing.price() * qty;
        Optional<Bank.Account> account = WorldBank.of(player.server).activeAccount(player);
        if (account.isEmpty()) {
            return Outcome.fail(Action.BUY_LISTING, Result.BANK_UNAVAILABLE, listing.item());
        }
        if (!account.get().deduct((int) value)) {
            return Outcome.fail(Action.BUY_LISTING, Result.NO_FUNDS, listing.item());
        }

        // Every unit of a listing sells at its price, so what has sold so far is price × sold.
        long tax = taxOnMore(listing.price() * listing.sold(), value, MarketConfig.exchangeTax());
        data.update(listing.withSold(listing.sold() + qty));
        data.count(value, tax);
        pay(player.server, data, listing.owner(), listing.team(), value - tax);
        tell(player.server, data, new Notice(listing.owner(), listing.item(), NoticeKind.SOLD, qty, value - tax,
                customName(listing.stack())));
        data.addParcel(player.getUUID(), listing.stack(), qty);
        return new Outcome(Action.BUY_LISTING, Result.OK, listing.item(), qty, qty, value, listing.id(), listing.stack());
    }

    // ---- Cancelling and expiry -------------------------------------------------------------------------------

    public static Outcome cancel(ServerPlayer player, long id) {
        return cancel(player, ExchangeData.get(player.server), id);
    }

    /** Cancels one of the player's open orders or listings, returning what it still holds. */
    public static Outcome cancel(ServerPlayer player, ExchangeData data, long id) {
        MarketService.requireServerThread(player);
        Optional<Order> found = data.find(id);
        if (found.isEmpty()) {
            Optional<Listing> listing = data.findListing(id);
            if (listing.isEmpty()) {
                return Outcome.fail(Action.CANCEL, Result.ORDER_GONE, none());
            }
            Listing l = listing.get();
            if (!l.owner().equals(player.getUUID())) {
                return Outcome.fail(Action.CANCEL, Result.NOT_YOUR_ORDER, l.item());
            }
            data.removeListing(l.id());
            data.addParcel(l.owner(), l.stack(), l.remaining());
            return new Outcome(Action.CANCEL, Result.OK, l.item(), l.remaining(), 0, 0, l.id(), l.stack());
        }
        Order order = found.get();
        if (!order.owner().equals(player.getUUID())) {
            return Outcome.fail(Action.CANCEL, Result.NOT_YOUR_ORDER, order.item());
        }
        Optional<Bank.Account> account = Optional.empty();
        if (order.buy()) {
            account = PayoutService.payee(player.server, order.owner(), order.team());
            if (account.isEmpty()) {
                return Outcome.fail(Action.CANCEL, Result.BANK_UNAVAILABLE, order.item());
            }
        }

        data.remove(order.id());
        if (order.buy()) {
            account.get().deposit((int) order.heldMoney());
        } else {
            data.addToBox(order.owner(), order.item(), order.remaining());
        }
        return new Outcome(Action.CANCEL, Result.OK, order.item(), order.remaining(), 0, order.heldMoney(), order.id());
    }

    /**
     * Closes an order whoever owns it (expiry, an admin, or the exchange being turned off), returning what it holds:
     * money to its account, or owed if the bank can't be reached; items to the owner's box. The owner is told.
     */
    public static void close(MinecraftServer server, ExchangeData data, Order order) {
        data.remove(order.id());
        if (order.buy()) {
            pay(server, data, order.owner(), order.team(), order.heldMoney());
        } else {
            data.addToBox(order.owner(), order.item(), order.remaining());
        }
        tell(server, data, new Notice(order.owner(), order.item(), NoticeKind.RETURNED, order.buy() ? 0 : order.remaining(),
                order.heldMoney()));
    }

    /** Closes a listing whoever owns it, returning its units to the owner's box. The owner is told. */
    public static void close(MinecraftServer server, ExchangeData data, Listing listing) {
        data.removeListing(listing.id());
        data.addParcel(listing.owner(), listing.stack(), listing.remaining());
        tell(server, data, new Notice(listing.owner(), listing.item(), NoticeKind.LISTING_RETURNED, listing.remaining(), 0,
                customName(listing.stack())));
    }

    /** Closes every order and listing posted at least {@code expiryDays} days before {@code day}. Returns how many. */
    public static int expire(MinecraftServer server, ExchangeData data, long day, int expiryDays) {
        if (expiryDays <= 0) {
            return 0;
        }
        List<Order> due = data.orders().stream().filter(o -> day - o.postedDay() >= expiryDays).toList();
        due.forEach(order -> close(server, data, order));
        List<Listing> dueListings = data.listings().stream().filter(l -> day - l.postedDay() >= expiryDays).toList();
        dueListings.forEach(listing -> close(server, data, listing));
        return due.size() + dueListings.size();
    }

    /**
     * Every tick: pays anything owed, closes every order if the exchange was turned off, and expires old orders.
     * Returns whether the book changed, so open screens can be refreshed.
     */
    public static boolean tick(MinecraftServer server) {
        ExchangeData data = ExchangeData.get(server);
        if (!data.owed().isEmpty()) {
            PayoutService.payOwed(server, data.owed());
        }
        if (data.orders().isEmpty() && data.listings().isEmpty()) {
            return false;
        }
        if (!MarketConfig.exchangeEnabled()) {
            closeAll(server, data);
            return true;
        }
        int expiryDays = MarketConfig.orderExpiryDays();
        if (expiryDays <= 0) {
            return false;
        }
        // Nothing posted since the last check can be due before the day count moves, so check once per day (and
        // again if the expiry setting changes).
        long day = today(server);
        if (!data.expiryCheckDue(day, expiryDays)) {
            return false;
        }
        return expire(server, data, day, expiryDays) > 0;
    }

    /** Closes every open order and listing, as when the exchange is turned off. Collection boxes stay. */
    public static void closeAll(MinecraftServer server, ExchangeData data) {
        List.copyOf(data.orders()).forEach(order -> close(server, data, order));
        List.copyOf(data.listings()).forEach(listing -> close(server, data, listing));
    }

    // ---- The collection box ----------------------------------------------------------------------------------

    public static Outcome collect(ServerPlayer player, Optional<ResourceLocation> only) {
        return collect(player, ExchangeData.get(player.server), only);
    }

    public static Outcome collectParcel(ServerPlayer player, long parcel) {
        return collectParcel(player, ExchangeData.get(player.server), parcel);
    }

    /**
     * Moves what fits of the player's box (all of it, or the plain copies of one item) into free main-inventory
     * space. The rest stays in the box. Works with the exchange turned off, so nothing is ever stranded.
     */
    public static Outcome collect(ServerPlayer player, ExchangeData data, Optional<ResourceLocation> only) {
        List<Parcel> parcels = only.isPresent() ? List.of() : data.parcels(player.getUUID());
        return collect(player, data, only, parcels);
    }

    /** The same for one parcel of the player's box: items with their own components. */
    public static Outcome collectParcel(ServerPlayer player, ExchangeData data, long parcel) {
        List<Parcel> one = data.parcels(player.getUUID()).stream().filter(p -> p.id() == parcel).toList();
        if (one.isEmpty()) {
            MarketService.requireServerThread(player);
            return Outcome.fail(Action.COLLECT, Result.NOT_ENOUGH_ITEMS, none());
        }
        return collect(player, data, Optional.of(none()), one);
    }

    /** Collects the plain items {@code only} names (everything when empty) and the given parcels. */
    private static Outcome collect(ServerPlayer player, ExchangeData data, Optional<ResourceLocation> only, List<Parcel> parcels) {
        MarketService.requireServerThread(player);
        Map<ResourceLocation, Long> box = new LinkedHashMap<>(data.box(player.getUUID()));
        if (only.isPresent()) {
            box.keySet().retainAll(Set.of(only.get()));
        }
        ResourceLocation shown = !parcels.isEmpty() && box.isEmpty() ? id(parcels.get(0).stack())
                : only.filter(item -> !item.equals(none())).orElse(box.isEmpty() ? none() : box.keySet().iterator().next());
        if (box.isEmpty() && parcels.isEmpty()) {
            return Outcome.fail(Action.COLLECT, Result.NOT_ENOUGH_ITEMS, shown);
        }
        NonNullList<ItemStack> items = player.getInventory().items;
        long moved = 0;
        for (Parcel parcel : List.copyOf(parcels)) {
            int fits = (int) Math.min(Math.min(parcel.count(), MarketService.spaceFor(items, parcel.stack())), Integer.MAX_VALUE);
            if (fits > 0) {
                MarketService.insert(items, parcel.stack(), fits);
                data.takeParcel(player.getUUID(), parcel.id(), fits);
                moved += fits;
            }
        }
        for (Map.Entry<ResourceLocation, Long> entry : box.entrySet()) {
            // An item whose mod was removed stays in the box until it's back.
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(entry.getKey()).filter(i -> i != Items.AIR);
            if (item.isEmpty()) {
                continue;
            }
            int fits = (int) Math.min(Math.min(entry.getValue(), MarketService.spaceFor(items, item.get())), Integer.MAX_VALUE);
            if (fits > 0) {
                MarketService.insert(items, item.get(), fits);
                data.takeFromBox(player.getUUID(), entry.getKey(), fits);
                moved += fits;
            }
        }
        if (moved == 0) {
            return Outcome.fail(Action.COLLECT, Result.NO_SPACE, shown);
        }
        player.getInventory().setChanged();
        return new Outcome(Action.COLLECT, Result.OK, shown, (int) Math.min(moved, Integer.MAX_VALUE), 0, 0, 0);
    }

    // ---- Paying and telling ----------------------------------------------------------------------------------

    /** Pays into the account an order's money goes to, or records it as owed if the bank can't be reached. */
    private static void pay(MinecraftServer server, ExchangeData data, UUID player, Optional<UUID> team, long amount) {
        PayoutService.pay(server, data.owed(), "Exchange payment", player, team, amount);
    }

    /** Tells an order's owner what happened to it: in chat if they're online, otherwise when they next log in. */
    private static void tell(MinecraftServer server, ExchangeData data, Notice notice) {
        ServerPlayer online = server.getPlayerList().getPlayer(notice.player());
        if (online != null) {
            online.sendSystemMessage(message(server, notice));
        } else {
            data.addNotice(notice);
        }
    }

    /** Sends a player who just logged in what happened to their orders while they were away, and what waits for them. */
    public static void loggedIn(ServerPlayer player) {
        ExchangeData data = ExchangeData.get(player.server);
        for (Notice notice : data.takeNotices(player.getUUID())) {
            player.sendSystemMessage(message(player.server, notice));
        }
        long waiting = data.box(player.getUUID()).values().stream().mapToLong(Long::longValue).sum()
                + data.parcels(player.getUUID()).stream().mapToLong(Parcel::count).sum();
        if (waiting > 0) {
            player.sendSystemMessage(Component.translatable("message.dynamic_economy.exchange.box_waiting", waiting));
        }
    }

    /** A listed item's custom name, for notices, or empty if it has none. */
    private static String customName(ItemStack stack) {
        return stack.has(DataComponents.CUSTOM_NAME) ? stack.getHoverName().getString() : "";
    }

    private static Component message(MinecraftServer server, Notice notice) {
        Component item = notice.name().isEmpty()
                ? BuiltInRegistries.ITEM.get(notice.item()).getDescription()
                : Component.literal(notice.name());
        return switch (notice.kind()) {
            case SOLD -> Component.translatable("message.dynamic_economy.exchange.sold", notice.quantity(), item, PayoutService.money(server, notice.amount()));
            case BOUGHT -> Component.translatable("message.dynamic_economy.exchange.bought", notice.quantity(), item, PayoutService.money(server, notice.amount()));
            case RETURNED -> notice.quantity() == 0
                    ? Component.translatable("message.dynamic_economy.exchange.returned_money", item, PayoutService.money(server, notice.amount()))
                    : Component.translatable("message.dynamic_economy.exchange.returned_items", notice.quantity(), item);
            case LISTING_RETURNED -> Component.translatable("message.dynamic_economy.exchange.returned_listing", notice.quantity(), item);
        };
    }

    // ---- Viewing ---------------------------------------------------------------------------------------------

    /** The best prices of every item with open orders, leaving out items locked for {@code player}. */
    public static List<Summary> summaries(ServerPlayer player, ExchangeData data) {
        Map<ResourceLocation, long[]> byItem = new LinkedHashMap<>();
        for (Order order : data.orders()) {
            long[] s = byItem.computeIfAbsent(order.item(), i -> new long[4]);
            if (order.buy()) {
                s[2] = Math.max(s[2], order.price());
                s[3] += order.remaining();
            } else {
                s[0] = s[0] == 0 ? order.price() : Math.min(s[0], order.price());
                s[1] += order.remaining();
            }
        }
        List<Summary> summaries = new ArrayList<>();
        byItem.forEach((item, s) -> {
            if (!hiddenFrom(player, item)) {
                summaries.add(new Summary(item, s[0], s[1], s[2], s[3]));
            }
        });
        return summaries;
    }

    /** An item's book, at most {@code depth} prices a side; empty if the item is locked for {@code player}. */
    public static Book book(ServerPlayer player, ExchangeData data, ResourceLocation item, int depth) {
        if (hiddenFrom(player, item)) {
            return new Book(item, List.of(), List.of());
        }
        return new Book(item, levels(player, data.side(item, false), depth), levels(player, data.side(item, true), depth));
    }

    private static List<Level> levels(ServerPlayer player, List<Order> side, int depth) {
        List<Level> levels = new ArrayList<>();
        for (Order order : side) {
            long mine = order.owner().equals(player.getUUID()) ? order.remaining() : 0;
            Level last = levels.isEmpty() ? null : levels.get(levels.size() - 1);
            if (last != null && last.price() == order.price()) {
                levels.set(levels.size() - 1, new Level(last.price(), last.quantity() + order.remaining(), last.mine() + mine));
            } else if (levels.size() < depth) {
                levels.add(new Level(order.price(), order.remaining(), mine));
            } else {
                break;
            }
        }
        return levels;
    }

    /** The order listings are browsed in: by item, then cheapest first, then oldest first. */
    public static final Comparator<Listing> BROWSING_ORDER =
            Comparator.comparing((Listing l) -> l.item().toString()).thenComparingLong(Listing::price).thenComparingLong(Listing::id);

    /** Every listing {@code player} may see, in {@link #BROWSING_ORDER}. */
    public static List<Listing> visibleListings(ServerPlayer player, ExchangeData data) {
        return data.listings().stream()
                .filter(l -> !MarketVisibility.hidden(player, l.stack().getItem()))
                .sorted(BROWSING_ORDER)
                .toList();
    }

    /** The player's listings, oldest first. */
    public static List<Listing> listingsOf(UUID player, ExchangeData data) {
        return data.listings().stream().filter(l -> l.owner().equals(player)).toList();
    }

    /** The player's open orders, oldest first. */
    public static List<Order> ordersOf(UUID player, ExchangeData data) {
        return data.orders().stream().filter(o -> o.owner().equals(player)).toList();
    }

    private static boolean hiddenFrom(ServerPlayer player, ResourceLocation item) {
        return BuiltInRegistries.ITEM.getOptional(item).map(i -> MarketVisibility.hidden(player, i)).orElse(true);
    }

    /**
     * Matches fresh copies of {@code item}, exactly as the market hands them out: the only kind a book order holds, as
     * it keeps just the item ID. {@code ignored_components} doesn't apply, so no data is ever lost on the book.
     */
    public static Predicate<ItemStack> fresh(Item item) {
        ItemStack like = new ItemStack(item);
        return stack -> ItemStack.isSameItemSameComponents(stack, like);
    }

    private static ResourceLocation id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    private static ResourceLocation none() {
        return BuiltInRegistries.ITEM.getDefaultKey();
    }
}
