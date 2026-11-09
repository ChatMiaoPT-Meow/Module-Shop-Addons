package cn.autoforged.module_shop_stock.client;

import cn.autoforged.module_shop_stock.network.StockMarketNetwork;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/** 客户端缓存：24+ 股票定义、五档买卖盘口、多空双向持仓、委托、自动快讯与 K 线滚轮缩放状态。 */
public final class StockMarketState {
    public static final int DEFAULT_ZOOM_BARS = 32;
    public static final int MIN_ZOOM_BARS = 8;
    public static final int MAX_ZOOM_BARS = 120;
    public static final int BOOK_LEVELS = 5;

    public static final class Def {
        public String symbol = "";
        public String name = "";
        public String nameKey = "";
        public String sector = "";
        public long base;
        public double volatility;
        public long depth;
        public boolean enabled = true;
        public boolean halted;

        public String label() {
            if (!this.name.isEmpty()) {
                return this.name;
            }
            return this.nameKey.isEmpty() ? this.symbol : Component.translatable(this.nameKey).getString();
        }
    }

    public static final class Quote {
        public String symbol = "";
        public long price;
        public long previous;
        public long prevClose;
        public long dayOpen;
        public long dayHigh;
        public long dayLow;
        public long volume;
        public long[] bidPrices = new long[BOOK_LEVELS];
        public long[] bidVolumes = new long[BOOK_LEVELS];
        public long[] askPrices = new long[BOOK_LEVELS];
        public long[] askVolumes = new long[BOOK_LEVELS];

        public long changeBps() {
            long base = this.prevClose > 0L ? this.prevClose : this.previous;
            return base <= 0L ? 0L : Math.round((this.price - base) * 10_000.0D / base);
        }
    }

    public static final class Position {
        public long quantity;
        public long locked;
        public long cost;
        public long shortQuantity;
        public long shortLocked;
        public long shortProceeds;
        public long shortMargin;

        public long available() {
            return Math.max(0L, this.quantity - this.locked);
        }

        public long averageCost() {
            return this.quantity <= 0L ? 0L : this.cost / this.quantity;
        }

        public long availableShort() {
            return Math.max(0L, this.shortQuantity - this.shortLocked);
        }

        public long averageShortPrice() {
            return this.shortQuantity <= 0L ? 0L : this.shortProceeds / this.shortQuantity;
        }

        public boolean isEmpty() {
            return this.quantity <= 0L && this.shortQuantity <= 0L;
        }
    }

    public static final class OrderView {
        public long id;
        public String symbol = "";
        public boolean buy;
        public String action = "BUY";
        public boolean market;
        public long price;
        public long quantity;
        public long filled;
        public String state = "OPEN";
        public long expireAt;
    }

    public static final class BoardRow {
        public String name = "";
        public long value;
        public long cost;
        public long pct;
        public boolean me;
    }

    public static final class AdminStock {
        public String symbol = "";
        public String name = "";
        public String sector = "";
        public long base;
        public double volatility;
        public long depth;
        public boolean enabled;
        public boolean halted;
        public int holders;
        public int orders;
        public long price;
    }

    public static final class AuditRow {
        public long time;
        public String actor = "";
        public String action = "";
        public String detail = "";
    }

    public static final class Chart {
        public String symbol = "";
        public int barSeconds = 60;
        public long[] time = new long[0];
        public long[] open = new long[0];
        public long[] high = new long[0];
        public long[] low = new long[0];
        public long[] close = new long[0];
        public long[] volume = new long[0];

        public boolean isEmpty() {
            return this.time.length == 0;
        }
    }

    private static final List<Def> DEFS = new ArrayList<>();
    private static final Map<String, Quote> QUOTES = new LinkedHashMap<>();
    private static final Map<String, Position> POSITIONS = new LinkedHashMap<>();
    private static final List<OrderView> ORDERS = new ArrayList<>();
    private static final Set<String> WATCH = new LinkedHashSet<>();
    private static final List<BoardRow> BOARD = new ArrayList<>();
    private static final List<AdminStock> ADMIN_STOCKS = new ArrayList<>();
    private static final List<AuditRow> AUDIT = new ArrayList<>();

    public static Chart chart = new Chart();

