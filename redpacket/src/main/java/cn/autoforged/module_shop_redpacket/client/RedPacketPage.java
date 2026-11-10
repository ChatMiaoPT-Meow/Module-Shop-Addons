package cn.autoforged.module_shop_redpacket.client;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPage;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop_redpacket.RedPacketManager;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 「红包」内容区页面（UI v2）：顶部工具条（标题 · 冷却说明 · 余额）+ 左右两栏。
 *
 * <pre>
 * ┌ 发红包 ························· 发送有冷却 · 余额 1,234 星币 ┐
 * │ 左栏：发红包表单            │ 右栏：规则说明（表格）           │
 * │ 类型 / 个数 / 总额 / 口令 / │ ……                               │
 * │ 祝福语 / 预览 / [塞钱进红包]│ 最近查看的红包（小卡，点开详情） │
 * └ 底栏：个数与总额范围 ················ 口令红包需在聊天栏发送口令 ┘
 * </pre>
 */
public final class RedPacketPage implements AddonPage {
    private static final float LABEL_H = 9.0F;
    private static final float GAP = 5.0F;
    private static final float PAD = 8.0F;
    private static final int RED_A = 0xFFE0434D;
    private static final int RED_B = 0xFFF08A3C;
    private static final float CARD_H = 30.0F;

    private record Rule(String label, String text) {
    }

    private final ScrollController rulesScroll = new ScrollController();

    private AddonPageHost host;
    private SkiaTextField countField;
    private SkiaTextField totalField;
    private SkiaTextField passwordField;
    private SkiaTextField greetingField;

    // 本帧登记的命中区（render 写、mouseClicked 读，保证画在哪点在哪）
    private float[][] segRects = new float[0][];
    private float segY;
    private float[] allRect;
    private float[] sendRect;
    private float[] cardRect;
    private float[] rulesRect;

    @Override
    public void init(AddonPageHost host) {
        this.host = host;
        // 每次商店界面 init 都要重建输入框（它们绑定在当前 Screen 上）；内容从共享状态恢复。
        this.countField = field(host, 4, "gui.module_shop_redpacket.count_hint", RedPacketState.count,
                v -> RedPacketState.count = v, true);
        this.totalField = field(host, 15, "gui.module_shop_redpacket.total_hint", RedPacketState.total,
                v -> RedPacketState.total = v, true);
        this.passwordField = field(host, RedPacketManager.MAX_PASSWORD, "gui.module_shop_redpacket.password_hint",
                RedPacketState.password, v -> RedPacketState.password = v, false);
        this.greetingField = field(host, RedPacketManager.MAX_GREETING, "gui.module_shop_redpacket.greeting_hint",
                RedPacketState.greeting, v -> RedPacketState.greeting = v, false);
        // 每次进入页面都刷新货币名与限制（余额本身直接读主商店快照，见 render）。
        RedPacketState.requestStatus();
    }

    private static SkiaTextField field(AddonPageHost host, int max, String hintKey, String value,
            Consumer<String> sink, boolean digits) {
        SkiaTextField field = new SkiaTextField(host.screen(), host.font(), max)
                .placeholder(Component.translatable(hintKey).getString())
                .fontSize(V2.FS);
        if (digits) {
            field.editBox().setFilter(s -> s.chars().allMatch(Character::isDigit));
        }
        field.setValue(value == null ? "" : value);
        field.onChange(sink);
        host.registerInput(field.editBox());
        return field;
    }

    private List<SkiaTextField> fields() {
        return List.of(this.countField, this.totalField, this.passwordField, this.greetingField);
    }

