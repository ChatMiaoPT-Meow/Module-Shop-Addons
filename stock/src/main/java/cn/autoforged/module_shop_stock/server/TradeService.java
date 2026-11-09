package cn.autoforged.module_shop_stock.server;

import cn.autoforged.module_shop.api.ApiErrorCode;
import cn.autoforged.module_shop.api.ApiResult;
import cn.autoforged.module_shop.api.ModuleShopApi;
import cn.autoforged.module_shop_stock.StockMarketConfig;
import cn.autoforged.module_shop_stock.data.StockMarketSavedData;
import cn.autoforged.module_shop_stock.market.Order;
import cn.autoforged.module_shop_stock.market.Position;
import cn.autoforged.module_shop_stock.market.Quote;
import cn.autoforged.module_shop_stock.market.StockDef;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 多空双向交易（买多 BUY / 平多 SELL / 卖空 SHORT / 平空 COVER）、做市商订单簿撮合、撤单与强平保护。
 */
public final class TradeService {
    private TradeService() {
    }

    // ------------------------------------------------------------------ 市价交易（含多单与空单）

    static void market(ServerPlayer player, StockMarketSavedData data, StockDef def, Quote quote,
            Order.Action action, long quantity, long nonce) {
        if (!tradable(player, def, quote, nonce)) {
            return;
        }
        if ((action == Order.Action.BUY || action == Order.Action.SHORT) && !def.enabled) {
            StockServer.reply(player, nonce, false, "msg.stock.delisted");
            return;
        }
        if (action == Order.Action.SHORT && !StockMarketConfig.SHORT_ENABLED.get()) {
            StockServer.reply(player, nonce, false, "msg.stock.short_disabled");
            return;
        }
        if (quantity <= 0L || quantity > StockMarketConfig.MAX_TRADE_QUANTITY.get()) {
            StockServer.reply(player, nonce, false, "msg.stock.invalid_quantity",
                    StockMarketConfig.MAX_TRADE_QUANTITY.get());
            return;
        }
        boolean buying = action == Order.Action.BUY || action == Order.Action.COVER;
        long execPrice = MarketMakerEngine.bestExecutionPrice(quote, buying, quantity);
        long total = safeMultiply(execPrice, quantity);
        if (total <= 0L) {
            StockServer.reply(player, nonce, false, "msg.stock.amount_overflow");
            return;
        }
        long fee = StockMarketConfig.fee(total);
        switch (action) {
            case BUY -> buyAtMarket(player, data, def, quote, quantity, execPrice, total, fee, nonce);
            case SELL -> sellAtMarket(player, data, def, quote, quantity, execPrice, total, fee, nonce);
            case SHORT -> shortAtMarket(player, data, def, quote, quantity, execPrice, total, fee, nonce);
            case COVER -> coverAtMarket(player, data, def, quote, quantity, execPrice, total, fee, nonce);
        }
    }

    private static void buyAtMarket(ServerPlayer player, StockMarketSavedData data, StockDef def, Quote quote,
            long quantity, long price, long total, long fee, long nonce) {
        Position position = data.position(player.getUUID(), def.symbol);
        long max = StockMarketConfig.MAX_POSITION.get();
        if (position.quantity + quantity > max) {
            StockServer.reply(player, nonce, false, "msg.stock.position_limit", max);
            return;
        }
        long need = total + fee;
        if (!debit(player.getUUID(), need, "股市买多 " + def.symbol + " ×" + quantity)) {
            StockServer.reply(player, nonce, false, "msg.stock.insufficient_funds");
            return;
        }
        position.quantity += quantity;
        position.cost = saturatedAdd(position.cost, need);
        data.fund += fee;
        quote.trade(price, quantity);
        MarketEngine.applyImpact(data, def, quote, quantity, true);
        record(player.getUUID(), data, def.symbol, Order.Action.BUY, price, quantity, total, 0L, true, Order.State.FILLED);
        data.revision++;
        data.markDirty();
        StockServer.reply(player, nonce, true, "msg.stock.bought", def.symbol, quantity, need);
    }

