package cn.autoforged.module_shop_quest.api;

import cn.autoforged.module_shop.api.ModuleShopApi;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestTaskType;
import cn.autoforged.module_shop_quest.data.QuestSavedData;
import cn.autoforged.module_shop_quest.server.BattlePassService;
import cn.autoforged.module_shop_quest.server.QuestManager;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * 星途纪行对外开放联动接口（供抽卡、股市、暗拍、KubeJS 或其他自定义玩法直接上报任务行为或查询赛季等级）。
 * <p>
 * 所有推进操作均要求在服务端主线程调用；若在非主线程调用会自动安全忽略并返回 {@code false}。
 */
public final class QuestIntegrationApi {
    private QuestIntegrationApi() {
    }

    /**
     * 触发一条自定义任务或联动行为进度（如 {@code CUSTOM_EVENT}、{@code LOTTERY_PULL}、
     * {@code STOCK_SPOT_TRADE}、{@code BLIND_PARTICIPATE}）。
     *
     * @param playerId  目标玩家 UUID
     * @param type      任务行为枚举名称（大小写不敏感，如 {@code "LOTTERY_PULL"} 或 {@code "CUSTOM_EVENT"}）
     * @param targetKey 细分过滤标识（如卡池 ID、自定义事件键；无细分传 {@code ""} 即可）
     * @param amount    推进次数（必须 {@code > 0}）
     * @return 若成功投递至任务管理器返回 {@code true}
     */
    public static boolean recordAction(UUID playerId, String type, String targetKey, int amount) {
        if (playerId == null || type == null || amount <= 0) {
            return false;
        }
        MinecraftServer server = ModuleShopApi.server();
        if (server == null || !ModuleShopApi.onServerThread(server)) {
            return false;
        }
        QuestTaskType parsed = QuestTaskType.fromName(type);
        if (parsed == null) {
            return false;
        }
        QuestManager.recordProgress(server, playerId, parsed, targetKey == null ? "" : targetKey, amount);
        return true;
    }

    /** 查询玩家当前赛季累计积分（离线可查，不存在返回 0）。 */
    public static long seasonPoints(UUID playerId) {
        MinecraftServer server = ModuleShopApi.server();
        if (server == null || playerId == null || !ModuleShopApi.onServerThread(server)) {
            return 0L;
        }
        QuestSavedData data = QuestSavedData.get(server);
        QuestSavedData.PlayerQuestProfile profile = data.peekProfile(playerId);
        return profile == null ? 0L : profile.seasonPoints;
    }

    /** 查询玩家当前赛季通行证等级（0 表示尚未升至 1 级）。 */
    public static int passLevel(UUID playerId) {
        return BattlePassService.computeLevel(seasonPoints(playerId));
    }

    /** 查询玩家当前赛季是否已解锁星币付费进阶线。 */
    public static boolean isPremiumUnlocked(UUID playerId) {
        MinecraftServer server = ModuleShopApi.server();
        if (server == null || playerId == null || !ModuleShopApi.onServerThread(server)) {
            return false;
        }
        QuestSavedData.PlayerQuestProfile profile = QuestSavedData.get(server).peekProfile(playerId);
        return profile != null && profile.premiumUnlocked;
    }
}
