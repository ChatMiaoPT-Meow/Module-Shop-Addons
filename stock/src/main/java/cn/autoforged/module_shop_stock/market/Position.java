package cn.autoforged.module_shop_stock.market;

/**
 * 玩家持仓：同时支持多头持仓（quantity）与融券空头持仓（shortQuantity）。
 *
 * <p>空单采用 100% 保证金机制（shortMargin）：
 * 卖空开仓时冻结等额保证金并记录开仓卖出总额（shortProceeds）；
 * 平空（买回）时，返还按比例释放的保证金 + (开仓卖出额 - 平仓买回成本)。
 */
public final class Position {
    /** 多仓总股数。 */
    public long quantity;
    /** 被限价平多卖单锁定的多仓股数。 */
    public long locked;
    /** 多仓累计买入成本（含手续费）。 */
    public long cost;

    /** 空仓总股数（融券卖空未平仓量）。 */
    public long shortQuantity;
    /** 被限价平空买单锁定的空仓股数。 */
    public long shortLocked;
    /** 空仓开仓卖出总价值（用于计算开仓均价与空单盈亏）。 */
    public long shortProceeds;
    /** 空仓质押保证金总额。 */
    public long shortMargin;

    public long available() {
        return Math.max(0L, this.quantity - this.locked);
    }

    public long averageCost() {
        return this.quantity <= 0L ? 0L : this.cost / this.quantity;
    }

    public long availableShort() {
        return Math.max(0L, this.shortQuantity - this.shortLocked);
    }

    public long averageShortPrice() {
        return this.shortQuantity <= 0L ? 0L : this.shortProceeds / this.shortQuantity;
    }

    public boolean isEmpty() {
        return this.quantity <= 0L && this.shortQuantity <= 0L;
    }
}