    private static void sellAtMarket(ServerPlayer player, StockMarketSavedData data, StockDef def, Quote quote,
            long quantity, long price, long total, long fee, long nonce) {
        Position position = data.position(player.getUUID(), def.symbol);
        if (position.available() < quantity) {
            StockServer.reply(player, nonce, false, "msg.stock.not_enough_stock", position.available());
            return;
        }
        long proceeds = Math.max(0L, total - fee);
        if (!credit(player.getUUID(), proceeds, "股市平多 " + def.symbol + " ×" + quantity)) {
            StockServer.reply(player, nonce, false, "msg.stock.delivery_failed");
            return;
        }
        long costRemoved = proportional(position.cost, position.quantity, quantity);
        position.quantity -= quantity;
        position.cost = Math.max(0L, position.cost - costRemoved);
        data.fund += fee;
        quote.trade(price, quantity);
        MarketEngine.applyImpact(data, def, quote, quantity, false);
        record(player.getUUID(), data, def.symbol, Order.Action.SELL, price, quantity, total, 0L, true, Order.State.FILLED);
        data.revision++;
        data.markDirty();
        StockServer.reply(player, nonce, true, "msg.stock.sold", def.symbol, quantity, proceeds);
    }

    /** 市价融券卖空（开空）：冻结 100% 市值保证金 + 手续费，记录开空卖出均价。 */
    private static void shortAtMarket(ServerPlayer player, StockMarketSavedData data, StockDef def, Quote quote,
            long quantity, long price, long total, long fee, long nonce) {
        Position position = data.position(player.getUUID(), def.symbol);
        long max = StockMarketConfig.MAX_POSITION.get();
        if (position.shortQuantity + quantity > max) {
            StockServer.reply(player, nonce, false, "msg.stock.position_limit", max);
            return;
        }
        long needMargin = total + fee;
        if (!debit(player.getUUID(), needMargin, "股市卖空保证金 " + def.symbol + " ×" + quantity)) {
            StockServer.reply(player, nonce, false, "msg.stock.insufficient_funds");
            return;
        }
        position.shortQuantity += quantity;
        position.shortProceeds = saturatedAdd(position.shortProceeds, total);
        position.shortMargin = saturatedAdd(position.shortMargin, total);
        data.fund += fee;
        quote.trade(price, quantity);
        MarketEngine.applyImpact(data, def, quote, quantity, false);
        record(player.getUUID(), data, def.symbol, Order.Action.SHORT, price, quantity, total, 0L, true, Order.State.FILLED);
        data.revision++;
        data.markDirty();
        StockServer.reply(player, nonce, true, "msg.stock.shorted", def.symbol, quantity, price);
    }

    /** 市价买回平空（平空）：释放对应比例的保证金 + (开空卖出额 - 平仓买回额) - 手续费。 */
    private static void coverAtMarket(ServerPlayer player, StockMarketSavedData data, StockDef def, Quote quote,
            long quantity, long price, long total, long fee, long nonce) {
        Position position = data.position(player.getUUID(), def.symbol);
        if (position.availableShort() < quantity) {
            StockServer.reply(player, nonce, false, "msg.stock.not_enough_short", position.availableShort());
            return;
        }
        long entryProceeds = proportional(position.shortProceeds, position.shortQuantity, quantity);
        long marginReleased = proportional(position.shortMargin, position.shortQuantity, quantity);
        long pnl = entryProceeds - total;
        long payout = Math.max(0L, marginReleased + pnl - fee);
        if (!credit(player.getUUID(), payout, "股市平空结算 " + def.symbol + " ×" + quantity)) {
            StockServer.reply(player, nonce, false, "msg.stock.delivery_failed");
            return;
        }
        position.shortQuantity -= quantity;
        position.shortProceeds = Math.max(0L, position.shortProceeds - entryProceeds);
        position.shortMargin = Math.max(0L, position.shortMargin - marginReleased);
        data.fund += fee;
        quote.trade(price, quantity);
        MarketEngine.applyImpact(data, def, quote, quantity, true);
        record(player.getUUID(), data, def.symbol, Order.Action.COVER, price, quantity, total, 0L, true, Order.State.FILLED);
        data.revision++;
        data.markDirty();
        StockServer.reply(player, nonce, true, "msg.stock.covered", def.symbol, quantity, payout);
    }

