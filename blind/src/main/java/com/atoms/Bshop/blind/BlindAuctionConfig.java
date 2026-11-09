package com.atoms.Bshop.blind;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class BlindAuctionConfig {
    public static final ModConfigSpec COMMON_SPEC;
    public static final ModConfigSpec.IntValue ROUND_DURATION_SECONDS;
    public static final ModConfigSpec.IntValue MATCHMAKING_WAIT_SECONDS;
    public static final ModConfigSpec.DoubleValue CHAMBER_SINK_TAX_RATE;
    public static final ModConfigSpec.DoubleValue LOSS_DIVIDEND_RATE;
    public static final ModConfigSpec.LongValue LOSS_DIVIDEND_CAP;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("blind_vault");
        ROUND_DURATION_SECONDS = b.comment("每轮报价倒计时（秒）")
                .defineInRange("roundDurationSeconds", 30, 10, 180);
        MATCHMAKING_WAIT_SECONDS = b.comment("联机匹配等待多少秒后用模拟 AI 补齐空位")
                .defineInRange("matchmakingWaitSeconds", 15, 3, 120);
        CHAMBER_SINK_TAX_RATE = b.comment("中标者开仓折现时扣除的回收税（星币回收）")
                .defineInRange("chamberSinkTaxRate", 0.05D, 0.0D, 0.5D);
        LOSS_DIVIDEND_RATE = b.comment("真人中标者亏损时，亏损额按此比例分给同局其他真人玩家（从中标者已付款中支出）")
                .defineInRange("lossDividendRate", 0.30D, 0.0D, 0.9D);
        LOSS_DIVIDEND_CAP = b.comment("每位玩家单局最多获得的亏损分红")
                .defineInRange("lossDividendCap", 1_000_000L, 0L, 1_000_000_000L);
        b.pop();
        COMMON_SPEC = b.build();
    }

    private BlindAuctionConfig() {}
}
