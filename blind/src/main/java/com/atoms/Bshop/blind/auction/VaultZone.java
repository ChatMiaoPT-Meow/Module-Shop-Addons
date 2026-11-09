package com.atoms.Bshop.blind.auction;

/**
 * 三个盲仓区。越贵的区：仓内期望价值相对门票越高（赚得多），但稀有及以上藏品出现「残损」（价值 ×0.3）
 * 与「完美」（价值 ×2.5）的概率也越高，AI 的判断也更飘忽——容易大赚，也容易亏光。
 */
public enum VaultZone {
    // id, 名称, 副标题, 风险, 图标, 轮数, 席位, 最低持有, 门票, 价值倍率, 残损率, 完美率, AI 读仓偏差下限/上限
    ANCIENT_CITY(0, "远古深暗盲仓", "深暗古迹 · 期望约 7.7 倍门票", "高", "minecraft:echo_shard", 5, 4,
            4_500_000L, 1_500_000L, 170, 0.20D, 0.13D, 0.80D, 1.20D),
    NETHER_BASTION(1, "下界堡垒盲仓", "猪灵金库 · 期望约 6 倍门票", "中", "minecraft:netherite_ingot", 5, 4,
            1_500_000L, 500_000L, 80, 0.10D, 0.07D, 0.85D, 1.15D),
    MINESHAFT(2, "废弃矿井盲仓", "主世界矿道 · 期望约 4.3 倍门票", "低", "minecraft:writable_book", 5, 4,
            30_000L, 10_000L, 2, 0.0D, 0.03D, 0.90D, 1.10D);

    public final int id;
    public final String title;
    public final String subTitle;
    public final String rating;
    public final String iconItem;
    public final int maxRounds;
    public final int seatCount;
    /** 开局时最少持有的星币（门票 + 报价余量）。 */
    public final long minStarCoinReq;
    public final long entryFee;
    /** 藏品价值相对图鉴基准的倍率。 */
    public final long valueScale;
    /** 稀有及以上藏品为「残损」的概率。 */
    public final double dudChance;
    /** 稀有及以上藏品为「完美」的概率。 */
    public final double gemChance;
    public final double aiBiasLow;
    public final double aiBiasHigh;

    VaultZone(int id, String title, String subTitle, String rating, String iconItem, int maxRounds, int seatCount,
              long minStarCoinReq, long entryFee, long valueScale, double dudChance, double gemChance,
              double aiBiasLow, double aiBiasHigh) {
        this.id = id;
        this.title = title;
        this.subTitle = subTitle;
        this.rating = rating;
        this.iconItem = iconItem;
        this.maxRounds = maxRounds;
        this.seatCount = seatCount;
        this.minStarCoinReq = minStarCoinReq;
        this.entryFee = entryFee;
        this.valueScale = valueScale;
        this.dudChance = dudChance;
        this.gemChance = gemChance;
        this.aiBiasLow = aiBiasLow;
        this.aiBiasHigh = aiBiasHigh;
    }

    public static VaultZone byId(int id) {
        for (VaultZone z : values()) {
            if (z.id == id) return z;
        }
        return ANCIENT_CITY;
    }
}
