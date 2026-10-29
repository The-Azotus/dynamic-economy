package dynamiceconomy.network;

import dynamiceconomy.client.ClientMarketState;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class MarketNetwork {
    private static final String PROTOCOL_VERSION = "9";

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

        registrar.playToServer(MarketPayloads.BankRequest.TYPE, MarketPayloads.BankRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                MarketSync.handleBankRequest(payload, player);
            }
        });

        registrar.playToServer(MarketPayloads.HistoryRequest.TYPE, MarketPayloads.HistoryRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                MarketSync.handleHistoryRequest(payload, player);
            }
        });

        registrar.playToServer(MarketPayloads.TradesRequest.TYPE, MarketPayloads.TradesRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                MarketSync.handleTradesRequest(player);
            }
        });

        registrar.playToServer(MarketPayloads.LeaderboardRequest.TYPE, MarketPayloads.LeaderboardRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                MarketSync.handleLeaderboardRequest(player);
            }
        });

        registrar.playToClient(MarketPayloads.Leaderboard.TYPE, MarketPayloads.Leaderboard.CODEC,
                (payload, context) -> ClientMarketState.applyLeaderboard(payload));
        registrar.playToClient(MarketPayloads.History.TYPE, MarketPayloads.History.CODEC,
                (payload, context) -> ClientMarketState.applyHistory(payload));
        registrar.playToClient(MarketPayloads.Trades.TYPE, MarketPayloads.Trades.CODEC,
                (payload, context) -> ClientMarketState.applyTrades(payload));
        registrar.playToClient(MarketPayloads.Snapshot.TYPE, MarketPayloads.Snapshot.CODEC,
                (payload, context) -> ClientMarketState.applySnapshot(payload));
        registrar.playToClient(MarketPayloads.RowUpdate.TYPE, MarketPayloads.RowUpdate.CODEC,
                (payload, context) -> payload.rows().forEach(ClientMarketState::applyRow));
        registrar.playToClient(MarketPayloads.PlayerState.TYPE, MarketPayloads.PlayerState.CODEC,
                (payload, context) -> ClientMarketState.applyPlayerState(payload));
        registrar.playToClient(MarketPayloads.BankOutcome.TYPE, MarketPayloads.BankOutcome.CODEC,
                (payload, context) -> ClientMarketState.applyBankOutcome(payload));
        registrar.playToClient(MarketPayloads.TradeOutcome.TYPE, MarketPayloads.TradeOutcome.CODEC,
                (payload, context) -> ClientMarketState.applyOutcome(payload));
    }
}
