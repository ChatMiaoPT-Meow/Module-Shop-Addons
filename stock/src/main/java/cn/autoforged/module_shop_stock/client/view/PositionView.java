package cn.autoforged.module_shop_stock.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop_stock.client.StockMarketState;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;

/** 持仓页：同时展示多仓（[多]）与空仓（[空]）的股数、开仓均价、净值与浮动盈亏。 */
public final class PositionView implements StockView {
    private static final float PAD = 4.0F;
    private static final float ROW_H = 21.0F;
    private final ScrollController scroll = new ScrollController();
    private final Runnable openQuote;

    public PositionView(Runnable openQuote) {
        this.openQuote = openQuote;
    }

    @Override
    public void render(Canvas canvas, AddonFrame f) {
        float x = f.x() + PAD;
        float y = f.y() + 2.0F;
        float w = f.w() - PAD * 2.0F;
        float bottom = f.listBottom() - 2.0F;
        String currency = StockMarketState.currency(f.host().currencyName());

        long value = StockMarketState.marketValue();
        long profit = StockMarketState.profit();
        long cost = 0L;
        for (StockMarketState.Position position : StockMarketState.positions().values()) {
            cost += position.cost + position.shortMargin;
        }
        long pct = cost <= 0L ? 0L : profit * 10_000L / cost;
        Draw.fill(canvas, x, y, w, 15.0F, 3.0F, Draw.alpha(ShopTheme.card(), 0.7F));
        Text.drawLine(canvas, Component.translatable("gui.stock.position_head",
                StockMarketState.number(rows().size()),
                StockMarketState.number(value), currency).getString(),
                ShopTheme.regular(), Text.SMALL, ShopTheme.text2(), x + 4.0F, y + 8.0F, w * 0.7F, Text.Align.LEFT);
        Text.drawLine(canvas, StockMarketState.percent(pct), ShopTheme.regular(), Text.SMALL,
                pct > 0L ? ShopTheme.red() : pct < 0L ? ShopTheme.green() : ShopTheme.text3(),
                x + w * 0.7F, y + 8.0F, w * 0.28F, Text.Align.RIGHT);

        float top = y + 18.0F;
        float viewH = Math.max(12.0F, bottom - top - 2.0F);
        List<Map.Entry<String, StockMarketState.Position>> rows = rows();
        this.scroll.setContent(rows.size() * ROW_H, viewH);
        this.scroll.update();

        if (rows.isEmpty()) {
            Text.drawLine(canvas, Component.translatable("gui.stock.no_position").getString(), ShopTheme.regular(),
                    Text.SMALL, ShopTheme.text3(), x, top + viewH / 2.0F, w, Text.Align.CENTER);
            return;
        }

        int saved = canvas.save();
        try {
            Draw.clip(canvas, x, top, w, viewH);
            for (int i = 0; i < rows.size(); i++) {
                float rowY = top + i * ROW_H - this.scroll.offset();
                if (rowY + ROW_H < top || rowY > top + viewH) {
                    continue;
                }
                renderRow(canvas, f, rows.get(i), x, rowY, w);
            }
        } finally {
            canvas.restoreToCount(saved);
        }
        this.scroll.drawBar(canvas, x, top, w, viewH, f.mouseX(), f.mouseY(), f.dtMs());
    }

    private void renderRow(Canvas canvas, AddonFrame f, Map.Entry<String, StockMarketState.Position> entry,
            float x, float y, float w) {
        String sym = entry.getKey();
        StockMarketState.Def def = StockMarketState.def(sym);
        StockMarketState.Position pos = entry.getValue();
        long value = StockMarketState.positionValue(sym);
        long profit = StockMarketState.positionProfit(sym);
        long baseCost = Math.max(1L, pos.cost + pos.shortMargin);
        boolean hover = f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + ROW_H - 1.0F;
        if (hover) {
            Draw.fill(canvas, x, y, w, ROW_H - 1.0F, 2.0F, ShopTheme.rowHover());
        }
        String badge = pos.quantity > 0L && pos.shortQuantity > 0L ? "[多+空] "
                : (pos.shortQuantity > 0L ? "[空] " : "[多] ");
        Text.drawLine(canvas, badge + def.label(), ShopTheme.regular(), Text.SMALL, ShopTheme.text(), x + 3.0F, y + 7.0F,
                w * 0.56F, Text.Align.LEFT);
        Text.drawLine(canvas, StockMarketState.percent(profit * 10_000L / baseCost), ShopTheme.regular(), Text.SMALL,
                profit > 0L ? ShopTheme.red() : profit < 0L ? ShopTheme.green() : ShopTheme.text3(),
                x + w * 0.55F, y + 7.0F, w * 0.42F, Text.Align.RIGHT);

        long totalQty = pos.quantity > 0L ? pos.quantity : pos.shortQuantity;
        long avgPrice = pos.quantity > 0L ? pos.averageCost() : pos.averageShortPrice();
        Text.drawLine(canvas, Component.translatable("gui.stock.position_line",
                StockMarketState.number(totalQty),
                StockMarketState.number(avgPrice),
                StockMarketState.number(value)).getString(),
                ShopTheme.regular(), Text.SMALL, ShopTheme.text3(), x + 3.0F, y + 16.0F, w - 6.0F, Text.Align.LEFT);
    }

    @Override
    public boolean mouseClicked(AddonFrame f, double mouseX, double mouseY, int button) {
        float x = f.x() + PAD;
        float w = f.w() - PAD * 2.0F;
        float top = f.y() + 2.0F + 18.0F;
        float viewH = f.listBottom() - 2.0F - top - 2.0F;
        if (this.scroll.pressBar(mouseX, mouseY, x, top, w, viewH)) {
            return true;
        }
        if (mouseX < x || mouseX > x + w || mouseY < top || mouseY > top + viewH) {
            return false;
        }
        int index = (int) ((mouseY - top + this.scroll.offset()) / ROW_H);
        List<Map.Entry<String, StockMarketState.Position>> rows = rows();
        if (index < 0 || index >= rows.size()) {
            return false;
        }
        String symbol = rows.get(index).getKey();
        StockMarketState.Position pos = rows.get(index).getValue();
        StockMarketState.select(symbol);
        long avail = pos.available() > 0L ? pos.available() : pos.availableShort();
        StockMarketState.quantity = String.valueOf(Math.min(avail, StockMarketState.maxTradeQuantity));
        this.openQuote.run();
        return true;
    }

    private static List<Map.Entry<String, StockMarketState.Position>> rows() {
        List<Map.Entry<String, StockMarketState.Position>> rows = new ArrayList<>();
        for (Map.Entry<String, StockMarketState.Position> entry : StockMarketState.positions().entrySet()) {
            if (!entry.getValue().isEmpty() && StockMarketState.def(entry.getKey()) != null) {
                rows.add(entry);
            }
        }
        rows.sort((a, b) -> Long.compare(StockMarketState.positionValue(b.getKey()),
                StockMarketState.positionValue(a.getKey())));
        return rows;
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mouseX, double mouseY, int button) {
        return this.scroll.releaseBar();
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mouseX, double mouseY, int button, double dragX, double dragY) {
        return this.scroll.dragBar(mouseY, f.listBottom() - 2.0F - (f.y() + 2.0F + 18.0F) - 2.0F);
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mouseX, double mouseY, double amount) {
        this.scroll.wheel(amount);
        return true;
    }
}
