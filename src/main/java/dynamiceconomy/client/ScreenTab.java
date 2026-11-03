package dynamiceconomy.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;

/**
 * One tab of the {@link MarketScreen}: its list, its side panel, and the controls only it shows. The screen owns
 * what every tab shares (the layout, the search box, the quantity controls, scrolling) and hands each event to the
 * open tab.
 *
 * <p>The defaults suit tabs that keep the Market tab's selection panel beside their own list (History and
 * Leaderboard): the selected market item, its chart, and Buy and Sell.
 */
abstract class ScreenTab {
    protected final MarketScreen screen;

    ScreenTab(MarketScreen screen) {
        this.screen = screen;
    }

    /** Whether this is the open tab. */
    final boolean isOpen() {
        return screen.activeTab() == this;
    }

    /** How many rows the list has. */
    abstract int listSize();

    /** The list's header and visible rows. */
    abstract void renderList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY);

    /** The side panel (below the list when narrow). */
    void renderPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        screen.market().renderPanel(graphics, x, y);
    }

    /** The tooltip for whatever is under the mouse; {@code index} is the hovered row, or -1. */
    void renderTooltip(GuiGraphics graphics, int index, int mouseX, int mouseY) {
        screen.market().renderChartTooltip(graphics, mouseX, mouseY);
    }

    /** A left click on row {@code index}; true if it was handled. */
    boolean rowClicked(int index) {
        return false;
    }

    /** A left click outside the list and the tab strip; true if it was handled. */
    boolean panelClicked(double mouseX, double mouseY) {
        return false;
    }

    /** Just opened: asks the server for anything the tab needs. */
    void opened() {
    }

    /** Shows or hides this tab's own widgets for the open tab. {@code quantityShown}: the quantity controls show. */
    void updateWidgets(boolean quantityShown) {
    }

    /** Whether the search box shows on this tab. */
    boolean showsSearch() {
        return false;
    }

    /** Whether the quantity controls show on this tab. */
    boolean showsQuantity() {
        return true;
    }

    /** Whether the panel shows the Market tab's selection, with Max, Buy and Sell. */
    boolean showsMarketSelection() {
        return true;
    }

    /** The largest quantity one request may ask for. */
    int quantityLimit() {
        return ClientMarketState.maxQuantity();
    }

    /** The All button: every copy held, up to the limit. */
    void fillAll() {
        screen.market().fillSellAll();
    }

    /** Whether {@code focused} is one of this tab's widgets that's now hidden, so focus should leave it. */
    boolean hides(GuiEventListener focused) {
        return false;
    }
}
