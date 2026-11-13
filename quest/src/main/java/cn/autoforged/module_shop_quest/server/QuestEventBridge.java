package cn.autoforged.module_shop_quest.server;

import cn.autoforged.module_shop.api.ModuleShopApi;
import cn.autoforged.module_shop.api.event.AuctionBidPlacedEvent;
import cn.autoforged.module_shop.api.event.DemandCreatedEvent;
import cn.autoforged.module_shop.api.event.PurchaseCompletedEvent;
import cn.autoforged.module_shop.api.event.SellCompletedEvent;
import cn.autoforged.module_shop.api.event.SignInCompletedEvent;
import cn.autoforged.module_shop.api.event.StallListedEvent;
import cn.autoforged.module_shop.api.view.TransactionInfo;
import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestTaskType;
import cn.autoforged.module_shop_quest.config.QuestTomlConfig;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 跨模组与原版事件监听桥接器（已合入盲仓净门票去重、股市限价真实成交防刷过滤、64条批量流水窗口与 1:1 令牌抵消池）。
 */
public final class QuestEventBridge {
    private static final int LEDGER_POLL_LIMIT = 64;
    private static final long API_CREDIT_TTL_MS = 3500L;

    private static final Map<UUID, LedgerCursor> LEDGER_CURSORS = new ConcurrentHashMap<>();
    private static final Map<String, ConcurrentLinkedDeque<Long>> PENDING_API_CREDITS = new ConcurrentHashMap<>();
    private static int ledgerTickCounter = 0;

    private static final class LedgerCursor {
        long lastTxTimeMs;
        String lastFingerprint;

        LedgerCursor(long lastTxTimeMs, String lastFingerprint) {
            this.lastTxTimeMs = lastTxTimeMs;
            this.lastFingerprint = lastFingerprint == null ? "" : lastFingerprint;
        }
    }

    private QuestEventBridge() {
    }

    public static void resetRuntime() {
        LEDGER_CURSORS.clear();
        PENDING_API_CREDITS.clear();
        ledgerTickCounter = 0;
    }

    public static void onPlayerLogin(ServerPlayer player) {
        initCursor(player.getUUID());
    }

    public static void onPlayerLogout(ServerPlayer player) {
        LEDGER_CURSORS.remove(player.getUUID());
    }

    private static void initCursor(UUID uuid) {
        List<TransactionInfo> latest = ModuleShopApi.history().history(uuid, 1);
        if (latest != null && !latest.isEmpty()) {
            TransactionInfo top = latest.get(0);
            LEDGER_CURSORS.put(uuid, new LedgerCursor(top.time(), fingerprint(top)));
        } else {
            LEDGER_CURSORS.put(uuid, new LedgerCursor(System.currentTimeMillis(), ""));
        }
    }

    // ================================================================ 1. Module-Shop 公共事件总线监听

