package com.atoms.Bshop.blind.client.ui;

import cn.autoforged.module_shop.api.client.ShopTheme;

/** 盲仓配色：全部取模组商店当前皮肤的语义色（每帧调用，切换皮肤即时跟随）；只有稀有度用固定品牌色。 */
public final class BlindTheme {
    private BlindTheme() {}

    public static int text() { return ShopTheme.text(); }
    public static int text2() { return ShopTheme.text2(); }
    public static int text3() { return ShopTheme.text3(); }
    public static int content() { return ShopTheme.content(); }
    public static int card() { return ShopTheme.card(); }
    public static int rowHover() { return ShopTheme.rowHover(); }
    public static int border() { return ShopTheme.border(); }
    public static int borderDim() { return ShopTheme.borderDim(); }
    public static int accent() { return ShopTheme.accent(); }
    public static int accentTint() { return ShopTheme.accentTint(); }
    public static int gold() { return ShopTheme.gold(); }
    public static int green() { return ShopTheme.green(); }
    public static int red() { return ShopTheme.red(); }
    public static int redTint() { return ShopTheme.redTint(); }

    /** 稀有：蓝；史诗：紫；传说：金（随皮肤）。 */
    public static int rarity(int rarity) {
        return switch (rarity) {
            case 3 -> ShopTheme.gold();
            case 2 -> 0xFF9B6BFF;
            case 1 -> 0xFF3BA7D9;
            default -> ShopTheme.borderDim();
        };
    }

    public static int alpha(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, a)));
        return (al << 24) | (argb & 0x00FFFFFF);
    }
}