    public static String selected = "";
    public static String query = "";
    public static String quantity = "1";
    public static String limitPrice = "";
    public static boolean limitMode;
    public static long balance = -1L;
    public static long index = 1000L;
    public static long indexPrev = 1000L;
    public static long fund;
    public static long revision;
    public static long lastSnapshotAt;
    public static int maxTradeQuantity = 1000;
    public static int maxOpenOrders = 12;
    public static long maxPosition = 100_000L;
    public static int feeBps = 30;
    public static int barSeconds = 60;
    public static long minFee = 1L;
    public static boolean enabled = true;
    public static boolean session = true;
    public static boolean admin;
    public static boolean hasSnapshot;
    public static String currency = "";

    public static String newsSymbol = "";
    public static int newsImpact;
    public static String newsText = "";
    public static long newsAt;
    /** 最近新闻（最新在前）。 */
    public static final List<News> NEWS_FEED = new ArrayList<>();

    public record News(String symbol, int impact, String text, long at) {
        public String banner() {
            Def d = def(symbol);
            String label = d != null ? d.label() : symbol;
            return "●【" + label + "】" + (impact >= 0 ? "+" : "") + impact + "% " + text;
        }
    }

    /** 滚动播报用：最近 2 小时内的新闻，最多 10 条。 */
    public static List<News> tickerNews() {
        List<News> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (News n : NEWS_FEED) {
            if (out.size() >= 10) {
                break;
            }
            if (n.at() <= 0L || now - n.at() < 7_200_000L) {
                out.add(n);
            }
        }
        return out;
    }

    public static float zoomBars = DEFAULT_ZOOM_BARS;
    public static int targetZoomBars = DEFAULT_ZOOM_BARS;
    public static int panBars;

    private static boolean pending;
    private static long pendingAt;
    private static long pendingNonce;
    private static long messageAt;
    private static String messageKey = "";
    private static Object[] messageArgs = new Object[0];
    private static boolean messageError;

    private StockMarketState() {
    }

    public static void reset() {
        DEFS.clear();
        QUOTES.clear();
        POSITIONS.clear();
        ORDERS.clear();
        WATCH.clear();
        BOARD.clear();
        ADMIN_STOCKS.clear();
        AUDIT.clear();
        chart = new Chart();
        selected = "";
        quantity = "1";
        limitPrice = "";
        limitMode = false;
        balance = -1L;
        index = 1000L;
        indexPrev = 1000L;
        fund = 0L;
        maxOpenOrders = 12;
        maxPosition = 100_000L;
        barSeconds = 60;
        revision = 0L;
        lastSnapshotAt = 0L;
        hasSnapshot = false;
        pending = false;
        messageKey = "";
        messageAt = 0L;
        currency = "";
        admin = false;
        newsSymbol = "";
        newsImpact = 0;
        newsText = "";
        newsAt = 0L;
        NEWS_FEED.clear();
        resetZoom();
    }

    public static void zoomChart(double wheelAmount) {
        if (wheelAmount == 0.0D) {
            return;
        }
        double factor = wheelAmount > 0.0D ? 0.82D : 1.22D;
        int next = (int) Math.round(targetZoomBars * factor);
        if (next == targetZoomBars) {
            next += wheelAmount > 0.0D ? -2 : 2;
        }
        targetZoomBars = Math.max(MIN_ZOOM_BARS, Math.min(MAX_ZOOM_BARS, next));
    }

    public static void resetZoom() {
        targetZoomBars = DEFAULT_ZOOM_BARS;
        zoomBars = DEFAULT_ZOOM_BARS;
        panBars = 0;
    }

    public static float zoomRatio() {
        return DEFAULT_ZOOM_BARS / Math.max(1.0F, zoomBars);
    }

    public static boolean hasActiveNews() {
        return !newsText.isEmpty() && (newsAt <= 0L || System.currentTimeMillis() - newsAt < 300_000L);
    }

    public static String formattedNewsBanner() {
        if (newsText.isEmpty()) {
            return "";
        }
        Def d = def(newsSymbol);
        String label = d != null ? d.label() : newsSymbol;
        String sign = (newsImpact >= 0 ? "+" : "") + newsImpact + "%";
        return "●【" + label + "】" + sign + " " + newsText;
    }

    public static List<Def> defs() {
        return DEFS;
    }

    public static Def def(String symbol) {
        for (Def def : DEFS) {
            if (def.symbol.equals(symbol)) {
                return def;
            }
        }
        return null;
    }

