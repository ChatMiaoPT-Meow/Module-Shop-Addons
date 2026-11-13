package cn.autoforged.module_shop_quest.server;

import cn.autoforged.module_shop.api.ModuleShopApi;
import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.config.ConfigModels.OnlineMilestoneSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.PassLevelSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.PeriodClock;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestDefSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestPoolsConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestTaskType;
import cn.autoforged.module_shop_quest.config.ConfigModels.RewardBundleSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.SeasonPassConfig;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import cn.autoforged.module_shop_quest.config.QuestTomlConfig;
import cn.autoforged.module_shop_quest.data.QuestSavedData;
import cn.autoforged.module_shop_quest.data.QuestSavedData.PlayerQuestProfile;
import cn.autoforged.module_shop_quest.data.QuestSavedData.QuestInstance;
import cn.autoforged.module_shop_quest.network.QuestNetwork;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * 星途纪行服务端总控编排器：
 * <ul>
 *     <li>管理赛季时间窗、跨赛季结转与 7 天补领窗口；</li>
 *     <li>执行按时区跨日/跨周轮转、权重抽池与软依赖卸载自动降级替换（§13.7）；</li>
 *     <li>处理任务进度推进、单笔领奖、一键全领及客户端完整快照编码。</li>
 * </ul>
 */
public final class QuestManager {
    private QuestManager() {
    }

    public static Set<String> loadedModIds() {
        Set<String> set = new HashSet<>();
        ModList.get().getMods().forEach(info -> set.add(info.getModId()));
        return set;
    }

    /** 确保世界存档中的赛季窗口已初始化，并在赛季到期或配置切换 seasonId 时平滑结转。 */
    public static void ensureSeasonInitialized(MinecraftServer server) {
        QuestSavedData data = QuestSavedData.get(server);
        SeasonPassConfig passCfg = QuestDataConfig.seasonPass();
        long nowSec = Instant.now().getEpochSecond();

        if (data.activeSeasonId().isBlank()) {
            long start = passCfg.seasonStartEpochSecond > 0L ? passCfg.seasonStartEpochSecond : nowSec;
            long end = start + (long) passCfg.durationWeeks * 7L * 86400L;
            data.updateSeasonWindow(passCfg.seasonId, start, end);
            data.updateCurrentSeasonSnapshot(passCfg);
            return;
        }

        // 若管理员在 season_pass.json 中显式切换了新的 seasonId，执行跨赛季归档与积分结转
        if (!data.activeSeasonId().equals(passCfg.seasonId)) {
            rollToNewSeason(server, data, passCfg.seasonId, nowSec);
            return;
        }

        data.updateCurrentSeasonSnapshot(passCfg);

        // 若赛季持续周数在热重载中被延长，同步更新结束时间（§6.3 允许延长当前赛季）
        long configuredEnd = data.seasonStartEpochSec() + (long) passCfg.durationWeeks * 7L * 86400L;
        if (configuredEnd > data.seasonEndEpochSec()) {
            data.updateSeasonWindow(passCfg.seasonId, data.seasonStartEpochSec(), configuredEnd);
        }
    }

    /** 切换至新赛季：归档上赛季宽限领奖快照并按比例折算结转积分。 */
    public static void rollToNewSeason(MinecraftServer server, QuestSavedData data, String newSeasonId, long nowSec) {
        SeasonPassConfig passCfg = QuestDataConfig.seasonPass();
        String oldSeasonId = data.activeSeasonId();
        long graceUntil = nowSec + (long) passCfg.graceClaimDaysAfterEnd * 86400L;

        data.archiveSeason(oldSeasonId, graceUntil);
        long endSec = nowSec + (long) passCfg.durationWeeks * 7L * 86400L;
        data.updateSeasonWindow(newSeasonId, nowSec, endSec);
        data.updateCurrentSeasonSnapshot(passCfg);

        for (PlayerQuestProfile profile : data.allProfiles().values()) {
            rotatePlayerSeason(profile, oldSeasonId, newSeasonId, graceUntil, passCfg);
        }
        data.setDirty();
        QuestMod.LOGGER.info("[{}] 赛季已从 {} 平滑切换至 {}（宽限期 {} 天，结转比例 {}%，上限 {}）",
                QuestMod.MODID, oldSeasonId, newSeasonId,
                passCfg.graceClaimDaysAfterEnd, passCfg.rolloverPercent, passCfg.rolloverCapPoints);
    }

    private static void rotatePlayerSeason(PlayerQuestProfile profile, String oldSeasonId,
                                           String newSeasonId, long graceUntil, SeasonPassConfig passCfg) {
        if (newSeasonId.equals(profile.seasonId)) {
            return;
        }
        if (profile.seasonId != null && !profile.seasonId.isBlank() && profile.seasonPoints > 0L) {
            profile.prevSeasonId = profile.seasonId;
            profile.prevSeasonPoints = profile.seasonPoints;
            profile.prevSeasonPremiumUnlocked = profile.premiumUnlocked;
            profile.prevSeasonGraceUntilSec = graceUntil;
        }
        long carried = passCfg.computeRolloverPoints(profile.seasonPoints);
        profile.seasonId = newSeasonId;
        profile.seasonPoints = carried;
        profile.premiumUnlocked = false;
        profile.deluxeUnlocked = false;
        profile.premiumUnlockedAt = 0L;
        profile.pruneArchivedClaimKeys(newSeasonId, oldSeasonId);
    }

