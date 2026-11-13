package cn.autoforged.module_shop_quest.config;

import cn.autoforged.module_shop_quest.QuestMod;
import cn.autoforged.module_shop_quest.config.ConfigModels.ItemRewardSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.OnlineMilestoneSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.OnlineRewardsConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.PassLevelSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestDefSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestPoolsConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestTaskType;
import cn.autoforged.module_shop_quest.config.ConfigModels.RewardBundleSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.SeasonPassConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 外部 JSON 数据表配置加载、热保存与可视化配置器双向序列化管理器（目录 {@code config/module_shop_quest/}）。
 */
public final class QuestDataConfig {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final Gson COMPACT_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private static volatile SeasonPassConfig seasonPass = createDefaultSeasonPass();
    private static volatile QuestPoolsConfig questPools = createDefaultQuestPools();
    private static volatile OnlineRewardsConfig onlineRewards = createDefaultOnlineRewards();

    private QuestDataConfig() {
    }

    public static SeasonPassConfig seasonPass() {
        return seasonPass;
    }

    public static QuestPoolsConfig questPools() {
        return questPools;
    }

    public static OnlineRewardsConfig onlineRewards() {
        return onlineRewards;
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve(QuestMod.MODID);
    }

    // ================================================================ JSON 编解码工具（供 GUI 配置器与快照共用）

    public static String toCompactJson(Object obj) {
        return obj == null ? "" : COMPACT_GSON.toJson(obj);
    }

    public static SeasonPassConfig parseSeasonPassJson(String json) {
        if (json == null || json.isBlank()) {
            return createDefaultSeasonPass();
        }
        SeasonPassConfig parsed = COMPACT_GSON.fromJson(json, SeasonPassConfig.class);
        return parsed != null ? parsed : createDefaultSeasonPass();
    }

    public static QuestPoolsConfig parseQuestPoolsJson(String json) {
        if (json == null || json.isBlank()) {
            return createDefaultQuestPools();
        }
        QuestPoolsConfig parsed = COMPACT_GSON.fromJson(json, QuestPoolsConfig.class);
        return parsed != null ? parsed : createDefaultQuestPools();
    }

    public static OnlineRewardsConfig parseOnlineRewardsJson(String json) {
        if (json == null || json.isBlank()) {
            return createDefaultOnlineRewards();
        }
        OnlineRewardsConfig parsed = COMPACT_GSON.fromJson(json, OnlineRewardsConfig.class);
        return parsed != null ? parsed : createDefaultOnlineRewards();
    }

    public static String serializeSeasonSnapshot(SeasonPassConfig cfg) {
        if (cfg == null) {
            return "";
        }
        try {
            return COMPACT_GSON.toJson(cfg);
        } catch (Exception e) {
            return "";
        }
    }