    public static List<Quote> quotes() {
        return new ArrayList<>(QUOTES.values());
    }

    public static Quote quote(String symbol) {
        return QUOTES.get(symbol);
    }

    public static Quote selectedQuote() {
        return QUOTES.get(selected);
    }

    public static Def selectedDef() {
        return def(selected);
    }

    public static Position position(String symbol) {
        return POSITIONS.getOrDefault(symbol, new Position());
    }

    public static Map<String, Position> positions() {
        return POSITIONS;
    }

    public static List<OrderView> orders() {
        return ORDERS;
    }

    public static List<OrderView> openOrders() {
        List<OrderView> out = new ArrayList<>();
        for (OrderView order : ORDERS) {
            if ("OPEN".equals(order.state)) {
                out.add(order);
            }
        }
        return out;
    }

    public static Set<String> watch() {
        return WATCH;
    }

    public static List<BoardRow> board() {
        return BOARD;
    }

    public static List<AdminStock> adminStocks() {
        return ADMIN_STOCKS;
    }

    public static List<AuditRow> audit() {
        return AUDIT;
    }

    public static List<String> visibleSymbols() {
        List<String> watched = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        for (Def def : DEFS) {
            if (!needle.isEmpty()) {
                String haystack = (def.symbol + " " + def.label() + " " + def.sector).toLowerCase(Locale.ROOT);
                if (!haystack.contains(needle)) {
                    continue;
                }
            }
            if (WATCH.contains(def.symbol)) {
                watched.add(def.symbol);
            } else {
                rest.add(def.symbol);
            }
        }
        watched.addAll(rest);
        return watched;
    }

    /** 持仓总净值（多头市值 + 空头保证金净值）。 */
    public static long marketValue() {
        long total = 0L;
        for (Map.Entry<String, Position> entry : POSITIONS.entrySet()) {
            total = saturatedAdd(total, positionValue(entry.getKey()));
        }
        return total;
    }

    public static long totalAssets() {
        return saturatedAdd(Math.max(0L, balance), marketValue());
    }

    public static long profit() {
        long total = 0L;
        for (Map.Entry<String, Position> entry : POSITIONS.entrySet()) {
            total = saturatedAdd(total, positionProfit(entry.getKey()));
        }
        return total;
    }

    public static long positionValue(String symbol) {
        Quote quote = QUOTES.get(symbol);
        Position pos = position(symbol);
        if (quote == null) {
            return 0L;
        }
        long longVal = pos.quantity > 0L ? saturatedMultiply(quote.price, pos.quantity) : 0L;
        long shortVal = 0L;
        if (pos.shortQuantity > 0L) {
            long coverCost = saturatedMultiply(quote.price, pos.shortQuantity);
            shortVal = Math.max(0L, pos.shortMargin + (pos.shortProceeds - coverCost));
        }
        return saturatedAdd(longVal, shortVal);
    }

    public static long positionProfit(String symbol) {
        Quote quote = QUOTES.get(symbol);
        Position pos = position(symbol);
        if (quote == null) {
            return 0L;
        }
        long pnl = 0L;
        if (pos.quantity > 0L) {
            pnl += saturatedMultiply(quote.price, pos.quantity) - pos.cost;
        }
        if (pos.shortQuantity > 0L) {
            pnl += pos.shortProceeds - saturatedMultiply(quote.price, pos.shortQuantity);
        }
        return pnl;
    }

