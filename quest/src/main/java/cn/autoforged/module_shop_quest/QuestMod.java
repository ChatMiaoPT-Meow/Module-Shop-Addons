package cn.autoforged.module_shop_quest;

import cn.autoforged.module_shop.api.ApiVersion;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import cn.autoforged.module_shop_quest.config.QuestTomlConfig;
import cn.autoforged.module_shop_quest.network.QuestNetwork;
import cn.autoforged.module_shop_quest.server.OnlineTimeService;
import cn.autoforged.module_shop_quest.server.QuestCommands;
import cn.autoforged.module_shop_quest.server.QuestEventBridge;
import cn.autoforged.module_shop_quest.server.QuestManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 星途纪行（Module Shop: Quest & Pass）主入口类。
 * <p>
 * 严格遵循物理隔离规范：顶层绝不 import 客户端 Screen、Skija Canvas 或 AtomLib 类，
 * 确保专用服务器（Dedicated Server）零客户端依赖安全启动。
 */
@Mod(QuestMod.MODID)
public final class QuestMod {
    public static final String MODID = "module_shop_quest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public QuestMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, QuestTomlConfig.SPEC);

        modBus.addListener(this::onCommonSetup);
        modBus.addListener(QuestNetwork::register);

        IEventBus gameBus = NeoForge.EVENT_BUS;
        gameBus.addListener(this::onServerStarted);
        gameBus.addListener(this::onServerStopping);
        gameBus.addListener(this::onServerTick);
        gameBus.addListener(this::onRegisterCommands);
        gameBus.addListener(this::onPlayerLogin);
        gameBus.addListener(this::onPlayerLogout);
        QuestEventBridge.registerNeoForgeListeners(gameBus);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            cn.autoforged.module_shop_quest.client.QuestClient.init(modBus);
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            String apiDesc = ApiVersion.describe();
            LOGGER.info("[{}] 已连接主模组公共 API：{}", MODID, apiDesc);
            QuestEventBridge.registerShopApiListeners();
        });
    }

    private void onServerStarted(ServerStartedEvent event) {
        QuestDataConfig.loadOrCreateAll(null);
        OnlineTimeService.resetRuntime();
        QuestEventBridge.resetRuntime();
        QuestManager.ensureSeasonInitialized(event.getServer());
        LOGGER.info("[{}] 服务端配置与赛季状态初始化完成（当前赛季：{}）",
                MODID, QuestDataConfig.seasonPass().seasonId);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        OnlineTimeService.flushAll(event.getServer());
        OnlineTimeService.resetRuntime();
        QuestEventBridge.resetRuntime();
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (!QuestTomlConfig.ENABLED.get()) {
            return;
        }
        OnlineTimeService.onServerTick(event.getServer());
        QuestEventBridge.onServerTick(event.getServer());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        QuestCommands.register(event.getDispatcher());
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            OnlineTimeService.onPlayerLogin(player);
            QuestEventBridge.onPlayerLogin(player);
            QuestManager.syncFullSnapshot(player);
        }
    }

    private void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            OnlineTimeService.onPlayerLogout(player);
            QuestEventBridge.onPlayerLogout(player);
        }
    }
}
