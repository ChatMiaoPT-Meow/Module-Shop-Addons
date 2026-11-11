package com.atoms.Bshop.blind.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import com.atoms.Bshop.blind.auction.BlindCatalog;
import com.atoms.Bshop.blind.auction.BlindCatalog.Relic;
import com.atoms.Bshop.blind.auction.VaultZone;
import com.atoms.Bshop.blind.client.BlindAuctionState;
import com.atoms.Bshop.blind.client.BlindAuctionState.Item;
import com.atoms.Bshop.blind.client.BlindAuctionState.Log;
import com.atoms.Bshop.blind.client.BlindAuctionState.Result;
import com.atoms.Bshop.blind.client.BlindAuctionState.SeatResult;
import com.atoms.Bshop.blind.client.BlindAuctionState.SeatView;
import com.atoms.Bshop.blind.client.ui.BlindSound;
import com.atoms.Bshop.blind.client.ui.BlindTheme;
import com.atoms.Bshop.blind.network.BlindAuctionNetwork;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 遗迹盲仓的商店内容区页面（UI v2 列表视觉层，只走 AtomLib / Skija）。
 * 版式：顶部工具条 → 各阶段主体（对比表 / 席位表 / 仓库格网 / 日志表）→ 底栏。
 * 备战、竞拍、开仓、成交记录各自是一组 {@link ListTable}（静态字段，界面重建后滚动位置保留）。
 * 原版回退界面仍用 {@link BlindVaultRouterView}。
 */
public final class BlindShopView {
    private static final Text.Align L = Text.Align.LEFT;
    private static final Text.Align C = Text.Align.CENTER;
    private static final Text.Align R = Text.Align.RIGHT;
    private static final float ACT_H = 24.0F;
    private static final float METRIC_H = 30.0F;
    private static final float BID_H = 62.0F;
    private static final float LINE = 6.6F;
    private static final int PURPLE = 0xFF9B6BFF;
    private static final String PFX = "module_shop_blind.";
    /** 服务端把成色拼进了藏品名（协议里的固定前缀），客户端据此还原成色。 */
    private static final String DUD_PREFIX = "残损·";
    private static final String GEM_PREFIX = "完美·";

    private static final ListTable ZONES = new ListTable();
    private static final ListTable ROLES = new ListTable();
    private static final ListTable TOOLS_INFO = new ListTable();
    private static final ListTable RULES = new ListTable();
    private static final ListTable SEATS = new ListTable();
    private static final ListTable TOOLS_M = new ListTable();
    private static final ListTable ROUNDS = new ListTable();
    private static final ListTable LOGS = new ListTable();
    private static final ListTable ITEMS = new ListTable();
    private static final ListTable RSEATS = new ListTable();
    private static final ListTable HISTORY = new ListTable();

    private static int logFilter;
    private static final Map<String, ItemStack> STACKS = new HashMap<>();
    private static final Map<String, List<String>> WRAPS = new HashMap<>();
    private static Map<String, String> nameToId;

    private record Zone(float x, float y, float w, float h, Runnable action) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private record TableReg(ListTable table, Consumer<ListTable.Hit> onHit) {
    }

    private record LogRow(boolean first, int round, int type, String badge, String text) {
    }

    private final List<Zone> zones = new ArrayList<>();
    private final List<TableReg> tables = new ArrayList<>();
    private Canvas c;
    private AddonFrame f;
    private float x;
    private float y;
    private float w;
    private float h;
    private float mx;
    private float my;
    private double clickX;
    private double clickY;
    private ItemOverlay items;
    private boolean locked;
    private boolean inputOpen = true;
    private String tip;
    private String footLeft;
    private float[] bidRect;

    private List<LogRow> logRows = List.of();
    private int logKey;

    // ================================================================== 入口

    public void render(Canvas canvas, AddonFrame frame) {
        this.c = canvas;
        this.f = frame;
        this.x = frame.x();
        this.y = frame.y();
        this.w = frame.w();
        this.h = frame.h();
        this.mx = frame.mouseX();
        this.my = frame.mouseY();
        zones.clear();
        tables.clear();
        tip = null;
        footLeft = null;
        bidRect = null;
        inputOpen = true;

        boolean received = BlindAuctionState.received();
        BlindAuctionState.Phase phase = BlindAuctionState.phase();
        boolean history = received && BlindAuctionState.historyOpen();
        locked = received && !history && phase == BlindAuctionState.Phase.QUEUE;
        items = locked ? null : frame.host().items();

        drawToolbar(received, phase, history);
        float top = y + V2.BAR;
        float bottom = y + h - V2.FOOT;
        if (!received) {
            V2.empty(c, tr("connecting"), x, top, w, bottom - top);
        } else if (history) {
            drawHistory(top, bottom);
        } else {
            switch (phase) {
                case MATCH -> drawMatch(top, bottom);
                case RESULT -> drawResult(top, bottom);
                default -> drawLoadout(top, bottom);
            }
        }
        if (locked) {
            inputOpen = true;
            drawQueueOverlay(top, bottom);
        }

        String toast = BlindAuctionState.activeToast();
        if (!toast.isEmpty()) {
            float tw = Math.min(w - 40, V2.width(toast, V2.FS_S, false) + 20);
            float tx = x + (w - tw) / 2;
            float ty = bottom - 22;
            Draw.fill(c, tx, ty, tw, 14, 7, ShopTheme.text());
            V2.text(c, toast, V2.FS_S, ShopTheme.content(), tx + 10, ty + 7, tw - 20, C);
        }
        if (tip != null && frame.host() != null) {
            frame.host().textTooltip(Component.literal(tip));
        }
    }

    // ================================================================== 工具条

    private void drawToolbar(boolean received, BlindAuctionState.Phase phase, boolean history) {
        float cy = y + V2.BAR / 2;
        float by = cy - V2.CTRL / 2;
        Draw.hairline(c, x, y + V2.BAR - 0.5F, w, ShopTheme.borderDim());
        float tw = V2.bold(c, tr("nav.title"), V2.FS_T, ShopTheme.text(), x + 8, cy, 90, L);
        float rx = x + w - 8;

        // 右侧：成交记录 / 返回、（开仓时）返回备战、余额
        String hl = history ? tr("tb.back") : tr("tb.history");
        float hw = V2.buttonWidth(hl) + 6;
        rx -= hw;
        button(hl, history ? V2.Btn.PRIMARY : V2.Btn.NORMAL, rx, by, hw, V2.CTRL,
                () -> {
                    BlindSound.pageTurn();
                    BlindAuctionState.setHistoryOpen(!BlindAuctionState.historyOpen());
                });
        rx -= 4;
        if (received && !history && phase == BlindAuctionState.Phase.RESULT) {
            String rl = tr("tb.leave");
            float rw = V2.buttonWidth(rl) + 6;
            rx -= rw;
            button(rl, V2.Btn.PRIMARY, rx, by, rw, V2.CTRL,
                    () -> {
                        BlindSound.clickBack();
                        BlindAuctionNetwork.sendAction(BlindAuctionNetwork.LEAVE_RESULT, 0, 0, 0);
                    });
            rx -= 4;
        }
        String bal = "★ " + fmt(BlindAuctionState.balance());
        float bw = V2.width(bal, V2.FS_S, true) + 12;
        rx -= bw;
        Draw.fill(c, rx, by + 1, bw, V2.CTRL - 2, V2.R, BlindTheme.alpha(ShopTheme.gold(), 0.14F));
        V2.bold(c, bal, V2.FS_S, ShopTheme.gold(), rx, cy, bw, C);
        if (received && hit(rx, by, bw, V2.CTRL)) tip = tr("tip.balance");

        // 阶段进度（只读分段）
        String[] steps = {tr("step.0"), tr("step.1"), tr("step.2"), tr("step.3")};
        float segW = 4;
        for (int i = 0; i < steps.length; i++) segW += V2.width(steps[i], V2.FS_S, false) + 14;
        float sx = x + 8 + tw + 12;
        if (sx + segW < rx - 6) {
            int current = switch (phase) {
                case LOADOUT -> 0;
                case QUEUE -> 1;
                case MATCH -> 2;
                case RESULT -> 3;
            };
            V2.segmented(c, steps, history ? -1 : current, sx, by, null);
        }
    }

    // ================================================================== 备战

