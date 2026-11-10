package cn.autoforged.module_shop_stock.market.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * v3 价格模型统计模拟（只依赖纯 Java 核心）。
 *
 * <p>既被 {@code PriceModelV3Test} 当断言用，也能直接运行打印完整验收表：
 * {@code ./gradlew.bat simulatePriceModel "-Pmodule_shop_root=..."}。
 */
public final class PriceModelSim {
    private PriceModelSim() {
    }

    /** 代表股：新口径 σ_hour 由旧值经 {@link VolatilityMigration} 迁移得到。 */
    public record Stock(String symbol, double oldVol, long base, long depth) {
        public double sigma() {
            return VolatilityMigration.migrate(oldVol);
        }
    }

    public static final Stock BRCK = new Stock("BRCK", 0.032D, 64L, 6_000L);
    public static final Stock FARM = new Stock("FARM", 0.036D, 100L, 4_200L);
    public static final Stock GOLD = new Stock("GOLD", 0.045D, 260L, 4_400L);
    public static final Stock VOID = new Stock("VOID", 0.085D, 500L, 2_200L);

    /** 基线统计（无成交、无新闻）。 */
    public static final class Baseline {
        public String symbol;
        public double sigma;
        public int paths;
        public int ticks;
        public double medianAbsTick;
        public double p99AbsTick;
        public double retStd;
        public double maxDdMedian;
        public double maxDdP95;
        public double touchFrac;
        public double lag1Tick;
        public double lag1Abs;
        public double lag1Min1;
        public double lag1Min5;
        public double lag1Min15;
        public double absLag1Min1;
        public double absLag1Min5;
        public double medianEndRatio;
        public double meanSigmaRatio;
        public double kurtosis5m;
        public double displayChangeFrac;
        public double retStdMin;
        public double retStdMax;
    }

    public static Baseline baseline(PriceParams p, Stock st, int paths, int ticks, long seed) {
        double sigma = st.sigma();
        Random rng = new Random(seed);
        double[][] r = new double[paths][ticks];
        double[] endRatio = new double[paths];
        double[] endLog = new double[paths];
        double[] dd = new double[paths];
        int touched = 0;
        double sigmaSum = 0;
        long sigmaN = 0;
        long displayChanges = 0;
        PriceModelV3.Env env = env(p, st, sigma);
        for (int i = 0; i < paths; i++) {
            PriceModelV3.State s = new PriceModelV3.State(st.base, st.base, sigma, 0.0D);
            env.dayRef = st.base;
            PriceModelV3.StepOut out = new PriceModelV3.StepOut();
            double peak = s.price;
            double maxdd = 0;
            boolean touch = false;
            for (int t = 0; t < ticks; t++) {
                long before = Math.round(s.price);
                PriceModelV3.step(p, env, s, rng, out);
                r[i][t] = out.dlog;
                if (Math.round(s.price) != before) {
                    displayChanges++;
                }
                peak = Math.max(peak, s.price);
                maxdd = Math.max(maxdd, (peak - s.price) / peak);
                if (Math.abs(s.price / st.base - 1.0D) >= p.dayLimitPct - 1e-9D) {
                    touch = true;
                }
                sigmaSum += s.sigma / sigma;
                sigmaN++;
            }
            if (touch) {
                touched++;
            }
            endRatio[i] = s.price / st.base;
            endLog[i] = Math.log(s.price / st.base);
            dd[i] = maxdd;
        }
        Baseline b = new Baseline();
        b.symbol = st.symbol;
        b.sigma = sigma;
        b.paths = paths;
        b.ticks = ticks;
        double[] abs = new double[paths * ticks];
        int k = 0;
        for (double[] row : r) {
            for (double v : row) {
                abs[k++] = Math.abs(v);
            }
        }
        Arrays.sort(abs);
        b.medianAbsTick = abs[abs.length / 2];
        b.p99AbsTick = abs[(int) (abs.length * 0.99D)];
        b.retStd = std(endLog);
        double[] sortedDd = dd.clone();
        Arrays.sort(sortedDd);
        b.maxDdMedian = sortedDd[paths / 2];
        b.maxDdP95 = sortedDd[(int) (paths * 0.95D)];
        b.touchFrac = touched / (double) paths;
        b.lag1Tick = lag1(r, 1, false);
        b.lag1Abs = lag1(r, 1, true);
        b.lag1Min1 = lag1(r, 12, false);
        b.lag1Min5 = lag1(r, 60, false);
        b.lag1Min15 = lag1(r, 180, false);
        b.absLag1Min1 = lag1(r, 12, true);
        b.absLag1Min5 = lag1(r, 60, true);
        double[] er = endRatio.clone();
        Arrays.sort(er);
        b.medianEndRatio = er[paths / 2];
        b.meanSigmaRatio = sigmaSum / sigmaN;
        b.kurtosis5m = kurtosis(r, 60);
        b.displayChangeFrac = displayChanges / (double) ((long) paths * ticks);
        // 1 小时收益标准差（取每条路径前一半），用于体感参照
        double[] half = new double[paths];
        for (int i = 0; i < paths; i++) {
            double sum = 0;
            for (int t = 0; t < ticks / 2; t++) {
                sum += r[i][t];
            }
            half[i] = sum;
        }
        b.retStdMin = std(half);
        return b;
    }

