package dynamiceconomy;

import com.mojang.logging.LogUtils;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.command.MarketCommand;
import dynamiceconomy.compat.NumismaticsBridge;
import dynamiceconomy.block.MarketBlocks;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketNetwork;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.PriceModel;
import net.minecraft.resources.ResourceLocation;
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
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Map;

@Mod(DynamicEconomy.MOD_ID)
public final class DynamicEconomy {
    public static final String MOD_ID = "dynamic_economy";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DynamicEconomy(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, MarketConfig.SPEC);
        MarketMenu.MENUS.register(modBus);
        MarketBlocks.register(modBus);
        modBus.addListener(MarketNetwork::register);

        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(MarketCatalog.INSTANCE));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                MarketCommand.register(event.getDispatcher(), event.getBuildContext()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            boolean recoveryStep = MarketStockData.now(event.getServer()) % MarketConfig.intervalTicks() == 0;
            if (recoveryStep) {
                MarketService.sampleHistory(event.getServer());
            }
            MarketSync.tick(event.getServer());
            if (recoveryStep) {
                // After the sync, so players are sent the step that brought each item back to 1.0.
                MarketService.pruneRecovered(event.getServer());
            }
        });

        // Once the world is up its admin edits can be applied; again after every /reload, since tags may change.
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            if (!NumismaticsBridge.isAvailable()) {
                LOGGER.error("Numismatics bank API not found; the market will refuse all trades");
            }
            catalogReloaded(event.getServer());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> MarketCatalog.INSTANCE.setOverrides(MarketOverrides.Snapshot.EMPTY));
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
            if (event.getPlayer() == null) {
                catalogReloaded(event.getPlayerList().getServer());
            } else {
                // A joining player gets the catalog so item tooltips can show what the market pays.
                MarketSync.sendCatalog(event.getPlayer());
            }
        });
    }

    /**
     * After the catalog files or the admin edits change: rebuild the live catalog, give new items their start stock,
     * and send every player the new prices. Returns the admin edits that couldn't be applied.
     */
    public static Map<ResourceLocation, String> catalogReloaded(MinecraftServer server) {
        Map<ResourceLocation, String> problems = MarketCatalog.INSTANCE.setOverrides(MarketOverrides.get(server).snapshot());
        MarketStockData.get(server).seed(MarketCatalog.INSTANCE.entries());
        warnAboutArbitrage();
        MarketSync.broadcastSnapshot(server);
        return problems;
    }

    /** Checked once config and catalog are both loaded, since entries inherit config defaults. */
    private static void warnAboutArbitrage() {
        for (CatalogEntry entry : MarketCatalog.INSTANCE.entries()) {
            PriceModel.arbitrageRisk(PriceModel.inputs(entry)).ifPresent(reason -> LOGGER.warn(
                    "Market item {}: buying and reselling it may make a profit ({})", entry.itemId(), reason));
        }
    }
}
