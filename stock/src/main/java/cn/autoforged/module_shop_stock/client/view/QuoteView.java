package cn.autoforged.module_shop_stock.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop_stock.client.ChartRenderer;
import cn.autoforged.module_shop_stock.client.StockMarketState;
import cn.autoforged.module_shop_stock.market.bars.BarPeriod;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 行情页（UI v2）：左侧行业筛选 + 全部股票的列表 + 右侧详情栏（K 线、五档盘口、下单区）。
 *
 * <p>列表的列随宽度增减（窄窗口只留代码 / 名称 / 现价 / 涨跌幅 / 自选）；表头点「现价 / 涨跌 / 涨跌幅 / 成交量」排序。
 * 中国习惯：涨红跌绿。
 */
public final class QuoteView implements StockView {
    private static final float PAD = 6.0F;
    private static final float DETAIL_GAP = 6.0F;
    private static final float BOOK_ROW = 8.5F;
    private static final float CHIP_H = 11.0F;

    /** 跨界面重建保留：表格滚动、排序、行业与范围筛选。 */
    private static final ListTable TABLE = new ListTable();
    private static String sortKey = "";
    private static boolean sortDesc = true;
    private static int scope;
    private static String sector = "";

    private record Col(String id, String head, float width, Text.Align align, boolean sortable) {
    }

    private final Ui.Hits hits = new Ui.Hits();
    private SkiaTextField search;
    private SkiaTextField quantity;
    private SkiaTextField price;

    private List<Col> cols = List.of();
    private List<String> shown = List.of();
    private float[][] scopeRects = new float[0][2];
    private float scopeY;
    private float[][] modeRects = new float[0][2];
    private float modeY;
    private final float[] chartRect = new float[4];
    private final float[] chipRect = new float[4];
    private boolean chartShown;
    private long chartAt;
    private boolean draggingChart;
    private double dragAccumX;
    private boolean formShown;

    private static String tr(String key, Object... args) {
        return Ui.tr(key, args);
    }

    @Override
    public void init(AddonPageHost host) {
        this.search = new SkiaTextField(host.screen(), host.font(), 10)
                .placeholder(tr("gui.stock.search")).fontSize(V2.FS_S);
        this.search.setValue(StockMarketState.query);
        this.search.onChange(text -> {
            StockMarketState.query = text;
            TABLE.scrollToTop();
        });
        host.registerInput(this.search.editBox());

        this.quantity = new SkiaTextField(host.screen(), host.font(), 7)
                .placeholder(tr("gui.stock.quantity")).fontSize(V2.FS_S);
        this.quantity.editBox().setFilter(text -> text.chars().allMatch(Character::isDigit));
        this.quantity.setValue(StockMarketState.quantity);
        this.quantity.onChange(text -> StockMarketState.quantity = text);
        host.registerInput(this.quantity.editBox());

        this.price = new SkiaTextField(host.screen(), host.font(), 7)
                .placeholder(tr("gui.stock.price")).fontSize(V2.FS_S);
        this.price.editBox().setFilter(text -> text.chars().allMatch(Character::isDigit));
        this.price.setValue(StockMarketState.limitPrice);
        this.price.onChange(text -> StockMarketState.limitPrice = text);
        host.registerInput(this.price.editBox());
    }

    // ------------------------------------------------------------------ 数据

    private static List<String> sectors() {
        List<String> out = new ArrayList<>();
        for (StockMarketState.Def def : StockMarketState.defs()) {
            if (!def.sector.isEmpty() && !out.contains(def.sector)) {
                out.add(def.sector);
            }
        }
        return out;
    }

    private static long baseOf(StockMarketState.Quote q) {
        return q.prevClose > 0L ? q.prevClose : q.previous;
    }

    private static boolean held(String symbol) {
        return !StockMarketState.position(symbol).isEmpty();
    }

    private static int heldCount() {
        int n = 0;
        for (StockMarketState.Def def : StockMarketState.defs()) {
            if (held(def.symbol)) {
                n++;
            }
        }
        return n;
    }

    private List<String> filtered() {
        String needle = StockMarketState.query.trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (StockMarketState.Def def : StockMarketState.defs()) {
            if (!needle.isEmpty() && !(def.symbol + " " + def.label() + " " + def.sector).toLowerCase(Locale.ROOT)
                    .contains(needle)) {
                continue;
            }
            if (!sector.isEmpty() && !sector.equals(def.sector)) {
                continue;
            }
            if (scope == 1 && !StockMarketState.watch().contains(def.symbol)) {
                continue;
            }
            if (scope == 2 && !held(def.symbol)) {
                continue;
            }
            out.add(def.symbol);
        }
        if (sortKey.isEmpty()) {
            List<String> watched = new ArrayList<>();
            List<String> rest = new ArrayList<>();
            for (String s : out) {
                (StockMarketState.watch().contains(s) ? watched : rest).add(s);
            }
            watched.addAll(rest);
            return watched;
        }
        out.sort((a, b) -> {
            int c = Long.compare(sortValue(a), sortValue(b));
            if (sortDesc) {
                c = -c;
            }
            return c != 0 ? c : a.compareTo(b);
        });
        return out;
    }

    private static long sortValue(String symbol) {
        StockMarketState.Quote q = StockMarketState.quote(symbol);
        if (q == null) {
            return 0L;
        }
        return switch (sortKey) {
            case "price" -> q.price;
            case "chg" -> q.price - baseOf(q);
            case "pct" -> q.changeBps();
            case "vol" -> q.volume;
            default -> 0L;
        };
    }

