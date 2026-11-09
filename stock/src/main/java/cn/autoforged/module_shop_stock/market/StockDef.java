package cn.autoforged.module_shop_stock.market;

import javax.annotation.Nullable;

public final class StockDef {
    public final String symbol;
    public String name;
    @Nullable
    public String nameKey;
    public String sector;
    public long basePrice;
    public double volatility;
    public long depth;
    public boolean enabled;
    public boolean halted;

    public StockDef(String symbol, String name, @Nullable String nameKey, String sector, long basePrice,
            double volatility, long depth) {
        this.symbol = symbol;
        this.name = name == null ? "" : name;
        this.nameKey = nameKey;
        this.sector = sector == null ? "" : sector;
        this.basePrice = Math.max(1L, basePrice);
        this.volatility = volatility;
        this.depth = Math.max(1L, depth);
        this.enabled = true;
        this.halted = false;
    }

    public String logName() {
        return this.name.isEmpty() ? this.symbol : this.name + "(" + this.symbol + ")";
    }

    public StockDef copy() {
        StockDef copy = new StockDef(this.symbol, this.name, this.nameKey, this.sector, this.basePrice,
                this.volatility, this.depth);
        copy.enabled = this.enabled;
        copy.halted = this.halted;
        return copy;
    }
}
