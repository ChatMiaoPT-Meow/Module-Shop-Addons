package cn.autoforged.module_shop_stock.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop_stock.client.StockMarketState;
import io.github.humbleui.skija.Canvas;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 持仓 + 委托页（UI v2）：上表是持仓（多 / 空分行，空头显示保证金与冻结），下表是委托（含剩余有效期与撤单），
 * 宽窗口时右侧再放账户资产与交易限额。
 */
public final class PortfolioView implements StockView {
    private static final float SECTION_H = 14.0F;
    private static final float SIDE_W = 150.0F;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DAY_CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final ListTable POSITIONS = new ListTable();
    private static final ListTable ORDERS = new ListTable();
    private static String sortKey = "value";
    private static boolean sortDesc = true;
    private static int scope;

    /** 持仓的一行：同一只股票多、空同持时拆成两行。 */
    private record Row(String symbol, boolean shortSide, long qty, long avg, long price, long value, long pnl,
            long pct, long margin, long frozen) {
    }

    private record Col(String id, String head, float width, Text.Align align, boolean sortable) {
    }

    private final Runnable openQuote;
    private List<Row> rows = List.of();
    private List<StockMarketState.OrderView> orders = List.of();
    private List<Col> posCols = List.of();
    private float[][] scopeRects = new float[0][2];
    private float scopeY;
    private long confirmId;
    private long confirmAt;

    public PortfolioView(Runnable openQuote) {
        this.openQuote = openQuote;
    }

    private static String tr(String key, Object... args) {
        return Ui.tr(key, args);
    }

    // ------------------------------------------------------------------ 数据

