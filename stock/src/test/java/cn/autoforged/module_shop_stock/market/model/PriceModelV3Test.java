package cn.autoforged.module_shop_stock.market.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.autoforged.module_shop_stock.StockMarket;
import cn.autoforged.module_shop_stock.market.Bar;
import cn.autoforged.module_shop_stock.market.StockDef;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 价格模型 v3 验收（含整数星币 1 分线防横盘与短周期波段动量校验，固定随机种子可复现）。
 *
 * <p>低/中/高波代表股 = BRCK / FARM / GOLD / VOID，各 200 条路径 x 1440 tick（5 秒一 tick，共 2 小时），无成交、无新闻。
 * 打印完整统计表：{@code ./gradlew.bat simulatePriceModel}。
 */
class PriceModelV3Test {
    private static final int PATHS = 200;
    private static final int TICKS = 1440;
    private static final long SEED = 20261110L;

    private static PriceParams params;
    private static PriceModelSim.Baseline brck;
    private static PriceModelSim.Baseline farm;
    private static PriceModelSim.Baseline voidB;
    private static PriceModelSim.Baseline gold;

    @BeforeAll
    static void simulate() {
        params = new PriceParams();
        brck = PriceModelSim.baseline(params, PriceModelSim.BRCK, PATHS, TICKS, SEED);
        farm = PriceModelSim.baseline(params, PriceModelSim.FARM, PATHS, TICKS, SEED);
        gold = PriceModelSim.baseline(params, PriceModelSim.GOLD, PATHS, TICKS, SEED);
        voidB = PriceModelSim.baseline(params, PriceModelSim.VOID, PATHS, TICKS, SEED);
    }

    private static void between(String what, double v, double lo, double hi) {
        assertTrue(v >= lo && v <= hi, what + " = " + v + " 不在 [" + lo + ", " + hi + "]");
    }

    // ------------------------------------------------------------ §9 基线表

    @Test
    void medianAbsTickReturn() {
        assertTrue(brck.medianAbsTick < 0.006D, "BRCK " + brck.medianAbsTick);
        assertTrue(farm.medianAbsTick < 0.004D, "FARM " + farm.medianAbsTick);
        assertTrue(voidB.medianAbsTick < 0.0040D, "VOID " + voidB.medianAbsTick);
    }

    @Test
    void p99AbsTickReturn() {
        assertTrue(brck.p99AbsTick < 0.020D, "BRCK " + brck.p99AbsTick);
        assertTrue(farm.p99AbsTick < 0.016D, "FARM " + farm.p99AbsTick);
        assertTrue(voidB.p99AbsTick < 0.025D, "VOID " + voidB.p99AbsTick);
    }

    @Test
    void twoHourReturnStd() {
        between("BRCK 2h std", brck.retStd, 0.04D, 0.09D);
        between("FARM 2h std", farm.retStd, 0.03D, 0.08D);
        between("VOID 2h std", voidB.retStd, 0.04D, 0.09D);
        assertTrue(voidB.retStd * PriceModelSim.VOID.base() > brck.retStd * PriceModelSim.BRCK.base());
    }

    @Test
    void maxDrawdownMedian() {
        assertTrue(brck.maxDdMedian < 0.22D, "BRCK " + brck.maxDdMedian);
        assertTrue(farm.maxDdMedian < 0.18D, "FARM " + farm.maxDdMedian);
        assertTrue(voidB.maxDdMedian < 0.12D, "VOID " + voidB.maxDdMedian);
    }

    @Test
    void dayLimitTouchFractionTwoHours() {
        assertTrue(brck.touchFrac < 0.05D);
        assertTrue(farm.touchFrac < 0.10D);
        assertTrue(voidB.touchFrac < 0.20D);
    }

    @Test
    void dayLimitTouchFractionFullDay() {
        double[] b = PriceModelSim.dayStress(params, PriceModelSim.BRCK, 100, SEED + 7);
        double[] f = PriceModelSim.dayStress(params, PriceModelSim.FARM, 100, SEED + 7);
        double[] v = PriceModelSim.dayStress(params, PriceModelSim.VOID, 100, SEED + 7);
        assertTrue(b[1] < 0.28D, "BRCK 24h 触限 " + b[1]);
        assertTrue(f[1] < 0.12D, "FARM 24h 触限 " + f[1]);
        assertTrue(v[1] < 0.20D, "VOID 24h 触限 " + v[1]);
    }

