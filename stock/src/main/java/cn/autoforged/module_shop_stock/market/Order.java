package cn.autoforged.module_shop_stock.market;

import java.util.UUID;

/**
 * 一笔委托（支持买多 BUY、平多 SELL、卖空 SHORT、平空 COVER，以及市价单与限价单）。
 */
public final class Order {
    public enum State { OPEN, FILLED, CANCELLED, EXPIRED }
    public enum Action { BUY, SELL, SHORT, COVER }

    public long id;
    public UUID owner;
    public String symbol;
    public boolean buy;
    /** 详细方向：买多(BUY)、平多(SELL)、卖空(SHORT)、平空(COVER)。 */
    public Action action = Action.BUY;
    /** 限价；市价单为提交时看到的价格。 */
    public long price;
    public long quantity;
    public long filled;
    /** 剩余冻结：BUY/SHORT/COVER 为冻结金额（保证金），SELL 为冻结多仓股数。 */
    public long escrow;
    /** 已成交金额（不含手续费），用于算成交均价。 */
    public long traded;
    public long createdAt;
    public long expireAt;
    public State state = State.OPEN;
    public boolean market;

    public long remaining() {
        return Math.max(0L, this.quantity - this.filled);
    }

    public long averagePrice() {
        return this.filled <= 0L ? this.price : this.traded / this.filled;
    }

    /** 在订单簿中属于买方挂单（买多 BUY 或 买回平空 COVER）还是卖方挂单（平多 SELL 或 融券卖空 SHORT）。 */
    public boolean isBidOrder() {
        return this.action == Action.BUY || this.action == Action.COVER;
    }
}
