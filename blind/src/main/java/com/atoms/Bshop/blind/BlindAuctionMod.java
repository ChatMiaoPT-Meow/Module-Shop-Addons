package com.atoms.Bshop.blind;

import com.atoms.Bshop.blind.network.BlindAuctionNetwork;
import com.atoms.Bshop.blind.server.BlindVaultServer;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/** 模组商店附属 · 遗迹盲仓（Forge 1.20.1 移植版 v3.6.0）。 */
@Mod(BlindAuctionMod.MODID)
public class BlindAuctionMod {
    public static final String MODID = "module_shop_blind";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BlindAuctionMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BlindAuctionConfig.COMMON_SPEC);
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(BlindAuctionNetwork::register));
        MinecraftForge.EVENT_BUS.register(new BlindVaultServer());
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                com.atoms.Bshop.blind.client.BlindAuctionClient.init(modBus));
    }
}
