package cn.autoforged.module_shop_stock.client;

import cn.autoforged.module_shop.api.client.ShopModules;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** 客户端入口：注册商店内容页、原版回退界面、网络回包与可选实机自动截屏巡检器。 */
public final class StockMarketClient {
    public static final String MODULE_ID = "module_shop_stock";

    private StockMarketClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() ->
                ShopModules.registerPage(MODULE_ID,
                        () -> Component.translatable("tab.module_shop_stock.market"),
                        StockMarketPage::new,
                        StockMarketFallbackScreen::new)));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> StockMarketState.reset());
        if (StockRealTour.enabled()) {
            NeoForge.EVENT_BUS.addListener(StockRealTour::onClientTick);
        }
    }

    public static void handle(CompoundTag tag) {
        StockMarketState.apply(tag);
    }
}
