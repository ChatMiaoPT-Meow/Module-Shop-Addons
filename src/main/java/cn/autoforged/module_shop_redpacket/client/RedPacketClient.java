package cn.autoforged.module_shop_redpacket.client;

import cn.autoforged.module_shop.api.client.ShopModules;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** 客户端入口：把「红包」挂进商店左侧功能区，并处理服务端回包。 */
public final class RedPacketClient {
    public static final String MODULE_ID = "module_shop_redpacket";

    private RedPacketClient() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(() ->
                ShopModules.register(MODULE_ID,
                        () -> Component.translatable("tab.module_shop_redpacket.red_packet"),
                        RedPacketSendScreen::new)));
    }

    public static void handle(CompoundTag tag) {
        Minecraft mc = Minecraft.getInstance();
        switch (tag.getString("type")) {
            case "status" -> {
                if (mc.screen instanceof RedPacketSendScreen screen) {
                    screen.applyStatus(tag);
                }
            }
            case "send_result" -> {
                if (mc.screen instanceof RedPacketSendScreen screen) {
                    screen.applyResult(tag);
                }
            }
            case "detail" -> {
                if (mc.screen instanceof RedPacketDetailScreen screen) {
                    screen.replace(tag);
                } else {
                    mc.setScreen(new RedPacketDetailScreen(tag, mc.screen));
                }
            }
            default -> {
            }
        }
    }
}
