package cn.autoforged.module_shop_redpacket.client;

import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import io.github.humbleui.skija.Canvas;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * 红包详情（UI v2 风格的弹窗，点聊天栏「详情」或页面右栏的小卡打开）。它由服务端回包触发、不在商店外壳里，
 * 所以自己决定缩放：按窗口物理像素取「宽 / 800」（和商店同样的字号观感），再保证面板放得进窗口，
 * 与 Minecraft 的 GUI 缩放（2 / 3 / 4）无关。AtomLib 不可用时用 {@link RedPacketDetailScreen}。
 *
 * <pre>
 * ┌ 红包详情 [已领完][拼手气][口令]            #id [复制 ID] [×] ┐
 * │ 左：信封头（祝福语/发送者/我领到）│ 右：领取记录表          │
 * │     总额·已领·剩余·退回 四格     │ 名次 玩家 时间 金额 占比 │
 * │     信息表（发出/到期/最高最低） │ 合计行                  │
 * └ 占比说明 ················································ ┘
 * </pre>
 */
public class RedPacketDetailAtomScreen extends Screen {
    private static final float PW = 560.0F;
    private static final float PH = 340.0F;
    private static final float HEAD_H = 26.0F;
    private static final float PAD = 8.0F;
    private static final int RED_A = 0xFFE0434D;
    private static final int RED_B = 0xFFF08A3C;
    private static final int SEAL = 0xFFFFC94A;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    /** 表格实例放 static：弹窗关了再开（或回包刷新）时滚动位置不丢。 */
    private static final ListTable TABLE = new ListTable();

    private record Row(String name, long amount, long time, boolean me, boolean best) {
    }

    private final Screen parent;
    private CompoundTag data;
    private final List<Row> rows = new ArrayList<>();
    private final Motion.Clock clock = new Motion.Clock();
    private float openT;

    // 本帧几何（画布单位）：render 算、输入共用
    private float s = 1.0F;
    private float px;
    private float py;
    private float pw;
    private float ph;
    private float[] closeRect;
    private float[] copyRect;

    public RedPacketDetailAtomScreen(CompoundTag data, Screen parent) {
        super(Component.translatable("gui.module_shop_redpacket.detail_title"));
        this.parent = parent;
        replace(data);
    }

