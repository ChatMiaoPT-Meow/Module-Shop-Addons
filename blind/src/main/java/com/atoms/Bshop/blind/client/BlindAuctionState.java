package com.atoms.Bshop.blind.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 客户端缓存：服务端为本玩家下发的快照（阶段、备战、匹配、本人视野下的对局、成交记录）。 */
public final class BlindAuctionState {
    public enum Phase { LOADOUT, QUEUE, MATCH, RESULT }

    /** 已知的一件藏品：lvl 1 轮廓 / 2 品质 / 3 全部信息。 */
    public record Item(int x, int y, int w, int h, int lvl, int tier, String name, String itemId, long value) {
        public boolean covers(int cx, int cy) {
            return cx >= x && cx < x + w && cy >= y && cy < y + h;
        }
    }

    public record SeatView(String name, boolean human, boolean me, boolean submitted, long[] bids) {}

    public record Log(int round, int type, String badge, String message) {}

    public record SeatResult(String name, boolean human, boolean winner, long[] bids, long dividend) {}

    public record Result(String zone, boolean practice, int theme, int comboSet, long comboBonus,
                         boolean flop, String winner, boolean human, long bid, long truth, long tax,
                         long profit, boolean instant, boolean defaulted, int rounds, String topItem, long topValue,
                         long at, List<SeatResult> seats) {
        static Result of(CompoundTag t) {
            List<SeatResult> seats = new ArrayList<>();
            ListTag l = t.getList("seats", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag s = l.getCompound(i);
                seats.add(new SeatResult(s.getString("n"), s.getBoolean("h"), s.getBoolean("w"), s.getLongArray("bids"),
                        s.getLong("div")));
            }
            return new Result(t.getString("zone"), t.getBoolean("practice"), t.getInt("theme"),
                    t.getInt("comboSet"), t.getLong("comboBonus"), t.getBoolean("flop"),
                    t.getString("winner"), t.getBoolean("human"), t.getLong("bid"), t.getLong("truth"),
                    t.getLong("tax"), t.getLong("profit"), t.getBoolean("instant"), t.getBoolean("defaulted"),
                    t.getInt("rounds"), t.getString("topItem"), t.getLong("topValue"), t.getLong("at"), seats);
        }
    }

    private static boolean received;
    private static Phase phase = Phase.LOADOUT;
    private static long balance;
    private static double tax = 0.05D;
    private static double dividend = 0.3D;

    private static int zone = 2;
    private static int role;
    private static boolean single;

    private static int queueCount;
    private static int queueSeats = 4;
    private static int queueWait;

    private static boolean matchPractice;
    private static long practiceCap = 120_000L;
    private static int vaultTheme;
    private static int comboSet;
    private static long comboBonus;
    private static int round = 1;
    private static int seconds;
    private static long floor;
    private static long myFloor;
    private static int itemCount;
    private static int cellCount;
    private static int matchRole;
    private static long myBid;
    private static boolean submitted;
    private static boolean toolThisRound;
    private static int toolsUsed;
    private static final List<Item> ITEMS = new ArrayList<>();
    private static final boolean[] EMPTY = new boolean[64];
    private static final List<SeatView> SEATS = new ArrayList<>();
    private static final List<Log> LOGS = new ArrayList<>();
    private static Result result;
    private static final List<Result> HISTORY = new ArrayList<>();

    // 纯客户端
    private static boolean keypadOpen;
    private static long keypad;
    /** 键盘里是预填的默认值：第一次按数字时先清空再输入。 */
    private static boolean keypadFresh;
    private static int armedTool = -1;
    private static String toast = "";
    private static long toastExpireAt;
    private static boolean historyOpen;

    private BlindAuctionState() {}

    public static void apply(CompoundTag t) {
        received = true;
        Phase next;
        try {
            next = Phase.valueOf(t.getString("phase"));
        } catch (IllegalArgumentException e) {
            next = Phase.LOADOUT;
        }
        if (next != Phase.MATCH) {
            keypadOpen = false;
            armedTool = -1;
        }
        phase = next;
        balance = t.getLong("balance");
        tax = t.getDouble("tax");
        dividend = t.getDouble("dividend");

        CompoundTag lo = t.getCompound("loadout");
        zone = lo.getInt("zone");
        role = lo.getInt("role");
        single = lo.getBoolean("single");

        HISTORY.clear();
        ListTag hist = t.getList("history", Tag.TAG_COMPOUND);
        for (int i = 0; i < hist.size(); i++) HISTORY.add(Result.of(hist.getCompound(i)));

        if (t.contains("queue")) {
            CompoundTag q = t.getCompound("queue");
            zone = q.getInt("zone");
            queueCount = q.getInt("count");
            queueSeats = q.getInt("seats");
            queueWait = q.getInt("wait");
        }

        result = null;
        if (t.contains("match")) {
            CompoundTag m = t.getCompound("match");
            int nextRound = m.getInt("round");
            if (nextRound != round) {
                keypadOpen = false;
                armedTool = -1;
            }
            zone = m.contains("zone") ? m.getInt("zone") : zone;
            matchPractice = m.getBoolean("practice");
            practiceCap = Math.max(1L, m.getLong("practiceCap"));
            vaultTheme = m.getInt("theme");
            comboSet = m.getInt("comboSet");
            comboBonus = m.getLong("comboBonus");
            round = nextRound;
            seconds = m.getInt("seconds");
            floor = m.getLong("floor");
            myFloor = Math.max(floor, m.getLong("myFloor"));
            itemCount = m.getInt("itemCount");
            cellCount = m.getInt("cellCount");
            matchRole = m.getInt("role");
            myBid = m.getLong("myBid");
            submitted = m.getBoolean("submitted");
            toolThisRound = m.getBoolean("toolThisRound");
            toolsUsed = m.getInt("toolsUsed");
            if (submitted || toolThisRound) armedTool = -1;
            ITEMS.clear();
            ListTag items = m.getList("items", Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++) {
                CompoundTag c = items.getCompound(i);
                ITEMS.add(new Item(c.getInt("x"), c.getInt("y"), c.getInt("w"), c.getInt("h"), c.getInt("lvl"),
                        c.contains("tier") ? c.getInt("tier") : -1, c.getString("name"), c.getString("item"),
                        c.getLong("value")));
            }
            java.util.Arrays.fill(EMPTY, false);
            for (int c : m.getIntArray("empty")) {
                if (c >= 0 && c < EMPTY.length) EMPTY[c] = true;
            }
            SEATS.clear();
            ListTag seats = m.getList("seats", Tag.TAG_COMPOUND);
            for (int i = 0; i < seats.size(); i++) {
                CompoundTag s = seats.getCompound(i);
                SEATS.add(new SeatView(s.getString("n"), s.getBoolean("h"), s.getBoolean("me"), s.getBoolean("s"),
                        s.getLongArray("bids")));
            }
            LOGS.clear();
            ListTag logs = m.getList("logs", Tag.TAG_COMPOUND);
            for (int i = 0; i < logs.size(); i++) {
                CompoundTag l = logs.getCompound(i);
                LOGS.add(new Log(l.getInt("r"), l.getInt("t"), l.getString("b"), l.getString("m")));
            }
            if (m.contains("result")) result = Result.of(m.getCompound("result"));
        }
    }