    static PriceModelV3.Env env(PriceParams p, Stock st, double sigma) {
        PriceModelV3.Env env = new PriceModelV3.Env();
        env.sigmaBase = sigma;
        env.basePrice = st.base;
        env.depth = st.depth;
        env.lower = Math.max(1L, st.base / 4L);
        env.upper = st.base * 4.0D;
        env.session = 1.0D;
        return env;
    }

    /** 24 小时压力：日内 std、触发日限比例、价格是否始终在长期带内。 */
    public static double[] dayStress(PriceParams p, Stock st, int paths, long seed) {
        int ticks = (int) Math.round(86_400.0D / p.dtSec);
        double sigma = st.sigma();
        Random rng = new Random(seed);
        PriceModelV3.Env env = env(p, st, sigma);
        double[] endLog = new double[paths];
        int touched = 0;
        double rangeSum = 0;
        for (int i = 0; i < paths; i++) {
            PriceModelV3.State s = new PriceModelV3.State(st.base, st.base, sigma, 0.0D);
            env.dayRef = st.base;
            PriceModelV3.StepOut out = new PriceModelV3.StepOut();
            double hi = s.price;
            double lo = s.price;
            boolean touch = false;
            for (int t = 0; t < ticks; t++) {
                PriceModelV3.step(p, env, s, rng, out);
                hi = Math.max(hi, s.price);
                lo = Math.min(lo, s.price);
                if (Math.abs(s.price / st.base - 1.0D) >= p.dayLimitPct - 1e-9D) {
                    touch = true;
                }
            }
            if (touch) {
                touched++;
            }
            endLog[i] = Math.log(s.price / st.base);
            rangeSum += (hi - lo) / st.base;
        }
        return new double[] {std(endLog), touched / (double) paths, rangeSum / paths};
    }

    /** 新闻情景结果。 */
    public static final class NewsResult {
        public double jumpMedian;
        public double jumpP95;
        public double sigmaPeakRatioMax;
        public double sigmaPeakRatioMedian;
        public double vol30mRatio;
        public double absorb30m;
    }

