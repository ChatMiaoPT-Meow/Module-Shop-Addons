package cn.autoforged.module_shop_redpacket.data;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 红包拆分算法（纯函数，便于单测）。
 *
 * <p>前提：{@code count >= 1} 且 {@code total >= count}（保证每份至少 1）。返回的列表之和恒等于 {@code total}。
 */
public final class RedPacketSplitter {
    private RedPacketSplitter() {
    }

    /** 均分：每份 total/count，除不尽的余数从前往后每份多 1。 */
    public static List<Long> even(long total, int count) {
        check(total, count);
        long base = total / count;
        long remainder = total % count;
        List<Long> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(base + (i < remainder ? 1L : 0L));
        }
        return out;
    }

    /**
     * 拼手气（二倍均值法）：每次在 {@code [1, 2 × 剩余均值]} 里随机，并给后面的人每人至少留 1；最后一份拿走剩余。
     * 期望上每份都等于平均值，但单份可以相差很大 —— 和常见的拼手气红包手感一致。
     */
    public static List<Long> lucky(long total, int count, RandomGenerator random) {
        check(total, count);
        List<Long> out = new ArrayList<>(count);
        long remaining = total;
        for (int i = 0; i < count - 1; i++) {
            int left = count - i;
            long upper = Math.max(1L, (remaining / left) * 2L);
            // 至少给后面 left-1 个人每人留 1。
            upper = Math.min(upper, remaining - (left - 1));
            long amount = upper <= 1L ? 1L : 1L + random.nextLong(upper);
            amount = Math.min(amount, upper);
            out.add(amount);
            remaining -= amount;
        }
        out.add(remaining);
        return out;
    }

    private static void check(long total, int count) {
        if (count < 1 || total < count) {
            throw new IllegalArgumentException("total=" + total + " count=" + count);
        }
    }
}
