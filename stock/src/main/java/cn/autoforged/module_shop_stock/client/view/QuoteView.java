package cn.autoforged.module_shop_stock.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop_stock.client.ChartRenderer;
import cn.autoforged.module_shop_stock.client.StockMarketState;
import cn.autoforged.module_shop_stock.market.bars.BarPeriod;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * 现代证券交易终端首页版式（左：紧凑股票代码列表；中：K线盘面 + 多空双向委托；右：竖排五档买卖盘口订单簿）。
 *
 * <p>严格遵循主 Mod 的 280×182 内容区、ShopTheme 动态令牌与三档字号（6.5 / 8 / 10）规范。
 */
public final class QuoteView implements StockView {
    private static final float PAD = 4.0F;
    private static final float FIELD_H = 13.0F;
    private static final float ROW_H = 20.0F;
    private static final float LEFT_W = 64.0F;
    private static final float GAP = 5.0F;
    private static final float BOOK_W = 58.0F;
    private static final float HEADER_H = 15.0F;

    private static final float NAME_Y = 25.0F;
    private static final float PRICE_Y = 38.0F;
    private static final float CHART_Y = 45.0F;
    private static final float INFO_Y = 115.0F;
    private static final float FORM_Y = 122.0F;
    private static final float CHIP_H = 10.0F;
    private static final float CHIP_PAD = 2.5F;
    private static final float CHIP_GAP = 1.0F;

    private final ScrollController scroll = new ScrollController();
    private final Button kind;
    private final Button fillMax;
    private final Button clearPos;
    private final Button buyLong;
    private final Button sellLong;
    private final Button openShort;
    private final Button coverShort;

    private SkiaTextField search;
    private SkiaTextField quantity;
    private SkiaTextField price;
    private long chartAt;
    private boolean draggingChart;
    private double dragAccumX;

    public QuoteView() {
        this.kind = new Button(Button.Kind.NORMAL, this::kindLabel, this::toggleKind)
                .radius(2.0F).fontSize(Text.SMALL);
        this.fillMax = new Button(Button.Kind.NORMAL, () -> label("gui.stock.fill_buy"), this::fillMaxQuantity)
                .radius(2.0F).fontSize(Text.SMALL);
        this.clearPos = new Button(Button.Kind.NORMAL, () -> label("gui.stock.fill_sell"), this::fillCloseQuantity)
                .radius(2.0F).fontSize(Text.SMALL);
        this.buyLong = new Button(Button.Kind.ACCENT,
                () -> label(StockMarketState.isPending() ? "gui.stock.pending" : "gui.stock.buy_long"),
                () -> StockMarketState.submitSide("buy")).radius(2.0F).fontSize(Text.SMALL);
        this.sellLong = new Button(Button.Kind.NORMAL,
                () -> label("gui.stock.sell_long"),
                () -> StockMarketState.submitSide("sell")).radius(2.0F).fontSize(Text.SMALL);
        this.openShort = new Button(Button.Kind.DANGER,
                () -> label("gui.stock.open_short"),
                () -> StockMarketState.submitSide("short")).radius(2.0F).fontSize(Text.SMALL);
        this.coverShort = new Button(Button.Kind.NORMAL,
                () -> label("gui.stock.cover_short"),
                () -> StockMarketState.submitSide("cover")).radius(2.0F).fontSize(Text.SMALL);
    }

    private static String label(String key) {
        return Component.translatable(key).getString();
    }

    @Override
    public void init(AddonPageHost host) {
        this.search = new SkiaTextField(host.screen(), host.font(), 10)
                .placeholder(label("gui.stock.search")).fontSize(Text.SMALL);
        this.search.setValue(StockMarketState.query);
        this.search.onChange(text -> StockMarketState.query = text);
        host.registerInput(this.search.editBox());

        this.quantity = new SkiaTextField(host.screen(), host.font(), 7)
                .placeholder(label("gui.stock.quantity")).fontSize(Text.SMALL);
        this.quantity.editBox().setFilter(text -> text.chars().allMatch(Character::isDigit));
        this.quantity.setValue(StockMarketState.quantity);
        this.quantity.onChange(text -> StockMarketState.quantity = text);
        host.registerInput(this.quantity.editBox());

        this.price = new SkiaTextField(host.screen(), host.font(), 7)
                .placeholder(label("gui.stock.price")).fontSize(Text.SMALL);
        this.price.editBox().setFilter(text -> text.chars().allMatch(Character::isDigit));
        this.price.setValue(StockMarketState.limitPrice);
        this.price.onChange(text -> StockMarketState.limitPrice = text);
        host.registerInput(this.price.editBox());
    }

    private String kindLabel() {
        return label(StockMarketState.limitMode ? "gui.stock.limit" : "gui.stock.market");
    }

