package dynamiceconomy;

import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.command.MarketCommand;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.data.MarketStockData;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

@Mod(DynamicEconomy.MOD_ID)
public final class DynamicEconomy {
    public static final String MOD_ID = "dynamic_economy";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DynamicEconomy(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, MarketConfig.SPEC);

        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(MarketCatalog.INSTANCE));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                MarketCommand.register(event.getDispatcher(), event.getBuildContext()));

        // Seed start_stock for newly listed items once the world is up, and again after every /reload.
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            if (!NumismaticsBridge.isAvailable()) {
                LOGGER.error("Numismatics bank API not found; the market will refuse all trades");
            }
            MarketStockData.get(event.getServer()).seed(MarketCatalog.INSTANCE.entries());
        });
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
            if (event.getPlayer() == null) {
                MarketStockData.get(event.getPlayerList().getServer()).seed(MarketCatalog.INSTANCE.entries());
            }
        });
    }
}
