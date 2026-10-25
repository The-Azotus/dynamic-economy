package dynamiceconomy.client;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.menu.MarketMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = DynamicEconomy.MOD_ID, dist = Dist.CLIENT)
public final class DynamicEconomyClient {
    public DynamicEconomyClient(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        modBus.addListener((RegisterMenuScreensEvent event) -> event.register(MarketMenu.TYPE.get(), MarketScreen::new));
        NeoForge.EVENT_BUS.addListener(SellPriceTooltip::onTooltip);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientMarketState.reset());
    }
}
