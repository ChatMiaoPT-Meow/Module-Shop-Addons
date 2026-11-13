package cn.autoforged.module_shop_quest.config;

import java.time.DateTimeException;
import java.time.ZoneId;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 全局标量运行参数配置（落盘 {@code config/module_shop_quest-common.toml}）。
 * <p>
 * 复杂的通行证每级奖励表、积分曲线公式、每日/每周任务池权重及在线奖励档位独立存放在
 * {@code config/module_shop_quest/} 目录下的 JSON 文件中，支持 {@code /quest reload} 一键热重载。
 */
public final class QuestTomlConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.ConfigValue<String> SIDEBAR_CUSTOM_NAME;
    public static final ModConfigSpec.ConfigValue<String> ZONE_ID;
    public static final ModConfigSpec.IntValue DAILY_RESET_HOUR;
    public static final ModConfigSpec.IntValue WEEKLY_RESET_DAY_OF_WEEK;

    public static final ModConfigSpec.IntValue AFK_IDLE_SECONDS;
    public static final ModConfigSpec.DoubleValue AFK_MIN_MOVE_BLOCKS;
    public static final ModConfigSpec.DoubleValue AFK_MIN_LOOK_DEGREES;
    public static final ModConfigSpec.BooleanValue SHOW_AFK_PAUSED_HINT;

    public static final ModConfigSpec.IntValue DAILY_QUEST_COUNT;
    public static final ModConfigSpec.IntValue WEEKLY_QUEST_COUNT;
    public static final ModConfigSpec.DoubleValue MIN_MOD_QUEST_RATIO;
    public static final ModConfigSpec.BooleanValue ENABLE_PREV_DAY_GRACE_CLAIM;
    public static final ModConfigSpec.BooleanValue ENABLE_SHOP_LEDGER_BRIDGE;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("星途纪行（每日任务 + 每周任务 + 在线时长奖励 + 双层赛季通行证）核心参数配置").push("general");
        ENABLED = b
                .comment("是否启用星途纪行系统总开关（关闭后暂停在线计时、任务计数与领奖请求）")
                .define("enabled", true);
        SIDEBAR_CUSTOM_NAME = b
                .comment("商店侧栏入口自定义显示名（留空则使用多语言键 gui.module_shop_quest.sidebar_title，默认「纪行任务」）")
                .define("sidebarCustomName", "");
        b.pop();

        b.comment("日历时区与重置时间边界配置（修改后自下一周期边界起平滑生效）").push("schedule");
        ZONE_ID = b
                .comment("日历计算时区 ID（默认 Asia/Shanghai；建议与服务器 JVM 时区保持一致，避免与商店签到跨日错位）")
                .define("zoneId", "Asia/Shanghai", o -> o instanceof String s && !s.isBlank());
        DAILY_RESET_HOUR = b
                .comment("每日任务与日在线里程的重置钟点（0 = 午夜 00:00，4 = 凌晨 04:00）")
                .defineInRange("dailyResetHour", 0, 0, 23);
        WEEKLY_RESET_DAY_OF_WEEK = b
                .comment("每周任务与周累计在线里程的重置星期几（1 = 星期一，7 = 星期日，对齐 ISO 周）")
                .defineInRange("weeklyResetDayOfWeek", 1, 1, 7);
        ENABLE_PREV_DAY_GRACE_CLAIM = b
                .comment("是否允许在次日重置前补领「昨日已完成但忘记点击领取」的日任务与日在线奖励")
                .define("enablePrevDayGraceClaim", true);
        b.pop();

        b.comment("在线时长奖励与防挂机（AFK）复合采样规则").push("online_afk");
        AFK_IDLE_SECONDS = b
                .comment("玩家无操作且无有效位移/视角转动达到多少秒后暂停活跃计时（已有分钟保留不清空）")
                .defineInRange("afkIdleSeconds", 30, 10, 600);
        AFK_MIN_MOVE_BLOCKS = b
                .comment("每秒采样认定的最小有效水平位移（格）")
                .defineInRange("afkMinMoveBlocks", 0.10D, 0.01D, 2.0D);
        AFK_MIN_LOOK_DEGREES = b
                .comment("每秒采样认定的最小有效视角偏转（度）")
                .defineInRange("afkMinLookDegrees", 1.0D, 0.1D, 45.0D);
        SHOW_AFK_PAUSED_HINT = b
                .comment("是否在客户端界面温和显示「闲置已暂停计时，活动后自动继续」状态提示")
                .define("showAfkPausedHint", true);
        b.pop();

        b.comment("每日任务与每周任务抽取及联动桥接策略").push("quests");
        DAILY_QUEST_COUNT = b
                .comment("每位玩家每日从 dailyPool 抽取的任务条数")
                .defineInRange("dailyQuestCount", 4, 1, 12);
        WEEKLY_QUEST_COUNT = b
                .comment("每位玩家每周从 weeklyPool 抽取的任务条数")
                .defineInRange("weeklyQuestCount", 3, 1, 12);
        MIN_MOD_QUEST_RATIO = b
                .comment("抽池时模组生态任务（商店/股市/盲仓/抽卡）的目标最低占比（0.60 表示至少 60% 优先抽模组任务）")
                .defineInRange("minModQuestRatio", 0.60D, 0.0D, 1.0D);
        ENABLE_SHOP_LEDGER_BRIDGE = b
                .comment("是否启用公共 HistoryApi 流水增量桥接（兼容尚未打 UI 事件补丁的 module_shop-1.1.0，自动与 ApiEvents 去重且严格过滤自身发奖）")
                .define("enableShopLedgerBridge", true);
        b.pop();

        SPEC = b.build();
    }

    private QuestTomlConfig() {
    }

    /** 解析配置的时区，配置非法时安全回退至 Asia/Shanghai。 */
    public static ZoneId zoneId() {
        try {
            String raw = ZONE_ID.get();
            if (raw != null && !raw.isBlank()) {
                return ZoneId.of(raw.trim());
            }
        } catch (DateTimeException | IllegalStateException ignored) {
        }
        return ZoneId.of("Asia/Shanghai");
    }
}
