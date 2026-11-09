package cn.autoforged.module_shop_stock.client;

import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import io.github.humbleui.skija.Canvas;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * K 线 + 均线（MA5 / MA15）+ 成交量 + 鼠标滚轮无级缩放与十字光标浮层。涨红跌绿。
 */
public final class ChartRenderer {
    private static final float AXIS_W = 22.0F;
    private static final float VOLUME_H = 9.0F;
    private static final float TIME_H = 10.0F;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());

    private ChartRenderer() {
    }

    public static void draw(Canvas canvas, StockMarketState.Chart chart, float x, float y, float w,
            float h, double mouseX, double mouseY) {
        if (chart == null || chart.isEmpty() || w < 36.0F || h < 24.0F) {
            return;
        }
        long[] at = chart.time;
        long[] ao = chart.open;
        long[] ah = chart.high;
        long[] al = chart.low;
        long[] ac = chart.close;
        long[] av = chart.volume;
        int index = at.length;
        if (index == 0) {
            return;
        }

        int desiredBars = Math.max(StockMarketState.MIN_ZOOM_BARS,
                Math.min(StockMarketState.MAX_ZOOM_BARS, Math.round(StockMarketState.zoomBars)));
        int visibleCapacity = Math.min(index, desiredBars);
        int maxPan = Math.max(0, index - visibleCapacity);
        StockMarketState.panBars = Math.max(0, Math.min(maxPan, StockMarketState.panBars));
        int end = index - StockMarketState.panBars;
        int first = Math.max(0, end - visibleCapacity);
        int bars = Math.max(1, end - first);

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        long maxVolume = 0L;
        for (int i = first; i < end; i++) {
            min = Math.min(min, al[i]);
            max = Math.max(max, ah[i]);
            maxVolume = Math.max(maxVolume, av[i]);
        }
        if (max <= min) {
            max = min + 1L;
        }
        long span = Math.max(1L, max - min);
        min = Math.max(1L, min - Math.max(1L, Math.round(span * 0.06D)));
        max = max + Math.max(2L, Math.round(span * 0.36D));

        // 刻度栏只留最长价格文字的宽度，其余全部给 K 线
        float labelW = Math.max(Text.width(StockMarketState.number(max), ShopTheme.regular(), 6.5F),
                Math.max(Text.width(StockMarketState.number(min), ShopTheme.regular(), 6.5F),
                        Text.width(StockMarketState.number(ac[end - 1]), ShopTheme.regular(), 6.5F)));
        float axisW = Math.min(AXIS_W, labelW + 5.0F);
        float plotW = w - axisW;

        float volumeBand = maxVolume > 0L ? VOLUME_H : 0.0F;
        float priceH = Math.max(12.0F, h - volumeBand - TIME_H - 2.0F);
        float top = y;
        float volumeTop = top + priceH + 2.0F;
        float timeTop = volumeTop + volumeBand;

        Draw.hairline(canvas, x, top, plotW, Draw.alpha(ShopTheme.borderDim(), 0.55F));
        Draw.hairline(canvas, x, top + priceH * 0.33F, plotW, Draw.alpha(ShopTheme.borderDim(), 0.24F));
        Draw.hairline(canvas, x, top + priceH * 0.66F, plotW, Draw.alpha(ShopTheme.borderDim(), 0.24F));
        Draw.hairline(canvas, x, top + priceH, plotW, Draw.alpha(ShopTheme.borderDim(), 0.55F));

        float slot = plotW / bars;
        float bodyW = Math.max(1.2F, Math.min(9.0F, slot * 0.66F));

        for (int i = first; i < end; i++) {
            int slotIndex = i - first;
            float cx = x + slot * slotIndex + slot / 2.0F;
            boolean up = ac[i] >= ao[i];
            int color = up ? ShopTheme.red() : ShopTheme.green();
            float yHigh = map(ah[i], min, max, top, priceH);
            float yLow = map(al[i], min, max, top, priceH);
            float yOpen = map(ao[i], min, max, top, priceH);
            float yClose = map(ac[i], min, max, top, priceH);
            Draw.line(canvas, cx, yHigh, cx, yLow, 0.85F, Draw.alpha(color, 0.85F));
            float bodyTop = Math.min(yOpen, yClose);
            float bodyH = Math.max(1.0F, Math.abs(yClose - yOpen));
            Draw.fill(canvas, cx - bodyW / 2.0F, bodyTop, bodyW, bodyH, 0.5F, color);

            if (maxVolume > 0L) {
                float barH = Math.max(0.8F, (float) av[i] / maxVolume * (VOLUME_H - 1.0F));
                Draw.fill(canvas, cx - bodyW / 2.0F, volumeTop + VOLUME_H - barH, bodyW, barH, 0.5F,
                        Draw.alpha(color, 0.45F));
            }
        }

        drawMovingAverage(canvas, ac, first, end, 5, min, max, x, top, priceH, slot,
                Draw.alpha(ShopTheme.gold(), 0.82F));
        drawMovingAverage(canvas, ac, first, end, 15, min, max, x, top, priceH, slot,
                Draw.alpha(ShopTheme.accent(), 0.78F));

        long last = ac[end - 1];
        float lastY = map(last, min, max, top, priceH);
        int lastColor = end >= 2 && last < ac[end - 2] ? ShopTheme.green() : ShopTheme.red();
        Draw.line(canvas, x, lastY, x + slot * (bars - 0.5F), lastY, 0.7F, Draw.alpha(lastColor, 0.45F));
        String lastText = StockMarketState.number(last);
        Draw.fill(canvas, x + plotW + 0.5F, lastY - 5.0F, axisW - 0.5F, 10.0F, 2.0F, ShopTheme.text());
        Text.drawLine(canvas, lastText, ShopTheme.regular(), 6.5F, ShopTheme.content(),
                x + plotW + 0.5F, lastY, axisW - 0.5F, Text.Align.CENTER);

        if (lastY > top + 14.0F) {
            Text.drawLine(canvas, StockMarketState.number(max), ShopTheme.regular(), 6.5F, ShopTheme.text3(),
                    x + plotW, top + 4.0F, axisW, Text.Align.RIGHT);
        }
        if (lastY < top + priceH - 14.0F) {
            Text.drawLine(canvas, StockMarketState.number(min), ShopTheme.regular(), 6.5F, ShopTheme.text3(),
                    x + plotW, top + priceH - 4.0F, axisW, Text.Align.RIGHT);
        }

        Text.drawLine(canvas, clock(at[first]), ShopTheme.regular(), 6.5F, ShopTheme.text3(),
                x, timeTop + TIME_H / 2.0F, plotW - 2.0F, Text.Align.LEFT);
        Text.drawLine(canvas, clock(at[end - 1]), ShopTheme.regular(), 6.5F, ShopTheme.text3(),
                x, timeTop + TIME_H / 2.0F, plotW - 2.0F, Text.Align.RIGHT);

        boolean hoverChart = mouseX >= x && mouseX <= x + plotW && mouseY >= top && mouseY <= volumeTop + VOLUME_H;
        String zoomLabel = String.format(Locale.ROOT, "×%.1f·%d根", StockMarketState.zoomRatio(), bars);
        float badgeW = Text.width(zoomLabel, ShopTheme.regular(), 6.5F) + 5.0F;
        Draw.fill(canvas, x + 1.5F, top + 1.5F, badgeW, 9.0F, 2.0F,
                Draw.alpha(hoverChart ? ShopTheme.accentTint() : ShopTheme.card(), 0.82F));
        Text.drawLine(canvas, zoomLabel, ShopTheme.regular(), 6.5F,
                hoverChart ? ShopTheme.accent() : ShopTheme.text2(),
                x + 4.0F, top + 6.0F, badgeW, Text.Align.LEFT);

        if (hoverChart) {
            int slotIndex = (int) ((mouseX - x) / slot);
            slotIndex = Math.max(0, Math.min(bars - 1, slotIndex));
            int bar = first + slotIndex;
            float cx = x + slot * slotIndex + slot / 2.0F;
            Draw.line(canvas, cx, top, cx, volumeTop + VOLUME_H, 0.7F, Draw.alpha(ShopTheme.text2(), 0.65F));
            float my = Math.max(top, Math.min(top + priceH, (float) mouseY));
            Draw.line(canvas, x, my, x + plotW, my, 0.7F, Draw.alpha(ShopTheme.text2(), 0.45F));

            String ohlc = String.format(Locale.ROOT, "高%d 低%d 收%d", ah[bar], al[bar], ac[bar]);
            Text.drawLine(canvas, ohlc, ShopTheme.regular(), 6.5F,
                    ac[bar] >= ao[bar] ? ShopTheme.red() : ShopTheme.green(),
                    x + badgeW + 3.5F, top + 6.0F, plotW - badgeW - 4.0F, Text.Align.LEFT);
            Text.drawLine(canvas, clock(at[bar]), ShopTheme.bold(), 6.5F, ShopTheme.text(),
                    x, timeTop + TIME_H / 2.0F, plotW - 2.0F, Text.Align.CENTER);
        }
    }

    private static void drawMovingAverage(Canvas canvas, long[] closes, int first, int end, int period,
            long min, long max, float x, float top, float priceH, float slot, int color) {
        if (end - first < 2) {
            return;
        }
        float prevX = Float.NaN;
        float prevY = Float.NaN;
        for (int i = first; i < end; i++) {
            int start = Math.max(0, i - period + 1);
            long sum = 0L;
            int cnt = i - start + 1;
            for (int k = start; k <= i; k++) {
                sum += closes[k];
            }
            float avg = (float) (sum / (double) cnt);
            int slotIndex = i - first;
            float cx = x + slot * slotIndex + slot / 2.0F;
            float cy = map(Math.round(avg), min, max, top, priceH);
            if (!Float.isNaN(prevX)) {
                Draw.line(canvas, prevX, prevY, cx, cy, 0.85F, color);
            }
            prevX = cx;
            prevY = cy;
        }
    }

    private static float map(long value, long min, long max, float top, float height) {
        double ratio = (value - min) / (double) Math.max(1L, max - min);
        return (float) (top + height - ratio * height);
    }

    private static String clock(long epochSecond) {
        return CLOCK.format(Instant.ofEpochSecond(epochSecond));
    }
}
