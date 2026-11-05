package cn.autoforged.module_shop_redpacket.data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;

/**
 * 一个红包。
 *
 * <p>金额在**发出时**就拆好（{@link #pending}，按领取顺序依次弹出），所以：
 * <ul>
 *     <li>总额永远精确：所有份额之和 == {@link #total}；</li>
 *     <li>领取只是「弹出下一份」，没有并发 / 重算误差；</li>
 *     <li>重启服务器后接着领，结果不变。</li>
 * </ul>
 */
public final class RedPacket {
    /** 一条领取记录。 */
    public record Claim(UUID player, String name, long amount, long time) {
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("player", this.player);
            tag.putString("name", this.name);
            tag.putLong("amount", this.amount);
            tag.putLong("time", this.time);
            return tag;
        }

        static Claim load(CompoundTag tag) {
            return new Claim(tag.getUUID("player"), tag.getString("name"), tag.getLong("amount"), tag.getLong("time"));
        }
    }

    public final String id;
    public final UUID sender;
    public final String senderName;
    public final long total;
    public final int count;
    /** true = 拼手气，false = 均分。 */
    public final boolean lucky;
    /** 口令（空 = 无口令，点击即领）。 */
    public final String password;
    public final String greeting;
    public final long createdAt;
    public final long expiresAt;
    /** 还没被领走的份额（按领取顺序）。 */
    public final List<Long> pending;
    public final List<Claim> claims;
    /** 过期退回给发送者的金额（0 = 没退过 / 已领完）。 */
    public long refunded;
    /** 是否已结束（领完 / 过期退款）。 */
    public boolean finished;
    /** 领完的时刻（0 = 还没领完）。 */
    public long finishedAt;

    public RedPacket(String id, UUID sender, String senderName, long total, int count, boolean lucky,
            String password, String greeting, long createdAt, long expiresAt, List<Long> pending) {
        this.id = id;
        this.sender = sender;
        this.senderName = senderName;
        this.total = total;
        this.count = count;
        this.lucky = lucky;
        this.password = password == null ? "" : password;
        this.greeting = greeting == null ? "" : greeting;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.pending = new ArrayList<>(pending);
        this.claims = new ArrayList<>();
    }

    public boolean hasPassword() {
        return !this.password.isEmpty();
    }

    public boolean isEmpty() {
        return this.pending.isEmpty();
    }

    /** 某玩家领到的记录；没领过返回 {@code null}。 */
    public Claim claimOf(UUID player) {
        for (Claim claim : this.claims) {
            if (claim.player().equals(player)) {
                return claim;
            }
        }
        return null;
    }

    public long claimedTotal() {
        long sum = 0L;
        for (Claim claim : this.claims) {
            sum += claim.amount();
        }
        return sum;
    }

    /** 手气最佳（只有拼手气且已领完才有意义；并列取先领的）。 */
    public Claim best() {
        Claim best = null;
        for (Claim claim : this.claims) {
            if (best == null || claim.amount() > best.amount()) {
                best = claim;
            }
        }
        return best;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", this.id);
        tag.putUUID("sender", this.sender);
        tag.putString("senderName", this.senderName);
        tag.putLong("total", this.total);
        tag.putInt("count", this.count);
        tag.putBoolean("lucky", this.lucky);
        tag.putString("password", this.password);
        tag.putString("greeting", this.greeting);
        tag.putLong("createdAt", this.createdAt);
        tag.putLong("expiresAt", this.expiresAt);
        ListTag pendingTag = new ListTag();
        for (Long amount : this.pending) {
            pendingTag.add(LongTag.valueOf(amount));
        }
        tag.put("pending", pendingTag);
        ListTag claimTag = new ListTag();
        for (Claim claim : this.claims) {
            claimTag.add(claim.save());
        }
        tag.put("claims", claimTag);
        tag.putLong("refunded", this.refunded);
        tag.putBoolean("finished", this.finished);
        tag.putLong("finishedAt", this.finishedAt);
        return tag;
    }

    public static RedPacket load(CompoundTag tag) {
        List<Long> pending = new ArrayList<>();
        ListTag pendingTag = tag.getList("pending", Tag.TAG_LONG);
        for (int i = 0; i < pendingTag.size(); i++) {
            pending.add(((LongTag) pendingTag.get(i)).getAsLong());
        }
        RedPacket packet = new RedPacket(tag.getString("id"), tag.getUUID("sender"), tag.getString("senderName"),
                tag.getLong("total"), tag.getInt("count"), tag.getBoolean("lucky"), tag.getString("password"),
                tag.getString("greeting"), tag.getLong("createdAt"), tag.getLong("expiresAt"), pending);
        ListTag claimTag = tag.getList("claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < claimTag.size(); i++) {
            packet.claims.add(Claim.load(claimTag.getCompound(i)));
        }
        packet.refunded = tag.getLong("refunded");
        packet.finished = tag.getBoolean("finished");
        packet.finishedAt = tag.getLong("finishedAt");
        return packet;
    }
}