    // ------------------------------------------------------------------ 限价委托（支持买多/平多/卖空/平空）

    static void limit(ServerPlayer player, StockMarketSavedData data, StockDef def, Quote quote,
            Order.Action action, long quantity, long limitPrice, long nonce) {
        if (!tradable(player, def, quote, nonce)) {
            return;
        }
        if ((action == Order.Action.BUY || action == Order.Action.SHORT) && !def.enabled) {
            StockServer.reply(player, nonce, false, "msg.stock.delisted");
            return;
        }
        if (action == Order.Action.SHORT && !StockMarketConfig.SHORT_ENABLED.get()) {
            StockServer.reply(player, nonce, false, "msg.stock.short_disabled");
            return;
        }
        if (quantity <= 0L || quantity > StockMarketConfig.MAX_TRADE_QUANTITY.get()) {
            StockServer.reply(player, nonce, false, "msg.stock.invalid_quantity",
                    StockMarketConfig.MAX_TRADE_QUANTITY.get());
            return;
        }
        long min = MarketEngine.lowerBound(def, quote);
        long max = MarketEngine.upperBound(def, quote);
        long price = Math.max(min, Math.min(max, limitPrice));
        if (price <= 0L) {
            StockServer.reply(player, nonce, false, "msg.stock.limit_invalid", min, max);
            return;
        }
        if (data.openOrders(player.getUUID()).size() >= StockMarketConfig.MAX_OPEN_ORDERS.get()) {
            StockServer.reply(player, nonce, false, "msg.stock.too_many_orders",
                    StockMarketConfig.MAX_OPEN_ORDERS.get());
            return;
        }
        long total = safeMultiply(price, quantity);
        if (total <= 0L) {
            StockServer.reply(player, nonce, false, "msg.stock.amount_overflow");
            return;
        }

        Order order = new Order();
        order.id = data.allocateOrderId();
        order.owner = player.getUUID();
        order.symbol = def.symbol;
        order.action = action;
        order.buy = action.equals(Order.Action.BUY) || action.equals(Order.Action.COVER);
        order.price = price;
        order.quantity = quantity;
        order.createdAt = MarketEngine.nowMillis();
        order.expireAt = order.createdAt + StockMarketConfig.ORDER_TTL_MINUTES.get() * 60_000L;
        order.state = Order.State.OPEN;

        Position position = data.position(player.getUUID(), def.symbol);
        switch (action) {
            case BUY -> {
                if (position.quantity + quantity > StockMarketConfig.MAX_POSITION.get()) {
                    StockServer.reply(player, nonce, false, "msg.stock.position_limit",
                            StockMarketConfig.MAX_POSITION.get());
                    return;
                }
                long need = total + StockMarketConfig.fee(total);
                if (!debit(player.getUUID(), need, "股市限价买多冻结 " + def.symbol + " ×" + quantity)) {
                    StockServer.reply(player, nonce, false, "msg.stock.insufficient_funds");
                    return;
                }
                order.escrow = need;
            }
            case SELL -> {
                if (position.available() < quantity) {
                    StockServer.reply(player, nonce, false, "msg.stock.not_enough_stock", position.available());
                    return;
                }
                position.locked += quantity;
                order.escrow = quantity;
            }
            case SHORT -> {
                if (position.shortQuantity + quantity > StockMarketConfig.MAX_POSITION.get()) {
                    StockServer.reply(player, nonce, false, "msg.stock.position_limit",
                            StockMarketConfig.MAX_POSITION.get());
                    return;
                }
                long need = total + StockMarketConfig.fee(total);
                if (!debit(player.getUUID(), need, "股市限价卖空保证金 " + def.symbol + " ×" + quantity)) {
                    StockServer.reply(player, nonce, false, "msg.stock.insufficient_funds");
                    return;
                }
                order.escrow = need;
            }
            case COVER -> {
                if (position.availableShort() < quantity) {
                    StockServer.reply(player, nonce, false, "msg.stock.not_enough_short", position.availableShort());
                    return;
                }
                position.shortLocked += quantity;
                order.escrow = quantity;
            }
        }

        data.addOrder(order);
        MarketMakerEngine.rebuildOrderBook(data, def, quote);
        // 挂单后若价格已穿过做市商最优档位，立即触发撮合
        matchSymbol(player.server, data, def, quote);
        data.revision++;
        data.markDirty();
        String msgKey = switch (action) {
            case BUY -> "msg.stock.limit_buy";
            case SELL -> "msg.stock.limit_sell";
            case SHORT -> "msg.stock.limit_short";
            case COVER -> "msg.stock.limit_cover";
        };
        StockServer.reply(player, nonce, true, msgKey, def.symbol, quantity, price);
    }

