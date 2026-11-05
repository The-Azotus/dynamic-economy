package dynamiceconomy.client;

import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.service.MarketListing;
import dynamiceconomy.service.OrderMath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static dynamiceconomy.client.MarketScreen.*;

/**
 * The Market tab: a searchable, sortable list of the catalog with stock and prices, and the selected item's chart,
 * prices and Buy and Sell buttons. History and Leaderboard keep this panel too, so the selection lives here.
 */
final class MarketTab extends ScreenTab {
    /** Shortest time span the line covers, so a fresh history isn't squeezed into one point (4 minutes). */
    private static final long MIN_CHART_TICKS = 4800;
    /** How far the price factor must be from 1.0 before the row shows a trend arrow. */
    private static final double TREND_THRESHOLD = 0.005;

    // Kept while the game runs, so the list looks the same the next time the market opens.
    private static MarketListing.Sort sort = MarketListing.Sort.NAME;
    private static MarketListing.Filter filter = MarketListing.Filter.ALL;

    private Button sortButton;
    private Button filterButton;
    private Button buyButton;
    private Button sellButton;

    private final List<MarketPayloads.Row> visible = new ArrayList<>();
    /** Rows that match the search but are hidden only for having no stock, and whether there is a search. */
    private int hiddenForStock;
    private boolean searching;
    @Nullable
    private ResourceLocation selected;
    /** The item and factor the last history request was for, so a price change triggers a fresh request. */
    @Nullable
    private ResourceLocation historyFor;
    private double historyFactor = Double.NaN;
    private int ticksSinceHistoryRequest;

    // Order totals for the current row and quantity. Each unit moves the price, so they cost more than a multiply.
    @Nullable
    private MarketPayloads.Row totalsRow;
    private int totalsQty = -1;
    private long buyTotal;
    private long sellTotal;

    MarketTab(MarketScreen screen) {
        super(screen);
    }

    /** Sort and Filter, at {@code x, y} in the toolbar beside the search box. */
    void initToolbar(int x, int y) {
        sortButton = screen.add(Button.builder(Component.empty(), b -> {
            sort = sort.next();
            screen.listDirty = true;
            screen.updateListButtons();
        }).bounds(x, y, 52, 16).build());
        filterButton = screen.add(Button.builder(Component.empty(), b -> {
            filter = filter.next();
            screen.listDirty = true;
            screen.updateListButtons();
        }).bounds(x + 54, y, 48, 16).build());
    }

    /** Buy and Sell: Buy above Sell when wide, side by side when narrow. They count as quantity controls. */
    void initTradeButtons(int x, int y) {
        if (screen.wide) {
            buyButton = screen.add(Button.builder(Component.empty(), b -> trade(true))
                    .bounds(x + screen.panelX, y + screen.tradeY, screen.panelWidth, 18).build());
            sellButton = screen.add(Button.builder(Component.empty(), b -> trade(false))
                    .bounds(x + screen.panelX, y + screen.tradeY + 20, screen.panelWidth, 18).build());
        } else {
            int half = (screen.panelWidth - 2) / 2;
            buyButton = screen.add(Button.builder(Component.empty(), b -> trade(true))
                    .bounds(x + MARGIN, y + screen.tradeY, half, 18).build());
            sellButton = screen.add(Button.builder(Component.empty(), b -> trade(false))
                    .bounds(x + MARGIN + screen.panelWidth - half, y + screen.tradeY, half, 18).build());
        }
        screen.marketControls.add(buyButton);
        screen.marketControls.add(sellButton);
    }

    Button buyButton() {
        return buyButton;
    }

    Button sellButton() {
        return sellButton;
    }

    /** Totals are rebuilt from scratch after a resize. */
    void forgetTotals() {
        totalsRow = null;
    }

    @Override
    void updateWidgets(boolean quantityShown) {
        sortButton.visible = isOpen();
        filterButton.visible = isOpen();
        if (buyButton != null) {
            boolean trades = quantityShown && screen.activeTab().showsMarketSelection();
            screen.maxButton.visible = trades;
            buyButton.visible = trades;
            sellButton.visible = trades;
        }
        String sortKey = sort.name().toLowerCase(Locale.ROOT);
        sortButton.setMessage(Component.translatable("screen.dynamic_economy.sort." + sortKey));
        sortButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.sort." + sortKey + ".tooltip")));
        String filterKey = filter.name().toLowerCase(Locale.ROOT);
        filterButton.setMessage(Component.translatable("screen.dynamic_economy.filter." + filterKey));
        filterButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.filter." + filterKey + ".tooltip")));
    }

    @Override
    boolean showsSearch() {
        return true;
    }