    private void drawLoadout(float top, float bottom) {
        inputOpen = !locked;
        long balance = BlindAuctionState.balance();
        VaultZone sel = VaultZone.byId(BlindAuctionState.zone());
        boolean single = BlindAuctionState.single();
        int queueZone = locked ? BlindAuctionState.zone() : -1;

        // ---- 三区对比表
        float zoneRow = 17;
        float zoneH = V2.HEAD + 3 * zoneRow;
        float[][] zc = cols(x, w, 8, 5, 80, 14, 0, 24, 54, 60, 36, 34, 34, 44, 34);
        String[] zh = {"", tr("col.zone"), tr("col.risk"), tr("col.ticket"), tr("col.minhold"), tr("col.mult"), tr("col.dud"),
                tr("col.gem"), tr("col.seats"), tr("col.queue")};
        Text.Align[] za = {L, L, C, R, R, R, R, R, R, R};
        ZONES.layout(x, top, w, zoneH, zc, zh, za, null).rowHeight(zoneRow);
        renderTable(ZONES, 3, (cv, row, ry, hv) -> {
            VaultZone z = VaultZone.values()[row];
            float cy = ry + zoneRow / 2;
            boolean on = z.id == BlindAuctionState.zone();
            if (on) ZONES.selected(cv, ry);
            radio(zc[0][0], cy, on);
            float nx = zc[1][0];
            if (items != null) {
                ItemStack st = stack(z.iconItem);
                if (!st.isEmpty()) {
                    items.add(st, nx, ry + (zoneRow - V2.ICON) / 2, V2.ICON, false);
                    nx += V2.ICON + 3;
                }
            }
            V2.bold(cv, tr("zone." + z.id + ".name"), V2.FS_S, on ? ShopTheme.accent() : ShopTheme.text(), nx, ry + 5.5F,
                    zc[1][0] + zc[1][1] - nx, L);
            V2.text(cv, tr("zone." + z.id + ".sub"), V2.FS_XS, ShopTheme.text3(), nx, ry + 12.3F, zc[1][0] + zc[1][1] - nx, L);
            int risk = z.id == 0 ? ShopTheme.red() : z.id == 1 ? ShopTheme.gold() : ShopTheme.green();
            V2.tag(cv, tr("zone." + z.id + ".risk"), risk, zc[2][0] + (zc[2][1] - V2.tagWidth(tr("zone." + z.id + ".risk")) + 3) / 2,
                    cy);
            V2.bold(cv, fmt(z.entryFee), V2.FS_S, ShopTheme.gold(), zc[3][0], cy, zc[3][1], R);
            long need = Math.max(z.minStarCoinReq, z.entryFee);
            V2.text(cv, fmt(need), V2.FS_S, balance >= need ? ShopTheme.green() : ShopTheme.red(), zc[4][0], cy, zc[4][1], R);
            V2.text(cv, "×" + z.valueScale, V2.FS_S, ShopTheme.text(), zc[5][0], cy, zc[5][1], R);
            V2.text(cv, pct(z.dudChance), V2.FS_S, z.dudChance > 0 ? ShopTheme.red() : ShopTheme.text3(), zc[6][0], cy, zc[6][1], R);
            V2.text(cv, pct(z.gemChance), V2.FS_S, ShopTheme.gold(), zc[7][0], cy, zc[7][1], R);
            V2.text(cv, tr("zone.seats", z.seatCount, z.maxRounds), V2.FS_S, ShopTheme.text2(), zc[8][0], cy, zc[8][1], R);
            boolean inQ = z.id == queueZone;
            V2.text(cv, inQ ? BlindAuctionState.queueCount() + " / " + z.seatCount : "—", V2.FS_S,
                    inQ ? ShopTheme.accent() : ShopTheme.text3(), zc[9][0], cy, zc[9][1], R);
        }, hit -> {
            if (hit.row() >= 0 && hit.row() < 3) {
                int id = hit.row();
                if (id != BlindAuctionState.zone()) BlindSound.selectOption();
                BlindAuctionNetwork.sendAction(BlindAuctionNetwork.SET_ZONE, id, 0, 0);
            }
        });
        if (inputOpen && ZONES.hoverRow() >= 0) {
            VaultZone z = VaultZone.values()[ZONES.hoverRow()];
            tip = tr("zone." + z.id + ".name") + " · " + tr("zone." + z.id + ".sub");
        }

        // ---- 下半区：鉴定师表 + 道具表 + 规则表
        float lowTop = top + zoneH;
        float actY = bottom - ACT_H;
        float lowH = actY - lowTop;
        float lw = Math.round(w * 0.55F);
        Draw.fill(c, x + lw - 0.25F, lowTop, 0.5F, lowH, 0, ShopTheme.borderDim());

        BlindCatalog.Role[] roles = Idx.ALL;
        float[][] rc = cols(x, lw, 8, 5, 40, 12, 66, 0);
        float descW = rc[2][1];
        int maxLines = 1;
        for (int i = 0; i < roles.length; i++) {
            maxLines = Math.max(maxLines, wrap(tr("role." + i + ".desc"), V2.FS_XS, descW).size());
        }
        float roleRow = Math.max(15, maxLines * LINE + 5);
        ROLES.layout(x, lowTop, lw, lowH, rc, new String[]{"", tr("col.role"), tr("col.skill")}, new Text.Align[]{L, L, L}, null)
                .rowHeight(roleRow);
        renderTable(ROLES, roles.length, (cv, row, ry, hv) -> {
            boolean on = row == BlindAuctionState.role();
            if (on) ROLES.selected(cv, ry);
            float cy = ry + roleRow / 2;
            radio(rc[0][0], cy, on);
            V2.tag(cv, tr("role." + row + ".glyph"), ShopTheme.gold(), rc[1][0], cy);
            V2.bold(cv, tr("role." + row + ".name"), V2.FS_S, on ? ShopTheme.accent() : ShopTheme.text(), rc[1][0] + 14, cy,
                    rc[1][1] - 14, L);
            multiline(cv, wrap(tr("role." + row + ".desc"), V2.FS_XS, descW), V2.FS_XS, ShopTheme.text2(), rc[2][0], ry, roleRow,
                    descW);
        }, hit -> {
            if (hit.row() >= 0 && hit.row() < Idx.ALL.length) {
                if (hit.row() != BlindAuctionState.role()) BlindSound.selectOption();
                BlindAuctionNetwork.sendAction(BlindAuctionNetwork.SET_ROLE, hit.row(), 0, 0);
            }
        });
        if (inputOpen && ROLES.hoverRow() >= 0) {
            int i = ROLES.hoverRow();
            tip = tr("role." + i + ".name") + "：" + tr("role." + i + ".desc");
        }

        float rx = x + lw;
        float rw = w - lw;
        float[][] tc = cols(rx, rw, 8, 5, 40, 50, 0, 28);
        int toolTotal = Idx.TOOLS.length;
        int toolLines = 1;
        for (int i = 0; i < toolTotal; i++) toolLines = Math.max(toolLines, wrap(tr("tool." + i + ".desc"), V2.FS_XS, tc[2][1]).size());
        float toolRow = Math.max(15, toolLines * LINE + 5);
        float toolH = Math.min(V2.HEAD + toolTotal * toolRow, Math.max(V2.HEAD + 2 * toolRow, lowH * 0.58F));
        TOOLS_INFO.layout(rx, lowTop, rw, toolH, tc,
                new String[]{tr("col.tool"), tr("col.desc"), tr("col.kind")}, new Text.Align[]{L, L, R}, null).rowHeight(toolRow);
        renderTable(TOOLS_INFO, toolTotal, (cv, row, ry, hv) -> {
            float cy = ry + toolRow / 2;
            V2.tag(cv, tr("tool." + row + ".glyph"), ShopTheme.gold(), tc[0][0], cy);
            V2.bold(cv, tr("tool." + row + ".name"), V2.FS_S, ShopTheme.text(), tc[0][0] + 14, cy, tc[0][1] - 14, L);
            multiline(cv, wrap(tr("tool." + row + ".desc"), V2.FS_XS, tc[1][1]), V2.FS_XS, ShopTheme.text2(), tc[1][0], ry,
                    toolRow, tc[1][1]);
            V2.tag(cv, tr("tool." + row + ".kind"), TOOL_KIND_COLOR[row], tc[2][0] + tc[2][1] - V2.tagWidth(tr("tool." + row + ".kind")) + 3, cy);
        }, null);

        float ruleTop = lowTop + toolH + 2;
        float[][] rl = cols(rx, rw, 8, 5, 40, 62, 0);
        String[] ruleKeys = {"tax", "div", "instant", "match"};
        List<List<String>> ruleLines = new ArrayList<>();
        int ruleMax = 1;
        for (String k : ruleKeys) {
            List<String> ls = wrap(ruleText(k), V2.FS_XS, rl[1][1]);
            ruleLines.add(ls);
            ruleMax = Math.max(ruleMax, ls.size());
        }
        float ruleRow = Math.max(15, ruleMax * LINE + 5);
        RULES.layout(rx, ruleTop, rw, lowTop + lowH - ruleTop, rl, new String[]{tr("col.rule"), tr("col.desc")},
                new Text.Align[]{L, L}, null).rowHeight(ruleRow);
        int[] ruleColor = {ShopTheme.gold(), ShopTheme.green(), ShopTheme.accent(), ShopTheme.text2()};
        renderTable(RULES, ruleKeys.length, (cv, row, ry, hv) -> {
            float cy = ry + ruleRow / 2;
            V2.bold(cv, ruleLabel(ruleKeys[row]), V2.FS_S, ruleColor[row], rl[0][0], cy, rl[0][1], L);
            multiline(cv, ruleLines.get(row), V2.FS_XS, ShopTheme.text2(), rl[1][0], ry, ruleRow, rl[1][1]);
        }, null);

        // ---- 操作条
        Draw.fill(c, x, actY, w, ACT_H, 0, BlindTheme.alpha(ShopTheme.card(), 0.6F));
        Draw.hairline(c, x, actY, w, ShopTheme.borderDim());
        float cy = actY + ACT_H / 2;
        float by = cy - V2.CTRL / 2;
        float[][] seg = V2.segmented(c, new String[]{tr("mode.multi"), tr("mode.single")}, single ? 1 : 0, x + 8, by, null);
        for (int i = 0; i < 2; i++) {
            int mode = i;
            region(seg[i][0], by, seg[i][1], V2.CTRL,
                    () -> {
                        if ((mode == 1) != BlindAuctionState.single()) BlindSound.selectOption();
                        BlindAuctionNetwork.sendAction(BlindAuctionNetwork.SET_SINGLE, mode, 0, 0);
                    });
        }
        long need = Math.max(sel.minStarCoinReq, sel.entryFee);
        boolean can = (single || balance >= need) && !locked;
        String startLabel = locked ? tr("start.queued") : single ? tr("start.single") : tr("start.match");
        float sw = V2.buttonWidth(startLabel) + 24;
        float sx = x + w - 8 - sw;
        button(startLabel, can ? V2.Btn.PRIMARY : V2.Btn.DISABLED, sx, by, sw, V2.CTRL,
                () -> {
                    BlindSound.startAction();
                    BlindAuctionNetwork.sendAction(BlindAuctionNetwork.START, 0, 0, 0);
                });
        float ix = seg[1][0] + seg[1][1] + 16;
        String[][] cells = single
                ? new String[][]{
                        {tr("act.zone"), tr("zone." + sel.id + ".name"), null},
                        {tr("act.ticket"), tr("act.free"), "ok"},
                        {tr("act.chips"), fmt(BlindCatalog.practiceChipCap(sel)) + " ★", "g"},
                        {tr("act.settle"), tr("act.practice_mode"), null}}
                : new String[][]{
                        {tr("act.zone"), tr("zone." + sel.id + ".name"), null},
                        {tr("act.ticket"), fmt(sel.entryFee) + " ★", "g"},
                        {tr("act.minhold"), fmt(need) + " ★", balance >= need ? "ok" : "bad"},
                        {tr("act.after"), fmt(Math.max(0, balance - sel.entryFee)) + " ★", null}};
        for (String[] cell : cells) {
            float cw = Math.max(V2.width(cell[0], V2.FS_XS, false), V2.width(cell[1], V2.FS_S, true)) + 6;
            if (ix + cw > sx - 8) break;
            V2.text(c, cell[0], V2.FS_XS, ShopTheme.text3(), ix, cy - 4.5F, cw, L);
            int col = "g".equals(cell[2]) ? ShopTheme.gold() : "ok".equals(cell[2]) ? ShopTheme.green()
                    : "bad".equals(cell[2]) ? ShopTheme.red() : ShopTheme.text();
            V2.bold(c, cell[1], V2.FS_S, col, ix, cy + 4.5F, cw, L);
            ix += cw + 12;
        }

        V2.foot(c, x, bottom, w, tr("foot.rates", pctD(BlindAuctionState.tax()), pctD(BlindAuctionState.dividend())),
                tr("foot.loadout"));
    }