    /**
     * 短周期波段动量与防高频秒级套利：
     * 5 秒单 tick 自相关仍近 0（不可秒级刷单），但 1m/5m/15m 允许温和正自相关（波段延续感，同时受双边手续费与盘口价差约束）。
     */
    @Test
    void returnsHaveNoExploitablePredictability() {
        for (PriceModelSim.Baseline b : new PriceModelSim.Baseline[] {brck, farm, gold, voidB}) {
            assertTrue(Math.abs(b.lag1Tick) < 0.03D, b.symbol + " lag1@5s " + b.lag1Tick);
            assertTrue(b.lag1Min1 > -0.05D && b.lag1Min1 < 0.18D, b.symbol + " lag1@1m " + b.lag1Min1);
            assertTrue(b.lag1Min5 > -0.08D && b.lag1Min5 < 0.28D, b.symbol + " lag1@5m " + b.lag1Min5);
            assertTrue(Math.abs(b.lag1Min15) < 0.30D, b.symbol + " lag1@15m " + b.lag1Min15);
        }
        // 中高价股在 5m 尺度呈现正向波段延续特征
        assertTrue(gold.lag1Min5 > 0.04D, "GOLD lag1@5m " + gold.lag1Min5);
        assertTrue(voidB.lag1Min5 > 0.04D, "VOID lag1@5m " + voidB.lag1Min5);
    }

    @Test
    void volatilityClustering() {
        assertTrue(voidB.lag1Abs > 0.05D, "VOID |r|@5s " + voidB.lag1Abs);
        assertTrue(voidB.absLag1Min1 > 0.05D, "VOID |r|@1m " + voidB.absLag1Min1);
        assertTrue(voidB.absLag1Min5 > 0.04D, "VOID |r|@5m " + voidB.absLag1Min5);
        assertTrue(voidB.kurtosis5m > 3.3D, "VOID 5m 峰度 " + voidB.kurtosis5m);
        for (PriceModelSim.Baseline b : new PriceModelSim.Baseline[] {brck, farm, gold}) {
            assertTrue(b.lag1Abs > 0.0D, b.symbol + " |r|@5s " + b.lag1Abs);
            assertTrue(b.kurtosis5m > 2.5D, b.symbol + " 5m 峰度 " + b.kurtosis5m);
        }
    }

    @Test
    void medianEndRatioHasNoDrift() {
        between("BRCK", brck.medianEndRatio, 0.97D, 1.03D);
        between("FARM", farm.medianEndRatio, 0.97D, 1.03D);
        between("VOID", voidB.medianEndRatio, 0.95D, 1.05D);
    }

    @Test
    void sigmaStaysAroundTarget() {
        for (PriceModelSim.Baseline b : new PriceModelSim.Baseline[] {brck, farm, gold, voidB}) {
            between(b.symbol + " σ 均值/目标", b.meanSigmaRatio, 0.95D, 1.25D);
        }
    }

    @Test
    void resultsAreDeterministicForFixedSeed() {
        PriceModelSim.Baseline again = PriceModelSim.baseline(params, PriceModelSim.BRCK, 20, 360, 5L);
        PriceModelSim.Baseline twice = PriceModelSim.baseline(params, PriceModelSim.BRCK, 20, 360, 5L);
        assertEquals(again.retStd, twice.retStd, 0.0D);
        PriceModelSim.Baseline other = PriceModelSim.baseline(params, PriceModelSim.BRCK, 20, 360, 6L);
        assertNotEquals(again.retStd, other.retStd);
    }

    /** 改 updateIntervalSeconds 不应改变统计性质（按 √dt / dt 缩放）。 */
    @Test
    void changingTickIntervalKeepsStatistics() {
        PriceParams p10 = params.copy();
        p10.dtSec = 10.0D;
        PriceModelSim.Baseline b10 = PriceModelSim.baseline(p10, PriceModelSim.FARM, PATHS, TICKS / 2, SEED);
        double ratio = b10.retStd / farm.retStd;
        between("dt=10s / dt=5s 的 2h std 比值", ratio, 0.85D, 1.15D);
        PriceParams p1 = params.copy();
        p1.dtSec = 1.0D;
        PriceModelSim.Baseline b1 = PriceModelSim.baseline(p1, PriceModelSim.FARM, 100, TICKS * 5, SEED);
        between("dt=1s / dt=5s 的 2h std 比值", b1.retStd / farm.retStd, 0.8D, 1.2D);
    }

