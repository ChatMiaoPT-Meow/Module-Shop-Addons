package com.atoms.Bshop.blind;

import com.atoms.Bshop.blind.network.BlindAuctionNetwork;
import com.atoms.Bshop.blind.server.BlindVaultServer;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** 模组商店附属 · 遗迹盲仓（NeoForge 1.21.1 移植版，原 Forge 1.20.1 v3.2）。 */
@Mod(BlindAuctionMod.MODID)
public class BlindAuctionMod {
    public static final String MODID = "module_shop_blind";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BlindAuctionMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, BlindAuctionConfig.COMMON_SPEC);
        modBus.addListener(BlindAuctionNetwork::register);
        NeoForge.EVENT_BUS.register(new BlindVaultServer());
        if (FMLEnvironment.dist == Dist.CLIENT) {
            com.atoms.Bshop.blind.client.BlindAuctionClient.init(modBus);
        }
    }
}