    private List<Col> columnsFor(float tw) {
        List<Col> out = new ArrayList<>();
        out.add(new Col("code", tr("gui.stock.v2.col.code"), 42, Text.Align.LEFT, false));
        out.add(new Col("name", tr("gui.stock.v2.col.name"), 0, Text.Align.LEFT, false));
        if (tw >= 400) {
            out.add(new Col("sector", tr("gui.stock.v2.col.sector"), 30, Text.Align.LEFT, false));
        }
        out.add(new Col("price", tr("gui.stock.v2.col.price"), 40, Text.Align.RIGHT, true));
        if (tw >= 340) {
            out.add(new Col("chg", tr("gui.stock.v2.col.chg"), 34, Text.Align.RIGHT, true));
        }
        out.add(new Col("pct", tr("gui.stock.v2.col.pct"), 44, Text.Align.RIGHT, true));
        if (tw >= 440) {
            out.add(new Col("open", tr("gui.stock.v2.col.open"), 36, Text.Align.RIGHT, false));
            out.add(new Col("prev", tr("gui.stock.v2.col.prev"), 36, Text.Align.RIGHT, false));
        }
        if (tw >= 340) {
            out.add(new Col("vol", tr("gui.stock.v2.col.vol"), 46, Text.Align.RIGHT, true));
        }
        out.add(new Col("star", "★", 14, Text.Align.CENTER, false));
        return out;
    }

    // ------------------------------------------------------------------ 工具条

    @Override
    public void toolbar(Canvas c, AddonFrame f, float x, float y, float w) {
        float cy = y + V2.BAR / 2.0F;
        if (!StockMarketState.hasSnapshot || this.search == null) {
            return;
        }
        float searchW = Math.max(50.0F, Math.min(100.0F, w * 0.30F));
        this.search.bounds(x, cy - V2.CTRL / 2.0F, searchW, V2.CTRL).visible(true);
        this.search.render(c, f.mouseX(), f.mouseY(), f.dtMs());
        float sx = x + searchW + 6.0F;
        String[] labels = {
                tr("gui.stock.v2.scope.all") + " " + StockMarketState.defs().size(),
                tr("gui.stock.v2.scope.watch") + " ★ " + StockMarketState.watch().size(),
                tr("gui.stock.v2.scope.held") + " " + heldCount()};
        this.scopeY = cy - V2.CTRL / 2.0F;
        this.scopeRects = V2.segmented(c, labels, scope, sx, this.scopeY, null);
        float[] last = this.scopeRects[this.scopeRects.length - 1];
        float rx = last[0] + last[1] + 10.0F;
        float rw = x + w - rx;
        if (rw < 40.0F) {
            return;
        }
        // 右侧：指数 / 现金 / 总资产，放得下几项画几项（从右往左排）
        long delta = StockMarketState.index - StockMarketState.indexPrev;
        long bps = StockMarketState.indexPrev <= 0L ? 0L : delta * 10_000L / StockMarketState.indexPrev;
        String index = tr("gui.stock.v2.index") + " " + String.format(Locale.ROOT, "%,.1f",
                StockMarketState.index / 1.0D) + " " + Ui.pct(bps);
        String cash = tr("gui.stock.v2.cash") + " " + V2.money(Math.max(0L, StockMarketState.balance));
        String assets = tr("gui.stock.v2.assets") + " " + V2.money(StockMarketState.totalAssets());
        String[] parts = {index, cash, assets};
        int[] colors = {Ui.trend(bps), ShopTheme.text2(), ShopTheme.text()};
        int count = 0;
        float used = 0.0F;
        for (String p : parts) {
            float need = V2.width(p, V2.FS_S, false) + (count > 0 ? 10.0F : 0.0F);
            if (used + need > rw) {
                break;
            }
            used += need;
            count++;
        }
        float cursor = x + w;
        for (int i = count - 1; i >= 0; i--) {
            float pw = V2.width(parts[i], V2.FS_S, false) + 1.0F;
            V2.text(c, parts[i], V2.FS_S, colors[i], cursor - pw, cy, pw, Text.Align.LEFT);
            cursor -= pw + 10.0F;
        }
    }

    // ------------------------------------------------------------------ 主体

    @Override
    public void render(Canvas c, AddonFrame b) {
        this.hits.clear();
        this.chartShown = false;
        if (!StockMarketState.hasSnapshot) {
            V2.empty(c, tr("gui.stock.loading"), b.x(), b.y(), b.w(), b.h());
            hideForm();
            return;
        }
        StockMarketState.zoomBars = Motion.approach(StockMarketState.zoomBars,
                (float) StockMarketState.targetZoomBars, b.dtMs(), 75.0F);
        syncFields();

        float fw = b.w() >= 600.0F ? 66.0F : 54.0F;
        float dw = Math.max(150.0F, Math.min(176.0F, b.w() * 0.25F));
        float tw = b.w() - fw - dw;
        drawFilter(c, b, b.x(), b.y(), fw, b.h());
        drawTable(c, b, b.x() + fw, b.y(), tw, b.h());
        drawDetail(c, b, b.x() + fw + tw, b.y(), dw, b.h());
    }

    @Override
    public String footLeft() {
        if (!StockMarketState.hasSnapshot) {
            return null;
        }
        return tr("gui.stock.v2.showing", TABLE.firstVisible(), TABLE.lastVisible(), this.shown.size());
    }

    @Override
    public String footHint() {
        return tr("gui.stock.v2.quote.hint");
    }

    // ---- 左：行业筛选

