package cn.autoforged.module_shop_redpacket.client;

import cn.autoforged.module_shop.api.client.ShopModules;
import cn.autoforged.module_shop.api.client.ShopTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** 客户端入口：把「红包」作为内容区页面挂进商店左侧功能区，并处理服务端回包。 */
public final class RedPacketClient {
    public static final String MODULE_ID = "module_shop_redpacket";

    private RedPacketClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() ->
                // AtomLib 可用：画在商店右侧内容区（RedPacketPage）；不可用：点了打开原版回退界面。
                ShopModules.registerPage(MODULE_ID,
                        () -> Component.translatable("tab.module_shop_redpacket.red_packet"),
                        RedPacketPage::new,
                        RedPacketSendScreen::new)));
    }

    public static void handle(CompoundTag tag) {
        Minecraft mc = Minecraft.getInstance();
        switch (tag.getString("type")) {
            case "status" -> RedPacketState.applyStatus(tag);
            case "send_result" -> {
                boolean ok = RedPacketState.applyResult(tag);
                if (mc.screen instanceof RedPacketSendScreen screen) {
                    screen.onResult(ok);
                }
            }
            case "detail" -> {
                if (mc.screen instanceof RedPacketDetailAtomScreen screen) {
                    screen.replace(tag);
                } else if (mc.screen instanceof RedPacketDetailScreen screen) {
                    screen.replace(tag);
                } else if (ShopTheme.atomLibAvailable()) {
                    mc.setScreen(new RedPacketDetailAtomScreen(tag, mc.screen));
                } else {
                    mc.setScreen(new RedPacketDetailScreen(tag, mc.screen));
                }
            }
            default -> {
            }
        }
    }
}