    public static void requestSnapshot() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "snapshot");
        StockMarketNetwork.sendToServer(tag);
    }

    public static void heartbeat() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "heartbeat");
        StockMarketNetwork.sendToServer(tag);
    }

    public static void requestChart(String symbol) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "chart");
        tag.putString("symbol", symbol);
        StockMarketNetwork.sendToServer(tag);
    }

    public static void requestBoard() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "board");
        StockMarketNetwork.sendToServer(tag);
    }

    public static void requestAdmin() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "admin");
        StockMarketNetwork.sendToServer(tag);
    }

    public static void select(String symbol) {
        if (def(symbol) != null) {
            selected = symbol;
            limitPrice = "";
            panBars = 0;
        }
    }

    public static void toggleWatch(String symbol) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "watch");
        tag.putString("symbol", symbol);
        StockMarketNetwork.sendToServer(tag);
    }

    public static boolean submit(boolean buy) {
        return submitSide(buy ? "buy" : "sell");
    }

    /** 提交四向委托：side 取值为 "buy"（买多）、"sell"（平多）、"short"（卖空）、"cover"（平空）。 */
    public static boolean submitSide(String side) {
        if (isPending()) {
            return false;
        }
        Def def = selectedDef();
        Quote quote = selectedQuote();
        long amount = parseLong(quantity, -1L);
        if (def == null || quote == null || amount <= 0L || amount > maxTradeQuantity) {
            setMessage("msg.stock.invalid_quantity", true, maxTradeQuantity);
            return false;
        }
        long price = limitMode ? parseLong(limitPrice, -1L) : quote.price;
        if (price <= 0L) {
            setMessage("msg.stock.limit_invalid", true, 1, quote.price);
            return false;
        }
        pending = true;
        pendingAt = System.currentTimeMillis();
        messageKey = "";
        pendingNonce = ThreadLocalRandom.current().nextLong();
        if (pendingNonce == 0L) {
            pendingNonce = 1L;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "trade");
        tag.putString("side", side);
        tag.putString("kind", limitMode ? "limit" : "market");
        tag.putString("symbol", def.symbol);
        tag.putLong("quantity", amount);
        tag.putLong("price", price);
        tag.putLong("expectedPrice", quote.price);
        tag.putLong("nonce", pendingNonce);
        StockMarketNetwork.sendToServer(tag);
        return true;
    }

    public static void cancel(long orderId) {
        if (isPending()) {
            return;
        }
        pending = true;
        pendingAt = System.currentTimeMillis();
        pendingNonce = ThreadLocalRandom.current().nextLong();
        if (pendingNonce == 0L) {
            pendingNonce = 1L;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "cancel");
        tag.putLong("orderId", orderId);
        tag.putLong("nonce", pendingNonce);
        StockMarketNetwork.sendToServer(tag);
    }

    public static void adminAction(String action, CompoundTag payload) {
        if (!admin || isPending()) {
            return;
        }
        pending = true;
        pendingAt = System.currentTimeMillis();
        pendingNonce = ThreadLocalRandom.current().nextLong();
        if (pendingNonce == 0L) {
            pendingNonce = 1L;
        }
        payload.putString("type", "adminAction");
        payload.putString("action", action);
        payload.putLong("nonce", pendingNonce);
        StockMarketNetwork.sendToServer(payload);
    }

    public static boolean isPending() {
        if (pending && System.currentTimeMillis() - pendingAt > 5000L) {
            pending = false;
        }
        return pending;
    }

    public static Component message() {
        return messageKey.isEmpty() ? Component.empty() : Component.translatable(messageKey, messageArgs);
    }

    public static boolean messageError() {
        return messageError;
    }

    public static long messageAt() {
        return messageAt;
    }

    public static String currency(String fallback) {
        return currency.isEmpty() ? fallback : currency;
    }

    public static void apply(CompoundTag tag) {
        switch (tag.getString("type")) {
            case "snapshot" -> applySnapshot(tag);
            case "market" -> applyMarket(tag);
            case "chart" -> applyChart(tag);
            case "result" -> applyResult(tag);
            case "board" -> applyBoard(tag);
            case "admin" -> applyAdmin(tag);
            default -> {
            }
        }
    }

    private static void applySnapshot(CompoundTag tag) {
        applyMarket(tag);
        hasSnapshot = true;
        admin = tag.getBoolean("admin");
        balance = tag.getLong("balance");
        currency = tag.getString("currency");
        fund = tag.getLong("fund");
        maxTradeQuantity = Math.max(1, tag.getInt("maxTradeQuantity"));
        maxOpenOrders = Math.max(1, tag.getInt("maxOpenOrders"));
        maxPosition = Math.max(1L, tag.getLong("maxPosition"));
        feeBps = Math.max(0, tag.getInt("feeBps"));
        barSeconds = Math.max(1, tag.getInt("barSeconds"));
        minFee = Math.max(0L, tag.getLong("minFee"));

        DEFS.clear();
        ListTag defs = tag.getList("defs", Tag.TAG_COMPOUND);
        for (int i = 0; i < defs.size(); i++) {
            CompoundTag entry = defs.getCompound(i);
            Def def = new Def();
            def.symbol = entry.getString("symbol");
            def.name = entry.getString("name");
            def.nameKey = entry.getString("nameKey");
            def.sector = entry.getString("sector");
            def.base = entry.getLong("base");
            def.volatility = entry.getDouble("vol");
            def.depth = entry.getLong("depth");
            def.enabled = entry.getBoolean("enabled");
            def.halted = entry.getBoolean("halted");
            DEFS.add(def);
        }

        POSITIONS.clear();
        ListTag positions = tag.getList("positions", Tag.TAG_COMPOUND);
        for (int i = 0; i < positions.size(); i++) {
            CompoundTag entry = positions.getCompound(i);
            Position position = new Position();
            position.quantity = entry.getLong("quantity");
            position.locked = entry.getLong("locked");
            position.cost = entry.getLong("cost");
            position.shortQuantity = entry.getLong("shortQuantity");
            position.shortLocked = entry.getLong("shortLocked");
            position.shortProceeds = entry.getLong("shortProceeds");
            position.shortMargin = entry.getLong("shortMargin");
            POSITIONS.put(entry.getString("symbol"), position);
        }

        ORDERS.clear();
        ListTag orders = tag.getList("orders", Tag.TAG_COMPOUND);
        for (int i = 0; i < orders.size(); i++) {
            CompoundTag entry = orders.getCompound(i);
            OrderView order = new OrderView();
            order.id = entry.getLong("id");
            order.symbol = entry.getString("symbol");
            order.buy = entry.getBoolean("buy");
            order.action = entry.contains("action") ? entry.getString("action") : (order.buy ? "BUY" : "SELL");
            order.market = entry.getBoolean("market");
            order.price = entry.getLong("price");
            order.quantity = entry.getLong("quantity");
            order.filled = entry.getLong("filled");
            order.state = entry.getString("state");
            order.expireAt = entry.getLong("expireAt");
            ORDERS.add(order);
        }

        WATCH.clear();
        ListTag watch = tag.getList("watch", Tag.TAG_STRING);
        for (int i = 0; i < watch.size(); i++) {
            WATCH.add(watch.getString(i));
        }

        if (selected.isEmpty() || def(selected) == null) {
            selected = DEFS.isEmpty() ? "" : DEFS.get(0).symbol;
        }
        if (!limitMode && selectedQuote() != null) {
            limitPrice = String.valueOf(selectedQuote().price);
        }
        lastSnapshotAt = System.currentTimeMillis();
    }

    private static void applyMarket(CompoundTag tag) {
        long next = tag.getLong("revision");
        if (next != 0L && next < revision) {
            return;
        }
        revision = next;
        enabled = tag.getBoolean("enabled");
        session = tag.getBoolean("session");
        index = tag.getLong("index");
        indexPrev = tag.getLong("indexPrev");
        if (tag.contains("newsText")) {
            newsSymbol = tag.getString("newsSymbol");
            newsImpact = tag.getInt("newsImpact");
            newsText = tag.getString("newsText");
            newsAt = tag.getLong("newsAt");
        }
        if (tag.contains("newsFeed")) {
            NEWS_FEED.clear();
            ListTag feed = tag.getList("newsFeed", Tag.TAG_COMPOUND);
            for (int i = 0; i < feed.size(); i++) {
                CompoundTag n = feed.getCompound(i);
                NEWS_FEED.add(new News(n.getString("symbol"), n.getInt("impact"), n.getString("text"), n.getLong("at")));
            }
        }
        ListTag quotes = tag.getList("quotes", Tag.TAG_COMPOUND);
        for (int i = 0; i < quotes.size(); i++) {
            CompoundTag entry = quotes.getCompound(i);
            String symbol = entry.getString("symbol");
            Quote quote = QUOTES.get(symbol);
            if (quote == null) {
                quote = new Quote();
                quote.symbol = symbol;
                QUOTES.put(symbol, quote);
            }
            quote.price = entry.getLong("price");
            quote.previous = entry.getLong("prev");
            quote.volume = entry.getLong("volume");
            copyBookArray(entry.getLongArray("bp"), quote.bidPrices);
            copyBookArray(entry.getLongArray("bv"), quote.bidVolumes);
            copyBookArray(entry.getLongArray("ap"), quote.askPrices);
            copyBookArray(entry.getLongArray("av"), quote.askVolumes);
            if (entry.contains("prevClose")) {
                quote.prevClose = entry.getLong("prevClose");
                quote.dayOpen = entry.getLong("dayOpen");
                quote.dayHigh = entry.getLong("dayHigh");
                quote.dayLow = entry.getLong("dayLow");
            }
            if (quote.prevClose <= 0L) {
                quote.prevClose = quote.previous;
            }
        }
        if (def(selected) == null) {
            selected = DEFS.isEmpty() ? "" : DEFS.get(0).symbol;
        }
    }

    private static void copyBookArray(long[] src, long[] dst) {
        if (src == null) {
            return;
        }
        int len = Math.min(src.length, dst.length);
        System.arraycopy(src, 0, dst, 0, len);
    }

    private static void applyChart(CompoundTag tag) {
        Chart next = new Chart();
        next.symbol = tag.getString("symbol");
        next.barSeconds = Math.max(1, tag.getInt("barSeconds"));
        next.time = tag.getLongArray("t");
        next.open = tag.getLongArray("o");
        next.high = tag.getLongArray("h");
        next.low = tag.getLongArray("l");
        next.close = tag.getLongArray("c");
        next.volume = tag.getLongArray("v");
        chart = next;
        Quote q = QUOTES.get(next.symbol);
        if (q != null && tag.contains("bp")) {
            copyBookArray(tag.getLongArray("bp"), q.bidPrices);
            copyBookArray(tag.getLongArray("bv"), q.bidVolumes);
            copyBookArray(tag.getLongArray("ap"), q.askPrices);
            copyBookArray(tag.getLongArray("av"), q.askVolumes);
        }
    }

    private static void applyResult(CompoundTag tag) {
        long nonce = tag.getLong("nonce");
        if (pending && nonce != pendingNonce) {
            return;
        }
        pending = false;
        boolean ok = tag.getBoolean("ok");
        ListTag args = tag.getList("args", Tag.TAG_STRING);
        Object[] values = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) {
            values[i] = args.getString(i);
        }
        setMessage(tag.getString("message"), !ok, values);
    }

    private static void applyBoard(CompoundTag tag) {
        BOARD.clear();
        ListTag rows = tag.getList("rows", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            CompoundTag entry = rows.getCompound(i);
            BoardRow row = new BoardRow();
            row.name = entry.getString("name");
            row.value = entry.getLong("value");
            row.cost = entry.getLong("cost");
            row.pct = entry.getLong("pct");
            row.me = entry.getBoolean("me");
            BOARD.add(row);
        }
        fund = tag.getLong("fund");
    }

    private static void applyAdmin(CompoundTag tag) {
        ADMIN_STOCKS.clear();
        ListTag stocks = tag.getList("stocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < stocks.size(); i++) {
            CompoundTag entry = stocks.getCompound(i);
            AdminStock stock = new AdminStock();
            stock.symbol = entry.getString("symbol");
            stock.name = entry.getString("name");
            stock.sector = entry.getString("sector");
            stock.base = entry.getLong("base");
            stock.volatility = entry.getDouble("vol");
            stock.depth = entry.getLong("depth");
            stock.enabled = entry.getBoolean("enabled");
            stock.halted = entry.getBoolean("halted");
            stock.holders = entry.getInt("holders");
            stock.orders = entry.getInt("orders");
            stock.price = entry.getLong("price");
            ADMIN_STOCKS.add(stock);
        }
        AUDIT.clear();
        ListTag audit = tag.getList("audit", Tag.TAG_COMPOUND);
        for (int i = 0; i < audit.size(); i++) {
            CompoundTag entry = audit.getCompound(i);
            AuditRow row = new AuditRow();
            row.time = entry.getLong("time");
            row.actor = entry.getString("actor");
            row.action = entry.getString("action");
            row.detail = entry.getString("detail");
            AUDIT.add(row);
        }
    }

    private static void setMessage(String key, boolean error, Object... args) {
        messageKey = key == null ? "" : key;
        messageError = error;
        messageArgs = args;
        messageAt = System.currentTimeMillis();
    }

    public static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String percent(long bps) {
        return String.format(Locale.ROOT, "%+.2f%%", bps / 100.0D);
    }

    public static String shortPercent(long bps) {
        return String.format(Locale.ROOT, "%+.1f%%", bps / 100.0D);
    }

    public static String price(double value) {
        return String.format(Locale.ROOT, "%,.2f", value);
    }

    public static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text == null ? "" : text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long saturatedMultiply(long a, long b) {
        if (a <= 0L || b <= 0L) {
            return 0L;
        }
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }
}