    /** 共享状态被外部改动（发送成功后清空、点「全部」）时，把输入框内容同步过去。 */
    private static void sync(SkiaTextField field, String state) {
        if (!field.value().equals(state)) {
            field.setValue(state);
        }
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private void submit() {
        fields().forEach(SkiaTextField::blur);
        if (this.host == null) {
            RedPacketState.submit("");
        } else {
            RedPacketState.submit(this.host.currencyName(), this.host.balance());
        }
    }

    private static boolean over(AddonFrame f, float[] r) {
        return r != null && f.mouseX() >= r[0] && f.mouseX() < r[0] + r[2] && f.mouseY() >= r[1] && f.mouseY() < r[1] + r[3];
    }

    /** 自动换行的文字（顶对齐），返回占用高度。 */
    private static float wrap(Canvas c, String s, float size, int color, float x, float top, float w, int maxLines,
            boolean bold) {
        var layout = Text.wrapped(s, bold ? ShopTheme.bold() : ShopTheme.regular(), size, color, w, maxLines);
        Text.draw(c, layout, x, top);
        return layout.size().height();
    }

    private static float wrapHeight(String s, float size, float w, int maxLines) {
        return Text.wrapped(s, ShopTheme.regular(), size, 0xFFFFFFFF, w, maxLines).size().height();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas c, AddonFrame f) {
        String currency = RedPacketState.currencyName(f.host().currencyName());
        long balance = f.host().balance();
        float x = f.x();
        float y = f.y();
        float w = f.w();
        float lw = Math.max(224.0F, Math.min(300.0F, w * 0.46F));
        float bodyTop = y + V2.BAR;
        float footY = y + f.h() - V2.FOOT;

        drawToolbar(c, f, currency, balance);
        drawForm(c, f, x, bodyTop, lw, footY - bodyTop, currency, balance);
        Draw.fill(c, x + lw - 0.5F, bodyTop, 0.5F, footY - bodyTop, 0, ShopTheme.borderDim());
        drawRight(c, f, x + lw, bodyTop, w - lw, footY - bodyTop);

        V2.foot(c, x, footY, w,
                tr("gui.module_shop_redpacket.foot_limits", RedPacketState.maxCount, V2.money(RedPacketState.minTotal),
                        V2.money(RedPacketState.maxTotal), currency),
                tr("gui.module_shop_redpacket.foot_hint"));
    }

    private void drawToolbar(Canvas c, AddonFrame f, String currency, long balance) {
        float x = f.x();
        float cy = f.y() + V2.BAR / 2;
        Draw.hairline(c, x, f.y() + V2.BAR - 0.5F, f.w(), ShopTheme.borderDim());
        float tw = V2.bold(c, tr("gui.module_shop_redpacket.send_title"), V2.FS_T, ShopTheme.text(), x + PAD, cy, 120,
                Text.Align.LEFT);
        float right = x + f.w() - PAD;
        String money = V2.money(balance) + " " + currency;
        float mw = V2.width(money, V2.FS + 1, true);
        V2.bold(c, money, V2.FS + 1, ShopTheme.gold(), right - mw, cy, mw + 2, Text.Align.LEFT);
        String lab = tr("gui.module_shop_redpacket.balance_label");
        float lwid = V2.width(lab, V2.FS_XS, false);
        V2.text(c, lab, V2.FS_XS, ShopTheme.text3(), right - mw - 4 - lwid, cy, lwid + 2, Text.Align.LEFT);
        float noteRight = right - mw - 4 - lwid - 14;
        float noteLeft = x + PAD + tw + 12;
        V2.text(c, tr("gui.module_shop_redpacket.cooldown_note"), V2.FS_XS, ShopTheme.text3(), noteLeft, cy,
                noteRight - noteLeft, Text.Align.RIGHT);
    }

    private void drawForm(Canvas c, AddonFrame f, float x, float top, float lw, float h, String currency, long balance) {
        float fx = x + PAD;
        float cw = lw - PAD * 2;
        float bottom = top + h;
        Draw.fill(c, x, top, lw, 1.5F, 0, RED_A);

        // 限制与预览要实时算，先取出
        sync(this.countField, RedPacketState.count);
        sync(this.totalField, RedPacketState.total);
        sync(this.passwordField, RedPacketState.password);
        sync(this.greetingField, RedPacketState.greeting);
        float mx = f.mouseX();
        float my = f.mouseY();

        float cy = top + 7;
        // 类型
        V2.text(c, tr("gui.module_shop_redpacket.type"), V2.FS_XS, ShopTheme.text3(), fx, cy + LABEL_H / 2, cw,
                Text.Align.LEFT);
        cy += LABEL_H;
        this.segY = cy;
        this.segRects = V2.segmented(c, new String[]{tr("gui.module_shop_redpacket.mode_lucky_short"),
                tr("gui.module_shop_redpacket.mode_even_short")}, RedPacketState.lucky ? 0 : 1, fx, cy, null);
        String modeHint = tr(RedPacketState.lucky ? "gui.module_shop_redpacket.hint_lucky" : "gui.module_shop_redpacket.hint_even");
        float sx = this.segRects[1][0] + this.segRects[1][1] + 8;
        V2.text(c, modeHint, V2.FS_XS, ShopTheme.text3(), sx, cy + V2.CTRL / 2, fx + cw - sx, Text.Align.LEFT);
        cy += V2.CTRL + GAP;

        // 个数
        label(c, tr("gui.module_shop_redpacket.count"), tr("gui.module_shop_redpacket.range",
                "1–" + RedPacketState.maxCount), null, fx, cy, cw);
        cy += LABEL_H;
        this.countField.bounds(fx, cy, cw, V2.CTRL).visible(true);
        cy += V2.CTRL + GAP;

        // 总额 + 「全部」
        label(c, tr("gui.module_shop_redpacket.total_lucky"), tr("gui.module_shop_redpacket.range",
                V2.money(RedPacketState.minTotal) + "–" + V2.money(RedPacketState.maxTotal)),
                tr("gui.module_shop_redpacket.balance_short", V2.money(balance)), fx, cy, cw);
        cy += LABEL_H;
        String allLabel = tr("gui.module_shop_redpacket.all");
        float aw = V2.buttonWidth(allLabel) + 4;
        this.totalField.bounds(fx, cy, cw - aw - 3, V2.CTRL).visible(true);
        this.allRect = new float[]{fx + cw - aw, cy, aw, V2.CTRL};
        V2.button(c, allLabel, balance > 0 ? V2.Btn.NORMAL : V2.Btn.DISABLED, this.allRect[0], cy, aw, V2.CTRL,
                over(f, this.allRect) ? 1.0F : 0.0F);
        cy += V2.CTRL + GAP;

        // 口令
        label(c, tr("gui.module_shop_redpacket.password"), RedPacketState.password.length() + " / "
                + RedPacketManager.MAX_PASSWORD, null, fx, cy, cw);
        cy += LABEL_H;
        this.passwordField.bounds(fx, cy, cw, V2.CTRL).visible(true);
        cy += V2.CTRL + GAP;

        // 祝福语
        label(c, tr("gui.module_shop_redpacket.greeting"), RedPacketState.greeting.length() + " / "
                + RedPacketManager.MAX_GREETING, null, fx, cy, cw);
        cy += LABEL_H;
        this.greetingField.bounds(fx, cy, cw, V2.CTRL).visible(true);
        cy += V2.CTRL + GAP;

        for (SkiaTextField field : fields()) {
            field.render(c, mx, my, f.dtMs());
        }

        // 底部：发送按钮（贴底）、提示行、预览（中间弹性）
        float sendH = V2.CTRL + 2;
        float sendY = bottom - 6 - sendH;
        float msgCy = sendY - 8;
        float prevH = Math.max(26.0F, Math.min(46.0F, msgCy - 6 - cy));
        drawPreview(c, fx, cy, cw, prevH, currency);

        Component live = RedPacketState.validate(currency, balance, false);
        String msg;
        int msgColor;
        if (live != null) {
            msg = live.getString();
            msgColor = ShopTheme.red();
        } else if (!RedPacketState.message.getString().isEmpty()) {
            msg = RedPacketState.message.getString();
            msgColor = RedPacketState.messageError ? ShopTheme.red()
                    : (System.currentTimeMillis() - RedPacketState.sentAt < 4000L ? ShopTheme.green() : ShopTheme.text2());
        } else {
            msg = "";
            msgColor = ShopTheme.text2();
        }
        V2.text(c, msg, V2.FS_S, msgColor, fx, msgCy, cw, Text.Align.LEFT);

        this.sendRect = new float[]{fx, sendY, cw, sendH};
        boolean waiting = RedPacketState.waiting;
        float hover = !waiting && over(f, this.sendRect) ? 1.0F : 0.0F;
        Draw.gradientH(c, fx, sendY, cw, sendH, V2.R, Draw.lerp(RED_A, 0xFFFFFFFF, 0.14F * hover),
                Draw.lerp(RED_B, 0xFFFFFFFF, 0.14F * hover));
        if (waiting) {
            Draw.fill(c, fx, sendY, cw, sendH, V2.R, Draw.alpha(ShopTheme.content(), 0.45F));
        }
        V2.bold(c, tr(waiting ? "gui.module_shop_redpacket.sending" : "gui.module_shop_redpacket.send"), V2.FS, 0xFFFFFFFF,
                fx, sendY + sendH / 2, cw, Text.Align.CENTER);
    }

    private static void label(Canvas c, String left, String rightText, String rightGold, float x, float top, float w) {
        float cy = top + LABEL_H / 2;
        V2.text(c, left, V2.FS_XS, ShopTheme.text3(), x, cy, w, Text.Align.LEFT);
        float right = x + w;
        if (rightGold != null) {
            float gw = V2.width(rightGold, V2.FS_XS, true);
            V2.bold(c, rightGold, V2.FS_XS, ShopTheme.gold(), right - gw, cy, gw + 2, Text.Align.LEFT);
            right -= gw + 6;
        }
        if (rightText != null) {
            float rw = V2.width(rightText, V2.FS_XS, false);
            V2.text(c, rightText, V2.FS_XS, ShopTheme.text3(), right - rw, cy, rw + 2, Text.Align.LEFT);
        }
    }

    private void drawPreview(Canvas c, float x, float y, float w, float h, String currency) {
        Draw.fill(c, x, y, w, h, V2.R, Draw.alpha(RED_A, 0.10F));
        Draw.stroke(c, x, y, w, h, V2.R, 0.5F, Draw.alpha(RED_A, 0.45F));
        long cnt = RedPacketState.parse(RedPacketState.count);
        long total = RedPacketState.parse(RedPacketState.total);
        boolean ok = cnt >= 1L && total >= cnt;
        float tx = x + 7;
        float tw = w - 14;
        if (!ok) {
            wrap(c, tr("gui.module_shop_redpacket.preview_idle", RedPacketState.expireMinutes > 0
                    ? RedPacketView.minutesText(RedPacketState.expireMinutes) : ""), V2.FS_S, ShopTheme.text2(), tx, y + 6, tw,
                    h >= 38 ? 3 : 2, false);
            return;
        }
        V2.bold(c, tr("gui.module_shop_redpacket.preview_head", cnt, V2.money(total), currency), V2.FS + 1,
                ShopTheme.text(), tx, y + 9, tw, Text.Align.LEFT);
        String detail;
        if (RedPacketState.lucky) {
            detail = tr("gui.module_shop_redpacket.preview_lucky_note");
        } else if (total % cnt == 0L) {
            detail = tr("gui.module_shop_redpacket.preview_even_each", V2.money(total / cnt), currency);
        } else {
            detail = tr("gui.module_shop_redpacket.preview_even_rem_each", V2.money(total / cnt),
                    V2.money(total / cnt + 1), total % cnt, currency);
        }
        wrap(c, detail, V2.FS_S, ShopTheme.text2(), tx, y + 17, tw, h >= 38 ? 2 : 1, false);
    }

    // ------------------------------------------------------------------ 右栏

    private List<Rule> rules() {
        return List.of(
                new Rule(tr("gui.module_shop_redpacket.rule_once"), tr("gui.module_shop_redpacket.rule_once_t")),
                new Rule(tr("gui.module_shop_redpacket.rule_expire"), tr("gui.module_shop_redpacket.rule_expire_t",
                        RedPacketView.minutesText(RedPacketState.expireMinutes))),
                new Rule(tr("gui.module_shop_redpacket.rule_refund"), tr("gui.module_shop_redpacket.rule_refund_t")),
                new Rule(tr("gui.module_shop_redpacket.rule_mail"), tr("gui.module_shop_redpacket.rule_mail_t")),
                new Rule(tr("gui.module_shop_redpacket.mode_lucky_short"), tr("gui.module_shop_redpacket.rule_lucky_t")),
                new Rule(tr("gui.module_shop_redpacket.mode_even_short"), tr("gui.module_shop_redpacket.rule_even_t")),
                new Rule(tr("gui.module_shop_redpacket.rule_password"), tr("gui.module_shop_redpacket.rule_password_t",
                        RedPacketManager.MAX_PASSWORD)),
                new Rule(tr("gui.module_shop_redpacket.rule_claim"), tr("gui.module_shop_redpacket.rule_claim_t")),
                new Rule(tr("gui.module_shop_redpacket.rule_announce"), tr("gui.module_shop_redpacket.rule_announce_t")));
    }

    private void drawRight(Canvas c, AddonFrame f, float x, float top, float w, float h) {
        CompoundTag last = RedPacketState.lastDetail;
        float bottom = top + h;
        float cardBlock = last == null ? 0 : LABEL_H + CARD_H + 14;
        float tableBottom = bottom - cardBlock;

        // 规则表
        float[][] cols = V2.columns(x, w, PAD, 6, 52, 0);
        V2.tableHead(c, x, top, w, cols, new String[]{tr("gui.module_shop_redpacket.rules_item"),
                tr("gui.module_shop_redpacket.rules_desc")}, new Text.Align[]{Text.Align.LEFT, Text.Align.LEFT}, -1, false);
        float listTop = top + V2.HEAD;
        float viewH = Math.max(0, tableBottom - listTop);
        this.rulesRect = new float[]{x, listTop, w, viewH};
        List<Rule> rules = rules();
        float[] hs = new float[rules.size()];
        float total = 0;
        float descW = cols[1][1] - (viewH > 0 ? 4 : 0);
        for (int i = 0; i < hs.length; i++) {
            hs[i] = Math.max(V2.ROW, wrapHeight(rules.get(i).text(), V2.FS_S, descW, 3) + 7);
            total += hs[i];
        }
        this.rulesScroll.setContent(total, viewH);
        this.rulesScroll.update();
        int saved = c.save();
        Draw.clip(c, x, listTop, w, viewH);
        float ry = listTop - this.rulesScroll.offset();
        for (int i = 0; i < hs.length; i++) {
            if (ry + hs[i] >= listTop && ry <= listTop + viewH) {
                V2.rowBg(c, x, ry, w, hs[i], i, 0, false);
                V2.bold(c, rules.get(i).label(), V2.FS_S, i == 4 ? ShopTheme.red() : ShopTheme.text(), cols[0][0],
                        ry + 7.5F, cols[0][1], Text.Align.LEFT);
                wrap(c, rules.get(i).text(), V2.FS_S, ShopTheme.text2(), cols[1][0], ry + 3.5F, descW, 3, false);
            }
            ry += hs[i];
        }
        c.restoreToCount(saved);
        this.rulesScroll.drawBar(c, x, listTop, w, viewH, f.mouseX(), f.mouseY(), f.dtMs());

        // 最近查看的红包
        this.cardRect = null;
        if (last != null) {
            float cy = tableBottom + 7;
            V2.section(c, tr("gui.module_shop_redpacket.recent"), x + PAD, cy + LABEL_H / 2, w - PAD * 2);
            float cardY = cy + LABEL_H + 2;
            this.cardRect = new float[]{x + PAD, cardY, w - PAD * 2, CARD_H};
            drawRecent(c, f, last, this.cardRect);
        }
    }

    private void drawRecent(Canvas c, AddonFrame f, CompoundTag t, float[] r) {
        boolean hover = over(f, r);
        Draw.fill(c, r[0], r[1], r[2], r[3], V2.R, hover ? ShopTheme.rowHover() : ShopTheme.card());
        Draw.stroke(c, r[0], r[1], r[2], r[3], V2.R, 0.5F, hover ? ShopTheme.border() : ShopTheme.borderDim());
        float x = r[0] + 7;
        float right = r[0] + r[2] - 7;
        RedPacketView.Status st = RedPacketView.status(t);
        int stColor = st == RedPacketView.Status.ACTIVE ? ShopTheme.gold()
                : st == RedPacketView.Status.DONE ? ShopTheme.green() : ShopTheme.text3();
        float cy1 = r[1] + 9;
        float tx = x;
        tx += V2.tag(c, RedPacketView.modeName(t), t.getBoolean("lucky") ? ShopTheme.red() : ShopTheme.accent(), tx, cy1);
        tx += V2.tag(c, RedPacketView.statusName(st), stColor, tx, cy1);
        String open = tr("gui.module_shop_redpacket.recent_open");
        float ow = V2.width(open, V2.FS_XS, false);
        V2.text(c, open, V2.FS_XS, hover ? ShopTheme.accent() : ShopTheme.text3(), right - ow, cy1, ow + 2, Text.Align.LEFT);
        V2.bold(c, RedPacketView.greeting(t), V2.FS_S, ShopTheme.text(), tx + 2, cy1, right - ow - 6 - tx - 2,
                Text.Align.LEFT);
        String cur = t.getString("currency");
        V2.text(c, tr("gui.module_shop_redpacket.recent_line", t.getString("sender"), RedPacketView.claimCount(t),
                t.getInt("count"), V2.money(RedPacketView.claimed(t)), V2.money(t.getLong("total")), cur), V2.FS_XS,
                ShopTheme.text2(), x, r[1] + 21, r[2] - 14, Text.Align.LEFT);
    }

    // ------------------------------------------------------------------ 输入

    private void setLucky(boolean lucky) {
        RedPacketState.lucky = lucky;
    }

    private void reopen(CompoundTag last) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        // 先用缓存的回包立刻打开，再向服务端要一份新的（回包到了会原地刷新）。
        mc.setScreen(new RedPacketDetailAtomScreen(last, mc.screen));
        mc.player.connection.sendCommand("redpacket info " + last.getString("id"));
    }

