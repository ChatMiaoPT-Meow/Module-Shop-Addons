package cn.autoforged.module_shop_stock.market.model;

/**
 * 做市盘口数学（v3）：价差跟瞬时 σ、库存偏斜、跨档加权成交价。纯函数，无 Minecraft 依赖。
 */
public final class BookMath {
    private BookMath() {
    }

    /** 相对半价差（小数）。取 {@code max(minSpread, spreadCoef × σ × √horizon)}。 */
    public static double halfSpreadFraction(PriceParams p, double sigmaHour) {
        return Math.max(p.minSpread, p.spreadCoef * sigmaHour * Math.sqrt(p.spreadHorizonHours));
    }

    /** 盘口档距（星币，至少 1）。 */
    public static long step(PriceParams p, long mid, double sigmaHour) {
        return Math.max(1L, Math.round(mid * halfSpreadFraction(p, sigmaHour)));
    }

    /**
     * 库存偏斜：趋势向上 → 买盘厚、卖盘薄；现价高于公允价 → 卖盘厚、买盘薄。结果限制在 ±skewCap。
     */
    public static double skew(PriceParams p, double mu, double price, double fair) {
        double gap = fair > 0.0D ? (price - fair) / fair : 0.0D;
        return PriceModelV3.clamp(p.skewMuCoef * mu - p.skewGapCoef * gap, -p.skewCap, p.skewCap);
    }

    /**
     * 市价单加权成交价。第 1 档视作贴着现价成交（小单与旧行为一致，无价差成本），
     * 之后逐档按各档价格累计；吃穿五档的剩余量按最远一档再滑点 {@code bookExtSlippage}。
     *
     * @param prices  买一..买五 或 卖一..卖五 的价格
     * @param volumes 对应挂单量
     */
    public static long weightedFill(PriceParams p, long lastPrice, long[] prices, long[] volumes, boolean buying,
            long quantity) {
        if (quantity <= 0L) {
            return lastPrice;
        }
        double cost = 0.0D;
        long remaining = quantity;
        long lastLevelPrice = lastPrice;
        for (int i = 0; i < prices.length && remaining > 0L; i++) {
            long levelPrice = i == 0 ? lastPrice : (prices[i] > 0L ? prices[i] : lastLevelPrice);
            long vol = Math.max(1L, volumes[i]);
            long take = Math.min(remaining, vol);
            cost += (double) take * levelPrice;
            remaining -= take;
            lastLevelPrice = prices[i] > 0L ? prices[i] : levelPrice;
        }
        if (remaining > 0L) {
            double ext = lastLevelPrice * (buying ? 1.0D + p.bookExtSlippage : 1.0D - p.bookExtSlippage);
            cost += remaining * ext;
        }
        long avg = Math.round(cost / quantity);
        return buying ? Math.max(lastPrice, avg) : Math.max(1L, Math.min(lastPrice, avg));
    }
}
