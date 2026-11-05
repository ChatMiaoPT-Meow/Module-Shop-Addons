package cn.autoforged.module_shop_redpacket.client;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPage;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop_redpacket.RedPacketManager;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;

/**
 * 「红包」内容区页面（AtomLib / Skija，和内置模块同一套控件与配色）。
 *
 * <pre>
 * ┌ 红色信封头：发红包 ·························· 余额 1,234 星币 ┐
 * │ [ 拼手气红包 ]  [ 普通红包（均分） ]                               │
 * │ 红包个数 [____]          总金额 [________]                         │
 * │ 口令     [________________________________]                        │
 * │ 祝福语   [________________________________]                        │
 * │ 10 个红包，共 1000 星币，金额随机                                   │
 * │ (错误 / 已发出 提示)                                                │
 * └ 未领完的部分 24 小时后自动退回 ················· [ 塞钱进红包 ] ┘
 * </pre>
 */
public final class RedPacketPage implements AddonPage {
    private static final float HEADER_H = 30.0F;
    private static final float ROW_H = 19.0F;
    private static final float FIELD_H = 14.0F;
    private static final float LABEL_W = 46.0F;
    /** 信封红（固定色，不随主题变）与金色封口。 */
    private static final int ENVELOPE = 0xFFD84343;
    private static final int ENVELOPE_DARK = 0xFFB33434;
    private static final int SEAL = 0xFFFFC94A;

    private final Button luckyBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop_redpacket.mode_lucky").getString(), () -> setLucky(true))
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button evenBtn = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.module_shop_redpacket.mode_even").getString(), () -> setLucky(false))
            .radius(2.0F).fontSize(Text.SMALL);
    private final Button send = new Button(Button.Kind.DANGER,
            () -> Component.translatable(RedPacketState.waiting
                    ? "gui.module_shop_redpacket.sending" : "gui.module_shop_redpacket.send").getString(), null)
            .radius(2.0F).fontSize(Text.SMALL);

