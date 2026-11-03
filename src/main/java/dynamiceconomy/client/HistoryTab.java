package dynamiceconomy.client;

import dynamiceconomy.network.MarketPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

import static dynamiceconomy.client.MarketScreen.*;

/** The History tab: the player's recent trades. Clicking one goes back to the Market tab with that item selected. */
final class HistoryTab extends ScreenTab {
    HistoryTab(MarketScreen screen) {
        super(screen);
    }

    @Override
    void opened() {
        PacketDistributor.sendToServer(new MarketPayloads.TradesRequest());
    }

    @Override
    int listSize() {
        return trades().size();
    }

    /** Clicking a past trade goes back to the market with that item selected, to trade it again. */
    @Override
    boolean rowClicked(int index) {
        screen.market().select(trades().get(index).item());
        return true;
    }

    @Override
    void renderTooltip(GuiGraphics graphics, int index, int mouseX, int mouseY) {
        if (!screen.market().renderChartTooltip(graphics, mouseX, mouseY) && index >= 0) {
            graphics.renderTooltip(screen.font(), Component.translatable("screen.dynamic_economy.trades.row_hint"), mouseX, mouseY);
        }
    }

    @Override
    void renderList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        renderTradeList(graphics, x, y, mouseX, mouseY);
    }

    private static List<dynamiceconomy.data.TradeHistory.Trade> trades() {
        MarketPayloads.Trades received = ClientMarketState.trades();
        return received == null ? List.of() : received.trades();
    }

    /** The player's recent trades, newest first: what, how many, the total, and how long ago. */
    private void renderTradeList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        MarketPayloads.Trades received = ClientMarketState.trades();
        List<dynamiceconomy.data.TradeHistory.Trade> trades = trades();
        int listRight = LIST_X + screen.listWidth;
        int amountRight = listRight - 54;
        graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.trades.title", trades.size()),
                x + LIST_X + 22, y + HEADER_Y, TEXT_DIM, false);
        int hovered = screen.rowAt(mouseX, mouseY);
        for (int i = 0; i < screen.rows && screen.scroll + i < trades.size(); i++) {
            dynamiceconomy.data.TradeHistory.Trade trade = trades.get(screen.scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            int background = screen.scroll + i == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(trade.item())), x + LIST_X + 2, rowY + 1);
            int textY = rowY + 5;
            String name = BuiltInRegistries.ITEM.get(trade.item()).getDescription().getString();
            Component what = Component.translatable(trade.buy() ? "screen.dynamic_economy.trades.bought"
                    : "screen.dynamic_economy.trades.sold", PriceFormat.amount(trade.quantity()), name);
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(what.getString(), amountRight - 60 - LIST_X - 22),
                    x + LIST_X + 22, textY, TEXT, false);
            screen.drawRight(graphics, Component.literal((trade.buy() ? "-" : "+") + PriceFormat.amount(trade.total())),
                    x + amountRight, textY, trade.buy() ? TEXT_MONEY : TEXT_GOOD);
            screen.drawRight(graphics, ago(received.now() - trade.time()), x + listRight - 2, textY, TEXT_DIM);
        }
        if (trades.isEmpty()) {
            graphics.drawCenteredString(screen.font(), Component.translatable(received == null
                    ? "screen.dynamic_economy.trades.loading" : "screen.dynamic_economy.trades.none"),
                    x + LIST_X + screen.listWidth / 2, y + LIST_Y + 20, TEXT_DIM);
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
}
