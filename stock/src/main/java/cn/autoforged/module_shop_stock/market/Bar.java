package cn.autoforged.module_shop_stock.market;

public final class Bar {
    public long time;
    public long open;
    public long high;
    public long low;
    public long close;
    public long volume;
    /** 风格化种子柱（非真实撮合行情）。第一次被真实行情/成交触碰后清除。 */
    public boolean seeded;

    public Bar(long time, long price) {
        this.time = time;
        this.open = price;
        this.high = price;
        this.low = price;
        this.close = price;
    }

    public Bar(long time, long open, long high, long low, long close, long volume) {
        this.time = time;
        this.open = Math.max(1L, open);
        this.high = Math.max(this.open, high);
        this.low = Math.max(1L, Math.min(this.open, low));
        this.close = Math.max(1L, close);
        this.volume = Math.max(0L, volume);
    }

    public void touch(long price) {
        this.seeded = false;
        this.close = Math.max(1L, price);
        if (this.close > this.high) {
            this.high = this.close;
        }
        if (this.close < this.low) {
            this.low = this.close;
        }
    }

    public void touchWick(long wickHigh, long wickLow, long closePrice, long simVolume) {
        touch(closePrice);
        if (wickHigh > this.high) {
            this.high = wickHigh;
        }
        if (wickLow > 0L && wickLow < this.low) {
            this.low = wickLow;
        }
        this.volume += Math.max(0L, simVolume);
    }

    public void trade(long price, long quantity) {
        touch(price);
        this.volume += Math.max(0L, quantity);
    }
}
