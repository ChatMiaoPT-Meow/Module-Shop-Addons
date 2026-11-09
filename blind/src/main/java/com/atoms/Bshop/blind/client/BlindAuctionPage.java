package com.atoms.Bshop.blind.client;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPage;
import com.atoms.Bshop.blind.client.ui.SkiaGfx;
import com.atoms.Bshop.blind.client.view.BlindVaultRouterView;
import com.atoms.Bshop.blind.network.BlindAuctionNetwork;
import io.github.humbleui.skija.Canvas;
import net.minecraft.client.gui.screens.Screen;

/** 商店侧栏「遗迹盲仓」内容页：AtomLib 绘制，与商店内置页面同一套皮肤与字体。实例常驻。 */
public final class BlindAuctionPage implements AddonPage {
    private final BlindVaultRouterView view = new BlindVaultRouterView();

    private static BlindAuctionPage visible;
    private static Screen visibleScreen;
    private static long lastRenderAt;

    @Override
    public void render(Canvas canvas, AddonFrame f) {
        long now = System.currentTimeMillis();
        if (visible != this || now - lastRenderAt > 1000L) {
            BlindAuctionNetwork.requestSync();
        }
        view.render(new SkiaGfx(canvas), f.x(), f.y(), f.w(), f.h(), f.mouseX(), f.mouseY());
        visible = this;
        visibleScreen = f.host().screen();
        lastRenderAt = now;
    }

    @Override
    public boolean mouseClicked(AddonFrame frame, double mouseX, double mouseY, int button) {
        return view.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void clickedOutside() {
        BlindAuctionState.setKeypadOpen(false);
    }

    @Override
    public void cancelInput() {
        BlindAuctionState.setKeypadOpen(false);
        visible = null;
        visibleScreen = null;
    }

    /** 这一帧盲仓页是否正显示在 {@code screen} 上（用于补画物品图标、转发键盘）。 */
    static BlindAuctionPage visibleOn(Screen screen) {
        if (visible == null || screen == null || screen != visibleScreen) return null;
        return System.currentTimeMillis() - lastRenderAt < 250L ? visible : null;
    }

    boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return view.keyPressed(keyCode, scanCode, modifiers);
    }

    boolean charTyped(char c, int modifiers) {
        return view.charTyped(c, modifiers);
    }
}
