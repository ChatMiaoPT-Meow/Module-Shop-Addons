package com.atoms.Bshop.blind.server;

import cn.autoforged.module_shop.api.ApiErrorCode;
import cn.autoforged.module_shop.api.ApiResult;
import cn.autoforged.module_shop.api.ModuleShopApi;
import com.atoms.Bshop.blind.BlindAuctionConfig;
import com.atoms.Bshop.blind.BlindAuctionMod;
import com.atoms.Bshop.blind.auction.BlindCatalog;
import com.atoms.Bshop.blind.auction.BlindCatalog.Relic;
import com.atoms.Bshop.blind.auction.BlindCatalog.Role;
import com.atoms.Bshop.blind.auction.BlindCatalog.Tool;
import com.atoms.Bshop.blind.auction.VaultZone;
import com.atoms.Bshop.blind.data.BlindAuctionSavedData;
import com.atoms.Bshop.blind.network.BlindAuctionNetwork;
import com.atoms.Bshop.blind.server.BlindRoom.Phase;
import com.atoms.Bshop.blind.server.BlindRoom.Placed;
import com.atoms.Bshop.blind.server.BlindRoom.Seat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 遗迹盲仓服务端。流程：备战（选区 / 鉴定师）→ 匹配 → 五轮暗标 → 开仓 → 返回备战。
 *
 * <p>每轮：系统公开揭示一部分藏品（逐轮增多），并按公开信息推算最低估值；公布上一轮全员报价；玩家的角色技能自动生效，可再用 1 个道具；
 * 报价提交即锁定，超时按 0 处理。第 1–4 轮第一名达到第二名的 2.0 / 1.6 / 1.4 / 1.2 倍直接秒仓，
 * 第 5 轮价高者得，全员 0 则流拍。
 *
 * <p>钱：开局收门票（台账记存档，异常退出下次开服退回）；中标者一笔结清「真实价值 − 回收税 − 成交价」，
 * 付不起视为违约流拍；真人中标亏损时，按比例分给同局其他真人（只从已收的亏损里出）。AI 从不动钱。
 */
public final class BlindVaultEngine {
    private static final Random RANDOM = new Random();
    private static final long MAX_BID = 1_000_000_000L;
    private static final String[] AI_NAMES = {"红石大亨", "考古学者", "下界猪灵", "末影行商", "沙漠旅商", "海底探员"};

    private static final Map<Integer, LinkedHashSet<UUID>> QUEUES = new HashMap<>();
    private static final Map<Integer, Integer> QUEUE_WAIT = new HashMap<>();
    private static final Map<Integer, BlindRoom> ROOMS = new LinkedHashMap<>();
    private static final Map<UUID, BlindRoom> PLAYER_ROOM = new HashMap<>();
    private static int nextRoomId = 1;

    private BlindVaultEngine() {}

    // ================================================================== 生命周期

    public static void onServerStarted(MinecraftServer server) {
        resetMemory();
        refundLedger(server, "遗迹盲仓：上次对局未结束，退回门票");
    }

    public static void onServerStopping(MinecraftServer server) {
        refundLedger(server, "遗迹盲仓：服务器关闭，对局作废，退回门票");
        resetMemory();
    }

    private static void resetMemory() {
        QUEUES.clear();
        QUEUE_WAIT.clear();
        ROOMS.clear();
        PLAYER_ROOM.clear();
    }

    private static void refundLedger(MinecraftServer server, String reason) {
        BlindAuctionSavedData data = BlindAuctionSavedData.get(server);
        for (Map.Entry<UUID, Long> e : new ArrayList<>(data.feeLedger().entrySet())) {
            if (deposit(e.getKey(), e.getValue(), reason)) {
                data.ledgerRemove(e.getKey(), e.getValue());
            } else {
                BlindAuctionMod.LOGGER.warn("盲仓门票退回失败 {} {}", e.getKey(), e.getValue());
            }
        }
    }

    public static void onLogout(ServerPlayer player) {
        int zone = leaveQueue(player.getUUID());
        if (zone >= 0) syncQueue(player.server, zone);
    }

    // ================================================================== 备战

    public static void setZone(ServerPlayer p, int zone) {
        if (!editable(p)) return;
        loadout(p).zone = Math.floorMod(zone, VaultZone.values().length);
        saveAndSync(p);
    }

    public static void setRole(ServerPlayer p, int role) {
        if (!editable(p)) return;
        loadout(p).role = Role.byId(role).ordinal();
        saveAndSync(p);
    }

    public static void setSingle(ServerPlayer p, boolean single) {
        if (!editable(p)) return;
        loadout(p).single = single;
        saveAndSync(p);
    }

