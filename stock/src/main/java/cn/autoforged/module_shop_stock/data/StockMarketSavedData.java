package cn.autoforged.module_shop_stock.data;

import cn.autoforged.module_shop_stock.StockMarket;
import cn.autoforged.module_shop_stock.StockMarketConfig;
import cn.autoforged.module_shop_stock.market.AuditEntry;
import cn.autoforged.module_shop_stock.market.Bar;
import cn.autoforged.module_shop_stock.market.Order;
import cn.autoforged.module_shop_stock.market.Position;
import cn.autoforged.module_shop_stock.market.Quote;
import cn.autoforged.module_shop_stock.market.StockDef;
import cn.autoforged.module_shop_stock.market.model.PriceModelV3;
import cn.autoforged.module_shop_stock.market.model.PriceParams;
import cn.autoforged.module_shop_stock.market.model.VolatilityMigration;
import cn.autoforged.module_shop_stock.server.MarketEngine;
import cn.autoforged.module_shop_stock.market.bars.BarCodec;
import cn.autoforged.module_shop_stock.market.bars.BarHistorySeeder;
import cn.autoforged.module_shop_stock.market.bars.BarPeriod;
import cn.autoforged.module_shop_stock.market.bars.BarSeries;
import cn.autoforged.module_shop_stock.server.MarketMakerEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 全部持久化状态：24+ 股票定义、行情、多空双向持仓、委托、自选、基金、指数、最新播报新闻、审计。
 */
public final class StockMarketSavedData extends SavedData {
    private static final String NAME = "module_shop_stock";
    private static final int DATA_VERSION = 3;
    /**
     * 区分同为 dataVersion 3 的本地 2.0.x / 2.2 / 2.3 存档；不是此值的存档首次加载前会被备份。
     * 同时也是"波动率已迁移到小时口径"的标记：只有读到非此值的存档才做一次 volatility 迁移，保存后写成此值。
     */
    public static final String FORMAT = "zip-2.3";
    /** 2.2 存档的 format；升级到 2.3 时备份为 pre-2.3。 */
    public static final String FORMAT_2_2 = "zip-2.2";
    private static final int AUDIT_LIMIT = 200;
    private static final int ORDER_HISTORY_LIMIT = 200;
    private static final int WATCH_LIMIT = 48;

    private final Map<String, StockDef> defs = new LinkedHashMap<>();
    private final Map<String, Quote> quotes = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Position>> positions = new LinkedHashMap<>();
    private final List<Order> orders = new ArrayList<>();
    private final Map<UUID, LinkedHashSet<String>> watchlist = new LinkedHashMap<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final List<Long> indexHistory = new ArrayList<>();
    /** 入账失败（经济与邮件都失败）的待补发金额，每个行情 tick 重试，保证金币不丢。 */
    private final Map<UUID, Long> pendingPayouts = new LinkedHashMap<>();

    public long revision;
    public long dayKey;
    public long fund;
    public long index = 1000L;
    public long nextOrderId = 1L;

    public long nextAutoNewsTick;
    public String lastNewsSymbol = "";
    public int lastNewsImpact;
    public String lastNewsText = "";
    public long lastNewsAt;
    /** 最近的新闻（最新在前，最多 20 条），供底栏滚动播报。 */
    public final java.util.ArrayDeque<CompoundTag> newsFeed = new java.util.ArrayDeque<>();

