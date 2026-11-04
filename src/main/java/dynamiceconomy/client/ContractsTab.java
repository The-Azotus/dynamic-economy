package dynamiceconomy.client;

import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.network.MarketPayloads;
import dynamiceconomy.service.ContractService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dynamiceconomy.client.MarketScreen.*;

/**
 * The Contracts tab: the contract board. The selected contract can be claimed, delivered to and abandoned with the
 * quantity controls and two buttons where Buy and Sell are.
 */
final class ContractsTab extends ScreenTab {
    /** Claim or deliver, and abandon: where Buy and Sell are, on the Contracts tab only. */
    private Button contractButton;
    private Button abandonButton;
    /** The selected contract's ID, if any. */
    @Nullable
    private Integer selectedContract;
    /** Abandoning loses the deposit, so it takes a second click; this is the contract the first click was for. */
    @Nullable
    private Integer abandonArmed;

    ContractsTab(MarketScreen screen) {
        super(screen);
    }

    /** Claim or deliver, and abandon, over the Market tab's Buy and Sell. */
    void init() {
        Button buyButton = screen.market().buyButton();
        Button sellButton = screen.market().sellButton();
        contractButton = screen.add(Button.builder(Component.empty(), b -> contractAction())
                .bounds(buyButton.getX(), buyButton.getY(), buyButton.getWidth(), 18).build());
        abandonButton = screen.add(Button.builder(Component.translatable("screen.dynamic_economy.contracts.abandon"), b -> abandon())
                .bounds(sellButton.getX(), sellButton.getY(), sellButton.getWidth(), 18).build());
    }

    @Override
    void updateWidgets(boolean quantityShown) {
        if (contractButton != null) {
            contractButton.visible = isOpen();
            abandonButton.visible = isOpen();
        }
    }

    @Override
    boolean showsMarketSelection() {
        return false;
    }

    @Override
    void opened() {
        PacketDistributor.sendToServer(new MarketPayloads.ContractsRequest());
    }

    @Override
    int listSize() {
        return contractLines().size();
    }

    @Override
    void fillAll() {
        fillDeliverAll();
    }

    @Override
    boolean rowClicked(int index) {
        screen.minecraft().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        MarketPayloads.ContractLine line = contractLines().get(index);
        if (!Integer.valueOf(line.id()).equals(selectedContract)) {
            selectedContract = line.id();
            abandonArmed = null;
            if (deliverable(line)) {
                // Start from what can be delivered now.
                startDeliveryQuantity(line);
            }
        }
        screen.setFocused(null);
        return true;
    }

    @Override
    void renderTooltip(GuiGraphics graphics, int index, int mouseX, int mouseY) {
        if (index >= 0) {
            graphics.renderTooltip(screen.font(), contractTooltip(contractLines().get(index)), Optional.empty(), mouseX, mouseY);
        }
    }

