package cn.autoforged.module_shop_redpacket;

import cn.autoforged.module_shop.api.ApiErrorCode;
import cn.autoforged.module_shop.api.ApiResult;
import cn.autoforged.module_shop.api.ModuleShopApi;
import cn.autoforged.module_shop.api.view.BalanceView;
import cn.autoforged.module_shop_redpacket.data.RedPacket;
import cn.autoforged.module_shop_redpacket.data.RedPacketSavedData;
import cn.autoforged.module_shop_redpacket.data.RedPacketSplitter;
import cn.autoforged.module_shop_redpacket.network.RedPacketNetwork;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 红包的全部服务端逻辑。所有入口都在**服务端主线程**调用（命令 / tick / 网络包的 enqueueWork）。
 *
 * <p>钱的流向只走模块商店公共 API：发红包 {@code economy().take}（余额不足整笔失败），
 * 领红包 / 过期退款 {@code economy().add}（超上限会自动转金钱邮件，不会丢钱）。
 */
public final class RedPacketManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("module_shop_redpacket");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ID_CHARS = "abcdefghjkmnpqrstuvwxyz23456789";
    public static final int MAX_PASSWORD = 16;
    public static final int MAX_GREETING = 24;
    private static final int RED = 0xE53935;
    private static final int GOLD = 0xFFC107;

    /** 发红包冷却：玩家 → 上次发出时刻。 */
    private static final Map<UUID, Long> LAST_SEND = new HashMap<>();
    /** 聊天口令命中后排队到下一 tick 领取：保证「口令那句聊天」先出现，再出现领取结果。 */
    private static final Deque<Runnable> NEXT_TICK = new ArrayDeque<>();

    private RedPacketManager() {
    }

    // ================================================================ 发红包

    /** 发红包；成功返回 {@code null}，失败返回给玩家看的原因。 */
    public static Component send(ServerPlayer player, int count, long total, boolean lucky,
            String rawPassword, String rawGreeting) {
        MinecraftServer server = player.server;
        RedPacketSavedData data = RedPacketSavedData.get(server);
        int maxCount = RedPacketConfig.MAX_COUNT.get();
        long minTotal = RedPacketConfig.MIN_TOTAL.get();
        long maxTotal = RedPacketConfig.MAX_TOTAL.get();
        String currency = currency(player.getUUID());
        if (count < 1 || count > maxCount) {
            return Component.translatable("msg.module_shop_redpacket.bad_count", maxCount);
        }
        if (total < minTotal || total > maxTotal) {
            return Component.translatable("msg.module_shop_redpacket.bad_total", minTotal, maxTotal, currency);
        }
        if (total < count) {
            return Component.translatable("msg.module_shop_redpacket.total_lt_count", currency);
        }
        String password = clean(rawPassword, MAX_PASSWORD + 1);
        if (password.length() > MAX_PASSWORD) {
            return Component.translatable("msg.module_shop_redpacket.password_too_long", MAX_PASSWORD);
        }
        if (password.startsWith("/")) {
            return Component.translatable("msg.module_shop_redpacket.password_slash");
        }
        if (!password.isEmpty() && findActiveByPassword(data, password) != null) {
            return Component.translatable("msg.module_shop_redpacket.password_in_use");
        }
        String greeting = clean(rawGreeting, MAX_GREETING);
        long now = System.currentTimeMillis();
        int cooldown = RedPacketConfig.SEND_COOLDOWN_SECONDS.get();
        Long last = LAST_SEND.get(player.getUUID());
        if (cooldown > 0 && last != null && now - last < cooldown * 1000L) {
            long wait = (cooldown * 1000L - (now - last) + 999L) / 1000L;
            return Component.translatable("msg.module_shop_redpacket.cooldown", wait);
        }

        List<Long> amounts = lucky ? RedPacketSplitter.lucky(total, count, RANDOM) : RedPacketSplitter.even(total, count);
        String id = newId(data);
        ApiResult<Long> taken = ModuleShopApi.economy().take(player.getUUID(), total, "红包 · 发出 #" + id);
        if (!taken.isOk()) {
            if (taken.code() == ApiErrorCode.INSUFFICIENT_FUNDS) {
                return Component.translatable("msg.module_shop_redpacket.insufficient", total, currency,
                        ModuleShopApi.economy().balance(player.getUUID()));
            }
            LOGGER.warn("take failed for {}: {} {}", player.getGameProfile().getName(), taken.code(), taken.message());
            return Component.translatable("msg.module_shop_redpacket.take_failed", taken.code().name());
        }
        long expiresAt = now + RedPacketConfig.EXPIRE_MINUTES.get() * 60_000L;
        RedPacket packet = new RedPacket(id, player.getUUID(), player.getGameProfile().getName(), total, count, lucky,
                password, greeting, now, expiresAt, amounts);
        data.put(packet);
        LAST_SEND.put(player.getUUID(), now);
        broadcast(server, announcement(packet, currency));
        return null;
    }

    /** 聊天栏公告：「🧧 xxx 发了一个拼手气红包 「祝福语」 共 N 个 · M 星币  [领取红包] [详情]」 */
    private static Component announcement(RedPacket packet, String currency) {
        MutableComponent line = Component.empty();
        line.append(Component.translatable("msg.module_shop_redpacket.tag").withStyle(s -> s.withColor(RED).withBold(true)));
        line.append(" ");
        line.append(Component.translatable(packet.lucky
                        ? "msg.module_shop_redpacket.sent_lucky" : "msg.module_shop_redpacket.sent_even",
                Component.literal(packet.senderName).withStyle(ChatFormatting.YELLOW)));
        line.append(" ");
        line.append(Component.literal("「").append(greetingOf(packet)).append("」")
                .withStyle(s -> s.withColor(GOLD)));
        line.append("\n  ");
        line.append(Component.translatable("msg.module_shop_redpacket.summary", packet.count, packet.total, currency)
                .withStyle(ChatFormatting.GRAY));
        if (packet.hasPassword()) {
            line.append("  ");
            line.append(Component.translatable("msg.module_shop_redpacket.password_line",
                    Component.literal(packet.password).withStyle(ChatFormatting.AQUA)).withStyle(ChatFormatting.GRAY));
        }
        line.append("\n  ");
        line.append(claimButton(packet));
        line.append("  ");
        line.append(detailButton(packet.id));
        return line;
    }

    private static Component claimButton(RedPacket packet) {
        if (packet.hasPassword()) {
            // 口令红包：点一下把口令填进聊天框，回车发送即领取。
            return Component.translatable("msg.module_shop_redpacket.btn_password").withStyle(Style.EMPTY
                    .withColor(RED).withBold(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, packet.password))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            Component.translatable("msg.module_shop_redpacket.btn_password_hover", packet.password))));
        }
        return Component.translatable("msg.module_shop_redpacket.btn_claim").withStyle(Style.EMPTY
                .withColor(RED).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/redpacket claim " + packet.id))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("msg.module_shop_redpacket.btn_claim_hover"))));
    }

    private static Component detailButton(String id) {
        return Component.translatable("msg.module_shop_redpacket.btn_detail").withStyle(Style.EMPTY
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/redpacket info " + id))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("msg.module_shop_redpacket.btn_detail_hover"))));
    }

    // ================================================================ 领红包

    /** 点击领取（无口令红包）或口令命中后领取。结果只发给领取人自己。 */
    public static void claim(ServerPlayer player, String id, boolean viaPassword) {
        RedPacketSavedData data = RedPacketSavedData.get(player.server);
        RedPacket packet = data.get(id == null ? null : id.toLowerCase(Locale.ROOT));
        if (packet == null) {
            tell(player, Component.translatable("msg.module_shop_redpacket.not_found").withStyle(ChatFormatting.GRAY));
            return;
        }
        String currency = currency(packet.sender);
        RedPacket.Claim mine = packet.claimOf(player.getUUID());
        if (mine != null) {
            tell(player, Component.translatable("msg.module_shop_redpacket.already", mine.amount(), currency)
                    .withStyle(ChatFormatting.GRAY).append("  ").append(detailButton(packet.id)));
            return;
        }
        if (packet.refunded > 0L || (!packet.finished && System.currentTimeMillis() >= packet.expiresAt)) {
            tell(player, Component.translatable("msg.module_shop_redpacket.expired")
                    .withStyle(ChatFormatting.GRAY).append("  ").append(detailButton(packet.id)));
            return;
        }
        if (packet.isEmpty()) {
            tell(player, Component.translatable("msg.module_shop_redpacket.empty")
                    .withStyle(ChatFormatting.GRAY).append("  ").append(detailButton(packet.id)));
            return;
        }
        if (packet.sender.equals(player.getUUID()) && !RedPacketConfig.ALLOW_SELF_CLAIM.get()) {
            tell(player, Component.translatable("msg.module_shop_redpacket.no_self").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (packet.hasPassword() && !viaPassword) {
            tell(player, Component.translatable("msg.module_shop_redpacket.need_password",
                    Component.literal(packet.password).withStyle(ChatFormatting.AQUA))
                    .withStyle(ChatFormatting.GRAY).append("  ").append(claimButton(packet)));
            return;
        }
        long amount = packet.pending.remove(0);
        ApiResult<?> added = payout(player.getUUID(), amount,
                "红包 · 领取 " + packet.senderName + " #" + packet.id,
                "红包：" + packet.senderName + " 的红包（余额已满，转为邮件）");
        if (!added.isOk()) {
            packet.pending.add(0, amount);   // 没入账：份额放回去，别人还能领
            LOGGER.warn("add failed for {}: {} {}", player.getGameProfile().getName(), added.code(), added.message());
            tell(player, Component.translatable("msg.module_shop_redpacket.claim_failed", added.code().name())
                    .withStyle(ChatFormatting.RED));
            return;
        }
        long now = System.currentTimeMillis();
        packet.claims.add(new RedPacket.Claim(player.getUUID(), player.getGameProfile().getName(), amount, now));
        if (packet.isEmpty()) {
            packet.finished = true;
            packet.finishedAt = now;
        }
        data.setDirty();
        // 自己可见：领到多少。
        tell(player, Component.empty()
                .append(Component.translatable("msg.module_shop_redpacket.tag").withStyle(s -> s.withColor(RED).withBold(true)))
                .append(" ")
                .append(Component.translatable("msg.module_shop_redpacket.claimed",
                        Component.literal(packet.senderName).withStyle(ChatFormatting.YELLOW),
                        Component.literal(Long.toString(amount)).withStyle(s -> s.withColor(GOLD).withBold(true)),
                        currency))
                .append("  ")
                .append(detailButton(packet.id)));
        if (packet.finished && RedPacketConfig.ANNOUNCE_FINISHED.get()) {
            broadcast(player.server, finishedAnnouncement(packet, currency));
        }
    }

    private static Component finishedAnnouncement(RedPacket packet, String currency) {
        long seconds = Math.max(1L, (packet.finishedAt - packet.createdAt) / 1000L);
        MutableComponent line = Component.empty()
                .append(Component.translatable("msg.module_shop_redpacket.tag").withStyle(s -> s.withColor(RED).withBold(true)))
                .append(" ")
                .append(Component.translatable("msg.module_shop_redpacket.finished",
                        Component.literal(packet.senderName).withStyle(ChatFormatting.YELLOW),
                        packet.count, duration(seconds)).withStyle(ChatFormatting.GRAY));
        RedPacket.Claim best = packet.best();
        if (packet.lucky && best != null && packet.count > 1) {
            line.append(" ").append(Component.translatable("msg.module_shop_redpacket.best",
                    Component.literal(best.name()).withStyle(ChatFormatting.YELLOW),
                    best.amount(), currency).withStyle(ChatFormatting.GRAY));
        }
        line.append("  ").append(detailButton(packet.id));
        return line;
    }

    private static Component duration(long seconds) {
        if (seconds < 60L) {
            return Component.translatable("msg.module_shop_redpacket.seconds", seconds);
        }
        if (seconds < 3600L) {
            return Component.translatable("msg.module_shop_redpacket.minutes", seconds / 60L, seconds % 60L);
        }
        return Component.translatable("msg.module_shop_redpacket.hours", seconds / 3600L, (seconds % 3600L) / 60L);
    }

    /** 聊天消息命中某个有效口令 → 排到下一 tick 领取（同一句话只领一个）。返回是否命中。 */
    public static boolean onChat(ServerPlayer player, String message) {
        String text = clean(message, MAX_PASSWORD + 1);
        if (text.isEmpty() || text.length() > MAX_PASSWORD) {
            return false;
        }
        RedPacket packet = findActiveByPassword(RedPacketSavedData.get(player.server), text);
        if (packet == null) {
            return false;
        }
        String id = packet.id;
        UUID uuid = player.getUUID();
        NEXT_TICK.add(() -> {
            ServerPlayer online = player.server.getPlayerList().getPlayer(uuid);
            if (online != null) {
                claim(online, id, true);
            }
        });
        return true;
    }

    private static RedPacket findActiveByPassword(RedPacketSavedData data, String password) {
        for (RedPacket packet : data.all()) {
            if (!packet.finished && packet.hasPassword() && packet.password.equals(password)
                    && System.currentTimeMillis() < packet.expiresAt) {
                return packet;
            }
        }
        return null;
    }

    // ================================================================ 详情

    /** 把红包详情发给玩家（客户端弹出详情界面）。 */
    public static void sendDetail(ServerPlayer player, String id) {
        RedPacket packet = RedPacketSavedData.get(player.server).get(id == null ? null : id.toLowerCase(Locale.ROOT));
        if (packet == null) {
            tell(player, Component.translatable("msg.module_shop_redpacket.not_found").withStyle(ChatFormatting.GRAY));
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "detail");
        tag.putString("id", packet.id);
        tag.putString("sender", packet.senderName);
        tag.putString("greeting", packet.greeting);
        tag.putLong("total", packet.total);
        tag.putInt("count", packet.count);
        tag.putBoolean("lucky", packet.lucky);
        tag.putBoolean("hasPassword", packet.hasPassword());
        tag.putString("password", packet.password);
        tag.putString("currency", currency(packet.sender));
        tag.putLong("createdAt", packet.createdAt);
        tag.putLong("expiresAt", packet.expiresAt);
        tag.putLong("finishedAt", packet.finishedAt);
        tag.putBoolean("finished", packet.finished);
        tag.putLong("refunded", packet.refunded);
        tag.putLong("now", System.currentTimeMillis());
        RedPacket.Claim best = packet.lucky && packet.finished && packet.refunded == 0L && packet.count > 1
                ? packet.best() : null;
        ListTag claims = new ListTag();
        for (RedPacket.Claim claim : packet.claims) {
            CompoundTag c = new CompoundTag();
            c.putString("name", claim.name());
            c.putLong("amount", claim.amount());
            c.putLong("time", claim.time());
            c.putBoolean("me", claim.player().equals(player.getUUID()));
            c.putBoolean("best", claim == best);
            claims.add(c);
        }
        tag.put("claims", claims);
        RedPacketNetwork.sendToPlayer(player, tag);
    }

    /** 发红包界面打开时要的数据：余额、货币名、限制。 */
    public static void sendStatus(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "status");
        tag.putLong("balance", ModuleShopApi.economy().balance(player.getUUID()));
        tag.putString("currency", currency(player.getUUID()));
        tag.putInt("maxCount", RedPacketConfig.MAX_COUNT.get());
        tag.putLong("minTotal", RedPacketConfig.MIN_TOTAL.get());
        tag.putLong("maxTotal", RedPacketConfig.MAX_TOTAL.get());
        tag.putInt("expireMinutes", RedPacketConfig.EXPIRE_MINUTES.get());
        RedPacketNetwork.sendToPlayer(player, tag);
    }

    // ================================================================ tick：过期退款 / 清理

    private static int tickCounter;

    public static void tick(MinecraftServer server) {
        while (!NEXT_TICK.isEmpty()) {
            try {
                NEXT_TICK.poll().run();
            } catch (Throwable error) {
                LOGGER.error("red packet task failed", error);
            }
        }
        if (++tickCounter < 100) {   // 每 5 秒检查一次过期
            return;
        }
        tickCounter = 0;
        RedPacketSavedData data = RedPacketSavedData.get(server);
        long now = System.currentTimeMillis();
        for (RedPacket packet : data.all()) {
            if (packet.finished || now < packet.expiresAt) {
                continue;
            }
            long left = 0L;
            for (Long amount : packet.pending) {
                left += amount;
            }
            if (left > 0L) {
                ApiResult<?> back = payout(packet.sender, left, "红包 · 过期退回 #" + packet.id,
                        "红包过期退回（余额已满，转为邮件）");
                if (!back.isOk()) {
                    LOGGER.warn("refund failed for packet {}: {} {}", packet.id, back.code(), back.message());
                    continue;   // 下次再试，钱不丢
                }
            }
            packet.pending.clear();
            packet.refunded = left;
            packet.finished = true;
            data.setDirty();
            ServerPlayer owner = server.getPlayerList().getPlayer(packet.sender);
            if (owner != null && left > 0L) {
                tell(owner, Component.translatable("msg.module_shop_redpacket.refunded",
                        packet.claims.size(), packet.count, left, currency(packet.sender))
                        .withStyle(ChatFormatting.GRAY).append("  ").append(detailButton(packet.id)));
            }
        }
        data.prune(now, RedPacketConfig.KEEP_HISTORY_HOURS.get() * 3_600_000L);
    }

    public static void reset() {
        LAST_SEND.clear();
        NEXT_TICK.clear();
        tickCounter = 0;
    }

    // ================================================================ 工具

    /**
     * 给玩家入账（只走公共 API）：优先 {@code economy().add}；余额已到上限（{@code LIMIT_EXCEEDED}）时
     * 改发 {@code mail().sendMoneyMail}，玩家去邮箱领取 —— 钱不会卡在红包里。
     */
    private static ApiResult<?> payout(UUID target, long amount, String reason, String mailTitle) {
        ApiResult<Long> added = ModuleShopApi.economy().add(target, amount, reason);
        if (added.isOk() || added.code() != ApiErrorCode.LIMIT_EXCEEDED) {
            return added;
        }
        return ModuleShopApi.mail().sendMoneyMail(target, amount, mailTitle, reason);
    }

    private static Component greetingOf(RedPacket packet) {
        return packet.greeting.isEmpty()
                ? Component.translatable("msg.module_shop_redpacket.default_greeting")
                : Component.literal(packet.greeting);
    }

    static String currency(UUID player) {
        BalanceView view = ModuleShopApi.economy().balanceView(player);
        return view == null || view.currencyName() == null || view.currencyName().isBlank()
                ? "星币" : view.currencyName();
    }

    private static String newId(RedPacketSavedData data) {
        while (true) {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                sb.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
            }
            String id = sb.toString();
            if (!data.contains(id)) {
                return id;
            }
        }
    }

    /** 去掉 § 格式符与控制字符、首尾空白；最多保留 max 个字符。 */
    public static String clean(String raw, int max) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < max; i++) {
            char c = raw.charAt(i);
            if (c == '§') {
                i++;
                continue;
            }
            if (c >= 0x20 && c != 0x7f) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static void tell(ServerPlayer player, Component message) {
        player.sendSystemMessage(message);
    }

    private static void broadcast(MinecraftServer server, Component message) {
        server.getPlayerList().broadcastSystemMessage(message, false);
    }
}
