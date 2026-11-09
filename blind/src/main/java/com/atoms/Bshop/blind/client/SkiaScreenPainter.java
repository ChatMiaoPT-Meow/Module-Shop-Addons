package com.atoms.Bshop.blind.client;

import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import com.atoms.Bshop.blind.client.ui.SkiaGfx;
import com.atoms.Bshop.blind.client.view.BlindVaultRouterView;
import net.minecraft.client.gui.GuiGraphics;

/** 独立盲仓界面的 AtomLib 绘制路径。单独成类：只有确认 AtomLib 可用后才会加载。 */
final class SkiaScreenPainter {
    private SkiaScreenPainter() {}

    static void paint(GuiGraphics g, BlindVaultRouterView view, int x, int y, int w, int h, int mouseX, int mouseY) {
        SkiaFrame.draw(g, canvas -> {
            SkiaGfx gfx = new SkiaGfx(canvas);
            gfx.fill(x - 9, y - 9, w + 18, h + 18, 8, ShopTheme.borderDim());
            gfx.fill(x - 8, y - 8, w + 16, h + 16, 7, ShopTheme.content());
            view.render(gfx, x, y, w, h, mouseX, mouseY);
        });
    }
}
