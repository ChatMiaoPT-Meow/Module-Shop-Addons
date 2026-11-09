package cn.autoforged.module_shop_redpacket.data;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** 红包存档（主世界 data/module_shop_redpacket.dat）：重启后未领完的红包照样能领 / 到期照样退款。 */
public final class RedPacketSavedData extends SavedData {
    private static final String NAME = "module_shop_redpacket";

    private final Map<String, RedPacket> packets = new LinkedHashMap<>();

    public static RedPacketSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RedPacketSavedData::new, RedPacketSavedData::load, null), NAME);
    }

    private static RedPacketSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        RedPacketSavedData data = new RedPacketSavedData();
        ListTag list = tag.getList("packets", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            RedPacket packet = RedPacket.load(list.getCompound(i));
            data.packets.put(packet.id, packet);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (RedPacket packet : this.packets.values()) {
            list.add(packet.save());
        }
        tag.put("packets", list);
        return tag;
    }

    public RedPacket get(String id) {
        return id == null ? null : this.packets.get(id);
    }

    public boolean contains(String id) {
        return this.packets.containsKey(id);
    }

    public void put(RedPacket packet) {
        this.packets.put(packet.id, packet);
        setDirty();
    }

    public Collection<RedPacket> all() {
        return this.packets.values();
    }

    /** 删掉结束超过 keepMillis 的红包（详情只保留一段时间）。 */
    public void prune(long now, long keepMillis) {
        boolean removed = this.packets.values().removeIf(p -> p.finished
                && now - Math.max(p.finishedAt, p.expiresAt) > keepMillis);
        if (removed) {
            setDirty();
        }
    }
}
