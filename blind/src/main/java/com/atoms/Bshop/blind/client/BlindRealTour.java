package com.atoms.Bshop.blind.client;

import com.atoms.Bshop.blind.auction.BlindCatalog;
import com.atoms.Bshop.blind.network.BlindAuctionNetwork;
import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 实机 OpenGL 帧缓冲自动巡检截屏器（仅在 JVM 参数包含 -Daddons.realshot=blind 或 all 时激活，平时零开销）。
 * 自动打开商店遗迹盲仓页，依次抓拍：5道具备战+AI演练免门票、声呐探针十字扫描、多轮对手节奏复盘、开仓零星币结算验证。
 */
public final class BlindRealTour {
    private static int ticks = 0;
    private static int startOffset = -1;
    private static boolean resultCaptured = false;
    private static boolean done = false;

    private BlindRealTour() {}

    public static boolean enabled() {
        String mode = System.getProperty("addons.realshot", "");
        return "blind".equalsIgnoreCase(mode) || "all".equalsIgnoreCase(mode);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (done) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (startOffset < 0) {
            startOffset = "all".equalsIgnoreCase(System.getProperty("addons.realshot", "")) ? 300 : 40;
        }
        ticks++;
        int t = ticks - startOffset;
        if (t < 0) {
            return;
        }
        if (t == 0) {
            openShopTab(mc, BlindAuctionClient.MODULE_ID);
            BlindAuctionNetwork.requestSync();
        } else if (t == 15) {
            BlindAuctionNetwork.sendAction(BlindAuctionNetwork.SET_ZONE, 0, 0, 0);
            BlindAuctionNetwork.sendAction(BlindAuctionNetwork.SET_ROLE, 3, 0, 0);
            BlindAuctionNetwork.sendAction(BlindAuctionNetwork.SET_SINGLE, 1, 0, 0);
        } else if (t == 40) {
            grab(mc, "08_blind_loadout_5tools_practice.png");
            BlindAuctionNetwork.sendAction(BlindAuctionNetwork.START, 0, 0, 0);
        } else if (t == 65) {
            // 使用第 5 道具：声呐探针（SONAR = 4）扫描仓库中心格 (4, 2) -> index 20
            BlindAuctionNetwork.sendAction(BlindAuctionNetwork.USE_TOOL, BlindCatalog.Tool.SONAR.ordinal(), 20, 0);
        } else if (t == 90) {
            grab(mc, "09_blind_match_r1_sonar_and_seal.png");
            long bid1 = Math.max(100_000L, BlindAuctionState.myFloor());
            BlindAuctionNetwork.sendAction(BlindAuctionNetwork.BID, 0, 0, bid1);
        } else if (t == 115 && BlindAuctionState.phase() == BlindAuctionState.Phase.MATCH && !BlindAuctionState.submitted()) {
            long bid2 = Math.min(BlindAuctionState.effectiveBidCap(), Math.max(200_000L, Math.round(BlindAuctionState.myFloor() * 1.15D)));
            BlindAuctionNetwork.sendAction(BlindAuctionNetwork.BID, 0, 0, bid2);
        } else if (t == 145) {
            if (BlindAuctionState.phase() == BlindAuctionState.Phase.MATCH) {
                grab(mc, "10_blind_match_r3_tempo_recap.png");
            }
        } else if (t > 150 && t % 20 == 0) {
            if (BlindAuctionState.phase() == BlindAuctionState.Phase.MATCH && !BlindAuctionState.submitted()) {
                long bidN = Math.min(BlindAuctionState.effectiveBidCap(), Math.max(300_000L, Math.round(BlindAuctionState.myFloor() * 1.35D)));
                BlindAuctionNetwork.sendAction(BlindAuctionNetwork.BID, 0, 0, bidN);
            } else if (BlindAuctionState.phase() == BlindAuctionState.Phase.RESULT && !resultCaptured) {
                grab(mc, "11_blind_result_practice_no_coins.png");
                resultCaptured = true;
            } else if (resultCaptured) {
                done = true;
                if (Boolean.getBoolean("addons.realshot.quit")) {
                    mc.stop();
                }
            }
        }
        if (t > 380) {
            if (!resultCaptured) {
                grab(mc, "11_blind_result_practice_no_coins.png");
            }
            done = true;
            if (Boolean.getBoolean("addons.realshot.quit")) {
                mc.stop();
            }
        }
    }

    private static void openShopTab(Minecraft mc, String tabId) {
        try {
            Class<?> cls = Class.forName("cn.autoforged.module_shop.client.screen.ShopScreen");
            Screen screen = (Screen) cls.getConstructor(String.class).newInstance(tabId);
            mc.setScreen(screen);
        } catch (ReflectiveOperationException ignored) {
            mc.setScreen(new BlindAuctionScreen());
        }
    }

    private static void grab(Minecraft mc, String fileName) {
        File dir = new File(mc.gameDirectory, "real_screenshots");
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        Screenshot.grab(dir, fileName, mc.getMainRenderTarget(), msg -> { });
    }
}