    static void cancel(ServerPlayer player, StockMarketSavedData data, long orderId, long nonce) {
        Order order = data.order(orderId);
        if (order == null || !order.owner.equals(player.getUUID())) {
            StockServer.reply(player, nonce, false, "msg.stock.order_not_found");
            return;
        }
        if (order.state != Order.State.OPEN) {
            StockServer.reply(player, nonce, false, "msg.stock.order_not_open");
            return;
        }
        long rem = order.remaining();
        release(data, order, Order.State.CANCELLED);
        StockDef def = data.def(order.symbol);
        Quote quote = data.quote(order.symbol);
        if (def != null && quote != null) {
            MarketMakerEngine.rebuildOrderBook(data, def, quote);
        }
        data.revision++;
        data.markDirty();
        StockServer.reply(player, nonce, true, "msg.stock.order_cancelled", order.symbol, rem);
    }

    static void release(StockMarketSavedData data, Order order, Order.State state) {
        if (order.escrow > 0L) {
            Position position = data.position(order.owner, order.symbol);
            switch (order.action) {
                case BUY, SHORT -> credit(order.owner, order.escrow, "股市撤单退款 " + order.symbol);
                case SELL -> position.locked = Math.max(0L, position.locked - order.escrow);
                case COVER -> position.shortLocked = Math.max(0L, position.shortLocked - order.escrow);
            }
            order.escrow = 0L;
        }
        order.state = state;
    }

    // ------------------------------------------------------------------ 订单簿与做市商撮合

    static void matchAll(MinecraftServer server, StockMarketSavedData data) {
        if (!MarketEngine.canMatch()) {
            return;
        }
        for (StockDef def : data.defs().values()) {
            if (!def.enabled || def.halted) {
                continue;
            }
            Quote quote = data.quote(def.symbol);
            if (quote != null) {
                matchSymbol(server, data, def, quote);
            }
        }
    }

    static void matchSymbol(MinecraftServer server, StockMarketSavedData data, StockDef def, Quote quote) {
        List<Order> open = data.openOrdersOf(def.symbol);
        if (open.isEmpty()) {
            return;
        }
        open.sort((a, b) -> {
            if (a.isBidOrder() != b.isBidOrder()) {
                return a.isBidOrder() ? -1 : 1;
            }
            return a.isBidOrder() ? Long.compare(b.price, a.price) : Long.compare(a.price, b.price);
        });
        for (Order order : open) {
            if (order.state != Order.State.OPEN) {
                continue;
            }
            boolean bidSide = order.isBidOrder();
            // 当买方限价 >= 现价（或卖一），或者卖方限价 <= 现价（或买一）时，与做市商盘口撮合成交
            boolean crosses = bidSide ? order.price >= quote.price : order.price <= quote.price;
            if (crosses) {
                fill(server, data, order, quote.price, def, quote);
            }
        }
        MarketMakerEngine.rebuildOrderBook(data, def, quote);
    }