    /** 检查并更新玩家当前的赛季、日切与周切状态，同时校验软依赖模组存活性（§13.7）。 */
    public static void ensurePlayerUpToDate(ServerPlayer player, QuestSavedData data) {
        ensureSeasonInitialized(player.server);
        SeasonPassConfig passCfg = QuestDataConfig.seasonPass();
        PlayerQuestProfile profile = data.profile(player.getUUID());

        long nowSec = Instant.now().getEpochSecond();
        ZoneId zone = QuestTomlConfig.zoneId();
        int resetHour = QuestTomlConfig.DAILY_RESET_HOUR.get();
        int resetDow = QuestTomlConfig.WEEKLY_RESET_DAY_OF_WEEK.get();

        // 1. 赛季对齐
        if (profile.seasonId == null || profile.seasonId.isBlank()) {
            profile.seasonId = data.activeSeasonId();
            data.setDirty();
        } else if (!profile.seasonId.equals(data.activeSeasonId())) {
            rotatePlayerSeason(profile, profile.seasonId, data.activeSeasonId(),
                    data.archivedGraceUntilEpochSec() > 0L ? data.archivedGraceUntilEpochSec()
                            : (nowSec + (long) passCfg.graceClaimDaysAfterEnd * 86400L),
                    passCfg);
            data.setDirty();
        }

        // VIP 权限节点自动解锁进阶线（可选开关）
        if (!profile.premiumUnlocked && passCfg.premiumTrack != null
                && passCfg.premiumTrack.enabled && passCfg.premiumTrack.allowPermissionBypass
                && player.hasPermissions(2)) {
            profile.premiumUnlocked = true;
            profile.premiumUnlockedAt = nowSec;
            data.setDirty();
        }

        Set<String> mods = loadedModIds();

        // 2. 跨日轮转
        String todayKey = PeriodClock.dayKey(nowSec, zone, resetHour);
        if (!todayKey.equals(profile.dayKey)) {
            if (profile.dayKey != null && !profile.dayKey.isBlank()) {
                profile.prevDayKey = profile.dayKey;
                profile.prevDayOnlineSeconds = profile.dailyOnlineSeconds;
                profile.prevDayQuests = new ArrayList<>(profile.dailyQuests);
            }
            profile.dayKey = todayKey;
            profile.dailyOnlineSeconds = 0;
            long seed = player.getUUID().getMostSignificantBits() ^ todayKey.hashCode() ^ 0x9E3779B97F4A7C15L;
            List<QuestDefSpec> drawn = QuestPoolsConfig.drawQuests(
                    QuestDataConfig.questPools().dailyPool,
                    QuestTomlConfig.DAILY_QUEST_COUNT.get(),
                    QuestTomlConfig.MIN_MOD_QUEST_RATIO.get(),
                    mods,
                    true,
                    new Random(seed)
            );
            profile.dailyQuests = toInstances(drawn);

            // 若玩家在跨日重置前已通过自动签到完成了今日商店签到，自动对齐签到任务进度
            var signView = ModuleShopApi.daily().signIn(player.getUUID());
            if (signView != null && signView.signedToday()) {
                advanceList(profile.dailyQuests, QuestTaskType.SHOP_SIGN_IN, "", 1);
            }
            data.setDirty();
        }

        // 3. 跨周轮转
        String currentWeekKey = PeriodClock.weekKey(nowSec, zone, resetHour, resetDow);
        if (!currentWeekKey.equals(profile.weekKey)) {
            profile.weekKey = currentWeekKey;
            profile.weeklyOnlineSeconds = 0;
            long seed = player.getUUID().getLeastSignificantBits() ^ currentWeekKey.hashCode() ^ 0xBF58476D1CE4E5B9L;
            List<QuestDefSpec> drawn = QuestPoolsConfig.drawQuests(
                    QuestDataConfig.questPools().weeklyPool,
                    QuestTomlConfig.WEEKLY_QUEST_COUNT.get(),
                    QuestTomlConfig.MIN_MOD_QUEST_RATIO.get(),
                    mods,
                    false,
                    new Random(seed)
            );
            profile.weeklyQuests = toInstances(drawn);
            data.setDirty();
        }

        // 4. §13.7：软依赖模组中途卸载自愈（免费替换 1 次可用任务，无可用项则标失效不卡板）
        if (healMissingSoftDeps(profile.dailyQuests, QuestDataConfig.questPools().dailyPool, mods, true)) {
            data.setDirty();
        }
        if (healMissingSoftDeps(profile.weeklyQuests, QuestDataConfig.questPools().weeklyPool, mods, false)) {
            data.setDirty();
        }
    }

