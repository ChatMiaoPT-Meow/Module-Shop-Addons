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
import net.minecraft.network.chat.Component;

/** 新闻 + 榜单页（UI v2）：左边是已下发的新闻列表（点击跳到该股行情），右边是手续费基金与收益榜。 */
public final class NewsBoardView implements StockView {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final ListTable NEWS = new ListTable();
    private static final ListTable BOARD = new ListTable();
    private static int scope;

    private final Runnable openQuote;
    private List<StockMarketState.News> shown = List.of();
    private float[][] scopeRects = new float[0][2];
    private float scopeY;
    private long lastRequest;

    public NewsBoardView(Runnable openQuote) {
        this.openQuote = openQuote;
    }

    private static String tr(String key, Object... args) {
        return Ui.tr(key, args);
    }

    private static List<StockMarketState.News> filtered() {
        List<StockMarketState.News> out = new ArrayList<>();
        for (StockMarketState.News n : StockMarketState.NEWS_FEED) {
            if (scope == 1 && n.impact() <= 0) {
                continue;
            }
            if (scope == 2 && n.impact() >= 0) {
                continue;
            }
            out.add(n);
        }
        return out;
    }

    private static String timeText(long at) {
        if (at <= 0L) {
            return "—";
        }
        ZoneId zone = ZoneId.systemDefault();
        var time = Instant.ofEpochMilli(at).atZone(zone);
        return (time.toLocalDate().equals(LocalDate.now(zone)) ? CLOCK : DAY_CLOCK).format(time);
    }

    @Override
    public void toolbar(Canvas c, AddonFrame f, float x, float y, float w) {
        float cy = y + V2.BAR / 2.0F;
        int good = 0;
        int bad = 0;
        for (StockMarketState.News n : StockMarketState.NEWS_FEED) {
            if (n.impact() > 0) {
                good++;
            } else if (n.impact() < 0) {
                bad++;
            }
        }
        String[] labels = {tr("gui.stock.v2.scope.all") + " " + StockMarketState.NEWS_FEED.size(),
                tr("gui.stock.v2.news.good") + " " + good, tr("gui.stock.v2.news.bad") + " " + bad};
        this.scopeY = cy - V2.CTRL / 2.0F;
        this.scopeRects = V2.segmented(c, labels, scope, x, this.scopeY, null);
        float[] last = this.scopeRects[this.scopeRects.length - 1];
        float rx = last[0] + last[1] + 10.0F;
        if (x + w - rx > 80.0F) {
            V2.text(c, tr("gui.stock.v2.news.note"), V2.FS_XS, ShopTheme.text3(), rx, cy, x + w - rx,
                    Text.Align.RIGHT);
        }
    }

    @Override
    public void render(Canvas c, AddonFrame b) {
        long now = System.currentTimeMillis();
        if (now - this.lastRequest > 8000L) {
            this.lastRequest = now;
            StockMarketState.requestBoard();
        }
        this.shown = filtered();
        float bw = b.w() >= 640.0F ? 250.0F : 180.0F;
        float nw = b.w() - bw;
        drawNews(c, b, b.x(), b.y(), nw, b.h());
        drawBoard(c, b, b.x() + nw, b.y(), bw, b.h());
    }

    @Override
    public String footLeft() {
        return tr("gui.stock.v2.news.foot", this.shown.size(), StockMarketState.board().size());
    }

    @Override
    public String footHint() {
        return tr("gui.stock.v2.news.hint");
    }

    // ------------------------------------------------------------------ 新闻