    public static List<PassLevelSpec> deserializeArchivedLevels(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            SeasonPassConfig archived = COMPACT_GSON.fromJson(json, SeasonPassConfig.class);
            if (archived == null) {
                return Collections.emptyList();
            }
            return archived.resolvedLevels();
        } catch (Exception e) {
            QuestMod.LOGGER.warn("[{}] 解析归档赛季快照失败，回退当前赛季表", QuestMod.MODID, e);
            return Collections.emptyList();
        }
    }

    /**
     * 接收来自 OP 游戏内可视化配置界面的 JSON 修改，校验后落盘写入 {@code config/module_shop_quest/} 并即时更新内存态。
     */
    public static synchronized boolean applyAndSaveAdminConfigs(
            String passJson, String poolsJson, String onlineJson, List<String> warningsOut) {
        List<String> warnings = warningsOut != null ? warningsOut : new ArrayList<>();
        try {
            Path dir = configDir();
            Files.createDirectories(dir);

            if (passJson != null && !passJson.isBlank()) {
                SeasonPassConfig parsedPass = COMPACT_GSON.fromJson(passJson, SeasonPassConfig.class);
                if (parsedPass != null) {
                    validateSeasonPass(parsedPass, seasonPass, warnings);
                    try (Writer w = Files.newBufferedWriter(dir.resolve("season_pass.json"), StandardCharsets.UTF_8)) {
                        GSON.toJson(parsedPass, w);
                    }
                    seasonPass = parsedPass;
                }
            }

            if (poolsJson != null && !poolsJson.isBlank()) {
                QuestPoolsConfig parsedPools = COMPACT_GSON.fromJson(poolsJson, QuestPoolsConfig.class);
                if (parsedPools != null) {
                    validateQuestPools(parsedPools, warnings);
                    try (Writer w = Files.newBufferedWriter(dir.resolve("quest_pools.json"), StandardCharsets.UTF_8)) {
                        GSON.toJson(parsedPools, w);
                    }
                    questPools = parsedPools;
                }
            }

            if (onlineJson != null && !onlineJson.isBlank()) {
                OnlineRewardsConfig parsedOnline = COMPACT_GSON.fromJson(onlineJson, OnlineRewardsConfig.class);
                if (parsedOnline != null) {
                    validateOnlineRewards(parsedOnline, warnings);
                    try (Writer w = Files.newBufferedWriter(dir.resolve("online_rewards.json"), StandardCharsets.UTF_8)) {
                        GSON.toJson(parsedOnline, w);
                    }
                    onlineRewards = parsedOnline;
                }
            }
            return true;
        } catch (Exception e) {
            QuestMod.LOGGER.error("[{}] 保存可视化配置失败", QuestMod.MODID, e);
            warnings.add("保存配置失败：" + e.getMessage());
            return false;
        }
    }

    public static synchronized void loadOrCreateAll(List<String> warningsOut) {
        List<String> warnings = warningsOut != null ? warningsOut : new ArrayList<>();
        try {
            Path dir = configDir();
            Files.createDirectories(dir);

            SeasonPassConfig previousPass = seasonPass;
            SeasonPassConfig loadedPass = readOrWriteDefault(
                    dir.resolve("season_pass.json"), SeasonPassConfig.class, createDefaultSeasonPass(), warnings);
            validateSeasonPass(loadedPass, previousPass, warnings);
            seasonPass = loadedPass;

            QuestPoolsConfig loadedPools = readOrWriteDefault(
                    dir.resolve("quest_pools.json"), QuestPoolsConfig.class, createDefaultQuestPools(), warnings);
            validateQuestPools(loadedPools, warnings);
            questPools = loadedPools;

            OnlineRewardsConfig loadedOnline = readOrWriteDefault(
                    dir.resolve("online_rewards.json"), OnlineRewardsConfig.class, createDefaultOnlineRewards(), warnings);
            validateOnlineRewards(loadedOnline, warnings);
            onlineRewards = loadedOnline;
        } catch (Exception e) {
            String msg = "加载配置目录失败，继续沿用内存配置：" + e.getMessage();
            warnings.add(msg);
            QuestMod.LOGGER.error("[{}] {}", QuestMod.MODID, msg, e);
        }
    }

    public static synchronized void updatePassLevelRewardAndSave(
            int level, boolean premium, long money, ItemStack heldTemplate, HolderLookup.Provider registries)
            throws IOException {
        SeasonPassConfig cfg = seasonPass;
        if (cfg.pointsRule != null && level > cfg.pointsRule.maxLevel) {
            cfg.pointsRule.maxLevel = level;
        }
        PassLevelSpec target = null;
        if (cfg.levels == null) {
            cfg.levels = new ArrayList<>();
        }
        for (PassLevelSpec spec : cfg.levels) {
            if (spec != null && spec.level == level) {
                target = spec;
                break;
            }
        }
        if (target == null) {
            List<PassLevelSpec> resolved = cfg.resolvedLevels();
            PassLevelSpec fallback = (level >= 1 && level <= resolved.size())
                    ? resolved.get(level - 1)
                    : new PassLevelSpec(level, level * 100L, level % 5 == 0,
                            new RewardBundleSpec(), new RewardBundleSpec());
            target = new PassLevelSpec(level, fallback.requiredPoints, fallback.highlight,
                    copyBundle(fallback.freeReward), copyBundle(fallback.premiumReward));
            cfg.levels.add(target);
            cfg.levels.sort((a, b) -> Integer.compare(a.level, b.level));
        }

        List<ItemRewardSpec> items = new ArrayList<>();
        if (heldTemplate != null && !heldTemplate.isEmpty()) {
            items.add(fromItemStack(heldTemplate, registries));
        }
        RewardBundleSpec updated = new RewardBundleSpec(Math.max(0L, money), 0L, items);
        if (premium) {
            target.premiumReward = updated;
        } else {
            target.freeReward = updated;
        }

        Path out = configDir().resolve("season_pass.json");
        Files.createDirectories(out.getParent());
        try (Writer writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            GSON.toJson(cfg, writer);
        }
    }

    public static ItemStack buildItemStack(ItemRewardSpec spec, HolderLookup.Provider registries) {
        if (spec == null || spec.item == null || spec.item.isBlank() || spec.count <= 0) {
            return ItemStack.EMPTY;
        }
        ResourceLocation rl = ResourceLocation.tryParse(spec.item.trim());
        if (rl == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        int clampedCount = Math.max(1, Math.min(item.getDefaultMaxStackSize(), spec.count));
        ItemStack stack = new ItemStack(item, clampedCount);
        if (spec.customName != null && !spec.customName.isBlank()) {
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(spec.customName));
        }
        if (registries != null && spec.enchantments != null && !spec.enchantments.isEmpty()) {
            try {
                HolderLookup.RegistryLookup<Enchantment> enchLookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
                ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                for (Map.Entry<String, Integer> entry : spec.enchantments.entrySet()) {
                    ResourceLocation enchId = ResourceLocation.tryParse(entry.getKey());
                    int lvl = entry.getValue() == null ? 0 : entry.getValue();
                    if (enchId != null && lvl > 0) {
                        ResourceKey<Enchantment> key = ResourceKey.create(Registries.ENCHANTMENT, enchId);
                        enchLookup.get(key).ifPresent((Holder.Reference<Enchantment> h) -> mutable.set(h, lvl));
                    }
                }
                ItemEnchantments built = mutable.toImmutable();
                if (!built.isEmpty()) {
                    stack.set(DataComponents.ENCHANTMENTS, built);
                }
            } catch (Exception e) {
                QuestMod.LOGGER.debug("[{}] 解析附魔组件跳过：{}", QuestMod.MODID, e.getMessage());
            }
        }
        return stack;
    }

    public static List<ItemStack> materializeItems(RewardBundleSpec bundle, HolderLookup.Provider registries) {
        return buildItemStacks(bundle, registries);
    }

    public static List<ItemStack> previewItems(RewardBundleSpec bundle, HolderLookup.Provider registries) {
        if (bundle == null || bundle.items == null || bundle.items.isEmpty()) {
            return List.of();
        }
        List<ItemStack> out = new ArrayList<>();
        for (ItemRewardSpec spec : bundle.items) {
            ItemStack st = buildItemStack(spec, registries);
            if (!st.isEmpty()) {
                out.add(st);
            }
        }
        return out;
    }

    public static List<ItemStack> buildItemStacks(RewardBundleSpec bundle, HolderLookup.Provider registries) {
        if (bundle == null || bundle.items == null || bundle.items.isEmpty()) {
            return List.of();
        }
        List<ItemStack> result = new ArrayList<>();
        for (ItemRewardSpec spec : bundle.items) {
            if (spec == null || spec.count <= 0) {
                continue;
            }
            ResourceLocation rl = spec.item == null ? null : ResourceLocation.tryParse(spec.item.trim());
            Item item = rl == null ? Items.AIR : BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
            if (item == Items.AIR) {
                QuestMod.LOGGER.warn("[{}] 配置了不存在的奖励物品 ID：{}，已跳过", QuestMod.MODID, spec.item);
                continue;
            }
            int remaining = spec.count;
            int maxStack = Math.max(1, item.getDefaultMaxStackSize());
            while (remaining > 0) {
                int batch = Math.min(maxStack, remaining);
                ItemRewardSpec slice = new ItemRewardSpec(spec.item, batch, spec.customName);
                if (spec.enchantments != null) {
                    slice.enchantments.putAll(spec.enchantments);
                }
                ItemStack stack = buildItemStack(slice, registries);
                if (!stack.isEmpty()) {
                    result.add(stack);
                }
                remaining -= batch;
            }
        }
        return result;
    }

    public static ItemRewardSpec fromItemStack(ItemStack stack, HolderLookup.Provider registries) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        String name = custom != null ? custom.getString() : "";
        ItemRewardSpec spec = new ItemRewardSpec(itemId, stack.getCount(), name);
        ItemEnchantments enchs = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (var entry : enchs.entrySet()) {
            entry.getKey().unwrapKey().ifPresent(k -> spec.enchantments.put(k.location().toString(), entry.getIntValue()));
        }
        return spec;
    }

    private static RewardBundleSpec copyBundle(RewardBundleSpec src) {
        if (src == null) {
            return new RewardBundleSpec();
        }
        List<ItemRewardSpec> items = new ArrayList<>();
        if (src.items != null) {
            for (ItemRewardSpec i : src.items) {
                ItemRewardSpec copy = new ItemRewardSpec(i.item, i.count, i.customName);
                if (i.enchantments != null) {
                    copy.enchantments.putAll(i.enchantments);
                }
                items.add(copy);
            }
        }
        return new RewardBundleSpec(src.money, src.points, items);
    }

    private static <T> T readOrWriteDefault(Path file, Class<T> type, T defaultObj, List<String> warnings)
            throws IOException {
        if (!Files.isRegularFile(file)) {
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(defaultObj, writer);
            }
            return defaultObj;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            T parsed = GSON.fromJson(reader, type);
            if (parsed == null) {
                warnings.add(file.getFileName() + " 内容为空，已恢复默认配置");
                return defaultObj;
            }
            return parsed;
        } catch (Exception ex) {
            warnings.add(file.getFileName() + " JSON 解析失败（" + ex.getMessage() + "），已临时采用默认配置");
            QuestMod.LOGGER.warn("[{}] 解析 {} 失败", QuestMod.MODID, file, ex);
            return defaultObj;
        }
    }

    private static void validateSeasonPass(SeasonPassConfig cfg, SeasonPassConfig prev, List<String> warnings) {
        if (cfg.seasonId == null || cfg.seasonId.isBlank()) {
            cfg.seasonId = "s1_2026";
            warnings.add("season_pass.json 缺少 seasonId，已设为 s1_2026");
        }
        cfg.durationWeeks = Math.max(1, Math.min(52, cfg.durationWeeks));
        cfg.graceClaimDaysAfterEnd = Math.max(0, Math.min(30, cfg.graceClaimDaysAfterEnd));
        cfg.rolloverPercent = Math.max(0, Math.min(100, cfg.rolloverPercent));
        cfg.rolloverCapPoints = Math.max(0L, cfg.rolloverCapPoints);
        if (cfg.freeTrackName == null || cfg.freeTrackName.isBlank()) {
            cfg.freeTrackName = "免费奖励";
        }
        if (cfg.premiumTrack == null) {
            cfg.premiumTrack = new ConfigModels.PremiumTrackSpec();
        }
        if (cfg.premiumTrack.trackName == null || cfg.premiumTrack.trackName.isBlank()) {
            cfg.premiumTrack.trackName = "进阶通行证";
        }
        cfg.premiumTrack.unlockStarCoinCost = Math.max(0L, cfg.premiumTrack.unlockStarCoinCost);
        cfg.premiumTrack.unlockBonusPoints = Math.max(0L, cfg.premiumTrack.unlockBonusPoints);
        if (cfg.premiumTrack.deluxeTrackName == null || cfg.premiumTrack.deluxeTrackName.isBlank()) {
            cfg.premiumTrack.deluxeTrackName = "典藏通行证";
        }
        if (cfg.premiumTrack.deluxeStarCoinCost < cfg.premiumTrack.unlockStarCoinCost) {
            cfg.premiumTrack.deluxeStarCoinCost = cfg.premiumTrack.unlockStarCoinCost + 2000L;
        }
        if (cfg.premiumTrack.deluxeOriginalStarCoinCost < cfg.premiumTrack.deluxeStarCoinCost) {
            cfg.premiumTrack.deluxeOriginalStarCoinCost = cfg.premiumTrack.deluxeStarCoinCost;
        }
        cfg.premiumTrack.deluxeBonusLevels = Math.max(0, Math.min(100, cfg.premiumTrack.deluxeBonusLevels));
        if (cfg.premiumTrack.deluxeExtraReward == null) {
            cfg.premiumTrack.deluxeExtraReward = new ConfigModels.PremiumTrackSpec().deluxeExtraReward;
        }
        if (cfg.pointsRule == null) {
            cfg.pointsRule = new ConfigModels.PointsRuleSpec();
        }
        cfg.pointsRule.maxLevel = Math.max(1, Math.min(200, cfg.pointsRule.maxLevel));
        cfg.pointsRule.basePointsPerLevel = Math.max(10L, cfg.pointsRule.basePointsPerLevel);
        cfg.pointsRule.buyLevelStarCoinCost = Math.max(1L, cfg.pointsRule.buyLevelStarCoinCost);
    }

    private static void validateQuestPools(QuestPoolsConfig cfg, List<String> warnings) {
        if (cfg.dailyPool == null) {
            cfg.dailyPool = new ArrayList<>();
        }
        if (cfg.weeklyPool == null) {
            cfg.weeklyPool = new ArrayList<>();
        }
        cfg.dailyPool.removeIf(q -> {
            if (q == null || q.id == null || q.id.isBlank()) {
                return true;
            }
            QuestTaskType t = q.parsedType();
            if (t == null || t.isForbiddenInDaily()) {
                warnings.add("已剔除不合规每日任务：" + q.id + " (type=" + q.type + ")");
                return true;
            }
            return false;
        });
        cfg.weeklyPool.removeIf(q -> {
            if (q == null || q.id == null || q.id.isBlank()) {
                return true;
            }
            QuestTaskType t = q.parsedType();
            if (t == null || t.isForbiddenInDaily()) {
                warnings.add("已剔除不合规每周任务：" + q.id + " (type=" + q.type + ")");
                return true;
            }
            return false;
        });
    }

    private static void validateOnlineRewards(OnlineRewardsConfig cfg, List<String> warnings) {
        if (cfg.dailyMilestones == null || cfg.dailyMilestones.isEmpty()) {
            cfg.dailyMilestones = createDefaultOnlineRewards().dailyMilestones;
            warnings.add("online_rewards.json 每日档位为空，已恢复默认三档");
        }
        if (cfg.weeklyMilestones == null || cfg.weeklyMilestones.isEmpty()) {
            cfg.weeklyMilestones = createDefaultOnlineRewards().weeklyMilestones;
            warnings.add("online_rewards.json 每周档位为空，已恢复默认三档");
        }
    }

    // ================================================================ 干净直白的默认配置（无浮夸修辞）

    public static SeasonPassConfig createDefaultSeasonPass() {
        SeasonPassConfig cfg = new SeasonPassConfig();
        cfg.seasonId = "s1_2026";
        cfg.seasonName = "第 1 赛季";
        cfg.seasonStartEpochSecond = 0L;
        cfg.durationWeeks = 8;
        cfg.graceClaimDaysAfterEnd = 7;
        cfg.rolloverPercent = 30;
        cfg.rolloverCapPoints = 600L;
        cfg.freeTrackName = "免费奖励";

        cfg.premiumTrack.enabled = true;
        cfg.premiumTrack.trackName = "进阶通行证";
        cfg.premiumTrack.unlockStarCoinCost = 2880L;
        cfg.premiumTrack.unlockBonusPoints = 200L;
        cfg.premiumTrack.deluxeEnabled = true;
        cfg.premiumTrack.deluxeTrackName = "典藏通行证";
        cfg.premiumTrack.deluxeStarCoinCost = 4880L;
        cfg.premiumTrack.deluxeOriginalStarCoinCost = 5880L;
        cfg.premiumTrack.deluxeBonusLevels = 10;
        cfg.premiumTrack.deluxeExtraReward = new RewardBundleSpec(680L, 0L, List.of(
                new ItemRewardSpec("minecraft:totem_of_undying", 1),
                new ItemRewardSpec("minecraft:enchanted_golden_apple", 2),
                new ItemRewardSpec("minecraft:netherite_ingot", 1)
        ));
        cfg.premiumTrack.allowPermissionBypass = false;
        cfg.premiumTrack.bypassPermissionNode = "module_shop_quest.pass.vip";

        cfg.pointsRule.maxLevel = 30;
        cfg.pointsRule.curveMode = "FORMULA";
        cfg.pointsRule.basePointsPerLevel = 100L;
        cfg.pointsRule.stepEveryLevels = 10;
        cfg.pointsRule.stepPointsIncrement = 20L;
        cfg.pointsRule.allowBuyLevels = true;
        cfg.pointsRule.buyLevelStarCoinCost = 150L;
        cfg.pointsRule.milestoneEveryLevels = 5;

        cfg.levels.add(new PassLevelSpec(1, 0L, true,
                new RewardBundleSpec(60L, 0L, List.of(new ItemRewardSpec("minecraft:bread", 16))),
                new RewardBundleSpec(150L, 0L, List.of(
                        new ItemRewardSpec("minecraft:golden_carrot", 12),
                        new ItemRewardSpec("minecraft:emerald", 4)))));
        cfg.levels.add(new PassLevelSpec(5, 0L, true,
                new RewardBundleSpec(120L, 0L, List.of(new ItemRewardSpec("minecraft:diamond", 2))),
                new RewardBundleSpec(280L, 0L, List.of(
                        new ItemRewardSpec("minecraft:diamond", 5),
                        new ItemRewardSpec("minecraft:golden_apple", 2)))));
        cfg.levels.add(new PassLevelSpec(10, 0L, true,
                new RewardBundleSpec(200L, 0L, List.of(
                        new ItemRewardSpec("minecraft:diamond", 4),
                        new ItemRewardSpec("minecraft:experience_bottle", 16))),
                new RewardBundleSpec(450L, 0L, List.of(
                        new ItemRewardSpec("minecraft:diamond", 8),
                        new ItemRewardSpec("minecraft:netherite_scrap", 1)))));
        cfg.levels.add(new PassLevelSpec(15, 0L, true,
                new RewardBundleSpec(260L, 0L, List.of(
                        new ItemRewardSpec("minecraft:emerald_block", 2))),
                new RewardBundleSpec(550L, 0L, List.of(
                        new ItemRewardSpec("minecraft:diamond_block", 1),
                        new ItemRewardSpec("minecraft:golden_apple", 4)))));
        cfg.levels.add(new PassLevelSpec(20, 0L, true,
                new RewardBundleSpec(350L, 0L, List.of(
                        new ItemRewardSpec("minecraft:netherite_scrap", 2))),
                new RewardBundleSpec(750L, 0L, List.of(
                        new ItemRewardSpec("minecraft:netherite_ingot", 1),
                        new ItemRewardSpec("minecraft:totem_of_undying", 1)))));
        cfg.levels.add(new PassLevelSpec(25, 0L, true,
                new RewardBundleSpec(420L, 0L, List.of(
                        new ItemRewardSpec("minecraft:diamond", 8))),
                new RewardBundleSpec(900L, 0L, List.of(
                        new ItemRewardSpec("minecraft:netherite_ingot", 1),
                        new ItemRewardSpec("minecraft:enchanted_golden_apple", 1)))));
        cfg.levels.add(new PassLevelSpec(30, 0L, true,
                new RewardBundleSpec(600L, 0L, List.of(
                        new ItemRewardSpec("minecraft:netherite_ingot", 1),
                        new ItemRewardSpec("minecraft:diamond_block", 1))),
                new RewardBundleSpec(1500L, 0L, List.of(
                        new ItemRewardSpec("minecraft:nether_star", 1),
                        new ItemRewardSpec("minecraft:netherite_ingot", 2),
                        new ItemRewardSpec("minecraft:enchanted_golden_apple", 2)))));
        return cfg;
    }

    public static QuestPoolsConfig createDefaultQuestPools() {
        QuestPoolsConfig pools = new QuestPoolsConfig();

        pools.dailyPool.add(new QuestDefSpec(
                "d_shop_signin", "商店签到", "完成 1 次每日签到",
                "signin", "module_shop", QuestTaskType.SHOP_SIGN_IN, "", 1, 25,
                new RewardBundleSpec(30L, 35L, List.of())));
        pools.dailyPool.add(new QuestDefSpec(
                "d_buy_daily", "商店购买", "在每日商店或购物城购买 1 次商品",
                "shop_buy", "module_shop", QuestTaskType.SHOP_BUY_ANY, "", 1, 22,
                new RewardBundleSpec(40L, 40L, List.of())));
        pools.dailyPool.add(new QuestDefSpec(
                "d_sell_buyback", "出售物品", "在每日收购或出货箱出售 1 次物品",
                "shop_sell", "module_shop", QuestTaskType.SHOP_SELL_BUYBACK, "", 1, 22,
                new RewardBundleSpec(45L, 40L, List.of())));
        pools.dailyPool.add(new QuestDefSpec(
                "d_stall_or_bid", "摊位与拍卖", "在玩家摊位买东西或参与 1 次拍卖出价",
                "market_social", "module_shop", QuestTaskType.STALL_BUY, "", 1, 14,
                new RewardBundleSpec(50L, 45L, List.of())));
        pools.dailyPool.add(new QuestDefSpec(
                "d_stock_spot", "股市交易", "在股市完成 1 笔买入或卖出成交",
                "stock", "module_shop_stock", QuestTaskType.STOCK_SPOT_TRADE, "", 1, 16,
                new RewardBundleSpec(55L, 45L, List.of())));
        pools.dailyPool.add(new QuestDefSpec(
                "d_blind_match", "遗迹盲仓", "参与 1 局真人遗迹盲仓",
                "blind", "module_shop_blind", QuestTaskType.BLIND_PARTICIPATE, "", 1, 14,
                new RewardBundleSpec(65L, 50L, List.of())));
        pools.dailyPool.add(new QuestDefSpec(
                "d_lottery_pull", "抽卡抽奖", "参与 1 次抽卡或刮刮乐",
                "lottery", "lottery", QuestTaskType.LOTTERY_PULL, "", 1, 15,
                new RewardBundleSpec(45L, 40L, List.of())));
        pools.dailyPool.add(new QuestDefSpec(
                "d_mine_ores", "挖掘矿石", "挖掘 8 个任意矿石方块",
                "vanilla_mine", "minecraft", QuestTaskType.VANILLA_BREAK_BLOCK, "_ore", 8, 12,
                new RewardBundleSpec(35L, 30L, List.of(new ItemRewardSpec("minecraft:torch", 16)))));
        pools.dailyPool.add(new QuestDefSpec(
                "d_kill_mobs", "击杀怪物", "击杀 10 只敌对生物",
                "vanilla_combat", "minecraft", QuestTaskType.VANILLA_KILL_MOB, "", 10, 12,
                new RewardBundleSpec(40L, 35L, List.of(new ItemRewardSpec("minecraft:cooked_beef", 4)))));
        pools.dailyPool.add(new QuestDefSpec(
                "d_fish_catch", "钓鱼", "成功钓起 3 次物品",
                "vanilla_fish", "minecraft", QuestTaskType.VANILLA_FISH, "", 3, 10,
                new RewardBundleSpec(35L, 30L, List.of())));
        pools.dailyPool.add(new QuestDefSpec(
                "d_craft_items", "合成物品", "合成 16 件任意物品",
                "vanilla_craft", "minecraft", QuestTaskType.VANILLA_CRAFT_ITEM, "", 16, 10,
                new RewardBundleSpec(30L, 30L, List.of())));

        pools.weeklyPool.add(new QuestDefSpec(
                "w_signin_5", "累计签到", "本周累计签到 5 次",
                "w_signin", "module_shop", QuestTaskType.SHOP_SIGN_IN, "", 5, 25,
                new RewardBundleSpec(180L, 180L, List.of(new ItemRewardSpec("minecraft:emerald", 4)))));
        pools.weeklyPool.add(new QuestDefSpec(
                "w_shop_buy_6", "商店采购", "本周累计购买 6 次商品",
                "w_buy", "module_shop", QuestTaskType.SHOP_BUY_ANY, "", 6, 20,
                new RewardBundleSpec(200L, 200L, List.of(new ItemRewardSpec("minecraft:diamond", 2)))));
        pools.weeklyPool.add(new QuestDefSpec(
                "w_buyback_6", "批量出售", "本周累计在收购或出货箱出售 6 次",
                "w_sell", "module_shop", QuestTaskType.SHOP_SELL_BUYBACK, "", 6, 20,
                new RewardBundleSpec(220L, 200L, List.of(new ItemRewardSpec("minecraft:diamond", 2)))));
        pools.weeklyPool.add(new QuestDefSpec(
                "w_stock_3", "股市交易", "本周完成 3 笔股票买卖",
                "w_stock", "module_shop_stock", QuestTaskType.STOCK_SPOT_TRADE, "", 3, 15,
                new RewardBundleSpec(240L, 220L, List.of(new ItemRewardSpec("minecraft:golden_apple", 2)))));
        pools.weeklyPool.add(new QuestDefSpec(
                "w_blind_2", "盲仓竞拍", "本周参与 2 局真人遗迹盲仓",
                "w_blind", "module_shop_blind", QuestTaskType.BLIND_PARTICIPATE, "", 2, 14,
                new RewardBundleSpec(260L, 240L, List.of(new ItemRewardSpec("minecraft:diamond", 3)))));
        pools.weeklyPool.add(new QuestDefSpec(
                "w_kill_45", "清理怪物", "本周累计击杀 45 只敌对生物",
                "w_combat", "minecraft", QuestTaskType.VANILLA_KILL_MOB, "", 45, 14,
                new RewardBundleSpec(180L, 160L, List.of(new ItemRewardSpec("minecraft:golden_apple", 1)))));
        pools.weeklyPool.add(new QuestDefSpec(
                "w_mine_32", "挖掘矿石", "本周累计挖掘 32 个矿石方块",
                "w_mine", "minecraft", QuestTaskType.VANILLA_BREAK_BLOCK, "_ore", 32, 14,
                new RewardBundleSpec(180L, 160L, List.of(new ItemRewardSpec("minecraft:iron_ingot", 16)))));

        return pools;
    }

    public static OnlineRewardsConfig createDefaultOnlineRewards() {
        OnlineRewardsConfig cfg = new OnlineRewardsConfig();
        cfg.dailyMilestones.add(new OnlineMilestoneSpec(
                "day_15m", "今日在线 15 分钟", 15,
                new RewardBundleSpec(25L, 25L, List.of(new ItemRewardSpec("minecraft:cooked_salmon", 4)))));
        cfg.dailyMilestones.add(new OnlineMilestoneSpec(
                "day_30m", "今日在线 30 分钟", 30,
                new RewardBundleSpec(45L, 35L, List.of(new ItemRewardSpec("minecraft:golden_carrot", 4)))));
        cfg.dailyMilestones.add(new OnlineMilestoneSpec(
                "day_60m", "今日在线 60 分钟", 60,
                new RewardBundleSpec(80L, 55L, List.of(new ItemRewardSpec("minecraft:emerald", 2)))));

        cfg.weeklyMilestones.add(new OnlineMilestoneSpec(
                "week_120m", "本周在线 120 分钟", 120,
                new RewardBundleSpec(150L, 100L, List.of(new ItemRewardSpec("minecraft:diamond", 2)))));
        cfg.weeklyMilestones.add(new OnlineMilestoneSpec(
                "week_240m", "本周在线 240 分钟", 240,
                new RewardBundleSpec(260L, 150L, List.of(new ItemRewardSpec("minecraft:golden_apple", 2)))));
        cfg.weeklyMilestones.add(new OnlineMilestoneSpec(
                "week_360m", "本周在线 360 分钟", 360,
                new RewardBundleSpec(400L, 220L, List.of(new ItemRewardSpec("minecraft:netherite_scrap", 1)))));
        return cfg;
    }
}
