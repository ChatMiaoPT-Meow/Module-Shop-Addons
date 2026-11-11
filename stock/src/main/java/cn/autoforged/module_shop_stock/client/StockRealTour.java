package cn.autoforged.module_shop_stock.client;

import cn.autoforged.module_shop_stock.market.bars.BarPeriod;
import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 实机 OpenGL 帧缓冲自动巡检截屏器（仅在 JVM 参数包含 -Daddons.realshot=stock 时激活，正常游玩零开销）。
 * 进入存档后自动打开主模组商店的股票页，依次切换 1m / 5m / 15m / 60m / 1d 并驱动真实滚轮缩放，
 * 调用原版 {@link Screenshot#grab} 抓取真实渲染画面输出到 run/real_screenshots/。
 */
public final class StockRealTour {
    private static int ticks = 0;
    private static boolean done = false;

    private StockRealTour() {
    }

    public static boolean enabled() {
        String mode = System.getProperty("addons.realshot", "");
        return "stock".equalsIgnoreCase(mode) || "all".equalsIgnoreCase(mode);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (done) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        ticks++;
        if (ticks == 40) {
            openShopTab(mc, StockMarketClient.MODULE_ID);
            StockMarketState.requestSnapshot();
        } else if (ticks == 65) {
            selectPreferred();
            switchAndFetch(BarPeriod.M1);
        } else if (ticks == 95) {
            grab(mc, "01_stock_1m_default.png");
            switchAndFetch(BarPeriod.M5);
        } else if (ticks == 125) {
            grab(mc, "02_stock_5m_default.png");
            switchAndFetch(BarPeriod.M15);
        } else if (ticks == 155) {
            grab(mc, "03_stock_15m_default.png");
            switchAndFetch(BarPeriod.H1);
        } else if (ticks == 185) {
            grab(mc, "04_stock_60m_default.png");
            for (int i = 0; i < 4; i++) {
                StockMarketState.zoomChart(1.0D, 0.85D);
            }
        } else if (ticks == 210) {
            grab(mc, "05_stock_60m_wheel_zoom_in.png");
            for (int i = 0; i < 9; i++) {
                StockMarketState.zoomChart(-1.0D, 0.85D);
            }
        } else if (ticks == 235) {
            grab(mc, "06_stock_60m_wheel_zoom_out.png");
            switchAndFetch(BarPeriod.D1);
        } else if (ticks == 265) {
            grab(mc, "07_stock_1d_daily.png");
        } else if (ticks == 285) {
            done = true;
            if (Boolean.getBoolean("addons.realshot.quit") && !"all".equalsIgnoreCase(System.getProperty("addons.realshot", ""))) {
                mc.stop();
            }
        }
    }

    private static void selectPreferred() {
        if (StockMarketState.def("GOLD") != null) {
            StockMarketState.select("GOLD");
        } else if (!StockMarketState.defs().isEmpty()) {
            StockMarketState.select(StockMarketState.defs().get(0).symbol);
        }
    }

    private static void switchAndFetch(BarPeriod period) {
        StockMarketState.setPeriod(period);
        if (!StockMarketState.selected.isEmpty()) {
            StockMarketState.requestChart(StockMarketState.selected);
        }
    }

    private static void openShopTab(Minecraft mc, String tabId) {
        try {
            Class<?> cls = Class.forName("cn.autoforged.module_shop.client.screen.ShopScreen");
            Screen screen = (Screen) cls.getConstructor(String.class).newInstance(tabId);
            mc.setScreen(screen);
        } catch (ReflectiveOperationException ignored) {
            mc.setScreen(new StockMarketFallbackScreen());
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
