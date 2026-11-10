package cn.autoforged.module_shop_stock.server;

import cn.autoforged.module_shop_stock.data.StockMarketSavedData;
import cn.autoforged.module_shop_stock.market.Order;
import cn.autoforged.module_shop_stock.market.Quote;
import cn.autoforged.module_shop_stock.StockMarketConfig;
import cn.autoforged.module_shop_stock.market.StockDef;
import cn.autoforged.module_shop_stock.market.model.BookMath;
import cn.autoforged.module_shop_stock.market.model.PriceParams;
import java.util.List;
import java.util.Random;

/**
 * 模拟做市商（Market Maker）与五档限价订单簿（Order Book）合成引擎。
 *
 * <p>围绕当前均衡价动态铺设买一~买五（Bids）、卖一~卖五（Asks）流动性阶梯，
 * 并将所有玩家的未成交限价挂单（买多/平空归入买盘，平多/卖空归入卖盘）实时叠加进订单簿。
 * 当做市商盘口与玩家挂单交叉、或做市商主动吃单时，触发真实撮合成交。
 */
public final class MarketMakerEngine {
    private static final Random RANDOM = new Random();

    private MarketMakerEngine() {
    }

    /** 重建单只股票的五档买卖盘口（做市商流动性 + 玩家真实限价单聚合）。 */
    public static void rebuildOrderBook(StockMarketSavedData data, StockDef def, Quote quote) {
        long mid = Math.max(1L, quote.price);
        boolean v3 = MarketEngine.isV3();
        PriceParams params = v3 ? StockMarketConfig.priceParams() : null;
        long step;
        double skewBidMul;
        double skewAskMul;
        if (v3) {
            // v3：价差跟瞬时 σ（未初始化时用标定值）；库存偏斜由趋势与"现价-公允价"决定
            double sigma = quote.sigma > 0.0D ? quote.sigma : MarketEngine.sigmaBase(def);
            step = BookMath.step(params, mid, sigma);
            double fair = quote.fairPrice > 0.0D ? quote.fairPrice : quote.price;
            double skew = BookMath.skew(params, quote.mu, quote.price, fair);
            skewBidMul = 1.0D + skew;
            skewAskMul = 1.0D - skew;
        } else {
            // 价差步长：高价股或高波动时刻步长自动放宽
            step = Math.max(1L, Math.round(mid * Math.max(0.004D, MarketEngine.legacyVolatility(def) * 0.14D)));
            skewBidMul = quote.trend >= 0.0D ? 1.18D : 0.86D;
            skewAskMul = quote.trend < 0.0D ? 1.18D : 0.86D;
        }
        long baseDepth = Math.max(25L, def.depth / 22L);
        List<Order> openOrders = data == null ? List.of() : data.openOrdersOf(def.symbol);

        for (int i = 0; i < Quote.BOOK_LEVELS; i++) {
            long bidP = Math.max(1L, mid - (i + 1L) * step);
            long askP = Math.max(mid + 1L, mid + (i + 1L) * step);
            if (i > 0 && bidP >= quote.bidPrices[i - 1]) {
                bidP = Math.max(1L, quote.bidPrices[i - 1] - 1L);
            }
            if (i > 0 && askP <= quote.askPrices[i - 1]) {
                askP = quote.askPrices[i - 1] + 1L;
            }
            quote.bidPrices[i] = bidP;
            quote.askPrices[i] = askP;

            // 做市商在各档位提供的动态挂单量（随趋势偏向与档位深度微调）
            double skewBid = skewBidMul;
            double skewAsk = skewAskMul;
            double levelWeight = 0.75D + i * 0.22D + RANDOM.nextDouble() * 0.35D;
            long mmBidVol = Math.max(10L, Math.round(baseDepth * levelWeight * skewBid));
            long mmAskVol = Math.max(10L, Math.round(baseDepth * levelWeight * skewAsk));

            // 叠加玩家在该档位附近的真实限价挂单
            long playerBidVol = 0L;
            long playerAskVol = 0L;
            for (Order order : openOrders) {
                if (order.state != Order.State.OPEN) {
                    continue;
                }
                long rem = order.remaining();
                if (rem <= 0L) {
                    continue;
                }
                if (order.isBidOrder()) {
                    if (Math.abs(order.price - bidP) <= Math.max(0L, step / 2L)) {
                        playerBidVol += rem;
                    }
                } else {
                    if (Math.abs(order.price - askP) <= Math.max(0L, step / 2L)) {
                        playerAskVol += rem;
                    }
                }
            }
            quote.bidVolumes[i] = mmBidVol + playerBidVol;
            quote.askVolumes[i] = mmAskVol + playerAskVol;
        }
    }

    /** 市价买单/平空单：优先按卖一（Ask 1）做市商最优报价成交，大单吃穿多档产生真实滑点。 */
    public static long bestExecutionPrice(Quote quote, boolean buying, long quantity) {
        if (MarketEngine.isV3()) {
            PriceParams params = StockMarketConfig.priceParams();
            return buying
                    ? BookMath.weightedFill(params, quote.price, quote.askPrices, quote.askVolumes, true, quantity)
                    : BookMath.weightedFill(params, quote.price, quote.bidPrices, quote.bidVolumes, false, quantity);
        }
        if (buying) {
            long ask1 = quote.askPrices[0] > 0L ? quote.askPrices[0] : quote.price;
            long ask1Vol = Math.max(1L, quote.askVolumes[0]);
            if (quantity <= ask1Vol) {
                return quote.price;
            }
            // 大单跨档吃盘口
            long ask2 = quote.askPrices[1] > 0L ? quote.askPrices[1] : ask1 + 1L;
            return Math.max(quote.price, (ask1 + ask2) / 2L);
        } else {
            long bid1 = quote.bidPrices[0] > 0L ? quote.bidPrices[0] : quote.price;
            long bid1Vol = Math.max(1L, quote.bidVolumes[0]);
            if (quantity <= bid1Vol) {
                return quote.price;
            }
            long bid2 = quote.bidPrices[1] > 0L ? quote.bidPrices[1] : Math.max(1L, bid1 - 1L);
            return Math.min(quote.price, (bid1 + bid2) / 2L);
        }
    }
}
