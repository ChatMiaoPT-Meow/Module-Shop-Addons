package cn.autoforged.module_shop_stock.client.view;

import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.V2;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;

/** 股市各视图共用的小工具：语言、涨跌色、可点击区登记与按钮。 */
final class Ui {
    private Ui() {
    }

    /** 本帧登记的可点击区（按 id 命中）。每帧开头 clear，绘制时 add。 */
    static final class Hits {
        private final List<float[]> rects = new ArrayList<>();
        private final List<String> ids = new ArrayList<>();

        void clear() {
            this.rects.clear();
            this.ids.clear();
        }

        void add(float x, float y, float w, float h, String id) {
            this.rects.add(new float[]{x, y, w, h});
            this.ids.add(id);
        }

        /** 后登记的优先（叠在上层）。 */
        String at(double mx, double my) {
            for (int i = this.rects.size() - 1; i >= 0; i--) {
                float[] r = this.rects.get(i);
                if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                    return this.ids.get(i);
                }
            }
            return null;
        }
    }

    static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    static boolean in(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 中国习惯：涨红跌绿，平灰。 */
    static int trend(long v) {
        return v > 0L ? ShopTheme.red() : v < 0L ? ShopTheme.green() : ShopTheme.text3();
    }

    static String signed(long v) {
        return v > 0L ? "+" + V2.money(v) : v < 0L ? "−" + V2.money(-v) : "0";
    }

    static String pct(long bps) {
        String body = String.format(java.util.Locale.ROOT, "%.2f%%", Math.abs(bps) / 100.0D);
        return bps > 0L ? "+" + body : bps < 0L ? "−" + body : body;
    }

    /** V2 按钮 + 登记命中区。 */
    static void button(Canvas c, Hits hits, String id, String label, V2.Btn kind, float x, float y, float w, float h,
            float mx, float my) {
        boolean over = kind != V2.Btn.DISABLED && in(mx, my, x, y, w, h);
        V2.button(c, label, kind, x, y, w, h, over ? 1.0F : 0.0F);
        if (kind != V2.Btn.DISABLED) {
            hits.add(x, y, w, h, id);
        }
    }

    /** 买卖方向按钮：实心 / 描边，红（多头方向）或绿（空头方向），灰为不可用。 */
    static void tradeButton(Canvas c, Hits hits, String id, String label, int color, boolean filled, boolean enabled,
            float x, float y, float w, float h, float mx, float my) {
        if (!enabled) {
            V2.button(c, label, V2.Btn.DISABLED, x, y, w, h, 0.0F);
            return;
        }
        float hv = in(mx, my, x, y, w, h) ? 1.0F : 0.0F;
        if (filled) {
            Draw.fill(c, x, y, w, h, V2.R, Draw.alpha(color, 0.82F + 0.18F * hv));
            V2.bold(c, label, V2.FS, 0xFFFFFFFF, x, y + h / 2, w, Text.Align.CENTER);
        } else {
            Draw.fill(c, x, y, w, h, V2.R, Draw.alpha(color, 0.06F + 0.16F * hv));
            Draw.stroke(c, x, y, w, h, V2.R, 0.75F, Draw.alpha(color, 0.65F + 0.35F * hv));
            V2.bold(c, label, V2.FS, color, x, y + h / 2, w, Text.Align.CENTER);
        }
        hits.add(x, y, w, h, id);
    }

    /** 工具条底线 + 标题之外的通用「分段」命中：返回命中的段序号，没命中 -1。 */
    static int segHit(float[][] segs, float y, double mx, double my) {
        if (my < y || my >= y + V2.CTRL) {
            return -1;
        }
        for (int i = 0; i < segs.length; i++) {
            if (mx >= segs[i][0] && mx < segs[i][0] + segs[i][1]) {
                return i;
            }
        }
        return -1;
    }

    /** 小标题行：左 FS_XS 灰字。 */
    static void caption(Canvas c, String s, float x, float cy, float w) {
        V2.section(c, s, x, cy, w);
    }

    /** 带底色的小面板（详情栏分块、卡片）。 */
    static void card(Canvas c, float x, float y, float w, float h) {
        Draw.fill(c, x, y, w, h, V2.R, Draw.alpha(ShopTheme.card(), 0.7F));
        Draw.stroke(c, x, y, w, h, V2.R, 0.5F, ShopTheme.borderDim());
    }
}