    private static boolean editable(ServerPlayer p) {
        if (PLAYER_ROOM.containsKey(p.getUUID()) || queueOf(p.getUUID()) >= 0) {
            BlindAuctionNetwork.sendToast(p, "匹配或对局中不能修改备战配置");
            return false;
        }
        return true;
    }

    // ================================================================== 匹配

    public static void startMatch(ServerPlayer p) {
        UUID id = p.getUUID();
        if (PLAYER_ROOM.containsKey(id) || queueOf(id) >= 0) return;
        BlindAuctionSavedData.Loadout l = loadout(p);
        VaultZone zone = VaultZone.byId(l.zone);
        long balance = balance(id);
        if (balance < Math.max(zone.minStarCoinReq, zone.entryFee)) {
            BlindAuctionNetwork.sendToast(p, "进入【" + zone.title + "】需持有 ≥ "
                    + fmt(Math.max(zone.minStarCoinReq, zone.entryFee)) + " ★");
            return;
        }
        if (l.single) {
            launch(p.server, zone, List.of(id));
            return;
        }
        LinkedHashSet<UUID> queue = QUEUES.computeIfAbsent(zone.id, k -> new LinkedHashSet<>());
        queue.add(id);
        if (queue.size() >= zone.seatCount) {
            List<UUID> players = new ArrayList<>(new ArrayList<>(queue).subList(0, zone.seatCount));
            players.forEach(queue::remove);
            if (queue.isEmpty()) QUEUE_WAIT.remove(zone.id);
            launch(p.server, zone, players);
        } else {
            QUEUE_WAIT.putIfAbsent(zone.id, BlindAuctionConfig.MATCHMAKING_WAIT_SECONDS.get());
        }
        syncQueue(p.server, zone.id);
        syncPlayer(p);
    }

    public static void cancelQueue(ServerPlayer p) {
        int zone = leaveQueue(p.getUUID());
        if (zone >= 0) syncQueue(p.server, zone);
        syncPlayer(p);
    }

    private static int leaveQueue(UUID id) {
        for (Map.Entry<Integer, LinkedHashSet<UUID>> e : QUEUES.entrySet()) {
            if (e.getValue().remove(id)) {
                if (e.getValue().isEmpty()) QUEUE_WAIT.remove(e.getKey());
                return e.getKey();
            }
        }
        return -1;
    }

    private static int queueOf(UUID id) {
        for (Map.Entry<Integer, LinkedHashSet<UUID>> e : QUEUES.entrySet()) {
            if (e.getValue().contains(id)) return e.getKey();
        }
        return -1;
    }

