package cn.autoforged.module_shop_stock.market.model;

/**
 * 波动率语义迁移（2.3）：{@code StockDef.volatility} 由"旧 tick 级乘数"改为"目标小时对数波动率 σ_hour"。
 * 迁移只在存档 format 非 {@code zip-2.3} 时做一次，见 {@code StockMarketSavedData}。
 */
public final class VolatilityMigration {
    /** 旧值 → 新值的缩放系数。 */
    public static final double FACTOR = 0.50D;
    public static final double MIN = 0.006D;
    public static final double MAX = 0.050D;

    private VolatilityMigration() {
    }

    public static double migrate(double oldVol) {
        if (Double.isNaN(oldVol) || oldVol <= 0.0D) {
            return 0.020D;
        }
        return Math.max(MIN, Math.min(MAX, oldVol * FACTOR));
    }

    /** 保留四位小数，用于内置股票常量。 */
    public static double round4(double v) {
        return Math.round(v * 10_000.0D) / 10_000.0D;
    }
}
