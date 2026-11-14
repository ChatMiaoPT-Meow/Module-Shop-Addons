package cn.autoforged.module_shop_stock.client;

import cn.autoforged.module_shop.api.client.ShopModules;
import cn.autoforged.module_shop_stock.network.StockMarketNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

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
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            StockMarketState.reset();
            StockMarketNetwork.forgetClient();
        });
        if (StockRealTour.enabled()) {
            MinecraftForge.EVENT_BUS.addListener(StockRealTour::onClientTick);
        }
    }

    public static void handle(CompoundTag tag) {
        StockMarketState.apply(tag);
    }
}