    // ------------------------------------------------------------ 新闻

    @Test
    void newsJumpIsGentle() {
        for (PriceModelSim.Stock st : new PriceModelSim.Stock[] {PriceModelSim.BRCK, PriceModelSim.FARM,
                PriceModelSim.VOID}) {
            PriceModelSim.NewsResult n = PriceModelSim.news(params, st, PATHS, SEED + 13);
            assertTrue(n.jumpMedian <= 0.03D, st.symbol() + " 现价跳动中位 " + n.jumpMedian);
            assertTrue(n.jumpP95 <= 0.06D, st.symbol() + " 现价跳动 p95 " + n.jumpP95);
            assertTrue(n.sigmaPeakRatioMax <= 1.8D, st.symbol() + " σ 抬升 " + n.sigmaPeakRatioMax);
            double scale = PriceModelV3.barScale(params, st.base(), st.sigma());
            assertTrue(n.vol30mRatio / scale <= 1.8D, st.symbol() + " 新闻后 30 分钟波动倍数 " + n.vol30mRatio);
            between(st.symbol() + " 追公允比例", n.absorb30m, 0.4D, 1.1D);
        }
    }

    @Test
    void newsMovesFairMoreThanSpot() {
        PriceModelV3.State s = new PriceModelV3.State(100.0D, 100.0D, 0.02D, 0.0D);
        double spot = NewsModel.apply(params, s, 6.0D, 0.02D);
        assertEquals(100.0D * (1.0D + 0.06D * 0.35D), spot, 1e-9D);
        assertEquals(100.0D * (1.0D + 0.06D * 0.70D), s.fair, 1e-9D);
        assertTrue(s.mu > 0.0D && s.mu <= params.muCap);
        assertTrue(s.sigma > 0.02D && s.sigma <= 0.02D * params.sigmaMaxRatio);
        PriceModelV3.State neg = new PriceModelV3.State(100.0D, 100.0D, 0.02D, 0.0D);
        double spotNeg = NewsModel.apply(params, neg, -6.0D, 0.02D);
        assertTrue(spotNeg < 100.0D && neg.fair < 100.0D && neg.mu < 0.0D);
    }

    @Test
    void templateScalingCapsAtEight() {
        assertEquals(8, NewsModel.scaleTemplatePct(22, 0.35D, 8));
        assertEquals(-8, NewsModel.scaleTemplatePct(-30, 0.35D, 8));
        assertEquals(2, NewsModel.scaleTemplatePct(5, 0.35D, 8));
        assertEquals(1, NewsModel.scaleTemplatePct(1, 0.35D, 8));
        assertEquals(0, NewsModel.scaleTemplatePct(0, 0.35D, 8));
    }

    // ------------------------------------------------------------ 成交冲击

    @Test
    void impactWindows() {
        for (PriceModelSim.Stock st : new PriceModelSim.Stock[] {PriceModelSim.BRCK, PriceModelSim.FARM,
                PriceModelSim.GOLD, PriceModelSim.VOID}) {
            double[] im = PriceModelSim.impacts(params, st);
            between(st.symbol() + " 0.02 x depth", im[0], 0.003D, 0.008D);
            between(st.symbol() + " 0.20 x depth", im[1], 0.010D, params.impactCap);
            assertTrue(im[1] <= params.impactCap + 1e-12D);
            assertTrue(im[1] > im[0], "冲击应随数量增大");
        }
    }

    @Test
    void impactIsSquareRootNotLinear() {
        double a = ImpactModel.magnitude(params, 100.0D, 4_000.0D, 0.018D);
        double b = ImpactModel.magnitude(params, 400.0D, 4_000.0D, 0.018D);
        assertEquals(2.0D, b / a, 1e-9D);
        assertEquals(0.0D, ImpactModel.magnitude(params, 0.0D, 4_000.0D, 0.018D), 0.0D);
        assertEquals(params.impactCap, ImpactModel.magnitude(params, 1e9D, 4_000.0D, 0.018D), 0.0D);
    }

