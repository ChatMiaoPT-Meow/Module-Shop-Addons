package com.atoms.Bshop.blind.server;

import com.atoms.Bshop.blind.auction.BlindCatalog;
import com.atoms.Bshop.blind.auction.BlindCatalog.Relic;
import com.atoms.Bshop.blind.auction.BlindCatalog.VaultTheme;
import com.atoms.Bshop.blind.auction.VaultZone;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** 一局盲仓：8×6 仓库里摆着若干占 1–4 格的藏品；各席位有自己的角色、道具次数、性格与情报视野。 */
final class BlindRoom {
    enum Phase { BIDDING, RESULT }

    /** 模拟 AI 的心理博弈性格（复刻暗标竞拍真人常见流派）。 */
    enum AiPersona {
        CONSERVATIVE("稳健派"),
        SNIPER("狙击手"),
        BLUFFER("做局客"),
        RAIDER("秒仓猎手");

        final String tag;

        AiPersona(String tag) {
            this.tag = tag;
        }
    }

    /** 摆进仓库的一件藏品。grade：0 正常 / 1 残损 / 2 完美（只有稀有及以上会出现）。 */
    record Placed(int id, Relic relic, int x, int y, long value, int grade) {
        String displayName() {
            return grade == 1 ? "残损·" + relic.name() : grade == 2 ? "完美·" + relic.name() : relic.name();
        }

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
        // AI：读仓偏差、性格流派与思考延迟
        double aiBias;
        double aiStyle;
        AiPersona aiPersona = AiPersona.CONSERVATIVE;
        boolean aiBluffFolded;
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
            while (logs.size() > 24) logs.remove(logs.size() - 1);
        }
    }

    final int id;
    final VaultZone zone;
    final boolean practice;
    final VaultTheme theme;
    final List<Placed> items;
    final int[] owner = new int[BlindCatalog.CELLS];
    /** 系统公开揭示给所有人的已知程度（同 Seat.know）。 */
    final int[] pub;
    final List<Seat> seats = new ArrayList<>();
    int comboSetId;
    int comboCount;
    long comboBonus;
    int round = 1;
    int seconds;
    int elapsed;
    long floor;
    long submitCounter;
    Phase phase = Phase.BIDDING;
    int resultTtl;
    CompoundTag result;

    BlindRoom(int id, VaultZone zone, boolean practice, VaultTheme theme, List<Placed> items) {
        this.id = id;
        this.zone = zone;
        this.practice = practice;
        this.theme = theme;
        this.items = items;
        this.pub = new int[items.size()];
        Arrays.fill(owner, -1);
        for (Placed p : items) {
            for (int c = 0; c < BlindCatalog.CELLS; c++) {
                if (p.covers(c)) owner[c] = p.id();
            }
        }
        computeSetSynergy();
    }

    private void computeSetSynergy() {
        int[] counts = new int[BlindCatalog.SET_NAMES.length];
        for (Placed p : items) {
            int sid = p.relic().setId();
            if (sid > 0 && sid < counts.length) {
                counts[sid]++;
            }
        }
        int bestSet = 0;
        int bestCnt = 0;
        for (int sid = 1; sid < counts.length; sid++) {
            if (counts[sid] >= BlindCatalog.SET_MIN_COUNT && counts[sid] > bestCnt) {
                bestCnt = counts[sid];
                bestSet = sid;
            }
        }
        this.comboSetId = bestSet;
        this.comboCount = bestCnt;
        this.comboBonus = bestSet > 0
                ? Math.round(rawItemsValue() * BlindCatalog.SET_BONUS_RATE / 10.0D) * 10L
                : 0L;
    }

    boolean hasAi() {
        for (Seat s : seats) {
            if (!s.human()) return true;
        }
        return false;
    }

    long rawItemsValue() {
        long sum = 0L;
        for (Placed p : items) sum += p.value();
        return sum;
    }

    long trueValue() {
        return rawItemsValue() + comboBonus;
    }

    /** 按给定视野推算的最低估值：已知价值按真实价值，已知品质按该品质最低价，其余按普通最低价。 */
    long floorFor(int[] know) {
        long sum = 0L;
        for (Placed p : items) {
            int lvl = p.id() < know.length ? know[p.id()] : 0;
            sum += BlindCatalog.floorOf(zone, lvl, p.relic().tier(), p.value());
        }
        return sum;
    }

    /** 按给定视野推算的期望价值（AI 用）：已知价值按真实价值，已知品质按该品质均价，其余按本区未知藏品均价。 */
    double expectedFor(int[] know) {
        double unknown = BlindCatalog.unknownMean(zone);
        double sum = 0;
        for (Placed p : items) {
            int lvl = p.id() < know.length ? know[p.id()] : 0;
            sum += lvl >= 3 ? p.value() : lvl == 2 ? BlindCatalog.tierMean(zone, p.relic().tier()) : unknown;
        }
        if (round >= 2 && comboSetId > 0) {
            sum *= 1.08D;
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
