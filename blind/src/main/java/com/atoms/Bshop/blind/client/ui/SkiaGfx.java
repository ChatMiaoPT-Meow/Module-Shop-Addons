package com.atoms.Bshop.blind.client.ui;

import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import io.github.humbleui.skija.Canvas;

/** AtomLib / Skija 后端（与模组商店内置页面同一套字体、圆角与配色）。图标画成色块徽章。 */
public final class SkiaGfx implements Gfx {
    private final Canvas canvas;

    public SkiaGfx(Canvas canvas) {
        this.canvas = canvas;
    }

    @Override
    public void fill(float x, float y, float w, float h, float radius, int color) {
        if (w > 0 && h > 0) Draw.fill(canvas, x, y, w, h, radius, color);
    }

    @Override
    public void stroke(float x, float y, float w, float h, float radius, int color) {
        if (w > 0 && h > 0) Draw.stroke(canvas, x, y, w, h, radius, 1.0F, color);
    }

    @Override
    public void text(String s, float x, float cy, float maxW, Align align, float size, boolean bold, int color) {
        if (s == null || s.isEmpty() || maxW <= 0) return;
        Text.Align a = switch (align) {
            case LEFT -> Text.Align.LEFT;
            case CENTER -> Text.Align.CENTER;
            case RIGHT -> Text.Align.RIGHT;
        };
        Text.drawLine(canvas, s, bold ? ShopTheme.bold() : ShopTheme.regular(), size, color, x, cy, maxW, a);
    }

    @Override
    public float width(String s, float size, boolean bold) {
        return Text.width(s, bold ? ShopTheme.bold() : ShopTheme.regular(), size);
    }

    @Override
    public void icon(String itemId, String glyph, int color, float x, float y, float size) {
        int a = (Math.round(((color >>> 24) & 0xFF) * 0.18F) << 24) | (color & 0x00FFFFFF);
        Draw.fill(canvas, x, y, size, size, Math.min(4.0F, size / 4.0F), a);
        if (glyph != null && !glyph.isEmpty()) {
            Text.drawLine(canvas, glyph, ShopTheme.bold(), Math.max(5.5F, size * 0.55F), color, x, y + size / 2.0F, size,
                    Text.Align.CENTER);
        }
    }
}