    @Test
    void impactSplitsPermanentAndTemporary() {
        PriceModelV3.State s = new PriceModelV3.State(100.0D, 100.0D, 0.018D, 0.0D);
        double spot = ImpactModel.apply(params, s, 0.01D, true, 0.018D);
        assertEquals(101.0D, spot, 1e-9D);
        assertEquals(100.0D * (1.0D + 0.35D * 0.01D), s.fair, 1e-9D);
        assertTrue(s.mu > 0.0D);
        PriceModelV3.State t = new PriceModelV3.State(100.0D, 100.0D, 0.018D, 0.0D);
        double spotSell = ImpactModel.apply(params, t, 0.01D, false, 0.018D);
        assertEquals(99.0D, spotSell, 1e-9D);
        assertTrue(t.fair < 100.0D && t.mu < 0.0D);
    }

    /** 买入冲击后不应继续单边闪崩/飙升：之后 30 分钟现价回吐一部分，而不是继续沿冲击方向跑。 */
    @Test
    void impactDoesNotRunAwayAfterwards() {
        PriceModelSim.Stock st = PriceModelSim.FARM;
        Random rng = new Random(99L);
        PriceModelV3.Env env = PriceModelSim.env(params, st, st.sigma());
        env.dayRef = st.base();
        int moreThanImpact = 0;
        int runs = 300;
        for (int i = 0; i < runs; i++) {
            PriceModelV3.State s = new PriceModelV3.State(st.base(), st.base(), st.sigma(), 0.0D);
            PriceModelV3.StepOut out = new PriceModelV3.StepOut();
            double impact = ImpactModel.magnitude(params, 0.2D * st.depth(), st.depth(), s.sigma);
            s.price = ImpactModel.apply(params, s, impact, true, st.sigma());
            double after = s.price;
            for (int t = 0; t < 360; t++) {
                PriceModelV3.step(params, env, s, rng, out);
            }
            if (s.price > after) {
                moreThanImpact++;
            }
        }
        assertTrue(moreThanImpact < runs * 0.62D, "30 分钟后仍高于冲击后价格的路径占比 " + (moreThanImpact / (double) runs));
    }

    // ------------------------------------------------------------ 护栏

    @Test
    void pricesStayInsideLongTermBandAndDayLimit() {
        PriceModelSim.Stock st = PriceModelSim.VOID;
        Random rng = new Random(5L);
        PriceModelV3.Env env = PriceModelSim.env(params, st, st.sigma() * 6.0D);
        env.dayRef = st.base();
        PriceModelV3.State s = new PriceModelV3.State(st.base(), st.base(), st.sigma() * 6.0D, 0.0D);
        PriceModelV3.StepOut out = new PriceModelV3.StepOut();
        double lo = st.base();
        double hi = st.base();
        for (int t = 0; t < 17_280 * 3; t++) {
            PriceModelV3.step(params, env, s, rng, out);
            assertTrue(Math.abs(out.dlog) <= params.tickMoveCap + 1e-12D, "单 tick 夹紧失效 " + out.dlog);
            lo = Math.min(lo, s.price);
            hi = Math.max(hi, s.price);
        }
        assertTrue(lo >= st.base() * (1.0D - params.dayLimitPct) - 1e-6D, "跌破日限 " + lo);
        assertTrue(hi <= st.base() * (1.0D + params.dayLimitPct) + 1e-6D, "涨破日限 " + hi);
        assertTrue(lo >= env.lower && hi <= env.upper);
    }

    @Test
    void softLandingDecaysTowardsLimit() {
        PriceModelV3.Env env = new PriceModelV3.Env();
        env.dayRef = 100.0D;
        double mid = PriceModelV3.softLandFactor(params, env, 110.8D, 0.01D);
        double near = PriceModelV3.softLandFactor(params, env, 111.6D, 0.01D);
        assertTrue(mid < 1.0D && near < mid, mid + " / " + near);
        assertTrue(near >= params.softLimitFloor);
        assertEquals(1.0D, PriceModelV3.softLandFactor(params, env, 111.6D, -0.01D), 0.0D);
        assertEquals(1.0D, PriceModelV3.softLandFactor(params, env, 109.0D, 0.01D), 0.0D);
    }

