package dynamiceconomy;

import com.mojang.logging.LogUtils;
import dynamiceconomy.bank.BankKind;
import dynamiceconomy.bank.OwnBank;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.coin.Coins;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.command.BankCommand;
import dynamiceconomy.command.MarketCommand;
import dynamiceconomy.compat.FtbTeamsBridge;
import dynamiceconomy.compat.StagesBridge;
import dynamiceconomy.block.MarketBlocks;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.menu.MarketMenu;
import dynamiceconomy.network.MarketNetwork;
import dynamiceconomy.network.MarketSync;
import dynamiceconomy.service.ContractService;
import dynamiceconomy.service.MarketService;
import dynamiceconomy.service.PriceModel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
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
        Coins.register(modBus);
        modBus.addListener(MarketNetwork::register);
        modBus.addListener(DynamicEconomy::addExampleCatalog);
        modBus.addListener(DynamicEconomy::addDefaultPrices);

        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(MarketCatalog.INSTANCE));
        // With ProgressiveStages, what a player can see changes with their stages.
        StagesBridge.listen(MarketSync::stagesChanged);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> {
            MarketCommand.register(event.getDispatcher(), event.getBuildContext());
            BankCommand.register(event.getDispatcher());
        });
        // A deleted FTB Teams party's account goes to its owner, so no money is lost with it.
        FtbTeamsBridge.onPartyDeleted(team -> {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null && WorldBank.kind(server) == BankKind.OWN) {
                long paid = OwnBank.get(server).closeTeam(team.id(), team.owner());
                if (paid > 0) {
                    LOGGER.info("FTB Teams party {} was deleted; its {} bits went to its owner {}", team.name(), paid, team.owner());
                }
            }
        });
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
            if (ContractService.tick(event.getServer())) {
                MarketSync.broadcastContracts(event.getServer());
            }
        });
        // How a held contract ended while its holder was away.
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ContractService.loggedIn(player);
            }
        });

        // Once the world is up its admin edits can be applied; again after every /reload, since tags may change.
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            // Records the world's bank on its first start, before anything can pay into one. Only our own bank is
            // always there.
            if (!WorldBank.available(event.getServer())) {
                LOGGER.error("This world uses Numismatics' bank, but Numismatics isn't installed or its bank API doesn't "
                        + "match this mod; the market will refuse all trades");
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
                    "Market item {}: a round trip may make a profit ({})", entry.itemId(), reason));
        }
    }

    /** The ID admins enable the example catalog with: {@code /datapack enable "mod/dynamic_economy:example_catalog"}. */
    public static final String EXAMPLE_PACK_ID = "mod/" + MOD_ID + ":example_catalog";

    /**
     * The five-item example catalog ships as an optional data pack, off unless an admin enables it, so placeholder
     * prices never appear on a server by surprise. Minecraft never switches on packs from the "feature" source by
     * itself. (The GameTest server enables every available pack, so the tests still see the example items.)
     */
    private static void addExampleCatalog(AddPackFindersEvent event) {
        event.addPackFinders(ResourceLocation.fromNamespaceAndPath(MOD_ID, "example_catalog"), PackType.SERVER_DATA,
                Component.literal("Dynamic-Economy example catalog"), PackSource.FEATURE, false, Pack.Position.TOP);
    }

    /** The ID admins turn the built-in prices off with: {@code /datapack disable "mod/dynamic_economy:default_prices"}. */
    public static final String DEFAULT_PRICES_PACK_ID = "mod/" + MOD_ID + ":default_prices";
    /** Set to {@code true} to leave the built-in prices out entirely. The GameTest server does, so tests see a small catalog. */
    public static final String SKIP_DEFAULT_PRICES = "dynamiceconomy.skipDefaultPrices";

    /**
     * Built-in prices for vanilla and popular mods, one file per mod, each loaded only if its mod is installed. The
     * "built-in" source switches it on by itself, in new and existing worlds, until an admin disables it. It goes to
     * the bottom of the pack order, so any other catalog file overrides its prices.
     */
    private static void addDefaultPrices(AddPackFindersEvent event) {
        if (Boolean.getBoolean(SKIP_DEFAULT_PRICES)) {
            return;
        }
        event.addPackFinders(ResourceLocation.fromNamespaceAndPath(MOD_ID, "default_prices"), PackType.SERVER_DATA,
                Component.literal("Dynamic-Economy default prices"), PackSource.BUILT_IN, false, Pack.Position.BOTTOM);
    }
}
