package com.atoms.Bshop.blind.auction;

public enum VaultZone {
    ANCIENT_CITY(0, "远古深暗盲仓", "深暗古迹 · 6x6封存仓", "极高", "minecraft:echo_shard", 5, 4, 200000L, 3000L),
    NETHER_BASTION(1, "下界堡垒盲仓", "猪灵金库 · 6x6封存仓", "中等", "minecraft:netherite_ingot", 5, 4, 50000L, 1000L),
    MINESHAFT(2, "废弃矿井盲仓", "主世界矿道 · 6x6封存仓", "基础", "minecraft:writable_book", 5, 4, 0L, 100L);

    public final int id;
    public final String title;
    public final String subTitle;
    public final String rating;
    public final String iconItem;
    public final int maxRounds;
    public final int seatCount;
    public final long minStarCoinReq;
    public final long entryFee;

    VaultZone(int id, String title, String subTitle, String rating, String iconItem,
              int maxRounds, int seatCount, long minStarCoinReq, long entryFee) {
        this.id = id;
        this.title = title;
        this.subTitle = subTitle;
        this.rating = rating;
        this.iconItem = iconItem;
        this.maxRounds = maxRounds;
        this.seatCount = seatCount;
        this.minStarCoinReq = minStarCoinReq;
        this.entryFee = entryFee;
    }

    public static VaultZone byId(int id) {
        for (VaultZone z : values()) {
            if (z.id == id) return z;
        }
        return ANCIENT_CITY;
    }
}