    private static final int[] TOOL_KIND_COLOR = {0xFF3BA7D9, 0xFF3BA7D9, PURPLE, 0xFF4FB06A, 0xFFE07A38};

    private static String ruleLabel(String key) {
        return switch (key) {
            case "tax" -> tr("rule.tax", pctD(BlindAuctionState.tax()));
            case "div" -> tr("rule.div", pctD(BlindAuctionState.dividend()));
            case "instant" -> tr("rule.instant");
            default -> tr("rule.match");
        };
    }

    private static String ruleText(String key) {
        return switch (key) {
            case "tax" -> tr("rule.tax.text", pctD(BlindAuctionState.tax()));
            case "div" -> tr("rule.div.text", pctD(BlindAuctionState.dividend()));
            case "instant" -> tr("rule.instant.text", buyoutList());
            default -> tr("rule.match.text");
        };
    }

    private static String buyoutList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < BlindCatalog.ROUNDS - 1; i++) {
            if (i > 0) sb.append(" / ");
            sb.append(String.format("%.1f", BlindCatalog.BUYOUT[i]));
        }
        return sb.toString();
    }

    // ================================================================== 匹配

    private void drawQueueOverlay(float top, float bottom) {
        Draw.fill(c, x, top, w, bottom - top, 0, BlindTheme.alpha(ShopTheme.content(), 0.72F));
        int seats = Math.max(1, BlindAuctionState.queueSeats());
        int count = BlindAuctionState.queueCount();
        float cw = Math.min(w - 20, 230);
        float ch = 100;
        float cx = x + (w - cw) / 2;
        float cy = top + Math.max(6, (bottom - top - ch) / 2 - 8);
        ShopTheme.dialogPanel(c, cx, cy, cw, ch);
        VaultZone zone = VaultZone.byId(BlindAuctionState.zone());
        V2.bold(c, tr("queue.title"), V2.FS_T - 1, ShopTheme.text(), cx + 10, cy + 12, 80, L);
        V2.text(c, tr("zone." + zone.id + ".name") + " · " + (BlindAuctionState.single() ? tr("mode.single") : tr("mode.multi")),
                V2.FS_S, ShopTheme.text2(), cx + 70, cy + 12, cw - 80, R);
        float slotW = (cw - 20 - (seats - 1) * 4) / seats;
        for (int i = 0; i < seats; i++) {
            float sx = cx + 10 + i * (slotW + 4);
            if (i < count) {
                Draw.fill(c, sx, cy + 24, slotW, 24, V2.R, ShopTheme.accentTint());
                Draw.stroke(c, sx, cy + 24, slotW, 24, V2.R, 0.8F, ShopTheme.accent());
                V2.bold(c, i == 0 ? tr("queue.you") : tr("queue.player"), V2.FS_S, ShopTheme.accent(), sx, cy + 36, slotW, C);
            } else {
                Draw.stroke(c, sx, cy + 24, slotW, 24, V2.R, 0.6F, ShopTheme.borderDim());
                V2.text(c, tr("queue.empty"), V2.FS_S, ShopTheme.text3(), sx, cy + 36, slotW, C);
            }
        }
        V2.bar(c, cx + 10, cy + 56, cw - 20, count / (float) seats, ShopTheme.accent());
        int wait = BlindAuctionState.queueWait();
        V2.text(c, wait > 0 ? tr("queue.wait", count, seats, wait) : tr("queue.waiting", count, seats), V2.FS_S, ShopTheme.text2(),
                cx + 10, cy + 66, cw - 20, L);
        V2.text(c, tr("queue.note", tr("role." + BlindAuctionState.role() + ".name")), V2.FS_XS, ShopTheme.text3(), cx + 10,
                cy + 76, cw - 20, L);
        String cancel = tr("queue.cancel");
        float bw = V2.buttonWidth(cancel) + 20;
        button(cancel, V2.Btn.NORMAL, cx + cw - 10 - bw, cy + ch - V2.CTRL - 5, bw, V2.CTRL,
                () -> {
                    BlindSound.clickBack();
                    BlindAuctionNetwork.sendAction(BlindAuctionNetwork.CANCEL_QUEUE, 0, 0, 0);
                });
        V2.foot(c, x, bottom, w, tr("foot.queue"), null);
    }

    // ================================================================== 竞拍

    private void drawMatch(float top, float bottom) {
        int round = BlindAuctionState.round();
        VaultZone zone = VaultZone.byId(BlindAuctionState.zone());
        double mult = BlindCatalog.buyout(round);
        int secs = BlindAuctionState.seconds();

        // ---- 指标条
        Draw.fill(c, x, top, w, METRIC_H, 0, BlindTheme.alpha(ShopTheme.card(), 0.6F));
        Draw.hairline(c, x, top + METRIC_H - 0.5F, w, ShopTheme.borderDim());
        String[][] cells = {
                {tr("zone." + zone.id + ".name"), tr("m.round", round, BlindCatalog.ROUNDS), "t"},
                {tr("m.timer"), secs + " s", secs <= 8 ? "r" : "a"},
                {tr("m.mult"), mult > 0 ? String.format("×%.1f", mult) : tr("m.highest"), "a"},
                {tr("m.public"), "≥ " + fmt(BlindAuctionState.floor()) + " ★", "g"},
                {tr("m.mine"), "≥ " + fmt(BlindAuctionState.myFloor()) + " ★", "g"},
                {tr("m.items"), tr("m.items.val", BlindAuctionState.itemCount(), BlindAuctionState.cellCount(), BlindCatalog.CELLS), "t"}};
        float[] wt = {1.15F, 0.8F, 0.85F, 1.2F, 1.2F, 1.15F};
        float wsum = 0;
        for (float v : wt) wsum += v;
        float cxp = x;
        for (int i = 0; i < cells.length; i++) {
            float cw = w * wt[i] / wsum;
            if (i > 0) Draw.fill(c, cxp, top + 6, 0.5F, METRIC_H - 12, 0, ShopTheme.borderDim());
            V2.text(c, cells[i][0], V2.FS_XS, ShopTheme.text3(), cxp + 8, top + 9, cw - 12, L);
            int col = switch (cells[i][2]) {
                case "r" -> ShopTheme.red();
                case "a" -> ShopTheme.accent();
                case "g" -> ShopTheme.gold();
                default -> ShopTheme.text();
            };
            V2.bold(c, cells[i][1], V2.FS + 1, col, cxp + 8, top + 21, cw - 12, L);
            if (i == 2 && hit(cxp, top, cw, METRIC_H)) tip = mult > 0 ? tr("tip.buyout", String.format("%.1f", mult)) : tr("tip.final");
            if ((i == 3 || i == 4) && hit(cxp, top, cw, METRIC_H)) {
                tip = tr("tip.floor", fmt(BlindAuctionState.floor()), fmt(BlindAuctionState.myFloor()));
            }
            cxp += cw;
        }

        float top2 = top + METRIC_H;
        float h2 = bottom - top2;
        float leftW = Math.max(150, Math.round(w * 0.31F));
        float rightW = Math.max(120, Math.round(w * 0.27F));
        float centerW = w - leftW - rightW;
        float cx0 = x + leftW;
        float rx0 = cx0 + centerW;
        Draw.fill(c, cx0 - 0.25F, top2, 0.5F, h2, 0, ShopTheme.borderDim());
        Draw.fill(c, rx0 - 0.25F, top2, 0.5F, h2, 0, ShopTheme.borderDim());

        drawSeatsAndTools(x, top2, leftW, h2, round);
        drawCenter(cx0, top2, centerW, h2, bottom);
        drawLogs(rx0, top2, rightW, h2);

        if (footLeft == null) {
            String themeName = tr("theme." + BlindAuctionState.vaultTheme() + ".name");
            String modeTag = BlindAuctionState.matchPractice() ? tr("m.practice_tag") + " · " : "";
            footLeft = modeTag + "【" + themeName + "】" + tr("foot.match", BlindAuctionState.itemCount(), BlindAuctionState.cellCount());
        }
        V2.foot(c, x, bottom, w, footLeft, tr("foot.match.hint"));
    }

    private void drawSeatsAndTools(float lx, float top, float lw, float lh, int round) {
        List<SeatView> seats = BlindAuctionState.seats();
        float seatRow = 18;
        float seatH = V2.HEAD + Math.max(1, seats.size()) * seatRow;
        float[][] sc = cols(lx, lw, 5, 2, 30, 0, 20, 20, 20, 20, 20);
        String[] sh = {tr("col.seat"), "R1", "R2", "R3", "R4", "R5"};
        SEATS.layout(lx, top, lw, seatH, sc, sh, new Text.Align[]{L, R, R, R, R, R}, null).rowHeight(seatRow);
        renderTable(SEATS, seats.size(), (cv, row, ry, hv) -> {
            SeatView s = seats.get(row);
            if (s.me()) SEATS.selected(cv, ry);
            float nw = sc[0][1];
            V2.bold(cv, s.name(), V2.FS_S, s.me() ? ShopTheme.accent() : ShopTheme.text(), sc[0][0], ry + 6, nw, L);
            String st = s.submitted() ? tr("seat.locked") : tr("seat.thinking");
            float tw = V2.tag(cv, st, s.submitted() ? ShopTheme.green() : ShopTheme.text3(), sc[0][0], ry + 13.2F);
            V2.text(cv, s.me() ? tr("seat.you") : s.human() ? tr("seat.player") : tr("seat.ai"), V2.FS_XS, ShopTheme.text3(),
                    sc[0][0] + tw, ry + 13.2F, Math.max(0, nw - tw), L);
            long[] bids = s.bids();
            for (int r = 0; r < 5; r++) {
                boolean has = r < bids.length;
                String v = has ? (bids[r] > 0 ? fmtShort(bids[r]) : "—") : "";
                V2.text(cv, v, V2.FS_XS + 0.25F, has && bids[r] > 0 ? ShopTheme.gold() : ShopTheme.text3(), sc[r + 1][0], ry + seatRow / 2,
                        sc[r + 1][1], R);
            }
        }, null);
        if (inputOpen && SEATS.hoverRow() >= 0 && SEATS.hoverRow() < seats.size()) {
            SeatView s = seats.get(SEATS.hoverRow());
            tip = s.name() + (s.bids().length == 0 ? "" : " · " + joinBids(s.bids()));
        }

        // 道具栏（5 种探查道具）
        int toolTotal = Idx.TOOLS.length;
        float toolRow = 14.5F;
        float toolTop = top + seatH + 2;
        float toolH = V2.HEAD + toolTotal * toolRow;
        float[][] tc = cols(lx, lw, 5, 3, 30, 0, 56);
        long used = 0;
        for (int i = 0; i < toolTotal; i++) if (BlindAuctionState.toolUsed(i)) used++;
        TOOLS_M.layout(lx, toolTop, lw, toolH, tc, new String[]{tr("col.tool"), tr("tools.used", used, toolTotal)}, new Text.Align[]{L, R},
                null).rowHeight(toolRow);
        boolean toolsLocked = BlindAuctionState.submitted() || BlindAuctionState.toolThisRound();
        renderTable(TOOLS_M, toolTotal, (cv, row, ry, hv) -> {
            boolean usedT = BlindAuctionState.toolUsed(row);
            boolean armed = BlindAuctionState.armedTool() == row;
            boolean usable = !usedT && !toolsLocked;
            if (armed) TOOLS_M.selected(cv, ry);
            float cy = ry + toolRow / 2;
            V2.tag(cv, tr("tool." + row + ".glyph"), usedT ? ShopTheme.text3() : ShopTheme.gold(), tc[0][0], cy);
            V2.text(cv, tr("tool." + row + ".name"), V2.FS_S, usedT ? ShopTheme.text3() : ShopTheme.text(), tc[0][0] + 14, cy,
                    tc[0][1] - 14, L);
            float right = tc[1][0] + tc[1][1];
            if (usedT) {
                V2.text(cv, tr("tool.used"), V2.FS_XS, ShopTheme.text3(), tc[1][0], cy, tc[1][1], R);
            } else if (armed) {
                TOOLS_M.button(cv, row, "act", tr("tool.cancel"), V2.Btn.PRIMARY, right, ry, hv);
            } else {
                String label = Idx.TARGETED[row] ? tr("tool.pick") : tr("tool.use");
                TOOLS_M.button(cv, row, "act", label, usable ? V2.Btn.NORMAL : V2.Btn.DISABLED, right, ry, hv);
            }
        }, hit -> {
            int i = hit.row();
            if (i < 0 || i >= toolTotal || !"act".equals(hit.button())) return;
            boolean usedT = BlindAuctionState.toolUsed(i);
            if (usedT || BlindAuctionState.submitted() || BlindAuctionState.toolThisRound()) return;
            if (Idx.TARGETED[i]) {
                if (BlindAuctionState.armedTool() == i) {
                    BlindSound.cancelTool();
                    BlindAuctionState.setArmedTool(-1);
                } else {
                    BlindSound.armTool();
                    BlindAuctionState.setArmedTool(i);
                }
            } else {
                BlindSound.useTool(i);
                BlindAuctionNetwork.sendAction(BlindAuctionNetwork.USE_TOOL, i, -1, 0);
            }
        });
        if (inputOpen && TOOLS_M.hoverRow() >= 0 && TOOLS_M.hoverRow() < toolTotal) {
            int i = TOOLS_M.hoverRow();
            tip = tr("tool." + i + ".name") + "：" + tr("tool." + i + ".desc");
        }

        // 每轮规则（空间够才画）
        float rTop = toolTop + toolH + 2;
        float rRow = 12;
        float rH = V2.HEAD + 5 * rRow;
        if (top + lh - rTop >= rH) {
            float[][] rc = cols(lx, lw, 5, 2, 30, 18, 0, 0, 0, 0);
            ROUNDS.layout(lx, rTop, lw, rH, rc, new String[]{tr("col.round"), tr("col.mult"), tr("col.outline"), tr("col.quality"),
                    tr("col.named")}, new Text.Align[]{L, R, R, R, R}, null).rowHeight(rRow);
            renderTable(ROUNDS, 5, (cv, row, ry, hv) -> {
                float cy = ry + rRow / 2;
                boolean now = row + 1 == round;
                if (now) ROUNDS.selected(cv, ry);
                V2.text(cv, "R" + (row + 1), V2.FS_XS, now ? ShopTheme.accent() : row + 1 < round ? ShopTheme.text3() : ShopTheme.text2(),
                        rc[0][0], cy, rc[0][1], L);
                double m = BlindCatalog.BUYOUT[row];
                V2.text(cv, m > 0 ? String.format("×%.1f", m) : tr("m.highest"), V2.FS_XS, ShopTheme.text(), rc[1][0], cy, rc[1][1], R);
                V2.text(cv, Math.round(BlindCatalog.PUBLIC_OUTLINE[row] * 100) + "%", V2.FS_XS, ShopTheme.text2(), rc[2][0], cy, rc[2][1], R);
                V2.text(cv, Math.round(BlindCatalog.PUBLIC_TIER[row] * 100) + "%", V2.FS_XS, ShopTheme.text2(), rc[3][0], cy, rc[3][1], R);
                V2.text(cv, String.valueOf(BlindCatalog.PUBLIC_VALUE[row]), V2.FS_XS, ShopTheme.text2(), rc[4][0], cy, rc[4][1], R);
            }, null);
        }
    }

    private void drawCenter(float cx0, float top, float cw, float ch, float bottom) {
        float bidY = bottom - BID_H - 2;
        float legendH = 11;
        float cell = Math.max(10, Math.min((cw - 8) / BlindCatalog.COLS, (bidY - top - legendH - 6) / BlindCatalog.ROWS));
        float gw = cell * BlindCatalog.COLS;
        float gh = cell * BlindCatalog.ROWS;
        float gx = cx0 + (cw - gw) / 2;
        float gy = top + 4;
        drawGrid(gx, gy, cell, true);

        // 图例
        float ly = gy + gh + 3 + legendH / 2;
        float lx = gx;
        String[][] legend = {{"lg.outline", "o"}, {"lg.tier", "t"}, {"lg.named", "n"}, {"lg.dud", "d"}, {"lg.gem", "g"}, {"lg.empty", "e"}};
        for (String[] l : legend) {
            String text = tr(l[0]);
            float need = 8 + V2.width(text, V2.FS_XS, false) + 6;
            if (lx + need > gx + gw) break;
            switch (l[1]) {
                case "o" -> {
                    Draw.fill(c, lx, ly - 3, 6, 6, 1, BlindTheme.alpha(ShopTheme.text3(), 0.25F));
                    Draw.stroke(c, lx, ly - 3, 6, 6, 1, 0.6F, ShopTheme.text3());
                }
                case "t" -> {
                    Draw.fill(c, lx, ly - 3, 6, 6, 1, BlindTheme.alpha(BlindCatalog.TIER_COLORS[3], 0.3F));
                    Draw.stroke(c, lx, ly - 3, 6, 6, 1, 0.6F, BlindCatalog.TIER_COLORS[3]);
                }
                case "n" -> {
                    Draw.fill(c, lx, ly - 3, 6, 6, 1, BlindTheme.alpha(BlindCatalog.TIER_COLORS[4], 0.3F));
                    Draw.stroke(c, lx, ly - 3, 6, 6, 1, 0.6F, BlindCatalog.TIER_COLORS[4]);
                }
                case "d" -> Draw.fill(c, lx, ly - 3, 6, 6, 1, ShopTheme.red());
                case "g" -> Draw.fill(c, lx, ly - 3, 6, 6, 1, ShopTheme.gold());
                default -> V2.text(c, "×", V2.FS_XS, ShopTheme.text3(), lx, ly, 6, C);
            }
            V2.text(c, text, V2.FS_XS, ShopTheme.text3(), lx + 8, ly, need, L);
            lx += need + 4;
        }
        drawBid(cx0 + 4, bidY, cw - 8, BID_H);
    }

    private void drawBid(float px, float py, float pw, float ph) {
        bidRect = new float[]{px, py, pw, ph};
        boolean open = BlindAuctionState.isKeypadOpen();
        boolean submitted = BlindAuctionState.submitted();
        long balance = BlindAuctionState.effectiveBidCap();
        V2.bold(c, tr("bid.title"), V2.FS_S, ShopTheme.text(), px, py + 5, pw * 0.5F, L);
        String balLabel = BlindAuctionState.matchPractice() ? tr("bid.chips", fmt(balance)) : tr("bid.balance", fmt(balance));
        V2.text(c, balLabel, V2.FS_XS, ShopTheme.text3(), px + pw * 0.35F, py + 5, pw * 0.65F, R);

        float fy = py + 10;
        float fh = 17;
        if (submitted) {
            long bid = BlindAuctionState.myBid();
            Draw.fill(c, px, fy, pw, fh, V2.R, BlindTheme.alpha(ShopTheme.green(), 0.14F));
            V2.bold(c, bid > 0 ? tr("bid.locked", fmt(bid)) : tr("bid.passed"), V2.FS_S + 1, ShopTheme.green(), px, fy + fh / 2, pw, C);
            V2.text(c, tr("bid.wait"), V2.FS_XS, ShopTheme.text3(), px, fy + fh + 8, pw, C);
            return;
        }
        long v = BlindAuctionState.keypadValue();
        V2.fieldBox(c, px, fy, pw, fh, open);
        if (open) {
            String s = fmt(v);
            float tw = V2.bold(c, s, V2.FS + 2, ShopTheme.gold(), px + 8, fy + fh / 2, pw - 24, L);
            if ((System.currentTimeMillis() / 500L) % 2 == 0) Draw.fill(c, px + 9 + tw, fy + 3.5F, 0.8F, fh - 7, 0, ShopTheme.gold());
        } else {
            V2.text(c, tr("bid.click"), V2.FS_S, ShopTheme.text3(), px + 8, fy + fh / 2, pw - 24, L);
        }
        V2.text(c, "★", V2.FS_S, ShopTheme.text3(), px, fy + fh / 2, pw - 7, R);
        region(px, fy, pw, fh, () -> {
            if (!BlindAuctionState.isKeypadOpen()) {
                BlindSound.selectOption();
                BlindAuctionState.setArmedTool(-1);
                BlindAuctionState.openKeypad();
            }
        });

        // 快捷键
        float qy = fy + fh + 3;
        String[] quick = {"+10%", tr("bid.q.floor"), tr("bid.q.last"), tr("bid.q.clear")};
        float qw = (pw - 3 * 3) / 4;
        for (int i = 0; i < 4; i++) {
            int k = i;
            button(quick[i], V2.Btn.NORMAL, px + i * (qw + 3), qy, qw, 12, () -> quick(k));
        }
        // 锁定 / 放弃
        float ay = qy + 15;
        float half = (pw - 4) / 2;
        boolean ok = open && v > 0 && v <= balance;
        if (!open) {
            button(tr("bid.open"), V2.Btn.PRIMARY, px, ay, half, V2.CTRL, () -> {
                BlindSound.selectOption();
                BlindAuctionState.setArmedTool(-1);
                BlindAuctionState.openKeypad();
            });
        } else {
            button(v > balance ? tr("bid.nofunds") : tr("bid.lock"), ok ? V2.Btn.PRIMARY : V2.Btn.DISABLED, px, ay, half, V2.CTRL,
                    this::submitBid);
        }
        button(tr("bid.pass"), V2.Btn.DANGER, px + half + 4, ay, half, V2.CTRL, () -> {
            BlindSound.passRound();
            BlindAuctionNetwork.sendAction(BlindAuctionNetwork.BID, 0, 0, 0);
        });
    }

    private void quick(int k) {
        if (!BlindAuctionState.isKeypadOpen()) {
            BlindAuctionState.setArmedTool(-1);
            BlindAuctionState.openKeypad();
        }
        long v = BlindAuctionState.keypadValue();
        switch (k) {
            case 0 -> {
                BlindSound.keyTap();
                BlindAuctionState.setKeypadValue(v <= 0 ? 0 : Math.max(v + 1, Math.round(v * 1.1D)));
            }
            case 1 -> {
                BlindSound.keyTap();
                BlindAuctionState.setKeypadValue(BlindAuctionState.myFloor());
            }
            case 2 -> {
                BlindSound.keyTap();
                long last = 0;
                for (SeatView s : BlindAuctionState.seats()) {
                    if (s.me() && s.bids().length > 0) last = s.bids()[s.bids().length - 1];
                }
                BlindAuctionState.setKeypadValue(last);
            }
            default -> {
                BlindSound.keyClear();
                BlindAuctionState.setKeypadValue(0);
            }
        }
    }

    private void submitBid() {
        long v = BlindAuctionState.keypadValue();
        if (v > 0 && v <= BlindAuctionState.effectiveBidCap()) {
            BlindSound.lockBid();
            BlindAuctionState.setKeypadOpen(false);
            BlindAuctionNetwork.sendAction(BlindAuctionNetwork.BID, 0, 0, v);
        }
    }

    private void drawLogs(float lx, float top, float lw, float lh) {
        List<Log> all = BlindAuctionState.logs();
        int shown = 0;
        for (Log l : all) if (logMatches(l)) shown++;
        float barH = 17;
        V2.bold(c, tr("log.title", all.size()), V2.FS_S, ShopTheme.text(), lx + 6, top + barH / 2, lw * 0.5F, L);
        String[] fl = {tr("log.all"), tr("log.public"), tr("log.mine")};
        float segW = 4;
        for (int i = 0; i < 3; i++) segW += V2.width(fl[i], V2.FS_S, i == logFilter) + 14;
        if (segW < lw - 40) {
            float[][] seg = V2.segmented(c, fl, logFilter, lx + lw - segW - 4, top + (barH - V2.CTRL) / 2, null);
            for (int i = 0; i < 3; i++) {
                int idx = i;
                region(seg[i][0], top + (barH - V2.CTRL) / 2, seg[i][1], V2.CTRL, () -> {
                    if (logFilter != idx) BlindSound.selectOption();
                    logFilter = idx;
                    LOGS.scrollToTop();
                });
            }
        }
        float[][] lc = cols(lx, lw, 5, 3, 30, 18, 30, 0);
        // 换行（缓存）：条数 / 过滤 / 宽度 / 末条内容变化时重算
        int key = all.size() * 31 + logFilter * 7 + Math.round(lc[2][1]) * 131 + (all.isEmpty() ? 0 : all.get(all.size() - 1).message().hashCode())
                + (all.isEmpty() ? 0 : all.get(0).message().hashCode() * 17);
        if (key != logKey || (logRows.isEmpty() && shown > 0)) {
            List<LogRow> rows = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0; i--) {
                Log l = all.get(i);
                if (!logMatches(l)) continue;
                List<String> ls = wrap(l.message(), V2.FS_S, lc[2][1]);
                for (int j = 0; j < ls.size(); j++) rows.add(new LogRow(j == 0, l.round(), l.type(), l.badge(), ls.get(j)));
            }
            logRows = rows;
            logKey = key;
        }
        float tableY = top + barH;
        float rowH = 11.5F;
        LOGS.layout(lx, tableY, lw, lh - barH, lc, new String[]{tr("col.round"), tr("col.source"), tr("col.content")},
                new Text.Align[]{L, L, L}, null).rowHeight(rowH);
        if (logRows.isEmpty()) {
            LOGS.render(c, null, -9999, -9999, f.dtMs(), 0, (cv, r, ry, hv) -> { });
            V2.empty(c, tr("log.empty"), lx, tableY + V2.HEAD, lw, lh - barH - V2.HEAD);
            tables.add(new TableReg(LOGS, null));
            return;
        }
        List<LogRow> rows = logRows;
        renderTable(LOGS, rows.size(), (cv, row, ry, hv) -> {
            LogRow r = rows.get(row);
            float cy = ry + rowH / 2;
            if (r.first()) {
                V2.text(cv, "R" + r.round(), V2.FS_XS, ShopTheme.text3(), lc[0][0], cy, lc[0][1], L);
                int col = r.type() == 0 ? ShopTheme.accent() : r.type() == 1 ? ShopTheme.gold() : PURPLE;
                String badge = r.badge();
                float bw = Math.min(lc[1][1], V2.width(badge, V2.FS_XS, true) + 6);
                Draw.fill(cv, lc[1][0], cy - 4.2F, bw, 8.4F, 2, BlindTheme.alpha(col, 0.18F));
                V2.bold(cv, badge, V2.FS_XS, col, lc[1][0], cy, bw, C);
            }
            V2.text(cv, r.text(), V2.FS_S, ShopTheme.text(), lc[2][0], cy, lc[2][1], L);
        }, null);
    }

    private static boolean logMatches(Log l) {
        return logFilter == 0 || (logFilter == 1 ? l.type() == 0 : l.type() != 0);
    }

    // ================================================================== 开仓

    private void drawResult(float top, float bottom) {
        Result res = BlindAuctionState.result();
        if (res == null) {
            V2.empty(c, tr("res.pending"), x, top, w, bottom - top);
            return;
        }
        // ---- 标题条
        float stripH = 22;
        Draw.fill(c, x, top, w, stripH, 0, BlindTheme.alpha(ShopTheme.card(), 0.6F));
        Draw.hairline(c, x, top + stripH - 0.5F, w, ShopTheme.borderDim());
        float cy = top + stripH / 2;
        float tw = V2.bold(c, tr("res.title"), V2.FS_T - 0.5F, ShopTheme.text(), x + 8, cy, 80, L);
        String head = res.flop() ? tr("res.flop") : res.defaulted() ? tr("res.defaulted")
                : res.instant() ? tr("res.instant", res.rounds()) : tr("res.final");
        float hx = x + 8 + tw + 8;
        hx += V2.tag(c, head, res.flop() || res.defaulted() ? ShopTheme.text3() : ShopTheme.gold(), hx, cy);
        if (res.practice()) {
            hx += V2.tag(c, tr("res.practice_tag"), ShopTheme.accent(), hx, cy);
        }
        int zid = zoneIdOf(res.zone());
        String zn = zid >= 0 ? tr("zone." + zid + ".name") : res.zone();
        V2.text(c, zn + " · " + tr("res.summary", BlindAuctionState.itemCount(), BlindAuctionState.cellCount(), BlindCatalog.CELLS),
                V2.FS_S, ShopTheme.text2(), hx, cy, w - hx - 140, L);
        String myName = null;
        for (SeatView s : BlindAuctionState.seats()) if (s.me()) myName = s.name();
        for (SeatResult s : res.seats()) {
            if (s.name().equals(myName) && s.dividend() > 0) {
                V2.bold(c, tr("res.mydiv", fmt(s.dividend())), V2.FS_S, ShopTheme.red(), x + w - 148, cy, 140, R);
            }
        }

        float bodyTop = top + stripH;
        float bodyH = bottom - bodyTop;
        float lw = Math.round(w * 0.52F);
        float rw = w - lw;
        Draw.fill(c, x + lw - 0.25F, bodyTop, 0.5F, bodyH, 0, ShopTheme.borderDim());

        // ---- 左：格网 + 藏品清单
        float cell = Math.max(10, Math.min((lw - 8) / BlindCatalog.COLS, bodyH * 0.5F / BlindCatalog.ROWS));
        float gx = x + (lw - cell * BlindCatalog.COLS) / 2;
        drawGrid(gx, bodyTop + 4, cell, false);
        float tTop = bodyTop + 4 + cell * BlindCatalog.ROWS + 5;

        List<Item> list = new ArrayList<>(BlindAuctionState.items());
        list.removeIf(i -> i.lvl() < 3);
        list.sort(Comparator.comparingLong(Item::value).reversed());
        long total = 0;
        for (Item i : list) total += i.value();
        boolean wide = lw >= 300;
        float[][] ic = wide ? cols(x, lw, 6, 4, 50, 14, 0, 26, 28, 52, 34) : cols(x, lw, 6, 4, 50, 14, 0, 26, 28, 52);
        String[] ih = wide ? new String[]{"", tr("col.item"), tr("col.tier"), tr("col.grade"), tr("col.value"), tr("col.share")}
                : new String[]{"", tr("col.item"), tr("col.tier"), tr("col.grade"), tr("col.value")};
        Text.Align[] ia = wide ? new Text.Align[]{L, L, L, L, R, R} : new Text.Align[]{L, L, L, L, R};
        float rowH = 14;
        ITEMS.layout(x, tTop, lw, bottom - tTop, ic, ih, ia, null).rowHeight(rowH);
        final long totalF = total;
        renderTable(ITEMS, list.size(), (cv, row, ry, hv) -> {
            Item it = list.get(row);
            float cy2 = ry + rowH / 2;
            int tier = Math.max(0, Math.min(5, it.tier()));
            int tc = BlindCatalog.TIER_COLORS[tier];
            ItemStack st = stack(it.itemId());
            if (items != null && !st.isEmpty()) items.add(st, ic[0][0], ry + (rowH - 11) / 2, 11, false);
            else V2.text(cv, "·", V2.FS_S, tc, ic[0][0], cy2, ic[0][1], C);
            V2.text(cv, itemName(it), V2.FS_S, ShopTheme.text(), ic[1][0], cy2, ic[1][1], L);
            V2.tag(cv, tr("tier." + tier), tc, ic[2][0], cy2);
            int g = gradeOf(it.name());
            if (g == 1) V2.tag(cv, tr("grade.dud"), ShopTheme.red(), ic[3][0], cy2);
            else if (g == 2) V2.tag(cv, tr("grade.gem"), ShopTheme.gold(), ic[3][0], cy2);
            else V2.text(cv, tr("grade.std"), V2.FS_XS, ShopTheme.text3(), ic[3][0], cy2, ic[3][1], L);
            V2.bold(cv, fmt(it.value()), V2.FS_S, ShopTheme.gold(), ic[4][0], cy2, ic[4][1], R);
            if (wide) {
                V2.text(cv, totalF > 0 ? String.format("%.1f%%", it.value() * 100.0D / totalF) : "—", V2.FS_XS, ShopTheme.text3(),
                        ic[5][0], cy2, ic[5][1], R);
            }
        }, null);

        // ---- 右：结算明细
        float rx = x + lw;
        float yy = bodyTop + 5;
        if (!res.flop()) {
            yy = settle(rx, yy, rw, tr("res.winner"), res.winner() + " " + (res.human() ? tr("res.human") : tr("res.ai")),
                    ShopTheme.text(), true);
            yy = settle(rx, yy, rw, tr("res.bid"), fmt(res.bid()) + " ★", ShopTheme.gold(), true);
        }
        yy = settle(rx, yy, rw, tr("res.truth"), fmt(res.truth()) + " ★", ShopTheme.text(), false);
        if (res.comboBonus() > 0 && res.comboSet() > 0) {
            yy = settle(rx, yy, rw, tr("res.combo", tr("set." + res.comboSet())), "+" + fmt(res.comboBonus()) + " ★", PURPLE, false);
        }
        if (!res.flop()) {
            yy = settle(rx, yy, rw, tr("res.tax", pctD(BlindAuctionState.tax())), "−" + fmt(res.tax()) + " ★", ShopTheme.text3(), false);
            boolean up = res.profit() >= 0;
            float bh = 22;
            Draw.fill(c, rx + 8, yy + 2, rw - 16, bh, V2.R, BlindTheme.alpha(res.defaulted() ? ShopTheme.text3() : up ? ShopTheme.red() : ShopTheme.green(), 0.14F));
            String pnlTag = res.defaulted() ? tr("res.nodeal")
                    : res.practice() ? (up ? tr("res.up.practice") : tr("res.down.practice"))
                    : (up ? tr("res.up") : tr("res.down"));
            V2.text(c, pnlTag, V2.FS_S, ShopTheme.text2(), rx + 14,
                    yy + 2 + bh / 2, rw * 0.46F, L);
            V2.bold(c, V2.signed(res.profit()) + " ★", V2.FS_T, res.defaulted() ? ShopTheme.text3() : up ? ShopTheme.red() : ShopTheme.green(),
                    rx + rw * 0.3F, yy + 2 + bh / 2, rw * 0.7F - 14, R);
            yy += bh + 6;
            if (res.profit() < 0 && res.human() && !res.defaulted()) {
                long sum = 0;
                for (SeatResult s : res.seats()) sum += s.dividend();
                V2.text(c, tr("res.divnote", pctD(BlindAuctionState.dividend()), fmt(sum)), V2.FS_XS, ShopTheme.text2(), rx + 8, yy + 4,
                        rw - 16, L);
                yy += 11;
            }
        }
        if (!res.topItem().isEmpty()) {
            yy = settle(rx, yy, rw, tr("res.top"), baseName(res.topItem()) + " " + fmt(res.topValue()) + " ★", ShopTheme.text2(), false);
        }

        float sTop = yy + 3;
        float[][] sc = cols(rx, rw, 6, 3, 40, 0, 26, 26, 26, 26, 26, 44);
        float rowS = 15;
        RSEATS.layout(rx, sTop, rw, bottom - sTop, sc, new String[]{tr("col.seat"), "R1", "R2", "R3", "R4", "R5", tr("col.dividend")},
                new Text.Align[]{L, R, R, R, R, R, R}, null).rowHeight(rowS);
        List<SeatResult> seats = res.seats();
        final String myNameF = myName;
        renderTable(RSEATS, seats.size(), (cv, row, ry, hv) -> {
            SeatResult s = seats.get(row);
            float cy2 = ry + rowS / 2;
            if (s.winner()) RSEATS.selected(cv, ry);
            String nm = (s.winner() ? "★ " : "") + s.name() + (s.human() ? "" : " · AI");
            V2.bold(cv, nm, V2.FS_S, s.name().equals(myNameF) ? ShopTheme.accent() : s.winner() ? ShopTheme.text() : ShopTheme.text2(),
                    sc[0][0], cy2, sc[0][1], L);
            for (int r = 0; r < 5; r++) {
                boolean has = r < s.bids().length;
                V2.text(cv, has ? (s.bids()[r] > 0 ? fmtShort(s.bids()[r]) : "—") : "", V2.FS_XS + 0.25F,
                        has && s.bids()[r] > 0 ? ShopTheme.gold() : ShopTheme.text3(), sc[r + 1][0], cy2, sc[r + 1][1], R);
            }
            String d = !s.human() ? tr("seat.ai") : s.dividend() > 0 ? "+" + fmt(s.dividend()) : "—";
            V2.text(cv, d, V2.FS_S, s.dividend() > 0 ? ShopTheme.red() : ShopTheme.text3(), sc[6][0], cy2, sc[6][1], R);
        }, null);
        if (inputOpen && RSEATS.hoverRow() >= 0 && RSEATS.hoverRow() < seats.size()) {
            SeatResult s = seats.get(RSEATS.hoverRow());
            tip = s.name() + " · " + joinBids(s.bids());
        }

        if (footLeft == null) {
            footLeft = tr("res.foot", list.size(), fmt(total));
        }
        V2.foot(c, x, bottom, w, footLeft, tr("res.hint"));
    }

    private float settle(float rx, float yy, float rw, String label, String value, int color, boolean bold) {
        V2.text(c, label, V2.FS_S, ShopTheme.text3(), rx + 8, yy + 6, rw * 0.4F, L);
        if (bold) V2.bold(c, value, V2.FS_S, color, rx + rw * 0.3F, yy + 6, rw * 0.7F - 8, R);
        else V2.text(c, value, V2.FS_S, color, rx + rw * 0.3F, yy + 6, rw * 0.7F - 8, R);
        return yy + 13;
    }

    // ================================================================== 成交记录

    private void drawHistory(float top, float bottom) {
        List<Result> list = BlindAuctionState.history();
        boolean narrow = w < 560;
        String[] hh = narrow
                ? new String[]{tr("col.time"), tr("col.zone"), tr("col.winner"), tr("col.instant"), tr("col.hammer"), tr("col.profit"), tr("col.top")}
                : new String[]{tr("col.time"), tr("col.zone"), tr("col.winner"), tr("col.round"), tr("col.instant"), tr("col.hammer"),
                        tr("col.truth"), tr("col.profit"), tr("col.top")};
        float[][] cc = narrow ? cols(x, w, 8, 4, 40, 46, 56, 0, 30, 52, 52, 0) : cols(x, w, 8, 5, 40, 50, 62, 0, 20, 34, 56, 56, 56, 0);
        Text.Align[] ha = narrow ? new Text.Align[]{L, L, L, L, R, R, L} : new Text.Align[]{L, L, L, L, L, R, R, R, L};
        HISTORY.layout(x, top, w, bottom - top, cc, hh, ha, null);
        long now = System.currentTimeMillis();
        final boolean nar = narrow;
        if (list.isEmpty()) {
            HISTORY.render(c, null, -9999, -9999, f.dtMs(), 0, (cv, r, ry, hv) -> { });
            V2.empty(c, tr("hist.empty"), x, top + V2.HEAD, w, bottom - top - V2.HEAD);
            tables.add(new TableReg(HISTORY, null));
        } else {
            renderTable(HISTORY, list.size(), (cv, row, ry, hv) -> {
                Result r = list.get(row);
                float cy = ry + V2.ROW / 2;
                int k = 0;
                V2.text(cv, ago(now - r.at()), V2.FS_XS, ShopTheme.text3(), cc[k][0], cy, cc[k][1], L);
                k++;
                int zid = zoneIdOf(r.zone());
                V2.text(cv, zid >= 0 ? tr("zone." + zid + ".name") : r.zone(), V2.FS_S, ShopTheme.text(), cc[k][0], cy, cc[k][1], L);
                k++;
                if (r.flop()) {
                    V2.tag(cv, tr("res.flop.short"), ShopTheme.text3(), cc[k][0], cy);
                } else {
                    float tw = V2.bold(cv, r.winner(), V2.FS_S, r.human() ? ShopTheme.accent() : ShopTheme.text2(), cc[k][0], cy,
                            cc[k][1] - (r.human() ? 0 : 16), L);
                    if (!r.human()) V2.tag(cv, "AI", ShopTheme.text3(), cc[k][0] + tw + 2, cy);
                }
                k++;
                if (!nar) {
                    V2.text(cv, r.flop() ? "—" : "R" + r.rounds(), V2.FS_S, ShopTheme.text2(), cc[k][0], cy, cc[k][1], L);
                    k++;
                }
                if (r.instant()) V2.tag(cv, tr("hist.instant"), ShopTheme.gold(), cc[k][0], cy);
                else if (r.defaulted()) V2.tag(cv, tr("hist.defaulted"), ShopTheme.red(), cc[k][0], cy);
                else V2.text(cv, "—", V2.FS_S, ShopTheme.text3(), cc[k][0], cy, cc[k][1], L);
                k++;
                V2.text(cv, r.flop() ? "—" : fmt(r.bid()), V2.FS_S, ShopTheme.gold(), cc[k][0], cy, cc[k][1], R);
                k++;
                if (!nar) {
                    V2.text(cv, fmt(r.truth()), V2.FS_S, ShopTheme.text(), cc[k][0], cy, cc[k][1], R);
                    k++;
                }
                boolean none = r.flop() || r.defaulted();
                V2.bold(cv, none ? "—" : V2.signed(r.profit()), V2.FS_S,
                        none ? ShopTheme.text3() : r.profit() >= 0 ? ShopTheme.red() : ShopTheme.green(), cc[k][0], cy, cc[k][1], R);
                k++;
                if (!r.topItem().isEmpty()) {
                    float ix = cc[k][0];
                    ItemStack st = stack(idOfName(baseName(r.topItem())));
                    if (items != null && !st.isEmpty()) {
                        items.add(st, ix, ry + (V2.ROW - 11) / 2, 11, false);
                        ix += 14;
                    }
                    String val = fmt(r.topValue());
                    float vw = V2.width(val, V2.FS_XS, false) + 2;
                    int g = gradeOf(r.topItem());
                    V2.text(cv, baseNameLocal(r.topItem()), V2.FS_S, g == 1 ? ShopTheme.red() : g == 2 ? ShopTheme.gold() : ShopTheme.text(), ix,
                            cy, Math.max(0, cc[k][0] + cc[k][1] - ix - vw - 2), L);
                    V2.text(cv, val, V2.FS_XS, ShopTheme.gold(), cc[k][0], cy, cc[k][1], R);
                } else {
                    V2.text(cv, "—", V2.FS_S, ShopTheme.text3(), cc[k][0], cy, cc[k][1], L);
                }
            }, null);
        }
        int a = HISTORY.firstVisible();
        int b = HISTORY.lastVisible();
        V2.foot(c, x, bottom, w, list.isEmpty() ? tr("hist.none") : tr("hist.foot", list.size(), a, b), tr("hist.hint"));
    }

    // ================================================================== 仓库格网

    private void drawGrid(float gx, float gy, float cell, boolean live) {
        int cols = BlindCatalog.COLS;
        int rows = BlindCatalog.ROWS;
        Draw.fill(c, gx - 1, gy - 1, cell * cols + 2, cell * rows + 2, 3, ShopTheme.borderDim());
        boolean showEmpty = true;
        for (int i = 0; i < BlindCatalog.CELLS; i++) {
            float cx = gx + (i % cols) * cell;
            float cy = gy + (i / cols) * cell;
            Draw.fill(c, cx + 0.4F, cy + 0.4F, cell - 0.8F, cell - 0.8F, 1.2F, ShopTheme.content());
            if (showEmpty && BlindAuctionState.knownEmpty(i)) {
                V2.text(c, "×", Math.max(V2.FS_XS, cell * 0.3F), ShopTheme.text3(), cx, cy + cell / 2, cell, C);
            }
        }
        int hoverCx = -1;
        int hoverCy = -1;
        if (inputOpen && mx >= gx && mx < gx + cell * cols && my >= gy && my < gy + cell * rows) {
            hoverCx = (int) ((mx - gx) / cell);
            hoverCy = (int) ((my - gy) / cell);
        }
        Item hovered = null;
        for (Item it : BlindAuctionState.items()) {
            float bx = gx + it.x() * cell + 0.8F;
            float by = gy + it.y() * cell + 0.8F;
            float bw = it.w() * cell - 1.6F;
            float bh = it.h() * cell - 1.6F;
            if (it.covers(hoverCx, hoverCy)) hovered = it;
            if (it.lvl() <= 1) {
                Draw.fill(c, bx, by, bw, bh, 1.8F, BlindTheme.alpha(ShopTheme.text3(), 0.22F));
                Draw.stroke(c, bx, by, bw, bh, 1.8F, 0.7F, ShopTheme.text3());
                V2.bold(c, "?", Math.max(V2.FS_S, Math.min(bw, bh) * 0.45F), ShopTheme.text3(), bx, by + bh / 2, bw, C);
                continue;
            }
            int tier = Math.max(0, Math.min(5, it.tier()));
            int col = BlindCatalog.TIER_COLORS[tier];
            Draw.fill(c, bx, by, bw, bh, 1.8F, BlindTheme.alpha(col, it.lvl() >= 3 ? 0.28F : 0.22F));
            Draw.stroke(c, bx, by, bw, bh, 1.8F, 0.8F, col);
            if (it.lvl() == 2) {
                V2.bold(c, tr("tier." + tier), Math.max(V2.FS_XS, Math.min(V2.FS_S + 1, Math.min(bw, bh) * 0.3F)), col, bx, by + bh / 2, bw, C);
                continue;
            }
            int grade = gradeOf(it.name());
            ItemStack st = stack(it.itemId());
            boolean valueLine = bh >= 15;
            float ic = Math.max(8, Math.min(26, Math.min(bw - 4, bh - (valueLine ? 9 : 3))));
            if (items != null && !st.isEmpty()) {
                items.add(st, bx + (bw - ic) / 2, by + ((valueLine ? bh - 8 : bh) - ic) / 2 + 0.5F, ic, false);
            } else {
                V2.bold(c, tr("tier." + tier), V2.FS_XS, col, bx, by + bh / 2 - (valueLine ? 3 : 0), bw, C);
            }
            if (valueLine) {
                V2.bold(c, fmtShort(it.value()), V2.FS_XS, grade == 1 ? ShopTheme.red() : grade == 2 ? ShopTheme.gold() : col, bx,
                        by + bh - 4.2F, bw, C);
            }
            if (grade != 0 && bw >= 13) {
                float s = 7;
                Draw.fill(c, bx + bw - s, by, s, s, 1.5F, grade == 1 ? ShopTheme.red() : ShopTheme.gold());
                V2.bold(c, tr(grade == 1 ? "grade.dud.short" : "grade.gem.short"), 4.5F, 0xFFFFFFFF, bx + bw - s, by + s / 2, s, C);
            }
        }
        int armed = live && !BlindAuctionState.submitted() ? BlindAuctionState.armedTool() : -1;
        if (hoverCx >= 0) {
            if (armed >= 0) {
                Draw.stroke(c, gx + hoverCx * cell, gy + hoverCy * cell, cell, cell, 1.5F, 1.2F, ShopTheme.accent());
                footLeft = tr("armed", tr("tool." + armed + ".name"));
            } else if (hovered != null) {
                Item it = hovered;
                Draw.stroke(c, gx + it.x() * cell, gy + it.y() * cell, it.w() * cell, it.h() * cell, 1.5F, 1.1F, ShopTheme.text());
                footLeft = describe(it);
            } else if (BlindAuctionState.knownEmpty(hoverCy * cols + hoverCx)) {
                footLeft = tr("cell.empty");
            }
        }
        if (armed >= 0) {
            int tool = armed;
            float fgx = gx;
            float fgy = gy;
            float fcell = cell;
            region(gx, gy, cell * cols, cell * rows, () -> {
                int cx = (int) ((clickX - fgx) / fcell);
                int cy = (int) ((clickY - fgy) / fcell);
                BlindSound.useTool(tool);
                BlindAuctionState.setArmedTool(-1);
                BlindAuctionNetwork.sendAction(BlindAuctionNetwork.USE_TOOL, tool, cy * BlindCatalog.COLS + cx, 0);
            });
            if (footLeft == null) footLeft = tr("armed", tr("tool." + armed + ".name"));
        }
    }

    private static String describe(Item it) {
        int size = it.w() * it.h();
        if (it.lvl() <= 1) return tr("cell.unknown", size);
        int tier = Math.max(0, Math.min(5, it.tier()));
        if (it.lvl() == 2) return tr("cell.tier", tr("tier." + tier), size);
        int g = gradeOf(it.name());
        return tr("cell.full", itemName(it), tr("tier." + tier), fmt(it.value()),
                g == 1 ? tr("grade.dud") : g == 2 ? tr("grade.gem") : tr("grade.std"), size);
    }

    // ================================================================== 输入

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && BlindAuctionState.armedTool() >= 0) {
            BlindSound.cancelTool();
            BlindAuctionState.setArmedTool(-1);
            return true;
        }
        if (button != 0) return false;
        clickX = mouseX;
        clickY = mouseY;
        if (BlindAuctionState.isKeypadOpen() && (bidRect == null || !(mouseX >= bidRect[0] && mouseX < bidRect[0] + bidRect[2]
                && mouseY >= bidRect[1] && mouseY < bidRect[1] + bidRect[3]))) {
            BlindAuctionState.setKeypadOpen(false);
        }
        for (int i = zones.size() - 1; i >= 0; i--) {
            Zone z = zones.get(i);
            if (z.contains(mouseX, mouseY)) {
                z.action.run();
                return true;
            }
        }
        for (TableReg t : tables) {
            if (t.table().pressBar(mouseX, mouseY)) return true;
        }
        for (TableReg t : tables) {
            ListTable.Hit hit = t.table().click(mouseX, mouseY);
            if (hit != null) {
                if (t.onHit() != null) t.onHit().accept(hit);
                return true;
            }
        }
        return false;
    }

    public boolean mouseReleased() {
        boolean any = false;
        for (TableReg t : tables) any |= t.table().releaseBar();
        return any;
    }

    public boolean mouseDragged(double mouseY) {
        for (TableReg t : tables) {
            if (t.table().dragBar(mouseY)) return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!inputOpenForInput()) return false;
        for (TableReg t : tables) {
            if (t.table().wheel(mouseX, mouseY, scrollY, false)) return true;
        }
        return false;
    }

    private boolean inputOpenForInput() {
        return !locked;
    }

    public void cancelInput() {
        for (TableReg t : tables) t.table().releaseBar();
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!BlindAuctionState.isKeypadOpen()) {
            if (keyCode == 256 && BlindAuctionState.armedTool() >= 0) {
                BlindSound.cancelTool();
                BlindAuctionState.setArmedTool(-1);
                return true;
            }
            return false;
        }
        if (keyCode == 259) {
            BlindSound.keyClear();
            BlindAuctionState.backspace();
            return true;
        }
        if (keyCode == 257 || keyCode == 335) {
            submitBid();
            return true;
        }
        if (keyCode == 256) {
            BlindSound.clickBack();
            BlindAuctionState.setKeypadOpen(false);
            return true;
        }
        return false;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (BlindAuctionState.isKeypadOpen() && Character.isDigit(codePoint)) {
            BlindSound.keyTap();
            BlindAuctionState.appendDigits(String.valueOf(codePoint));
            return true;
        }
        return false;
    }

    // ================================================================== 小工具

    private boolean hit(float bx, float by, float bw, float bh) {
        return inputOpen && mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }

    private void region(float rx, float ry, float rw, float rh, Runnable action) {
        if (inputOpen) zones.add(new Zone(rx, ry, rw, rh, action));
    }

    private void button(String label, V2.Btn kind, float bx, float by, float bw, float bh, Runnable action) {
        V2.button(c, label, kind, bx, by, bw, bh, kind != V2.Btn.DISABLED && hit(bx, by, bw, bh) ? 1.0F : 0.0F);
        if (kind != V2.Btn.DISABLED) region(bx, by, bw, bh, action);
    }

    private void radio(float rx, float cy, boolean on) {
        Draw.stroke(c, rx + 1, cy - 3, 6, 6, 3, 0.8F, on ? ShopTheme.accent() : ShopTheme.borderDim());
        if (on) Draw.fill(c, rx + 2.6F, cy - 1.4F, 2.8F, 2.8F, 1.4F, ShopTheme.accent());
    }

    /** 渲染表格并登记命中；锁定（匹配遮罩）时不响应鼠标。 */
    private void renderTable(ListTable t, int rows, ListTable.RowPainter painter, Consumer<ListTable.Hit> onHit) {
        t.render(c, items, inputOpen ? mx : -9999, inputOpen ? my : -9999, f.dtMs(), rows, painter);
        if (inputOpen) tables.add(new TableReg(t, onHit));
    }

    private void multiline(Canvas cv, List<String> lines, float size, int color, float tx, float ry, float rowH, float tw) {
        float total = lines.size() * LINE;
        float start = ry + (rowH - total) / 2 + LINE / 2;
        for (int i = 0; i < lines.size(); i++) {
            V2.text(cv, lines.get(i), size, color, tx, start + i * LINE, tw, L);
        }
    }

    /** 固定列按给定宽度，0 为弹性列（平分剩余）；窗口窄时固定列最多收窄到 70%。 */
    private static float[][] cols(float x, float w, float pad, float gap, float minFlex, float... spec) {
        float fixed = 0;
        int flex = 0;
        for (float s : spec) {
            if (s <= 0) flex++;
            else fixed += s;
        }
        float gaps = gap * (spec.length - 1);
        float rest = w - pad * 2 - gaps - fixed;
        float shrink = 1.0F;
        if (flex > 0 && rest < minFlex * flex && fixed > 0) {
            shrink = Math.max(0.7F, (w - pad * 2 - gaps - minFlex * flex) / fixed);
            rest = w - pad * 2 - gaps - fixed * shrink;
        }
        float flexW = flex == 0 ? 0 : Math.max(18, rest / flex);
        float[][] out = new float[spec.length][2];
        float ox = x + pad;
        for (int i = 0; i < spec.length; i++) {
            float cw = spec[i] <= 0 ? flexW : spec[i] * shrink;
            out[i][0] = ox;
            out[i][1] = cw;
            ox += cw + gap;
        }
        return out;
    }

    /** 按宽度折行（CJK 逐字、拉丁文优先在空格处断）。结果缓存。 */
    private static List<String> wrap(String s, float size, float maxW) {
        if (s == null || s.isEmpty()) return List.of("");
        String key = size + "|" + Math.round(maxW) + "|" + s;
        List<String> cached = WRAPS.get(key);
        if (cached != null) return cached;
        if (WRAPS.size() > 800) WRAPS.clear();
        List<String> out = new ArrayList<>();
        int start = 0;
        int n = s.length();
        while (start < n) {
            int end = start;
            int lastSpace = -1;
            while (end < n && V2.width(s.substring(start, end + 1), size, false) <= maxW) {
                if (s.charAt(end) == ' ') lastSpace = end;
                end++;
            }
            if (end >= n) {
                out.add(s.substring(start));
                break;
            }
            if (end == start) end = start + 1;
            else if (lastSpace > start && s.charAt(end) <= 0x2E80) end = lastSpace + 1;
            out.add(s.substring(start, end).stripTrailing());
            start = end;
        }
        WRAPS.put(key, out);
        return out;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(PFX + key, args).getString();
    }

    private static String fmt(long v) {
        return V2.money(v);
    }

    private static String fmtShort(long v) {
        if (v >= 1_000_000L) return String.format("%.1fM", v / 1_000_000.0D);
        if (v >= 10_000L) return Math.round(v / 1000.0D) + "k";
        if (v >= 1000L) return String.format("%.1fk", v / 1000.0D);
        return String.valueOf(v);
    }

    private static String pct(double p) {
        return Math.round(p * 100) + "%";
    }

    /** 配置里的比例（可能带小数）显示成百分数：5 或 2.5。 */
    private static String pctD(double p) {
        double v = p * 100.0D;
        return Math.abs(v - Math.round(v)) < 0.05D ? String.valueOf(Math.round(v)) : String.format("%.1f", v);
    }

    private static String joinBids(long[] bids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bids.length; i++) {
            if (i > 0) sb.append(" / ");
            sb.append('R').append(i + 1).append(' ').append(bids[i] > 0 ? fmt(bids[i]) : "—");
        }
        return sb.toString();
    }

    private static String ago(long ms) {
        long min = Math.max(0L, ms / 60_000L);
        if (min < 1) return tr("ago.now");
        if (min < 60) return tr("ago.min", min);
        if (min < 1440) return tr("ago.hour", min / 60);
        return tr("ago.day", min / 1440);
    }

    private static int zoneIdOf(String title) {
        for (VaultZone z : VaultZone.values()) {
            if (z.title.equals(title)) return z.id;
        }
        return -1;
    }

    private static int gradeOf(String name) {
        if (name == null) return 0;
        if (name.startsWith(DUD_PREFIX)) return 1;
        if (name.startsWith(GEM_PREFIX)) return 2;
        return 0;
    }

    private static String baseName(String name) {
        return gradeOf(name) == 0 ? name : name.substring(DUD_PREFIX.length());
    }

    /** 藏品显示名：优先用物品的本地化名，取不到时用服务端给的名字（去掉成色前缀）。 */
    private static String itemName(Item it) {
        ItemStack st = stack(it.itemId());
        return st.isEmpty() ? baseName(it.name()) : st.getHoverName().getString();
    }

    private static String baseNameLocal(String serverName) {
        String base = baseName(serverName);
        ItemStack st = stack(idOfName(base));
        return st.isEmpty() ? base : st.getHoverName().getString();
    }

    private static String idOfName(String baseName) {
        if (nameToId == null) {
            Map<String, String> m = new HashMap<>();
            for (Relic r : BlindCatalog.RELICS) m.put(r.name(), r.itemId());
            nameToId = m;
        }
        return nameToId.getOrDefault(baseName, "");
    }

    private static ItemStack stack(String id) {
        if (id == null || id.isEmpty()) return ItemStack.EMPTY;
        return STACKS.computeIfAbsent(id, k -> {
            ResourceLocation rl = ResourceLocation.tryParse(k);
            if (rl == null) return ItemStack.EMPTY;
            net.minecraft.world.item.Item it = BuiltInRegistries.ITEM.get(rl);
            return it == Items.AIR ? ItemStack.EMPTY : new ItemStack(it);
        });
    }

    /** 鉴定师 / 道具的静态索引（文字走 lang，这里只放结构信息）。 */
    private static final class Idx {
        static final BlindCatalog.Role[] ALL = BlindCatalog.Role.values();
        static final BlindCatalog.Tool[] TOOLS = BlindCatalog.Tool.values();
        static final boolean[] TARGETED = new boolean[TOOLS.length];
        static {
            for (int i = 0; i < TOOLS.length; i++) TARGETED[i] = TOOLS[i].targeted;
        }

        private Idx() {
        }
    }
}