    public static void registerShopApiListeners() {
        IEventBus shopBus = ModuleShopApi.events();

        shopBus.addListener((SignInCompletedEvent e) -> {
            MinecraftServer server = ModuleShopApi.server();
            if (server != null && ModuleShopApi.onServerThread(server)) {
                markApiEventSeen(e.playerId(), "sign_in");
                OnlineTimeService.recordRealAction(e.playerId());
                QuestManager.recordProgress(server, e.playerId(), QuestTaskType.SHOP_SIGN_IN, "", 1);
            }
        });

        shopBus.addListener((PurchaseCompletedEvent e) -> {
            MinecraftServer server = ModuleShopApi.server();
            if (server != null && ModuleShopApi.onServerThread(server)) {
                String src = e.source() == null ? "" : e.source();
                markApiEventSeen(e.playerId(), "buy:" + src);
                OnlineTimeService.recordRealAction(e.playerId());
                String itemId = e.item() != null && !e.item().isEmpty()
                        ? BuiltInRegistries.ITEM.getKey(e.item().getItem()).toString()
                        : e.itemId();
                if ("mall".equalsIgnoreCase(src)) {
                    QuestManager.recordProgress(server, e.playerId(), QuestTaskType.SHOP_BUY_MALL, itemId, 1);
                } else if ("stall".equalsIgnoreCase(src)) {
                    QuestManager.recordProgress(server, e.playerId(), QuestTaskType.STALL_BUY, itemId, 1);
                } else {
                    QuestManager.recordProgress(server, e.playerId(), QuestTaskType.SHOP_BUY_DAILY, itemId, 1);
                }
            }
        });

        shopBus.addListener((SellCompletedEvent e) -> {
            MinecraftServer server = ModuleShopApi.server();
            if (server != null && ModuleShopApi.onServerThread(server)) {
                markApiEventSeen(e.playerId(), "sell");
                OnlineTimeService.recordRealAction(e.playerId());
                String itemId = e.item() != null && !e.item().isEmpty()
                        ? BuiltInRegistries.ITEM.getKey(e.item().getItem()).toString()
                        : e.itemId();
                QuestManager.recordProgress(server, e.playerId(), QuestTaskType.SHOP_SELL_BUYBACK, itemId, 1);
            }
        });

        shopBus.addListener((AuctionBidPlacedEvent e) -> {
            MinecraftServer server = ModuleShopApi.server();
            if (server != null && ModuleShopApi.onServerThread(server)) {
                markApiEventSeen(e.playerId(), "auction_bid");
                OnlineTimeService.recordRealAction(e.playerId());
                QuestManager.recordProgress(server, e.playerId(), QuestTaskType.AUCTION_BID, "", 1);
            }
        });

        shopBus.addListener((DemandCreatedEvent e) -> {
            MinecraftServer server = ModuleShopApi.server();
            if (server != null && ModuleShopApi.onServerThread(server)) {
                markApiEventSeen(e.playerId(), "demand");
                OnlineTimeService.recordRealAction(e.playerId());
                QuestManager.recordProgress(server, e.playerId(), QuestTaskType.DEMAND_CREATE, "", 1);
            }
        });

        shopBus.addListener((StallListedEvent e) -> {
            MinecraftServer server = ModuleShopApi.server();
            if (server != null && ModuleShopApi.onServerThread(server)) {
                markApiEventSeen(e.playerId(), "stall_list");
                OnlineTimeService.recordRealAction(e.playerId());
                String itemId = e.stack() != null && !e.stack().isEmpty()
                        ? BuiltInRegistries.ITEM.getKey(e.stack().getItem()).toString()
                        : "";
                QuestManager.recordProgress(server, e.playerId(), QuestTaskType.STALL_LIST, itemId, 1);
            }
        });

        QuestMod.LOGGER.info("[{}] 已挂载 ModuleShopApi.events() 业务事件监听器", QuestMod.MODID);
    }

    // ================================================================ 2. NeoForge 原版生存任务监听

    public static void registerNeoForgeListeners(IEventBus gameBus) {
        gameBus.addListener((BlockEvent.BreakEvent e) -> {
            if (!(e.getPlayer() instanceof ServerPlayer player) || player.isCreative()) {
                return;
            }
            OnlineTimeService.recordRealAction(player.getUUID());
            if (!QuestManager.hasActiveUnfinishedType(player.server, player.getUUID(), QuestTaskType.VANILLA_BREAK_BLOCK)) {
                return;
            }
            String blockKey = BuiltInRegistries.BLOCK.getKey(e.getState().getBlock()).toString();
            QuestManager.recordProgress(player.server, player.getUUID(), QuestTaskType.VANILLA_BREAK_BLOCK, blockKey, 1);
        });

        gameBus.addListener((LivingDeathEvent e) -> {
            if (e.getEntity().level().isClientSide()) {
                return;
            }
            if (!(e.getSource().getEntity() instanceof ServerPlayer player)) {
                return;
            }
            OnlineTimeService.recordRealAction(player.getUUID());
            if (!(e.getEntity() instanceof Enemy)) {
                return;
            }
            if (!QuestManager.hasActiveUnfinishedType(player.server, player.getUUID(), QuestTaskType.VANILLA_KILL_MOB)) {
                return;
            }
            String entityKey = BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType()).toString();
            QuestManager.recordProgress(player.server, player.getUUID(), QuestTaskType.VANILLA_KILL_MOB, entityKey, 1);
        });

