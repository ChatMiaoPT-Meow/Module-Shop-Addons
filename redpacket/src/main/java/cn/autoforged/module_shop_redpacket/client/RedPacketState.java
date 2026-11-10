package cn.autoforged.module_shop_redpacket.client;

import cn.autoforged.module_shop_redpacket.network.RedPacketNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 发红包表单的客户端状态：内容区页面（AtomLib）和回退界面（原版）共用一份，
 * 商店界面被服务端快照重建、或在两种界面之间切换时，填好的内容都不会丢。
 */
public final class RedPacketState {
    public static boolean lucky = true;
    public static String count = "";
    public static String total = "";
    public static String password = "";
    public static String greeting = "";

    /** 服务端下发的余额与限制（-1 = 还没收到）。 */
    public static long balance = -1L;
    public static String currency = "";
    public static int maxCount = 100;
    public static long minTotal = 1L;
    public static long maxTotal = Long.MAX_VALUE;
    public static int expireMinutes = 1440;

    /** 最近一次收到的详情回包（页面右栏「最近查看的红包」用）；null = 还没看过。 */
    public static CompoundTag lastDetail;

    public static Component message = Component.empty();
    public static boolean messageError;
    public static boolean waiting;
    /** 上次发出成功的时刻（页面用来播放「已发出」提示）。 */
    public static long sentAt;

    private RedPacketState() {
    }

    /** 上次发出 status 请求的时刻（毫秒），用于节流。 */
    private static long lastStatusRequest;

    /**
     * 节流版的 {@link #requestStatus()}：距上次请求不足 {@code intervalMs} 就跳过。
     * 余额归主商店的经济系统管，商店购买、出货箱、股市、领红包都会改它，
     * 所以界面开着时要定期重新拉取，不能只在第一次打开时拿一次。
     */
    public static void refreshStatus(long intervalMs) {
        if (System.currentTimeMillis() - lastStatusRequest >= intervalMs) {
            requestStatus();
        }
    }

    public static void requestStatus() {
        lastStatusRequest = System.currentTimeMillis();
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "status");
        RedPacketNetwork.sendToServer(tag);
    }

    static void applyStatus(CompoundTag tag) {
        balance = tag.getLong("balance");
        currency = tag.getString("currency");
        maxCount = tag.getInt("maxCount");
        minTotal = tag.getLong("minTotal");
        maxTotal = tag.getLong("maxTotal");
        expireMinutes = tag.getInt("expireMinutes");
    }

    /** 返回是否成功。 */
    static boolean applyResult(CompoundTag tag) {
        waiting = false;
        if (tag.getBoolean("ok")) {
            count = "";
            total = "";
            password = "";
            greeting = "";
            message = Component.translatable("gui.module_shop_redpacket.sent_ok");
            messageError = false;
            sentAt = System.currentTimeMillis();
            requestStatus();
            return true;
        }
        Component error = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && tag.contains("error")) {
            error = Component.Serializer.fromJson(tag.getString("error"), mc.level.registryAccess());
        }
        showError(error == null ? Component.translatable("gui.module_shop_redpacket.send_failed") : error);
        requestStatus();
        return false;
    }

    public static void showError(Component text) {
        message = text;
        messageError = true;
    }

    public static String currencyName(String fallback) {
        if (!currency.isEmpty()) {
            return currency;
        }
        return fallback == null || fallback.isEmpty()
                ? Component.translatable("gui.module_shop_redpacket.currency_default").getString() : fallback;
    }

    public static long parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 18) {
            return -1L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** 本地预检 + 发送；返回是否已发出请求。 */
    public static boolean submit(String fallbackCurrency) {
        return submit(fallbackCurrency, balance);
    }

    /**
     * 同上，但用调用方给的余额做本地预检（商店内容页传主商店快照里的实时余额）。
     * 余额 &lt; 0 表示未知：跳过本地预检，交给服务端判断。
     */
    public static boolean submit(String fallbackCurrency, long knownBalance) {
        if (waiting) {
            return false;
        }
        Component error = validate(currencyName(fallbackCurrency), knownBalance, true);
        if (error != null) {
            showError(error);
            return false;
        }
        long c = parse(count);
        long t = parse(total);
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "send");
        tag.putInt("count", (int) c);
        tag.putLong("total", t);
        tag.putBoolean("lucky", lucky);
        tag.putString("password", password);
        tag.putString("greeting", greeting);
        RedPacketNetwork.sendToServer(tag);
        waiting = true;
        message = Component.translatable("gui.module_shop_redpacket.sending");
        messageError = false;
        return true;
    }

    /**
     * 校验当前表单，返回第一条错误（没有错误返回 null）。{@code strict} = 提交时：没填的项也算错；
     * 否则（页面实时提示）只检查已经填了的项，不对空白处报错。余额 &lt; 0 表示未知，跳过余额检查。
     */
    public static Component validate(String cur, long knownBalance, boolean strict) {
        long c = parse(count);
        long t = parse(total);
        if ((strict || !count.isEmpty()) && (c < 1L || c > maxCount)) {
            return Component.translatable("msg.module_shop_redpacket.bad_count", maxCount);
        }
        if ((strict || !total.isEmpty()) && (t < minTotal || t > maxTotal)) {
            return Component.translatable("msg.module_shop_redpacket.bad_total", minTotal, maxTotal, cur);
        }
        if (c >= 1L && t >= 1L && t < c) {
            return Component.translatable("msg.module_shop_redpacket.total_lt_count", cur);
        }
        if (knownBalance >= 0L && t > knownBalance) {
            return Component.translatable("msg.module_shop_redpacket.insufficient", t, cur, knownBalance);
        }
        if (!strict && password.startsWith("/")) {
            return Component.translatable("msg.module_shop_redpacket.password_slash");
        }
        return null;
    }

    /** 表单下方的预览文字（原版回退界面用）。 */
    public static Component preview(String fallbackCurrency) {
        String cur = currencyName(fallbackCurrency);
        long c = parse(count);
        long t = parse(total);
        if (c > 0L && t >= c) {
            if (lucky) {
                return Component.translatable("gui.module_shop_redpacket.preview_lucky", c, t, cur);
            }
            return t % c == 0L
                    ? Component.translatable("gui.module_shop_redpacket.preview_even", c, t / c, cur)
                    : Component.translatable("gui.module_shop_redpacket.preview_even_rem", c, t / c, t / c + 1, cur);
        }
        return Component.translatable("gui.module_shop_redpacket.preview_none", expireMinutes / 60, expireMinutes % 60);
    }
}
