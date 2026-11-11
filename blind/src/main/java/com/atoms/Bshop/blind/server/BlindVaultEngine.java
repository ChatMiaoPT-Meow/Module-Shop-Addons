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
import com.atoms.Bshop.blind.auction.BlindCatalog.VaultTheme;
import com.atoms.Bshop.blind.auction.VaultZone;
import com.atoms.Bshop.blind.data.BlindAuctionSavedData;
import com.atoms.Bshop.blind.network.BlindAuctionNetwork;
import com.atoms.Bshop.blind.server.BlindRoom.AiPersona;
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
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 遗迹盲仓服务端核心引擎（v3.6 博弈增强版）。
 *
 * <p>核心规则与安全隔离：
 * 1. 联机匹配默认关闭倒计时自动塞 AI（仅凑满真人席位或达到配置的纯真人开局人数后开局，无 AI 混入）。
 * 2. 单人对战 AI（或任何含 AI 席位的对局）强制标记为「演练模式（practice）」：免门票、使用虚拟演练筹码出价、
 *    结算时绝不调用 EconomyApi 给玩家发星币或扣星币，彻底杜绝刷星币漏洞，且不写入全服真实成交榜。
 * 3. 引入仓门封条主题、藏品同源套组共鸣溢价（+12%）、第 5 道具「声呐探针」、每轮对手出价节奏博弈复盘与四流派心理 AI。
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

        // 单人 AI 模式：纯演练博弈，免门票、不限持有门槛、不结算真实星币
        if (l.single) {
            launch(p.server, zone, List.of(id), true, true);
            return;
        }

        long need = Math.max(zone.minStarCoinReq, zone.entryFee);
        if (balance(id) < need) {
            BlindAuctionNetwork.sendToast(p, "联机进入【" + zone.title + "】需持有 ≥ " + fmt(need) + " ★");
            return;
        }

        LinkedHashSet<UUID> queue = QUEUES.computeIfAbsent(zone.id, k -> new LinkedHashSet<>());
        queue.add(id);
        if (queue.size() >= zone.seatCount) {
            List<UUID> players = new ArrayList<>(new ArrayList<>(queue).subList(0, zone.seatCount));
            players.forEach(queue::remove);
            refreshQueueWait(zone, queue);
            launch(p.server, zone, players, false, false);
        } else {
            refreshQueueWait(zone, queue);
        }
        syncQueue(p.server, zone.id);
        syncPlayer(p);
    }

    /**
     * 刷新联机队列倒计时：默认关闭自动 AI 补位（multiplayerAutoAiFill = false），
     * 人数未达纯真人开局下限时永不启动倒计时强塞 AI。
     */
    private static void refreshQueueWait(VaultZone zone, LinkedHashSet<UUID> queue) {
        if (queue == null || queue.isEmpty()) {
            QUEUE_WAIT.remove(zone.id);
            return;
        }
        boolean autoAi = BlindAuctionConfig.MULTIPLAYER_AUTO_AI_FILL.get();
        int minHumans = Math.max(2, Math.min(zone.seatCount, BlindAuctionConfig.MIN_MULTIPLAYER_PLAYERS.get()));
        if (autoAi || (minHumans < zone.seatCount && queue.size() >= minHumans)) {
            QUEUE_WAIT.putIfAbsent(zone.id, BlindAuctionConfig.MATCHMAKING_WAIT_SECONDS.get());
        } else {
            QUEUE_WAIT.remove(zone.id);
        }
    }

    public static void cancelQueue(ServerPlayer p) {
        int zone = leaveQueue(p.getUUID());
        if (zone >= 0) syncQueue(p.server, zone);
        syncPlayer(p);
    }

    private static int leaveQueue(UUID id) {
        for (Map.Entry<Integer, LinkedHashSet<UUID>> e : QUEUES.entrySet()) {
            if (e.getValue().remove(id)) {
                refreshQueueWait(VaultZone.byId(e.getKey()), e.getValue());
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

    private static void launch(MinecraftServer server, VaultZone zone, List<UUID> players, boolean practiceMode,
            boolean fillAi) {
        BlindAuctionSavedData data = BlindAuctionSavedData.get(server);
        boolean effectivePractice = practiceMode || fillAi;
        VaultTheme theme = VaultTheme.values()[RANDOM.nextInt(VaultTheme.values().length)];
        BlindRoom room = new BlindRoom(nextRoomId++, zone, effectivePractice, theme, generate(zone, theme));

        for (UUID id : players) {
            ServerPlayer sp = server.getPlayerList().getPlayer(id);
            if (sp == null) continue;
            long feePaid = 0L;
            if (!effectivePractice) {
                if (!withdraw(id, zone.entryFee, "遗迹盲仓门票 " + zone.title)) {
                    BlindAuctionNetwork.sendToast(sp, "扣除门票失败，未能入座");
                    syncPlayer(sp);
                    continue;
                }
                feePaid = zone.entryFee;
                data.ledgerAdd(id, feePaid);
            }
            room.seats.add(new Seat(id, sp.getGameProfile().getName(), data.loadout(id).role, feePaid));
        }

        // 真人联机若因个别玩家掉线/余额不足导致不足 2 人，立即原路退回门票并取消开局，绝不塞 AI 凑数
        if (!effectivePractice && room.seats.size() < 2) {
            for (Seat s : room.seats) {
                if (s.human() && s.feePaid > 0L && deposit(s.uuid, s.feePaid, "遗迹盲仓：人数不足退回门票")) {
                    data.ledgerRemove(s.uuid, s.feePaid);
                }
                ServerPlayer sp = s.human() ? server.getPlayerList().getPlayer(s.uuid) : null;
                if (sp != null) {
                    BlindAuctionNetwork.sendToast(sp, "有效入座玩家不足 2 人，已退回门票");
                    syncPlayer(sp);
                }
            }
            return;
        }
        if (room.seats.isEmpty()) return;

        if (fillAi) {
            List<String> names = new ArrayList<>(List.of(AI_NAMES));
            Collections.shuffle(names, RANDOM);
            List<AiPersona> personas = new ArrayList<>(List.of(AiPersona.values()));
            Collections.shuffle(personas, RANDOM);
            int ai = 0;
            while (room.seats.size() < zone.seatCount) {
                AiPersona persona = personas.get(ai % personas.size());
                Seat s = new Seat(null, names.get(ai % names.size()) + "·AI", RANDOM.nextInt(Role.values().length), 0L);
                s.aiPersona = persona;
                s.aiBias = zone.aiBiasLow + RANDOM.nextDouble() * (zone.aiBiasHigh - zone.aiBiasLow);
                s.aiStyle = 0.90D + RANDOM.nextDouble() * 0.22D;
                room.seats.add(s);
                ai++;
            }
        }

        for (Seat s : room.seats) s.know = new int[room.items.size()];
        ROOMS.put(room.id, room);
        for (Seat s : room.seats) {
            if (s.human()) PLAYER_ROOM.put(s.uuid, room);
        }
        startRound(room);
        for (Seat s : room.seats) {
            ServerPlayer sp = s.human() ? server.getPlayerList().getPlayer(s.uuid) : null;
            if (sp != null) {
                String tag = room.practice ? "（AI演练·免门票不结算星币）" : "";
                BlindAuctionNetwork.sendToast(sp, "已进入【" + zone.title + " · " + theme.title + "】" + tag);
            }
        }
        syncRoom(server, room);
    }

    /** 往 8×6 仓库里按仓门封条主题随机摆藏品（各自占 1–4 格，不重叠）。 */
    private static List<Placed> generate(VaultZone zone, VaultTheme theme) {
        int[] baseWeights = BlindCatalog.tierWeights(zone).clone();
        if (theme == VaultTheme.ROYAL_TREASURY || theme == VaultTheme.WEATHERED_RUIN) {
            baseWeights[3] += 5;
            baseWeights[4] += 4;
            baseWeights[5] += 2;
        } else if (theme == VaultTheme.ARCANE_SANCTUM) {
            baseWeights[2] += 4;
            baseWeights[3] += 6;
            baseWeights[4] += 3;
        }
        int total = 0;
        for (int w : baseWeights) total += w;

        double dudChance = zone.dudChance * (theme == VaultTheme.WEATHERED_RUIN ? 1.45D : 1.0D);
        double gemChance = zone.gemChance * (theme == VaultTheme.ROYAL_TREASURY ? 1.50D
                : theme == VaultTheme.WEATHERED_RUIN ? 1.40D : 1.0D);

        boolean[] used = new boolean[BlindCatalog.CELLS];
        List<Placed> out = new ArrayList<>();
        int target = theme == VaultTheme.HOARDER_CACHE ? 34 + RANDOM.nextInt(6) : 26 + RANDOM.nextInt(8);
        int preferredSet = theme == VaultTheme.ARCANE_SANCTUM ? 1 + RANDOM.nextInt(4) : 0;
        int filled = 0;

        for (int attempt = 0; attempt < 650 && filled < target; attempt++) {
            int roll = RANDOM.nextInt(total);
            int tier = 0;
            while (roll >= baseWeights[tier]) roll -= baseWeights[tier++];
            int t = tier;
            List<Relic> pool = BlindCatalog.RELICS.stream().filter(r -> r.tier() == t).toList();
            Relic relic = pickThemedRelic(pool, theme, preferredSet);
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
            int grade = 0;
            double mult = 1.0D;
            if (relic.tier() >= BlindCatalog.GRADE_TIER) {
                double g = RANDOM.nextDouble();
                if (g < dudChance) {
                    grade = 1;
                    mult = BlindCatalog.DUD_MULT;
                } else if (g < dudChance + gemChance) {
                    grade = 2;
                    mult = BlindCatalog.GEM_MULT;
                }
            }
            double base = relic.min() + RANDOM.nextDouble() * (relic.max() - relic.min());
            long value = Math.max(BlindCatalog.tierMin(zone, relic.tier()),
                    Math.round(base * zone.valueScale * mult / 10.0D) * 10L);
            out.add(new Placed(out.size(), relic, x, y, value, grade));
            filled += relic.w() * relic.h();
        }
        return out;
    }

    private static Relic pickThemedRelic(List<Relic> pool, VaultTheme theme, int preferredSet) {
        if (pool.size() <= 1) return pool.get(0);
        if (theme == VaultTheme.ANCIENT_ARMORY && RANDOM.nextDouble() < 0.60D) {
            List<Relic> big = pool.stream().filter(r -> r.w() * r.h() >= 2).toList();
            if (!big.isEmpty()) return big.get(RANDOM.nextInt(big.size()));
        }
        if (preferredSet > 0 && RANDOM.nextDouble() < 0.45D) {
            List<Relic> setPool = pool.stream().filter(r -> r.setId() == preferredSet).toList();
            if (!setPool.isEmpty()) return setPool.get(RANDOM.nextInt(setPool.size()));
        }
        return pool.get(RANDOM.nextInt(pool.size()));
    }

    // ================================================================== 回合

    private static void startRound(BlindRoom room) {
        int r = room.round;
        room.seconds = BlindAuctionConfig.ROUND_DURATION_SECONDS.get();
        room.elapsed = 0;
        String reveal = revealPublic(room);
        room.floor = room.floorFor(room.pub);
        double expected = room.expectedFor(room.pub);

        long prevTop = 0L;
        long prevSecond = 0L;
        if (r >= 2) {
            List<Long> prevs = new ArrayList<>();
            for (Seat s : room.seats) prevs.add(Math.max(0L, s.bids[r - 2]));
            prevs.sort(Comparator.reverseOrder());
            if (!prevs.isEmpty()) prevTop = prevs.get(0);
            if (prevs.size() > 1) prevSecond = prevs.get(1);
        }

        for (Seat s : room.seats) {
            s.bid = 0L;
            s.submitted = false;
            s.toolThisRound = false;
            if (s.human()) {
                if (r == 1) {
                    s.log(r, 0, "封条", "【" + room.theme.title + "】" + room.theme.clue);
                    s.log(r, 0, "系统", "仓内共 " + room.items.size() + " 件藏品，占 " + room.occupiedCells() + " 格"
                            + (room.practice ? "（AI演练局：使用演练筹码，不扣门票不结算星币）" : ""));
                } else if (r == 2 && room.comboSetId > 0) {
                    s.log(r, 0, "封条", "清单残页：仓内检出「" + BlindCatalog.SET_NAMES[room.comboSetId]
                            + "」共鸣迹象（凑齐≥3件结算享 +12% 套组溢价）");
                }
                s.log(r, 0, "系统", reveal);
                s.log(r, 0, "系统", "第 " + r + " 轮公开底价 ≥ " + fmt(room.floor) + " ★");
                roleSkill(room, s);
            } else {
                s.bid = computeAiBid(room, s, r, expected, prevTop, prevSecond);
                s.aiDelay = 2 + RANDOM.nextInt(Math.max(1, Math.min(12, room.seconds - 3)));
            }
        }
    }

    /** 四流派心理博弈 AI：结合公开期望、底价、秒仓倍率与上一轮全场最高报价动态决策。 */
    private static long computeAiBid(BlindRoom room, Seat s, int r, double expected, long prevTop, long prevSecond) {
        double estimate = Math.max(room.floor * 1.05D, expected * s.aiBias * s.aiStyle);
        double buyoutMult = BlindCatalog.buyout(r);
        long cap = BlindCatalog.practiceChipCap(room.zone);
        long rawBid;

        switch (s.aiPersona) {
            case SNIPER -> {
                // 前 2 轮潜伏或仅卡住防低价秒仓底线；第 4~5 轮突然发力偷鸡
                if (r <= 2) {
                    if (r == 2 && prevTop > 0 && buyoutMult > 1.0D && prevTop < estimate * 0.55D && RANDOM.nextDouble() < 0.65D) {
                        rawBid = Math.round(prevTop / buyoutMult * (1.05D + RANDOM.nextDouble() * 0.08D));
                    } else {
                        rawBid = RANDOM.nextDouble() < 0.65D ? 0L : Math.round(estimate * 0.22D);
                    }
                } else if (r == 3) {
                    rawBid = Math.round(estimate * (0.38D + RANDOM.nextDouble() * 0.10D));
                } else if (r == 4) {
                    rawBid = Math.round(estimate * (0.72D + RANDOM.nextDouble() * 0.10D));
                } else {
                    double target = Math.max(estimate * (0.84D + RANDOM.nextDouble() * 0.11D),
                            prevTop <= estimate * 0.92D ? prevTop * 1.03D : estimate * 0.86D);
                    rawBid = Math.round(Math.min(estimate * 0.96D, target));
                }
            }
            case BLUFFER -> {
                // 前 2 轮高报施压抬价；若有人跟风抬到高位，第 4~5 轮果断弃标让对手高价接盘
                long myPrev = r >= 2 ? Math.max(0L, s.bids[r - 2]) : 0L;
                boolean opponentChasedHigh = r >= 3 && prevTop > myPrev && prevTop >= estimate * 0.74D;
                if (opponentChasedHigh || s.aiBluffFolded) {
                    s.aiBluffFolded = true;
                    rawBid = 0L;
                } else if (r == 1) {
                    rawBid = Math.round(estimate * (0.54D + RANDOM.nextDouble() * 0.12D));
                } else if (r == 2) {
                    rawBid = Math.round(estimate * (0.68D + RANDOM.nextDouble() * 0.10D));
                } else if (r == 3) {
                    rawBid = Math.round(estimate * (0.74D + RANDOM.nextDouble() * 0.08D));
                } else {
                    rawBid = RANDOM.nextDouble() < 0.30D ? 0L : Math.round(estimate * (0.76D + RANDOM.nextDouble() * 0.08D));
                }
            }
            case RAIDER -> {
                // 秒仓猎手：第 2~4 轮若计算上一轮头名的秒仓线仍有利可图，直接冲击秒仓倍率！
                if (r >= 2 && r <= 4 && buyoutMult > 1.0D && prevTop > 0) {
                    double strike = prevTop * buyoutMult * (1.01D + RANDOM.nextDouble() * 0.04D);
                    if (strike <= estimate * 0.86D) {
                        rawBid = Math.round(strike);
                        break;
                    }
                }
                double[] prog = {0.42D, 0.56D, 0.68D, 0.78D, 0.87D};
                rawBid = Math.round(estimate * prog[r - 1] * (0.96D + RANDOM.nextDouble() * 0.09D));
            }
            default -> {
                // 稳健估价派：按揭示进度稳步推进至期望价值的 80% 左右，绝不上头
                double[] prog = {0.32D, 0.45D, 0.58D, 0.70D, 0.81D};
                rawBid = Math.round(estimate * prog[r - 1] * (0.95D + RANDOM.nextDouble() * 0.10D));
                if (r >= 3 && prevSecond > estimate * 0.88D) {
                    rawBid = 0L;
                }
            }
        }
        long rounded = Math.max(0L, Math.round(rawBid / 100.0D) * 100L);
        return Math.min(cap, rounded);
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
                if (items.isEmpty()) break;
                Placed best = Collections.max(items, Comparator.comparingLong(Placed::value));
                if (r == 1) {
                    s.learn(best.id(), 2);
                    s.log(r, 1, badge, "全仓最贵的是一件「" + BlindCatalog.TIER_NAMES[best.relic().tier()] + "」藏品，占 "
                            + best.cells() + " 格，已标出位置");
                } else if (r == 3) {
                    s.learn(best.id(), 3);
                    s.log(r, 1, badge, "全仓最贵：" + best.displayName() + " " + fmt(best.value()) + " ★");
                }
            }
            case ARCHAEOLOGIST -> {
                if (r == 1) {
                    for (Placed p : items) s.learn(p.id(), 1);
                    s.log(r, 1, badge, "全仓 " + items.size() + " 件藏品轮廓已全部显示");
                } else {
                    List<Placed> cand = new ArrayList<>(items.stream().filter(p -> s.know[p.id()] < 2).toList());
                    Collections.shuffle(cand, RANDOM);
                    cand.sort(Comparator.comparingInt(Placed::cells).reversed());
                    int n = Math.min(2, cand.size());
                    for (int i = 0; i < n; i++) s.learn(cand.get(i).id(), 2);
                    s.log(r, 1, badge, "揭示了 " + n + " 件大件藏品的品质");
                }
            }
            case ENDER_EYE -> {
                int n = learnRandom(s, items, 1, 2, r == 1 ? 3 : 2);
                s.log(r, 1, badge, "揭示了 " + n + " 件藏品的品质");
            }
            case GOLD_EYE -> {
                if (r == 1) {
                    long low = items.stream().filter(p -> p.relic().tier() <= 2).mapToLong(Placed::value).sum();
                    s.log(r, 1, badge, "普通~稀有藏品合计价值 " + fmt(low) + " ★");
                } else if (r == 2) {
                    long duds = items.stream().filter(p -> p.grade() == 1).count();
                    long gems = items.stream().filter(p -> p.grade() == 2).count();
                    String comboMsg = room.comboBonus > 0
                            ? "；触发「" + BlindCatalog.SET_NAMES[room.comboSetId] + "」溢价 +" + fmt(room.comboBonus) + " ★"
                            : "；无套组溢价";
                    s.log(r, 1, badge, "残损 " + duds + " 件，完美 " + gems + " 件" + comboMsg);
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
                int tier = 6 - r;
                int cells = items.stream().filter(p -> p.relic().tier() == tier).mapToInt(Placed::cells).sum();
                s.log(r, 1, badge, "「" + BlindCatalog.TIER_NAMES[tier] + "」藏品共占 " + cells + " 格");
            }
            case STATISTICIAN -> {
                if (r == 1) {
                    s.log(r, 1, badge, "史诗及以上藏品共 " + items.stream().filter(p -> p.relic().tier() >= 3).count() + " 件");
                } else if (r == 2) {
                    String setInfo = room.comboSetId > 0
                            ? "，「" + BlindCatalog.SET_NAMES[room.comboSetId] + "」共 " + room.comboCount + " 件"
                            : "，未凑齐任何套组";
                    s.log(r, 1, badge, "神话藏品 " + items.stream().filter(p -> p.relic().tier() == 5).count() + " 件" + setInfo);
                } else if (r == 4) {
                    s.log(r, 1, badge, tierCounts(items));
                }
            }
            case GAMBLER -> {
                if (r == 1) {
                    s.log(r, 1, badge, "你报价第一时，秒仓倍率降低 " + BlindCatalog.GAMBLER_BUYOUT_CUT);
                } else if (r == 3) {
                    s.log(r, 1, badge, "随机揭示了 " + learnRandom(s, items, 1, 2, 2) + " 件藏品的品质");
                } else if (r == 5) {
                    for (Placed p : items) s.learn(p.id(), 2);
                    s.log(r, 1, badge, "全仓藏品品质已全部揭示");
                }
            }
        }
    }

    private static String tierCounts(List<Placed> items) {
        StringBuilder sb = new StringBuilder();
        for (int t = 0; t < BlindCatalog.TIER_NAMES.length; t++) {
            int tt = t;
            long n = items.stream().filter(x -> x.relic().tier() == tt).count();
            if (sb.length() > 0) sb.append(' ');
            sb.append(BlindCatalog.TIER_NAMES[t]).append(n);
        }
        return sb.toString();
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
                        s.log(r, 2, badge, it.displayName() + "（" + BlindCatalog.TIER_NAMES[it.relic().tier()] + "）价值 "
                                + fmt(it.value()) + " ★");
                    }
                }
            }
            case SCANNER -> s.log(r, 2, badge, "揭示了 " + learnRandom(s, items, 1, 2, 2) + " 件藏品的品质");
            case GLOWSTICK -> s.log(r, 2, badge, tierCounts(items));
            case SONAR -> {
                int cx = cell % BlindCatalog.COLS;
                int cy = cell / BlindCatalog.COLS;
                int[][] dirs = {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}};
                Set<Integer> hitOwners = new LinkedHashSet<>();
                int emptyMarked = 0;
                for (int[] d : dirs) {
                    int nx = cx + d[0];
                    int ny = cy + d[1];
                    if (nx < 0 || nx >= BlindCatalog.COLS || ny < 0 || ny >= BlindCatalog.ROWS) continue;
                    int nc = ny * BlindCatalog.COLS + nx;
                    int own = room.owner[nc];
                    if (own < 0) {
                        if (!s.knownEmpty[nc]) emptyMarked++;
                        s.knownEmpty[nc] = true;
                    } else {
                        hitOwners.add(own);
                        s.learn(own, 1);
                    }
                }
                if (hitOwners.isEmpty()) {
                    s.log(r, 2, badge, "十字区域全部为空格（已标出 " + emptyMarked + " 处空格）");
                } else {
                    long sumVal = 0L;
                    for (int own : hitOwners) sumVal += items.get(own).value();
                    double jitter = 0.94D + RANDOM.nextDouble() * 0.12D;
                    long loEst = Math.max(10L, Math.round(sumVal * jitter * 0.85D / 10.0D) * 10L);
                    long hiEst = Math.max(loEst + 10L, Math.round(sumVal * jitter * 1.16D / 10.0D) * 10L);
                    s.log(r, 2, badge, "十字命中 " + hitOwners.size() + " 件藏品轮廓（标空 " + emptyMarked
                            + " 格），区域估值约 " + fmt(loEst) + "~" + fmt(hiEst) + " ★");
                }
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
        long cap = room.practice ? BlindCatalog.practiceChipCap(room.zone) : balance(p.getUUID());
        if (amount > cap) {
            BlindAuctionNetwork.sendToast(p, room.practice ? "报价超过演练筹码上限" : "报价超过你的星币余额");
            return;
        }
        seat.bid = amount;
        seat.submitted = true;
        seat.submitOrder = ++room.submitCounter;
        if (allHumansDone(p.server, room)) {
            endRound(p.server, room);
        } else {
            BlindAuctionNetwork.sendToast(p, amount == 0 ? "本轮观望放弃（报价 0）" : "报价已锁定，等待其他席位");
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
        if (top.human() && Role.byId(top.role) == Role.GAMBLER) mult -= BlindCatalog.GAMBLER_BUYOUT_CUT;
        boolean instant = top.bid > 0 && (second > 0 ? top.bid >= Math.ceil(second * mult) : top.bid >= room.floor);
        if (instant) {
            finish(server, room, top, true);
            return;
        }
        logRoundTempo(room, r, top, second, mult);
        room.round++;
        startRound(room);
    }

    /** 记录上一轮各席位出价节奏与心理态势，辅助玩家进行《俱乐大玩家》式读牌反推。 */
    private static void logRoundTempo(BlindRoom room, int r, Seat top, long second, double mult) {
        StringBuilder sb = new StringBuilder();
        if (top.bid <= 0L) {
            sb.append("全员观望（0 ★）");
        } else {
            sb.append("头名 ").append(top.name).append(" ").append(fmt(top.bid)).append("★");
            if (second > 0L) {
                long need = (long) Math.ceil(second * mult);
                sb.append("（距秒仓差 ").append(fmt(Math.max(1L, need - top.bid))).append("★）");
            }
            sb.append(" · ");
            int count = 0;
            for (Seat s : room.seats) {
                long cur = s.bids[r - 1];
                long prev = r >= 2 ? Math.max(0L, s.bids[r - 2]) : 0L;
                String tag;
                if (cur <= 0L) {
                    tag = prev > room.floor ? "高位撤退" : "观望";
                } else if (cur < room.floor) {
                    tag = "试探";
                } else if (prev > 0L && cur >= Math.round(prev * 1.38D)) {
                    tag = "猛抬逼秒";
                } else if (prev > 0L && cur < prev) {
                    tag = "压价控盘";
                } else {
                    tag = "稳跟";
                }
                if (count++ > 0) sb.append(" / ");
                sb.append(s.name).append('[').append(tag).append(']');
            }
        }
        String msg = sb.toString();
        for (Seat s : room.seats) {
            if (s.human()) s.log(r, 0, "博弈", msg);
        }
    }

    // ================================================================== 开仓结算

    private static void finish(MinecraftServer server, BlindRoom room, Seat winner, boolean instant) {
        BlindAuctionSavedData data = BlindAuctionSavedData.get(server);
        boolean realEconomy = !room.practice && !room.hasAi();
        long truth = room.trueValue();
        long tax = winner == null ? 0L : Math.round(truth * BlindAuctionConfig.CHAMBER_SINK_TAX_RATE.get());
        long bid = winner == null ? 0L : winner.bid;
        long profit = truth - tax - bid;
        boolean defaulted = false;
        Map<Seat, Long> dividends = new HashMap<>();

        // 严密经济锁：只有 100% 纯真人联机对局（realEconomy == true）才动真实星币账户；
        // AI 演练对局或任何含 AI 的房间绝不发放或扣除任何星币！
        if (realEconomy && winner != null && winner.human()) {
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
        res.putBoolean("practice", !realEconomy);
        res.putInt("theme", room.theme.ordinal());
        res.putInt("comboSet", room.comboSetId);
        res.putLong("comboBonus", room.comboBonus);
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
        res.putString("topItem", top == null ? "" : top.displayName());
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
            if (s.human() && s.feePaid > 0L) data.ledgerRemove(s.uuid, s.feePaid);
        }
        if (realEconomy) {
            data.pushHistory(res);
        }

        String prefix = realEconomy ? "【遗迹盲仓】" : "【遗迹盲仓·AI演练】";
        String suffix = realEconomy ? "" : "（演练局不结算星币）";
        String comboTxt = room.comboBonus > 0
                ? "（含「" + BlindCatalog.SET_NAMES[room.comboSetId] + "」共鸣 +" + fmt(room.comboBonus) + " ★）"
                : "";
        String line = winner == null
                ? prefix + room.zone.title + " 无人出价，流拍。开仓真实价值 " + fmt(truth) + " ★" + comboTxt + suffix
                : prefix + room.zone.title + "：" + winner.name + (instant ? " 秒仓" : " 第5轮") + "以 " + fmt(bid)
                        + " ★ 中标，开仓真实价值 " + fmt(truth) + " ★" + comboTxt
                        + (defaulted ? "（余额不足，违约流拍）" : profit >= 0 ? "，模拟净赚 " + fmt(profit) + " ★" : "，模拟净亏 " + fmt(-profit) + " ★")
                        .replace("模拟净", realEconomy ? "" : "演练") + suffix;
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
        boolean autoAi = BlindAuctionConfig.MULTIPLAYER_AUTO_AI_FILL.get();
        for (Map.Entry<Integer, Integer> e : new ArrayList<>(QUEUE_WAIT.entrySet())) {
            int zoneId = e.getKey();
            VaultZone zone = VaultZone.byId(zoneId);
            LinkedHashSet<UUID> queue = QUEUES.get(zoneId);
            if (queue == null || queue.isEmpty()) {
                QUEUE_WAIT.remove(zoneId);
                continue;
            }
            int minHumans = Math.max(2, Math.min(zone.seatCount, BlindAuctionConfig.MIN_MULTIPLAYER_PLAYERS.get()));
            if (!autoAi && (minHumans >= zone.seatCount || queue.size() < minHumans)) {
                QUEUE_WAIT.remove(zoneId);
                syncQueue(server, zoneId);
                continue;
            }
            int left = e.getValue() - 1;
            if (left <= 0) {
                int take = Math.min(queue.size(), zone.seatCount);
                List<UUID> players = new ArrayList<>(new ArrayList<>(queue).subList(0, take));
                players.forEach(queue::remove);
                QUEUE_WAIT.remove(zoneId);
                refreshQueueWait(zone, queue);
                // 若未开启 autoAi，则纯真人开局（fillAi=false, practice=false）；若开启 autoAi，强制按演练局处理防刷钱
                launch(server, zone, players, autoAi, autoAi);
                syncQueue(server, zoneId);
            } else {
                QUEUE_WAIT.put(zoneId, left);
                syncQueue(server, zoneId);
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
        m.putBoolean("practice", room.practice || room.hasAi());
        m.putLong("practiceCap", BlindCatalog.practiceChipCap(room.zone));
        m.putInt("theme", room.theme.ordinal());
        m.putInt("comboSet", over || room.round >= 2 ? room.comboSetId : 0);
        m.putLong("comboBonus", over ? room.comboBonus : 0L);
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
                t.putString("name", it.displayName());
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

    // ================================================================== 经济（仅用于纯真人联机）

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
