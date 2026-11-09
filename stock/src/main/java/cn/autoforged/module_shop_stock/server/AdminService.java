package cn.autoforged.module_shop_stock.server;

import cn.autoforged.module_shop_stock.StockMarket;
import cn.autoforged.module_shop_stock.StockMarketConfig;
import cn.autoforged.module_shop_stock.data.StockMarketSavedData;
import cn.autoforged.module_shop_stock.market.AuditEntry;
import cn.autoforged.module_shop_stock.market.Order;
import cn.autoforged.module_shop_stock.market.Position;
import cn.autoforged.module_shop_stock.market.Quote;
import cn.autoforged.module_shop_stock.market.StockDef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AdminService {
    private static final Logger LOG = LoggerFactory.getLogger("module_shop_stock/admin");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int NAME_MAX = 24;
    private static final int SECTOR_MAX = 8;

    private AdminService() {
    }

    static void handle(ServerPlayer player, MinecraftServer server, StockMarketSavedData data, CompoundTag tag,
            long nonce) {
        if (!player.hasPermissions(2)) {
            StockServer.reply(player, nonce, false, "msg.stock.no_permission");
            return;
        }
        String action = tag.getString("action");
        switch (action) {
            case "add" -> add(player, server, data, tag, nonce);
            case "edit" -> edit(player, server, data, tag, nonce);
            case "remove" -> remove(player, server, data, tag, nonce);
            case "toggleEnabled" -> toggleEnabled(player, server, data, tag, nonce);
            case "toggleHalt" -> toggleHalt(player, server, data, tag, nonce);
            case "news" -> news(player, server, data, tag, nonce);
            case "dividend" -> dividend(player, server, data, nonce);
            default -> StockServer.reply(player, nonce, false, "msg.stock.failed");
        }
    }

    private static void add(ServerPlayer player, MinecraftServer server, StockMarketSavedData data, CompoundTag tag,
            long nonce) {
        String symbol = StockMarket.normalizeSymbol(tag.getString("symbol"));
        if (!StockMarket.isValidSymbol(symbol)) {
            StockServer.reply(player, nonce, false, "msg.stock.symbol_invalid");
            return;
        }
        if (data.def(symbol) != null) {
            StockServer.reply(player, nonce, false, "msg.stock.symbol_exists", symbol);
            return;
        }
        StockDef def = readDef(symbol, tag, nonce, player);
        if (def == null) {
            return;
        }
        data.putDef(def);
        Quote quote = data.ensureQuote(def, StockMarketConfig.MIN_PRICE.get(), StockMarketConfig.MAX_PRICE.get());
        quote.prevClose = quote.price;
        quote.dayOpen = quote.price;
        quote.dayHigh = quote.price;
        quote.dayLow = quote.price;
        MarketEngine.recomputeIndex(data);
        data.revision++;
        data.markDirty();
        audit(server, player, "add", def.logName() + " 基准价 " + def.basePrice);
        StockServer.reply(player, nonce, true, "msg.stock.added", symbol, def.basePrice);
        StockServer.afterAdminChange(server, data);
    }

    private static void edit(ServerPlayer player, MinecraftServer server, StockMarketSavedData data, CompoundTag tag,
            long nonce) {
        String symbol = StockMarket.normalizeSymbol(tag.getString("symbol"));
        StockDef existing = data.def(symbol);
        if (existing == null) {
            StockServer.reply(player, nonce, false, "msg.stock.unknown_symbol", symbol);
            return;
        }
        StockDef edited = readDef(symbol, tag, nonce, player);
        if (edited == null) {
            return;
        }
        edited.enabled = existing.enabled;
        edited.halted = existing.halted;
        data.putDef(edited);

        Quote quote = data.quote(symbol);
        if (quote != null) {
            long min = MarketEngine.lowerBound(edited, quote);
            long max = MarketEngine.upperBound(edited, quote);
            long clamped = Math.max(min, Math.min(max, quote.price));
            if (clamped != quote.price) {
                quote.moveTo(clamped);
            }
            quote.prevClose = Math.max(1L, Math.min(quote.prevClose, edited.basePrice * 4L));
            MarketMakerEngine.rebuildOrderBook(data, edited, quote);
        }
        MarketEngine.recomputeIndex(data);
        data.revision++;
        data.markDirty();
        audit(server, player, "edit", edited.logName() + " 基准价 " + edited.basePrice
                + " 波动 " + String.format("%.3f", edited.volatility) + " 深度 " + edited.depth);
        StockServer.reply(player, nonce, true, "msg.stock.updated", symbol);
        StockServer.afterAdminChange(server, data);
    }

    private static StockDef readDef(String symbol, CompoundTag tag, long nonce, ServerPlayer player) {
        String name = tag.getString("name").trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            StockServer.reply(player, nonce, false, "msg.stock.name_invalid", NAME_MAX);
            return null;
        }
        String sector = tag.getString("sector").trim();
        if (sector.length() > SECTOR_MAX) {
            StockServer.reply(player, nonce, false, "msg.stock.sector_invalid", SECTOR_MAX);
            return null;
        }
        long base = parseLong(tag.getString("base"), -1L);
        if (base < 1L || base > 1_000_000L) {
            StockServer.reply(player, nonce, false, "msg.stock.base_invalid");
            return null;
        }
        double volatility = parseDouble(tag.getString("vol"), -1.0D);
        if (volatility < 0.002D || volatility > 0.35D) {
            StockServer.reply(player, nonce, false, "msg.stock.vol_invalid");
            return null;
        }
        long depth = parseLong(tag.getString("depth"), -1L);
        if (depth < 100L || depth > 1_000_000L) {
            StockServer.reply(player, nonce, false, "msg.stock.depth_invalid");
            return null;
        }
        return new StockDef(symbol, name, null, sector, base, volatility, depth);
    }

    private static void toggleEnabled(ServerPlayer player, MinecraftServer server, StockMarketSavedData data,
            CompoundTag tag, long nonce) {
        String symbol = StockMarket.normalizeSymbol(tag.getString("symbol"));
        StockDef def = data.def(symbol);
        if (def == null) {
            StockServer.reply(player, nonce, false, "msg.stock.unknown_symbol", symbol);
            return;
        }
        def.enabled = !def.enabled;
        if (!def.enabled) {
            cancelOrdersOf(server, data, symbol, "msg.stock.order_cancelled_delist");
        }
        MarketEngine.recomputeIndex(data);
        data.revision++;
        data.markDirty();
        audit(server, player, def.enabled ? "list" : "delist", def.logName());
        StockServer.reply(player, nonce, true, def.enabled ? "msg.stock.listed" : "msg.stock.delisted_ok", symbol);
        StockServer.afterAdminChange(server, data);
    }

    private static void toggleHalt(ServerPlayer player, MinecraftServer server, StockMarketSavedData data,
            CompoundTag tag, long nonce) {
        String symbol = StockMarket.normalizeSymbol(tag.getString("symbol"));
        StockDef def = data.def(symbol);
        if (def == null) {
            StockServer.reply(player, nonce, false, "msg.stock.unknown_symbol", symbol);
            return;
        }
        def.halted = !def.halted;
        if (def.halted) {
            cancelOrdersOf(server, data, symbol, "msg.stock.order_cancelled_halt");
        }
        data.revision++;
        data.markDirty();
        audit(server, player, def.halted ? "halt" : "resume", def.logName());
        StockServer.reply(player, nonce, true, def.halted ? "msg.stock.halted_ok" : "msg.stock.resumed_ok", symbol);
        StockServer.afterAdminChange(server, data);
    }

    private static void remove(ServerPlayer player, MinecraftServer server, StockMarketSavedData data, CompoundTag tag,
            long nonce) {
        String symbol = StockMarket.normalizeSymbol(tag.getString("symbol"));
        StockDef def = data.def(symbol);
        if (def == null) {
            StockServer.reply(player, nonce, false, "msg.stock.unknown_symbol", symbol);
            return;
        }
        int holders = data.holderCount(symbol);
        int openOrders = data.openOrdersOf(symbol).size();
        boolean force = tag.getBoolean("force");
        if ((holders > 0 || openOrders > 0) && !force) {
            StockServer.reply(player, nonce, false, "msg.stock.remove_blocked", holders, openOrders);
            return;
        }
        Quote quote = data.quote(symbol);
        long price = quote == null ? def.basePrice : quote.price;

        cancelOrdersOf(server, data, symbol, "msg.stock.order_cancelled_remove");

        int liquidated = 0;
        long paid = 0L;
        for (Map.Entry<UUID, Map<String, Position>> entry : new ArrayList<>(data.allPositions().entrySet())) {
            Position position = entry.getValue().get(symbol);
            if (position == null || position.isEmpty()) {
                continue;
            }
            long longProceeds = position.quantity > 0L ? Math.max(0L, TradeService.safeMultiply(price, position.quantity)) : 0L;
            long shortPayout = 0L;
            if (position.shortQuantity > 0L) {
                long coverCost = Math.max(0L, TradeService.safeMultiply(price, position.shortQuantity));
                shortPayout = Math.max(0L, position.shortMargin + (position.shortProceeds - coverCost));
            }
            long totalPayout = longProceeds + shortPayout;
            if (totalPayout > 0L) {
                TradeService.credit(entry.getKey(), totalPayout, "股市下市清算 " + symbol);
                paid += totalPayout;
            }
            TradeService.notify(server, entry.getKey(), Component.translatable("msg.stock.liquidated",
                    symbol, position.quantity + position.shortQuantity, totalPayout));
            data.dropPosition(entry.getKey(), symbol);
            liquidated++;
        }

        data.removeDef(symbol);
        for (java.util.Set<String> list : data.watchlists()) {
            list.remove(symbol);
        }
        MarketEngine.recomputeIndex(data);
        data.revision++;
        data.markDirty();
        audit(server, player, "remove", def.logName() + " 清算 " + liquidated + " 人 / " + paid);
        StockServer.reply(player, nonce, true, "msg.stock.removed", symbol, liquidated, paid);
        StockServer.afterAdminChange(server, data);
    }

    private static void cancelOrdersOf(MinecraftServer server, StockMarketSavedData data, String symbol, String key) {
        for (Order order : data.openOrdersOf(symbol)) {
            long remaining = order.remaining();
            TradeService.release(data, order, Order.State.CANCELLED);
            TradeService.notify(server, order.owner, Component.translatable(key, symbol, remaining));
        }
    }

    private static void news(ServerPlayer player, MinecraftServer server, StockMarketSavedData data, CompoundTag tag,
            long nonce) {
        String symbol = StockMarket.normalizeSymbol(tag.getString("symbol"));
        StockDef def = data.def(symbol);
        if (def == null) {
            StockServer.reply(player, nonce, false, "msg.stock.unknown_symbol", symbol);
            return;
        }
        String text = tag.getString("text").trim();
        if (text.isEmpty() || text.length() > 60) {
            StockServer.reply(player, nonce, false, "msg.stock.news_invalid");
            return;
        }
        int percent = (int) parseLong(tag.getString("impact"), 0L);
        percent = Math.max(-30, Math.min(30, percent));
        NewsEngine.applyNewsImpact(server, data, def, percent, text, player.getGameProfile().getName());
        StockServer.reply(player, nonce, true, "msg.stock.news_ok", symbol, percent);
    }

    private static void dividend(ServerPlayer player, MinecraftServer server, StockMarketSavedData data, long nonce) {
        long fund = data.fund;
        if (fund <= 0L) {
            StockServer.reply(player, nonce, false, "msg.stock.fund_empty");
            return;
        }
        long totalValue = 0L;
        List<long[]> weights = new ArrayList<>();
        List<UUID> owners = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Position>> entry : data.allPositions().entrySet()) {
            long value = 0L;
            for (Map.Entry<String, Position> held : entry.getValue().entrySet()) {
                if (held.getValue().quantity <= 0L) {
                    continue;
                }
                Quote quote = data.quote(held.getKey());
                if (quote != null) {
                    value += TradeService.safeMultiply(quote.price, held.getValue().quantity);
                }
            }
            if (value > 0L) {
                owners.add(entry.getKey());
                weights.add(new long[]{value, 0L});
                totalValue += value;
            }
        }
        if (totalValue <= 0L) {
            StockServer.reply(player, nonce, false, "msg.stock.no_holders");
            return;
        }
        long distributed = 0L;
        for (int i = 0; i < owners.size(); i++) {
            long share = fund * weights.get(i)[0] / totalValue;
            if (i == owners.size() - 1) {
                share = fund - distributed;
            }
            if (share <= 0L) {
                continue;
            }
            TradeService.credit(owners.get(i), share, "股市分红");
            TradeService.notify(server, owners.get(i), Component.translatable("msg.stock.dividend_paid", share));
            distributed += share;
        }
        data.fund = Math.max(0L, data.fund - distributed);
        data.revision++;
        data.markDirty();
        audit(server, player, "dividend", "派发 " + distributed + " 给 " + owners.size() + " 人");
        StockServer.reply(player, nonce, true, "msg.stock.dividend_ok", distributed, owners.size());
        StockServer.afterAdminChange(server, data);
    }

    private static void audit(MinecraftServer server, ServerPlayer actor, String action, String detail) {
        StockMarketSavedData data = StockMarketSavedData.get(server);
        String name = actor.getGameProfile().getName();
        data.addAudit(new AuditEntry(System.currentTimeMillis(), name, action, detail));
        String line = STAMP.format(LocalDateTime.now()) + " " + name + " " + action + " " + detail;
        Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("module_shop_stock-admin.log"), line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("写入管理日志失败：{}", e.getMessage());
        }
        LOG.info(line);
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String text, double fallback) {
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
