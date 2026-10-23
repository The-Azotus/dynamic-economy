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
        long buyTotal = row.buyPrice() * qty;
        long sellTotal = row.sellPrice() * qty;
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
        long quotedTotal = (buy ? row.buyPrice() : row.sellPrice()) * qty;
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
        if (index >= 0) {
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
            graphics.drawString(font, displayName(row), x + 28, selectedY + 4, TEXT, false);
        } else {
            graphics.drawString(font, Component.translatable("screen.dynamic_economy.select_hint"), x + 8, selectedY + 4, TEXT_DIM, false);
        }
    }

    private void renderRow(GuiGraphics graphics, MarketPayloads.Row row, int x, int rowY) {
        graphics.renderItem(stack(row), x + LIST_X + 2, rowY + 1);
        int textY = rowY + 5;
        String name = font.plainSubstrByWidth(displayName(row), COL_STOCK - LIST_X - 22 - 34);
        graphics.drawString(font, name, x + LIST_X + 22, textY, TEXT, false);
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