    public static void reset() {
        received = false;
        phase = Phase.LOADOUT;
        keypadOpen = false;
        armedTool = -1;
        historyOpen = false;
        ITEMS.clear();
        SEATS.clear();
        LOGS.clear();
        HISTORY.clear();
        result = null;
    }

    public static void pushToast(String message) {
        toast = message;
        toastExpireAt = System.currentTimeMillis() + 4000L;
    }

    public static String activeToast() {
        return System.currentTimeMillis() > toastExpireAt ? "" : toast;
    }

    public static boolean received() { return received; }
    public static Phase phase() { return phase; }
    public static long balance() { return balance; }
    public static double tax() { return tax; }
    public static double dividend() { return dividend; }
    public static int zone() { return zone; }
    public static int role() { return role; }
    public static boolean single() { return single; }
    public static int queueCount() { return queueCount; }
    public static int queueSeats() { return queueSeats; }
    public static int queueWait() { return queueWait; }
    public static boolean matchPractice() { return matchPractice; }
    public static long practiceCap() { return practiceCap; }
    public static long effectiveBidCap() { return matchPractice ? practiceCap : balance; }
    public static int vaultTheme() { return vaultTheme; }
    public static int comboSet() { return comboSet; }
    public static long comboBonus() { return comboBonus; }
    public static int round() { return round; }
    public static int seconds() { return seconds; }
    public static long floor() { return floor; }
    /** 按本人视野（公开 + 角色 + 道具）推算的最低估值，只有自己知道。 */
    public static long myFloor() { return myFloor; }
    public static int itemCount() { return itemCount; }
    public static int cellCount() { return cellCount; }
    public static int matchRole() { return matchRole; }
    public static long myBid() { return myBid; }
    public static boolean submitted() { return submitted; }
    public static boolean toolThisRound() { return toolThisRound; }
    public static boolean toolUsed(int tool) { return (toolsUsed & (1 << tool)) != 0; }
    public static List<Item> items() { return ITEMS; }
    public static boolean knownEmpty(int cell) { return cell >= 0 && cell < EMPTY.length && EMPTY[cell]; }
    public static List<SeatView> seats() { return SEATS; }
    public static List<Log> logs() { return LOGS; }
    public static Result result() { return result; }
    public static List<Result> history() { return HISTORY; }

    public static boolean historyOpen() { return historyOpen; }
    public static void setHistoryOpen(boolean open) { historyOpen = open; }

    public static int armedTool() { return armedTool; }
    public static void setArmedTool(int tool) { armedTool = tool; }

    public static boolean isKeypadOpen() { return keypadOpen; }

    /** 打开报价键盘，默认填上一轮自己的报价（第 1 轮或上一轮报 0 时为 0）。 */
    public static void openKeypad() {
        long last = 0L;
        for (SeatView s : SEATS) {
            if (s.me() && s.bids().length > 0) last = s.bids()[s.bids().length - 1];
        }
        keypad = Math.max(0L, last);
        keypadFresh = keypad > 0;
        keypadOpen = true;
    }

    public static void setKeypadOpen(boolean open) { keypadOpen = open; }

    public static long keypadValue() { return keypad; }

    public static void setKeypadValue(long v) {
        keypadFresh = false;
        keypad = Math.max(0L, Math.min(9_999_999_999L, v));
    }

    /** 按数字键：数值左移后补位，天然没有前导 0。 */
    public static void appendDigits(String digits) {
        long v = keypadFresh ? 0L : keypad;
        keypadFresh = false;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(i) - '0';
            if (d < 0 || d > 9) continue;
            if (v > 999_999_999L) return;
            v = v * 10L + d;
        }
        setKeypadValue(v);
    }

    public static void backspace() {
        keypadFresh = false;
        keypad /= 10L;
    }
}
