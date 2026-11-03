package dynamiceconomy.client;

import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.service.ExchangeService;
import dynamiceconomy.service.MarketService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static dynamiceconomy.client.MarketScreen.*;

/**
 * The Exchange tab: trading with other players. Its list switches between items and their order books, other
 * players' listings, the player's own items that can be listed, their open orders and listings, and their collection
 * box. The panel shows the selected item's book and the order controls, or the selected listing or order.
 */
final class ExchangeTab extends ScreenTab {
    /**
     * What the Exchange tab's list shows: items and their books, other players' listings, the player's own items that
     * can be listed, their open orders and listings, or their collection box.
     */
    private enum ExchangeMode { ITEMS, LISTINGS, LIST, ORDERS, BOX }

    private ExchangeMode exchangeMode = ExchangeMode.ITEMS;
    /** Cycles the Exchange tab's list between items, the player's orders and their collection box. */
    private Button exchangeModeButton;
    /** The price each for a new order. */
    private EditBox price;
    /** Post a buy or sell order: where Buy and Sell are, on the Exchange tab's item view. */
    private Button postBuyButton;
    private Button postSellButton;
    /** Cancel the selected order, or take everything out of the box: where Buy is. */
    private Button exchangeActionButton;
    /** On the Market tab's panel: opens the selected item on the Exchange tab. */
    private Button openExchangeButton;
    /** The item selected on the Exchange tab, listed or not. */
    @Nullable
    private ResourceLocation exchangeSelected;
    /** The selected order or listing under My orders; IDs never repeat across the two. */
    @Nullable
    private Long selectedOrder;
    /** The listing selected while browsing listings. */
    @Nullable
    private Long selectedListing;
    /** The main-inventory slot picked to list, and the item it held then; cleared if it no longer does. */
    private int listSlot = -1;
    private ItemStack listStack = ItemStack.EMPTY;
    /** The listings shown, filtered by the search. */
    private final List<MarketPayloads.ListingLine> browseLines = new ArrayList<>();
    /** The Exchange tab's item list: items with orders and items held, or with a search, every item that matches. */
    private final List<ResourceLocation> exchangeItems = new ArrayList<>();
    /** Where each price of the shown book is drawn, so a click can copy it into the price box. */
    private final List<BookRow> bookRows = new ArrayList<>();

    /** The most items a search of every item lists, so a one-letter search doesn't list the whole game. */
    private static final int MAX_SEARCH_RESULTS = 300;

    ExchangeTab(MarketScreen screen) {
        super(screen);
    }