    private static void fill(MinecraftServer server, StockMarketSavedData data, Order order, long price,
            StockDef def, Quote quote) {
        long quantity = order.remaining();
        if (quantity <= 0L) {
            order.state = Order.State.FILLED;
            return;
        }
        Position position = data.position(order.owner, order.symbol);
        long total = safeMultiply(price, quantity);
        if (total <= 0L) {
            return;
        }
        long fee = StockMarketConfig.fee(total);

        switch (order.action) {
            case BUY -> {
                long room = StockMarketConfig.MAX_POSITION.get() - position.quantity;
                if (room <= 0L) {
                    release(data, order, Order.State.CANCELLED);
                    notify(server, order.owner, Component.translatable("msg.stock.order_cancelled_limit"));
                    return;
                }
                quantity = Math.min(quantity, room);
                total = safeMultiply(price, quantity);
                fee = StockMarketConfig.fee(total);
                long escrowPart = order.escrow * quantity / Math.max(1L, order.remaining());
                long actual = total + fee;
                if (escrowPart < actual && !debit(order.owner, actual - escrowPart, "股市买多补扣 " + order.symbol)) {
                    release(data, order, Order.State.CANCELLED);
                    notify(server, order.owner, Component.translatable("msg.stock.order_cancelled_limit"));
                    return;
                }
                order.escrow = Math.max(0L, order.escrow - escrowPart);
                if (escrowPart > actual) {
                    credit(order.owner, escrowPart - actual, "股市买多差额退回 " + order.symbol);
                }
                position.quantity += quantity;
                position.cost = saturatedAdd(position.cost, actual);
            }
            case SELL -> {
                quantity = Math.min(quantity, position.quantity);
                if (quantity <= 0L) {
                    release(data, order, Order.State.CANCELLED);
                    return;
                }
                total = safeMultiply(price, quantity);
                fee = StockMarketConfig.fee(total);
                long costRemoved = proportional(position.cost, position.quantity, quantity);
                position.quantity = Math.max(0L, position.quantity - quantity);
                position.locked = Math.max(0L, position.locked - quantity);
                position.cost = Math.max(0L, position.cost - costRemoved);
                order.escrow = Math.max(0L, order.escrow - quantity);
                long proceeds = Math.max(0L, total - fee);
                credit(order.owner, proceeds, "股市限价平多成交 " + order.symbol + " ×" + quantity);
            }
            case SHORT -> {
                long room = StockMarketConfig.MAX_POSITION.get() - position.shortQuantity;
                if (room <= 0L) {
                    release(data, order, Order.State.CANCELLED);
                    return;
                }
                quantity = Math.min(quantity, room);
                total = safeMultiply(price, quantity);
                fee = StockMarketConfig.fee(total);
                long escrowPart = order.escrow * quantity / Math.max(1L, order.remaining());
                long need = total + fee;
                if (escrowPart < need && !debit(order.owner, need - escrowPart, "股市卖空补足保证金 " + order.symbol)) {
                    release(data, order, Order.State.CANCELLED);
                    notify(server, order.owner, Component.translatable("msg.stock.order_cancelled_limit"));
                    return;
                }
                order.escrow = Math.max(0L, order.escrow - escrowPart);
                if (escrowPart > need) {
                    credit(order.owner, escrowPart - need, "股市卖空保证金差额退回 " + order.symbol);
                }
                long margin = total;
                position.shortQuantity += quantity;
                position.shortProceeds = saturatedAdd(position.shortProceeds, total);
                position.shortMargin = saturatedAdd(position.shortMargin, margin);
            }
            case COVER -> {
                quantity = Math.min(quantity, position.shortQuantity);
                if (quantity <= 0L) {
                    release(data, order, Order.State.CANCELLED);
                    return;
                }
                total = safeMultiply(price, quantity);
                fee = StockMarketConfig.fee(total);
                long entryProceeds = proportional(position.shortProceeds, position.shortQuantity, quantity);
                long marginReleased = proportional(position.shortMargin, position.shortQuantity, quantity);
                long pnl = entryProceeds - total;
                long payout = Math.max(0L, marginReleased + pnl - fee);
                position.shortQuantity = Math.max(0L, position.shortQuantity - quantity);
                position.shortLocked = Math.max(0L, position.shortLocked - quantity);
                position.shortProceeds = Math.max(0L, position.shortProceeds - entryProceeds);
                position.shortMargin = Math.max(0L, position.shortMargin - marginReleased);
                order.escrow = Math.max(0L, order.escrow - quantity);
                credit(order.owner, payout, "股市限价平空结算 " + order.symbol + " ×" + quantity);
            }
        }

        data.fund += fee;
        order.filled += quantity;
        order.traded += total;
        order.state = order.filled >= order.quantity ? Order.State.FILLED : Order.State.OPEN;
        quote.trade(price, quantity);
        MarketEngine.applyImpact(data, def, quote, quantity, order.isBidOrder());
        data.revision++;
        data.markDirty();

        notify(server, order.owner, Component.translatable("msg.stock.order_filled",
                order.symbol, quantity, total));
        ServerPlayer online = server.getPlayerList().getPlayer(order.owner);
        if (online != null) {
            StockServer.sendSnapshot(online);
        }
    }