        gameBus.addListener((PlayerEvent.ItemCraftedEvent e) -> {
            if (!(e.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            OnlineTimeService.recordRealAction(player.getUUID());
            if (e.getCrafting().isEmpty()
                    || !QuestManager.hasActiveUnfinishedType(player.server, player.getUUID(), QuestTaskType.VANILLA_CRAFT_ITEM)) {
                return;
            }
            String itemKey = BuiltInRegistries.ITEM.getKey(e.getCrafting().getItem()).toString();
            int craftedCount = Math.max(1, e.getCrafting().getCount());
            QuestManager.recordProgress(player.server, player.getUUID(), QuestTaskType.VANILLA_CRAFT_ITEM, itemKey, craftedCount);
        });

        gameBus.addListener((ItemFishedEvent e) -> {
            if (!(e.getEntity() instanceof ServerPlayer player) || e.getDrops().isEmpty()) {
                return;
            }
            OnlineTimeService.recordRealAction(player.getUUID());
            if (!QuestManager.hasActiveUnfinishedType(player.server, player.getUUID(), QuestTaskType.VANILLA_FISH)) {
                return;
            }
            String fishKey = BuiltInRegistries.ITEM.getKey(e.getDrops().get(0).getItem()).toString();
            QuestManager.recordProgress(player.server, player.getUUID(), QuestTaskType.VANILLA_FISH, fishKey, 1);
        });
    }

    // ================================================================ 3. 公共 HistoryApi 只读流水去重桥接

    public static void onServerTick(MinecraftServer server) {
        if (!QuestTomlConfig.ENABLE_SHOP_LEDGER_BRIDGE.get()) {
            return;
        }
        ledgerTickCounter++;
        if (ledgerTickCounter < 20) {
            return;
        }
        ledgerTickCounter = 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            LedgerCursor cursor = LEDGER_CURSORS.get(uuid);
            if (cursor == null) {
                initCursor(uuid);
                continue;
            }

            List<TransactionInfo> history = ModuleShopApi.history().history(uuid, LEDGER_POLL_LIMIT);
            if (history == null || history.isEmpty()) {
                continue;
            }

            int stopIdx = history.size();
            for (int i = 0; i < history.size(); i++) {
                TransactionInfo tx = history.get(i);
                if (tx.time() < cursor.lastTxTimeMs
                        || (tx.time() == cursor.lastTxTimeMs && fingerprint(tx).equals(cursor.lastFingerprint))) {
                    stopIdx = i;
                    break;
                }
            }

            if (stopIdx > 0) {
                int blindTickets = 0;
                int blindCancelRefunds = 0;
                String lastBlindNote = "";

                for (int i = stopIdx - 1; i >= 0; i--) {
                    TransactionInfo tx = history.get(i);
                    if ("external".equals(tx.type()) && tx.note() != null) {
                        String n = tx.note();
                        if (n.startsWith("遗迹盲仓门票 ")) {
                            blindTickets++;
                            lastBlindNote = n;
                        } else if (n.contains("人数不足退回门票")) {
                            blindCancelRefunds++;
                        }
                    }
                    dispatchVerifiedLedgerEntry(server, uuid, tx);
                }

                // 盲仓对局严格按「净有效门票数（门票 - 同批次人数不足退票）」计数，每局每名真人精确记 1 次
                int netBlindMatches = Math.max(0, blindTickets - blindCancelRefunds);
                if (netBlindMatches > 0) {
                    OnlineTimeService.recordRealAction(uuid);
                    QuestManager.recordProgress(server, uuid, QuestTaskType.BLIND_PARTICIPATE, lastBlindNote, netBlindMatches);
                }

                TransactionInfo newest = history.get(0);
                cursor.lastTxTimeMs = newest.time();
                cursor.lastFingerprint = fingerprint(newest);
            }
        }
    }