    public static StockMarketSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(StockMarketSavedData::new, StockMarketSavedData::load, null), NAME);
    }

    public void setLastNews(String symbol, int impact, String text, long timestamp) {
        this.lastNewsSymbol = symbol == null ? "" : symbol;
        this.lastNewsImpact = impact;
        this.lastNewsText = text == null ? "" : text;
        this.lastNewsAt = timestamp;
        CompoundTag item = new CompoundTag();
        item.putString("symbol", this.lastNewsSymbol);
        item.putInt("impact", impact);
        item.putString("text", this.lastNewsText);
        item.putLong("at", timestamp);
        this.newsFeed.addFirst(item);
        while (this.newsFeed.size() > 20) {
            this.newsFeed.removeLast();
        }
        markDirty();
    }

    public Map<String, StockDef> defs() {
        return this.defs;
    }

    /** 自动补齐全部 24 只内置股票定义（保留旧存档中已有的修改）。 */
    public void seedIfEmpty() {
        boolean changed = false;
        for (StockDef seed : StockMarket.SEED) {
            if (!this.defs.containsKey(seed.symbol)) {
                this.defs.put(seed.symbol, seed.copy());
                changed = true;
            }
        }
        ensureQuotesSeeded();
        if (this.dayKey == 0L) {
            this.dayKey = java.time.LocalDate.now().toEpochDay();
            changed = true;
        }
        if (changed) {
            markDirty();
        }
    }

    public List<StockDef> defList() {
        return new ArrayList<>(this.defs.values());
    }

    @Nullable
    public StockDef def(String symbol) {
        return symbol == null ? null : this.defs.get(symbol);
    }

    public void putDef(StockDef def) {
        this.defs.put(def.symbol, def);
        markDirty();
    }

    public void removeDef(String symbol) {
        this.defs.remove(symbol);
        this.quotes.remove(symbol);
        markDirty();
    }

    @Nullable
    public Quote quote(String symbol) {
        return symbol == null ? null : this.quotes.get(symbol);
    }

    public Map<String, Quote> quotes() {
        return this.quotes;
    }

    public Quote ensureQuote(StockDef def, long minPrice, long maxPrice) {
        Quote quote = this.quotes.get(def.symbol);
        if (quote == null) {
            long min = Math.max(1L, minPrice);
            long max = Math.max(min, maxPrice);
            long price = Math.max(min, Math.min(max, def.basePrice));
            quote = new Quote(price);
            seedBars(quote, def, price, def.symbol.hashCode());
            finishBars(quote, def, true, true);
            MarketMakerEngine.rebuildOrderBook(this, def, quote);
            this.quotes.put(def.symbol, quote);
            markDirty();
        } else if (quote.bidPrices[0] <= 0L || quote.askPrices[0] <= 0L) {
            MarketMakerEngine.rebuildOrderBook(this, def, quote);
        }
        return quote;
    }

    /** 新档种子历史：v3 走真实的 v3 价格过程；v2 回滚时沿用旧随机走。 */
    private static void seedBars(Quote quote, StockDef def, long basePrice, long seed) {
        if (!MarketEngine.isV3()) {
            seedBarsLegacy(quote, basePrice, MarketEngine.legacyVolatility(def), seed);
            return;
        }
        long now = System.currentTimeMillis() / 1000L;
        long alignedNow = now - Math.floorMod(now, 60L);
        PriceParams params = StockMarketConfig.priceParams();
        double sigmaBase = Math.max(1e-4D, def.volatility * StockMarketConfig.priceModelVolatilityMultiplier());
        long base = Math.max(1L, def.basePrice);
        PriceModelV3.History h = PriceModelV3.generateHistory(params, base, sigmaBase, def.depth,
                Math.max(1L, base / 4L), base * 4.0D, alignedNow, 60, 1440, seed);
        quote.bars.clear();
        quote.bars.addAll(h.bars);
        Bar last = h.bars.get(h.bars.size() - 1);
        Bar prev = h.bars.get(Math.max(0, h.bars.size() - 2));
        quote.price = last.close;
        quote.prevPrice = prev.close;
        quote.prevClose = h.prevClose;
        quote.dayOpen = h.dayOpen;
        quote.dayHigh = Math.max(h.dayHigh, quote.price);
        quote.dayLow = Math.min(h.dayLow, quote.price);
        quote.volume = h.volumeLastDay;
        quote.exact = h.state.price;
        quote.fairPrice = h.state.fair;
        quote.sigma = h.state.sigma;
        quote.mu = h.state.mu;
        quote.newsBoost = 0.0D;
        quote.trend = 0.0D;
        quote.volCluster = 0.0D;
    }

    private static final String KLINE_KEY = "kline";

    /**
     * 1m 就绪后的收尾：把 1m 聚合回填到空的粗周期，再给日/周补风格化种子（柱上带 seeded）。
     * {@code markSeeded}：1m 本身就是刚生成的种子；{@code clearCoarse}：丢弃现有粗周期重建（v1 迁移/新生成）。
     */
    private static void finishBars(Quote quote, @Nullable StockDef def, boolean markSeeded, boolean clearCoarse) {
        if (markSeeded) {
            for (Bar bar : quote.bars) {
                bar.seeded = true;
            }
        }
        if (clearCoarse) {
            for (BarPeriod period : BarPeriod.all()) {
                if (period != BarPeriod.M1) {
                    quote.book.series(period).clear();
                }
            }
        }
        quote.book.backfillFromSource();
        long sum = 0L;
        for (Bar bar : quote.bars) {
            sum += bar.volume;
        }
        long typical = quote.bars.isEmpty() ? 0L : sum / quote.bars.size() * 1440L;
        if (typical <= 0L) {
            typical = def == null ? 100_000L : Math.max(1_000L, def.depth * 100L);
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        long baseP = def == null ? quote.price : def.basePrice;
        double volP = def == null ? 0.04D : def.volatility;
        long symSeed = def == null ? 0L : def.symbol.hashCode();
        BarHistorySeeder.seedCoarse(quote.book, nowSec, quote.price, baseP, volP, typical, symSeed);
        BarHistorySeeder.seedIntradayCoarse(quote.book, nowSec, quote.price, baseP, volP, typical, symSeed);
    }

    private static CompoundTag saveKline(Quote quote) {
        CompoundTag kline = new CompoundTag();
        for (BarPeriod period : BarPeriod.all()) {
            BarSeries series = quote.book.series(period);
            if (series.isEmpty()) {
                continue;
            }
            BarCodec.Packed packed = BarCodec.pack(series);
            CompoundTag out = new CompoundTag();
            out.putLong("t0", packed.t0);
            out.putIntArray("dt", packed.dt);
            out.putIntArray("p", packed.ohlc);
            out.putLongArray("v", packed.vol);
            if (packed.flags != null) {
                out.putByteArray("f", packed.flags);
            }
            kline.put(period.id(), out);
        }
        return kline;
    }

    private static void loadKline(CompoundTag kline, Quote quote) {
        for (BarPeriod period : BarPeriod.all()) {
            if (!kline.contains(period.id(), Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag in = kline.getCompound(period.id());
            BarCodec.Packed packed = new BarCodec.Packed();
            packed.t0 = in.getLong("t0");
            packed.dt = in.getIntArray("dt");
            packed.ohlc = in.getIntArray("p");
            packed.vol = in.getLongArray("v");
            packed.flags = in.contains("f", Tag.TAG_BYTE_ARRAY) ? in.getByteArray("f") : null;
            BarSeries series = quote.book.series(period);
            series.clear();
            BarCodec.unpack(packed, series);
        }
    }

    private static void seedBarsLegacy(Quote quote, long basePrice, double volatility, long seed) {
        long now = System.currentTimeMillis() / 1000L;
        long alignedNow = now - Math.floorMod(now, 60L);
        quote.bars.clear();
        Random rng = new Random(seed ^ 0x5DEECE66DL);
        double cur = Math.max(2.0D, basePrice * (0.94D + rng.nextDouble() * 0.12D));
        double trend = (rng.nextDouble() - 0.48D) * volatility * 0.35D;
        double vol = Math.max(0.025D, volatility);
        long dayHigh = Math.round(cur);
        long dayLow = Math.round(cur);
        long totalVol = 0L;

        int count = 72;
        for (int i = 0; i < count; i++) {
            long barTime = alignedNow - (count - 1L - i) * 60L;
            long open = Math.max(1L, Math.round(cur));
            if (rng.nextDouble() < 0.18D) {
                trend = (rng.nextDouble() - 0.48D) * vol * 0.65D;
            }
            double revert = (basePrice - cur) / Math.max(1.0D, basePrice) * 0.045D;
            double shock = rng.nextGaussian() * vol * (rng.nextDouble() < 0.10D ? 2.1D : 1.0D);
            double pctMove = shock + trend + revert;
            cur = Math.max(Math.max(1.0D, basePrice * 0.30D), Math.min(basePrice * 3.50D, cur * (1.0D + pctMove)));
            long close = Math.max(1L, Math.round(cur));
            long bodyMax = Math.max(open, close);
            long bodyMin = Math.min(open, close);
            long wickUp = Math.max(1L, Math.round(cur * vol * (0.25D + Math.abs(rng.nextGaussian()) * 0.75D)));
            long wickDn = Math.max(1L, Math.round(cur * vol * (0.25D + Math.abs(rng.nextGaussian()) * 0.75D)));
            long high = bodyMax + wickUp;
            long low = Math.max(1L, bodyMin - wickDn);
            long barVol = Math.max(20L, Math.round((120.0D + Math.abs(pctMove) * 4200.0D) * (0.6D + rng.nextDouble() * 0.9D)));

            quote.bars.add(new Bar(barTime, open, high, low, close, barVol));
            if (i >= count - 24) {
                dayHigh = Math.max(dayHigh, high);
                dayLow = Math.min(dayLow, low);
                totalVol += barVol;
            }
        }
        Bar last = quote.bars.get(quote.bars.size() - 1);
        Bar prev = quote.bars.get(Math.max(0, quote.bars.size() - 2));
        Bar dayStart = quote.bars.get(Math.max(0, quote.bars.size() - 24));
        quote.price = last.close;
        quote.prevPrice = prev.close;
        quote.prevClose = dayStart.open;
        quote.dayOpen = dayStart.open;
        quote.dayHigh = Math.max(dayHigh, quote.price);
        quote.dayLow = Math.min(dayLow, quote.price);
        quote.volume = totalVol;
        quote.trend = trend;
        quote.volCluster = 0.25D;
        quote.exact = quote.price;
        quote.fairPrice = quote.price;
    }

    public Position position(UUID owner, String symbol) {
        return this.positions.computeIfAbsent(owner, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(symbol, ignored -> new Position());
    }

    @Nullable
    public Position positionOrNull(UUID owner, String symbol) {
        Map<String, Position> owned = this.positions.get(owner);
        return owned == null ? null : owned.get(symbol);
    }

    public Map<String, Position> positions(UUID owner) {
        return this.positions.getOrDefault(owner, Map.of());
    }

    public Map<UUID, Map<String, Position>> allPositions() {
        return this.positions;
    }

    public void dropPosition(UUID owner, String symbol) {
        Map<String, Position> owned = this.positions.get(owner);
        if (owned != null) {
            owned.remove(symbol);
            if (owned.isEmpty()) {
                this.positions.remove(owner);
            }
        }
        markDirty();
    }

    public int holderCount(String symbol) {
        int count = 0;
        for (Map<String, Position> owned : this.positions.values()) {
            Position position = owned.get(symbol);
            if (position != null && !position.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public long allocateOrderId() {
        long id = Math.max(1L, this.nextOrderId);
        this.nextOrderId = id + 1L;
        markDirty();
        return id;
    }

    public List<Order> orders() {
        return this.orders;
    }

    public List<Order> openOrders(UUID owner) {
        List<Order> out = new ArrayList<>();
        for (Order order : this.orders) {
            if (order.state == Order.State.OPEN && order.owner.equals(owner)) {
                out.add(order);
            }
        }
        return out;
    }

    public List<Order> openOrdersOf(String symbol) {
        List<Order> out = new ArrayList<>();
        for (Order order : this.orders) {
            if (order.state == Order.State.OPEN && order.symbol.equals(symbol)) {
                out.add(order);
            }
        }
        return out;
    }

    @Nullable
    public Order order(long id) {
        for (Order order : this.orders) {
            if (order.id == id) {
                return order;
            }
        }
        return null;
    }

    public List<Order> recentOrders(UUID owner, int limit) {
        List<Order> out = new ArrayList<>();
        for (int i = this.orders.size() - 1; i >= 0 && out.size() < limit; i--) {
            Order order = this.orders.get(i);
            if (order.owner.equals(owner)) {
                out.add(order);
            }
        }
        return out;
    }

    public Map<UUID, Long> pendingPayouts() {
        return this.pendingPayouts;
    }

    public void addPendingPayout(UUID owner, long amount) {
        if (owner == null || amount <= 0L) {
            return;
        }
        this.pendingPayouts.merge(owner, amount, (a, b) -> {
            long sum = a + b;
            return sum < 0L ? Long.MAX_VALUE : sum;
        });
        markDirty();
    }

    public void addOrder(Order order) {
        this.orders.add(order);
        pruneOrders();
        markDirty();
    }

    private void pruneOrders() {
        int finished = 0;
        for (Order order : this.orders) {
            if (order.state != Order.State.OPEN) {
                finished++;
            }
        }
        int drop = finished - ORDER_HISTORY_LIMIT;
        if (drop <= 0) {
            return;
        }
        java.util.Iterator<Order> iterator = this.orders.iterator();
        while (iterator.hasNext() && drop > 0) {
            if (iterator.next().state != Order.State.OPEN) {
                iterator.remove();
                drop--;
            }
        }
    }

    public Set<String> watchlist(UUID owner) {
        return this.watchlist.computeIfAbsent(owner, ignored -> new LinkedHashSet<>());
    }

    public java.util.Collection<LinkedHashSet<String>> watchlists() {
        return this.watchlist.values();
    }

    public boolean toggleWatch(UUID owner, String symbol) {
        Set<String> list = watchlist(owner);
        boolean added;
        if (list.contains(symbol)) {
            list.remove(symbol);
            added = false;
        } else if (list.size() < WATCH_LIMIT) {
            list.add(symbol);
            added = true;
        } else {
            added = false;
        }
        markDirty();
        return added;
    }

    public List<Long> indexHistory() {
        return this.indexHistory;
    }

    public void pushIndex(long value, int limit) {
        this.indexHistory.add(value);
        while (this.indexHistory.size() > Math.max(2, limit)) {
            this.indexHistory.remove(0);
        }
    }

    public List<AuditEntry> audit() {
        return this.audit;
    }

    public void addAudit(AuditEntry entry) {
        this.audit.add(entry);
        while (this.audit.size() > AUDIT_LIMIT) {
            this.audit.remove(0);
        }
        markDirty();
    }

    public void markDirty() {
        setDirty();
    }

    private static StockMarketSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        StockMarketSavedData data = new StockMarketSavedData();
        int version = tag.getInt("dataVersion");
        // 非 zip-2.3 的存档：个股 volatility 还是旧口径，读取时迁移一次；保存后 format 变成 zip-2.3，不会再迁。
        boolean migrateVol = !FORMAT.equals(tag.getString("format"));
        data.revision = tag.getLong("revision");
        data.dayKey = tag.getLong("dayKey");
        data.fund = Math.max(0L, tag.getLong("fund"));
        data.index = tag.contains("index") ? Math.max(1L, tag.getLong("index")) : 1000L;
        data.nextOrderId = Math.max(1L, tag.getLong("nextOrderId"));
        data.nextAutoNewsTick = tag.getLong("nextAutoNewsTick");
        data.lastNewsSymbol = tag.getString("lastNewsSymbol");
        data.lastNewsImpact = tag.getInt("lastNewsImpact");
        data.lastNewsText = tag.getString("lastNewsText");
        data.lastNewsAt = tag.getLong("lastNewsAt");
        ListTag feed = tag.getList("newsFeed", Tag.TAG_COMPOUND);
        for (int i = 0; i < feed.size() && i < 20; i++) {
            data.newsFeed.addLast(feed.getCompound(i).copy());
        }
        if (data.newsFeed.isEmpty() && data.lastNewsText != null && !data.lastNewsText.isEmpty()) {
            CompoundTag item = new CompoundTag();
            item.putString("symbol", data.lastNewsSymbol == null ? "" : data.lastNewsSymbol);
            item.putInt("impact", data.lastNewsImpact);
            item.putString("text", data.lastNewsText);
            item.putLong("at", data.lastNewsAt);
            data.newsFeed.addLast(item);
        }
        data.loadDefs(tag, migrateVol);
        data.loadQuotes(tag, migrateVol);
        if (migrateVol) {
            data.setDirty();
        }
        data.loadPositions(tag);
        data.loadOrders(tag);
        data.loadWatchlist(tag);
        data.loadAudit(tag);
        data.loadPendingPayouts(tag);
        for (long value : tag.getLongArray("indexHistory")) {
            if (value > 0L) {
                data.indexHistory.add(value);
            }
        }
        if (version < 2) {
            data.migrateFromV1(tag);
        }
        for (StockDef seed : StockMarket.SEED) {
            data.defs.computeIfAbsent(seed.symbol, ignored -> seed.copy());
        }
        data.ensureQuotesSeeded();
        return data;
    }

    private void loadDefs(CompoundTag tag, boolean migrateVol) {
        ListTag list = tag.getList("defs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String symbol = entry.getString("symbol");
            if (!StockMarket.isValidSymbol(symbol) || this.defs.containsKey(symbol)) {
                continue;
            }
            StockDef def = new StockDef(symbol, entry.getString("name"),
                    entry.contains("nameKey") ? entry.getString("nameKey") : null,
                    entry.getString("sector"),
                    Math.max(1L, entry.getLong("base")),
                    migrateVol ? VolatilityMigration.migrate(clampVolatility(entry.getDouble("vol")))
                            : clampVolatility(entry.getDouble("vol")),
                    loadDepth(entry));
            def.enabled = !entry.contains("enabled") || entry.getBoolean("enabled");
            def.halted = entry.getBoolean("halted");
            this.defs.put(symbol, def);
        }
    }

    private void loadQuotes(CompoundTag tag, boolean migrateVol) {
        ListTag list = tag.getList("quotes", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String symbol = entry.getString("symbol");
            if (!this.defs.containsKey(symbol)) {
                continue;
            }
            Quote quote = new Quote(Math.max(1L, entry.getLong("price")));
            quote.prevPrice = Math.max(1L, entry.getLong("prev"));
            quote.prevClose = Math.max(1L, entry.contains("prevClose") ? entry.getLong("prevClose") : quote.price);
            quote.dayOpen = Math.max(1L, entry.contains("dayOpen") ? entry.getLong("dayOpen") : quote.price);
            quote.dayHigh = Math.max(quote.price, entry.getLong("dayHigh"));
            quote.dayLow = Math.max(1L, entry.contains("dayLow") ? entry.getLong("dayLow") : quote.price);
            quote.volume = Math.max(0L, entry.getLong("volume"));
            quote.newsBoost = migrateVol ? 0.0D : entry.getDouble("newsBoost");
            quote.trend = entry.getDouble("trend");
            quote.volCluster = entry.getDouble("volCluster");
            // v3 字段：旧档缺失时 fair=price、exact=price、sigma=按标定后的个股波动、mu=0
            quote.exact = entry.contains("exact") ? Math.max(1.0D, entry.getDouble("exact")) : quote.price;
            quote.fairPrice = entry.contains("fair") ? Math.max(1.0D, entry.getDouble("fair")) : quote.price;
            StockDef quoteDef = this.defs.get(symbol);
            quote.sigma = entry.contains("sigma") ? Math.max(0.0D, entry.getDouble("sigma"))
                    : (quoteDef == null ? 0.0D : quoteDef.volatility);
            quote.mu = entry.contains("mu") ? entry.getDouble("mu") : 0.0D;
            StockDef def = quoteDef;
            boolean hasKline = entry.contains(KLINE_KEY, Tag.TAG_COMPOUND);
            if (hasKline) {
                loadKline(entry.getCompound(KLINE_KEY), quote);
            } else {
                // 2.2 及更早：单一 bars 列表，视为 1m；粗周期稍后从 1m 回填
                ListTag bars = entry.getList("bars", Tag.TAG_COMPOUND);
                for (int j = 0; j < bars.size(); j++) {
                    CompoundTag barTag = bars.getCompound(j);
                    Bar bar = new Bar(barTag.getLong("t"), Math.max(1L, barTag.getLong("o")));
                    bar.high = Math.max(bar.open, barTag.getLong("h"));
                    bar.low = Math.max(1L, barTag.contains("l") ? barTag.getLong("l") : bar.open);
                    bar.close = Math.max(1L, barTag.getLong("c"));
                    bar.volume = Math.max(0L, barTag.getLong("v"));
                    quote.bars.add(bar);
                }
            }
            if (quote.bars.isEmpty()) {
                if (def == null) { seedBarsLegacy(quote, quote.price, 0.04D, symbol.hashCode()); } else { seedBars(quote, def, quote.price, symbol.hashCode()); }
                finishBars(quote, def, true, !hasKline);
            } else if (!hasKline) {
                finishBars(quote, def, false, true);
            }
            this.quotes.put(symbol, quote);
        }
    }

    private void loadPositions(CompoundTag tag) {
        ListTag list = tag.getList("positions", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("owner")) {
                continue;
            }
            String symbol = entry.getString("symbol");
            long quantity = Math.max(0L, entry.getLong("quantity"));
            long shortQuantity = Math.max(0L, entry.getLong(entry.contains("shortQuantity") ? "shortQuantity" : "shortQty"));
            if (quantity <= 0L && shortQuantity <= 0L) {
                continue;
            }
            Position position = this.positions.computeIfAbsent(entry.getUUID("owner"), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(symbol, ignored -> new Position());
            position.quantity = quantity;
            position.locked = Math.max(0L, Math.min(quantity, entry.getLong("locked")));
            position.cost = Math.max(0L, entry.getLong("cost"));
            position.shortQuantity = shortQuantity;
            position.shortLocked = Math.max(0L, Math.min(shortQuantity, entry.getLong("shortLocked")));
            position.shortProceeds = Math.max(0L, entry.getLong(entry.contains("shortProceeds") ? "shortProceeds" : "shortCost"));
            position.shortMargin = Math.max(0L, entry.getLong("shortMargin"));
        }
    }

    private void loadOrders(CompoundTag tag) {
        ListTag list = tag.getList("orders", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("owner")) {
                continue;
            }
            Order order = new Order();
            order.id = entry.getLong("id");
            order.owner = entry.getUUID("owner");
            order.symbol = entry.getString("symbol");
            order.buy = entry.getBoolean("buy");
            order.action = parseAction(entry.getString("action"), order.buy, entry.getBoolean("short"));
            order.price = Math.max(1L, entry.getLong("price"));
            order.quantity = Math.max(0L, entry.getLong("quantity"));
            order.filled = Math.max(0L, entry.getLong("filled"));
            order.escrow = Math.max(0L, entry.getLong("escrow"));
            order.traded = Math.max(0L, entry.getLong("traded"));
            order.createdAt = entry.getLong("createdAt");
            order.expireAt = entry.getLong("expireAt");
            order.market = entry.getBoolean("market");
            String rawState = entry.getString("state");
            if ("REFUND_PENDING".equals(rawState)) {
                // 本地 2.0.1：退款失败待重试的单。现金类转入待补发；股数类直接解锁。
                if (order.action == Order.Action.BUY || order.action == Order.Action.SHORT) {
                    addPendingPayout(order.owner, order.escrow);
                } else {
                    Position pos = position(order.owner, order.symbol);
                    if (order.action == Order.Action.SELL) {
                        pos.locked = Math.max(0L, pos.locked - order.escrow);
                    } else {
                        pos.shortLocked = Math.max(0L, pos.shortLocked - order.escrow);
                    }
                }
                order.escrow = 0L;
                order.state = "EXPIRED".equals(entry.getString("refundTarget")) ? Order.State.EXPIRED : Order.State.CANCELLED;
            } else {
                order.state = parseState(rawState);
            }
            if (order.quantity > 0L) {
                this.orders.add(order);
            }
        }
    }

    private void loadWatchlist(CompoundTag tag) {
        ListTag list = tag.getList("watchlist", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("owner")) {
                continue;
            }
            this.watchlist.computeIfAbsent(entry.getUUID("owner"), ignored -> new LinkedHashSet<>())
                    .add(entry.getString("symbol"));
        }
    }

    private void loadAudit(CompoundTag tag) {
        ListTag list = tag.getList("audit", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            this.audit.add(new AuditEntry(entry.getLong("time"), entry.getString("actor"),
                    entry.getString("action"), entry.getString("detail")));
        }
    }

    private void migrateFromV1(CompoundTag tag) {
        for (StockDef seed : StockMarket.SEED) {
            this.defs.computeIfAbsent(seed.symbol, ignored -> seed.copy());
        }
        ListTag quoteList = tag.getList("quotes", Tag.TAG_COMPOUND);
        long now = System.currentTimeMillis() / 1000L;
        for (int i = 0; i < quoteList.size(); i++) {
            CompoundTag entry = quoteList.getCompound(i);
            String symbol = entry.getString("symbol");
            StockDef def = this.defs.get(symbol);
            if (def == null) {
                continue;
            }
            Quote quote = this.quotes.get(symbol);
            if (quote == null) {
                quote = new Quote(Math.max(1L, entry.getLong("price")));
                this.quotes.put(symbol, quote);
            }
            quote.price = Math.max(1L, entry.getLong("price"));
            quote.prevPrice = Math.max(1L, entry.getLong("previous"));
            quote.prevClose = quote.prevPrice;
            quote.dayOpen = quote.price;
            quote.dayHigh = Math.max(quote.price, quote.prevPrice);
            quote.dayLow = Math.min(quote.price, quote.prevPrice);
            long[] history = entry.getLongArray("history");
            if (history.length > 0) {
                quote.bars.clear();
                for (int j = 0; j < history.length; j++) {
                    long price = Math.max(1L, history[j]);
                    Bar bar = new Bar(now - (history.length - 1L - j) * 60L, price);
                    bar.close = price;
                    quote.bars.add(bar);
                }
                finishBars(quote, def, false, true);
            }
        }
    }

    private void ensureQuotesSeeded() {
        for (StockDef def : this.defs.values()) {
            Quote quote = this.quotes.get(def.symbol);
            if (quote == null) {
                quote = new Quote(def.basePrice);
                seedBars(quote, def, quote.price, def.symbol.hashCode());
                finishBars(quote, def, true, true);
                this.quotes.put(def.symbol, quote);
            } else {
                // 对已有存档自动补足分线/时线/日线种子深度（不改真实柱），确保旧档无需删档即可顺畅滚轮缩放
                finishBars(quote, def, false, false);
            }
            MarketMakerEngine.rebuildOrderBook(this, def, quote);
        }
    }

    private static Order.State parseState(String raw) {
        try {
            return Order.State.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return Order.State.CANCELLED;
        }
    }

    private void loadPendingPayouts(CompoundTag tag) {
        ListTag list = tag.getList("pendingPayouts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID("owner")) {
                addPendingPayout(entry.getUUID("owner"), entry.getLong("amount"));
            }
        }
    }

    /** zip 2.2 存 depth；本地 2.0.x 存 float（流通盘 = depth × 200）。 */
    private static long loadDepth(CompoundTag entry) {
        if (entry.contains("depth")) {
            return Math.max(1L, entry.getLong("depth"));
        }
        return Math.max(1L, entry.getLong("float") / 200L);
    }

    private static Order.Action parseAction(String raw, boolean fallbackBuy, boolean legacyShort) {
        if (raw != null && !raw.isEmpty()) {
            try {
                return Order.Action.valueOf(raw);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (legacyShort) {
            return fallbackBuy ? Order.Action.COVER : Order.Action.SHORT;
        }
        return fallbackBuy ? Order.Action.BUY : Order.Action.SELL;
    }

    private static double clampVolatility(double value) {
        if (Double.isNaN(value) || value <= 0.0D) {
            return 0.042D;
        }
        return Math.max(0.002D, Math.min(0.35D, value));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("dataVersion", DATA_VERSION);
        tag.putString("format", FORMAT);
        ListTag payoutList = new ListTag();
        for (Map.Entry<UUID, Long> payout : this.pendingPayouts.entrySet()) {
            if (payout.getValue() > 0L) {
                CompoundTag out = new CompoundTag();
                out.putUUID("owner", payout.getKey());
                out.putLong("amount", payout.getValue());
                payoutList.add(out);
            }
        }
        tag.put("pendingPayouts", payoutList);
        tag.putLong("revision", this.revision);
        tag.putLong("dayKey", this.dayKey);
        tag.putLong("fund", this.fund);
        tag.putLong("index", this.index);
        tag.putLong("nextOrderId", this.nextOrderId);
        tag.putLong("nextAutoNewsTick", this.nextAutoNewsTick);
        tag.putString("lastNewsSymbol", this.lastNewsSymbol);
        tag.putInt("lastNewsImpact", this.lastNewsImpact);
        tag.putString("lastNewsText", this.lastNewsText);
        tag.putLong("lastNewsAt", this.lastNewsAt);
        ListTag feedList = new ListTag();
        for (CompoundTag item : this.newsFeed) {
            feedList.add(item.copy());
        }
        tag.put("newsFeed", feedList);
        tag.putLongArray("indexHistory", this.indexHistory.stream().mapToLong(Long::longValue).toArray());

        ListTag defList = new ListTag();
        for (StockDef def : this.defs.values()) {
            CompoundTag out = new CompoundTag();
            out.putString("symbol", def.symbol);
            out.putString("name", def.name);
            if (def.nameKey != null) {
                out.putString("nameKey", def.nameKey);
            }
            out.putString("sector", def.sector);
            out.putLong("base", def.basePrice);
            out.putDouble("vol", def.volatility);
            out.putLong("depth", def.depth);
            out.putBoolean("enabled", def.enabled);
            out.putBoolean("halted", def.halted);
            defList.add(out);
        }
        tag.put("defs", defList);

        ListTag quoteList = new ListTag();
        for (Map.Entry<String, Quote> entry : this.quotes.entrySet()) {
            Quote quote = entry.getValue();
            CompoundTag out = new CompoundTag();
            out.putString("symbol", entry.getKey());
            out.putLong("price", quote.price);
            out.putLong("prev", quote.prevPrice);
            out.putLong("prevClose", quote.prevClose);
            out.putLong("dayOpen", quote.dayOpen);
            out.putLong("dayHigh", quote.dayHigh);
            out.putLong("dayLow", quote.dayLow);
            out.putLong("volume", quote.volume);
            out.putDouble("newsBoost", quote.newsBoost);
            out.putDouble("trend", quote.trend);
            out.putDouble("volCluster", quote.volCluster);
            out.putDouble("exact", quote.exact);
            out.putDouble("fair", quote.fairPrice);
            out.putDouble("sigma", quote.sigma);
            out.putDouble("mu", quote.mu);
            out.put(KLINE_KEY, saveKline(quote));
            quoteList.add(out);
        }
        tag.put("quotes", quoteList);

        ListTag positionList = new ListTag();
        for (Map.Entry<UUID, Map<String, Position>> owner : this.positions.entrySet()) {
            for (Map.Entry<String, Position> entry : owner.getValue().entrySet()) {
                Position position = entry.getValue();
                if (position.isEmpty()) {
                    continue;
                }
                CompoundTag out = new CompoundTag();
                out.putUUID("owner", owner.getKey());
                out.putString("symbol", entry.getKey());
                out.putLong("quantity", position.quantity);
                out.putLong("locked", Math.min(position.locked, position.quantity));
                out.putLong("cost", position.cost);
                out.putLong("shortQuantity", position.shortQuantity);
                out.putLong("shortLocked", Math.min(position.shortLocked, position.shortQuantity));
                out.putLong("shortProceeds", position.shortProceeds);
                out.putLong("shortMargin", position.shortMargin);
                positionList.add(out);
            }
        }
        tag.put("positions", positionList);

        ListTag orderList = new ListTag();
        for (Order order : this.orders) {
            CompoundTag out = new CompoundTag();
            out.putLong("id", order.id);
            out.putUUID("owner", order.owner);
            out.putString("symbol", order.symbol);
            out.putBoolean("buy", order.buy);
            out.putString("action", order.action.name());
            out.putLong("price", order.price);
            out.putLong("quantity", order.quantity);
            out.putLong("filled", order.filled);
            out.putLong("escrow", order.escrow);
            out.putLong("traded", order.traded);
            out.putLong("createdAt", order.createdAt);
            out.putLong("expireAt", order.expireAt);
            out.putBoolean("market", order.market);
            out.putString("state", order.state.name());
            orderList.add(out);
        }
        tag.put("orders", orderList);

        ListTag watchList = new ListTag();
        for (Map.Entry<UUID, LinkedHashSet<String>> entry : this.watchlist.entrySet()) {
            for (String symbol : entry.getValue()) {
                CompoundTag out = new CompoundTag();
                out.putUUID("owner", entry.getKey());
                out.putString("symbol", symbol);
                watchList.add(out);
            }
        }
        tag.put("watchlist", watchList);

        ListTag auditList = new ListTag();
        for (AuditEntry entry : this.audit) {
            CompoundTag out = new CompoundTag();
            out.putLong("time", entry.time);
            out.putString("actor", entry.actor);
            out.putString("action", entry.action);
            out.putString("detail", entry.detail);
            auditList.add(out);
        }
        tag.put("audit", auditList);
        return tag;
    }
}
