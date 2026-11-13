package cn.autoforged.module_shop_quest.server;

import cn.autoforged.module_shop.api.ApiErrorCode;
import cn.autoforged.module_shop.api.ApiResult;
import cn.autoforged.module_shop.api.ModuleShopApi;
import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.config.ConfigModels.PassLevelSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.PeriodClock;
import cn.autoforged.module_shop_quest.config.ConfigModels.PointsRuleSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.PremiumTrackSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.RewardBundleSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.SeasonPassConfig;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import cn.autoforged.module_shop_quest.data.QuestSavedData;
import cn.autoforged.module_shop_quest.data.QuestSavedData.PlayerQuestProfile;
import cn.autoforged.module_shop_quest.network.QuestNetwork;
import java.time.Instant;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/**
 * 双层积分赛季通行证与典藏奖章核心服务：
 * <ul>
 *     <li>免费基础线（{@code pass}）与星币付费进阶线（{@code pass-extra}）并行推进；</li>
 *     <li>支持两档星币买断：标准「星辉进阶线」与典藏「星辉典藏奖章」（含直升等级 + 限定额外礼 + 支持补差价升级）；</li>
 *     <li>支持星币按级快速购买通行证等级；</li>
 *     <li>跨赛季 7 天宽限补领严格读取 {@link QuestSavedData#resolveArchivedLevels(SeasonPassConfig)} 归档奖励表。</li>
 * </ul>
 */
public final class BattlePassService {
    private BattlePassService() {
    }

    public static int computeLevel(long points) {
        return QuestDataConfig.seasonPass().computeLevel(points);
    }

    /** 处理客户端发起的「解锁标准进阶线」请求（兼容旧版调用入口）。 */
    public static void handleUnlockPremium(ServerPlayer player) {
        handleUnlockTier(player, false);
    }

    /**
     * 处理客户端发起的「解锁标准进阶线（{@code deluxe = false}）」或「解锁/补差升级典藏奖章（{@code deluxe = true}）」请求。
     */
    public static void handleUnlockTier(ServerPlayer player, boolean deluxe) {
        QuestSavedData data = QuestSavedData.get(player.server);
        QuestManager.ensurePlayerUpToDate(player, data);
        PlayerQuestProfile profile = data.profile(player.getUUID());
        SeasonPassConfig passCfg = QuestDataConfig.seasonPass();
        PremiumTrackSpec prem = passCfg.premiumTrack;

        if (prem == null || !prem.enabled) {
            QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.premium_disabled", "error", false);
            return;
        }

        if (!deluxe) {
            if (profile.premiumUnlocked) {
                QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.premium_already_unlocked", "error", true);
                return;
            }
            long cost = Math.max(0L, prem.unlockStarCoinCost);
            if (!deductStarCoins(player, cost, "解锁星途纪行进阶线 [" + passCfg.seasonName + "]")) {
                return;
            }
            profile.premiumUnlocked = true;
            profile.premiumUnlockedAt = Instant.now().getEpochSecond();
            if (prem.unlockBonusPoints > 0L) {
                profile.seasonPoints = RewardGrantService.safeAddPoints(profile.seasonPoints, prem.unlockBonusPoints);
            }
            data.setDirty();

            QuestMod.LOGGER.info("[{}] 玩家 {} 消耗 {} 星币解锁赛季 {} 标准进阶线（附赠积分：{}）",
                    QuestMod.MODID, player.getGameProfile().getName(), cost, passCfg.seasonId, prem.unlockBonusPoints);
            QuestNetwork.sendActionResult(player, true, "msg.module_shop_quest.premium_unlocked_ok", "unlock", true);
            return;
        }

        // 典藏奖章（支持直接买断或已买标准进阶线后补差价升级）
        if (!prem.deluxeEnabled) {
            QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.deluxe_disabled", "error", false);
            return;
        }
        if (profile.deluxeUnlocked) {
            QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.deluxe_already_unlocked", "error", true);
            return;
        }

        boolean upgrading = profile.premiumUnlocked;
        long cost = upgrading
                ? Math.max(0L, prem.deluxeStarCoinCost - prem.unlockStarCoinCost)
                : Math.max(0L, prem.deluxeStarCoinCost);
        String reason = upgrading
                ? ("升级星途纪行典藏奖章 [" + passCfg.seasonName + "]")
                : ("解锁星途纪行典藏奖章 [" + passCfg.seasonName + "]");

        if (!deductStarCoins(player, cost, reason)) {
            return;
        }

        long nowSec = Instant.now().getEpochSecond();
        if (!upgrading) {
            profile.premiumUnlocked = true;
            profile.premiumUnlockedAt = nowSec;
            if (prem.unlockBonusPoints > 0L) {
                profile.seasonPoints = RewardGrantService.safeAddPoints(profile.seasonPoints, prem.unlockBonusPoints);
            }
        }
        profile.deluxeUnlocked = true;

        // 立即提升 deluxeBonusLevels 级纪行等级
        long levelDeltaPts = passCfg.computePointsDeltaForBonusLevels(profile.seasonPoints, prem.deluxeBonusLevels);
        if (levelDeltaPts > 0L) {
            profile.seasonPoints = RewardGrantService.safeAddPoints(profile.seasonPoints, levelDeltaPts);
        }
        data.setDirty();

        // 幂等发放典藏奖章专属立得礼包
        if (prem.deluxeExtraReward != null && !prem.deluxeExtraReward.isEmpty()) {
            String deluxeReqId = PeriodClock.formatRequestId(
                    passCfg.seasonId, player.getUUID(), "pass-deluxe", "season", "medal");
            RewardGrantService.grantIdempotent(
                    player, data, profile, deluxeReqId, prem.deluxeExtraReward, true,
                    "星途纪行典藏奖章专属礼 [" + prem.deluxeTrackName + "]");
        }

        QuestMod.LOGGER.info("[{}] 玩家 {} 消耗 {} 星币激活 {}（补差模式={}，直升 +{} 级）",
                QuestMod.MODID, player.getGameProfile().getName(), cost, prem.deluxeTrackName, upgrading, prem.deluxeBonusLevels);
        QuestNetwork.sendActionResult(player, true, "msg.module_shop_quest.deluxe_unlocked_ok", "unlock", true);
    }