    /**
     * The list mode where Sort and Filter are ({@code sortX} across), the price box above the quantity (beside it
     * when narrow), its own buttons over Buy and Sell, and the Market tab's ⇄ button.
     */
    void init(int x, int y, int sortX) {
        Button buyButton = screen.market().buyButton();
        Button sellButton = screen.market().sellButton();
        String priceText = price != null ? price.getValue() : "";
        exchangeModeButton = screen.add(Button.builder(Component.empty(), b -> cycleExchangeMode())
                .bounds(x + sortX, y + TOOLBAR_Y, 102, 16)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.exchange.mode.tooltip")))
                .build());
        price = new EditBox(screen.font(), x + screen.priceX, y + screen.priceY, screen.priceWidth, 14, Component.translatable("screen.dynamic_economy.exchange.price"));
        price.setHint(Component.translatable("screen.dynamic_economy.exchange.price_hint").withColor(TEXT_DIM));
        price.setFilter(s -> s.isEmpty() || (s.length() <= 10 && s.chars().allMatch(Character::isDigit)));
        price.setValue(priceText);
        screen.add(price);
        postBuyButton = screen.add(Button.builder(Component.empty(), b -> postOrder(true))
                .bounds(buyButton.getX(), buyButton.getY(), buyButton.getWidth(), 18).build());
        postSellButton = screen.add(Button.builder(Component.empty(), b -> postOrder(false))
                .bounds(sellButton.getX(), sellButton.getY(), sellButton.getWidth(), 18).build());
        exchangeActionButton = screen.add(Button.builder(Component.empty(), b -> exchangeAction())
                .bounds(buyButton.getX(), buyButton.getY(), buyButton.getWidth(), 18).build());
        openExchangeButton = screen.add(Button.builder(Component.literal("⇄"), b -> openOnExchange())
                .bounds(x + (screen.wide ? screen.panelX + screen.panelWidth : screen.chartX - 4) - 18, y + screen.panelY - 1, 18, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.exchange.open.tooltip")))
                .build());
    }

    @Override
    void updateWidgets(boolean quantityShown) {
        if (price != null) {
            boolean exchange = isOpen();
            boolean itemsShown = exchange && exchangeMode == ExchangeMode.ITEMS;
            exchangeModeButton.visible = exchange;
            price.visible = showsPrice();
            postBuyButton.visible = itemsShown;
            postSellButton.visible = itemsShown;
            exchangeActionButton.visible = exchange && !itemsShown;
            openExchangeButton.visible = screen.tab == Tab.MARKET && screen.market().selectedRow() != null
                    && Tab.EXCHANGE.available(screen.bankOnly());
            exchangeModeButton.setMessage(exchangeModeLabel());
        }
    }

    /** Whether the price box shows: for a new order or a new listing. */
    private boolean showsPrice() {
        return isOpen() && (exchangeMode == ExchangeMode.ITEMS || exchangeMode == ExchangeMode.LIST);
    }

    @Override
    boolean hides(GuiEventListener focused) {
        return focused == price && !showsPrice();
    }

    @Override
    boolean showsSearch() {
        return exchangeMode == ExchangeMode.ITEMS || exchangeMode == ExchangeMode.LISTINGS;
    }

    @Override
    boolean showsQuantity() {
        return exchangeMode != ExchangeMode.ORDERS && exchangeMode != ExchangeMode.BOX;
    }

    @Override
    boolean showsMarketSelection() {
        return false;
    }

    /** Whether the Market tab's ⇄ button shows, which leaves less room for the item's name. */
    boolean openButtonVisible() {
        return openExchangeButton.visible;
    }

    @Override
    void opened() {
        PacketDistributor.sendToServer(new MarketPayloads.ExchangeRequest(Optional.ofNullable(exchangeSelected), exchangeMode == ExchangeMode.LISTINGS));
    }

    @Override
    int quantityLimit() {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        if (exchangeMode == ExchangeMode.LIST && listSlot >= 0) {
            return listStack.getMaxStackSize();
        }
        return exchange != null ? exchange.maxOrderSize() : ClientMarketState.maxQuantity();
    }

    @Override
    void fillAll() {
        fillExchangeAll();
    }

    @Override
    int listSize() {
        return switch (exchangeMode) {
            case ITEMS -> exchangeItems.size();
            case LISTINGS -> browseLines.size();
            case LIST -> listableSlots().size();
            case ORDERS -> orderLines().size() + myListings().size();
            case BOX -> boxLines().size() + parcelLines().size();
        };
    }

    @Override
    boolean rowClicked(int index) {
                screen.minecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                int orders = orderLines().size();
                int boxed = boxLines().size();
                switch (exchangeMode) {
                    case ITEMS -> selectExchangeItem(exchangeItems.get(index));
                    case LISTINGS -> {
                        long id = browseLines.get(index).id();
                        if (!Long.valueOf(id).equals(selectedListing)) {
                            selectedListing = id;
                            screen.setQuantity(1, Component.empty());
                        }
                    }
                    case LIST -> {
                        int slot = listableSlots().get(index);
                        if (slot != listSlot) {
                            listSlot = slot;
                            listStack = slotStack(slot).copyWithCount(1);
                            screen.setQuantity(Math.min(heldLike(listStack), listStack.getMaxStackSize()), Component.empty());
                        }
                    }
                    case ORDERS -> selectedOrder = index < orders ? orderLines().get(index).id() : myListings().get(index - orders).id();
                    // Clicking a box line takes that item out.
                    case BOX -> PacketDistributor.sendToServer(index < boxed
                            ? MarketPayloads.ExchangeAction.collect(Optional.of(boxLines().get(index).item()))
                            : MarketPayloads.ExchangeAction.collectParcel(parcelLines().get(index - boxed).id()));
                }
                screen.setFocused(null);
                return true;
    }

    @Override
    boolean panelClicked(double mouseX, double mouseY) {
            BookRow bookRow = bookRowAt(mouseX, mouseY);
            if (bookRow != null) {
                // Clicking a price in the book copies it into the price box.
                screen.minecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                price.setValue(String.valueOf(bookRow.price()));
                return true;
            }
        return false;
    }

    @Override
    void renderTooltip(GuiGraphics graphics, int index, int mouseX, int mouseY) {
        if (index >= 0 && exchangeMode == ExchangeMode.ITEMS) {
            graphics.renderTooltip(screen.font(), exchangeTooltip(exchangeItems.get(index)), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && exchangeMode == ExchangeMode.LISTINGS) {
            graphics.renderTooltip(screen.font(), listingTooltip(browseLines.get(index)), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && exchangeMode == ExchangeMode.LIST) {
            graphics.renderTooltip(screen.font(), getTooltipFromItem(screen.minecraft(), slotStack(listableSlots().get(index))), Optional.empty(), mouseX, mouseY);
        } else if (index >= orderLines().size() && exchangeMode == ExchangeMode.ORDERS) {
            graphics.renderTooltip(screen.font(), listingTooltip(myListings().get(index - orderLines().size())), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && exchangeMode == ExchangeMode.BOX) {
            List<Component> lines = new ArrayList<>();
            if (index >= boxLines().size()) {
                lines.addAll(getTooltipFromItem(screen.minecraft(), parcelLines().get(index - boxLines().size()).stack()));
            }
            lines.add(Component.translatable("screen.dynamic_economy.exchange.box.click").withColor(TEXT_DIM));
            graphics.renderTooltip(screen.font(), lines, Optional.empty(), mouseX, mouseY);
        } else if (bookRowAt(mouseX, mouseY) != null) {
            BookRow row = bookRowAt(mouseX, mouseY);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("screen.dynamic_economy.exchange.book.click"));
            if (row.mine() > 0) {
                lines.add(Component.translatable("screen.dynamic_economy.exchange.book.yours", PriceFormat.count(row.mine())).withColor(TEXT_DIM));
            }
            graphics.renderComponentTooltip(screen.font(), lines, mouseX, mouseY);
        }
    }

    @Override
    void renderList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        renderExchangeList(graphics, x, y, mouseX, mouseY);
    }

    @Override
    void renderPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        bookRows.clear();
        renderExchangePanel(graphics, x, y, mouseX, mouseY);
    }

    /**
     * Without a search: items with orders, then items the player holds. With one: every tradeable item that matches,
     * those with orders first. Each group is sorted by name.
     */
    void rebuildExchangeItems(String query) {
        browseLines.clear();
        MarketPayloads.ExchangeListings listings = ClientMarketState.exchangeListings();
        if (listings != null) {
            for (MarketPayloads.ListingLine line : listings.listings()) {
                if (matches(BuiltInRegistries.ITEM.getKey(line.stack().getItem()), query)
                        || line.stack().getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) {
                    browseLines.add(line);
                }
            }
        }
        exchangeItems.clear();
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        List<ResourceLocation> withOrders = new ArrayList<>();
        if (exchange != null) {
            exchange.summaries().forEach(s -> withOrders.add(s.item()));
        }
        List<ResourceLocation> others = new ArrayList<>();
        if (query.isEmpty()) {
            ClientMarketState.heldItems().stream().filter(id -> !withOrders.contains(id) && freshHeld(id) > 0).forEach(others::add);
        } else {
            withOrders.removeIf(id -> !matches(id, query));
            for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                if (others.size() >= MAX_SEARCH_RESULTS) {
                    break;
                }
                if (!withOrders.contains(id) && ExchangeService.tradeable(BuiltInRegistries.ITEM.get(id)) && matches(id, query)) {
                    others.add(id);
                }
            }
        }
        java.util.Comparator<ResourceLocation> byName = java.util.Comparator.comparing((ResourceLocation id) -> displayName(id));
        withOrders.sort(byName);
        others.sort(byName);
        exchangeItems.addAll(withOrders);
        exchangeItems.addAll(others);
    }

    // ---- Exchange --------------------------------------------------------------------------------------------

    private Component exchangeModeLabel() {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        return switch (exchangeMode) {
            case ITEMS -> Component.translatable("screen.dynamic_economy.exchange.mode.items");
            case LISTINGS -> Component.translatable("screen.dynamic_economy.exchange.mode.listings");
            case LIST -> Component.translatable("screen.dynamic_economy.exchange.mode.list");
            case ORDERS -> Component.translatable("screen.dynamic_economy.exchange.mode.orders", exchange == null ? 0 : exchange.open());
            case BOX -> Component.translatable("screen.dynamic_economy.exchange.mode.box",
                    PriceFormat.count(exchange == null ? 0 : exchange.boxed()));
        };
    }

    private void cycleExchangeMode() {
        ExchangeMode was = exchangeMode;
        exchangeMode = ExchangeMode.values()[(exchangeMode.ordinal() + 1) % ExchangeMode.values().length];
        // The server sends every listing only while they're browsed.
        if (was == ExchangeMode.LISTINGS || exchangeMode == ExchangeMode.LISTINGS) {
            PacketDistributor.sendToServer(new MarketPayloads.ExchangeRequest(Optional.ofNullable(exchangeSelected),
                    exchangeMode == ExchangeMode.LISTINGS));
        }
        screen.scroll = 0;
        screen.listDirty = true;
        screen.updateListButtons();
    }

    private static List<MarketPayloads.OrderLine> orderLines() {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        return exchange == null ? List.of() : exchange.orders();
    }

    private static List<MarketPayloads.BoxLine> boxLines() {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        return exchange == null ? List.of() : exchange.box();
    }

    private static List<MarketPayloads.ParcelLine> parcelLines() {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        return exchange == null ? List.of() : exchange.parcels();
    }

    /** The player's own listings, listed after their orders under My orders. */
    private static List<MarketPayloads.ListingLine> myListings() {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        return exchange == null ? List.of() : exchange.listings();
    }

    @Nullable
    private MarketPayloads.ListingLine selectedMyListing() {
        for (MarketPayloads.ListingLine line : myListings()) {
            if (Long.valueOf(line.id()).equals(selectedOrder)) {
                return line;
            }
        }
        return null;
    }