    private void toggleKind() {
        StockMarketState.limitMode = !StockMarketState.limitMode;
        StockMarketState.Quote quote = StockMarketState.selectedQuote();
        if (StockMarketState.limitMode && quote != null && StockMarketState.limitPrice.isEmpty()) {
            StockMarketState.limitPrice = String.valueOf(quote.price);
            if (this.price != null) {
                this.price.setValue(StockMarketState.limitPrice);
            }
        }
    }

    private void fillMaxQuantity() {
        StockMarketState.Quote quote = StockMarketState.selectedQuote();
        StockMarketState.Position position = StockMarketState.position(StockMarketState.selected);
        if (quote == null) {
            return;
        }
        long refPrice = StockMarketState.limitMode
                ? Math.max(1L, StockMarketState.parseLong(StockMarketState.limitPrice, quote.price))
                : quote.price;
        long unit = Math.max(1L, refPrice + priceFee(refPrice));
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
        if (this.quantity != null) {
            this.quantity.setValue(StockMarketState.quantity);
        }
    }

    private static long priceFee(long price) {
        return (Math.max(1L, price) * StockMarketState.feeBps + 9_999L) / 10_000L;
    }

    @Override
    public void render(Canvas canvas, AddonFrame f) {
        float x = f.x() + PAD;
        float y = f.y() + 2.0F;
        float w = f.w() - PAD * 2.0F;
        float bottom = f.listBottom() - 2.0F;

        if (!StockMarketState.hasSnapshot) {
            Text.drawLine(canvas, label("gui.stock.loading"), ShopTheme.regular(), Text.BODY, ShopTheme.text2(),
                    x, y + f.h() / 2.0F, w, Text.Align.CENTER);
            hideForm();
            return;
        }
        StockMarketState.zoomBars = Motion.approach(StockMarketState.zoomBars,
                (float) StockMarketState.targetZoomBars, f.dtMs(), 75.0F);

        syncFields();
        renderHeader(canvas, x, y, w);

        float contentTop = y + HEADER_H + 4.0F;
        float listTop = contentTop + FIELD_H + 3.0F;
        renderList(canvas, f, x, contentTop, LEFT_W, bottom - listTop);

        // 左中分隔线
        Draw.fill(canvas, x + LEFT_W + GAP / 2.0F, contentTop, 0.8F, bottom - contentTop,
                0.0F, Draw.alpha(ShopTheme.borderDim(), 0.8F));

        float centerX = x + LEFT_W + GAP;
        float bookX = x + w - BOOK_W;
        float centerW = bookX - GAP - centerX;

        // 中右分隔线（K线区与竖排五档订单簿之间）
        Draw.fill(canvas, bookX - GAP / 2.0F, contentTop, 0.8F, bottom - contentTop,
                0.0F, Draw.alpha(ShopTheme.borderDim(), 0.8F));

        renderCenterPane(canvas, f, centerX, y, centerW, bottom);
        renderOrderBook(canvas, f, bookX, contentTop, BOOK_W, bottom - contentTop);
    }

    private void renderHeader(Canvas canvas, float x, float y, float w) {
        Draw.fill(canvas, x, y, w, HEADER_H, 3.0F, Draw.alpha(ShopTheme.card(), 0.6F));
        long delta = StockMarketState.index - StockMarketState.indexPrev;
        int color = delta > 0L ? ShopTheme.red() : delta < 0L ? ShopTheme.green() : ShopTheme.text3();
        Text.drawLine(canvas, Component.translatable("gui.stock.index",
                StockMarketState.number(StockMarketState.index),
                StockMarketState.percent(StockMarketState.indexPrev <= 0L ? 0L
                        : delta * 10_000L / StockMarketState.indexPrev)).getString(),
                ShopTheme.regular(), Text.SMALL, color, x + 4.0F, y + HEADER_H / 2.0F, w * 0.40F, Text.Align.LEFT);
        Text.drawLine(canvas, Component.translatable("gui.stock.cash",
                StockMarketState.number(Math.max(0L, StockMarketState.balance))).getString(),
                ShopTheme.regular(), Text.SMALL, ShopTheme.text2(), x + w * 0.41F, y + HEADER_H / 2.0F, w * 0.28F,
                Text.Align.LEFT);
        Text.drawLine(canvas, Component.translatable("gui.stock.assets",
                StockMarketState.number(StockMarketState.totalAssets())).getString(),
                ShopTheme.regular(), Text.SMALL, ShopTheme.text(), x + w * 0.70F, y + HEADER_H / 2.0F, w * 0.29F,
                Text.Align.LEFT);
    }

    private void renderList(Canvas canvas, AddonFrame f, float x, float y, float w, float viewH) {
        this.search.bounds(x, y, w, FIELD_H).visible(true);
        this.search.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());

        float top = y + FIELD_H + 3.0F;
        List<String> symbols = StockMarketState.visibleSymbols();
        this.scroll.setContent(symbols.size() * ROW_H, viewH);
        this.scroll.update();

