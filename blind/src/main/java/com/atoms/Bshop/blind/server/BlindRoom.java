package com.atoms.Bshop.blind.server;

import com.atoms.Bshop.blind.auction.BlindCatalog;
import com.atoms.Bshop.blind.auction.BlindCatalog.Relic;
import com.atoms.Bshop.blind.auction.VaultZone;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** 一局盲仓：8×6 仓库里摆着若干占 1–4 格的藏品；4 个席位各有自己的角色、道具次数与情报视野。 */
final class BlindRoom {
    enum Phase { BIDDING, RESULT }

    /** 摆进仓库的一件藏品。 */
    record Placed(int id, Relic relic, int x, int y, long value) {
        boolean covers(int cell) {
            int cx = cell % BlindCatalog.COLS;
            int cy = cell / BlindCatalog.COLS;
            return cx >= x && cx < x + relic.w() && cy >= y && cy < y + relic.h();
        }

        int cells() { return relic.w() * relic.h(); }
    }

    static final class Seat {
        final UUID uuid;          // null = 模拟 AI
        final String name;
        final int role;
        final long feePaid;
        /** 每件藏品的已知程度：0 未知 / 1 轮廓 / 2 轮廓+品质 / 3 全部信息。 */
        int[] know = new int[0];
        final boolean[] knownEmpty = new boolean[BlindCatalog.CELLS];
        final boolean[] toolUsed = new boolean[BlindCatalog.Tool.values().length];
        boolean toolThisRound;
        final List<CompoundTag> logs = new ArrayList<>();
        /** 各轮报价，-1 = 该轮还没结束。 */
        final long[] bids = {-1, -1, -1, -1, -1};
        long bid;
        boolean submitted;
        long submitOrder;
        // AI：读仓水平（对公开信息推算的偏差）与出价风格
        double aiBias;
        double aiStyle;
        int aiDelay;

        Seat(UUID uuid, String name, int role, long feePaid) {
            this.uuid = uuid;
            this.name = name;
            this.role = role;
            this.feePaid = feePaid;
        }

        boolean human() { return uuid != null; }

        void learn(int item, int level) {
            if (item >= 0 && item < know.length && know[item] < level) know[item] = level;
        }

        void log(int round, int type, String badge, String message) {
            CompoundTag t = new CompoundTag();
            t.putInt("r", round);
            t.putInt("t", type);
            t.putString("b", badge);
            t.putString("m", message);
            logs.add(0, t);
            while (logs.size() > 16) logs.remove(logs.size() - 1);
        }
    }

    final int id;
    final VaultZone zone;
    final List<Placed> items;
    final int[] owner = new int[BlindCatalog.CELLS];
    /** 系统公开揭示给所有人的已知程度（同 Seat.know）。 */
    final int[] pub;
    final List<Seat> seats = new ArrayList<>();
    int round = 1;
    int seconds;
    int elapsed;
    long floor;
    long submitCounter;
    Phase phase = Phase.BIDDING;
    int resultTtl;
    CompoundTag result;

    BlindRoom(int id, VaultZone zone, List<Placed> items) {
        this.id = id;
        this.zone = zone;
        this.items = items;
        this.pub = new int[items.size()];
        Arrays.fill(owner, -1);
        for (Placed p : items) {
            for (int c = 0; c < BlindCatalog.CELLS; c++) {
                if (p.covers(c)) owner[c] = p.id();
            }
        }
    }

    long trueValue() {
        long sum = 0L;
        for (Placed p : items) sum += p.value();
        return sum;
    }

    /** 按给定视野推算的最低估值：已知价值按真实价值，已知品质按该品质最低价，其余按普通最低价。 */
    long floorFor(int[] know) {
        long sum = 0L;
        for (Placed p : items) {
            int lvl = p.id() < know.length ? know[p.id()] : 0;
            sum += BlindCatalog.floorOf(lvl, p.relic().tier(), p.value());
        }
        return sum;
    }

    /** 按给定视野推算的期望价值（AI 用）：已知价值按真实价值，已知品质按该品质均价，其余按本区未知藏品均价。 */
    double expectedFor(int[] know) {
        double unknown = BlindCatalog.unknownMean(zone);
        double sum = 0;
        for (Placed p : items) {
            int lvl = p.id() < know.length ? know[p.id()] : 0;
            sum += lvl >= 3 ? p.value() : lvl == 2 ? BlindCatalog.tierMean(p.relic().tier()) : unknown;
        }
        return sum;
    }

    int occupiedCells() {
        int n = 0;
        for (int o : owner) if (o >= 0) n++;
        return n;
    }

    Seat seatOf(UUID uuid) {
        for (Seat s : seats) {
            if (uuid.equals(s.uuid)) return s;
        }
        return null;
    }
}