    private static List<Row> allRows() {
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, StockMarketState.Position> entry : StockMarketState.positions().entrySet()) {
            String symbol = entry.getKey();
            StockMarketState.Position pos = entry.getValue();
            StockMarketState.Quote q = StockMarketState.quote(symbol);
            if (pos.isEmpty() || StockMarketState.def(symbol) == null || q == null) {
                continue;
            }
            if (pos.quantity > 0L) {
                long value = StockMarketState.multiply(q.price, pos.quantity);
                long pnl = value - pos.cost;
                out.add(new Row(symbol, false, pos.quantity, pos.averageCost(), q.price, value, pnl,
                        pos.cost <= 0L ? 0L : pnl * 10_000L / pos.cost, 0L, pos.locked));
            }
            if (pos.shortQuantity > 0L) {
                long value = StockMarketState.multiply(q.price, pos.shortQuantity);
                long pnl = pos.shortProceeds - value;
                long base = pos.shortMargin > 0L ? pos.shortMargin : pos.shortProceeds;
                out.add(new Row(symbol, true, pos.shortQuantity, pos.averageShortPrice(), q.price, value, pnl,
                        base <= 0L ? 0L : pnl * 10_000L / base, pos.shortMargin, pos.shortLocked));
            }
        }
        return out;
    }

    private static List<Row> scoped(List<Row> all) {
        if (scope == 0) {
            return all;
        }
        List<Row> out = new ArrayList<>();
        for (Row r : all) {
            if (r.shortSide == (scope == 2)) {
                out.add(r);
            }
        }
        return out;
    }

    private static long sortValue(Row r) {
        return switch (sortKey) {
            case "pnl" -> r.pnl;
            case "pct" -> r.pct;
            default -> r.value;
        };
    }

    private List<Col> columnsFor(float w) {
        List<Col> out = new ArrayList<>();
        out.add(new Col("name", tr("gui.stock.v2.col.target"), 0, Text.Align.LEFT, false));
        out.add(new Col("dir", tr("gui.stock.v2.col.dir"), 26, Text.Align.LEFT, false));
        out.add(new Col("qty", tr("gui.stock.v2.col.qty"), 36, Text.Align.RIGHT, false));
        out.add(new Col("avg", tr("gui.stock.v2.col.avg"), 38, Text.Align.RIGHT, false));
        out.add(new Col("price", tr("gui.stock.v2.col.price"), 38, Text.Align.RIGHT, false));
        out.add(new Col("value", tr("gui.stock.v2.col.value"), 50, Text.Align.RIGHT, true));
        out.add(new Col("pnl", tr("gui.stock.v2.col.pnl"), 46, Text.Align.RIGHT, true));
        out.add(new Col("pct", tr("gui.stock.v2.col.ret"), 44, Text.Align.RIGHT, true));
        if (w >= 420.0F) {
            out.add(new Col("margin", tr("gui.stock.v2.col.margin"), 46, Text.Align.RIGHT, false));
        }
        if (w >= 520.0F) {
            out.add(new Col("frozen", tr("gui.stock.v2.col.frozen"), 30, Text.Align.RIGHT, false));
        }
        out.add(new Col("act", "", 30, Text.Align.RIGHT, false));
        return out;
    }

    // ------------------------------------------------------------------ 工具条

    @Override
    public void toolbar(Canvas c, AddonFrame f, float x, float y, float w) {
        float cy = y + V2.BAR / 2.0F;
        List<Row> all = allRows();
        int longs = 0;
        int shorts = 0;
        for (Row r : all) {
            if (r.shortSide) {
                shorts++;
            } else {
                longs++;
            }
        }
        String[] labels = {tr("gui.stock.v2.scope.all") + " " + all.size(),
                tr("gui.stock.v2.dir.long") + " " + longs, tr("gui.stock.v2.dir.short") + " " + shorts};
        this.scopeY = cy - V2.CTRL / 2.0F;
        this.scopeRects = V2.segmented(c, labels, scope, x, this.scopeY, null);
        float[] last = this.scopeRects[this.scopeRects.length - 1];
        float rx = last[0] + last[1] + 10.0F;
        float rw = x + w - rx;
        if (rw < 60.0F) {
            return;
        }
        long value = StockMarketState.marketValue();
        long profit = StockMarketState.profit();
        long cost = 0L;
        for (StockMarketState.Position position : StockMarketState.positions().values()) {
            cost += position.cost + position.shortMargin;
        }
        long pct = cost <= 0L ? 0L : profit * 10_000L / cost;
        String[] parts = {
                tr("gui.stock.v2.net", V2.money(value)),
                tr("gui.stock.v2.cost", V2.money(cost)),
                tr("gui.stock.v2.profit", Ui.signed(profit), Ui.pct(pct))};
        int[] colors = {ShopTheme.text(), ShopTheme.text2(), Ui.trend(profit)};
        // 重要的放右边：收益 > 净值 > 成本，放不下就丢成本 / 净值
        int[] order = {2, 0, 1};
        boolean[] show = new boolean[3];
        float used = 0.0F;
        for (int idx : order) {
            float need = V2.width(parts[idx], V2.FS_S, false) + 10.0F;
            if (used + need > rw) {
                break;
            }
            used += need;
            show[idx] = true;
        }
        float cursor = x + w;
        for (int i = 2; i >= 0; i--) {
            if (!show[i]) {
                continue;
            }
            float pw = V2.width(parts[i], V2.FS_S, false) + 1.0F;
            V2.text(c, parts[i], V2.FS_S, colors[i], cursor - pw, cy, pw, Text.Align.LEFT);
            cursor -= pw + 10.0F;
        }
    }

    // ------------------------------------------------------------------ 主体

    @Override
    public void render(Canvas c, AddonFrame b) {
        this.rows = scoped(allRows());
        this.rows.sort((p, q) -> {
            int cmp = Long.compare(sortValue(p), sortValue(q));
            if (sortDesc) {
                cmp = -cmp;
            }
            return cmp != 0 ? cmp : p.symbol.compareTo(q.symbol);
        });
        List<StockMarketState.OrderView> list = new ArrayList<>(StockMarketState.orders());
        list.sort((p, q) -> Long.compare(q.id, p.id));
        this.orders = list;

        boolean side = b.w() >= 640.0F;
        float mw = side ? b.w() - SIDE_W : b.w();
        float avail = b.h() - SECTION_H * 2.0F;
        float posH = V2.HEAD + Math.max(2, this.rows.size()) * V2.ROW;
        posH = Math.max(avail * 0.34F, Math.min(avail * 0.62F, posH));
        float ordH = avail - posH;

        float y = b.y();
        int openCount = StockMarketState.openOrders().size();
        drawSection(c, b.x(), y, mw, tr("gui.stock.v2.sec.pos"),
                tr("gui.stock.v2.sec.pos_info", countSide(false), countSide(true)));
        drawPositions(c, b, b.x(), y + SECTION_H, mw, posH);
        y += SECTION_H + posH;
        drawSection(c, b.x(), y, mw, tr("gui.stock.v2.sec.ord"),
                tr("gui.stock.v2.sec.ord_info", openCount, StockMarketState.maxOpenOrders, list.size()));
        drawOrders(c, b, b.x(), y + SECTION_H, mw, ordH);
        if (side) {
            drawSide(c, b.x() + mw, b.y(), SIDE_W, b.h());
        }
    }

    private int countSide(boolean shortSide) {
        int n = 0;
        for (Row r : allRows()) {
            if (r.shortSide == shortSide) {
                n++;
            }
        }
        return n;
    }

    @Override
    public String footLeft() {
        return tr("gui.stock.v2.pf.foot", this.rows.size(), StockMarketState.openOrders().size(),
                StockMarketState.maxOpenOrders);
    }

    @Override
    public String footHint() {
        return tr("gui.stock.v2.pf.hint");
    }

    private static void drawSection(Canvas c, float x, float y, float w, String title, String info) {
        Draw.fill(c, x, y, w, SECTION_H, 0, ShopTheme.sidebar());
        Draw.hairline(c, x, y, w, ShopTheme.borderDim());
        float tw = V2.bold(c, title, V2.FS_S, ShopTheme.text(), x + 8.0F, y + SECTION_H / 2.0F, w * 0.3F,
                Text.Align.LEFT);
        V2.text(c, info, V2.FS_XS, ShopTheme.text3(), x + 8.0F + tw + 8.0F, y + SECTION_H / 2.0F,
                w - tw - 24.0F, Text.Align.LEFT);
    }

    private void drawPositions(Canvas c, AddonFrame f, float x, float y, float w, float h) {
        this.posCols = columnsFor(w);
        float[] spec = new float[this.posCols.size()];
        String[] heads = new String[spec.length];
        Text.Align[] aligns = new Text.Align[spec.length];
        boolean[] sortable = new boolean[spec.length];
        int sortCol = -1;
        for (int i = 0; i < spec.length; i++) {
            Col col = this.posCols.get(i);
            spec[i] = col.width;
            heads[i] = col.head;
            aligns[i] = col.align;
            sortable[i] = col.sortable;
            if (col.id.equals(sortKey)) {
                sortCol = i;
            }
        }
        float[][] pos = V2.columns(x, w, 8.0F, 4.0F, spec);
        POSITIONS.layout(x, y, w, h, pos, heads, aligns, sortable);
        POSITIONS.sortCol = sortCol;
        POSITIONS.sortDesc = sortDesc;
        if (this.rows.isEmpty()) {
            V2.tableHead(c, x, y, w, pos, heads, aligns, sortCol, sortDesc);
            V2.empty(c, tr("gui.stock.no_position"), x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        List<Col> columns = this.posCols;
        POSITIONS.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), this.rows.size(),
                (cv, row, ry, hover) -> {
                    Row r = this.rows.get(row);
                    StockMarketState.Def def = StockMarketState.def(r.symbol);
                    float cy = ry + V2.ROW / 2.0F;
                    int dirColor = r.shortSide ? ShopTheme.green() : ShopTheme.red();
                    for (int i = 0; i < columns.size(); i++) {
                        float cx = pos[i][0];
                        float cw = pos[i][1];
                        switch (columns.get(i).id) {
                            case "name" -> {
                                float nw = V2.text(cv, def == null ? r.symbol : def.label(), V2.FS, ShopTheme.text(),
                                        cx, cy, cw, Text.Align.LEFT);
                                if (cw - nw > 28.0F) {
                                    V2.text(cv, r.symbol, V2.FS_S, ShopTheme.text3(), cx + nw + 4.0F, cy,
                                            cw - nw - 4.0F, Text.Align.LEFT);
                                }
                            }
                            case "dir" -> V2.tag(cv, tr(r.shortSide ? "gui.stock.v2.dir.short"
                                    : "gui.stock.v2.dir.long"), dirColor, cx, cy);
                            case "qty" -> V2.bold(cv, V2.money(r.qty), V2.FS_S, ShopTheme.text(), cx, cy, cw,
                                    Text.Align.RIGHT);
                            case "avg" -> V2.text(cv, V2.money(r.avg), V2.FS_S, ShopTheme.text2(), cx, cy, cw,
                                    Text.Align.RIGHT);
                            case "price" -> V2.bold(cv, V2.money(r.price), V2.FS_S, ShopTheme.text(), cx, cy, cw,
                                    Text.Align.RIGHT);
                            case "value" -> V2.text(cv, V2.money(r.value), V2.FS_S, ShopTheme.text2(), cx, cy, cw,
                                    Text.Align.RIGHT);
                            case "pnl" -> V2.bold(cv, Ui.signed(r.pnl), V2.FS_S, Ui.trend(r.pnl), cx, cy, cw,
                                    Text.Align.RIGHT);
                            case "pct" -> V2.bold(cv, Ui.pct(r.pct), V2.FS_S, Ui.trend(r.pnl), cx, cy, cw,
                                    Text.Align.RIGHT);
                            case "margin" -> V2.text(cv, r.shortSide ? V2.money(r.margin) : "—", V2.FS_S,
                                    r.shortSide ? ShopTheme.text2() : ShopTheme.text3(), cx, cy, cw, Text.Align.RIGHT);
                            case "frozen" -> V2.text(cv, r.frozen > 0L ? V2.money(r.frozen) : "0", V2.FS_S,
                                    r.frozen > 0L ? ShopTheme.gold() : ShopTheme.text3(), cx, cy, cw,
                                    Text.Align.RIGHT);
                            case "act" -> POSITIONS.button(cv, row, "close", tr("gui.stock.v2.close"), V2.Btn.NORMAL,
                                    cx + cw, ry, hover);
                            default -> {
                            }
                        }
                    }
                });
    }

    // ------------------------------------------------------------------ 委托表

    private static String actionKey(String action) {
        return switch (action) {
            case "SELL" -> "gui.stock.sell_long";
            case "SHORT" -> "gui.stock.open_short";
            case "COVER" -> "gui.stock.cover_short";
            default -> "gui.stock.buy_long";
        };
    }

    private static String timeText(long at) {
        if (at <= 0L) {
            return "—";
        }
        Instant instant = Instant.ofEpochMilli(at);
        ZoneId zone = ZoneId.systemDefault();
        boolean today = instant.atZone(zone).toLocalDate().equals(LocalDate.now(zone));
        return (today ? CLOCK : DAY_CLOCK).format(instant.atZone(zone));
    }

    private static String countdown(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600L, s / 60L % 60L, s % 60L);
    }

    private void drawOrders(Canvas c, AddonFrame f, float x, float y, float w, float h) {
        boolean wide = w >= 440.0F;
        List<Float> spec = new ArrayList<>();
        List<String> heads = new ArrayList<>();
        List<Text.Align> aligns = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        add(spec, heads, aligns, ids, 62, tr("gui.stock.v2.col.time"), Text.Align.LEFT, "time");
        add(spec, heads, aligns, ids, 0, tr("gui.stock.v2.col.target"), Text.Align.LEFT, "name");
        add(spec, heads, aligns, ids, 28, tr("gui.stock.v2.col.dir"), Text.Align.LEFT, "dir");
        add(spec, heads, aligns, ids, 28, tr("gui.stock.v2.col.type"), Text.Align.LEFT, "type");
        add(spec, heads, aligns, ids, 40, tr("gui.stock.v2.col.price"), Text.Align.RIGHT, "price");
        add(spec, heads, aligns, ids, 52, tr("gui.stock.v2.col.filled"), Text.Align.RIGHT, "filled");
        add(spec, heads, aligns, ids, 42, tr("gui.stock.v2.col.state"), Text.Align.LEFT, "state");
        if (wide) {
            add(spec, heads, aligns, ids, 56, tr("gui.stock.v2.col.remain"), Text.Align.LEFT, "remain");
        }
        add(spec, heads, aligns, ids, 30, "", Text.Align.RIGHT, "act");
        float[] sp = new float[spec.size()];
        for (int i = 0; i < sp.length; i++) {
            sp[i] = spec.get(i);
        }
        String[] headArr = heads.toArray(new String[0]);
        Text.Align[] alignArr = aligns.toArray(new Text.Align[0]);
        float[][] pos = V2.columns(x, w, 8.0F, 4.0F, sp);
        ORDERS.layout(x, y, w, h, pos, headArr, alignArr, null);
        ORDERS.sortCol = -1;
        if (this.orders.isEmpty()) {
            V2.tableHead(c, x, y, w, pos, headArr, alignArr, -1, false);
            V2.empty(c, tr("gui.stock.no_order"), x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        long now = System.currentTimeMillis();
        ORDERS.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), this.orders.size(),
                (cv, row, ry, hover) -> {
                    StockMarketState.OrderView o = this.orders.get(row);
                    StockMarketState.Def def = StockMarketState.def(o.symbol);
                    boolean open = "OPEN".equals(o.state);
                    float cy = ry + V2.ROW / 2.0F;
                    boolean bidColor = "BUY".equals(o.action) || "COVER".equals(o.action);
                    int dirColor = bidColor ? ShopTheme.red() : ShopTheme.green();
                    for (int i = 0; i < ids.size(); i++) {
                        float cx = pos[i][0];
                        float cw = pos[i][1];
                        switch (ids.get(i)) {
                            case "time" -> V2.text(cv, timeText(o.createdAt), V2.FS_S, ShopTheme.text2(), cx, cy, cw,
                                    Text.Align.LEFT);
                            case "name" -> {
                                float nw = V2.text(cv, def == null ? o.symbol : def.label(), V2.FS, ShopTheme.text(),
                                        cx, cy, cw, Text.Align.LEFT);
                                if (cw - nw > 28.0F) {
                                    V2.text(cv, o.symbol, V2.FS_S, ShopTheme.text3(), cx + nw + 4.0F, cy,
                                            cw - nw - 4.0F, Text.Align.LEFT);
                                }
                            }
                            case "dir" -> V2.bold(cv, tr(actionKey(o.action)), V2.FS_S, dirColor, cx, cy, cw,
                                    Text.Align.LEFT);
                            case "type" -> V2.text(cv, tr(o.market ? "gui.stock.market" : "gui.stock.limit"),
                                    V2.FS_S, ShopTheme.text2(), cx, cy, cw, Text.Align.LEFT);
                            case "price" -> V2.text(cv, o.price > 0L ? V2.money(o.price) : "—", V2.FS_S,
                                    ShopTheme.text(), cx, cy, cw, Text.Align.RIGHT);
                            case "filled" -> V2.text(cv, V2.money(o.filled) + " / " + V2.money(o.quantity), V2.FS_S,
                                    ShopTheme.text2(), cx, cy, cw, Text.Align.RIGHT);
                            case "state" -> drawState(cv, o, cx, cy, cw);
                            case "remain" -> drawRemain(cv, o, open, now, cx, cy, cw);
                            case "act" -> {
                                if (open) {
                                    boolean confirm = this.confirmId == o.id && now - this.confirmAt < 3000L;
                                    ORDERS.button(cv, row, "cancel", tr(confirm ? "gui.stock.confirm"
                                            : "gui.stock.cancel_order"), confirm ? V2.Btn.DANGER : V2.Btn.NORMAL,
                                            cx + cw, ry, hover);
                                }
                            }
                            default -> {
                            }
                        }
                    }
                });
    }

    private static void add(List<Float> spec, List<String> heads, List<Text.Align> aligns, List<String> ids,
            float width, String head, Text.Align align, String id) {
        spec.add(width);
        heads.add(head);
        aligns.add(align);
        ids.add(id);
    }

    private static void drawState(Canvas c, StockMarketState.OrderView o, float x, float cy, float w) {
        String key;
        int color;
        switch (o.state) {
            case "FILLED" -> {
                key = "gui.stock.state_filled";
                color = ShopTheme.text2();
            }
            case "CANCELLED" -> {
                key = "gui.stock.state_cancelled";
                color = ShopTheme.text3();
            }
            case "EXPIRED" -> {
                key = "gui.stock.state_expired";
                color = ShopTheme.text3();
            }
            default -> {
                boolean partial = o.filled > 0L;
                key = partial ? "gui.stock.v2.state_partial" : "gui.stock.state_open";
                color = partial ? ShopTheme.gold() : ShopTheme.accent();
            }
        }
        V2.tag(c, tr(key), color, x, cy);
    }

    private static void drawRemain(Canvas c, StockMarketState.OrderView o, boolean open, long now, float x,
            float cy, float w) {
        if (!open || o.expireAt <= 0L) {
            V2.text(c, "—", V2.FS_S, ShopTheme.text3(), x, cy, w, Text.Align.LEFT);
            return;
        }
        long left = o.expireAt - now;
        V2.text(c, countdown(left), V2.FS_S, left < 600_000L ? ShopTheme.gold() : ShopTheme.text2(), x, cy - 1.0F, w,
                Text.Align.LEFT);
        long total = o.createdAt > 0L ? o.expireAt - o.createdAt : 0L;
        if (total > 0L) {
            V2.bar(c, x, cy + 5.0F, w - 6.0F, Math.max(0.0F, Math.min(1.0F, left / (float) total)),
                    V2.levelColor(left / (float) total));
        }
    }

    // ------------------------------------------------------------------ 右栏：账户资产 / 交易限额

    private void drawSide(Canvas c, float x, float y, float w, float h) {
        Draw.fill(c, x, y, 0.5F, h, 0, ShopTheme.borderDim());
        float px = x + 7.0F;
        float pw = w - 13.0F;
        long cash = Math.max(0L, StockMarketState.balance);
        long longVal = 0L;
        long shortEq = 0L;
        for (Map.Entry<String, StockMarketState.Position> entry : StockMarketState.positions().entrySet()) {
            StockMarketState.Quote q = StockMarketState.quote(entry.getKey());
            if (q == null) {
                continue;
            }
            StockMarketState.Position p = entry.getValue();
            long lv = p.quantity > 0L ? StockMarketState.multiply(q.price, p.quantity) : 0L;
            longVal += lv;
            shortEq += Math.max(0L, StockMarketState.positionValue(entry.getKey()) - lv);
        }
        long total = StockMarketState.totalAssets();

        float cy = y + 6.0F;
        V2.section(c, tr("gui.stock.v2.side.account"), px, cy, pw);
        cy += 11.0F;
        cy = line(c, tr("gui.stock.v2.cash"), V2.money(cash), ShopTheme.text(), px, cy, pw);
        cy = line(c, tr("gui.stock.v2.side.long"), V2.money(longVal), ShopTheme.red(), px, cy, pw);
        cy = line(c, tr("gui.stock.v2.side.short"), V2.money(shortEq), ShopTheme.green(), px, cy, pw);
        cy += 2.0F;
        Draw.hairline(c, px, cy - 4.0F, pw, ShopTheme.borderDim());
        V2.text(c, tr("gui.stock.v2.assets"), V2.FS_S, ShopTheme.text2(), px, cy + 2.0F, pw, Text.Align.LEFT);
        V2.bold(c, V2.money(total), V2.FS, ShopTheme.text(), px, cy + 2.0F, pw, Text.Align.RIGHT);
        cy += 12.0F;
        // 堆叠比例条：现金 / 多头 / 空头
        if (total > 0L) {
            float bx = px;
            float[] parts = {cash / (float) total, longVal / (float) total, shortEq / (float) total};
            int[] colors = {ShopTheme.accent(), ShopTheme.red(), ShopTheme.green()};
            Draw.fill(c, px, cy, pw, 3.0F, 1.5F, ShopTheme.borderDim());
            for (int i = 0; i < 3; i++) {
                float segW = pw * Math.min(1.0F, parts[i]);
                if (segW > 0.5F) {
                    Draw.fill(c, bx, cy, segW, 3.0F, 0, colors[i]);
                    bx += segW;
                }
            }
        }
        cy += 14.0F;

        Draw.hairline(c, px, cy - 4.0F, pw, ShopTheme.borderDim());
        V2.section(c, tr("gui.stock.v2.side.limits"), px, cy + 2.0F, pw);
        cy += 12.0F;
        int open = StockMarketState.openOrders().size();
        V2.text(c, tr("gui.stock.v2.side.orders"), V2.FS_S, ShopTheme.text2(), px, cy, pw, Text.Align.LEFT);
        V2.bold(c, open + " / " + StockMarketState.maxOpenOrders, V2.FS_S, ShopTheme.text(), px, cy, pw,
                Text.Align.RIGHT);
        V2.bar(c, px, cy + 6.0F, pw, open / (float) Math.max(1, StockMarketState.maxOpenOrders),
                V2.levelColor(1.0F - open / (float) Math.max(1, StockMarketState.maxOpenOrders)));
        cy += 14.0F;
        cy = line(c, tr("gui.stock.v2.side.max_trade"), tr("gui.stock.v2.unit_shares",
                V2.money(StockMarketState.maxTradeQuantity)), ShopTheme.text(), px, cy, pw);
        cy = line(c, tr("gui.stock.v2.side.max_pos"), tr("gui.stock.v2.unit_shares",
                V2.money(StockMarketState.maxPosition)), ShopTheme.text(), px, cy, pw);
        line(c, tr("gui.stock.v2.side.fee"), tr("gui.stock.v2.side.fee_value",
                String.format(Locale.ROOT, "%.2f%%", StockMarketState.feeBps / 100.0D),
                V2.money(StockMarketState.minFee)), ShopTheme.text(), px, cy, pw);
    }

    private static float line(Canvas c, String label, String value, int color, float x, float cy, float w) {
        V2.text(c, label, V2.FS_S, ShopTheme.text2(), x, cy, w, Text.Align.LEFT);
        V2.bold(c, value, V2.FS_S, color, x, cy, w, Text.Align.RIGHT);
        return cy + 11.0F;
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(AddonFrame f, double mx, double my, int button) {
        if (button == 0) {
            int seg = Ui.segHit(this.scopeRects, this.scopeY, mx, my);
            if (seg >= 0) {
                scope = seg;
                POSITIONS.scrollToTop();
                return true;
            }
            if (POSITIONS.pressBar(mx, my) || ORDERS.pressBar(mx, my)) {
                return true;
            }
        }
        ListTable.Hit hit = POSITIONS.click(mx, my);
        if (hit != null) {
            if ("sort".equals(hit.button())) {
                String key = this.posCols.get(POSITIONS.sortCol).id;
                if (!key.equals(sortKey)) {
                    sortKey = key;
                    sortDesc = true;
                } else {
                    sortDesc = POSITIONS.sortDesc;
                }
                return true;
            }
            if (hit.row() >= 0 && hit.row() < this.rows.size() && button == 0) {
                openRow(this.rows.get(hit.row()));
            }
            return true;
        }
        hit = ORDERS.click(mx, my);
        if (hit != null) {
            if (hit.row() >= 0 && hit.row() < this.orders.size() && button == 0 && "cancel".equals(hit.button())) {
                StockMarketState.OrderView order = this.orders.get(hit.row());
                long now = System.currentTimeMillis();
                if (this.confirmId == order.id && now - this.confirmAt < 3000L) {
                    this.confirmId = 0L;
                    StockMarketState.cancel(order.id);
                } else {
                    this.confirmId = order.id;
                    this.confirmAt = now;
                }
            }
            return true;
        }
        return false;
    }

    /** 点持仓行 / 「平仓」：选中该股并预填可平数量，切到行情页下单。 */
    private void openRow(Row r) {
        StockMarketState.select(r.symbol);
        StockMarketState.Position pos = StockMarketState.position(r.symbol);
        long avail = r.shortSide ? pos.availableShort() : pos.available();
        if (avail <= 0L) {
            avail = r.qty;
        }
        StockMarketState.quantity = String.valueOf(Math.min(avail, StockMarketState.maxTradeQuantity));
        this.openQuote.run();
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mx, double my, int button) {
        return POSITIONS.releaseBar() | ORDERS.releaseBar();
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mx, double my, int button, double dx, double dy) {
        return POSITIONS.dragBar(my) || ORDERS.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mx, double my, double amount) {
        boolean page = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        return POSITIONS.wheel(mx, my, amount, page) || ORDERS.wheel(mx, my, amount, page);
    }

    @Override
    public void cancelInput() {
        POSITIONS.releaseBar();
        ORDERS.releaseBar();
    }
}
