package dynamiceconomy.network;

import dynamiceconomy.client.ClientMarketState;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class MarketNetwork {
    private static final String PROTOCOL_VERSION = "2";

    private MarketNetwork() {
    }

    /**
     * Handlers run on the main thread (the registrar default), which the market service requires. The clientbound
     * handlers are lambdas rather than method references so a dedicated server never loads the client classes.
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToServer(MarketPayloads.TradeRequest.TYPE, MarketPayloads.TradeRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                MarketSync.handleTradeRequest(payload, player);
            }
        });

        registrar.playToClient(MarketPayloads.Snapshot.TYPE, MarketPayloads.Snapshot.CODEC,
                (payload, context) -> ClientMarketState.applySnapshot(payload));
        registrar.playToClient(MarketPayloads.RowUpdate.TYPE, MarketPayloads.RowUpdate.CODEC,
                (payload, context) -> ClientMarketState.applyRow(payload.row()));
        registrar.playToClient(MarketPayloads.PlayerState.TYPE, MarketPayloads.PlayerState.CODEC,
                (payload, context) -> ClientMarketState.applyPlayerState(payload));
        registrar.playToClient(MarketPayloads.TradeOutcome.TYPE, MarketPayloads.TradeOutcome.CODEC,
                (payload, context) -> ClientMarketState.applyOutcome(payload));
    }
}
