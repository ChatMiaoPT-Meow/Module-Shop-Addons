package cn.autoforged.module_shop_stock.server;

import cn.autoforged.module_shop.api.ModuleShopApi;
import cn.autoforged.module_shop.api.view.BalanceView;
import cn.autoforged.module_shop_stock.StockMarketConfig;
import cn.autoforged.module_shop_stock.data.StockMarketSavedData;
import cn.autoforged.module_shop_stock.market.Bar;
import cn.autoforged.module_shop_stock.market.Order;
import cn.autoforged.module_shop_stock.market.Position;
import cn.autoforged.module_shop_stock.market.Quote;
import cn.autoforged.module_shop_stock.market.StockDef;
import cn.autoforged.module_shop_stock.network.StockMarketNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 请求路由与协议载荷（包含五档买卖盘口、多空双向委托与实时快讯同步）。
 */
public final class StockServer {
    private static final Map<UUID, Long> SUBSCRIBERS = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_SNAPSHOT = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_CHART = new LinkedHashMap<>();
    private static final long SUBSCRIBE_TTL_MS = 90_000L;

    private StockServer() {
    }

    private static boolean throttled(Map<UUID, Long> map, UUID player, long windowMs) {
        long now = System.currentTimeMillis();
        Long last = map.get(player);
        if (last != null && now - last < windowMs) {
            return true;
        }
        map.put(player, now);
        if (map.size() > 256) {
            map.entrySet().removeIf(entry -> now - entry.getValue() > 60_000L);
        }
        return false;
    }

    public static void handleRequest(ServerPlayer player, CompoundTag tag) {
        MinecraftServer server = player.server;
        StockMarketSavedData data = StockMarketSavedData.get(server);
        switch (tag.getString("type")) {
            case "snapshot" -> {
                subscribe(player);
                if (!throttled(LAST_SNAPSHOT, player.getUUID(), 300L)) {
                    sendSnapshot(player);
                }
            }
            case "chart" -> {
                if (!throttled(LAST_CHART, player.getUUID(), 400L)) {
                    sendChart(player, data, tag.getString("symbol"));
                }
            }
            case "trade" -> trade(player, data, tag);
            case "cancel" -> {
                TradeService.cancel(player, data, tag.getLong("orderId"), tag.getLong("nonce"));
                sendSnapshot(player);
            }
            case "watch" -> {
                String symbol = tag.getString("symbol");
                if (data.def(symbol) != null) {
                    data.toggleWatch(player.getUUID(), symbol);
                    data.revision++;
                    data.markDirty();
                }
                sendSnapshot(player);
            }
            case "board" -> sendBoard(player, data, server);
            case "admin" -> {
                if (player.hasPermissions(2)) {
                    sendAdminData(player, data);
                } else {
                    reply(player, 0L, false, "msg.stock.no_permission");
                }
            }
            case "adminAction" -> AdminService.handle(player, server, data, tag, tag.getLong("nonce"));
            case "heartbeat" -> subscribe(player);
            default -> {
            }
        }
    }

    private static void trade(ServerPlayer player, StockMarketSavedData data, CompoundTag tag) {
        String symbol = tag.getString("symbol");
        StockDef def = data.def(symbol);
        long nonce = tag.getLong("nonce");
        if (def == null) {
            reply(player, nonce, false, "msg.stock.unknown_symbol", symbol);
            sendSnapshot(player);
            return;
        }
        Quote quote = MarketEngine.quoteOf(data, def);
        Order.Action action = switch (tag.getString("side")) {
            case "sell" -> Order.Action.SELL;
            case "short" -> Order.Action.SHORT;
            case "cover" -> Order.Action.COVER;
            default -> Order.Action.BUY;
        };
        long quantity = tag.getLong("quantity");
        if ("limit".equals(tag.getString("kind"))) {
            TradeService.limit(player, data, def, quote, action, quantity, tag.getLong("price"), nonce);
        } else {
            if (tag.getLong("expectedPrice") != quote.price) {
                reply(player, nonce, false, "msg.stock.price_changed");
                sendSnapshot(player);
                return;
            }
            TradeService.market(player, data, def, quote, action, quantity, nonce);
        }
        sendSnapshot(player);
    }