    public static NewsResult news(PriceParams p, Stock st, int paths, long seed) {
        double sigma = st.sigma();
        Random rng = new Random(seed);
        PriceModelV3.Env env = env(p, st, sigma);
        double[] jumps = new double[paths];
        double[] peaks = new double[paths];
        double[] absorbs = new double[paths];
        double postVarSum = 0;
        long postN = 0;
        double baseTick = sigma * Math.sqrt(p.dtHour());
        for (int i = 0; i < paths; i++) {
            PriceModelV3.State s = new PriceModelV3.State(st.base, st.base, sigma, 0.0D);
            env.dayRef = st.base;
            PriceModelV3.StepOut out = new PriceModelV3.StepOut();
            for (int t = 0; t < 360; t++) {
                PriceModelV3.step(p, env, s, rng, out);
            }
            double raw = 5 + rng.nextDouble() * 15;
            int pct = NewsModel.scaleTemplatePct((int) Math.round(raw) * (rng.nextBoolean() ? 1 : -1), 0.35D, 8);
            pct = Math.max(-6, Math.min(6, pct));
            double s0 = s.price;
            double sigmaBefore = s.sigma;
            double spot = NewsModel.apply(p, s, pct, sigma);
            double[] band = PriceModelV3.band(p, env, s.price);
            s.price = PriceModelV3.clamp(spot, band[0], band[1]);
            jumps[i] = Math.abs(Math.log(s.price / s0));
            peaks[i] = s.sigma / sigmaBefore;
            double fairMove = Math.log(s.fair / s0);
            for (int t = 0; t < 360; t++) {
                PriceModelV3.step(p, env, s, rng, out);
                postVarSum += out.dlog * out.dlog;
                postN++;
            }
            absorbs[i] = fairMove == 0 ? 1 : (Math.log(s.price / s0)) / fairMove;
        }
        Arrays.sort(jumps);
        Arrays.sort(peaks);
        NewsResult n = new NewsResult();
        n.jumpMedian = jumps[paths / 2];
        n.jumpP95 = jumps[(int) (paths * 0.95D)];
        n.sigmaPeakRatioMax = peaks[paths - 1];
        n.sigmaPeakRatioMedian = peaks[paths / 2];
        n.vol30mRatio = Math.sqrt(postVarSum / postN) / baseTick;
        Arrays.sort(absorbs);
        n.absorb30m = absorbs[paths / 2];
        return n;
    }

    /** 冲击：返回 {0.02·depth, 0.20·depth} 的冲击幅度。 */
    public static double[] impacts(PriceParams p, Stock st) {
        double sigma = st.sigma();
        return new double[] {
            ImpactModel.magnitude(p, 0.02D * st.depth, st.depth, sigma),
            ImpactModel.magnitude(p, 0.20D * st.depth, st.depth, sigma)};
    }

    // ---------------------------------------------------------------- 统计小工具

    static double std(double[] a) {
        double m = 0;
        for (double v : a) {
            m += v;
        }
        m /= a.length;
        double s = 0;
        for (double v : a) {
            s += (v - m) * (v - m);
        }
        return Math.sqrt(s / (a.length - 1));
    }

    /** 把每条路径按 k 个 tick 聚合成不重叠收益后，求一阶自相关（可选取绝对值）。 */
    static double lag1(double[][] r, int k, boolean abs) {
        int paths = r.length;
        int n = r[0].length / k;
        double[][] agg = new double[paths][n];
        double mean = 0;
        long cnt = 0;
        for (int i = 0; i < paths; i++) {
            for (int j = 0; j < n; j++) {
                double sum = 0;
                for (int t = 0; t < k; t++) {
                    sum += r[i][j * k + t];
                }
                agg[i][j] = abs ? Math.abs(sum) : sum;
                mean += agg[i][j];
                cnt++;
            }
        }
        mean /= cnt;
        double var = 0;
        double cov = 0;
        for (int i = 0; i < paths; i++) {
            for (int j = 0; j < n; j++) {
                double d = agg[i][j] - mean;
                var += d * d;
                if (j > 0) {
                    cov += d * (agg[i][j - 1] - mean);
                }
            }
        }
        return cov / var;
    }

    static double kurtosis(double[][] r, int k) {
        int n = r[0].length / k;
        double m2 = 0;
        double m4 = 0;
        long cnt = 0;
        for (double[] row : r) {
            for (int j = 0; j < n; j++) {
                double sum = 0;
                for (int t = 0; t < k; t++) {
                    sum += row[j * k + t];
                }
                m2 += sum * sum;
                m4 += sum * sum * sum * sum;
                cnt++;
            }
        }
        m2 /= cnt;
        m4 /= cnt;
        return m4 / (m2 * m2);
    }

