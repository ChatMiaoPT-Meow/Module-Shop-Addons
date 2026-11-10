package cn.autoforged.module_shop_stock.market.bars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.autoforged.module_shop_stock.market.Bar;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BarAggregatorTest {
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private static long at(int d, int h, int mi, int s) {
        return LocalDateTime.of(2026, 11, d, h, mi, s).atZone(CN).toEpochSecond();
    }

    @Test
    void oneSampleCreatesOneBarPerPeriod() {
        BarAggregator book = new BarAggregator(CN);
        book.sample(at(10, 12, 34, 5), 60, 100, 101, 103, 99, 5);
        for (BarPeriod period : BarPeriod.all()) {
            assertEquals(1, book.series(period).size(), period.id());
            Bar bar = book.series(period).get(0);
            assertEquals(100L, bar.open);
            assertEquals(103L, bar.high);
            assertEquals(99L, bar.low);
            assertEquals(101L, bar.close);
            assertEquals(5L, bar.volume);
        }
    }

    @Test
    void coarseBarFoldsFinerSamples() {
        BarAggregator book = new BarAggregator(CN);
        book.sample(at(10, 12, 30, 0), 60, 100, 100, 100, 100, 1);
        book.sample(at(10, 12, 31, 0), 60, 100, 110, 112, 100, 2);
        book.sample(at(10, 12, 32, 0), 60, 100, 95, 111, 90, 3);
        assertEquals(3, book.series(BarPeriod.M1).size());
        assertEquals(1, book.series(BarPeriod.M5).size());
        Bar five = book.series(BarPeriod.M5).get(0);
        assertEquals(100L, five.open);
        assertEquals(112L, five.high);
        assertEquals(90L, five.low);
        assertEquals(95L, five.close);
        assertEquals(6L, five.volume);
        // 新 1m 柱以上一根收盘作开盘
        assertEquals(110L, book.series(BarPeriod.M1).get(2).open);
    }

    @Test
    void crossingBoundariesOpensNewBarsAtPreviousClose() {
        BarAggregator book = new BarAggregator(CN);
        book.sample(at(10, 23, 59, 0), 60, 100, 100, 100, 100, 1);
        book.sample(at(11, 0, 0, 0), 60, 100, 105, 105, 100, 1);
        assertEquals(2, book.series(BarPeriod.D1).size());
        assertEquals(100L, book.series(BarPeriod.D1).get(1).open);
        assertEquals(2, book.series(BarPeriod.H1).size());
        // 周二 -> 周三仍同一周
        assertEquals(1, book.series(BarPeriod.W1).size());
        book.sample(at(16, 0, 0, 1), 60, 100, 106, 106, 105, 1);
        assertEquals(2, book.series(BarPeriod.W1).size());
    }

    @Test
    void tradeTouchesEveryPeriodLastBarAndClearsSeeded() {
        BarAggregator book = new BarAggregator(CN);
        book.sample(at(10, 12, 0, 0), 60, 100, 100, 100, 100, 0);
        for (BarPeriod period : BarPeriod.all()) {
            book.series(period).last().seeded = true;
        }
        book.trade(120, 7);
        for (BarPeriod period : BarPeriod.all()) {
            Bar bar = book.series(period).last();
            assertEquals(120L, bar.close, period.id());
            assertEquals(120L, bar.high, period.id());
            assertEquals(7L, bar.volume, period.id());
            assertFalse(bar.seeded, period.id());
        }
    }

    @Test
    void backfillBuildsCoarseSeriesFromSourceOnlyWhenEmpty() {
        BarAggregator book = new BarAggregator(CN);
        BarSeries source = book.series(BarPeriod.M1);
        for (int i = 0; i < 12; i++) {
            Bar bar = new Bar(at(10, 12, 0, 0) + i * 60L, 100 + i, 100 + i + 2, 100 + i - 1, 101 + i, 10);
            bar.seeded = true;
            source.add(bar);
        }
        book.backfillFromSource();
        BarSeries five = book.series(BarPeriod.M5);
        assertEquals(3, five.size());
        assertEquals(100L, five.get(0).open);
        assertEquals(105L, five.get(0).close);
        assertEquals(50L, five.get(0).volume);
        assertTrue(five.get(0).seeded);
        assertEquals(1, book.series(BarPeriod.D1).size());
        // 已有数据不被覆盖
        int before = five.size();
        book.backfillFromSource();
        assertEquals(before, five.size());
    }

    @Test
    void coarseSeriesOutlivesSourceWindow() {
        BarAggregator book = new BarAggregator(CN);
        book.setLimit(BarPeriod.M1, 20);
        for (int i = 0; i < 200; i++) {
            book.sample(at(10, 8, 0, 0) + i * 60L, 60, 100, 100 + i, 100 + i, 100 + i, 1);
        }
        assertEquals(20, book.series(BarPeriod.M1).size());
        assertEquals(1, book.series(BarPeriod.D1).size());
        assertEquals(100L, book.series(BarPeriod.D1).get(0).open);
        assertEquals(299L, book.series(BarPeriod.D1).get(0).close);
    }
}
