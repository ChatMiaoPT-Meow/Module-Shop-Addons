package com.atoms.Bshop.blind.auction;

import java.util.List;

/**
 * 盲仓静态资料：藏品图鉴（品质 / 占格 / 价值区间 / 收藏套组）、盲仓封条主题、鉴定师角色、5 种探查道具、各轮秒仓倍率。
 * 客户端与服务端共用，只含纯数据。
 */
public final class BlindCatalog {
    private BlindCatalog() {}

    public static final int COLS = 8;
    public static final int ROWS = 6;
    public static final int CELLS = COLS * ROWS;
    public static final int ROUNDS = 5;

    public static final String[] TIER_NAMES = {"普通", "优良", "稀有", "史诗", "传说", "神话"};
    /** 白 / 绿 / 蓝 / 紫 / 橙 / 红。 */
    public static final int[] TIER_COLORS = {0xFFAEB4BD, 0xFF4FB06A, 0xFF3BA7D9, 0xFF9B6BFF, 0xFFF0A020, 0xFFE5484D};

    /** 藏品主题套组：单仓内同套组藏品 ≥ 3 件时触发 +12% 藏品共鸣溢价。 */
    public static final int SET_MIN_COUNT = 3;
    public static final double SET_BONUS_RATE = 0.12D;
    public static final String[] SET_NAMES = {"无", "下界熔火套", "深暗回响套", "皇室秘宝套", "末地龙裔套"};

    /** 第 1–4 轮的秒仓倍率：第一名 ≥ 第二名 × 倍率即直接成交；第 5 轮价高者得。 */
    public static final double[] BUYOUT = {2.0D, 1.6D, 1.4D, 1.2D, 0.0D};

    /** 赌徒领先时秒仓倍率的减免。 */
    public static final double GAMBLER_BUYOUT_CUT = 0.2D;

    /** 截至第 1–5 轮，系统对所有人公开揭示的藏品比例：轮廓。 */
    public static final double[] PUBLIC_OUTLINE = {0.30D, 0.45D, 0.60D, 0.75D, 0.90D};
    /** 截至第 1–5 轮，公开揭示品质的藏品比例（从已显示轮廓的藏品里挑）。 */
    public static final double[] PUBLIC_TIER = {0.12D, 0.22D, 0.32D, 0.42D, 0.52D};
    /** 截至第 1–5 轮，公开揭示名称与价值的藏品件数。 */
    public static final int[] PUBLIC_VALUE = {0, 0, 1, 2, 3};

    public static double buyout(int round) {
        return BUYOUT[Math.max(0, Math.min(ROUNDS - 1, round - 1))];
    }

    /** 稀有及以上藏品的成色：残损价值 ×0.3，完美价值 ×2.5。 */
    public static final double DUD_MULT = 0.3D;
    public static final double GEM_MULT = 2.5D;
    public static final int GRADE_TIER = 2;

    /** AI 演练模式的虚拟筹码上限（门票的 12 倍，足够博弈高倍盲仓且不动用真星币）。 */
    public static long practiceChipCap(VaultZone zone) {
        return Math.max(120_000L, zone.entryFee * 12L);
    }

    /** 图鉴里某品质的最低基准价。 */
    public static long tierMin(int tier) {
        long m = Long.MAX_VALUE;
        for (Relic r : RELICS) if (r.tier() == tier) m = Math.min(m, r.min());
        return m == Long.MAX_VALUE ? 0L : m;
    }

