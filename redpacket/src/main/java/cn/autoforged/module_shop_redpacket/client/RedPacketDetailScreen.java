package cn.autoforged.module_shop_redpacket.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 红包详情（点聊天栏里的「详情」打开）：发送者、祝福语、总额、已领 / 总个数、状态，以及每个人领了多少、手气最佳。
 */
public class RedPacketDetailScreen extends Screen {
    private static final int W = 248;
    private static final int H = 232;
    private static final int HEADER_H = 92;
    private static final int ROW_H = 20;
    private static final int RED = 0xFFD84343;
    private static final int RED_DARK = 0xFFA82E2E;
    private static final int PAPER = 0xFFFFF4E0;
    private static final int GOLD = 0xFFFFC94A;
    private static final int TEXT = 0xFF4A2A10;
    private static final int MUTED = 0xFF8A6A50;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private record Row(String name, long amount, long time, boolean me, boolean best) {
    }

    private final Screen parent;
    private CompoundTag data;
    private final List<Row> rows = new ArrayList<>();
    private double scroll;

    public RedPacketDetailScreen(CompoundTag data, Screen parent) {
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

    private int left() {
        return (this.width - W) / 2;
    }

    private int top() {
        return (this.height - H) / 2;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left() + (W - 90) / 2, top() + H - 26, 90, 20).build());
    }

    private int listTop() {
        return top() + HEADER_H + 22;
    }

    private int listBottom() {
        return top() + H - 32;
    }

    private int maxScroll() {
        return Math.max(0, this.rows.size() * ROW_H - (listBottom() - listTop()));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scroll = Mth.clamp(this.scroll - scrollY * ROW_H, 0, maxScroll());
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        g.fill(x - 1, y - 1, x + W + 1, y + H + 1, RED_DARK);
        g.fill(x, y, x + W, y + H, PAPER);
        g.fill(x, y, x + W, y + HEADER_H, RED);
        g.fill(x, y + HEADER_H, x + W, y + HEADER_H + 2, GOLD);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        int cx = x + W / 2;
        String currency = this.data.getString("currency");
        long total = this.data.getLong("total");
        int count = this.data.getInt("count");
        boolean lucky = this.data.getBoolean("lucky");

        g.drawCenteredString(this.font, Component.translatable("gui.module_shop_redpacket.detail_from",
                this.data.getString("sender")), cx, y + 9, GOLD);
        String greetingRaw = this.data.getString("greeting");
        Component greeting = greetingRaw.isEmpty()
                ? Component.translatable("msg.module_shop_redpacket.default_greeting")
                : Component.literal(greetingRaw);
        g.drawCenteredString(this.font, greeting, cx, y + 21, 0xFFFFE6C0);

        // 自己领到的金额（大字）
        Row mine = null;
        for (Row row : this.rows) {
            if (row.me()) {
                mine = row;
            }
        }
        if (mine != null) {
            g.pose().pushPose();
            g.pose().translate(cx, y + 36, 0);
            g.pose().scale(2.0F, 2.0F, 1.0F);
            String amount = Long.toString(mine.amount());
            g.drawString(this.font, amount, -this.font.width(amount) / 2, 0, GOLD, true);
            g.pose().popPose();
            g.drawCenteredString(this.font, Component.translatable("gui.module_shop_redpacket.detail_mine", currency),
                    cx, y + 56, 0xFFFFE6C0);
        } else {
            g.drawCenteredString(this.font, Component.translatable("gui.module_shop_redpacket.detail_not_mine"),
                    cx, y + 42, 0xFFFFE6C0);
        }

        Component mode = Component.translatable(lucky
                ? "gui.module_shop_redpacket.mode_lucky" : "gui.module_shop_redpacket.mode_even");
        Component summary = Component.translatable("gui.module_shop_redpacket.detail_summary", mode,
                total, currency);
        g.drawCenteredString(this.font, summary, cx, y + 70, 0xFFFFFFFF);
        if (this.data.getBoolean("hasPassword")) {
            g.drawCenteredString(this.font, Component.translatable("gui.module_shop_redpacket.detail_password",
                    this.data.getString("password")), cx, y + 80, 0xFFFFE6C0);
        }

        // 状态行：已领 x/y，共 a/b；领完用时 / 已过期退回 / 剩余时间
        long claimed = 0L;
        for (Row row : this.rows) {
            claimed += row.amount();
        }
        Component status;
        long now = this.data.getLong("now");
        if (this.data.getLong("refunded") > 0L) {
            status = Component.translatable("gui.module_shop_redpacket.status_expired", this.rows.size(), count,
                    claimed, total, this.data.getLong("refunded"));
        } else if (this.data.getBoolean("finished")) {
            long seconds = Math.max(1L, (this.data.getLong("finishedAt") - this.data.getLong("createdAt")) / 1000L);
            status = Component.translatable("gui.module_shop_redpacket.status_done", count, total, currency,
                    formatDuration(seconds));
        } else {
            long leftMin = Math.max(0L, (this.data.getLong("expiresAt") - now) / 60_000L);
            status = Component.translatable("gui.module_shop_redpacket.status_active", this.rows.size(), count,
                    claimed, total, leftMin / 60L, leftMin % 60L);
        }
        g.drawString(this.font, status, x + 10, y + HEADER_H + 8, MUTED, false);

        // 领取列表
        int top = listTop();
        int bottom = listBottom();
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll());
        g.enableScissor(x, top, x + W, bottom);
        if (this.rows.isEmpty()) {
            g.drawCenteredString(this.font, Component.translatable("gui.module_shop_redpacket.detail_empty"),
                    cx, top + 10, MUTED);
        }
        for (int i = 0; i < this.rows.size(); i++) {
            Row row = this.rows.get(i);
            int ry = top + i * ROW_H - (int) this.scroll;
            if (ry + ROW_H < top || ry > bottom) {
                continue;
            }
            if (row.me()) {
                g.fill(x + 6, ry, x + W - 6, ry + ROW_H - 2, 0x33D84343);
            }
            g.drawString(this.font, row.name(), x + 12, ry + 2, TEXT, false);
            g.drawString(this.font, TIME.format(Instant.ofEpochMilli(row.time())), x + 12, ry + 11, MUTED, false);
            String amount = row.amount() + " " + currency;
            g.drawString(this.font, amount, x + W - 12 - this.font.width(amount), ry + 2, TEXT, false);
            if (row.best()) {
                Component best = Component.translatable("gui.module_shop_redpacket.best");
                g.drawString(this.font, best, x + W - 12 - this.font.width(best), ry + 11, 0xFFE09B00, false);
            }
            g.fill(x + 10, ry + ROW_H - 1, x + W - 10, ry + ROW_H, 0x22000000);
        }
        g.disableScissor();
        if (maxScroll() > 0) {
            int trackH = bottom - top;
            int thumbH = Math.max(12, trackH * trackH / (this.rows.size() * ROW_H));
            int thumbY = top + (int) ((trackH - thumbH) * (this.scroll / maxScroll()));
            g.fill(x + W - 4, thumbY, x + W - 2, thumbY + thumbH, 0x66000000);
        }
    }

    private static Component formatDuration(long seconds) {
        if (seconds < 60L) {
            return Component.translatable("msg.module_shop_redpacket.seconds", seconds);
        }
        if (seconds < 3600L) {
            return Component.translatable("msg.module_shop_redpacket.minutes", seconds / 60L, seconds % 60L);
        }
        return Component.translatable("msg.module_shop_redpacket.hours", seconds / 3600L, (seconds % 3600L) / 60L);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent instanceof RedPacketDetailScreen ? null : this.parent);
        }
    }
}
