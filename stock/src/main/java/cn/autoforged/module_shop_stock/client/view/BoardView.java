package cn.autoforged.module_shop_stock.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop_stock.client.StockMarketState;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import net.minecraft.network.chat.Component;

/** 榜单页：持仓收益率排行与手续费基金余额。 */
public final class BoardView implements StockView {
    private static final float PAD = 4.0F;
    private static final float ROW_H = 18.0F;
    private long lastRequest;

    @Override
    public void render(Canvas canvas, AddonFrame f) {
        long now = System.currentTimeMillis();
        if (now - this.lastRequest > 8000L) {
            this.lastRequest = now;
            StockMarketState.requestBoard();
        }
        float x = f.x() + PAD;
        float y = f.y() + 2.0F;
        float w = f.w() - PAD * 2.0F;
        float bottom = f.listBottom() - 2.0F;
        String currency = StockMarketState.currency(f.host().currencyName());

        Draw.fill(canvas, x, y, w, 15.0F, 3.0F, Draw.alpha(ShopTheme.card(), 0.7F));
        Text.drawLine(canvas, Component.translatable("gui.stock.board_head",
                StockMarketState.number(StockMarketState.fund), currency).getString(),
                ShopTheme.regular(), Text.SMALL, ShopTheme.text2(), x + 4.0F, y + 8.0F, w - 8.0F, Text.Align.LEFT);

        float top = y + 18.0F;
        List<StockMarketState.BoardRow> rows = StockMarketState.board();
        if (rows.isEmpty()) {
            Text.drawLine(canvas, Component.translatable("gui.stock.no_board").getString(), ShopTheme.regular(),
                    Text.SMALL, ShopTheme.text3(), x, top + (bottom - top) / 2.0F, w, Text.Align.CENTER);
            return;
        }
        int max = (int) Math.min(rows.size(), Math.floor((bottom - top) / ROW_H));
        for (int i = 0; i < max; i++) {
            StockMarketState.BoardRow row = rows.get(i);
            float rowY = top + i * ROW_H;
            if (row.me) {
                Draw.fill(canvas, x, rowY, w, ROW_H - 1.0F, 2.0F, ShopTheme.accentTint());
            }
            Text.drawLine(canvas, String.valueOf(i + 1), ShopTheme.regular(), 6.5F, ShopTheme.text3(),
                    x + 2.0F, rowY + 8.5F, 12.0F, Text.Align.LEFT);
            Text.drawLine(canvas, row.name, row.me ? ShopTheme.bold() : ShopTheme.regular(), Text.SMALL,
                    ShopTheme.text(), x + 16.0F, rowY + 8.5F, w * 0.46F, Text.Align.LEFT);
            Text.drawLine(canvas, StockMarketState.percent(row.pct), ShopTheme.regular(), 6.5F,
                    row.pct > 0L ? ShopTheme.red() : row.pct < 0L ? ShopTheme.green() : ShopTheme.text3(),
                    x + w * 0.58F, rowY + 8.5F, w * 0.18F, Text.Align.RIGHT);
            Text.drawLine(canvas, StockMarketState.number(row.value), ShopTheme.regular(), 6.5F, ShopTheme.text2(),
                    x + w * 0.76F, rowY + 8.5F, w * 0.22F, Text.Align.RIGHT);
        }
    }
}
