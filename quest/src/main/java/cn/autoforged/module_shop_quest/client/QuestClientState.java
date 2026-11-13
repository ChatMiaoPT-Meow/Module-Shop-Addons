package cn.autoforged.module_shop_quest.client;

import cn.autoforged.module_shop_quest.config.ConfigModels.OnlineRewardsConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestPoolsConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.SeasonPassConfig;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 客户端快照缓存与 OP 可视化配置工作区副本。
 */
public final class QuestClientState {
    public record RewardView(long money, long points, List<ItemStack> items) {
        public static final RewardView EMPTY = new RewardView(0L, 0L, List.of());

        public boolean hasAny() {
            return money > 0L || points > 0L || !items.isEmpty();
        }
    }

    public record PassRow(
            int level,
            long reqPoints,
            boolean highlight,
            boolean freeClaimed,
            boolean premClaimed,
            RewardView freeReward,
            RewardView premReward
    ) {
    }

    public record QuestRow(
            String id,
            String period,
            String title,
            String desc,
            String category,
            String requiredMod,
            int progress,
            int target,
            boolean invalidated,
            boolean claimed,
            RewardView reward
    ) {
        public boolean completed() {
            return !invalidated && progress >= Math.max(1, target);
        }

        public boolean claimable() {
            return completed() && !claimed;
        }
    }

    public record OnlineRow(
            String id,
            String period,
            String title,
            int currentMin,
            int reqMin,
            boolean claimed,
            RewardView reward
    ) {
        public boolean completed() {
            return currentMin >= Math.max(1, reqMin);
        }

        public boolean claimable() {
            return completed() && !claimed;
        }
    }

    private static volatile boolean received = false;
    private static long serverNowSec = 0L;
    private static long clientRecvSec = 0L;
    private static String zoneId = "Asia/Shanghai";
    private static long nextDailyResetSec = 0L;
    private static long nextWeeklyResetSec = 0L;
    private static boolean isOp = false;
    private static long walletBalance = 0L;

    private static String seasonId = "";
    private static String seasonName = "";
    private static long seasonEndSec = 0L;
    private static long seasonPoints = 0L;
    private static int currentLevel = 0;
    private static int maxLevel = 30;
    private static long levelPrevReq = 0L;
    private static long levelNextReq = 100L;

    private static String freeTrackName = "";
    private static boolean premiumEnabled = true;
    private static String premiumTrackName = "";
    private static long premiumUnlockCost = 2880L;
    private static long premiumBonusPoints = 200L;
    private static boolean premiumUnlocked = false;

    private static boolean deluxeEnabled = true;
    private static String deluxeTrackName = "典藏通行证";
    private static long deluxeUnlockCost = 4880L;
    private static long deluxeOrigCost = 5880L;
    private static int deluxeBonusLevels = 10;
    private static boolean deluxeUnlocked = false;
    private static RewardView deluxeReward = RewardView.EMPTY;

    private static boolean allowBuyLevels = true;
    private static long buyLevelCost = 150L;

    private static int rolloverPercent = 30;
    private static long rolloverCap = 600L;
    private static int prevSeasonUnclaimed = 0;
    private static long prevSeasonGraceUntilSec = 0L;

    private static int dailyMinutes = 0;
    private static int weeklyMinutes = 0;
    private static boolean afkPaused = false;
    private static boolean showAfkHint = true;
    private static int totalClaimable = 0;

    private static List<PassRow> passRows = List.of();
    private static List<QuestRow> dailyRows = List.of();
    private static List<QuestRow> weeklyRows = List.of();
    private static List<OnlineRow> onlineRows = List.of();

    // OP 游戏内可视化编辑器工作副本
    private static volatile boolean adminLoaded = false;
    private static int adminRevision = 0;
    private static SeasonPassConfig editPass;
    private static QuestPoolsConfig editPools;
    private static OnlineRewardsConfig editOnline;

    private static String bannerMessage = "";
    private static boolean bannerOk = true;
    private static long bannerExpireMs = 0L;
    private static int snapshotRevision = 0;

    private QuestClientState() {
    }

    public static synchronized void reset() {
        received = false;
        adminLoaded = false;
        editPass = null;
        editPools = null;
        editOnline = null;
        passRows = List.of();
        dailyRows = List.of();
        weeklyRows = List.of();
        onlineRows = List.of();
        deluxeReward = RewardView.EMPTY;
        totalClaimable = 0;
        bannerMessage = "";
        bannerExpireMs = 0L;
    }

