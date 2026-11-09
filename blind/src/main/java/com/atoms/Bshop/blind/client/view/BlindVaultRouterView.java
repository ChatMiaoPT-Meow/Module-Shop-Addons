package com.atoms.Bshop.blind.client.view;

import com.atoms.Bshop.blind.auction.BlindCatalog;
import com.atoms.Bshop.blind.auction.BlindCatalog.Role;
import com.atoms.Bshop.blind.auction.BlindCatalog.Tool;
import com.atoms.Bshop.blind.auction.VaultZone;
import com.atoms.Bshop.blind.client.BlindAuctionState;
import com.atoms.Bshop.blind.client.BlindAuctionState.Item;
import com.atoms.Bshop.blind.client.BlindAuctionState.Log;
import com.atoms.Bshop.blind.client.BlindAuctionState.Result;
import com.atoms.Bshop.blind.client.BlindAuctionState.SeatResult;
import com.atoms.Bshop.blind.client.BlindAuctionState.SeatView;
import com.atoms.Bshop.blind.client.ui.BlindTheme;
import com.atoms.Bshop.blind.client.ui.Gfx;
import com.atoms.Bshop.blind.client.ui.Gfx.Align;
import com.atoms.Bshop.blind.network.BlindAuctionNetwork;
import java.util.ArrayList;
import java.util.List;

/**
 * 遗迹盲仓界面，按对局阶段切换：备战 → 匹配 → 竞拍 → 开仓。顶部只显示当前进度。
 * 只通过 {@link Gfx} 绘制（AtomLib 页面 / 原版回退共用）；配色来自商店皮肤，盈亏沿用股市的红涨绿跌。
 */
public class BlindVaultRouterView {
    private static final float SMALL = 6.5F;
    private static final float BODY = 8.0F;
    private static final float TITLE = 10.0F;
    private static final float R = 3.0F;
    private static final float CELL = 12.0F;

    private enum Kind { ACCENT, NORMAL, GHOST, DISABLED }

