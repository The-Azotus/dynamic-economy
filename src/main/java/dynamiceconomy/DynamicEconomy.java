package dynamiceconomy;

import com.mojang.logging.LogUtils;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.command.MarketCommand;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketNetwork;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.PriceModel;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(DynamicEconomy.MOD_ID)
public final class DynamicEconomy {
    public static final String MOD_ID = "dynamic_economy";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DynamicEconomy(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, MarketConfig.SPEC);
        MarketMenu.MENUS.register(modBus);
        modBus.addListener(MarketNetwork::register);

        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(MarketCatalog.INSTANCE));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                MarketCommand.register(event.getDispatcher(), event.getBuildContext()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            if (MarketStockData.now(event.getServer()) % MarketConfig.intervalTicks() == 0) {
                MarketService.sampleHistory(event.getServer());
            }
            MarketSync.tick(event.getServer());
        });

        // Seed start_stock for newly listed items once the world is up, and again after every /reload.
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            if (!NumismaticsBridge.isAvailable()) {
                LOGGER.error("Numismatics bank API not found; the market will refuse all trades");
            }
            MarketStockData.get(event.getServer()).seed(MarketCatalog.INSTANCE.entries());
            warnAboutArbitrage();
        });
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
            if (event.getPlayer() == null) {
                catalogReloaded(event.getPlayerList().getServer());
            }
        });
    }

    /** After the catalog is re-read: give new items their start stock and refresh every open market screen. */
    public static void catalogReloaded(MinecraftServer server) {
        MarketStockData.get(server).seed(MarketCatalog.INSTANCE.entries());
        warnAboutArbitrage();
        MarketSync.broadcastSnapshot(server);
    }

    /** Checked once config and catalog are both loaded, since entries inherit config defaults. */
    private static void warnAboutArbitrage() {
        for (CatalogEntry entry : MarketCatalog.INSTANCE.entries()) {
            PriceModel.arbitrageRisk(PriceModel.inputs(entry)).ifPresent(reason -> LOGGER.warn(
                    "Market item {}: buying and reselling it may make a profit ({})", entry.itemId(), reason));
        }
    }
}
