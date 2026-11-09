package com.atoms.Bshop.blind.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 原版 GuiGraphics 后端：AtomLib 不可用时的回退界面使用。字号参数忽略（原版字体固定）。 */
public final class VanillaGfx implements Gfx {
    private final GuiGraphics g;
    private final Font font = Minecraft.getInstance().font;

    public VanillaGfx(GuiGraphics g) {
        this.g = g;
    }

    @Override
    public void fill(float x, float y, float w, float h, float radius, int color) {
        if (w > 0 && h > 0) g.fill(Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h), color);
    }

    @Override
    public void stroke(float x, float y, float w, float h, float radius, int color) {
        int x0 = Math.round(x), y0 = Math.round(y), x1 = Math.round(x + w), y1 = Math.round(y + h);
        if (x1 <= x0 || y1 <= y0) return;
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color);
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color);
    }

    @Override
    public void text(String s, float x, float cy, float maxW, Align align, float size, boolean bold, int color) {
        if (s == null || s.isEmpty() || maxW <= 0) return;
        String fitted = s;
        if (font.width(s) > maxW) {
            fitted = font.plainSubstrByWidth(s, Math.max(0, (int) maxW - font.width("…"))) + "…";
        }
        int w = font.width(fitted);
        float dx = switch (align) {
            case LEFT -> x;
            case CENTER -> x + (maxW - w) / 2.0F;
            case RIGHT -> x + maxW - w;
        };
        g.drawString(font, fitted, Math.round(dx), Math.round(cy - 4.0F), color, false);
    }

    @Override
    public float width(String s, float size, boolean bold) {
        return font.width(s);
    }

    @Override
    public void icon(String itemId, String glyph, int color, float x, float y, float size) {
        ResourceLocation rl = itemId == null ? null : ResourceLocation.tryParse(itemId);
        var item = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) {
            fill(x, y, size, size, 0, (0x30 << 24) | (color & 0x00FFFFFF));
            text(glyph, x, y + size / 2.0F, size, Align.CENTER, size, true, color);
            return;
        }
        ItemStack stack = new ItemStack(item);
        float scale = size / 16.0F;
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(scale, scale, 1.0F);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }
}
