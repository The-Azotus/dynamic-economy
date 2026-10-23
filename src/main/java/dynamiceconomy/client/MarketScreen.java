package dynamiceconomy.client;

import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketPayloads;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The market catalog: a searchable list of items with stock and prices, and buy/sell controls for the selection. */
public final class MarketScreen extends AbstractContainerScreen<MarketMenu> {
    private static final int LIST_X = 8;
    private static final int LIST_Y = 46;
    private static final int LIST_WIDTH = 252;
    private static final int ROW_HEIGHT = 18;
    private static final int VISIBLE_ROWS = 7;
    private static final int SCROLLBAR_X = LIST_X + LIST_WIDTH + 2;

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

    private EditBox search;
    private EditBox quantity;
    private Button buyButton;
    private Button sellButton;

    private final List<MarketPayloads.Row> visible = new ArrayList<>();
    private int seenVersion = -1;
    private String seenQuery = "";
    private int scroll;
    @Nullable
    private ResourceLocation selected;
    /** The item and factor the last history request was for, so a price change triggers a fresh request. */
    @Nullable
    private ResourceLocation historyFor;
    private double historyFactor = Double.NaN;
    private int ticksSinceHistoryRequest;

    public MarketScreen(MarketMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 276;
        this.imageHeight = 228;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;

        search = new EditBox(font, x + LIST_X, y + 18, LIST_WIDTH + 8, 14, Component.translatable("screen.dynamic_economy.search"));
        search.setHint(Component.translatable("screen.dynamic_economy.search").withColor(TEXT_DIM));
        search.setMaxLength(50);
        addRenderableWidget(search);

        addRenderableWidget(Button.builder(Component.literal("-"), b -> adjustQuantity(-step()))
                .bounds(x + 8, y + 192, 16, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.step_hint")))
                .build());
        quantity = new EditBox(font, x + 26, y + 194, 40, 14, Component.translatable("screen.dynamic_economy.quantity"));
        quantity.setFilter(s -> s.isEmpty() || (s.length() <= 5 && s.chars().allMatch(Character::isDigit)));
        quantity.setValue("1");
        addRenderableWidget(quantity);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> adjustQuantity(step()))
                .bounds(x + 68, y + 192, 16, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.step_hint")))
                .build());

        buyButton = addRenderableWidget(Button.builder(Component.empty(), b -> trade(true))
                .bounds(x + 90, y + 192, 88, 18).build());
        sellButton = addRenderableWidget(Button.builder(Component.empty(), b -> trade(false))
                .bounds(x + 180, y + 192, 88, 18).build());

        seenVersion = -1;
        refreshButtons();
    }

    // ---- State -----------------------------------------------------------------------------------------------

    @Override
    protected void containerTick() {
        super.containerTick();
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (seenVersion != ClientMarketState.version() || !query.equals(seenQuery)) {
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
            if (matches(row, query)) {
                visible.add(row);
            }
        }
        visible.sort(Comparator.comparing(MarketScreen::displayName, String.CASE_INSENSITIVE_ORDER));
        scroll = Mth.clamp(scroll, 0, maxScroll());
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
        quantity.setValue(String.valueOf(Mth.clamp(quantityValue() + delta, 1, 99_999)));
    }

    /** Client-side hints only; the server re-checks everything and replies with the real outcome. */
    private void refreshButtons() {
        MarketPayloads.Row row = selectedRow();
        int qty = quantityValue();
        if (row == null || qty <= 0) {
            buyButton.setMessage(Component.translatable("screen.dynamic_economy.buy"));
            sellButton.setMessage(Component.translatable("screen.dynamic_economy.sell"));
            buyButton.active = false;
            sellButton.active = false;
            return;
        }
        // Each unit moves the price, so totals come from the same model the server uses, not unit price × qty.
        long buyTotal = row.buyTotal(qty);
        long sellTotal = row.sellTotal(qty);
        buyButton.setMessage(row.canBuy()
                ? Component.translatable("screen.dynamic_economy.buy_for", formatSpurs(buyTotal))
                : Component.translatable("screen.dynamic_economy.buy"));
        sellButton.setMessage(row.canSell()
                ? Component.translatable("screen.dynamic_economy.sell_for", formatSpurs(sellTotal))
                : Component.translatable("screen.dynamic_economy.sell"));
        buyButton.active = row.canBuy() && row.hasStock(qty) && ClientMarketState.balance() >= buyTotal;
        sellButton.active = row.canSell() && ClientMarketState.held(row.item()) >= qty;
    }