    /** 处理客户端发起的「使用星币购买纪行等级」请求。 */
    public static void handleBuyLevels(ServerPlayer player, int count) {
        QuestSavedData data = QuestSavedData.get(player.server);
        QuestManager.ensurePlayerUpToDate(player, data);
        PlayerQuestProfile profile = data.profile(player.getUUID());
        SeasonPassConfig passCfg = QuestDataConfig.seasonPass();
        PointsRuleSpec rule = passCfg.pointsRule;

        if (rule == null || !rule.allowBuyLevels) {
            QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.buy_level_disabled", "error", false);
            return;
        }

        List<PassLevelSpec> levels = passCfg.resolvedLevels();
        int curLv = passCfg.computeLevel(profile.seasonPoints);
        int maxLv = levels.size();
        if (curLv >= maxLv) {
            QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.already_max_level", "error", false);
            return;
        }

        int actualBuy = Math.max(1, Math.min(count, maxLv - curLv));
        int targetLv = curLv + actualBuy;
        long unitCost = Math.max(1L, rule.buyLevelStarCoinCost);
        long totalCost = unitCost * actualBuy;

        String reason = "购买星途纪行等级 Lv." + curLv + " -> Lv." + targetLv;
        if (!deductStarCoins(player, totalCost, reason)) {
            return;
        }

        long targetPoints = levels.get(targetLv - 1).requiredPoints;
        if (targetPoints > profile.seasonPoints) {
            profile.seasonPoints = targetPoints;
        }
        data.setDirty();

        QuestMod.LOGGER.info("[{}] 玩家 {} 消耗 {} 星币购买 {} 级纪行等级（Lv.{} -> Lv.{}）",
                QuestMod.MODID, player.getGameProfile().getName(), totalCost, actualBuy, curLv, targetLv);
        QuestNetwork.sendActionResult(player, true, "msg.module_shop_quest.buy_level_ok", "unlock", true);
    }

