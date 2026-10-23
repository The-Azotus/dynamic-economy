package dynamiceconomy.block;

import dynamiceconomy.DynamicEconomy;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class MarketBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DynamicEconomy.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DynamicEconomy.MOD_ID);

    public static final DeferredBlock<MarketTerminalBlock> MARKET_TERMINAL = BLOCKS.register("market_terminal",
            () -> new MarketTerminalBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.5f, 6.0f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)));
    public static final DeferredItem<BlockItem> MARKET_TERMINAL_ITEM = ITEMS.registerSimpleBlockItem(MARKET_TERMINAL);

    private MarketBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                event.accept(MARKET_TERMINAL_ITEM);
            }
        });
    }
}