    // ---------------------------------------------------------------- 打印

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.3f%%", v * 100.0D);
    }

    public static void printReport(PriceParams p, int paths, long seed) {
        Stock[] stocks = {BRCK, FARM, GOLD, VOID};
        int ticks = (int) Math.round(7200.0D / p.dtSec);
        System.out.println("== v3 基线（无成交、无新闻）：" + paths + " 条 x " + ticks + " tick（2 小时，dt="
                + p.dtSec + "s）==");
        System.out.println(String.format(Locale.ROOT, "%-5s %-7s %-9s %-9s %-9s %-9s %-9s %-8s %-8s %-8s",
                "股票", "σ_hour", "中位|r|", "p99|r|", "2h std", "回撤中位", "回撤p95", "触±12%", "末/初", "σ均比"));
        for (Stock st : stocks) {
            Baseline b = baseline(p, st, paths, ticks, seed);
            System.out.println(String.format(Locale.ROOT, "%-5s %-7.4f %-9s %-9s %-9s %-9s %-9s %-8s %-8.4f %-8.3f",
                    b.symbol, b.sigma, pct(b.medianAbsTick), pct(b.p99AbsTick), pct(b.retStd),
                    pct(b.maxDdMedian), pct(b.maxDdP95), pct(b.touchFrac), b.medianEndRatio, b.meanSigmaRatio));
        }
        System.out.println();
        System.out.println(String.format(Locale.ROOT, "%-5s %-9s %-9s %-9s %-9s %-9s %-9s %-9s %-9s %-9s",
                "股票", "r lag1@5s", "r@1m", "r@5m", "r@15m", "|r|@5s", "|r|@1m", "|r|@5m", "5m峰度", "取整变价率"));
        for (Stock st : stocks) {
            Baseline b = baseline(p, st, paths, ticks, seed);
            System.out.println(String.format(Locale.ROOT, "%-5s %-9.4f %-9.4f %-9.4f %-9.4f %-9.4f %-9.4f %-9.4f %-9.2f %-9s",
                    b.symbol, b.lag1Tick, b.lag1Min1, b.lag1Min5, b.lag1Min15, b.lag1Abs, b.absLag1Min1,
                    b.absLag1Min5, b.kurtosis5m, pct(b.displayChangeFrac)));
        }
        System.out.println();
        System.out.println("== 24 小时压力（日内收益 std / 触日限比例 / 日内平均振幅）==");
        for (Stock st : stocks) {
            double[] d = dayStress(p, st, Math.max(40, paths / 4), seed + 7);
            System.out.println(String.format(Locale.ROOT, "%-5s std=%s 触限=%s 振幅=%s", st.symbol, pct(d[0]), pct(d[1]), pct(d[2])));
        }
        System.out.println();
        System.out.println("== 新闻（模板 x0.35 封顶 8，再夹 autoNewsMaxImpact=6）==");
        for (Stock st : stocks) {
            NewsResult n = news(p, st, paths, seed + 13);
            System.out.println(String.format(Locale.ROOT,
                    "%-5s 现价跳动 中位=%s p95=%s  新闻后σ抬升倍数 中位=%.3f 最大=%.3f  后30分钟实现波动/目标=%.2f  30分钟追上公允比例中位=%.2f",
                    st.symbol, pct(n.jumpMedian), pct(n.jumpP95), n.sigmaPeakRatioMedian, n.sigmaPeakRatioMax,
                    n.vol30mRatio, n.absorb30m));
        }
        System.out.println();
        System.out.println("== 成交冲击 ==");
        for (Stock st : stocks) {
            double[] im = impacts(p, st);
            System.out.println(String.format(Locale.ROOT, "%-5s 0.02 x depth -> %s ; 0.20 x depth -> %s (cap %s)",
                    st.symbol, pct(im[0]), pct(im[1]), pct(p.impactCap)));
        }
    }

    public static void main(String[] args) throws Exception {
        int paths = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261110L;
        PriceParams p = new PriceParams();
        for (int i = 2; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            java.lang.reflect.Field f = PriceParams.class.getField(kv[0]);
            f.setDouble(p, Double.parseDouble(kv[1]));
        }
        printReport(p, paths, seed);
    }
}