    @Override
    void renderList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        renderContractList(graphics, x, y, mouseX, mouseY);
    }

    @Override
    void renderPanel(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        renderContractPanel(graphics, x, y);
        screen.renderQuantityNote(graphics, x + screen.noteX, y + screen.noteY);
    }

    private static List<MarketPayloads.ContractLine> contractLines() {
        MarketPayloads.Contracts board = ClientMarketState.contracts();
        return board == null ? List.of() : board.lines();
    }

    @Nullable
    private MarketPayloads.ContractLine selectedContractLine() {
        if (selectedContract == null) {
            return null;
        }
        for (MarketPayloads.ContractLine line : contractLines()) {
            if (line.id() == selectedContract) {
                return line;
            }
        }
        return null;
    }

    private static boolean deliverable(MarketPayloads.ContractLine line) {
        return line.mine() && line.status() == ContractBoard.Status.CLAIMED;
    }

    /** "All" on the Contracts tab: every copy held, up to what the contract still wants and the order limit. */
    private void fillDeliverAll() {
        MarketPayloads.ContractLine line = selectedContractLine();
        if (line == null || !deliverable(line)) {
            return;
        }
        int held = ClientMarketState.held(line.item());
        if (held == 0) {
            screen.quantityNote = Component.translatable("screen.dynamic_economy.note.none_held");
            screen.quantityNoteFor = screen.quantityValue();
            return;
        }
        screen.setQuantity(Math.min(Math.min(held, line.remaining()), ClientMarketState.maxQuantity()), Component.empty());
    }

    /** Client-side hints only; the server re-checks everything and replies with the real outcome. */
    void refreshContractButtons() {
        if (screen.tab != Tab.CONTRACTS) {
            return;
        }
        MarketPayloads.ContractLine line = selectedContractLine();
        MarketPayloads.Contracts board = ClientMarketState.contracts();
        if (line == null || board == null) {
            contractButton.setMessage(Component.translatable("screen.dynamic_economy.contracts.claim"));
            contractButton.active = false;
            contractButton.setTooltip(null);
            abandonButton.active = false;
            abandonButton.setTooltip(null);
            abandonArmed = null;
            return;
        }
        if (deliverable(line)) {
            int qty = screen.quantityValue();
            contractButton.setMessage(Component.translatable("screen.dynamic_economy.contracts.deliver", PriceFormat.amount(qty)));
            Component problem = qty <= 0 ? Component.translatable("screen.dynamic_economy.result.invalid_quantity")
                    : qty > ClientMarketState.maxQuantity() ? Component.translatable("screen.dynamic_economy.result.too_large")
                    : qty > line.remaining() ? Component.translatable("screen.dynamic_economy.result.contract_full")
                    : ClientMarketState.held(line.item()) < qty ? Component.translatable("screen.dynamic_economy.result.not_enough_items")
                    : null;
            contractButton.active = problem == null;
            contractButton.setTooltip(Tooltip.create(problem != null ? problem
                    : Component.translatable(qty == line.remaining() ? "screen.dynamic_economy.contracts.deliver.finishes"
                            : "screen.dynamic_economy.contracts.deliver.tooltip", PriceFormat.money(PriceFormat.amount(line.reward() + line.deposit())))));
            abandonButton.active = true;
            abandonButton.setMessage(Component.translatable(Integer.valueOf(line.id()).equals(abandonArmed)
                    ? "screen.dynamic_economy.contracts.abandon_confirm" : "screen.dynamic_economy.contracts.abandon"));
            abandonButton.setTooltip(Tooltip.create(Component.translatable("screen.dynamic_economy.contracts.abandon.tooltip",
                    PriceFormat.money(PriceFormat.amount(line.deposit())))));
            return;
        }
        abandonArmed = null;
        abandonButton.active = false;
        abandonButton.setMessage(Component.translatable("screen.dynamic_economy.contracts.abandon"));
        abandonButton.setTooltip(null);
        contractButton.setMessage(line.status() == ContractBoard.Status.OPEN
                ? Component.translatable("screen.dynamic_economy.contracts.claim_for", PriceFormat.money(PriceFormat.amount(line.deposit())))
                : Component.translatable("screen.dynamic_economy.contracts.claim"));
        Component problem = line.status() != ContractBoard.Status.OPEN ? Component.translatable("screen.dynamic_economy.result.contract_taken")
                : board.held() >= board.maxHeld() ? Component.translatable("screen.dynamic_economy.result.contract_limit")
                : !ClientMarketState.looksAffordable(line.deposit()) ? Component.translatable("screen.dynamic_economy.result.no_funds")
                : null;
        contractButton.active = problem == null;
        contractButton.setTooltip(Tooltip.create(problem != null ? problem
                : Component.translatable("screen.dynamic_economy.contracts.claim.tooltip", PriceFormat.money(PriceFormat.amount(line.deposit())))));
    }

    private void contractAction() {
        MarketPayloads.ContractLine line = selectedContractLine();
        if (line == null) {
            return;
        }
        if (deliverable(line)) {
            int qty = screen.quantityValue();
            if (qty > 0) {
                PacketDistributor.sendToServer(new MarketPayloads.ContractRequest(ContractService.Action.DELIVER, line.id(), qty));
            }
        } else if (line.status() == ContractBoard.Status.OPEN) {
            PacketDistributor.sendToServer(new MarketPayloads.ContractRequest(ContractService.Action.CLAIM, line.id(), 0));
        }
    }

    private void abandon() {
        MarketPayloads.ContractLine line = selectedContractLine();
        if (line == null || !deliverable(line)) {
            return;
        }
        if (!Integer.valueOf(line.id()).equals(abandonArmed)) {
            abandonArmed = line.id();
            return;
        }
        abandonArmed = null;
        PacketDistributor.sendToServer(new MarketPayloads.ContractRequest(ContractService.Action.ABANDON, line.id(), 0));
    }

    private void startDeliveryQuantity(MarketPayloads.ContractLine line) {
        int held = ClientMarketState.held(line.item());
        screen.setQuantity(Math.max(1, Math.min(Math.min(held, line.remaining()), ClientMarketState.maxQuantity())), Component.empty());
    }

    /** The board: what each contract wants, what it pays, and who holds it; the days left and the player's count above. */
    private void renderContractList(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        MarketPayloads.Contracts board = ClientMarketState.contracts();
        List<MarketPayloads.ContractLine> lines = contractLines();
        int listRight = LIST_X + screen.listWidth;
        int statusRight = listRight - 2;
        int rewardRight = listRight - 66;
        if (board != null) {
            graphics.drawString(screen.font(), Component.translatable(board.daysLeft() == 1 ? "screen.dynamic_economy.contracts.title_one"
                    : "screen.dynamic_economy.contracts.title", board.daysLeft()), x + LIST_X + 4, y + HEADER_Y, TEXT_DIM, false);
            screen.drawRight(graphics, Component.translatable("screen.dynamic_economy.contracts.held", board.held(), board.maxHeld()),
                    x + statusRight, y + HEADER_Y, board.held() >= board.maxHeld() ? TEXT_MONEY : TEXT_DIM);
        }
        int hovered = screen.rowAt(mouseX, mouseY);
        for (int i = 0; i < screen.rows && screen.scroll + i < lines.size(); i++) {
            MarketPayloads.ContractLine line = lines.get(screen.scroll + i);
            int rowY = y + LIST_Y + i * ROW_HEIGHT;
            boolean selectedRow = Integer.valueOf(line.id()).equals(selectedContract);
            int background = selectedRow ? ROW_SELECTED : screen.scroll + i == hovered ? ROW_HOVER : i % 2 == 1 ? ROW_ALT : 0;
            if (background != 0) {
                graphics.fill(x + LIST_X, rowY, x + listRight, rowY + ROW_HEIGHT, background);
            }
            boolean done = line.status() == ContractBoard.Status.COMPLETED || line.status() == ContractBoard.Status.ABANDONED;
            graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(line.item())), x + LIST_X + 2, rowY + 1);
            int textY = rowY + 5;
            Component what = Component.translatable("screen.dynamic_economy.contracts.wants", PriceFormat.amount(line.units()),
                    BuiltInRegistries.ITEM.get(line.item()).getDescription());
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(what.getString(), rewardRight - 60 - LIST_X - 22),
                    x + LIST_X + 22, textY, done ? TEXT_DIM : TEXT, false);
            screen.drawRight(graphics, PriceFormat.money(PriceFormat.amount(line.reward())), x + rewardRight, textY, done ? TEXT_DIM : TEXT_MONEY);
            Component state;
            int color;
            switch (line.status()) {
                case OPEN -> {
                    state = Component.translatable("screen.dynamic_economy.contracts.open");
                    color = TEXT_GOOD;
                }
                case CLAIMED -> {
                    state = line.mine()
                            ? Component.literal(PriceFormat.count(line.delivered()) + "/" + PriceFormat.count(line.units()))
                            : Component.literal(line.holder());
                    color = line.mine() ? TEXT_MONEY : TEXT_DIM;
                }
                case COMPLETED -> {
                    state = Component.translatable("screen.dynamic_economy.contracts.done");
                    color = TEXT_OFF;
                }
                default -> {
                    state = Component.translatable("screen.dynamic_economy.contracts.given_up");
                    color = TEXT_OFF;
                }
            }
            screen.drawRight(graphics, Component.literal(screen.font().plainSubstrByWidth(state.getString(), 60)), x + statusRight, textY, color);
        }
        if (lines.isEmpty()) {
            graphics.drawCenteredString(screen.font(), Component.translatable(board == null
                            ? "screen.dynamic_economy.trades.loading" : "screen.dynamic_economy.contracts.none"),
                    x + LIST_X + screen.listWidth / 2, y + LIST_Y + 20, TEXT_DIM);
        }
    }

    /** The selected contract: what it pays, the deposit, progress, and how many the player holds; or how contracts work. */
    private void renderContractPanel(GuiGraphics graphics, int x, int y) {
        int left = x + screen.panelX;
        MarketPayloads.ContractLine line = selectedContractLine();
        if (line == null) {
            List<net.minecraft.util.FormattedCharSequence> help = screen.font().split(
                    Component.translatable(screen.wide ? "screen.dynamic_economy.contracts.help" : "screen.dynamic_economy.contracts.select_hint"),
                    screen.panelWidth);
            for (int i = 0; i < help.size() && screen.panelY + 4 + i * 10 < screen.controlsY - 10; i++) {
                graphics.drawString(screen.font(), help.get(i), left, y + screen.panelY + 4 + i * 10, TEXT_DIM, false);
            }
            return;
        }
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(line.item()));
        graphics.renderItem(stack, left, y + screen.panelY);
        int held = ClientMarketState.held(line.item());
        if (!screen.wide) {
            Component summary = Component.translatable("screen.dynamic_economy.contracts.summary", stack.getHoverName(),
                    PriceFormat.money(PriceFormat.amount(line.reward())), PriceFormat.money(PriceFormat.amount(line.deposit())),
                    PriceFormat.count(line.delivered()), PriceFormat.count(line.units()));
            graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(summary.getString(), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
            return;
        }
        graphics.drawString(screen.font(), screen.font().plainSubstrByWidth(stack.getHoverName().getString(), screen.panelWidth - 20), left + 20, y + screen.panelY + 4, TEXT, false);
        int right = left + screen.panelWidth;
        int top = y + screen.chartY;
        screen.infoLine(graphics, "contracts.reward", PriceFormat.amount(line.reward()), TEXT_MONEY, left, right, top);
        screen.infoLine(graphics, "contracts.per_unit", PriceFormat.decimal(java.math.BigDecimal.valueOf(line.reward())
                .divide(java.math.BigDecimal.valueOf(line.units()), 2, RoundingMode.FLOOR)), TEXT_MONEY, left, right, top + INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "contracts.deposit", PriceFormat.amount(line.deposit()), TEXT, left, right, top + 2 * INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "contracts.progress", PriceFormat.count(line.delivered()) + "/" + PriceFormat.count(line.units()),
                line.mine() ? TEXT_MONEY : TEXT, left, right, top + 3 * INFO_LINE_HEIGHT);
        screen.infoLine(graphics, "col.have", PriceFormat.amount(held), held > 0 ? TEXT : TEXT_DIM, left, right, top + 4 * INFO_LINE_HEIGHT);
        Component note = switch (line.status()) {
            case OPEN -> Component.translatable("screen.dynamic_economy.contracts.note.open");
            case CLAIMED -> line.mine() ? Component.translatable("screen.dynamic_economy.contracts.note.mine")
                    : Component.translatable("screen.dynamic_economy.contracts.note.taken", line.holder());
            case COMPLETED -> Component.translatable("screen.dynamic_economy.contracts.note.done");
            case ABANDONED -> Component.translatable("screen.dynamic_economy.contracts.note.given_up");
        };
        List<net.minecraft.util.FormattedCharSequence> lines = screen.font().split(note, screen.panelWidth);
        int noteTop = screen.chartY + 5 * INFO_LINE_HEIGHT + 4;
        for (int i = 0; i < lines.size() && noteTop + i * 10 < screen.controlsY - 9; i++) {
            graphics.drawString(screen.font(), lines.get(i), left, y + noteTop + i * 10, TEXT_DIM, false);
        }
    }

    private List<Component> contractTooltip(MarketPayloads.ContractLine line) {
        List<Component> lines = new ArrayList<>(getTooltipFromItem(screen.minecraft(), new ItemStack(BuiltInRegistries.ITEM.get(line.item()))));
        lines.add(Component.translatable("screen.dynamic_economy.contracts.tooltip.reward",
                PriceFormat.money(PriceFormat.amount(line.reward())), PriceFormat.money(PriceFormat.amount(line.deposit()))).withColor(TEXT_MONEY));
        if (line.status() == ContractBoard.Status.CLAIMED && !line.mine()) {
            lines.add(Component.translatable("screen.dynamic_economy.contracts.note.taken", line.holder()).withColor(TEXT_DIM));
        }
        return lines;
    }
}