    private static boolean deductStarCoins(ServerPlayer player, long cost, String reason) {
        if (cost <= 0L) {
            return true;
        }
        long currentBalance = ModuleShopApi.economy().balance(player.getUUID());
        if (currentBalance < cost) {
            QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.insufficient_starcoins", "error", false);
            return false;
        }
        ApiResult<Long> takeRes = ModuleShopApi.economy().take(player.getUUID(), cost, reason);
        if (!takeRes.isOk()) {
            String errKey = takeRes.code() == ApiErrorCode.INSUFFICIENT_FUNDS
                    ? "msg.module_shop_quest.insufficient_starcoins"
                    : "msg.module_shop_quest.unlock_failed";
            QuestNetwork.sendActionResult(player, false, errKey, "error", false);
            return false;
        }
        return true;
    }

    /** 领取单个等级的单条奖励。 */
    public static void handleClaimPassLevel(ServerPlayer player, int level, boolean premium, boolean prevSeason) {
        QuestSavedData data = QuestSavedData.get(player.server);
        QuestManager.ensurePlayerUpToDate(player, data);
        PlayerQuestProfile profile = data.profile(player.getUUID());

        OutcomeSummary res = tryClaimPassSlot(player, data, profile, level, premium, prevSeason);
        if (!res.ok) {
            QuestNetwork.sendActionResult(player, false, res.errorKey, "error", false);
            return;
        }
        QuestNetwork.sendActionResult(player, true, "msg.module_shop_quest.claimed_ok", "claim", true);
    }

    public record OutcomeSummary(boolean ok, int claimedCount, long totalMoney, long totalPoints, String errorKey) {
    }

    /** 尝试领取指定通行证槽位（上赛季补领严格读取归档快照奖励表）。 */
    public static OutcomeSummary tryClaimPassSlot(
            ServerPlayer player,
            QuestSavedData data,
            PlayerQuestProfile profile,
            int level,
            boolean premium,
            boolean prevSeason) {
        SeasonPassConfig passCfg = QuestDataConfig.seasonPass();
        List<PassLevelSpec> levels = prevSeason
                ? data.resolveArchivedLevels(passCfg)
                : passCfg.resolvedLevels();
        if (level < 1 || level > levels.size()) {
            return new OutcomeSummary(false, 0, 0L, 0L, "msg.module_shop_quest.invalid_level");
        }

        long nowSec = Instant.now().getEpochSecond();
        String targetSeasonId;
        long effectivePoints;
        boolean hasPremiumAccess;

        if (prevSeason) {
            if (profile.prevSeasonId == null || profile.prevSeasonId.isBlank()
                    || nowSec > profile.prevSeasonGraceUntilSec) {
                return new OutcomeSummary(false, 0, 0L, 0L, "msg.module_shop_quest.grace_expired");
            }
            targetSeasonId = profile.prevSeasonId;
            effectivePoints = profile.prevSeasonPoints;
            hasPremiumAccess = profile.prevSeasonPremiumUnlocked;
        } else {
            targetSeasonId = passCfg.seasonId;
            effectivePoints = profile.seasonPoints;
            hasPremiumAccess = profile.premiumUnlocked;
        }

        PassLevelSpec spec = levels.get(level - 1);
        if (effectivePoints < spec.requiredPoints) {
            return new OutcomeSummary(false, 0, 0L, 0L, "msg.module_shop_quest.level_not_reached");
        }

        if (premium) {
            if (!prevSeason && (passCfg.premiumTrack == null || !passCfg.premiumTrack.enabled)) {
                return new OutcomeSummary(false, 0, 0L, 0L, "msg.module_shop_quest.premium_disabled");
            }
            if (!hasPremiumAccess) {
                return new OutcomeSummary(false, 0, 0L, 0L, "msg.module_shop_quest.premium_locked");
            }
        }

        RewardBundleSpec bundle = premium ? spec.premiumReward : spec.freeReward;
        if (bundle == null || bundle.isEmpty()) {
            return new OutcomeSummary(false, 0, 0L, 0L, "msg.module_shop_quest.empty_reward");
        }

        String kind = premium ? "pass-extra" : "pass";
        String reqId = PeriodClock.formatRequestId(targetSeasonId, player.getUUID(), kind, "season", String.valueOf(level));
        String trackLabel = premium ? "进阶线" : "免费线";
        String reason = (prevSeason ? "上季补领·" : "") + "通行证 Lv." + level + " " + trackLabel + "奖励";

        RewardGrantService.Outcome out = RewardGrantService.grantIdempotent(
                player, data, profile, reqId, bundle, !prevSeason, reason);
        if (!out.ok()) {
            return new OutcomeSummary(false, 0, 0L, 0L, out.errorKey());
        }
        return new OutcomeSummary(true, 1, out.grantedMoney(), out.grantedPoints(), "");
    }