    public static synchronized void applyAdminConfig(String passJson, String poolsJson, String onlineJson) {
        editPass = QuestDataConfig.parseSeasonPassJson(passJson);
        editPools = QuestDataConfig.parseQuestPoolsJson(poolsJson);
        editOnline = QuestDataConfig.parseOnlineRewardsJson(onlineJson);
        adminLoaded = true;
        adminRevision++;
    }

    public static boolean adminLoaded() { return adminLoaded; }
    public static int adminRevision() { return adminRevision; }
    public static SeasonPassConfig editPass() { return editPass; }
    public static QuestPoolsConfig editPools() { return editPools; }
    public static OnlineRewardsConfig editOnline() { return editOnline; }

    public static synchronized void applySnapshot(CompoundTag root) {
        if (root == null || root.isEmpty()) {
            return;
        }
        HolderLookup.Provider regs = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.registryAccess()
                : null;

        received = true;
        snapshotRevision++;
        serverNowSec = root.getLong("serverNowSec");
        clientRecvSec = System.currentTimeMillis() / 1000L;
        zoneId = root.getString("zoneId");
        nextDailyResetSec = root.getLong("nextDailyResetSec");
        nextWeeklyResetSec = root.getLong("nextWeeklyResetSec");
        isOp = root.getBoolean("isOp");
        walletBalance = root.getLong("walletBalance");

        seasonId = root.getString("seasonId");
        seasonName = root.getString("seasonName");
        seasonEndSec = root.getLong("seasonEndSec");
        seasonPoints = root.getLong("seasonPoints");
        currentLevel = root.getInt("currentLevel");
        maxLevel = Math.max(1, root.getInt("maxLevel"));
        levelPrevReq = root.getLong("levelPrevReq");
        levelNextReq = root.getLong("levelNextReq");

        freeTrackName = root.getString("freeTrackName");
        premiumEnabled = root.getBoolean("premiumEnabled");
        premiumTrackName = root.getString("premiumTrackName");
        premiumUnlockCost = root.getLong("premiumUnlockCost");
        premiumBonusPoints = root.getLong("premiumBonusPoints");
        premiumUnlocked = root.getBoolean("premiumUnlocked");

        deluxeEnabled = !root.contains("deluxeEnabled") || root.getBoolean("deluxeEnabled");
        String dName = root.getString("deluxeTrackName");
        deluxeTrackName = dName.isBlank() ? "典藏通行证" : dName;
        deluxeUnlockCost = root.contains("deluxeUnlockCost") ? root.getLong("deluxeUnlockCost") : 4880L;
        deluxeOrigCost = root.contains("deluxeOrigCost") ? root.getLong("deluxeOrigCost") : 5880L;
        deluxeBonusLevels = root.contains("deluxeBonusLevels") ? root.getInt("deluxeBonusLevels") : 10;
        deluxeUnlocked = root.getBoolean("deluxeUnlocked");
        deluxeReward = decodeReward(root.getCompound("deluxeReward"), regs);

        allowBuyLevels = !root.contains("allowBuyLevels") || root.getBoolean("allowBuyLevels");
        buyLevelCost = root.contains("buyLevelCost") ? Math.max(1L, root.getLong("buyLevelCost")) : 150L;

        rolloverPercent = root.getInt("rolloverPercent");
        rolloverCap = root.getLong("rolloverCap");
        prevSeasonUnclaimed = root.getInt("prevSeasonUnclaimed");
        prevSeasonGraceUntilSec = root.getLong("prevSeasonGraceUntilSec");

        dailyMinutes = root.getInt("dailyMinutes");
        weeklyMinutes = root.getInt("weeklyMinutes");
        afkPaused = root.getBoolean("afkPaused");
        showAfkHint = root.getBoolean("showAfkHint");
        totalClaimable = root.getInt("totalClaimable");

        ListTag passTag = root.getList("passLevels", Tag.TAG_COMPOUND);
        List<PassRow> pList = new ArrayList<>(passTag.size());
        for (int i = 0; i < passTag.size(); i++) {
            CompoundTag t = passTag.getCompound(i);
            pList.add(new PassRow(
                    t.getInt("level"),
                    t.getLong("reqPoints"),
                    t.getBoolean("highlight"),
                    t.getBoolean("freeClaimed"),
                    t.getBoolean("premClaimed"),
                    decodeReward(t.getCompound("freeReward"), regs),
                    decodeReward(t.getCompound("premReward"), regs)
            ));
        }
        passRows = Collections.unmodifiableList(pList);

        dailyRows = decodeQuestList(root.getList("dailyQuests", Tag.TAG_COMPOUND), regs);
        weeklyRows = decodeQuestList(root.getList("weeklyQuests", Tag.TAG_COMPOUND), regs);

        ListTag onlineTag = root.getList("onlineMilestones", Tag.TAG_COMPOUND);
        List<OnlineRow> oList = new ArrayList<>(onlineTag.size());
        for (int i = 0; i < onlineTag.size(); i++) {
            CompoundTag t = onlineTag.getCompound(i);
            oList.add(new OnlineRow(
                    t.getString("id"),
                    t.getString("period"),
                    t.getString("title"),
                    t.getInt("currentMin"),
                    t.getInt("reqMin"),
                    t.getBoolean("claimed"),
                    decodeReward(t.getCompound("reward"), regs)
            ));
        }
        onlineRows = Collections.unmodifiableList(oList);
    }

