package dynamiceconomy.client;

import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
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
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The market screen: a row of tabs over a list and a side panel. Everything here is a hint; the server re-checks
 * every trade.
 *
 * <p>This class is the frame: the tab strip, the search box, the quantity controls, the layout, scrolling and the
 * drawing helpers every tab uses. Each tab's list, panel and own controls are in its {@link ScreenTab}
 * ({@link MarketTab}, {@link BankTab}, {@link ContractsTab}, {@link ExchangeTab}, {@link LeaderboardTab},
 * {@link HistoryTab}), and the frame hands every event to the open one. Opened from a Bank Card, the screen is
 * bank-only: the other tabs are greyed out.
 *
 * <p>The screen sizes itself to the window. On a wide window the panel sits beside the list; on a narrow one it sits
 * below. The list shows as many rows as fit.
 */
public final class MarketScreen extends AbstractContainerScreen<MarketMenu> {
    /** The largest the screen grows to, so rows stay readable on a big window at GUI scale 1. */
    static final int MAX_WIDTH = 600;
    static final int MAX_HEIGHT = 400;
    /** Below this width the selection panel goes under the list instead of beside it. */
    static final int WIDE_MIN_WIDTH = 440;
    static final int SIDE_WIDTH = 150;
    static final int MARGIN = 8;
    static final int TAB_Y = 4;
    static final int TAB_HEIGHT = 14;
    static final int TOOLBAR_Y = 22;
    static final int HEADER_Y = 42;
    static final int LIST_X = MARGIN;
    static final int LIST_Y = 52;
    static final int ROW_HEIGHT = 18;
    static final int SCROLLBAR_WIDTH = 4;
    /** The narrow layout's panel: the selected item, the quantity controls, the trade buttons. */
    static final int BOTTOM_PANEL_HEIGHT = 62;
    /** Room left under the list and panel for the status line. */
    static final int STATUS_HEIGHT = 16;
    /** The wide panel's label/value lines under the chart. */
    static final int INFO_LINES = 5;
    static final int INFO_LINE_HEIGHT = 10;
    static final int MIN_SIDE_CHART_HEIGHT = 24;
    static final int MAX_SIDE_CHART_HEIGHT = 80;
    static final int NARROW_CHART_WIDTH = 118;
    static final int NARROW_CHART_HEIGHT = 16;
    /** Width of the quantity controls: -, the box, +, Max and All. */
    static final int CONTROLS_WIDTH = 132;

    static final int PANEL = 0xF0202024;
    static final int PANEL_BORDER = 0xFF5A5A64;
    static final int ROW_ALT = 0x18FFFFFF;
    static final int ROW_HOVER = 0x30FFFFFF;
    static final int ROW_SELECTED = 0x50FFFFFF;
    static final int TAB_IDLE = 0x14FFFFFF;
    static final int TAB_ACTIVE = 0x40FFFFFF;
    static final int TEXT = 0xFFFFFF;
    static final int TEXT_DIM = 0xA0A0A8;
    static final int TEXT_OFF = 0x606068;
    static final int TEXT_GOOD = 0x6EE07A;
    static final int TEXT_BAD = 0xFF6B6B;
    static final int TEXT_MONEY = 0xF2C94C;

    /** Lower-case mod display names by namespace, for {@code @mod} searches. The mod list never changes in a session. */
    private static final Map<String, String> MOD_NAMES = new HashMap<>();

    /** The tabs along the top. Those that can't be opened are shown greyed out. */
    enum Tab {
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

    EditBox search;
    EditBox quantity;
    Tab tab = Tab.MARKET;
    Button maxButton;
    /** The quantity and trade controls, hidden on the Bank tab. */
    final List<AbstractWidget> marketControls = new ArrayList<>();

    private final MarketTab market = new MarketTab(this);
    private final BankTab bank = new BankTab(this);
    private final ContractsTab contracts = new ContractsTab(this);
    private final ExchangeTab exchange = new ExchangeTab(this);
    private final LeaderboardTab leaderboard = new LeaderboardTab(this);
    private final HistoryTab history = new HistoryTab(this);

