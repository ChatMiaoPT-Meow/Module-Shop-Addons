package cn.autoforged.module_shop_stock.market.model;

/**
 * 新闻冲击（v3）：先推公允价，现价只跟一部分，波动率与趋势温和抬升。纯函数，无 Minecraft 依赖。
 */
public final class NewsModel {
    private NewsModel() {
    }

    /** 自动新闻模板幅度缩放：整体 ×scale，下限 1，封顶 cap（保持符号）。 */
    public static int scaleTemplatePct(int pct, double scale, int cap) {
        if (pct == 0) {
            return 0;
        }
        int sign = pct > 0 ? 1 : -1;
        int scaled = (int) Math.round(Math.abs(pct) * scale);
        scaled = Math.max(1, Math.min(cap, scaled));
        return sign * scaled;
    }

    /**
     * 把 {@code pct}（百分比，可正可负）打进状态。返回现价的精确新值（尚未做日限/长期带夹紧，由调用方夹）。
     *
     * @param sigmaBase 个股基础小时波动（用于 σ 上限）
     */
    public static double apply(PriceParams p, PriceModelV3.State s, double pct, double sigmaBase) {
        double frac = pct / 100.0D;
        double sign = pct >= 0.0D ? 1.0D : -1.0D;
        if (!(s.fair > 0.0D)) {
            s.fair = s.price;
        }
        s.fair = Math.max(1.0D, s.fair * (1.0D + frac * p.newsFairShare));
        double spot = Math.max(1.0D, s.price * (1.0D + frac * p.newsSpotShare));
        double cap = sigmaBase * p.sigmaMaxRatio;
        double sigma = s.sigma > 0.0D ? s.sigma : sigmaBase;
        s.sigma = Math.min(cap, sigma * (1.0D + Math.abs(frac) * p.newsSigmaGain));
        s.mu = PriceModelV3.clamp(s.mu + sign * Math.min(p.muCap, Math.abs(frac) * p.newsMuGain),
                -p.muCap, p.muCap);
        s.newsBoost = Math.max(s.newsBoost, Math.abs(frac) * 0.8D);
        return spot;
    }
}