        if (symbols.isEmpty()) {
            Text.drawLine(canvas, label("gui.stock.no_match"), ShopTheme.regular(), Text.SMALL, ShopTheme.text3(),
                    x, top + 10.0F, w, Text.Align.CENTER);
            return;
        }
        int saved = canvas.save();
        try {
            Draw.clip(canvas, x, top, w, viewH);
            for (int i = 0; i < symbols.size(); i++) {
                float rowY = top + i * ROW_H - this.scroll.offset();
                if (rowY + ROW_H < top || rowY > top + viewH) {
                    continue;
                }
                renderRow(canvas, symbols.get(i), x, rowY, w, f.mouseX(), f.mouseY());
            }
        } finally {
            canvas.restoreToCount(saved);
        }
        this.scroll.drawBar(canvas, x, top, w, viewH, f.mouseX(), f.mouseY(), f.dtMs());
    }

    /** 紧凑化左侧股票行：第一行 ★ + 代码 + 异动红点，第二行 现价 + 涨跌幅。 */
    private void renderRow(Canvas canvas, String symbol, float x, float y, float w, float mouseX, float mouseY) {
        StockMarketState.Def def = StockMarketState.def(symbol);
        StockMarketState.Quote quote = StockMarketState.quote(symbol);
        if (def == null || quote == null) {
            return;
        }
        boolean selected = symbol.equals(StockMarketState.selected);
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H - 1.0F;
        if (selected) {
            Draw.fill(canvas, x, y, w, ROW_H - 1.0F, 2.0F, ShopTheme.accentTint());
            Draw.fill(canvas, x, y + 2.0F, 1.8F, ROW_H - 5.0F, 0.9F, ShopTheme.accent());
        } else if (hover) {
            Draw.fill(canvas, x, y, w, ROW_H - 1.0F, 2.0F, ShopTheme.rowHover());
        }
        boolean watched = StockMarketState.watch().contains(symbol);
        Text.drawLine(canvas, watched ? "★" : "☆", ShopTheme.regular(), Text.SMALL,
                watched ? ShopTheme.gold() : ShopTheme.text3(), x + 2.5F, y + 6.5F, 9.0F, Text.Align.LEFT);
        float symW = Text.drawLine(canvas, symbol, selected ? ShopTheme.bold() : ShopTheme.regular(), Text.SMALL,
                !def.enabled ? ShopTheme.text3() : def.halted ? ShopTheme.gold() : ShopTheme.text(),
                x + 11.5F, y + 6.5F, w - 18.0F, Text.Align.LEFT);
        if (StockMarketState.hasActiveNews() && symbol.equals(StockMarketState.newsSymbol)) {
            Draw.fill(canvas, x + 13.5F + symW, y + 4.8F, 3.4F, 3.4F, 1.7F, ShopTheme.red());
        }
        long bps = quote.changeBps();
        Text.drawLine(canvas, StockMarketState.number(quote.price), ShopTheme.regular(), Text.SMALL,
                ShopTheme.text2(), x + 3.0F, y + 14.8F, 26.0F, Text.Align.LEFT);
        Text.drawLine(canvas, StockMarketState.shortPercent(bps), ShopTheme.regular(), Text.SMALL,
                bps > 0L ? ShopTheme.red() : bps < 0L ? ShopTheme.green() : ShopTheme.text3(),
                x + 28.0F, y + 14.8F, w - 30.0F, Text.Align.RIGHT);
    }

    private void renderCenterPane(Canvas canvas, AddonFrame f, float x, float y, float w, float bottom) {
        StockMarketState.Def def = StockMarketState.selectedDef();
        StockMarketState.Quote quote = StockMarketState.selectedQuote();
        if (def == null || quote == null) {
            hideForm();
            Text.drawLine(canvas, label("gui.stock.empty"), ShopTheme.regular(), Text.SMALL, ShopTheme.text3(),
                    x, y + 30.0F, w, Text.Align.CENTER);
            return;
        }

        // 第一行：股票名称 + 代码（左）；K 线周期切换芯片（右，1分/5分/15分/1时/日/周）
        float chipsW = periodChipsWidth();
        float nameW = Math.max(20.0F, w - chipsW - 4.0F);
        float labelW = Text.drawLine(canvas, def.label(), ShopTheme.bold(), Text.BODY, ShopTheme.text(),
                x, y + NAME_Y, nameW, Text.Align.LEFT);
        if (nameW - labelW > 24.0F) {
            Text.drawLine(canvas, def.symbol, ShopTheme.regular(), Text.SMALL, ShopTheme.text3(),
                    x + labelW + 3.0F, y + NAME_Y, nameW - labelW - 3.0F, Text.Align.LEFT);
        }
        renderPeriodChips(canvas, f, x + w - chipsW, y + NAME_Y - CHIP_H / 2.0F);

        // 第二行：大号现价 + 涨跌幅 + 高低价摘要（取消了 5m/15m 按钮，空间留给盘面与订单簿）
        long bps = quote.changeBps();
        int color = bps > 0L ? ShopTheme.red() : bps < 0L ? ShopTheme.green() : ShopTheme.text3();
        String priceText = StockMarketState.number(quote.price);
        float priceW = Text.width(priceText, ShopTheme.bold(), Text.TITLE);
        Text.drawLine(canvas, priceText, ShopTheme.bold(), Text.TITLE, color,
                x, y + PRICE_Y, w * 0.45F, Text.Align.LEFT);
        Text.drawLine(canvas, StockMarketState.percent(bps), ShopTheme.regular(), Text.SMALL, color,
                x + priceW + 4.0F, y + PRICE_Y, w * 0.35F, Text.Align.LEFT);
        String hlText = "高" + StockMarketState.number(quote.dayHigh) + " 低" + StockMarketState.number(quote.dayLow);
        Text.drawLine(canvas, hlText, ShopTheme.regular(), Text.SMALL, ShopTheme.text3(),
                x + w * 0.48F, y + PRICE_Y, w * 0.52F, Text.Align.RIGHT);

        // 第三行：K 线盘面（支持滚轮无级缩放与左右拖拽）
        float chartH = Math.max(30.0F, bottom - (y + CHART_Y) - 48.0F);
        boolean hoverChart = f.mouseX() >= x && f.mouseX() <= x + w
                && f.mouseY() >= y + CHART_Y && f.mouseY() <= y + CHART_Y + chartH;
        Draw.fill(canvas, x, y + CHART_Y, w, chartH, 3.0F, Draw.alpha(ShopTheme.card(), 0.45F));
        Draw.stroke(canvas, x, y + CHART_Y, w, chartH, 3.0F, 1.0F,
                hoverChart ? Draw.alpha(ShopTheme.border(), 0.75F) : Draw.alpha(ShopTheme.borderDim(), 0.9F));
        if (StockMarketState.chartReady(def.symbol)) {
            ChartRenderer.draw(canvas, StockMarketState.chart, x + 3.0F, y + CHART_Y + 3.0F,
                    w - 6.0F, chartH - 6.0F, f.mouseX(), f.mouseY());
        } else {
            Text.drawLine(canvas, label("gui.stock.chart_loading"), ShopTheme.regular(), Text.SMALL,
                    ShopTheme.text3(), x, y + CHART_Y + chartH / 2.0F, w, Text.Align.CENTER);
        }

        // 第四行：多空持仓摘要 + 浮动盈亏 + 下单预估
        StockMarketState.Position position = StockMarketState.position(def.symbol);
        long pnl = StockMarketState.positionProfit(def.symbol);
        String holding;
        if (position.quantity > 0L && position.shortQuantity > 0L) {
            holding = "多" + StockMarketState.number(position.quantity) + " 空" + StockMarketState.number(position.shortQuantity);
        } else if (position.quantity > 0L) {
            holding = "多 " + StockMarketState.number(position.quantity) + " · 均 " + StockMarketState.number(position.averageCost());
        } else if (position.shortQuantity > 0L) {
            holding = "空 " + StockMarketState.number(position.shortQuantity) + " · 均 " + StockMarketState.number(position.averageShortPrice());
        } else {
            holding = label("gui.stock.no_holding");
        }
        Text.drawLine(canvas, holding, ShopTheme.regular(), Text.SMALL,
                position.isEmpty() ? ShopTheme.text3() : ShopTheme.text2(),
                x, y + INFO_Y, w * 0.50F, Text.Align.LEFT);
        if (!position.isEmpty()) {
            Text.drawLine(canvas, (pnl >= 0L ? "+" : "") + StockMarketState.number(pnl),
                    ShopTheme.regular(), Text.SMALL,
                    pnl > 0L ? ShopTheme.red() : pnl < 0L ? ShopTheme.green() : ShopTheme.text3(),
                    x + w * 0.46F, y + INFO_Y, w * 0.22F, Text.Align.LEFT);
        }
        long amount = StockMarketState.parseLong(StockMarketState.quantity, 0L);
        long refPrice = StockMarketState.limitMode
                ? Math.max(1L, StockMarketState.parseLong(StockMarketState.limitPrice, quote.price))
                : quote.price;
        Text.drawLine(canvas, Component.translatable("gui.stock.estimate",
                StockMarketState.number(amount * refPrice)).getString(),
                ShopTheme.regular(), Text.SMALL, ShopTheme.text3(), x + w * 0.64F, y + INFO_Y, w * 0.36F,
                Text.Align.RIGHT);

        renderForm(canvas, f, x, y + FORM_Y, w, def, quote, position);
        updateChart(def.symbol);
    }

    /** 右侧竖排五档买卖盘口（Order Book）：卖5→卖1（上）、最新价与价差（中）、买1→买5（下）。 */
    private void renderOrderBook(Canvas canvas, AddonFrame f, float x, float y, float w, float h) {
        Draw.fill(canvas, x, y, w, h, 3.0F, Draw.alpha(ShopTheme.card(), 0.52F));
        Draw.stroke(canvas, x, y, w, h, 3.0F, 1.0F, Draw.alpha(ShopTheme.borderDim(), 0.9F));

        // 顶部标题栏
        Text.drawLine(canvas, label("gui.stock.orderbook_title"), ShopTheme.bold(), Text.SMALL, ShopTheme.text2(),
                x + 3.0F, y + 6.5F, w - 6.0F, Text.Align.LEFT);
        Text.drawLine(canvas, "价/量", ShopTheme.regular(), Text.SMALL, ShopTheme.text3(),
                x + 3.0F, y + 6.5F, w - 6.0F, Text.Align.RIGHT);
        Draw.hairline(canvas, x + 2.0F, y + 12.0F, w - 4.0F, ShopTheme.borderDim());

        StockMarketState.Quote quote = StockMarketState.selectedQuote();
        if (quote == null) {
            return;
        }

        long maxVol = 1L;
        for (int i = 0; i < StockMarketState.BOOK_LEVELS; i++) {
            maxVol = Math.max(maxVol, Math.max(quote.askVolumes[i], quote.bidVolumes[i]));
        }

        float rowH = 9.6F;
        float askTop = y + 13.5F;

        // 上半区：卖5 到 卖1（倒序排列，卖1 紧贴中间现价栏）
        for (int idx = 0; idx < StockMarketState.BOOK_LEVELS; idx++) {
            int level = StockMarketState.BOOK_LEVELS - 1 - idx; // 4..0 -> 卖5..卖1
            float ry = askTop + idx * rowH;
            long ap = quote.askPrices[level] > 0L ? quote.askPrices[level] : quote.price + level + 1L;
            long av = quote.askVolumes[level] > 0L ? quote.askVolumes[level] : (level + 1L) * 40L;
            float depthW = Math.min(w - 4.0F, (w - 4.0F) * (av / (float) maxVol));
            Draw.fill(canvas, x + w - 2.0F - depthW, ry + 0.8F, depthW, rowH - 1.4F, 1.0F,
                    Draw.alpha(ShopTheme.green(), 0.18F));
            boolean hover = f.mouseX() >= x && f.mouseX() <= x + w && f.mouseY() >= ry && f.mouseY() < ry + rowH;
            if (hover) {
                Draw.fill(canvas, x + 1.0F, ry, w - 2.0F, rowH, 1.0F, ShopTheme.rowHover());
            }
            Text.drawLine(canvas, "卖" + (level + 1), ShopTheme.regular(), 6.0F, ShopTheme.text3(),
                    x + 2.5F, ry + rowH / 2.0F, 14.0F, Text.Align.LEFT);
            Text.drawLine(canvas, StockMarketState.number(ap), ShopTheme.regular(), 6.0F, ShopTheme.green(),
                    x + 15.5F, ry + rowH / 2.0F, 22.0F, Text.Align.LEFT);
            Text.drawLine(canvas, compactVol(av), ShopTheme.regular(), 6.0F, ShopTheme.text2(),
                    x + 34.0F, ry + rowH / 2.0F, w - 36.5F, Text.Align.RIGHT);
        }

        // 中间现价与盘口价差条
        float midY = askTop + StockMarketState.BOOK_LEVELS * rowH + 1.0F;
        float midH = 12.5F;
        Draw.fill(canvas, x + 2.0F, midY, w - 4.0F, midH, 2.0F, Draw.alpha(ShopTheme.content(), 0.9F));
        long bps = quote.changeBps();
        int midCol = bps > 0L ? ShopTheme.red() : bps < 0L ? ShopTheme.green() : ShopTheme.text();
        Text.drawLine(canvas, StockMarketState.number(quote.price), ShopTheme.bold(), Text.SMALL, midCol,
                x + 3.5F, midY + midH / 2.0F, w * 0.55F, Text.Align.LEFT);
        long spread = Math.max(1L, (quote.askPrices[0] > 0L ? quote.askPrices[0] : quote.price + 1L)
                - (quote.bidPrices[0] > 0L ? quote.bidPrices[0] : Math.max(1L, quote.price - 1L)));
        Text.drawLine(canvas, "差" + spread, ShopTheme.regular(), 6.0F, ShopTheme.text3(),
                x + w * 0.50F, midY + midH / 2.0F, w * 0.46F, Text.Align.RIGHT);

        // 下半区：买1 到 买5（正序排列，买1 紧贴中间现价栏）
        float bidTop = midY + midH + 1.5F;
        for (int level = 0; level < StockMarketState.BOOK_LEVELS; level++) {
            float ry = bidTop + level * rowH;
            long bp = quote.bidPrices[level] > 0L ? quote.bidPrices[level] : Math.max(1L, quote.price - level - 1L);
            long bv = quote.bidVolumes[level] > 0L ? quote.bidVolumes[level] : (level + 1L) * 45L;
            float depthW = Math.min(w - 4.0F, (w - 4.0F) * (bv / (float) maxVol));
            Draw.fill(canvas, x + w - 2.0F - depthW, ry + 0.8F, depthW, rowH - 1.4F, 1.0F,
                    Draw.alpha(ShopTheme.red(), 0.18F));
            boolean hover = f.mouseX() >= x && f.mouseX() <= x + w && f.mouseY() >= ry && f.mouseY() < ry + rowH;
            if (hover) {
                Draw.fill(canvas, x + 1.0F, ry, w - 2.0F, rowH, 1.0F, ShopTheme.rowHover());
            }
            Text.drawLine(canvas, "买" + (level + 1), ShopTheme.regular(), 6.0F, ShopTheme.text3(),
                    x + 2.5F, ry + rowH / 2.0F, 14.0F, Text.Align.LEFT);
            Text.drawLine(canvas, StockMarketState.number(bp), ShopTheme.regular(), 6.0F, ShopTheme.red(),
                    x + 15.5F, ry + rowH / 2.0F, 22.0F, Text.Align.LEFT);
            Text.drawLine(canvas, compactVol(bv), ShopTheme.regular(), 6.0F, ShopTheme.text2(),
                    x + 34.0F, ry + rowH / 2.0F, w - 36.5F, Text.Align.RIGHT);
        }
    }

    private static String compactVol(long v) {
        if (v >= 10_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fk", v / 1000.0D);
        }
        return String.valueOf(v);
    }

    /** 双排下单表单：第一排 [市价/限价] + 数量 + (限价) + [满仓] + [清仓]；第二排 [买多] [平多] [卖空] [平空]。 */
    private void renderForm(Canvas canvas, AddonFrame f, float x, float y, float w, StockMarketState.Def def,
            StockMarketState.Quote quote, StockMarketState.Position position) {
        float kindW = 24.0F;
        float quickW = 22.0F;
        float gap = 2.5F;
        float clearX = x + w - quickW;
        float fillX = clearX - quickW - gap;
        float fieldStart = x + kindW + gap;
        float fieldEnd = fillX - gap;
        float priceW = StockMarketState.limitMode ? Math.min(34.0F, (fieldEnd - fieldStart) * 0.45F) : 0.0F;
        float qtyW = fieldEnd - fieldStart - (StockMarketState.limitMode ? priceW + gap : 0.0F);

        this.kind.bounds(x, y, kindW, FIELD_H).visible(true);
        this.quantity.bounds(fieldStart, y, Math.max(20.0F, qtyW), FIELD_H).visible(true);
        this.price.bounds(fieldStart + qtyW + gap, y, priceW, FIELD_H)
                .visible(StockMarketState.limitMode).enabled(StockMarketState.limitMode);
        this.fillMax.bounds(fillX, y, quickW, FIELD_H);
        this.clearPos.bounds(clearX, y, quickW, FIELD_H);
        this.kind.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.quantity.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.price.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.fillMax.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.clearPos.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());

        boolean tradable = StockMarketState.enabled && StockMarketState.session && !def.halted
                && !StockMarketState.isPending();
        float btnGap = 2.5F;
        float btnW = (w - btnGap * 3.0F) / 4.0F;
        float buttonY = y + FIELD_H + 3.0F;
        this.buyLong.enabled(tradable && def.enabled)
                .bounds(x, buttonY, btnW, FIELD_H + 1.0F).visible(true);
        this.sellLong.enabled(tradable && position.available() > 0L)
                .bounds(x + (btnW + btnGap), buttonY, btnW, FIELD_H + 1.0F).visible(true);
        this.openShort.enabled(tradable && def.enabled)
                .bounds(x + (btnW + btnGap) * 2.0F, buttonY, btnW, FIELD_H + 1.0F).visible(true);
        this.coverShort.enabled(tradable && position.availableShort() > 0L)
                .bounds(x + (btnW + btnGap) * 3.0F, buttonY, btnW, FIELD_H + 1.0F).visible(true);

        this.buyLong.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.sellLong.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.openShort.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.coverShort.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
    }

    /** 全量重拉：换股/换周期尽快（服务端对换股不节流），平时每 15 秒一次兜底，实时更新靠行情广播里的增量柱。 */
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
        if (this.quantity != null) {
            this.quantity.visible(false);
        }
        if (this.price != null) {
            this.price.visible(false);
        }
        if (this.search != null) {
            this.search.visible(false);
        }
        for (Button b : buttons()) {
            b.visible(false);
        }
    }

    private static String periodLabel(BarPeriod period) {
        return label("gui.stock.period." + period.id());
    }

    private static float chipWidth(BarPeriod period) {
        return Text.width(periodLabel(period), ShopTheme.regular(), Text.SMALL) + CHIP_PAD * 2.0F;
    }

    private static float periodChipsWidth() {
        float total = 0.0F;
        BarPeriod[] periods = BarPeriod.all();
        for (BarPeriod period : periods) {
            total += chipWidth(period);
        }
        return total + CHIP_GAP * (periods.length - 1);
    }

    private void renderPeriodChips(Canvas canvas, AddonFrame f, float x, float y) {
        float cursor = x;
        boolean locked = StockMarketState.legacyServer;
        for (BarPeriod period : BarPeriod.all()) {
            float cw = chipWidth(period);
            boolean selected = StockMarketState.period == period;
            boolean hover = !locked && f.mouseX() >= cursor && f.mouseX() < cursor + cw
                    && f.mouseY() >= y && f.mouseY() < y + CHIP_H;
            if (selected) {
                Draw.fill(canvas, cursor, y, cw, CHIP_H, 2.0F, ShopTheme.accentTint());
            } else if (hover) {
                Draw.fill(canvas, cursor, y, cw, CHIP_H, 2.0F, ShopTheme.rowHover());
            }
            Text.drawLine(canvas, periodLabel(period), selected ? ShopTheme.bold() : ShopTheme.regular(), Text.SMALL,
                    selected ? ShopTheme.accent() : locked ? ShopTheme.text3() : ShopTheme.text2(),
                    cursor, y + CHIP_H / 2.0F, cw, Text.Align.CENTER);
            cursor += cw + CHIP_GAP;
        }
    }

    private boolean clickPeriodChips(AddonFrame f, double mouseX, double mouseY) {
        float x = centerPaneX(f);
        float w = centerPaneW(f);
        float y = f.y() + 2.0F + NAME_Y - CHIP_H / 2.0F;
        if (mouseY < y || mouseY >= y + CHIP_H) {
            return false;
        }
        float cursor = x + w - periodChipsWidth();
        for (BarPeriod period : BarPeriod.all()) {
            float cw = chipWidth(period);
            if (mouseX >= cursor && mouseX < cursor + cw) {
                if (!StockMarketState.legacyServer && period != StockMarketState.period) {
                    StockMarketState.setPeriod(period);
                    this.chartAt = 0L;
                }
                return true;
            }
            cursor += cw + CHIP_GAP;
        }
        return false;
    }

    private float centerPaneX(AddonFrame f) {
        return f.x() + PAD + LEFT_W + GAP;
    }

    private float centerPaneW(AddonFrame f) {
        float w = f.w() - PAD * 2.0F;
        return w - LEFT_W - BOOK_W - GAP * 2.0F;
    }

    private boolean insideChart(AddonFrame f, double mouseX, double mouseY) {
        float cx = centerPaneX(f);
        float cw = centerPaneW(f);
        float chartTop = f.y() + 2.0F + CHART_Y;
        float bottom = f.listBottom() - 2.0F;
        float chartH = Math.max(30.0F, bottom - chartTop - 48.0F);
        return mouseX >= cx && mouseX <= cx + cw && mouseY >= chartTop && mouseY <= chartTop + chartH;
    }

    @Override
    public boolean mouseClicked(AddonFrame f, double mouseX, double mouseY, int button) {
        if (!StockMarketState.hasSnapshot) {
            return false;
        }
        if (this.search != null && this.search.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        for (Button item : buttons()) {
            if (item.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        if (this.quantity != null && this.quantity.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (StockMarketState.limitMode && this.price != null && this.price.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (clickOrderBook(f, mouseX, mouseY)) {
            return true;
        }
        if (clickPeriodChips(f, mouseX, mouseY)) {
            return true;
        }
        if (insideChart(f, mouseX, mouseY)) {
            float cx = centerPaneX(f);
            float chartTop = f.y() + 2.0F + CHART_Y;
            if (mouseX <= cx + 48.0F && mouseY <= chartTop + 14.0F) {
                StockMarketState.resetZoom();
                return true;
            }
            this.draggingChart = true;
            this.dragAccumX = 0.0D;
            return true;
        }
        return clickList(f, mouseX, mouseY);
    }

    /** 点击右侧订单簿任意一档价格：自动切换至限价模式并将该档位价格填入限价框。 */
    private boolean clickOrderBook(AddonFrame f, double mouseX, double mouseY) {
        float x = f.x() + PAD;
        float y = f.y() + 2.0F;
        float w = f.w() - PAD * 2.0F;
        float bookX = x + w - BOOK_W;
        float contentTop = y + HEADER_H + 4.0F;
        float bottom = f.listBottom() - 2.0F;
        if (mouseX < bookX || mouseX > bookX + BOOK_W || mouseY < contentTop || mouseY > bottom) {
            return false;
        }
        StockMarketState.Quote quote = StockMarketState.selectedQuote();
        if (quote == null) {
            return false;
        }
        float rowH = 9.6F;
        float askTop = contentTop + 13.5F;
        float midY = askTop + StockMarketState.BOOK_LEVELS * rowH + 1.0F;
        float bidTop = midY + 12.5F + 1.5F;

        long pickedPrice = -1L;
        if (mouseY >= askTop && mouseY < askTop + StockMarketState.BOOK_LEVELS * rowH) {
            int idx = (int) ((mouseY - askTop) / rowH);
            int level = Math.max(0, Math.min(StockMarketState.BOOK_LEVELS - 1, StockMarketState.BOOK_LEVELS - 1 - idx));
            pickedPrice = quote.askPrices[level] > 0L ? quote.askPrices[level] : quote.price + level + 1L;
        } else if (mouseY >= bidTop && mouseY < bidTop + StockMarketState.BOOK_LEVELS * rowH) {
            int level = Math.max(0, Math.min(StockMarketState.BOOK_LEVELS - 1, (int) ((mouseY - bidTop) / rowH)));
            pickedPrice = quote.bidPrices[level] > 0L ? quote.bidPrices[level] : Math.max(1L, quote.price - level - 1L);
        }
        if (pickedPrice > 0L) {
            StockMarketState.limitMode = true;
            StockMarketState.limitPrice = String.valueOf(pickedPrice);
            if (this.price != null) {
                this.price.setValue(StockMarketState.limitPrice);
            }
            return true;
        }
        return false;
    }

    private Button[] buttons() {
        return new Button[]{this.kind, this.fillMax, this.clearPos,
                this.buyLong, this.sellLong, this.openShort, this.coverShort};
    }

    private boolean clickList(AddonFrame f, double mouseX, double mouseY) {
        float x = f.x() + PAD;
        float top = f.y() + 2.0F + HEADER_H + 4.0F + FIELD_H + 3.0F;
        float h = f.listBottom() - 2.0F - top;
        if (this.scroll.pressBar(mouseX, mouseY, x, top, LEFT_W, h)) {
            return true;
        }
        if (mouseX < x || mouseX > x + LEFT_W || mouseY < top || mouseY > top + h) {
            return false;
        }
        int index = (int) ((mouseY - top + this.scroll.offset()) / ROW_H);
        List<String> symbols = StockMarketState.visibleSymbols();
        if (index < 0 || index >= symbols.size()) {
            return false;
        }
        String symbol = symbols.get(index);
        if (mouseX < x + 11.0F) {
            StockMarketState.toggleWatch(symbol);
        } else {
            StockMarketState.select(symbol);
            this.chartAt = 0L;
        }
        return true;
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mouseX, double mouseY, int button) {
        this.draggingChart = false;
        boolean handled = this.scroll.releaseBar();
        for (Button item : buttons()) {
            handled |= item.mouseReleased(mouseX, mouseY, button);
        }
        if (this.quantity != null) {
            handled |= this.quantity.mouseReleased(mouseX, mouseY, button);
        }
        if (this.price != null) {
            handled |= this.price.mouseReleased(mouseX, mouseY, button);
        }
        if (this.search != null) {
            handled |= this.search.mouseReleased(mouseX, mouseY, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingChart) {
            this.dragAccumX += dragX;
            double barPx = Math.max(2.0D, 110.0D / Math.max(8.0F, StockMarketState.zoomBars));
            int deltaBars = (int) (this.dragAccumX / barPx);
            if (deltaBars != 0) {
                this.dragAccumX -= deltaBars * barPx;
                StockMarketState.panBars = Math.max(0, StockMarketState.panBars + deltaBars);
            }
            return true;
        }
        float h = f.listBottom() - 2.0F - (f.y() + 2.0F + HEADER_H + 4.0F + FIELD_H + 3.0F);
        if (this.scroll.dragBar(mouseY, h)) {
            return true;
        }
        return (this.quantity != null && this.quantity.mouseDragged(mouseX, mouseY, button, dragX, dragY))
                || (this.search != null && this.search.mouseDragged(mouseX, mouseY, button, dragX, dragY))
                || (this.price != null && this.price.mouseDragged(mouseX, mouseY, button, dragX, dragY));
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mouseX, double mouseY, double amount) {
        if (mouseX < f.x() + PAD + LEFT_W) {
            this.scroll.wheel(amount);
            return true;
        }
        if (insideChart(f, mouseX, mouseY)) {
            StockMarketState.zoomChart(amount);
            return true;
        }
        return false;
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
        this.draggingChart = false;
        for (Button item : buttons()) {
            item.cancelPress();
        }
        clickedOutside();
        hideForm();
    }
}
