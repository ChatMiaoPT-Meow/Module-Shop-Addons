package cn.autoforged.module_shop_stock.market.bars;

import cn.autoforged.module_shop_stock.market.Bar;
import java.time.ZoneId;

/**
 * 一只股票的多周期 K 线容器与 O(1) 聚合器（纯 Java）。
 *
 * <p>只有一份价格过程：每次采样/成交的增量（价、影线、量）在更新 1m 末根的同时，落到其余 5 个周期各自的末根上，
 * 效果等价于「细周期柱 fold 进粗周期最后一根」，且粗周期末根始终包含未收盘的 1m 柱；每次最多 6 次 O(1) 更新，
 * 不会回扫历史。粗周期各自是持久化的环形缓冲，1m 滑出窗口后日/周仍在。
 */
public final class BarAggregator {
    private final BarSeries[] series;
    private final ZoneId zone;

    public BarAggregator() {
        this(ZoneId.systemDefault());
    }

    public BarAggregator(ZoneId zone) {
        this.zone = zone;
        BarPeriod[] periods = BarPeriod.all();
        this.series = new BarSeries[periods.length];
        for (BarPeriod period : periods) {
            // 初始上限放到最大，避免读档时被截断；开服后由配置同步收紧。
            this.series[period.ordinal()] = new BarSeries(period.maxLimit());
        }
    }

    public ZoneId zone() {
        return this.zone;
    }

    public BarSeries series(BarPeriod period) {
        return this.series[period.ordinal()];
    }

    public void setLimit(BarPeriod period, int limit) {
        series(period).setLimit(Math.max(BarPeriod.MIN_LIMIT, Math.min(period.maxLimit(), limit)));
    }

    /**
     * 一次行情采样。{@code firstOpen} 仅在某周期还没有任何柱时作开盘价；之后新柱开盘价 = 上一根收盘。
     */
    public void sample(long nowSecond, int sourceSeconds, long firstOpen, long price, long wickHigh, long wickLow,
            long simVolume) {
        long hi = Math.max(price, wickHigh);
        long lo = Math.max(1L, Math.min(price, wickLow));
        for (BarPeriod period : BarPeriod.all()) {
            BarSeries list = series(period);
            long start = period.start(nowSecond, sourceSeconds, this.zone);
            Bar last = list.last();
            if (last == null) {
                Bar first = new Bar(start, firstOpen > 0L ? firstOpen : price);
                first.touchWick(hi, lo, price, simVolume);
                list.add(first);
            } else if (start > last.time) {
                Bar next = new Bar(start, last.close);
                next.touchWick(hi, lo, price, simVolume);
                list.add(next);
            } else {
                last.touchWick(hi, lo, price, simVolume);
            }
        }
    }

    /** 玩家成交：价与量落到每个周期当前末根。 */
    public void trade(long price, long quantity) {
        for (BarPeriod period : BarPeriod.all()) {
            Bar last = series(period).last();
            if (last != null) {
                last.trade(price, quantity);
            }
        }
    }

    /**
     * 升档/新档：把 1m 柱聚合回填到「还是空的」粗周期。覆盖范围就是 1m 已有的范围；
     * 一根粗周期柱只有当它的全部 1m 成员都是种子柱时才标 {@code seeded}。
     */
    public void backfillFromSource() {
        BarSeries source = series(BarPeriod.M1);
        if (source.isEmpty()) {
            return;
        }
        for (BarPeriod period : BarPeriod.all()) {
            if (period == BarPeriod.M1) {
                continue;
            }
            BarSeries target = series(period);
            if (!target.isEmpty()) {
                continue;
            }
            Bar current = null;
            boolean allSeeded = true;
            for (Bar bar : source) {
                long start = period.start(bar.time, 60, this.zone);
                if (current == null || start > current.time) {
                    if (current != null) {
                        current.seeded = allSeeded;
                        target.add(current);
                    }
                    current = new Bar(start, bar.open, bar.high, bar.low, bar.close, bar.volume);
                    allSeeded = bar.seeded;
                } else {
                    current.high = Math.max(current.high, bar.high);
                    current.low = Math.min(current.low, bar.low);
                    current.close = bar.close;
                    current.volume += bar.volume;
                    allSeeded &= bar.seeded;
                }
            }
            if (current != null) {
                current.seeded = allSeeded;
                target.add(current);
            }
        }
    }
}
