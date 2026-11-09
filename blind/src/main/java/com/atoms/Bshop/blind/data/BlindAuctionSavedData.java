package com.atoms.Bshop.blind.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 盲仓存档：每位玩家的备战配置、最近成交记录、以及「已收门票但对局尚未结束」的台账。
 * 进行中的对局只在内存里；服务器异常退出后，台账里的门票会在下次开服时原路退回。
 */
public class BlindAuctionSavedData extends SavedData {
    private static final String DATA_NAME = "module_shop_blind_v2";
    public static final int HISTORY_LIMIT = 10;

    public static final class Loadout {
        public int zone = 2;
        public int role = 0;
        public boolean single = false;
    }

    private final Map<UUID, Loadout> loadouts = new LinkedHashMap<>();
    private final List<CompoundTag> history = new ArrayList<>();
    private final Map<UUID, Long> feeLedger = new LinkedHashMap<>();

    public static BlindAuctionSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(BlindAuctionSavedData::new, BlindAuctionSavedData::load, null), DATA_NAME);
    }

    public Loadout loadout(UUID player) {
        return loadouts.computeIfAbsent(player, k -> new Loadout());
    }

    public List<CompoundTag> history() { return history; }

    public void pushHistory(CompoundTag result) {
        history.add(0, result.copy());
        while (history.size() > HISTORY_LIMIT) history.remove(history.size() - 1);
        setDirty();
    }

    public Map<UUID, Long> feeLedger() { return feeLedger; }

    public void ledgerAdd(UUID player, long amount) {
        if (amount <= 0L) return;
        feeLedger.merge(player, amount, Long::sum);
        setDirty();
    }

    public void ledgerRemove(UUID player, long amount) {
        if (amount <= 0L) return;
        Long left = feeLedger.computeIfPresent(player, (k, v) -> v - amount);
        if (left != null && left <= 0L) feeLedger.remove(player);
        setDirty();
    }

    public static BlindAuctionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        BlindAuctionSavedData data = new BlindAuctionSavedData();
        ListTag ls = tag.getList("loadouts", Tag.TAG_COMPOUND);
        for (int i = 0; i < ls.size(); i++) {
            CompoundTag e = ls.getCompound(i);
            if (!e.hasUUID("p")) continue;
            Loadout l = new Loadout();
            l.zone = Math.floorMod(e.getInt("zone"), 3);
            l.role = Math.max(0, e.getInt("role"));
            l.single = e.getBoolean("single");
            data.loadouts.put(e.getUUID("p"), l);
        }
        ListTag hs = tag.getList("history", Tag.TAG_COMPOUND);
        for (int i = 0; i < hs.size() && i < HISTORY_LIMIT; i++) data.history.add(hs.getCompound(i));
        ListTag fs = tag.getList("feeLedger", Tag.TAG_COMPOUND);
        for (int i = 0; i < fs.size(); i++) {
            CompoundTag e = fs.getCompound(i);
            if (e.hasUUID("p") && e.getLong("a") > 0L) data.feeLedger.put(e.getUUID("p"), e.getLong("a"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag ls = new ListTag();
        for (Map.Entry<UUID, Loadout> en : loadouts.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("p", en.getKey());
            Loadout l = en.getValue();
            e.putInt("zone", l.zone);
            e.putInt("role", l.role);
            e.putBoolean("single", l.single);
            ls.add(e);
        }
        tag.put("loadouts", ls);
        ListTag hs = new ListTag();
        for (CompoundTag h : history) hs.add(h.copy());
        tag.put("history", hs);
        ListTag fs = new ListTag();
        for (Map.Entry<UUID, Long> en : feeLedger.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("p", en.getKey());
            e.putLong("a", en.getValue());
            fs.add(e);
        }
        tag.put("feeLedger", fs);
        return tag;
    }
}