    /** 空单强平巡检：当股价相对空单开仓均价涨幅超过强平线（默认 +85%）时自动平空返还剩余保证金。 */
    static void checkShortLiquidations(MinecraftServer server, StockMarketSavedData data) {
        int liqBps = StockMarketConfig.SHORT_LIQUIDATION_BPS.get();
        for (Map.Entry<UUID, Map<String, Position>> ownerEntry : data.allPositions().entrySet()) {
            UUID owner = ownerEntry.getKey();
            for (Map.Entry<String, Position> posEntry : ownerEntry.getValue().entrySet()) {
                String symbol = posEntry.getKey();
                Position pos = posEntry.getValue();
                if (pos.shortQuantity <= 0L) {
                    continue;
                }
                Quote quote = data.quote(symbol);
                if (quote == null) {
                    continue;
                }
                long avgEntry = Math.max(1L, pos.averageShortPrice());
                long riseBps = (quote.price - avgEntry) * 10_000L / avgEntry;
                if (riseBps >= liqBps) {
                    long qty = pos.shortQuantity;
                    long coverCost = safeMultiply(quote.price, qty);
                    long fee = StockMarketConfig.fee(coverCost);
                    long pnl = pos.shortProceeds - coverCost;
                    long remain = Math.max(0L, pos.shortMargin + pnl - fee);
                    pos.shortQuantity = 0L;
                    pos.shortLocked = 0L;
                    pos.shortProceeds = 0L;
                    pos.shortMargin = 0L;
                    if (remain > 0L) {
                        credit(owner, remain, "股市空单强平退回保证金 " + symbol);
                    }
                    notify(server, owner, Component.translatable("msg.stock.short_liquidated",
                            symbol, qty, remain));
                    ServerPlayer online = server.getPlayerList().getPlayer(owner);
                    if (online != null) {
                        StockServer.sendSnapshot(online);
                    }
                }
            }
        }
    }