    public static synchronized void applyOnlineDelta(CompoundTag delta) {
        if (!received || delta == null) {
            return;
        }
        dailyMinutes = delta.getInt("dailyMinutes");
        weeklyMinutes = delta.getInt("weeklyMinutes");
        afkPaused = delta.getBoolean("afkPaused");
        totalClaimable = delta.getInt("claimableCount");

        List<OnlineRow> updated = new ArrayList<>(onlineRows.size());
        for (OnlineRow r : onlineRows) {
            int cur = "weekly".equals(r.period())
                    ? Math.min(weeklyMinutes, r.reqMin())
                    : ("daily".equals(r.period()) ? Math.min(dailyMinutes, r.reqMin()) : r.currentMin());
            updated.add(new OnlineRow(r.id(), r.period(), r.title(), cur, r.reqMin(), r.claimed(), r.reward()));
        }
        onlineRows = Collections.unmodifiableList(updated);
    }

    public static synchronized void postBanner(boolean ok, String text) {
        bannerOk = ok;
        bannerMessage = text == null ? "" : text;
        bannerExpireMs = System.currentTimeMillis() + 3600L;
    }

    private static List<QuestRow> decodeQuestList(ListTag tag, HolderLookup.Provider regs) {
        List<QuestRow> list = new ArrayList<>(tag.size());
        for (int i = 0; i < tag.size(); i++) {
            CompoundTag t = tag.getCompound(i);
            list.add(new QuestRow(
                    t.getString("id"),
                    t.getString("period"),
                    t.getString("title"),
                    t.getString("desc"),
                    t.getString("category"),
                    t.getString("requiredMod"),
                    t.getInt("progress"),
                    t.getInt("target"),
                    t.getBoolean("invalidated"),
                    t.getBoolean("claimed"),
                    decodeReward(t.getCompound("reward"), regs)
            ));
        }
        return Collections.unmodifiableList(list);
    }

    private static RewardView decodeReward(CompoundTag t, HolderLookup.Provider regs) {
        if (t == null || t.isEmpty()) {
            return RewardView.EMPTY;
        }
        long money = t.getLong("money");
        long points = t.getLong("points");
        ListTag itemsTag = t.getList("items", Tag.TAG_COMPOUND);
        List<ItemStack> stacks = new ArrayList<>(itemsTag.size());
        if (regs != null) {
            for (int i = 0; i < itemsTag.size(); i++) {
                ItemStack parsed = ItemStack.parseOptional(regs, itemsTag.getCompound(i));
                if (!parsed.isEmpty()) {
                    stacks.add(parsed);
                }
            }
        }
        return new RewardView(money, points, Collections.unmodifiableList(stacks));
    }

