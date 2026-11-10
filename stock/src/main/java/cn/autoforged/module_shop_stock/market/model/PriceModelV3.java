package cn.autoforged.module_shop_stock.market.model;

import cn.autoforged.module_shop_stock.market.Bar;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 价格模型 v3：对数 OU 公允价 + EWMA 随机波动率 + 日限/长期带护栏（纯 Java，无 Minecraft 依赖）。
 *
 * <p>单票单 tick 的全部数学都在 {@link #step}。{@code MarketEngine} 只负责把 {@code Quote} 与配置适配进来。
 * 价格全程用 double 保存（便宜股单 tick 变动远小于 1 星币，取整会吃掉全部演化），对外展示价由调用方取整。
 */
public final class PriceModelV3 {
    private PriceModelV3() {
    }

    /** 单票可演化状态。 */
    public static final class State {
        /** 精确现价（未取整）。 */
        public double price;
        /** 公允价。 */
        public double fair;
        /** 瞬时波动率（每√小时，对数）。 */
        public double sigma;
        /** 趋势（每小时对数漂移）。 */
        public double mu;
        /** 新闻残余标记（仅衰减展示，不乘进波动）。 */
        public double newsBoost;
        /** 上一 tick 已实现的对数收益（不持久化）。 */
        public double lastRet;

        public State() {
        }

        public State(double price, double fair, double sigma, double mu) {
            this.price = price;
            this.fair = fair;
            this.sigma = sigma;
            this.mu = mu;
        }
    }

    /** 每 tick 的外部输入。 */
    public static final class Env {
        /** 个股基础小时波动 = def.volatility × 全局倍率（未乘会话因子）。 */
        public double sigmaBase;
        public double basePrice;
        public double depth;
        /** 日限参考价（昨收或开盘价）；小于等于 0 表示不启用日限。 */
        public double dayRef;
        /** 长期带下沿/上沿。 */
        public double lower;
        public double upper;
        /** 会话因子 s（默认 1）。 */
        public double session = 1.0D;
    }

    /** 每 tick 的输出（供取整、写 K 线、模拟成交量）。 */
    public static final class StepOut {
        public double prevPrice;
        public double dlog;
        public double wickHigh;
        public double wickLow;
        public double simVolume;
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 日限 + 长期带内允许的价格区间；当前价已在区间外时，区间向当前价扩展，避免瞬间被硬拽回（旧档/管理员改价）。 */
    public static double[] band(PriceParams p, Env env, double current) {
        double lo = env.lower;
        double hi = env.upper;
        if (env.dayRef > 0.0D && p.dayLimitPct > 0.0D) {
            lo = Math.max(lo, env.dayRef * (1.0D - p.dayLimitPct));
            hi = Math.min(hi, env.dayRef * (1.0D + p.dayLimitPct));
        }
        if (hi < lo) {
            hi = lo;
        }
        lo = Math.min(lo, current);
        hi = Math.max(hi, current);
        return new double[] {lo, hi};
    }

    /** 日限软着陆倍率：只对"继续远离昨收"的变动生效。 */
    static double softLandFactor(PriceParams p, Env env, double price, double dlog) {
        if (env.dayRef <= 0.0D || p.dayLimitPct <= 0.0D) {
            return 1.0D;
        }
        double dev = (price - env.dayRef) / env.dayRef;
        boolean outward = dev * dlog > 0.0D;
        if (!outward) {
            return 1.0D;
        }
        double closeness = Math.abs(dev) / p.dayLimitPct;
        if (closeness <= p.softLimitStart) {
            return 1.0D;
        }
        double span = Math.max(1e-9D, 1.0D - p.softLimitStart);
        double f = (1.0D - Math.min(1.0D, closeness)) / span;
        return Math.max(p.softLimitFloor, f * f);
    }

    /** 把旧档/缺字段的状态收拾到可用。 */
    public static void sanitize(State s, Env env, double target) {
        if (!(s.price > 0.0D)) {
            s.price = Math.max(1.0D, env.basePrice);
        }
        if (!(s.fair > 0.0D)) {
            s.fair = s.price;
        }
        if (!(s.sigma > 0.0D) || Double.isNaN(s.sigma)) {
            s.sigma = target;
        }
        if (Double.isNaN(s.mu)) {
            s.mu = 0.0D;
        }
        if (Double.isNaN(s.lastRet)) {
            s.lastRet = 0.0D;
        }
    }

    /** 单票单 tick。修改 {@code s}，结果写入 {@code out}。 */
    public static void step(PriceParams p, Env env, State s, Random rng, StepOut out) {
        final double dtH = p.dtHour();
        final double sq = Math.sqrt(dtH);
        final double target = Math.max(1e-6D, env.sigmaBase * env.session);
        sanitize(s, env, target);

        // 1) 瞬时波动率 σ
        double sg = s.sigma;
        sg += p.kappaSigmaPerHour * (target - sg) * dtH + p.xiSigma * sg * rng.nextGaussian() * sq;
        double absRet = Math.abs(s.lastRet);
        if (absRet > p.realizedThreshold * target * sq) {
            // realizedBump 以 5 秒 tick 为基准，按 dt 折算，保证改 updateIntervalSeconds 后统计性质不变
            sg += p.realizedBump * (p.dtSec / 5.0D) * (absRet / sq - target);
        }
        sg = clamp(sg, p.sigmaMinRatio * target, p.sigmaMaxRatio * target);
        s.sigma = sg;

        // 2) 趋势 μ（OU，不硬切；偶发温和主题）
        double mu = s.mu * Math.exp(-p.kappaTrendPerHour * dtH)
                + p.sigmaTrendRatio * target * rng.nextGaussian() * sq;
        if (rng.nextDouble() < p.themeProbPerHour * dtH) {
            mu += (rng.nextBoolean() ? 1.0D : -1.0D) * p.themeJumpRatio * target;
        }
        mu = clamp(mu, -p.muCap, p.muCap);
        s.mu = mu;

        // 3) 现价扩散冲击（+ 稀疏跳空）
        double diff = sg * rng.nextGaussian() * sq;
        if (rng.nextDouble() < p.jumpProbPerHour * dtH) {
            diff += p.jumpScale * sg * rng.nextGaussian();
        }
        diff = clamp(diff, -p.tickMoveCap, p.tickMoveCap);

        double lnS = Math.log(s.price);
        double lnF = Math.log(s.fair);
        double lnB = Math.log(Math.max(1.0D, env.basePrice));

        // 4) 公允价：拉向 base + 趋势传导 + 一部分永久创新
        double lnFNext = lnF + p.kappaFairPerHour * (lnB - lnF) * dtH + mu * p.fairFollow * dtH
                + p.fairShockShare * diff;
        double fairNext = clamp(Math.exp(lnFNext), env.lower, env.upper);

        // 5) 现价：对数 OU 追公允价 + 趋势余量 + 伊藤修正 + 扩散
        double gap = lnF - lnS;
        double drift = p.meanReversionPerHour * gap + mu * (1.0D - p.fairFollow) + 0.5D * sg * sg;
        double dlog = clamp(drift * dtH + diff, -p.tickMoveCap, p.tickMoveCap);
        dlog *= softLandFactor(p, env, s.price, dlog);

        double[] band = band(p, env, s.price);
        double next = clamp(s.price * Math.exp(dlog), band[0], band[1]);
        double realized = Math.log(next / s.price);

        // 6) 影线与模拟成交量
        double tickSigma = sg * sq;
        double wickUp = next * tickSigma * 0.5D * Math.abs(rng.nextGaussian());
        double wickDn = next * tickSigma * 0.5D * Math.abs(rng.nextGaussian());
        double volScale = Math.abs(realized) / Math.max(1e-9D, tickSigma);
        double simVol = env.depth * p.depthVolFrac * (0.4D + 0.6D * rng.nextDouble())
                * (1.0D + p.volMoveGain * volScale) * env.session;

        out.prevPrice = s.price;
        out.dlog = realized;
        out.wickHigh = Math.max(s.price, next) + wickUp;
        out.wickLow = Math.max(1e-6D, Math.min(s.price, next) - wickDn);
        out.simVolume = simVol;

        s.price = next;
        s.fair = fairNext;
        s.lastRet = realized;
        s.newsBoost *= Math.exp(-p.kappaSigmaPerHour * dtH);
        if (s.newsBoost < 0.001D) {
            s.newsBoost = 0.0D;
        }
    }

    /**
     * 会话软剖面：开盘后 N 分钟放大、收盘前 N 分钟放大、其余略静。未启用时恒为 1。
     * {@code closeMinute} 小于 {@code openMinute} 视为跨午夜。
     */
    public static double sessionFactor(PriceParams p, int minuteOfDay, int openMinute, int closeMinute) {
        if (!p.sessionProfileEnabled) {
            return 1.0D;
        }
        int total = 1440;
        int len = ((closeMinute - openMinute) % total + total) % total;
        if (len == 0) {
            len = total;
        }
        int since = ((minuteOfDay - openMinute) % total + total) % total;
        if (since >= len) {
            return 1.0D;
        }
        int edge = Math.max(1, p.sessionEdgeMinutes);
        if (since < edge) {
            return p.sessionOpenBoost;
        }
        if (len - since <= edge) {
            return p.sessionCloseBoost;
        }
        return p.sessionMidDamp;
    }

    /** {@link #generateHistory} 的结果。 */
    public static final class History {
        public final List<Bar> bars = new ArrayList<>();
        public final State state = new State();
        public long volumeLastDay;
        public long dayOpen;
        public long dayHigh;
        public long dayLow;
        public long prevClose;
    }

    /**
     * 新档种子历史：跑真正的 v3 过程生成 {@code barCount} 根 {@code barSeconds} 秒的 K 线。
     * 起点在基准价 ±3% 内，末根结束于 {@code alignedNow}。
     */
    public static History generateHistory(PriceParams p, double basePrice, double sigmaBase, double depth,
            double lower, double upper, long alignedNow, int barSeconds, int barCount, long seed) {
        Random rng = new Random(seed ^ 0x5DEECE66DL);
        History h = new History();
        double start = Math.max(1.0D, basePrice * (0.97D + rng.nextDouble() * 0.06D));
        State s = h.state;
        s.price = start;
        s.fair = start;
        s.sigma = sigmaBase;
        s.mu = 0.0D;
        Env env = new Env();
        env.sigmaBase = sigmaBase;
        env.basePrice = basePrice;
        env.depth = depth;
        env.lower = lower;
        env.upper = upper;
        env.dayRef = -1.0D;
        StepOut out = new StepOut();

        int ticksPerBar = Math.max(1, (int) Math.round(barSeconds / p.dtSec));
        int dayBars = Math.min(barCount, Math.max(1, 86_400 / Math.max(1, barSeconds)));
        long dayVolume = 0L;
        long dayHigh = Math.round(start);
        long dayLow = Math.round(start);
        long dayOpen = Math.round(start);
        long prevClose = Math.round(start);
        for (int i = 0; i < barCount; i++) {
            long barTime = alignedNow - (barCount - 1L - i) * (long) barSeconds;
            long open = Math.max(1L, Math.round(s.price));
            long hi = open;
            long lo = open;
            long vol = 0L;
            for (int t = 0; t < ticksPerBar; t++) {
                step(p, env, s, rng, out);
                long close = Math.max(1L, Math.round(s.price));
                hi = Math.max(hi, Math.max(close, Math.round(out.wickHigh)));
                lo = Math.min(lo, Math.min(close, Math.max(1L, Math.round(out.wickLow))));
                vol += Math.max(1L, Math.round(out.simVolume));
            }
            long close = Math.max(1L, Math.round(s.price));
            h.bars.add(new Bar(barTime, open, hi, lo, close, vol));
            if (i == barCount - dayBars) {
                dayOpen = open;
                prevClose = open;
                dayHigh = hi;
                dayLow = lo;
                dayVolume = 0L;
            }
            if (i >= barCount - dayBars) {
                dayHigh = Math.max(dayHigh, hi);
                dayLow = Math.min(dayLow, lo);
                dayVolume += vol;
            }
        }
        h.dayOpen = dayOpen;
        h.prevClose = prevClose;
        h.dayHigh = Math.max(dayHigh, Math.round(s.price));
        h.dayLow = Math.min(dayLow, Math.round(s.price));
        h.volumeLastDay = dayVolume;
        return h;
    }
}