    public static void sendSnapshot(ServerPlayer player) {
        MinecraftServer server = player.server;
        StockMarketSavedData data = StockMarketSavedData.get(server);
        CompoundTag out = baseTag("snapshot", data);
        out.putBoolean("admin", player.hasPermissions(2));
        out.putInt("maxTradeQuantity", StockMarketConfig.MAX_TRADE_QUANTITY.get());
        out.putInt("maxOpenOrders", StockMarketConfig.MAX_OPEN_ORDERS.get());
        out.putLong("maxPosition", StockMarketConfig.MAX_POSITION.get());
        out.putInt("feeBps", StockMarketConfig.FEE_BPS.get());
        out.putLong("minFee", StockMarketConfig.MIN_FEE.get());
        out.putLong("fund", data.fund);
        out.putInt("barSeconds", StockMarketConfig.BAR_SECONDS.get());

        BalanceView balance = ModuleShopApi.economy().balanceView(player.getUUID());
        out.putLong("balance", balance == null ? 0L : balance.balance());
        out.putString("currency", balance == null ? "" : balance.currencyName());

        out.put("defs", defsTag(data));
        out.put("quotes", quotesTag(data, false));
        out.put("positions", positionsTag(data, player.getUUID()));
        out.put("orders", ordersTag(data.recentOrders(player.getUUID(), 18)));
        ListTag watch = new ListTag();
        for (String symbol : data.watchlist(player.getUUID())) {
            watch.add(StringTag.valueOf(symbol));
        }
        out.put("watch", watch);
        StockMarketNetwork.sendToPlayer(player, out);
    }