    @Override
    public boolean mouseClicked(AddonFrame f, double mx, double my, int button) {
        if (button == 0) {
            if (this.rulesRect != null && this.rulesScroll.pressBar(mx, my, this.rulesRect[0], this.rulesRect[1],
                    this.rulesRect[2], this.rulesRect[3])) {
                return true;
            }
            for (int i = 0; i < this.segRects.length; i++) {
                float[] s = this.segRects[i];
                if (mx >= s[0] && mx < s[0] + s[1] && my >= this.segY && my < this.segY + V2.CTRL) {
                    setLucky(i == 0);
                    return true;
                }
            }
            if (inRect(this.allRect, mx, my)) {
                long balance = f.host().balance();
                if (balance > 0L) {
                    RedPacketState.total = Long.toString(Math.min(balance, RedPacketState.maxTotal));
                }
                return true;
            }
            if (inRect(this.sendRect, mx, my)) {
                submit();
                return true;
            }
            if (this.cardRect != null && inRect(this.cardRect, mx, my) && RedPacketState.lastDetail != null) {
                reopen(RedPacketState.lastDetail);
                return true;
            }
        }
        boolean hit = false;
        for (SkiaTextField field : fields()) {
            if (field.mouseClicked(mx, my, button)) {
                hit = true;
            } else {
                field.blur();
            }
        }
        return hit;
    }

    private static boolean inRect(float[] r, double mx, double my) {
        return r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mx, double my, int button) {
        boolean handled = this.rulesScroll.releaseBar();
        for (SkiaTextField field : fields()) {
            handled |= field.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mx, double my, int button, double dx, double dy) {
        if (this.rulesRect != null && this.rulesScroll.dragBar(my, this.rulesRect[3])) {
            return true;
        }
        for (SkiaTextField field : fields()) {
            if (field.mouseDragged(mx, my, button, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mx, double my, double scrollY) {
        if (inRect(this.rulesRect, mx, my)) {
            this.rulesScroll.wheel(scrollY * 1.25);
            return true;
        }
        return false;
    }

    @Override
    public void clickedOutside() {
        if (this.countField != null) {
            fields().forEach(SkiaTextField::blur);
        }
    }

    @Override
    public void cancelInput() {
        this.rulesScroll.releaseBar();
        if (this.countField != null) {
            for (SkiaTextField field : fields()) {
                field.blur();
                field.visible(false);   // 切走后隐藏背后的输入框，下次渲染时重新显示
            }
        }
    }
}
