package cn.autoforged.module_shop_stock.market.model;

/**
 * 玩家成交冲击（v3）：平方根冲击，永久部分进公允价、临时部分进现价。纯函数，无 Minecraft 依赖。
 */
public final class ImpactModel {
    private ImpactModel() {
    }

    /**
     * 单笔冲击幅度（小数，如 0.01 = 1%）。
     *
     * <p>{@code impact = coef × √(σ·σRef) × √(qty / (depth × advFrac))}，再封顶 {@code impactCap}。
     * σ 取 0.5 次幂（几何平均到参考波动），让低波股与高波股的冲击落在同一个量级窗口。
     */
    public static double magnitude(PriceParams p, double quantity, double depth, double sigmaHour) {
        if (quantity <= 0.0D) {
            return 0.0D;
        }
        double adv = Math.max(depth * p.impactAdvFrac, 1.0D);
        double x = quantity / adv;
        double sig = Math.sqrt(Math.max(1e-6D, sigmaHour) * p.impactSigmaRef);
        return Math.min(p.impactCap, p.impactCoef * sig * Math.sqrt(x));
    }

    /**
     * 把冲击打进状态，返回现价的精确新值（未夹紧）。
     *
     * @param sigmaBase 个股基础小时波动（σ 微抬的上限参照）
     */
    public static double apply(PriceParams p, PriceModelV3.State s, double impact, boolean buySide, double sigmaBase) {
        double sign = buySide ? 1.0D : -1.0D;
        if (!(s.fair > 0.0D)) {
            s.fair = s.price;
        }
        s.fair = Math.max(1.0D, s.fair * (1.0D + sign * p.impactPermanentShare * impact));
        double spot = Math.max(1.0D, s.price * (1.0D + sign * impact));
        s.mu = PriceModelV3.clamp(s.mu + sign * impact * p.impactMu, -p.muCap, p.muCap);
        double sigma = s.sigma > 0.0D ? s.sigma : sigmaBase;
        s.sigma = Math.min(sigmaBase * p.sigmaMaxRatio, sigma * (1.0D + impact * p.impactSigmaGain));
        return spot;
    }
}
