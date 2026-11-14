package cn.autoforged.module_shop_redpacket;

import net.minecraftforge.common.ForgeConfigSpec;

/** 服务端配置（config/module_shop_redpacket-server.toml）。 */
public final class RedPacketConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.IntValue MAX_COUNT;
    public static final ForgeConfigSpec.LongValue MIN_TOTAL;
    public static final ForgeConfigSpec.LongValue MAX_TOTAL;
    public static final ForgeConfigSpec.IntValue EXPIRE_MINUTES;
    public static final ForgeConfigSpec.IntValue SEND_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_SELF_CLAIM;
    public static final ForgeConfigSpec.BooleanValue ANNOUNCE_FINISHED;
    public static final ForgeConfigSpec.IntValue KEEP_HISTORY_HOURS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("red_packet");
        MAX_COUNT = b.comment("单个红包最多拆成几份").defineInRange("maxCount", 100, 1, 1000);
        MIN_TOTAL = b.comment("单个红包最小总额").defineInRange("minTotal", 1L, 1L, Long.MAX_VALUE / 4);
        MAX_TOTAL = b.comment("单个红包最大总额").defineInRange("maxTotal", 1_000_000L, 1L, Long.MAX_VALUE / 4);
        EXPIRE_MINUTES = b.comment("多少分钟没领完就过期，剩余金额退回发送者")
                .defineInRange("expireMinutes", 1440, 1, 60 * 24 * 30);
        SEND_COOLDOWN_SECONDS = b.comment("同一玩家两次发红包的最短间隔（秒，0 = 不限）")
                .defineInRange("sendCooldownSeconds", 10, 0, 3600);
        ALLOW_SELF_CLAIM = b.comment("发送者能否领自己的红包").define("allowSelfClaim", true);
        ANNOUNCE_FINISHED = b.comment("红包被领完时是否在聊天栏公告（含手气最佳）").define("announceFinished", true);
        KEEP_HISTORY_HOURS = b.comment("结束后的红包详情保留多少小时")
                .defineInRange("keepHistoryHours", 72, 1, 24 * 90);
        b.pop();
        SPEC = b.build();
    }

    private RedPacketConfig() {
    }
}
