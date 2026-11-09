package com.atoms.Bshop.blind.client;

import cn.autoforged.module_shop.api.client.ShopTheme;
import com.atoms.Bshop.blind.client.ui.VanillaGfx;
import com.atoms.Bshop.blind.client.view.BlindVaultRouterView;
import com.atoms.Bshop.blind.network.BlindAuctionNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 独立盲仓界面：快捷键打开，以及商店内容页不可用时的回退界面。
 * AtomLib 可用时与商店页同样用 Skija 绘制；不可用时退回原版 GuiGraphics。ESC 回到上一个界面。
 */
public class BlindAuctionScreen extends Screen {
    private static final int W = 280;
    private static final int H = 182;
    private final BlindVaultRouterView view = new BlindVaultRouterView();
    private final Screen parent;
    private Boolean atom;

    public BlindAuctionScreen() {
        super(Component.translatable("module_shop_blind.nav.title"));
        this.parent = Minecraft.getInstance().screen;
    }

    @Override
    protected void init() {
        BlindAuctionNetwork.requestSync();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = (this.width - W) / 2;
        int y = (this.height - H) / 2;
        if (atom == null) {
            atom = ShopTheme.atomLibAvailable();
        }
        if (atom) {
            SkiaScreenPainter.paint(g, view, x, y, W, H, mouseX, mouseY);
        } else {
            VanillaGfx gfx = new VanillaGfx(g);
            gfx.fill(x - 7, y - 7, W + 14, H + 14, 0, ShopTheme.borderDim());
            gfx.fill(x - 6, y - 6, W + 12, H + 12, 0, ShopTheme.content());
            view.render(gfx, x, y, W, H, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (view.mouseClicked(mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (view.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (view.charTyped(codePoint, modifiers)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        BlindAuctionState.setKeypadOpen(false);
        Minecraft.getInstance().setScreen(this.parent);
    }
}
