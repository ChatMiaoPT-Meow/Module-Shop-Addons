package cn.autoforged.module_shop_stock.market.bars;

import cn.autoforged.module_shop_stock.market.Bar;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 日线/周线「风格化种子」历史（纯 Java）。
 *
 * <p>这是占位实现：升档存档或新档的 1m 只覆盖不到一天，日/周没有历史，于是补一段有界、温和的随机游走，
 * 柱上打 {@link Bar#seeded}，客户端据此淡显并标注「模拟」，不得当作真实行情。
 * 价格过程换成新模型后，只需改 {@link #generateDailyPath} 这一处（或让它调用新模型的日尺度采样）。
 */
public final class BarHistorySeeder {
    /** 日线补到的根数。 */
    public static final int DAILY_BARS = 60;
    /** 周线由更长的日路径聚合得到（26 周），保证日/周两张图同源。 */
    public static final int WEEKLY_DAYS = 182;
    /** 日振幅（开到高/低/收）夹紧到 ±12%。 */
    public static final double DAY_RANGE_CAP = 0.12D;

    private BarHistorySeeder() {
    }

    /** 个股日波动 σ：由 volatility 按日尺度缩放，夹在 0.8%～4%。 */
    public static double dailySigma(double volatility) {
        return Math.max(0.008D, Math.min(0.04D, volatility * 0.30D));
    }

    /**
     * 给 {@code book} 补日/周种子历史，只在日线不足 {@link #DAILY_BARS} 根时才补，且只补到现有最老日柱之前
     * （没有日柱时补到「昨天」）。返回新增的日线根数。
     *
     * @param fallbackPrice 日线完全为空时，路径末端（昨天收盘）对齐的价格
     * @param basePrice 路径围绕的均值（个股基准价）
     * @param typicalDailyVolume 典型日成交量，用来生成种子柱的量
     */
    public static int seedCoarse(BarAggregator book, long nowSecond, long fallbackPrice, long basePrice,
            double volatility, long typicalDailyVolume, long seed) {
        BarSeries daily = book.series(BarPeriod.D1);
        BarSeries weekly = book.series(BarPeriod.W1);
        if (daily.size() >= DAILY_BARS) {
            return 0;
        }
        ZoneId zone = book.zone();
        LocalDate endDay;
        long anchor;
        if (daily.isEmpty()) {
            endDay = java.time.Instant.ofEpochSecond(nowSecond).atZone(zone).toLocalDate().minusDays(1L);
            anchor = fallbackPrice;
        } else {
            Bar oldest = daily.get(0);
            endDay = java.time.Instant.ofEpochSecond(oldest.time).atZone(zone).toLocalDate().minusDays(1L);
            anchor = oldest.open;
        }
        List<Bar> days = generateDailyPath(endDay, zone, Math.max(1L, anchor), Math.max(1L, basePrice), volatility,
                typicalDailyVolume, seed, WEEKLY_DAYS);

        int needDaily = DAILY_BARS - daily.size();
        List<Bar> dailyPart = new ArrayList<>(days.subList(Math.max(0, days.size() - needDaily), days.size()));
        daily.prepend(dailyPart);

        // 周线：把现有最老周柱所在周及之后的种子日剔除（那一周已有真实/回填数据），其余按 ISO 周聚合
        long firstRealWeek = weekly.isEmpty() ? Long.MAX_VALUE : weekly.get(0).time;
        List<Bar> weeks = new ArrayList<>();
        Bar current = null;
        for (Bar day : days) {
            long ws = BarPeriod.W1.start(day.time, 60, zone);
            if (ws >= firstRealWeek) {
                continue;
            }
            if (current == null || ws > current.time) {
                if (current != null) {
                    weeks.add(current);
                }
                current = new Bar(ws, day.open, day.high, day.low, day.close, day.volume);
                current.seeded = true;
            } else {
                current.high = Math.max(current.high, day.high);
                current.low = Math.min(current.low, day.low);
                current.close = day.close;
                current.volume += day.volume;
            }
        }
        if (current != null) {
            weeks.add(current);
        }
        if (weekly.size() < BarPeriod.W1.maxLimit()) {
            weekly.prepend(weeks);
        }
        return dailyPart.size();
    }

    /**
     * 生成以 {@code endDay} 当天收盘 = {@code anchor} 为终点、向前 {@code count} 天的日线（按时间升序）。
     * 反向有界游走：对数价以 OU 回归向 {@code basePrice} 靠拢（OU 时间可逆，反向走回归项符号不变），
     * 单日涨跌夹紧 ±{@value #DAY_RANGE_CAP}，整体不离开 base 的 0.5～2 倍。
     */
    static List<Bar> generateDailyPath(LocalDate endDay, ZoneId zone, long anchor, long basePrice, double volatility,
            long typicalDailyVolume, long seed, int count) {
        Random rng = new Random(seed ^ 0x9E3779B97F4A7C15L);
        double sigma = dailySigma(volatility);
        double logBase = Math.log(Math.max(1.0D, basePrice));
        double lo = logBase + Math.log(0.5D);
        double hi = logBase + Math.log(2.0D);
        double maxStep = Math.log(1.0D + DAY_RANGE_CAP);
        double kappa = 0.05D;

        double[] x = new double[count + 1];
        x[count] = Math.log(Math.max(1.0D, anchor));
        for (int k = count; k >= 1; k--) {
            double step = kappa * (logBase - x[k]) + sigma * rng.nextGaussian();
            step = Math.max(-maxStep, Math.min(maxStep, step));
            double open = x[k] + step;
            // 锚点附近允许超出 [lo, hi]，其余夹回区间
            x[k - 1] = x[k] >= lo && x[k] <= hi ? Math.max(lo, Math.min(hi, open)) : open;
        }

        List<Bar> out = new ArrayList<>(count);
        for (int k = 1; k <= count; k++) {
            double o = Math.exp(x[k - 1]);
            double c = Math.exp(x[k]);
            double top = Math.max(o, c);
            double bottom = Math.min(o, c);
            double up = top * sigma * (0.15D + Math.abs(rng.nextGaussian()) * 0.55D);
            double dn = bottom * sigma * (0.15D + Math.abs(rng.nextGaussian()) * 0.55D);
            double high = Math.min(o * (1.0D + DAY_RANGE_CAP), top + up);
            double low = Math.max(o * (1.0D - DAY_RANGE_CAP), bottom - dn);
            double move = Math.abs(x[k] - x[k - 1]);
            long volume = Math.max(1L, Math.round(typicalDailyVolume * (0.6D + rng.nextDouble() * 0.8D)
                    * (1.0D + 6.0D * move)));
            LocalDate day = endDay.minusDays(count - k);
            Bar bar = new Bar(day.atStartOfDay(zone).toEpochSecond(), Math.round(o), Math.round(high),
                    Math.round(low), Math.round(c), volume);
            bar.seeded = true;
            out.add(bar);
        }
        // 终点收盘强制等于锚点（取整误差归零，保证与真实柱首尾相接）
        Bar last = out.get(out.size() - 1);
        last.close = Math.max(1L, anchor);
        last.high = Math.max(last.high, last.close);
        last.low = Math.min(last.low, last.close);
        return out;
    }
}