    private void drawNews(Canvas c, AddonFrame f, float x, float y, float w, float h) {
        boolean withToday = w >= 380.0F;
        List<String> ids = new ArrayList<>();
        List<String> heads = new ArrayList<>();
        List<Text.Align> aligns = new ArrayList<>();
        List<Float> spec = new ArrayList<>();
        ids.add("time");
        heads.add(tr("gui.stock.v2.col.time"));
        aligns.add(Text.Align.LEFT);
        spec.add(50.0F);
        ids.add("stock");
        heads.add(tr("gui.stock.v2.col.target"));
        aligns.add(Text.Align.LEFT);
        spec.add(78.0F);
        ids.add("impact");
        heads.add(tr("gui.stock.v2.col.impact"));
        aligns.add(Text.Align.RIGHT);
        spec.add(40.0F);
        if (withToday) {
            ids.add("today");
            heads.add(tr("gui.stock.v2.col.today"));
            aligns.add(Text.Align.RIGHT);
            spec.add(44.0F);
        }
        ids.add("text");
        heads.add(tr("gui.stock.v2.col.content"));
        aligns.add(Text.Align.LEFT);
        spec.add(0.0F);
        float[] sp = new float[spec.size()];
        for (int i = 0; i < sp.length; i++) {
            sp[i] = spec.get(i);
        }
        float[][] pos = V2.columns(x, w, 8.0F, 6.0F, sp);
        String[] headArr = heads.toArray(new String[0]);
        Text.Align[] alignArr = aligns.toArray(new Text.Align[0]);
        NEWS.layout(x, y, w, h, pos, headArr, alignArr, null);
        if (this.shown.isEmpty()) {
            V2.tableHead(c, x, y, w, pos, headArr, alignArr, -1, false);
            V2.empty(c, tr("gui.stock.v2.news.empty"), x, y + V2.HEAD, w, h - V2.HEAD);
            return;
        }
        NEWS.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), this.shown.size(), (cv, row, ry, hv) -> {
            StockMarketState.News n = this.shown.get(row);
            StockMarketState.Def def = StockMarketState.def(n.symbol());
            StockMarketState.Quote q = StockMarketState.quote(n.symbol());
            float cy = ry + V2.ROW / 2.0F;
            int trend = n.impact() > 0 ? ShopTheme.red() : n.impact() < 0 ? ShopTheme.green() : ShopTheme.text3();
            for (int i = 0; i < ids.size(); i++) {
                float cx = pos[i][0];
                float cw = pos[i][1];
                switch (ids.get(i)) {
                    case "time" -> V2.text(cv, timeText(n.at()), V2.FS_S, ShopTheme.text2(), cx, cy, cw,
                            Text.Align.LEFT);
                    case "stock" -> {
                        float sw = V2.text(cv, def == null ? n.symbol() : def.label(), V2.FS, ShopTheme.text(), cx,
                                cy, cw, Text.Align.LEFT);
                        if (cw - sw > 26.0F) {
                            V2.text(cv, n.symbol(), V2.FS_S, ShopTheme.text3(), cx + sw + 4.0F, cy, cw - sw - 4.0F,
                                    Text.Align.LEFT);
                        }
                    }
                    case "impact" -> V2.bold(cv, (n.impact() > 0 ? "+" : n.impact() < 0 ? "−" : "")
                            + Math.abs(n.impact()) + "%", V2.FS, trend, cx, cy, cw, Text.Align.RIGHT);
                    case "today" -> {
                        if (q == null) {
                            V2.text(cv, "—", V2.FS_S, ShopTheme.text3(), cx, cy, cw, Text.Align.RIGHT);
                        } else {
                            long bps = q.changeBps();
                            V2.text(cv, Ui.pct(bps), V2.FS_S, Ui.trend(bps), cx, cy, cw, Text.Align.RIGHT);
                        }
                    }
                    case "text" -> V2.text(cv, n.text(), V2.FS, ShopTheme.text(), cx, cy, cw, Text.Align.LEFT);
                    default -> {
                    }
                }
            }
        });
        int row = NEWS.hoverRow();
        if (row >= 0 && row < this.shown.size()) {
            float[] textCol = pos[pos.length - 1];
            String text = this.shown.get(row).text();
            if (V2.width(text, V2.FS, false) > textCol[1] && f.mouseX() >= textCol[0]) {
                f.host().textTooltip(Component.literal(text));
            }
        }
    }

    // ------------------------------------------------------------------ 榜单

    private void drawBoard(Canvas c, AddonFrame f, float x, float y, float w, float h) {
        Draw.fill(c, x, y, 0.5F, h, 0, ShopTheme.borderDim());
        String currency = StockMarketState.currency(f.host().currencyName());
        float px = x + 8.0F;
        float pw = w - 16.0F;
        float cy = y + 8.0F;
        V2.section(c, tr("gui.stock.v2.board.fund"), px, cy, pw);
        cy += 10.0F;
        float vw = V2.bold(c, V2.money(StockMarketState.fund), V2.FS_T + 2.0F, ShopTheme.gold(), px, cy + 2.0F, pw,
                Text.Align.LEFT);
        V2.text(c, currency, V2.FS_S, ShopTheme.text2(), px + vw + 4.0F, cy + 3.5F, pw - vw - 4.0F, Text.Align.LEFT);
        cy += 13.0F;
        V2.text(c, tr("gui.stock.v2.board.fund_note", String.format(Locale.ROOT, "%.2f%%",
                StockMarketState.feeBps / 100.0D), V2.money(StockMarketState.minFee)), V2.FS_S, ShopTheme.text2(),
                px, cy, pw, Text.Align.LEFT);
        cy += 9.0F;

        // 自己的名次（榜上有自己时才有）
        List<StockMarketState.BoardRow> list = StockMarketState.board();
        int me = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).me) {
                me = i;
                break;
            }
        }
        if (me >= 0) {
            StockMarketState.BoardRow r = list.get(me);
            Draw.fill(c, px, cy, pw, 16.0F, V2.R, ShopTheme.accentTint());
            Draw.stroke(c, px, cy, pw, 16.0F, V2.R, 0.75F, ShopTheme.accent());
            V2.bold(c, tr("gui.stock.v2.board.me", me + 1), V2.FS_S, ShopTheme.text(), px + 5.0F, cy + 8.0F, pw * 0.6F,
                    Text.Align.LEFT);
            V2.bold(c, Ui.pct(r.pct), V2.FS_S, Ui.trend(r.pct), px, cy + 8.0F, pw - 4.0F, Text.Align.RIGHT);
            cy += 20.0F;
        } else {
            cy += 2.0F;
        }
        V2.section(c, tr("gui.stock.v2.board.title"), px, cy + 3.0F, pw);
        cy += 9.0F;

        boolean full = w >= 230.0F;
        List<String> ids = new ArrayList<>();
        List<String> heads = new ArrayList<>();
        List<Text.Align> aligns = new ArrayList<>();
        List<Float> spec = new ArrayList<>();
        ids.add("rank");
        heads.add(tr("gui.stock.v2.col.rank"));
        aligns.add(Text.Align.LEFT);
        spec.add(14.0F);
        ids.add("name");
        heads.add(tr("gui.stock.v2.col.player"));
        aligns.add(Text.Align.LEFT);
        spec.add(0.0F);
        if (full) {
            ids.add("value");
            heads.add(tr("gui.stock.v2.col.net"));
            aligns.add(Text.Align.RIGHT);
            spec.add(46.0F);
            ids.add("cost");
            heads.add(tr("gui.stock.v2.col.cost"));
            aligns.add(Text.Align.RIGHT);
            spec.add(42.0F);
        }
        ids.add("pct");
        heads.add(tr("gui.stock.v2.col.ret"));
        aligns.add(Text.Align.RIGHT);
        spec.add(44.0F);
        float[] sp = new float[spec.size()];
        for (int i = 0; i < sp.length; i++) {
            sp[i] = spec.get(i);
        }
        float[][] pos = V2.columns(x, w, 6.0F, 4.0F, sp);
        String[] headArr = heads.toArray(new String[0]);
        Text.Align[] alignArr = aligns.toArray(new Text.Align[0]);
        float th = y + h - cy;
        BOARD.layout(x, cy, w, th, pos, headArr, alignArr, null);
        if (list.isEmpty()) {
            V2.tableHead(c, x, cy, w, pos, headArr, alignArr, -1, false);
            V2.empty(c, tr("gui.stock.no_board"), x, cy + V2.HEAD, w, th - V2.HEAD);
            return;
        }
        BOARD.render(c, f.host().items(), f.mouseX(), f.mouseY(), f.dtMs(), list.size(), (cv, row, ry, hover) -> {
            StockMarketState.BoardRow r = list.get(row);
            if (r.me) {
                BOARD.selected(cv, ry);
            }
            float rowCy = ry + V2.ROW / 2.0F;
            for (int i = 0; i < ids.size(); i++) {
                float cx = pos[i][0];
                float cw = pos[i][1];
                switch (ids.get(i)) {
                    case "rank" -> drawRank(cv, row + 1, cx, rowCy);
                    case "name" -> {
                        if (r.me) {
                            V2.bold(cv, r.name, V2.FS, ShopTheme.text(), cx, rowCy, cw, Text.Align.LEFT);
                        } else {
                            V2.text(cv, r.name, V2.FS, ShopTheme.text(), cx, rowCy, cw, Text.Align.LEFT);
                        }
                    }
                    case "value" -> V2.text(cv, V2.money(r.value), V2.FS_S, ShopTheme.text2(), cx, rowCy, cw,
                            Text.Align.RIGHT);
                    case "cost" -> V2.text(cv, V2.money(r.cost), V2.FS_S, ShopTheme.text3(), cx, rowCy, cw,
                            Text.Align.RIGHT);
                    case "pct" -> V2.bold(cv, Ui.pct(r.pct), V2.FS_S, Ui.trend(r.pct), cx, rowCy, cw,
                            Text.Align.RIGHT);
                    default -> {
                    }
                }
            }
        });
    }

    /** 前三名画金银铜圆章，其余直接写数字。 */
    private static void drawRank(Canvas c, int rank, float x, float cy) {
        if (rank <= 3) {
            int color = rank == 1 ? ShopTheme.gold() : rank == 2 ? 0xFFB8C0CC : 0xFFC98B5A;
            Draw.fill(c, x - 1.0F, cy - 5.0F, 10.0F, 10.0F, 5.0F, color);
            V2.bold(c, String.valueOf(rank), V2.FS_S, 0xFF202020, x - 1.0F, cy, 10.0F, Text.Align.CENTER);
        } else {
            V2.text(c, String.valueOf(rank), V2.FS_S, ShopTheme.text3(), x, cy, 14.0F, Text.Align.LEFT);
        }
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(AddonFrame f, double mx, double my, int button) {
        if (button == 0) {
            int seg = Ui.segHit(this.scopeRects, this.scopeY, mx, my);
            if (seg >= 0) {
                scope = seg;
                NEWS.scrollToTop();
                return true;
            }
            if (NEWS.pressBar(mx, my) || BOARD.pressBar(mx, my)) {
                return true;
            }
        }
        ListTable.Hit hit = NEWS.click(mx, my);
        if (hit != null) {
            if (hit.row() >= 0 && hit.row() < this.shown.size() && button == 0) {
                String symbol = this.shown.get(hit.row()).symbol();
                if (StockMarketState.def(symbol) != null) {
                    StockMarketState.select(symbol);
                    this.openQuote.run();
                }
            }
            return true;
        }
        return BOARD.click(mx, my) != null;
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mx, double my, int button) {
        return NEWS.releaseBar() | BOARD.releaseBar();
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mx, double my, int button, double dx, double dy) {
        return NEWS.dragBar(my) || BOARD.dragBar(my);
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mx, double my, double amount) {
        boolean page = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        return NEWS.wheel(mx, my, amount, page) || BOARD.wheel(mx, my, amount, page);
    }

    @Override
    public void cancelInput() {
        NEWS.releaseBar();
        BOARD.releaseBar();
    }
}
