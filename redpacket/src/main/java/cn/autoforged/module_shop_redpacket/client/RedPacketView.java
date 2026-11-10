package cn.autoforged.module_shop_redpacket.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/** 详情回包（CompoundTag）的只读小工具：状态、已领合计、时长文字。页面右栏的小卡与详情弹窗共用。 */
final class RedPacketView {
    enum Status { ACTIVE, DONE, EXPIRED }

    private RedPacketView() {
    }

    static Status status(CompoundTag t) {
        if (t.getLong("refunded") > 0L) {
            return Status.EXPIRED;
        }
        if (t.getBoolean("finished")) {
            return Status.DONE;
        }
        return t.getLong("expiresAt") <= t.getLong("now") ? Status.EXPIRED : Status.ACTIVE;
    }

    static long claimed(CompoundTag t) {
        ListTag list = t.getList("claims", Tag.TAG_COMPOUND);
        long sum = 0L;
        for (int i = 0; i < list.size(); i++) {
            sum += list.getCompound(i).getLong("amount");
        }
        return sum;
    }

    static int claimCount(CompoundTag t) {
        return t.getList("claims", Tag.TAG_COMPOUND).size();
    }

    static String greeting(CompoundTag t) {
        String g = t.getString("greeting");
        return g.isEmpty() ? Component.translatable("msg.module_shop_redpacket.default_greeting").getString() : g;
    }

    static String modeName(CompoundTag t) {
        return Component.translatable(t.getBoolean("lucky")
                ? "gui.module_shop_redpacket.mode_lucky_short" : "gui.module_shop_redpacket.mode_even_short").getString();
    }

    static String statusName(Status s) {
        return Component.translatable("gui.module_shop_redpacket.state_" + s.name().toLowerCase()).getString();
    }

    /** 秒数 → 「12 秒 / 3 分 5 秒 / 2 小时 10 分」。 */
    static String duration(long seconds) {
        if (seconds < 60L) {
            return Component.translatable("msg.module_shop_redpacket.seconds", seconds).getString();
        }
        if (seconds < 3600L) {
            return Component.translatable("msg.module_shop_redpacket.minutes", seconds / 60L, seconds % 60L).getString();
        }
        return Component.translatable("msg.module_shop_redpacket.hours", seconds / 3600L, (seconds % 3600L) / 60L)
                .getString();
    }

    /** 分钟数 → 「45 分钟 / 24 小时 / 1 小时 30 分」。 */
    static String minutesText(long minutes) {
        if (minutes < 60L) {
            return Component.translatable("gui.module_shop_redpacket.minutes_only", minutes).getString();
        }
        if (minutes % 60L == 0L) {
            return Component.translatable("gui.module_shop_redpacket.hours_only", minutes / 60L).getString();
        }
        return Component.translatable("msg.module_shop_redpacket.hours", minutes / 60L, minutes % 60L).getString();
    }
}
