package dynamiceconomy.client;

import dynamiceconomy.bank.CoinMath;
import dynamiceconomy.bank.Denomination;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.service.BankService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dynamiceconomy.client.MarketScreen.*;

/**
 * The Bank tab: the coins the player carries, which it deposits or withdraws. Opened from a Bank Card, the screen
 * shows only this tab.
 */
final class BankTab extends ScreenTab {
    private EditBox withdrawAmount;
    private Button withdrawButton;
    private Button depositButton;
    /** Switches between the player's own account and their team's; shown only to team members. */
    private Button accountButton;

    BankTab(MarketScreen screen) {
        super(screen);
    }

    /** The withdraw box and buttons, where the quantity and trade controls are on other tabs. */
    void init(int x, int y, int controlsX) {
        // init() runs again on every resize; keep what the player typed.
        String withdrawText = withdrawAmount != null ? withdrawAmount.getValue() : "";
        int bankWidth = screen.wide ? screen.panelWidth : CONTROLS_WIDTH;
        withdrawAmount = new EditBox(screen.font(), x + controlsX, y + screen.controlsY + 2, bankWidth - 64, 14,
                Component.translatable("screen.dynamic_economy.bank.amount"));
        withdrawAmount.setHint(Component.translatable("screen.dynamic_economy.bank.amount").withColor(TEXT_DIM));
        withdrawAmount.setFilter(s -> s.isEmpty() || (s.length() <= 10 && s.chars().allMatch(Character::isDigit)));
        withdrawAmount.setValue(withdrawText);
        screen.add(withdrawAmount);
        withdrawButton = screen.add(Button.builder(Component.translatable("screen.dynamic_economy.bank.withdraw"), b -> withdraw())
                .bounds(x + controlsX + bankWidth - 62, y + screen.controlsY, 62, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.bank.withdraw.tooltip")))
                .build());
        depositButton = screen.add(Button.builder(Component.translatable("screen.dynamic_economy.bank.deposit"), b -> deposit())
                .bounds(x + (screen.wide ? screen.panelX : MARGIN), y + screen.tradeY, screen.panelWidth, 18)
                .build());
        // Wide: under the two balance lines. Narrow: beside Deposit, which then takes half the row.
        accountButton = screen.add(Button.builder(Component.empty(), b -> switchAccount())
                .bounds(x + (screen.wide ? screen.panelX : MARGIN + screen.panelWidth - (screen.panelWidth - 2) / 2), y + (screen.wide ? screen.panelY + 28 : screen.tradeY),
                        screen.wide ? screen.panelWidth : (screen.panelWidth - 2) / 2, 18)
                .tooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.bank.use.tooltip")))
                .build());
    }

    @Override
    void updateWidgets(boolean quantityShown) {
        if (withdrawAmount != null) {
            boolean bank = isOpen();
            withdrawAmount.visible = bank;
            withdrawButton.visible = bank;
            depositButton.visible = bank;
            accountButton.visible = bank && !ClientMarketState.team().isEmpty();
        }
    }

    @Override
    boolean hides(GuiEventListener focused) {
        return focused == withdrawAmount && !isOpen();
    }

    @Override
    boolean showsQuantity() {
        return false;
    }

    @Override
    boolean showsMarketSelection() {
        return false;
    }

    @Override
    int listSize() {
        return ClientMarketState.coins().size();
    }

    @Override
    boolean rowClicked(int index) {
                screen.minecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                withdrawCoins(ClientMarketState.coins().get(index));
                return true;
    }

    @Override
    void renderTooltip(GuiGraphics graphics, int index, int mouseX, int mouseY) {
        if (index >= 0) {
            Denomination coin = ClientMarketState.coins().get(index);
            int count = coinsPerClick(coin);
            graphics.renderComponentTooltip(screen.font(), List.of(
                    Component.translatable("screen.dynamic_economy.bank.click_withdraw", count, coin.item().getDescription(),
                            PriceFormat.money(PriceFormat.amount((long) count * coin.value()))),
                    Component.translatable("screen.dynamic_economy.bank.click_hint").withColor(TEXT_DIM)), mouseX, mouseY);
        }
    }

    @Override
    void renderList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        renderCoinList(graphics, x, y, mouseX, mouseY);
    }