    @Test
    void bandExpandsToCurrentPriceWhenOutside() {
        PriceModelV3.Env env = new PriceModelV3.Env();
        env.lower = 25.0D;
        env.upper = 400.0D;
        env.dayRef = 100.0D;
        double[] band = PriceModelV3.band(params, env, 100.0D);
        assertEquals(88.0D, band[0], 1e-9D);
        assertEquals(112.0D, band[1], 1e-9D);
        double[] out = PriceModelV3.band(params, env, 150.0D);
        assertEquals(150.0D, out[1], 1e-9D);
        assertEquals(88.0D, out[0], 1e-9D);
    }

    @Test
    void legacyAndBrokenStateIsRepaired() {
        PriceModelV3.Env env = new PriceModelV3.Env();
        env.sigmaBase = 0.02D;
        env.basePrice = 100.0D;
        env.depth = 4_000.0D;
        env.lower = 25.0D;
        env.upper = 400.0D;
        env.dayRef = -1.0D;
        PriceModelV3.State s = new PriceModelV3.State(100.0D, 0.0D, 0.0D, Double.NaN);
        PriceModelV3.step(params, env, s, new Random(1L), new PriceModelV3.StepOut());
        assertTrue(s.fair > 0.0D && s.sigma > 0.0D && !Double.isNaN(s.mu) && s.price > 0.0D);
        assertEquals(100.0D, s.fair, 3.0D);
    }

    @Test
    void sessionFactorProfile() {
        PriceParams p = params.copy();
        assertEquals(1.0D, PriceModelV3.sessionFactor(p, 600, 0, 1440), 0.0D);
        p.sessionProfileEnabled = true;
        assertEquals(p.sessionOpenBoost, PriceModelV3.sessionFactor(p, 5, 0, 1440), 0.0D);
        assertEquals(p.sessionMidDamp, PriceModelV3.sessionFactor(p, 600, 0, 1440), 0.0D);
        assertEquals(p.sessionCloseBoost, PriceModelV3.sessionFactor(p, 1435, 0, 1440), 0.0D);
        assertEquals(p.sessionOpenBoost, PriceModelV3.sessionFactor(p, 22 * 60 + 3, 22 * 60, 6 * 60), 0.0D);
        assertEquals(p.sessionMidDamp, PriceModelV3.sessionFactor(p, 2 * 60, 22 * 60, 6 * 60), 0.0D);
    }

    // ------------------------------------------------------------ 做市盘口

    @Test
    void spreadTracksSigmaWithFloor() {
        long calm = BookMath.step(params, 1000L, 0.005D);
        long usual = BookMath.step(params, 1000L, 0.02D);
        long wild = BookMath.step(params, 1000L, 0.08D);
        assertEquals(4L, calm);
        assertTrue(wild > usual && usual >= calm, calm + " " + usual + " " + wild);
        assertEquals(1L, BookMath.step(params, 10L, 0.001D));
    }

    @Test
    void skewDirection() {
        assertTrue(BookMath.skew(params, 0.03D, 100.0D, 100.0D) > 0.0D);
        assertTrue(BookMath.skew(params, -0.03D, 100.0D, 100.0D) < 0.0D);
        assertTrue(BookMath.skew(params, 0.0D, 105.0D, 100.0D) < 0.0D);
        assertEquals(params.skewCap, BookMath.skew(params, 0.5D, 100.0D, 100.0D), 0.0D);
    }

    @Test
    void weightedFillWalksTheBook() {
        long[] asks = {101, 102, 103, 104, 105};
        long[] vols = {100, 100, 100, 100, 100};
        assertEquals(100L, BookMath.weightedFill(params, 100L, asks, vols, true, 10L));
        assertEquals(101L, BookMath.weightedFill(params, 100L, asks, vols, true, 200L));
        long big = BookMath.weightedFill(params, 100L, asks, vols, true, 1_000L);
        assertTrue(big >= 103L && big <= 106L, "big=" + big);
        assertTrue(big > BookMath.weightedFill(params, 100L, asks, vols, true, 300L));
        long[] bids = {99, 98, 97, 96, 95};
        long sellSmall = BookMath.weightedFill(params, 100L, bids, vols, false, 10L);
        long sellBig = BookMath.weightedFill(params, 100L, bids, vols, false, 1_000L);
        assertEquals(100L, sellSmall);
        assertTrue(sellBig < 98L && sellBig >= 90L, "sellBig=" + sellBig);
        assertEquals(100L, BookMath.weightedFill(params, 100L, asks, vols, true, 0L));
    }

