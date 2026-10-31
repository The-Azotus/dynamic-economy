package dynamiceconomy.network;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.bank.BankKind;
import dynamiceconomy.bank.Denomination;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.service.BankService;
import dynamiceconomy.service.ContractService;
import dynamiceconomy.service.ExchangeService;
import dynamiceconomy.data.TradeHistory;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.PriceModel;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/** Every packet the market sends. Prices, totals and balances are in bits (= spurs). */
public final class MarketPayloads {
    private MarketPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DynamicEconomy.MOD_ID, path));
    }

    /** A stock level on the wire: a flag for unlimited, then the amount only when finite. */
    private static final StreamCodec<ByteBuf, OptionalLong> STOCK_CODEC = StreamCodec.of(
            (buf, stock) -> {
                buf.writeBoolean(stock.isPresent());
                if (stock.isPresent()) {
                    ByteBufCodecs.VAR_LONG.encode(buf, stock.getAsLong());
                }
            },
            buf -> buf.readBoolean() ? OptionalLong.of(ByteBufCodecs.VAR_LONG.decode(buf)) : OptionalLong.empty());

    /** Pricing inputs on the wire. Doubles travel as exact bits so client and server price identically. */
    private static final StreamCodec<ByteBuf, PriceModel.Inputs> INPUTS_CODEC = StreamCodec.of(
            (buf, in) -> {
                ByteBufCodecs.VAR_INT.encode(buf, in.basePrice());
                buf.writeDouble(in.markup());
                buf.writeDouble(in.sellFee());
                buf.writeDouble(in.minBuyMarkup());
                buf.writeBoolean(in.unlimited());
                buf.writeBoolean(in.dynamic());
                buf.writeDouble(in.sellImpact());
                buf.writeDouble(in.buyImpact());
                buf.writeDouble(in.floor());
                buf.writeDouble(in.ceiling());
                buf.writeDouble(in.recovery());
            },
            buf -> new PriceModel.Inputs(ByteBufCodecs.VAR_INT.decode(buf), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readBoolean(), buf.readBoolean(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble()));

    /**
     * One listed item as every player sees it. The client prices orders itself from {@code inputs} and
     * {@code factor} with the same {@link PriceModel} the server uses. {@code stock} is empty for unlimited items.
     */
    public record Row(ResourceLocation item, boolean canSell, boolean canBuy, OptionalLong stock,
                      PriceModel.Inputs inputs, double factor) {
        public static final StreamCodec<ByteBuf, Row> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Row::item,
                ByteBufCodecs.BOOL, Row::canSell,
                ByteBufCodecs.BOOL, Row::canBuy,
                STOCK_CODEC, Row::stock,
                INPUTS_CODEC, Row::inputs,
                ByteBufCodecs.DOUBLE, Row::factor,
                Row::new);

        public boolean unlimited() {
            return stock.isEmpty();
        }

        /** Whether the market can supply {@code qty} right now. */
        public boolean hasStock(long qty) {
            return stock.isEmpty() || stock.getAsLong() >= qty;
        }

        /** What the market pays for the next unit. */
        public long sellPrice() {
            return PriceModel.unitSellPrice(inputs, factor);
        }

        /** What the market charges for the next unit. */
        public long buyPrice() {
            return PriceModel.unitBuyPrice(inputs, factor);
        }

        /** What the market pays for the next unit, unrounded, so cheap items don't show as 0. Display only. */
        public BigDecimal exactSellPrice() {
            return PriceModel.exactUnitSellPrice(inputs, factor);
        }

        /** What the market charges for the next unit, unrounded. Display only. */
        public BigDecimal exactBuyPrice() {
            return PriceModel.exactUnitBuyPrice(inputs, factor);
        }

        /** The exact total the server will pay for selling {@code qty}. */
        public long sellTotal(int qty) {
            return PriceModel.sell(inputs, factor, qty).total();
        }

        /** The exact total the server will charge for buying {@code qty}. */
        public long buyTotal(int qty) {
            return PriceModel.buy(inputs, factor, qty).total();
        }
    }

    /**
     * Client to server: buy or sell from the open market screen. {@code quotedTotal} is the total the player was
     * shown; the server rejects the trade with PRICE_CHANGED if its own total differs.
     */
    public record TradeRequest(boolean buy, ResourceLocation item, int quantity, long quotedTotal) implements CustomPacketPayload {
        public static final Type<TradeRequest> TYPE = payloadType("trade_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, TradeRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, TradeRequest::buy,
                ResourceLocation.STREAM_CODEC, TradeRequest::item,
                ByteBufCodecs.VAR_INT, TradeRequest::quantity,
                ByteBufCodecs.VAR_LONG, TradeRequest::quotedTotal,
                TradeRequest::new);

        @Override
        public Type<TradeRequest> type() {
            return TYPE;
        }
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, Denomination> DENOMINATION_CODEC = StreamCodec.composite(
            ByteBufCodecs.registry(Registries.ITEM), Denomination::item,
            ByteBufCodecs.VAR_INT, Denomination::value,
            Denomination::new);

    private static final StreamCodec<ByteBuf, BankKind> BANK_KIND_CODEC =
            ByteBufCodecs.idMapper(i -> BankKind.values()[i], BankKind::ordinal);
    private static final StreamCodec<ByteBuf, List<Row>> ROWS_CODEC = Row.CODEC.apply(ByteBufCodecs.list());
    private static final StreamCodec<RegistryFriendlyByteBuf, List<Denomination>> COINS_CODEC =
            DENOMINATION_CODEC.apply(ByteBufCodecs.list(32));

    /**
     * Server to client: the whole catalog, sent on joining, when the screen opens and after the catalog changes, with
     * the server's order limit so the screen can stop an oversized order before it is sent, whether the leaderboard
     * contracts and exchange tabs are turned on, and the world's bank: it names the money unit, its {@code balanceCap} is
     * the largest balance it shows (a balance at the cap means "at least this much"), and {@code coins} are what the
     * Bank tab moves.
     */
    public record Snapshot(List<Row> rows, int maxQuantity, boolean leaderboardEnabled, boolean contractsEnabled,
                           boolean exchangeEnabled, BankKind bank, long balanceCap, List<Denomination> coins) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = payloadType("snapshot");
        // Written out by hand: StreamCodec.composite takes at most six fields.
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (buf, snapshot) -> {
                    ROWS_CODEC.encode(buf, snapshot.rows());
                    ByteBufCodecs.VAR_INT.encode(buf, snapshot.maxQuantity());
                    buf.writeBoolean(snapshot.leaderboardEnabled());
                    buf.writeBoolean(snapshot.contractsEnabled());
                    buf.writeBoolean(snapshot.exchangeEnabled());
                    BANK_KIND_CODEC.encode(buf, snapshot.bank());
                    ByteBufCodecs.VAR_LONG.encode(buf, snapshot.balanceCap());
                    COINS_CODEC.encode(buf, snapshot.coins());
                },
                buf -> new Snapshot(ROWS_CODEC.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), buf.readBoolean(), buf.readBoolean(),
                        buf.readBoolean(), BANK_KIND_CODEC.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf), COINS_CODEC.decode(buf)));

        @Override
        public Type<Snapshot> type() {
            return TYPE;
        }
    }

    /** Server to client: some items' stock or price changed. Sent to every player. */
    public record RowUpdate(List<Row> rows) implements CustomPacketPayload {
        public static final Type<RowUpdate> TYPE = payloadType("row_update");
        public static final StreamCodec<RegistryFriendlyByteBuf, RowUpdate> CODEC = StreamCodec.composite(
                Row.CODEC.apply(ByteBufCodecs.list()), RowUpdate::rows,
                RowUpdate::new);

        @Override
        public Type<RowUpdate> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the balance of the account this player uses, how many tradeable copies of each item (and plain
     * coins of the bank's set) they hold, and how much room their main inventory has (empty slots, plus the space left
     * in stacks of each of those). Tradeable means plain enough for the market or the exchange, listed or not. {@code team} is their team's name ("" if none) and {@code usingTeam} whether
     * the balance is the team's. The client cannot
     * work this out itself: vanilla does not sync the inventory while a slotless menu is open.
     */
    public record PlayerState(long balance, Map<ResourceLocation, Integer> held, int emptySlots,
                              Map<ResourceLocation, Integer> stackRoom, String team, boolean usingTeam) implements CustomPacketPayload {
        public static final Type<PlayerState> TYPE = payloadType("player_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerState> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, PlayerState::balance,
                ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT), PlayerState::held,
                ByteBufCodecs.VAR_INT, PlayerState::emptySlots,
                ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT), PlayerState::stackRoom,
                ByteBufCodecs.stringUtf8(64), PlayerState::team,
                ByteBufCodecs.BOOL, PlayerState::usingTeam,
                PlayerState::new);

        /** Room for {@code item}, counted the way the server's purchase check counts it. */
        public long room(ResourceLocation item, int maxStackSize) {
            return (long) emptySlots * maxStackSize + stackRoom.getOrDefault(item, 0);
        }

        @Override
        public Type<PlayerState> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: from the Bank tab, deposit every coin in the main inventory, or withdraw
     * {@code amount} as the fewest coins, or, with {@code coin}, {@code amount} of exactly that coin.
     */
    public record BankRequest(boolean deposit, long amount, Optional<Item> coin) implements CustomPacketPayload {
        public static final Type<BankRequest> TYPE = payloadType("bank_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, BankRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, BankRequest::deposit,
                ByteBufCodecs.VAR_LONG, BankRequest::amount,
                ByteBufCodecs.optional(ByteBufCodecs.registry(Registries.ITEM)), BankRequest::coin,
                BankRequest::new);

        /** Withdraws {@code count} of exactly this coin, instead of an amount as the fewest coins. */
        public static BankRequest withdrawCoins(Item coin, int count) {
            return new BankRequest(false, count, Optional.of(coin));
        }

        @Override
        public Type<BankRequest> type() {
            return TYPE;
        }
    }

    /** Client to server: from the Bank tab, use the team's account ({@code team}) or the player's own. */
    public record PayFromRequest(boolean team) implements CustomPacketPayload {
        public static final Type<PayFromRequest> TYPE = payloadType("pay_from_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, PayFromRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, PayFromRequest::team,
                PayFromRequest::new);

        @Override
        public Type<PayFromRequest> type() {
            return TYPE;
        }
    }

    /** Server to client: how a bank request went, and the amount moved. */
    public record BankOutcome(boolean deposit, BankService.Result result, long amount) implements CustomPacketPayload {
        public static final Type<BankOutcome> TYPE = payloadType("bank_outcome");
        public static final StreamCodec<RegistryFriendlyByteBuf, BankOutcome> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, BankOutcome::deposit,
                ByteBufCodecs.idMapper(i -> BankService.Result.values()[i], BankService.Result::ordinal), BankOutcome::result,
                ByteBufCodecs.VAR_LONG, BankOutcome::amount,
                BankOutcome::new);

        @Override
        public Type<BankOutcome> type() {
            return TYPE;
        }
    }

    /** Client to server: send me my recent trades (the screen asks when its trade list is opened). */
    public record TradesRequest() implements CustomPacketPayload {
        public static final Type<TradesRequest> TYPE = payloadType("trades_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, TradesRequest> CODEC = StreamCodec.unit(new TradesRequest());

        @Override
        public Type<TradesRequest> type() {
            return TYPE;
        }
    }

    private static final StreamCodec<ByteBuf, TradeHistory.Trade> TRADE_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, TradeHistory.Trade::time,
            ResourceLocation.STREAM_CODEC, TradeHistory.Trade::item,
            ByteBufCodecs.BOOL, TradeHistory.Trade::buy,
            ByteBufCodecs.VAR_INT, TradeHistory.Trade::quantity,
            ByteBufCodecs.VAR_LONG, TradeHistory.Trade::total,
            TradeHistory.Trade::new);

    /**
     * Server to client: this player's recent trades, newest first, with the server's clock so the screen can say
     * "3 min ago" even if the player's clock differs.
     */
    public record Trades(long now, List<TradeHistory.Trade> trades) implements CustomPacketPayload {
        public static final Type<Trades> TYPE = payloadType("trades");
        public static final StreamCodec<RegistryFriendlyByteBuf, Trades> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Trades::now,
                TRADE_CODEC.apply(ByteBufCodecs.list(TradeHistory.CAP)), Trades::trades,
                Trades::new);

        @Override
        public Type<Trades> type() {
            return TYPE;
        }
    }

    /** Client to server: send me the leaderboard (the screen asks when its tab is opened). */
    public record LeaderboardRequest() implements CustomPacketPayload {
        public static final Type<LeaderboardRequest> TYPE = payloadType("leaderboard_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, LeaderboardRequest> CODEC = StreamCodec.unit(new LeaderboardRequest());

        @Override
        public Type<LeaderboardRequest> type() {
            return TYPE;
        }
    }

    /** One leaderboard line: the player's name, their balance, and whether it's the player receiving it. */
    public record LeaderboardLine(String name, long balance, boolean you) {
        static final StreamCodec<ByteBuf, LeaderboardLine> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(16), LeaderboardLine::name,
                ByteBufCodecs.VAR_LONG, LeaderboardLine::balance,
                ByteBufCodecs.BOOL, LeaderboardLine::you,
                LeaderboardLine::new);
    }

    /**
     * Server to client: the richest players, richest first, and the receiving player's own rank (0 when they aren't
     * ranked) out of {@code ranked} players with money.
     */
    public record Leaderboard(List<LeaderboardLine> lines, int ownRank, int ranked) implements CustomPacketPayload {
        public static final Type<Leaderboard> TYPE = payloadType("leaderboard");
        public static final StreamCodec<RegistryFriendlyByteBuf, Leaderboard> CODEC = StreamCodec.composite(
                LeaderboardLine.CODEC.apply(ByteBufCodecs.list(100)), Leaderboard::lines,
                ByteBufCodecs.VAR_INT, Leaderboard::ownRank,
                ByteBufCodecs.VAR_INT, Leaderboard::ranked,
                Leaderboard::new);

        @Override
        public Type<Leaderboard> type() {
            return TYPE;
        }
    }

    /** Client to server: send me this item's price history (the screen asks when an item is selected). */
    public record HistoryRequest(ResourceLocation item) implements CustomPacketPayload {
        public static final Type<HistoryRequest> TYPE = payloadType("history_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, HistoryRequest> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, HistoryRequest::item,
                HistoryRequest::new);

        @Override
        public Type<HistoryRequest> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: an item's recent price factors, oldest first. Each point holds from its tick until the next;
     * the last holds until {@code now}, the server's game time when this was sent.
     */
    public record History(ResourceLocation item, long now, long[] ticks, float[] factors) implements CustomPacketPayload {
        public static final Type<History> TYPE = payloadType("history");
        public static final StreamCodec<RegistryFriendlyByteBuf, History> CODEC = StreamCodec.of(
                (buf, history) -> {
                    ResourceLocation.STREAM_CODEC.encode(buf, history.item());
                    buf.writeVarLong(history.now());
                    buf.writeVarInt(history.ticks().length);
                    for (int i = 0; i < history.ticks().length; i++) {
                        buf.writeVarLong(history.ticks()[i]);
                        buf.writeFloat(history.factors()[i]);
                    }
                },
                buf -> {
                    ResourceLocation item = ResourceLocation.STREAM_CODEC.decode(buf);
                    long now = buf.readVarLong();
                    int count = Math.min(buf.readVarInt(), 1024);
                    long[] ticks = new long[count];
                    float[] factors = new float[count];
                    for (int i = 0; i < count; i++) {
                        ticks[i] = buf.readVarLong();
                        factors[i] = buf.readFloat();
                    }
                    return new History(item, now, ticks, factors);
                });

        @Override
        public Type<History> type() {
            return TYPE;
        }
    }

    /** Client to server: send me the contract board (the screen asks when its tab is opened). */
    public record ContractsRequest() implements CustomPacketPayload {
        public static final Type<ContractsRequest> TYPE = payloadType("contracts_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ContractsRequest> CODEC = StreamCodec.unit(new ContractsRequest());

        @Override
        public Type<ContractsRequest> type() {
            return TYPE;
        }
    }

    private static final StreamCodec<ByteBuf, ContractService.Action> ACTION_CODEC =
            ByteBufCodecs.idMapper(i -> ContractService.Action.values()[i], ContractService.Action::ordinal);
    private static final StreamCodec<ByteBuf, MarketService.Result> RESULT_CODEC =
            ByteBufCodecs.VAR_INT.map(i -> MarketService.Result.values()[i], MarketService.Result::ordinal);

    /** Client to server: claim, deliver {@code quantity} units to, or abandon a contract, from the Contracts tab. */
    public record ContractRequest(ContractService.Action action, int id, int quantity) implements CustomPacketPayload {
        public static final Type<ContractRequest> TYPE = payloadType("contract_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ContractRequest> CODEC = StreamCodec.composite(
                ACTION_CODEC, ContractRequest::action,
                ByteBufCodecs.VAR_INT, ContractRequest::id,
                ByteBufCodecs.VAR_INT, ContractRequest::quantity,
                ContractRequest::new);

        @Override
        public Type<ContractRequest> type() {
            return TYPE;
        }
    }

    /**
     * One contract as the receiving player sees it. {@code reward} is what completing it pays now; {@code holder} is
     * the name of whoever claimed it ("" if nobody), and {@code mine} whether that's the receiving player.
     */
    public record ContractLine(int id, ResourceLocation item, int units, int delivered, long reward, long deposit,
                               ContractBoard.Status status, boolean mine, String holder) {
        private static final StreamCodec<ByteBuf, ContractBoard.Status> STATUS_CODEC =
                ByteBufCodecs.idMapper(i -> ContractBoard.Status.values()[i], ContractBoard.Status::ordinal);
        static final StreamCodec<ByteBuf, ContractLine> CODEC = StreamCodec.of(
                (buf, line) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, line.id());
                    ResourceLocation.STREAM_CODEC.encode(buf, line.item());
                    ByteBufCodecs.VAR_INT.encode(buf, line.units());
                    ByteBufCodecs.VAR_INT.encode(buf, line.delivered());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.reward());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.deposit());
                    STATUS_CODEC.encode(buf, line.status());
                    buf.writeBoolean(line.mine());
                    ByteBufCodecs.stringUtf8(16).encode(buf, line.holder());
                },
                buf -> new ContractLine(ByteBufCodecs.VAR_INT.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
                        ByteBufCodecs.VAR_LONG.decode(buf), STATUS_CODEC.decode(buf), buf.readBoolean(),
                        ByteBufCodecs.stringUtf8(16).decode(buf)));

        public int remaining() {
            return units - delivered;
        }
    }

    /**
     * Server to client: the contract board as this player sees it, how many in-game days until it's replaced, and
     * how many contracts the player holds out of the most they may.
     */
    public record Contracts(List<ContractLine> lines, long daysLeft, int held, int maxHeld) implements CustomPacketPayload {
        public static final Type<Contracts> TYPE = payloadType("contracts");
        public static final StreamCodec<RegistryFriendlyByteBuf, Contracts> CODEC = StreamCodec.composite(
                ContractLine.CODEC.apply(ByteBufCodecs.list(64)), Contracts::lines,
                ByteBufCodecs.VAR_LONG, Contracts::daysLeft,
                ByteBufCodecs.VAR_INT, Contracts::held,
                ByteBufCodecs.VAR_INT, Contracts::maxHeld,
                Contracts::new);

        @Override
        public Type<Contracts> type() {
            return TYPE;
        }
    }

    /** Server to client: how this player's last contract request went. */
    public record ContractOutcome(ContractService.Action action, ResourceLocation item, MarketService.Result result, int quantity,
                                  long amount) implements CustomPacketPayload {
        public static final Type<ContractOutcome> TYPE = payloadType("contract_outcome");
        public static final StreamCodec<RegistryFriendlyByteBuf, ContractOutcome> CODEC = StreamCodec.composite(
                ACTION_CODEC, ContractOutcome::action,
                ResourceLocation.STREAM_CODEC, ContractOutcome::item,
                RESULT_CODEC, ContractOutcome::result,
                ByteBufCodecs.VAR_INT, ContractOutcome::quantity,
                ByteBufCodecs.VAR_LONG, ContractOutcome::amount,
                ContractOutcome::new);

        @Override
        public Type<ContractOutcome> type() {
            return TYPE;
        }
    }

    /** Server to client: the outcome of this player's last trade request. */
    public record TradeOutcome(boolean buy, ResourceLocation item, MarketService.Result result, int quantity, long total)
            implements CustomPacketPayload {
        public static final Type<TradeOutcome> TYPE = payloadType("trade_outcome");
        public static final StreamCodec<RegistryFriendlyByteBuf, TradeOutcome> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, TradeOutcome::buy,
                ResourceLocation.STREAM_CODEC, TradeOutcome::item,
                RESULT_CODEC, TradeOutcome::result,
                ByteBufCodecs.VAR_INT, TradeOutcome::quantity,
                ByteBufCodecs.VAR_LONG, TradeOutcome::total,
                TradeOutcome::new);

        @Override
        public Type<TradeOutcome> type() {
            return TYPE;
        }
    }

    // ---- Exchange ----------------------------------------------------------------------------------------------

    private static final StreamCodec<ByteBuf, ExchangeService.Action> EXCHANGE_ACTION_CODEC =
            ByteBufCodecs.idMapper(i -> ExchangeService.Action.values()[i], ExchangeService.Action::ordinal);

    /** Client to server: send me the exchange (the screen asks when its tab opens), and this item's book if given. */
    public record ExchangeRequest(Optional<ResourceLocation> book) implements CustomPacketPayload {
        public static final Type<ExchangeRequest> TYPE = payloadType("exchange_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), ExchangeRequest::book,
                ExchangeRequest::new);

        @Override
        public Type<ExchangeRequest> type() {
            return TYPE;
        }
    }

    /**
     * Client to server, from the Exchange tab: post an order for {@code quantity} of {@code item} at {@code price}
     * each, cancel order {@code orderId}, or take items out of the collection box ({@code item} only, or everything
     * when it's empty).
     */
    public record ExchangeAction(ExchangeService.Action action, Optional<ResourceLocation> item, boolean buy, long price, int quantity,
                                 long orderId) implements CustomPacketPayload {
        public static final Type<ExchangeAction> TYPE = payloadType("exchange_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeAction> CODEC = StreamCodec.composite(
                EXCHANGE_ACTION_CODEC, ExchangeAction::action,
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), ExchangeAction::item,
                ByteBufCodecs.BOOL, ExchangeAction::buy,
                ByteBufCodecs.VAR_LONG, ExchangeAction::price,
                ByteBufCodecs.VAR_INT, ExchangeAction::quantity,
                ByteBufCodecs.VAR_LONG, ExchangeAction::orderId,
                ExchangeAction::new);

        public static ExchangeAction post(ResourceLocation item, boolean buy, long price, int quantity) {
            return new ExchangeAction(ExchangeService.Action.POST, Optional.of(item), buy, price, quantity, 0);
        }

        public static ExchangeAction cancel(long orderId) {
            return new ExchangeAction(ExchangeService.Action.CANCEL, Optional.empty(), false, 0, 0, orderId);
        }

        public static ExchangeAction collect(Optional<ResourceLocation> item) {
            return new ExchangeAction(ExchangeService.Action.COLLECT, item, false, 0, 0, 0);
        }

        @Override
        public Type<ExchangeAction> type() {
            return TYPE;
        }
    }

    private static final StreamCodec<ByteBuf, ExchangeService.Summary> SUMMARY_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, ExchangeService.Summary::item,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::bestAsk,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::askUnits,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::bestBid,
            ByteBufCodecs.VAR_LONG, ExchangeService.Summary::bidUnits,
            ExchangeService.Summary::new);
    private static final StreamCodec<ByteBuf, List<ExchangeService.Summary>> SUMMARIES_CODEC = SUMMARY_CODEC.apply(ByteBufCodecs.list(65_536));

    /** One of the receiving player's open orders. {@code daysLeft} is -1 when orders don't expire. */
    public record OrderLine(long id, ResourceLocation item, boolean buy, long price, int quantity, int filled, long daysLeft) {
        static final StreamCodec<ByteBuf, OrderLine> CODEC = StreamCodec.of(
                (buf, line) -> {
                    ByteBufCodecs.VAR_LONG.encode(buf, line.id());
                    ResourceLocation.STREAM_CODEC.encode(buf, line.item());
                    buf.writeBoolean(line.buy());
                    ByteBufCodecs.VAR_LONG.encode(buf, line.price());
                    ByteBufCodecs.VAR_INT.encode(buf, line.quantity());
                    ByteBufCodecs.VAR_INT.encode(buf, line.filled());
                    buf.writeLong(line.daysLeft());
                },
                buf -> new OrderLine(ByteBufCodecs.VAR_LONG.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf), buf.readBoolean(),
                        ByteBufCodecs.VAR_LONG.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                        buf.readLong()));

        public int remaining() {
            return quantity - filled;
        }
    }

    private static final StreamCodec<ByteBuf, List<OrderLine>> ORDERS_CODEC = OrderLine.CODEC.apply(ByteBufCodecs.list(1000));

    /** Items waiting in the collection box. */
    public record BoxLine(ResourceLocation item, long count) {
        static final StreamCodec<ByteBuf, BoxLine> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, BoxLine::item,
                ByteBufCodecs.VAR_LONG, BoxLine::count,
                BoxLine::new);
    }

    private static final StreamCodec<ByteBuf, List<BoxLine>> BOX_CODEC = BoxLine.CODEC.apply(ByteBufCodecs.list(65_536));

    /**
     * Server to client: the exchange as this player sees it: the best prices of every item with orders, their own
     * open orders and collection box, and the rules (open orders allowed, largest order, tax, expiry in days or 0).
     */
    public record Exchange(List<ExchangeService.Summary> summaries, List<OrderLine> orders, List<BoxLine> box, int maxOrders,
                           int maxOrderSize, double tax, int expiryDays) implements CustomPacketPayload {
        public static final Type<Exchange> TYPE = payloadType("exchange");
        // Written out by hand: StreamCodec.composite takes at most six fields.
        public static final StreamCodec<RegistryFriendlyByteBuf, Exchange> CODEC = StreamCodec.of(
                (buf, exchange) -> {
                    SUMMARIES_CODEC.encode(buf, exchange.summaries());
                    ORDERS_CODEC.encode(buf, exchange.orders());
                    BOX_CODEC.encode(buf, exchange.box());
                    ByteBufCodecs.VAR_INT.encode(buf, exchange.maxOrders());
                    ByteBufCodecs.VAR_INT.encode(buf, exchange.maxOrderSize());
                    buf.writeDouble(exchange.tax());
                    ByteBufCodecs.VAR_INT.encode(buf, exchange.expiryDays());
                },
                buf -> new Exchange(SUMMARIES_CODEC.decode(buf), ORDERS_CODEC.decode(buf), BOX_CODEC.decode(buf),
                        ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), buf.readDouble(), ByteBufCodecs.VAR_INT.decode(buf)));

        /** How many items wait in the box in all. */
        public long boxed() {
            return box.stream().mapToLong(BoxLine::count).sum();
        }

        @Override
        public Type<Exchange> type() {
            return TYPE;
        }
    }

    private static final StreamCodec<ByteBuf, ExchangeService.Level> LEVEL_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, ExchangeService.Level::price,
            ByteBufCodecs.VAR_LONG, ExchangeService.Level::quantity,
            ByteBufCodecs.VAR_LONG, ExchangeService.Level::mine,
            ExchangeService.Level::new);

    /** Server to client: one item's book, best prices first, sent when the screen asks and whenever it changes. */
    public record ExchangeBook(ResourceLocation item, List<ExchangeService.Level> asks, List<ExchangeService.Level> bids)
            implements CustomPacketPayload {
        /** The most prices sent for each side. */
        public static final int DEPTH = 8;
        public static final Type<ExchangeBook> TYPE = payloadType("exchange_book");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeBook> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, ExchangeBook::item,
                LEVEL_CODEC.apply(ByteBufCodecs.list(DEPTH)), ExchangeBook::asks,
                LEVEL_CODEC.apply(ByteBufCodecs.list(DEPTH)), ExchangeBook::bids,
                ExchangeBook::new);

        @Override
        public Type<ExchangeBook> type() {
            return TYPE;
        }
    }

    /** Server to client: how this player's last exchange request went; see {@link ExchangeService.Outcome}. */
    public record ExchangeOutcome(ExchangeService.Action action, ResourceLocation item, MarketService.Result result, int quantity,
                                  int filled, long amount) implements CustomPacketPayload {
        public static final Type<ExchangeOutcome> TYPE = payloadType("exchange_outcome");
        public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeOutcome> CODEC = StreamCodec.composite(
                EXCHANGE_ACTION_CODEC, ExchangeOutcome::action,
                ResourceLocation.STREAM_CODEC, ExchangeOutcome::item,
                RESULT_CODEC, ExchangeOutcome::result,
                ByteBufCodecs.VAR_INT, ExchangeOutcome::quantity,
                ByteBufCodecs.VAR_INT, ExchangeOutcome::filled,
                ByteBufCodecs.VAR_LONG, ExchangeOutcome::amount,
                ExchangeOutcome::new);

        @Override
        public Type<ExchangeOutcome> type() {
            return TYPE;
        }
    }
}