    private void trade(boolean buy) {
        MarketPayloads.Row row = selectedRow();
        int qty = quantityValue();
        if (row == null || qty <= 0) {
            return;
        }
        long quotedTotal = buy ? row.buyTotal(qty) : row.sellTotal(qty);
        PacketDistributor.sendToServer(new MarketPayloads.TradeRequest(buy, row.item(), qty, quotedTotal));
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
            selected = visible.get(index).item();
            setFocused(null);
            refreshButtons();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
        return index < visible.size() ? index : -1;
    }

    private int maxScroll() {
        return Math.max(0, visible.size() - VISIBLE_ROWS);
    }

    // ---- Rendering -------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int index = rowAt(mouseX, mouseY);
        MarketPayloads.Row chartRow = selectedRow();
        if (chartRow != null && isOverChart(mouseX, mouseY)) {
            graphics.renderTooltip(font, chartTooltip(chartRow), Optional.empty(), mouseX, mouseY);
        } else if (index >= 0) {
            graphics.renderTooltip(font, tooltip(visible.get(index)), Optional.empty(), mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, PANEL_BORDER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);

        int headerY = y + 36;
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
        renderScrollbar(graphics, x, y);

        MarketPayloads.Row row = selectedRow();
        int selectedY = y + 172;
        if (row != null) {
            graphics.renderItem(stack(row), x + 8, selectedY);
            graphics.drawString(font, font.plainSubstrByWidth(displayName(row), CHART_X - 32), x + 28, selectedY + 4, TEXT, false);
            renderHistory(graphics, row, x + CHART_X, selectedY);
        } else {
            graphics.drawString(font, Component.translatable("screen.dynamic_economy.select_hint"), x + 8, selectedY + 4, TEXT_DIM, false);
        }
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
            drawRight(graphics, Component.literal(formatCount(stock)), x + COL_STOCK, textY, stock > 0 ? TEXT : TEXT_BAD);
        }
        drawRight(graphics, Component.literal(row.canSell() ? formatCompact(row.sellPrice()) : "-"), x + COL_SELL, textY,
                row.canSell() ? TEXT_GOOD : TEXT_DIM);
        drawRight(graphics, Component.literal(row.canBuy() ? formatCompact(row.buyPrice()) : "-"), x + COL_BUY, textY,
                row.canBuy() ? TEXT_MONEY : TEXT_DIM);
        int held = ClientMarketState.held(row.item());
        drawRight(graphics, Component.literal(formatCount(held)), x + COL_HAVE, textY, held > 0 ? TEXT : TEXT_DIM);
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
        double y = mouseY - topPos - 172;
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
            int thumb = Math.max(12, height * VISIBLE_ROWS / visible.size());
            int thumbY = top + (height - thumb) * scroll / maxScroll();
            graphics.fill(x + SCROLLBAR_X, thumbY, x + SCROLLBAR_X + 4, thumbY + thumb, 0xFFA0A0A8);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, TEXT, false);
        drawRight(graphics, Component.translatable("screen.dynamic_economy.balance", formatSpurs(ClientMarketState.balance())),
                imageWidth - 8, 6, TEXT_MONEY);
        Component status = ClientMarketState.status();
        if (!status.getString().isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status.getString(), imageWidth - 16), 8, 214,
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
                ? Component.translatable("screen.dynamic_economy.tooltip.sell", formatSpurs(row.sellPrice())).withColor(TEXT_GOOD)
                : Component.translatable("screen.dynamic_economy.tooltip.sell_disabled").withColor(TEXT_DIM));
        lines.add(row.canBuy()
                ? Component.translatable("screen.dynamic_economy.tooltip.buy", formatSpurs(row.buyPrice())).withColor(TEXT_MONEY)
                : Component.translatable("screen.dynamic_economy.tooltip.buy_disabled").withColor(TEXT_DIM));
        lines.add((row.unlimited()
                ? Component.translatable("screen.dynamic_economy.tooltip.unlimited")
                : Component.translatable("screen.dynamic_economy.tooltip.stock", formatSpurs(row.stock().getAsLong())))
                .withColor(TEXT_DIM));
        return lines;
    }

    private static String formatSpurs(long spurs) {
        return String.format(Locale.ROOT, "%,d", spurs);
    }

    /** Full digits up to 99,999; abbreviated above that so the price columns keep their width. */
    private static String formatCompact(long spurs) {
        if (spurs < 100_000) {
            return formatSpurs(spurs);
        }
        return spurs < 1_000_000
                ? String.format(Locale.ROOT, "%.0fk", spurs / 1_000.0)
                : String.format(Locale.ROOT, "%.2fM", spurs / 1_000_000.0);
    }

    private static String formatCount(long count) {
        if (count >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", count / 1_000_000.0);
        }
        if (count >= 10_000) {
            return String.format(Locale.ROOT, "%.1fk", count / 1_000.0);
        }
        return String.valueOf(count);
    }
}