    // The layout, worked out from the window size in init(). Positions are relative to leftPos/topPos.
    boolean wide;
    private final int[] tabX = new int[Tab.values().length];
    private final int[] tabWidth = new int[Tab.values().length];
    /** Whether the balance fits beside the tabs; if not it goes at the end of the toolbar, abbreviated. */
    private boolean balanceInTabRow;
    int listWidth;
    int rows;
    // Right edges of the numeric columns, and the left edge of the trend arrow.
    int colStock;
    int colBuy;
    int colSell;
    int colHave;
    int colTrend;
    int panelX;
    int panelY;
    int panelWidth;
    int chartX;
    int chartY;
    int chartWidth;
    int chartHeight;
    int infoY;
    int controlsY;
    /** Top of the trade buttons: Buy above Sell when wide, side by side when narrow. */
    int tradeY;
    int noteX;
    int noteY;
    int noteWidth;
    int statusY;
    /** The Exchange tab's price box: above the quantity controls when wide, beside them when narrow. */
    int priceX;
    int priceY;
    int priceWidth;

    private int seenVersion = -1;
    private String seenQuery = "";
    boolean listDirty = true;
    int scroll;

    /** A short explanation under the quantity, set by All and Max; it lasts until the quantity changes. */
    Component quantityNote = Component.empty();
    int quantityNoteFor = -1;

    public MarketScreen(MarketMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        tab = menu.bankOnly() ? Tab.BANK : Tab.MARKET;
    }

    private boolean available(Tab t) {
        return t.available(menu.bankOnly());
    }

    /** The open tab's class. */
    ScreenTab activeTab() {
        return switch (tab) {
            case MARKET -> market;
            case BANK -> bank;
            case CONTRACTS -> contracts;
            case EXCHANGE -> exchange;
            case LEADERBOARD -> leaderboard;
            case HISTORY -> history;
        };
    }

    MarketTab market() {
        return market;
    }

    ExchangeTab exchange() {
        return exchange;
    }

    // Tabs live outside the screen's class hierarchy, so they reach its protected parts through these.

    Font font() {
        return font;
    }

    Minecraft minecraft() {
        return minecraft;
    }

    int leftPos() {
        return leftPos;
    }

    int topPos() {
        return topPos;
    }

    boolean bankOnly() {
        return menu.bankOnly();
    }