    /** The catalog rows that match the search and filter, in the chosen order. */
    void rebuildList(String query) {
        visible.clear();
        hiddenForStock = 0;
        searching = !query.isBlank();
        for (MarketPayloads.Row row : ClientMarketState.rows().values()) {
            if (!matches(row.item(), query)) {
                continue;
            }
            if (MarketListing.shows(filter, row, ClientMarketState.held(row.item()))) {
                visible.add(row);
            } else if (MarketListing.hiddenForStock(filter, row)) {
                hiddenForStock++;
            }
        }
        visible.sort(MarketListing.order(sort, MarketScreen::displayName));
    }

    /** Asks for the selected item's history when the selection or its price changed, at most twice a second. */
    void requestHistoryIfStale() {
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

    @Nullable
    MarketPayloads.Row selectedRow() {
        return selected == null ? null : ClientMarketState.rows().get(selected);
    }

    /** Selects {@code item} and opens the Market tab on it. */
    void select(ResourceLocation item) {
        selected = item;
        screen.selectTab(Tab.MARKET);
        screen.setFocused(null);
        refreshButtons();
    }

    @Override
    boolean rowClicked(int index) {
        select(visible.get(index).item());
        return true;
    }

    @Override
    int listSize() {
        return visible.size();
    }

    /** "Max": as many as the balance, stock, inventory room and order limit allow, priced the way the server will. */
    void fillMaxBuy() {
        MarketPayloads.Row row = selectedRow();
        if (row == null) {
            return;
        }
        if (!row.canBuy()) {
            screen.quantityNote = Component.translatable("screen.dynamic_economy.note.not_sold");
            screen.quantityNoteFor = screen.quantityValue();
            return;
        }
        int limit = ClientMarketState.maxQuantity();
        long room = ClientMarketState.room(row.item(), stack(row).getMaxStackSize());
        long balance = ClientMarketState.balance();
        int best = OrderMath.maxBuy(row.inputs(), row.factor(), balance, row.stock(), room, limit);
        if (best == 0) {
            String reason = !row.hasStock(1) ? "none_stock" : room < 1 ? "none_room" : "none_afford";
            screen.quantityNote = Component.translatable("screen.dynamic_economy.note." + reason);
            screen.quantityNoteFor = screen.quantityValue();
            return;
        }
        // Say which limit stopped it, checking the hard limits before money.
        String reason = best == limit ? "max_limit"
                : row.stock().isPresent() && best == row.stock().getAsLong() ? "max_stock"
                : best == room ? "max_room"
                : "max_afford";
        screen.setQuantity(best, Component.translatable("screen.dynamic_economy.note." + reason, PriceFormat.amount(limit)));
    }

    /** "All": every copy held, up to the order limit. */
    void fillSellAll() {
        MarketPayloads.Row row = selectedRow();
        if (row == null) {
            return;
        }
        int held = ClientMarketState.held(row.item());
        if (!row.canSell() || held == 0) {
            screen.quantityNote = Component.translatable(!row.canSell() ? "screen.dynamic_economy.note.not_bought"
                    : "screen.dynamic_economy.note.none_held");
            screen.quantityNoteFor = screen.quantityValue();
            return;
        }
        int limit = ClientMarketState.maxQuantity();
        int amount = OrderMath.sellAll(held, limit);
        screen.setQuantity(amount, held > limit
                ? Component.translatable("screen.dynamic_economy.note.capped", PriceFormat.amount(amount), PriceFormat.amount(held))
                : Component.empty());
    }

    /** Client-side hints only; the server re-checks everything and replies with the real outcome. */
    void refreshButtons() {
        MarketPayloads.Row row = selectedRow();
        int qty = screen.quantityValue();
        if (qty != screen.quantityNoteFor) {
            screen.quantityNote = Component.empty();
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
                ? Component.translatable("screen.dynamic_economy.buy_for", PriceFormat.amount(buyTotal))
                : Component.translatable("screen.dynamic_economy.buy"));
        sellButton.setMessage(row.canSell()
                ? Component.translatable("screen.dynamic_economy.sell_for", PriceFormat.amount(sellTotal))
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
        int qty = screen.quantityValue();
        if (row == null || qty <= 0 || qty > ClientMarketState.maxQuantity()) {
            return;
        }
        MarketPayloads.TradeRequest request = new MarketPayloads.TradeRequest(buy, row.item(), qty,
                buy ? row.buyTotal(qty) : row.sellTotal(qty));
        ClientMarketState.sent(request);
        PacketDistributor.sendToServer(request);
    }

    // ---- Rendering -------------------------------------------------------------------------------------------

    @Override
    void renderList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        renderCatalogList(graphics, x, y, mouseX, mouseY);
    }

    /** The selected item's icon, name, chart and (when wide) its prices; or a hint to pick one. */
    void renderPanel(GuiGraphics graphics, int x, int y) {
        MarketPayloads.Row row = selectedRow();
        if (row != null) {
            int nameWidth = (screen.wide ? screen.panelWidth : screen.chartX - MARGIN - 4) - (screen.exchange().openButtonVisible() ? 40 : 20);
            graphics.renderItem(stack(row), x + screen.panelX, y + screen.panelY);
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(displayName(row), nameWidth), x + screen.panelX + 20, y + screen.panelY + 4, TEXT, false);
            renderHistory(graphics, row, x + screen.chartX, y + screen.chartY);
            if (screen.wide) {
                renderInfo(graphics, row, x + screen.panelX, y + screen.infoY);
            }
        } else {
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(Component.translatable("screen.dynamic_economy.select_hint").getString(), screen.panelWidth),
                    x + screen.panelX, y + screen.panelY + 4, TEXT_DIM, false);
        }
        screen.renderQuantityNote(graphics, x + screen.noteX, y + screen.noteY);
    }

    @Override
    void renderTooltip(GuiGraphics graphics, int index, int mouseX, int mouseY) {
        if (!renderChartTooltip(graphics, mouseX, mouseY) && index >= 0) {
            graphics.renderTooltip(screen.font(), tooltip(visible.get(index)), Optional.empty(), mouseX, mouseY);
        }
    }

    /** The chart's tooltip if the mouse is over it; returns whether it drew one. */
    boolean renderChartTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        MarketPayloads.Row chartRow = selectedRow();
        if (chartRow != null && isOverChart(mouseX, mouseY)) {
            graphics.renderTooltip(screen.font(), chartTooltip(chartRow), Optional.empty(), mouseX, mouseY);
            return true;
        }
        return false;
    }

    private void renderCatalogList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        int headerY = y + HEADER_Y;
        graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.col.item"), x + LIST_X + 22, headerY, TEXT_DIM, false);
        screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.col.stock"), x + screen.colStock, headerY, TEXT_DIM);
        screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.col.buy"), x + screen.colBuy, headerY, TEXT_DIM);
        screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.col.sell"), x + screen.colSell, headerY, TEXT_DIM);
        screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.col.have"), x + screen.colHave, headerY, TEXT_DIM);

        int hovered = screen.rowAt(mouseX, mouseY);
        for (int i = 0; i < screen.rows && screen.scroll + i < visible.size(); i++) {
            MarketPayloads.Row row = visible.get(screen.scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            int background = row.item().equals(selected) ? ROW_SELECTED : screen.scroll + i == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + LIST_X + screen.listWidth, rowY + ROW_HEIGHT, background);
            }
            renderRow(graphics, row, x, rowY);
        }
        if (visible.isEmpty()) {
            String key = ClientMarketState.rows().isEmpty() ? "empty"
                    : hiddenForStock == 0 ? "no_matches"
                    : searching ? "no_matches_in_stock" : "none_in_stock";
            // The stock messages are long enough to need wrapping on a narrow list.
            List<net.minecraft.util.FormattedCharSequence> lines = screen.font().split(
                    Component.translatable("screen.dynamic_economy." + key), screen.listWidth - 8);
            for (int i = 0; i < lines.size(); i++) {
                graphics.drawCenteredString(screen.font(), lines.get(i), x + LIST_X + screen.listWidth / 2, y + LIST_Y + 20 + i * 10, TEXT_DIM);
            }
        }
    }

    private void renderRow(GuiGraphics graphics, MarketPayloads.Row row, int x, int rowY) {
        graphics.renderItem(stack(row), x + LIST_X + 2, rowY + 1);
        int textY = rowY + 5;
        String name = screen.font().plainSubstrByWidth(displayName(row), screen.colStock - 34 - LIST_X - 22);
        graphics.drawString(screen.font(), name, x + LIST_X + 22, textY, TEXT, false);
        if (row.unlimited()) {
            screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.unlimited_short"), x + screen.colStock, textY, TEXT_GOOD);
        } else {
            long stock = row.stock().getAsLong();
            screen.drawRight(graphics, Component.literal(PriceFormat.count(stock)), x + screen.colStock, textY, stock > 0 ? TEXT : TEXT_BAD);
        }
        screen.drawRight(graphics, Component.literal(row.canBuy() ? PriceFormat.unitPrice(row.exactBuyPrice(), RoundingMode.CEILING) : "-"),
                x + screen.colBuy, textY, row.canBuy() ? TEXT_MONEY : TEXT_DIM);
        screen.drawRight(graphics, Component.literal(row.canSell() ? PriceFormat.unitPrice(row.exactSellPrice(), RoundingMode.FLOOR) : "-"),
                x + screen.colSell, textY, row.canSell() ? TEXT_GOOD : TEXT_DIM);
        int held = ClientMarketState.held(row.item());
        screen.drawRight(graphics, Component.literal(PriceFormat.count(held)), x + screen.colHave, textY, held > 0 ? TEXT : TEXT_DIM);
        renderTrend(graphics, row, x + screen.colTrend, textY);
    }

    /** ▲ when the item trades above its base price, ▼ below; nothing within half a percent of it. */
    private void renderTrend(GuiGraphics graphics, MarketPayloads.Row row, int x, int y) {
        if (row.factor() > 1 + TREND_THRESHOLD) {
            graphics.drawString(screen.font(), "▲", x, y, TEXT_BAD, false);
        } else if (row.factor() < 1 - TREND_THRESHOLD) {
            graphics.drawString(screen.font(), "▼", x, y, TEXT_GOOD, false);
        }
    }

    /** The wide panel's summary of the selection: unit prices, stock, how many you have, and the price against base. */
    private void renderInfo(GuiGraphics graphics, MarketPayloads.Row row, int x, int y) {
        int right = x + screen.panelWidth;
        screen.infoLine(graphics, "col.buy", row.canBuy() ? PriceFormat.unitPrice(row.exactBuyPrice(), RoundingMode.CEILING) : "-",
                row.canBuy() ? TEXT_MONEY : TEXT_DIM, x, right, y);
        screen.infoLine(graphics, "col.sell", row.canSell() ? PriceFormat.unitPrice(row.exactSellPrice(), RoundingMode.FLOOR) : "-",
                row.canSell() ? TEXT_GOOD : TEXT_DIM, x, right, y + INFO_LINE_HEIGHT);
        if (row.unlimited()) {
            screen.infoLine(graphics, "col.stock", Component.translatable("screen.dynamic_economy.unlimited_short").getString(),
                    TEXT_GOOD, x, right, y + 2 * INFO_LINE_HEIGHT);
        } else {
            long stock = row.stock().getAsLong();
            screen.infoLine(graphics, "col.stock", PriceFormat.amount(stock), stock > 0 ? TEXT : TEXT_BAD, x, right, y + 2 * INFO_LINE_HEIGHT);
        }
        int held = ClientMarketState.held(row.item());
        screen.infoLine(graphics, "col.have", PriceFormat.amount(held), held > 0 ? TEXT : TEXT_DIM, x, right, y + 3 * INFO_LINE_HEIGHT);
        double factor = row.factor();
        int factorColor = factor > 1 + TREND_THRESHOLD ? TEXT_BAD : factor < 1 - TREND_THRESHOLD ? TEXT_GOOD : TEXT;
        screen.infoLine(graphics, "col.vs_base", percent(factor), factorColor, x, right, y + 4 * INFO_LINE_HEIGHT);
    }

    /**
     * The selected item's recent price factor as a line: points hold until the next one, the last until now. The
     * dim line marks the base price (factor 1.0).
     */
    private void renderHistory(GuiGraphics graphics, MarketPayloads.Row row, int left, int top) {
        graphics.fill(left, top, left + screen.chartWidth, top + screen.chartHeight, 0x30000000);
        HistoryLine line = historyLine(row);
        int base = chartY(1.0, line, top, screen.chartHeight);
        for (int x = 0; x < screen.chartWidth; x += 2) {
            graphics.fill(left + x, base, left + x + 1, base + 1, 0x50FFFFFF);
        }
        int previous = -1;
        for (int x = 0; x < screen.chartWidth; x++) {
            long tick = line.start + (line.end - line.start) * x / (screen.chartWidth - 1);
            int y = chartY(line.valueAt(tick), line, top, screen.chartHeight);
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
        double x = mouseX - screen.leftPos() - screen.chartX;
        double y = mouseY - screen.topPos() - screen.chartY;
        return screen.tab != Tab.BANK && screen.tab != Tab.CONTRACTS && screen.tab != Tab.EXCHANGE && selectedRow() != null && x >= 0 && x < screen.chartWidth && y >= 0 && y < screen.chartHeight;
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

    /** The item's own tooltip plus the exact prices, which the list may abbreviate. */
    private List<Component> tooltip(MarketPayloads.Row row) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(screen.minecraft(), stack(row)));
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
                : Component.translatable("screen.dynamic_economy.tooltip.stock", PriceFormat.amount(row.stock().getAsLong())))
                .withColor(TEXT_DIM));
        return lines;
    }
}
