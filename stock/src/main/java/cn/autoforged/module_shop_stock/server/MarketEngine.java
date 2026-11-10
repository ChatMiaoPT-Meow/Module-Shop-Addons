package cn.autoforged.module_shop_stock.server;

import cn.autoforged.module_shop_stock.StockMarketConfig;
import cn.autoforged.module_shop_stock.data.StockMarketSavedData;
import cn.autoforged.module_shop_stock.market.Order;
import cn.autoforged.module_shop_stock.market.Quote;
import cn.autoforged.module_shop_stock.market.StockDef;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Random;
import net.minecraft.server.MinecraftServer;

/**
 * 行情时钟：日切、高波动价格演化、做市商订单簿刷新、多空限价单撮合、空单强平巡检与指数重算。
 */
public final class MarketEngine {
    private static final Random RANDOM = new Random();
    private static long nextUpdateAt;

    private MarketEngine() {
    }

    public static void reset() {
        nextUpdateAt = 0L;
    }

    public static void tick(MinecraftServer server) {
        NewsEngine.tick(server);

        long now = System.currentTimeMillis();
        long interval = StockMarketConfig.UPDATE_INTERVAL_SECONDS.get() * 1000L;
        if (nextUpdateAt == 0L) {
            nextUpdateAt = now + interval;
            return;
        }
        if (now < nextUpdateAt) {
            return;
        }
        nextUpdateAt = now + interval;

        StockMarketSavedData data = StockMarketSavedData.get(server);
        rollDayIfNeeded(data);
        StockMarketConfig.syncBarLimits(data.quotes().values());

        long nowSecond = now / 1000L;
        int barSeconds = StockMarketConfig.BAR_SECONDS.get();
        int barLimit = StockMarketConfig.BAR_LIMIT.get();
        boolean open = sessionOpen();

        for (StockDef def : data.defs().values()) {
            Quote quote = quoteOf(data, def);
            if (!open || !def.enabled || def.halted) {
                quote.sample(nowSecond, barSeconds, barLimit);
                MarketMakerEngine.rebuildOrderBook(data, def, quote);
                continue;
            }
            evolveAndSample(data, def, quote, nowSecond, barSeconds, barLimit);
        }

        TradeService.matchAll(server, data);
        TradeService.checkShortLiquidations(server, data);
        TradeService.expireOrders(server, data);
        TradeService.retryPendingPayouts(data);
        recomputeIndex(data);

        data.revision++;
        data.markDirty();
        StockServer.broadcastMarket(server, data);
    }

    static void evolveAndSample(StockMarketSavedData data, StockDef def, Quote quote, long nowSecond,
            int barSeconds, int barLimit) {
        double mult = StockMarketConfig.VOLATILITY_MULTIPLIER.get();
        double baseSigma = Math.max(0.004D, def.volatility * mult * 0.55D);

        if (RANDOM.nextDouble() < 0.14D) {
            quote.trend = (RANDOM.nextDouble() - 0.5D) * baseSigma * 0.95D;
        } else {
            quote.trend = quote.trend * 0.86D + RANDOM.nextGaussian() * baseSigma * 0.18D;
        }

        double deviation = (def.basePrice - quote.price) / (double) Math.max(1L, def.basePrice);
        double revertStrength = StockMarketConfig.MEAN_REVERSION_BPS.get() / 10_000.0D;
        if (Math.abs(deviation) > 0.60D) {
            revertStrength *= 2.4D;
        }
        double revert = deviation * revertStrength;

        double effVol = baseSigma * (1.0D + Math.min(2.5D, Math.max(0.0D, quote.volCluster))
                + Math.max(0.0D, quote.newsBoost));

        double tailFactor = RANDOM.nextDouble() < 0.09D ? (2.1D + RANDOM.nextDouble() * 0.7D) : 1.0D;
        double shock = RANDOM.nextGaussian() * effVol * tailFactor;
        double move = shock + quote.trend + revert;

        long prev = quote.price;
        long rawNext = Math.round(prev * (1.0D + move));
        if (rawNext == prev && Math.abs(move) > 0.003D) {
            rawNext = prev + (move > 0.0D ? 1L : -1L);
        }
        long next = clamp(def, quote, rawNext);
        quote.moveTo(next);

        long hiBase = Math.max(prev, next);
        long loBase = Math.min(prev, next);
        long wickUp = Math.max(0L, Math.round(prev * effVol * (0.20D + Math.abs(RANDOM.nextGaussian()) * 0.65D)));
        long wickDn = Math.max(0L, Math.round(prev * effVol * (0.20D + Math.abs(RANDOM.nextGaussian()) * 0.65D)));
        long wickHigh = clamp(def, quote, hiBase + wickUp);
        long wickLow = clamp(def, quote, Math.max(1L, loBase - wickDn));
        long simVolume = Math.max(5L, Math.round((25.0D + Math.abs(move) * 1800.0D) * (0.6D + RANDOM.nextDouble() * 0.8D)));

        quote.sampleWithWick(nowSecond, barSeconds, barLimit, wickHigh, wickLow, simVolume);
        MarketMakerEngine.rebuildOrderBook(data, def, quote);

        double realizedRatio = Math.abs(move) / Math.max(0.002D, baseSigma);
        quote.volCluster = Math.min(2.5D, quote.volCluster * 0.78D + Math.max(0.0D, realizedRatio - 0.75D) * 0.38D);
        quote.newsBoost *= 0.86D;
        if (quote.newsBoost < 0.01D) {
            quote.newsBoost = 0.0D;
        }
    }

