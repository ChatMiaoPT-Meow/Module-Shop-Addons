package com.atoms.Bshop.blind.client.ui;

import com.atoms.Bshop.blind.auction.BlindCatalog.Tool;
import com.atoms.Bshop.blind.client.BlindAuctionState;
import com.atoms.Bshop.blind.client.BlindAuctionState.Phase;
import com.atoms.Bshop.blind.client.BlindAuctionState.Result;
import com.atoms.Bshop.blind.client.BlindAuctionState.SeatResult;
import com.atoms.Bshop.blind.client.BlindAuctionState.SeatView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * 遗迹盲仓原生 UI 交互音效（纯短促单次反馈，无任何背景音乐或循环环境音）。
 * 音量对齐 Module Shop 主界面 ClickSound（0.24F ~ 0.50F），按物理直觉与操作语义映射原版音效。
 */
public final class BlindSound {
    private static long lastClickMs;
    private static boolean initialized;
    private static Phase lastPhase = Phase.LOADOUT;
    private static int lastRound = 1;
    private static int lastSeconds = -1;

    private BlindSound() {}

    private static void play(SoundEvent event, float pitch, float volume) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getSoundManager() == null) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
    }

    private static boolean debounce(long minGapMs) {
        long now = System.currentTimeMillis();
        if (now - lastClickMs < minGapMs) return false;
        lastClickMs = now;
        return true;
    }

    /** 常规选项切换（选仓区、选鉴定师、切换模式/筛选、打开出价框）：与主模组一致的清脆木质轻触声。 */
    public static void selectOption() {
        if (debounce(25L)) play(SoundEvents.WOODEN_BUTTON_CLICK_ON, 1.25F, 0.38F);
    }

    /** 返回 / 关闭弹窗 / 取消匹配：低音复位轻触声。 */
    public static void clickBack() {
        if (debounce(25L)) play(SoundEvents.WOODEN_BUTTON_CLICK_OFF, 0.96F, 0.36F);
    }

    /** 打开/关闭成交记录：账本翻页声。 */
    public static void pageTurn() {
        if (debounce(35L)) play(SoundEvents.BOOK_PAGE_TURN, 1.08F, 0.42F);
    }

    /** 开始匹配 / 开始 AI 演练：1.21 铜灯清脆通电声。 */
    public static void startAction() {
        if (debounce(40L)) play(SoundEvents.COPPER_BULB_TURN_ON, 1.15F, 0.45F);
    }

    /** 数字键输入与快捷加价（+10% / 估值 / 上轮）：极轻短促滴答声，连按不吵。 */
    public static void keyTap() {
        if (debounce(18L)) play(SoundEvents.NOTE_BLOCK_HAT.value(), 1.68F, 0.24F);
    }

    /** 退格 / 清零：轻柔退位声。 */
    public static void keyClear() {
        if (debounce(20L)) play(SoundEvents.WOODEN_BUTTON_CLICK_OFF, 1.12F, 0.28F);
    }

    /** 拿起选格类道具进入瞄准态：望远镜举起声。 */
    public static void armTool() {
        if (debounce(30L)) play(SoundEvents.SPYGLASS_USE, 1.15F, 0.40F);
    }

    /** 取消道具瞄准态：望远镜收回声。 */
    public static void cancelTool() {
        if (debounce(30L)) play(SoundEvents.SPYGLASS_STOP_USING, 1.05F, 0.34F);
    }

    /** 释放 5 种探查道具：按物理材质给出短促干净的专属反馈。 */
    public static void useTool(int toolId) {
        if (!debounce(35L)) return;
        Tool tool = Tool.byId(toolId);
        switch (tool) {
            case HAMMER -> play(SoundEvents.STONE_BUTTON_CLICK_ON, 0.92F, 0.45F);
            case LENS -> play(SoundEvents.SPYGLASS_USE, 1.35F, 0.45F);
            case SCANNER -> play(SoundEvents.COPPER_BULB_TURN_ON, 1.38F, 0.42F);
            case GLOWSTICK -> play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.22F, 0.44F);
            case SONAR -> play(SoundEvents.COPPER_BULB_TURN_ON, 1.52F, 0.42F);
        }
    }

    /** 锁定本轮报价：机械拉杆落锁声，体现锁定不可撤回的规则感。 */
    public static void lockBid() {
        if (debounce(40L)) play(SoundEvents.LEVER_CLICK, 1.20F, 0.45F);
    }

    /** 放弃本轮（报 0 观望）：低沉放手声。 */
    public static void passRound() {
        if (debounce(40L)) play(SoundEvents.WOODEN_BUTTON_CLICK_OFF, 0.85F, 0.35F);
    }

    public static void resetTracking() {
        initialized = false;
        lastPhase = Phase.LOADOUT;
        lastRound = 1;
        lastSeconds = -1;
    }

    /**
     * 客户端每 Tick 轮询服务端状态跃迁（对局开启、进入新回合、未出价时最后 5 秒轻滴答、开仓揭晓）。
     * {@code uiVisible} 为 false 时不播读秒或回合音，绝不在玩家关闭界面逛世界时产生莫名声响。
     */
    public static void pollStateTransitions(boolean uiVisible) {
        if (!BlindAuctionState.received()) {
            initialized = false;
            return;
        }
        Phase curPhase = BlindAuctionState.phase();
        int curRound = BlindAuctionState.round();
        int curSeconds = BlindAuctionState.seconds();

        if (!initialized) {
            initialized = true;
            lastPhase = curPhase;
            lastRound = curRound;
            lastSeconds = curSeconds;
            return;
        }

        // 1. 匹配成功 / 进入第 1 轮开局：试炼宝库激活声
        if (lastPhase != Phase.MATCH && curPhase == Phase.MATCH) {
            play(SoundEvents.VAULT_ACTIVATE, 1.05F, 0.48F);
            lastSeconds = curSeconds;
        } else if (lastPhase == Phase.MATCH && curPhase == Phase.MATCH) {
            // 2. 推进至新一轮公开揭示（仅界面打开时提示）
            if (curRound > lastRound) {
                if (uiVisible) {
                    play(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.15F, 0.38F);
                }
                lastSeconds = curSeconds;
            } else if (uiVisible && !BlindAuctionState.submitted() && curSeconds != lastSeconds
                    && curSeconds >= 1 && curSeconds <= 5) {
                // 3. 尚未提交报价且剩余 ≤5 秒：轻微秒表提示，已出价则保持安静不打扰
                float p = curSeconds <= 2 ? 1.98F : 1.82F;
                play(SoundEvents.NOTE_BLOCK_HAT.value(), p, 0.25F);
            }
        }

        // 4. 进入开仓结算揭晓阶段（仅界面打开时播放开仓声与盈亏短音）
        if (lastPhase != Phase.RESULT && curPhase == Phase.RESULT && uiVisible) {
            Result res = BlindAuctionState.result();
            if (res == null || res.flop() || res.defaulted()) {
                play(SoundEvents.VAULT_OPEN_SHUTTER, 0.90F, 0.45F);
                play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.80F, 0.32F);
            } else {
                String me = null;
                for (SeatView s : BlindAuctionState.seats()) {
                    if (s.me()) me = s.name();
                }
                long myDiv = 0L;
                if (me != null) {
                    for (SeatResult sr : res.seats()) {
                        if (me.equals(sr.name())) myDiv = sr.dividend();
                    }
                }
                boolean iWon = me != null && me.equals(res.winner()) && res.human();
                if ((iWon && res.profit() >= 0L) || (!iWon && myDiv > 0L)) {
                    play(SoundEvents.VAULT_OPEN_SHUTTER, 1.08F, 0.50F);
                    play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.18F, 0.38F);
                } else if (iWon && res.profit() < 0L) {
                    play(SoundEvents.VAULT_OPEN_SHUTTER, 0.92F, 0.46F);
                    play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.78F, 0.34F);
                } else {
                    play(SoundEvents.VAULT_OPEN_SHUTTER, 1.00F, 0.45F);
                }
            }
        }

        lastPhase = curPhase;
        lastRound = curRound;
        lastSeconds = curSeconds;
    }
}