    // ------------------------------------------------------------ 1 分线防横盘 / 种子历史 / 内置股

    /** 便宜股整数星币 1 分钟柱应有高低差，且走连贯波段而非原地翻向心电图。 */
    @Test
    void cheapStockMinuteBarsFollowThePricePath() {
        PriceModelSim.Stock st = PriceModelSim.BRCK;
        assertTrue(PriceModelV3.barScale(params, st.base(), st.sigma()) > 1.5D);
        assertEquals(1.0D, PriceModelV3.barScale(params, PriceModelSim.VOID.base(), PriceModelSim.VOID.sigma()), 0.0D);
        Random rng = new Random(7L);
        PriceModelV3.Env env = PriceModelSim.env(params, st, st.sigma());
        env.dayRef = st.base();
        PriceModelV3.State s = new PriceModelV3.State(st.base(), st.base(), st.sigma(), 0.0D);
        PriceModelV3.StepOut out = new PriceModelV3.StepOut();
        int minutes = 90;
        int alive = 0;
        int reversals = 0;
        int moves = 0;
        int prevSign = 0;
        for (int m = 0; m < minutes; m++) {
            long open = Math.round(s.price);
            long hi = open;
            long lo = open;
            for (int t = 0; t < 12; t++) {
                PriceModelV3.step(params, env, s, rng, out);
                long px = Math.round(s.price);
                hi = Math.max(hi, Math.max(px, Math.round(out.wickHigh)));
                lo = Math.min(lo, Math.min(px, Math.max(1L, Math.round(out.wickLow))));
            }
            long close = Math.round(s.price);
            if (hi > lo) {
                alive++;
            }
            int sign = Long.compare(close, open);
            if (sign != 0) {
                if (prevSign != 0 && sign != prevSign) {
                    reversals++;
                }
                moves++;
                prevSign = sign;
            }
        }
        assertTrue(alive >= 70, "有高低差的分钟 " + alive + "/" + minutes);
        assertTrue(moves > 0 && reversals < moves * 0.72D, "逐根翻向 " + reversals + "/" + moves);
    }

    /** 校验全价位代表股 1 分线不出现大面积一字平柱，且涨停附近不会因公允价越界而长时间死锁贴顶。 */
    @Test
    void noLongSidewaysFlatlineOnOneMinuteChart() {
        Random rng = new Random(20261111L);
        for (PriceModelSim.Stock st : new PriceModelSim.Stock[] {
                PriceModelSim.BRCK, PriceModelSim.FARM, PriceModelSim.GOLD, PriceModelSim.VOID}) {
            PriceModelV3.Env env = PriceModelSim.env(params, st, st.sigma());
            env.dayRef = st.base();
            int flatBars = 0;
            int totalBars = 0;
            int maxSameClose = 1;
            for (int path = 0; path < 10; path++) {
                PriceModelV3.State s = new PriceModelV3.State(st.base(), st.base(), st.sigma(), 0.0D);
                PriceModelV3.StepOut out = new PriceModelV3.StepOut();
                long prevClose = Math.round(s.price);
                int streak = 1;
                for (int m = 0; m < 180; m++) {
                    long open = Math.round(s.price);
                    long hi = open;
                    long lo = open;
                    for (int t = 0; t < 12; t++) {
                        PriceModelV3.step(params, env, s, rng, out);
                        long px = Math.round(s.price);
                        hi = Math.max(hi, Math.max(px, Math.round(out.wickHigh)));
                        lo = Math.min(lo, Math.min(px, Math.max(1L, Math.round(out.wickLow))));
                    }
                    long close = Math.round(s.price);
                    if (open == hi && hi == lo && lo == close) {
                        flatBars++;
                    }
                    totalBars++;
                    if (close == prevClose) {
                        streak++;
                        maxSameClose = Math.max(maxSameClose, streak);
                    } else {
                        streak = 1;
                        prevClose = close;
                    }
                }
            }
            assertTrue(flatBars < totalBars * 0.03D, st.symbol() + " 一字平柱占比过高 " + flatBars + "/" + totalBars);
            assertTrue(maxSameClose <= 18, st.symbol() + " 连续同收盘价过长 " + maxSameClose);
        }

        // 涨跌停边界测试：即便现价已触及 +12% 且公允价曾被新闻推至 +15%，60 分钟内应自然回落脱离死锁
        PriceModelV3.Env env = PriceModelSim.env(params, PriceModelSim.FARM, PriceModelSim.FARM.sigma());
        env.dayRef = 100.0D;
        int unpinned = 0;
        for (int i = 0; i < 50; i++) {
            PriceModelV3.State s = new PriceModelV3.State(112.0D, 115.0D, PriceModelSim.FARM.sigma(), 0.03D);
            PriceModelV3.StepOut out = new PriceModelV3.StepOut();
            for (int t = 0; t < 720; t++) {
                PriceModelV3.step(params, env, s, rng, out);
            }
            if (Math.round(s.price) < 112L) {
                unpinned++;
            }
        }
        assertTrue(unpinned >= 35, "涨停附近脱离粘滞比例过低 " + unpinned + "/50");
    }

