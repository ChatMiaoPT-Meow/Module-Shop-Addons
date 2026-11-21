package cn.autoforged.module_shop_quest.data;

import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.config.ConfigModels;
import cn.autoforged.module_shop_quest.config.ConfigModels.ItemRewardSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.PassLevelSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestDefSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestTaskType;
import cn.autoforged.module_shop_quest.config.ConfigModels.RewardBundleSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.SeasonPassConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.TaskTarget;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 星途纪行持久化世界存档（{@code world/data/module_shop_quest.dat}）。
 */
public final class QuestSavedData extends SavedData {
    public static final String FILE_ID = "module_shop_quest";
    public static final int SCHEMA_VERSION = 3;
    private static final int SAFE_NBT_STRING_BYTES = 32_000;

    private String activeSeasonId = "";
    private long seasonStartEpochSec = 0L;
    private long seasonEndEpochSec = 0L;
    private String currentSeasonConfigJson = "";

    private String archivedSeasonId = "";
    private long archivedGraceUntilEpochSec = 0L;
    private String archivedSeasonConfigJson = "";
    private transient List<PassLevelSpec> cachedArchivedLevels;

    private final Map<UUID, PlayerQuestProfile> profiles = new HashMap<>();

    public QuestSavedData() {
    }

    public static QuestSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(QuestSavedData::new, QuestSavedData::load, null),
                FILE_ID
        );
    }

    public PlayerQuestProfile profile(UUID uuid) {
        return profiles.computeIfAbsent(uuid, k -> {
            setDirty();
            return new PlayerQuestProfile();
        });
    }

    public PlayerQuestProfile peekProfile(UUID uuid) {
        return profiles.get(uuid);
    }

    public Map<UUID, PlayerQuestProfile> allProfiles() {
        return Collections.unmodifiableMap(profiles);
    }

    public String activeSeasonId() {
        return activeSeasonId;
    }

    public long seasonStartEpochSec() {
        return seasonStartEpochSec;
    }

    public long seasonEndEpochSec() {
        return seasonEndEpochSec;
    }

    public String archivedSeasonId() {
        return archivedSeasonId;
    }

    public long archivedGraceUntilEpochSec() {
        return archivedGraceUntilEpochSec;
    }

    public void updateSeasonWindow(String seasonId, long startSec, long endSec) {
        this.activeSeasonId = seasonId == null ? "" : seasonId;
        this.seasonStartEpochSec = startSec;
        this.seasonEndEpochSec = endSec;
        setDirty();
    }

    public void updateCurrentSeasonSnapshot(SeasonPassConfig cfg) {
        if (cfg == null) {
            return;
        }
        String json = QuestDataConfig.serializeSeasonSnapshot(cfg);
        if (!json.isBlank() && !json.equals(this.currentSeasonConfigJson)) {
            this.currentSeasonConfigJson = json;
            setDirty();
        }
    }

    public void archiveSeason(String prevId, long graceUntilSec) {
        this.archivedSeasonId = prevId == null ? "" : prevId;
        this.archivedGraceUntilEpochSec = graceUntilSec;
        if (this.currentSeasonConfigJson != null && !this.currentSeasonConfigJson.isBlank()) {
            this.archivedSeasonConfigJson = this.currentSeasonConfigJson;
            this.cachedArchivedLevels = null;
        }
        setDirty();
    }

    /** 获取上一赛季定格的等级奖励表（若历史存档无快照则安全回退至当前配置表）。 */
    public List<PassLevelSpec> resolveArchivedLevels(SeasonPassConfig fallbackCurrent) {
        if (cachedArchivedLevels == null && archivedSeasonConfigJson != null && !archivedSeasonConfigJson.isBlank()) {
            List<PassLevelSpec> parsed = QuestDataConfig.deserializeArchivedLevels(archivedSeasonConfigJson);
            if (!parsed.isEmpty()) {
                cachedArchivedLevels = parsed;
            }
        }
        if (cachedArchivedLevels != null && !cachedArchivedLevels.isEmpty()) {
            return cachedArchivedLevels;
        }
        return fallbackCurrent != null ? fallbackCurrent.resolvedLevels() : List.of();
    }

    // ================================================================ 序列化与反序列化

    public static QuestSavedData load(CompoundTag root, HolderLookup.Provider registries) {
        QuestSavedData data = new QuestSavedData();
        data.activeSeasonId = root.getString("activeSeasonId");
        data.seasonStartEpochSec = root.getLong("seasonStartEpochSec");
        data.seasonEndEpochSec = root.getLong("seasonEndEpochSec");
        data.currentSeasonConfigJson = readLargeString(root, "currentSeasonConfigJson", "currentSeasonConfigGzip");
        data.archivedSeasonId = root.getString("archivedSeasonId");
        data.archivedGraceUntilEpochSec = root.getLong("archivedGraceUntilEpochSec");
        data.archivedSeasonConfigJson = readLargeString(root, "archivedSeasonConfigJson", "archivedSeasonConfigGzip");

        ListTag playerList = root.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < playerList.size(); i++) {
            CompoundTag pTag = playerList.getCompound(i);
            if (!pTag.hasUUID("uuid")) {
                continue;
            }
            UUID uuid = pTag.getUUID("uuid");
            data.profiles.put(uuid, PlayerQuestProfile.fromTag(pTag));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        root.putInt("schemaVersion", SCHEMA_VERSION);
        root.putString("activeSeasonId", activeSeasonId);
        root.putLong("seasonStartEpochSec", seasonStartEpochSec);
        root.putLong("seasonEndEpochSec", seasonEndEpochSec);
        writeLargeString(root, "currentSeasonConfigJson", "currentSeasonConfigGzip", currentSeasonConfigJson);
        root.putString("archivedSeasonId", archivedSeasonId);
        root.putLong("archivedGraceUntilEpochSec", archivedGraceUntilEpochSec);
        writeLargeString(root, "archivedSeasonConfigJson", "archivedSeasonConfigGzip", archivedSeasonConfigJson);

        ListTag playerList = new ListTag();
        for (Map.Entry<UUID, PlayerQuestProfile> entry : profiles.entrySet()) {
            CompoundTag pTag = entry.getValue().toTag();
            pTag.putUUID("uuid", entry.getKey());
            playerList.add(pTag);
        }
        root.put("players", playerList);
        return root;
    }

    /** 规避 NBT StringTag 65535 字节硬上限：大 JSON 统一以 GZIP ByteArray 落盘并兼容读取旧字段。 */
    public static void writeLargeString(CompoundTag root, String legacyKey, String gzipKey, String value) {
        String safe = value == null ? "" : value;
        if (safe.isEmpty()) {
            root.putString(legacyKey, "");
            return;
        }
        byte[] utf8 = safe.getBytes(StandardCharsets.UTF_8);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(utf8.length, 4096));
             GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(utf8);
            gzip.finish();
            root.putByteArray(gzipKey, baos.toByteArray());
            // 仅在安全长度内同步保留 legacyKey，超长配置绝不写入超限 StringTag
            root.putString(legacyKey, utf8.length <= SAFE_NBT_STRING_BYTES ? safe : "");
        } catch (Exception e) {
            QuestMod.LOGGER.warn("[{}] 压缩存档字段 {} 失败：{}", QuestMod.MODID, gzipKey, e.getMessage());
            if (utf8.length <= SAFE_NBT_STRING_BYTES) {
                root.putString(legacyKey, safe);
            }
        }
    }

    public static String readLargeString(CompoundTag root, String legacyKey, String gzipKey) {
        if (root.contains(gzipKey, Tag.TAG_BYTE_ARRAY)) {
            byte[] compressed = root.getByteArray(gzipKey);
            if (compressed.length > 0) {
                try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed));
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096];
                    int read;
                    while ((read = gis.read(buf)) >= 0) {
                        out.write(buf, 0, read);
                    }
                    return out.toString(StandardCharsets.UTF_8);
                } catch (Exception e) {
                    QuestMod.LOGGER.warn("[{}] 解压存档字段 {} 失败，尝试回退读取旧字段：{}", QuestMod.MODID, gzipKey, e.getMessage());
                }
            }
        }
        return root.getString(legacyKey);
    }

    // ================================================================ 玩家档案结构

    public static final class PlayerQuestProfile {
        public String seasonId = "";
        public long seasonPoints = 0L;
        public boolean premiumUnlocked = false;
        public boolean deluxeUnlocked = false;
        public long premiumUnlockedAt = 0L;

        // 上赛季宽限补领归档快照
        public String prevSeasonId = "";
        public long prevSeasonPoints = 0L;
        public boolean prevSeasonPremiumUnlocked = false;
        public long prevSeasonGraceUntilSec = 0L;

        // 每日进度与昨日未领宽限快照
        public String dayKey = "";
        public int dailyOnlineSeconds = 0;
        public List<QuestInstance> dailyQuests = new ArrayList<>();

        public String prevDayKey = "";
        public int prevDayOnlineSeconds = 0;
        public List<QuestInstance> prevDayQuests = new ArrayList<>();

        // 每周进度
        public String weekKey = "";
        public int weeklyOnlineSeconds = 0;
        public List<QuestInstance> weeklyQuests = new ArrayList<>();

        // 第一道幂等账本（持久化）与并发防双击锁（内存态）
        public final LinkedHashSet<String> claimedRequestIds = new LinkedHashSet<>();
        private transient Set<String> inFlightKeys;

        public boolean hasClaimed(String requestId) {
            return claimedRequestIds.contains(requestId);
        }

        public synchronized boolean tryAcquireInFlight(String requestId) {
            if (claimedRequestIds.contains(requestId)) {
                return false;
            }
            if (inFlightKeys == null) {
                inFlightKeys = new HashSet<>();
            }
            return inFlightKeys.add(requestId);
        }

        public synchronized void releaseInFlight(String requestId) {
            if (inFlightKeys != null) {
                inFlightKeys.remove(requestId);
            }
        }

        public synchronized void markClaimed(String requestId) {
            claimedRequestIds.add(requestId);
            if (inFlightKeys != null) {
                inFlightKeys.remove(requestId);
            }
        }

        public void pruneArchivedClaimKeys(String keepSeasonA, String keepSeasonB) {
            String prefixA = "quest:" + keepSeasonA + ":";
            String prefixB = (keepSeasonB == null || keepSeasonB.isBlank()) ? null : ("quest:" + keepSeasonB + ":");
            claimedRequestIds.removeIf(key -> {
                if (key.startsWith(prefixA)) {
                    return false;
                }
                return prefixB == null || !key.startsWith(prefixB);
            });
        }

        public CompoundTag toTag() {
            CompoundTag t = new CompoundTag();
            t.putString("seasonId", seasonId);
            t.putLong("seasonPoints", seasonPoints);
            t.putBoolean("premiumUnlocked", premiumUnlocked);
            t.putBoolean("deluxeUnlocked", deluxeUnlocked);
            t.putLong("premiumUnlockedAt", premiumUnlockedAt);

            t.putString("prevSeasonId", prevSeasonId);
            t.putLong("prevSeasonPoints", prevSeasonPoints);
            t.putBoolean("prevSeasonPremiumUnlocked", prevSeasonPremiumUnlocked);
            t.putLong("prevSeasonGraceUntilSec", prevSeasonGraceUntilSec);

            t.putString("dayKey", dayKey);
            t.putInt("dailyOnlineSeconds", dailyOnlineSeconds);
            t.put("dailyQuests", saveQuestList(dailyQuests));

            t.putString("prevDayKey", prevDayKey);
            t.putInt("prevDayOnlineSeconds", prevDayOnlineSeconds);
            t.put("prevDayQuests", saveQuestList(prevDayQuests));

            t.putString("weekKey", weekKey);
            t.putInt("weeklyOnlineSeconds", weeklyOnlineSeconds);
            t.put("weeklyQuests", saveQuestList(weeklyQuests));

            ListTag claims = new ListTag();
            for (String key : claimedRequestIds) {
                claims.add(StringTag.valueOf(key));
            }
            t.put("claimedRequestIds", claims);
            return t;
        }

        public static PlayerQuestProfile fromTag(CompoundTag t) {
            PlayerQuestProfile p = new PlayerQuestProfile();
            p.seasonId = t.getString("seasonId");
            p.seasonPoints = Math.max(0L, t.getLong("seasonPoints"));
            p.premiumUnlocked = t.getBoolean("premiumUnlocked");
            p.deluxeUnlocked = t.getBoolean("deluxeUnlocked");
            p.premiumUnlockedAt = t.getLong("premiumUnlockedAt");

            p.prevSeasonId = t.getString("prevSeasonId");
            p.prevSeasonPoints = Math.max(0L, t.getLong("prevSeasonPoints"));
            p.prevSeasonPremiumUnlocked = t.getBoolean("prevSeasonPremiumUnlocked");
            p.prevSeasonGraceUntilSec = t.getLong("prevSeasonGraceUntilSec");

            p.dayKey = t.getString("dayKey");
            p.dailyOnlineSeconds = Math.max(0, t.getInt("dailyOnlineSeconds"));
            p.dailyQuests = loadQuestList(t.getList("dailyQuests", Tag.TAG_COMPOUND));

            p.prevDayKey = t.getString("prevDayKey");
            p.prevDayOnlineSeconds = Math.max(0, t.getInt("prevDayOnlineSeconds"));
            p.prevDayQuests = loadQuestList(t.getList("prevDayQuests", Tag.TAG_COMPOUND));

            p.weekKey = t.getString("weekKey");
            p.weeklyOnlineSeconds = Math.max(0, t.getInt("weeklyOnlineSeconds"));
            p.weeklyQuests = loadQuestList(t.getList("weeklyQuests", Tag.TAG_COMPOUND));

            ListTag claims = t.getList("claimedRequestIds", Tag.TAG_STRING);
            for (int i = 0; i < claims.size(); i++) {
                String key = claims.getString(i);
                if (!key.isBlank()) {
                    p.claimedRequestIds.add(key);
                }
            }
            return p;
        }

        private static ListTag saveQuestList(List<QuestInstance> list) {
            ListTag out = new ListTag();
            if (list != null) {
                for (QuestInstance q : list) {
                    out.add(q.toTag());
                }
            }
            return out;
        }

        private static List<QuestInstance> loadQuestList(ListTag tag) {
            List<QuestInstance> out = new ArrayList<>(tag.size());
            for (int i = 0; i < tag.size(); i++) {
                out.add(QuestInstance.fromTag(tag.getCompound(i)));
            }
            return out;
        }
    }

    /** 单个子目标的独立进度状态（继承纯 Java {@link ConfigModels.SubTargetState} 便于离线单测复用核心算法）。 */
    public static final class SubTargetProgress extends ConfigModels.SubTargetState {
        public SubTargetProgress() {
            super();
        }

        public SubTargetProgress(String target, String label, int count, int progress) {
            super(target, label, count, progress);
        }

        public CompoundTag toTag() {
            CompoundTag t = new CompoundTag();
            t.putString("target", target == null ? "" : target);
            t.putString("label", label == null ? "" : label);
            t.putInt("count", Math.max(1, count));
            t.putInt("progress", Math.max(0, Math.min(Math.max(1, count), progress)));
            return t;
        }

        public static SubTargetProgress fromTag(CompoundTag t) {
            return new SubTargetProgress(
                    t.getString("target"),
                    t.getString("label"),
                    Math.max(1, t.getInt("count")),
                    Math.max(0, t.getInt("progress"))
            );
        }
    }

    /** 玩家已抽出的单条任务实例（支持多子目标分项计数，100% 兼容旧存档）。 */
    public static final class QuestInstance {
        public String id = "";
        public String title = "";
        public String description = "";
        public String categoryTag = "";
        public String requiredMod = "";
        public String type = "";
        public String targetFilter = "";
        public int targetCount = 1;
        public int progress = 0;
        public List<SubTargetProgress> subTargets = new ArrayList<>();
        public boolean invalidated = false;
        public boolean replacedOnce = false;
        public RewardBundleSpec fallbackReward = new RewardBundleSpec();

        public QuestInstance() {
        }

        public static QuestInstance fromDef(QuestDefSpec def) {
            QuestInstance inst = new QuestInstance();
            inst.id = def.id;
            inst.title = def.title;
            inst.description = def.description;
            inst.categoryTag = def.categoryTag == null ? "" : def.categoryTag;
            inst.requiredMod = def.requiredMod == null ? "" : def.requiredMod;
            inst.type = def.type;
            inst.targetFilter = def.targetFilter == null ? "" : def.targetFilter;
            inst.subTargets = new ArrayList<>();
            for (TaskTarget tt : def.resolvedTargets()) {
                inst.subTargets.add(new SubTargetProgress(tt.target, tt.label, Math.max(1, tt.count), 0));
            }
            inst.recalculateAggregateProgress();
            inst.invalidated = false;
            inst.replacedOnce = false;
            inst.fallbackReward = def.reward != null ? def.reward : new RewardBundleSpec();
            return inst;
        }

        public void ensureSubTargetsInitialized() {
            if (subTargets == null) {
                subTargets = new ArrayList<>();
            }
            if (subTargets.isEmpty()) {
                int cnt = Math.max(1, targetCount);
                int prog = Math.max(0, Math.min(cnt, progress));
                subTargets.add(new SubTargetProgress(targetFilter, "", cnt, prog));
            }
            recalculateAggregateProgress();
        }

        public void recalculateAggregateProgress() {
            if (subTargets == null || subTargets.isEmpty()) {
                this.targetCount = Math.max(1, this.targetCount);
                this.progress = Math.max(0, Math.min(this.targetCount, this.progress));
                return;
            }
            int totalTarget = 0;
            int totalProgress = 0;
            for (SubTargetProgress st : subTargets) {
                if (st == null) {
                    continue;
                }
                st.count = Math.max(1, st.count);
                st.progress = Math.max(0, Math.min(st.count, st.progress));
                totalTarget += st.count;
                totalProgress += st.progress;
            }
            this.targetCount = Math.max(1, totalTarget);
            this.progress = Math.max(0, Math.min(this.targetCount, totalProgress));
        }

        public boolean isCompleted() {
            if (invalidated) {
                return false;
            }
            if (subTargets != null && !subTargets.isEmpty()) {
                for (SubTargetProgress st : subTargets) {
                    if (st != null && !st.isCompleted()) {
                        return false;
                    }
                }
                return true;
            }
            return progress >= Math.max(1, targetCount);
        }

        public QuestTaskType parsedType() {
            return QuestTaskType.fromName(type);
        }

        public boolean matchesFilter(String actualKey) {
            return matchesFilter(actualKey, null);
        }

        public boolean matchesFilter(String actualKey, Set<String> actualTags) {
            ensureSubTargetsInitialized();
            for (SubTargetProgress st : subTargets) {
                if (st != null && st.matches(actualKey, actualTags)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 推进所有匹配的未完成子目标进度，并自动重算总进度。
         *
         * @param requireEnemyForWildcard 仅对击杀类任务通配目标生效（通配"任意敌对生物"要求实体系 Enemy，显式指定实体 ID/Tag 则不限）
         */
        public boolean advanceProgress(String actualKey, Set<String> actualTags, int amount,
                                       boolean requireEnemyForWildcard, boolean entityIsEnemy) {
            if (invalidated || amount <= 0 || isCompleted()) {
                return false;
            }
            ensureSubTargetsInitialized();
            boolean anyChanged = ConfigModels.SubTargetState.advanceAll(
                    subTargets, actualKey, actualTags, amount, requireEnemyForWildcard, entityIsEnemy);
            if (anyChanged) {
                recalculateAggregateProgress();
            }
            return anyChanged;
        }

        /**
         * 尝试将一份物品提交给未完成的匹配子目标（供 {@code SUBMIT_ITEM} 服务端扣背包与单元测试共用）。
         *
         * @param targetIndex 指定子目标下标（{@code < 0} 表示匹配全部未完成子目标）
         * @return 实际消耗数量
         */
        public int consumeSubmittedItem(String itemId, Set<String> itemTags, int availableCount, int targetIndex) {
            if (invalidated || availableCount <= 0 || isCompleted()) {
                return 0;
            }
            ensureSubTargetsInitialized();
            int consumed = ConfigModels.SubTargetState.consumeSubmittedItem(
                    subTargets, itemId, itemTags, availableCount, targetIndex);
            if (consumed > 0) {
                recalculateAggregateProgress();
            }
            return consumed;
        }

        public CompoundTag toTag() {
            ensureSubTargetsInitialized();
            CompoundTag t = new CompoundTag();
            t.putString("id", id);
            t.putString("title", title);
            t.putString("description", description);
            t.putString("categoryTag", categoryTag);
            t.putString("requiredMod", requiredMod);
            t.putString("type", type);
            t.putString("targetFilter", targetFilter);
            t.putInt("targetCount", targetCount);
            t.putInt("progress", progress);
            t.putBoolean("invalidated", invalidated);
            t.putBoolean("replacedOnce", replacedOnce);

            ListTag stList = new ListTag();
            for (SubTargetProgress st : subTargets) {
                if (st != null) {
                    stList.add(st.toTag());
                }
            }
            t.put("subTargets", stList);

            t.putLong("rMoney", fallbackReward != null ? fallbackReward.money : 0L);
            t.putLong("rPoints", fallbackReward != null ? fallbackReward.points : 0L);
            ListTag itemsTag = new ListTag();
            if (fallbackReward != null && fallbackReward.items != null) {
                for (ItemRewardSpec item : fallbackReward.items) {
                    CompoundTag it = new CompoundTag();
                    it.putString("item", item.item == null ? "" : item.item);
                    it.putInt("count", item.count);
                    it.putString("customName", item.customName == null ? "" : item.customName);
                    itemsTag.add(it);
                }
            }
            t.put("rItems", itemsTag);
            return t;
        }

        public static QuestInstance fromTag(CompoundTag t) {
            QuestInstance q = new QuestInstance();
            q.id = t.getString("id");
            q.title = t.getString("title");
            q.description = t.getString("description");
            q.categoryTag = t.getString("categoryTag");
            q.requiredMod = t.getString("requiredMod");
            q.type = t.getString("type");
            q.targetFilter = t.getString("targetFilter");
            q.targetCount = Math.max(1, t.getInt("targetCount"));
            q.progress = Math.max(0, t.getInt("progress"));
            q.invalidated = t.getBoolean("invalidated");
            q.replacedOnce = t.getBoolean("replacedOnce");

            q.subTargets = new ArrayList<>();
            if (t.contains("subTargets", Tag.TAG_LIST)) {
                ListTag stList = t.getList("subTargets", Tag.TAG_COMPOUND);
                for (int i = 0; i < stList.size(); i++) {
                    q.subTargets.add(SubTargetProgress.fromTag(stList.getCompound(i)));
                }
            }
            q.ensureSubTargetsInitialized();

            List<ItemRewardSpec> items = new ArrayList<>();
            ListTag itemsTag = t.getList("rItems", Tag.TAG_COMPOUND);
            for (int i = 0; i < itemsTag.size(); i++) {
                CompoundTag it = itemsTag.getCompound(i);
                items.add(new ItemRewardSpec(it.getString("item"), it.getInt("count"), it.getString("customName")));
            }
            q.fallbackReward = new RewardBundleSpec(t.getLong("rMoney"), t.getLong("rPoints"), items);
            return q;
        }
    }
}