    private void drawFilter(Canvas c, AddonFrame f, float x, float y, float w, float h) {
        Draw.fill(c, x + w - 0.5F, y, 0.5F, h, 0, ShopTheme.borderDim());
        float cy = y + 8.0F;
        V2.section(c, tr("gui.stock.v2.filter.sector"), x + 6.0F, cy, w - 10.0F);
        cy += 6.0F;
        List<String> sectors = sectors();
        List<String> keys = new ArrayList<>();
        keys.add("");
        keys.addAll(sectors);
        float bottom = y + h - 56.0F;
        for (String key : keys) {
            if (cy + 13.0F > bottom) {
                break;
            }
            boolean on = key.equals(sector);
            boolean over = Ui.in(f.mouseX(), f.mouseY(), x + 3.0F, cy, w - 7.0F, 13.0F);
            V2.filterItem(c, key.isEmpty() ? tr("gui.stock.v2.scope.all") : key, null, on, over ? 1.0F : 0.0F,
                    x + 3.0F, cy, w - 7.0F);
            long avg = avgBps(key);
            V2.text(c, Ui.pct(avg).replace(".00%", "%"), V2.FS_XS, Ui.trend(avg), x + 3.0F + 6.0F, cy + 6.5F,
                    w - 7.0F - 9.0F, Text.Align.RIGHT);
            this.hits.add(x + 3.0F, cy, w - 7.0F, 13.0F, "sec:" + key);
            cy += 14.0F;
        }
        // 盘面统计
        int up = 0;
        int down = 0;
        int flat = 0;
        int halted = 0;
        for (StockMarketState.Def def : StockMarketState.defs()) {
            StockMarketState.Quote q = StockMarketState.quote(def.symbol);
            if (def.halted) {
                halted++;
            }
            long bps = q == null ? 0L : q.changeBps();
            if (bps > 0L) {
                up++;
            } else if (bps < 0L) {
                down++;
            } else {
                flat++;
            }
        }
        cy = Math.max(cy + 4.0F, y + h - 54.0F);
        if (cy + 46.0F > y + h) {
            return;
        }
        Draw.hairline(c, x + 5.0F, cy - 2.0F, w - 12.0F, ShopTheme.borderDim());
        cy += 5.0F;
        String[] labels = {tr("gui.stock.v2.stat.up"), tr("gui.stock.v2.stat.down"), tr("gui.stock.v2.stat.flat"),
                tr("gui.stock.v2.stat.halt")};
        int[] values = {up, down, flat, halted};
        int[] colors = {ShopTheme.red(), ShopTheme.green(), ShopTheme.text3(), ShopTheme.gold()};
        for (int i = 0; i < 4; i++) {
            V2.text(c, labels[i], V2.FS_S, ShopTheme.text2(), x + 6.0F, cy + i * 10.0F, w - 12.0F, Text.Align.LEFT);
            V2.bold(c, String.valueOf(values[i]), V2.FS_S, colors[i], x + 6.0F, cy + i * 10.0F, w - 14.0F,
                    Text.Align.RIGHT);
        }
    }

    private static long avgBps(String sectorKey) {
        long sum = 0L;
        int n = 0;
        for (StockMarketState.Def def : StockMarketState.defs()) {
            if (!sectorKey.isEmpty() && !sectorKey.equals(def.sector)) {
                continue;
            }
            StockMarketState.Quote q = StockMarketState.quote(def.symbol);
            if (q != null) {
                sum += q.changeBps();
                n++;
            }
        }
        return n == 0 ? 0L : sum / n;
    }

    // ---- 中：股票列表

    private void drawTable(Canvas c, AddonFrame f, float x, float y, float w, float h) {
        this.cols = columnsFor(w);
        float[] spec = new float[this.cols.size()];
        String[] heads = new String[spec.length];
        Text.Align[] aligns = new Text.Align[spec.length];
        boolean[] sortable = new boolean[spec.length];
        int sortCol = -1;
        for (int i = 0; i < spec.length; i++) {
            Col col = this.cols.get(i);
            spec[i] = col.width;
            heads[i] = col.head;
            aligns[i] = col.align;
            sortable[i] = col.sortable;
            if (col.id.equals(sortKey)) {
                sortCol = i;
            }
        }
        float[][] pos = V2.columns(x, w, 6.0F, 4.0F, spec);
        TABLE.layout(x, y, w, h, pos, heads, aligns, sortable);
        TABLE.sortCol = sortCol;
        TABLE.sortDesc = sortDesc;
        this.shown = filtered();
        if (this.shown.isEmpty()) {
            V2.tableHead(c, x, y, w, pos, heads, aligns, sortCol, sortDesc);
            V2.empty(c, tr(StockMarketState.defs().isEmpty() ? "gui.stock.empty" : "gui.stock.no_match"),
                    x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        List<Col> columns = this.cols;
        TABLE.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), this.shown.size(), (cv, row, ry, hover) -> {
            String symbol = this.shown.get(row);
            StockMarketState.Def def = StockMarketState.def(symbol);
            StockMarketState.Quote q = StockMarketState.quote(symbol);
            if (def == null || q == null) {
                return;
            }
            boolean selected = symbol.equals(StockMarketState.selected);
            if (selected) {
                TABLE.selected(cv, ry);
            }
            float cy = ry + V2.ROW / 2.0F;
            long bps = q.changeBps();
            long chg = q.price - baseOf(q);
            int trend = Ui.trend(bps);
            for (int i = 0; i < columns.size(); i++) {
                float cx = pos[i][0];
                float cw = pos[i][1];
                switch (columns.get(i).id) {
                    case "code" -> V2.bold(cv, symbol, V2.FS_S,
                            !def.enabled ? ShopTheme.text3() : def.halted ? ShopTheme.gold() : ShopTheme.text(),
                            cx, cy, cw, Text.Align.LEFT);
                    case "name" -> drawNameCell(cv, def, symbol, selected, cx, cy, cw);
                    case "sector" -> V2.text(cv, def.sector, V2.FS_S, ShopTheme.text3(), cx, cy, cw, Text.Align.LEFT);
                    case "price" -> V2.bold(cv, V2.money(q.price), V2.FS, trend, cx, cy, cw, Text.Align.RIGHT);
                    case "chg" -> V2.text(cv, Ui.signed(chg), V2.FS_S, trend, cx, cy, cw, Text.Align.RIGHT);
                    case "pct" -> V2.bold(cv, Ui.pct(bps), V2.FS_S, trend, cx, cy, cw, Text.Align.RIGHT);
                    case "open" -> V2.text(cv, q.dayOpen > 0L ? V2.money(q.dayOpen) : "—", V2.FS_S,
                            ShopTheme.text2(), cx, cy, cw, Text.Align.RIGHT);
                    case "prev" -> V2.text(cv, baseOf(q) > 0L ? V2.money(baseOf(q)) : "—", V2.FS_S,
                            ShopTheme.text2(), cx, cy, cw, Text.Align.RIGHT);
                    case "vol" -> V2.text(cv, V2.money(q.volume), V2.FS_S, ShopTheme.text2(), cx, cy, cw,
                            Text.Align.RIGHT);
                    case "star" -> {
                        boolean watched = StockMarketState.watch().contains(symbol);
                        V2.text(cv, watched ? "★" : "☆", V2.FS, watched ? ShopTheme.gold() : ShopTheme.text3(),
                                cx, cy, cw, Text.Align.CENTER);
                    }
                    default -> {
                    }
                }
            }
        });
    }

