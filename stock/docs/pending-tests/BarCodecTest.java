package cn.autoforged.module_shop_stock.market.bars;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.autoforged.module_shop_stock.market.Bar;
import org.junit.jupiter.api.Test;

class BarCodecTest {
    @Test
    void roundTripsBarsAndSeededFlags() {
        BarSeries src = new BarSeries(50);
        for (int i = 0; i < 10; i++) {
            Bar bar = new Bar(1_000_000L + i * 60L, 100 + i, 120 + i, 90 + i, 105 + i, 1_000_000_000_000L + i);
            bar.seeded = i % 3 == 0;
            src.add(bar);
        }
        BarSeries dst = new BarSeries(50);
        BarCodec.unpack(BarCodec.pack(src), dst);
        assertEquals(src.size(), dst.size());
        for (int i = 0; i < src.size(); i++) {
            Bar a = src.get(i);
            Bar b = dst.get(i);
            assertEquals(a.time, b.time);
            assertEquals(a.open, b.open);
            assertEquals(a.high, b.high);
            assertEquals(a.low, b.low);
            assertEquals(a.close, b.close);
            assertEquals(a.volume, b.volume);
            assertEquals(a.seeded, b.seeded);
        }
    }

    @Test
    void omitsFlagsWhenNothingSeeded() {
        BarSeries src = new BarSeries(5);
        src.add(new Bar(10L, 100L));
        assertNull(BarCodec.pack(src).flags);
    }

    @Test
    void emptySeriesRoundTrips() {
        BarSeries dst = new BarSeries(5);
        BarCodec.unpack(BarCodec.pack(new BarSeries(5)), dst);
        assertTrue(dst.isEmpty());
    }

    @Test
    void truncatedArraysDoNotThrow() {
        BarSeries src = new BarSeries(10);
        for (int i = 0; i < 4; i++) {
            src.add(new Bar(100L + i, 10, 12, 9, 11, 5));
        }
        BarCodec.Packed packed = BarCodec.pack(src);
        packed.vol = new long[2];
        packed.flags = new byte[1];
        BarSeries dst = new BarSeries(10);
        BarCodec.unpack(packed, dst);
        assertEquals(2, dst.size());
        assertFalse(dst.get(1).seeded);
    }
}
