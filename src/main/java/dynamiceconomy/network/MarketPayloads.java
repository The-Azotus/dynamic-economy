package dynamiceconomy.network;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.PriceModel;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** Every packet the market sends. Prices and totals are in spurs. */
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

    /** Server to client: the whole catalog, sent when the screen opens and after a catalog reload. */
    public record Snapshot(List<Row> rows) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = payloadType("snapshot");
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.composite(
                Row.CODEC.apply(ByteBufCodecs.list()), Snapshot::rows,
                Snapshot::new);

        @Override
        public Type<Snapshot> type() {
            return TYPE;
        }
    }

    /** Server to client: one item's stock or price changed. Sent to every player with the market open. */
    public record RowUpdate(Row row) implements CustomPacketPayload {
        public static final Type<RowUpdate> TYPE = payloadType("row_update");
        public static final StreamCodec<RegistryFriendlyByteBuf, RowUpdate> CODEC = StreamCodec.composite(
                Row.CODEC, RowUpdate::row,
                RowUpdate::new);

        @Override
        public Type<RowUpdate> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: this player's balance and how many tradeable copies of each listed item they hold. The client
     * cannot work this out itself: vanilla does not sync the inventory while a slotless menu is open.
     */
    public record PlayerState(int balance, Map<ResourceLocation, Integer> held) implements CustomPacketPayload {
        public static final Type<PlayerState> TYPE = payloadType("player_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, PlayerState> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, PlayerState::balance,
                ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT), PlayerState::held,
                PlayerState::new);

        @Override
        public Type<PlayerState> type() {
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

    /** Server to client: the outcome of this player's last trade request. */
    public record TradeOutcome(boolean buy, ResourceLocation item, MarketService.Result result, int quantity, long total)
            implements CustomPacketPayload {
        public static final Type<TradeOutcome> TYPE = payloadType("trade_outcome");
        private static final StreamCodec<ByteBuf, MarketService.Result> RESULT_CODEC =
                ByteBufCodecs.VAR_INT.map(i -> MarketService.Result.values()[i], MarketService.Result::ordinal);
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
}
