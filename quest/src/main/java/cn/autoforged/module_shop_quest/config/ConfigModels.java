package cn.autoforged.module_shop_quest.config;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 纯 Java 数据配置模型与算法核（支持离线单元测试与客户端/服务端可视化编辑器双向序列化）。
 */
public final class ConfigModels {
    private ConfigModels() {
    }

    // ================================================================ 通用奖励模型

    public static final class ItemRewardSpec {
        public String item = "minecraft:emerald";
        public int count = 1;
        public String customName = "";
        public Map<String, Integer> enchantments = new LinkedHashMap<>();

        public ItemRewardSpec() {
        }

        public ItemRewardSpec(String item, int count) {
            this.item = item;
            this.count = count;
        }

        public ItemRewardSpec(String item, int count, String customName) {
            this.item = item;
            this.count = count;
            this.customName = customName == null ? "" : customName;
        }
    }

    public static final class RewardBundleSpec {
        public long money = 0L;
        public long points = 0L;
        public List<ItemRewardSpec> items = new ArrayList<>();

        public RewardBundleSpec() {
        }

        public RewardBundleSpec(long money, long points, List<ItemRewardSpec> items) {
            this.money = Math.max(0L, money);
            this.points = Math.max(0L, points);
            if (items != null) {
                this.items.addAll(items);
            }
        }

        public boolean hasShopGrant() {
            return money > 0L || (items != null && !items.isEmpty());
        }

        public boolean isEmpty() {
            return money <= 0L && points <= 0L && (items == null || items.isEmpty());
        }
    }

    // ================================================================ 双层通行证与典藏版模型

    public static final class PremiumTrackSpec {
        public boolean enabled = true;
        public String trackName = "进阶通行证";
        public long unlockStarCoinCost = 2880L;
        public long unlockBonusPoints = 200L;

        public boolean deluxeEnabled = true;
        public String deluxeTrackName = "典藏通行证";
        public long deluxeStarCoinCost = 4880L;
        public long deluxeOriginalStarCoinCost = 5880L;
        public int deluxeBonusLevels = 10;
        public RewardBundleSpec deluxeExtraReward = new RewardBundleSpec(680L, 0L, List.of(
                new ItemRewardSpec("minecraft:totem_of_undying", 1),
                new ItemRewardSpec("minecraft:enchanted_golden_apple", 2),
                new ItemRewardSpec("minecraft:netherite_ingot", 1)
        ));

        public boolean allowPermissionBypass = false;
        public String bypassPermissionNode = "module_shop_quest.pass.vip";
    }

    public static final class PointsRuleSpec {
        public int maxLevel = 30;
        public String curveMode = "FORMULA";
        public long basePointsPerLevel = 100L;
        public int stepEveryLevels = 5;
        public long stepPointsIncrement = 20L;
        public boolean allowBuyLevels = true;
        public long buyLevelStarCoinCost = 150L;
        public RewardBundleSpec defaultFreeReward = new RewardBundleSpec(40L, 0L,
                List.of(new ItemRewardSpec("minecraft:experience_bottle", 4)));
        public RewardBundleSpec defaultPremiumReward = new RewardBundleSpec(90L, 0L,
                List.of(new ItemRewardSpec("minecraft:emerald", 2)));
        public int milestoneEveryLevels = 5;
        public RewardBundleSpec defaultMilestoneFreeReward = new RewardBundleSpec(120L, 0L,
                List.of(new ItemRewardSpec("minecraft:diamond", 2)));
        public RewardBundleSpec defaultMilestonePremiumReward = new RewardBundleSpec(260L, 0L,
                List.of(new ItemRewardSpec("minecraft:diamond", 4),
                        new ItemRewardSpec("minecraft:golden_apple", 2)));
    }

    public static final class PassLevelSpec {
        public int level = 1;
        public long requiredPoints = 0L;
        public boolean highlight = false;
        public RewardBundleSpec freeReward = new RewardBundleSpec();
        public RewardBundleSpec premiumReward = new RewardBundleSpec();

        public PassLevelSpec() {
        }

