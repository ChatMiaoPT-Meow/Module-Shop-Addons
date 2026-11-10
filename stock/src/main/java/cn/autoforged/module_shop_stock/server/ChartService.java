package cn.autoforged.module_shop_stock.server;

import cn.autoforged.module_shop_stock.StockMarketConfig;
import cn.autoforged.module_shop_stock.data.StockMarketSavedData;
import cn.autoforged.module_shop_stock.market.Bar;
import cn.autoforged.module_shop_stock.market.Quote;
import cn.autoforged.module_shop_stock.market.StockDef;
import cn.autoforged.module_shop_stock.market.bars.BarPeriod;
import cn.autoforged.module_shop_stock.market.bars.BarSeries;
import cn.autoforged.module_shop_stock.network.StockMarketNetwork;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * 多周期 K 线的按需下发：{@code type=chart} 带 {@code period}（缺省 1m，兼容旧客户端），一次只发所请求周期；
 * 同时记住每位玩家正在看的 (symbol, period)，行情广播时只给这些人附带最近两根柱（{@code cd}），其余人不发。
 */
public final class ChartService {
    /** 同 (symbol, period) 全量请求的最小间隔；换股/换周期不受此限。 */
    private static final long FULL_THROTTLE_MS = 500L;
    /** 任何 chart 请求之间的最小间隔，防刷。 */
    private static final long MIN_GAP_MS = 80L;
    /** 超过这个时间没有 chart 请求就停止推增量（客户端约每 15 秒重拉一次全量）。 */
    private static final long VIEW_TTL_MS = 45_000L;

    private static final class View {
        String symbol = "";
        BarPeriod period = BarPeriod.M1;
        long lastFullAt;
        long lastRequestAt;
    }

    private static final Map<UUID, View> VIEWS = new LinkedHashMap<>();

    private ChartService() {
    }

    public static void clear() {
        VIEWS.clear();
    }

    public static void handle(ServerPlayer player, StockMarketSavedData data, CompoundTag tag) {
        String symbol = tag.getString("symbol");
        StockDef def = data.def(symbol);
        if (def == null) {
            return;
        }
        BarPeriod period = BarPeriod.parse(tag.getString("period"));
        long now = System.currentTimeMillis();
        View view = VIEWS.computeIfAbsent(player.getUUID(), ignored -> new View());
        boolean changed = !symbol.equals(view.symbol) || period != view.period;
        if (now - view.lastRequestAt < MIN_GAP_MS || (!changed && now - view.lastFullAt < FULL_THROTTLE_MS)) {
            return;
        }
        view.symbol = symbol;
        view.period = period;
        view.lastFullAt = now;
        view.lastRequestAt = now;
        if (VIEWS.size() > 256) {
            VIEWS.entrySet().removeIf(entry -> now - entry.getValue().lastRequestAt > VIEW_TTL_MS);
        }
        sendFull(player, data, def, period);
    }

    private static void sendFull(ServerPlayer player, StockMarketSavedData data, StockDef def, BarPeriod period) {
        Quote quote = MarketEngine.quoteOf(data, def);
        BarSeries series = quote.book.series(period);
        CompoundTag out = new CompoundTag();
        out.putString("type", "chart");
        out.putString("symbol", def.symbol);
        out.putString("period", period.id());
        out.putInt("barSeconds", barSeconds(period));
        out.putInt("limit", series.limit());
        out.putInt("tz", quote.book.zone().getRules().getOffset(java.time.Instant.now()).getTotalSeconds());
        putBars(out, series, 0, series.size());
        out.putLongArray("bp", quote.bidPrices);
        out.putLongArray("bv", quote.bidVolumes);
        out.putLongArray("ap", quote.askPrices);
        out.putLongArray("av", quote.askVolumes);
        StockMarketNetwork.sendToPlayer(player, out);
    }

    /** 在行情广播里给正在看图的玩家附带当前周期最近两根柱（上一根可能被成交补过，一并带上）。 */
    public static void attachDelta(CompoundTag market, UUID player, StockMarketSavedData data) {
        View view = VIEWS.get(player);
        if (view == null || view.symbol.isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() - view.lastRequestAt > VIEW_TTL_MS) {
            VIEWS.remove(player);
            return;
        }
        Quote quote = data.quote(view.symbol);
        if (quote == null) {
            return;
        }
        BarSeries series = quote.book.series(view.period);
        if (series.isEmpty()) {
            return;
        }
        CompoundTag delta = new CompoundTag();
        delta.putString("symbol", view.symbol);
        delta.putString("period", view.period.id());
        delta.putInt("limit", series.limit());
        putBars(delta, series, Math.max(0, series.size() - 2), series.size());
        market.put("cd", delta);
    }

    private static int barSeconds(BarPeriod period) {
        return period == BarPeriod.M1 ? StockMarketConfig.BAR_SECONDS.get() : period.nominalSeconds();
    }

    private static void putBars(CompoundTag out, BarSeries series, int from, int to) {
        int size = Math.max(0, to - from);
        long[] time = new long[size];
        long[] open = new long[size];
        long[] high = new long[size];
        long[] low = new long[size];
        long[] close = new long[size];
        long[] volume = new long[size];
        byte[] seeded = new byte[size];
        boolean anySeeded = false;
        for (int i = 0; i < size; i++) {
            Bar bar = series.get(from + i);
            time[i] = bar.time;
            open[i] = bar.open;
            high[i] = bar.high;
            low[i] = bar.low;
            close[i] = bar.close;
            volume[i] = bar.volume;
            if (bar.seeded) {
                seeded[i] = 1;
                anySeeded = true;
            }
        }
        out.putLongArray("t", time);
        out.putLongArray("o", open);
        out.putLongArray("h", high);
        out.putLongArray("l", low);
        out.putLongArray("c", close);
        out.putLongArray("v", volume);
        if (anySeeded) {
            out.putByteArray("sd", seeded);
        }
    }
}