    private static boolean healMissingSoftDeps(List<QuestInstance> active, List<QuestDefSpec> pool,
                                               Set<String> loadedMods, boolean daily) {
        if (active == null || active.isEmpty()) {
            return false;
        }
        boolean changed = false;
        Set<String> existingIds = new HashSet<>();
        for (QuestInstance q : active) {
            existingIds.add(q.id);
        }
        for (int i = 0; i < active.size(); i++) {
            QuestInstance q = active.get(i);
            if (q.invalidated || QuestPoolsConfig.isModRequirementMet(q.requiredMod, loadedMods)) {
                continue;
            }
            if (!q.replacedOnce) {
                QuestDefSpec replacement = findReplacement(pool, existingIds, loadedMods, daily);
                if (replacement != null) {
                    QuestInstance fresh = QuestInstance.fromDef(replacement);
                    fresh.replacedOnce = true;
                    active.set(i, fresh);
                    existingIds.add(fresh.id);
                    changed = true;
                    QuestMod.LOGGER.info("[{}] 软依赖模组 {} 未加载，已按 §13.7 将任务 {} 免费替换为 {}",
                            QuestMod.MODID, q.requiredMod, q.id, fresh.id);
                    continue;
                }
            }
            q.invalidated = true;
            changed = true;
            QuestMod.LOGGER.warn("[{}] 软依赖模组 {} 未加载且无可用替代项，已将任务 {} 标记为失效",
                    QuestMod.MODID, q.requiredMod, q.id);
        }
        return changed;
    }

