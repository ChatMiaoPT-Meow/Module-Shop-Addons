package cn.autoforged.module_shop_stock;

import cn.autoforged.module_shop_stock.market.Quote;
import cn.autoforged.module_shop_stock.market.bars.BarPeriod;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import net.neoforged.neoforge.common.ModConfigSpec;

/** 服务端配置（config/module_shop_stock-server.toml）。 */
public final class StockMarketConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue UPDATE_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue BAR_SECONDS;
    public static final ModConfigSpec.IntValue BAR_LIMIT;
    /** 各周期根数配置：barLimit1m / 5m / 15m / 60m / 1d / 1w。 */
    public static final Map<BarPeriod, ModConfigSpec.IntValue> BAR_LIMITS = new EnumMap<>(BarPeriod.class);
    public static final ModConfigSpec.IntValue MAX_TRADE_QUANTITY;
    public static final ModConfigSpec.LongValue MAX_POSITION;
    public static final ModConfigSpec.LongValue MIN_PRICE;
    public static final ModConfigSpec.LongValue MAX_PRICE;
    public static final ModConfigSpec.IntValue FEE_BPS;
    public static final ModConfigSpec.LongValue MIN_FEE;
    public static final ModConfigSpec.IntValue MEAN_REVERSION_BPS;
    public static final ModConfigSpec.DoubleValue VOLATILITY_MULTIPLIER;
    public static final ModConfigSpec.IntValue MAX_OPEN_ORDERS;
    public static final ModConfigSpec.IntValue ORDER_TTL_MINUTES;
    public static final ModConfigSpec.BooleanValue SESSION_ALWAYS_OPEN;
    public static final ModConfigSpec.IntValue SESSION_OPEN_MINUTE;
    public static final ModConfigSpec.IntValue SESSION_CLOSE_MINUTE;
    public static final ModConfigSpec.BooleanValue SHORT_ENABLED;
    public static final ModConfigSpec.IntValue SHORT_LIQUIDATION_BPS;

    public static final ModConfigSpec.BooleanValue AUTO_NEWS_ENABLED;
    public static final ModConfigSpec.IntValue AUTO_NEWS_MIN_TICKS;
    public static final ModConfigSpec.IntValue AUTO_NEWS_MAX_TICKS;
    public static final ModConfigSpec.IntValue AUTO_NEWS_MAX_IMPACT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("stock_market");

        ENABLED = b.comment("是否开放股市交易；关闭后仍可查看行情与持仓、可撤单")
                .define("enabled", true);
        UPDATE_INTERVAL_SECONDS = b.comment("行情演化与做市商报价刷新间隔（秒）")
                .defineInRange("updateIntervalSeconds", 5, 1, 3600);
        BAR_SECONDS = b.comment("K 线周期（秒）")
                .defineInRange("barSeconds", 60, 10, 3600);
        BAR_LIMIT = b.comment("【已弃用，请改用 barLimit1m】旧的单一 K 线根数。仅当它被改成非默认值(240)且 barLimit1m 仍是默认值时，才映射为 1m 的根数")
                .defineInRange("barLimit", 240, 20, 2880);
        for (BarPeriod period : BarPeriod.all()) {
            BAR_LIMITS.put(period, b.comment("多周期 K 线：" + period.id() + " 周期保留的根数（默认 "
                    + period.defaultLimit() + "，上限 " + period.maxLimit() + "）。所有周期均由 1m 源周期聚合并各自持久化")
                    .defineInRange("barLimit" + period.id(), period.defaultLimit(), BarPeriod.MIN_LIMIT,
                            period.maxLimit()));
        }
        MAX_TRADE_QUANTITY = b.comment("单次下单最大数量")
                .defineInRange("maxTradeQuantity", 1000, 1, 100_000);
        MAX_POSITION = b.comment("每只股票每位玩家的最大多仓或空仓数量")
                .defineInRange("maxPosition", 100_000L, 1L, 100_000_000L);
        MIN_PRICE = b.comment("股票价格下限").defineInRange("minPrice", 1L, 1L, 1_000_000L);
        MAX_PRICE = b.comment("股票价格上限").defineInRange("maxPrice", 1_000_000L, 1L, 100_000_000L);

        FEE_BPS = b.comment("手续费率（万分之一，30 = 0.3%），开平仓双边收取")
                .defineInRange("feeBps", 30, 0, 1000);
        MIN_FEE = b.comment("单笔最低手续费").defineInRange("minFee", 1L, 0L, 100_000L);
        MEAN_REVERSION_BPS = b.comment("价格向基准价回归的强度（万分之一 / 次）")
                .defineInRange("meanReversionBps", 45, 0, 5000);
        VOLATILITY_MULTIPLIER = b.comment("全局波动率放大倍率")
                .defineInRange("volatilityMultiplier", 1.85D, 0.2D, 5.0D);

        MAX_OPEN_ORDERS = b.comment("每位玩家同时挂单上限").defineInRange("maxOpenOrders", 12, 1, 64);
        ORDER_TTL_MINUTES = b.comment("限价单有效期（分钟），到期自动撤单退回保证金/股票")
                .defineInRange("orderTtlMinutes", 1440, 5, 43_200);

        SHORT_ENABLED = b.comment("是否允许融券卖空（做空机制，100% 保证金冻结，下跌盈利、上涨亏损）")
                .define("shortEnabled", true);
        SHORT_LIQUIDATION_BPS = b.comment("空单强平线（相对开仓均价涨幅万分比，8500 = 上涨 85% 触发强制平空保护）")
                .defineInRange("shortLiquidationBps", 8500, 2000, 9500);

        SESSION_ALWAYS_OPEN = b.comment("全天可交易；关闭后在下面的时段内才能下单")
                .define("sessionAlwaysOpen", true);
        SESSION_OPEN_MINUTE = b.comment("开盘时间（当天第几分钟，540 = 09:00）")
                .defineInRange("sessionOpenMinute", 540, 0, 1439);
        SESSION_CLOSE_MINUTE = b.comment("收盘时间（当天第几分钟，1380 = 23:00）")
                .defineInRange("sessionCloseMinute", 1380, 0, 1440);

        b.pop();
        b.push("auto_news");

        AUTO_NEWS_ENABLED = b.comment("是否启用游戏时间自动新闻播报系统")
                .define("autoNewsEnabled", true);
        AUTO_NEWS_MIN_TICKS = b.comment("两次自动新闻播报的最小游戏刻间隔（6000 ticks = 5 分钟）")
                .defineInRange("autoNewsMinTicks", 6000, 200, 72_000);
        AUTO_NEWS_MAX_TICKS = b.comment("两次自动新闻播报的最大游戏刻间隔（18000 ticks = 15 分钟）")
                .defineInRange("autoNewsMaxTicks", 18000, 400, 168_000);
        AUTO_NEWS_MAX_IMPACT = b.comment("自动新闻单次最大跳空幅度百分比（%）")
                .defineInRange("autoNewsMaxImpact", 22, 3, 30);

        b.pop();
        SPEC = b.build();
    }

    private StockMarketConfig() {
    }

    /** 某周期当前生效的根数；1m 兼容旧键 barLimit。 */
    public static int barLimit(BarPeriod period) {
        int value = BAR_LIMITS.get(period).get();
        if (period == BarPeriod.M1 && value == period.defaultLimit() && BAR_LIMIT.get() != 240) {
            return Math.max(BarPeriod.MIN_LIMIT, Math.min(period.maxLimit(), BAR_LIMIT.get()));
        }
        return value;
    }

    /** 把配置的各周期根数同步到每只股票的环形缓冲（调小立刻丢头）。每个行情 tick 调用一次，开销可忽略。 */
    public static void syncBarLimits(Collection<Quote> quotes) {
        for (Quote quote : quotes) {
            for (BarPeriod period : BarPeriod.all()) {
                int limit = barLimit(period);
                if (quote.book.series(period).limit() != limit) {
                    quote.book.setLimit(period, limit);
                }
            }
        }
    }

    public static long fee(long total) {
        int bps = FEE_BPS.get();
        if (bps <= 0 || total <= 0L) {
            return 0L;
        }
        long raw = (total / 10_000L) * bps + ((total % 10_000L) * bps + 9_999L) / 10_000L;
        long fee = Math.max(MIN_FEE.get(), raw);
        return Math.min(fee, Math.max(0L, total - 1L));
    }
}
