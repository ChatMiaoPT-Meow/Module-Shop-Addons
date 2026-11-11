package cn.autoforged.module_shop_stock.market.model;

/**
 * v3 价格模型的全部可调参数（纯 Java，不依赖 Minecraft）。
 *
 * <p>字段默认值就是发布默认值；{@code StockMarketConfig} 里的 {@code price_model} 分组在运行时把配置值灌进来。
 * 所有"每小时"量纲的参数都按 {@code dtSec/3600} 缩放，所以改 {@code updateIntervalSeconds} 不会改变统计性质。
 */
public final class PriceParams {
    /** 行情 tick 间隔（秒）。 */
    public double dtSec = 5.0D;

    // ---- 瞬时波动率 σ（EWMA / 半 Heston）----
    /** σ 回归目标的强度（每小时）。 */
    public double kappaSigmaPerHour = 2.2D;
    /** σ 自身噪声（相对）。 */
    public double xiSigma = 0.48D;
    /** 已实现大跳动后抬高 σ 的系数。 */
    public double realizedBump = 0.02D;
    /** 触发"已实现抬升"的阈值：|r| > 阈值 × σ_target × √dt。 */
    public double realizedThreshold = 2.4D;
    /** 瞬时波动率下限比例（抬高至 0.65，防止个股跌入数十分钟的死水横盘）。 */
    public double sigmaMinRatio = 0.65D;
    public double sigmaMaxRatio = 2.20D;

    // ---- 日内波段动量 μ（朗之万订单流 OU，特征波段时长约 15～20 分钟）----
    public double kappaTrendPerHour = 3.2D;
    /** 波段订单流激励强度 = 该比例 × 有效小时波动（每√小时）。 */
    public double sigmaTrendRatio = 3.5D;
    /** 每小时触发一次波段突破（主题推力）的概率。 */
    public double themeProbPerHour = 1.20D;
    /** 波段突破推力强度 = 该比例 × 有效小时波动。 */
    public double themeJumpRatio = 1.90D;
    /** 波段趋势（每小时对数漂移）基准绝对值上限。 */
    public double muCap = 0.16D;

    // ---- 公允价 F ----
    public double kappaFairPerHour = 0.10D;
    /** 波段趋势直接传导进公允价的比例（避免趋势与均值回归互相抵消）。 */
    public double fairFollow = 0.94D;
    /** 每 tick 扩散冲击中永久计入公允价的比例。 */
    public double fairShockShare = 0.92D;

    // ---- 现价 S 与整数星币低价股联动 ----
    public double meanReversionPerHour = 0.60D;
    /** 直接进入单 tick 随机扩散的波动份额（其余方差由波段动量 μ 承载，使 1 分线走出连贯波段而非原地毛刺）。 */
    public double diffusionShare = 0.70D;
    public double jumpProbPerHour = 0.15D;
    /** 跳空标准差 = 该比例 × σ（每小时 σ，不随 dt 缩放）。 */
    public double jumpScale = 0.55D;
    public double tickMoveCap = 0.025D;
    /**
     * 1 分钟有效扩散的星币标准差下限（整数星币防死区）。
     * 低价股不足该下限时，按 {@link #barFairShare} 同步放大公允价与波段趋势，避免现价独自放大后围着静止公允价原地锯齿横盘。
     * 0 = 关闭；本身已超过该幅度的中高价股不放大。
     */
    public double barMoveCoins = 0.78D;
    /** 低价补偿倍率传导至公允价与波段动量的指数份额（0～1）。 */
    public double barFairShare = 0.82D;
    /** 低价补偿传导至公允价与波段动量的最大倍率上限（保证 24h 日限触碰率温和）。 */
    public double barFairMaxScale = 3.20D;

    // ---- 日限 ----
    public double dayLimitPct = 0.12D;
    /** 日限软着陆起点：距离占限额的比例超过它后，外推方向的变动按平方减速。 */
    public double softLimitStart = 0.85D;
    public double softLimitFloor = 0.05D;

    // ---- 模拟成交量 / 影线 ----
    public double depthVolFrac = 0.008D;
    public double volMoveGain = 0.8D;