    private static void drawNameCell(Canvas c, StockMarketState.Def def, String symbol, boolean selected, float x,
            float cy, float w) {
        int color = !def.enabled ? ShopTheme.text3() : ShopTheme.text();
        float nw = selected ? V2.bold(c, def.label(), V2.FS, color, x, cy, w, Text.Align.LEFT)
                : V2.text(c, def.label(), V2.FS, color, x, cy, w, Text.Align.LEFT);
        float cursor = x + nw + 4.0F;
        float end = x + w;
        if (held(symbol)) {
            String tag = tr("gui.stock.v2.tag.held");
            if (cursor + V2.tagWidth(tag) <= end) {
                cursor += V2.tag(c, tag, ShopTheme.accent(), cursor, cy);
            }
        }
        if (def.halted) {
            String tag = tr("gui.stock.v2.tag.halt");
            if (cursor + V2.tagWidth(tag) <= end) {
                cursor += V2.tag(c, tag, ShopTheme.gold(), cursor, cy);
            }
        } else if (!def.enabled) {
            String tag = tr("gui.stock.v2.tag.delisted");
            if (cursor + V2.tagWidth(tag) <= end) {
                cursor += V2.tag(c, tag, ShopTheme.text3(), cursor, cy);
            }
        }
        if (StockMarketState.hasActiveNews() && symbol.equals(StockMarketState.newsSymbol) && cursor + 4.0F <= end) {
            Draw.fill(c, cursor, cy - 1.7F, 3.4F, 3.4F, 1.7F, ShopTheme.red());
        }
    }

    // ---- 右：详情栏

    private void drawDetail(Canvas c, AddonFrame f, float x, float y, float w, float h) {
        Draw.fill(c, x, y, 0.5F, h, 0, ShopTheme.borderDim());
        StockMarketState.Def def = StockMarketState.selectedDef();
        StockMarketState.Quote q = StockMarketState.selectedQuote();
        if (def == null || q == null) {
            hideForm();
            V2.empty(c, tr("gui.stock.empty"), x, y, w, h);
            return;
        }
        float px = x + DETAIL_GAP;
        float pw = w - DETAIL_GAP * 2.0F;
        StockMarketState.Position pos = StockMarketState.position(def.symbol);
        boolean compact = h < 320.0F;

        float cy = y + 3.0F;
        drawHeader(c, def, q, pos, px, cy, pw);
        cy += 26.0F;

        // 自上而下：统计格（高度够才画）、K 线（吃掉剩余高度）、盘口、下单区
        float formH = compact ? 63.0F : 100.0F;
        float bookH = 9.0F + 5 * BOOK_ROW + 3.0F;
        float rest = y + h - 3.0F - formH - bookH - cy - CHIP_H - 4.0F;
        boolean stats = !compact && rest - 36.0F >= 70.0F;
        if (stats) {
            drawStats(c, def, q, pos, px, cy, pw);
            cy += 36.0F;
            rest -= 36.0F;
        }
        float chartH = Math.max(36.0F, rest);
        drawChartBlock(c, f, def, px, cy, pw, chartH);
        cy += CHIP_H + 2.0F + chartH + 4.0F;

        drawBook(c, f, q, px, cy, pw);
        cy += bookH;

        drawForm(c, f, def, q, pos, px, cy, pw, compact);
        updateChart(def.symbol);
    }

    private void drawHeader(Canvas c, StockMarketState.Def def, StockMarketState.Quote q,
            StockMarketState.Position pos, float x, float y, float w) {
        long bps = q.changeBps();
        int trend = Ui.trend(bps);
        float half = w * 0.5F;
        V2.bold(c, V2.money(q.price), 11.5F, trend, x + half, y + 7.0F, half, Text.Align.RIGHT);
        float nw = V2.bold(c, def.label(), V2.FS_T, ShopTheme.text(), x, y + 7.0F, half - 2.0F, Text.Align.LEFT);
        if (half - nw > 22.0F) {
            V2.text(c, def.symbol, V2.FS_S, ShopTheme.text3(), x + nw + 3.0F, y + 7.5F, half - nw - 5.0F,
                    Text.Align.LEFT);
        }
        float ty = y + 19.0F;
        float cursor = x;
        float limit = x + w - 46.0F;
        if (!def.sector.isEmpty() && cursor + V2.tagWidth(def.sector) < limit) {
            cursor += V2.tag(c, def.sector, ShopTheme.text2(), cursor, ty);
        }
        String status;
        int statusColor;
        if (def.halted) {
            status = tr("gui.stock.v2.tag.halt");
            statusColor = ShopTheme.gold();
        } else if (!def.enabled) {
            status = tr("gui.stock.v2.tag.delisted");
            statusColor = ShopTheme.text3();
        } else if (StockMarketState.enabled && StockMarketState.session) {
            status = tr("gui.stock.v2.tag.trading");
            statusColor = ShopTheme.accent();
        } else {
            status = tr("gui.stock.v2.tag.closed");
            statusColor = ShopTheme.gold();
        }
        if (cursor + V2.tagWidth(status) < limit) {
            cursor += V2.tag(c, status, statusColor, cursor, ty);
        }
        if (!pos.isEmpty()) {
            String held = pos.quantity > 0L && pos.shortQuantity > 0L
                    ? tr("gui.stock.v2.tag.held_both", pos.quantity, pos.shortQuantity)
                    : pos.quantity > 0L ? tr("gui.stock.v2.tag.held_long", V2.money(pos.quantity))
                    : tr("gui.stock.v2.tag.held_short", V2.money(pos.shortQuantity));
            if (cursor + V2.tagWidth(held) < limit) {
                V2.tag(c, held, ShopTheme.accent(), cursor, ty);
            }
        }
        long chg = q.price - baseOf(q);
        V2.bold(c, Ui.signed(chg) + "  " + Ui.pct(bps), V2.FS_S, trend, x + w - 70.0F, ty, 70.0F, Text.Align.RIGHT);
    }