    @Nullable
    private MarketPayloads.ListingLine selectedBrowseListing() {
        MarketPayloads.ExchangeListings listings = ClientMarketState.exchangeListings();
        if (listings != null) {
            for (MarketPayloads.ListingLine line : listings.listings()) {
                if (Long.valueOf(line.id()).equals(selectedListing)) {
                    return line;
                }
            }
        }
        return null;
    }

    /** Main-inventory slots holding something that can be listed, as the server checks it. */
    private List<Integer> listableSlots() {
        List<Integer> slots = new ArrayList<>();
        List<ItemStack> items = screen.minecraft().player.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) {
            if (ExchangeService.listable(items.get(slot))) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private ItemStack slotStack(int slot) {
        return screen.minecraft().player.getInventory().items.get(slot);
    }

    /** How many fresh copies of {@code item} the main inventory holds: what a sell order on the book can take. */
    private int freshHeld(ResourceLocation item) {
        return heldLike(new ItemStack(BuiltInRegistries.ITEM.get(item)));
    }

    /** How many units of exactly {@code like} the main inventory holds. */
    private int heldLike(ItemStack like) {
        int count = 0;
        for (ItemStack stack : screen.minecraft().player.getInventory().items) {
            if (ItemStack.isSameItemSameComponents(stack, like)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Keeps the item picked to list in step with the inventory: follows it to another slot, or drops it. */
    private void checkListSlot() {
        if (listSlot < 0 || ItemStack.isSameItemSameComponents(slotStack(listSlot), listStack)) {
            return;
        }
        for (int slot : listableSlots()) {
            if (ItemStack.isSameItemSameComponents(slotStack(slot), listStack)) {
                listSlot = slot;
                return;
            }
        }
        listSlot = -1;
        listStack = ItemStack.EMPTY;
    }

    @Nullable
    private MarketPayloads.OrderLine selectedOrderLine() {
        for (MarketPayloads.OrderLine line : orderLines()) {
            if (Long.valueOf(line.id()).equals(selectedOrder)) {
                return line;
            }
        }
        return null;
    }

    /** The best prices for an item, or null if it has no orders. */
    @Nullable
    private static ExchangeService.Summary summary(ResourceLocation item) {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        if (exchange != null) {
            for (ExchangeService.Summary summary : exchange.summaries()) {
                if (summary.item().equals(item)) {
                    return summary;
                }
            }
        }
        return null;
    }

    private long priceValue() {
        try {
            return Math.max(0, Long.parseLong(price.getValue()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Selects an item on the Exchange tab, asks for its book, and starts the price at the best one on offer. */
    private void selectExchangeItem(ResourceLocation item) {
        if (item.equals(exchangeSelected)) {
            return;
        }
        exchangeSelected = item;
        PacketDistributor.sendToServer(new MarketPayloads.ExchangeRequest(Optional.of(item), false));
        ExchangeService.Summary s = summary(item);
        long start = s != null && s.bestAsk() > 0 ? s.bestAsk() : s != null && s.bestBid() > 0 ? s.bestBid() : marketSellPrice(item);
        price.setValue(start > 0 ? String.valueOf(start) : "");
    }

    /** What the market pays for a listed item, a fair first price to ask for it; 0 when the market doesn't buy it. */
    private static long marketSellPrice(ResourceLocation item) {
        MarketPayloads.Row row = ClientMarketState.rows().get(item);
        return row != null && row.canSell() ? row.sellPrice() : 0;
    }

    /** "All" on the Exchange tab: every copy held, up to the largest order. */
    private void fillExchangeAll() {
        if (exchangeMode == ExchangeMode.LISTINGS) {
            MarketPayloads.ListingLine line = selectedBrowseListing();
            if (line != null) {
                screen.setQuantity(line.remaining(), Component.empty());
            }
            return;
        }
        if (exchangeMode == ExchangeMode.LIST) {
            if (listSlot >= 0) {
                screen.setQuantity(Math.min(heldLike(listStack), listStack.getMaxStackSize()), Component.empty());
            }
            return;
        }
        if (exchangeSelected == null) {
            return;
        }
        int held = freshHeld(exchangeSelected);
        if (held == 0) {
            screen.quantityNote = Component.translatable("screen.dynamic_economy.note.none_held");
            screen.quantityNoteFor = screen.quantityValue();
            return;
        }
        screen.setQuantity(Math.min(held, quantityLimit()), Component.empty());
    }

    /** Why the order can't be posted, as far as the client can tell, or null if it looks fine. The server re-checks. */
    @Nullable
    private Component postProblem(boolean buy, int qty, long each) {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        String problem;
        if (!ClientMarketState.exchangeEnabled()) {
            problem = "exchange_closed";
        } else if (qty <= 0) {
            problem = "invalid_quantity";
        } else if (each <= 0) {
            problem = "invalid_price";
        } else if (exchange != null && qty > exchange.maxOrderSize() || MarketService.checkedTotal(each, qty).isEmpty()) {
            problem = "too_large";
        } else if (exchange != null && exchange.open() >= exchange.maxOrders()) {
            problem = "order_limit";
        } else if (buy && !ClientMarketState.looksAffordable(MarketService.checkedTotal(each, qty).getAsLong())) {
            problem = "no_funds";
        } else if (!buy && exchangeSelected != null && freshHeld(exchangeSelected) < qty) {
            problem = "not_enough_items";
        } else {
            return null;
        }
        return Component.translatable("screen.dynamic_economy.result." + problem);
    }

    /** Why the listing can't be bought, as far as the client can tell, or null if it looks fine. */
    @Nullable
    private Component buyListingProblem(MarketPayloads.ListingLine line, int qty) {
        String problem;
        if (!ClientMarketState.exchangeEnabled()) {
            problem = "exchange_closed";
        } else if (line.mine()) {
            problem = "own_listing";
        } else if (qty <= 0) {
            problem = "invalid_quantity";
        } else if (qty > line.remaining()) {
            problem = "no_stock";
        } else if (!ClientMarketState.looksAffordable(MarketService.checkedTotal(line.price(), qty).orElse(Long.MAX_VALUE))) {
            problem = "no_funds";
        } else {
            return null;
        }
        return Component.translatable("screen.dynamic_economy.result." + problem);
    }

    /** Why the picked item can't be listed, as far as the client can tell, or null if it looks fine. */
    @Nullable
    private Component listProblem(int qty, long each) {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        String problem;
        if (!ClientMarketState.exchangeEnabled()) {
            problem = "exchange_closed";
        } else if (qty <= 0) {
            problem = "invalid_quantity";
        } else if (each <= 0) {
            problem = "invalid_price";
        } else if (qty > listStack.getMaxStackSize() || MarketService.checkedTotal(each, qty).isEmpty()) {
            problem = "too_large";
        } else if (exchange != null && exchange.open() >= exchange.maxOrders()) {
            problem = "order_limit";
        } else if (heldLike(listStack) < qty) {
            problem = "not_enough_items";
        } else {
            return null;
        }
        return Component.translatable("screen.dynamic_economy.result." + problem);
    }

    /** Client-side hints only; the server re-checks everything and replies with the real outcome. */
    void refreshExchangeButtons() {
        openExchangeButton.visible = screen.tab == Tab.MARKET && screen.market().selectedRow() != null && Tab.EXCHANGE.available(screen.bankOnly());
        if (screen.tab != Tab.EXCHANGE) {
            return;
        }
        exchangeModeButton.setMessage(exchangeModeLabel());
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        switch (exchangeMode) {
            case ITEMS -> {
                int qty = screen.quantityValue();
                if (qty != screen.quantityNoteFor) {
                    screen.quantityNote = Component.empty();
                }
                long each = priceValue();
                long total = qty > 0 && each > 0 ? MarketService.checkedTotal(each, qty).orElse(0) : 0;
                long tax = ExchangeService.tax(total, exchange == null ? 0 : exchange.tax());
                postBuyButton.setMessage(total > 0 && exchangeSelected != null
                        ? Component.translatable("screen.dynamic_economy.exchange.buy_for", PriceFormat.amount(qty), PriceFormat.amount(total))
                        : Component.translatable("screen.dynamic_economy.exchange.buy"));
                postSellButton.setMessage(total > 0 && exchangeSelected != null
                        ? Component.translatable("screen.dynamic_economy.exchange.sell_for", PriceFormat.amount(qty), PriceFormat.amount(total))
                        : Component.translatable("screen.dynamic_economy.exchange.sell"));
                Component buyProblem = exchangeSelected == null ? Component.translatable("screen.dynamic_economy.exchange.select_hint")
                        : postProblem(true, qty, each);
                Component sellProblem = exchangeSelected == null ? buyProblem : postProblem(false, qty, each);
                postBuyButton.active = buyProblem == null;
                postSellButton.active = sellProblem == null;
                postBuyButton.setTooltip(Tooltip.create(buyProblem != null ? buyProblem
                        : Component.translatable("screen.dynamic_economy.exchange.buy.tooltip", PriceFormat.money(PriceFormat.amount(total)))));
                postSellButton.setTooltip(Tooltip.create(sellProblem != null ? sellProblem
                        : Component.translatable("screen.dynamic_economy.exchange.sell.tooltip", PriceFormat.money(PriceFormat.amount(total - tax)),
                                PriceFormat.money(PriceFormat.amount(tax)))));
            }
            case LISTINGS -> {
                int qty = screen.quantityValue();
                if (qty != screen.quantityNoteFor) {
                    screen.quantityNote = Component.empty();
                }
                MarketPayloads.ListingLine line = selectedBrowseListing();
                long total = line == null || qty <= 0 ? 0 : MarketService.checkedTotal(line.price(), qty).orElse(0);
                exchangeActionButton.setMessage(total > 0
                        ? Component.translatable("screen.dynamic_economy.exchange.buy_for", PriceFormat.amount(qty), PriceFormat.amount(total))
                        : Component.translatable("screen.dynamic_economy.exchange.buy_listing"));
                Component problem = line == null ? Component.translatable("screen.dynamic_economy.exchange.listings.select_hint")
                        : buyListingProblem(line, qty);
                exchangeActionButton.active = problem == null;
                exchangeActionButton.setTooltip(Tooltip.create(problem != null ? problem
                        : Component.translatable("screen.dynamic_economy.exchange.buy_listing.tooltip", PriceFormat.money(PriceFormat.amount(total)))));
            }
            case LIST -> {
                checkListSlot();
                int qty = screen.quantityValue();
                if (qty != screen.quantityNoteFor) {
                    screen.quantityNote = Component.empty();
                }
                long each = priceValue();
                long total = qty > 0 && each > 0 ? MarketService.checkedTotal(each, qty).orElse(0) : 0;
                long tax = ExchangeService.tax(total, exchange == null ? 0 : exchange.tax());
                exchangeActionButton.setMessage(total > 0 && listSlot >= 0
                        ? Component.translatable("screen.dynamic_economy.exchange.list_for", PriceFormat.amount(qty), PriceFormat.amount(total))
                        : Component.translatable("screen.dynamic_economy.exchange.list"));
                Component problem = listSlot < 0 ? Component.translatable("screen.dynamic_economy.exchange.list.select_hint")
                        : listProblem(qty, each);
                exchangeActionButton.active = problem == null;
                exchangeActionButton.setTooltip(Tooltip.create(problem != null ? problem
                        : Component.translatable("screen.dynamic_economy.exchange.list.tooltip", PriceFormat.money(PriceFormat.amount(total - tax)),
                                PriceFormat.money(PriceFormat.amount(tax)))));
            }
            case ORDERS -> {
                MarketPayloads.OrderLine line = selectedOrderLine();
                MarketPayloads.ListingLine listing = selectedMyListing();
                exchangeActionButton.setMessage(Component.translatable("screen.dynamic_economy.exchange.cancel"));
                exchangeActionButton.active = line != null || listing != null;
                exchangeActionButton.setTooltip(listing != null
                        ? Tooltip.create(Component.translatable("screen.dynamic_economy.exchange.cancel.items", PriceFormat.count(listing.remaining())))
                        : line == null ? null : Tooltip.create(line.buy()
                        ? Component.translatable("screen.dynamic_economy.exchange.cancel.money",
                                PriceFormat.money(PriceFormat.amount(line.price() * line.remaining())))
                        : Component.translatable("screen.dynamic_economy.exchange.cancel.items", PriceFormat.count(line.remaining()))));
            }
            case BOX -> {
                long boxed = exchange == null ? 0 : exchange.boxed();
                exchangeActionButton.setMessage(Component.translatable("screen.dynamic_economy.exchange.take_all", PriceFormat.count(boxed)));
                exchangeActionButton.active = boxed > 0;
                exchangeActionButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.exchange.take_all.tooltip")));
            }
        }
    }

    private void postOrder(boolean buy) {
        int qty = screen.quantityValue();
        long each = priceValue();
        if (exchangeSelected != null && postProblem(buy, qty, each) == null) {
            PacketDistributor.sendToServer(MarketPayloads.ExchangeAction.post(exchangeSelected, buy, each, qty));
        }
    }

    /** Buys from the selected listing, lists the picked item, cancels the selected order, or empties the box. */
    private void exchangeAction() {
        int qty = screen.quantityValue();
        switch (exchangeMode) {
            case LISTINGS -> {
                MarketPayloads.ListingLine line = selectedBrowseListing();
                if (line != null && buyListingProblem(line, qty) == null) {
                    PacketDistributor.sendToServer(MarketPayloads.ExchangeAction.buyListing(line.id(), qty, line.price()));
                }
            }
            case LIST -> {
                long each = priceValue();
                if (listSlot >= 0 && listProblem(qty, each) == null) {
                    PacketDistributor.sendToServer(new MarketPayloads.ListItem(listSlot, listStack.copy(), each, qty));
                }
            }
            case ORDERS -> {
                if (selectedOrderLine() != null || selectedMyListing() != null) {
                    PacketDistributor.sendToServer(MarketPayloads.ExchangeAction.cancel(selectedOrder));
                }
            }
            case BOX -> PacketDistributor.sendToServer(MarketPayloads.ExchangeAction.collect(Optional.empty()));
            default -> {
            }
        }
    }

    /** The Market tab's ⇄ button: the selected item on the Exchange tab. */
    private void openOnExchange() {
        MarketPayloads.Row row = screen.market().selectedRow();
        if (row == null) {
            return;
        }
        exchangeMode = ExchangeMode.ITEMS;
        selectExchangeItem(row.item());
        screen.selectTab(Tab.EXCHANGE);
        screen.updateListButtons();
    }

    /** One price drawn in the Exchange tab's book, in screen coordinates. */
    private record BookRow(int left, int top, int right, int bottom, long price, long mine) {
    }

    @Nullable
    private BookRow bookRowAt(double mouseX, double mouseY) {
        if (screen.tab != Tab.EXCHANGE || exchangeMode != ExchangeMode.ITEMS) {
            return null;
        }
        for (BookRow row : bookRows) {
            if (mouseX >= row.left() && mouseX < row.right() && mouseY >= row.top() && mouseY < row.bottom()) {
                return row;
            }
        }
        return null;
    }

    /** The Exchange tab's list: items with their best prices, the player's open orders, or their collection box. */
    private void renderExchangeList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        int listRight = LIST_X + screen.listWidth;
        int headerY = y + HEADER_Y;
        int hovered = screen.rowAt(mouseX, mouseY);
        int size = listSize();
        switch (exchangeMode) {
            case ITEMS -> {
                graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
                screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.col.ask"), x + screen.colBuy, headerY, TEXT_DIM);
                screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.col.bid"), x + screen.colSell, headerY, TEXT_DIM);
                screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.col.have"), x + screen.colHave, headerY, TEXT_DIM);
            }
            case LISTINGS -> {
                graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
                screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.col.price"), x + screen.colSell, headerY, TEXT_DIM);
                screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.col.left"), x + screen.colHave, headerY, TEXT_DIM);
            }
            case LIST -> {
                graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
                screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.col.have"), x + screen.colHave, headerY, TEXT_DIM);
            }
            case ORDERS -> graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.exchange.orders.title",
                    exchange == null ? 0 : exchange.open(), exchange == null ? 0 : exchange.maxOrders()), x + LIST_X + 4, headerY, TEXT_DIM, false);
            case BOX -> {
                graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.exchange.box.title"), x + LIST_X + 4, headerY, TEXT_DIM, false);
                screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.col.waiting"), x + listRight - 2, headerY, TEXT_DIM);
            }
        }
        List<Integer> slots = exchangeMode == ExchangeMode.LIST ? listableSlots() : List.of();
        int orders = orderLines().size();
        int boxed = boxLines().size();
        for (int i = 0; i < screen.rows && screen.scroll + i < size; i++) {
            int index = screen.scroll + i;
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            boolean picked = switch (exchangeMode) {
                case ITEMS -> exchangeItems.get(index).equals(exchangeSelected);
                case LISTINGS -> Long.valueOf(browseLines.get(index).id()).equals(selectedListing);
                case LIST -> slots.get(index) == listSlot;
                case ORDERS -> Long.valueOf(index < orders ? orderLines().get(index).id() : myListings().get(index - orders).id()).equals(selectedOrder);
                case BOX -> false;
            };
            int background = picked ? ROW_SELECTED : index == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            int textY = rowY + 5;
            switch (exchangeMode) {
                case ITEMS -> {
                    ResourceLocation item = exchangeItems.get(index);
                    graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(item)), x + LIST_X + 2, rowY + 1);
                    graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(displayName(item), screen.colBuy - 44 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
                    ExchangeService.Summary s = summary(item);
                    boolean asks = s != null && s.bestAsk() > 0;
                    boolean bids = s != null && s.bestBid() > 0;
                    screen.drawRight(graphics, Component.literal(asks ? PriceFormat.compact(s.bestAsk()) : "-"), x + screen.colBuy, textY, asks ? TEXT_MONEY : TEXT_DIM);
                    screen.drawRight(graphics, Component.literal(bids ? PriceFormat.compact(s.bestBid()) : "-"), x + screen.colSell, textY, bids ? TEXT_GOOD : TEXT_DIM);
                    int held = freshHeld(item);
                    screen.drawRight(graphics, Component.literal(PriceFormat.count(held)), x + screen.colHave, textY, held > 0 ? TEXT : TEXT_DIM);
                }
                case LISTINGS -> {
                    MarketPayloads.ListingLine line = browseLines.get(index);
                    screen.renderStack(graphics, line.stack(), x + LIST_X + 2, rowY + 1);
                    String name = line.stack().getHoverName().getString() + (line.mine() ? " *" : "");
                    graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(name, screen.colSell - 44 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
                    screen.drawRight(graphics, Component.literal(PriceFormat.compact(line.price())), x + screen.colSell, textY, TEXT_MONEY);
                    screen.drawRight(graphics, Component.literal(PriceFormat.count(line.remaining())), x + screen.colHave, textY, TEXT);
                }
                case LIST -> {
                    ItemStack stack = slotStack(slots.get(index));
                    screen.renderStack(graphics, stack, x + LIST_X + 2, rowY + 1);
                    graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(stack.getHoverName().getString(), screen.colHave - 44 - LIST_X - 22),
                            x + LIST_X + 22, textY, TEXT, false);
                    screen.drawRight(graphics, Component.literal(PriceFormat.count(heldLike(stack))), x + screen.colHave, textY, TEXT);
                }
                case ORDERS -> {
                    if (index >= orders) {
                        renderListingRow(graphics, myListings().get(index - orders), x, rowY, textY, listRight);
                        continue;
                    }
                    MarketPayloads.OrderLine line = orderLines().get(index);
                    int priceRight = listRight - 60;
                    graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(line.item())), x + LIST_X + 2, rowY + 1);
                    Component what = Component.translatable(line.buy() ? "screen.dynamic_economy.exchange.order.buy"
                            : "screen.dynamic_economy.exchange.order.sell", PriceFormat.amount(line.quantity()), displayName(line.item()));
                    graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(what.getString(), priceRight - 50 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
                    screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.order.each", PriceFormat.amount(line.price())),
                            x + priceRight, textY, line.buy() ? TEXT_MONEY : TEXT_GOOD);
                    screen.drawRight(graphics, Component.literal(PriceFormat.count(line.filled()) + "/" + PriceFormat.count(line.quantity())),
                            x + listRight - 2, textY, line.filled() > 0 ? TEXT : TEXT_DIM);
                }
                case BOX -> {
                    if (index >= boxed) {
                        MarketPayloads.ParcelLine parcel = parcelLines().get(index - boxed);
                        screen.renderStack(graphics, parcel.stack(), x + LIST_X + 2, rowY + 1);
                        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(parcel.stack().getHoverName().getString(), screen.listWidth - 90),
                                x + LIST_X + 22, textY, TEXT, false);
                        screen.drawRight(graphics, Component.literal(PriceFormat.count(parcel.count())), x + listRight - 2, textY, TEXT);
                        continue;
                    }
                    MarketPayloads.BoxLine line = boxLines().get(index);
                    graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(line.item())), x + LIST_X + 2, rowY + 1);
                    graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(displayName(line.item()), screen.listWidth - 90), x + LIST_X + 22, textY, TEXT, false);
                    screen.drawRight(graphics, Component.literal(PriceFormat.count(line.count())), x + listRight - 2, textY, TEXT);
                }
            }
        }
        if (size == 0) {
            String key = exchange == null ? "screen.dynamic_economy.trades.loading" : switch (exchangeMode) {
                case ITEMS -> screen.search.getValue().isBlank() ? "screen.dynamic_economy.exchange.none_items" : "screen.dynamic_economy.no_matches";
                case LISTINGS -> ClientMarketState.exchangeListings() == null ? "screen.dynamic_economy.trades.loading"
                        : screen.search.getValue().isBlank() ? "screen.dynamic_economy.exchange.none_listings" : "screen.dynamic_economy.no_matches";
                case LIST -> "screen.dynamic_economy.exchange.none_listable";
                case ORDERS -> "screen.dynamic_economy.exchange.none_orders";
                case BOX -> "screen.dynamic_economy.exchange.none_box";
            };
            // Long enough to need wrapping when it explains what can be listed.
            List<net.minecraft.util.FormattedCharSequence> lines = screen.font().split(Component.translatable(key), screen.listWidth - 8);
            for (int i = 0; i < lines.size(); i++) {
                graphics.drawCenteredString(screen.font(), lines.get(i), x + LIST_X + screen.listWidth / 2, y + LIST_Y + 20 + i * 10, TEXT_DIM);
            }
        } else if (exchangeMode == ExchangeMode.LISTINGS && ClientMarketState.exchangeListings() != null
                && ClientMarketState.exchangeListings().more() && screen.scroll + screen.rows >= size) {
            graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.exchange.listings.more"), x + LIST_X + 4,
                    y + LIST_Y + Math.min(screen.rows, size) * ROW_HEIGHT + 2, TEXT_DIM, false);
        }
    }

    /** One of the player's listings under My orders, drawn like an order. */
    private void renderListingRow(GuiGraphics graphics, MarketPayloads.ListingLine line, int x, int rowY, int textY, int listRight) {
        int priceRight = listRight - 60;
        screen.renderStack(graphics, line.stack(), x + LIST_X + 2, rowY + 1);
        Component what = Component.translatable("screen.dynamic_economy.exchange.order.listing", PriceFormat.amount(line.quantity()),
                line.stack().getHoverName().getString());
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(what.getString(), priceRight - 50 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
        screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.order.each", PriceFormat.amount(line.price())),
                x + priceRight, textY, TEXT_GOOD);
        screen.drawRight(graphics, Component.literal(PriceFormat.count(line.sold()) + "/" + PriceFormat.count(line.quantity())),
                x + listRight - 2, textY, line.sold() > 0 ? TEXT : TEXT_DIM);
    }

    /** A listed item's own tooltip, then who sells it and for how much. */
    private List<Component> listingTooltip(MarketPayloads.ListingLine line) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(screen.minecraft(), line.stack()));
        lines.add(Component.translatable("screen.dynamic_economy.exchange.listing.offer", PriceFormat.count(line.remaining()),
                PriceFormat.money(PriceFormat.amount(line.price()))).withColor(TEXT_MONEY));
        lines.add((line.mine() ? Component.translatable("screen.dynamic_economy.exchange.listing.yours")
                : Component.translatable("screen.dynamic_economy.exchange.listing.by", line.seller())).withColor(TEXT_DIM));
        return lines;
    }

    /** The item's tooltip below its name, from {@code top} down to {@code bottom}, so a buyer sees it in full. */
    private void renderItemDetails(GuiGraphics graphics, ItemStack stack, int left, int top, int bottom) {
        List<Component> lines = getTooltipFromItem(screen.minecraft(), stack);
        int y = top;
        // The first line is the name, shown above already.
        for (int i = 1; i < lines.size(); i++) {
            for (net.minecraft.util.FormattedCharSequence part : screen.font().split(lines.get(i), screen.panelWidth)) {
                if (y + 9 > bottom) {
                    return;
                }
                graphics.drawString(screen.font(), part, left, y, TEXT_DIM, false);
                y += 10;
            }
        }
    }

    /** Browsing listings: the selected listing in full, and what buying the quantity costs. */
    private void renderListingPanel(GuiGraphics graphics, int x, int y, String percent) {
        int left = x + screen.panelX;
        MarketPayloads.ListingLine line = selectedBrowseListing();
        if (line == null) {
            renderHelp(graphics, Component.translatable(screen.wide ? "screen.dynamic_economy.exchange.listings.help"
                    : "screen.dynamic_economy.exchange.listings.select_hint", percent), left, y + screen.panelY + 4);
            return;
        }
        screen.renderStack(graphics, line.stack(), left, y + screen.panelY);
        String name = line.stack().getHoverName().getString();
        if (screen.wide) {
            int right = left + screen.panelWidth;
            int top = y + screen.chartY;
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(name, screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            screen.infoLine(graphics, "exchange.listing.seller", line.mine() ? Component.translatable("screen.dynamic_economy.exchange.listing.you").getString()
                    : line.seller(), TEXT, left, right, top);
            screen.infoLine(graphics, "exchange.order.price", PriceFormat.amount(line.price()), TEXT_MONEY, left, right, top + INFO_LINE_HEIGHT);
            screen.infoLine(graphics, "exchange.listing.left", PriceFormat.count(line.remaining()), TEXT, left, right, top + 2 * INFO_LINE_HEIGHT);
            renderItemDetails(graphics, line.stack(), left, top + 3 * INFO_LINE_HEIGHT + 4, y + screen.controlsY - 4);
        } else {
            Component summary = Component.translatable("screen.dynamic_economy.exchange.listing.summary", name, PriceFormat.amount(line.price()),
                    PriceFormat.count(line.remaining()), line.seller());
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(summary.getString(), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
        }
        int qty = screen.quantityValue();
        long total = qty > 0 ? MarketService.checkedTotal(line.price(), qty).orElse(0) : 0;
        Component note = !screen.quantityNote.getString().isEmpty() ? screen.quantityNote
                : total > 0 ? Component.translatable("screen.dynamic_economy.exchange.listing.total", PriceFormat.amount(total)) : Component.empty();
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(note.getString(), screen.noteWidth), x + screen.noteX, y + screen.noteY, TEXT_DIM, false);
    }

    /** Listing an item: the picked item in full, the price box, and what the listing comes to. */
    private void renderListPanel(GuiGraphics graphics, int x, int y, String percent) {
        int left = x + screen.panelX;
        if (listSlot < 0) {
            renderHelp(graphics, Component.translatable(screen.wide ? "screen.dynamic_economy.exchange.list.help"
                    : "screen.dynamic_economy.exchange.list.select_hint", percent), left, y + screen.panelY + 4);
            return;
        }
        screen.renderStack(graphics, listStack, left, y + screen.panelY);
        String name = listStack.getHoverName().getString();
        if (!screen.wide) {
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(name, screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            graphics.drawString(screen.font(), "@", x + screen.noteX, y + screen.controlsY + 5, TEXT_DIM, false);
            return;
        }
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(name, screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
        renderItemDetails(graphics, listStack, left, y + screen.chartY, y + screen.priceY - 14);
        screen.infoLine(graphics, "col.have", PriceFormat.amount(heldLike(listStack)), TEXT, left, left + screen.panelWidth, y + screen.priceY - 12);
        graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.exchange.price"), left, y + screen.priceY + 3, TEXT_DIM, false);
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        int qty = screen.quantityValue();
        long each = priceValue();
        long total = qty > 0 && each > 0 ? MarketService.checkedTotal(each, qty).orElse(0) : 0;
        Component note;
        int color = TEXT_DIM;
        if (!screen.quantityNote.getString().isEmpty()) {
            note = screen.quantityNote;
        } else if (qty > listStack.getMaxStackSize()) {
            note = Component.translatable("screen.dynamic_economy.note.over_limit", PriceFormat.amount(listStack.getMaxStackSize()));
            color = TEXT_BAD;
        } else if (total > 0) {
            note = Component.translatable("screen.dynamic_economy.exchange.note.total", PriceFormat.amount(total),
                    PriceFormat.amount(ExchangeService.tax(total, exchange == null ? 0 : exchange.tax())));
        } else {
            note = Component.translatable("screen.dynamic_economy.exchange.note.tax", percent);
        }
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(note.getString(), screen.noteWidth), x + screen.noteX, y + screen.noteY, color, false);
    }

    /** The Exchange tab's panel: the selected item's book and order controls, the selected order, or the box's help. */
    private void renderExchangePanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        int left = x + screen.panelX;
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        String percent = exchange == null ? "-" : String.format(Locale.ROOT, "%.1f%%", exchange.tax() * 100);
        if (exchangeMode == ExchangeMode.BOX) {
            renderHelp(graphics, Component.translatable("screen.dynamic_economy.exchange.box.help"), left, y + screen.panelY + 4);
            return;
        }
        if (exchangeMode == ExchangeMode.ORDERS) {
            renderOrderPanel(graphics, x, y);
            return;
        }
        if (exchangeMode == ExchangeMode.LISTINGS) {
            renderListingPanel(graphics, x, y, percent);
            return;
        }
        if (exchangeMode == ExchangeMode.LIST) {
            renderListPanel(graphics, x, y, percent);
            return;
        }
        if (exchangeSelected == null) {
            renderHelp(graphics, Component.translatable(screen.wide ? "screen.dynamic_economy.exchange.help" : "screen.dynamic_economy.exchange.select_hint",
                    percent), left, y + screen.panelY + 4);
            return;
        }
        graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(exchangeSelected)), left, y + screen.panelY);
        if (!screen.wide) {
            ExchangeService.Summary s = summary(exchangeSelected);
            Component line = Component.translatable("screen.dynamic_economy.exchange.summary", displayName(exchangeSelected),
                    s != null && s.bestAsk() > 0 ? PriceFormat.amount(s.bestAsk()) : "-", s != null && s.bestBid() > 0 ? PriceFormat.amount(s.bestBid()) : "-");
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(line.getString(), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            graphics.drawString(screen.font(), "@", x + screen.noteX, y + screen.controlsY + 5, TEXT_DIM, false);
            return;
        }
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(displayName(exchangeSelected), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
        renderBook(graphics, left, y + screen.chartY, y + screen.priceY - 13, mouseX, mouseY);
        int held = freshHeld(exchangeSelected);
        screen.infoLine(graphics, "col.have", PriceFormat.amount(held), held > 0 ? TEXT : TEXT_DIM, left, left + screen.panelWidth, y + screen.priceY - 12);
        graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.exchange.price"), left, y + screen.priceY + 3, TEXT_DIM, false);

        // Under the quantity: what the order comes to, and the tax a sale pays.
        int qty = screen.quantityValue();
        long each = priceValue();
        long total = qty > 0 && each > 0 ? MarketService.checkedTotal(each, qty).orElse(0) : 0;
        Component note;
        int color = TEXT_DIM;
        if (!screen.quantityNote.getString().isEmpty()) {
            note = screen.quantityNote;
        } else if (exchange != null && qty > exchange.maxOrderSize()) {
            note = Component.translatable("screen.dynamic_economy.note.over_limit", PriceFormat.amount(exchange.maxOrderSize()));
            color = TEXT_BAD;
        } else if (total > 0) {
            note = Component.translatable("screen.dynamic_economy.exchange.note.total", PriceFormat.amount(total),
                    PriceFormat.amount(ExchangeService.tax(total, exchange == null ? 0 : exchange.tax())));
        } else {
            note = Component.translatable("screen.dynamic_economy.exchange.note.tax", percent);
        }
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(note.getString(), screen.noteWidth), x + screen.noteX, y + screen.noteY, color, false);
    }

    /**
     * The selected item's book between {@code top} and {@code bottom}: sellers' prices above the line with the
     * cheapest nearest it, buyers' below with the highest nearest it. Each price can be clicked to copy it.
     */
    private void renderBook(GuiGraphics graphics, int left, int top, int bottom, int mouseX, int mouseY) {
        int right = left + screen.panelWidth;
        graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.exchange.book.price"), left, top, TEXT_DIM, false);
        screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.book.units"), right, top, TEXT_DIM);
        MarketPayloads.ExchangeBook book = exchangeSelected == null ? null : ClientMarketState.exchangeBook(exchangeSelected);
        if (book == null) {
            graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.trades.loading"), left, top + 12, TEXT_DIM, false);
            return;
        }
        int perSide = Mth.clamp((bottom - top - 20) / 20, 1, MarketPayloads.ExchangeBook.DEPTH);
        int asksTop = top + 10;
        int lineY = asksTop + perSide * 10;
        int bidsTop = lineY + 10;
        int shownAsks = Math.min(perSide, book.asks().size());
        for (int j = 0; j < shownAsks; j++) {
            // The cheapest ask sits right above the line.
            ExchangeService.Level level = book.asks().get(shownAsks - 1 - j);
            bookLine(graphics, level, left, right, asksTop + (perSide - shownAsks + j) * 10, TEXT_MONEY, mouseX, mouseY);
        }
        if (book.asks().isEmpty()) {
            graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.exchange.book.no_asks"), left, lineY - 10, TEXT_OFF, false);
        }
        graphics.fill(left, lineY + 4, right, lineY + 5, PANEL_BORDER);
        if (!book.asks().isEmpty() && !book.bids().isEmpty()) {
            Component spread = Component.translatable("screen.dynamic_economy.exchange.book.spread",
                    PriceFormat.amount(book.asks().get(0).price() - book.bids().get(0).price()));
            int width = screen.font().width(spread) + 4;
            int middle = (left + right) / 2;
            graphics.fill(middle - width / 2, lineY, middle + width / 2, lineY + 9, PANEL);
            graphics.drawCenteredString(screen.font(), spread, middle, lineY + 1, TEXT_DIM);
        }
        for (int j = 0; j < Math.min(perSide, book.bids().size()); j++) {
            bookLine(graphics, book.bids().get(j), left, right, bidsTop + j * 10, TEXT_GOOD, mouseX, mouseY);
        }
        if (book.bids().isEmpty()) {
            graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.exchange.book.no_bids"), left, bidsTop, TEXT_OFF, false);
        }
    }

    /** One price in the book; the player's own share is marked with a star. */
    private void bookLine(GuiGraphics graphics, ExchangeService.Level level, int left, int right, int top, int color, int mouseX, int mouseY) {
        BookRow row = new BookRow(left, top - 1, right, top + 9, level.price(), level.mine());
        bookRows.add(row);
        if (mouseX >= row.left() && mouseX < row.right() && mouseY >= row.top() && mouseY < row.bottom()) {
            graphics.fill(row.left(), row.top(), row.right(), row.bottom(), ROW_HOVER);
        }
        graphics.drawString(screen.font(), PriceFormat.amount(level.price()) + (level.mine() > 0 ? " *" : ""), left, top, color, false);
        screen.drawRight(graphics, Component.literal(PriceFormat.count(level.quantity())), right, top, TEXT);
    }

    /** The selected order: its side, price, progress, what it still holds and when it expires. */
    private void renderOrderPanel(GuiGraphics graphics, int x, int y) {
        int left = x + screen.panelX;
        MarketPayloads.OrderLine line = selectedOrderLine();
        MarketPayloads.ListingLine listing = selectedMyListing();
        if (line == null && listing != null) {
            renderMyListingPanel(graphics, x, y, listing);
            return;
        }
        if (line == null) {
            renderHelp(graphics, Component.translatable(screen.wide ? "screen.dynamic_economy.exchange.orders.help"
                    : "screen.dynamic_economy.exchange.orders.select_hint"), left, y + screen.panelY + 4);
            return;
        }
        graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(line.item())), left, y + screen.panelY);
        Component expires = line.daysLeft() < 0 ? Component.translatable("screen.dynamic_economy.exchange.order.never")
                : Component.translatable("screen.dynamic_economy.exchange.order.days", line.daysLeft());
        if (!screen.wide) {
            Component summary = Component.translatable("screen.dynamic_economy.exchange.order.summary", displayName(line.item()),
                    PriceFormat.amount(line.price()), PriceFormat.count(line.filled()), PriceFormat.count(line.quantity()));
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(summary.getString(), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            return;
        }
        int right = left + screen.panelWidth;
        int top = y + screen.chartY;
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(displayName(line.item()), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
        screen.infoLine(graphics, "exchange.order.side", Component.translatable(line.buy() ? "screen.dynamic_economy.exchange.order.side_buy"
                : "screen.dynamic_economy.exchange.order.side_sell").getString(), line.buy() ? TEXT_MONEY : TEXT_GOOD, left, right, top);
        screen.infoLine(graphics, "exchange.order.price", PriceFormat.amount(line.price()), TEXT, left, right, top + INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "exchange.order.filled", PriceFormat.count(line.filled()) + "/" + PriceFormat.count(line.quantity()), TEXT,
                left, right, top + 2 * INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "exchange.order.holds", line.buy() ? PriceFormat.money(PriceFormat.amount(line.price() * line.remaining())).getString()
                : Component.translatable("screen.dynamic_economy.exchange.order.items", PriceFormat.count(line.remaining())).getString(),
                TEXT, left, right, top + 3 * INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "exchange.order.expires", expires.getString(), TEXT_DIM, left, right, top + 4 * INFO_LINE_HEIGHT);
    }

    /** One of the player's own listings under My orders: its price, sales, what it still holds and when it expires. */
    private void renderMyListingPanel(GuiGraphics graphics, int x, int y, MarketPayloads.ListingLine line) {
        int left = x + screen.panelX;
        screen.renderStack(graphics, line.stack(), left, y + screen.panelY);
        String name = line.stack().getHoverName().getString();
        if (!screen.wide) {
            Component summary = Component.translatable("screen.dynamic_economy.exchange.order.summary", name,
                    PriceFormat.amount(line.price()), PriceFormat.count(line.sold()), PriceFormat.count(line.quantity()));
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(summary.getString(), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            return;
        }
        int right = left + screen.panelWidth;
        int top = y + screen.chartY;
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(name, screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
        screen.infoLine(graphics, "exchange.order.side", Component.translatable("screen.dynamic_economy.exchange.order.side_listing").getString(),
                TEXT_GOOD, left, right, top);
        screen.infoLine(graphics, "exchange.order.price", PriceFormat.amount(line.price()), TEXT, left, right, top + INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "exchange.order.sold", PriceFormat.count(line.sold()) + "/" + PriceFormat.count(line.quantity()), TEXT,
                left, right, top + 2 * INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "exchange.order.holds", Component.translatable("screen.dynamic_economy.exchange.order.items",
                PriceFormat.count(line.remaining())).getString(), TEXT, left, right, top + 3 * INFO_LINE_HEIGHT);
        Component expires = line.daysLeft() < 0 ? Component.translatable("screen.dynamic_economy.exchange.order.never")
                : Component.translatable("screen.dynamic_economy.exchange.order.days", line.daysLeft());
        screen.infoLine(graphics, "exchange.order.expires", expires.getString(), TEXT_DIM, left, right, top + 4 * INFO_LINE_HEIGHT);
        renderItemDetails(graphics, line.stack(), left, top + 5 * INFO_LINE_HEIGHT + 4, y + screen.tradeY - 4);
    }

    /** Wrapped help text from {@code top} down to the controls. */
    private void renderHelp(GuiGraphics graphics, Component text, int left, int top) {
        List<net.minecraft.util.FormattedCharSequence> lines = screen.font().split(text, screen.panelWidth);
        // Above the price box when it shows, otherwise above the button; one line when narrow.
        int bottom = screen.topPos() + (!screen.wide ? screen.panelY + 14 : exchangeMode == ExchangeMode.ITEMS || exchangeMode == ExchangeMode.LIST ? screen.priceY - 2
                : exchangeMode == ExchangeMode.LISTINGS ? screen.controlsY - 2 : screen.tradeY - 2);
        for (int i = 0; i < lines.size() && top + i * 10 + 9 <= bottom; i++) {
            graphics.drawString(screen.font(), lines.get(i), left, top + i * 10, TEXT_DIM, false);
        }
    }

    /** The item's own tooltip plus its best prices on the exchange and, if listed, what the market pays. */
    private List<Component> exchangeTooltip(ResourceLocation item) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(screen.minecraft(), new ItemStack(BuiltInRegistries.ITEM.get(item))));
        ExchangeService.Summary s = summary(item);
        if (s != null && s.bestAsk() > 0) {
            lines.add(Component.translatable("screen.dynamic_economy.exchange.tooltip.ask", PriceFormat.money(PriceFormat.amount(s.bestAsk())),
                    PriceFormat.count(s.askUnits())).withColor(TEXT_MONEY));
        }
        if (s != null && s.bestBid() > 0) {
            lines.add(Component.translatable("screen.dynamic_economy.exchange.tooltip.bid", PriceFormat.money(PriceFormat.amount(s.bestBid())),
                    PriceFormat.count(s.bidUnits())).withColor(TEXT_GOOD));
        }
        if (s == null) {
            lines.add(Component.translatable("screen.dynamic_economy.exchange.tooltip.none").withColor(TEXT_DIM));
        }
        MarketPayloads.Row row = ClientMarketState.rows().get(item);
        if (row != null && row.canSell()) {
            lines.add(Component.translatable("screen.dynamic_economy.tooltip.sell",
                    PriceFormat.money(PriceFormat.unitPrice(row.exactSellPrice(), RoundingMode.FLOOR))).withColor(TEXT_DIM));
        }
        return lines;
    }
}
