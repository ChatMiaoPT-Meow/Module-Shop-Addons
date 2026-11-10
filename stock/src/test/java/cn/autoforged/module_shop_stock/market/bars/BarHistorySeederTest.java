package cn.autoforged.module_shop_stock.market.bars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.autoforged.module_shop_stock.market.Bar;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BarHistorySeederTest {
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final long NOW = LocalDateTime.of(2026, 11, 10, 15, 0).atZone(CN).toEpochSecond();

    @Test
    void emptyBookGetsSixtySeededDailyBarsEndingYesterdayAtAnchor() {
        BarAggregator book = new BarAggregator(CN);
        int added = BarHistorySeeder.seedCoarse(book, NOW, 1000L, 1000L, 0.04D, 50_000L, 42L);
        BarSeries daily = book.series(BarPeriod.D1);
        assertEquals(BarHistorySeeder.DAILY_BARS, added);
        assertEquals(BarHistorySeeder.DAILY_BARS, daily.size());
        Bar last = daily.last();
        assertEquals(LocalDateTime.of(2026, 11, 9, 0, 0).atZone(CN).toEpochSecond(), last.time);
        assertEquals(1000L, last.close);
        for (Bar bar : daily) {
            assertTrue(bar.seeded);
            assertTrue(bar.high >= Math.max(bar.open, bar.close));
            assertTrue(bar.low <= Math.min(bar.open, bar.close) && bar.low >= 1L);
            assertTrue(bar.high <= bar.open * 1.1201D + 1, "daily range capped");
            assertTrue(bar.low >= bar.open * 0.8799D - 1, "daily range capped");
        }
    }

    @Test
    void dailyBarsAreContiguousAndOpenAtPreviousClose() {
        BarAggregator book = new BarAggregator(CN);
        BarHistorySeeder.seedCoarse(book, NOW, 800L, 900L, 0.05D, 10_000L, 7L);
        BarSeries daily = book.series(BarPeriod.D1);
        for (int i = 1; i < daily.size(); i++) {
            assertEquals(86_400L, daily.get(i).time - daily.get(i - 1).time);
            assertTrue(Math.abs(daily.get(i).open - daily.get(i - 1).close) <= 1L);
        }
    }

    @Test
    void pathStaysNearBasePrice() {
        BarAggregator book = new BarAggregator(CN);
        BarHistorySeeder.seedCoarse(book, NOW, 1000L, 1000L, 0.06D, 10_000L, 9L);
        for (Bar bar : book.series(BarPeriod.W1)) {
            assertTrue(bar.high < 1000L * 2.6D && bar.low > 1000L * 0.35D);
        }
    }

    @Test
    void weeklyBarsStartOnMondayAndSortAscending() {
        BarAggregator book = new BarAggregator(CN);
        BarHistorySeeder.seedCoarse(book, NOW, 500L, 500L, 0.04D, 10_000L, 3L);
        BarSeries weekly = book.series(BarPeriod.W1);
        assertTrue(weekly.size() >= 20);
        for (int i = 0; i < weekly.size(); i++) {
            assertEquals(DayOfWeek.MONDAY, Instant.ofEpochSecond(weekly.get(i).time).atZone(CN).getDayOfWeek());
            assertTrue(weekly.get(i).seeded);
            if (i > 0) {
                assertTrue(weekly.get(i).time > weekly.get(i - 1).time);
            }
        }
    }

    @Test
    void deterministicPerSeedAndIdempotent() {
        BarAggregator a = new BarAggregator(CN);
        BarAggregator b = new BarAggregator(CN);
        BarHistorySeeder.seedCoarse(a, NOW, 700L, 700L, 0.04D, 1_000L, 5L);
        BarHistorySeeder.seedCoarse(b, NOW, 700L, 700L, 0.04D, 1_000L, 5L);
        for (int i = 0; i < a.series(BarPeriod.D1).size(); i++) {
            assertEquals(a.series(BarPeriod.D1).get(i).close, b.series(BarPeriod.D1).get(i).close);
        }
        assertEquals(0, BarHistorySeeder.seedCoarse(a, NOW, 700L, 700L, 0.04D, 1_000L, 5L));
    }

    @Test
    void prependsBeforeExistingRealDailyBars() {
        BarAggregator book = new BarAggregator(CN);
        long today = LocalDateTime.of(2026, 11, 10, 0, 0).atZone(CN).toEpochSecond();
        book.series(BarPeriod.D1).add(new Bar(today, 1000L, 1010L, 990L, 1005L, 100L));
        int added = BarHistorySeeder.seedCoarse(book, NOW, 1005L, 1000L, 0.04D, 1_000L, 5L);
        BarSeries daily = book.series(BarPeriod.D1);
        assertEquals(BarHistorySeeder.DAILY_BARS - 1, added);
        assertEquals(today, daily.last().time);
        assertEquals(today - 86_400L, daily.get(daily.size() - 2).time);
        assertTrue(Math.abs(daily.get(daily.size() - 2).close - 1000L) <= 1L);
    }
}