    static void expireOrders(MinecraftServer server, StockMarketSavedData data) {
        long now = MarketEngine.nowMillis();
        for (Order order : new ArrayList<>(data.orders())) {
            if (order.state != Order.State.OPEN || order.expireAt <= 0L || now < order.expireAt) {
                continue;
            }
            release(data, order, Order.State.EXPIRED);
            data.revision++;
            data.markDirty();
            notify(server, order.owner, Component.translatable("msg.stock.order_expired", order.symbol));
            ServerPlayer online = server.getPlayerList().getPlayer(order.owner);
            if (online != null) {
                StockServer.sendSnapshot(online);
            }
        }
    }

    private static boolean tradable(ServerPlayer player, StockDef def, Quote quote, long nonce) {
        if (!StockMarketConfig.ENABLED.get()) {
            StockServer.reply(player, nonce, false, "msg.stock.closed");
            return false;
        }
        if (!MarketEngine.sessionOpen()) {
            StockServer.reply(player, nonce, false, "msg.stock.session_closed");
            return false;
        }
        if (def.halted) {
            StockServer.reply(player, nonce, false, "msg.stock.halted");
            return false;
        }
        return true;
    }

    static void record(UUID owner, StockMarketSavedData data, String symbol, Order.Action action, long price,
            long quantity, long total, long escrow, boolean market, Order.State state) {
        Order order = new Order();
        order.id = data.allocateOrderId();
        order.owner = owner;
        order.symbol = symbol;
        order.action = action;
        order.buy = action.equals(Order.Action.BUY) || action.equals(Order.Action.COVER);
        order.price = price;
        order.quantity = quantity;
        order.filled = state == Order.State.FILLED ? quantity : 0L;
        order.traded = total;
        order.escrow = escrow;
        order.createdAt = MarketEngine.nowMillis();
        order.market = market;
        order.state = state;
        data.addOrder(order);
    }

    static long proportional(long totalAmount, long totalQuantity, long partQuantity) {
        if (totalQuantity <= 0L || partQuantity <= 0L || totalAmount <= 0L) {
            return 0L;
        }
        return BigInteger.valueOf(totalAmount)
                .multiply(BigInteger.valueOf(partQuantity))
                .divide(BigInteger.valueOf(totalQuantity))
                .longValue();
    }

    static boolean credit(UUID target, long amount, String reason) {
        if (amount <= 0L) {
            return true;
        }
        if (tryCredit(target, amount, reason)) {
            return true;
        }
        // 经济与邮件都失败：记入待补发，下个 tick 重试，不让金币凭空消失。
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            StockMarketSavedData.get(server).addPendingPayout(target, amount);
            return true;
        }
        return false;
    }

    /** 重试待补发；单个玩家失败不影响其他人。 */
    static void retryPendingPayouts(StockMarketSavedData data) {
        if (data.pendingPayouts().isEmpty()) {
            return;
        }
        var iterator = data.pendingPayouts().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            if (entry.getValue() <= 0L || tryCredit(entry.getKey(), entry.getValue(), "股市待补发款")) {
                iterator.remove();
                data.markDirty();
            }
        }
    }

    private static boolean tryCredit(UUID target, long amount, String reason) {
        if (amount <= 0L) {
            return true;
        }
        ApiResult<Long> result = ModuleShopApi.economy().add(target, amount, reason);
        if (result.isOk()) {
            return true;
        }
        if (result.code() == ApiErrorCode.LIMIT_EXCEEDED) {
            return ModuleShopApi.mail().sendMoneyMail(target, amount, "股市", reason).isOk();
        }
        return false;
    }

    static boolean debit(UUID target, long amount, String reason) {
        if (amount <= 0L) {
            return true;
        }
        ApiResult<Long> result = ModuleShopApi.economy().take(target, amount, reason);
        return result.isOk();
    }

    static void notify(MinecraftServer server, UUID target, Component message) {
        ServerPlayer player = server.getPlayerList().getPlayer(target);
        if (player != null) {
            player.displayClientMessage(message, false);
        }
    }

    static long safeMultiply(long a, long b) {
        if (a <= 0L || b <= 0L || a > Long.MAX_VALUE / b) {
            return -1L;
        }
        return a * b;
    }

    static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }
}