    public static boolean received() { return received; }
    public static int snapshotRevision() { return snapshotRevision; }
    public static String zoneId() { return zoneId; }
    public static boolean isOp() { return isOp; }
    public static long walletBalance() { return walletBalance; }
    public static String seasonId() { return seasonId; }
    public static String seasonName() { return seasonName; }
    public static long seasonPoints() { return seasonPoints; }
    public static int currentLevel() { return currentLevel; }
    public static int maxLevel() { return maxLevel; }
    public static long levelPrevReq() { return levelPrevReq; }
    public static long levelNextReq() { return levelNextReq; }
    public static String freeTrackName() { return freeTrackName; }
    public static boolean premiumEnabled() { return premiumEnabled; }
    public static String premiumTrackName() { return premiumTrackName; }
    public static long premiumUnlockCost() { return premiumUnlockCost; }
    public static long premiumBonusPoints() { return premiumBonusPoints; }
    public static boolean premiumUnlocked() { return premiumUnlocked; }

    public static boolean deluxeEnabled() { return deluxeEnabled; }
    public static String deluxeTrackName() { return deluxeTrackName; }
    public static long deluxeUnlockCost() { return deluxeUnlockCost; }
    public static long deluxeOrigCost() { return deluxeOrigCost; }
    public static int deluxeBonusLevels() { return deluxeBonusLevels; }
    public static boolean deluxeUnlocked() { return deluxeUnlocked; }
    public static RewardView deluxeReward() { return deluxeReward; }
    public static long deluxeEffectiveCost() {
        return premiumUnlocked ? Math.max(0L, deluxeUnlockCost - premiumUnlockCost) : deluxeUnlockCost;
    }

    public static boolean allowBuyLevels() { return allowBuyLevels; }
    public static long buyLevelCost() { return buyLevelCost; }

    public static int rolloverPercent() { return rolloverPercent; }
    public static long rolloverCap() { return rolloverCap; }
    public static int prevSeasonUnclaimed() { return prevSeasonUnclaimed; }
    public static int dailyMinutes() { return dailyMinutes; }
    public static int weeklyMinutes() { return weeklyMinutes; }
    public static boolean afkPaused() { return afkPaused; }
    public static boolean showAfkHint() { return showAfkHint; }
    public static int totalClaimable() { return totalClaimable; }

    public static List<PassRow> passRows() { return passRows; }
    public static List<QuestRow> dailyRows() { return dailyRows; }
    public static List<QuestRow> weeklyRows() { return weeklyRows; }
    public static List<OnlineRow> onlineRows() { return onlineRows; }

    public static String activeBanner() {
        return System.currentTimeMillis() < bannerExpireMs ? bannerMessage : "";
    }

    public static boolean bannerOk() { return bannerOk; }

    public static long estimatedServerNowSec() {
        if (serverNowSec <= 0L) {
            return System.currentTimeMillis() / 1000L;
        }
        long elapsed = Math.max(0L, (System.currentTimeMillis() / 1000L) - clientRecvSec);
        return serverNowSec + elapsed;
    }

    public static long secondsUntilDailyReset() {
        return Math.max(0L, nextDailyResetSec - estimatedServerNowSec());
    }

    public static long secondsUntilWeeklyReset() {
        return Math.max(0L, nextWeeklyResetSec - estimatedServerNowSec());
    }

    public static long secondsUntilSeasonEnd() {
        return Math.max(0L, seasonEndSec - estimatedServerNowSec());
    }

    public static int countPassClaimable() {
        int c = prevSeasonUnclaimed;
        for (PassRow r : passRows) {
            if (seasonPoints < r.reqPoints()) {
                break;
            }
            if (!r.freeClaimed() && (r.freeReward().money() > 0L || !r.freeReward().items().isEmpty())) {
                c++;
            }
            if (premiumEnabled && premiumUnlocked && !r.premClaimed()
                    && (r.premReward().money() > 0L || !r.premReward().items().isEmpty())) {
                c++;
            }
        }
        return c;
    }

    public static int countQuestClaimable(List<QuestRow> rows) {
        int c = 0;
        for (QuestRow r : rows) {
            if (r.claimable()) {
                c++;
            }
        }
        return c;
    }

    public static int countOnlineClaimable() {
        int c = 0;
        for (OnlineRow r : onlineRows) {
            if (r.claimable()) {
                c++;
            }
        }
        return c;
    }

    public static String formatCountdown(long seconds) {
        if (seconds <= 0L) {
            return "00:00";
        }
        long days = seconds / 86400L;
        long hrs = (seconds % 86400L) / 3600L;
        long mins = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (days > 0L) {
            return days + "天 " + hrs + "小时";
        }
        if (hrs > 0L) {
            return String.format("%02d:%02d:%02d", hrs, mins, secs);
        }
        return String.format("%02d:%02d", mins, secs);
    }
}