    <T extends GuiEventListener & Renderable & NarratableEntry> T add(T widget) {
        return addRenderableWidget(widget);
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
        market.initToolbar(x + sortX, y + TOOLBAR_Y);
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
        maxButton = addRenderableWidget(Button.builder(Component.translatable("screen.dynamic_economy.max"), b -> market.fillMaxBuy())
                .bounds(x + controlsX + 78, y + controlsY, 28, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.max.tooltip")))
                .build());
        marketControls.add(maxButton);
        marketControls.add(addRenderableWidget(Button.builder(Component.translatable("screen.dynamic_economy.all"),
                        b -> activeTab().fillAll())
                .bounds(x + controlsX + 108, y + controlsY, 24, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.all.tooltip")))
                .build()));

        bank.init(x, y, controlsX);
        market.initTradeButtons(x, y);
        contracts.init();
        exchange.init(x, y, sortX);
        updateListButtons();

        seenVersion = -1;
        market.forgetTotals();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        market.refreshButtons();
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
        market.refreshButtons();
        bank.refreshBankButtons();
        contracts.refreshContractButtons();
        exchange.refreshExchangeButtons();
        market.requestHistoryIfStale();
    }

    private void rebuildList(String query) {
        market.rebuildList(query);
        if (tab == Tab.EXCHANGE) {
            exchange.rebuildExchangeItems(query);
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    /** Switches tabs, asking the server for whatever the new tab shows (the player's trades, the board, the book). */
    void selectTab(Tab next) {
        if (!available(next) || next == tab) {
            return;
        }
        tab = next;
        scroll = 0;
        activeTab().opened();
        listDirty = true;
        updateListButtons();
    }

    /** Shows the controls the open tab uses, and moves focus off any box that's now hidden. */
    void updateListButtons() {
        ScreenTab open = activeTab();
        search.visible = open.showsSearch();
        boolean quantityShown = open.showsQuantity();
        marketControls.forEach(widget -> widget.visible = quantityShown);
        market.updateWidgets(quantityShown);
        contracts.updateWidgets(quantityShown);
        bank.updateWidgets(quantityShown);
        exchange.updateWidgets(quantityShown);
        GuiEventListener focused = getFocused();
        if (focused == search && !search.visible || focused == quantity && !quantityShown
                || bank.hides(focused) || exchange.hides(focused)) {
            setFocused(null);
        }
    }

    /** Every space-separated term must match: {@code @mod}, {@code #tag}, or a plain word (see {@link #matchesTerm}). */
    static boolean matches(ResourceLocation item, String query) {
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

    int quantityValue() {
        try {
            return Math.max(0, Integer.parseInt(quantity.getValue()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int step() {
        return hasShiftDown() ? 16 : 1;
    }

    private void adjustQuantity(int delta) {
        setQuantity(Mth.clamp(quantityValue() + delta, 1, quantityLimit()), Component.empty());
    }

    /** The largest quantity one request may ask for: one exchange order, or one market trade. */
    private int quantityLimit() {
        return activeTab().quantityLimit();
    }

    void setQuantity(int value, Component note) {
        quantity.setValue(String.valueOf(value));
        quantityNote = note;
        quantityNoteFor = value;
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
            if (index >= 0 && activeTab().rowClicked(index)) {
                return true;
            }
            if (activeTab().panelClicked(mouseX, mouseY)) {
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

    /** How many rows the list has in its current mode. */
    private int listSize() {
        return activeTab().listSize();
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
            // Enter leaves a number box: every box but the search.
            if (keyCode == GLFW.GLFW_KEY_ENTER && box != search) {
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

    int rowAt(double mouseX, double mouseY) {
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
        if (hoveredTab != null && !available(hoveredTab)) {
            graphics.renderTooltip(font, hoveredTab.unavailableReason(menu.bankOnly()), mouseX, mouseY);
        } else {
            activeTab().renderTooltip(graphics, index, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        renderTabs(graphics, x, y, mouseX, mouseY);
        ScreenTab open = activeTab();
        open.renderList(graphics, x, y, mouseX, mouseY);
        renderScrollbar(graphics, x, y);
        open.renderPanel(graphics, x, y, mouseX, mouseY);
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

    /** An item with its count and durability bar. */
    void renderStack(GuiGraphics graphics, ItemStack stack, int x, int y) {
        graphics.renderItem(stack, x, y);
        graphics.renderItemDecorations(font, stack, x, y);
    }

    /** The note from All or Max, a warning when the quantity is over the order limit, or else the limit itself. */
    void renderQuantityNote(GuiGraphics graphics, int x, int y) {
        int limit = ClientMarketState.maxQuantity();
        Component text;
        int color = TEXT_DIM;
        if (quantityValue() > limit) {
            text = Component.translatable("screen.dynamic_economy.note.over_limit", PriceFormat.amount(limit));
            color = TEXT_BAD;
        } else if (!quantityNote.getString().isEmpty()) {
            text = quantityNote;
        } else {
            text = Component.translatable("screen.dynamic_economy.note.limit", PriceFormat.amount(limit));
        }
        graphics.drawString(font, font.plainSubstrByWidth(text.getString(), noteWidth), x, y, color, false);
    }

    void infoLine(GuiGraphics graphics, String labelKey, String value, int color, int left, int right, int y) {
        graphics.drawString(font, Component.translatable("screen.dynamic_economy." + labelKey), left, y, TEXT_DIM, false);
        drawRight(graphics, Component.literal(value), right, y, color);
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
        String amount = (abbreviated ? PriceFormat.compact(balance) : PriceFormat.amount(balance))
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

    void drawRight(GuiGraphics graphics, Component text, int right, int y, int color) {
        graphics.drawString(font, text, right - font.width(text), y, color, false);
    }

    static ItemStack stack(MarketPayloads.Row row) {
        return new ItemStack(BuiltInRegistries.ITEM.get(row.item()));
    }

    static String displayName(MarketPayloads.Row row) {
        return displayName(row.item());
    }

    static String displayName(ResourceLocation item) {
        return BuiltInRegistries.ITEM.get(item).getDescription().getString();
    }
}
