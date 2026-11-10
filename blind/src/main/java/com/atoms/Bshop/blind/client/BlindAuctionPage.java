package com.atoms.Bshop.blind.client;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPage;
import com.atoms.Bshop.blind.client.view.BlindShopView;
import com.atoms.Bshop.blind.network.BlindAuctionNetwork;
import io.github.humbleui.skija.Canvas;
import net.minecraft.client.gui.screens.Screen;

/** 商店侧栏「遗迹盲仓」内容页：UI v2 列表视觉层（AtomLib 绘制，与商店内置页面同一套皮肤与字体）。实例常驻。 */
public final class BlindAuctionPage implements AddonPage {
    private final BlindShopView view = new BlindShopView();

    private static BlindAuctionPage visible;
    private static Screen visibleScreen;
    private static long lastRenderAt;

    @Override
    public void render(Canvas canvas, AddonFrame f) {
        long now = System.currentTimeMillis();
        if (visible != this || now - lastRenderAt > 1000L) {
            BlindAuctionNetwork.requestSync();
        }
        view.render(canvas, f);
        visible = this;
        visibleScreen = f.host().screen();
        lastRenderAt = now;
    }

    @Override
    public boolean mouseClicked(AddonFrame frame, double mouseX, double mouseY, int button) {
        return view.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(AddonFrame frame, double mouseX, double mouseY, int button) {
        return view.mouseReleased();
    }

    @Override
    public boolean mouseDragged(AddonFrame frame, double mouseX, double mouseY, int button, double dragX, double dragY) {
        return view.mouseDragged(mouseY);
    }

    @Override
    public boolean mouseScrolled(AddonFrame frame, double mouseX, double mouseY, double scrollY) {
        return view.mouseScrolled(mouseX, mouseY, scrollY);
    }

    @Override
    public void clickedOutside() {
        BlindAuctionState.setKeypadOpen(false);
    }

    @Override
    public void cancelInput() {
        BlindAuctionState.setKeypadOpen(false);
        view.cancelInput();
        visible = null;
        visibleScreen = null;
    }

    /** 这一帧盲仓页是否正显示在 {@code screen} 上（用于转发键盘）。 */
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
