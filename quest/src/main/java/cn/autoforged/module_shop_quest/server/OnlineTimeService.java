package cn.autoforged.module_shop_quest.server;

import cn.autoforged.module_shop_quest.config.QuestTomlConfig;
import cn.autoforged.module_shop_quest.data.QuestSavedData;
import cn.autoforged.module_shop_quest.data.QuestSavedData.PlayerQuestProfile;
import cn.autoforged.module_shop_quest.network.QuestNetwork;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * 服务端权威在线时长采样与防挂机（AFK）判定引擎（已加固防挂机池水流/载具被动位移及 {@code ui_ping} 续表上限）。
 */
public final class OnlineTimeService {
    /** 无任何视角转动或真实动作时，纯水平位移最长允许续表秒数（防止水流挂机池/矿车环线无限计时）。 */
    private static final long MAX_PASSIVE_MOVE_STREAK_SEC = 90L;
    /** 无任何世界动作/真实交易时，单纯依靠客户端 {@code ui_ping} 最长允许续表秒数（防止发包伪造心跳）。 */
    private static final long MAX_UI_PING_STREAK_SEC = 180L;

    private static final Map<UUID, SessionSample> SAMPLES = new ConcurrentHashMap<>();
    private static int tickAccumulator = 0;

    private static final class SessionSample {
        double lastX;
        double lastY;
        double lastZ;
        float lastYaw;
        float lastPitch;
        long lastActiveEpochSec;
        long lastRealInputSec;
        long lastUiTouchEpochSec;
        boolean afkPaused;

        SessionSample(ServerPlayer player, long nowSec) {
            this.lastX = player.getX();
            this.lastY = player.getY();
            this.lastZ = player.getZ();
            this.lastYaw = player.getYRot();
            this.lastPitch = player.getXRot();
            this.lastActiveEpochSec = nowSec;
            this.lastRealInputSec = nowSec;
            this.lastUiTouchEpochSec = nowSec;
            this.afkPaused = false;
        }
    }

    private OnlineTimeService() {
    }

    public static void resetRuntime() {
        SAMPLES.clear();
        tickAccumulator = 0;
    }

    public static void onPlayerLogin(ServerPlayer player) {
        long nowSec = Instant.now().getEpochSecond();
        SAMPLES.put(player.getUUID(), new SessionSample(player, nowSec));
    }

    public static void onPlayerLogout(ServerPlayer player) {
        SAMPLES.remove(player.getUUID());
    }

    /** 记录服务端已验证的真实世界操作或商店交易/领奖行为，重置真实输入时钟。 */
    public static void recordRealAction(UUID playerId) {
        if (playerId == null) {
            return;
        }
        SessionSample sample = SAMPLES.get(playerId);
        if (sample != null) {
            long nowSec = Instant.now().getEpochSecond();
            sample.lastRealInputSec = nowSec;
            sample.lastActiveEpochSec = nowSec;
            sample.afkPaused = false;
        }
    }

    /** 玩家在商店/纪行 UI 内发生滚动或点击心跳时调用（受 {@link #MAX_UI_PING_STREAK_SEC} 防伪造上限保护）。 */
    public static void touchUiActivity(ServerPlayer player, boolean verifiedAction) {
        long nowSec = Instant.now().getEpochSecond();
        SessionSample sample = SAMPLES.computeIfAbsent(player.getUUID(), k -> new SessionSample(player, nowSec));
        if (verifiedAction) {
            sample.lastRealInputSec = nowSec;
            sample.lastUiTouchEpochSec = nowSec;
            sample.lastActiveEpochSec = nowSec;
            sample.afkPaused = false;
            return;
        }
        if ((nowSec - sample.lastRealInputSec) <= MAX_UI_PING_STREAK_SEC) {
            sample.lastUiTouchEpochSec = nowSec;
            sample.lastActiveEpochSec = nowSec;
            sample.afkPaused = false;
        }
    }

    public static boolean isAfkPaused(UUID playerId) {
        SessionSample sample = SAMPLES.get(playerId);
        return sample != null && sample.afkPaused;
    }

    public static void flushAll(MinecraftServer server) {
        QuestSavedData.get(server).setDirty();
    }