    // ---- 新闻（fair/spot 拆分）----
    public double newsFairShare = 0.70D;
    public double newsSpotShare = 0.35D;
    public double newsSigmaGain = 1.2D;
    public double newsMuGain = 0.25D;

    // ---- 成交冲击 ----
    public double impactCoef = 0.40D;
    /** 冲击对 σ 的弹性取 0.5：冲击 ∝ √(σ·σRef)，避免低波股几乎无冲击而高波股一碰就顶满。 */
    public double impactSigmaRef = 0.02D;
    public double impactAdvFrac = 0.05D;
    public double impactCap = 0.02D;
    public double impactPermanentShare = 0.35D;
    public double impactMu = 0.10D;
    public double impactSigmaGain = 1.0D;

    // ---- 做市 ----
    public double minSpread = 0.004D;
    public double spreadCoef = 1.3D;
    /** 价差对应 σ 的观察窗口（小时）。1/60 = 1 分钟。 */
    public double spreadHorizonHours = 1.0D / 60.0D;
    public double skewMuCoef = 2.5D;
    public double skewGapCoef = 8.0D;
    public double skewCap = 0.25D;
    /** 吃穿五档后，剩余量按最远一档再滑点的比例。 */
    public double bookExtSlippage = 0.005D;

    // ---- 会话软剖面（默认关闭）----
    public boolean sessionProfileEnabled = false;
    public double sessionOpenBoost = 1.35D;
    public double sessionCloseBoost = 1.25D;
    public double sessionMidDamp = 0.9D;
    public int sessionEdgeMinutes = 15;

    public double dtHour() {
        return this.dtSec / 3600.0D;
    }

    public PriceParams copy() {
        PriceParams c = new PriceParams();
        c.dtSec = dtSec;
        c.kappaSigmaPerHour = kappaSigmaPerHour;
        c.xiSigma = xiSigma;
        c.realizedBump = realizedBump;
        c.realizedThreshold = realizedThreshold;
        c.sigmaMinRatio = sigmaMinRatio;
        c.sigmaMaxRatio = sigmaMaxRatio;
        c.kappaTrendPerHour = kappaTrendPerHour;
        c.sigmaTrendRatio = sigmaTrendRatio;
        c.themeProbPerHour = themeProbPerHour;
        c.themeJumpRatio = themeJumpRatio;
        c.muCap = muCap;
        c.kappaFairPerHour = kappaFairPerHour;
        c.fairFollow = fairFollow;
        c.fairShockShare = fairShockShare;
        c.meanReversionPerHour = meanReversionPerHour;
        c.diffusionShare = diffusionShare;
        c.jumpProbPerHour = jumpProbPerHour;
        c.jumpScale = jumpScale;
        c.tickMoveCap = tickMoveCap;
        c.barMoveCoins = barMoveCoins;
        c.barFairShare = barFairShare;
        c.barFairMaxScale = barFairMaxScale;
        c.dayLimitPct = dayLimitPct;
        c.softLimitStart = softLimitStart;
        c.softLimitFloor = softLimitFloor;
        c.depthVolFrac = depthVolFrac;
        c.volMoveGain = volMoveGain;
        c.newsFairShare = newsFairShare;
        c.newsSpotShare = newsSpotShare;
        c.newsSigmaGain = newsSigmaGain;
        c.newsMuGain = newsMuGain;
        c.impactCoef = impactCoef;
        c.impactSigmaRef = impactSigmaRef;
        c.impactAdvFrac = impactAdvFrac;
        c.impactCap = impactCap;
        c.impactPermanentShare = impactPermanentShare;
        c.impactMu = impactMu;
        c.impactSigmaGain = impactSigmaGain;
        c.minSpread = minSpread;
        c.spreadCoef = spreadCoef;
        c.spreadHorizonHours = spreadHorizonHours;
        c.skewMuCoef = skewMuCoef;
        c.skewGapCoef = skewGapCoef;
        c.skewCap = skewCap;
        c.bookExtSlippage = bookExtSlippage;
        c.sessionProfileEnabled = sessionProfileEnabled;
        c.sessionOpenBoost = sessionOpenBoost;
        c.sessionCloseBoost = sessionCloseBoost;
        c.sessionMidDamp = sessionMidDamp;
        c.sessionEdgeMinutes = sessionEdgeMinutes;
        return c;
    }
}