    private record ClickZone(float x, float y, float w, float h, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<ClickZone> clickZones = new ArrayList<>();
    private Gfx g;
    private double mx;
    private double my;
    private String hoverTip;

    public void render(Gfx gfx, float x, float y, float width, float height, double mouseX, double mouseY) {
        this.g = gfx;
        this.mx = mouseX;
        this.my = mouseY;
        this.hoverTip = null;
        clickZones.clear();

        renderTopBar(x, y, width);
        float by = y + 21;
        float bh = height - 21;
        if (!BlindAuctionState.received()) {
            g.text("正在连接盲仓…", x, by + bh / 2, width, Align.CENTER, BODY, false, BlindTheme.text3());
        } else if (BlindAuctionState.historyOpen()) {
            renderHistory(x, by, width, bh);
        } else {
            switch (BlindAuctionState.phase()) {
                case QUEUE -> renderQueue(x, by, width, bh);
                case MATCH -> renderMatch(x, by, width, bh);
                case RESULT -> renderResult(x, by, width, bh);
                default -> renderLoadout(x, by, width, bh);
            }
        }

        if (hoverTip != null) {
            float tw = Math.min(width - 4, g.width(hoverTip, SMALL, false) + 10);
            float tx = Math.max(x, Math.min(x + width - tw, (float) mx + 8));
            float ty = Math.max(y, (float) my - 15);
            g.fill(tx, ty, tw, 12, R, BlindTheme.text());
            g.text(hoverTip, tx + 5, ty + 6, tw - 10, Align.LEFT, SMALL, false, BlindTheme.content());
        }

        String toast = BlindAuctionState.activeToast();
        if (!toast.isEmpty()) {
            float ty = y + height - 16;
            g.fill(x + 30, ty, width - 60, 13, 6, BlindTheme.text());
            g.text(toast, x + 36, ty + 6.5F, width - 72, Align.CENTER, SMALL, false, BlindTheme.content());
        }
    }

    // ================================================================== 顶栏

    private void renderTopBar(float x, float y, float w) {
        g.text("遗迹盲仓", x + 2, y + 7, 46, Align.LEFT, TITLE, true, BlindTheme.text());
        String[] steps = {"备战", "匹配", "竞拍", "开仓"};
        int current = switch (BlindAuctionState.phase()) {
            case LOADOUT -> 0;
            case QUEUE -> 1;
            case MATCH -> 2;
            case RESULT -> 3;
        };
        float sx = x + 52;
        for (int i = 0; i < 4; i++) {
            boolean on = i == current;
            if (on) g.fill(sx, y + 1, 24, 12, 6, BlindTheme.accentTint());
            g.text(steps[i], sx, y + 7, 24, Align.CENTER, SMALL, on,
                    on ? BlindTheme.accent() : i < current ? BlindTheme.text2() : BlindTheme.text3());
            sx += 24;
            if (i < 3) {
                g.text("›", sx, y + 7, 7, Align.CENTER, SMALL, false, BlindTheme.text3());
                sx += 7;
            }
        }
        float hbW = 34;
        boolean open = BlindAuctionState.historyOpen();
        button(x + w - hbW, y + 1, hbW, 12, open ? "返回" : "成交记录", open ? Kind.ACCENT : Kind.NORMAL,
                () -> BlindAuctionState.setHistoryOpen(!BlindAuctionState.historyOpen()));
        String bal = "★ " + fmt(BlindAuctionState.balance());
        float balW = Math.min(64, g.width(bal, SMALL, false) + 10);
        g.fill(x + w - hbW - 3 - balW, y + 1, balW, 12, 6, BlindTheme.alpha(BlindTheme.gold(), 0.14F));
        g.text(bal, x + w - hbW - 3 - balW, y + 7, balW, Align.CENTER, SMALL, false, BlindTheme.gold());
        g.fill(x, y + 16, w, 1, 0, BlindTheme.borderDim());
    }

    // ================================================================== 备战

    private void renderLoadout(float x, float y, float w, float h) {
        long balance = BlindAuctionState.balance();

        section(x, y + 3, "① 选择盲仓");
        float zw = (w - 6) / 3;
        for (VaultZone zone : VaultZone.values()) {
            float zx = x + zone.id * (zw + 3);
            float zy = y + 8;
            boolean sel = BlindAuctionState.zone() == zone.id;
            boolean locked = balance < Math.max(zone.minStarCoinReq, zone.entryFee);
            card(zx, zy, zw, 26, sel, hit(zx, zy, zw, 26));
            g.icon(zone.iconItem, zone.title.substring(0, 1), BlindTheme.accent(), zx + 4, zy + 6, 14);
            g.text(zone.title, zx + 21, zy + 8, zw - 24, Align.LEFT, SMALL, true, sel ? BlindTheme.accent() : BlindTheme.text());
            g.text(locked ? "需持有 ≥ " + fmt(Math.max(zone.minStarCoinReq, zone.entryFee)) + " ★" : "门票 " + fmt(zone.entryFee) + " ★",
                    zx + 21, zy + 18, zw - 24, Align.LEFT, SMALL, false, locked ? BlindTheme.red() : BlindTheme.text3());
            if (hit(zx, zy, zw, 26)) hoverTip = zone.subTitle + " · 价值" + zone.rating;
            int id = zone.id;
            click(zx, zy, zw, 26, () -> BlindAuctionNetwork.sendAction(BlindAuctionNetwork.SET_ZONE, id, 0, 0));
        }

        section(x, y + 38, "② 选择鉴定师（技能按轮次自动生效，只有你看得到）");
        Role[] roles = Role.values();
        float rw = (w - (roles.length - 1) * 2) / roles.length;
        Role selRole = Role.byId(BlindAuctionState.role());
        for (int i = 0; i < roles.length; i++) {
            Role role = roles[i];
            float rx = x + i * (rw + 2);
            float ry = y + 43;
            boolean sel = role == selRole;
            boolean hov = hit(rx, ry, rw, 28);
            card(rx, ry, rw, 28, sel, hov);
            g.icon(null, role.glyph, sel ? BlindTheme.accent() : BlindTheme.text2(), rx + (rw - 12) / 2, ry + 3, 12);
            g.text(role.title, rx + 1, ry + 21, rw - 2, Align.CENTER, SMALL, sel, sel ? BlindTheme.accent() : BlindTheme.text());
            if (hov) hoverTip = role.desc;
            int idx = i;
            click(rx, ry, rw, 28, () -> BlindAuctionNetwork.sendAction(BlindAuctionNetwork.SET_ROLE, idx, 0, 0));
        }
        String d1 = fitPrefix(selRole.desc, w - 4);
        g.text(d1, x + 2, y + 78, w - 4, Align.LEFT, SMALL, false, BlindTheme.text2());
        if (d1.length() < selRole.desc.length()) {
            g.text(selRole.desc.substring(d1.length()), x + 2, y + 86, w - 4, Align.LEFT, SMALL, false, BlindTheme.text2());
        }

        section(x, y + 94, "③ 局内道具（每种每局 1 次，每轮最多用 1 个，提交报价前使用）");
        Tool[] tools = Tool.values();
        float tw = (w - (tools.length - 1) * 3) / tools.length;
        for (int i = 0; i < tools.length; i++) {
            Tool tool = tools[i];
            float tx = x + i * (tw + 3);
            float ty = y + 99;
            card(tx, ty, tw, 14, false, hit(tx, ty, tw, 14));
            g.icon(null, tool.glyph, BlindTheme.gold(), tx + 2, ty + 2, 10);
            g.text(tool.title, tx + 14, ty + 7, tw - 16, Align.LEFT, SMALL, false, BlindTheme.text());
            if (hit(tx, ty, tw, 14)) hoverTip = tool.desc;
        }

        float by = y + h - 19;
        boolean single = BlindAuctionState.single();
        segment(x, by + 2, 52, "联机匹配", !single, () -> BlindAuctionNetwork.sendAction(BlindAuctionNetwork.SET_SINGLE, 0, 0, 0));
        segment(x + 54, by + 2, 52, "单人对战AI", single, () -> BlindAuctionNetwork.sendAction(BlindAuctionNetwork.SET_SINGLE, 1, 0, 0));
        VaultZone zone = VaultZone.byId(BlindAuctionState.zone());
        boolean can = balance >= Math.max(zone.minStarCoinReq, zone.entryFee);
        g.text("门票 " + fmt(zone.entryFee) + " ★", x + 110, by + 8.5F, 70, Align.LEFT, SMALL, true,
                can ? BlindTheme.gold() : BlindTheme.red());
        button(x + w - 92, by, 92, 17, single ? "开始单人对局" : "开始匹配", can ? Kind.ACCENT : Kind.DISABLED,
                () -> BlindAuctionNetwork.sendAction(BlindAuctionNetwork.START, 0, 0, 0));
    }

    // ================================================================== 匹配

    private void renderQueue(float x, float y, float w, float h) {
        VaultZone zone = VaultZone.byId(BlindAuctionState.zone());
        float cw = 190;
        float cx = x + (w - cw) / 2;
        float cy = y + 12;
        card(cx, cy, cw, 100, true, false);
        g.icon(zone.iconItem, zone.title.substring(0, 1), BlindTheme.accent(), cx + cw / 2 - 9, cy + 8, 18);
        g.text(zone.title, cx, cy + 35, cw, Align.CENTER, BODY, true, BlindTheme.text());
        int count = BlindAuctionState.queueCount();
        int seats = Math.max(1, BlindAuctionState.queueSeats());
        g.text("正在匹配玩家 " + count + " / " + seats, cx, cy + 49, cw, Align.CENTER, BODY, false, BlindTheme.accent());
        float pw = cw - 40;
        float sw = (pw - (seats - 1) * 3) / seats;
        for (int i = 0; i < seats; i++) {
            g.fill(cx + 20 + i * (sw + 3), cy + 58, sw, 5, 2.5F, i < count ? BlindTheme.accent() : BlindTheme.borderDim());
        }
        int wait = BlindAuctionState.queueWait();
        g.text(wait > 0 ? wait + " 秒后由模拟 AI 补齐空位并开局" : "等待开局…", cx, cy + 73, cw, Align.CENTER, SMALL, false,
                BlindTheme.text3());
        g.text("鉴定师：" + Role.byId(BlindAuctionState.role()).title + " · 门票开局时扣除，取消不收费", cx + 6, cy + 86, cw - 12,
                Align.CENTER, SMALL, false, BlindTheme.text3());
        button(x + (w - 90) / 2, y + h - 22, 90, 16, "取消匹配", Kind.NORMAL,
                () -> BlindAuctionNetwork.sendAction(BlindAuctionNetwork.CANCEL_QUEUE, 0, 0, 0));
    }

    // ================================================================== 竞拍

    private void renderMatch(float x, float y, float w, float h) {
        float leftW = 68;
        float centerW = 108;
        float rightW = w - leftW - centerW - 6;
        float cx0 = x + leftW + 3;
        float rx0 = cx0 + centerW + 3;
        int round = BlindAuctionState.round();

        // 左：席位 + 上一轮报价（公开）
        float cardH = (h - 3) / 4;
        float sy = y;
        for (SeatView seat : BlindAuctionState.seats()) {
            boolean hov = hit(x, sy, leftW, cardH - 3);
            card(x, sy, leftW, cardH - 3, seat.me(), hov);
            g.text(seat.name(), x + 4, sy + 7, leftW - 8, Align.LEFT, SMALL, true, seat.me() ? BlindTheme.accent() : BlindTheme.text());
            boolean done = seat.submitted();
            g.fill(x + 4, sy + 13, 26, 9, 4.5F, done ? BlindTheme.alpha(BlindTheme.green(), 0.16F) : BlindTheme.content());
            g.text(done ? "已报价" : "思考中", x + 4, sy + 17.5F, 26, Align.CENTER, 5.5F, false,
                    done ? BlindTheme.green() : BlindTheme.text3());
            g.text(seat.me() ? "你" : seat.human() ? "玩家" : "AI", x + 32, sy + 17.5F, leftW - 36, Align.LEFT, 5.5F, false,
                    BlindTheme.text3());
            long[] bids = seat.bids();
            String last = bids.length == 0 ? "上轮 —" : "上轮 " + fmt(bids[bids.length - 1]);
            g.text(last, x + 4, sy + 28, leftW - 8, Align.LEFT, SMALL, false, bids.length == 0 ? BlindTheme.text3() : BlindTheme.gold());
            if (hov && bids.length > 0) hoverTip = seat.name() + " 历轮报价：" + joinBids(bids);
            sy += cardH;
        }

        // 中：轮次 / 秒仓倍率 / 最低估值 / 仓库 / 道具 / 报价
        card(cx0, y, centerW, h - 3, false, false);
        g.text("第 " + round + " / 5 轮", cx0 + 5, y + 7, 60, Align.LEFT, BODY, true, BlindTheme.text());
        int secs = BlindAuctionState.seconds();
        g.text(secs + "s", cx0 + centerW - 30, y + 7, 25, Align.RIGHT, BODY, true, secs <= 8 ? BlindTheme.red() : BlindTheme.accent());
        double mult = BlindCatalog.buyout(round);
        String multText = mult > 0 ? String.format("秒仓 ×%.1f", mult) : "价高者得";
        float mw = g.width(multText, SMALL, false) + 8;
        g.fill(cx0 + 4, y + 13, mw, 10, 5, BlindTheme.accentTint());
        g.text(multText, cx0 + 4, y + 18, mw, Align.CENTER, SMALL, false, BlindTheme.accent());
        if (hit(cx0 + 4, y + 13, mw, 10)) {
            hoverTip = mult > 0 ? String.format("本轮第一名报价 ≥ 第二名 × %.1f 时直接成交", mult) : "最后一轮：报价最高者中标，全员 0 则流拍";
        }
        g.text("公开估值 ≥ " + fmtShort(BlindAuctionState.floor()), cx0 + mw + 6, y + 18, centerW - mw - 10, Align.RIGHT, SMALL, true,
                BlindTheme.gold());
        if (hit(cx0 + mw + 6, y + 13, centerW - mw - 10, 10)) {
            hoverTip = "公开 ≥ " + fmt(BlindAuctionState.floor()) + " ★ · 你的情报 ≥ " + fmt(BlindAuctionState.myFloor())
                    + " ★（未知按普通最低价算，真实价值不低于此）";
        }

        float gx = cx0 + (centerW - CELL * BlindCatalog.COLS) / 2;
        float gy = y + 26;
        drawGrid(gx, gy, BlindAuctionState.phase() == BlindAuctionState.Phase.MATCH);

        float ty = gy + CELL * BlindCatalog.ROWS + 3;
        Tool[] tools = Tool.values();
        float tw = (centerW - 8 - 4 * 2) / tools.length;
        boolean toolsLocked = BlindAuctionState.submitted() || BlindAuctionState.toolThisRound();
        for (int i = 0; i < tools.length; i++) {
            Tool tool = tools[i];
            float tx = cx0 + 4 + i * (tw + 2);
            boolean used = BlindAuctionState.toolUsed(i);
            boolean armed = BlindAuctionState.armedTool() == i;
            boolean usable = !used && !toolsLocked;
            boolean hov = hit(tx, ty, tw, 14);
            int fill = armed ? BlindTheme.accent() : hov && usable ? BlindTheme.rowHover() : BlindTheme.content();
            g.fill(tx, ty, tw, 14, R, fill);
            g.text(tool.glyph, tx, ty + 7, tw, Align.CENTER, BODY, true,
                    armed ? 0xFFFFFFFF : usable ? BlindTheme.gold() : BlindTheme.text3());
            if (used) g.fill(tx + 3, ty + 7, tw - 6, 1, 0, BlindTheme.text3());
            if (hov) hoverTip = tool.title + (used ? "（已用）" : "") + "：" + tool.desc;
            int idx = i;
            click(tx, ty, tw, 14, () -> {
                if (!usable) return;
                if (tool.targeted) {
                    BlindAuctionState.setArmedTool(armed ? -1 : idx);
                } else {
                    BlindAuctionNetwork.sendAction(BlindAuctionNetwork.USE_TOOL, idx, -1, 0);
                }
            });
        }
        if (BlindAuctionState.armedTool() >= 0) {
            g.text("点选仓库中的一个格子", cx0, ty + 21, centerW, Align.CENTER, SMALL, false, BlindTheme.accent());
        }

        float by = y + h - 21;
        if (BlindAuctionState.submitted()) {
            g.fill(cx0 + 4, by, centerW - 8, 15, R, BlindTheme.alpha(BlindTheme.green(), 0.14F));
            g.text(BlindAuctionState.myBid() > 0 ? "已锁定 " + fmt(BlindAuctionState.myBid()) + " ★" : "本轮已放弃",
                    cx0 + 4, by + 7.5F, centerW - 8, Align.CENTER, SMALL, true, BlindTheme.green());
        } else {
            float half = (centerW - 12) / 2;
            button(cx0 + 4, by, half, 15, "出价", Kind.ACCENT, () -> {
                BlindAuctionState.setArmedTool(-1);
                BlindAuctionState.openKeypad();
            });
            button(cx0 + 8 + half, by, half, 15, "本轮放弃", Kind.NORMAL,
                    () -> BlindAuctionNetwork.sendAction(BlindAuctionNetwork.BID, 0, 0, 0));
        }

        // 右：情报
        card(rx0, y, rightW, h - 3, false, false);
        g.text("情报", rx0 + 5, y + 7, rightW - 10, Align.LEFT, BODY, true, BlindTheme.text());
        g.text(Role.byId(BlindAuctionState.matchRole()).title, rx0 + 5, y + 7, rightW - 10, Align.RIGHT, SMALL, false, BlindTheme.text3());
        float iy = y + 14;
        for (Log log : BlindAuctionState.logs()) {
            if (iy + 18 > y + h - 4) break;
            int color = log.type() == 0 ? BlindTheme.gold() : log.type() == 1 ? BlindTheme.accent() : 0xFF9B6BFF;
            String l1 = fitPrefix(log.message(), rightW - 13);
            boolean two = l1.length() < log.message().length();
            float lh = two ? 25 : 18;
            g.fill(rx0 + 4, iy + 1, 2, lh - 4, 1, color);
            g.text("R" + log.round() + " · " + log.badge(), rx0 + 9, iy + 5, rightW - 13, Align.LEFT, 5.5F, true, color);
            g.text(l1, rx0 + 9, iy + 12.5F, rightW - 13, Align.LEFT, SMALL, false, BlindTheme.text());
            if (two) {
                g.text(log.message().substring(l1.length()), rx0 + 9, iy + 19.5F, rightW - 13, Align.LEFT, SMALL, false,
                        BlindTheme.text());
            }
            iy += lh;
        }

        if (BlindAuctionState.isKeypadOpen()) {
            renderKeypad(cx0 - 20, y + 10, centerW + 40, h - 14);
        }
    }

    private void renderKeypad(float kx, float ky, float kw, float kh) {
        hoverTip = null;
        click(kx - 300, ky - 300, kw + 600, kh + 600, () -> { });
        g.fill(kx - 1, ky - 1, kw + 2, kh + 2, 6, BlindTheme.borderDim());
        g.fill(kx, ky, kw, kh, 5, BlindTheme.content());
        g.text("第 " + BlindAuctionState.round() + " 轮报价 · 剩余 " + BlindAuctionState.seconds() + "s", kx + 7, ky + 8, kw - 24,
                Align.LEFT, SMALL, true, BlindTheme.text());
        boolean hoverX = hit(kx + kw - 14, ky + 2, 12, 12);
        g.text("×", kx + kw - 14, ky + 8, 12, Align.CENTER, BODY, false, hoverX ? BlindTheme.red() : BlindTheme.text3());
        click(kx + kw - 14, ky + 2, 12, 12, () -> BlindAuctionState.setKeypadOpen(false));
        g.text("你的情报估值 ≥ " + fmt(BlindAuctionState.myFloor()) + " ★ · 提交后锁定", kx + 7, ky + 17, kw - 14, Align.LEFT, SMALL, false, BlindTheme.text3());

        long v = BlindAuctionState.keypadValue();
        g.fill(kx + 6, ky + 23, kw - 12, 15, 4, BlindTheme.alpha(BlindTheme.gold(), 0.12F));
        g.stroke(kx + 6, ky + 23, kw - 12, 15, 4, BlindTheme.alpha(BlindTheme.gold(), 0.6F));
        g.text(fmt(v) + " ★", kx + 6, ky + 30.5F, kw - 12, Align.CENTER, BODY, true, BlindTheme.gold());

        String[][] keys = {{"1", "2", "3", "退格"}, {"4", "5", "6", "估值"}, {"7", "8", "9", "+10%"}, {"0", "00", "000", "清零"}};
        float bw = (kw - 12 - 3 * 2) / 4;
        float bh = Math.max(10, Math.min(13, (kh - 42 - 18 - 4 * 2) / 4));
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                String label = keys[r][c];
                button(kx + 6 + c * (bw + 2), ky + 42 + r * (bh + 2), bw, bh, label, c < 3 ? Kind.GHOST : Kind.NORMAL,
                        () -> handleKey(label));
            }
        }
        float botY = ky + kh - 17;
        float half = (kw - 14) / 2;
        button(kx + 5, botY, half, 14, "取消", Kind.NORMAL, () -> BlindAuctionState.setKeypadOpen(false));
        boolean ok = v > 0 && v <= BlindAuctionState.balance();
        button(kx + 9 + half, botY, half, 14, v > BlindAuctionState.balance() ? "余额不足" : "锁定报价",
                ok ? Kind.ACCENT : Kind.DISABLED, () -> {
                    BlindAuctionState.setKeypadOpen(false);
                    BlindAuctionNetwork.sendAction(BlindAuctionNetwork.BID, 0, 0, v);
                });
    }

    public void handleKey(String key) {
        long v = BlindAuctionState.keypadValue();
        switch (key) {
            case "退格" -> BlindAuctionState.backspace();
            case "估值" -> BlindAuctionState.setKeypadValue(BlindAuctionState.myFloor());
            case "+10%" -> BlindAuctionState.setKeypadValue(v <= 0 ? 0 : Math.max(v + 1, Math.round(v * 1.1D)));
            case "清零" -> BlindAuctionState.setKeypadValue(0);
            default -> BlindAuctionState.appendDigits(key);
        }
    }

    // ================================================================== 开仓

    private void renderResult(float x, float y, float w, float h) {
        Result res = BlindAuctionState.result();
        float leftW = 104;
        card(x, y, leftW, h - 3, false, false);
        g.text("开仓明细", x + 5, y + 7, leftW - 10, Align.LEFT, BODY, true, BlindTheme.text());
        drawGrid(x + (leftW - CELL * BlindCatalog.COLS) / 2, y + 15, false);
        if (res != null && !res.topItem().isEmpty()) {
            g.text("最贵：" + res.topItem() + " " + fmt(res.topValue()) + " ★", x + 4, y + 96, leftW - 8, Align.CENTER, SMALL,
                    false, BlindTheme.text2());
        }
        g.text("悬停格子查看藏品", x, y + 106, leftW, Align.CENTER, SMALL, false, BlindTheme.text3());

        float rx = x + leftW + 4;
        float rw = w - leftW - 4;
        if (res == null) {
            g.text("结算中…", rx, y + h / 2, rw, Align.CENTER, BODY, false, BlindTheme.text3());
            return;
        }
        card(rx, y, rw, h - 3, false, false);
        String head = res.flop() ? "无人出价 · 流拍" : res.defaulted() ? "中标者付不起 · 流拍"
                : res.instant() ? "第 " + res.rounds() + " 轮秒仓！" : "第 5 轮落锤";
        g.fill(rx, y, rw, 15, R, BlindTheme.alpha(BlindTheme.gold(), 0.16F));
        g.text(head, rx + 6, y + 7.5F, rw - 12, Align.LEFT, BODY, true, BlindTheme.gold());
        g.text(res.zone(), rx + 6, y + 7.5F, rw - 12, Align.RIGHT, SMALL, false, BlindTheme.text2());

        float ly = y + 19;
        if (!res.flop()) {
            row(rx, ly, rw, "中标者", res.winner() + (res.human() ? "（玩家）" : "（AI）"), BlindTheme.text(), true);
            row(rx, ly + 9, rw, "中标花费", fmt(res.bid()) + " ★", BlindTheme.gold(), true);
        }
        row(rx, ly + 18, rw, "开仓真实价值", fmt(res.truth()) + " ★", BlindTheme.text(), false);
        if (!res.flop()) row(rx, ly + 27, rw, "回收税", "−" + fmt(res.tax()) + " ★", BlindTheme.text3(), false);

        float py = ly + 38;
        if (!res.flop()) {
            boolean up = res.profit() >= 0;
            g.fill(rx + 4, py, rw - 8, 15, R, BlindTheme.alpha(up ? BlindTheme.red() : BlindTheme.green(), 0.12F));
            g.text(res.defaulted() ? "未成交" : up ? "中标者赚了" : "中标者亏了", rx + 9, py + 7.5F, 60, Align.LEFT, SMALL, false,
                    BlindTheme.text2());
            g.text((up ? "+" : "−") + fmt(Math.abs(res.profit())) + " ★", rx + 60, py + 7.5F, rw - 69, Align.RIGHT, TITLE, true,
                    up ? BlindTheme.red() : BlindTheme.green());
        }

        float ty = py + 20;
        g.text("玩家", rx + 6, ty + 3, 50, Align.LEFT, 5.5F, false, BlindTheme.text3());
        g.text("最终报价", rx + rw * 0.42F, ty + 3, rw * 0.3F, Align.RIGHT, 5.5F, false, BlindTheme.text3());
        g.text("亏损分红", rx + rw * 0.72F, ty + 3, rw * 0.28F - 6, Align.RIGHT, 5.5F, false, BlindTheme.text3());
        float by = ty + 7;
        for (SeatResult s : res.seats()) {
            boolean hov = hit(rx + 4, by, rw - 8, 9.5F);
            if (s.winner()) g.fill(rx + 4, by, rw - 8, 9.5F, 2, BlindTheme.accentTint());
            else if (hov) g.fill(rx + 4, by, rw - 8, 9.5F, 2, BlindTheme.rowHover());
            g.text((s.winner() ? "★ " : "") + s.name(), rx + 6, by + 4.75F, rw * 0.42F, Align.LEFT, SMALL, s.winner(),
                    s.winner() ? BlindTheme.accent() : BlindTheme.text2());
            long last = s.bids().length == 0 ? 0 : s.bids()[s.bids().length - 1];
            g.text(fmt(last), rx + rw * 0.42F, by + 4.75F, rw * 0.3F, Align.RIGHT, SMALL, false, BlindTheme.gold());
            g.text(s.human() ? (s.dividend() > 0 ? "+" + fmt(s.dividend()) : "—") : "AI", rx + rw * 0.72F, by + 4.75F,
                    rw * 0.28F - 6, Align.RIGHT, SMALL, false, s.dividend() > 0 ? BlindTheme.red() : BlindTheme.text3());
            if (hov) hoverTip = s.name() + " 历轮报价：" + joinBids(s.bids());
            by += 9.5F;
        }

        button(rx + (rw - 90) / 2, y + h - 21, 90, 15, "返回备战", Kind.ACCENT,
                () -> BlindAuctionNetwork.sendAction(BlindAuctionNetwork.LEAVE_RESULT, 0, 0, 0));
    }

    private void row(float x, float y, float w, String label, String value, int color, boolean bold) {
        g.text(label, x + 6, y + 5, 60, Align.LEFT, SMALL, false, BlindTheme.text3());
        g.text(value, x + 60, y + 5, w - 66, Align.RIGHT, SMALL, bold, color);
    }

    // ================================================================== 成交记录

    private void renderHistory(float x, float y, float w, float h) {
        g.text("最近成交（全服）", x + 2, y + 5, w, Align.LEFT, BODY, true, BlindTheme.text());
        List<Result> list = BlindAuctionState.history();
        if (list.isEmpty()) {
            g.text("还没有成交记录", x, y + h / 2, w, Align.CENTER, BODY, false, BlindTheme.text3());
            return;
        }
        float hy = y + 12;
        g.text("盲仓", x + 4, hy + 4, 60, Align.LEFT, SMALL, false, BlindTheme.text3());
        g.text("中标者", x + 66, hy + 4, 60, Align.LEFT, SMALL, false, BlindTheme.text3());
        g.text("花费", x + 120, hy + 4, 50, Align.RIGHT, SMALL, false, BlindTheme.text3());
        g.text("真实价值", x + 172, hy + 4, 50, Align.RIGHT, SMALL, false, BlindTheme.text3());
        g.text("盈亏", x + 224, hy + 4, w - 228, Align.RIGHT, SMALL, false, BlindTheme.text3());
        float ry = hy + 9;
        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size() && ry + 14 <= y + h; i++) {
            Result r = list.get(i);
            if (i % 2 == 0) g.fill(x, ry, w, 14, 2, BlindTheme.card());
            g.text(r.zone(), x + 4, ry + 4, 60, Align.LEFT, SMALL, false, BlindTheme.text());
            g.text(ago(now - r.at()), x + 4, ry + 10.5F, 60, Align.LEFT, 5.5F, false, BlindTheme.text3());
            String who = r.flop() ? "流拍" : r.winner() + (r.human() ? "" : " · AI");
            g.text(who, x + 66, ry + 7, 54, Align.LEFT, SMALL, r.human(), r.human() ? BlindTheme.accent() : BlindTheme.text2());
            g.text(r.flop() ? "—" : fmt(r.bid()), x + 120, ry + 7, 50, Align.RIGHT, SMALL, false, BlindTheme.gold());
            g.text(fmt(r.truth()), x + 172, ry + 7, 50, Align.RIGHT, SMALL, false, BlindTheme.text());
            boolean none = r.flop() || r.defaulted();
            String p = none ? "—" : (r.profit() >= 0 ? "+" : "−") + fmt(Math.abs(r.profit()));
            g.text(p, x + 224, ry + 7, w - 228, Align.RIGHT, SMALL, true,
                    none ? BlindTheme.text3() : r.profit() >= 0 ? BlindTheme.red() : BlindTheme.green());
            ry += 14;
        }
    }

    // ================================================================== 仓库网格

    private void drawGrid(float gx, float gy, boolean interactive) {
        int cols = BlindCatalog.COLS;
        int rows = BlindCatalog.ROWS;
        g.fill(gx - 1, gy - 1, CELL * cols + 2, CELL * rows + 2, R, BlindTheme.borderDim());
        int armed = interactive && !BlindAuctionState.submitted() ? BlindAuctionState.armedTool() : -1;
        for (int c = 0; c < BlindCatalog.CELLS; c++) {
            float cx = gx + (c % cols) * CELL;
            float cy = gy + (c / cols) * CELL;
            g.fill(cx + 0.5F, cy + 0.5F, CELL - 1, CELL - 1, 1.5F, BlindTheme.content());
            if (BlindAuctionState.knownEmpty(c)) {
                g.text("×", cx, cy + CELL / 2, CELL, Align.CENTER, SMALL, false, BlindTheme.text3());
            }
        }
        for (Item it : BlindAuctionState.items()) {
            float ix = gx + it.x() * CELL + 1;
            float iy = gy + it.y() * CELL + 1;
            float iw = it.w() * CELL - 2;
            float ih = it.h() * CELL - 2;
            if (it.lvl() == 1) {
                g.fill(ix, iy, iw, ih, 2, BlindTheme.alpha(BlindTheme.text3(), 0.25F));
                g.stroke(ix, iy, iw, ih, 2, BlindTheme.text3());
                g.text("?", ix, iy + ih / 2, iw, Align.CENTER, SMALL, true, BlindTheme.text3());
            } else {
                int col = BlindCatalog.TIER_COLORS[Math.max(0, Math.min(5, it.tier()))];
                g.fill(ix, iy, iw, ih, 2, BlindTheme.alpha(col, 0.28F));
                g.stroke(ix, iy, iw, ih, 2, col);
                String label = it.lvl() >= 3 ? fmtShort(it.value()) : BlindCatalog.TIER_NAMES[it.tier()].substring(0, 1);
                g.text(label, ix, iy + ih / 2, iw, Align.CENTER, it.lvl() >= 3 && iw < 12 ? 4.5F : 5.5F, true, col);
            }
        }
        // 悬停与点选
        if (mx >= gx && mx < gx + CELL * cols && my >= gy && my < gy + CELL * rows) {
            int cx = (int) ((mx - gx) / CELL);
            int cy = (int) ((my - gy) / CELL);
            int cell = cy * cols + cx;
            Item hovered = null;
            for (Item it : BlindAuctionState.items()) {
                if (it.covers(cx, cy)) hovered = it;
            }
            if (hovered != null) {
                String size = "占 " + hovered.w() * hovered.h() + " 格";
                hoverTip = switch (hovered.lvl()) {
                    case 1 -> "未知品质的藏品 · " + size;
                    case 2 -> BlindCatalog.TIER_NAMES[hovered.tier()] + "藏品 · " + size;
                    default -> hovered.name() + "（" + BlindCatalog.TIER_NAMES[hovered.tier()] + "）" + fmt(hovered.value()) + " ★";
                };
            } else if (BlindAuctionState.knownEmpty(cell)) {
                hoverTip = "空格";
            }
            if (armed >= 0) {
                g.stroke(gx + cx * CELL, gy + cy * CELL, CELL, CELL, 1.5F, BlindTheme.accent());
                hoverTip = Tool.byId(armed).title + "：点击使用";
            }
        }
        if (armed >= 0) {
            int tool = armed;
            click(gx, gy, CELL * cols, CELL * rows, () -> {
                int cx = (int) ((mx - gx) / CELL);
                int cy = (int) ((my - gy) / CELL);
                BlindAuctionState.setArmedTool(-1);
                BlindAuctionNetwork.sendAction(BlindAuctionNetwork.USE_TOOL, tool, cy * cols + cx, 0);
            });
        }
    }

    // ================================================================== 组件

    private void section(float x, float y, String label) {
        g.text(label, x + 1, y + 1, 280, Align.LEFT, SMALL, true, BlindTheme.text2());
    }

    private void card(float x, float y, float w, float h, boolean selected, boolean hover) {
        g.fill(x, y, w, h, R, hover ? BlindTheme.rowHover() : BlindTheme.card());
        g.stroke(x, y, w, h, R, selected ? BlindTheme.alpha(BlindTheme.accent(), 0.8F) : BlindTheme.borderDim());
    }

    private void segment(float x, float y, float w, String label, boolean active, Runnable action) {
        boolean hover = hit(x, y, w, 13);
        g.fill(x, y, w, 13, 6.5F, active ? BlindTheme.accentTint() : hover ? BlindTheme.rowHover() : BlindTheme.card());
        g.text(label, x, y + 6.5F, w, Align.CENTER, SMALL, active, active ? BlindTheme.accent() : BlindTheme.text2());
        click(x, y, w, 13, action);
    }

    private void button(float x, float y, float w, float h, String label, Kind kind, Runnable action) {
        boolean hover = kind != Kind.DISABLED && hit(x, y, w, h);
        int fill;
        int text;
        switch (kind) {
            case ACCENT -> {
                fill = BlindTheme.alpha(BlindTheme.accent(), hover ? 0.85F : 1.0F);
                text = 0xFFFFFFFF;
            }
            case GHOST -> {
                fill = hover ? BlindTheme.rowHover() : BlindTheme.card();
                text = BlindTheme.text();
            }
            case DISABLED -> {
                fill = BlindTheme.card();
                text = BlindTheme.text3();
            }
            default -> {
                fill = hover ? BlindTheme.rowHover() : BlindTheme.card();
                text = BlindTheme.text2();
            }
        }
        g.fill(x, y, w, h, R, fill);
        if (kind != Kind.ACCENT) g.stroke(x, y, w, h, R, BlindTheme.borderDim());
        g.text(label, x + 2, y + h / 2, w - 4, Align.CENTER, SMALL, kind == Kind.ACCENT, text);
        click(x, y, w, h, kind == Kind.DISABLED ? () -> { } : action);
    }

    private boolean hit(float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void click(float x, float y, float w, float h, Runnable action) {
        clickZones.add(new ClickZone(x, y, w, h, action));
    }

    private String fitPrefix(String s, float maxW) {
        if (g.width(s, SMALL, false) <= maxW) return s;
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (g.width(s.substring(0, mid), SMALL, false) <= maxW) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, Math.max(1, lo));
    }

    private static String joinBids(long[] bids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bids.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append("R").append(i + 1).append(' ').append(fmt(bids[i]));
        }
        return sb.toString();
    }

    private static String ago(long ms) {
        long min = Math.max(0L, ms / 60_000L);
        if (min < 1) return "刚刚";
        if (min < 60) return min + " 分钟前";
        if (min < 1440) return min / 60 + " 小时前";
        return min / 1440 + " 天前";
    }

    private static String fmtShort(long v) {
        if (v >= 1_000_000L) return String.format("%.1fM", v / 1_000_000.0D);
        if (v >= 10_000L) return Math.round(v / 1000.0D) + "k";
        if (v >= 1000L) return String.format("%.1fk", v / 1000.0D);
        return String.valueOf(v);
    }

    private static String fmt(long v) {
        return String.format("%,d", v);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && BlindAuctionState.armedTool() >= 0) {
            BlindAuctionState.setArmedTool(-1);
            return true;
        }
        if (button != 0) return false;
        this.mx = mouseX;
        this.my = mouseY;
        for (int i = clickZones.size() - 1; i >= 0; i--) {
            ClickZone z = clickZones.get(i);
            if (z.contains(mouseX, mouseY)) {
                z.action.run();
                return true;
            }
        }
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!BlindAuctionState.isKeypadOpen()) {
            if (keyCode == 256 && BlindAuctionState.armedTool() >= 0) {
                BlindAuctionState.setArmedTool(-1);
                return true;
            }
            return false;
        }
        if (keyCode == 259) {
            BlindAuctionState.backspace();
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            long v = BlindAuctionState.keypadValue();
            if (v > 0 && v <= BlindAuctionState.balance()) {
                BlindAuctionState.setKeypadOpen(false);
                BlindAuctionNetwork.sendAction(BlindAuctionNetwork.BID, 0, 0, v);
            }
            return true;
        }
        if (keyCode == 256) {
            BlindAuctionState.setKeypadOpen(false);
            return true;
        }
        return false;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (BlindAuctionState.isKeypadOpen() && Character.isDigit(codePoint)) {
            BlindAuctionState.appendDigits(String.valueOf(codePoint));
            return true;
        }
        return false;
    }
}