    private void drawStats(Canvas c, StockMarketState.Def def, StockMarketState.Quote q,
            StockMarketState.Position pos, float x, float y, float w) {
        long profit = StockMarketState.positionProfit(def.symbol);
        long avg = pos.quantity > 0L ? pos.averageCost() : pos.shortQuantity > 0L ? pos.averageShortPrice() : 0L;
        String[] labels = {tr("gui.stock.v2.stat.open"), tr("gui.stock.v2.stat.high"), tr("gui.stock.v2.stat.low"),
                tr("gui.stock.v2.stat.prev"), tr("gui.stock.v2.stat.vol"), tr("gui.stock.v2.stat.vola"),
                tr("gui.stock.v2.stat.avg"), tr("gui.stock.v2.stat.pnl")};
        String[] values = {
                q.dayOpen > 0L ? V2.money(q.dayOpen) : "—",
                q.dayHigh > 0L ? V2.money(q.dayHigh) : "—",
                q.dayLow > 0L ? V2.money(q.dayLow) : "—",
                baseOf(q) > 0L ? V2.money(baseOf(q)) : "—",
                compact(q.volume),
                String.format(Locale.ROOT, "%.1f%%", def.volatility * 100.0D),
                avg > 0L ? V2.money(avg) : "—",
                pos.isEmpty() ? "—" : Ui.signed(profit)};
        int[] colors = {ShopTheme.text(), ShopTheme.red(), ShopTheme.green(), ShopTheme.text(), ShopTheme.text(),
                ShopTheme.text(), ShopTheme.text(), pos.isEmpty() ? ShopTheme.text3() : Ui.trend(profit)};
        float cw = w / 4.0F;
        for (int i = 0; i < 8; i++) {
            float cx = x + (i % 4) * cw;
            float cy = y + (i / 4) * 17.0F;
            V2.text(c, labels[i], V2.FS_XS, ShopTheme.text3(), cx, cy + 4.5F, cw - 2.0F, Text.Align.LEFT);
            V2.bold(c, values[i], V2.FS_S, colors[i], cx, cy + 12.0F, cw - 2.0F, Text.Align.LEFT);
        }
        Draw.hairline(c, x, y + 34.5F, w, ShopTheme.borderDim());
    }

    private static String compact(long v) {
        if (v >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", v / 1_000_000.0D);
        }
        if (v >= 100_000L) {
            return String.format(Locale.ROOT, "%.0fk", v / 1000.0D);
        }
        return V2.money(v);
    }

    // K 线：周期切换 + 图
    private void drawChartBlock(Canvas c, AddonFrame f, StockMarketState.Def def, float x, float y, float w,
            float chartH) {
        float cy = y + CHIP_H / 2.0F;
        V2.bold(c, tr("gui.stock.v2.kline"), V2.FS_S, ShopTheme.text2(), x, cy, 30.0F, Text.Align.LEFT);
        // 周期芯片：右对齐
        BarPeriod[] periods = BarPeriod.all();
        float total = 0.0F;
        float[] widths = new float[periods.length];
        for (int i = 0; i < periods.length; i++) {
            widths[i] = V2.width(periodLabel(periods[i]), V2.FS_S, true) + 7.0F;
            total += widths[i] + 1.0F;
        }
        float cursor = x + w - total + 1.0F;
        boolean locked = StockMarketState.legacyServer;
        this.chipRect[0] = cursor;
        this.chipRect[1] = y;
        this.chipRect[2] = total;
        this.chipRect[3] = CHIP_H;
        for (int i = 0; i < periods.length; i++) {
            boolean selected = StockMarketState.period == periods[i];
            boolean hover = !locked && Ui.in(f.mouseX(), f.mouseY(), cursor, y, widths[i], CHIP_H);
            if (selected) {
                Draw.fill(c, cursor, y, widths[i], CHIP_H, 2.5F, ShopTheme.accentTint());
            } else if (hover) {
                Draw.fill(c, cursor, y, widths[i], CHIP_H, 2.5F, ShopTheme.rowHover());
            }
            String label = periodLabel(periods[i]);
            if (selected) {
                V2.bold(c, label, V2.FS_S, ShopTheme.accent(), cursor, cy, widths[i], Text.Align.CENTER);
            } else {
                V2.text(c, label, V2.FS_S, locked ? ShopTheme.text3() : ShopTheme.text2(), cursor, cy, widths[i],
                        Text.Align.CENTER);
            }
            if (!locked) {
                this.hits.add(cursor, y, widths[i], CHIP_H, "period:" + periods[i].id());
            }
            cursor += widths[i] + 1.0F;
        }
        float by = y + CHIP_H + 2.0F;
        boolean over = Ui.in(f.mouseX(), f.mouseY(), x, by, w, chartH);
        Draw.fill(c, x, by, w, chartH, V2.R, Draw.alpha(ShopTheme.card(), 0.45F));
        Draw.stroke(c, x, by, w, chartH, V2.R, 0.5F,
                over ? Draw.alpha(ShopTheme.border(), 0.75F) : Draw.alpha(ShopTheme.borderDim(), 0.9F));
        this.chartRect[0] = x;
        this.chartRect[1] = by;
        this.chartRect[2] = w;
        this.chartRect[3] = chartH;
        this.chartShown = true;
        if (StockMarketState.chartReady(def.symbol)) {
            ChartRenderer.draw(c, StockMarketState.chart, x + 3.0F, by + 3.0F, w - 6.0F, chartH - 6.0F,
                    f.mouseX(), f.mouseY());
        } else {
            V2.text(c, tr("gui.stock.chart_loading"), V2.FS_S, ShopTheme.text3(), x, by + chartH / 2.0F, w,
                    Text.Align.CENTER);
        }
    }

    private static String periodLabel(BarPeriod period) {
        return tr("gui.stock.period." + period.id());
    }

    // 五档盘口：左买右卖，字号不小于 FS_S，点击填入限价
    private static long bidPrice(StockMarketState.Quote q, int i) {
        return q.bidPrices[i] > 0L ? q.bidPrices[i] : Math.max(1L, q.price - i - 1L);
    }

    private static long bidVol(StockMarketState.Quote q, int i) {
        return q.bidVolumes[i] > 0L ? q.bidVolumes[i] : (i + 1L) * 45L;
    }

    private static long askPrice(StockMarketState.Quote q, int i) {
        return q.askPrices[i] > 0L ? q.askPrices[i] : q.price + i + 1L;
    }