    private AddonPageHost host;
    private SkiaTextField countField;
    private SkiaTextField totalField;
    private SkiaTextField passwordField;
    private SkiaTextField greetingField;

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
        this.send.onClick(this::submit);
        if (RedPacketState.balance < 0L) {
            RedPacketState.requestStatus();
        }
    }

    private static SkiaTextField field(AddonPageHost host, int max, String hintKey, String value,
            Consumer<String> sink, boolean digits) {
        SkiaTextField field = new SkiaTextField(host.screen(), host.font(), max)
                .placeholder(Component.translatable(hintKey).getString())
                .fontSize(Text.SMALL);
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

    private List<Button> buttons() {
        return List.of(this.luckyBtn, this.evenBtn, this.send);
    }

    private void setLucky(boolean lucky) {
        RedPacketState.lucky = lucky;
    }

    private void submit() {
        fields().forEach(SkiaTextField::blur);
        RedPacketState.submit(this.host == null ? "" : this.host.currencyName());
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Canvas canvas, AddonFrame f) {
        String currency = RedPacketState.currencyName(f.host().currencyName());
        float x = f.x();
        float y = f.y() + 2.0F;
        float w = f.w();

        // 信封头：红底 + 底边金色封口线 + 中间小圆封口
        Draw.fill(canvas, x, y, w, HEADER_H, 3.0F, ENVELOPE);
        Draw.fill(canvas, x, y + HEADER_H - 7.0F, w, 7.0F, 0.0F, ENVELOPE_DARK);
        Draw.fill(canvas, x, y + HEADER_H - 2.0F, w, 2.0F, 0.0F, SEAL);
        Draw.fill(canvas, x + w / 2.0F - 5.0F, y + HEADER_H - 6.0F, 10.0F, 10.0F, 5.0F, SEAL);
        Text.drawLine(canvas, Component.translatable("gui.module_shop_redpacket.send_title").getString(),
                ShopTheme.bold(), Text.TITLE, 0xFFFFF4E0, x + 8.0F, y + 11.0F, 120.0F, Text.Align.LEFT);
        long balance = RedPacketState.balance >= 0L ? RedPacketState.balance : f.host().balance();
        String balanceText = Component.translatable("gui.module_shop_redpacket.balance", balance, currency).getString();
        Text.drawLine(canvas, balanceText, ShopTheme.regular(), Text.SMALL, 0xFFFFE6C0,
                x + w - 8.0F - 140.0F, y + 11.0F, 140.0F, Text.Align.RIGHT);

        // 模式：两个分段按钮，选中的用红色
        float segY = y + HEADER_H + 6.0F;
        float segW = (w - 4.0F) / 2.0F;
        this.luckyBtn.kind(RedPacketState.lucky ? Button.Kind.DANGER : Button.Kind.NORMAL)
                .bounds(x, segY, segW, FIELD_H);
        this.evenBtn.kind(RedPacketState.lucky ? Button.Kind.NORMAL : Button.Kind.DANGER)
                .bounds(x + segW + 4.0F, segY, segW, FIELD_H);
        this.luckyBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.evenBtn.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());

        // 表单
        float rowY = segY + FIELD_H + 6.0F;
        float half = (w - 6.0F) / 2.0F;
        label(canvas, "gui.module_shop_redpacket.count", x, rowY);
        this.countField.bounds(x + LABEL_W, rowY, half - LABEL_W, FIELD_H).visible(true);
        label(canvas, "gui.module_shop_redpacket.total_lucky", x + half + 6.0F, rowY);
        this.totalField.bounds(x + half + 6.0F + LABEL_W, rowY, half - LABEL_W, FIELD_H).visible(true);
        rowY += ROW_H;
        label(canvas, "gui.module_shop_redpacket.password", x, rowY);
        this.passwordField.bounds(x + LABEL_W, rowY, w - LABEL_W, FIELD_H).visible(true);
        rowY += ROW_H;
        label(canvas, "gui.module_shop_redpacket.greeting", x, rowY);
        this.greetingField.bounds(x + LABEL_W, rowY, w - LABEL_W, FIELD_H).visible(true);
        for (SkiaTextField field : fields()) {
            field.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        }
        rowY += ROW_H + 2.0F;

        // 预览卡片 + 提示
        Draw.fill(canvas, x, rowY, w, 30.0F, 3.0F, ShopTheme.card());
        Draw.stroke(canvas, x, rowY, w, 30.0F, 3.0F, 1.0F, ShopTheme.borderDim());
        Text.drawLine(canvas, RedPacketState.preview(currency).getString(), ShopTheme.regular(), Text.SMALL,
                ShopTheme.text2(), x + 8.0F, rowY + 9.0F, w - 16.0F, Text.Align.LEFT);
        if (!RedPacketState.message.getString().isEmpty()) {
            int color = RedPacketState.messageError ? ShopTheme.red()
                    : (System.currentTimeMillis() - RedPacketState.sentAt < 4000L ? ShopTheme.green() : ShopTheme.text2());
            Text.drawLine(canvas, RedPacketState.message.getString(), ShopTheme.regular(), Text.SMALL, color,
                    x + 8.0F, rowY + 21.0F, w - 16.0F, Text.Align.LEFT);
        }

        // 底栏：说明 + 发送按钮
        float barMid = f.barTop() + AddonFrame.BAR_H / 2.0F;
        float sendW = 72.0F;
        this.send.bounds(x + w - sendW, barMid - 7.0F, sendW, 14.0F);
        this.send.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        Text.drawLine(canvas, Component.translatable("gui.module_shop_redpacket.hint").getString(),
                ShopTheme.regular(), Text.SMALL, ShopTheme.text3(), x + 2.0F, barMid, w - sendW - 10.0F,
                Text.Align.LEFT);
    }

    private static void label(Canvas canvas, String key, float x, float rowY) {
        Text.drawLine(canvas, Component.translatable(key).getString(), ShopTheme.regular(), Text.SMALL,
                ShopTheme.text2(), x, rowY + FIELD_H / 2.0F, LABEL_W - 4.0F, Text.Align.LEFT);
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(AddonFrame f, double mx, double my, int button) {
        for (Button b : buttons()) {
            if (b.mouseClicked(mx, my, button)) {
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

    @Override
    public boolean mouseReleased(AddonFrame f, double mx, double my, int button) {
        boolean handled = false;
        for (Button b : buttons()) {
            handled |= b.mouseReleased(mx, my, button);
        }
        for (SkiaTextField field : fields()) {
            handled |= field.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mx, double my, int button, double dx, double dy) {
        for (SkiaTextField field : fields()) {
            if (field.mouseDragged(mx, my, button, dx, dy)) {
                return true;
            }
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
        for (Button b : buttons()) {
            b.cancelPress();
        }
        if (this.countField != null) {
            for (SkiaTextField field : fields()) {
                field.blur();
                field.visible(false);   // 切走后隐藏背后的输入框，下次渲染时重新显示
            }
        }
    }
}