        public PassLevelSpec(int level, long requiredPoints, boolean highlight,
                             RewardBundleSpec freeReward, RewardBundleSpec premiumReward) {
            this.level = level;
            this.requiredPoints = requiredPoints;
            this.highlight = highlight;
            this.freeReward = freeReward;
            this.premiumReward = premiumReward;
        }
    }

    public static final class SeasonPassConfig {
        public String seasonId = "s1_2026";
        public String seasonName = "第 1 赛季";
        public long seasonStartEpochSecond = 0L;
        public int durationWeeks = 8;
        public int graceClaimDaysAfterEnd = 7;
        public int rolloverPercent = 30;
        public long rolloverCapPoints = 600L;
        public String freeTrackName = "免费奖励";
        public PremiumTrackSpec premiumTrack = new PremiumTrackSpec();
        public PointsRuleSpec pointsRule = new PointsRuleSpec();
        public List<PassLevelSpec> levels = new ArrayList<>();

        public List<PassLevelSpec> resolvedLevels() {
            int maxLv = Math.max(1, Math.min(200, pointsRule != null ? pointsRule.maxLevel : 30));
            PointsRuleSpec rule = pointsRule != null ? pointsRule : new PointsRuleSpec();
            Map<Integer, PassLevelSpec> explicit = new HashMap<>();
            if (levels != null) {
                for (PassLevelSpec spec : levels) {
                    if (spec != null && spec.level >= 1 && spec.level <= maxLv) {
                        explicit.put(spec.level, spec);
                    }
                }
            }

            List<PassLevelSpec> out = new ArrayList<>(maxLv);
            long cumulative = 0L;
            boolean explicitMode = "EXPLICIT".equalsIgnoreCase(rule.curveMode);

            for (int lv = 1; lv <= maxLv; lv++) {
                int tierStep = rule.stepEveryLevels > 0 ? (lv - 1) / rule.stepEveryLevels : 0;
                long delta = Math.max(10L, rule.basePointsPerLevel + tierStep * Math.max(0L, rule.stepPointsIncrement));
                cumulative += delta;

                PassLevelSpec custom = explicit.get(lv);
                boolean milestone = rule.milestoneEveryLevels > 0 && (lv % rule.milestoneEveryLevels == 0);
                long req = cumulative;
                if (custom != null && custom.requiredPoints > 0L) {
                    req = custom.requiredPoints;
                } else if (explicitMode && custom != null && custom.requiredPoints > 0L) {
                    req = custom.requiredPoints;
                }
                if (!out.isEmpty() && req <= out.get(out.size() - 1).requiredPoints) {
                    req = out.get(out.size() - 1).requiredPoints + Math.max(10L, rule.basePointsPerLevel);
                }
                cumulative = req;

                RewardBundleSpec free = (custom != null && custom.freeReward != null && !custom.freeReward.isEmpty())
                        ? custom.freeReward
                        : (milestone ? rule.defaultMilestoneFreeReward : rule.defaultFreeReward);
                RewardBundleSpec prem = (custom != null && custom.premiumReward != null && !custom.premiumReward.isEmpty())
                        ? custom.premiumReward
                        : (milestone ? rule.defaultMilestonePremiumReward : rule.defaultPremiumReward);
                boolean hi = (custom != null && custom.highlight) || milestone;

                out.add(new PassLevelSpec(
                        lv,
                        req,
                        hi,
                        free != null ? free : new RewardBundleSpec(),
                        prem != null ? prem : new RewardBundleSpec()
                ));
            }
            return Collections.unmodifiableList(out);
        }

        public int computeLevel(long points) {
            if (points <= 0L) {
                return 0;
            }
            List<PassLevelSpec> list = resolvedLevels();
            int lv = 0;
            for (PassLevelSpec spec : list) {
                if (points >= spec.requiredPoints) {
                    lv = spec.level;
                } else {
                    break;
                }
            }
            return lv;
        }

