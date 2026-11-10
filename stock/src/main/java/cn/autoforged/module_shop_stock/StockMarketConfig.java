package cn.autoforged.module_shop_stock;

import cn.autoforged.module_shop_stock.market.model.PriceParams;
import net.neoforged.neoforge.common.ModConfigSpec;

/** 服务端配置（config/module_shop_stock-server.toml）。 */
public final class StockMarketConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue UPDATE_INTERVAL_SECONDS;
    public static final ModConfigSpec.IntValue BAR_SECONDS;
    public static final ModConfigSpec.IntValue BAR_LIMIT;
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

    // ---- price_model：价格模型 v3 ----
    public static final ModConfigSpec.IntValue PRICE_MODEL_VERSION;
    public static final ModConfigSpec.DoubleValue PM_VOLATILITY_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue PM_MEAN_REVERSION_PER_HOUR;
    public static final ModConfigSpec.DoubleValue PM_KAPPA_FAIR_PER_HOUR;
    public static final ModConfigSpec.DoubleValue PM_KAPPA_TREND_PER_HOUR;
    public static final ModConfigSpec.DoubleValue PM_KAPPA_SIGMA_PER_HOUR;
    public static final ModConfigSpec.DoubleValue PM_XI_SIGMA;
    public static final ModConfigSpec.DoubleValue PM_FAIR_SHOCK_SHARE;
    public static final ModConfigSpec.DoubleValue PM_FAIR_FOLLOW;
    public static final ModConfigSpec.DoubleValue PM_SIGMA_TREND_RATIO;
    public static final ModConfigSpec.DoubleValue PM_THEME_PROB_PER_HOUR;
    public static final ModConfigSpec.DoubleValue PM_THEME_JUMP_RATIO;
    public static final ModConfigSpec.DoubleValue PM_MU_CAP;
    public static final ModConfigSpec.DoubleValue PM_TICK_MOVE_CAP;
    public static final ModConfigSpec.DoubleValue PM_DAY_LIMIT_PCT;
    public static final ModConfigSpec.DoubleValue PM_JUMP_PROB_PER_HOUR;
    public static final ModConfigSpec.DoubleValue PM_JUMP_SCALE;
    public static final ModConfigSpec.DoubleValue PM_IMPACT_COEF;
    public static final ModConfigSpec.DoubleValue PM_IMPACT_CAP;
    public static final ModConfigSpec.DoubleValue PM_IMPACT_PERMANENT_SHARE;
    public static final ModConfigSpec.DoubleValue PM_MIN_SPREAD;
    public static final ModConfigSpec.DoubleValue PM_SPREAD_COEF;
    public static final ModConfigSpec.DoubleValue PM_NEWS_TEMPLATE_SCALE;
    public static final ModConfigSpec.DoubleValue PM_AUTO_NEWS_FAIR_SHARE;
    public static final ModConfigSpec.DoubleValue PM_AUTO_NEWS_SPOT_SHARE;
    public static final ModConfigSpec.DoubleValue PM_SESSION_OPEN_BOOST;
    public static final ModConfigSpec.DoubleValue PM_SESSION_MID_DAMP;
    public static final ModConfigSpec.IntValue PM_AUTO_NEWS_MAX_IMPACT;
    public static final ModConfigSpec.BooleanValue PM_SESSION_PROFILE_ENABLED;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("stock_market");

        ENABLED = b.comment("是否开放股市交易；关闭后仍可查看行情与持仓、可撤单")
                .define("enabled", true);
        UPDATE_INTERVAL_SECONDS = b.comment("行情演化与做市商报价刷新间隔（秒）")
                .defineInRange("updateIntervalSeconds", 5, 1, 3600);
        BAR_SECONDS = b.comment("K 线周期（秒）")
                .defineInRange("barSeconds", 60, 10, 3600);
        BAR_LIMIT = b.comment("每只股票保留的 K 线根数")
                .defineInRange("barLimit", 240, 20, 600);
        MAX_TRADE_QUANTITY = b.comment("单次下单最大数量")
                .defineInRange("maxTradeQuantity", 1000, 1, 100_000);
        MAX_POSITION = b.comment("每只股票每位玩家的最大多仓或空仓数量")
                .defineInRange("maxPosition", 100_000L, 1L, 100_000_000L);
        MIN_PRICE = b.comment("股票价格下限").defineInRange("minPrice", 1L, 1L, 1_000_000L);
        MAX_PRICE = b.comment("股票价格上限").defineInRange("maxPrice", 1_000_000L, 1L, 100_000_000L);

        FEE_BPS = b.comment("手续费率（万分之一，30 = 0.3%），开平仓双边收取")
                .defineInRange("feeBps", 30, 0, 1000);
        MIN_FEE = b.comment("单笔最低手续费").defineInRange("minFee", 1L, 0L, 100_000L);
        MEAN_REVERSION_BPS = b.comment("【仅 priceModelVersion=2 的旧模型使用】价格向基准价回归的强度（万分之一 / 次）")
                .defineInRange("meanReversionBps", 45, 0, 5000);
        VOLATILITY_MULTIPLIER = b.comment("【仅 priceModelVersion=2 的旧模型使用】全局波动率放大倍率；新模型用 price_model.volatilityMultiplier")
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
        AUTO_NEWS_MIN_TICKS = b.comment("两次自动新闻播报的最小游戏刻间隔（9600 ticks = 8 分钟）")
                .defineInRange("autoNewsMinTicks", 9600, 200, 72_000);
        AUTO_NEWS_MAX_TICKS = b.comment("两次自动新闻播报的最大游戏刻间隔（24000 ticks = 20 分钟）")
                .defineInRange("autoNewsMaxTicks", 24000, 400, 168_000);
        AUTO_NEWS_MAX_IMPACT = b.comment("【仅 priceModelVersion=2 的旧模型使用】自动新闻单次最大跳空幅度百分比（%）；新模型用 price_model.autoNewsMaxImpact")
                .defineInRange("autoNewsMaxImpact", 22, 3, 30);

        b.pop();
        b.push("price_model");

        PRICE_MODEL_VERSION = b.comment("价格模型版本：3 = 对数 OU 公允价 + EWMA 波动率 + 日限（2.3 新模型）；2 = 旧随机游走（回滚用，个股波动自动按旧口径还原）")
                .defineInRange("priceModelVersion", 3, 2, 3);
        PM_VOLATILITY_MULTIPLIER = b.comment("v3 全局波动率倍率（个股 volatility 是小时对数波动率，这里再整体放大/缩小）")
                .defineInRange("volatilityMultiplier", 1.0D, 0.2D, 5.0D);
        PM_MEAN_REVERSION_PER_HOUR = b.comment("现价追公允价的强度（每小时，0.8 = 约 52 分钟缩小一半的偏差）")
                .defineInRange("meanReversionPerHour", 0.8D, 0.0D, 20.0D);
        PM_KAPPA_FAIR_PER_HOUR = b.comment("公允价拉回基准价的强度（每小时，0.15 = 约 4.6 小时缩小一半）")
                .defineInRange("kappaFairPerHour", 0.15D, 0.0D, 5.0D);
        PM_KAPPA_TREND_PER_HOUR = b.comment("趋势（漂移）自然消退速度（每小时）")
                .defineInRange("kappaTrendPerHour", 1.2D, 0.05D, 20.0D);
        PM_KAPPA_SIGMA_PER_HOUR = b.comment("瞬时波动率回归个股标定值的速度（每小时，躁动退烧速度）")
                .defineInRange("kappaSigmaPerHour", 2.5D, 0.1D, 50.0D);
        PM_XI_SIGMA = b.comment("瞬时波动率自身的噪声强度（越大波动聚集越明显）")
                .defineInRange("xiSigma", 0.45D, 0.0D, 2.0D);
        PM_FAIR_SHOCK_SHARE = b.comment("每个 tick 的随机冲击中永久计入公允价的比例；其余部分是会被均值回归吃掉的临时噪声")
                .defineInRange("fairShockShare", 0.8D, 0.0D, 1.0D);
        PM_FAIR_FOLLOW = b.comment("趋势传导到公允价的比例（其余部分直接推现价）")
                .defineInRange("fairFollow", 0.35D, 0.0D, 1.0D);
        PM_SIGMA_TREND_RATIO = b.comment("趋势噪声强度，相对个股小时波动的比例")
                .defineInRange("sigmaTrendRatio", 1.0D, 0.0D, 5.0D);
        PM_THEME_PROB_PER_HOUR = b.comment("每小时触发一次\"主题行情\"（给趋势一个温和推力）的概率")
                .defineInRange("themeProbPerHour", 0.08D, 0.0D, 5.0D);
        PM_THEME_JUMP_RATIO = b.comment("主题行情推力，相对个股小时波动的比例")
                .defineInRange("themeJumpRatio", 0.8D, 0.0D, 10.0D);
        PM_MU_CAP = b.comment("趋势（每小时对数漂移）绝对值上限")
                .defineInRange("muCap", 0.045D, 0.001D, 0.5D);
        PM_TICK_MOVE_CAP = b.comment("单个行情 tick 的对数变动硬上限（0.025 = 约 2.5%）")
                .defineInRange("tickMoveCap", 0.025D, 0.001D, 0.5D);
        PM_DAY_LIMIT_PCT = b.comment("日涨跌幅限制（相对昨收，0.12 = ±12%）：接近时减速，到顶硬夹；0 = 关闭")
                .defineInRange("dayLimitPct", 0.12D, 0.0D, 1.0D);
        PM_JUMP_PROB_PER_HOUR = b.comment("每小时出现一次随机跳空的概率")
                .defineInRange("jumpProbPerHour", 0.15D, 0.0D, 20.0D);
        PM_JUMP_SCALE = b.comment("跳空幅度标准差，相对个股小时波动的比例")
                .defineInRange("jumpScale", 0.6D, 0.0D, 10.0D);
        PM_IMPACT_COEF = b.comment("平方根成交冲击系数")
                .defineInRange("impactCoef", 0.40D, 0.0D, 5.0D);
        PM_IMPACT_CAP = b.comment("单笔成交冲击上限（0.02 = 2%）")
                .defineInRange("impactCap", 0.02D, 0.001D, 0.5D);
        PM_IMPACT_PERMANENT_SHARE = b.comment("成交冲击中永久计入公允价的比例，其余只推现价并逐渐回落")
                .defineInRange("impactPermanentShare", 0.35D, 0.0D, 1.0D);
        PM_MIN_SPREAD = b.comment("做市盘口相对半价差下限（0.004 = 0.4%）")
                .defineInRange("minSpread", 0.004D, 0.0D, 0.2D);
        PM_SPREAD_COEF = b.comment("做市盘口半价差相对瞬时波动（1 分钟尺度）的倍数")
                .defineInRange("spreadCoef", 1.3D, 0.0D, 20.0D);
        PM_AUTO_NEWS_MAX_IMPACT = b.comment("v3 自动新闻单次最大幅度百分比（%）；公允价与现价会分别吸收一部分，不是一次跳满")
                .defineInRange("autoNewsMaxImpact", 6, 1, 30);
        PM_NEWS_TEMPLATE_SCALE = b.comment("v3 自动新闻模板幅度整体缩放（旧库 5～22% x 0.35，再封顶 8%）")
                .defineInRange("newsTemplateScale", 0.35D, 0.05D, 1.0D);
        PM_AUTO_NEWS_FAIR_SHARE = b.comment("新闻幅度中计入公允价的比例")
                .defineInRange("autoNewsFairShare", 0.70D, 0.0D, 2.0D);
        PM_AUTO_NEWS_SPOT_SHARE = b.comment("新闻幅度中立即计入现价的比例（其余靠现价追公允价）")
                .defineInRange("autoNewsSpotShare", 0.35D, 0.0D, 2.0D);
        PM_SESSION_PROFILE_ENABLED = b.comment("会话软剖面：开盘后/收盘前 15 分钟波动略放大，其余略静（按 sessionOpenMinute/sessionCloseMinute）。默认关闭")
                .define("sessionProfileEnabled", false);
        PM_SESSION_OPEN_BOOST = b.comment("开盘/收盘前 15 分钟的波动放大倍率")
                .defineInRange("sessionOpenBoost", 1.35D, 1.0D, 3.0D);
        PM_SESSION_MID_DAMP = b.comment("盘中的波动倍率（略小于 1 表示稍静）")
                .defineInRange("sessionMidDamp", 0.9D, 0.3D, 1.0D);

        b.pop();
        SPEC = b.build();
    }

    private StockMarketConfig() {
    }

    /** 当前价格模型版本；配置尚未加载时按默认 3 处理。 */
    public static int priceModelVersion() {
        try {
            return PRICE_MODEL_VERSION.get();
        } catch (IllegalStateException e) {
            return 3;
        }
    }

    /** 把 price_model 配置灌进纯 Java 参数对象（配置未加载时保持默认值）。 */
    public static PriceParams priceParams() {
        PriceParams p = new PriceParams();
        try {
            p.dtSec = UPDATE_INTERVAL_SECONDS.get();
            p.meanReversionPerHour = PM_MEAN_REVERSION_PER_HOUR.get();
            p.kappaFairPerHour = PM_KAPPA_FAIR_PER_HOUR.get();
            p.kappaTrendPerHour = PM_KAPPA_TREND_PER_HOUR.get();
            p.kappaSigmaPerHour = PM_KAPPA_SIGMA_PER_HOUR.get();
            p.xiSigma = PM_XI_SIGMA.get();
            p.fairShockShare = PM_FAIR_SHOCK_SHARE.get();
            p.fairFollow = PM_FAIR_FOLLOW.get();
            p.sigmaTrendRatio = PM_SIGMA_TREND_RATIO.get();
            p.themeProbPerHour = PM_THEME_PROB_PER_HOUR.get();
            p.themeJumpRatio = PM_THEME_JUMP_RATIO.get();
            p.muCap = PM_MU_CAP.get();
            p.tickMoveCap = PM_TICK_MOVE_CAP.get();
            p.dayLimitPct = PM_DAY_LIMIT_PCT.get();
            p.jumpProbPerHour = PM_JUMP_PROB_PER_HOUR.get();
            p.jumpScale = PM_JUMP_SCALE.get();
            p.impactCoef = PM_IMPACT_COEF.get();
            p.impactCap = PM_IMPACT_CAP.get();
            p.impactPermanentShare = PM_IMPACT_PERMANENT_SHARE.get();
            p.minSpread = PM_MIN_SPREAD.get();
            p.spreadCoef = PM_SPREAD_COEF.get();
            p.newsFairShare = PM_AUTO_NEWS_FAIR_SHARE.get();
            p.newsSpotShare = PM_AUTO_NEWS_SPOT_SHARE.get();
            p.sessionProfileEnabled = PM_SESSION_PROFILE_ENABLED.get();
            p.sessionOpenBoost = PM_SESSION_OPEN_BOOST.get();
            p.sessionCloseBoost = Math.max(1.0D, PM_SESSION_OPEN_BOOST.get() - 0.1D);
            p.sessionMidDamp = PM_SESSION_MID_DAMP.get();
        } catch (IllegalStateException ignored) {
            // 配置未加载：保持默认
        }
        return p;
    }

    public static double priceModelVolatilityMultiplier() {
        try {
            return PM_VOLATILITY_MULTIPLIER.get();
        } catch (IllegalStateException e) {
            return 1.0D;
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
