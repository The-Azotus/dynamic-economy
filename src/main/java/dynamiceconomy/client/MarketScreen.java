package dynamiceconomy.client;

import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.service.MarketListing;
import dynamiceconomy.service.OrderMath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The market catalog: a searchable, sortable list of items with stock and prices, and buy/sell controls for the
 * selection. Everything here is a hint; the server re-checks every trade.
 */
public final class MarketScreen extends AbstractContainerScreen<MarketMenu> {
    private static final int LIST_X = 8;
    private static final int LIST_Y = 46;
    private static final int LIST_WIDTH = 252;
    private static final int ROW_HEIGHT = 18;
    private static final int VISIBLE_ROWS = 7;
    private static final int SCROLLBAR_X = LIST_X + LIST_WIDTH + 2;
    // Rows below the list: the selected item, the quantity controls, the trade buttons, the status line.
    private static final int SELECTED_Y = 174;
    private static final int CONTROLS_Y = 194;
    private static final int TRADE_Y = 214;
    private static final int STATUS_Y = 236;
    /** Where the quantity note starts, right of the All button. */
    private static final int NOTE_X = 144;
    /** Right edge of the money column in the trade list. */
    private static final int TRADE_AMOUNT_RIGHT = 196;

    // Right edges of the numeric columns, relative to the panel.
    private static final int COL_STOCK = 146;
    private static final int COL_SELL = 186;
    private static final int COL_BUY = 226;
    private static final int COL_HAVE = 258;

    private static final int PANEL = 0xF0202024;
    private static final int PANEL_BORDER = 0xFF5A5A64;
    private static final int ROW_ALT = 0x18FFFFFF;
    private static final int ROW_HOVER = 0x30FFFFFF;
    private static final int ROW_SELECTED = 0x50FFFFFF;
    private static final int TEXT = 0xFFFFFF;
    private static final int TEXT_DIM = 0xA0A0A8;
    private static final int TEXT_GOOD = 0x6EE07A;
    private static final int TEXT_BAD = 0xFF6B6B;
    private static final int TEXT_MONEY = 0xF2C94C;
    // The price history line, beside the selected item's name.
    private static final int CHART_X = 150;
    private static final int CHART_WIDTH = 118;
    private static final int CHART_HEIGHT = 16;
    /** Shortest time span the line covers, so a fresh history isn't squeezed into one point (4 minutes). */
    private static final long MIN_CHART_TICKS = 4800;
    /** How far the price factor must be from 1.0 before the row shows a trend arrow. */
    private static final double TREND_THRESHOLD = 0.005;

    // Kept while the game runs, so the list looks the same the next time the market opens.
    private static MarketListing.Sort sort = MarketListing.Sort.NAME;
    private static MarketListing.Filter filter = MarketListing.Filter.ALL;

    private EditBox search;
    private EditBox quantity;
    private Button sortButton;
    private Button filterButton;
    private Button tradesButton;
    /** Whether the list shows the player's recent trades instead of the catalog. */
    private boolean showingTrades;
    private Button buyButton;
    private Button sellButton;

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
        this.imageWidth = 276;
        this.imageHeight = 248;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;

