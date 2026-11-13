package cn.autoforged.module_shop_quest.server;

import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestDefSpec;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import cn.autoforged.module_shop_quest.data.QuestSavedData;
import cn.autoforged.module_shop_quest.data.QuestSavedData.PlayerQuestProfile;
import cn.autoforged.module_shop_quest.data.QuestSavedData.QuestInstance;
import cn.autoforged.module_shop_quest.network.QuestNetwork;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 服务端可视化 GUI 配置管理服务（严格校验 OP 2 级权限）。
 * <p>
 * 支持管理员直接在商店 GUI 内可视化编辑通行证等级奖励、价格规则、每日/每周任务池与在线时长奖励，
 * 实时写回 {@code config/module_shop_quest/*.json} 并同步全服在线玩家快照。
 */
public final class QuestAdminService {
    private QuestAdminService() {
    }

    /** 向管理员客户端下发当前完整可编辑配置 JSON。 */
    public static void handleFetchConfig(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.no_permission", "error", false);
            return;
        }
        sendAdminConfigPacket(player);
    }

    public static void sendAdminConfigPacket(ServerPlayer player) {
        CompoundTag out = new CompoundTag();
        out.putString("type", "admin_config");
        out.putString("passJson", QuestDataConfig.toCompactJson(QuestDataConfig.seasonPass()));
        out.putString("poolsJson", QuestDataConfig.toCompactJson(QuestDataConfig.questPools()));
        out.putString("onlineJson", QuestDataConfig.toCompactJson(QuestDataConfig.onlineRewards()));
        QuestNetwork.sendToPlayer(player, out);
    }

    /** 接收管理员在 GUI 内编辑后的配置，落盘保存并刷新全服玩家视图。 */
    public static void handleSaveConfig(ServerPlayer player, CompoundTag req) {
        if (!player.hasPermissions(2)) {
            QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.no_permission", "error", false);
            return;
        }

        String passJson = req.contains("passJson") ? req.getString("passJson") : null;
        String poolsJson = req.contains("poolsJson") ? req.getString("poolsJson") : null;
        String onlineJson = req.contains("onlineJson") ? req.getString("onlineJson") : null;
        boolean rerollSelf = req.getBoolean("rerollSelf");

        List<String> warnings = new ArrayList<>();
        boolean saved = QuestDataConfig.applyAndSaveAdminConfigs(passJson, poolsJson, onlineJson, warnings);
        if (!saved) {
            QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.admin_save_failed", "error", false);
            return;
        }

        MinecraftServer server = player.server;
        QuestSavedData data = QuestSavedData.get(server);
        QuestManager.ensureSeasonInitialized(server);

        // 若任务池发生保存，无损同步所有玩家已抽出任务的最新标题、描述与目标次数
        syncExistingQuestMetadata(data);

        if (rerollSelf) {
            PlayerQuestProfile p = data.profile(player.getUUID());
            p.dayKey = "";
            p.weekKey = "";
            data.setDirty();
            QuestManager.ensurePlayerUpToDate(player, data);
        }

        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            QuestManager.syncFullSnapshot(online);
        }
        sendAdminConfigPacket(player);

        QuestMod.LOGGER.info("[{}] 管理员 {} 通过游戏内 GUI 保存了配置（rerollSelf={}，告警数={}）",
                QuestMod.MODID, player.getGameProfile().getName(), rerollSelf, warnings.size());
        QuestNetwork.sendActionResult(player, true,
                rerollSelf ? "msg.module_shop_quest.admin_saved_rerolled" : "msg.module_shop_quest.admin_saved_ok",
                "claim", true);
    }

    /** 将最新任务池中的标题、描述与目标次数同步到已抽出的活跃任务实例（保留已有完成进度）。 */
    public static void syncExistingQuestMetadata(QuestSavedData data) {
        Map<String, QuestDefSpec> dailyMap = new HashMap<>();
        for (QuestDefSpec d : QuestDataConfig.questPools().dailyPool) {
            if (d != null && d.id != null) {
                dailyMap.put(d.id, d);
            }
        }
        Map<String, QuestDefSpec> weeklyMap = new HashMap<>();
        for (QuestDefSpec w : QuestDataConfig.questPools().weeklyPool) {
            if (w != null && w.id != null) {
                weeklyMap.put(w.id, w);
            }
        }

        boolean touched = false;
        for (PlayerQuestProfile profile : data.allProfiles().values()) {
            touched |= patchQuestList(profile.dailyQuests, dailyMap);
            touched |= patchQuestList(profile.prevDayQuests, dailyMap);
            touched |= patchQuestList(profile.weeklyQuests, weeklyMap);
        }
        if (touched) {
            data.setDirty();
        }
    }

    private static boolean patchQuestList(List<QuestInstance> list, Map<String, QuestDefSpec> defs) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (QuestInstance q : list) {
            QuestDefSpec def = defs.get(q.id);
            if (def == null) {
                continue;
            }
            if (!def.title.equals(q.title) || !def.description.equals(q.description)
                    || def.targetCount != q.targetCount) {
                q.title = def.title;
                q.description = def.description;
                q.targetCount = Math.max(1, def.targetCount);
                q.type = def.type;
                q.targetFilter = def.targetFilter == null ? "" : def.targetFilter;
                q.requiredMod = def.requiredMod == null ? "" : def.requiredMod;
                q.fallbackReward = def.reward;
                changed = true;
            }
        }
        return changed;
    }
}
