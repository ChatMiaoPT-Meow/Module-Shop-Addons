package cn.autoforged.module_shop_quest.server;

import cn.autoforged.module_shop.api.ApiErrorCode;
import cn.autoforged.module_shop.api.ApiResult;
import cn.autoforged.module_shop.api.ModuleShopApi;
import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.config.ConfigModels.RewardBundleSpec;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import cn.autoforged.module_shop_quest.data.QuestSavedData;
import cn.autoforged.module_shop_quest.data.QuestSavedData.PlayerQuestProfile;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 双重幂等发奖状态机（严格落实设计规范 §1.1、§8.2、§13.3）：
 * <ol>
 *     <li>先核查本模组 {@link PlayerQuestProfile#hasClaimed(String)} 第一幂等表并加内存防并发锁；</li>
 *     <li>有星币或物品时统一调用 {@code ModuleShopApi.reward().grantOnce(...)} 第二道防线（自动处理背包满/钱包上限转邮箱与商店流水）；</li>
 *     <li>若商店返回 {@code DUPLICATE_REQUEST} 而本模组尚未记账，按 §13.3 补加积分并标记已领、打警告日志，绝不重发星币；</li>
 *     <li>通行证积分仅写入 {@link PlayerQuestProfile#seasonPoints}，严禁触碰 {@code economy()}。</li>
 * </ol>
 */
public final class RewardGrantService {
    private RewardGrantService() {
    }

    public record Outcome(boolean ok, long grantedMoney, long grantedPoints, int grantedItemStacks, String errorKey) {
        public static Outcome success(long money, long points, int itemStacks) {
            return new Outcome(true, money, points, itemStacks, "");
        }

        public static Outcome fail(String errorKey) {
            return new Outcome(false, 0L, 0L, 0, errorKey);
        }
    }

    /**
     * 执行单笔幂等奖励发放。
     *
     * @param player            在线目标玩家
     * @param data              世界存档
     * @param profile           玩家档案
     * @param requestId         稳定幂等键（{@code quest:<seasonId>:<uuid>:<kind>:<periodKey>:<id>}）
     * @param reward            奖励定义
     * @param applySeasonPoints 是否将奖励中的积分计入当前赛季（补领上赛季宽限格子时积分不计入新季）
     * @param reason            商店流水可读中文摘要
     */
    public static Outcome grantIdempotent(
            ServerPlayer player,
            QuestSavedData data,
            PlayerQuestProfile profile,
            String requestId,
            RewardBundleSpec reward,
            boolean applySeasonPoints,
            String reason) {
        if (reward == null) {
            return Outcome.fail("msg.module_shop_quest.invalid_reward");
        }
        if (profile.hasClaimed(requestId)) {
            return Outcome.fail("msg.module_shop_quest.already_claimed");
        }
        if (!profile.tryAcquireInFlight(requestId)) {
            return Outcome.fail("msg.module_shop_quest.claim_busy");
        }

        try {
            long money = Math.max(0L, reward.money);
            long points = applySeasonPoints ? Math.max(0L, reward.points) : 0L;
            List<ItemStack> stacks = QuestDataConfig.materializeItems(reward, player.registryAccess());

            if (money > 0L || !stacks.isEmpty()) {
                ApiResult<Void> apiRes = ModuleShopApi.reward().grantOnce(
                        player.getUUID(),
                        requestId,
                        stacks,
                        money,
                        reason == null || reason.isBlank() ? "星途纪行奖励" : reason
                );

                if (apiRes.isOk()) {
                    if (points > 0L) {
                        profile.seasonPoints = safeAddPoints(profile.seasonPoints, points);
                    }
                    profile.markClaimed(requestId);
                    data.setDirty();
                    return Outcome.success(money, points, stacks.size());
                }

                if (apiRes.code() == ApiErrorCode.DUPLICATE_REQUEST) {
                    // §13.3：商店已发过该单据但本附属上次未落盘，补记积分与已领标记，绝不再发星币
                    if (points > 0L) {
                        profile.seasonPoints = safeAddPoints(profile.seasonPoints, points);
                    }
                    profile.markClaimed(requestId);
                    data.setDirty();
                    QuestMod.LOGGER.warn("[{}] 检测到 grantOnce DUPLICATE_REQUEST，已按 §13.3 补记积分({})并对齐已领表：{}",
                            QuestMod.MODID, points, requestId);
                    return Outcome.success(0L, points, 0);
                }

                profile.releaseInFlight(requestId);
                QuestMod.LOGGER.warn("[{}] grantOnce 失败 code={} msg={} key={}",
                        QuestMod.MODID, apiRes.code(), apiRes.message(), requestId);
                return Outcome.fail(mapApiError(apiRes.code()));
            }

            // 纯积分奖励（无星币且无物品）：直接在本附属存档内完成积分入账与幂等标记
            if (points > 0L) {
                profile.seasonPoints = safeAddPoints(profile.seasonPoints, points);
            }
            profile.markClaimed(requestId);
            data.setDirty();
            return Outcome.success(0L, points, 0);
        } catch (Throwable t) {
            profile.releaseInFlight(requestId);
            QuestMod.LOGGER.error("[{}] 发放奖励异常 key={}", QuestMod.MODID, requestId, t);
            return Outcome.fail("msg.module_shop_quest.grant_failed");
        }
    }

    public static long safeAddPoints(long current, long delta) {
        if (delta <= 0L) {
            return Math.max(0L, current);
        }
        long sum = current + delta;
        return sum < 0L ? Long.MAX_VALUE / 2 : sum;
    }

    private static String mapApiError(ApiErrorCode code) {
        if (code == ApiErrorCode.MAILBOX_FULL) {
            return "msg.module_shop_quest.mailbox_full";
        }
        if (code == ApiErrorCode.LIMIT_EXCEEDED) {
            return "msg.module_shop_quest.balance_limit";
        }
        return "msg.module_shop_quest.grant_failed";
    }
}