    public static void onServerTick(MinecraftServer server) {
        tickAccumulator++;
        if (tickAccumulator < 20) {
            return;
        }
        tickAccumulator = 0;

        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        long nowSec = Instant.now().getEpochSecond();
        long nowMillis = Util.getMillis();
        int idleThresholdSec = QuestTomlConfig.AFK_IDLE_SECONDS.get();
        double minMoveSq = square(QuestTomlConfig.AFK_MIN_MOVE_BLOCKS.get());
        float minLookDeg = QuestTomlConfig.AFK_MIN_LOOK_DEGREES.get().floatValue();

        QuestSavedData data = QuestSavedData.get(server);
        boolean anyDirty = false;

        for (ServerPlayer player : players) {
            if (player.isSpectator() && !player.hasPermissions(2)) {
                continue;
            }
            QuestManager.ensurePlayerUpToDate(player, data);
            PlayerQuestProfile profile = data.profile(player.getUUID());
            SessionSample sample = SAMPLES.computeIfAbsent(player.getUUID(), k -> new SessionSample(player, nowSec));

            double dx = player.getX() - sample.lastX;
            double dz = player.getZ() - sample.lastZ;
            double horizDistSq = dx * dx + dz * dz;
            float dYaw = Math.abs(Mth.wrapDegrees(player.getYRot() - sample.lastYaw));
            float dPitch = Math.abs(player.getXRot() - sample.lastPitch);

            sample.lastX = player.getX();
            sample.lastY = player.getY();
            sample.lastZ = player.getZ();
            sample.lastYaw = player.getYRot();
            sample.lastPitch = player.getXRot();

            long sinceLastActionMs = Math.max(0L, nowMillis - player.getLastActionTime());
            boolean realLookOrAction = dYaw >= minLookDeg || dPitch >= minLookDeg || sinceLastActionMs <= 1500L;
            if (realLookOrAction) {
                sample.lastRealInputSec = nowSec;
                sample.lastActiveEpochSec = nowSec;
            } else if (horizDistSq >= minMoveSq) {
                boolean passiveMedium = player.isInWaterOrBubble() || player.isPassenger();
                long sinceReal = nowSec - sample.lastRealInputSec;
                if (!passiveMedium && sinceReal <= MAX_PASSIVE_MOVE_STREAK_SEC) {
                    sample.lastActiveEpochSec = nowSec;
                } else if (passiveMedium && sinceReal <= 15L) {
                    sample.lastActiveEpochSec = nowSec;
                }
            } else if ((nowSec - sample.lastUiTouchEpochSec) <= idleThresholdSec
                    && (nowSec - sample.lastRealInputSec) <= MAX_UI_PING_STREAK_SEC) {
                sample.lastActiveEpochSec = nowSec;
            }

            boolean wasPaused = sample.afkPaused;
            boolean activeNow = (nowSec - sample.lastActiveEpochSec) < idleThresholdSec;
            sample.afkPaused = !activeNow;

            if (activeNow) {
                int prevDailyMin = profile.dailyOnlineSeconds / 60;
                int prevWeeklyMin = profile.weeklyOnlineSeconds / 60;
                profile.dailyOnlineSeconds++;
                profile.weeklyOnlineSeconds++;
                anyDirty = true;

                int newDailyMin = profile.dailyOnlineSeconds / 60;
                int newWeeklyMin = profile.weeklyOnlineSeconds / 60;
                if (newDailyMin != prevDailyMin || newWeeklyMin != prevWeeklyMin || wasPaused) {
                    sendOnlineDelta(player, profile, false);
                }
            } else if (!wasPaused) {
                sendOnlineDelta(player, profile, true);
            }
        }

        if (anyDirty) {
            data.setDirty();
        }
    }

    private static void sendOnlineDelta(ServerPlayer player, PlayerQuestProfile profile, boolean afkPaused) {
        CompoundTag delta = new CompoundTag();
        delta.putString("type", "online_delta");
        delta.putInt("dailyMinutes", profile.dailyOnlineSeconds / 60);
        delta.putInt("weeklyMinutes", profile.weeklyOnlineSeconds / 60);
        delta.putBoolean("afkPaused", afkPaused);
        delta.putInt("claimableCount", QuestManager.countTotalClaimable(player, QuestSavedData.get(player.server), profile));
        QuestNetwork.sendToPlayer(player, delta);
    }

    private static double square(double v) {
        return v * v;
    }
}
