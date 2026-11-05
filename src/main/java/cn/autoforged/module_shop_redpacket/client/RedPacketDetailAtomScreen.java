package cn.autoforged.module_shop_redpacket.client;

import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.SkiaFrame;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import io.github.humbleui.skija.Canvas;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/**
 * 红包详情（AtomLib / Skija 版，点聊天栏「详情」打开）：信封头里是发送者、祝福语和自己领到的金额（大字），
 * 下面是状态行与领取列表（谁、什么时候、多少，手气最佳打标）。AtomLib 不可用时用 {@link RedPacketDetailScreen}。
 */
public class RedPacketDetailAtomScreen extends Screen {
    private static final float W = 250.0F;
    private static final float H = 236.0F;
    private static final float HEADER_H = 92.0F;
    private static final float ROW_H = 22.0F;
    private static final int ENVELOPE = 0xFFD84343;
    private static final int ENVELOPE_DARK = 0xFFB33434;
    private static final int SEAL = 0xFFFFC94A;
    private static final int CREAM = 0xFFFFE6C0;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private record Row(String name, long amount, long time, boolean me, boolean best) {
    }

    private final Screen parent;
    private CompoundTag data;
    private final List<Row> rows = new ArrayList<>();
    private final ScrollController scroll = new ScrollController();
    private final Button done = new Button(Button.Kind.NORMAL,
            () -> Component.translatable("gui.done").getString(), this::onClose).radius(2.0F).fontSize(Text.SMALL);
    private final Motion.Clock clock = new Motion.Clock();
    private float openT;

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
    }

    private float left() {
        return (this.width - W) / 2.0F;
    }

    private float top() {
        return (this.height - H) / 2.0F;
    }

    private float listTop() {
        return top() + HEADER_H + 22.0F;
    }

    private float listBottom() {
        return top() + H - 28.0F;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        float dt = this.clock.tick();
        this.openT = Motion.approach(this.openT, 1.0F, dt, 90.0F);
        SkiaFrame.draw(g, canvas -> paint(canvas, mouseX, mouseY, dt));
    }

    private void paint(Canvas canvas, float mouseX, float mouseY, float dt) {
        float x = left();
        float y = top();
        float cx = x + W / 2.0F;
        String currency = this.data.getString("currency");
        long total = this.data.getLong("total");
        int count = this.data.getInt("count");

        // 卡片 + 信封头
        Draw.fill(canvas, x - 1.0F, y - 1.0F, W + 2.0F, H + 2.0F, 6.0F, ShopTheme.borderDim());
        Draw.fill(canvas, x, y, W, H, 5.0F, ShopTheme.content());
        Draw.fill(canvas, x, y, W, HEADER_H, 5.0F, ENVELOPE);
        Draw.fill(canvas, x, y + HEADER_H - 12.0F, W, 12.0F, 0.0F, ENVELOPE_DARK);
        Draw.fill(canvas, x, y + HEADER_H - 2.0F, W, 2.0F, 0.0F, SEAL);
        Draw.fill(canvas, cx - 7.0F, y + HEADER_H - 8.0F, 14.0F, 14.0F, 7.0F, SEAL);

        Text.drawLine(canvas, Component.translatable("gui.module_shop_redpacket.detail_from",
                this.data.getString("sender")).getString(), ShopTheme.bold(), Text.TITLE, SEAL,
                x + 8.0F, y + 11.0F, W - 16.0F, Text.Align.CENTER);
        String greetingRaw = this.data.getString("greeting");
        String greeting = greetingRaw.isEmpty()
                ? Component.translatable("msg.module_shop_redpacket.default_greeting").getString() : greetingRaw;
        Text.drawLine(canvas, greeting, ShopTheme.regular(), Text.BODY, CREAM, x + 8.0F, y + 24.0F, W - 16.0F,
                Text.Align.CENTER);

        Row mine = null;
        for (Row row : this.rows) {
            if (row.me()) {
                mine = row;
            }
        }
        if (mine != null) {
            // 金额从 0 滚到实际值（打开时的小动画）
            long shown = Math.round(mine.amount() * Motion.easeOutCubic(this.openT));
            Text.drawLine(canvas, Long.toString(shown), ShopTheme.bold(), 22.0F, SEAL, x, y + 46.0F, W,
                    Text.Align.CENTER);
            Text.drawLine(canvas, Component.translatable("gui.module_shop_redpacket.detail_mine", currency).getString(),
                    ShopTheme.regular(), Text.SMALL, CREAM, x, y + 62.0F, W, Text.Align.CENTER);
        } else {
            Text.drawLine(canvas, Component.translatable("gui.module_shop_redpacket.detail_not_mine").getString(),
                    ShopTheme.regular(), Text.BODY, CREAM, x, y + 48.0F, W, Text.Align.CENTER);
        }
        Component mode = Component.translatable(this.data.getBoolean("lucky")
                ? "gui.module_shop_redpacket.mode_lucky" : "gui.module_shop_redpacket.mode_even");
        String summary = Component.translatable("gui.module_shop_redpacket.detail_summary", mode, total, currency)
                .getString();
        if (this.data.getBoolean("hasPassword")) {
            summary += "  ·  " + Component.translatable("gui.module_shop_redpacket.detail_password",
                    this.data.getString("password")).getString();
        }
        Text.drawLine(canvas, summary, ShopTheme.regular(), Text.SMALL, 0xFFFFFFFF, x + 8.0F, y + 76.0F,
                W - 16.0F, Text.Align.CENTER);

        // 状态行
        Text.drawLine(canvas, statusLine(currency, total, count).getString(), ShopTheme.regular(), Text.SMALL,
                ShopTheme.text2(), x + 10.0F, y + HEADER_H + 11.0F, W - 20.0F, Text.Align.LEFT);

        // 领取列表
        float lt = listTop();
        float lb = listBottom();
        float lh = lb - lt;
        this.scroll.setContent(this.rows.size() * ROW_H, lh);
        this.scroll.update();
        if (this.rows.isEmpty()) {
            Text.drawLine(canvas, Component.translatable("gui.module_shop_redpacket.detail_empty").getString(),
                    ShopTheme.regular(), Text.BODY, ShopTheme.text3(), x, lt + 20.0F, W, Text.Align.CENTER);
        }
        int saved = canvas.save();
        try {
            Draw.clip(canvas, x, lt, W, lh);
            float rowX = x + 8.0F;
            float rowW = W - 16.0F - (this.scroll.canScroll() ? 4.0F : 0.0F);
            for (int i = 0; i < this.rows.size(); i++) {
                Row row = this.rows.get(i);
                float ry = lt + i * ROW_H - this.scroll.offset();
                if (ry + ROW_H < lt || ry > lb) {
                    continue;
                }
                if (row.me()) {
                    Draw.fill(canvas, rowX, ry + 1.0F, rowW, ROW_H - 2.0F, 3.0F, ShopTheme.redTint());
                } else if (mouseY >= ry && mouseY < ry + ROW_H && mouseX >= rowX && mouseX < rowX + rowW) {
                    Draw.fill(canvas, rowX, ry + 1.0F, rowW, ROW_H - 2.0F, 3.0F, ShopTheme.rowHover());
                }
                // 头像位：名字首字的小圆
                Draw.fill(canvas, rowX + 4.0F, ry + 4.0F, 14.0F, 14.0F, 7.0F, row.me() ? ENVELOPE : ShopTheme.card());
                String initial = row.name().isEmpty() ? "?" : row.name().substring(0, 1).toUpperCase();
                Text.drawLine(canvas, initial, ShopTheme.bold(), Text.SMALL, 0xFFFFFFFF, rowX + 4.0F, ry + 11.0F,
                        14.0F, Text.Align.CENTER);
                Text.drawLine(canvas, row.name(), ShopTheme.regular(), Text.BODY, ShopTheme.text(),
                        rowX + 22.0F, ry + 7.5F, rowW - 100.0F, Text.Align.LEFT);
                Text.drawLine(canvas, TIME.format(Instant.ofEpochMilli(row.time())), ShopTheme.regular(),
                        Text.SMALL, ShopTheme.text3(), rowX + 22.0F, ry + 16.0F, rowW - 100.0F, Text.Align.LEFT);
                Text.drawLine(canvas, row.amount() + " " + currency, ShopTheme.bold(), Text.BODY, ShopTheme.text(),
                        rowX, ry + 7.5F, rowW - 4.0F, Text.Align.RIGHT);
                if (row.best()) {
                    Text.drawLine(canvas, "★ " + Component.translatable("gui.module_shop_redpacket.best").getString(),
                            ShopTheme.bold(), Text.SMALL, ShopTheme.gold(), rowX, ry + 16.0F, rowW - 4.0F,
                            Text.Align.RIGHT);
                }
                Draw.hairline(canvas, rowX + 22.0F, ry + ROW_H - 0.5F, rowW - 22.0F, ShopTheme.borderDim());
            }
        } finally {
            canvas.restoreToCount(saved);
        }
        this.scroll.drawBar(canvas, x + 4.0F, lt, W - 8.0F, lh, mouseX, mouseY, dt);

        this.done.bounds(cx - 40.0F, y + H - 22.0F, 80.0F, 15.0F);
        this.done.render(canvas, mouseX, mouseY, dt);
    }

    private Component statusLine(String currency, long total, int count) {
        long claimed = 0L;
        for (Row row : this.rows) {
            claimed += row.amount();
        }
        if (this.data.getLong("refunded") > 0L) {
            return Component.translatable("gui.module_shop_redpacket.status_expired", this.rows.size(), count,
                    claimed, total, this.data.getLong("refunded"));
        }
        if (this.data.getBoolean("finished")) {
            long seconds = Math.max(1L, (this.data.getLong("finishedAt") - this.data.getLong("createdAt")) / 1000L);
            return Component.translatable("gui.module_shop_redpacket.status_done", count, total, currency,
                    duration(seconds));
        }
        long leftMin = Math.max(0L, (this.data.getLong("expiresAt") - this.data.getLong("now")) / 60_000L);
        return Component.translatable("gui.module_shop_redpacket.status_active", this.rows.size(), count,
                claimed, total, leftMin / 60L, leftMin % 60L);
    }

    private static Component duration(long seconds) {
        if (seconds < 60L) {
            return Component.translatable("msg.module_shop_redpacket.seconds", seconds);
        }
        if (seconds < 3600L) {
            return Component.translatable("msg.module_shop_redpacket.minutes", seconds / 60L, seconds % 60L);
        }
        return Component.translatable("msg.module_shop_redpacket.hours", seconds / 3600L, (seconds % 3600L) / 60L);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.done.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        float lt = listTop();
        if (button == 0 && this.scroll.pressBar(mouseX, mouseY, left() + 4.0F, lt, W - 8.0F, listBottom() - lt)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = this.scroll.releaseBar();
        handled |= this.done.mouseReleased(mouseX, mouseY, button);
        return handled || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return this.scroll.dragBar(mouseY, listBottom() - listTop())
                || super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scroll.wheel(scrollY);
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
