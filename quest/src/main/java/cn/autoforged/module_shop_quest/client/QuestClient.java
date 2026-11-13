package cn.autoforged.module_shop_quest.client;

import cn.autoforged.module_shop.api.client.ShopModules;
import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.client.view.QuestAddonPage;
import cn.autoforged.module_shop_quest.config.ConfigModels.OnlineRewardsConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestPoolsConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.SeasonPassConfig;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import cn.autoforged.module_shop_quest.config.QuestTomlConfig;
import cn.autoforged.module_shop_quest.network.QuestNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端引导、侧栏页面挂载、网络收发与 UI 音效辅助类。
 */
public final class QuestClient {
    private static long lastUiPingMs = 0L;

    private QuestClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(QuestClient::onClientSetup);
        NeoForge.EVENT_BUS.addListener(QuestClient::onLoggingOut);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ShopModules.registerPage(
                QuestMod.MODID,
                QuestClient::sidebarTitle,
                QuestAddonPage::new,
                QuestFallbackScreen::new
        ));
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        QuestClientState.reset();
    }

    public static Component sidebarTitle() {
        try {
            String custom = QuestTomlConfig.SIDEBAR_CUSTOM_NAME.get();
            if (custom != null && !custom.isBlank()) {
                return Component.literal(custom.trim());
            }
        } catch (IllegalStateException ignored) {
        }
        return Component.translatable("gui.module_shop_quest.sidebar_title");
    }

    // ================================================================ S2C 网络包分发

    public static void handlePacket(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        String type = tag.getString("type");
        switch (type) {
            case "snapshot" -> QuestClientState.applySnapshot(tag.getCompound("snapshot"));
            case "online_delta" -> QuestClientState.applyOnlineDelta(tag);
            case "admin_config" -> QuestClientState.applyAdminConfig(
                    tag.getString("passJson"),
                    tag.getString("poolsJson"),
                    tag.getString("onlineJson")
            );
            case "action_result" -> {
                boolean ok = tag.getBoolean("ok");
                String msgKey = tag.getString("message");
                String sound = tag.getString("sound");
                if (tag.contains("snapshot")) {
                    QuestClientState.applySnapshot(tag.getCompound("snapshot"));
                }
                if (!msgKey.isBlank()) {
                    String localized = Component.translatable(msgKey).getString();
                    QuestClientState.postBanner(ok, localized);
                }
                switch (sound) {
                    case "unlock" -> playUnlockSound();
                    case "claim" -> playClaimSound();
                    case "error" -> playUiSound(SoundEvents.WOODEN_BUTTON_CLICK_OFF, 0.85F, 0.32F);
                    default -> {
                    }
                }
            }
            default -> {
            }
        }
    }

    // ================================================================ C2S 请求封装

    public static void requestSync() {
        CompoundTag t = new CompoundTag();
        t.putString("action", "sync");
        QuestNetwork.sendToServer(t);
    }

    public static void pingUiActivityThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastUiPingMs >= 5000L) {
            lastUiPingMs = now;
            CompoundTag t = new CompoundTag();
            t.putString("action", "ui_ping");
            QuestNetwork.sendToServer(t);
        }
    }

    public static void sendClaimPass(int level, boolean premium, boolean prevSeason) {
        playClickSound(1.20F);
        CompoundTag t = new CompoundTag();
        t.putString("action", "claim_pass");
        t.putInt("level", level);
        t.putBoolean("premium", premium);
        t.putBoolean("prevSeason", prevSeason);
        QuestNetwork.sendToServer(t);
    }

    public static void sendClaimQuest(String period, String questId) {
        playClickSound(1.20F);
        CompoundTag t = new CompoundTag();
        t.putString("action", "claim_quest");
        t.putString("period", period);
        t.putString("questId", questId);
        QuestNetwork.sendToServer(t);
    }

    public static void sendClaimOnline(String period, String tierId) {
        playClickSound(1.20F);
        CompoundTag t = new CompoundTag();
        t.putString("action", "claim_online");
        t.putString("period", period);
        t.putString("tierId", tierId);
        QuestNetwork.sendToServer(t);
    }

    public static void sendClaimAll() {
        playClickSound(1.25F);
        CompoundTag t = new CompoundTag();
        t.putString("action", "claim_all");
        QuestNetwork.sendToServer(t);
    }

    public static void sendUnlockPremium() {
        playClickSound(1.25F);
        CompoundTag t = new CompoundTag();
        t.putString("action", "unlock_premium");
        QuestNetwork.sendToServer(t);
    }

    public static void sendUnlockDeluxe() {
        playClickSound(1.28F);
        CompoundTag t = new CompoundTag();
        t.putString("action", "unlock_deluxe");
        QuestNetwork.sendToServer(t);
    }

    public static void sendBuyLevels(int count) {
        playClickSound(1.22F);
        CompoundTag t = new CompoundTag();
        t.putString("action", "buy_levels");
        t.putInt("count", Math.max(1, count));
        QuestNetwork.sendToServer(t);
    }

    public static void sendAdminReload() {
        playClickSound(1.15F);
        CompoundTag t = new CompoundTag();
        t.putString("action", "admin_reload");
        QuestNetwork.sendToServer(t);
    }

    public static void sendFetchAdminConfig() {
        CompoundTag t = new CompoundTag();
        t.putString("action", "admin_fetch_config");
        QuestNetwork.sendToServer(t);
    }

    public static void sendSaveAdminConfig(SeasonPassConfig pass, QuestPoolsConfig pools,
                                           OnlineRewardsConfig online, boolean rerollSelf) {
        playClickSound(1.22F);
        CompoundTag t = new CompoundTag();
        t.putString("action", "admin_save_config");
        if (pass != null) {
            t.putString("passJson", QuestDataConfig.toCompactJson(pass));
        }
        if (pools != null) {
            t.putString("poolsJson", QuestDataConfig.toCompactJson(pools));
        }
        if (online != null) {
            t.putString("onlineJson", QuestDataConfig.toCompactJson(online));
        }
        t.putBoolean("rerollSelf", rerollSelf);
        QuestNetwork.sendToServer(t);
    }

    // ================================================================ UI 短促音效

    private static void playUiSound(SoundEvent event, float pitch, float volume) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getSoundManager() != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
        }
    }

    public static void playClickSound(float pitch) {
        playUiSound(SoundEvents.WOODEN_BUTTON_CLICK_ON, pitch, 0.34F);
    }

    public static void playClaimSound() {
        playUiSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.35F, 0.38F);
    }

    public static void playUnlockSound() {
        playUiSound(SoundEvents.VAULT_ACTIVATE, 1.08F, 0.46F);
    }
}
