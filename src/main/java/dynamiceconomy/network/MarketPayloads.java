package dynamiceconomy.network;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.service.MarketService;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Every packet the market sends. Prices and totals are in spurs. */
public final class MarketPayloads {
    private MarketPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(DynamicEconomy.MOD_ID, path));
    }

    /** One listed item as every player sees it. Prices are per unit. */
    public record Row(ResourceLocation item, long sellPrice, long buyPrice, boolean canSell, boolean canBuy, long stock) {
        public static final StreamCodec<ByteBuf, Row> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, Row::item,
                ByteBufCodecs.VAR_LONG, Row::sellPrice,
                ByteBufCodecs.VAR_LONG, Row::buyPrice,
                ByteBufCodecs.BOOL, Row::canSell,
                ByteBufCodecs.BOOL, Row::canBuy,
                ByteBufCodecs.VAR_LONG, Row::stock,
                Row::new);
    }

    /** Client to server: buy or sell from the open market screen at the unit price the player was shown. */
    public record TradeRequest(boolean buy, ResourceLocation item, int quantity, long quotedUnitPrice) implements CustomPacketPayload {
        public static final Type<TradeRequest> TYPE = payloadType("trade_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, TradeRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, TradeRequest::buy,
                ResourceLocation.STREAM_CODEC, TradeRequest::item,
                ByteBufCodecs.VAR_INT, TradeRequest::quantity,
                ByteBufCodecs.VAR_LONG, TradeRequest::quotedUnitPrice,
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