    private static long askVol(StockMarketState.Quote q, int i) {
        return q.askVolumes[i] > 0L ? q.askVolumes[i] : (i + 1L) * 40L;
    }

    private void drawBook(Canvas c, AddonFrame f, StockMarketState.Quote q, float x, float y, float w) {
        float half = (w - 4.0F) / 2.0F;
        long maxVol = 1L;
        for (int i = 0; i < StockMarketState.BOOK_LEVELS; i++) {
            maxVol = Math.max(maxVol, Math.max(askVol(q, i), bidVol(q, i)));
        }
        long limit = StockMarketState.limitMode ? StockMarketState.parseLong(StockMarketState.limitPrice, -1L) : -1L;
        for (int side = 0; side < 2; side++) {
            boolean bid = side == 0;
            float cx = x + side * (half + 4.0F);
            int color = bid ? ShopTheme.red() : ShopTheme.green();
            V2.bold(c, tr(bid ? "gui.stock.v2.book.bid" : "gui.stock.v2.book.ask"), V2.FS_S, ShopTheme.text2(),
                    cx + 1.0F, y + 4.5F, half, Text.Align.LEFT);
            V2.text(c, tr("gui.stock.v2.book.pv"), V2.FS_XS, ShopTheme.text3(), cx, y + 4.5F, half - 1.0F,
                    Text.Align.RIGHT);
            for (int i = 0; i < StockMarketState.BOOK_LEVELS; i++) {
                float ry = y + 9.0F + i * BOOK_ROW;
                long p = bid ? bidPrice(q, i) : askPrice(q, i);
                long v = bid ? bidVol(q, i) : askVol(q, i);
                float depth = (half - 1.0F) * Math.min(1.0F, v / (float) maxVol);
                Draw.fill(c, cx + half - depth, ry + 0.5F, depth, BOOK_ROW - 1.0F, 1.0F, Draw.alpha(color, 0.17F));
                boolean over = Ui.in(f.mouseX(), f.mouseY(), cx, ry, half, BOOK_ROW);
                if (over) {
                    Draw.fill(c, cx, ry, half, BOOK_ROW, 1.0F, ShopTheme.rowHover());
                }
                if (p == limit) {
                    Draw.stroke(c, cx, ry + 0.25F, half, BOOK_ROW - 0.5F, 1.5F, 0.75F, ShopTheme.accent());
                }
                float cyRow = ry + BOOK_ROW / 2.0F;
                V2.text(c, tr(bid ? "gui.stock.book_bid_level" : "gui.stock.book_ask_level", i + 1), V2.FS_S,
                        ShopTheme.text3(), cx + 1.5F, cyRow, 12.0F, Text.Align.LEFT);
                V2.text(c, V2.money(p), V2.FS_S, color, cx + 12.0F, cyRow, half * 0.5F, Text.Align.LEFT);
                V2.text(c, compact(v), V2.FS_S, ShopTheme.text2(), cx, cyRow, half - 2.0F, Text.Align.RIGHT);
                this.hits.add(cx, ry, half, BOOK_ROW, (bid ? "bid:" : "ask:") + i);
            }
        }
    }

    // ---- 下单区

    private static long refPrice(StockMarketState.Quote q) {
        return StockMarketState.limitMode
                ? Math.max(1L, StockMarketState.parseLong(StockMarketState.limitPrice, q.price))
                : q.price;
    }

