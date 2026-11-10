package cn.autoforged.module_shop_stock.market.bars;

import cn.autoforged.module_shop_stock.market.Bar;

/**
 * 一个周期的紧凑打包格式（纯 Java，写 NBT 时直接对应 long/int/byte 数组）：
 * <ul>
 *   <li>{@code t0}：首根时间（epoch 秒）；{@code dt[i]}：与上一根的时间差（秒，首根为 0）；</li>
 *   <li>{@code ohlc}：长度 4n，依次为 n 个开、n 个高、n 个低、n 个收（价格上限 1e8，落在 int 内）；</li>
 *   <li>{@code vol}：n 个成交量；{@code flags}：n 个字节（bit0 = seeded），全无种子时为 null。</li>
 * </ul>
 * 每根约 28 字节（写盘还会被 gzip），24 股 × 约 4000 根 ≈ 2.7MB 未压缩。
 */
public final class BarCodec {
    private BarCodec() {
    }

    public static final class Packed {
        public long t0;
        public int[] dt = new int[0];
        public int[] ohlc = new int[0];
        public long[] vol = new long[0];
        public byte[] flags;

        public int count() {
            return this.dt.length;
        }
    }

    public static Packed pack(BarSeries series) {
        int n = series.size();
        Packed out = new Packed();
        out.dt = new int[n];
        out.ohlc = new int[n * 4];
        out.vol = new long[n];
        byte[] flags = new byte[n];
        boolean anySeeded = false;
        long prev = 0L;
        for (int i = 0; i < n; i++) {
            Bar bar = series.get(i);
            if (i == 0) {
                out.t0 = bar.time;
                prev = bar.time;
            }
            out.dt[i] = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, bar.time - prev));
            prev = bar.time;
            out.ohlc[i] = clampInt(bar.open);
            out.ohlc[n + i] = clampInt(bar.high);
            out.ohlc[2 * n + i] = clampInt(bar.low);
            out.ohlc[3 * n + i] = clampInt(bar.close);
            out.vol[i] = bar.volume;
            if (bar.seeded) {
                flags[i] = 1;
                anySeeded = true;
            }
        }
        out.flags = anySeeded ? flags : null;
        return out;
    }

    /** 追加到 {@code target}（调用方负责先清空）。数组长度不一致的残缺数据按最短对齐，不抛异常。 */
    public static void unpack(Packed packed, BarSeries target) {
        int n = Math.min(packed.dt.length, Math.min(packed.vol.length, packed.ohlc.length / 4));
        if (n > target.limit()) {
            target.setLimit(n);
        }
        long time = packed.t0;
        for (int i = 0; i < n; i++) {
            time += packed.dt[i];
            Bar bar = new Bar(time, packed.ohlc[i], packed.ohlc[n + i], packed.ohlc[2 * n + i],
                    packed.ohlc[3 * n + i], packed.vol[i]);
            bar.seeded = packed.flags != null && i < packed.flags.length && (packed.flags[i] & 1) != 0;
            target.add(bar);
        }
    }

    private static int clampInt(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }
}