        public long computePointsDeltaForBonusLevels(long currentPoints, int bonusLevels) {
            if (bonusLevels <= 0) {
                return 0L;
            }
            List<PassLevelSpec> list = resolvedLevels();
            if (list.isEmpty()) {
                return 0L;
            }
            int curLv = computeLevel(currentPoints);
            long curFloor = curLv >= 1 ? list.get(curLv - 1).requiredPoints : 0L;
            long overflow = Math.max(0L, currentPoints - curFloor);
            int targetLv = Math.min(list.size(), curLv + bonusLevels);
            if (targetLv <= curLv) {
                long step = pointsRule != null ? Math.max(10L, pointsRule.basePointsPerLevel) : 100L;
                return step * bonusLevels;
            }
            long targetReq = list.get(targetLv - 1).requiredPoints + overflow;
            long maxCap = list.get(list.size() - 1).requiredPoints;
            long desired = Math.min(Math.max(targetReq, list.get(targetLv - 1).requiredPoints), maxCap);
            return Math.max(0L, desired - currentPoints);
        }

        public long computeRolloverPoints(long previousSeasonPoints) {
            if (previousSeasonPoints <= 0L || rolloverPercent <= 0 || rolloverCapPoints <= 0L) {
                return 0L;
            }
            int pct = Math.min(100, Math.max(0, rolloverPercent));
            long discounted = (previousSeasonPoints * pct) / 100L;
            return Math.min(rolloverCapPoints, Math.max(0L, discounted));
        }
    }

    // ================================================================ 任务模型

    public enum QuestTaskType {
        SHOP_SIGN_IN(true, false),
        SHOP_BUY_DAILY(true, false),
        SHOP_BUY_MALL(true, false),
        SHOP_BUY_ANY(true, false),
        SHOP_SELL_BUYBACK(true, false),
        SHOP_SHIPPING_SELL(true, false),
        STALL_BUY(true, false),
        STALL_LIST(true, false),
        AUCTION_BID(true, false),
        DEMAND_CREATE(true, false),
        PLAYER_TRADE(true, false),
        STOCK_SPOT_TRADE(true, false),
        BLIND_PARTICIPATE(true, false),
        LOTTERY_PULL(true, false),
        VANILLA_BREAK_BLOCK(false, false),
        VANILLA_KILL_MOB(false, false),
        VANILLA_CRAFT_ITEM(false, false),
        VANILLA_FISH(false, false),
        SUBMIT_ITEM(false, false),
        VISIT_BIOME(false, false),
        VISIT_STRUCTURE(false, false),
        CUSTOM_EVENT(true, false),
        HIGH_RISK_FORBIDDEN(true, true);

        private final boolean modEcosystem;
        private final boolean forbiddenInDaily;

        QuestTaskType(boolean modEcosystem, boolean forbiddenInDaily) {
            this.modEcosystem = modEcosystem;
            this.forbiddenInDaily = forbiddenInDaily;
        }

        public boolean isModEcosystem() {
            return modEcosystem;
        }

        public boolean isForbiddenInDaily() {
            return forbiddenInDaily;
        }

        /**
         * 返回该任务行为对应的目标选择器类别（供客户端目标选择器联动）：
         * {@code "item" | "block" | "entity" | "biome" | "structure" | "any"}。
         */
        public String targetKind() {
            return switch (this) {
                case SUBMIT_ITEM, VANILLA_CRAFT_ITEM, VANILLA_FISH,
                        SHOP_BUY_DAILY, SHOP_BUY_MALL, SHOP_BUY_ANY,
                        SHOP_SELL_BUYBACK, SHOP_SHIPPING_SELL, STALL_BUY, STALL_LIST -> "item";
                case VANILLA_BREAK_BLOCK -> "block";
                case VANILLA_KILL_MOB -> "entity";
                case VISIT_BIOME -> "biome";
                case VISIT_STRUCTURE -> "structure";
                default -> "any";
            };
        }

