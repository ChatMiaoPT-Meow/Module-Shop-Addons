package com.atoms.Bshop.blind.client.ui;

/**
 * 绘制后端抽象：同一份盲仓布局既能画在 AtomLib（Skija）商店页面上，也能画在原版回退界面上。
 * 坐标一律是 GUI 像素；文字按中线 {@code cy} 垂直居中。
 */
public interface Gfx {
    enum Align { LEFT, CENTER, RIGHT }

    void fill(float x, float y, float w, float h, float radius, int color);

    void stroke(float x, float y, float w, float h, float radius, int color);

    void text(String s, float x, float cy, float maxW, Align align, float size, boolean bold, int color);

    float width(String s, float size, boolean bold);

    /**
     * 图标徽章：AtomLib 下画成圆角色块 + 单字（与商店风格一致，且和页面动画同步）；
     * 原版回退界面在给了 {@code itemId} 时画原版物品。
     */
    void icon(String itemId, String glyph, int color, float x, float y, float size);
}
