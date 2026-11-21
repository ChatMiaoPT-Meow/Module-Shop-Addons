package cn.autoforged.module_shop_quest.server;

import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestDefSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.TaskTarget;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import cn.autoforged.module_shop_quest.data.QuestSavedData;
import cn.autoforged.module_shop_quest.data.QuestSavedData.PlayerQuestProfile;
import cn.autoforged.module_shop_quest.data.QuestSavedData.QuestInstance;
import cn.autoforged.module_shop_quest.data.QuestSavedData.SubTargetProgress;
import cn.autoforged.module_shop_quest.network.QuestNetwork;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
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

    /** 向管理员客户端单独下发服务端动态注册表候选（Biome / Structure / Tags）。 */
    public static void handleFetchRegistries(ServerPlayer player) {
        if (!player.hasPermissions(2)) {
            QuestNetwork.sendActionResult(player, false, "msg.module_shop_quest.no_permission", "error", false);
            return;
        }
        CompoundTag out = new CompoundTag();
        out.putString("type", "admin_registries");
        appendDynamicRegistryCandidates(out, player.server.registryAccess());
        QuestNetwork.sendToPlayer(player, out);
    }

    public static void sendAdminConfigPacket(ServerPlayer player) {
        CompoundTag out = new CompoundTag();
        out.putString("type", "admin_config");
        out.putString("passJson", QuestDataConfig.toCompactJson(QuestDataConfig.seasonPass()));
        out.putString("poolsJson", QuestDataConfig.toCompactJson(QuestDataConfig.questPools()));
        out.putString("onlineJson", QuestDataConfig.toCompactJson(QuestDataConfig.onlineRewards()));
        appendDynamicRegistryCandidates(out, player.server.registryAccess());
        QuestNetwork.sendToPlayer(player, out);
    }

    private static void appendDynamicRegistryCandidates(CompoundTag out, RegistryAccess registries) {
        List<String> biomeIds = new ArrayList<>();
        List<String> biomeTags = new ArrayList<>();
        List<String> structureIds = new ArrayList<>();
        List<String> structureTags = new ArrayList<>();
        List<String> entityTags = new ArrayList<>();

        try {
            registries.registry(Registries.BIOME).ifPresent(reg -> {
                reg.keySet().forEach(rl -> biomeIds.add(rl.toString()));
                reg.getTagNames().forEach(tk -> biomeTags.add("#" + tk.location()));
            });
            registries.registry(Registries.STRUCTURE).ifPresent(reg -> {
                reg.keySet().forEach(rl -> structureIds.add(rl.toString()));
                reg.getTagNames().forEach(tk -> structureTags.add("#" + tk.location()));
            });
            BuiltInRegistries.ENTITY_TYPE.getTagNames().forEach(tk -> entityTags.add("#" + tk.location()));
        } catch (Exception e) {
            QuestMod.LOGGER.warn("[{}] 收集服务端动态注册表候选失败：{}", QuestMod.MODID, e.getMessage());
        }

        Collections.sort(biomeIds);
        Collections.sort(biomeTags);
        Collections.sort(structureIds);
        Collections.sort(structureTags);
        Collections.sort(entityTags);

        out.put("biomeIds", toStringListTag(biomeIds));
        out.put("structureIds", toStringListTag(structureIds));
        out.put("biomeTags", toStringListTag(biomeTags));
        out.put("structureTags", toStringListTag(structureTags));
        out.put("entityTags", toStringListTag(entityTags));
    }

    private static ListTag toStringListTag(List<String> items) {
        ListTag list = new ListTag();
        for (String s : items) {
            if (s != null && !s.isBlank()) {
                list.add(StringTag.valueOf(s));
            }
        }
        return list;
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
            q.ensureSubTargetsInitialized();
            List<TaskTarget> newTargets = def.resolvedTargets();
            boolean targetsDiffer = subTargetsDiffer(q.subTargets, newTargets);

            if (!def.title.equals(q.title) || !def.description.equals(q.description)
                    || !def.type.equals(q.type) || targetsDiffer) {
                q.title = def.title;
                q.description = def.description;
                q.type = def.type;
                q.targetFilter = def.targetFilter == null ? "" : def.targetFilter;
                q.requiredMod = def.requiredMod == null ? "" : def.requiredMod;
                q.fallbackReward = def.reward;

                if (targetsDiffer) {
                    Map<String, Integer> oldProgByTarget = new HashMap<>();
                    for (SubTargetProgress oldSt : q.subTargets) {
                        if (oldSt != null) {
                            oldProgByTarget.putIfAbsent(oldSt.target == null ? "" : oldSt.target, oldSt.progress);
                        }
                    }
                    List<SubTargetProgress> rebuilt = new ArrayList<>(newTargets.size());
                    for (int i = 0; i < newTargets.size(); i++) {
                        TaskTarget nt = newTargets.get(i);
                        int prevProg = oldProgByTarget.getOrDefault(nt.target,
                                (i < q.subTargets.size() && q.subTargets.get(i) != null && newTargets.size() == q.subTargets.size())
                                        ? q.subTargets.get(i).progress : 0);
                        rebuilt.add(new SubTargetProgress(nt.target, nt.label, Math.max(1, nt.count),
                                Math.min(Math.max(1, nt.count), Math.max(0, prevProg))));
                    }
                    q.subTargets = rebuilt;
                    q.recalculateAggregateProgress();
                }
                changed = true;
            }
        }
        return changed;
    }

    private static boolean subTargetsDiffer(List<SubTargetProgress> current, List<TaskTarget> desired) {
        if (current == null || current.size() != desired.size()) {
            return true;
        }
        for (int i = 0; i < desired.size(); i++) {
            SubTargetProgress c = current.get(i);
            TaskTarget d = desired.get(i);
            if (c == null || d == null) {
                return true;
            }
            if (!c.target.equals(d.target) || !c.label.equals(d.label) || c.count != d.count) {
                return true;
            }
        }
        return false;
    }
}
