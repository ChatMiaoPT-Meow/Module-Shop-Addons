package cn.autoforged.module_shop_quest.server;

import cn.autoforged.module_shop_quest.config.ConfigModels.SeasonPassConfig;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import cn.autoforged.module_shop_quest.data.QuestSavedData;
import cn.autoforged.module_shop_quest.data.QuestSavedData.PlayerQuestProfile;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 玩家查询与 OP 极速配置/热重载指令树（注册 {@code /quest} 与别名 {@code /shopquest}）：
 * <ul>
 *     <li>{@code /quest status} — 查看自身赛季等级、积分、进阶线状态与今日活跃分钟</li>
 *     <li>{@code /quest reload} — 热重载 {@code config/module_shop_quest/*.json} 全部奖励与积分规则</li>
 *     <li>{@code /quest admin setreward <level> <free|premium> <starcoins> <useHeldItem>} — 手持物品一键写入指定等级奖励并热生效</li>
 *     <li>{@code /quest admin setcurve <maxLevel> <basePoints> <stepEvery> <stepIncrement>} — 游戏内一键调整满级上限与积分梯度公式</li>
 *     <li>{@code /quest admin setunlockcost <starcoins> <bonusPoints>} — 游戏内一键调整星币解锁付费线价格与附赠积分</li>
 *     <li>{@code /quest admin points <add|set> <player> <amount>} — 增设玩家通行证积分</li>
 *     <li>{@code /quest admin unlockpass <player> <unlocked>} — 为指定玩家解锁/重置进阶线</li>
 *     <li>{@code /quest admin reset <player> <daily|weekly|online>} — 重置玩家当日/当周任务或在线时长</li>
 *     <li>{@code /quest admin nextseason <seasonId> <seasonName>} — 开启新赛季并自动执行结转与宽限归档</li>
 * </ul>
 */
public final class QuestCommands {
    private QuestCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(buildRoot("quest"));
        dispatcher.register(buildRoot("shopquest"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("status")
                        .executes(ctx -> showSelfStatus(ctx.getSource())))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> executeReload(ctx.getSource())))
                .then(Commands.literal("admin")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("status")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(ctx -> showTargetStatus(
                                                ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
                        .then(Commands.literal("points")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .then(Commands.argument("amount", LongArgumentType.longArg(1L, 1_000_000L))
                                                        .executes(ctx -> modifyPoints(
                                                                ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                LongArgumentType.getLong(ctx, "amount"),
                                                                true)))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("player", EntityArgument.player())
                                                .then(Commands.argument("amount", LongArgumentType.longArg(0L, 10_000_000L))
                                                        .executes(ctx -> modifyPoints(
                                                                ctx.getSource(),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                LongArgumentType.getLong(ctx, "amount"),
                                                                false))))))
                        .then(Commands.literal("unlockpass")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("unlocked", BoolArgumentType.bool())
                                                .executes(ctx -> setUnlockPass(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        BoolArgumentType.getBool(ctx, "unlocked"))))))
                        .then(Commands.literal("setreward")
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 200))
                                        .then(Commands.argument("track", StringArgumentType.word())
                                                .then(Commands.argument("starcoins", LongArgumentType.longArg(0L, 100_000_000L))
                                                        .then(Commands.argument("useHeldItem", BoolArgumentType.bool())
                                                                .executes(ctx -> setLevelRewardInGame(
                                                                        ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "level"),
                                                                        StringArgumentType.getString(ctx, "track"),
                                                                        LongArgumentType.getLong(ctx, "starcoins"),
                                                                        BoolArgumentType.getBool(ctx, "useHeldItem"))))))))
                        .then(Commands.literal("setcurve")
                                .then(Commands.argument("maxLevel", IntegerArgumentType.integer(1, 200))
                                        .then(Commands.argument("basePoints", LongArgumentType.longArg(10L, 100_000L))
                                                .then(Commands.argument("stepEvery", IntegerArgumentType.integer(1, 100))
                                                        .then(Commands.argument("stepIncrement", LongArgumentType.longArg(0L, 50_000L))
                                                                .executes(ctx -> updatePointsCurveInGame(
                                                                        ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "maxLevel"),
                                                                        LongArgumentType.getLong(ctx, "basePoints"),
                                                                        IntegerArgumentType.getInteger(ctx, "stepEvery"),
                                                                        LongArgumentType.getLong(ctx, "stepIncrement"))))))))
                        .then(Commands.literal("setunlockcost")
                                .then(Commands.argument("starcoins", LongArgumentType.longArg(1L, 100_000_000L))
                                        .then(Commands.argument("bonusPoints", LongArgumentType.longArg(0L, 100_000L))
                                                .executes(ctx -> updateUnlockCostInGame(
                                                        ctx.getSource(),
                                                        LongArgumentType.getLong(ctx, "starcoins"),
                                                        LongArgumentType.getLong(ctx, "bonusPoints"))))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("scope", StringArgumentType.word())
                                                .executes(ctx -> resetPlayerScope(
                                                        ctx.getSource(),
                                                        EntityArgument.getPlayer(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "scope"))))))
                        .then(Commands.literal("nextseason")
                                .then(Commands.argument("seasonId", StringArgumentType.word())
                                        .then(Commands.argument("seasonName", StringArgumentType.greedyString())
                                                .executes(ctx -> startNextSeason(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "seasonId"),
                                                        StringArgumentType.getString(ctx, "seasonName")))))));
    }

    private static int showSelfStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("该子命令仅限游戏内玩家使用。"));
            return 0;
        }
        return showTargetStatus(source, player);
    }

    private static int showTargetStatus(CommandSourceStack source, ServerPlayer target) {
        QuestSavedData data = QuestSavedData.get(target.server);
        QuestManager.ensurePlayerUpToDate(target, data);
        PlayerQuestProfile p = data.profile(target.getUUID());
        SeasonPassConfig pass = QuestDataConfig.seasonPass();
        int lv = pass.computeLevel(p.seasonPoints);

        source.sendSuccess(() -> Component.literal(String.format(
                "[星途纪行] 玩家 %s | 赛季：%s | 等级：Lv.%d/%d（%d 积分）| 进阶线：%s | 今日活跃：%d 分钟 | 本周活跃：%d 分钟",
                target.getGameProfile().getName(),
                pass.seasonName,
                lv,
                pass.resolvedLevels().size(),
                p.seasonPoints,
                p.premiumUnlocked ? "已解锁" : "未解锁",
                p.dailyOnlineSeconds / 60,
                p.weeklyOnlineSeconds / 60
        )).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int executeReload(CommandSourceStack source) {
        List<String> warnings = new ArrayList<>();
        QuestManager.reloadAllConfigs(source.getServer(), warnings);
        SeasonPassConfig pass = QuestDataConfig.seasonPass();
        source.sendSuccess(() -> Component.literal(String.format(
                "[星途纪行] 配置热重载成功！赛季=%s（共 %d 级，进阶解锁=%d 星币），每日池=%d 条，每周池=%d 条。",
                pass.seasonId,
                pass.resolvedLevels().size(),
                pass.premiumTrack != null ? pass.premiumTrack.unlockStarCoinCost : 0L,
                QuestDataConfig.questPools().dailyPool.size(),
                QuestDataConfig.questPools().weeklyPool.size()
        )).withStyle(ChatFormatting.GREEN), true);
        for (String w : warnings) {
            source.sendSuccess(() -> Component.literal("  ⚠ " + w).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int modifyPoints(CommandSourceStack source, ServerPlayer target, long amount, boolean add) {
        QuestSavedData data = QuestSavedData.get(target.server);
        QuestManager.ensurePlayerUpToDate(target, data);
        PlayerQuestProfile p = data.profile(target.getUUID());
        p.seasonPoints = add ? RewardGrantService.safeAddPoints(p.seasonPoints, amount) : Math.max(0L, amount);
        data.setDirty();
        QuestManager.syncFullSnapshot(target);
        int lv = QuestDataConfig.seasonPass().computeLevel(p.seasonPoints);
        source.sendSuccess(() -> Component.literal(String.format(
                "[星途纪行] 已更新 %s 的通行证积分为 %d（当前 Lv.%d）。",
                target.getGameProfile().getName(), p.seasonPoints, lv
        )).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int setUnlockPass(CommandSourceStack source, ServerPlayer target, boolean unlocked) {
        QuestSavedData data = QuestSavedData.get(target.server);
        QuestManager.ensurePlayerUpToDate(target, data);
        PlayerQuestProfile p = data.profile(target.getUUID());
        p.premiumUnlocked = unlocked;
        p.premiumUnlockedAt = unlocked ? Instant.now().getEpochSecond() : 0L;
        data.setDirty();
        QuestManager.syncFullSnapshot(target);
        source.sendSuccess(() -> Component.literal(String.format(
                "[星途纪行] 已将 %s 的当季付费进阶线状态设为：%s。",
                target.getGameProfile().getName(), unlocked ? "已解锁" : "未解锁"
        )).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int setLevelRewardInGame(CommandSourceStack source, int level, String track,
                                            long starcoins, boolean useHeldItem) {
        boolean premium = "premium".equalsIgnoreCase(track) || "paid".equalsIgnoreCase(track) || "extra".equalsIgnoreCase(track);
        ItemStack held = ItemStack.EMPTY;
        if (useHeldItem) {
            ServerPlayer player = source.getPlayer();
            if (player == null || player.getMainHandItem().isEmpty()) {
                source.sendFailure(Component.literal("开启 useHeldItem=true 时，请在主手持有要设为奖励的模板物品！"));
                return 0;
            }
            held = player.getMainHandItem().copy();
        }
        try {
            QuestDataConfig.updatePassLevelRewardAndSave(
                    level, premium, starcoins, held, source.getServer().registryAccess());
            QuestManager.reloadAllConfigs(source.getServer(), new ArrayList<>());
            source.sendSuccess(() -> Component.literal(String.format(
                    "[星途纪行] 已写入并热生效通行证 Lv.%d [%s] 奖励：%d 星币%s。",
                    level, premium ? "星币进阶线" : "免费基础线", starcoins,
                    useHeldItem ? " + 主手物品模板" : ""
            )).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("保存 season_pass.json 失败：" + e.getMessage()));
            return 0;
        }
    }

    private static int updatePointsCurveInGame(CommandSourceStack source, int maxLevel, long basePoints,
                                               int stepEvery, long stepIncrement) {
        try {
            SeasonPassConfig cfg = QuestDataConfig.seasonPass();
            cfg.pointsRule.maxLevel = maxLevel;
            cfg.pointsRule.curveMode = "FORMULA";
            cfg.pointsRule.basePointsPerLevel = basePoints;
            cfg.pointsRule.stepEveryLevels = stepEvery;
            cfg.pointsRule.stepPointsIncrement = stepIncrement;
            saveSeasonPassJson(cfg);
            QuestManager.reloadAllConfigs(source.getServer(), new ArrayList<>());
            source.sendSuccess(() -> Component.literal(String.format(
                    "[星途纪行] 积分曲线已热更新：上限 Lv.%d，基础每级 %d 积分，每 %d 级递增 +%d 积分。",
                    maxLevel, basePoints, stepEvery, stepIncrement
            )).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("保存积分曲线失败：" + e.getMessage()));
            return 0;
        }
    }

    private static int updateUnlockCostInGame(CommandSourceStack source, long starcoins, long bonusPoints) {
        try {
            SeasonPassConfig cfg = QuestDataConfig.seasonPass();
            cfg.premiumTrack.enabled = true;
            cfg.premiumTrack.unlockStarCoinCost = starcoins;
            cfg.premiumTrack.unlockBonusPoints = bonusPoints;
            saveSeasonPassJson(cfg);
            QuestManager.reloadAllConfigs(source.getServer(), new ArrayList<>());
            source.sendSuccess(() -> Component.literal(String.format(
                    "[星途纪行] 付费积分线解锁规则已热更新：消耗 %d 星币，解锁立得 +%d 积分。",
                    starcoins, bonusPoints
            )).withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("保存解锁价格失败：" + e.getMessage()));
            return 0;
        }
    }

    private static int resetPlayerScope(CommandSourceStack source, ServerPlayer target, String scope) {
        QuestSavedData data = QuestSavedData.get(target.server);
        PlayerQuestProfile p = data.profile(target.getUUID());
        if ("daily".equalsIgnoreCase(scope)) {
            p.dayKey = "";
        } else if ("weekly".equalsIgnoreCase(scope)) {
            p.weekKey = "";
        } else if ("online".equalsIgnoreCase(scope)) {
            p.dailyOnlineSeconds = 0;
            p.weeklyOnlineSeconds = 0;
        } else {
            source.sendFailure(Component.literal("未知重置范围，可选：daily | weekly | online"));
            return 0;
        }
        data.setDirty();
        QuestManager.ensurePlayerUpToDate(target, data);
        QuestManager.syncFullSnapshot(target);
        source.sendSuccess(() -> Component.literal(String.format(
                "[星途纪行] 已重置玩家 %s 的 [%s] 数据并重新下发快照。",
                target.getGameProfile().getName(), scope
        )).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int startNextSeason(CommandSourceStack source, String seasonId, String seasonName) {
        try {
            SeasonPassConfig cfg = QuestDataConfig.seasonPass();
            cfg.seasonId = seasonId.trim();
            cfg.seasonName = seasonName.trim();
            cfg.seasonStartEpochSecond = Instant.now().getEpochSecond();
            saveSeasonPassJson(cfg);
            QuestManager.reloadAllConfigs(source.getServer(), new ArrayList<>());
            source.sendSuccess(() -> Component.literal(String.format(
                    "[星途纪行] 新赛季 [%s · %s] 已开启！旧赛季积分已按配置自动结转并开放宽限期补领。",
                    cfg.seasonId, cfg.seasonName
            )).withStyle(ChatFormatting.GOLD), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("切换新赛季失败：" + e.getMessage()));
            return 0;
        }
    }

    private static void saveSeasonPassJson(SeasonPassConfig cfg) throws Exception {
        Path path = QuestDataConfig.configDir().resolve("season_pass.json");
        Files.createDirectories(path.getParent());
        var gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            gson.toJson(cfg, writer);
        }
    }
}
