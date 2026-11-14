package com.atoms.Bshop.blind.client;

import cn.autoforged.module_shop.api.client.ShopModules;
import com.atoms.Bshop.blind.client.ui.BlindSound;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** 客户端入口：商店侧栏内容页（AtomLib）+ 回退界面 + 快捷键 + 可选实机自动截屏巡检器。 */
public final class BlindAuctionClient {
    public static final String MODULE_ID = "module_shop_blind";
    public static KeyMapping OPEN_BLIND_KEY;

    private BlindAuctionClient() {}

    public static void init(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() ->
                ShopModules.registerPage(MODULE_ID,
                        () -> Component.translatable("module_shop_blind.nav.title"),
                        BlindAuctionPage::new,
                        BlindAuctionScreen::new)));
        modBus.addListener(BlindAuctionClient::onRegisterKeys);
        MinecraftForge.EVENT_BUS.addListener(BlindAuctionClient::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(BlindAuctionClient::onScreenKey);
        MinecraftForge.EVENT_BUS.addListener(BlindAuctionClient::onScreenChar);
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            BlindAuctionState.reset();
            BlindSound.resetTracking();
        });
        if (BlindRealTour.enabled()) {
            MinecraftForge.EVENT_BUS.addListener(BlindRealTour::onClientTick);
        }
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        OPEN_BLIND_KEY = new KeyMapping("key.module_shop_blind.open", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B, "key.categories.module_shop_blind");
        event.register(OPEN_BLIND_KEY);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        boolean uiVisible = mc.screen instanceof BlindAuctionScreen
                || (mc.screen != null && BlindAuctionPage.visibleOn(mc.screen) != null);
        BlindSound.pollStateTransitions(uiVisible);
        if (OPEN_BLIND_KEY == null) return;
        while (OPEN_BLIND_KEY.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                BlindSound.selectOption();
                mc.setScreen(new BlindAuctionScreen());
            }
        }
    }

    /** AddonPage 没有键盘回调：出价键盘打开时把数字、退格、回车转给盲仓页。 */
    private static void onScreenKey(ScreenEvent.KeyPressed.Pre event) {
        BlindAuctionPage page = BlindAuctionPage.visibleOn(event.getScreen());
        if (page != null && page.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }

    private static void onScreenChar(ScreenEvent.CharacterTyped.Pre event) {
        BlindAuctionPage page = BlindAuctionPage.visibleOn(event.getScreen());
        if (page != null && page.charTyped(event.getCodePoint(), event.getModifiers())) {
            event.setCanceled(true);
        }
    }
}