    private static QuestDefSpec findReplacement(List<QuestDefSpec> pool, Set<String> existingIds,
                                                Set<String> loadedMods, boolean daily) {
        if (pool == null) {
            return null;
        }
        for (QuestDefSpec candidate : pool) {
            if (candidate == null || existingIds.contains(candidate.id)) {
                continue;
            }
            QuestTaskType qt = candidate.parsedType();
            if (qt == null || (daily && qt.isForbiddenInDaily())) {
                continue;
            }
            if (QuestPoolsConfig.isModRequirementMet(candidate.requiredMod, loadedMods)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<QuestInstance> toInstances(List<QuestDefSpec> defs) {
        List<QuestInstance> list = new ArrayList<>(defs.size());
        for (QuestDefSpec def : defs) {
            list.add(QuestInstance.fromDef(def));
        }
        return list;
    }

    // ================================================================ 任务进度推进

    /** 判断玩家当前是否有待完成的特定类型激活任务（用于原版高频事件快速短路，未抽出时不记账）。 */
    public static boolean hasActiveUnfinishedType(MinecraftServer server, UUID playerId, QuestTaskType type) {
        QuestSavedData.PlayerQuestProfile profile = QuestSavedData.get(server).peekProfile(playerId);
        if (profile == null) {
            return false;
        }
        return containsActiveType(profile.dailyQuests, type) || containsActiveType(profile.weeklyQuests, type);
    }

    private static boolean containsActiveType(List<QuestInstance> list, QuestTaskType target) {
        if (list == null) {
            return false;
        }
        for (QuestInstance q : list) {
            if (!q.invalidated && !q.isCompleted() && matchesTaskType(q.parsedType(), target)) {
                return true;
            }
        }
        return false;
    }

    /** 推进玩家任务进度（必须在服务端主线程调用）。 */
    public static void recordProgress(MinecraftServer server, UUID playerId, QuestTaskType eventType,
                                      String targetKey, int amount) {
        if (server == null || playerId == null || eventType == null || amount <= 0 || !QuestTomlConfig.ENABLED.get()) {
            return;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        QuestSavedData data = QuestSavedData.get(server);
        if (online != null) {
            ensurePlayerUpToDate(online, data);
        }
        PlayerQuestProfile profile = data.profile(playerId);

        boolean changedDaily = advanceList(profile.dailyQuests, eventType, targetKey, amount);
        boolean changedWeekly = advanceList(profile.weeklyQuests, eventType, targetKey, amount);

        if (changedDaily || changedWeekly) {
            data.setDirty();
            if (online != null) {
                syncFullSnapshot(online);
            }
        }
    }

    private static boolean advanceList(List<QuestInstance> list, QuestTaskType eventType,
                                       String targetKey, int amount) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        boolean anyChanged = false;
        for (QuestInstance q : list) {
            if (q.invalidated || q.isCompleted()) {
                continue;
            }
            QuestTaskType qType = q.parsedType();
            if (qType == null || !matchesTaskType(qType, eventType)) {
                continue;
            }
            if (!q.matchesFilter(targetKey)) {
                continue;
            }
            int next = Math.min(q.targetCount, q.progress + amount);
            if (next > q.progress) {
                q.progress = next;
                anyChanged = true;
            }
        }
        return anyChanged;
    }

    private static boolean matchesTaskType(QuestTaskType questType, QuestTaskType incomingEvent) {
        if (questType == incomingEvent) {
            return true;
        }
        // SHOP_BUY_ANY 兼容每日商店、购物城与玩家摊位购买
        if (questType == QuestTaskType.SHOP_BUY_ANY
                && (incomingEvent == QuestTaskType.SHOP_BUY_DAILY
                || incomingEvent == QuestTaskType.SHOP_BUY_MALL
                || incomingEvent == QuestTaskType.STALL_BUY)) {
            return true;
        }
        // SHOP_SELL_BUYBACK 兼容每日收购与出货箱出售
        if (questType == QuestTaskType.SHOP_SELL_BUYBACK
                && incomingEvent == QuestTaskType.SHOP_SHIPPING_SELL) {
            return true;
        }
        // STALL_BUY 兼容市集互动（购买玩家摊位或参与拍卖出价）
        return questType == QuestTaskType.STALL_BUY && incomingEvent == QuestTaskType.AUCTION_BID;
    }

    // ================================================================ 领奖处理（任务 / 在线 / 一键全领）

    public static void handleClaimQuest(ServerPlayer player, String period, String questId) {
        QuestSavedData data = QuestSavedData.get(player.server);
        ensurePlayerUpToDate(player, data);
        PlayerQuestProfile profile = data.profile(player.getUUID());

        RewardGrantService.Outcome out = tryClaimQuest(player, data, profile, period, questId);
        if (!out.ok()) {
            QuestNetwork.sendActionResult(player, false, out.errorKey(), "error", false);
            return;
        }
        QuestNetwork.sendActionResult(player, true, "msg.module_shop_quest.claimed_ok", "claim", true);
    }

    public static RewardGrantService.Outcome tryClaimQuest(
            ServerPlayer player, QuestSavedData data, PlayerQuestProfile profile, String period, String questId) {
        String seasonId = QuestDataConfig.seasonPass().seasonId;
        List<QuestInstance> list;
        String kind;
        String periodKey;
        String reasonPrefix;

        if ("weekly".equalsIgnoreCase(period)) {
            list = profile.weeklyQuests;
            kind = "weekly";
            periodKey = profile.weekKey;
            reasonPrefix = "每周任务奖励：";
        } else if ("prev_daily".equalsIgnoreCase(period)) {
            if (!QuestTomlConfig.ENABLE_PREV_DAY_GRACE_CLAIM.get() || profile.prevDayKey.isBlank()) {
                return RewardGrantService.Outcome.fail("msg.module_shop_quest.grace_expired");
            }
            list = profile.prevDayQuests;
            kind = "daily";
            periodKey = profile.prevDayKey;
            reasonPrefix = "昨日任务补领：";
        } else {
            list = profile.dailyQuests;
            kind = "daily";
            periodKey = profile.dayKey;
            reasonPrefix = "每日任务奖励：";
        }

        QuestInstance target = null;
        for (QuestInstance q : list) {
            if (q.id.equals(questId)) {
                target = q;
                break;
            }
        }
        if (target == null || target.invalidated) {
            return RewardGrantService.Outcome.fail("msg.module_shop_quest.quest_not_found");
        }
        if (!target.isCompleted()) {
            return RewardGrantService.Outcome.fail("msg.module_shop_quest.quest_incomplete");
        }

        RewardBundleSpec reward = resolveLiveQuestReward(target, "weekly".equalsIgnoreCase(period));
        String reqId = PeriodClock.formatRequestId(seasonId, player.getUUID(), kind, periodKey, target.id);
        return RewardGrantService.grantIdempotent(
                player, data, profile, reqId, reward, true, reasonPrefix + target.title);
    }

    public static void handleClaimOnline(ServerPlayer player, String period, String tierId) {
        QuestSavedData data = QuestSavedData.get(player.server);
        ensurePlayerUpToDate(player, data);
        PlayerQuestProfile profile = data.profile(player.getUUID());

        RewardGrantService.Outcome out = tryClaimOnline(player, data, profile, period, tierId);
        if (!out.ok()) {
            QuestNetwork.sendActionResult(player, false, out.errorKey(), "error", false);
            return;
        }
        QuestNetwork.sendActionResult(player, true, "msg.module_shop_quest.claimed_ok", "claim", true);
    }

    public static RewardGrantService.Outcome tryClaimOnline(
            ServerPlayer player, QuestSavedData data, PlayerQuestProfile profile, String period, String tierId) {
        String seasonId = QuestDataConfig.seasonPass().seasonId;
        List<OnlineMilestoneSpec> tiers;
        int currentMinutes;
        String kind;
        String periodKey;
        String reasonPrefix;

        if ("weekly".equalsIgnoreCase(period)) {
            tiers = QuestDataConfig.onlineRewards().weeklyMilestones;
            currentMinutes = profile.weeklyOnlineSeconds / 60;
            kind = "online-week";
            periodKey = profile.weekKey;
            reasonPrefix = "周在线里程奖励：";
        } else if ("prev_daily".equalsIgnoreCase(period)) {
            if (!QuestTomlConfig.ENABLE_PREV_DAY_GRACE_CLAIM.get() || profile.prevDayKey.isBlank()) {
                return RewardGrantService.Outcome.fail("msg.module_shop_quest.grace_expired");
            }
            tiers = QuestDataConfig.onlineRewards().dailyMilestones;
            currentMinutes = profile.prevDayOnlineSeconds / 60;
            kind = "online-day";
            periodKey = profile.prevDayKey;
            reasonPrefix = "昨日在线补领：";
        } else {
            tiers = QuestDataConfig.onlineRewards().dailyMilestones;
            currentMinutes = profile.dailyOnlineSeconds / 60;
            kind = "online-day";
            periodKey = profile.dayKey;
            reasonPrefix = "日在线里程奖励：";
        }

        OnlineMilestoneSpec found = null;
        for (OnlineMilestoneSpec m : tiers) {
            if (m.id.equals(tierId)) {
                found = m;
                break;
            }
        }
        if (found == null) {
            return RewardGrantService.Outcome.fail("msg.module_shop_quest.invalid_reward");
        }
        if (currentMinutes < found.requiredMinutes) {
            return RewardGrantService.Outcome.fail("msg.module_shop_quest.online_not_reached");
        }

        String reqId = PeriodClock.formatRequestId(seasonId, player.getUUID(), kind, periodKey, found.id);
        return RewardGrantService.grantIdempotent(
                player, data, profile, reqId, found.reward, true, reasonPrefix + found.title);
    }

    /** 一键领取全部可达奖励（先领任务与在线里程累积积分，再一次性领取所有新升达的通行证格子！）。 */
    public static void handleClaimAll(ServerPlayer player) {
        QuestSavedData data = QuestSavedData.get(player.server);
        ensurePlayerUpToDate(player, data);
        PlayerQuestProfile profile = data.profile(player.getUUID());
        String seasonId = QuestDataConfig.seasonPass().seasonId;

        int claimedTotal = 0;

        // 1. 今日任务
        for (QuestInstance q : profile.dailyQuests) {
            String key = PeriodClock.formatRequestId(seasonId, player.getUUID(), "daily", profile.dayKey, q.id);
            if (q.isCompleted() && !profile.hasClaimed(key)) {
                if (tryClaimQuest(player, data, profile, "daily", q.id).ok()) {
                    claimedTotal++;
                }
            }
        }
        // 2. 昨日宽限补领任务
        if (QuestTomlConfig.ENABLE_PREV_DAY_GRACE_CLAIM.get() && !profile.prevDayKey.isBlank()) {
            for (QuestInstance q : profile.prevDayQuests) {
                String key = PeriodClock.formatRequestId(seasonId, player.getUUID(), "daily", profile.prevDayKey, q.id);
                if (q.isCompleted() && !profile.hasClaimed(key)) {
                    if (tryClaimQuest(player, data, profile, "prev_daily", q.id).ok()) {
                        claimedTotal++;
                    }
                }
            }
        }
        // 3. 本周任务
        for (QuestInstance q : profile.weeklyQuests) {
            String key = PeriodClock.formatRequestId(seasonId, player.getUUID(), "weekly", profile.weekKey, q.id);
            if (q.isCompleted() && !profile.hasClaimed(key)) {
                if (tryClaimQuest(player, data, profile, "weekly", q.id).ok()) {
                    claimedTotal++;
                }
            }
        }
        // 4. 日在线里程 + 昨日在线宽限 + 周在线里程
        int dayMin = profile.dailyOnlineSeconds / 60;
        for (OnlineMilestoneSpec m : QuestDataConfig.onlineRewards().dailyMilestones) {
            String key = PeriodClock.formatRequestId(seasonId, player.getUUID(), "online-day", profile.dayKey, m.id);
            if (dayMin >= m.requiredMinutes && !profile.hasClaimed(key)) {
                if (tryClaimOnline(player, data, profile, "daily", m.id).ok()) {
                    claimedTotal++;
                }
            }
        }
        if (QuestTomlConfig.ENABLE_PREV_DAY_GRACE_CLAIM.get() && !profile.prevDayKey.isBlank()) {
            int prevMin = profile.prevDayOnlineSeconds / 60;
            for (OnlineMilestoneSpec m : QuestDataConfig.onlineRewards().dailyMilestones) {
                String key = PeriodClock.formatRequestId(seasonId, player.getUUID(), "online-day", profile.prevDayKey, m.id);
                if (prevMin >= m.requiredMinutes && !profile.hasClaimed(key)) {
                    if (tryClaimOnline(player, data, profile, "prev_daily", m.id).ok()) {
                        claimedTotal++;
                    }
                }
            }
        }
        int weekMin = profile.weeklyOnlineSeconds / 60;
        for (OnlineMilestoneSpec m : QuestDataConfig.onlineRewards().weeklyMilestones) {
            String key = PeriodClock.formatRequestId(seasonId, player.getUUID(), "online-week", profile.weekKey, m.id);
            if (weekMin >= m.requiredMinutes && !profile.hasClaimed(key)) {
                if (tryClaimOnline(player, data, profile, "weekly", m.id).ok()) {
                    claimedTotal++;
                }
            }
        }

        // 5. 积分刚刚实时入账后，紧接着领取所有新解锁的通行证免费线与进阶线等级奖励！
        BattlePassService.OutcomeSummary passRes = BattlePassService.claimAllReachablePassRewards(player, data, profile);
        claimedTotal += passRes.claimedCount();

        if (claimedTotal <= 0) {
            QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.nothing_to_claim", "error", false);
            return;
        }
        QuestNetwork.sendActionResult(player, true, "msg.module_shop_quest.claimed_all_ok", "claim", true);
    }

    /** 热重载全部 JSON 配置并向所有在线玩家广播最新快照。 */
    public static void reloadAllConfigs(MinecraftServer server, List<String> warningsOut) {
        QuestDataConfig.loadOrCreateAll(warningsOut);
        if (server != null) {
            ensureSeasonInitialized(server);
            QuestAdminService.syncExistingQuestMetadata(QuestSavedData.get(server));
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                syncFullSnapshot(p);
            }
        }
    }

    private static RewardBundleSpec resolveLiveQuestReward(QuestInstance inst, boolean weekly) {
        List<QuestDefSpec> pool = weekly
                ? QuestDataConfig.questPools().weeklyPool
                : QuestDataConfig.questPools().dailyPool;
        if (pool != null) {
            for (QuestDefSpec def : pool) {
                if (def != null && def.id.equals(inst.id) && def.reward != null) {
                    return def.reward;
                }
            }
        }
        return inst.fallbackReward != null ? inst.fallbackReward : new RewardBundleSpec();
    }

    // ================================================================ 快照构建与未领计数

    public static void syncFullSnapshot(ServerPlayer player) {
        CompoundTag wrapper = new CompoundTag();
        wrapper.putString("type", "snapshot");
        wrapper.put("snapshot", buildSnapshotTag(player));
        QuestNetwork.sendToPlayer(player, wrapper);
    }

    public static int countTotalClaimable(ServerPlayer player, QuestSavedData data, PlayerQuestProfile profile) {
        SeasonPassConfig passCfg = QuestDataConfig.seasonPass();
        String seasonId = passCfg.seasonId;
        int count = 0;

        for (PassLevelSpec spec : passCfg.resolvedLevels()) {
            if (profile.seasonPoints < spec.requiredPoints) {
                break;
            }
            String fk = PeriodClock.formatRequestId(seasonId, player.getUUID(), "pass", "season", String.valueOf(spec.level));
            if (!profile.hasClaimed(fk) && spec.freeReward != null && !spec.freeReward.isEmpty()) {
                count++;
            }
            if (profile.premiumUnlocked && passCfg.premiumTrack != null && passCfg.premiumTrack.enabled) {
                String pk = PeriodClock.formatRequestId(seasonId, player.getUUID(), "pass-extra", "season", String.valueOf(spec.level));
                if (!profile.hasClaimed(pk) && spec.premiumReward != null && !spec.premiumReward.isEmpty()) {
                    count++;
                }
            }
        }
        for (QuestInstance q : profile.dailyQuests) {
            String k = PeriodClock.formatRequestId(seasonId, player.getUUID(), "daily", profile.dayKey, q.id);
            if (q.isCompleted() && !profile.hasClaimed(k)) {
                count++;
            }
        }
        for (QuestInstance q : profile.weeklyQuests) {
            String k = PeriodClock.formatRequestId(seasonId, player.getUUID(), "weekly", profile.weekKey, q.id);
            if (q.isCompleted() && !profile.hasClaimed(k)) {
                count++;
            }
        }
        int dMin = profile.dailyOnlineSeconds / 60;
        for (OnlineMilestoneSpec m : QuestDataConfig.onlineRewards().dailyMilestones) {
            String k = PeriodClock.formatRequestId(seasonId, player.getUUID(), "online-day", profile.dayKey, m.id);
            if (dMin >= m.requiredMinutes && !profile.hasClaimed(k)) {
                count++;
            }
        }
        int wMin = profile.weeklyOnlineSeconds / 60;
        for (OnlineMilestoneSpec m : QuestDataConfig.onlineRewards().weeklyMilestones) {
            String k = PeriodClock.formatRequestId(seasonId, player.getUUID(), "online-week", profile.weekKey, m.id);
            if (wMin >= m.requiredMinutes && !profile.hasClaimed(k)) {
                count++;
            }
        }
        return count;
    }

    public static CompoundTag buildSnapshotTag(ServerPlayer player) {
        QuestSavedData data = QuestSavedData.get(player.server);
        ensurePlayerUpToDate(player, data);
        PlayerQuestProfile profile = data.profile(player.getUUID());
        HolderLookup.Provider regs = player.registryAccess();

        SeasonPassConfig passCfg = QuestDataConfig.seasonPass();
        List<PassLevelSpec> levels = passCfg.resolvedLevels();
        long nowSec = Instant.now().getEpochSecond();
        ZoneId zone = QuestTomlConfig.zoneId();
        int resetHour = QuestTomlConfig.DAILY_RESET_HOUR.get();
        int resetDow = QuestTomlConfig.WEEKLY_RESET_DAY_OF_WEEK.get();

        CompoundTag root = new CompoundTag();
        root.putLong("serverNowSec", nowSec);
        root.putString("zoneId", zone.getId());
        root.putLong("nextDailyResetSec", PeriodClock.nextDailyResetEpochSec(nowSec, zone, resetHour));
        root.putLong("nextWeeklyResetSec", PeriodClock.nextWeeklyResetEpochSec(nowSec, zone, resetHour, resetDow));
        root.putBoolean("isOp", player.hasPermissions(2));
        root.putLong("walletBalance", ModuleShopApi.economy().balance(player.getUUID()));

        // 1. 赛季与双轨通行证数据
        root.putString("seasonId", passCfg.seasonId);
        root.putString("seasonName", passCfg.seasonName);
        root.putLong("seasonEndSec", data.seasonEndEpochSec());
        root.putLong("seasonPoints", profile.seasonPoints);
        int curLv = passCfg.computeLevel(profile.seasonPoints);
        root.putInt("currentLevel", curLv);
        root.putInt("maxLevel", levels.size());
        long prevReq = curLv >= 1 ? levels.get(curLv - 1).requiredPoints : 0L;
        long nextReq = curLv < levels.size() ? levels.get(curLv).requiredPoints : prevReq;
        root.putLong("levelPrevReq", prevReq);
        root.putLong("levelNextReq", nextReq);

        root.putString("freeTrackName", passCfg.freeTrackName == null ? "免费奖励" : passCfg.freeTrackName);
        boolean premEnabled = passCfg.premiumTrack != null && passCfg.premiumTrack.enabled;
        root.putBoolean("premiumEnabled", premEnabled);
        root.putString("premiumTrackName", premEnabled ? passCfg.premiumTrack.trackName : "进阶通行证");
        root.putLong("premiumUnlockCost", premEnabled ? passCfg.premiumTrack.unlockStarCoinCost : 0L);
        root.putLong("premiumBonusPoints", premEnabled ? passCfg.premiumTrack.unlockBonusPoints : 0L);
        root.putBoolean("premiumUnlocked", profile.premiumUnlocked);

        boolean deluxeEnabled = premEnabled && passCfg.premiumTrack.deluxeEnabled;
        root.putBoolean("deluxeEnabled", deluxeEnabled);
        root.putString("deluxeTrackName", deluxeEnabled ? passCfg.premiumTrack.deluxeTrackName : "典藏通行证");
        root.putLong("deluxeUnlockCost", deluxeEnabled ? passCfg.premiumTrack.deluxeStarCoinCost : 0L);
        root.putLong("deluxeOrigCost", deluxeEnabled ? passCfg.premiumTrack.deluxeOriginalStarCoinCost : 0L);
        root.putInt("deluxeBonusLevels", deluxeEnabled ? passCfg.premiumTrack.deluxeBonusLevels : 0);
        root.putBoolean("deluxeUnlocked", profile.deluxeUnlocked);
        root.put("deluxeReward", encodeRewardBundle(
                deluxeEnabled ? passCfg.premiumTrack.deluxeExtraReward : null, regs));

        boolean allowBuyLv = passCfg.pointsRule != null && passCfg.pointsRule.allowBuyLevels;
        root.putBoolean("allowBuyLevels", allowBuyLv);
        root.putLong("buyLevelCost", passCfg.pointsRule != null ? passCfg.pointsRule.buyLevelStarCoinCost : 150L);

        root.putInt("rolloverPercent", passCfg.rolloverPercent);
        root.putLong("rolloverCap", passCfg.rolloverCapPoints);

        // 上赛季宽限补领统计（使用归档快照等级表）
        int prevGraceUnclaimed = 0;
        if (profile.prevSeasonId != null && !profile.prevSeasonId.isBlank()
                && nowSec <= profile.prevSeasonGraceUntilSec) {
            List<PassLevelSpec> archivedLevels = data.resolveArchivedLevels(passCfg);
            for (PassLevelSpec s : archivedLevels) {
                if (profile.prevSeasonPoints < s.requiredPoints) {
                    break;
                }
                String fk = PeriodClock.formatRequestId(profile.prevSeasonId, player.getUUID(), "pass", "season", String.valueOf(s.level));
                if (!profile.hasClaimed(fk) && s.freeReward != null && !s.freeReward.isEmpty()) {
                    prevGraceUnclaimed++;
                }
                if (profile.prevSeasonPremiumUnlocked && premEnabled) {
                    String pk = PeriodClock.formatRequestId(profile.prevSeasonId, player.getUUID(), "pass-extra", "season", String.valueOf(s.level));
                    if (!profile.hasClaimed(pk) && s.premiumReward != null && !s.premiumReward.isEmpty()) {
                        prevGraceUnclaimed++;
                    }
                }
            }
        }
        root.putInt("prevSeasonUnclaimed", prevGraceUnclaimed);
        root.putLong("prevSeasonGraceUntilSec", profile.prevSeasonGraceUntilSec);

        ListTag passTagList = new ListTag();
        for (PassLevelSpec s : levels) {
            CompoundTag lt = new CompoundTag();
            lt.putInt("level", s.level);
            lt.putLong("reqPoints", s.requiredPoints);
            lt.putBoolean("highlight", s.highlight);
            String fk = PeriodClock.formatRequestId(passCfg.seasonId, player.getUUID(), "pass", "season", String.valueOf(s.level));
            String pk = PeriodClock.formatRequestId(passCfg.seasonId, player.getUUID(), "pass-extra", "season", String.valueOf(s.level));
            lt.putBoolean("freeClaimed", profile.hasClaimed(fk));
            lt.putBoolean("premClaimed", profile.hasClaimed(pk));
            lt.put("freeReward", encodeRewardBundle(s.freeReward, regs));
            lt.put("premReward", encodeRewardBundle(s.premiumReward, regs));
            passTagList.add(lt);
        }
        root.put("passLevels", passTagList);

        // 2. 每日任务（含昨日未领宽限项）与每周任务
        ListTag dailyTag = new ListTag();
        for (QuestInstance q : profile.dailyQuests) {
            String k = PeriodClock.formatRequestId(passCfg.seasonId, player.getUUID(), "daily", profile.dayKey, q.id);
            dailyTag.add(encodeQuestInstance(q, "daily", profile.hasClaimed(k), false, regs));
        }
        if (QuestTomlConfig.ENABLE_PREV_DAY_GRACE_CLAIM.get() && !profile.prevDayKey.isBlank()) {
            for (QuestInstance q : profile.prevDayQuests) {
                String k = PeriodClock.formatRequestId(passCfg.seasonId, player.getUUID(), "daily", profile.prevDayKey, q.id);
                if (q.isCompleted() && !profile.hasClaimed(k)) {
                    dailyTag.add(encodeQuestInstance(q, "prev_daily", false, false, regs));
                }
            }
        }
        root.put("dailyQuests", dailyTag);

        ListTag weeklyTag = new ListTag();
        for (QuestInstance q : profile.weeklyQuests) {
            String k = PeriodClock.formatRequestId(passCfg.seasonId, player.getUUID(), "weekly", profile.weekKey, q.id);
            weeklyTag.add(encodeQuestInstance(q, "weekly", profile.hasClaimed(k), true, regs));
        }
        root.put("weeklyQuests", weeklyTag);

        // 3. 在线时长与里程碑
        int dayMin = profile.dailyOnlineSeconds / 60;
        int weekMin = profile.weeklyOnlineSeconds / 60;
        root.putInt("dailyMinutes", dayMin);
        root.putInt("weeklyMinutes", weekMin);
        root.putBoolean("afkPaused", OnlineTimeService.isAfkPaused(player.getUUID()));
        root.putBoolean("showAfkHint", QuestTomlConfig.SHOW_AFK_PAUSED_HINT.get());

        ListTag onlineTag = new ListTag();
        for (OnlineMilestoneSpec m : QuestDataConfig.onlineRewards().dailyMilestones) {
            String k = PeriodClock.formatRequestId(passCfg.seasonId, player.getUUID(), "online-day", profile.dayKey, m.id);
            onlineTag.add(encodeOnlineMilestone(m, "daily", dayMin, profile.hasClaimed(k), regs));
        }
        if (QuestTomlConfig.ENABLE_PREV_DAY_GRACE_CLAIM.get() && !profile.prevDayKey.isBlank()) {
            int prevMin = profile.prevDayOnlineSeconds / 60;
            for (OnlineMilestoneSpec m : QuestDataConfig.onlineRewards().dailyMilestones) {
                String k = PeriodClock.formatRequestId(passCfg.seasonId, player.getUUID(), "online-day", profile.prevDayKey, m.id);
                if (prevMin >= m.requiredMinutes && !profile.hasClaimed(k)) {
                    onlineTag.add(encodeOnlineMilestone(m, "prev_daily", prevMin, false, regs));
                }
            }
        }
        for (OnlineMilestoneSpec m : QuestDataConfig.onlineRewards().weeklyMilestones) {
            String k = PeriodClock.formatRequestId(passCfg.seasonId, player.getUUID(), "online-week", profile.weekKey, m.id);
            onlineTag.add(encodeOnlineMilestone(m, "weekly", weekMin, profile.hasClaimed(k), regs));
        }
        root.put("onlineMilestones", onlineTag);

        root.putInt("totalClaimable", countTotalClaimable(player, data, profile) + prevGraceUnclaimed);
        return root;
    }

    private static CompoundTag encodeQuestInstance(QuestInstance q, String period, boolean claimed,
                                                   boolean weekly, HolderLookup.Provider regs) {
        CompoundTag t = new CompoundTag();
        t.putString("id", q.id);
        t.putString("period", period);
        t.putString("title", "prev_daily".equals(period)
                ? Component.translatable("gui.module_shop_quest.grace_prefix", q.title).getString()
                : q.title);
        t.putString("desc", q.description);
        t.putString("category", q.categoryTag);
        t.putString("requiredMod", q.requiredMod);
        t.putInt("progress", q.progress);
        t.putInt("target", q.targetCount);
        t.putBoolean("invalidated", q.invalidated);
        t.putBoolean("claimed", claimed);
        t.put("reward", encodeRewardBundle(resolveLiveQuestReward(q, weekly), regs));
        return t;
    }

    private static CompoundTag encodeOnlineMilestone(OnlineMilestoneSpec m, String period, int currentMin,
                                                     boolean claimed, HolderLookup.Provider regs) {
        CompoundTag t = new CompoundTag();
        t.putString("id", m.id);
        t.putString("period", period);
        t.putString("title", "prev_daily".equals(period)
                ? Component.translatable("gui.module_shop_quest.grace_prefix", m.title).getString()
                : m.title);
        t.putInt("currentMin", Math.min(currentMin, m.requiredMinutes));
        t.putInt("reqMin", m.requiredMinutes);
        t.putBoolean("claimed", claimed);
        t.put("reward", encodeRewardBundle(m.reward, regs));
        return t;
    }

    private static CompoundTag encodeRewardBundle(RewardBundleSpec b, HolderLookup.Provider regs) {
        CompoundTag t = new CompoundTag();
        if (b == null) {
            return t;
        }
        t.putLong("money", Math.max(0L, b.money));
        t.putLong("points", Math.max(0L, b.points));
        ListTag items = new ListTag();
        for (ItemStack preview : QuestDataConfig.previewItems(b, regs)) {
            if (!preview.isEmpty()) {
                CompoundTag st = (CompoundTag) preview.save(regs, new CompoundTag());
                items.add(st);
            }
        }
        t.put("items", items);
        return t;
    }
}
