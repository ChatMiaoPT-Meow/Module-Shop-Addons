package cn.autoforged.module_shop_stock.market.bars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.autoforged.module_shop_stock.market.Bar;
import java.util.List;
import org.junit.jupiter.api.Test;

class BarSeriesTest {
    private static Bar bar(long time) {
        return new Bar(time, 100L);
    }

    @Test
    void dropsOldestWhenFull() {
        BarSeries series = new BarSeries(3);
        for (long t = 1; t <= 5; t++) {
            series.add(bar(t));
        }
        assertEquals(3, series.size());
        assertEquals(3L, series.get(0).time);
        assertEquals(5L, series.last().time);
    }

    @Test
    void shrinkingLimitDropsHeadImmediately() {
        BarSeries series = new BarSeries(100);
        for (long t = 1; t <= 50; t++) {
            series.add(bar(t));
        }
        series.setLimit(10);
        assertEquals(10, series.size());
        assertEquals(41L, series.get(0).time);
        series.setLimit(100);
        assertEquals(10, series.size());
    }

    @Test
    void wrapsAroundWithoutLosingOrder() {
        BarSeries series = new BarSeries(7);
        for (long t = 1; t <= 1000; t++) {
            series.add(bar(t));
        }
        for (int i = 0; i < series.size(); i++) {
            assertEquals(994L + i, series.get(i).time);
        }
    }

    @Test
    void emptySeriesHasNoLast() {
        BarSeries series = new BarSeries(5);
        assertNull(series.last());
        assertThrows(IndexOutOfBoundsException.class, () -> series.get(0));
    }

    @Test
    void prependKeepsNewestWithinLimit() {
        BarSeries series = new BarSeries(5);
        series.add(bar(10));
        series.add(bar(11));
        series.prepend(List.of(bar(1), bar(2), bar(3), bar(4)));
        assertEquals(5, series.size());
        assertEquals(2L, series.get(0).time);
        assertEquals(11L, series.last().time);
    }
}