        search = new EditBox(font, x + LIST_X, y + 18, 118, 14, Component.translatable("screen.dynamic_economy.search"));
        search.setHint(Component.translatable("screen.dynamic_economy.search").withColor(TEXT_DIM));
        search.setMaxLength(50);
        addRenderableWidget(search);
        sortButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            sort = sort.next();
            listDirty = true;
            updateListButtons();
        }).bounds(x + 128, y + 17, 52, 16).build());
        filterButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            filter = filter.next();
            listDirty = true;
            updateListButtons();
        }).bounds(x + 182, y + 17, 48, 16).build());
        tradesButton = addRenderableWidget(Button.builder(Component.empty(), b -> showTrades(!showingTrades))
                .bounds(x + 232, y + 17, 36, 16).build());
        updateListButtons();

        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustQuantity(-step()))
                .bounds(x + 8, y + CONTROLS_Y, 16, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.step_hint")))
                .build());
        quantity = new EditBox(font, x + 26, y + CONTROLS_Y + 2, 40, 14, Component.translatable("screen.dynamic_economy.quantity"));
        quantity.setFilter(s -> s.isEmpty() || (s.length() <= 7 && s.chars().allMatch(Character::isDigit)));
        quantity.setValue("1");
        addRenderableWidget(quantity);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustQuantity(step()))
                .bounds(x + 68, y + CONTROLS_Y, 16, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.step_hint")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable("screen.dynamic_economy.max"), b -> fillMaxBuy())
                .bounds(x + 86, y + CONTROLS_Y, 28, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.max.tooltip")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable("screen.dynamic_economy.all"), b -> fillSellAll())
                .bounds(x + 116, y + CONTROLS_Y, 24, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.all.tooltip")))
                .build());

        buyButton = addRenderableWidget(Button.builder(Component.empty(), b -> trade(true))
                .bounds(x + 8, y + TRADE_Y, 129, 18).build());
        sellButton = addRenderableWidget(Button.builder(Component.empty(), b -> trade(false))
                .bounds(x + 139, y + TRADE_Y, 129, 18).build());

        seenVersion = -1;
        totalsRow = null;
        refreshButtons();
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
        refreshButtons();
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

    /** Switches the list between the catalog and the player's recent trades, asking the server for the latter. */
    private void showTrades(boolean show) {
        showingTrades = show;
        scroll = 0;
        if (show) {
            PacketDistributor.sendToServer(new MarketPayloads.TradesRequest());
        }
        updateListButtons();
    }

    private void updateListButtons() {
        tradesButton.setMessage(Component.translatable(showingTrades ? "screen.dynamic_economy.trades.back" : "screen.dynamic_economy.trades"));
        tradesButton.setTooltip(Tooltip.create(Component.translatable(showingTrades
                ? "screen.dynamic_economy.trades.back.tooltip" : "screen.dynamic_economy.trades.tooltip")));
        sortButton.active = !showingTrades;
        filterButton.active = !showingTrades;
        String sortKey = sort.name().toLowerCase(Locale.ROOT);
        sortButton.setMessage(Component.translatable("screen.dynamic_economy.sort." + sortKey));
        sortButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.sort." + sortKey + ".tooltip")));
        String filterKey = filter.name().toLowerCase(Locale.ROOT);
        filterButton.setMessage(Component.translatable("screen.dynamic_economy.filter." + filterKey));
        filterButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.filter." + filterKey + ".tooltip")));
    }

    /**
     * Matches the display name or the ID's path. The namespace only counts when the query contains ':', or every
     * vanilla item would match "mine".
     */
    private static boolean matches(MarketPayloads.Row row, String query) {
        if (query.isEmpty()) {
            return true;
        }
        if (query.contains(":")) {
            return row.item().toString().contains(query);
        }
        return displayName(row).toLowerCase(Locale.ROOT).contains(query) || row.item().getPath().contains(query);
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
                    PriceFormat.decimal(OrderMath.average(buyTotal, qty)))));
            sellButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.average",
                    PriceFormat.decimal(OrderMath.average(sellTotal, qty)))));
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
        int index = rowAt(mouseX, mouseY);
        if (index >= 0 && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            // Clicking a past trade goes back to the market with that item selected, to trade it again.
            selected = showingTrades ? trades().get(index).item() : visible.get(index).item();
            if (showingTrades) {
                showTrades(false);
            }
            setFocused(null);
            refreshButtons();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static List<dynamiceconomy.data.TradeHistory.Trade> trades() {
        MarketPayloads.Trades received = ClientMarketState.trades();
        return received == null ? List.of() : received.trades();
    }

    /** How many rows the list has in its current mode. */
    private int listSize() {
        return showingTrades ? trades().size() : visible.size();
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
            if (keyCode == GLFW.GLFW_KEY_ENTER && box == quantity) {
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
        return x >= LIST_X && x < SCROLLBAR_X + 4 && y >= LIST_Y && y < LIST_Y + VISIBLE_ROWS * ROW_HEIGHT;
    }

    private int rowAt(double mouseX, double mouseY) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        if (x < LIST_X || x >= LIST_X + LIST_WIDTH || y < LIST_Y || y >= LIST_Y + VISIBLE_ROWS * ROW_HEIGHT) {
            return -1;
        }
        int index = scroll + (int) ((y - LIST_Y) / ROW_HEIGHT);
        return index < listSize() ? index : -1;
    }

    private int maxScroll() {
        return Math.max(0, listSize() - VISIBLE_ROWS);
    }

    // ---- Rendering -------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int index = rowAt(mouseX, mouseY);
        MarketPayloads.Row chartRow = selectedRow();
        if (chartRow != null && isOverChart(mouseX, mouseY)) {
            graphics.renderTooltip(font, chartTooltip(chartRow), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0 && !showingTrades) {
            graphics.renderTooltip(font, tooltip(visible.get(index)), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0) {
            graphics.renderTooltip(font, Component.translatable("screen.dynamic_economy.trades.row_hint"), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);

        int headerY = y + 36;
        if (showingTrades) {
            renderTradeList(graphics, x, y, headerY, mouseX, mouseY);
        } else {
            renderCatalogList(graphics, x, y, headerY, mouseX, mouseY);
        }
        renderScrollbar(graphics, x, y);

        MarketPayloads.Row row = selectedRow();
        int selectedY = y + SELECTED_Y;
        if (row != null) {
            graphics.renderItem(stack(row), x + 8, selectedY);
            graphics.drawString(font, font.plainSubstrByWidth(displayName(row), CHART_X - 32), x + 28, selectedY + 4, TEXT, false);
            renderHistory(graphics, row, x + CHART_X, selectedY);
        } else {
            graphics.drawString(font, Component.translatable("screen.dynamic_economy.select_hint"), x + 8, selectedY + 4, TEXT_DIM, false);
        }
        renderQuantityNote(graphics, x + NOTE_X, y + CONTROLS_Y + 5);
    }

    /** The player's recent trades, newest first: what, how many, the total, and how long ago. */
    private void renderTradeList(GuiGraphics graphics, int x, int y, int headerY, int mouseX, int mouseY) {
        MarketPayloads.Trades received = ClientMarketState.trades();
        List<dynamiceconomy.data.TradeHistory.Trade> trades = trades();
        graphics.drawString(font, Component.translatable("screen.dynamic_economy.trades.title", trades.size()),
                x + LIST_X + 22, headerY, TEXT_DIM, false);
        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < trades.size(); i++) {
            dynamiceconomy.data.TradeHistory.Trade trade = trades.get(scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            int background = scroll + i == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + LIST_X + LIST_WIDTH, rowY + ROW_HEIGHT, background);
            }
            graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(trade.item())), x + LIST_X + 2, rowY + 1);
            int textY = rowY + 5;
            String name = BuiltInRegistries.ITEM.get(trade.item()).getDescription().getString();
            Component what = Component.translatable(trade.buy() ? "screen.dynamic_economy.trades.bought"
                    : "screen.dynamic_economy.trades.sold", PriceFormat.spurs(trade.quantity()), name);
            graphics.drawString(font, font.plainSubstrByWidth(what.getString(), TRADE_AMOUNT_RIGHT - 60 - LIST_X - 22),
                    x + LIST_X + 22, textY, TEXT, false);
            drawRight(graphics, Component.literal((trade.buy() ? "-" : "+") + PriceFormat.spurs(trade.total())),
                    x + TRADE_AMOUNT_RIGHT, textY, trade.buy() ? TEXT_MONEY : TEXT_GOOD);
            drawRight(graphics, ago(received.now() - trade.time()), x + COL_HAVE, textY, TEXT_DIM);
        }
        if (trades.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable(received == null
                    ? "screen.dynamic_economy.trades.loading" : "screen.dynamic_economy.trades.none"),
                    x + LIST_X + LIST_WIDTH / 2, y + LIST_Y + 20, TEXT_DIM);
        }
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

    private void renderCatalogList(GuiGraphics graphics, int x, int y, int headerY, int mouseX, int mouseY) {
        graphics.drawString(font, Component.translatable("screen.dynamic_economy.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.col.stock"), x + COL_STOCK, headerY, TEXT_DIM);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.col.sell"), x + COL_SELL, headerY, TEXT_DIM);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.col.buy"), x + COL_BUY, headerY, TEXT_DIM);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.col.have"), x + COL_HAVE, headerY, TEXT_DIM);

        int hovered = rowAt(mouseX, mouseY);
        for (int i = 0; i < VISIBLE_ROWS && scroll + i < visible.size(); i++) {
            MarketPayloads.Row row = visible.get(scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            int background = row.item().equals(selected) ? ROW_SELECTED : scroll + i == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + LIST_X + LIST_WIDTH, rowY + ROW_HEIGHT, background);
            }
            renderRow(graphics, row, x, rowY);
        }
        if (visible.isEmpty()) {
            Component empty = Component.translatable(ClientMarketState.rows().isEmpty()
                    ? "screen.dynamic_economy.empty" : "screen.dynamic_economy.no_matches");
            graphics.drawCenteredString(font, empty, x + LIST_X + LIST_WIDTH / 2, y + LIST_Y + 20, TEXT_DIM);
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
        graphics.drawString(font, font.plainSubstrByWidth(text.getString(), imageWidth - 8 - NOTE_X), x, y, color, false);
    }

    private void renderRow(GuiGraphics graphics, MarketPayloads.Row row, int x, int rowY) {
        graphics.renderItem(stack(row), x + LIST_X + 2, rowY + 1);
        int textY = rowY + 5;
        String name = font.plainSubstrByWidth(displayName(row), COL_STOCK - LIST_X - 22 - 42);
        graphics.drawString(font, name, x + LIST_X + 22, textY, TEXT, false);
        renderTrend(graphics, row, x + COL_STOCK - 36, textY);
        if (row.unlimited()) {
            drawRight(graphics, Component.translatable("screen.dynamic_economy.unlimited_short"), x + COL_STOCK, textY, TEXT_GOOD);
        } else {
            long stock = row.stock().getAsLong();
            drawRight(graphics, Component.literal(PriceFormat.count(stock)), x + COL_STOCK, textY, stock > 0 ? TEXT : TEXT_BAD);
        }
        drawRight(graphics, Component.literal(row.canSell() ? PriceFormat.unitPrice(row.exactSellPrice(), RoundingMode.FLOOR) : "-"),
                x + COL_SELL, textY, row.canSell() ? TEXT_GOOD : TEXT_DIM);
        drawRight(graphics, Component.literal(row.canBuy() ? PriceFormat.unitPrice(row.exactBuyPrice(), RoundingMode.CEILING) : "-"),
                x + COL_BUY, textY, row.canBuy() ? TEXT_MONEY : TEXT_DIM);
        int held = ClientMarketState.held(row.item());
        drawRight(graphics, Component.literal(PriceFormat.count(held)), x + COL_HAVE, textY, held > 0 ? TEXT : TEXT_DIM);
    }

    /** ▲ when the item trades above its base price, ▼ below; nothing within half a percent of it. */
    private void renderTrend(GuiGraphics graphics, MarketPayloads.Row row, int x, int y) {
        if (row.factor() > 1 + TREND_THRESHOLD) {
            graphics.drawString(font, "▲", x, y, TEXT_BAD, false);
        } else if (row.factor() < 1 - TREND_THRESHOLD) {
            graphics.drawString(font, "▼", x, y, TEXT_GOOD, false);
        }
    }

    /**
     * The selected item's recent price factor as a line: points hold until the next one, the last until now. The
     * dim line marks the base price (factor 1.0).
     */
    private void renderHistory(GuiGraphics graphics, MarketPayloads.Row row, int left, int top) {
        graphics.fill(left, top, left + CHART_WIDTH, top + CHART_HEIGHT, 0x30000000);
        HistoryLine line = historyLine(row);
        int base = chartY(1.0, line, top);
        for (int x = 0; x < CHART_WIDTH; x += 2) {
            graphics.fill(left + x, base, left + x + 1, base + 1, 0x50FFFFFF);
        }
        int previous = -1;
        for (int x = 0; x < CHART_WIDTH; x++) {
            long tick = line.start + (line.end - line.start) * x / (CHART_WIDTH - 1);
            int y = chartY(line.valueAt(tick), line, top);
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

    private static int chartY(double value, HistoryLine line, int top) {
        double fraction = (value - line.low()) / (line.high() - line.low());
        return top + CHART_HEIGHT - 1 - (int) Math.round(Mth.clamp(fraction, 0, 1) * (CHART_HEIGHT - 1));
    }

    private boolean isOverChart(double mouseX, double mouseY) {
        double x = mouseX - leftPos - CHART_X;
        double y = mouseY - topPos - SELECTED_Y;
        return selectedRow() != null && x >= 0 && x < CHART_WIDTH && y >= 0 && y < CHART_HEIGHT;
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
        int top = y + LIST_Y;
        int height = VISIBLE_ROWS * ROW_HEIGHT;
        graphics.fill(x + SCROLLBAR_X, top, x + SCROLLBAR_X + 4, top + height, 0x40000000);
        if (maxScroll() > 0) {
            int thumb = Math.max(12, height * VISIBLE_ROWS / listSize());
            int thumbY = top + (height - thumb) * scroll / maxScroll();
            graphics.fill(x + SCROLLBAR_X, thumbY, x + SCROLLBAR_X + 4, thumbY + thumb, 0xFFA0A0A8);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, TEXT, false);
        int balance = ClientMarketState.balance();
        drawRight(graphics, Component.translatable(balance >= ClientMarketState.DISPLAYED_BALANCE_CAP
                        ? "screen.dynamic_economy.balance_capped" : "screen.dynamic_economy.balance", PriceFormat.spurs(balance)),
                imageWidth - 8, 6, TEXT_MONEY);
        Component status = ClientMarketState.status();
        if (!status.getString().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status.getString(), imageWidth - 16), 8, STATUS_Y,
                    ClientMarketState.statusIsError() ? TEXT_BAD : TEXT_GOOD, false);
        }
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
                        PriceFormat.unitPrice(row.exactSellPrice(), RoundingMode.FLOOR)).withColor(TEXT_GOOD)
                : Component.translatable("screen.dynamic_economy.tooltip.sell_disabled").withColor(TEXT_DIM));
        lines.add(row.canBuy()
                ? Component.translatable("screen.dynamic_economy.tooltip.buy",
                        PriceFormat.unitPrice(row.exactBuyPrice(), RoundingMode.CEILING)).withColor(TEXT_MONEY)
                : Component.translatable("screen.dynamic_economy.tooltip.buy_disabled").withColor(TEXT_DIM));
        lines.add((row.unlimited()
                ? Component.translatable("screen.dynamic_economy.tooltip.unlimited")
                : Component.translatable("screen.dynamic_economy.tooltip.stock", PriceFormat.spurs(row.stock().getAsLong())))
                .withColor(TEXT_DIM));
        return lines;
    }
}