    @Test
    void seedHistoryUsesV3ProcessAndIsWellFormed() {
        PriceModelSim.Stock st = PriceModelSim.FARM;
        long now = 1_800_000_000L - 1_800_000_000L % 60L;
        PriceModelV3.History h = PriceModelV3.generateHistory(params, st.base(), st.sigma(), st.depth(),
                st.base() / 4L, st.base() * 4.0D, now, 60, 72, 42L);
        assertEquals(72, h.bars.size());
        assertEquals(now, h.bars.get(71).time);
        assertEquals(now - 71L * 60L, h.bars.get(0).time);
        for (int i = 0; i < h.bars.size(); i++) {
            Bar b = h.bars.get(i);
            assertTrue(b.high >= Math.max(b.open, b.close) && b.low <= Math.min(b.open, b.close), "bar " + i);
            assertTrue(b.low >= 1L && b.close >= st.base() / 4L && b.close <= st.base() * 4L);
            if (i > 0) {
                assertTrue(b.time > h.bars.get(i - 1).time);
            }
        }
        assertEquals(Math.round(h.state.price), h.bars.get(71).close);
        assertTrue(h.state.fair > 0.0D && h.state.sigma > 0.0D);
        double maxRange = 0.0D;
        for (Bar b : h.bars) {
            maxRange = Math.max(maxRange, (b.high - b.low) / (double) b.open);
        }
        assertTrue(maxRange < 0.06D, "种子历史单根振幅 " + maxRange);
        PriceModelV3.History same = PriceModelV3.generateHistory(params, st.base(), st.sigma(), st.depth(),
                st.base() / 4L, st.base() * 4.0D, now, 60, 72, 42L);
        assertEquals(h.bars.get(71).close, same.bars.get(71).close);
    }

    @Test
    void volatilityMigrationRules() {
        assertEquals(0.018D, VolatilityMigration.migrate(0.036D), 1e-12D);
        assertEquals(VolatilityMigration.MAX, VolatilityMigration.migrate(0.35D), 0.0D);
        assertEquals(VolatilityMigration.MIN, VolatilityMigration.migrate(0.002D), 0.0D);
        assertEquals(0.020D, VolatilityMigration.migrate(Double.NaN), 0.0D);
        assertEquals(0.020D, VolatilityMigration.migrate(-1.0D), 0.0D);
        assertEquals(0.0425D, VolatilityMigration.round4(0.04250001D), 0.0D);
    }

    @Test
    void builtinStocksAreInTheNewHourlyScale() {
        assertEquals(24, StockMarket.SEED.size());
        for (StockDef def : StockMarket.SEED) {
            between(def.symbol + " σ_hour", def.volatility, VolatilityMigration.MIN, VolatilityMigration.MAX);
        }
        double brckVol = StockMarket.SEED.stream().filter(d -> d.symbol.equals("BRCK")).findFirst().get().volatility;
        double voidVol = StockMarket.SEED.stream().filter(d -> d.symbol.equals("VOID")).findFirst().get().volatility;
        assertEquals(PriceModelSim.BRCK.sigma(), brckVol, 1e-9D);
        assertEquals(PriceModelSim.VOID.sigma(), voidVol, 1e-9D);
    }
}
