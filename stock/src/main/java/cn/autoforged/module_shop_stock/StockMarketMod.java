package cn.autoforged.module_shop_stock;

import cn.autoforged.module_shop_stock.network.StockMarketNetwork;
import cn.autoforged.module_shop_stock.server.MarketEngine;
import cn.autoforged.module_shop_stock.server.NewsEngine;
import cn.autoforged.module_shop_stock.server.StockSeeder;
import cn.autoforged.module_shop_stock.server.StockServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 模组商店附属 · 虚拟股市（v2.2 订单簿做市撮合 + 多空双向交易 + 24股/120新闻版）。 */
@Mod(StockMarketMod.MODID)
public class StockMarketMod {
    public static final String MODID = "module_shop_stock";

    public StockMarketMod(IEventBus modBus, ModContainer container) {
        checkApiVersion(cn.autoforged.module_shop.api.ApiVersion.describe());
        container.registerConfig(ModConfig.Type.SERVER, StockMarketConfig.SPEC);
        modBus.addListener(StockMarketNetwork::register);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> MarketEngine.tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> StockSeeder.backupLegacy(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> StockSeeder.load(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            MarketEngine.reset();
            NewsEngine.reset();
            StockServer.clearSubscribers();
        });
        if (FMLEnvironment.dist == Dist.CLIENT) {
            cn.autoforged.module_shop_stock.client.StockMarketClient.init(modBus);
        }
    }

    static void checkApiVersion(String described) {
        String version = described == null ? "" : described.substring(described.lastIndexOf(' ') + 1);
        String[] parts = version.split("\\.");
        int major = parts.length > 0 ? parseInt(parts[0]) : -1;
        int minor = parts.length > 1 ? parseInt(parts[1]) : -1;
        if (major != 1 || minor < 1) {
            throw new IllegalStateException("module_shop_stock 需要模组商店公共 API 1.1+，当前：" + described);
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