    private void drawForm(Canvas c, AddonFrame f, StockMarketState.Def def, StockMarketState.Quote q,
            StockMarketState.Position pos, float x, float y, float w, boolean compact) {
        this.formShown = true;
        float mx = f.mouseX();
        float my = f.mouseY();
        float gap = 3.0F;
        float cursor = y;

        // 行 A：市价 / 限价（compact 时右侧直接放限价输入）
        this.modeY = cursor;
        String[] modes = {tr("gui.stock.market"), tr("gui.stock.limit")};
        this.modeRects = V2.segmented(c, modes, StockMarketState.limitMode ? 1 : 0, x, cursor, null);
        float afterSeg = this.modeRects[1][0] + this.modeRects[1][1] + 4.0F;
        if (compact) {
            placePrice(c, f, afterSeg, cursor, x + w - afterSeg);
        } else {
            V2.text(c, tr("gui.stock.v2.can_close", V2.money(pos.available()), V2.money(pos.availableShort())),
                    V2.FS_S, ShopTheme.text2(), afterSeg, cursor + V2.CTRL / 2.0F, x + w - afterSeg, Text.Align.RIGHT);
        }
        cursor += V2.CTRL + gap;

        // 行 B：数量 + 满仓 + 清仓
        float qb = 24.0F;
        float qx = x + w - qb * 2.0F - 3.0F;
        this.quantity.bounds(x, cursor, Math.max(20.0F, qx - x - 3.0F), V2.CTRL).visible(true);
        this.quantity.render(c, mx, my, f.dtMs());
        Ui.button(c, this.hits, "fill", tr("gui.stock.fill_buy"), V2.Btn.NORMAL, qx, cursor, qb, V2.CTRL, mx, my);
        Ui.button(c, this.hits, "clear", tr("gui.stock.fill_sell"), V2.Btn.NORMAL, qx + qb + 3.0F, cursor, qb,
                V2.CTRL, mx, my);
        cursor += V2.CTRL + gap;

        // 行 C：限价（非 compact）
        if (!compact) {
            placePrice(c, f, x, cursor, w);
            cursor += V2.CTRL + gap;
        }

        // 预估：金额 + 手续费 + 含费合计
        long qty = StockMarketState.parseLong(StockMarketState.quantity, 0L);
        long ref = refPrice(q);
        long amount = StockMarketState.multiply(Math.max(0L, qty), ref);
        long fee = StockMarketState.fee(amount);
        boolean any = amount > 0L;
        if (compact) {
            V2.text(c, tr("gui.stock.v2.est.short", any ? V2.money(amount) : "—"), V2.FS_S, ShopTheme.text2(), x,
                    cursor + 4.5F, w * 0.5F, Text.Align.LEFT);
            V2.text(c, tr("gui.stock.v2.est.short2", any ? V2.money(fee) : "—", any ? V2.money(amount + fee) : "—"),
                    V2.FS_S, ShopTheme.text(), x + w * 0.35F, cursor + 4.5F, w * 0.65F, Text.Align.RIGHT);
            cursor += 9.0F + gap;
        } else {
            String feeRate = String.format(Locale.ROOT, "%.2f%%", StockMarketState.feeBps / 100.0D);
            V2.text(c, tr("gui.stock.v2.est.amount", V2.money(ref), V2.money(Math.max(0L, qty))), V2.FS_S,
                    ShopTheme.text2(), x, cursor + 4.0F, w * 0.62F, Text.Align.LEFT);
            V2.bold(c, any ? V2.money(amount) : "—", V2.FS_S, ShopTheme.text(), x, cursor + 4.0F, w, Text.Align.RIGHT);
            V2.text(c, tr("gui.stock.v2.est.fee", feeRate, V2.money(StockMarketState.minFee)), V2.FS_S,
                    ShopTheme.text2(), x, cursor + 12.5F, w * 0.7F, Text.Align.LEFT);
            V2.text(c, any ? V2.money(fee) : "—", V2.FS_S, ShopTheme.text(), x, cursor + 12.5F, w, Text.Align.RIGHT);
            V2.text(c, tr("gui.stock.v2.est.total"), V2.FS_S, ShopTheme.text2(), x, cursor + 21.0F, w * 0.4F,
                    Text.Align.LEFT);
            V2.bold(c, any ? tr("gui.stock.v2.est.both", V2.money(amount + fee), V2.money(Math.max(0L, amount - fee)))
                    : "—", V2.FS_S, ShopTheme.text(), x + w * 0.25F, cursor + 21.0F, w * 0.75F, Text.Align.RIGHT);
            cursor += 26.0F + gap;
        }

        // 四个方向按钮
        boolean tradable = StockMarketState.enabled && StockMarketState.session && !def.halted
                && !StockMarketState.isPending();
        float bh = compact ? V2.CTRL : 17.0F;
        float bg = 2.5F;
        float bw = (w - bg * 3.0F) / 4.0F;
        Ui.tradeButton(c, this.hits, "buy", tr(StockMarketState.isPending() ? "gui.stock.pending"
                : "gui.stock.buy_long"), ShopTheme.red(), true, tradable && def.enabled, x, cursor, bw, bh, mx, my);
        Ui.tradeButton(c, this.hits, "sell", tr("gui.stock.sell_long"), ShopTheme.green(), false,
                tradable && pos.available() > 0L, x + (bw + bg), cursor, bw, bh, mx, my);
        Ui.tradeButton(c, this.hits, "short", tr("gui.stock.open_short"), ShopTheme.green(), true,
                tradable && def.enabled, x + (bw + bg) * 2.0F, cursor, bw, bh, mx, my);
        Ui.tradeButton(c, this.hits, "cover", tr("gui.stock.cover_short"), ShopTheme.red(), false,
                tradable && pos.availableShort() > 0L, x + (bw + bg) * 3.0F, cursor, bw, bh, mx, my);
    }

    private void placePrice(Canvas c, AddonFrame f, float x, float y, float w) {
        if (StockMarketState.limitMode) {
            this.price.bounds(x, y, Math.max(20.0F, w), V2.CTRL).visible(true).enabled(true);
            this.price.render(c, f.mouseX(), f.mouseY(), f.dtMs());
        } else {
            this.price.visible(false);
            V2.fieldBox(c, x, y, w, V2.CTRL, false);
            V2.text(c, tr("gui.stock.v2.market_fill"), V2.FS_S, ShopTheme.text3(), x + 5.0F, y + V2.CTRL / 2.0F,
                    w - 10.0F, Text.Align.LEFT);
        }
    }

    private void setLimitMode(boolean limit) {
        if (limit == StockMarketState.limitMode) {
            return;
        }
        StockMarketState.limitMode = limit;
        StockMarketState.Quote quote = StockMarketState.selectedQuote();
        if (limit && quote != null && StockMarketState.limitPrice.isEmpty()) {
            StockMarketState.limitPrice = String.valueOf(quote.price);
            this.price.setValue(StockMarketState.limitPrice);
        }
    }

    private void fillMaxQuantity() {
        StockMarketState.Quote quote = StockMarketState.selectedQuote();
        StockMarketState.Position position = StockMarketState.position(StockMarketState.selected);
        if (quote == null) {
            return;
        }
        long ref = refPrice(quote);
        long unit = Math.max(1L, ref + StockMarketState.fee(ref));
        long affordable = StockMarketState.balance <= 0L ? 0L : StockMarketState.balance / unit;
        long room = Math.max(0L, StockMarketState.maxPosition - position.quantity);
        long value = Math.min(Math.min(affordable, room), StockMarketState.maxTradeQuantity);
        if (value > 0L) {
            setQuantity(value);
        }
    }

    private void fillCloseQuantity() {
        StockMarketState.Position pos = StockMarketState.position(StockMarketState.selected);
        long avail = pos.available() > 0L ? pos.available() : pos.availableShort();
        if (avail > 0L) {
            setQuantity(Math.min(avail, StockMarketState.maxTradeQuantity));
        }
    }

    private void setQuantity(long value) {
        StockMarketState.quantity = String.valueOf(Math.max(0L, value));
        this.quantity.setValue(StockMarketState.quantity);
    }

    private void pickPrice(long picked) {
        StockMarketState.limitMode = true;
        StockMarketState.limitPrice = String.valueOf(picked);
        this.price.setValue(StockMarketState.limitPrice);
    }

    /** 全量重拉：换股 / 换周期尽快（服务端对换股不节流），平时每 15 秒一次兜底，实时更新靠行情广播里的增量柱。 */
    private void updateChart(String symbol) {
        long now = System.currentTimeMillis();
        boolean stale = !StockMarketState.chartReady(symbol);
        boolean expired = now - this.chartAt > 15_000L;
        if ((stale && now - this.chartAt > 250L) || (!stale && expired)) {
            this.chartAt = now;
            StockMarketState.requestChart(symbol);
        }
    }