    /** 一键领取该玩家在当前赛季（及上季宽限期内）所有已解锁但尚未领取的通行证奖励。 */
    public static OutcomeSummary claimAllReachablePassRewards(
            ServerPlayer player, QuestSavedData data, PlayerQuestProfile profile) {
        SeasonPassConfig passCfg = QuestDataConfig.seasonPass();
        List<PassLevelSpec> curLevels = passCfg.resolvedLevels();
        int count = 0;
        long money = 0L;
        long pts = 0L;

        // 1. 当前赛季免费线 + 付费进阶线
        for (PassLevelSpec spec : curLevels) {
            if (profile.seasonPoints < spec.requiredPoints) {
                break;
            }
            String freeKey = PeriodClock.formatRequestId(
                    passCfg.seasonId, player.getUUID(), "pass", "season", String.valueOf(spec.level));
            if (!profile.hasClaimed(freeKey) && spec.freeReward != null && !spec.freeReward.isEmpty()) {
                OutcomeSummary r = tryClaimPassSlot(player, data, profile, spec.level, false, false);
                if (r.ok) {
                    count++;
                    money += r.totalMoney;
                    pts += r.totalPoints;
                }
            }
            if (profile.premiumUnlocked && passCfg.premiumTrack != null && passCfg.premiumTrack.enabled) {
                String premKey = PeriodClock.formatRequestId(
                        passCfg.seasonId, player.getUUID(), "pass-extra", "season", String.valueOf(spec.level));
                if (!profile.hasClaimed(premKey) && spec.premiumReward != null && !spec.premiumReward.isEmpty()) {
                    OutcomeSummary r = tryClaimPassSlot(player, data, profile, spec.level, true, false);
                    if (r.ok) {
                        count++;
                        money += r.totalMoney;
                        pts += r.totalPoints;
                    }
                }
            }
        }

        // 2. 上赛季宽限补领窗口内未领奖励（使用上赛季归档快照表）
        long nowSec = Instant.now().getEpochSecond();
        if (profile.prevSeasonId != null && !profile.prevSeasonId.isBlank()
                && nowSec <= profile.prevSeasonGraceUntilSec) {
            List<PassLevelSpec> prevLevels = data.resolveArchivedLevels(passCfg);
            for (PassLevelSpec spec : prevLevels) {
                if (profile.prevSeasonPoints < spec.requiredPoints) {
                    break;
                }
                String freeKey = PeriodClock.formatRequestId(
                        profile.prevSeasonId, player.getUUID(), "pass", "season", String.valueOf(spec.level));
                if (!profile.hasClaimed(freeKey) && spec.freeReward != null && !spec.freeReward.isEmpty()) {
                    OutcomeSummary r = tryClaimPassSlot(player, data, profile, spec.level, false, true);
                    if (r.ok) {
                        count++;
                        money += r.totalMoney;
                    }
                }
                if (profile.prevSeasonPremiumUnlocked) {
                    String premKey = PeriodClock.formatRequestId(
                            profile.prevSeasonId, player.getUUID(), "pass-extra", "season", String.valueOf(spec.level));
                    if (!profile.hasClaimed(premKey) && spec.premiumReward != null && !spec.premiumReward.isEmpty()) {
                        OutcomeSummary r = tryClaimPassSlot(player, data, profile, spec.level, true, true);
                        if (r.ok) {
                            count++;
                            money += r.totalMoney;
                        }
                    }
                }
            }
        }

        return new OutcomeSummary(count > 0, count, money, pts, count > 0 ? "" : "msg.module_shop_quest.nothing_to_claim");
    }
}
