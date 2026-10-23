package dynamiceconomy.client;

import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.menu.MarketMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@Mod(value = DynamicEconomy.MOD_ID, dist = Dist.CLIENT)
public final class DynamicEconomyClient {
    public DynamicEconomyClient(IEventBus modBus) {
        modBus.addListener((RegisterMenuScreensEvent event) -> event.register(MarketMenu.TYPE.get(), MarketScreen::new));
    }
}
