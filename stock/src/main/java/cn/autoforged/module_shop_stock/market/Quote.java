package cn.autoforged.module_shop_stock.market;

import cn.autoforged.module_shop_stock.market.bars.BarAggregator;
import cn.autoforged.module_shop_stock.market.bars.BarPeriod;
import cn.autoforged.module_shop_stock.market.bars.BarSeries;

/**
 * 一只股票的运行时行情：价格、趋势动量、波动率聚集度、五档买卖盘口（订单簿）与 K 线柱。
 */
public final class Quote {
    public static final int BOOK_LEVELS = 5;

    public long price;
    public long prevPrice;
    public long prevClose;
    public long dayOpen;
    public long dayHigh;
    public long dayLow;
    public long volume;
    public double newsBoost;
    public double trend;
    public double volCluster;

    /** 五档买盘（买1..买5 价格与挂单量，包含真实玩家限价单与做市商深度）。 */
    public final long[] bidPrices = new long[BOOK_LEVELS];
    public final long[] bidVolumes = new long[BOOK_LEVELS];
    /** 五档卖盘（卖1..卖5 价格与挂单量，包含真实玩家限价单与做市商深度）。 */
    public final long[] askPrices = new long[BOOK_LEVELS];
    public final long[] askVolumes = new long[BOOK_LEVELS];

    /** 六个周期的 K 线容器；{@link #bars} 是其中的 1m 源序列（旧代码按 List&lt;Bar&gt; 使用即可）。 */
    public final BarAggregator book = new BarAggregator();
    public final BarSeries bars = this.book.series(BarPeriod.M1);

    public Quote(long price) {
        this.price = Math.max(1L, price);
        this.prevPrice = this.price;
        this.prevClose = this.price;
        this.dayOpen = this.price;
        this.dayHigh = this.price;
        this.dayLow = this.price;
    }

    public void moveTo(long next) {
        long value = Math.max(1L, next);
        this.prevPrice = this.price;
        this.price = value;
        if (value > this.dayHigh) {
            this.dayHigh = value;
        }
        if (value < this.dayLow) {
            this.dayLow = value;
        }
    }

    public void trade(long price, long quantity) {
        long qty = Math.max(0L, quantity);
        this.volume += qty;
        this.book.trade(price, qty);
    }

    public void sample(long nowSecond, long barSeconds, int barLimit) {
        sampleWithWick(nowSecond, barSeconds, barLimit, this.price, this.price, 0L);
    }

    public void sampleWithWick(long nowSecond, long barSeconds, int barLimit, long wickHigh, long wickLow,
            long simVolume) {
        long hi = Math.max(this.price, wickHigh);
        long lo = Math.max(1L, Math.min(this.price, wickLow));
        if (hi > this.dayHigh) {
            this.dayHigh = hi;
        }
        if (lo < this.dayLow) {
            this.dayLow = lo;
        }
        this.volume += Math.max(0L, simVolume);
        // barLimit 参数保留以兼容旧调用；各周期上限现由 BarAggregator 的环形缓冲持有（配置同步见 StockMarketConfig.syncBarLimits）
        this.book.sample(nowSecond, (int) Math.max(1L, barSeconds), this.prevPrice > 0L ? this.prevPrice : this.price,
                this.price, hi, lo, simVolume);
    }

    public void rollDay() {
        this.prevClose = this.price;
        this.dayOpen = this.price;
        this.dayHigh = this.price;
        this.dayLow = this.price;
        this.volume = 0L;
    }

    public long changeBps() {
        if (this.prevClose <= 0L) {
            return 0L;
        }
        return Math.round((this.price - this.prevClose) * 10_000.0D / this.prevClose);
    }
}
