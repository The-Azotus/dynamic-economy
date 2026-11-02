package dynamiceconomy.client;

import dynamiceconomy.network.MarketPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

import static dynamiceconomy.client.MarketScreen.*;

/** The Leaderboard tab: the richest players, with the player's own line highlighted and their rank above. */
final class LeaderboardTab extends ScreenTab {
    LeaderboardTab(MarketScreen screen) {
        super(screen);
    }

    @Override
    void opened() {
        PacketDistributor.sendToServer(new MarketPayloads.LeaderboardRequest());
    }

    @Override
    int listSize() {
        return leaderboardLines().size();
    }

    @Override
    void renderList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        renderLeaderboard(graphics, x, y);
    }

    private static List<MarketPayloads.LeaderboardLine> leaderboardLines() {
        MarketPayloads.Leaderboard board = ClientMarketState.leaderboard();
        return board == null ? List.of() : board.lines();
    }

    /** The richest players: rank, name and balance, with the player's own line highlighted and their rank above. */
    private void renderLeaderboard(GuiGraphics graphics, int x, int y) {
        MarketPayloads.Leaderboard board = ClientMarketState.leaderboard();
        List<MarketPayloads.LeaderboardLine> lines = leaderboardLines();
        int listRight = LIST_X + screen.listWidth;
        graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.leaderboard.title"),
                x + LIST_X + 4, y + HEADER_Y, TEXT_DIM, false);
        if (board != null) {
            Component own = board.ownRank() > 0
                    ? Component.translatable("screen.dynamic_economy.leaderboard.you", board.ownRank(), board.ranked())
                    : Component.translatable("screen.dynamic_economy.leaderboard.you_unranked");
            screen.drawRight(graphics, own, x + listRight - 2, y + HEADER_Y, TEXT_DIM);
        }
        for (int i = 0; i < screen.rows && screen.scroll + i < lines.size(); i++) {
            MarketPayloads.LeaderboardLine line = lines.get(screen.scroll + i);
            int rank = screen.scroll + i + 1;
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            int background = line.you() ? ROW_SELECTED : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            int textY = rowY + 5;
            screen.drawRight(graphics, Component.literal("#" + rank), x + LIST_X + 26, textY, rank <= 3 ? TEXT_MONEY : TEXT_DIM);
            graphics.drawString(screen.font(), line.name(), x + LIST_X + 32, textY, line.you() ? TEXT_GOOD : TEXT, false);
            screen.drawRight(graphics, PriceFormat.money(PriceFormat.amount(line.balance())
                    + (ClientMarketState.capped(line.balance()) ? "+" : "")), x + listRight - 2, textY, TEXT_MONEY);
        }
        if (lines.isEmpty()) {
            graphics.drawCenteredString(screen.font(), Component.translatable(board == null
                            ? "screen.dynamic_economy.trades.loading" : "screen.dynamic_economy.leaderboard.none"),
                    x + LIST_X + screen.listWidth / 2, y + LIST_Y + 20, TEXT_DIM);
        }
    }
}