    void replace(CompoundTag data) {
        this.data = data;
        this.rows.clear();
        ListTag list = data.getList("claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            this.rows.add(new Row(c.getString("name"), c.getLong("amount"), c.getLong("time"),
                    c.getBoolean("me"), c.getBoolean("best")));
        }
        TABLE.scrollToTop();
    }

    // ------------------------------------------------------------------ 几何

    /** 缩放：窗口宽 / 800（至少 1），面板不超出窗口；面板尺寸与位置取整。 */
    private void metrics() {
        var win = Minecraft.getInstance().getWindow();
        float fw = win.getWidth();
        float fh = win.getHeight();
        this.s = Math.max(1.0F, (float) Math.floor(Math.min(fw / 800.0F, fh * 0.94F / PH) * 4.0F) / 4.0F);
        float vw = fw / this.s;
        float vh = fh / this.s;
        this.pw = Math.min(PW, vw * 0.96F);
        this.ph = Math.min(PH, vh * 0.94F);
        this.px = (float) Math.floor((vw - this.pw) / 2.0F);
        this.py = (float) Math.floor((vh - this.ph) / 2.0F);
    }

    /** GUI 坐标 → 画布单位。 */
    private float toUnits(double gui) {
        return (float) (gui * Minecraft.getInstance().getWindow().getGuiScale() / this.s);
    }

    private float leftW() {
        return Math.max(190.0F, Math.min(250.0F, this.pw * 0.43F));
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static boolean in(float[] r, float x, float y) {
        return r != null && x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        float dt = this.clock.tick();
        this.openT = Motion.approach(this.openT, 1.0F, dt, 90.0F);
        metrics();
        float mx = toUnits(mouseX);
        float my = toUnits(mouseY);
        SkiaFrame.drawScaled(g, this.s, canvas -> paint(canvas, mx, my, dt));
    }

    private void paint(Canvas c, float mx, float my, float dt) {
        float x = this.px;
        float y = this.py;
        String currency = this.data.getString("currency");
        long total = this.data.getLong("total");
        int count = this.data.getInt("count");
        RedPacketView.Status status = RedPacketView.status(this.data);
        long claimed = RedPacketView.claimed(this.data);

        ShopTheme.dialogPanel(c, x, y, this.pw, this.ph);
        drawHeader(c, mx, my, status);

        float bodyTop = y + HEAD_H;
        float footY = y + this.ph - V2.FOOT;
        float lw = leftW();
        Draw.fill(c, x + lw - 0.5F, bodyTop, 0.5F, footY - bodyTop, 0, ShopTheme.borderDim());
        drawLeft(c, x + PAD, bodyTop + 7, lw - PAD * 2, footY - bodyTop - 7, currency, total, count, status, claimed);
        drawTable(c, mx, my, dt, x + lw, bodyTop, this.pw - lw, footY - bodyTop, total, count, claimed);

        // 底栏：说明
        Draw.hairline(c, x + 1, footY, this.pw - 2, ShopTheme.borderDim());
        V2.text(c, tr("gui.module_shop_redpacket.detail_foot_share"), V2.FS_XS, ShopTheme.text3(), x + PAD,
                footY + V2.FOOT / 2, this.pw / 2 - PAD, Text.Align.LEFT);
        V2.text(c, tr("gui.module_shop_redpacket.detail_foot_best"), V2.FS_XS, ShopTheme.text3(), x + this.pw / 2,
                footY + V2.FOOT / 2, this.pw / 2 - PAD, Text.Align.RIGHT);
    }

    private void drawHeader(Canvas c, float mx, float my, RedPacketView.Status status) {
        float x = this.px;
        float cy = this.py + HEAD_H / 2;
        Draw.hairline(c, x + 1, this.py + HEAD_H - 0.5F, this.pw - 2, ShopTheme.borderDim());
        float tx = x + PAD + 2;
        tx += V2.bold(c, tr("gui.module_shop_redpacket.detail_title"), V2.FS_T, ShopTheme.text(), tx, cy, 90,
                Text.Align.LEFT) + 8;
        int stColor = status == RedPacketView.Status.ACTIVE ? ShopTheme.gold()
                : status == RedPacketView.Status.DONE ? ShopTheme.green() : ShopTheme.text3();
        tx += V2.tag(c, RedPacketView.statusName(status), stColor, tx, cy);
        boolean lucky = this.data.getBoolean("lucky");
        tx += V2.tag(c, RedPacketView.modeName(this.data), lucky ? ShopTheme.red() : ShopTheme.accent(), tx, cy);
        if (this.data.getBoolean("hasPassword")) {
            V2.tag(c, tr("gui.module_shop_redpacket.tag_password"), ShopTheme.gold(), tx, cy);
        }

        float right = x + this.pw - PAD;
        this.closeRect = new float[]{right - V2.CTRL, cy - V2.CTRL / 2, V2.CTRL, V2.CTRL};
        V2.iconButton(c, V2.Icon.CLOSE, this.closeRect[0], this.closeRect[1], in(this.closeRect, mx, my) ? 1.0F : 0.0F, false);
        right = this.closeRect[0] - 4;
        String copy = tr("gui.module_shop_redpacket.copy_id");
        float cw = V2.buttonWidth(copy);
        this.copyRect = new float[]{right - cw, cy - V2.CTRL / 2, cw, V2.CTRL};
        V2.button(c, copy, V2.Btn.NORMAL, this.copyRect[0], this.copyRect[1], cw, V2.CTRL,
                in(this.copyRect, mx, my) ? 1.0F : 0.0F);
        right = this.copyRect[0] - 6;
        String id = "#" + this.data.getString("id");
        float iw = V2.width(id, V2.FS_S, true);
        V2.bold(c, id, V2.FS_S, ShopTheme.text(), right - iw, cy, iw + 2, Text.Align.LEFT);
        String lab = tr("gui.module_shop_redpacket.id_label");
        float lwid = V2.width(lab, V2.FS_XS, false);
        V2.text(c, lab, V2.FS_XS, ShopTheme.text3(), right - iw - 4 - lwid, cy, lwid + 2, Text.Align.LEFT);
    }

    // ---- 左栏

    private void drawLeft(Canvas c, float x, float y, float w, float h, String currency, long total, int count,
            RedPacketView.Status status, long claimed) {
        float bottom = y + h;
        // 信封头
        float heroH = 54.0F;
        Draw.gradientD(c, x, y, w, heroH, V2.R + 1, Draw.alpha(RED_A, 0.22F), Draw.alpha(RED_B, 0.08F));
        Draw.stroke(c, x, y, w, heroH, V2.R + 1, 0.6F, Draw.alpha(RED_A, 0.5F));
        // 信封小图标
        float ax = x + 8;
        float ay = y + 7;
        Draw.gradientV(c, ax, ay, 22, 22, 4, RED_A, RED_B);
        Draw.fill(c, ax + 7.5F, ay + 12.0F, 7, 7, 3.5F, SEAL);
        Draw.fill(c, ax, ay + 10.0F, 22, 1.2F, 0, Draw.alpha(0xFFFFFFFF, 0.3F));
        float tx = ax + 22 + 8;
        V2.bold(c, RedPacketView.greeting(this.data), V2.FS + 2.0F, ShopTheme.text(), tx, y + 14, x + w - tx - 6,
                Text.Align.LEFT);
        V2.text(c, tr("gui.module_shop_redpacket.detail_from", this.data.getString("sender")), V2.FS_S, ShopTheme.text2(),
                tx, y + 26, x + w - tx - 6, Text.Align.LEFT);
        // 我领到
        float sy = y + 38;
        Draw.fill(c, x + 1, sy, w - 2, heroH - 38 - 1, 0, Draw.alpha(ShopTheme.content(), 0.35F));
        Row mine = null;
        int rank = 0;
        for (int i = 0; i < this.rows.size(); i++) {
            if (this.rows.get(i).me()) {
                mine = this.rows.get(i);
                rank = i + 1;
            }
        }
        float scy = sy + (heroH - 38) / 2;
        if (mine != null) {
            long shown = Math.round(mine.amount() * Motion.easeOutCubic(this.openT));
            float nw = V2.bold(c, "+" + V2.money(shown), V2.FS + 3.0F, ShopTheme.gold(), x + 8, scy, 80, Text.Align.LEFT);
            String line = tr("gui.module_shop_redpacket.detail_mine_line", currency, rank,
                    pct(mine.amount(), total));
            V2.text(c, line, V2.FS_XS, ShopTheme.text2(), x + 8 + nw + 6, scy, w - 16 - nw - 6, Text.Align.LEFT);
        } else {
            V2.text(c, tr("gui.module_shop_redpacket.detail_not_mine"), V2.FS_S, ShopTheme.text3(), x + 8, scy, w - 16,
                    Text.Align.LEFT);
        }

        // 四格
        float cy = y + heroH + 5;
        float cellW = (w - 4) / 2;
        float cellH = 29.0F;
        long refunded = this.data.getLong("refunded");
        long left = status == RedPacketView.Status.ACTIVE ? Math.max(0, total - claimed) : 0L;
        String avg = V2.money(count > 0 ? total / count : 0);
        cell(c, x, cy, cellW, cellH, tr("gui.module_shop_redpacket.stat_total"), V2.money(total), ShopTheme.text(),
                tr("gui.module_shop_redpacket.stat_total_sub", count, avg));
        cell(c, x + cellW + 4, cy, cellW, cellH, tr("gui.module_shop_redpacket.stat_claimed"), V2.money(claimed),
                ShopTheme.gold(), tr("gui.module_shop_redpacket.stat_claimed_sub", this.rows.size(), count,
                        pct(claimed, total)));
        String leftSub;
        if (status == RedPacketView.Status.ACTIVE) {
            long min = Math.max(0L, (this.data.getLong("expiresAt") - this.data.getLong("now")) / 60_000L);
            leftSub = tr("gui.module_shop_redpacket.stat_left_active", RedPacketView.minutesText(min));
        } else {
            leftSub = tr(status == RedPacketView.Status.DONE ? "gui.module_shop_redpacket.stat_left_done"
                    : "gui.module_shop_redpacket.stat_left_expired");
        }
        cell(c, x, cy + cellH + 4, cellW, cellH, tr("gui.module_shop_redpacket.stat_left"), V2.money(left),
                left > 0 ? ShopTheme.text() : ShopTheme.text3(), leftSub);
        cell(c, x + cellW + 4, cy + cellH + 4, cellW, cellH, tr("gui.module_shop_redpacket.stat_refund"),
                refunded > 0 ? "+" + V2.money(refunded) : "0", refunded > 0 ? ShopTheme.green() : ShopTheme.text3(),
                tr(refunded > 0 ? "gui.module_shop_redpacket.stat_refund_sub" : "gui.module_shop_redpacket.stat_refund_none"));

        // 信息表
        float ky = cy + cellH * 2 + 4 + 6;
        List<String[]> kv = new ArrayList<>();
        kv.add(new String[]{tr("gui.module_shop_redpacket.info_sender"), this.data.getString("sender")});
        kv.add(new String[]{tr("gui.module_shop_redpacket.info_type"), tr(this.data.getBoolean("lucky")
                ? "gui.module_shop_redpacket.info_type_lucky" : "gui.module_shop_redpacket.info_type_even")});
        if (this.data.getBoolean("hasPassword")) {
            kv.add(new String[]{tr("gui.module_shop_redpacket.info_password"), this.data.getString("password")});
        }
        long created = this.data.getLong("createdAt");
        long expires = this.data.getLong("expiresAt");
        kv.add(new String[]{tr("gui.module_shop_redpacket.info_created"), TIME.format(Instant.ofEpochMilli(created))});
        kv.add(new String[]{tr("gui.module_shop_redpacket.info_expires"), TIME.format(Instant.ofEpochMilli(expires))
                + "  (" + RedPacketView.minutesText(Math.max(0L, (expires - created) / 60_000L)) + ")"});
        if (this.data.getBoolean("finished") && this.data.getLong("finishedAt") > 0L) {
            long secs = Math.max(1L, (this.data.getLong("finishedAt") - created) / 1000L);
            kv.add(new String[]{tr("gui.module_shop_redpacket.info_finished"), TIME.format(
                    Instant.ofEpochMilli(this.data.getLong("finishedAt"))) + "  (" + RedPacketView.duration(secs) + ")"});
        }
        if (!this.rows.isEmpty()) {
            long hi = 0L;
            long lo = Long.MAX_VALUE;
            for (Row r : this.rows) {
                hi = Math.max(hi, r.amount());
                lo = Math.min(lo, r.amount());
            }
            kv.add(new String[]{tr("gui.module_shop_redpacket.info_range"), V2.money(hi) + " / " + V2.money(lo)});
        }
        float rowH = 13.0F;
        for (int i = 0; i < kv.size(); i++) {
            float ry = ky + i * rowH;
            if (ry + rowH > bottom + 1) {
                break;
            }
            V2.rowBg(c, x, ry, w, rowH, i, 0, false);
            V2.text(c, kv.get(i)[0], V2.FS_XS + 0.25F, ShopTheme.text3(), x + 6, ry + rowH / 2, 56, Text.Align.LEFT);
            V2.bold(c, kv.get(i)[1], V2.FS_S, ShopTheme.text(), x + 62, ry + rowH / 2, w - 68, Text.Align.RIGHT);
        }
    }

    private static void cell(Canvas c, float x, float y, float w, float h, String label, String value, int valueColor,
            String sub) {
        Draw.fill(c, x, y, w, h, V2.R, ShopTheme.card());
        Draw.stroke(c, x, y, w, h, V2.R, 0.5F, ShopTheme.borderDim());
        V2.text(c, label, V2.FS_XS, ShopTheme.text3(), x + 6, y + 6, w - 12, Text.Align.LEFT);
        V2.bold(c, value, V2.FS + 3.0F, valueColor, x + 6, y + 15.5F, w - 12, Text.Align.LEFT);
        V2.text(c, sub, V2.FS_XS, ShopTheme.text3(), x + 6, y + 24.5F, w - 12, Text.Align.LEFT);
    }

    private static String pct(long part, long total) {
        return total <= 0L ? "0%" : String.format("%.1f%%", part * 100.0 / total);
    }

    // ---- 右栏：领取记录

    private void drawTable(Canvas c, float mx, float my, float dt, float x, float y, float w, float h, long total,
            int count, long claimed) {
        float sumH = V2.ROW;
        float[][] cols = V2.columns(x, w, PAD, 5, 22, 0, 54, 44, 58);
        String[] heads = {tr("gui.module_shop_redpacket.col_rank"), tr("gui.module_shop_redpacket.col_player"),
                tr("gui.module_shop_redpacket.col_time"), tr("gui.module_shop_redpacket.col_amount"),
                tr("gui.module_shop_redpacket.col_share")};
        Text.Align[] al = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT};
        TABLE.layout(x, y, w, h - sumH, cols, heads, al, null);
        long max = 1L;
        for (Row r : this.rows) {
            max = Math.max(max, r.amount());
        }
        final long maxAmount = max;
        int shown = Math.max(count, this.rows.size());
        TABLE.render(c, null, mx, my, dt, shown, (cv, i, ry, hover) -> {
            float cy = ry + V2.ROW / 2;
            if (i >= this.rows.size()) {
                V2.text(cv, String.valueOf(i + 1), V2.FS_S, ShopTheme.text3(), cols[0][0], cy, cols[0][1], Text.Align.LEFT);
                V2.text(cv, tr("gui.module_shop_redpacket.unclaimed"), V2.FS_S, ShopTheme.text3(), cols[1][0] + 12, cy,
                        cols[1][1] - 12, Text.Align.LEFT);
                V2.text(cv, "—", V2.FS_S, ShopTheme.text3(), cols[3][0], cy, cols[3][1], Text.Align.RIGHT);
                return;
            }
            Row r = this.rows.get(i);
            if (r.me()) {
                Draw.fill(cv, x, ry, w, V2.ROW, 0, Draw.alpha(ShopTheme.gold(), 0.16F));
                Draw.fill(cv, x, ry, 1.5F, V2.ROW, 0, ShopTheme.gold());
            }
            int gold = ShopTheme.gold();
            if (i < 3) {
                V2.bold(cv, String.valueOf(i + 1), V2.FS_S, gold, cols[0][0], cy, cols[0][1], Text.Align.LEFT);
            } else {
                V2.text(cv, String.valueOf(i + 1), V2.FS_S, ShopTheme.text3(), cols[0][0], cy, cols[0][1], Text.Align.LEFT);
            }
            // 头像小块 + 名字 + 标记
            float nx = cols[1][0];
            Draw.fill(cv, nx, cy - 4.5F, 9, 9, 2, Draw.alpha(r.me() ? gold : ShopTheme.red(), 0.28F));
            String initial = r.name().isEmpty() ? "?" : r.name().substring(0, 1).toUpperCase();
            V2.bold(cv, initial, V2.FS_XS, r.me() ? gold : ShopTheme.red(), nx, cy, 9, Text.Align.CENTER);
            String best = tr("gui.module_shop_redpacket.tag_best");
            String me = tr("gui.module_shop_redpacket.tag_me");
            float tags = (r.me() ? V2.tagWidth(me) : 0) + (r.best() ? V2.tagWidth(best) : 0);
            float avail = Math.max(10, cols[1][1] - 13 - tags);
            float nameW = V2.text(cv, r.name(), V2.FS_S, r.me() ? gold : ShopTheme.text(), nx + 13, cy, avail,
                    Text.Align.LEFT);
            float tagX = nx + 13 + nameW + 3;
            if (r.me()) {
                tagX += V2.tag(cv, me, gold, tagX, cy);
            }
            if (r.best()) {
                V2.tag(cv, best, gold, tagX, cy);
            }
            V2.text(cv, TIME.format(Instant.ofEpochMilli(r.time())), V2.FS_S, ShopTheme.text2(), cols[2][0], cy,
                    cols[2][1], Text.Align.LEFT);
            V2.bold(cv, V2.money(r.amount()), V2.FS_S, r.best() ? gold : ShopTheme.text(), cols[3][0], cy, cols[3][1],
                    Text.Align.RIGHT);
            float barW = 18;
            float px2 = cols[4][0];
            V2.text(cv, pct(r.amount(), total), V2.FS_XS, ShopTheme.text2(), px2, cy, cols[4][1] - barW - 4, Text.Align.RIGHT);
            V2.bar(cv, px2 + cols[4][1] - barW, cy, barW, r.amount() / (float) maxAmount, r.best() ? gold : ShopTheme.red());
        });

        // 合计行
        float sy = y + h - sumH;
        Draw.fill(c, x, sy, w - 1, sumH, 0, Draw.alpha(ShopTheme.card(), 0.8F));
        Draw.hairline(c, x, sy, w - 1, ShopTheme.borderDim());
        float cy = sy + sumH / 2;
        if (this.rows.isEmpty()) {
            V2.text(c, tr("gui.module_shop_redpacket.detail_empty"), V2.FS_S, ShopTheme.text3(), cols[1][0], cy,
                    cols[1][1] + 60, Text.Align.LEFT);
        } else {
            V2.bold(c, tr("gui.module_shop_redpacket.sum_label", this.rows.size(), count), V2.FS_S, ShopTheme.text(),
                    cols[0][0], cy, cols[2][0] + cols[2][1] - cols[0][0], Text.Align.LEFT);
        }
        V2.bold(c, V2.money(claimed), V2.FS_S, ShopTheme.gold(), cols[3][0], cy, cols[3][1], Text.Align.RIGHT);
        V2.bold(c, pct(claimed, total), V2.FS_S, ShopTheme.text(), cols[4][0], cy, cols[4][1], Text.Align.RIGHT);
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        metrics();
        float mx = toUnits(mouseX);
        float my = toUnits(mouseY);
        if (button == 0) {
            if (in(this.closeRect, mx, my)) {
                onClose();
                return true;
            }
            if (in(this.copyRect, mx, my)) {
                Minecraft.getInstance().keyboardHandler.setClipboard(this.data.getString("id"));
                return true;
            }
            if (TABLE.pressBar(mx, my)) {
                return true;
            }
            if (mx < this.px || mx >= this.px + this.pw || my < this.py || my >= this.py + this.ph) {
                onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return TABLE.releaseBar() || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return TABLE.dragBar(toUnits(mouseY)) || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        TABLE.wheel(toUnits(mouseX), toUnits(mouseY), scrollY, hasShiftDown());
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent instanceof RedPacketDetailAtomScreen
                    || this.parent instanceof RedPacketDetailScreen ? null : this.parent);
        }
    }
}