    private void syncFields() {
        if (this.quantity != null && !this.quantity.value().equals(StockMarketState.quantity)) {
            this.quantity.setValue(StockMarketState.quantity);
        }
        if (this.price != null && !this.price.isFocused()
                && !this.price.value().equals(StockMarketState.limitPrice)) {
            this.price.setValue(StockMarketState.limitPrice);
        }
    }

    private void hideForm() {
        this.formShown = false;
        if (this.quantity != null) {
            this.quantity.visible(false);
        }
        if (this.price != null) {
            this.price.visible(false);
        }
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(AddonFrame f, double mx, double my, int button) {
        if (!StockMarketState.hasSnapshot) {
            return false;
        }
        if (this.search != null && this.search.mouseClicked(mx, my, button)) {
            return true;
        }
        if (this.search != null && this.search.isFocused()) {
            this.search.blur();
        }
        if (button == 0) {
            int seg = Ui.segHit(this.scopeRects, this.scopeY, mx, my);
            if (seg >= 0) {
                scope = seg;
                TABLE.scrollToTop();
                return true;
            }
        }
        if (this.formShown) {
            if (this.quantity.mouseClicked(mx, my, button)) {
                return true;
            }
            if (StockMarketState.limitMode && this.price.mouseClicked(mx, my, button)) {
                return true;
            }
            if (button == 0) {
                int seg = Ui.segHit(this.modeRects, this.modeY, mx, my);
                if (seg >= 0) {
                    setLimitMode(seg == 1);
                    return true;
                }
            }
        }
        if (button == 0) {
            String id = this.hits.at(mx, my);
            if (id != null && handleHit(id)) {
                return true;
            }
            if (this.chartShown && Ui.in(mx, my, this.chartRect[0], this.chartRect[1], this.chartRect[2],
                    this.chartRect[3])) {
                if (mx <= this.chartRect[0] + 48.0F && my <= this.chartRect[1] + 14.0F) {
                    StockMarketState.resetZoom();
                } else {
                    this.draggingChart = true;
                    this.dragAccumX = 0.0D;
                }
                return true;
            }
            if (TABLE.pressBar(mx, my)) {
                return true;
            }
        }
        ListTable.Hit hit = TABLE.click(mx, my);
        if (hit == null) {
            return false;
        }
        if ("sort".equals(hit.button())) {
            String key = this.cols.get(TABLE.sortCol).id;
            if (!key.equals(sortKey)) {
                sortKey = key;
                sortDesc = true;
            } else {
                sortDesc = TABLE.sortDesc;
            }
            TABLE.scrollToTop();
            return true;
        }
        if (hit.row() < 0 || hit.row() >= this.shown.size() || button != 0) {
            return hit.row() >= 0;
        }
        String symbol = this.shown.get(hit.row());
        float[][] pos = TABLE.cols();
        float[] star = pos[pos.length - 1];
        if (mx >= star[0] - 3.0F && mx < star[0] + star[1] + 3.0F) {
            StockMarketState.toggleWatch(symbol);
        } else {
            StockMarketState.select(symbol);
            this.chartAt = 0L;
        }
        return true;
    }

    private boolean handleHit(String id) {
        if (id.startsWith("sec:")) {
            String key = id.substring(4);
            sector = key.equals(sector) ? "" : key;
            TABLE.scrollToTop();
            return true;
        }
        if (id.startsWith("period:")) {
            BarPeriod p = BarPeriod.parse(id.substring(7));
            if (!StockMarketState.legacyServer && p != StockMarketState.period) {
                StockMarketState.setPeriod(p);
                this.chartAt = 0L;
            }
            return true;
        }
        StockMarketState.Quote q = StockMarketState.selectedQuote();
        if (id.startsWith("bid:") || id.startsWith("ask:")) {
            if (q != null) {
                int level = Integer.parseInt(id.substring(4));
                pickPrice(id.startsWith("bid:") ? bidPrice(q, level) : askPrice(q, level));
            }
            return true;
        }
        switch (id) {
            case "fill" -> fillMaxQuantity();
            case "clear" -> fillCloseQuantity();
            case "buy", "sell", "short", "cover" -> StockMarketState.submitSide(id);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mx, double my, int button) {
        this.draggingChart = false;
        boolean handled = TABLE.releaseBar();
        if (this.quantity != null) {
            handled |= this.quantity.mouseReleased(mx, my, button);
        }
        if (this.price != null) {
            handled |= this.price.mouseReleased(mx, my, button);
        }
        if (this.search != null) {
            handled |= this.search.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mx, double my, int button, double dx, double dy) {
        if (this.draggingChart) {
            this.dragAccumX += dx;
            double barPx = Math.max(2.0D, 110.0D / Math.max(8.0F, StockMarketState.zoomBars));
            int deltaBars = (int) (this.dragAccumX / barPx);
            if (deltaBars != 0) {
                this.dragAccumX -= deltaBars * barPx;
                StockMarketState.panBars = Math.max(0, StockMarketState.panBars + deltaBars);
            }
            return true;
        }
        if (TABLE.dragBar(my)) {
            return true;
        }
        return (this.quantity != null && this.quantity.mouseDragged(mx, my, button, dx, dy))
                || (this.search != null && this.search.mouseDragged(mx, my, button, dx, dy))
                || (this.price != null && this.price.mouseDragged(mx, my, button, dx, dy));
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mx, double my, double amount) {
        if (this.chartShown && Ui.in(mx, my, this.chartRect[0], this.chartRect[1], this.chartRect[2],
                this.chartRect[3])) {
            StockMarketState.zoomChart(amount);
            return true;
        }
        return TABLE.wheel(mx, my, amount, net.minecraft.client.gui.screens.Screen.hasShiftDown());
    }

    @Override
    public void clickedOutside() {
        this.draggingChart = false;
        if (this.search != null) {
            this.search.blur();
        }
        if (this.quantity != null) {
            this.quantity.blur();
        }
        if (this.price != null) {
            this.price.blur();
        }
    }

    @Override
    public void cancelInput() {
        clickedOutside();
        TABLE.releaseBar();
        hideForm();
        if (this.search != null) {
            this.search.visible(false);
        }
    }
}