    /** 图鉴里某品质的平均基准价（各藏品区间中点的平均）。 */
    public static double tierMean(int tier) {
        double sum = 0;
        int n = 0;
        for (Relic r : RELICS) {
            if (r.tier() == tier) {
                sum += (r.min() + r.max()) / 2.0D;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** 某区某品质藏品的最低可能价值（已计入价值倍率与残损）。 */
    public static long tierMin(VaultZone zone, int tier) {
        double m = tier >= GRADE_TIER && zone.dudChance > 0 ? DUD_MULT : 1.0D;
        return (long) Math.floor(tierMin(tier) * zone.valueScale * m);
    }

    /** 某区某品质藏品的期望价值（已计入价值倍率与残损 / 完美概率）。 */
    public static double tierMean(VaultZone zone, int tier) {
        double e = tier >= GRADE_TIER ? 1 + zone.gemChance * (GEM_MULT - 1) - zone.dudChance * (1 - DUD_MULT) : 1;
        return tierMean(tier) * zone.valueScale * e;
    }

    /** 某区一件品质未知藏品的期望价值（按该区品质权重）。 */
    public static double unknownMean(VaultZone zone) {
        int[] w = tierWeights(zone);
        double sum = 0;
        int total = 0;
        for (int t = 0; t < w.length; t++) {
            sum += w[t] * tierMean(zone, t);
            total += w[t];
        }
        return total == 0 ? 0 : sum / total;
    }

    /**
     * 按已知程度推算一件藏品：lvl 3 用真实价值，lvl 2 用该品质最低可能价，lvl 0/1 用普通品质最低价。
     * 结果保证不高于真实价值。
     */
    public static long floorOf(VaultZone zone, int lvl, int tier, long value) {
        if (lvl >= 3) return value;
        if (lvl == 2) return tierMin(zone, tier);
        return tierMin(zone, 0);
    }

    public record Relic(String name, String itemId, int tier, int w, int h, long min, long max, int setId) {
        public Relic(String name, String itemId, int tier, int w, int h, long min, long max) {
            this(name, itemId, tier, w, h, min, max, 0);
        }
    }

    public static final List<Relic> RELICS = List.of(
            new Relic("煤炭", "minecraft:coal", 0, 1, 1, 40, 90, 0),
            new Relic("铁锭", "minecraft:iron_ingot", 0, 1, 1, 60, 120, 0),
            new Relic("皮革", "minecraft:leather", 0, 1, 1, 50, 100, 0),
            new Relic("橡木原木", "minecraft:oak_log", 0, 1, 2, 80, 160, 0),
            new Relic("骨头", "minecraft:bone", 0, 2, 1, 60, 130, 0),
            new Relic("圆石堆", "minecraft:cobblestone", 0, 2, 2, 100, 220, 0),

            new Relic("金锭", "minecraft:gold_ingot", 1, 1, 1, 200, 380, 3),
            new Relic("青金石块", "minecraft:lapis_block", 1, 1, 1, 220, 420, 0),
            new Relic("红石块", "minecraft:redstone_block", 1, 1, 1, 200, 400, 0),
            new Relic("书架", "minecraft:bookshelf", 1, 1, 2, 300, 560, 2),
            new Relic("鞍", "minecraft:saddle", 1, 2, 1, 350, 650, 0),
            new Relic("铁砧", "minecraft:anvil", 1, 2, 2, 500, 900, 0),

            new Relic("钻石", "minecraft:diamond", 2, 1, 1, 700, 1300, 3),
            new Relic("绿宝石块", "minecraft:emerald_block", 2, 1, 1, 800, 1500, 3),
            new Relic("末影珍珠", "minecraft:ender_pearl", 2, 1, 1, 650, 1200, 4),
            new Relic("猫唱片", "minecraft:music_disc_cat", 2, 1, 1, 700, 1400, 2),
            new Relic("烈焰棒", "minecraft:blaze_rod", 2, 1, 2, 900, 1600, 1),
            new Relic("钻石胸甲", "minecraft:diamond_chestplate", 2, 2, 2, 1500, 2600, 3),

            new Relic("附魔书", "minecraft:enchanted_book", 3, 1, 1, 1800, 3200, 2),
            new Relic("不死图腾", "minecraft:totem_of_undying", 3, 1, 1, 2200, 3800, 3),
            new Relic("潜影盒", "minecraft:shulker_box", 3, 1, 1, 2000, 3500, 2),
            new Relic("三叉戟", "minecraft:trident", 3, 1, 2, 2600, 4600, 4),
            new Relic("鞘翅", "minecraft:elytra", 3, 2, 2, 4000, 7000, 4),

            new Relic("附魔金苹果", "minecraft:enchanted_golden_apple", 4, 1, 1, 5000, 9000, 1),
            new Relic("下界合金锭", "minecraft:netherite_ingot", 4, 1, 1, 5500, 9500, 1),
            new Relic("回响碎片", "minecraft:echo_shard", 4, 1, 1, 4800, 8500, 2),
            new Relic("下界合金剑", "minecraft:netherite_sword", 4, 1, 2, 7000, 12000, 1),
            new Relic("信标", "minecraft:beacon", 4, 2, 2, 9000, 15000, 3),

            new Relic("海洋之心", "minecraft:heart_of_the_sea", 5, 1, 1, 12000, 22000, 4),
            new Relic("下界之星", "minecraft:nether_star", 5, 1, 1, 14000, 24000, 1),
            new Relic("重锤", "minecraft:mace", 5, 1, 2, 18000, 30000, 2),
            new Relic("龙首", "minecraft:dragon_head", 5, 2, 2, 22000, 40000, 4),
            new Relic("龙蛋", "minecraft:dragon_egg", 5, 2, 2, 25000, 45000, 4));

    /** 仓门封条主题：第 1 轮向全场公开，制造差异化估值预期与心理博弈。 */
    public enum VaultTheme {
        STANDARD("常规封存仓", "标准遗迹封存物资，品质与成色分布均衡"),
        ROYAL_TREASURY("皇室秘藏仓", "王室珍宝偏多，完美成色出现率提升 50%"),
        ANCIENT_ARMORY("古战场军械库", "大尺寸重型军械占比提升，易出高价值大件"),
        WEATHERED_RUIN("岁月侵蚀仓", "高阶遗物增多，但残损率与完美率双双放大"),
        ARCANE_SANCTUM("奥术祭坛仓", "秘法与圣物偏多，更容易凑齐同源套组共鸣"),
        HOARDER_CACHE("拾荒囤积仓", "仓位填充密度极高，中小件密集堆积");

        public final String title;
        public final String clue;

        VaultTheme(String title, String clue) {
            this.title = title;
            this.clue = clue;
        }

        public static VaultTheme byId(int id) {
            VaultTheme[] v = values();
            return v[Math.floorMod(id, v.length)];
        }
    }

    /** 鉴定师：开局选 1 位，技能按轮次自动生效，只有自己看得到。 */
    public enum Role {
        HUNTER("寻宝猎人", "猎", "第 1 轮锁定全仓最贵藏品的位置与品质；第 3 轮得知它的名称与价值"),
        ARCHAEOLOGIST("考古学家", "考", "第 1 轮显示全部藏品轮廓；第 2–5 轮每轮揭示 2 件占格最大的未知藏品品质"),
        ENDER_EYE("末影之眼", "眼", "第 1 轮揭示 3 件藏品的品质；第 2–5 轮每轮再揭示 2 件"),
        GOLD_EYE("金矿鉴定师", "金", "第 1 轮知普通~稀有总价值；第 2 轮知残损/完美件数与套组共鸣；第 3 轮揭示史诗以上随机一档"),
        ARCHITECT("结构师", "构", "第 1–5 轮依次得知 神话/传说/史诗/稀有/优良 藏品各占多少格"),
        STATISTICIAN("统计员", "统", "第 1 轮知史诗以上件数；第 2 轮知神话件数与套组线索；第 4 轮知各品质件数"),
        GAMBLER("赌徒", "赌", "第 3 轮随机揭示 2 件品质，第 5 轮揭示全部品质；你报价第一时秒仓倍率 −0.2");

        public final String title;
        public final String glyph;
        public final String desc;

        Role(String title, String glyph, String desc) {
            this.title = title;
            this.glyph = glyph;
            this.desc = desc;
        }

        public static Role byId(int id) {
            Role[] v = values();
            return v[Math.floorMod(id, v.length)];
        }
    }

    /** 局内道具：共 5 种，每种每局 1 次，每轮最多用 1 个，提交报价后本轮不能再用。 */
    public enum Tool {
        HAMMER("鉴定锤", "锤", true, "点一个格子：揭示该处藏品的轮廓与品质（空格会被标出）"),
        LENS("放大镜", "镜", true, "点一个格子：揭示该处藏品的名称、成色与价值"),
        SCANNER("扫描仪", "扫", false, "随机揭示 2 件未知品质藏品的品质"),
        GLOWSTICK("荧光棒", "荧", false, "得知每种品质各有几件藏品"),
        SONAR("声呐探针", "探", true, "点一个格子：扫描十字 5 格，标出全部空格、揭示命中藏品轮廓并回报区域估值区间");

        public final String title;
        public final String glyph;
        public final boolean targeted;
        public final String desc;

        Tool(String title, String glyph, boolean targeted, String desc) {
            this.title = title;
            this.glyph = glyph;
            this.targeted = targeted;
            this.desc = desc;
        }

        public static Tool byId(int id) {
            Tool[] v = values();
            return v[Math.floorMod(id, v.length)];
        }
    }

    /** 各区品质出现权重（普通→神话）。 */
    public static int[] tierWeights(VaultZone zone) {
        return switch (zone) {
            case MINESHAFT -> new int[]{40, 30, 18, 8, 3, 1};
            case NETHER_BASTION -> new int[]{25, 27, 24, 15, 7, 2};
            case ANCIENT_CITY -> new int[]{14, 22, 25, 21, 12, 6};
        };
    }
}
