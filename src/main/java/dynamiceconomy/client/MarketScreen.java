package dynamiceconomy.client;

import dynamiceconomy.bank.CoinMath;
import dynamiceconomy.bank.Denomination;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.service.MarketListing;
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

    /** The tabs along the top. Those that can't be opened are shown greyed out; Exchange joins them when it exists. */
    private enum Tab {
        MARKET, BANK, CONTRACTS, LEADERBOARD, HISTORY;

        /** Contracts isn't built yet, the server can turn the leaderboard off, and a Bank Card opens only the bank. */
        boolean available(boolean bankOnly) {
            if (bankOnly) {
                return this == BANK;
            }
            return switch (this) {
                case MARKET, BANK, HISTORY -> true;
                case CONTRACTS -> false;
                case LEADERBOARD -> ClientMarketState.leaderboardEnabled();
            };
        }

        /** Why a greyed-out tab can't be opened. */
        Component unavailableReason(boolean bankOnly) {
            return Component.translatable(bankOnly && this != CONTRACTS ? "screen.dynamic_economy.tab.terminal_only"
                    : this == LEADERBOARD ? "screen.dynamic_economy.tab.turned_off"
                    : "screen.dynamic_economy.tab.coming_soon");
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
    /** The quantity and trade controls, hidden on the Bank tab. */
    private final List<AbstractWidget> marketControls = new ArrayList<>();
    private EditBox withdrawAmount;
    private Button withdrawButton;
    private Button depositButton;

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
        marketControls.add(addRenderableWidget(Button.builder(Component.translatable("screen.dynamic_economy.max"), b -> fillMaxBuy())
                .bounds(x + controlsX + 78, y + controlsY, 28, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.max.tooltip")))
                .build()));
        marketControls.add(addRenderableWidget(Button.builder(Component.translatable("screen.dynamic_economy.all"), b -> fillSellAll())
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
            if (matches(row, query) && MarketListing.shows(filter, row, ClientMarketState.held(row.item()))) {
                visible.add(row);
            }
        }
        visible.sort(MarketListing.order(sort, MarketScreen::displayName));
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    /** Switches tabs, asking the server for the player's trades or the leaderboard when those open. */
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
        }
        updateListButtons();
    }

    private boolean showingTrades() {
        return tab == Tab.HISTORY;
    }

    private void updateListButtons() {
        boolean market = tab == Tab.MARKET;
        search.visible = market;
        sortButton.visible = market;
        filterButton.visible = market;
        boolean bank = tab == Tab.BANK;
        marketControls.forEach(widget -> widget.visible = !bank);
        if (withdrawAmount != null) {
            withdrawAmount.visible = bank;
            withdrawButton.visible = bank;
            depositButton.visible = bank;
        }
        if (getFocused() == search && !market || getFocused() == quantity && bank || getFocused() == withdrawAmount && !bank) {
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
    private static boolean matches(MarketPayloads.Row row, String query) {
        for (String term : query.split("\\s+")) {
            if (!term.isEmpty() && !matchesTerm(row, term)) {
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
    private static boolean matchesTerm(MarketPayloads.Row row, String term) {
        if (term.startsWith("@")) {
            String mod = term.substring(1);
            return row.item().getNamespace().contains(mod) || modName(row.item().getNamespace()).contains(mod);
        }
        if (term.startsWith("#")) {
            String tag = term.substring(1);
            boolean full = tag.contains(":");
            return BuiltInRegistries.ITEM.wrapAsHolder(BuiltInRegistries.ITEM.get(row.item())).tags()
                    .anyMatch(key -> (full ? key.location().toString() : key.location().getPath()).contains(tag));
        }
        if (term.contains(":")) {
            return row.item().toString().contains(term);
        }
        return displayName(row).toLowerCase(Locale.ROOT).contains(term) || row.item().getPath().contains(term);
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
        setQuantity(Mth.clamp(quantityValue() + delta, 1, ClientMarketState.maxQuantity()), Component.empty());
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

    private void deposit() {
        PacketDistributor.sendToServer(new MarketPayloads.BankRequest(true, 0));
    }

    private void withdraw() {
        long amount = withdrawValue();
        if (amount > 0) {
            PacketDistributor.sendToServer(new MarketPayloads.BankRequest(false, amount));
        }
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
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
            if (keyCode == GLFW.GLFW_KEY_ENTER && (box == quantity || box == withdrawAmount)) {
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
            case BANK -> renderCoinList(graphics, x, y);
            default -> renderCatalogList(graphics, x, y, mouseX, mouseY);
        }
        renderScrollbar(graphics, x, y);

        if (tab == Tab.BANK) {
            renderBankPanel(graphics, x, y);
            return;
        }
        MarketPayloads.Row row = selectedRow();
        if (row != null) {
            int nameWidth = (wide ? panelWidth : chartX - MARGIN - 4) - 20;
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

    /** The bank's coins, largest first: what each is worth and how many the player carries. */
    private void renderCoinList(GuiGraphics graphics, int x, int y) {
        List<Denomination> coins = ClientMarketState.coins();
        int listRight = LIST_X + listWidth;
        int worthRight = listRight - 60;
        graphics.drawString(font, Component.translatable("screen.dynamic_economy.bank.col.coin"), x + LIST_X + 22, y + HEADER_Y, TEXT_DIM, false);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.bank.col.worth"), x + worthRight, y + HEADER_Y, TEXT_DIM);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.col.have"), x + listRight - 2, y + HEADER_Y, TEXT_DIM);
        for (int i = 0; i < rows && scroll + i < coins.size(); i++) {
            Denomination coin = coins.get(scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            if (i % 2 == 1) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, ROW_ALT);
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
        Component inBank = Component.translatable("screen.dynamic_economy.bank.in_bank",
                PriceFormat.money(PriceFormat.spurs(balance) + (ClientMarketState.capped(balance) ? "+" : "")));
        Component carried = Component.translatable("screen.dynamic_economy.bank.carried",
                PriceFormat.money(PriceFormat.spurs(ClientMarketState.carriedCoinValue())));
        if (wide) {
            graphics.drawString(font, font.plainSubstrByWidth(inBank.getString(), panelWidth), left, y + panelY + 4, TEXT_MONEY, false);
            graphics.drawString(font, font.plainSubstrByWidth(carried.getString(), panelWidth), left, y + panelY + 16, TEXT, false);
            List<net.minecraft.util.FormattedCharSequence> help = font.split(
                    Component.translatable("screen.dynamic_economy.bank.help"), panelWidth);
            for (int i = 0; i < help.size() && panelY + 32 + i * 10 < controlsY - 10; i++) {
                graphics.drawString(font, help.get(i), left, y + panelY + 32 + i * 10, TEXT_DIM, false);
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
        return tab != Tab.BANK && selectedRow() != null && x >= 0 && x < chartWidth && y >= 0 && y < chartHeight;
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
        return BuiltInRegistries.ITEM.get(row.item()).getDescription().getString();
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