    static void applyImpact(StockMarketSavedData data, StockDef def, Quote quote, long quantity, boolean buySide) {
        if (quantity <= 0L) {
            return;
        }
        double depth = Math.max(1.0D, def.depth);
        double impact = Math.min(0.045D, quantity / depth * 0.012D);
        long next = Math.round(quote.price * (buySide ? 1.0D + impact : 1.0D - impact));
        quote.moveTo(clamp(def, quote, next));
        quote.trend += (buySide ? 1.0D : -1.0D) * impact * 0.25D;
        MarketMakerEngine.rebuildOrderBook(data, def, quote);
    }

    static long clamp(StockDef def, Quote quote, long price) {
        long min = lowerBound(def, quote);
        long max = upperBound(def, quote);
        return Math.max(min, Math.min(max, Math.max(1L, price)));
    }

    static long lowerBound(StockDef def, Quote quote) {
        return Math.max(1L, Math.max(StockMarketConfig.MIN_PRICE.get(), def.basePrice / 4L));
    }

    static long upperBound(StockDef def, Quote quote) {
        long max = Math.min(StockMarketConfig.MAX_PRICE.get(), def.basePrice * 4L);
        return Math.max(lowerBound(def, quote), max);
    }

    public static Quote quoteOf(StockMarketSavedData data, StockDef def) {
        return data.ensureQuote(def, StockMarketConfig.MIN_PRICE.get(), StockMarketConfig.MAX_PRICE.get());
    }

    static void rollDayIfNeeded(StockMarketSavedData data) {
        long today = LocalDate.now().toEpochDay();
        if (data.dayKey == today) {
            return;
        }
        data.dayKey = today;
        for (Quote quote : data.quotes().values()) {
            quote.rollDay();
        }
        data.markDirty();
    }

    public static boolean sessionOpen() {
        if (StockMarketConfig.SESSION_ALWAYS_OPEN.get()) {
            return true;
        }
        int minute = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        int open = StockMarketConfig.SESSION_OPEN_MINUTE.get();
        int close = StockMarketConfig.SESSION_CLOSE_MINUTE.get();
        if (open <= close) {
            return minute >= open && minute < close;
        }
        return minute >= open || minute < close;
    }

    static void recomputeIndex(StockMarketSavedData data) {
        long sum = 0L;
        int count = 0;
        for (StockDef def : data.defs().values()) {
            if (!def.enabled) {
                continue;
            }
            Quote quote = data.quote(def.symbol);
            if (quote == null) {
                continue;
            }
            sum += quote.price * 10_000L / Math.max(1L, def.basePrice);
            count++;
        }
        if (count == 0) {
            data.index = 1000L;
            return;
        }
        data.index = Math.max(1L, sum / count * 1000L / 10_000L);
        data.pushIndex(data.index, 90);
    }

    static boolean canMatch() {
        return StockMarketConfig.ENABLED.get() && sessionOpen();
    }

    public static long nowMillis() {
        return System.currentTimeMillis();
    }

    static int openOrderCount(StockMarketSavedData data, String symbol) {
        int count = 0;
        for (Order order : data.orders()) {
            if (order.state == Order.State.OPEN && order.symbol.equals(symbol)) {
                count++;
            }
        }
        return count;
    }
}