    public static void broadcastMarket(MinecraftServer server, StockMarketSavedData data) {
        long now = System.currentTimeMillis();
        SUBSCRIBERS.entrySet().removeIf(entry -> now - entry.getValue() > SUBSCRIBE_TTL_MS);
        if (SUBSCRIBERS.isEmpty()) {
            return;
        }
        CompoundTag template = baseTag("market", data);
        template.put("quotes", quotesTag(data, true));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (SUBSCRIBERS.containsKey(player.getUUID())) {
                StockMarketNetwork.sendToPlayer(player, template.copy());
            }
        }
    }

    public static void afterAdminChange(MinecraftServer server, StockMarketSavedData data) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendSnapshot(player);
        }
        broadcastMarket(server, data);
    }

    private static void subscribe(ServerPlayer player) {
        SUBSCRIBERS.put(player.getUUID(), System.currentTimeMillis());
    }

    public static void clearSubscribers() {
        SUBSCRIBERS.clear();
    }

    private static void sendChart(ServerPlayer player, StockMarketSavedData data, String symbol) {
        StockDef def = data.def(symbol);
        if (def == null) {
            return;
        }
        Quote quote = MarketEngine.quoteOf(data, def);
        int size = quote.bars.size();
        long[] time = new long[size];
        long[] open = new long[size];
        long[] high = new long[size];
        long[] low = new long[size];
        long[] close = new long[size];
        long[] volume = new long[size];
        for (int i = 0; i < size; i++) {
            Bar bar = quote.bars.get(i);
            time[i] = bar.time;
            open[i] = bar.open;
            high[i] = bar.high;
            low[i] = bar.low;
            close[i] = bar.close;
            volume[i] = bar.volume;
        }
        CompoundTag out = new CompoundTag();
        out.putString("type", "chart");
        out.putString("symbol", symbol);
        out.putInt("barSeconds", StockMarketConfig.BAR_SECONDS.get());
        out.putLongArray("t", time);
        out.putLongArray("o", open);
        out.putLongArray("h", high);
        out.putLongArray("l", low);
        out.putLongArray("c", close);
        out.putLongArray("v", volume);
        out.putLongArray("bp", quote.bidPrices);
        out.putLongArray("bv", quote.bidVolumes);
        out.putLongArray("ap", quote.askPrices);
        out.putLongArray("av", quote.askVolumes);
        StockMarketNetwork.sendToPlayer(player, out);
    }

    private static void sendBoard(ServerPlayer player, StockMarketSavedData data, MinecraftServer server) {
        List<long[]> values = new ArrayList<>();
        List<UUID> owners = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Position>> entry : data.allPositions().entrySet()) {
            long cost = 0L;
            long value = 0L;
            for (Map.Entry<String, Position> held : entry.getValue().entrySet()) {
                Position position = held.getValue();
                if (position.isEmpty()) {
                    continue;
                }
                Quote quote = data.quote(held.getKey());
                if (quote != null) {
                    if (position.quantity > 0L) {
                        cost += position.cost;
                        value += TradeService.safeMultiply(quote.price, position.quantity);
                    }
                    if (position.shortQuantity > 0L) {
                        cost += position.shortMargin;
                        long coverCost = TradeService.safeMultiply(quote.price, position.shortQuantity);
                        value += Math.max(0L, position.shortMargin + (position.shortProceeds - coverCost));
                    }
                }
            }
            if (cost > 0L) {
                owners.add(entry.getKey());
                values.add(new long[]{value, cost});
            }
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < owners.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingLong((Integer i) -> {
            long[] pair = values.get(i);
            return pair[1] <= 0L ? Long.MIN_VALUE : (pair[0] - pair[1]) * 10_000L / pair[1];
        }).reversed());

        CompoundTag out = new CompoundTag();
        out.putString("type", "board");
        ListTag rows = new ListTag();
        int limit = Math.min(20, order.size());
        for (int i = 0; i < limit; i++) {
            int index = order.get(i);
            long[] pair = values.get(index);
            CompoundTag row = new CompoundTag();
            row.putString("name", nameOf(server, owners.get(index)));
            row.putLong("value", pair[0]);
            row.putLong("cost", pair[1]);
            row.putLong("pct", pair[1] <= 0L ? 0L : (pair[0] - pair[1]) * 10_000L / pair[1]);
            row.putBoolean("me", owners.get(index).equals(player.getUUID()));
            rows.add(row);
        }
        out.put("rows", rows);
        out.putLong("fund", data.fund);
        StockMarketNetwork.sendToPlayer(player, out);
    }

    private static void sendAdminData(ServerPlayer player, StockMarketSavedData data) {
        CompoundTag out = new CompoundTag();
        out.putString("type", "admin");
        ListTag stocks = new ListTag();
        for (StockDef def : data.defs().values()) {
            Quote quote = data.quote(def.symbol);
            CompoundTag item = new CompoundTag();
            item.putString("symbol", def.symbol);
            item.putString("name", def.name);
            item.putString("sector", def.sector);
            item.putLong("base", def.basePrice);
            item.putDouble("vol", def.volatility);
            item.putLong("depth", def.depth);
            item.putBoolean("enabled", def.enabled);
            item.putBoolean("halted", def.halted);
            item.putInt("holders", data.holderCount(def.symbol));
            item.putInt("orders", data.openOrdersOf(def.symbol).size());
            item.putLong("price", quote == null ? def.basePrice : quote.price);
            stocks.add(item);
        }
        out.put("stocks", stocks);
        ListTag audit = new ListTag();
        List<cn.autoforged.module_shop_stock.market.AuditEntry> entries = data.audit();
        for (int i = entries.size() - 1; i >= 0 && audit.size() < 20; i--) {
            CompoundTag item = new CompoundTag();
            item.putLong("time", entries.get(i).time);
            item.putString("actor", entries.get(i).actor);
            item.putString("action", entries.get(i).action);
            item.putString("detail", entries.get(i).detail);
            audit.add(item);
        }
        out.put("audit", audit);
        StockMarketNetwork.sendToPlayer(player, out);
    }

    private static String nameOf(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getProfileCache().get(uuid)
                .map(profile -> profile.getName())
                .orElse(uuid.toString().substring(0, 8));
    }

    private static CompoundTag baseTag(String type, StockMarketSavedData data) {
        CompoundTag out = new CompoundTag();
        out.putString("type", type);
        out.putBoolean("enabled", StockMarketConfig.ENABLED.get());
        out.putBoolean("session", MarketEngine.sessionOpen());
        out.putLong("revision", data.revision);
        out.putLong("index", data.index);
        List<Long> history = data.indexHistory();
        long previous = history.size() >= 2 ? history.get(history.size() - 2) : data.index;
        out.putLong("indexPrev", previous);
        out.putString("newsSymbol", data.lastNewsSymbol == null ? "" : data.lastNewsSymbol);
        out.putInt("newsImpact", data.lastNewsImpact);
        out.putString("newsText", data.lastNewsText == null ? "" : data.lastNewsText);
        out.putLong("newsAt", data.lastNewsAt);
        ListTag feed = new ListTag();
        for (CompoundTag item : data.newsFeed) {
            feed.add(item.copy());
        }
        out.put("newsFeed", feed);
        return out;
    }

    private static ListTag defsTag(StockMarketSavedData data) {
        ListTag list = new ListTag();
        for (StockDef def : data.defs().values()) {
            CompoundTag item = new CompoundTag();
            item.putString("symbol", def.symbol);
            item.putString("name", def.name);
            if (def.nameKey != null) {
                item.putString("nameKey", def.nameKey);
            }
            item.putString("sector", def.sector);
            item.putLong("base", def.basePrice);
            item.putDouble("vol", def.volatility);
            item.putLong("depth", def.depth);
            item.putBoolean("enabled", def.enabled);
            item.putBoolean("halted", def.halted);
            list.add(item);
        }
        return list;
    }

    private static ListTag quotesTag(StockMarketSavedData data, boolean lean) {
        ListTag list = new ListTag();
        for (StockDef def : data.defs().values()) {
            Quote quote = MarketEngine.quoteOf(data, def);
            CompoundTag item = new CompoundTag();
            item.putString("symbol", def.symbol);
            item.putLong("price", quote.price);
            item.putLong("prev", quote.prevPrice);
            item.putLong("volume", quote.volume);
            item.putLongArray("bp", quote.bidPrices);
            item.putLongArray("bv", quote.bidVolumes);
            item.putLongArray("ap", quote.askPrices);
            item.putLongArray("av", quote.askVolumes);
            if (!lean) {
                item.putLong("prevClose", quote.prevClose);
                item.putLong("dayOpen", quote.dayOpen);
                item.putLong("dayHigh", quote.dayHigh);
                item.putLong("dayLow", quote.dayLow);
            }
            list.add(item);
        }
        return list;
    }

    private static ListTag positionsTag(StockMarketSavedData data, UUID owner) {
        ListTag list = new ListTag();
        for (Map.Entry<String, Position> entry : data.positions(owner).entrySet()) {
            Position position = entry.getValue();
            if (position.isEmpty()) {
                continue;
            }
            CompoundTag item = new CompoundTag();
            item.putString("symbol", entry.getKey());
            item.putLong("quantity", position.quantity);
            item.putLong("locked", position.locked);
            item.putLong("cost", position.cost);
            item.putLong("shortQuantity", position.shortQuantity);
            item.putLong("shortLocked", position.shortLocked);
            item.putLong("shortProceeds", position.shortProceeds);
            item.putLong("shortMargin", position.shortMargin);
            list.add(item);
        }
        return list;
    }

    private static ListTag ordersTag(List<Order> orders) {
        ListTag list = new ListTag();
        for (Order order : orders) {
            CompoundTag item = new CompoundTag();
            item.putLong("id", order.id);
            item.putString("symbol", order.symbol);
            item.putBoolean("buy", order.buy);
            item.putString("action", order.action.name());
            item.putBoolean("market", order.market);
            item.putLong("price", order.averagePrice());
            item.putLong("quantity", order.quantity);
            item.putLong("filled", order.filled);
            item.putString("state", order.state.name());
            item.putLong("createdAt", order.createdAt);
            item.putLong("expireAt", order.expireAt);
            list.add(item);
        }
        return list;
    }

    public static void reply(ServerPlayer player, long nonce, boolean ok, String key, Object... args) {
        CompoundTag out = new CompoundTag();
        out.putString("type", "result");
        out.putLong("nonce", nonce);
        out.putBoolean("ok", ok);
        out.putString("message", key);
        ListTag values = new ListTag();
        for (Object arg : args) {
            values.add(StringTag.valueOf(String.valueOf(arg)));
        }
        out.put("args", values);
        StockMarketNetwork.sendToPlayer(player, out);
    }
}
