package cn.autoforged.module_shop_stock.market;

import java.util.ArrayList;
import java.util.List;

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

    public final List<Bar> bars = new ArrayList<>();

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
        Bar last = this.bars.isEmpty() ? null : this.bars.get(this.bars.size() - 1);
        if (last != null) {
            last.trade(price, qty);
        }
    }

    public void sample(long nowSecond, long barSeconds, int barLimit) {
        sampleWithWick(nowSecond, barSeconds, barLimit, this.price, this.price, 0L);
    }

    public void sampleWithWick(long nowSecond, long barSeconds, int barLimit, long wickHigh, long wickLow,
            long simVolume) {
        long width = Math.max(1L, barSeconds);
        long start = nowSecond - Math.floorMod(nowSecond, width);
        long hi = Math.max(this.price, wickHigh);
        long lo = Math.max(1L, Math.min(this.price, wickLow));
        if (hi > this.dayHigh) {
            this.dayHigh = hi;
        }
        if (lo < this.dayLow) {
            this.dayLow = lo;
        }
        this.volume += Math.max(0L, simVolume);
        if (this.bars.isEmpty()) {
            Bar first = new Bar(start, this.prevPrice > 0L ? this.prevPrice : this.price);
            first.touchWick(hi, lo, this.price, simVolume);
            this.bars.add(first);
            return;
        }
        Bar last = this.bars.get(this.bars.size() - 1);
        if (start > last.time) {
            Bar next = new Bar(start, last.close);
            next.touchWick(hi, lo, this.price, simVolume);
            this.bars.add(next);
            while (this.bars.size() > Math.max(2, barLimit)) {
                this.bars.remove(0);
            }
        } else {
            last.touchWick(hi, lo, this.price, simVolume);
        }
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
