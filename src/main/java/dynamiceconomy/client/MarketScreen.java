package dynamiceconomy.client;

import dynamiceconomy.bank.CoinMath;
import dynamiceconomy.bank.Denomination;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.service.BankService;
import dynamiceconomy.service.ContractService;
import dynamiceconomy.service.ExchangeService;
import dynamiceconomy.service.MarketListing;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.OrderMath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The market catalog: a searchable, sortable list of items with stock and prices, and buy/sell controls for the
 * selection. Everything here is a hint; the server re-checks every trade.
 *
 * <p>The Bank tab lists the coins the player carries and deposits or withdraws them. Opened from a Bank Card, the
 * screen is bank-only: the other tabs are greyed out.
 *
 * <p>The Contracts tab lists the contract board; the selected contract can be claimed, delivered to and abandoned
 * with the quantity controls and two buttons where Buy and Sell are.
 *
 * <p>The screen sizes itself to the window. On a wide window the selected item's panel sits beside the list; on a
 * narrow one it sits below. The list shows as many rows as fit.
 */
public final class MarketScreen extends AbstractContainerScreen<MarketMenu> {
    /** The largest the screen grows to, so rows stay readable on a big window at GUI scale 1. */
    private static final int MAX_WIDTH = 600;
    private static final int MAX_HEIGHT = 400;
    /** Below this width the selection panel goes under the list instead of beside it. */
    private static final int WIDE_MIN_WIDTH = 440;
    private static final int SIDE_WIDTH = 150;
    private static final int MARGIN = 8;
    private static final int TAB_Y = 4;
    private static final int TAB_HEIGHT = 14;
    private static final int TOOLBAR_Y = 22;
    private static final int HEADER_Y = 42;
    private static final int LIST_X = MARGIN;
    private static final int LIST_Y = 52;
    private static final int ROW_HEIGHT = 18;
    private static final int SCROLLBAR_WIDTH = 4;
    /** The narrow layout's panel: the selected item, the quantity controls, the trade buttons. */
    private static final int BOTTOM_PANEL_HEIGHT = 62;
    /** Room left under the list and panel for the status line. */
    private static final int STATUS_HEIGHT = 16;
    /** The wide panel's label/value lines under the chart. */
    private static final int INFO_LINES = 5;
    private static final int INFO_LINE_HEIGHT = 10;
    private static final int MIN_SIDE_CHART_HEIGHT = 24;
    private static final int MAX_SIDE_CHART_HEIGHT = 80;
    private static final int NARROW_CHART_WIDTH = 118;
    private static final int NARROW_CHART_HEIGHT = 16;
    /** Width of the quantity controls: -, the box, +, Max and All. */
    private static final int CONTROLS_WIDTH = 132;

    private static final int PANEL = 0xF0202024;
    private static final int PANEL_BORDER = 0xFF5A5A64;
    private static final int ROW_ALT = 0x18FFFFFF;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int ROW_SELECTED = 0x50FFFFFF;
    private static final int TAB_IDLE = 0x14FFFFFF;
    private static final int TAB_ACTIVE = 0x40FFFFFF;
    private static final int TEXT = 0xFFFFFF;
    private static final int TEXT_DIM = 0xA0A0A8;
    private static final int TEXT_OFF = 0x606068;
    private static final int TEXT_GOOD = 0x6EE07A;
    private static final int TEXT_BAD = 0xFF6B6B;
    private static final int TEXT_MONEY = 0xF2C94C;
    /** Shortest time span the line covers, so a fresh history isn't squeezed into one point (4 minutes). */
    private static final long MIN_CHART_TICKS = 4800;
    /** How far the price factor must be from 1.0 before the row shows a trend arrow. */
    private static final double TREND_THRESHOLD = 0.005;

    // Kept while the game runs, so the list looks the same the next time the market opens.
    private static MarketListing.Sort sort = MarketListing.Sort.NAME;
    private static MarketListing.Filter filter = MarketListing.Filter.ALL;
    /** Lower-case mod display names by namespace, for {@code @mod} searches. The mod list never changes in a session. */
    private static final Map<String, String> MOD_NAMES = new HashMap<>();

    /** The tabs along the top. Those that can't be opened are shown greyed out. */
    private enum Tab {
        MARKET, BANK, CONTRACTS, EXCHANGE, LEADERBOARD, HISTORY;

        /**
         * The server can turn contracts, the exchange and the leaderboard off, and a Bank Card opens only the bank. The
         * Exchange tab stays open while the player has items waiting in their collection box.
         */
        boolean available(boolean bankOnly) {
            if (bankOnly) {
                return this == BANK;
            }
            return switch (this) {
                case MARKET, BANK, HISTORY -> true;
                case CONTRACTS -> ClientMarketState.contractsEnabled();
                case EXCHANGE -> ClientMarketState.exchangeEnabled()
                        || ClientMarketState.exchange() != null && ClientMarketState.exchange().boxed() > 0;
                case LEADERBOARD -> ClientMarketState.leaderboardEnabled();
            };
        }

        /** Why a greyed-out tab can't be opened. */
        Component unavailableReason(boolean bankOnly) {
            return Component.translatable(bankOnly ? "screen.dynamic_economy.tab.terminal_only" : "screen.dynamic_economy.tab.turned_off");
        }

        Component label() {
            return Component.translatable("screen.dynamic_economy.tab." + name().toLowerCase(Locale.ROOT));
        }
    }

    private EditBox search;
    private EditBox quantity;
    private Button sortButton;
    private Button filterButton;
    private Tab tab = Tab.MARKET;
    private Button buyButton;
    private Button sellButton;
    private Button maxButton;
    /** The quantity and trade controls, hidden on the Bank tab. */
    private final List<AbstractWidget> marketControls = new ArrayList<>();
    /** Claim or deliver, and abandon: where Buy and Sell are, on the Contracts tab only. */
    private Button contractButton;
    private Button abandonButton;
    /** The selected contract's ID, if any. */
    @Nullable
    private Integer selectedContract;
    /** Abandoning loses the deposit, so it takes a second click; this is the contract the first click was for. */
    @Nullable
    private Integer abandonArmed;
    private EditBox withdrawAmount;
    private Button withdrawButton;
    private Button depositButton;
    /** Switches between the player's own account and their team's; shown only to team members. */
    private Button accountButton;

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

    // The layout, worked out from the window size in init(). Positions are relative to leftPos/topPos.
    private boolean wide;
    private final int[] tabX = new int[Tab.values().length];
    private final int[] tabWidth = new int[Tab.values().length];
    /** Whether the balance fits beside the tabs; if not it goes at the end of the toolbar, abbreviated. */
    private boolean balanceInTabRow;
    private int listWidth;
    private int rows;
    // Right edges of the numeric columns, and the left edge of the trend arrow.
    private int colStock;
    private int colBuy;
    private int colSell;
    private int colHave;
    private int colTrend;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int chartX;
    private int chartY;
    private int chartWidth;
    private int chartHeight;
    private int infoY;
    private int controlsY;
    /** Top of the trade buttons: Buy above Sell when wide, side by side when narrow. */
    private int tradeY;
    private int noteX;
    private int noteY;
    private int noteWidth;
    private int statusY;
    /** The Exchange tab's price box: above the quantity controls when wide, beside them when narrow. */
    private int priceX;
    private int priceY;
    private int priceWidth;

    private final List<MarketPayloads.Row> visible = new ArrayList<>();
    private int seenVersion = -1;
    private String seenQuery = "";
    private boolean listDirty = true;
    private int scroll;
    @Nullable
    private ResourceLocation selected;
    /** The item and factor the last history request was for, so a price change triggers a fresh request. */
    @Nullable
    private ResourceLocation historyFor;
    private double historyFactor = Double.NaN;
    private int ticksSinceHistoryRequest;

    /** A short explanation under the quantity, set by All and Max; it lasts until the quantity changes. */
    private Component quantityNote = Component.empty();
    private int quantityNoteFor = -1;

    // Order totals for the current row and quantity. Each unit moves the price, so they cost more than a multiply.
    @Nullable
    private MarketPayloads.Row totalsRow;
    private int totalsQty = -1;
    private long buyTotal;
    private long sellTotal;

    public MarketScreen(MarketMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        tab = menu.bankOnly() ? Tab.BANK : Tab.MARKET;
    }

    private boolean available(Tab t) {
        return t.available(menu.bankOnly());
    }