    @Override
    void renderPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        renderBankPanel(graphics, x, y);
    }

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

    void refreshBankButtons() {
        if (screen.tab != Tab.BANK) {
            return;
        }
        // Joining or leaving a team changes this while the screen is open.
        boolean inTeam = !ClientMarketState.team().isEmpty();
        accountButton.visible = inTeam;
        accountButton.setMessage(ClientMarketState.usingTeam()
                ? Component.translatable("screen.dynamic_economy.bank.use_team", ClientMarketState.team())
                : Component.translatable("screen.dynamic_economy.bank.use_own"));
        if (!screen.wide) {
            depositButton.setWidth(inTeam ? (screen.panelWidth - 2) / 2 : screen.panelWidth);
        }
        long carried = ClientMarketState.carriedCoinValue();
        depositButton.active = carried > 0;
        depositButton.setMessage(carried > 0
                ? Component.translatable("screen.dynamic_economy.bank.deposit_for", PriceFormat.money(PriceFormat.amount(carried)))
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

    /** The bank's coins, largest first: what each is worth and how many the player carries. Clicking one withdraws it. */
    private void renderCoinList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        List<Denomination> coins = ClientMarketState.coins();
        int listRight = LIST_X + screen.listWidth;
        int worthRight = listRight - 60;
        graphics.drawString(screen.font(), Component.translatable("screen.dynamic_economy.bank.col.coin"), x + LIST_X + 22, y + HEADER_Y, TEXT_DIM, false);
        screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.bank.col.worth"), x + worthRight, y + HEADER_Y, TEXT_DIM);
        screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.col.have"), x + listRight - 2, y + HEADER_Y, TEXT_DIM);
        int hovered = screen.rowAt(mouseX, mouseY);
        for (int i = 0; i < screen.rows && screen.scroll + i < coins.size(); i++) {
            Denomination coin = coins.get(screen.scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            int background = screen.scroll + i == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            graphics.renderItem(new ItemStack(coin.item()), x + LIST_X + 2, rowY + 1);
            int textY = rowY + 5;
            graphics.drawString(screen.font(), coin.item().getDescription(), x + LIST_X + 22, textY, TEXT, false);
            screen.drawRight(graphics, PriceFormat.money(PriceFormat.amount(coin.value())), x + worthRight, textY, TEXT_MONEY);
            int held = ClientMarketState.held(coin);
            screen.drawRight(graphics, Component.literal(PriceFormat.count(held)), x + listRight - 2, textY, held > 0 ? TEXT : TEXT_DIM);
        }
        if (coins.isEmpty()) {
            graphics.drawCenteredString(screen.font(), Component.translatable("screen.dynamic_economy.bank.result.bank_unavailable"),
                    x + LIST_X + screen.listWidth / 2, y + LIST_Y + 20, TEXT_DIM);
        }
    }

    /** What the bank holds and the carried coins are worth, and what a withdrawal would give, or why it can't. */
    private void renderBankPanel(GuiGraphics graphics, int x, int y) {
        int left = x + screen.panelX;
        long balance = ClientMarketState.balance();
        Component inBankAmount = PriceFormat.money(PriceFormat.amount(balance) + (ClientMarketState.capped(balance) ? "+" : ""));
        Component inBank = ClientMarketState.usingTeam()
                ? Component.translatable("screen.dynamic_economy.bank.in_team_bank", ClientMarketState.team(), inBankAmount)
                : Component.translatable("screen.dynamic_economy.bank.in_bank", inBankAmount);
        Component carried = Component.translatable("screen.dynamic_economy.bank.carried",
                PriceFormat.money(PriceFormat.amount(ClientMarketState.carriedCoinValue())));
        if (screen.wide) {
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(inBank.getString(), screen.panelWidth), left, y + screen.panelY + 4, TEXT_MONEY, false);
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(carried.getString(), screen.panelWidth), left, y + screen.panelY + 16, TEXT, false);
            List<net.minecraft.util.FormattedCharSequence> help = screen.font().split(
                    Component.translatable("screen.dynamic_economy.bank.help"), screen.panelWidth);
            int helpY = screen.panelY + (accountButton.visible ? 52 : 32);
            for (int i = 0; i < help.size() && helpY + i * 10 < screen.controlsY - 10; i++) {
                graphics.drawString(screen.font(), help.get(i), left, y + helpY + i * 10, TEXT_DIM, false);
            }
        } else {
            String both = inBank.getString() + "   " + carried.getString();
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(both, screen.panelWidth), left, y + screen.panelY + 4, TEXT, false);
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
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(note.getString(), screen.noteWidth), x + screen.noteX, y + screen.noteY, color, false);
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
}