    private static void launch(MinecraftServer server, VaultZone zone, List<UUID> players) {
        BlindAuctionSavedData data = BlindAuctionSavedData.get(server);
        BlindRoom room = new BlindRoom(nextRoomId++, zone, generate(zone));
        for (UUID id : players) {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) continue;
            if (!withdraw(id, zone.entryFee, "遗迹盲仓门票 " + zone.title)) {
                BlindAuctionNetwork.sendToast(sp, "扣除门票失败，未能入座");
                syncPlayer(sp);
                continue;
            }
            data.ledgerAdd(id, zone.entryFee);
            room.seats.add(new Seat(id, sp.getGameProfile().getName(), data.loadout(id).role, zone.entryFee));
        }
        if (room.seats.isEmpty()) return;
        List<String> names = new ArrayList<>(List.of(AI_NAMES));
        Collections.shuffle(names, RANDOM);
        int ai = 0;
        while (room.seats.size() < zone.seatCount) {
            Seat s = new Seat(null, names.get(ai++ % names.size()) + "·AI", 0, 0L);
            s.aiBias = 0.85D + RANDOM.nextDouble() * 0.30D;
            s.aiStyle = 0.88D + RANDOM.nextDouble() * 0.20D;
            room.seats.add(s);
        }
        for (Seat s : room.seats) s.know = new int[room.items.size()];
        ROOMS.put(room.id, room);
        for (Seat s : room.seats) {
            if (s.human()) PLAYER_ROOM.put(s.uuid, room);
        }
        startRound(room);
        for (Seat s : room.seats) {
            ServerPlayer sp = s.human() ? server.getPlayerList().getPlayer(s.uuid) : null;
            if (sp != null) BlindAuctionNetwork.sendToast(sp, "已进入【" + zone.title + "】，第 1 轮开始");
        }
        syncRoom(server, room);
    }

    /** 往 8×6 仓库里随机摆藏品（各自占 1–4 格，不重叠），约占满 55%–70% 的格子。 */
    private static List<Placed> generate(VaultZone zone) {
        int[] weights = BlindCatalog.tierWeights(zone);
        int total = 0;
        for (int w : weights) total += w;
        boolean[] used = new boolean[BlindCatalog.CELLS];
        List<Placed> out = new ArrayList<>();
        int target = 26 + RANDOM.nextInt(8);
        int filled = 0;
        for (int attempt = 0; attempt < 600 && filled < target; attempt++) {
            int roll = RANDOM.nextInt(total);
            int tier = 0;
            while (roll >= weights[tier]) roll -= weights[tier++];
            int t = tier;
            List<Relic> pool = BlindCatalog.RELICS.stream().filter(r -> r.tier() == t).toList();
            Relic relic = pool.get(RANDOM.nextInt(pool.size()));
            int x = RANDOM.nextInt(BlindCatalog.COLS - relic.w() + 1);
            int y = RANDOM.nextInt(BlindCatalog.ROWS - relic.h() + 1);
            boolean free = true;
            for (int dx = 0; dx < relic.w() && free; dx++) {
                for (int dy = 0; dy < relic.h(); dy++) {
                    if (used[(y + dy) * BlindCatalog.COLS + x + dx]) {
                        free = false;
                        break;
                    }
                }
            }
            if (!free) continue;
            for (int dx = 0; dx < relic.w(); dx++) {
                for (int dy = 0; dy < relic.h(); dy++) used[(y + dy) * BlindCatalog.COLS + x + dx] = true;
            }
            long value = Math.round((relic.min() + (long) (RANDOM.nextDouble() * (relic.max() - relic.min()))) / 10.0D) * 10L;
            out.add(new Placed(out.size(), relic, x, y, value));
            filled += relic.w() * relic.h();
        }
        return out;
    }

    // ================================================================== 回合

    private static void startRound(BlindRoom room) {
        int r = room.round;
        room.seconds = BlindAuctionConfig.ROUND_DURATION_SECONDS.get();
        room.elapsed = 0;
        String reveal = revealPublic(room);
        room.floor = room.floorFor(room.pub);
        double expected = room.expectedFor(room.pub);

        for (Seat s : room.seats) {
            s.bid = 0L;
            s.submitted = false;
            s.toolThisRound = false;
            if (s.human()) {
                if (r == 1) {
                    s.log(r, 0, "系统", "仓内共 " + room.items.size() + " 件藏品，占 " + room.occupiedCells() + " 格");
                }
                s.log(r, 0, "系统", reveal);
                s.log(r, 0, "系统", "第 " + r + " 轮公开估值 ≥ " + fmt(room.floor) + " ★");
                roleSkill(room, s);
            } else {
                // AI 只看公开信息：按期望价值 × 读仓偏差 × 逐轮递增的出价比例
                double[] prog = {0.30D, 0.39D, 0.47D, 0.54D, 0.61D};
                double estimate = Math.max(room.floor, expected * s.aiBias);
                long bid;
                if (r <= 3 && RANDOM.nextDouble() < 0.12D) {
                    bid = Math.round(estimate * (0.15D + RANDOM.nextDouble() * 0.15D));
                } else {
                    bid = Math.round(estimate * prog[r - 1] * s.aiStyle * (0.95D + RANDOM.nextDouble() * 0.1D));
                }
                s.bid = Math.max(0L, Math.round(bid / 100.0D) * 100L);
                s.aiDelay = 2 + RANDOM.nextInt(Math.max(1, Math.min(15, room.seconds - 3)));
            }
        }
    }

    /** 按轮次把公开揭示补到目标比例，同步进每个席位的视野；返回给玩家看的说明。 */
    private static String revealPublic(BlindRoom room) {
        int r = room.round;
        int n = room.items.size();
        int outline = raisePublic(room, 1, (int) Math.round(n * BlindCatalog.PUBLIC_OUTLINE[r - 1]));
        int tier = raisePublic(room, 2, (int) Math.round(n * BlindCatalog.PUBLIC_TIER[r - 1]));
        int value = raisePublic(room, 3, Math.min(n, BlindCatalog.PUBLIC_VALUE[r - 1]));
        for (Seat s : room.seats) {
            for (Placed p : room.items) s.learn(p.id(), room.pub[p.id()]);
        }
        StringBuilder sb = new StringBuilder("公开揭示");
        if (outline > 0) sb.append(" ").append(outline).append(" 件轮廓");
        if (tier > 0) sb.append(" ").append(tier).append(" 件品质");
        if (value > 0) sb.append(" ").append(value).append(" 件价值");
        return sb.length() == 4 ? "本轮没有新的公开揭示" : sb.toString();
    }

    /** 把公开已知程度 ≥ level 的藏品补到 want 件；优先从低一级里挑。全仓最贵的那件不公开价值。 */
    private static int raisePublic(BlindRoom room, int level, int want) {
        int have = 0;
        for (int v : room.pub) if (v >= level) have++;
        if (have >= want) return 0;
        Placed top = room.items.isEmpty() ? null : Collections.max(room.items, Comparator.comparingLong(Placed::value));
        List<Placed> near = new ArrayList<>();
        List<Placed> far = new ArrayList<>();
        for (Placed p : room.items) {
            int v = room.pub[p.id()];
            if (v >= level || (level >= 3 && p == top)) continue;
            (v == level - 1 ? near : far).add(p);
        }
        Collections.shuffle(near, RANDOM);
        Collections.shuffle(far, RANDOM);
        near.addAll(far);
        int k = Math.min(want - have, near.size());
        for (int i = 0; i < k; i++) room.pub[near.get(i).id()] = level;
        return k;
    }

    private static void roleSkill(BlindRoom room, Seat s) {
        int r = room.round;
        Role role = Role.byId(s.role);
        String badge = "角色 · " + role.title;
        List<Placed> items = room.items;
        switch (role) {
            case HUNTER -> {
                if (r == 1 && !items.isEmpty()) {
                    Placed best = Collections.max(items, Comparator.comparingLong(Placed::value));
                    s.learn(best.id(), 3);
                    s.log(r, 1, badge, "全仓最贵：" + best.relic().name() + "（" + BlindCatalog.TIER_NAMES[best.relic().tier()]
                            + "）" + fmt(best.value()) + " ★");
                }
            }
            case ARCHAEOLOGIST -> {
                if (r == 1) {
                    List<Integer> tiers = items.stream().map(p -> p.relic().tier()).distinct().toList();
                    if (!tiers.isEmpty()) {
                        int tier = tiers.get(RANDOM.nextInt(tiers.size()));
                        int n = 0;
                        for (Placed p : items) {
                            if (p.relic().tier() == tier) {
                                s.learn(p.id(), 1);
                                n++;
                            }
                        }
                        s.log(r, 1, badge, "显示了同一品质的 " + n + " 件藏品轮廓");
                    }
                } else if (r < 5) {
                    s.log(r, 1, badge, "再显示 " + learnRandom(s, items, 0, 1, 2) + " 件藏品轮廓");
                } else {
                    for (Placed p : items) s.learn(p.id(), 1);
                    s.log(r, 1, badge, "全仓藏品轮廓已全部显示");
                }
            }
            case ENDER_EYE -> {
                int n = learnRandom(s, items, 1, 2, r == 1 ? 5 : 2 + RANDOM.nextInt(3));
                s.log(r, 1, badge, "揭示了 " + n + " 件藏品的品质");
            }
            case GOLD_EYE -> {
                if (r == 1) {
                    long low = items.stream().filter(p -> p.relic().tier() <= 2).mapToLong(Placed::value).sum();
                    s.log(r, 1, badge, "普通~稀有藏品合计价值 " + fmt(low) + " ★");
                } else if (r == 3) {
                    List<Integer> high = items.stream().map(p -> p.relic().tier()).filter(t -> t >= 3).distinct().toList();
                    if (high.isEmpty()) {
                        s.log(r, 1, badge, "仓内没有史诗及以上藏品");
                    } else {
                        int tier = high.get(RANDOM.nextInt(high.size()));
                        int n = 0;
                        for (Placed p : items) {
                            if (p.relic().tier() == tier) {
                                s.learn(p.id(), 2);
                                n++;
                            }
                        }
                        s.log(r, 1, badge, "揭示了全部 " + n + " 件「" + BlindCatalog.TIER_NAMES[tier] + "」藏品");
                    }
                }
            }
            case ARCHITECT -> {
                int tier = r; // 1..5 → 优良…神话
                int cells = items.stream().filter(p -> p.relic().tier() == tier).mapToInt(Placed::cells).sum();
                s.log(r, 1, badge, "「" + BlindCatalog.TIER_NAMES[tier] + "」藏品共占 " + cells + " 格");
            }
            case STATISTICIAN -> {
                if (r == 1) {
                    s.log(r, 1, badge, "史诗及以上藏品共 " + items.stream().filter(p -> p.relic().tier() >= 3).count() + " 件");
                } else if (r == 3) {
                    s.log(r, 1, badge, "其中神话藏品 " + items.stream().filter(p -> p.relic().tier() == 5).count() + " 件");
                }
            }
            case GAMBLER -> {
                if (r == 5) {
                    for (Placed p : items) s.learn(p.id(), 2);
                    s.log(r, 1, badge, "全仓藏品品质已全部揭示");
                }
            }
        }
    }

    /** 从「已知程度 ≤ below」的藏品里随机挑 count 件提升到 level，返回实际数量。 */
    private static int learnRandom(Seat s, List<Placed> items, int below, int level, int count) {
        List<Placed> cand = new ArrayList<>();
        for (Placed p : items) {
            if (s.know[p.id()] <= below && s.know[p.id()] < level) cand.add(p);
        }
        Collections.shuffle(cand, RANDOM);
        int n = Math.min(count, cand.size());
        for (int i = 0; i < n; i++) s.learn(cand.get(i).id(), level);
        return n;
    }

    // ================================================================== 道具

    public static void useTool(ServerPlayer p, int toolId, int cell) {
        BlindRoom room = PLAYER_ROOM.get(p.getUUID());
        if (room == null || room.phase != Phase.BIDDING) return;
        Seat s = room.seatOf(p.getUUID());
        if (s == null) return;
        Tool tool = Tool.byId(toolId);
        if (s.submitted) {
            BlindAuctionNetwork.sendToast(p, "本轮已提交报价，不能再用道具");
            return;
        }
        if (s.toolThisRound) {
            BlindAuctionNetwork.sendToast(p, "每轮只能使用 1 个道具");
            return;
        }
        if (s.toolUsed[tool.ordinal()]) {
            BlindAuctionNetwork.sendToast(p, tool.title + "本局已用过");
            return;
        }
        if (tool.targeted && (cell < 0 || cell >= BlindCatalog.CELLS)) return;
        int r = room.round;
        String badge = "道具 · " + tool.title;
        List<Placed> items = room.items;
        switch (tool) {
            case HAMMER, LENS -> {
                int owner = room.owner[cell];
                if (owner < 0) {
                    s.knownEmpty[cell] = true;
                    s.log(r, 2, badge, "第 " + (cell / BlindCatalog.COLS + 1) + " 行第 " + (cell % BlindCatalog.COLS + 1) + " 列是空格");
                } else {
                    Placed it = items.get(owner);
                    if (tool == Tool.HAMMER) {
                        s.learn(owner, 2);
                        s.log(r, 2, badge, "这是一件「" + BlindCatalog.TIER_NAMES[it.relic().tier()] + "」藏品，占 " + it.cells() + " 格");
                    } else {
                        s.learn(owner, 3);
                        s.log(r, 2, badge, it.relic().name() + "（" + BlindCatalog.TIER_NAMES[it.relic().tier()] + "）价值 "
                                + fmt(it.value()) + " ★");
                    }
                }
            }
            case SCANNER -> s.log(r, 2, badge, "显示了 " + learnRandom(s, items, 0, 1, 3) + " 件藏品轮廓");
            case GLOWSTICK -> {
                StringBuilder sb = new StringBuilder();
                for (int t = 0; t < BlindCatalog.TIER_NAMES.length; t++) {
                    int tt = t;
                    long n = items.stream().filter(x -> x.relic().tier() == tt).count();
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(BlindCatalog.TIER_NAMES[t]).append(n);
                }
                s.log(r, 2, badge, sb.toString());
            }
            case TABLET -> {
                double center = room.trueValue() * (0.92D + RANDOM.nextDouble() * 0.16D);
                long low = Math.round(center * 0.85D / 100.0D) * 100L;
                long high = Math.round(center * 1.15D / 100.0D) * 100L;
                s.log(r, 2, badge, "全仓总价值约 " + fmt(low) + " ~ " + fmt(high) + " ★");
            }
        }
        s.toolUsed[tool.ordinal()] = true;
        s.toolThisRound = true;
        syncPlayer(p);
    }

    // ================================================================== 报价

    public static void submitBid(ServerPlayer p, long amount) {
        BlindRoom room = PLAYER_ROOM.get(p.getUUID());
        if (room == null || room.phase != Phase.BIDDING) return;
        Seat seat = room.seatOf(p.getUUID());
        if (seat == null) return;
        if (seat.submitted) {
            BlindAuctionNetwork.sendToast(p, "本轮报价已锁定");
            return;
        }
        if (amount < 0L || amount > MAX_BID) {
            BlindAuctionNetwork.sendToast(p, "报价无效");
            return;
        }
        if (amount > balance(p.getUUID())) {
            BlindAuctionNetwork.sendToast(p, "报价超过你的星币余额");
            return;
        }
        seat.bid = amount;
        seat.submitted = true;
        seat.submitOrder = ++room.submitCounter;
        if (allHumansDone(p.server, room)) {
            endRound(p.server, room);
        } else {
            BlindAuctionNetwork.sendToast(p, amount == 0 ? "本轮放弃（报价 0）" : "报价已锁定，等待其他玩家");
        }
        syncRoom(p.server, room);
    }

    private static boolean allHumansDone(MinecraftServer server, BlindRoom room) {
        for (Seat s : room.seats) {
            if (s.human() && !s.submitted && server.getPlayerList().getPlayer(s.uuid) != null) return false;
        }
        return true;
    }

    private static void endRound(MinecraftServer server, BlindRoom room) {
        int r = room.round;
        for (Seat s : room.seats) {
            if (!s.submitted) {
                s.bid = s.human() ? 0L : s.bid;
                s.submitOrder = ++room.submitCounter;
            }
            s.bids[r - 1] = s.bid;
        }
        List<Seat> sorted = new ArrayList<>(room.seats);
        sorted.sort(Comparator.comparingLong((Seat s) -> s.bid).reversed().thenComparingLong(s -> s.submitOrder));
        Seat top = sorted.get(0);
        long second = sorted.size() > 1 ? sorted.get(1).bid : 0L;
        if (r >= BlindCatalog.ROUNDS) {
            finish(server, room, top.bid > 0 ? top : null, false);
            return;
        }
        double mult = BlindCatalog.buyout(r);
        boolean instant = top.bid > 0 && (second > 0 ? top.bid >= Math.ceil(second * mult) : top.bid >= room.floor);
        if (instant) {
            finish(server, room, top, true);
            return;
        }
        room.round++;
        startRound(room);
    }

    // ================================================================== 开仓结算

    private static void finish(MinecraftServer server, BlindRoom room, Seat winner, boolean instant) {
        BlindAuctionSavedData data = BlindAuctionSavedData.get(server);
        long truth = room.trueValue();
        long tax = winner == null ? 0L : Math.round(truth * BlindAuctionConfig.CHAMBER_SINK_TAX_RATE.get());
        long bid = winner == null ? 0L : winner.bid;
        long profit = truth - tax - bid;
        boolean defaulted = false;
        Map<Seat, Long> dividends = new HashMap<>();

        if (winner != null && winner.human()) {
            boolean ok = profit >= 0L
                    ? deposit(winner.uuid, profit, "遗迹盲仓开仓 " + room.zone.title)
                    : withdraw(winner.uuid, -profit, "遗迹盲仓中标结算 " + room.zone.title);
            if (!ok) {
                defaulted = true;
            } else if (profit < 0L) {
                List<Seat> others = room.seats.stream().filter(s -> s.human() && s != winner).toList();
                if (!others.isEmpty()) {
                    long pool = Math.round(-profit * BlindAuctionConfig.LOSS_DIVIDEND_RATE.get());
                    long each = Math.min(BlindAuctionConfig.LOSS_DIVIDEND_CAP.get(), pool / others.size());
                    for (Seat o : others) {
                        if (each > 0 && deposit(o.uuid, each, "遗迹盲仓亏损分红")) dividends.put(o, each);
                    }
                }
            }
        }

        Placed top = room.items.isEmpty() ? null : Collections.max(room.items, Comparator.comparingLong(Placed::value));
        CompoundTag res = new CompoundTag();
        res.putString("zone", room.zone.title);
        res.putBoolean("flop", winner == null);
        res.putString("winner", winner == null ? "" : winner.name);
        res.putBoolean("human", winner != null && winner.human());
        res.putLong("bid", bid);
        res.putLong("truth", truth);
        res.putLong("tax", tax);
        res.putLong("profit", winner == null || defaulted ? 0L : profit);
        res.putBoolean("instant", instant);
        res.putBoolean("defaulted", defaulted);
        res.putInt("rounds", room.round);
        res.putString("topItem", top == null ? "" : top.relic().name());
        res.putLong("topValue", top == null ? 0L : top.value());
        res.putLong("at", System.currentTimeMillis());
        ListTag seats = new ListTag();
        for (Seat s : room.seats) {
            CompoundTag t = new CompoundTag();
            t.putString("n", s.name);
            t.putBoolean("h", s.human());
            t.putBoolean("w", s == winner);
            t.putLongArray("bids", java.util.Arrays.copyOf(s.bids, room.round));
            t.putLong("div", dividends.getOrDefault(s, 0L));
            seats.add(t);
        }
        res.put("seats", seats);

        room.phase = Phase.RESULT;
        room.result = res;
        room.resultTtl = 180;
        for (Seat s : room.seats) {
            if (s.human()) data.ledgerRemove(s.uuid, s.feePaid);
        }
        data.pushHistory(res);

        String line = winner == null
                ? "【遗迹盲仓】" + room.zone.title + " 无人出价，流拍。开仓真实价值 " + fmt(truth) + " ★"
                : "【遗迹盲仓】" + room.zone.title + "：" + winner.name + (instant ? " 秒仓" : " 第5轮") + "以 " + fmt(bid)
                        + " ★ 中标，开仓真实价值 " + fmt(truth) + " ★"
                        + (defaulted ? "（余额不足，违约流拍）" : profit >= 0 ? "，赚 " + fmt(profit) + " ★" : "，亏 " + fmt(-profit) + " ★");
        for (Seat s : room.seats) {
            ServerPlayer sp = s.human() ? server.getPlayerList().getPlayer(s.uuid) : null;
            if (sp != null) sp.displayClientMessage(Component.literal(line), false);
        }
        syncRoom(server, room);
    }

    public static void leaveResult(ServerPlayer p) {
        BlindRoom room = PLAYER_ROOM.get(p.getUUID());
        if (room == null || room.phase != Phase.RESULT) return;
        PLAYER_ROOM.remove(p.getUUID());
        if (PLAYER_ROOM.values().stream().noneMatch(r -> r == room)) ROOMS.remove(room.id);
        syncPlayer(p);
    }

    // ================================================================== 每秒

    public static void tickServerSecond(MinecraftServer server) {
        for (Map.Entry<Integer, Integer> e : new ArrayList<>(QUEUE_WAIT.entrySet())) {
            int zone = e.getKey();
            LinkedHashSet<UUID> queue = QUEUES.get(zone);
            if (queue == null || queue.isEmpty()) {
                QUEUE_WAIT.remove(zone);
                continue;
            }
            int left = e.getValue() - 1;
            if (left <= 0) {
                List<UUID> players = new ArrayList<>(queue);
                queue.clear();
                QUEUE_WAIT.remove(zone);
                launch(server, VaultZone.byId(zone), players);
            } else {
                QUEUE_WAIT.put(zone, left);
                syncQueue(server, zone);
            }
        }

        for (BlindRoom room : new ArrayList<>(ROOMS.values())) {
            if (room.phase == Phase.RESULT) {
                if (--room.resultTtl <= 0) {
                    ROOMS.remove(room.id);
                    for (Seat s : room.seats) {
                        if (s.human() && PLAYER_ROOM.remove(s.uuid, room)) {
                            ServerPlayer sp = server.getPlayerList().getPlayer(s.uuid);
                            if (sp != null) syncPlayer(sp);
                        }
                    }
                }
                continue;
            }
            room.elapsed++;
            for (Seat s : room.seats) {
                if (!s.human() && !s.submitted && room.elapsed >= s.aiDelay) {
                    s.submitted = true;
                    s.submitOrder = ++room.submitCounter;
                }
            }
            if (--room.seconds <= 0 || allHumansDone(server, room)) {
                endRound(server, room);
            }
            syncRoom(server, room);
        }
    }

    // ================================================================== 同步

    public static void syncPlayer(ServerPlayer p) {
        BlindAuctionNetwork.sendSnapshot(p, snapshot(p));
    }

    private static void syncRoom(MinecraftServer server, BlindRoom room) {
        for (Seat s : room.seats) {
            ServerPlayer sp = s.human() ? server.getPlayerList().getPlayer(s.uuid) : null;
            if (sp != null && PLAYER_ROOM.get(s.uuid) == room) syncPlayer(sp);
        }
    }

    private static void syncQueue(MinecraftServer server, int zone) {
        LinkedHashSet<UUID> queue = QUEUES.get(zone);
        if (queue == null) return;
        for (UUID id : queue) {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp != null) syncPlayer(sp);
        }
    }

    public static CompoundTag snapshot(ServerPlayer p) {
        UUID id = p.getUUID();
        BlindAuctionSavedData data = BlindAuctionSavedData.get(p.server);
        CompoundTag out = new CompoundTag();
        out.putLong("balance", balance(id));
        out.putDouble("tax", BlindAuctionConfig.CHAMBER_SINK_TAX_RATE.get());
        out.putDouble("dividend", BlindAuctionConfig.LOSS_DIVIDEND_RATE.get());

        BlindAuctionSavedData.Loadout l = data.loadout(id);
        CompoundTag lo = new CompoundTag();
        lo.putInt("zone", l.zone);
        lo.putInt("role", l.role);
        lo.putBoolean("single", l.single);
        out.put("loadout", lo);

        ListTag hist = new ListTag();
        for (CompoundTag h : data.history()) hist.add(h.copy());
        out.put("history", hist);

        BlindRoom room = PLAYER_ROOM.get(id);
        int queueZone = queueOf(id);
        if (room != null) {
            out.putString("phase", room.phase == Phase.RESULT ? "RESULT" : "MATCH");
            out.put("match", matchTag(room, room.seatOf(id)));
        } else if (queueZone >= 0) {
            out.putString("phase", "QUEUE");
            CompoundTag q = new CompoundTag();
            q.putInt("zone", queueZone);
            q.putInt("count", QUEUES.get(queueZone).size());
            q.putInt("seats", VaultZone.byId(queueZone).seatCount);
            q.putInt("wait", QUEUE_WAIT.getOrDefault(queueZone, 0));
            out.put("queue", q);
        } else {
            out.putString("phase", "LOADOUT");
        }
        return out;
    }

    private static CompoundTag matchTag(BlindRoom room, Seat me) {
        boolean over = room.phase == Phase.RESULT;
        CompoundTag m = new CompoundTag();
        m.putInt("zone", room.zone.id);
        m.putInt("round", room.round);
        m.putInt("seconds", Math.max(0, room.seconds));
        m.putLong("floor", room.floor);
        if (me != null) m.putLong("myFloor", Math.max(room.floor, room.floorFor(me.know)));
        m.putInt("itemCount", room.items.size());
        m.putInt("cellCount", room.occupiedCells());
        if (me != null) {
            m.putInt("role", me.role);
            m.putLong("myBid", me.bid);
            m.putBoolean("submitted", me.submitted);
            m.putBoolean("toolThisRound", me.toolThisRound);
            int mask = 0;
            for (int i = 0; i < me.toolUsed.length; i++) if (me.toolUsed[i]) mask |= 1 << i;
            m.putInt("toolsUsed", mask);
            ListTag logs = new ListTag();
            for (CompoundTag t : me.logs) logs.add(t.copy());
            m.put("logs", logs);
        }
        ListTag seats = new ListTag();
        for (Seat s : room.seats) {
            CompoundTag t = new CompoundTag();
            t.putString("n", s.name);
            t.putBoolean("h", s.human());
            t.putBoolean("me", s == me);
            t.putBoolean("s", s.submitted);
            int known = over ? room.round : room.round - 1;
            t.putLongArray("bids", java.util.Arrays.copyOf(s.bids, Math.max(0, known)));
            seats.add(t);
        }
        m.put("seats", seats);

        ListTag items = new ListTag();
        for (Placed it : room.items) {
            int lvl = over ? 3 : me == null ? 0 : me.know[it.id()];
            if (lvl <= 0) continue;
            CompoundTag t = new CompoundTag();
            t.putInt("x", it.x());
            t.putInt("y", it.y());
            t.putInt("w", it.relic().w());
            t.putInt("h", it.relic().h());
            t.putInt("lvl", lvl);
            if (lvl >= 2) t.putInt("tier", it.relic().tier());
            if (lvl >= 3) {
                t.putString("name", it.relic().name());
                t.putString("item", it.relic().itemId());
                t.putLong("value", it.value());
            }
            items.add(t);
        }
        m.put("items", items);
        List<Integer> empties = new ArrayList<>();
        for (int c = 0; c < BlindCatalog.CELLS; c++) {
            if ((over && room.owner[c] < 0) || (!over && me != null && me.knownEmpty[c])) empties.add(c);
        }
        m.putIntArray("empty", empties.stream().mapToInt(Integer::intValue).toArray());
        if (over && room.result != null) m.put("result", room.result.copy());
        return m;
    }

    // ================================================================== 经济（只对真人 UUID）

    private static long balance(UUID id) {
        return Math.max(0L, ModuleShopApi.economy().balance(id));
    }

    private static boolean withdraw(UUID id, long amount, String reason) {
        if (amount <= 0L) return true;
        return ModuleShopApi.economy().take(id, amount, reason).isOk();
    }

    private static boolean deposit(UUID id, long amount, String reason) {
        if (amount <= 0L) return true;
        ApiResult<Long> r = ModuleShopApi.economy().add(id, amount, reason);
        if (r.isOk()) return true;
        if (r.code() == ApiErrorCode.LIMIT_EXCEEDED) {
            return ModuleShopApi.mail().sendMoneyMail(id, amount, "遗迹盲仓", reason).isOk();
        }
        return false;
    }

    private static BlindAuctionSavedData.Loadout loadout(ServerPlayer p) {
        return BlindAuctionSavedData.get(p.server).loadout(p.getUUID());
    }

    private static void saveAndSync(ServerPlayer p) {
        BlindAuctionSavedData.get(p.server).setDirty();
        syncPlayer(p);
    }

    private static String fmt(long v) {
        return String.format("%,d", v);
    }
}