    @Override
    protected void init() {
        imageWidth = Math.min(MAX_WIDTH, width - MARGIN);
        imageHeight = Math.min(MAX_HEIGHT, height - MARGIN);
        super.init();
        layout();
        int x = leftPos;
        int y = topPos;

        // init() runs again on every resize; keep what the player typed.
        String searchText = search != null ? search.getValue() : "";
        String quantityText = quantity != null ? quantity.getValue() : "1";
        String withdrawText = withdrawAmount != null ? withdrawAmount.getValue() : "";
        marketControls.clear();

        int toolbarEnd = (wide ? panelX - MARGIN : imageWidth - MARGIN) - (balanceInTabRow ? 0 : balanceReserve() + 4);
        int searchWidth = Mth.clamp(toolbarEnd - LIST_X - 104, 60, 160);
        search = new EditBox(font, x + LIST_X, y + TOOLBAR_Y + 1, searchWidth, 14, Component.translatable("screen.dynamic_economy.search"));
        search.setHint(Component.translatable("screen.dynamic_economy.search").withColor(TEXT_DIM));
        search.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.search.tooltip")));
        search.setMaxLength(50);
        search.setValue(searchText);
        addRenderableWidget(search);
        int sortX = LIST_X + searchWidth + 2;
        sortButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            sort = sort.next();
            listDirty = true;
            updateListButtons();
        }).bounds(x + sortX, y + TOOLBAR_Y, 52, 16).build());
        filterButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            filter = filter.next();
            listDirty = true;
            updateListButtons();
        }).bounds(x + sortX + 54, y + TOOLBAR_Y, 48, 16).build());
        updateListButtons();

        int controlsX = wide ? panelX : MARGIN;
        marketControls.add(addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustQuantity(-step()))
                .bounds(x + controlsX, y + controlsY, 16, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.step_hint")))
                .build()));
        quantity = new EditBox(font, x + controlsX + 18, y + controlsY + 2, 40, 14, Component.translatable("screen.dynamic_economy.quantity"));
        quantity.setFilter(s -> s.isEmpty() || (s.length() <= 7 && s.chars().allMatch(Character::isDigit)));
        quantity.setValue(quantityText);
        marketControls.add(addRenderableWidget(quantity));
        marketControls.add(addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustQuantity(step()))
                .bounds(x + controlsX + 60, y + controlsY, 16, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.step_hint")))
                .build()));
        maxButton = addRenderableWidget(Button.builder(Component.translatable("screen.dynamic_economy.max"), b -> fillMaxBuy())
                .bounds(x + controlsX + 78, y + controlsY, 28, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.max.tooltip")))
                .build());
        marketControls.add(maxButton);
        marketControls.add(addRenderableWidget(Button.builder(Component.translatable("screen.dynamic_economy.all"),
                        b -> {
                            if (tab == Tab.CONTRACTS) {
                                fillDeliverAll();
                            } else if (tab == Tab.EXCHANGE) {
                                fillExchangeAll();
                            } else {
                                fillSellAll();
                            }
                        })
                .bounds(x + controlsX + 108, y + controlsY, 24, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.all.tooltip")))
                .build()));

        // The Bank tab's controls sit where the quantity and trade controls do.
        int bankWidth = wide ? panelWidth : CONTROLS_WIDTH;
        withdrawAmount = new EditBox(font, x + controlsX, y + controlsY + 2, bankWidth - 64, 14,
                Component.translatable("screen.dynamic_economy.bank.amount"));
        withdrawAmount.setHint(Component.translatable("screen.dynamic_economy.bank.amount").withColor(TEXT_DIM));
        withdrawAmount.setFilter(s -> s.isEmpty() || (s.length() <= 10 && s.chars().allMatch(Character::isDigit)));
        withdrawAmount.setValue(withdrawText);
        addRenderableWidget(withdrawAmount);
        withdrawButton = addRenderableWidget(Button.builder(Component.translatable("screen.dynamic_economy.bank.withdraw"), b -> withdraw())
                .bounds(x + controlsX + bankWidth - 62, y + controlsY, 62, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.bank.withdraw.tooltip")))
                .build());
        depositButton = addRenderableWidget(Button.builder(Component.translatable("screen.dynamic_economy.bank.deposit"), b -> deposit())
                .bounds(x + (wide ? panelX : MARGIN), y + tradeY, panelWidth, 18)
                .build());
        // Wide: under the two balance lines. Narrow: beside Deposit, which then takes half the row.
        accountButton = addRenderableWidget(Button.builder(Component.empty(), b -> switchAccount())
                .bounds(x + (wide ? panelX : MARGIN + panelWidth - (panelWidth - 2) / 2), y + (wide ? panelY + 28 : tradeY),
                        wide ? panelWidth : (panelWidth - 2) / 2, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.bank.use.tooltip")))
                .build());

        if (wide) {
            buyButton = addRenderableWidget(Button.builder(Component.empty(), b -> trade(true))
                    .bounds(x + panelX, y + tradeY, panelWidth, 18).build());
            sellButton = addRenderableWidget(Button.builder(Component.empty(), b -> trade(false))
                    .bounds(x + panelX, y + tradeY + 20, panelWidth, 18).build());
        } else {
            int half = (panelWidth - 2) / 2;
            buyButton = addRenderableWidget(Button.builder(Component.empty(), b -> trade(true))
                    .bounds(x + MARGIN, y + tradeY, half, 18).build());
            sellButton = addRenderableWidget(Button.builder(Component.empty(), b -> trade(false))
                    .bounds(x + MARGIN + panelWidth - half, y + tradeY, half, 18).build());
        }
        marketControls.add(buyButton);
        marketControls.add(sellButton);
        contractButton = addRenderableWidget(Button.builder(Component.empty(), b -> contractAction())
                .bounds(buyButton.getX(), buyButton.getY(), buyButton.getWidth(), 18).build());
        abandonButton = addRenderableWidget(Button.builder(Component.translatable("screen.dynamic_economy.contracts.abandon"), b -> abandon())
                .bounds(sellButton.getX(), sellButton.getY(), sellButton.getWidth(), 18).build());

        // The Exchange tab: its list mode where Sort and Filter are, the price box above the quantity (beside it when
        // narrow), and its own buttons where Buy and Sell are.
        String priceText = price != null ? price.getValue() : "";
        exchangeModeButton = addRenderableWidget(Button.builder(Component.empty(), b -> cycleExchangeMode())
                .bounds(x + sortX, y + TOOLBAR_Y, 102, 16)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.exchange.mode.tooltip")))
                .build());
        price = new EditBox(font, x + priceX, y + priceY, priceWidth, 14, Component.translatable("screen.dynamic_economy.exchange.price"));
        price.setHint(Component.translatable("screen.dynamic_economy.exchange.price_hint").withColor(TEXT_DIM));
        price.setFilter(s -> s.isEmpty() || (s.length() <= 10 && s.chars().allMatch(Character::isDigit)));
        price.setValue(priceText);
        addRenderableWidget(price);
        postBuyButton = addRenderableWidget(Button.builder(Component.empty(), b -> postOrder(true))
                .bounds(buyButton.getX(), buyButton.getY(), buyButton.getWidth(), 18).build());
        postSellButton = addRenderableWidget(Button.builder(Component.empty(), b -> postOrder(false))
                .bounds(sellButton.getX(), sellButton.getY(), sellButton.getWidth(), 18).build());
        exchangeActionButton = addRenderableWidget(Button.builder(Component.empty(), b -> exchangeAction())
                .bounds(buyButton.getX(), buyButton.getY(), buyButton.getWidth(), 18).build());
        openExchangeButton = addRenderableWidget(Button.builder(Component.literal("⇄"), b -> openOnExchange())
                .bounds(x + (wide ? panelX + panelWidth : chartX - 4) - 18, y + panelY - 1, 18, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.exchange.open.tooltip")))
                .build());
        updateListButtons();

        seenVersion = -1;
        totalsRow = null;
        scroll = Mth.clamp(scroll, 0, maxScroll());
        refreshButtons();
    }

    /** Works out where everything goes for the current screen size. */
    private void layout() {
        int right = imageWidth - MARGIN;
        int bottom = imageHeight - STATUS_HEIGHT;
        statusY = imageHeight - 12;

        int tx = MARGIN;
        for (Tab t : Tab.values()) {
            tabX[t.ordinal()] = tx;
            tabWidth[t.ordinal()] = font.width(t.label()) + 10;
            tx += tabWidth[t.ordinal()] + 2;
        }
        balanceInTabRow = tx + 8 + font.width(balanceText(widestBalance(), false)) <= right;

        // Wide: the panel is a column on the right, stacked from the top; the chart takes spare height up to a limit.
        wide = imageWidth >= WIDE_MIN_WIDTH;
        if (wide) {
            panelWidth = SIDE_WIDTH;
            panelX = right - SIDE_WIDTH;
            panelY = HEADER_Y;
            chartX = panelX;
            chartY = panelY + 20;
            chartWidth = panelWidth;
            // Under the chart: 4 gap, the info lines, 4 gap, controls (21), note (11), Buy and Sell (38).
            int below = 4 + INFO_LINES * INFO_LINE_HEIGHT + 4 + 21 + 11 + 38;
            chartHeight = Math.min(MAX_SIDE_CHART_HEIGHT, bottom - below - chartY);
            infoY = chartY + chartHeight + 4;
            controlsY = infoY + INFO_LINES * INFO_LINE_HEIGHT + 4;
            noteX = panelX;
            noteY = controlsY + 21;
            noteWidth = panelWidth;
            tradeY = noteY + 11;
            wide = chartHeight >= MIN_SIDE_CHART_HEIGHT;
        }
        if (wide) {
            listWidth = panelX - MARGIN - SCROLLBAR_WIDTH - 2 - LIST_X;
            rows = Math.max(1, (bottom - LIST_Y) / ROW_HEIGHT);
        } else {
            panelX = MARGIN;
            panelWidth = right - MARGIN;
            panelY = bottom - BOTTOM_PANEL_HEIGHT;
            chartWidth = Math.min(NARROW_CHART_WIDTH, panelWidth / 2);
            chartHeight = NARROW_CHART_HEIGHT;
            chartX = right - chartWidth;
            chartY = panelY;
            controlsY = panelY + 20;
            tradeY = panelY + 40;
            noteX = MARGIN + CONTROLS_WIDTH + 4;
            noteY = controlsY + 5;
            noteWidth = right - noteX;
            listWidth = right - SCROLLBAR_WIDTH - 2 - LIST_X;
            rows = Math.max(1, (panelY - 4 - LIST_Y) / ROW_HEIGHT);
        }
        if (wide) {
            priceWidth = 70;
            priceX = panelX + panelWidth - priceWidth;
            priceY = controlsY - 18;
        } else {
            priceX = noteX + 10;
            priceY = controlsY + 2;
            priceWidth = Math.min(70, right - priceX);
        }

        int listRight = LIST_X + listWidth;
        colTrend = listRight - 10;
        colHave = listRight - 13;
        colSell = colHave - 34;
        colBuy = colSell - 42;
        colStock = colBuy - 42;
    }

    // ---- State -----------------------------------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (listDirty || seenVersion != ClientMarketState.version() || !query.equals(seenQuery)) {
            listDirty = false;
            seenVersion = ClientMarketState.version();
            seenQuery = query;
            rebuildList(query);
        }
        // The server can turn the leaderboard off while its tab is open.
        if (!available(tab)) {
            selectTab(menu.bankOnly() ? Tab.BANK : Tab.MARKET);
        }
        refreshButtons();
        refreshBankButtons();
        refreshContractButtons();
        refreshExchangeButtons();
        requestHistoryIfStale();
    }

    /** Asks for the selected item's history when the selection or its price changed, at most twice a second. */
    private void requestHistoryIfStale() {
        ticksSinceHistoryRequest++;
        MarketPayloads.Row row = selectedRow();
        if (row == null || ticksSinceHistoryRequest < 10) {
            return;
        }
        if (!row.item().equals(historyFor) || row.factor() != historyFactor) {
            historyFor = row.item();
            historyFactor = row.factor();
            ticksSinceHistoryRequest = 0;
            PacketDistributor.sendToServer(new MarketPayloads.HistoryRequest(row.item()));
        }
    }

    private void rebuildList(String query) {
        visible.clear();
        for (MarketPayloads.Row row : ClientMarketState.rows().values()) {
            if (matches(row.item(), query) && MarketListing.shows(filter, row, ClientMarketState.held(row.item()))) {
                visible.add(row);
            }
        }
        visible.sort(MarketListing.order(sort, MarketScreen::displayName));
        if (tab == Tab.EXCHANGE) {
            rebuildExchangeItems(query);
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    /** The most items a search of every item lists, so a one-letter search doesn't list the whole game. */
    private static final int MAX_SEARCH_RESULTS = 300;

    /**
     * Without a search: items with orders, then items the player holds. With one: every tradeable item that matches,
     * those with orders first. Each group is sorted by name.
     */
    private void rebuildExchangeItems(String query) {
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
            ClientMarketState.heldItems().stream().filter(id -> !withOrders.contains(id)).forEach(others::add);
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

    /** Switches tabs, asking the server for the player's trades, the leaderboard or the contract board when those open. */
    private void selectTab(Tab next) {
        if (!available(next) || next == tab) {
            return;
        }
        tab = next;
        scroll = 0;
        if (tab == Tab.HISTORY) {
            PacketDistributor.sendToServer(new MarketPayloads.TradesRequest());
        } else if (tab == Tab.LEADERBOARD) {
            PacketDistributor.sendToServer(new MarketPayloads.LeaderboardRequest());
        } else if (tab == Tab.CONTRACTS) {
            PacketDistributor.sendToServer(new MarketPayloads.ContractsRequest());
        } else if (tab == Tab.EXCHANGE) {
            PacketDistributor.sendToServer(new MarketPayloads.ExchangeRequest(Optional.ofNullable(exchangeSelected), exchangeMode == ExchangeMode.LISTINGS));
        }
        listDirty = true;
        updateListButtons();
    }

    private boolean showingTrades() {
        return tab == Tab.HISTORY;
    }

    private void updateListButtons() {
        boolean market = tab == Tab.MARKET;
        boolean exchange = tab == Tab.EXCHANGE;
        boolean exchangeItemsShown = exchange && exchangeMode == ExchangeMode.ITEMS;
        boolean exchangeSearch = exchangeItemsShown || exchange && exchangeMode == ExchangeMode.LISTINGS;
        boolean exchangeQuantity = exchangeSearch || exchange && exchangeMode == ExchangeMode.LIST;
        boolean exchangePrice = exchangeItemsShown || exchange && exchangeMode == ExchangeMode.LIST;
        search.visible = market || exchangeSearch;
        sortButton.visible = market;
        filterButton.visible = market;
        boolean bank = tab == Tab.BANK;
        boolean contracts = tab == Tab.CONTRACTS;
        boolean quantityShown = !bank && (!exchange || exchangeQuantity);
        marketControls.forEach(widget -> widget.visible = quantityShown);
        if (contractButton != null) {
            maxButton.visible = quantityShown && !contracts && !exchange;
            buyButton.visible = quantityShown && !contracts && !exchange;
            sellButton.visible = quantityShown && !contracts && !exchange;
            contractButton.visible = contracts;
            abandonButton.visible = contracts;
        }
        if (withdrawAmount != null) {
            withdrawAmount.visible = bank;
            withdrawButton.visible = bank;
            depositButton.visible = bank;
            accountButton.visible = bank && !ClientMarketState.team().isEmpty();
        }
        if (price != null) {
            exchangeModeButton.visible = exchange;
            price.visible = exchangePrice;
            postBuyButton.visible = exchangeItemsShown;
            postSellButton.visible = exchangeItemsShown;
            exchangeActionButton.visible = exchange && !exchangeItemsShown;
            openExchangeButton.visible = market && selectedRow() != null && Tab.EXCHANGE.available(menu.bankOnly());
            exchangeModeButton.setMessage(exchangeModeLabel());
        }
        if (getFocused() == search && !search.visible || getFocused() == quantity && !quantityShown
                || getFocused() == withdrawAmount && !bank || getFocused() == price && !exchangePrice) {
            setFocused(null);
        }
        String sortKey = sort.name().toLowerCase(Locale.ROOT);
        sortButton.setMessage(Component.translatable("screen.dynamic_economy.sort." + sortKey));
        sortButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.sort." + sortKey + ".tooltip")));
        String filterKey = filter.name().toLowerCase(Locale.ROOT);
        filterButton.setMessage(Component.translatable("screen.dynamic_economy.filter." + filterKey));
        filterButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.filter." + filterKey + ".tooltip")));
    }

    /** Every space-separated term must match: {@code @mod}, {@code #tag}, or a plain word (see {@link #matchesTerm}). */
    private static boolean matches(ResourceLocation item, String query) {
        for (String term : query.split("\\s+")) {
            if (!term.isEmpty() && !matchesTerm(item, term)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code @create} matches the mod's display name or ID; {@code #ingots} matches a tag's path, and {@code #c:ingots}
     * the full tag ID. A plain word matches the display name or the ID's path. The namespace only counts when the
     * word contains ':', or every vanilla item would match "mine".
     */
    private static boolean matchesTerm(ResourceLocation item, String term) {
        if (term.startsWith("@")) {
            String mod = term.substring(1);
            return item.getNamespace().contains(mod) || modName(item.getNamespace()).contains(mod);
        }
        if (term.startsWith("#")) {
            String tag = term.substring(1);
            boolean full = tag.contains(":");
            return BuiltInRegistries.ITEM.wrapAsHolder(BuiltInRegistries.ITEM.get(item)).tags()
                    .anyMatch(key -> (full ? key.location().toString() : key.location().getPath()).contains(tag));
        }
        if (term.contains(":")) {
            return item.toString().contains(term);
        }
        return item.getPath().contains(term) || displayName(item).toLowerCase(Locale.ROOT).contains(term);
    }

    /** A mod's display name in lower case, or "" for a namespace no loaded mod owns. */
    private static String modName(String namespace) {
        return MOD_NAMES.computeIfAbsent(namespace, ns -> ModList.get().getModContainerById(ns)
                .map(container -> container.getModInfo().getDisplayName().toLowerCase(Locale.ROOT))
                .orElse(""));
    }

    @Nullable
    private MarketPayloads.Row selectedRow() {
        return selected == null ? null : ClientMarketState.rows().get(selected);
    }

    private int quantityValue() {
        try {
            return Math.max(0, Integer.parseInt(quantity.getValue()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int step() {
        return hasShiftDown() ? 16 : 1;
    }

    private void adjustQuantity(int delta) {
        setQuantity(Mth.clamp(quantityValue() + delta, 1, quantityLimit()), Component.empty());
    }

    /** The largest quantity one request may ask for: one exchange order, or one market trade. */
    private int quantityLimit() {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        if (tab == Tab.EXCHANGE && exchangeMode == ExchangeMode.LIST && listSlot >= 0) {
            return listStack.getMaxStackSize();
        }
        return tab == Tab.EXCHANGE && exchange != null ? exchange.maxOrderSize() : ClientMarketState.maxQuantity();
    }

    private void setQuantity(int value, Component note) {
        quantity.setValue(String.valueOf(value));
        quantityNote = note;
        quantityNoteFor = value;
    }

    /** "Max": as many as the balance, stock, inventory room and order limit allow, priced the way the server will. */
    private void fillMaxBuy() {
        MarketPayloads.Row row = selectedRow();
        if (row == null) {
            return;
        }
        if (!row.canBuy()) {
            quantityNote = Component.translatable("screen.dynamic_economy.note.not_sold");
            quantityNoteFor = quantityValue();
            return;
        }
        int limit = ClientMarketState.maxQuantity();
        long room = ClientMarketState.room(row.item(), stack(row).getMaxStackSize());
        long balance = ClientMarketState.balance();
        int best = OrderMath.maxBuy(row.inputs(), row.factor(), balance, row.stock(), room, limit);
        if (best == 0) {
            String reason = !row.hasStock(1) ? "none_stock" : room < 1 ? "none_room" : "none_afford";
            quantityNote = Component.translatable("screen.dynamic_economy.note." + reason);
            quantityNoteFor = quantityValue();
            return;
        }
        // Say which limit stopped it, checking the hard limits before money.
        String reason = best == limit ? "max_limit"
                : row.stock().isPresent() && best == row.stock().getAsLong() ? "max_stock"
                : best == room ? "max_room"
                : "max_afford";
        setQuantity(best, Component.translatable("screen.dynamic_economy.note." + reason, PriceFormat.spurs(limit)));
    }

    /** "All": every copy held, up to the order limit. */
    private void fillSellAll() {
        MarketPayloads.Row row = selectedRow();
        if (row == null) {
            return;
        }
        int held = ClientMarketState.held(row.item());
        if (!row.canSell() || held == 0) {
            quantityNote = Component.translatable(!row.canSell() ? "screen.dynamic_economy.note.not_bought"
                    : "screen.dynamic_economy.note.none_held");
            quantityNoteFor = quantityValue();
            return;
        }
        int limit = ClientMarketState.maxQuantity();
        int amount = OrderMath.sellAll(held, limit);
        setQuantity(amount, held > limit
                ? Component.translatable("screen.dynamic_economy.note.capped", PriceFormat.spurs(amount), PriceFormat.spurs(held))
                : Component.empty());
    }

    /** Client-side hints only; the server re-checks everything and replies with the real outcome. */
    private void refreshButtons() {
        MarketPayloads.Row row = selectedRow();
        int qty = quantityValue();
        if (qty != quantityNoteFor) {
            quantityNote = Component.empty();
        }
        if (row == null || qty <= 0 || qty > ClientMarketState.maxQuantity()) {
            buyButton.setMessage(Component.translatable("screen.dynamic_economy.buy"));
            sellButton.setMessage(Component.translatable("screen.dynamic_economy.sell"));
            buyButton.setTooltip(null);
            sellButton.setTooltip(null);
            buyButton.active = false;
            sellButton.active = false;
            totalsRow = null; // So the totals and their tooltips are rebuilt when the order is valid again.
            return;
        }
        if (row != totalsRow || qty != totalsQty) {
            // Each unit moves the price, so totals come from the same model the server uses, not unit price × qty.
            totalsRow = row;
            totalsQty = qty;
            buyTotal = row.buyTotal(qty);
            sellTotal = row.sellTotal(qty);
            buyButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.average",
                    PriceFormat.money(PriceFormat.decimal(OrderMath.average(buyTotal, qty))))));
            sellButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.average",
                    PriceFormat.money(PriceFormat.decimal(OrderMath.average(sellTotal, qty))))));
        }
        buyButton.setMessage(row.canBuy()
                ? Component.translatable("screen.dynamic_economy.buy_for", PriceFormat.spurs(buyTotal))
                : Component.translatable("screen.dynamic_economy.buy"));
        sellButton.setMessage(row.canSell()
                ? Component.translatable("screen.dynamic_economy.sell_for", PriceFormat.spurs(sellTotal))
                : Component.translatable("screen.dynamic_economy.sell"));
        buyButton.active = row.canBuy() && row.hasStock(qty) && ClientMarketState.looksAffordable(buyTotal)
                && ClientMarketState.room(row.item(), stack(row).getMaxStackSize()) >= qty;
        sellButton.active = row.canSell() && ClientMarketState.held(row.item()) >= qty;
        if (!row.canBuy()) {
            buyButton.setTooltip(null);
        }
        if (!row.canSell()) {
            sellButton.setTooltip(null);
        }
    }

    // ---- Contracts -------------------------------------------------------------------------------------------

    private static List<MarketPayloads.ContractLine> contractLines() {
        MarketPayloads.Contracts board = ClientMarketState.contracts();
        return board == null ? List.of() : board.lines();
    }

    @Nullable
    private MarketPayloads.ContractLine selectedContractLine() {
        if (selectedContract == null) {
            return null;
        }
        for (MarketPayloads.ContractLine line : contractLines()) {
            if (line.id() == selectedContract) {
                return line;
            }
        }
        return null;
    }

    private static boolean deliverable(MarketPayloads.ContractLine line) {
        return line.mine() && line.status() == ContractBoard.Status.CLAIMED;
    }

    /** "All" on the Contracts tab: every copy held, up to what the contract still wants and the order limit. */
    private void fillDeliverAll() {
        MarketPayloads.ContractLine line = selectedContractLine();
        if (line == null || !deliverable(line)) {
            return;
        }
        int held = ClientMarketState.held(line.item());
        if (held == 0) {
            quantityNote = Component.translatable("screen.dynamic_economy.note.none_held");
            quantityNoteFor = quantityValue();
            return;
        }
        setQuantity(Math.min(Math.min(held, line.remaining()), ClientMarketState.maxQuantity()), Component.empty());
    }

    /** Client-side hints only; the server re-checks everything and replies with the real outcome. */
    private void refreshContractButtons() {
        if (tab != Tab.CONTRACTS) {
            return;
        }
        MarketPayloads.ContractLine line = selectedContractLine();
        MarketPayloads.Contracts board = ClientMarketState.contracts();
        if (line == null || board == null) {
            contractButton.setMessage(Component.translatable("screen.dynamic_economy.contracts.claim"));
            contractButton.active = false;
            contractButton.setTooltip(null);
            abandonButton.active = false;
            abandonButton.setTooltip(null);
            abandonArmed = null;
            return;
        }
        if (deliverable(line)) {
            int qty = quantityValue();
            contractButton.setMessage(Component.translatable("screen.dynamic_economy.contracts.deliver", PriceFormat.spurs(qty)));
            Component problem = qty <= 0 ? Component.translatable("screen.dynamic_economy.result.invalid_quantity")
                    : qty > ClientMarketState.maxQuantity() ? Component.translatable("screen.dynamic_economy.result.too_large")
                    : qty > line.remaining() ? Component.translatable("screen.dynamic_economy.result.contract_full")
                    : ClientMarketState.held(line.item()) < qty ? Component.translatable("screen.dynamic_economy.result.not_enough_items")
                    : null;
            contractButton.active = problem == null;
            contractButton.setTooltip(Tooltip.create(problem != null ? problem
                    : Component.translatable(qty == line.remaining() ? "screen.dynamic_economy.contracts.deliver.finishes"
                            : "screen.dynamic_economy.contracts.deliver.tooltip", PriceFormat.money(PriceFormat.spurs(line.reward() + line.deposit())))));
            abandonButton.active = true;
            abandonButton.setMessage(Component.translatable(Integer.valueOf(line.id()).equals(abandonArmed)
                    ? "screen.dynamic_economy.contracts.abandon_confirm" : "screen.dynamic_economy.contracts.abandon"));
            abandonButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.contracts.abandon.tooltip",
                    PriceFormat.money(PriceFormat.spurs(line.deposit())))));
            return;
        }
        abandonArmed = null;
        abandonButton.active = false;
        abandonButton.setMessage(Component.translatable("screen.dynamic_economy.contracts.abandon"));
        abandonButton.setTooltip(null);
        contractButton.setMessage(line.status() == ContractBoard.Status.OPEN
                ? Component.translatable("screen.dynamic_economy.contracts.claim_for", PriceFormat.money(PriceFormat.spurs(line.deposit())))
                : Component.translatable("screen.dynamic_economy.contracts.claim"));
        Component problem = line.status() != ContractBoard.Status.OPEN ? Component.translatable("screen.dynamic_economy.result.contract_taken")
                : board.held() >= board.maxHeld() ? Component.translatable("screen.dynamic_economy.result.contract_limit")
                : !ClientMarketState.looksAffordable(line.deposit()) ? Component.translatable("screen.dynamic_economy.result.no_funds")
                : null;
        contractButton.active = problem == null;
        contractButton.setTooltip(Tooltip.create(problem != null ? problem
                : Component.translatable("screen.dynamic_economy.contracts.claim.tooltip", PriceFormat.money(PriceFormat.spurs(line.deposit())))));
    }

    private void contractAction() {
        MarketPayloads.ContractLine line = selectedContractLine();
        if (line == null) {
            return;
        }
        if (deliverable(line)) {
            int qty = quantityValue();
            if (qty > 0) {
                PacketDistributor.sendToServer(new MarketPayloads.ContractRequest(ContractService.Action.DELIVER, line.id(), qty));
            }
        } else if (line.status() == ContractBoard.Status.OPEN) {
            PacketDistributor.sendToServer(new MarketPayloads.ContractRequest(ContractService.Action.CLAIM, line.id(), 0));
        }
    }

    private void abandon() {
        MarketPayloads.ContractLine line = selectedContractLine();
        if (line == null || !deliverable(line)) {
            return;
        }
        if (!Integer.valueOf(line.id()).equals(abandonArmed)) {
            abandonArmed = line.id();
            return;
        }
        abandonArmed = null;
        PacketDistributor.sendToServer(new MarketPayloads.ContractRequest(ContractService.Action.ABANDON, line.id(), 0));
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
        scroll = 0;
        listDirty = true;
        updateListButtons();
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
        List<ItemStack> items = minecraft.player.getInventory().items;
        for (int slot = 0; slot < items.size(); slot++) {
            if (ExchangeService.listable(items.get(slot))) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private ItemStack slotStack(int slot) {
        return minecraft.player.getInventory().items.get(slot);
    }

    /** How many units of exactly {@code like} the main inventory holds. */
    private int heldLike(ItemStack like) {
        int count = 0;
        for (ItemStack stack : minecraft.player.getInventory().items) {
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
                setQuantity(line.remaining(), Component.empty());
            }
            return;
        }
        if (exchangeMode == ExchangeMode.LIST) {
            if (listSlot >= 0) {
                setQuantity(Math.min(heldLike(listStack), listStack.getMaxStackSize()), Component.empty());
            }
            return;
        }
        if (exchangeSelected == null) {
            return;
        }
        int held = ClientMarketState.held(exchangeSelected);
        if (held == 0) {
            quantityNote = Component.translatable("screen.dynamic_economy.note.none_held");
            quantityNoteFor = quantityValue();
            return;
        }
        setQuantity(Math.min(held, quantityLimit()), Component.empty());
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
        } else if (!buy && exchangeSelected != null && ClientMarketState.held(exchangeSelected) < qty) {
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
    private void refreshExchangeButtons() {
        openExchangeButton.visible = tab == Tab.MARKET && selectedRow() != null && Tab.EXCHANGE.available(menu.bankOnly());
        if (tab != Tab.EXCHANGE) {
            return;
        }
        exchangeModeButton.setMessage(exchangeModeLabel());
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        switch (exchangeMode) {
            case ITEMS -> {
                int qty = quantityValue();
                if (qty != quantityNoteFor) {
                    quantityNote = Component.empty();
                }
                long each = priceValue();
                long total = qty > 0 && each > 0 ? MarketService.checkedTotal(each, qty).orElse(0) : 0;
                long tax = ExchangeService.tax(total, exchange == null ? 0 : exchange.tax());
                postBuyButton.setMessage(total > 0 && exchangeSelected != null
                        ? Component.translatable("screen.dynamic_economy.exchange.buy_for", PriceFormat.spurs(qty), PriceFormat.spurs(total))
                        : Component.translatable("screen.dynamic_economy.exchange.buy"));
                postSellButton.setMessage(total > 0 && exchangeSelected != null
                        ? Component.translatable("screen.dynamic_economy.exchange.sell_for", PriceFormat.spurs(qty), PriceFormat.spurs(total))
                        : Component.translatable("screen.dynamic_economy.exchange.sell"));
                Component buyProblem = exchangeSelected == null ? Component.translatable("screen.dynamic_economy.exchange.select_hint")
                        : postProblem(true, qty, each);
                Component sellProblem = exchangeSelected == null ? buyProblem : postProblem(false, qty, each);
                postBuyButton.active = buyProblem == null;
                postSellButton.active = sellProblem == null;
                postBuyButton.setTooltip(Tooltip.create(buyProblem != null ? buyProblem
                        : Component.translatable("screen.dynamic_economy.exchange.buy.tooltip", PriceFormat.money(PriceFormat.spurs(total)))));
                postSellButton.setTooltip(Tooltip.create(sellProblem != null ? sellProblem
                        : Component.translatable("screen.dynamic_economy.exchange.sell.tooltip", PriceFormat.money(PriceFormat.spurs(total - tax)),
                                PriceFormat.money(PriceFormat.spurs(tax)))));
            }
            case LISTINGS -> {
                int qty = quantityValue();
                if (qty != quantityNoteFor) {
                    quantityNote = Component.empty();
                }
                MarketPayloads.ListingLine line = selectedBrowseListing();
                long total = line == null || qty <= 0 ? 0 : MarketService.checkedTotal(line.price(), qty).orElse(0);
                exchangeActionButton.setMessage(total > 0
                        ? Component.translatable("screen.dynamic_economy.exchange.buy_for", PriceFormat.spurs(qty), PriceFormat.spurs(total))
                        : Component.translatable("screen.dynamic_economy.exchange.buy_listing"));
                Component problem = line == null ? Component.translatable("screen.dynamic_economy.exchange.listings.select_hint")
                        : buyListingProblem(line, qty);
                exchangeActionButton.active = problem == null;
                exchangeActionButton.setTooltip(Tooltip.create(problem != null ? problem
                        : Component.translatable("screen.dynamic_economy.exchange.buy_listing.tooltip", PriceFormat.money(PriceFormat.spurs(total)))));
            }
            case LIST -> {
                checkListSlot();
                int qty = quantityValue();
                if (qty != quantityNoteFor) {
                    quantityNote = Component.empty();
                }
                long each = priceValue();
                long total = qty > 0 && each > 0 ? MarketService.checkedTotal(each, qty).orElse(0) : 0;
                long tax = ExchangeService.tax(total, exchange == null ? 0 : exchange.tax());
                exchangeActionButton.setMessage(total > 0 && listSlot >= 0
                        ? Component.translatable("screen.dynamic_economy.exchange.list_for", PriceFormat.spurs(qty), PriceFormat.spurs(total))
                        : Component.translatable("screen.dynamic_economy.exchange.list"));
                Component problem = listSlot < 0 ? Component.translatable("screen.dynamic_economy.exchange.list.select_hint")
                        : listProblem(qty, each);
                exchangeActionButton.active = problem == null;
                exchangeActionButton.setTooltip(Tooltip.create(problem != null ? problem
                        : Component.translatable("screen.dynamic_economy.exchange.list.tooltip", PriceFormat.money(PriceFormat.spurs(total - tax)),
                                PriceFormat.money(PriceFormat.spurs(tax)))));
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
                                PriceFormat.money(PriceFormat.spurs(line.price() * line.remaining())))
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
        int qty = quantityValue();
        long each = priceValue();
        if (exchangeSelected != null && postProblem(buy, qty, each) == null) {
            PacketDistributor.sendToServer(MarketPayloads.ExchangeAction.post(exchangeSelected, buy, each, qty));
        }
    }

    /** Buys from the selected listing, lists the picked item, cancels the selected order, or empties the box. */
    private void exchangeAction() {
        int qty = quantityValue();
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
        MarketPayloads.Row row = selectedRow();
        if (row == null) {
            return;
        }
        exchangeMode = ExchangeMode.ITEMS;
        selectExchangeItem(row.item());
        selectTab(Tab.EXCHANGE);
        updateListButtons();
    }

    // ---- Bank ------------------------------------------------------------------------------------------------

    /** The amount typed to withdraw, or 0 if none. Ten digits can exceed one transaction; the server says so. */
    private long withdrawValue() {
        try {
            return Math.max(0, Long.parseLong(withdrawAmount.getValue()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Why the typed amount can't be withdrawn, or null if it looks fine. The server re-checks everything. */
    @Nullable
    private Component withdrawProblem(long amount, @Nullable long[] counts) {
        if (amount > Integer.MAX_VALUE) {
            return Component.translatable("screen.dynamic_economy.bank.result.too_large");
        }
        if (counts == null) {
            return Component.translatable("screen.dynamic_economy.bank.result.bank_unavailable");
        }
        if (!ClientMarketState.looksAffordable(amount)) {
            return Component.translatable("screen.dynamic_economy.bank.result.no_funds");
        }
        if (!ClientMarketState.coinsFit(counts)) {
            return Component.translatable("screen.dynamic_economy.bank.result.no_space");
        }
        return null;
    }

    /** The fewest coins for {@code amount}, or null if the coins aren't known. */
    @Nullable
    private static long[] coinsFor(long amount) {
        List<Denomination> coins = ClientMarketState.coins();
        return coins.isEmpty() || amount > Integer.MAX_VALUE ? null : CoinMath.breakdown(coins, amount);
    }

    private void refreshBankButtons() {
        if (tab != Tab.BANK) {
            return;
        }
        // Joining or leaving a team changes this while the screen is open.
        boolean inTeam = !ClientMarketState.team().isEmpty();
        accountButton.visible = inTeam;
        accountButton.setMessage(ClientMarketState.usingTeam()
                ? Component.translatable("screen.dynamic_economy.bank.use_team", ClientMarketState.team())
                : Component.translatable("screen.dynamic_economy.bank.use_own"));
        if (!wide) {
            depositButton.setWidth(inTeam ? (panelWidth - 2) / 2 : panelWidth);
        }
        long carried = ClientMarketState.carriedCoinValue();
        depositButton.active = carried > 0;
        depositButton.setMessage(carried > 0
                ? Component.translatable("screen.dynamic_economy.bank.deposit_for", PriceFormat.money(PriceFormat.spurs(carried)))
                : Component.translatable("screen.dynamic_economy.bank.deposit"));
        depositButton.setTooltip(Tooltip.create(Component.translatable(carried > 0
                ? "screen.dynamic_economy.bank.deposit.tooltip" : "screen.dynamic_economy.bank.result.no_coins")));
        long amount = withdrawValue();
        withdrawButton.active = amount > 0 && withdrawProblem(amount, coinsFor(amount)) == null;
    }

    private void switchAccount() {
        PacketDistributor.sendToServer(new MarketPayloads.PayFromRequest(!ClientMarketState.usingTeam()));
    }

    private void deposit() {
        PacketDistributor.sendToServer(new MarketPayloads.BankRequest(true, 0, Optional.empty()));
    }

    private void withdraw() {
        long amount = withdrawValue();
        if (amount > 0) {
            PacketDistributor.sendToServer(new MarketPayloads.BankRequest(false, amount, Optional.empty()));
        }
    }

    /** How many of {@code coin} a click withdraws: one, or with Shift a stack, or as many as the balance covers. */
    private static int coinsPerClick(Denomination coin) {
        if (!hasShiftDown()) {
            return 1;
        }
        long affordable = ClientMarketState.balance() / coin.value();
        return (int) Math.max(1, Math.min(BankService.maxStackSize(coin), affordable));
    }

    private void withdrawCoins(Denomination coin) {
        PacketDistributor.sendToServer(MarketPayloads.BankRequest.withdrawCoins(coin.item(), coinsPerClick(coin)));
    }

    private void trade(boolean buy) {
        MarketPayloads.Row row = selectedRow();
        int qty = quantityValue();
        if (row == null || qty <= 0 || qty > ClientMarketState.maxQuantity()) {
            return;
        }
        MarketPayloads.TradeRequest request = new MarketPayloads.TradeRequest(buy, row.item(), qty,
                buy ? row.buyTotal(qty) : row.sellTotal(qty));
        ClientMarketState.sent(request);
        PacketDistributor.sendToServer(request);
    }

    @Override
    public void removed() {
        super.removed();
        ClientMarketState.clear();
    }

    // ---- Input -----------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Tab clickedTab = tabAt(mouseX, mouseY);
            if (clickedTab != null) {
                if (available(clickedTab) && clickedTab != tab) {
                    minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    selectTab(clickedTab);
                }
                return true;
            }
            int index = rowAt(mouseX, mouseY);
            if (index >= 0 && (tab == Tab.MARKET || tab == Tab.HISTORY)) {
                // Clicking a past trade goes back to the market with that item selected, to trade it again.
                selected = showingTrades() ? trades().get(index).item() : visible.get(index).item();
                selectTab(Tab.MARKET);
                setFocused(null);
                refreshButtons();
                return true;
            }
            if (index >= 0 && tab == Tab.CONTRACTS) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                MarketPayloads.ContractLine line = contractLines().get(index);
                if (!Integer.valueOf(line.id()).equals(selectedContract)) {
                    selectedContract = line.id();
                    abandonArmed = null;
                    if (deliverable(line)) {
                        // Start from what can be delivered now.
                        startDeliveryQuantity(line);
                    }
                }
                setFocused(null);
                return true;
            }
            if (index >= 0 && tab == Tab.BANK) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                withdrawCoins(ClientMarketState.coins().get(index));
                return true;
            }
            if (index >= 0 && tab == Tab.EXCHANGE) {
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                int orders = orderLines().size();
                int boxed = boxLines().size();
                switch (exchangeMode) {
                    case ITEMS -> selectExchangeItem(exchangeItems.get(index));
                    case LISTINGS -> {
                        long id = browseLines.get(index).id();
                        if (!Long.valueOf(id).equals(selectedListing)) {
                            selectedListing = id;
                            setQuantity(1, Component.empty());
                        }
                    }
                    case LIST -> {
                        int slot = listableSlots().get(index);
                        if (slot != listSlot) {
                            listSlot = slot;
                            listStack = slotStack(slot).copyWithCount(1);
                            setQuantity(Math.min(heldLike(listStack), listStack.getMaxStackSize()), Component.empty());
                        }
                    }
                    case ORDERS -> selectedOrder = index < orders ? orderLines().get(index).id() : myListings().get(index - orders).id();
                    // Clicking a box line takes that item out.
                    case BOX -> PacketDistributor.sendToServer(index < boxed
                            ? MarketPayloads.ExchangeAction.collect(Optional.of(boxLines().get(index).item()))
                            : MarketPayloads.ExchangeAction.collectParcel(parcelLines().get(index - boxed).id()));
                }
                setFocused(null);
                return true;
            }
            BookRow bookRow = bookRowAt(mouseX, mouseY);
            if (bookRow != null) {
                // Clicking a price in the book copies it into the price box.
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                price.setValue(String.valueOf(bookRow.price()));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** One price drawn in the Exchange tab's book, in screen coordinates. */
    private record BookRow(int left, int top, int right, int bottom, long price, long mine) {
    }

    @Nullable
    private BookRow bookRowAt(double mouseX, double mouseY) {
        if (tab != Tab.EXCHANGE || exchangeMode != ExchangeMode.ITEMS) {
            return null;
        }
        for (BookRow row : bookRows) {
            if (mouseX >= row.left() && mouseX < row.right() && mouseY >= row.top() && mouseY < row.bottom()) {
                return row;
            }
        }
        return null;
    }

    private void startDeliveryQuantity(MarketPayloads.ContractLine line) {
        int held = ClientMarketState.held(line.item());
        setQuantity(Math.max(1, Math.min(Math.min(held, line.remaining()), ClientMarketState.maxQuantity())), Component.empty());
    }

    @Nullable
    private Tab tabAt(double mouseX, double mouseY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        if (y < TAB_Y || y >= TAB_Y + TAB_HEIGHT) {
            return null;
        }
        for (Tab t : Tab.values()) {
            if (x >= tabX[t.ordinal()] && x < tabX[t.ordinal()] + tabWidth[t.ordinal()]) {
                return t;
            }
        }
        return null;
    }

    private static List<dynamiceconomy.data.TradeHistory.Trade> trades() {
        MarketPayloads.Trades received = ClientMarketState.trades();
        return received == null ? List.of() : received.trades();
    }

    /** How many rows the list has in its current mode. */
    private int listSize() {
        return switch (tab) {
            case HISTORY -> trades().size();
            case LEADERBOARD -> leaderboardLines().size();
            case BANK -> ClientMarketState.coins().size();
            case CONTRACTS -> contractLines().size();
            case EXCHANGE -> switch (exchangeMode) {
                case ITEMS -> exchangeItems.size();
                case LISTINGS -> browseLines.size();
                case LIST -> listableSlots().size();
                case ORDERS -> orderLines().size() + myListings().size();
                case BOX -> boxLines().size() + parcelLines().size();
            };
            default -> visible.size();
        };
    }

    private static List<MarketPayloads.LeaderboardLine> leaderboardLines() {
        MarketPayloads.Leaderboard board = ClientMarketState.leaderboard();
        return board == null ? List.of() : board.lines();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isOverList(mouseX, mouseY)) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Keep typing in a text box from triggering the inventory key, which would close the screen.
        if (keyCode != GLFW.GLFW_KEY_ESCAPE && getFocused() instanceof EditBox box && box.canConsumeInput()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER && (box == quantity || box == withdrawAmount || box == price)) {
                setFocused(null);
                return true;
            }
            box.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean isOverList(double mouseX, double mouseY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        return x >= LIST_X && x < LIST_X + listWidth + 2 + SCROLLBAR_WIDTH && y >= LIST_Y && y < LIST_Y + rows * ROW_HEIGHT;
    }

    private int rowAt(double mouseX, double mouseY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        if (x < LIST_X || x >= LIST_X + listWidth || y < LIST_Y || y >= LIST_Y + rows * ROW_HEIGHT) {
            return -1;
        }
        int index = scroll + (int) ((y - LIST_Y) / ROW_HEIGHT);
        return index < listSize() ? index : -1;
    }

    private int maxScroll() {
        return Math.max(0, listSize() - rows);
    }

    // ---- Rendering -------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        Tab hoveredTab = tabAt(mouseX, mouseY);
        int index = rowAt(mouseX, mouseY);
        MarketPayloads.Row chartRow = selectedRow();
        if (hoveredTab != null && !available(hoveredTab)) {
            graphics.renderTooltip(font, hoveredTab.unavailableReason(menu.bankOnly()), mouseX, mouseY);
        } else if (chartRow != null && isOverChart(mouseX, mouseY)) {
            graphics.renderTooltip(font, chartTooltip(chartRow), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && tab == Tab.MARKET) {
            graphics.renderTooltip(font, tooltip(visible.get(index)), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && tab == Tab.HISTORY) {
            graphics.renderTooltip(font, Component.translatable("screen.dynamic_economy.trades.row_hint"), mouseX, mouseY);
        } else if (index >= 0 && tab == Tab.CONTRACTS) {
            graphics.renderTooltip(font, contractTooltip(contractLines().get(index)), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && tab == Tab.EXCHANGE && exchangeMode == ExchangeMode.ITEMS) {
            graphics.renderTooltip(font, exchangeTooltip(exchangeItems.get(index)), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && tab == Tab.EXCHANGE && exchangeMode == ExchangeMode.LISTINGS) {
            graphics.renderTooltip(font, listingTooltip(browseLines.get(index)), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && tab == Tab.EXCHANGE && exchangeMode == ExchangeMode.LIST) {
            graphics.renderTooltip(font, getTooltipFromItem(minecraft, slotStack(listableSlots().get(index))), Optional.empty(), mouseX, mouseY);
        } else if (index >= orderLines().size() && tab == Tab.EXCHANGE && exchangeMode == ExchangeMode.ORDERS) {
            graphics.renderTooltip(font, listingTooltip(myListings().get(index - orderLines().size())), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && tab == Tab.EXCHANGE && exchangeMode == ExchangeMode.BOX) {
            List<Component> lines = new ArrayList<>();
            if (index >= boxLines().size()) {
                lines.addAll(getTooltipFromItem(minecraft, parcelLines().get(index - boxLines().size()).stack()));
            }
            lines.add(Component.translatable("screen.dynamic_economy.exchange.box.click").withColor(TEXT_DIM));
            graphics.renderTooltip(font, lines, Optional.empty(), mouseX, mouseY);
        } else if (bookRowAt(mouseX, mouseY) != null) {
            BookRow row = bookRowAt(mouseX, mouseY);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("screen.dynamic_economy.exchange.book.click"));
            if (row.mine() > 0) {
                lines.add(Component.translatable("screen.dynamic_economy.exchange.book.yours", PriceFormat.count(row.mine())).withColor(TEXT_DIM));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        } else if (index >= 0 && tab == Tab.BANK) {
            Denomination coin = ClientMarketState.coins().get(index);
            int count = coinsPerClick(coin);
            graphics.renderComponentTooltip(font, List.of(
                    Component.translatable("screen.dynamic_economy.bank.click_withdraw", count, coin.item().getDescription(),
                            PriceFormat.money(PriceFormat.spurs((long) count * coin.value()))),
                    Component.translatable("screen.dynamic_economy.bank.click_hint").withColor(TEXT_DIM)), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        renderTabs(graphics, x, y, mouseX, mouseY);

        switch (tab) {
            case HISTORY -> renderTradeList(graphics, x, y, mouseX, mouseY);
            case LEADERBOARD -> renderLeaderboard(graphics, x, y);
            case BANK -> renderCoinList(graphics, x, y, mouseX, mouseY);
            case CONTRACTS -> renderContractList(graphics, x, y, mouseX, mouseY);
            case EXCHANGE -> renderExchangeList(graphics, x, y, mouseX, mouseY);
            default -> renderCatalogList(graphics, x, y, mouseX, mouseY);
        }
        renderScrollbar(graphics, x, y);

        if (tab == Tab.BANK) {
            renderBankPanel(graphics, x, y);
            return;
        }
        bookRows.clear();
        if (tab == Tab.EXCHANGE) {
            renderExchangePanel(graphics, x, y, mouseX, mouseY);
            return;
        }
        if (tab == Tab.CONTRACTS) {
            renderContractPanel(graphics, x, y);
            renderQuantityNote(graphics, x + noteX, y + noteY);
            return;
        }
        MarketPayloads.Row row = selectedRow();
        if (row != null) {
            int nameWidth = (wide ? panelWidth : chartX - MARGIN - 4) - (openExchangeButton.visible ? 40 : 20);
            graphics.renderItem(stack(row), x + panelX, y + panelY);
            graphics.drawString(font, font.plainSubstrByWidth(displayName(row), nameWidth), x + panelX + 20, y + panelY + 4, TEXT, false);
            renderHistory(graphics, row, x + chartX, y + chartY);
            if (wide) {
                renderInfo(graphics, row, x + panelX, y + infoY);
            }
        } else {
            graphics.drawString(font, font.plainSubstrByWidth(Component.translatable("screen.dynamic_economy.select_hint").getString(), panelWidth),
                    x + panelX, y + panelY + 4, TEXT_DIM, false);
        }
        renderQuantityNote(graphics, x + noteX, y + noteY);
    }

    /** The tab strip: the open tab highlighted, tabs not built yet greyed out. */
    private void renderTabs(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        Tab hovered = tabAt(mouseX, mouseY);
        for (Tab t : Tab.values()) {
            int left = x + tabX[t.ordinal()];
            int right = left + tabWidth[t.ordinal()];
            int top = y + TAB_Y;
            boolean open = t == tab;
            graphics.fill(left, top, right, top + TAB_HEIGHT, open ? TAB_ACTIVE : t == hovered && available(t) ? ROW_HOVER : TAB_IDLE);
            if (open) {
                graphics.fill(left, top + TAB_HEIGHT - 1, right, top + TAB_HEIGHT, 0xFF000000 | TEXT_MONEY);
            }
            graphics.drawCenteredString(font, t.label(), (left + right) / 2, top + 3, open ? TEXT : available(t) ? TEXT_DIM : TEXT_OFF);
        }
        graphics.fill(x + MARGIN, y + TAB_Y + TAB_HEIGHT, x + imageWidth - MARGIN, y + TAB_Y + TAB_HEIGHT + 1, PANEL_BORDER);
    }

    /** The player's recent trades, newest first: what, how many, the total, and how long ago. */
    private void renderTradeList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        MarketPayloads.Trades received = ClientMarketState.trades();
        List<dynamiceconomy.data.TradeHistory.Trade> trades = trades();
        int listRight = LIST_X + listWidth;
        int amountRight = listRight - 54;
        graphics.drawString(font, Component.translatable("screen.dynamic_economy.trades.title", trades.size()),
                x + LIST_X + 22, y + HEADER_Y, TEXT_DIM, false);
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < rows && scroll + i < trades.size(); i++) {
            dynamiceconomy.data.TradeHistory.Trade trade = trades.get(scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            int background = scroll + i == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(trade.item())), x + LIST_X + 2, rowY + 1);
            int textY = rowY + 5;
            String name = BuiltInRegistries.ITEM.get(trade.item()).getDescription().getString();
            Component what = Component.translatable(trade.buy() ? "screen.dynamic_economy.trades.bought"
                    : "screen.dynamic_economy.trades.sold", PriceFormat.spurs(trade.quantity()), name);
            graphics.drawString(font, font.plainSubstrByWidth(what.getString(), amountRight - 60 - LIST_X - 22),
                    x + LIST_X + 22, textY, TEXT, false);
            drawRight(graphics, Component.literal((trade.buy() ? "-" : "+") + PriceFormat.spurs(trade.total())),
                    x + amountRight, textY, trade.buy() ? TEXT_MONEY : TEXT_GOOD);
            drawRight(graphics, ago(received.now() - trade.time()), x + listRight - 2, textY, TEXT_DIM);
        }
        if (trades.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(received == null
                    ? "screen.dynamic_economy.trades.loading" : "screen.dynamic_economy.trades.none"),
                    x + LIST_X + listWidth / 2, y + LIST_Y + 20, TEXT_DIM);
        }
    }

    /** The richest players: rank, name and balance, with the player's own line highlighted and their rank above. */
    private void renderLeaderboard(GuiGraphics graphics, int x, int y) {
        MarketPayloads.Leaderboard board = ClientMarketState.leaderboard();
        List<MarketPayloads.LeaderboardLine> lines = leaderboardLines();
        int listRight = LIST_X + listWidth;
        graphics.drawString(font, Component.translatable("screen.dynamic_economy.leaderboard.title"),
                x + LIST_X + 4, y + HEADER_Y, TEXT_DIM, false);
        if (board != null) {
            Component own = board.ownRank() > 0
                    ? Component.translatable("screen.dynamic_economy.leaderboard.you", board.ownRank(), board.ranked())
                    : Component.translatable("screen.dynamic_economy.leaderboard.you_unranked");
            drawRight(graphics, own, x + listRight - 2, y + HEADER_Y, TEXT_DIM);
        }
        for (int i = 0; i < rows && scroll + i < lines.size(); i++) {
            MarketPayloads.LeaderboardLine line = lines.get(scroll + i);
            int rank = scroll + i + 1;
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            int background = line.you() ? ROW_SELECTED : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            int textY = rowY + 5;
            drawRight(graphics, Component.literal("#" + rank), x + LIST_X + 26, textY, rank <= 3 ? TEXT_MONEY : TEXT_DIM);
            graphics.drawString(font, line.name(), x + LIST_X + 32, textY, line.you() ? TEXT_GOOD : TEXT, false);
            drawRight(graphics, PriceFormat.money(PriceFormat.spurs(line.balance())
                    + (ClientMarketState.capped(line.balance()) ? "+" : "")), x + listRight - 2, textY, TEXT_MONEY);
        }
        if (lines.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(board == null
                            ? "screen.dynamic_economy.trades.loading" : "screen.dynamic_economy.leaderboard.none"),
                    x + LIST_X + listWidth / 2, y + LIST_Y + 20, TEXT_DIM);
        }
    }

    /** The board: what each contract wants, what it pays, and who holds it; the days left and the player's count above. */
    private void renderContractList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        MarketPayloads.Contracts board = ClientMarketState.contracts();
        List<MarketPayloads.ContractLine> lines = contractLines();
        int listRight = LIST_X + listWidth;
        int statusRight = listRight - 2;
        int rewardRight = listRight - 66;
        if (board != null) {
            graphics.drawString(font, Component.translatable(board.daysLeft() == 1 ? "screen.dynamic_economy.contracts.title_one"
                    : "screen.dynamic_economy.contracts.title", board.daysLeft()), x + LIST_X + 4, y + HEADER_Y, TEXT_DIM, false);
            drawRight(graphics, Component.translatable("screen.dynamic_economy.contracts.held", board.held(), board.maxHeld()),
                    x + statusRight, y + HEADER_Y, board.held() >= board.maxHeld() ? TEXT_MONEY : TEXT_DIM);
        }
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < rows && scroll + i < lines.size(); i++) {
            MarketPayloads.ContractLine line = lines.get(scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            boolean selectedRow = Integer.valueOf(line.id()).equals(selectedContract);
            int background = selectedRow ? ROW_SELECTED : scroll + i == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            boolean done = line.status() == ContractBoard.Status.COMPLETED || line.status() == ContractBoard.Status.ABANDONED;
            graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(line.item())), x + LIST_X + 2, rowY + 1);
            int textY = rowY + 5;
            Component what = Component.translatable("screen.dynamic_economy.contracts.wants", PriceFormat.spurs(line.units()),
                    BuiltInRegistries.ITEM.get(line.item()).getDescription());
            graphics.drawString(font, font.plainSubstrByWidth(what.getString(), rewardRight - 60 - LIST_X - 22),
                    x + LIST_X + 22, textY, done ? TEXT_DIM : TEXT, false);
            drawRight(graphics, PriceFormat.money(PriceFormat.spurs(line.reward())), x + rewardRight, textY, done ? TEXT_DIM : TEXT_MONEY);
            Component state;
            int color;
            switch (line.status()) {
                case OPEN -> {
                    state = Component.translatable("screen.dynamic_economy.contracts.open");
                    color = TEXT_GOOD;
                }
                case CLAIMED -> {
                    state = line.mine()
                            ? Component.literal(PriceFormat.count(line.delivered()) + "/" + PriceFormat.count(line.units()))
                            : Component.literal(line.holder());
                    color = line.mine() ? TEXT_MONEY : TEXT_DIM;
                }
                case COMPLETED -> {
                    state = Component.translatable("screen.dynamic_economy.contracts.done");
                    color = TEXT_OFF;
                }
                default -> {
                    state = Component.translatable("screen.dynamic_economy.contracts.given_up");
                    color = TEXT_OFF;
                }
            }
            drawRight(graphics, Component.literal(font.plainSubstrByWidth(state.getString(), 60)), x + statusRight, textY, color);
        }
        if (lines.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(board == null
                            ? "screen.dynamic_economy.trades.loading" : "screen.dynamic_economy.contracts.none"),
                    x + LIST_X + listWidth / 2, y + LIST_Y + 20, TEXT_DIM);
        }
    }

    /** The selected contract: what it pays, the deposit, progress, and how many the player holds; or how contracts work. */
    private void renderContractPanel(GuiGraphics graphics, int x, int y) {
        int left = x + panelX;
        MarketPayloads.ContractLine line = selectedContractLine();
        if (line == null) {
            List<net.minecraft.util.FormattedCharSequence> help = font.split(
                    Component.translatable(wide ? "screen.dynamic_economy.contracts.help" : "screen.dynamic_economy.contracts.select_hint"),
                    panelWidth);
            for (int i = 0; i < help.size() && panelY + 4 + i * 10 < controlsY - 10; i++) {
                graphics.drawString(font, help.get(i), left, y + panelY + 4 + i * 10, TEXT_DIM, false);
            }
            return;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(line.item()));
        graphics.renderItem(stack, left, y + panelY);
        int held = ClientMarketState.held(line.item());
        if (!wide) {
            Component summary = Component.translatable("screen.dynamic_economy.contracts.summary", stack.getHoverName(),
                    PriceFormat.money(PriceFormat.spurs(line.reward())), PriceFormat.money(PriceFormat.spurs(line.deposit())),
                    PriceFormat.count(line.delivered()), PriceFormat.count(line.units()));
            graphics.drawString(font, font.plainSubstrByWidth(summary.getString(), panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
            return;
        }
        graphics.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
        int right = left + panelWidth;
        int top = y + chartY;
        infoLine(graphics, "contracts.reward", PriceFormat.spurs(line.reward()), TEXT_MONEY, left, right, top);
        infoLine(graphics, "contracts.per_unit", PriceFormat.decimal(java.math.BigDecimal.valueOf(line.reward())
                .divide(java.math.BigDecimal.valueOf(line.units()), 2, RoundingMode.FLOOR)), TEXT_MONEY, left, right, top + INFO_LINE_HEIGHT);
        infoLine(graphics, "contracts.deposit", PriceFormat.spurs(line.deposit()), TEXT, left, right, top + 2 * INFO_LINE_HEIGHT);
        infoLine(graphics, "contracts.progress", PriceFormat.count(line.delivered()) + "/" + PriceFormat.count(line.units()),
                line.mine() ? TEXT_MONEY : TEXT, left, right, top + 3 * INFO_LINE_HEIGHT);
        infoLine(graphics, "col.have", PriceFormat.spurs(held), held > 0 ? TEXT : TEXT_DIM, left, right, top + 4 * INFO_LINE_HEIGHT);
        Component note = switch (line.status()) {
            case OPEN -> Component.translatable("screen.dynamic_economy.contracts.note.open");
            case CLAIMED -> line.mine() ? Component.translatable("screen.dynamic_economy.contracts.note.mine")
                    : Component.translatable("screen.dynamic_economy.contracts.note.taken", line.holder());
            case COMPLETED -> Component.translatable("screen.dynamic_economy.contracts.note.done");
            case ABANDONED -> Component.translatable("screen.dynamic_economy.contracts.note.given_up");
        };
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(note, panelWidth);
        int noteTop = chartY + 5 * INFO_LINE_HEIGHT + 4;
        for (int i = 0; i < lines.size() && noteTop + i * 10 < controlsY - 9; i++) {
            graphics.drawString(font, lines.get(i), left, y + noteTop + i * 10, TEXT_DIM, false);
        }
    }

    private List<Component> contractTooltip(MarketPayloads.ContractLine line) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, new ItemStack(BuiltInRegistries.ITEM.get(line.item()))));
        lines.add(Component.translatable("screen.dynamic_economy.contracts.tooltip.reward",
                PriceFormat.money(PriceFormat.spurs(line.reward())), PriceFormat.money(PriceFormat.spurs(line.deposit()))).withColor(TEXT_MONEY));
        if (line.status() == ContractBoard.Status.CLAIMED && !line.mine()) {
            lines.add(Component.translatable("screen.dynamic_economy.contracts.note.taken", line.holder()).withColor(TEXT_DIM));
        }
        return lines;
    }

    /** The Exchange tab's list: items with their best prices, the player's open orders, or their collection box. */
    private void renderExchangeList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        int listRight = LIST_X + listWidth;
        int headerY = y + HEADER_Y;
        int hovered = rowAt(mouseX, mouseY);
        int size = listSize();
        switch (exchangeMode) {
            case ITEMS -> {
                graphics.drawString(font, Component.translatable("screen.dynamic_economy.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
                drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.col.ask"), x + colBuy, headerY, TEXT_DIM);
                drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.col.bid"), x + colSell, headerY, TEXT_DIM);
                drawRight(graphics, Component.translatable("screen.dynamic_economy.col.have"), x + colHave, headerY, TEXT_DIM);
            }
            case LISTINGS -> {
                graphics.drawString(font, Component.translatable("screen.dynamic_economy.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
                drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.col.price"), x + colSell, headerY, TEXT_DIM);
                drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.col.left"), x + colHave, headerY, TEXT_DIM);
            }
            case LIST -> {
                graphics.drawString(font, Component.translatable("screen.dynamic_economy.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
                drawRight(graphics, Component.translatable("screen.dynamic_economy.col.have"), x + colHave, headerY, TEXT_DIM);
            }
            case ORDERS -> graphics.drawString(font, Component.translatable("screen.dynamic_economy.exchange.orders.title",
                    exchange == null ? 0 : exchange.open(), exchange == null ? 0 : exchange.maxOrders()), x + LIST_X + 4, headerY, TEXT_DIM, false);
            case BOX -> {
                graphics.drawString(font, Component.translatable("screen.dynamic_economy.exchange.box.title"), x + LIST_X + 4, headerY, TEXT_DIM, false);
                drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.col.waiting"), x + listRight - 2, headerY, TEXT_DIM);
            }
        }
        List<Integer> slots = exchangeMode == ExchangeMode.LIST ? listableSlots() : List.of();
        int orders = orderLines().size();
        int boxed = boxLines().size();
        for (int i = 0; i < rows && scroll + i < size; i++) {
            int index = scroll + i;
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
                    graphics.drawString(font, font.plainSubstrByWidth(displayName(item), colBuy - 44 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
                    ExchangeService.Summary s = summary(item);
                    boolean asks = s != null && s.bestAsk() > 0;
                    boolean bids = s != null && s.bestBid() > 0;
                    drawRight(graphics, Component.literal(asks ? PriceFormat.compact(s.bestAsk()) : "-"), x + colBuy, textY, asks ? TEXT_MONEY : TEXT_DIM);
                    drawRight(graphics, Component.literal(bids ? PriceFormat.compact(s.bestBid()) : "-"), x + colSell, textY, bids ? TEXT_GOOD : TEXT_DIM);
                    int held = ClientMarketState.held(item);
                    drawRight(graphics, Component.literal(PriceFormat.count(held)), x + colHave, textY, held > 0 ? TEXT : TEXT_DIM);
                }
                case LISTINGS -> {
                    MarketPayloads.ListingLine line = browseLines.get(index);
                    renderStack(graphics, line.stack(), x + LIST_X + 2, rowY + 1);
                    String name = line.stack().getHoverName().getString() + (line.mine() ? " *" : "");
                    graphics.drawString(font, font.plainSubstrByWidth(name, colSell - 44 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
                    drawRight(graphics, Component.literal(PriceFormat.compact(line.price())), x + colSell, textY, TEXT_MONEY);
                    drawRight(graphics, Component.literal(PriceFormat.count(line.remaining())), x + colHave, textY, TEXT);
                }
                case LIST -> {
                    ItemStack stack = slotStack(slots.get(index));
                    renderStack(graphics, stack, x + LIST_X + 2, rowY + 1);
                    graphics.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), colHave - 44 - LIST_X - 22),
                            x + LIST_X + 22, textY, TEXT, false);
                    drawRight(graphics, Component.literal(PriceFormat.count(heldLike(stack))), x + colHave, textY, TEXT);
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
                            : "screen.dynamic_economy.exchange.order.sell", PriceFormat.spurs(line.quantity()), displayName(line.item()));
                    graphics.drawString(font, font.plainSubstrByWidth(what.getString(), priceRight - 50 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
                    drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.order.each", PriceFormat.spurs(line.price())),
                            x + priceRight, textY, line.buy() ? TEXT_MONEY : TEXT_GOOD);
                    drawRight(graphics, Component.literal(PriceFormat.count(line.filled()) + "/" + PriceFormat.count(line.quantity())),
                            x + listRight - 2, textY, line.filled() > 0 ? TEXT : TEXT_DIM);
                }
                case BOX -> {
                    if (index >= boxed) {
                        MarketPayloads.ParcelLine parcel = parcelLines().get(index - boxed);
                        renderStack(graphics, parcel.stack(), x + LIST_X + 2, rowY + 1);
                        graphics.drawString(font, font.plainSubstrByWidth(parcel.stack().getHoverName().getString(), listWidth - 90),
                                x + LIST_X + 22, textY, TEXT, false);
                        drawRight(graphics, Component.literal(PriceFormat.count(parcel.count())), x + listRight - 2, textY, TEXT);
                        continue;
                    }
                    MarketPayloads.BoxLine line = boxLines().get(index);
                    graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(line.item())), x + LIST_X + 2, rowY + 1);
                    graphics.drawString(font, font.plainSubstrByWidth(displayName(line.item()), listWidth - 90), x + LIST_X + 22, textY, TEXT, false);
                    drawRight(graphics, Component.literal(PriceFormat.count(line.count())), x + listRight - 2, textY, TEXT);
                }
            }
        }
        if (size == 0) {
            String key = exchange == null ? "screen.dynamic_economy.trades.loading" : switch (exchangeMode) {
                case ITEMS -> search.getValue().isBlank() ? "screen.dynamic_economy.exchange.none_items" : "screen.dynamic_economy.no_matches";
                case LISTINGS -> ClientMarketState.exchangeListings() == null ? "screen.dynamic_economy.trades.loading"
                        : search.getValue().isBlank() ? "screen.dynamic_economy.exchange.none_listings" : "screen.dynamic_economy.no_matches";
                case LIST -> "screen.dynamic_economy.exchange.none_listable";
                case ORDERS -> "screen.dynamic_economy.exchange.none_orders";
                case BOX -> "screen.dynamic_economy.exchange.none_box";
            };
            // Long enough to need wrapping when it explains what can be listed.
            List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.translatable(key), listWidth - 8);
            for (int i = 0; i < lines.size(); i++) {
                graphics.drawCenteredString(font, lines.get(i), x + LIST_X + listWidth / 2, y + LIST_Y + 20 + i * 10, TEXT_DIM);
            }
        } else if (exchangeMode == ExchangeMode.LISTINGS && ClientMarketState.exchangeListings() != null
                && ClientMarketState.exchangeListings().more() && scroll + rows >= size) {
            graphics.drawString(font, Component.translatable("screen.dynamic_economy.exchange.listings.more"), x + LIST_X + 4,
                    y + LIST_Y + Math.min(rows, size) * ROW_HEIGHT + 2, TEXT_DIM, false);
        }
    }

    /** One of the player's listings under My orders, drawn like an order. */
    private void renderListingRow(GuiGraphics graphics, MarketPayloads.ListingLine line, int x, int rowY, int textY, int listRight) {
        int priceRight = listRight - 60;
        renderStack(graphics, line.stack(), x + LIST_X + 2, rowY + 1);
        Component what = Component.translatable("screen.dynamic_economy.exchange.order.listing", PriceFormat.spurs(line.quantity()),
                line.stack().getHoverName().getString());
        graphics.drawString(font, font.plainSubstrByWidth(what.getString(), priceRight - 50 - LIST_X - 22), x + LIST_X + 22, textY, TEXT, false);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.order.each", PriceFormat.spurs(line.price())),
                x + priceRight, textY, TEXT_GOOD);
        drawRight(graphics, Component.literal(PriceFormat.count(line.sold()) + "/" + PriceFormat.count(line.quantity())),
                x + listRight - 2, textY, line.sold() > 0 ? TEXT : TEXT_DIM);
    }

    /** An item with its count and durability bar. */
    private void renderStack(GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.renderItem(stack, x, y);
        graphics.renderItemDecorations(font, stack, x, y);
    }

    /** A listed item's own tooltip, then who sells it and for how much. */
    private List<Component> listingTooltip(MarketPayloads.ListingLine line) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, line.stack()));
        lines.add(Component.translatable("screen.dynamic_economy.exchange.listing.offer", PriceFormat.count(line.remaining()),
                PriceFormat.money(PriceFormat.spurs(line.price()))).withColor(TEXT_MONEY));
        lines.add((line.mine() ? Component.translatable("screen.dynamic_economy.exchange.listing.yours")
                : Component.translatable("screen.dynamic_economy.exchange.listing.by", line.seller())).withColor(TEXT_DIM));
        return lines;
    }

    /** The item's tooltip below its name, from {@code top} down to {@code bottom}, so a buyer sees it in full. */
    private void renderItemDetails(GuiGraphics graphics, ItemStack stack, int left, int top, int bottom) {
        List<Component> lines = getTooltipFromItem(minecraft, stack);
        int y = top;
        // The first line is the name, shown above already.
        for (int i = 1; i < lines.size(); i++) {
            for (net.minecraft.util.FormattedCharSequence part : font.split(lines.get(i), panelWidth)) {
                if (y + 9 > bottom) {
                    return;
                }
                graphics.drawString(font, part, left, y, TEXT_DIM, false);
                y += 10;
            }
        }
    }

    /** Browsing listings: the selected listing in full, and what buying the quantity costs. */
    private void renderListingPanel(GuiGraphics graphics, int x, int y, String percent) {
        int left = x + panelX;
        MarketPayloads.ListingLine line = selectedBrowseListing();
        if (line == null) {
            renderHelp(graphics, Component.translatable(wide ? "screen.dynamic_economy.exchange.listings.help"
                    : "screen.dynamic_economy.exchange.listings.select_hint", percent), left, y + panelY + 4);
            return;
        }
        renderStack(graphics, line.stack(), left, y + panelY);
        String name = line.stack().getHoverName().getString();
        if (wide) {
            int right = left + panelWidth;
            int top = y + chartY;
            graphics.drawString(font, font.plainSubstrByWidth(name, panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
            infoLine(graphics, "exchange.listing.seller", line.mine() ? Component.translatable("screen.dynamic_economy.exchange.listing.you").getString()
                    : line.seller(), TEXT, left, right, top);
            infoLine(graphics, "exchange.order.price", PriceFormat.spurs(line.price()), TEXT_MONEY, left, right, top + INFO_LINE_HEIGHT);
            infoLine(graphics, "exchange.listing.left", PriceFormat.count(line.remaining()), TEXT, left, right, top + 2 * INFO_LINE_HEIGHT);
            renderItemDetails(graphics, line.stack(), left, top + 3 * INFO_LINE_HEIGHT + 4, y + controlsY - 4);
        } else {
            Component summary = Component.translatable("screen.dynamic_economy.exchange.listing.summary", name, PriceFormat.spurs(line.price()),
                    PriceFormat.count(line.remaining()), line.seller());
            graphics.drawString(font, font.plainSubstrByWidth(summary.getString(), panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
        }
        int qty = quantityValue();
        long total = qty > 0 ? MarketService.checkedTotal(line.price(), qty).orElse(0) : 0;
        Component note = !quantityNote.getString().isEmpty() ? quantityNote
                : total > 0 ? Component.translatable("screen.dynamic_economy.exchange.listing.total", PriceFormat.spurs(total)) : Component.empty();
        graphics.drawString(font, font.plainSubstrByWidth(note.getString(), noteWidth), x + noteX, y + noteY, TEXT_DIM, false);
    }

    /** Listing an item: the picked item in full, the price box, and what the listing comes to. */
    private void renderListPanel(GuiGraphics graphics, int x, int y, String percent) {
        int left = x + panelX;
        if (listSlot < 0) {
            renderHelp(graphics, Component.translatable(wide ? "screen.dynamic_economy.exchange.list.help"
                    : "screen.dynamic_economy.exchange.list.select_hint", percent), left, y + panelY + 4);
            return;
        }
        renderStack(graphics, listStack, left, y + panelY);
        String name = listStack.getHoverName().getString();
        if (!wide) {
            graphics.drawString(font, font.plainSubstrByWidth(name, panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
            graphics.drawString(font, "@", x + noteX, y + controlsY + 5, TEXT_DIM, false);
            return;
        }
        graphics.drawString(font, font.plainSubstrByWidth(name, panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
        renderItemDetails(graphics, listStack, left, y + chartY, y + priceY - 14);
        infoLine(graphics, "col.have", PriceFormat.spurs(heldLike(listStack)), TEXT, left, left + panelWidth, y + priceY - 12);
        graphics.drawString(font, Component.translatable("screen.dynamic_economy.exchange.price"), left, y + priceY + 3, TEXT_DIM, false);
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        int qty = quantityValue();
        long each = priceValue();
        long total = qty > 0 && each > 0 ? MarketService.checkedTotal(each, qty).orElse(0) : 0;
        Component note;
        int color = TEXT_DIM;
        if (!quantityNote.getString().isEmpty()) {
            note = quantityNote;
        } else if (qty > listStack.getMaxStackSize()) {
            note = Component.translatable("screen.dynamic_economy.note.over_limit", PriceFormat.spurs(listStack.getMaxStackSize()));
            color = TEXT_BAD;
        } else if (total > 0) {
            note = Component.translatable("screen.dynamic_economy.exchange.note.total", PriceFormat.spurs(total),
                    PriceFormat.spurs(ExchangeService.tax(total, exchange == null ? 0 : exchange.tax())));
        } else {
            note = Component.translatable("screen.dynamic_economy.exchange.note.tax", percent);
        }
        graphics.drawString(font, font.plainSubstrByWidth(note.getString(), noteWidth), x + noteX, y + noteY, color, false);
    }

    /** The Exchange tab's panel: the selected item's book and order controls, the selected order, or the box's help. */
    private void renderExchangePanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        int left = x + panelX;
        MarketPayloads.Exchange exchange = ClientMarketState.exchange();
        String percent = exchange == null ? "-" : String.format(Locale.ROOT, "%.1f%%", exchange.tax() * 100);
        if (exchangeMode == ExchangeMode.BOX) {
            renderHelp(graphics, Component.translatable("screen.dynamic_economy.exchange.box.help"), left, y + panelY + 4);
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
            renderHelp(graphics, Component.translatable(wide ? "screen.dynamic_economy.exchange.help" : "screen.dynamic_economy.exchange.select_hint",
                    percent), left, y + panelY + 4);
            return;
        }
        graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(exchangeSelected)), left, y + panelY);
        if (!wide) {
            ExchangeService.Summary s = summary(exchangeSelected);
            Component line = Component.translatable("screen.dynamic_economy.exchange.summary", displayName(exchangeSelected),
                    s != null && s.bestAsk() > 0 ? PriceFormat.spurs(s.bestAsk()) : "-", s != null && s.bestBid() > 0 ? PriceFormat.spurs(s.bestBid()) : "-");
            graphics.drawString(font, font.plainSubstrByWidth(line.getString(), panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
            graphics.drawString(font, "@", x + noteX, y + controlsY + 5, TEXT_DIM, false);
            return;
        }
        graphics.drawString(font, font.plainSubstrByWidth(displayName(exchangeSelected), panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
        renderBook(graphics, left, y + chartY, y + priceY - 13, mouseX, mouseY);
        int held = ClientMarketState.held(exchangeSelected);
        infoLine(graphics, "col.have", PriceFormat.spurs(held), held > 0 ? TEXT : TEXT_DIM, left, left + panelWidth, y + priceY - 12);
        graphics.drawString(font, Component.translatable("screen.dynamic_economy.exchange.price"), left, y + priceY + 3, TEXT_DIM, false);

        // Under the quantity: what the order comes to, and the tax a sale pays.
        int qty = quantityValue();
        long each = priceValue();
        long total = qty > 0 && each > 0 ? MarketService.checkedTotal(each, qty).orElse(0) : 0;
        Component note;
        int color = TEXT_DIM;
        if (!quantityNote.getString().isEmpty()) {
            note = quantityNote;
        } else if (exchange != null && qty > exchange.maxOrderSize()) {
            note = Component.translatable("screen.dynamic_economy.note.over_limit", PriceFormat.spurs(exchange.maxOrderSize()));
            color = TEXT_BAD;
        } else if (total > 0) {
            note = Component.translatable("screen.dynamic_economy.exchange.note.total", PriceFormat.spurs(total),
                    PriceFormat.spurs(ExchangeService.tax(total, exchange == null ? 0 : exchange.tax())));
        } else {
            note = Component.translatable("screen.dynamic_economy.exchange.note.tax", percent);
        }
        graphics.drawString(font, font.plainSubstrByWidth(note.getString(), noteWidth), x + noteX, y + noteY, color, false);
    }

    /**
     * The selected item's book between {@code top} and {@code bottom}: sellers' prices above the line with the
     * cheapest nearest it, buyers' below with the highest nearest it. Each price can be clicked to copy it.
     */
    private void renderBook(GuiGraphics graphics, int left, int top, int bottom, int mouseX, int mouseY) {
        int right = left + panelWidth;
        graphics.drawString(font, Component.translatable("screen.dynamic_economy.exchange.book.price"), left, top, TEXT_DIM, false);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.exchange.book.units"), right, top, TEXT_DIM);
        MarketPayloads.ExchangeBook book = exchangeSelected == null ? null : ClientMarketState.exchangeBook(exchangeSelected);
        if (book == null) {
            graphics.drawString(font, Component.translatable("screen.dynamic_economy.trades.loading"), left, top + 12, TEXT_DIM, false);
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
            graphics.drawString(font, Component.translatable("screen.dynamic_economy.exchange.book.no_asks"), left, lineY - 10, TEXT_OFF, false);
        }
        graphics.fill(left, lineY + 4, right, lineY + 5, PANEL_BORDER);
        if (!book.asks().isEmpty() && !book.bids().isEmpty()) {
            Component spread = Component.translatable("screen.dynamic_economy.exchange.book.spread",
                    PriceFormat.spurs(book.asks().get(0).price() - book.bids().get(0).price()));
            int width = font.width(spread) + 4;
            int middle = (left + right) / 2;
            graphics.fill(middle - width / 2, lineY, middle + width / 2, lineY + 9, PANEL);
            graphics.drawCenteredString(font, spread, middle, lineY + 1, TEXT_DIM);
        }
        for (int j = 0; j < Math.min(perSide, book.bids().size()); j++) {
            bookLine(graphics, book.bids().get(j), left, right, bidsTop + j * 10, TEXT_GOOD, mouseX, mouseY);
        }
        if (book.bids().isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.dynamic_economy.exchange.book.no_bids"), left, bidsTop, TEXT_OFF, false);
        }
    }

    /** One price in the book; the player's own share is marked with a star. */
    private void bookLine(GuiGraphics graphics, ExchangeService.Level level, int left, int right, int top, int color, int mouseX, int mouseY) {
        BookRow row = new BookRow(left, top - 1, right, top + 9, level.price(), level.mine());
        bookRows.add(row);
        if (mouseX >= row.left() && mouseX < row.right() && mouseY >= row.top() && mouseY < row.bottom()) {
            graphics.fill(row.left(), row.top(), row.right(), row.bottom(), ROW_HOVER);
        }
        graphics.drawString(font, PriceFormat.spurs(level.price()) + (level.mine() > 0 ? " *" : ""), left, top, color, false);
        drawRight(graphics, Component.literal(PriceFormat.count(level.quantity())), right, top, TEXT);
    }

    /** The selected order: its side, price, progress, what it still holds and when it expires. */
    private void renderOrderPanel(GuiGraphics graphics, int x, int y) {
        int left = x + panelX;
        MarketPayloads.OrderLine line = selectedOrderLine();
        MarketPayloads.ListingLine listing = selectedMyListing();
        if (line == null && listing != null) {
            renderMyListingPanel(graphics, x, y, listing);
            return;
        }
        if (line == null) {
            renderHelp(graphics, Component.translatable(wide ? "screen.dynamic_economy.exchange.orders.help"
                    : "screen.dynamic_economy.exchange.orders.select_hint"), left, y + panelY + 4);
            return;
        }
        graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(line.item())), left, y + panelY);
        Component expires = line.daysLeft() < 0 ? Component.translatable("screen.dynamic_economy.exchange.order.never")
                : Component.translatable("screen.dynamic_economy.exchange.order.days", line.daysLeft());
        if (!wide) {
            Component summary = Component.translatable("screen.dynamic_economy.exchange.order.summary", displayName(line.item()),
                    PriceFormat.spurs(line.price()), PriceFormat.count(line.filled()), PriceFormat.count(line.quantity()));
            graphics.drawString(font, font.plainSubstrByWidth(summary.getString(), panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
            return;
        }
        int right = left + panelWidth;
        int top = y + chartY;
        graphics.drawString(font, font.plainSubstrByWidth(displayName(line.item()), panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
        infoLine(graphics, "exchange.order.side", Component.translatable(line.buy() ? "screen.dynamic_economy.exchange.order.side_buy"
                : "screen.dynamic_economy.exchange.order.side_sell").getString(), line.buy() ? TEXT_MONEY : TEXT_GOOD, left, right, top);
        infoLine(graphics, "exchange.order.price", PriceFormat.spurs(line.price()), TEXT, left, right, top + INFO_LINE_HEIGHT);
        infoLine(graphics, "exchange.order.filled", PriceFormat.count(line.filled()) + "/" + PriceFormat.count(line.quantity()), TEXT,
                left, right, top + 2 * INFO_LINE_HEIGHT);
        infoLine(graphics, "exchange.order.holds", line.buy() ? PriceFormat.money(PriceFormat.spurs(line.price() * line.remaining())).getString()
                : Component.translatable("screen.dynamic_economy.exchange.order.items", PriceFormat.count(line.remaining())).getString(),
                TEXT, left, right, top + 3 * INFO_LINE_HEIGHT);
        infoLine(graphics, "exchange.order.expires", expires.getString(), TEXT_DIM, left, right, top + 4 * INFO_LINE_HEIGHT);
    }

    /** One of the player's own listings under My orders: its price, sales, what it still holds and when it expires. */
    private void renderMyListingPanel(GuiGraphics graphics, int x, int y, MarketPayloads.ListingLine line) {
        int left = x + panelX;
        renderStack(graphics, line.stack(), left, y + panelY);
        String name = line.stack().getHoverName().getString();
        if (!wide) {
            Component summary = Component.translatable("screen.dynamic_economy.exchange.order.summary", name,
                    PriceFormat.spurs(line.price()), PriceFormat.count(line.sold()), PriceFormat.count(line.quantity()));
            graphics.drawString(font, font.plainSubstrByWidth(summary.getString(), panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
            return;
        }
        int right = left + panelWidth;
        int top = y + chartY;
        graphics.drawString(font, font.plainSubstrByWidth(name, panelWidth - 20), left + 20, y + panelY + 4, TEXT, false);
        infoLine(graphics, "exchange.order.side", Component.translatable("screen.dynamic_economy.exchange.order.side_listing").getString(),
                TEXT_GOOD, left, right, top);
        infoLine(graphics, "exchange.order.price", PriceFormat.spurs(line.price()), TEXT, left, right, top + INFO_LINE_HEIGHT);
        infoLine(graphics, "exchange.order.sold", PriceFormat.count(line.sold()) + "/" + PriceFormat.count(line.quantity()), TEXT,
                left, right, top + 2 * INFO_LINE_HEIGHT);
        infoLine(graphics, "exchange.order.holds", Component.translatable("screen.dynamic_economy.exchange.order.items",
                PriceFormat.count(line.remaining())).getString(), TEXT, left, right, top + 3 * INFO_LINE_HEIGHT);
        Component expires = line.daysLeft() < 0 ? Component.translatable("screen.dynamic_economy.exchange.order.never")
                : Component.translatable("screen.dynamic_economy.exchange.order.days", line.daysLeft());
        infoLine(graphics, "exchange.order.expires", expires.getString(), TEXT_DIM, left, right, top + 4 * INFO_LINE_HEIGHT);
        renderItemDetails(graphics, line.stack(), left, top + 5 * INFO_LINE_HEIGHT + 4, y + tradeY - 4);
    }

    /** Wrapped help text from {@code top} down to the controls. */
    private void renderHelp(GuiGraphics graphics, Component text, int left, int top) {
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(text, panelWidth);
        // Above the price box when it shows, otherwise above the button; one line when narrow.
        int bottom = topPos + (!wide ? panelY + 14 : exchangeMode == ExchangeMode.ITEMS || exchangeMode == ExchangeMode.LIST ? priceY - 2
                : exchangeMode == ExchangeMode.LISTINGS ? controlsY - 2 : tradeY - 2);
        for (int i = 0; i < lines.size() && top + i * 10 + 9 <= bottom; i++) {
            graphics.drawString(font, lines.get(i), left, top + i * 10, TEXT_DIM, false);
        }
    }

    /** The item's own tooltip plus its best prices on the exchange and, if listed, what the market pays. */
    private List<Component> exchangeTooltip(ResourceLocation item) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, new ItemStack(BuiltInRegistries.ITEM.get(item))));
        ExchangeService.Summary s = summary(item);
        if (s != null && s.bestAsk() > 0) {
            lines.add(Component.translatable("screen.dynamic_economy.exchange.tooltip.ask", PriceFormat.money(PriceFormat.spurs(s.bestAsk())),
                    PriceFormat.count(s.askUnits())).withColor(TEXT_MONEY));
        }
        if (s != null && s.bestBid() > 0) {
            lines.add(Component.translatable("screen.dynamic_economy.exchange.tooltip.bid", PriceFormat.money(PriceFormat.spurs(s.bestBid())),
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

    /** The bank's coins, largest first: what each is worth and how many the player carries. Clicking one withdraws it. */
    private void renderCoinList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        List<Denomination> coins = ClientMarketState.coins();
        int listRight = LIST_X + listWidth;
        int worthRight = listRight - 60;
        graphics.drawString(font, Component.translatable("screen.dynamic_economy.bank.col.coin"), x + LIST_X + 22, y + HEADER_Y, TEXT_DIM, false);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.bank.col.worth"), x + worthRight, y + HEADER_Y, TEXT_DIM);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.col.have"), x + listRight - 2, y + HEADER_Y, TEXT_DIM);
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < rows && scroll + i < coins.size(); i++) {
            Denomination coin = coins.get(scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            int background = scroll + i == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            graphics.renderItem(new ItemStack(coin.item()), x + LIST_X + 2, rowY + 1);
            int textY = rowY + 5;
            graphics.drawString(font, coin.item().getDescription(), x + LIST_X + 22, textY, TEXT, false);
            drawRight(graphics, PriceFormat.money(PriceFormat.spurs(coin.value())), x + worthRight, textY, TEXT_MONEY);
            int held = ClientMarketState.held(coin);
            drawRight(graphics, Component.literal(PriceFormat.count(held)), x + listRight - 2, textY, held > 0 ? TEXT : TEXT_DIM);
        }
        if (coins.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.dynamic_economy.bank.result.bank_unavailable"),
                    x + LIST_X + listWidth / 2, y + LIST_Y + 20, TEXT_DIM);
        }
    }

    /** What the bank holds and the carried coins are worth, and what a withdrawal would give, or why it can't. */
    private void renderBankPanel(GuiGraphics graphics, int x, int y) {
        int left = x + panelX;
        long balance = ClientMarketState.balance();
        Component inBankAmount = PriceFormat.money(PriceFormat.spurs(balance) + (ClientMarketState.capped(balance) ? "+" : ""));
        Component inBank = ClientMarketState.usingTeam()
                ? Component.translatable("screen.dynamic_economy.bank.in_team_bank", ClientMarketState.team(), inBankAmount)
                : Component.translatable("screen.dynamic_economy.bank.in_bank", inBankAmount);
        Component carried = Component.translatable("screen.dynamic_economy.bank.carried",
                PriceFormat.money(PriceFormat.spurs(ClientMarketState.carriedCoinValue())));
        if (wide) {
            graphics.drawString(font, font.plainSubstrByWidth(inBank.getString(), panelWidth), left, y + panelY + 4, TEXT_MONEY, false);
            graphics.drawString(font, font.plainSubstrByWidth(carried.getString(), panelWidth), left, y + panelY + 16, TEXT, false);
            List<net.minecraft.util.FormattedCharSequence> help = font.split(
                    Component.translatable("screen.dynamic_economy.bank.help"), panelWidth);
            int helpY = panelY + (accountButton.visible ? 52 : 32);
            for (int i = 0; i < help.size() && helpY + i * 10 < controlsY - 10; i++) {
                graphics.drawString(font, help.get(i), left, y + helpY + i * 10, TEXT_DIM, false);
            }
        } else {
            String both = inBank.getString() + "   " + carried.getString();
            graphics.drawString(font, font.plainSubstrByWidth(both, panelWidth), left, y + panelY + 4, TEXT, false);
        }

        long amount = withdrawValue();
        long[] counts = coinsFor(amount);
        Component problem = amount > 0 ? withdrawProblem(amount, counts) : null;
        Component note;
        int color = TEXT_DIM;
        if (amount <= 0) {
            note = Component.translatable("screen.dynamic_economy.bank.amount_hint");
        } else if (problem != null) {
            note = problem;
            color = TEXT_BAD;
        } else {
            note = Component.translatable("screen.dynamic_economy.bank.as", coinList(counts));
        }
        graphics.drawString(font, font.plainSubstrByWidth(note.getString(), noteWidth), x + noteX, y + noteY, color, false);
    }

    /** "2 × Crown, 1 × Bit". */
    private static String coinList(long[] counts) {
        List<Denomination> coins = ClientMarketState.coins();
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < coins.size(); i++) {
            if (counts[i] > 0) {
                parts.add(counts[i] + " × " + coins.get(i).item().getDescription().getString());
            }
        }
        return String.join(", ", parts);
    }

    /** "just now", "5 min ago", "3 h ago", "2 d ago". */
    private static Component ago(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        if (minutes < 1) {
            return Component.translatable("screen.dynamic_economy.ago.now");
        }
        if (minutes < 60) {
            return Component.translatable("screen.dynamic_economy.ago.minutes", minutes);
        }
        long hours = minutes / 60;
        return hours < 48 ? Component.translatable("screen.dynamic_economy.ago.hours", hours)
                : Component.translatable("screen.dynamic_economy.ago.days", hours / 24);
    }

    private void renderCatalogList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        int headerY = y + HEADER_Y;
        graphics.drawString(font, Component.translatable("screen.dynamic_economy.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.col.stock"), x + colStock, headerY, TEXT_DIM);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.col.buy"), x + colBuy, headerY, TEXT_DIM);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.col.sell"), x + colSell, headerY, TEXT_DIM);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.col.have"), x + colHave, headerY, TEXT_DIM);

        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < rows && scroll + i < visible.size(); i++) {
            MarketPayloads.Row row = visible.get(scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            int background = row.item().equals(selected) ? ROW_SELECTED : scroll + i == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + LIST_X + listWidth, rowY + ROW_HEIGHT, background);
            }
            renderRow(graphics, row, x, rowY);
        }
        if (visible.isEmpty()) {
            Component empty = Component.translatable(ClientMarketState.rows().isEmpty()
                    ? "screen.dynamic_economy.empty" : "screen.dynamic_economy.no_matches");
            graphics.drawCenteredString(font, empty, x + LIST_X + listWidth / 2, y + LIST_Y + 20, TEXT_DIM);
        }
    }

    /** The note from All or Max, a warning when the quantity is over the order limit, or else the limit itself. */
    private void renderQuantityNote(GuiGraphics graphics, int x, int y) {
        int limit = ClientMarketState.maxQuantity();
        Component text;
        int color = TEXT_DIM;
        if (quantityValue() > limit) {
            text = Component.translatable("screen.dynamic_economy.note.over_limit", PriceFormat.spurs(limit));
            color = TEXT_BAD;
        } else if (!quantityNote.getString().isEmpty()) {
            text = quantityNote;
        } else {
            text = Component.translatable("screen.dynamic_economy.note.limit", PriceFormat.spurs(limit));
        }
        graphics.drawString(font, font.plainSubstrByWidth(text.getString(), noteWidth), x, y, color, false);
    }

    private void renderRow(GuiGraphics graphics, MarketPayloads.Row row, int x, int rowY) {
        graphics.renderItem(stack(row), x + LIST_X + 2, rowY + 1);
        int textY = rowY + 5;
        String name = font.plainSubstrByWidth(displayName(row), colStock - 34 - LIST_X - 22);
        graphics.drawString(font, name, x + LIST_X + 22, textY, TEXT, false);
        if (row.unlimited()) {
            drawRight(graphics, Component.translatable("screen.dynamic_economy.unlimited_short"), x + colStock, textY, TEXT_GOOD);
        } else {
            long stock = row.stock().getAsLong();
            drawRight(graphics, Component.literal(PriceFormat.count(stock)), x + colStock, textY, stock > 0 ? TEXT : TEXT_BAD);
        }
        drawRight(graphics, Component.literal(row.canBuy() ? PriceFormat.unitPrice(row.exactBuyPrice(), RoundingMode.CEILING) : "-"),
                x + colBuy, textY, row.canBuy() ? TEXT_MONEY : TEXT_DIM);
        drawRight(graphics, Component.literal(row.canSell() ? PriceFormat.unitPrice(row.exactSellPrice(), RoundingMode.FLOOR) : "-"),
                x + colSell, textY, row.canSell() ? TEXT_GOOD : TEXT_DIM);
        int held = ClientMarketState.held(row.item());
        drawRight(graphics, Component.literal(PriceFormat.count(held)), x + colHave, textY, held > 0 ? TEXT : TEXT_DIM);
        renderTrend(graphics, row, x + colTrend, textY);
    }

    /** ▲ when the item trades above its base price, ▼ below; nothing within half a percent of it. */
    private void renderTrend(GuiGraphics graphics, MarketPayloads.Row row, int x, int y) {
        if (row.factor() > 1 + TREND_THRESHOLD) {
            graphics.drawString(font, "▲", x, y, TEXT_BAD, false);
        } else if (row.factor() < 1 - TREND_THRESHOLD) {
            graphics.drawString(font, "▼", x, y, TEXT_GOOD, false);
        }
    }

    /** The wide panel's summary of the selection: unit prices, stock, how many you have, and the price against base. */
    private void renderInfo(GuiGraphics graphics, MarketPayloads.Row row, int x, int y) {
        int right = x + panelWidth;
        infoLine(graphics, "col.buy", row.canBuy() ? PriceFormat.unitPrice(row.exactBuyPrice(), RoundingMode.CEILING) : "-",
                row.canBuy() ? TEXT_MONEY : TEXT_DIM, x, right, y);
        infoLine(graphics, "col.sell", row.canSell() ? PriceFormat.unitPrice(row.exactSellPrice(), RoundingMode.FLOOR) : "-",
                row.canSell() ? TEXT_GOOD : TEXT_DIM, x, right, y + INFO_LINE_HEIGHT);
        if (row.unlimited()) {
            infoLine(graphics, "col.stock", Component.translatable("screen.dynamic_economy.unlimited_short").getString(),
                    TEXT_GOOD, x, right, y + 2 * INFO_LINE_HEIGHT);
        } else {
            long stock = row.stock().getAsLong();
            infoLine(graphics, "col.stock", PriceFormat.spurs(stock), stock > 0 ? TEXT : TEXT_BAD, x, right, y + 2 * INFO_LINE_HEIGHT);
        }
        int held = ClientMarketState.held(row.item());
        infoLine(graphics, "col.have", PriceFormat.spurs(held), held > 0 ? TEXT : TEXT_DIM, x, right, y + 3 * INFO_LINE_HEIGHT);
        double factor = row.factor();
        int factorColor = factor > 1 + TREND_THRESHOLD ? TEXT_BAD : factor < 1 - TREND_THRESHOLD ? TEXT_GOOD : TEXT;
        infoLine(graphics, "col.vs_base", percent(factor), factorColor, x, right, y + 4 * INFO_LINE_HEIGHT);
    }

    private void infoLine(GuiGraphics graphics, String labelKey, String value, int color, int left, int right, int y) {
        graphics.drawString(font, Component.translatable("screen.dynamic_economy." + labelKey), left, y, TEXT_DIM, false);
        drawRight(graphics, Component.literal(value), right, y, color);
    }

    /**
     * The selected item's recent price factor as a line: points hold until the next one, the last until now. The
     * dim line marks the base price (factor 1.0).
     */
    private void renderHistory(GuiGraphics graphics, MarketPayloads.Row row, int left, int top) {
        graphics.fill(left, top, left + chartWidth, top + chartHeight, 0x30000000);
        HistoryLine line = historyLine(row);
        int base = chartY(1.0, line, top, chartHeight);
        for (int x = 0; x < chartWidth; x += 2) {
            graphics.fill(left + x, base, left + x + 1, base + 1, 0x50FFFFFF);
        }
        int previous = -1;
        for (int x = 0; x < chartWidth; x++) {
            long tick = line.start + (line.end - line.start) * x / (chartWidth - 1);
            int y = chartY(line.valueAt(tick), line, top, chartHeight);
            int from = previous < 0 ? y : Math.min(previous, y);
            int to = previous < 0 ? y : Math.max(previous, y);
            // Text colours have no alpha byte; fill() needs one or the line is transparent.
            graphics.fill(left + x, from, left + x + 1, to + 1, 0xFF000000 | TEXT_MONEY);
            previous = y;
        }
    }

    /** A history plus the current factor, with the window and vertical range to draw it in. */
    private record HistoryLine(long[] ticks, float[] values, long start, long end, double low, double high) {
        double valueAt(long tick) {
            double value = values[0];
            for (int i = 0; i < ticks.length && ticks[i] <= tick; i++) {
                value = values[i];
            }
            return value;
        }
    }

    private static HistoryLine historyLine(MarketPayloads.Row row) {
        MarketPayloads.History history = ClientMarketState.history(row.item());
        long now = history != null ? history.now() : 0;
        int count = history == null ? 0 : history.ticks().length;
        long[] ticks = new long[count + 1];
        float[] values = new float[count + 1];
        if (count > 0) {
            System.arraycopy(history.ticks(), 0, ticks, 0, count);
            System.arraycopy(history.factors(), 0, values, 0, count);
        }
        // The row's factor is the freshest value; it holds from the last point until now.
        ticks[count] = Math.max(now, count > 0 ? ticks[count - 1] : now);
        values[count] = (float) row.factor();
        long start = Math.min(ticks[0], now - MIN_CHART_TICKS);
        double low = 1.0;
        double high = 1.0;
        for (float value : values) {
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        double pad = Math.max(0.01, (high - low) * 0.1);
        return new HistoryLine(ticks, values, start, Math.max(now, start + 1), low - pad, high + pad);
    }

    private static int chartY(double value, HistoryLine line, int top, int height) {
        double fraction = (value - line.low()) / (line.high() - line.low());
        return top + height - 1 - (int) Math.round(Mth.clamp(fraction, 0, 1) * (height - 1));
    }

    private boolean isOverChart(double mouseX, double mouseY) {
        double x = mouseX - leftPos - chartX;
        double y = mouseY - topPos - chartY;
        return tab != Tab.BANK && tab != Tab.CONTRACTS && tab != Tab.EXCHANGE && selectedRow() != null && x >= 0 && x < chartWidth && y >= 0 && y < chartHeight;
    }

    private List<Component> chartTooltip(MarketPayloads.Row row) {
        HistoryLine line = historyLine(row);
        long minutes = Math.max(1, (line.end() - line.start()) / 1200);
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (float value : line.values()) {
            low = Math.min(low, value);
            high = Math.max(high, value);
        }
        return List.of(
                Component.translatable("screen.dynamic_economy.history.title", minutes),
                Component.translatable("screen.dynamic_economy.history.range", percent(low), percent(high)).withColor(TEXT_DIM),
                Component.translatable("screen.dynamic_economy.history.now", percent(row.factor())).withColor(TEXT_MONEY));
    }

    private static String percent(double factor) {
        return String.format(Locale.ROOT, "%.1f%%", factor * 100);
    }

    private void renderScrollbar(GuiGraphics graphics, int x, int y) {
        int left = x + LIST_X + listWidth + 2;
        int top = y + LIST_Y;
        int height = rows * ROW_HEIGHT;
        graphics.fill(left, top, left + SCROLLBAR_WIDTH, top + height, 0x40000000);
        if (maxScroll() > 0) {
            int thumb = Math.max(12, height * rows / listSize());
            int thumbY = top + (height - thumb) * scroll / maxScroll();
            graphics.fill(left, thumbY, left + SCROLLBAR_WIDTH, thumbY + thumb, 0xFFA0A0A8);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        long balance = ClientMarketState.balance();
        drawRight(graphics, balanceText(balance, !balanceInTabRow), imageWidth - MARGIN,
                balanceInTabRow ? TAB_Y + 3 : TOOLBAR_Y + 4, TEXT_MONEY);
        Component status = ClientMarketState.status();
        if (!status.getString().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status.getString(), imageWidth - 2 * MARGIN), MARGIN, statusY,
                    ClientMarketState.statusIsError() ? TEXT_BAD : TEXT_GOOD, false);
        }
    }

    /** "Bank: 1,234 spurs"; abbreviated ("Bank: 123k spurs") where it has to share the toolbar. */
    private static Component balanceText(long balance, boolean abbreviated) {
        String amount = (abbreviated ? PriceFormat.compact(balance) : PriceFormat.spurs(balance))
                + (ClientMarketState.capped(balance) ? "+" : "");
        return Component.translatable("screen.dynamic_economy.balance", PriceFormat.money(amount));
    }

    /**
     * The widest balance the layout makes room for: the bank's cap, or for a bank without one (ours) a balance of
     * about 100 billion, far beyond any likely fortune.
     */
    private static long widestBalance() {
        return Math.min(ClientMarketState.balanceCap(), 99_999_999_999L);
    }

    /** The toolbar room kept for the abbreviated balance: its widest form. */
    private int balanceReserve() {
        return font.width(balanceText(widestBalance(), true));
    }

    private void drawRight(GuiGraphics graphics, Component text, int right, int y, int color) {
        graphics.drawString(font, text, right - font.width(text), y, color, false);
    }

    private static ItemStack stack(MarketPayloads.Row row) {
        return new ItemStack(BuiltInRegistries.ITEM.get(row.item()));
    }

    private static String displayName(MarketPayloads.Row row) {
        return displayName(row.item());
    }

    private static String displayName(ResourceLocation item) {
        return BuiltInRegistries.ITEM.get(item).getDescription().getString();
    }

    /** The item's own tooltip plus the exact prices, which the list may abbreviate. */
    private List<Component> tooltip(MarketPayloads.Row row) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(minecraft, stack(row)));
        if (Math.abs(row.factor() - 1) > TREND_THRESHOLD) {
            lines.add(Component.translatable("screen.dynamic_economy.tooltip.factor",
                    String.format(Locale.ROOT, "%.1f", row.factor() * 100)).withColor(row.factor() > 1 ? TEXT_BAD : TEXT_GOOD));
        }
        lines.add(row.canSell()
                ? Component.translatable("screen.dynamic_economy.tooltip.sell",
                        PriceFormat.money(PriceFormat.unitPrice(row.exactSellPrice(), RoundingMode.FLOOR))).withColor(TEXT_GOOD)
                : Component.translatable("screen.dynamic_economy.tooltip.sell_disabled").withColor(TEXT_DIM));
        lines.add(row.canBuy()
                ? Component.translatable("screen.dynamic_economy.tooltip.buy",
                        PriceFormat.money(PriceFormat.unitPrice(row.exactBuyPrice(), RoundingMode.CEILING))).withColor(TEXT_MONEY)
                : Component.translatable("screen.dynamic_economy.tooltip.buy_disabled").withColor(TEXT_DIM));
        lines.add((row.unlimited()
                ? Component.translatable("screen.dynamic_economy.tooltip.unlimited")
                : Component.translatable("screen.dynamic_economy.tooltip.stock", PriceFormat.spurs(row.stock().getAsLong())))
                .withColor(TEXT_DIM));
        return lines;
    }
}