    private static void dispatchVerifiedLedgerEntry(MinecraftServer server, UUID uuid, TransactionInfo tx) {
        String type = tx.type() == null ? "" : tx.type();
        String cp = tx.counterParty() == null ? "" : tx.counterParty();
        String note = tx.note() == null ? "" : tx.note();

        // 严格过滤一切自身发奖、解锁扣款、买级扣款、邮件入账与补签流水（防止自激循环）
        if (note.startsWith("quest:") || note.contains("星途纪行") || note.contains("通行证")
                || note.contains("任务奖励") || note.contains("在线里程") || note.contains("纪行等级")) {
            return;
        }

        switch (type) {
            case "buy" -> {
                OnlineTimeService.recordRealAction(uuid);
                if ("购物城".equals(cp)) {
                    if (!consumeRecentApiCredit(uuid, "buy:mall")) {
                        QuestManager.recordProgress(server, uuid, QuestTaskType.SHOP_BUY_MALL, note, 1);
                    }
                } else if ("模块商店".equals(cp)) {
                    if (!consumeRecentApiCredit(uuid, "buy:daily")) {
                        QuestManager.recordProgress(server, uuid, QuestTaskType.SHOP_BUY_DAILY, note, 1);
                    }
                }
            }
            case "sell" -> {
                OnlineTimeService.recordRealAction(uuid);
                if ("模块商店".equals(cp)) {
                    if (!consumeRecentApiCredit(uuid, "sell")) {
                        QuestManager.recordProgress(server, uuid, QuestTaskType.SHOP_SELL_BUYBACK, note, 1);
                    }
                } else if ("出货箱".equals(cp)) {
                    QuestManager.recordProgress(server, uuid, QuestTaskType.SHOP_SHIPPING_SELL, note, 1);
                }
            }
            case "stall_buy" -> {
                OnlineTimeService.recordRealAction(uuid);
                if (!consumeRecentApiCredit(uuid, "buy:stall")) {
                    QuestManager.recordProgress(server, uuid, QuestTaskType.STALL_BUY, note, 1);
                }
            }
            case "auction_bid" -> {
                OnlineTimeService.recordRealAction(uuid);
                if (!consumeRecentApiCredit(uuid, "auction_bid")) {
                    QuestManager.recordProgress(server, uuid, QuestTaskType.AUCTION_BID, note, 1);
                }
            }
            case "auction_buyout" -> {
                OnlineTimeService.recordRealAction(uuid);
                QuestManager.recordProgress(server, uuid, QuestTaskType.SHOP_BUY_ANY, note, 1);
            }
            case "trade_out", "trade_in" -> {
                OnlineTimeService.recordRealAction(uuid);
                QuestManager.recordProgress(server, uuid, QuestTaskType.PLAYER_TRADE, cp, 1);
            }
            case "external" -> {
                // 识别股市真实现货成交（含市价买/平、限价平多成交与限价买多差额/补扣成交；严禁纳入可零成本撤单退款的「股市限价买多冻结」）
                if (note.startsWith("股市买多 ")
                        || note.startsWith("股市平多 ")
                        || note.startsWith("股市限价平多成交 ")
                        || note.startsWith("股市买多差额退回 ")
                        || note.startsWith("股市买多补扣 ")) {
                    OnlineTimeService.recordRealAction(uuid);
                    QuestManager.recordProgress(server, uuid, QuestTaskType.STOCK_SPOT_TRADE, note, 1);
                } else if (note.contains("抽卡") || note.contains("刮刮乐") || cp.toLowerCase().contains("lottery")) {
                    OnlineTimeService.recordRealAction(uuid);
                    QuestManager.recordProgress(server, uuid, QuestTaskType.LOTTERY_PULL, note, 1);
                }
            }
            default -> {
            }
        }
    }

    private static void markApiEventSeen(UUID uuid, String category) {
        ConcurrentLinkedDeque<Long> q = PENDING_API_CREDITS.computeIfAbsent(
                uuid + "|" + category, k -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        while (true) {
            Long head = q.peekFirst();
            if (head == null || (now - head) <= API_CREDIT_TTL_MS) {
                break;
            }
            q.pollFirst();
        }
        q.addLast(now);
    }

    private static boolean consumeRecentApiCredit(UUID uuid, String category) {
        ConcurrentLinkedDeque<Long> q = PENDING_API_CREDITS.get(uuid + "|" + category);
        if (q == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        while (true) {
            Long head = q.pollFirst();
            if (head == null) {
                return false;
            }
            if ((now - head) <= API_CREDIT_TTL_MS) {
                return true;
            }
        }
    }

    private static String fingerprint(TransactionInfo tx) {
        return tx.time() + "|" + tx.type() + "|" + tx.counterParty() + "|" + tx.amount() + "|" + tx.note();
    }
}