        public static QuestTaskType fromName(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String norm = raw.trim().toUpperCase(Locale.ROOT);
            if (norm.contains("LEVERAGE") || norm.contains("SHORT") || norm.contains("TEN_PULL")
                    || norm.contains("MUST_WIN")) {
                return HIGH_RISK_FORBIDDEN;
            }
            switch (norm) {
                case "KILL_ENTITY", "KILL_MOB" -> {
                    return VANILLA_KILL_MOB;
                }
                case "MINE_BLOCK", "BREAK_BLOCK" -> {
                    return VANILLA_BREAK_BLOCK;
                }
                case "CRAFT_ITEM", "CRAFT" -> {
                    return VANILLA_CRAFT_ITEM;
                }
                case "FISH", "FISHING" -> {
                    return VANILLA_FISH;
                }
                case "SUBMIT", "ITEM_SUBMIT" -> {
                    return SUBMIT_ITEM;
                }
                case "BIOME", "REACH_BIOME" -> {
                    return VISIT_BIOME;
                }
                case "STRUCTURE", "REACH_STRUCTURE" -> {
                    return VISIT_STRUCTURE;
                }
                default -> {
                }
            }
            try {
                return QuestTaskType.valueOf(norm);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * 任务多子目标项（类 FTB Quests）：支持单任务配置多个独立目标与需求数量（如击杀僵尸 5 只 + 骷髅 3 只）。
     */
    public static final class TaskTarget {
        /** 目标匹配标识：注册名（如 {@code minecraft:zombie}）、标签（{@code #minecraft:ores}）、通配符空/{@code *} 或 {@code |} 分隔。 */
        public String target = "";
        /** 可选展示名（留空由客户端按注册表译名或 target 原文展示）。 */
        public String label = "";
        /** 该子目标独立需求数量（>= 1）。 */
        public int count = 1;

        public TaskTarget() {
        }

        public TaskTarget(String target, int count) {
            this(target, "", count);
        }

        public TaskTarget(String target, String label, int count) {
            this.target = target == null ? "" : target.trim();
            this.label = label == null ? "" : label.trim();
            this.count = Math.max(1, count);
        }

        public TaskTarget copy() {
            return new TaskTarget(target, label, count);
        }

        public boolean isWildcard() {
            return isWildcardPattern(target);
        }

        public boolean matches(String actualKey) {
            return matchesTargetPattern(target, actualKey, null);
        }

        public boolean matches(String actualKey, Set<String> actualTags) {
            return matchesTargetPattern(target, actualKey, actualTags);
        }
    }

    /**
     * 纯 Java 多子目标进度状态与分批提交/事件推进核（供 {@code QuestSavedData.QuestInstance} 与离线单测共用）。
     */
    public static class SubTargetState {
        public String target = "";
        public String label = "";
        public int count = 1;
        public int progress = 0;

        public SubTargetState() {
        }

        public SubTargetState(String target, String label, int count, int progress) {
            this.target = target == null ? "" : target.trim();
            this.label = label == null ? "" : label.trim();
            this.count = Math.max(1, count);
            this.progress = Math.max(0, Math.min(this.count, progress));
        }

        public boolean isCompleted() {
            return progress >= Math.max(1, count);
        }

        public int remaining() {
            return Math.max(0, Math.max(1, count) - progress);
        }

        public boolean isWildcard() {
            return isWildcardPattern(target);
        }

        public boolean matches(String actualKey, Set<String> actualTags) {
            return matchesTargetPattern(target, actualKey, actualTags);
        }

        public static List<SubTargetState> fromDef(QuestDefSpec def) {
            List<TaskTarget> resolved = def.resolvedTargets();
            List<SubTargetState> out = new ArrayList<>(resolved.size());
            for (TaskTarget tt : resolved) {
                out.add(new SubTargetState(tt.target, tt.label, Math.max(1, tt.count), 0));
            }
            return out;
        }

        public static List<SubTargetState> fromLegacy(String targetFilter, int targetCount, int progress) {
            int cnt = Math.max(1, targetCount);
            int prog = Math.max(0, Math.min(cnt, progress));
            List<SubTargetState> out = new ArrayList<>(1);
            out.add(new SubTargetState(targetFilter, "", cnt, prog));
            return out;
        }

        public static boolean allCompleted(List<? extends SubTargetState> states) {
            if (states == null || states.isEmpty()) {
                return false;
            }
            for (SubTargetState st : states) {
                if (st != null && !st.isCompleted()) {
                    return false;
                }
            }
            return true;
        }

        public static int totalProgress(List<? extends SubTargetState> states) {
            int sum = 0;
            if (states != null) {
                for (SubTargetState st : states) {
                    if (st != null) {
                        sum += Math.max(0, Math.min(Math.max(1, st.count), st.progress));
                    }
                }
            }
            return sum;
        }

        public static int totalCount(List<? extends SubTargetState> states) {
            int sum = 0;
            if (states != null) {
                for (SubTargetState st : states) {
                    if (st != null) {
                        sum += Math.max(1, st.count);
                    }
                }
            }
            return Math.max(1, sum);
        }

        public static boolean advanceAll(List<? extends SubTargetState> states, String actualKey,
                                         Set<String> actualTags, int amount,
                                         boolean requireEnemyForWildcard, boolean entityIsEnemy) {
            if (states == null || states.isEmpty() || amount <= 0) {
                return false;
            }
            boolean anyChanged = false;
            for (SubTargetState st : states) {
                if (st == null || st.isCompleted()) {
                    continue;
                }
                if (requireEnemyForWildcard && st.isWildcard() && !entityIsEnemy) {
                    continue;
                }
                if (!st.matches(actualKey, actualTags)) {
                    continue;
                }
                int next = Math.min(Math.max(1, st.count), st.progress + amount);
                if (next > st.progress) {
                    st.progress = next;
                    anyChanged = true;
                }
            }
            return anyChanged;
        }

        public static int consumeSubmittedItem(List<? extends SubTargetState> states, String itemId,
                                               Set<String> itemTags, int availableCount, int targetIndex) {
            if (states == null || states.isEmpty() || availableCount <= 0) {
                return 0;
            }
            int remainingAvailable = availableCount;
            int consumed = 0;
            for (int i = 0; i < states.size() && remainingAvailable > 0; i++) {
                if (targetIndex >= 0 && i != targetIndex) {
                    continue;
                }
                SubTargetState st = states.get(i);
                if (st == null || st.isCompleted()) {
                    continue;
                }
                if (!st.matches(itemId, itemTags)) {
                    continue;
                }
                int take = Math.min(st.remaining(), remainingAvailable);
                if (take > 0) {
                    st.progress += take;
                    remainingAvailable -= take;
                    consumed += take;
                }
            }
            return consumed;
        }
    }

    public static boolean isWildcardPattern(String pattern) {
        return pattern == null || pattern.isBlank() || "*".equals(pattern.trim());
    }

    /**
     * 通用目标匹配：支持通配符（空 / {@code *}）、管道多选（{@code a|b}）、标签匹配（{@code #namespace:tag}）、
     * 完整注册名精确匹配（含 {@code :}）以及旧版子串匹配（如 {@code _ore}）。
     */
    public static boolean matchesTargetPattern(String pattern, String actualKey, Set<String> actualTags) {
        if (isWildcardPattern(pattern)) {
            return true;
        }
        String actual = actualKey == null ? "" : actualKey.trim().toLowerCase(Locale.ROOT);
        for (String rawToken : pattern.toLowerCase(Locale.ROOT).split("\\|")) {
            String t = rawToken.trim();
            if (t.isEmpty()) {
                continue;
            }
            if ("*".equals(t)) {
                return true;
            }
            if (t.startsWith("#")) {
                String tagId = t.substring(1).trim();
                if (tagId.isEmpty()) {
                    continue;
                }
                if (actualTags != null && !actualTags.isEmpty()) {
                    for (String actualTag : actualTags) {
                        if (actualTag == null) {
                            continue;
                        }
                        String normTag = actualTag.trim().toLowerCase(Locale.ROOT);
                        if (normTag.startsWith("#")) {
                            normTag = normTag.substring(1);
                        }
                        if (normTag.equals(tagId)) {
                            return true;
                        }
                    }
                }
                if (!actual.isEmpty() && (actual.equals(t) || actual.equals(tagId))) {
                    return true;
                }
                continue;
            }
            if (actual.isEmpty()) {
                continue;
            }
            if (t.contains(":")) {
                if (actual.equals(t) || (actual.indexOf(' ') >= 0 && actual.contains(t))) {
                    return true;
                }
            } else if (actual.equals(t) || actual.contains(t)) {
                return true;
            }
        }
        return false;
    }

    public static final class QuestDefSpec {
        public String id = "";
        public String title = "";
        public String description = "";
        public String categoryTag = "general";
        public String requiredMod = "";
        public String type = "SHOP_SIGN_IN";
        public String targetFilter = "";
        public int targetCount = 1;
        /** 多子目标列表；非空时优先使用，为空时自动兼容旧版 {@code targetFilter} + {@code targetCount}。 */
        public List<TaskTarget> targets = new ArrayList<>();
        public int weight = 10;
        public RewardBundleSpec reward = new RewardBundleSpec();

        public QuestDefSpec() {
        }

        public QuestDefSpec(String id, String title, String description, String categoryTag,
                            String requiredMod, QuestTaskType type, String targetFilter,
                            int targetCount, int weight, RewardBundleSpec reward) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.categoryTag = categoryTag;
            this.requiredMod = requiredMod == null ? "" : requiredMod;
            this.type = type.name();
            this.targetFilter = targetFilter == null ? "" : targetFilter;
            this.targetCount = Math.max(1, targetCount);
            this.weight = Math.max(1, weight);
            this.reward = reward;
        }

        public QuestDefSpec(String id, String title, String description, String categoryTag,
                            String requiredMod, QuestTaskType type, List<TaskTarget> targets,
                            int weight, RewardBundleSpec reward) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.categoryTag = categoryTag;
            this.requiredMod = requiredMod == null ? "" : requiredMod;
            this.type = type.name();
            if (targets != null) {
                for (TaskTarget tt : targets) {
                    if (tt != null) {
                        this.targets.add(tt.copy());
                    }
                }
            }
            this.weight = Math.max(1, weight);
            this.reward = reward;
            syncLegacyFieldsFromTargets();
        }

        public QuestTaskType parsedType() {
            return QuestTaskType.fromName(type);
        }

        /** 解析生效的子目标列表（若未配置多目标，自动由旧字段 targetFilter/targetCount 合成单项）。 */
        public List<TaskTarget> resolvedTargets() {
            if (targets != null && !targets.isEmpty()) {
                List<TaskTarget> out = new ArrayList<>(targets.size());
                for (TaskTarget t : targets) {
                    if (t != null) {
                        out.add(new TaskTarget(t.target, t.label, Math.max(1, t.count)));
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
            return List.of(new TaskTarget(targetFilter == null ? "" : targetFilter, "", Math.max(1, targetCount)));
        }

        public int totalTargetCount() {
            int sum = 0;
            for (TaskTarget t : resolvedTargets()) {
                sum += Math.max(1, t.count);
            }
            return Math.max(1, sum);
        }

        public boolean hasMultiTargets() {
            return targets != null && targets.size() > 1;
        }

        /** 双向同步：有 targets 时同步回写旧字段 targetFilter/targetCount，保证旧展示/统计零破坏。 */
        public void syncLegacyFieldsFromTargets() {
            if (targets != null && !targets.isEmpty()) {
                List<TaskTarget> cleaned = new ArrayList<>(targets.size());
                int sum = 0;
                List<String> keys = new ArrayList<>();
                for (TaskTarget t : targets) {
                    if (t == null) {
                        continue;
                    }
                    t.target = t.target == null ? "" : t.target.trim();
                    t.label = t.label == null ? "" : t.label.trim();
                    t.count = Math.max(1, t.count);
                    cleaned.add(t);
                    sum += t.count;
                    if (!t.target.isEmpty()) {
                        keys.add(t.target);
                    }
                }
                targets = cleaned;
                if (!cleaned.isEmpty()) {
                    this.targetCount = Math.max(1, sum);
                    this.targetFilter = cleaned.size() == 1
                            ? cleaned.get(0).target
                            : String.join("|", keys);
                    return;
                }
            }
            this.targetFilter = this.targetFilter == null ? "" : this.targetFilter.trim();
            this.targetCount = Math.max(1, this.targetCount);
        }

        public boolean matchesFilter(String actualKey) {
            return matchesFilter(actualKey, null);
        }

        public boolean matchesFilter(String actualKey, Set<String> actualTags) {
            for (TaskTarget t : resolvedTargets()) {
                if (t.matches(actualKey, actualTags)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final class QuestPoolsConfig {
        public List<QuestDefSpec> dailyPool = new ArrayList<>();
        public List<QuestDefSpec> weeklyPool = new ArrayList<>();

        public static List<QuestDefSpec> drawQuests(
                List<QuestDefSpec> pool,
                int count,
                double minModRatio,
                Set<String> loadedMods,
                boolean dailySafety,
                Random deterministic) {
            if (pool == null || pool.isEmpty() || count <= 0) {
                return List.of();
            }
            List<QuestDefSpec> available = new ArrayList<>();
            for (QuestDefSpec q : pool) {
                if (q == null || q.id == null || q.id.isBlank() || q.weight <= 0) {
                    continue;
                }
                QuestTaskType qt = q.parsedType();
                if (qt == null || (dailySafety && qt.isForbiddenInDaily())) {
                    continue;
                }
                if (!isModRequirementMet(q.requiredMod, loadedMods)) {
                    continue;
                }
                available.add(q);
            }
            if (available.isEmpty()) {
                return List.of();
            }

            int targetModMin = (int) Math.ceil(count * Math.max(0.0D, Math.min(1.0D, minModRatio)));
            List<QuestDefSpec> chosen = new ArrayList<>(count);
            Set<String> usedIds = new HashSet<>();
            Set<String> usedCategories = new HashSet<>();

            for (int i = 0; i < targetModMin && chosen.size() < count; i++) {
                QuestDefSpec pick = weightedPick(available, usedIds, usedCategories, true, true, deterministic);
                if (pick == null) {
                    pick = weightedPick(available, usedIds, usedCategories, true, false, deterministic);
                }
                if (pick == null) {
                    break;
                }
                chosen.add(pick);
                usedIds.add(pick.id);
                if (pick.categoryTag != null && !pick.categoryTag.isBlank()) {
                    usedCategories.add(pick.categoryTag);
                }
            }

            while (chosen.size() < count) {
                QuestDefSpec pick = weightedPick(available, usedIds, usedCategories, false, true, deterministic);
                if (pick == null) {
                    pick = weightedPick(available, usedIds, usedCategories, false, false, deterministic);
                }
                if (pick == null) {
                    break;
                }
                chosen.add(pick);
                usedIds.add(pick.id);
                if (pick.categoryTag != null && !pick.categoryTag.isBlank()) {
                    usedCategories.add(pick.categoryTag);
                }
            }
            return chosen;
        }

        public static boolean isModRequirementMet(String requiredMod, Set<String> loadedMods) {
            if (requiredMod == null || requiredMod.isBlank()
                    || "minecraft".equalsIgnoreCase(requiredMod)
                    || "module_shop".equalsIgnoreCase(requiredMod)) {
                return true;
            }
            return loadedMods != null && loadedMods.contains(requiredMod.trim());
        }

        private static QuestDefSpec weightedPick(
                List<QuestDefSpec> candidates,
                Set<String> usedIds,
                Set<String> usedCategories,
                boolean onlyMod,
                boolean enforceCategoryUnique,
                Random rng) {
            int totalWeight = 0;
            List<QuestDefSpec> filtered = new ArrayList<>();
            for (QuestDefSpec q : candidates) {
                if (usedIds.contains(q.id)) {
                    continue;
                }
                QuestTaskType qt = q.parsedType();
                if (onlyMod && (qt == null || !qt.isModEcosystem())) {
                    continue;
                }
                if (enforceCategoryUnique && q.categoryTag != null && !q.categoryTag.isBlank()
                        && usedCategories.contains(q.categoryTag)) {
                    continue;
                }
                filtered.add(q);
                totalWeight += Math.max(1, q.weight);
            }
            if (filtered.isEmpty() || totalWeight <= 0) {
                return null;
            }
            int roll = rng.nextInt(totalWeight);
            int acc = 0;
            for (QuestDefSpec q : filtered) {
                acc += Math.max(1, q.weight);
                if (roll < acc) {
                    return q;
                }
            }
            return filtered.get(filtered.size() - 1);
        }
    }

    // ================================================================ 在线里程碑模型

    public static final class OnlineMilestoneSpec {
        public String id = "day_15m";
        public String title = "在线 15 分钟";
        public int requiredMinutes = 15;
        public RewardBundleSpec reward = new RewardBundleSpec();

        public OnlineMilestoneSpec() {
        }

        public OnlineMilestoneSpec(String id, String title, int requiredMinutes, RewardBundleSpec reward) {
            this.id = id;
            this.title = title;
            this.requiredMinutes = Math.max(1, requiredMinutes);
            this.reward = reward;
        }
    }

    public static final class OnlineRewardsConfig {
        public List<OnlineMilestoneSpec> dailyMilestones = new ArrayList<>();
        public List<OnlineMilestoneSpec> weeklyMilestones = new ArrayList<>();
    }

    // ================================================================ 时区周期时钟工具

    public static final class PeriodClock {
        private PeriodClock() {
        }

        public static String dayKey(long epochSeconds, ZoneId zone, int resetHour) {
            ZonedDateTime zdt = Instant.ofEpochSecond(epochSeconds).atZone(zone)
                    .minusHours(Math.max(0, Math.min(23, resetHour)));
            return zdt.toLocalDate().toString();
        }

        public static String weekKey(long epochSeconds, ZoneId zone, int resetHour, int resetDayOfWeek) {
            ZonedDateTime shifted = Instant.ofEpochSecond(epochSeconds).atZone(zone)
                    .minusHours(Math.max(0, Math.min(23, resetHour)));
            DayOfWeek startDow = DayOfWeek.of(Math.max(1, Math.min(7, resetDayOfWeek)));
            LocalDate weekStart = shifted.toLocalDate().with(TemporalAdjusters.previousOrSame(startDow));
            WeekFields wf = WeekFields.of(startDow, 4);
            int weekYear = weekStart.get(wf.weekBasedYear());
            int weekNum = weekStart.get(wf.weekOfWeekBasedYear());
            return String.format(Locale.ROOT, "%04d-W%02d", weekYear, weekNum);
        }

        public static long nextDailyResetEpochSec(long nowEpochSec, ZoneId zone, int resetHour) {
            int hr = Math.max(0, Math.min(23, resetHour));
            ZonedDateTime now = Instant.ofEpochSecond(nowEpochSec).atZone(zone);
            ZonedDateTime todayReset = now.toLocalDate().atTime(hr, 0).atZone(zone);
            ZonedDateTime next = !now.isBefore(todayReset) ? todayReset.plusDays(1) : todayReset;
            return next.toEpochSecond();
        }

        public static long nextWeeklyResetEpochSec(long nowEpochSec, ZoneId zone, int resetHour, int resetDayOfWeek) {
            int hr = Math.max(0, Math.min(23, resetHour));
            DayOfWeek dow = DayOfWeek.of(Math.max(1, Math.min(7, resetDayOfWeek)));
            ZonedDateTime now = Instant.ofEpochSecond(nowEpochSec).atZone(zone);
            LocalDate candidateDate = now.toLocalDate().with(TemporalAdjusters.previousOrSame(dow));
            ZonedDateTime candidate = LocalDateTime.of(candidateDate, java.time.LocalTime.of(hr, 0)).atZone(zone);
            if (!now.isBefore(candidate)) {
                candidate = candidate.plusWeeks(1);
            }
            return candidate.toEpochSecond();
        }

        public static String formatRequestId(String seasonId, java.util.UUID uuid, String kind,
                                             String periodKey, String id) {
            String s = sanitizeSegment(seasonId, "s1");
            String k = sanitizeSegment(kind, "misc");
            String p = sanitizeSegment(periodKey, "0");
            String item = sanitizeSegment(id, "0");
            return "quest:" + s + ":" + uuid + ":" + k + ":" + p + ":" + item;
        }

        private static String sanitizeSegment(String raw, String fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            return raw.trim().replace('|', '_').replace(':', '_');
        }
    }
}
