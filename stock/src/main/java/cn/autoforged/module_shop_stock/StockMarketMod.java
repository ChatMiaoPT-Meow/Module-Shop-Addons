package cn.autoforged.module_shop_stock;

import cn.autoforged.module_shop_stock.network.StockMarketNetwork;
import cn.autoforged.module_shop_stock.server.MarketEngine;
import cn.autoforged.module_shop_stock.server.NewsEngine;
import cn.autoforged.module_shop_stock.server.StockSeeder;
import cn.autoforged.module_shop_stock.server.StockServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** 模组商店附属 · 虚拟股市（v2.4 Forge 1.20.1 订单簿做市撮合 + 多空双向交易 + 24股/120新闻版）。 */
@Mod(StockMarketMod.MODID)
public class StockMarketMod {
    public static final String MODID = "module_shop_stock";

    public StockMarketMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        checkApiVersion(cn.autoforged.module_shop.api.ApiVersion.describe());
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, StockMarketConfig.SPEC);
        StockMarketNetwork.register();
        MinecraftForge.EVENT_BUS.addListener(StockMarketMod::onServerTick);
        MinecraftForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> StockSeeder.backupLegacy(event.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent event) -> StockSeeder.load(event.getServer()));
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                StockMarketNetwork.forgetPlayer(player.getUUID());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            MarketEngine.reset();
            NewsEngine.reset();
            StockServer.clearSubscribers();
            StockMarketNetwork.clearAssemblers();
        });
        if (FMLEnvironment.dist == Dist.CLIENT) {
            cn.autoforged.module_shop_stock.client.StockMarketClient.init(modBus);
        }
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MarketEngine.tick(event.getServer());
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
