package cn.autoforged.module_shop_stock.market.bars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BarPeriodTest {
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    private static long at(ZoneId zone, int y, int mo, int d, int h, int mi, int s) {
        return LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toEpochSecond();
    }

    @Test
    void sourcePeriodUsesConfiguredWidth() {
        long t = at(CN, 2026, 11, 10, 12, 34, 56);
        assertEquals(at(CN, 2026, 11, 10, 12, 34, 0), BarPeriod.M1.start(t, 60, CN));
        assertEquals(at(CN, 2026, 11, 10, 12, 34, 50), BarPeriod.M1.start(t, 10, CN));
    }

    @Test
    void minutePeriodsAlignToLocalClock() {
        long t = at(CN, 2026, 11, 10, 12, 34, 56);
        assertEquals(at(CN, 2026, 11, 10, 12, 30, 0), BarPeriod.M5.start(t, 60, CN));
        assertEquals(at(CN, 2026, 11, 10, 12, 30, 0), BarPeriod.M15.start(t, 60, CN));
        assertEquals(at(CN, 2026, 11, 10, 12, 0, 0), BarPeriod.H1.start(t, 60, CN));
    }

    @Test
    void hourAlignsToLocalHourInHalfHourZone() {
        ZoneId india = ZoneId.of("Asia/Kolkata");
        long t = at(india, 2026, 11, 10, 12, 34, 56);
        assertEquals(at(india, 2026, 11, 10, 12, 0, 0), BarPeriod.H1.start(t, 60, india));
    }

    @Test
    void dayStartsAtLocalMidnight() {
        long t = at(CN, 2026, 11, 10, 23, 59, 59);
        assertEquals(at(CN, 2026, 11, 10, 0, 0, 0), BarPeriod.D1.start(t, 60, CN));
        assertEquals(at(CN, 2026, 11, 11, 0, 0, 0), BarPeriod.D1.start(t + 1, 60, CN));
    }

    @Test
    void weekStartsOnIsoMonday() {
        long sunday = at(CN, 2026, 11, 15, 23, 0, 0);
        long monday = at(CN, 2026, 11, 16, 0, 0, 0);
        long start = BarPeriod.W1.start(sunday, 60, CN);
        assertEquals(DayOfWeek.MONDAY, Instant.ofEpochSecond(start).atZone(CN).getDayOfWeek());
        assertEquals(at(CN, 2026, 11, 9, 0, 0, 0), start);
        assertEquals(monday, BarPeriod.W1.start(monday, 60, CN));
    }

    @Test
    void parseDefaultsToOneMinute() {
        assertSame(BarPeriod.M1, BarPeriod.parse(null));
        assertSame(BarPeriod.M1, BarPeriod.parse(""));
        assertSame(BarPeriod.M1, BarPeriod.parse("garbage"));
        assertSame(BarPeriod.H1, BarPeriod.parse("60m"));
        assertSame(BarPeriod.H1, BarPeriod.parse("1h"));
        assertSame(BarPeriod.W1, BarPeriod.parse("1W"));
    }
}
