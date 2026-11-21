package cn.autoforged.module_shop_quest.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop_quest.client.QuestClient;
import cn.autoforged.module_shop_quest.client.QuestClientState;
import cn.autoforged.module_shop_quest.config.ConfigModels.ItemRewardSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.OnlineMilestoneSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.OnlineRewardsConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.PassLevelSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.PointsRuleSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.PremiumTrackSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestDefSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestPoolsConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.QuestTaskType;
import cn.autoforged.module_shop_quest.config.ConfigModels.RewardBundleSpec;
import cn.autoforged.module_shop_quest.config.ConfigModels.SeasonPassConfig;
import cn.autoforged.module_shop_quest.config.ConfigModels.TaskTarget;
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * 通行证与任务 OP 可视化配置工作台（v3）：
 * <ul>
 *     <li>每次 {@code ShopScreen.init()} 均重建并重新登记全部 {@link SkiaTextField}，彻底根治输入框失焦/打不了字/按 1~9 误切商城页；</li>
 *     <li>全标签页独立垂直滚动容器 + Scissor 裁剪，小窗口/高缩放与超长奖励列表零截断；</li>
 *     <li>任务多子目标列表编辑器（增删目标行、独立数量框与目标 ID 框）+ 右侧双模式类 JEI 检索面板（物品/方块/生物实体/群系/结构，30,000+ 物品零卡顿）。</li>
 * </ul>
 */
public final class QuestAdminView {
    private static final int SEARCH_IDX = 9;
    private static final int MAX_TARGET_ROWS = 10;
    private static final int TARGET_ID_BASE = 10;
    private static final int TARGET_CNT_BASE = 20;
    private static final int FIELD_POOL_SIZE = 30;

    private static final QuestTaskType[] SHOP_ACTIONS = {
            QuestTaskType.SHOP_SIGN_IN, QuestTaskType.SHOP_BUY_ANY, QuestTaskType.SHOP_BUY_DAILY,
            QuestTaskType.SHOP_BUY_MALL, QuestTaskType.SHOP_SELL_BUYBACK, QuestTaskType.SHOP_SHIPPING_SELL,
            QuestTaskType.STALL_BUY, QuestTaskType.STALL_LIST, QuestTaskType.AUCTION_BID,
            QuestTaskType.DEMAND_CREATE, QuestTaskType.PLAYER_TRADE
    };
    private static final QuestTaskType[] ADDON_ACTIONS = {
            QuestTaskType.STOCK_SPOT_TRADE, QuestTaskType.BLIND_PARTICIPATE, QuestTaskType.LOTTERY_PULL
    };
    private static final QuestTaskType[] VANILLA_ACTIONS = {
            QuestTaskType.VANILLA_BREAK_BLOCK, QuestTaskType.VANILLA_KILL_MOB,
            QuestTaskType.VANILLA_CRAFT_ITEM, QuestTaskType.VANILLA_FISH,
            QuestTaskType.SUBMIT_ITEM, QuestTaskType.VISIT_BIOME, QuestTaskType.VISIT_STRUCTURE
    };

    private static int subTab = 0; // 0=等级奖励, 1=通行证设置, 2=每日与每周任务, 3=在线时长奖励
    private static int selectedLevel = 1; // -1=普通默认, 0=每5级重点默认, 1..N=具体等级
    private static boolean pickTargetPremium = false;
    private static boolean editWeeklyPool = false;
    private static int selectedQuestIdx = 0;
    private static boolean editWeeklyOnline = false;
    private static int selectedOnlineIdx = 0;

    // 右侧选择器状态（支持奖励模式 / 任务目标选择模式双联动）
    private static boolean dockTargetMode = false; // 仅在 Tab 2 生效：false=添加奖励物品, true=选择任务目标
    private static int editingTargetRowIdx = -1; // >=0 替换该子目标行, -1 追加新子目标行
    private static boolean pickerCatalogMode = true; // true=全库搜索, false=我的背包
    private static boolean entityOnlyMobs = true; // 实体选择器：true=生物/怪物, false=全部实体
    private static boolean blockIncludeAllItems = false; // 方块选择器：false=仅方块, true=全部物品
    private static QuestTargetCatalog.Kind manualTargetKind = QuestTargetCatalog.Kind.ITEM;
    private static int pickerBatchCount = 1; // 1, 4, 16, 64
    private static String persistedSearchText = "";
    private static boolean dirty = false;

    private static final ScrollController LEVEL_STRIP_SCROLL = new ScrollController(false);
    private static final ScrollController FREE_ITEMS_SCROLL = new ScrollController(false);
    private static final ScrollController PREM_ITEMS_SCROLL = new ScrollController(false);
    private static final ScrollController SETTINGS_LEFT_SCROLL = new ScrollController(false);
    private static final ScrollController SETTINGS_RIGHT_SCROLL = new ScrollController(false);
    private static final ScrollController LEFT_LIST_SCROLL = new ScrollController(false);
    private static final ScrollController CENTER_SCROLL = new ScrollController(false);
    private static final ScrollController ONLINE_LEFT_SCROLL = new ScrollController(false);
    private static final ScrollController ONLINE_CENTER_SCROLL = new ScrollController(false);
    private static final ScrollController PICKER_SCROLL = new ScrollController(false);

    private final SkiaTextField[] fields = new SkiaTextField[FIELD_POOL_SIZE];
    @SuppressWarnings("unchecked")
    private final Consumer<String>[] fieldListeners = new Consumer[FIELD_POOL_SIZE];
    private final float[] fieldClipMinY = new float[FIELD_POOL_SIZE];
    private final float[] fieldClipMaxY = new float[FIELD_POOL_SIZE];
    private Screen boundScreen = null;
    private boolean suppressFieldCallback = false;
    private String boundContextKey = "";
    private int syncedAdminRev = -1;

    private final float[] tabHovers = new float[4];
    private float saveHover = 0.0F;
    private float rerollSaveHover = 0.0F;
    private float[][] tabRects;
    private float tabY;

    private record Hit(float x, float y, float w, float h, Runnable run) {
        boolean contains(float mx, float my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record ScrollRegion(ScrollController scroll, float x, float y, float w, float h, boolean horizontal) {
        boolean contains(float mx, float my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<ScrollRegion> scrollRegions = new ArrayList<>();
    private ScrollRegion activeBarDrag = null;

    /**
     * 每次宿主 {@code ShopScreen.init()} 调用时必须重建并重新登记全部输入框，
     * 防止单例 {@link QuestAddonPage} 持有已销毁的旧 {@code Screen} 导致所有输入框打不了字且按 1~9 误切商城页。
     */
    public void rebindInputs(AddonPageHost host) {
        if (host == null || host.screen() == null || host.font() == null) {
            return;
        }
        if (fields[SEARCH_IDX] != null) {
            persistedSearchText = fields[SEARCH_IDX].value();
        }
        for (int i = 0; i < FIELD_POOL_SIZE; i++) {
            final int slot = i;
            int maxLen = (i == SEARCH_IDX) ? 64 : (i >= TARGET_CNT_BASE ? 8 : 128);
            SkiaTextField tf = new SkiaTextField(host.screen(), host.font(), maxLen)
                    .fontSize(6.2F);
            tf.visible(false);
            tf.onChange(val -> {
                if (slot == SEARCH_IDX) {
                    persistedSearchText = val == null ? "" : val;
                    PICKER_SCROLL.reset();
                }
                if (!suppressFieldCallback && fieldListeners[slot] != null) {
                    fieldListeners[slot].accept(val == null ? "" : val);
                    if (slot != SEARCH_IDX) {
                        dirty = true;
                    }
                }
            });
            host.registerInput(tf.editBox());
            fields[i] = tf;
            fieldClipMinY[i] = -99999.0F;
            fieldClipMaxY[i] = 99999.0F;
        }
        suppressFieldCallback = true;
        fields[SEARCH_IDX].placeholder("搜索中文名 / ID / @modid ...");
        fields[SEARCH_IDX].setValue(persistedSearchText == null ? "" : persistedSearchText);
        suppressFieldCallback = false;
        boundScreen = host.screen();
        boundContextKey = "";
    }

    public void ensureInputInitialized(AddonPageHost host) {
        if (host == null) {
            return;
        }
        if (fields[0] == null || boundScreen != host.screen()) {
            rebindInputs(host);
        }
    }

    public void closeInlineEditor() {
        activeBarDrag = null;
        for (SkiaTextField tf : fields) {
            if (tf != null) {
                tf.blur();
                tf.visible(false);
            }
        }
    }

    public void blurAllInputs() {
        for (SkiaTextField tf : fields) {
            if (tf != null && tf.isFocused()) {
                tf.blur();
            }
        }
    }

    public boolean isInputFocused() {
        for (SkiaTextField tf : fields) {
            if (tf != null && tf.isVisible() && tf.isFocused()) {
                return true;
            }
        }
        return false;
    }

    private SkiaTextField findFocusedField() {
        for (SkiaTextField tf : fields) {
            if (tf != null && tf.isVisible() && tf.isFocused()) {
                return tf;
            }
        }
        return null;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        SkiaTextField tf = findFocusedField();
        if (tf == null) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            tf.blur();
            return true;
        }
        tf.editBox().keyPressed(keyCode, scanCode, modifiers);
        // 聚焦输入框时消费全部按键（含 1~9、退格、方向键、Ctrl+A/C/V），绝不穿透触发商城切页或关闭界面
        return true;
    }

    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        return findFocusedField() != null;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        SkiaTextField tf = findFocusedField();
        if (tf == null) {
            return false;
        }
        tf.editBox().charTyped(codePoint, modifiers);
        return true;
    }

    private void hideAllFieldsBeforeLayout() {
        for (int i = 0; i < FIELD_POOL_SIZE; i++) {
            if (fields[i] != null) {
                fieldClipMinY[i] = -99999.0F;
                fieldClipMaxY[i] = 99999.0F;
            }
        }
    }

    private void hideUnusedFields(boolean[] usedThisFrame) {
        for (int i = 0; i < FIELD_POOL_SIZE; i++) {
            if (!usedThisFrame[i] && fields[i] != null) {
                fields[i].visible(false);
            }
        }
    }

    private void setFieldSilently(int idx, String val, String placeholder, Consumer<String> listener) {
        if (idx < 0 || idx >= FIELD_POOL_SIZE || fields[idx] == null) {
            return;
        }
        fieldListeners[idx] = listener;
        fields[idx].placeholder(placeholder == null ? "" : placeholder);
        suppressFieldCallback = true;
        fields[idx].setValue(val == null ? "" : val);
        suppressFieldCallback = false;
    }

    private void updateFieldValueAndModel(int idx, String newVal) {
        if (idx < 0 || idx >= FIELD_POOL_SIZE || fields[idx] == null) {
            return;
        }
        fields[idx].setValue(newVal == null ? "" : newVal);
        dirty = true;
    }

    private void renderFieldClipped(Canvas c, boolean[] used, int idx,
                                    float fx, float fy, float fw, float fh,
                                    float clipMinY, float clipMaxY,
                                    float mx, float my, float dt) {
        if (idx < 0 || idx >= FIELD_POOL_SIZE || fields[idx] == null) {
            return;
        }
        if (fy + fh <= clipMinY + 0.5F || fy >= clipMaxY - 0.5F) {
            fields[idx].visible(false);
            return;
        }
        used[idx] = true;
        fieldClipMinY[idx] = clipMinY;
        fieldClipMaxY[idx] = clipMaxY;
        fields[idx].bounds(fx, fy, fw, fh);
        fields[idx].visible(true);
        boolean allowHover = my >= clipMinY && my < clipMaxY;
        fields[idx].render(c, allowHover ? mx : -9999.0F, allowHover ? my : -9999.0F, dt);
    }

    public void render(Canvas c, AddonFrame f, ItemOverlay items,
                       float x, float y, float w, float h, float mx, float my, float dt) {
        ensureInputInitialized(f.host());
        hits.clear();
        scrollRegions.clear();
        hideAllFieldsBeforeLayout();

        LEVEL_STRIP_SCROLL.update();
        FREE_ITEMS_SCROLL.update();
        PREM_ITEMS_SCROLL.update();
        SETTINGS_LEFT_SCROLL.update();
        SETTINGS_RIGHT_SCROLL.update();
        LEFT_LIST_SCROLL.update();
        CENTER_SCROLL.update();
        ONLINE_LEFT_SCROLL.update();
        ONLINE_CENTER_SCROLL.update();
        PICKER_SCROLL.update();

        if (!QuestClientState.adminLoaded() || QuestClientState.editPass() == null) {
            closeInlineEditor();
            V2.empty(c, "正在读取服务端配置文件...", x, y, w, h);
            return;
        }

        if (syncedAdminRev != QuestClientState.adminRevision()) {
            syncedAdminRev = QuestClientState.adminRevision();
            boundContextKey = "";
            dirty = false;
        }

        float pad = 8.0F;
        float ix = x + pad;
        float iw = w - pad * 2.0F;

        // 1. 顶部工作台导航条
        float barH = 22.0F;
        renderSubToolbar(c, ix, y, iw, barH, mx, my, dt);

        float bodyY = y + barH + 5.0F;
        float bodyH = Math.max(100.0F, h - barH - 7.0F);

        // 右侧常驻双模式面板（全物品库/背包/任务目标选择器）
        float dockW = clamp(iw * 0.295F, 180.0F, 232.0F);
        float mainW = iw - dockW - 8.0F;
        float dockX = ix + mainW + 8.0F;

        boolean[] usedFields = new boolean[FIELD_POOL_SIZE];
        switch (subTab) {
            case 0 -> renderPassLevelsStudio(c, f, items, usedFields, ix, bodyY, mainW, bodyH, dockX, dockW, mx, my, dt);
            case 1 -> renderPassSettingsStudio(c, f, items, usedFields, ix, bodyY, mainW, bodyH, dockX, dockW, mx, my, dt);
            case 2 -> renderQuestDesignerStudio(c, f, items, usedFields, ix, bodyY, mainW, bodyH, dockX, dockW, mx, my, dt);
            case 3 -> renderOnlineDesignerStudio(c, f, items, usedFields, ix, bodyY, mainW, bodyH, dockX, dockW, mx, my, dt);
            default -> {
            }
        }
        hideUnusedFields(usedFields);
    }

    // ================================================================ 顶部工具栏

    private void renderSubToolbar(Canvas c, float x, float y, float w, float h,
                                  float mx, float my, float dt) {
        String[] tabs = {"等级奖励", "通行证设置", "每日与每周任务", "在线时长奖励"};
        float ctrlY = y + (h - V2.CTRL) / 2.0F;
        if (tabRects != null && tabRects.length == 4) {
            for (int i = 0; i < 4; i++) {
                boolean over = mx >= tabRects[i][0] && mx < tabRects[i][0] + tabRects[i][1]
                        && my >= ctrlY && my < ctrlY + V2.CTRL;
                tabHovers[i] = Motion.hover(tabHovers[i], over, dt);
            }
        }
        tabY = ctrlY;
        tabRects = V2.segmented(c, tabs, subTab, x, ctrlY, tabHovers);

        float right = x + w;
        String saveLbl = dirty ? "● 保存更改" : "保存配置";
        float sw = V2.buttonWidth(saveLbl) + 14.0F;
        float sx = right - sw;
        boolean overSave = inRect(mx, my, sx, ctrlY, sw, V2.CTRL);
        saveHover = Motion.hover(saveHover, overSave, dt);
        V2.button(c, saveLbl, V2.Btn.PRIMARY, sx, ctrlY, sw, V2.CTRL, saveHover);
        hit(sx, ctrlY, sw, V2.CTRL, () -> {
            syncAllQuestLegacyFields();
            dirty = false;
            QuestClient.sendSaveAdminConfig(
                    QuestClientState.editPass(),
                    QuestClientState.editPools(),
                    QuestClientState.editOnline(),
                    false
            );
        });
        right = sx - 6.0F;

        if (subTab == 2) {
            String rerollLbl = "保存并刷新我的任务";
            float rw = V2.buttonWidth(rerollLbl) + 10.0F;
            float rx = right - rw;
            boolean overR = inRect(mx, my, rx, ctrlY, rw, V2.CTRL);
            rerollSaveHover = Motion.hover(rerollSaveHover, overR, dt);
            V2.button(c, rerollLbl, V2.Btn.NORMAL, rx, ctrlY, rw, V2.CTRL, rerollSaveHover);
            hit(rx, ctrlY, rw, V2.CTRL, () -> {
                syncAllQuestLegacyFields();
                dirty = false;
                QuestClient.sendSaveAdminConfig(
                        QuestClientState.editPass(),
                        QuestClientState.editPools(),
                        QuestClientState.editOnline(),
                        true
                );
            });
            right = rx - 6.0F;
        }

        String reloadLbl = "放弃重读";
        float rlw = V2.buttonWidth(reloadLbl) + 6.0F;
        float rlx = right - rlw;
        float segRight = (tabRects != null && tabRects.length > 0)
                ? (tabRects[tabRects.length - 1][0] + tabRects[tabRects.length - 1][1] + 6.0F)
                : (x + 240.0F);
        if (rlx > segRight) {
            V2.button(c, reloadLbl, V2.Btn.GHOST, rlx, ctrlY, rlw, V2.CTRL,
                    inRect(mx, my, rlx, ctrlY, rlw, V2.CTRL) ? 1.0F : 0.0F);
            hit(rlx, ctrlY, rlw, V2.CTRL, () -> {
                dirty = false;
                QuestClient.sendAdminReload();
            });
        }
    }

    private static void syncAllQuestLegacyFields() {
        QuestPoolsConfig pools = QuestClientState.editPools();
        if (pools == null) return;
        if (pools.dailyPool != null) {
            for (QuestDefSpec q : pools.dailyPool) {
                if (q != null) q.syncLegacyFieldsFromTargets();
            }
        }
        if (pools.weeklyPool != null) {
            for (QuestDefSpec q : pools.weeklyPool) {
                if (q != null) q.syncLegacyFieldsFromTargets();
            }
        }
    }

    // ================================================================ 1. 【等级奖励】工作台

    private void renderPassLevelsStudio(Canvas c, AddonFrame f, ItemOverlay items, boolean[] used,
                                        float x, float y, float w, float h,
                                        float dockX, float dockW, float mx, float my, float dt) {
        SeasonPassConfig pass = QuestClientState.editPass();
        PointsRuleSpec rule = pass.pointsRule;
        int maxLv = Math.max(1, Math.min(200, rule.maxLevel));
        if (selectedLevel > maxLv) selectedLevel = maxLv;
        if (selectedLevel < -1) selectedLevel = -1;

        RewardBundleSpec freeBundle;
        RewardBundleSpec premBundle;
        PassLevelSpec explicitSpec = selectedLevel >= 1 ? findExplicitLevel(pass, selectedLevel) : null;

        if (selectedLevel == -1) {
            if (rule.defaultFreeReward == null) rule.defaultFreeReward = new RewardBundleSpec();
            if (rule.defaultPremiumReward == null) rule.defaultPremiumReward = new RewardBundleSpec();
            freeBundle = rule.defaultFreeReward;
            premBundle = rule.defaultPremiumReward;
        } else if (selectedLevel == 0) {
            if (rule.defaultMilestoneFreeReward == null) rule.defaultMilestoneFreeReward = new RewardBundleSpec();
            if (rule.defaultMilestonePremiumReward == null) rule.defaultMilestonePremiumReward = new RewardBundleSpec();
            freeBundle = rule.defaultMilestoneFreeReward;
            premBundle = rule.defaultMilestonePremiumReward;
        } else {
            List<PassLevelSpec> resolved = pass.resolvedLevels();
            PassLevelSpec res = resolved.get(Math.min(resolved.size() - 1, selectedLevel - 1));
            freeBundle = explicitSpec != null ? explicitSpec.freeReward : res.freeReward;
            premBundle = explicitSpec != null ? explicitSpec.premiumReward : res.premiumReward;
        }

        // 注意：context key 不包含 explicitSpec != null，避免用户首次敲字自动创建独立等级时重置输入框光标！
        String ctx = "tab0:" + selectedLevel;
        if (!ctx.equals(boundContextKey)) {
            boundContextKey = ctx;
            FREE_ITEMS_SCROLL.reset();
            PREM_ITEMS_SCROLL.reset();
            setFieldSilently(0, String.valueOf(freeBundle.money), "0", v -> {
                RewardBundleSpec target = selectedLevel >= 1
                        ? getOrCreateExplicitLevel(pass, selectedLevel).freeReward : freeBundle;
                target.money = parseLongBlankZero(v, target.money, 0L, 99999999L);
            });
            setFieldSilently(1, String.valueOf(premBundle.money), "0", v -> {
                RewardBundleSpec target = selectedLevel >= 1
                        ? getOrCreateExplicitLevel(pass, selectedLevel).premiumReward : premBundle;
                target.money = parseLongBlankZero(v, target.money, 0L, 99999999L);
            });
        }

        // 顶部等级快速导航条
        float stripH = 26.0F;
        Draw.fill(c, x, y, w, stripH, 5.5F, ShopTheme.card());
        Draw.stroke(c, x, y, w, stripH, 5.5F, 0.7F, ShopTheme.borderDim());

        float navBtnW = 16.0F;
        drawChip(c, "‹", x + 4.0F, y + 4.0F, navBtnW, stripH - 8.0F, false, inRect(mx, my, x + 4.0F, y + 4.0F, navBtnW, stripH - 8.0F));
        hit(x + 4.0F, y + 4.0F, navBtnW, stripH - 8.0F, () -> LEVEL_STRIP_SCROLL.wheel(4.5D));

        drawChip(c, "›", x + w - navBtnW - 4.0F, y + 4.0F, navBtnW, stripH - 8.0F, false,
                inRect(mx, my, x + w - navBtnW - 4.0F, y + 4.0F, navBtnW, stripH - 8.0F));
        hit(x + w - navBtnW - 4.0F, y + 4.0F, navBtnW, stripH - 8.0F, () -> LEVEL_STRIP_SCROLL.wheel(-4.5D));

        float lvStripX = x + navBtnW + 7.0F;
        float lvStripW = Math.max(40.0F, w - (navBtnW + 7.0F) * 2.0F);
        float totalW = 74.0F + 74.0F + maxLv * 42.0F + (maxLv + 2) * 4.0F;
        LEVEL_STRIP_SCROLL.setContent(totalW, lvStripW);
        scrollRegions.add(new ScrollRegion(LEVEL_STRIP_SCROLL, lvStripX, y, lvStripW, stripH, true));

        int save = c.save();
        Draw.clip(c, lvStripX, y, lvStripW, stripH);
        float cx = lvStripX - LEVEL_STRIP_SCROLL.offset();
        for (int idx = -1; idx <= maxLv; idx++) {
            float cw = idx <= 0 ? 72.0F : 40.0F;
            if (cx + cw >= lvStripX && cx <= lvStripX + lvStripW) {
                boolean sel = selectedLevel == idx;
                boolean customized = idx >= 1 && findExplicitLevel(pass, idx) != null;
                String lbl = idx == -1 ? "普通级通用"
                        : (idx == 0 ? "每" + rule.milestoneEveryLevels + "级通用" : ((customized ? "★ " : "") + idx + "级"));
                boolean over = mx >= lvStripX && mx < lvStripX + lvStripW && inRect(mx, my, cx, y + 4.0F, cw, stripH - 8.0F);
                drawChip(c, lbl, cx, y + 4.0F, cw, stripH - 8.0F, sel, over);
                if (mx >= lvStripX && mx < lvStripX + lvStripW) {
                    final int target = idx;
                    hit(Math.max(cx, lvStripX), y + 4.0F,
                            Math.min(cx + cw, lvStripX + lvStripW) - Math.max(cx, lvStripX),
                            stripH - 8.0F, () -> {
                                selectedLevel = target;
                                boundContextKey = "";
                                QuestClient.playClickSound(1.04F);
                            });
                }
            }
            cx += cw + 4.0F;
        }
        c.restoreToCount(save);

        // 等级状态与快捷操作条
        float infoY = y + stripH + 5.0F;
        float infoH = 22.0F;
        Draw.fill(c, x, infoY, w, infoH, 5.0F, ShopTheme.card());
        Draw.stroke(c, x, infoY, w, infoH, 5.0F, 0.6F, ShopTheme.borderDim());

        if (selectedLevel <= 0) {
            String desc = selectedLevel == -1
                    ? "通用模板：所有没单独改过的普通等级，自动发放以下奖励"
                    : "重点级通用模板：每逢 " + rule.milestoneEveryLevels + " 的倍数级且未单独修改时生效";
            V2.bold(c, desc, 6.0F, ShopTheme.text(), x + 10.0F, infoY + infoH / 2.0F, w - 20.0F, Text.Align.LEFT);
        } else {
            List<PassLevelSpec> resolved = pass.resolvedLevels();
            long reqPts = resolved.get(Math.min(resolved.size() - 1, selectedLevel - 1)).requiredPoints;
            float rx = x + w - 6.0F;
            if (explicitSpec != null) {
                float bw = 60.0F;
                rx -= bw;
                drawChip(c, "恢复通用", rx, infoY + 3.0F, bw, infoH - 6.0F, false, inRect(mx, my, rx, infoY + 3.0F, bw, infoH - 6.0F));
                final int lvClear = selectedLevel;
                hit(rx, infoY + 3.0F, bw, infoH - 6.0F, () -> {
                    pass.levels.removeIf(s -> s != null && s.level == lvClear);
                    dirty = true;
                    boundContextKey = "";
                    QuestClient.playClickSound(0.95F);
                });
                rx -= 4.0F;
            }
            if (selectedLevel > 1) {
                float bw = 60.0F;
                rx -= bw;
                drawChip(c, "复制上一级", rx, infoY + 3.0F, bw, infoH - 6.0F, false, inRect(mx, my, rx, infoY + 3.0F, bw, infoH - 6.0F));
                final int cur = selectedLevel;
                hit(rx, infoY + 3.0F, bw, infoH - 6.0F, () -> {
                    PassLevelSpec prevResolved = pass.resolvedLevels().get(Math.max(0, cur - 2));
                    PassLevelSpec target = getOrCreateExplicitLevel(pass, cur);
                    target.freeReward = cloneBundle(prevResolved.freeReward);
                    target.premiumReward = cloneBundle(prevResolved.premiumReward);
                    dirty = true;
                    boundContextKey = "";
                    QuestClient.playClickSound(1.06F);
                });
                rx -= 4.0F;
            }
            boolean isHi = explicitSpec != null ? explicitSpec.highlight
                    : (selectedLevel % Math.max(1, rule.milestoneEveryLevels) == 0);
            float hw = 60.0F;
            rx -= hw;
            drawChip(c, isHi ? "★ 重点展示" : "☆ 普通展示", rx, infoY + 3.0F, hw, infoH - 6.0F, isHi,
                    inRect(mx, my, rx, infoY + 3.0F, hw, infoH - 6.0F));
            hit(rx, infoY + 3.0F, hw, infoH - 6.0F, () -> {
                PassLevelSpec target = getOrCreateExplicitLevel(pass, selectedLevel);
                target.highlight = !isHi;
                dirty = true;
                QuestClient.playClickSound(1.04F);
            });
            float textMaxW = Math.max(60.0F, rx - (x + 10.0F) - 6.0F);
            String state = explicitSpec != null
                    ? ("第 " + selectedLevel + " 级（独立配置 · 需 " + reqPts + " 积分）")
                    : ("第 " + selectedLevel + " 级（沿用模板 · 需 " + reqPts + " 积分）");
            V2.bold(c, state, 6.0F, explicitSpec != null ? ShopTheme.accent() : ShopTheme.text2(),
                    x + 10.0F, infoY + infoH / 2.0F, textMaxW, Text.Align.LEFT);
        }

        // 左右并列双轨可视化奖励大卡片（各自独立垂直滚动，支持任意数量物品不截断！）
        float cardsY = infoY + infoH + 5.0F;
        float cardsH = Math.max(80.0F, h - (cardsY - y));
        float colW = (w - 8.0F) / 2.0F;

        renderVisualTrackEditorCard(c, f, items, used,
                pass.freeTrackName,
                !pickTargetPremium,
                () -> pickTargetPremium = false,
                freeBundle,
                0,
                FREE_ITEMS_SCROLL,
                () -> selectedLevel >= 1 ? getOrCreateExplicitLevel(pass, selectedLevel).freeReward : freeBundle,
                x, cardsY, colW, cardsH, mx, my, dt);

        renderVisualTrackEditorCard(c, f, items, used,
                pass.premiumTrack.trackName,
                pickTargetPremium,
                () -> pickTargetPremium = true,
                premBundle,
                1,
                PREM_ITEMS_SCROLL,
                () -> selectedLevel >= 1 ? getOrCreateExplicitLevel(pass, selectedLevel).premiumReward : premBundle,
                x + colW + 8.0F, cardsY, colW, cardsH, mx, my, dt);

        String targetName = pickTargetPremium ? pass.premiumTrack.trackName : pass.freeTrackName;
        renderRightItemRewardDock(c, f, items, used, "添加至：" + targetName, spec -> {
            RewardBundleSpec dest = selectedLevel >= 1
                    ? (pickTargetPremium
                    ? getOrCreateExplicitLevel(pass, selectedLevel).premiumReward
                    : getOrCreateExplicitLevel(pass, selectedLevel).freeReward)
                    : (pickTargetPremium ? premBundle : freeBundle);
            appendOrMergeItem(dest, spec);
            dirty = true;
        }, dockX, y, dockW, h, mx, my, dt);
    }

    private void renderVisualTrackEditorCard(Canvas c, AddonFrame f, ItemOverlay items, boolean[] used,
                                             String title, boolean activeTarget, Runnable onSelect,
                                             RewardBundleSpec bundle, int moneyFieldIdx,
                                             ScrollController itemsScroll,
                                             BundleMutator mutator,
                                             float x, float y, float w, float h,
                                             float mx, float my, float dt) {
        Draw.fill(c, x, y, w, h, 6.0F,
                activeTarget ? Draw.lerp(ShopTheme.card(), ShopTheme.accentTint(), 0.22F) : ShopTheme.card());
        Draw.stroke(c, x, y, w, h, 6.0F, activeTarget ? 1.2F : 0.7F,
                activeTarget ? ShopTheme.accent() : ShopTheme.borderDim());

        V2.bold(c, (activeTarget ? "● " : "○ ") + title, 7.0F,
                activeTarget ? ShopTheme.accent() : ShopTheme.text(),
                x + 10.0F, y + 13.0F, w * 0.50F, Text.Align.LEFT);
        String targetTag = activeTarget ? "← 右侧点物品加入此轨" : "点此切换目标";
        V2.text(c, targetTag, 5.2F, activeTarget ? ShopTheme.accent() : ShopTheme.text3(),
                x + 10.0F, y + 13.0F, w - 20.0F, Text.Align.RIGHT);
        hit(x, y, w, 24.0F, () -> {
            onSelect.run();
            QuestClient.playClickSound(1.02F);
        });

        float mRowY = y + 26.0F;
        V2.bold(c, "奖励星币", 6.0F, ShopTheme.text2(), x + 10.0F, mRowY + 8.5F, 48.0F, Text.Align.LEFT);
        float tfX = x + 54.0F;
        float presetsTotalW = 28.0F * 3.0F + 6.0F;
        float tfW = Math.max(46.0F, w - 54.0F - presetsTotalW - 14.0F);
        renderFieldClipped(c, used, moneyFieldIdx, tfX, mRowY, tfW, 17.0F, y, y + h, mx, my, dt);

        String[] presets = {"清零", "+50", "+200"};
        long[] deltas = {0L, 50L, 200L};
        float px = tfX + tfW + 4.0F;
        for (int i = 0; i < presets.length; i++) {
            float pw = 28.0F;
            drawChip(c, presets[i], px, mRowY + 1.0F, pw, 15.0F, false, inRect(mx, my, px, mRowY + 1.0F, pw, 15.0F));
            final int k = i;
            hit(px, mRowY + 1.0F, pw, 15.0F, () -> {
                RewardBundleSpec wr = mutator.writable();
                wr.money = k == 0 ? 0L : (wr.money + deltas[k]);
                updateFieldValueAndModel(moneyFieldIdx, String.valueOf(wr.money));
                QuestClient.playClickSound(1.04F);
            });
            px += pw + 3.0F;
        }

        Draw.hairline(c, x + 10.0F, mRowY + 22.0F, w - 20.0F, ShopTheme.borderDim());
        int itemCount = bundle.items == null ? 0 : bundle.items.size();
        V2.bold(c, "奖励物品列表（共 " + itemCount + " 项 · 可滚轮滚动）",
                5.8F, ShopTheme.text2(), x + 10.0F, mRowY + 31.0F, w - 20.0F, Text.Align.LEFT);

        float listTop = mRowY + 40.0F;
        float listH = Math.max(36.0F, y + h - listTop - 6.0F);
        renderScrollableRichItemCards(c, items, bundle, mutator, itemsScroll,
                x + 8.0F, listTop, w - 16.0F, listH, mx, my, dt);
    }

    // ================================================================ 2. 【通行证设置】双栏独立可滚动工作台

    private void renderPassSettingsStudio(Canvas c, AddonFrame f, ItemOverlay items, boolean[] used,
                                          float x, float y, float w, float h,
                                          float dockX, float dockW, float mx, float my, float dt) {
        SeasonPassConfig pass = QuestClientState.editPass();
        PointsRuleSpec rule = pass.pointsRule;
        PremiumTrackSpec prem = pass.premiumTrack;
        if (prem.deluxeExtraReward == null) prem.deluxeExtraReward = new RewardBundleSpec();

        String ctx = "tab1:" + pass.seasonId;
        if (!ctx.equals(boundContextKey)) {
            boundContextKey = ctx;
            SETTINGS_LEFT_SCROLL.reset();
            SETTINGS_RIGHT_SCROLL.reset();
            setFieldSilently(0, pass.seasonName, "第 1 赛季", v -> { if (!v.isBlank()) pass.seasonName = v.trim(); });
            setFieldSilently(1, String.valueOf(rule.maxLevel), "30", v -> rule.maxLevel = parseIntClamp(v, rule.maxLevel, 1, 200));
            setFieldSilently(2, String.valueOf(rule.basePointsPerLevel), "100", v -> rule.basePointsPerLevel = parseLongClamp(v, rule.basePointsPerLevel, 10L, 100000L));
            setFieldSilently(3, String.valueOf(rule.buyLevelStarCoinCost), "150", v -> rule.buyLevelStarCoinCost = parseLongClamp(v, rule.buyLevelStarCoinCost, 1L, 9999999L));
            setFieldSilently(4, String.valueOf(prem.unlockStarCoinCost), "2880", v -> prem.unlockStarCoinCost = parseLongBlankZero(v, prem.unlockStarCoinCost, 0L, 99999999L));
            setFieldSilently(5, String.valueOf(prem.unlockBonusPoints), "200", v -> prem.unlockBonusPoints = parseLongBlankZero(v, prem.unlockBonusPoints, 0L, 999999L));
            setFieldSilently(6, String.valueOf(prem.deluxeStarCoinCost), "4880", v -> {
                prem.deluxeStarCoinCost = parseLongBlankZero(v, prem.deluxeStarCoinCost, 0L, 99999999L);
                if (prem.deluxeOriginalStarCoinCost < prem.deluxeStarCoinCost) {
                    prem.deluxeOriginalStarCoinCost = prem.deluxeStarCoinCost;
                }
            });
            setFieldSilently(7, String.valueOf(prem.deluxeOriginalStarCoinCost), "5880", v ->
                    prem.deluxeOriginalStarCoinCost = parseLongBlankZero(v, prem.deluxeOriginalStarCoinCost, 0L, 99999999L));
            setFieldSilently(8, String.valueOf(prem.deluxeExtraReward.money), "680", v ->
                    prem.deluxeExtraReward.money = parseLongBlankZero(v, prem.deluxeExtraReward.money, 0L, 99999999L));
        }

        float colW = (w - 8.0F) / 2.0F;

        // 左栏：赛季周期、满级积分节奏与买级设置（可垂直滚动）
        Draw.fill(c, x, y, colW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, x, y, colW, h, 6.0F, 0.7F, ShopTheme.borderDim());
        V2.bold(c, "赛季节奏与等级设置", 7.2F, ShopTheme.text(), x + 10.0F, y + 13.0F, colW - 20.0F, Text.Align.LEFT);
        Draw.hairline(c, x + 8.0F, y + 22.0F, colW - 16.0F, ShopTheme.borderDim());

        float leftVpY = y + 25.0F;
        float leftVpH = Math.max(40.0F, h - 29.0F);
        float leftContentH = 224.0F;
        SETTINGS_LEFT_SCROLL.setContent(leftContentH, leftVpH);
        scrollRegions.add(new ScrollRegion(SETTINGS_LEFT_SCROLL, x, leftVpY, colW, leftVpH, false));
        float leftOff = SETTINGS_LEFT_SCROLL.offset();
        float leftInnerW = colW - (SETTINGS_LEFT_SCROLL.canScroll() ? 24.0F : 20.0F);

        int saveL = c.save();
        Draw.clip(c, x + 2.0F, leftVpY, colW - 4.0F, leftVpH);
        float ly = leftVpY + 2.0F - leftOff;
        float leftClipMin = leftVpY;
        float leftClipMax = leftVpY + leftVpH;

        renderDirectInputRow(c, used, "当前赛季名称", 0, "", x + 10.0F, ly, leftInnerW, leftClipMin, leftClipMax, mx, my, dt, null, null);
        ly += 23.0F;

        renderDirectInputRow(c, used, "最高等级", 1, "级", x + 10.0F, ly, leftInnerW, leftClipMin, leftClipMax, mx, my, dt,
                () -> { rule.maxLevel = Math.max(1, rule.maxLevel - 5); updateFieldValueAndModel(1, String.valueOf(rule.maxLevel)); },
                () -> { rule.maxLevel = Math.min(200, rule.maxLevel + 5); updateFieldValueAndModel(1, String.valueOf(rule.maxLevel)); });
        ly += 23.0F;

        renderDirectInputRow(c, used, "每级基础积分", 2, "分", x + 10.0F, ly, leftInnerW, leftClipMin, leftClipMax, mx, my, dt,
                () -> { rule.basePointsPerLevel = Math.max(10L, rule.basePointsPerLevel - 20L); updateFieldValueAndModel(2, String.valueOf(rule.basePointsPerLevel)); },
                () -> { rule.basePointsPerLevel += 20L; updateFieldValueAndModel(2, String.valueOf(rule.basePointsPerLevel)); });
        ly += 21.0F;

        List<PassLevelSpec> previewLv = pass.resolvedLevels();
        long maxTotalPts = previewLv.isEmpty() ? 0L : previewLv.get(previewLv.size() - 1).requiredPoints;
        V2.text(c, "→ 升满 " + rule.maxLevel + " 级共需累计 " + V2.money(maxTotalPts) + " 积分",
                5.5F, ShopTheme.accent(), x + 10.0F, ly + 5.0F, leftInnerW, Text.Align.LEFT);
        ly += 16.0F;

        Draw.hairline(c, x + 10.0F, ly, leftInnerW, ShopTheme.borderDim());
        ly += 7.0F;

        renderToggleRow(c, "允许玩家用星币购买等级", rule.allowBuyLevels, x + 10.0F, ly, leftInnerW, leftClipMin, leftClipMax, mx, my,
                () -> { rule.allowBuyLevels = !rule.allowBuyLevels; dirty = true; });
        ly += 22.0F;

        renderDirectInputRow(c, used, "买 1 级价格", 3, "星币", x + 10.0F, ly, leftInnerW, leftClipMin, leftClipMax, mx, my, dt,
                () -> { rule.buyLevelStarCoinCost = Math.max(1L, rule.buyLevelStarCoinCost - 25L); updateFieldValueAndModel(3, String.valueOf(rule.buyLevelStarCoinCost)); },
                () -> { rule.buyLevelStarCoinCost += 25L; updateFieldValueAndModel(3, String.valueOf(rule.buyLevelStarCoinCost)); });
        ly += 26.0F;

        Draw.hairline(c, x + 10.0F, ly, leftInnerW, ShopTheme.borderDim());
        ly += 8.0F;

        V2.text(c, "换季（自动归档旧季补领，结转 " + pass.rolloverPercent + "% 积分）：",
                5.5F, ShopTheme.text2(), x + 10.0F, ly + 6.0F, leftInnerW, Text.Align.LEFT);
        ly += 15.0F;
        boolean overNewSeason = my >= leftClipMin && my < leftClipMax && inRect(mx, my, x + 10.0F, ly, leftInnerW, 18.0F);
        drawChip(c, "一键开启下一赛季（点右上保存生效）", x + 10.0F, ly, leftInnerW, 18.0F, false, overNewSeason);
        hitClipped(x + 10.0F, ly, leftInnerW, 18.0F, leftClipMin, leftClipMax, () -> {
            pass.seasonId = "season_" + (System.currentTimeMillis() / 1000L);
            pass.seasonName = bumpSeasonTitle(pass.seasonName);
            pass.seasonStartEpochSecond = System.currentTimeMillis() / 1000L;
            dirty = true;
            boundContextKey = "";
            QuestClient.playClickSound(1.12F);
        });
        c.restoreToCount(saveL);
        if (SETTINGS_LEFT_SCROLL.canScroll()) {
            SETTINGS_LEFT_SCROLL.drawBar(c, x, leftVpY, colW, leftVpH, mx, my, dt);
        }

        // 右栏：进阶通行证 + 典藏通行证独立定价与礼包（完整垂直滚动，零截断！）
        float rx = x + colW + 8.0F;
        Draw.fill(c, rx, y, colW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, rx, y, colW, h, 6.0F, 0.7F, ShopTheme.borderDim());
        V2.bold(c, "进阶版与典藏版定价及额外礼包", 7.2F, ShopTheme.text(), rx + 10.0F, y + 13.0F, colW - 20.0F, Text.Align.LEFT);
        Draw.hairline(c, rx + 8.0F, y + 22.0F, colW - 16.0F, ShopTheme.borderDim());

        float rightVpY = y + 25.0F;
        float rightVpH = Math.max(40.0F, h - 29.0F);
        int deluxeItems = prem.deluxeExtraReward.items == null ? 0 : prem.deluxeExtraReward.items.size();
        float deluxeListH = Math.max(44.0F, deluxeItems * 27.0F + 6.0F);
        float rightContentH = 186.0F + deluxeListH;
        SETTINGS_RIGHT_SCROLL.setContent(rightContentH, rightVpH);
        scrollRegions.add(new ScrollRegion(SETTINGS_RIGHT_SCROLL, rx, rightVpY, colW, rightVpH, false));
        float rightOff = SETTINGS_RIGHT_SCROLL.offset();
        float rightInnerW = colW - (SETTINGS_RIGHT_SCROLL.canScroll() ? 24.0F : 20.0F);

        int saveR = c.save();
        Draw.clip(c, rx + 2.0F, rightVpY, colW - 4.0F, rightVpH);
        if (items != null) {
            items.pushClip(rx + 2.0F, rightVpY, colW - 4.0F, rightVpH);
        }
        float ry = rightVpY + 2.0F - rightOff;
        float rClipMin = rightVpY;
        float rClipMax = rightVpY + rightVpH;

        renderDirectInputRow(c, used, "进阶版解锁价", 4, "星币", rx + 10.0F, ry, rightInnerW, rClipMin, rClipMax, mx, my, dt, null, null);
        ry += 22.0F;

        renderDirectInputRow(c, used, "进阶立送积分", 5, "分", rx + 10.0F, ry, rightInnerW, rClipMin, rClipMax, mx, my, dt, null, null);
        ry += 22.0F;

        renderToggleRow(c, "开启第二档【典藏通行证】", prem.deluxeEnabled, rx + 10.0F, ry, rightInnerW, rClipMin, rClipMax, mx, my,
                () -> { prem.deluxeEnabled = !prem.deluxeEnabled; dirty = true; });
        ry += 21.0F;

        renderDirectInputRow(c, used, "典藏版实际售价", 6, "星币", rx + 10.0F, ry, rightInnerW, rClipMin, rClipMax, mx, my, dt, null, null);
        ry += 22.0F;

        renderDirectInputRow(c, used, "典藏划线原价", 7, "星币", rx + 10.0F, ry, rightInnerW, rClipMin, rClipMax, mx, my, dt, null, null);
        ry += 22.0F;

        V2.text(c, "典藏立即直升等级", 6.0F, ShopTheme.text2(), rx + 10.0F, ry + 8.5F, rightInnerW * 0.5F, Text.Align.LEFT);
        float stepRight = rx + 10.0F + rightInnerW;
        drawChip(c, "+", stepRight - 18.0F, ry + 1.0F, 18.0F, 15.0F, false,
                my >= rClipMin && my < rClipMax && inRect(mx, my, stepRight - 18.0F, ry + 1.0F, 18.0F, 15.0F));
        hitClipped(stepRight - 18.0F, ry + 1.0F, 18.0F, 15.0F, rClipMin, rClipMax, () -> {
            prem.deluxeBonusLevels = Math.min(100, prem.deluxeBonusLevels + 1);
            dirty = true;
            QuestClient.playClickSound(1.05F);
        });
        drawChip(c, "+" + prem.deluxeBonusLevels + " 级", stepRight - 66.0F, ry + 1.0F, 45.0F, 15.0F, true, false);
        drawChip(c, "-", stepRight - 87.0F, ry + 1.0F, 18.0F, 15.0F, false,
                my >= rClipMin && my < rClipMax && inRect(mx, my, stepRight - 87.0F, ry + 1.0F, 18.0F, 15.0F));
        hitClipped(stepRight - 87.0F, ry + 1.0F, 18.0F, 15.0F, rClipMin, rClipMax, () -> {
            prem.deluxeBonusLevels = Math.max(0, prem.deluxeBonusLevels - 1);
            dirty = true;
            QuestClient.playClickSound(0.98F);
        });
        ry += 21.0F;

        renderDirectInputRow(c, used, "典藏额外送星币", 8, "星币", rx + 10.0F, ry, rightInnerW, rClipMin, rClipMax, mx, my, dt, null, null);
        ry += 22.0F;

        V2.bold(c, "典藏额外礼包物品（共 " + deluxeItems + " 项 · 右侧点选添加）：",
                5.8F, ShopTheme.text2(), rx + 10.0F, ry + 6.0F, rightInnerW, Text.Align.LEFT);
        ry += 14.0F;

        renderInlineRichItemCards(c, items, prem.deluxeExtraReward, () -> prem.deluxeExtraReward,
                rx + 10.0F, ry, rightInnerW, rClipMin, rClipMax, mx, my);

        if (items != null) {
            items.popClip();
        }
        c.restoreToCount(saveR);
        if (SETTINGS_RIGHT_SCROLL.canScroll()) {
            SETTINGS_RIGHT_SCROLL.drawBar(c, rx, rightVpY, colW, rightVpH, mx, my, dt);
        }

        renderRightItemRewardDock(c, f, items, used, "添加至：典藏额外礼包", spec -> {
            appendOrMergeItem(prem.deluxeExtraReward, spec);
            dirty = true;
        }, dockX, y, dockW, h, mx, my, dt);
    }

    // ================================================================ 3. 【每日与每周任务】多子目标 + 类 JEI 目标选择器工作台

    private void renderQuestDesignerStudio(Canvas c, AddonFrame f, ItemOverlay items, boolean[] used,
                                           float x, float y, float w, float h,
                                           float dockX, float dockW, float mx, float my, float dt) {
        QuestPoolsConfig pools = QuestClientState.editPools();
        List<QuestDefSpec> list = editWeeklyPool ? pools.weeklyPool : pools.dailyPool;
        if (selectedQuestIdx >= list.size()) {
            selectedQuestIdx = Math.max(0, list.size() - 1);
        }

        float leftW = clamp(w * 0.285F, 128.0F, 170.0F);
        float midX = x + leftW + 8.0F;
        float midW = w - leftW - 8.0F;

        // 1. 左栏：可滚动任务列表
        Draw.fill(c, x, y, leftW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, x, y, leftW, h, 6.0F, 0.7F, ShopTheme.borderDim());

        float halfToggleW = (leftW - 14.0F) / 2.0F;
        drawChip(c, "每日 (" + pools.dailyPool.size() + ")", x + 5.0F, y + 5.0F, halfToggleW, 16.0F, !editWeeklyPool,
                inRect(mx, my, x + 5.0F, y + 5.0F, halfToggleW, 16.0F));
        hit(x + 5.0F, y + 5.0F, halfToggleW, 16.0F, () -> {
            editWeeklyPool = false;
            selectedQuestIdx = 0;
            editingTargetRowIdx = -1;
            boundContextKey = "";
            LEFT_LIST_SCROLL.reset();
            CENTER_SCROLL.reset();
        });

        drawChip(c, "每周 (" + pools.weeklyPool.size() + ")", x + 9.0F + halfToggleW, y + 5.0F, halfToggleW, 16.0F, editWeeklyPool,
                inRect(mx, my, x + 9.0F + halfToggleW, y + 5.0F, halfToggleW, 16.0F));
        hit(x + 9.0F + halfToggleW, y + 5.0F, halfToggleW, 16.0F, () -> {
            editWeeklyPool = true;
            selectedQuestIdx = 0;
            editingTargetRowIdx = -1;
            boundContextKey = "";
            LEFT_LIST_SCROLL.reset();
            CENTER_SCROLL.reset();
        });

        float addBtnY = y + 24.0F;
        V2.button(c, "+ 新建任务", V2.Btn.NORMAL, x + 5.0F, addBtnY, leftW - 10.0F, 16.0F,
                inRect(mx, my, x + 5.0F, addBtnY, leftW - 10.0F, 16.0F) ? 1.0F : 0.0F);
        hit(x + 5.0F, addBtnY, leftW - 10.0F, 16.0F, () -> {
            String newId = (editWeeklyPool ? "w_task_" : "d_task_") + (System.currentTimeMillis() % 100000L);
            list.add(new QuestDefSpec(newId, "商店购买", editWeeklyPool ? "本周累计购买 5 次商品" : "在商店购买 1 次商品",
                    "custom_" + (list.size() + 1), "module_shop", QuestTaskType.SHOP_BUY_ANY, "",
                    editWeeklyPool ? 5 : 1, 15,
                    new RewardBundleSpec(editWeeklyPool ? 180L : 40L, editWeeklyPool ? 180L : 40L, List.of())));
            selectedQuestIdx = list.size() - 1;
            editingTargetRowIdx = -1;
            dirty = true;
            boundContextKey = "";
            CENTER_SCROLL.reset();
            QuestClient.playClickSound(1.08F);
        });

        float leftListX = x + 5.0F;
        float leftListY = y + 44.0F;
        float leftListW = leftW - 10.0F;
        float leftListH = Math.max(40.0F, h - 48.0F);
        float itemCardH = 30.0F;
        LEFT_LIST_SCROLL.setContent(list.size() * itemCardH, leftListH);
        scrollRegions.add(new ScrollRegion(LEFT_LIST_SCROLL, leftListX, leftListY, leftListW, leftListH, false));

        int saveLeft = c.save();
        Draw.clip(c, leftListX, leftListY, leftListW, leftListH);
        float cardDrawW = leftListW - (LEFT_LIST_SCROLL.canScroll() ? 6.0F : 0.0F);
        for (int i = 0; i < list.size(); i++) {
            float ry = leftListY + i * itemCardH - LEFT_LIST_SCROLL.offset();
            if (ry + itemCardH < leftListY || ry > leftListY + leftListH) continue;
            QuestDefSpec qItem = list.get(i);
            boolean sel = i == selectedQuestIdx;
            boolean over = my >= leftListY && my < leftListY + leftListH
                    && inRect(mx, my, leftListX, ry, cardDrawW, itemCardH - 2.5F);
            int bg = sel ? Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.30F : 0.16F)
                    : (over ? Draw.alpha(ShopTheme.accent(), 0.08F) : Draw.alpha(ShopTheme.text3(), 0.06F));
            Draw.fill(c, leftListX, ry, cardDrawW, itemCardH - 2.5F, 4.0F, bg);
            Draw.stroke(c, leftListX, ry, cardDrawW, itemCardH - 2.5F, 4.0F, sel ? 0.9F : 0.5F,
                    sel ? ShopTheme.accent() : ShopTheme.borderDim());

            V2.bold(c, qItem.title, 6.1F, sel ? ShopTheme.accent() : ShopTheme.text(),
                    leftListX + 6.0F, ry + 8.5F, cardDrawW - 12.0F, Text.Align.LEFT);
            long rm = qItem.reward != null ? qItem.reward.money : 0L;
            long rp = qItem.reward != null ? qItem.reward.points : 0L;
            int objCnt = qItem.resolvedTargets().size();
            String sub = (objCnt > 1 ? (objCnt + "目标") : ("×" + qItem.totalTargetCount()))
                    + " · +" + rm + "★ +" + rp + "分";
            V2.text(c, sub, 5.0F, ShopTheme.text3(), leftListX + 6.0F, ry + 19.5F, cardDrawW - 12.0F, Text.Align.LEFT);

            final int pick = i;
            hitClipped(leftListX, ry, cardDrawW, itemCardH - 2.5F, leftListY, leftListY + leftListH, () -> {
                selectedQuestIdx = pick;
                editingTargetRowIdx = -1;
                boundContextKey = "";
                CENTER_SCROLL.reset();
                QuestClient.playClickSound(1.03F);
            });
        }
        c.restoreToCount(saveLeft);
        if (LEFT_LIST_SCROLL.canScroll()) {
            LEFT_LIST_SCROLL.drawBar(c, leftListX, leftListY, leftListW, leftListH, mx, my, dt);
        }

        // 2. 中栏：可垂直滚动的任务设计器表单
        Draw.fill(c, midX, y, midW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, midX, y, midW, h, 6.0F, 0.7F, ShopTheme.borderDim());

        if (list.isEmpty()) {
            V2.empty(c, "点击左侧「+ 新建任务」开始创建", midX, y, midW, h);
            return;
        }

        QuestDefSpec q = list.get(selectedQuestIdx);
        if (q.reward == null) q.reward = new RewardBundleSpec();
        if (q.targets == null) q.targets = new ArrayList<>();
        QuestTaskType curType = q.parsedType() != null ? q.parsedType() : QuestTaskType.SHOP_SIGN_IN;
        boolean supportsTargets = supportsTargetFilter(curType);
        QuestTargetCatalog.Kind targetKind = resolveCatalogKind(curType);

        if (supportsTargets && q.targets.isEmpty()) {
            q.targets.add(new TaskTarget(q.targetFilter == null ? "" : q.targetFilter, "", Math.max(1, q.targetCount)));
        }

        int editableTargets = supportsTargets ? Math.min(MAX_TARGET_ROWS, q.targets.size()) : 0;
        String ctx = "tab2:" + editWeeklyPool + ":" + selectedQuestIdx + ":" + q.id + ":" + q.type + ":" + editableTargets;
        if (!ctx.equals(boundContextKey)) {
            boundContextKey = ctx;
            setFieldSilently(0, q.title, "例如：商店签到", v -> { if (!v.isBlank()) q.title = v.trim(); });
            setFieldSilently(1, q.description, "向玩家展示的任务说明", v -> q.description = v.trim());
            setFieldSilently(2, String.valueOf(q.targetCount), "1", v -> {
                q.targetCount = parseIntClamp(v, q.targetCount, 1, 99999);
                if (!q.targets.isEmpty()) {
                    q.targets.get(0).count = q.targetCount;
                    q.syncLegacyFieldsFromTargets();
                }
            });
            setFieldSilently(4, String.valueOf(q.reward.money), "40", v -> q.reward.money = parseLongBlankZero(v, q.reward.money, 0L, 99999999L));
            setFieldSilently(5, String.valueOf(q.reward.points), "40", v -> q.reward.points = parseLongBlankZero(v, q.reward.points, 0L, 999999L));
            for (int tIdx = 0; tIdx < editableTargets; tIdx++) {
                final int row = tIdx;
                TaskTarget tt = q.targets.get(row);
                setFieldSilently(TARGET_ID_BASE + row, tt.target, targetPlaceholderFor(curType), v -> {
                    if (row < q.targets.size()) {
                        TaskTarget cur = q.targets.get(row);
                        cur.target = v.trim();
                        cur.label = "";
                        q.syncLegacyFieldsFromTargets();
                    }
                });
                setFieldSilently(TARGET_CNT_BASE + row, String.valueOf(Math.max(1, tt.count)), "1", v -> {
                    if (row < q.targets.size()) {
                        q.targets.get(row).count = parseIntClamp(v, q.targets.get(row).count, 1, 99999);
                        q.syncLegacyFieldsFromTargets();
                    }
                });
            }
        }

        // 中栏固定顶栏：标题 + 自动填文案 + 删除
        float headH = 24.0F;
        float padX = midX + 10.0F;
        V2.bold(c, "任务配置", 7.2F, ShopTheme.text(), padX, y + 12.0F, 64.0F, Text.Align.LEFT);
        float topBtnRight = midX + midW - 10.0F;
        if (list.size() > 1) {
            float delW = 46.0F;
            topBtnRight -= delW;
            drawChip(c, "删除任务", topBtnRight, y + 4.5F, delW, 15.0F, false, inRect(mx, my, topBtnRight, y + 4.5F, delW, 15.0F));
            hit(topBtnRight, y + 4.5F, delW, 15.0F, () -> {
                list.remove(selectedQuestIdx);
                selectedQuestIdx = Math.max(0, selectedQuestIdx - 1);
                editingTargetRowIdx = -1;
                dirty = true;
                boundContextKey = "";
                QuestClient.playClickSound(0.92F);
            });
            topBtnRight -= 5.0F;
        }
        float autoTextW = 72.0F;
        topBtnRight -= autoTextW;
        drawChip(c, "自动填名称/说明", topBtnRight, y + 4.5F, autoTextW, 15.0F, false,
                inRect(mx, my, topBtnRight, y + 4.5F, autoTextW, 15.0F));
        hit(topBtnRight, y + 4.5F, autoTextW, 15.0F, () -> {
            applySuggestedTitleAndDesc(q, editWeeklyPool);
            updateFieldValueAndModel(0, q.title);
            updateFieldValueAndModel(1, q.description);
            QuestClient.playClickSound(1.06F);
        });
        Draw.hairline(c, midX + 8.0F, y + headH, midW - 16.0F, ShopTheme.borderDim());

        // 中栏滚动主体
        float vpY = y + headH + 3.0F;
        float vpH = Math.max(60.0F, h - headH - 6.0F);
        float innerW = midW - (CENTER_SCROLL.canScroll() ? 24.0F : 20.0F);
        float clipMin = vpY;
        float clipMax = vpY + vpH;
        float offY = CENTER_SCROLL.offset();

        int saveMid = c.save();
        Draw.clip(c, midX + 2.0F, vpY, midW - 4.0F, vpH);
        if (items != null) {
            items.pushClip(midX + 2.0F, vpY, midW - 4.0F, vpH);
        }

        float startY = vpY + 3.0F - offY;
        float cy = startY;

        // 行 1：名称 + 说明
        float nameBoxW = Math.min(118.0F, innerW * 0.35F);
        V2.text(c, "名称", 5.8F, ShopTheme.text2(), padX, cy + 8.5F, 24.0F, Text.Align.LEFT);
        renderFieldClipped(c, used, 0, padX + 24.0F, cy, nameBoxW - 24.0F, 17.0F, clipMin, clipMax, mx, my, dt);

        float descLblX = padX + nameBoxW + 6.0F;
        V2.text(c, "说明", 5.8F, ShopTheme.text2(), descLblX, cy + 8.5F, 24.0F, Text.Align.LEFT);
        renderFieldClipped(c, used, 1, descLblX + 24.0F, cy, innerW - (nameBoxW + 6.0F) - 24.0F, 17.0F, clipMin, clipMax, mx, my, dt);
        cy += 21.0F;

        // 行 2：行为类型矩阵（含新增：提交物品 / 到达群系 / 到达结构）
        V2.bold(c, "任务行为类型（点击切换）", 5.8F, ShopTheme.text2(), padX, cy + 5.0F, innerW, Text.Align.LEFT);
        cy += 11.0F;
        cy = renderActionPillsWrap(c, q, SHOP_ACTIONS, padX, cy, innerW, clipMin, clipMax, mx, my);
        cy = renderActionPillsWrap(c, q, ADDON_ACTIONS, padX, cy, innerW, clipMin, clipMax, mx, my);
        cy = renderActionPillsWrap(c, q, VANILLA_ACTIONS, padX, cy, innerW, clipMin, clipMax, mx, my);
        cy += 3.0F;

        // 行 3：抽中概率（+ 不支持多目标时的单数量框）
        if (!supportsTargets) {
            V2.text(c, "需要次数", 5.8F, ShopTheme.text2(), padX, cy + 8.5F, 42.0F, Text.Align.LEFT);
            drawChip(c, "-", padX + 42.0F, cy + 1.0F, 15.0F, 15.0F, false,
                    my >= clipMin && my < clipMax && inRect(mx, my, padX + 42.0F, cy + 1.0F, 15.0F, 15.0F));
            hitClipped(padX + 42.0F, cy + 1.0F, 15.0F, 15.0F, clipMin, clipMax, () -> {
                q.targetCount = Math.max(1, q.targetCount - 1);
                updateFieldValueAndModel(2, String.valueOf(q.targetCount));
                QuestClient.playClickSound(0.98F);
            });
            renderFieldClipped(c, used, 2, padX + 59.0F, cy, 40.0F, 17.0F, clipMin, clipMax, mx, my, dt);
            drawChip(c, "+", padX + 101.0F, cy + 1.0F, 15.0F, 15.0F, false,
                    my >= clipMin && my < clipMax && inRect(mx, my, padX + 101.0F, cy + 1.0F, 15.0F, 15.0F));
            hitClipped(padX + 101.0F, cy + 1.0F, 15.0F, 15.0F, clipMin, clipMax, () -> {
                q.targetCount++;
                updateFieldValueAndModel(2, String.valueOf(q.targetCount));
                QuestClient.playClickSound(1.04F);
            });
            renderWeightRow(c, q, padX + 124.0F, cy, Math.max(100.0F, innerW - 124.0F), clipMin, clipMax, mx, my);
            cy += 21.0F;
        } else {
            renderWeightRow(c, q, padX, cy, innerW, clipMin, clipMax, mx, my);
            cy += 20.0F;

            // 行 4：多子目标列表编辑器（如 怪1[10] + 怪2[5]，可点右侧可视化搜索选择，也可手输 ID/#tag）
            Draw.hairline(c, padX, cy, innerW, ShopTheme.borderDim());
            cy += 5.0F;

            String targetSecTitle = "指定目标列表（共 " + q.targets.size() + " 项 · 全部达成完成）";
            V2.bold(c, targetSecTitle, 6.0F, ShopTheme.accent(), padX, cy + 7.5F, innerW - 110.0F, Text.Align.LEFT);

            float addTargetW = 68.0F;
            float addTargetX = padX + innerW - addTargetW;
            boolean canAddMore = q.targets.size() < MAX_TARGET_ROWS;
            drawChip(c, "+ 添加目标", addTargetX, cy, addTargetW, 15.0F, dockTargetMode && editingTargetRowIdx == -1,
                    canAddMore && my >= clipMin && my < clipMax && inRect(mx, my, addTargetX, cy, addTargetW, 15.0F));
            if (canAddMore) {
                hitClipped(addTargetX, cy, addTargetW, 15.0F, clipMin, clipMax, () -> {
                    q.targets.add(new TaskTarget("", "", 1));
                    q.syncLegacyFieldsFromTargets();
                    editingTargetRowIdx = q.targets.size() - 1;
                    dockTargetMode = true;
                    dirty = true;
                    boundContextKey = "";
                    QuestClient.playClickSound(1.08F);
                });
            }
            cy += 18.0F;

            // 快捷预设胶囊（作用于当前选中目标行或第 1 行）
            String[][] presets = filterPresetsFor(curType);
            if (presets.length > 0) {
                float fx = padX;
                for (String[] pr : presets) {
                    float pw = V2.width(pr[0], 5.2F, true) + 9.0F;
                    if (fx + pw > padX + innerW && fx > padX) {
                        fx = padX;
                        cy += 16.0F;
                    }
                    int activeRow = (editingTargetRowIdx >= 0 && editingTargetRowIdx < q.targets.size()) ? editingTargetRowIdx : 0;
                    boolean act = activeRow < q.targets.size() && pr[1].equals(q.targets.get(activeRow).target);
                    drawChip(c, pr[0], fx, cy, pw, 14.0F, act,
                            my >= clipMin && my < clipMax && inRect(mx, my, fx, cy, pw, 14.0F));
                    final String pVal = pr[1];
                    final String pLbl = pr[0];
                    hitClipped(fx, cy, pw, 14.0F, clipMin, clipMax, () -> {
                        int idx = (editingTargetRowIdx >= 0 && editingTargetRowIdx < q.targets.size()) ? editingTargetRowIdx : 0;
                        if (q.targets.isEmpty()) q.targets.add(new TaskTarget(pVal, pLbl, 1));
                        else {
                            q.targets.get(idx).target = pVal;
                            q.targets.get(idx).label = pVal.isEmpty() ? "" : pLbl;
                        }
                        q.syncLegacyFieldsFromTargets();
                        updateFieldValueAndModel(TARGET_ID_BASE + idx, pVal);
                        QuestClient.playClickSound(1.03F);
                    });
                    fx += pw + 3.0F;
                }
                cy += 17.0F;
            }

            // 逐行渲染多子目标卡片
            for (int tIdx = 0; tIdx < editableTargets; tIdx++) {
                TaskTarget tt = q.targets.get(tIdx);
                float rowH = 26.0F;
                boolean activeRow = dockTargetMode && editingTargetRowIdx == tIdx;

                Draw.fill(c, padX, cy, innerW, rowH, 4.5F,
                        activeRow ? Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.24F : 0.13F)
                                : Draw.alpha(ShopTheme.text3(), 0.06F));
                Draw.stroke(c, padX, cy, innerW, rowH, 4.5F, activeRow ? 1.0F : 0.6F,
                        activeRow ? ShopTheme.accent() : ShopTheme.borderDim());

                ItemStack icon = QuestTargetCatalog.resolveIcon(targetKind, tt.target);
                if (items != null && !icon.isEmpty() && cy + rowH > clipMin && cy < clipMax) {
                    items.add(icon, padX + 4.0F, cy + 5.0F, 16.0F, false);
                    if (my >= clipMin && my < clipMax && inRect(mx, my, padX + 2.0F, cy + 2.0F, 20.0F, rowH - 4.0F)) {
                        String disp = QuestTargetCatalog.resolveDisplayName(targetKind, tt.target,
                                QuestClientState.syncedBiomes(), QuestClientState.syncedStructures(),
                                QuestClientState.worldRegistryRevision());
                        String tip = disp + (tt.target.isBlank() ? "" : (" (" + tt.target + ")"));
                        f.host().textTooltip(Component.literal(tip));
                    }
                }

                // 右侧控件布局：[选] [-] [数量框] [+] [删]
                float right = padX + innerW - 4.0F;
                final int row = tIdx;

                // [×] 删除或清空
                float delW = 16.0F;
                right -= delW;
                drawChip(c, "×", right, cy + 5.0F, delW, 16.0F, false,
                        my >= clipMin && my < clipMax && inRect(mx, my, right, cy + 5.0F, delW, 16.0F));
                hitClipped(right, cy + 5.0F, delW, 16.0F, clipMin, clipMax, () -> {
                    if (q.targets.size() > 1) {
                        q.targets.remove(row);
                        if (editingTargetRowIdx >= q.targets.size()) editingTargetRowIdx = q.targets.size() - 1;
                    } else {
                        q.targets.get(0).target = "";
                        q.targets.get(0).label = "";
                        q.targets.get(0).count = 1;
                    }
                    q.syncLegacyFieldsFromTargets();
                    dirty = true;
                    boundContextKey = "";
                    QuestClient.playClickSound(0.92F);
                });
                right -= 3.0F;

                // [+]
                float pmW = 14.0F;
                right -= pmW;
                drawChip(c, "+", right, cy + 5.0F, pmW, 16.0F, false,
                        my >= clipMin && my < clipMax && inRect(mx, my, right, cy + 5.0F, pmW, 16.0F));
                hitClipped(right, cy + 5.0F, pmW, 16.0F, clipMin, clipMax, () -> {
                    if (row < q.targets.size()) {
                        q.targets.get(row).count = Math.min(99999, q.targets.get(row).count + 1);
                        q.syncLegacyFieldsFromTargets();
                        updateFieldValueAndModel(TARGET_CNT_BASE + row, String.valueOf(q.targets.get(row).count));
                        QuestClient.playClickSound(1.04F);
                    }
                });
                right -= 2.0F;

                // 数量输入框
                float cntW = 32.0F;
                right -= cntW;
                renderFieldClipped(c, used, TARGET_CNT_BASE + row, right, cy + 4.5F, cntW, 17.0F, clipMin, clipMax, mx, my, dt);
                right -= 2.0F;

                // [-]
                right -= pmW;
                drawChip(c, "-", right, cy + 5.0F, pmW, 16.0F, false,
                        my >= clipMin && my < clipMax && inRect(mx, my, right, cy + 5.0F, pmW, 16.0F));
                hitClipped(right, cy + 5.0F, pmW, 16.0F, clipMin, clipMax, () -> {
                    if (row < q.targets.size()) {
                        q.targets.get(row).count = Math.max(1, q.targets.get(row).count - 1);
                        q.syncLegacyFieldsFromTargets();
                        updateFieldValueAndModel(TARGET_CNT_BASE + row, String.valueOf(q.targets.get(row).count));
                        QuestClient.playClickSound(0.98F);
                    }
                });
                right -= 4.0F;

                // [选目标] 切换右侧面板为选择目标并指向本行
                float pickW = 28.0F;
                right -= pickW;
                drawChip(c, "选目标", right, cy + 5.0F, pickW, 16.0F, activeRow,
                        my >= clipMin && my < clipMax && inRect(mx, my, right, cy + 5.0F, pickW, 16.0F));
                hitClipped(right, cy + 5.0F, pickW, 16.0F, clipMin, clipMax, () -> {
                    dockTargetMode = true;
                    editingTargetRowIdx = row;
                    PICKER_SCROLL.reset();
                    QuestClient.playClickSound(1.06F);
                });
                right -= 4.0F;

                // 左侧目标 ID 框 + 译名标签
                float idX = padX + 23.0F;
                float idW = Math.max(46.0F, right - idX);
                renderFieldClipped(c, used, TARGET_ID_BASE + row, idX, cy + 4.5F, idW, 17.0F, clipMin, clipMax, mx, my, dt);

                cy += rowH + 3.0F;
            }
        }

        Draw.hairline(c, padX, cy + 2.0F, innerW, ShopTheme.borderDim());
        cy += 6.0F;

        // 行 5：奖励星币 + 奖励积分
        float halfRw = (innerW - 10.0F) / 2.0F;
        V2.bold(c, "奖励星币", 5.8F, ShopTheme.text2(), padX, cy + 8.5F, 42.0F, Text.Align.LEFT);
        renderFieldClipped(c, used, 4, padX + 42.0F, cy, Math.max(36.0F, halfRw - 74.0F), 17.0F, clipMin, clipMax, mx, my, dt);
        drawChip(c, "+20", padX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F, false,
                my >= clipMin && my < clipMax && inRect(mx, my, padX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F));
        hitClipped(padX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F, clipMin, clipMax, () -> {
            q.reward.money += 20L;
            updateFieldValueAndModel(4, String.valueOf(q.reward.money));
            QuestClient.playClickSound(1.04F);
        });

        float ptX = padX + halfRw + 10.0F;
        V2.bold(c, "奖励积分", 5.8F, ShopTheme.text2(), ptX, cy + 8.5F, 42.0F, Text.Align.LEFT);
        renderFieldClipped(c, used, 5, ptX + 42.0F, cy, Math.max(36.0F, halfRw - 74.0F), 17.0F, clipMin, clipMax, mx, my, dt);
        drawChip(c, "+20", ptX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F, false,
                my >= clipMin && my < clipMax && inRect(mx, my, ptX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F));
        hitClipped(ptX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F, clipMin, clipMax, () -> {
            q.reward.points += 20L;
            updateFieldValueAndModel(5, String.valueOf(q.reward.points));
            QuestClient.playClickSound(1.04F);
        });
        cy += 22.0F;

        // 行 6：奖励物品卡片区（点击标头切回右侧「添加奖励模式」）
        int rItemsCnt = q.reward.items == null ? 0 : q.reward.items.size();
        V2.bold(c, "任务额外物品奖励（共 " + rItemsCnt + " 项）", 5.8F, ShopTheme.text2(),
                padX, cy + 7.5F, innerW - 76.0F, Text.Align.LEFT);
        drawChip(c, "+ 添加奖励", padX + innerW - 68.0F, cy, 68.0F, 15.0F, !dockTargetMode,
                my >= clipMin && my < clipMax && inRect(mx, my, padX + innerW - 68.0F, cy, 68.0F, 15.0F));
        hitClipped(padX + innerW - 68.0F, cy, 68.0F, 15.0F, clipMin, clipMax, () -> {
            dockTargetMode = false;
            PICKER_SCROLL.reset();
            QuestClient.playClickSound(1.04F);
        });
        cy += 18.0F;

        cy = renderInlineRichItemCards(c, items, q.reward, () -> q.reward,
                padX, cy, innerW, clipMin, clipMax, mx, my);
        cy += 6.0F;

        float totalCenterH = cy - startY;
        CENTER_SCROLL.setContent(totalCenterH, vpH);
        scrollRegions.add(new ScrollRegion(CENTER_SCROLL, midX, vpY, midW, vpH, false));

        if (items != null) {
            items.popClip();
        }
        c.restoreToCount(saveMid);
        if (CENTER_SCROLL.canScroll()) {
            CENTER_SCROLL.drawBar(c, midX, vpY, midW, vpH, mx, my, dt);
        }

        // 3. 右栏：双模式切换（[添加奖励] vs [选择任务目标]）
        renderQuestDualModeRightDock(c, f, items, used, q, curType, supportsTargets, targetKind,
                dockX, y, dockW, h, mx, my, dt);
    }

    private void renderWeightRow(Canvas c, QuestDefSpec q, float x, float y, float w,
                                 float clipMin, float clipMax, float mx, float my) {
        V2.text(c, "抽中概率", 5.8F, ShopTheme.text2(), x, y + 8.5F, 42.0F, Text.Align.LEFT);
        String[] wLabels = {"少见", "普通", "常见", "高频"};
        int[] wVals = {6, 14, 22, 35};
        float wx = x + 42.0F;
        float remW = Math.max(80.0F, w - 42.0F);
        float ww = Math.min(38.0F, (remW - 9.0F) / 4.0F);
        for (int i = 0; i < wLabels.length; i++) {
            boolean activeW = isWeightBucket(q.weight, i);
            drawChip(c, wLabels[i], wx, y + 1.0F, ww, 15.0F, activeW,
                    my >= clipMin && my < clipMax && inRect(mx, my, wx, y + 1.0F, ww, 15.0F));
            final int wSet = wVals[i];
            hitClipped(wx, y + 1.0F, ww, 15.0F, clipMin, clipMax, () -> {
                q.weight = wSet;
                dirty = true;
                QuestClient.playClickSound(1.03F);
            });
            wx += ww + 3.0F;
        }
    }

    private float renderActionPillsWrap(Canvas c, QuestDefSpec q, QuestTaskType[] types,
                                        float x, float startY, float maxW,
                                        float clipMin, float clipMax, float mx, float my) {
        float cx = x;
        float cy = startY;
        QuestTaskType active = q.parsedType();
        for (QuestTaskType t : types) {
            String lbl = shortTaskLabel(t);
            float pw = V2.width(lbl, 5.2F, true) + 9.0F;
            if (cx + pw > x + maxW && cx > x) {
                cx = x;
                cy += 16.0F;
            }
            boolean isSel = t == active;
            drawChip(c, lbl, cx, cy, pw, 14.0F, isSel,
                    my >= clipMin && my < clipMax && inRect(mx, my, cx, cy, pw, 14.0F));
            hitClipped(cx, cy, pw, 14.0F, clipMin, clipMax, () -> {
                setQuestTaskType(q, t);
                if (supportsTargetFilter(t)) {
                    if (q.targets == null) q.targets = new ArrayList<>();
                    if (q.targets.isEmpty()) q.targets.add(new TaskTarget("", "", Math.max(1, q.targetCount)));
                    dockTargetMode = true;
                    editingTargetRowIdx = 0;
                } else {
                    if (q.targets != null) q.targets.clear();
                    q.targetFilter = "";
                    dockTargetMode = false;
                }
                q.syncLegacyFieldsFromTargets();
                dirty = true;
                boundContextKey = "";
                PICKER_SCROLL.reset();
                QuestClient.playClickSound(1.04F);
            });
            cx += pw + 3.0F;
        }
        return cy + 16.5F;
    }

    // ================================================================ 4. 【在线时长奖励】双栏可滚动工作台

    private void renderOnlineDesignerStudio(Canvas c, AddonFrame f, ItemOverlay items, boolean[] used,
                                            float x, float y, float w, float h,
                                            float dockX, float dockW, float mx, float my, float dt) {
        OnlineRewardsConfig online = QuestClientState.editOnline();
        List<OnlineMilestoneSpec> list = editWeeklyOnline ? online.weeklyMilestones : online.dailyMilestones;
        if (selectedOnlineIdx >= list.size()) {
            selectedOnlineIdx = Math.max(0, list.size() - 1);
        }

        float leftW = clamp(w * 0.30F, 134.0F, 174.0F);
        float midX = x + leftW + 8.0F;
        float midW = w - leftW - 8.0F;

        Draw.fill(c, x, y, leftW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, x, y, leftW, h, 6.0F, 0.7F, ShopTheme.borderDim());

        float halfW = (leftW - 14.0F) / 2.0F;
        drawChip(c, "每日 (" + online.dailyMilestones.size() + ")", x + 5.0F, y + 5.0F, halfW, 16.0F, !editWeeklyOnline,
                inRect(mx, my, x + 5.0F, y + 5.0F, halfW, 16.0F));
        hit(x + 5.0F, y + 5.0F, halfW, 16.0F, () -> {
            editWeeklyOnline = false;
            selectedOnlineIdx = 0;
            boundContextKey = "";
            ONLINE_LEFT_SCROLL.reset();
            ONLINE_CENTER_SCROLL.reset();
        });

        drawChip(c, "每周 (" + online.weeklyMilestones.size() + ")", x + 9.0F + halfW, y + 5.0F, halfW, 16.0F, editWeeklyOnline,
                inRect(mx, my, x + 9.0F + halfW, y + 5.0F, halfW, 16.0F));
        hit(x + 9.0F + halfW, y + 5.0F, halfW, 16.0F, () -> {
            editWeeklyOnline = true;
            selectedOnlineIdx = 0;
            boundContextKey = "";
            ONLINE_LEFT_SCROLL.reset();
            ONLINE_CENTER_SCROLL.reset();
        });

        V2.button(c, "+ 新增时长档位", V2.Btn.NORMAL, x + 5.0F, y + 24.0F, leftW - 10.0F, 16.0F,
                inRect(mx, my, x + 5.0F, y + 24.0F, leftW - 10.0F, 16.0F) ? 1.0F : 0.0F);
        hit(x + 5.0F, y + 24.0F, leftW - 10.0F, 16.0F, () -> {
            int nextMin = list.isEmpty() ? 15 : (list.get(list.size() - 1).requiredMinutes + (editWeeklyOnline ? 60 : 15));
            String id = (editWeeklyOnline ? "week_" : "day_") + nextMin + "m_" + (System.currentTimeMillis() % 10000L);
            String title = (editWeeklyOnline ? "本周在线 " : "今日在线 ") + nextMin + " 分钟";
            list.add(new OnlineMilestoneSpec(id, title, nextMin, new RewardBundleSpec(60L, 40L, List.of())));
            selectedOnlineIdx = list.size() - 1;
            dirty = true;
            boundContextKey = "";
            ONLINE_CENTER_SCROLL.reset();
            QuestClient.playClickSound(1.08F);
        });

        // 左侧档位列表（补齐滚动与裁剪，支持任意数量档位！）
        float oLeftX = x + 5.0F;
        float oLeftY = y + 44.0F;
        float oLeftW = leftW - 10.0F;
        float oLeftH = Math.max(40.0F, h - 48.0F);
        float rowStep = 31.0F;
        ONLINE_LEFT_SCROLL.setContent(list.size() * rowStep, oLeftH);
        scrollRegions.add(new ScrollRegion(ONLINE_LEFT_SCROLL, oLeftX, oLeftY, oLeftW, oLeftH, false));

        int saveOL = c.save();
        Draw.clip(c, oLeftX, oLeftY, oLeftW, oLeftH);
        float cardW = oLeftW - (ONLINE_LEFT_SCROLL.canScroll() ? 6.0F : 0.0F);
        for (int i = 0; i < list.size(); i++) {
            float ry = oLeftY + i * rowStep - ONLINE_LEFT_SCROLL.offset();
            if (ry + 28.0F < oLeftY || ry > oLeftY + oLeftH) continue;
            OnlineMilestoneSpec m = list.get(i);
            boolean sel = i == selectedOnlineIdx;
            boolean over = my >= oLeftY && my < oLeftY + oLeftH && inRect(mx, my, oLeftX, ry, cardW, 28.0F);
            Draw.fill(c, oLeftX, ry, cardW, 28.0F, 4.0F,
                    sel ? Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.30F : 0.16F)
                            : (over ? Draw.alpha(ShopTheme.accent(), 0.08F) : Draw.alpha(ShopTheme.text3(), 0.06F)));
            Draw.stroke(c, oLeftX, ry, cardW, 28.0F, 4.0F, sel ? 0.9F : 0.5F,
                    sel ? ShopTheme.accent() : ShopTheme.borderDim());
            V2.bold(c, formatHumanDuration(m.requiredMinutes) + " · " + m.title, 6.0F,
                    sel ? ShopTheme.accent() : ShopTheme.text(), oLeftX + 6.0F, ry + 9.0F, cardW - 12.0F, Text.Align.LEFT);
            long rm = m.reward != null ? m.reward.money : 0L;
            long rp = m.reward != null ? m.reward.points : 0L;
            V2.text(c, "+" + rm + " 星币 · +" + rp + " 积分", 5.1F, ShopTheme.text3(),
                    oLeftX + 6.0F, ry + 19.5F, cardW - 12.0F, Text.Align.LEFT);
            final int pick = i;
            hitClipped(oLeftX, ry, cardW, 28.0F, oLeftY, oLeftY + oLeftH, () -> {
                selectedOnlineIdx = pick;
                boundContextKey = "";
                ONLINE_CENTER_SCROLL.reset();
                QuestClient.playClickSound(1.03F);
            });
        }
        c.restoreToCount(saveOL);
        if (ONLINE_LEFT_SCROLL.canScroll()) {
            ONLINE_LEFT_SCROLL.drawBar(c, oLeftX, oLeftY, oLeftW, oLeftH, mx, my, dt);
        }

        // 中栏：可垂直滚动的在线档位表单
        Draw.fill(c, midX, y, midW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, midX, y, midW, h, 6.0F, 0.7F, ShopTheme.borderDim());
        if (list.isEmpty()) {
            return;
        }

        OnlineMilestoneSpec m = list.get(selectedOnlineIdx);
        if (m.reward == null) m.reward = new RewardBundleSpec();

        String ctx = "tab3:" + editWeeklyOnline + ":" + selectedOnlineIdx + ":" + m.id;
        if (!ctx.equals(boundContextKey)) {
            boundContextKey = ctx;
            setFieldSilently(0, m.title, "今日在线 15 分钟", v -> { if (!v.isBlank()) m.title = v.trim(); });
            setFieldSilently(1, String.valueOf(m.requiredMinutes), "15", v -> m.requiredMinutes = parseIntClamp(v, m.requiredMinutes, 1, 10080));
            setFieldSilently(2, String.valueOf(m.reward.money), "40", v -> m.reward.money = parseLongBlankZero(v, m.reward.money, 0L, 99999999L));
            setFieldSilently(3, String.valueOf(m.reward.points), "35", v -> m.reward.points = parseLongBlankZero(v, m.reward.points, 0L, 999999L));
        }

        float padX = midX + 10.0F;
        float headH = 24.0F;
        V2.bold(c, "在线时长档位（" + formatHumanDuration(m.requiredMinutes) + "）",
                7.0F, ShopTheme.text(), padX, y + 12.0F, midW - 74.0F, Text.Align.LEFT);
        if (list.size() > 1) {
            float delX = midX + midW - 56.0F;
            drawChip(c, "删除档位", delX, y + 4.5F, 46.0F, 15.0F, false, inRect(mx, my, delX, y + 4.5F, 46.0F, 15.0F));
            hit(delX, y + 4.5F, 46.0F, 15.0F, () -> {
                list.remove(selectedOnlineIdx);
                selectedOnlineIdx = Math.max(0, selectedOnlineIdx - 1);
                dirty = true;
                boundContextKey = "";
                QuestClient.playClickSound(0.92F);
            });
        }
        Draw.hairline(c, midX + 8.0F, y + headH, midW - 16.0F, ShopTheme.borderDim());

        float vpY = y + headH + 3.0F;
        float vpH = Math.max(50.0F, h - headH - 6.0F);
        float innerW = midW - (ONLINE_CENTER_SCROLL.canScroll() ? 24.0F : 20.0F);
        float clipMin = vpY;
        float clipMax = vpY + vpH;

        int saveOC = c.save();
        Draw.clip(c, midX + 2.0F, vpY, midW - 4.0F, vpH);
        if (items != null) {
            items.pushClip(midX + 2.0F, vpY, midW - 4.0F, vpH);
        }

        float startY = vpY + 3.0F - ONLINE_CENTER_SCROLL.offset();
        float cy = startY;

        renderDirectInputRow(c, used, "档位显示名称", 0, "", padX, cy, innerW, clipMin, clipMax, mx, my, dt, null, null);
        cy += 23.0F;

        renderDirectInputRow(c, used, "需要在线时长", 1, "分钟", padX, cy, innerW, clipMin, clipMax, mx, my, dt,
                () -> {
                    m.requiredMinutes = Math.max(1, m.requiredMinutes - (editWeeklyOnline ? 30 : 5));
                    m.title = (editWeeklyOnline ? "本周在线 " : "今日在线 ") + m.requiredMinutes + " 分钟";
                    updateFieldValueAndModel(1, String.valueOf(m.requiredMinutes));
                    updateFieldValueAndModel(0, m.title);
                },
                () -> {
                    m.requiredMinutes += (editWeeklyOnline ? 30 : 5);
                    m.title = (editWeeklyOnline ? "本周在线 " : "今日在线 ") + m.requiredMinutes + " 分钟";
                    updateFieldValueAndModel(1, String.valueOf(m.requiredMinutes));
                    updateFieldValueAndModel(0, m.title);
                });
        cy += 23.0F;

        renderDirectInputRow(c, used, "奖励星币数量", 2, "星币", padX, cy, innerW, clipMin, clipMax, mx, my, dt,
                () -> { m.reward.money = Math.max(0L, m.reward.money - 20L); updateFieldValueAndModel(2, String.valueOf(m.reward.money)); },
                () -> { m.reward.money += 20L; updateFieldValueAndModel(2, String.valueOf(m.reward.money)); });
        cy += 23.0F;

        renderDirectInputRow(c, used, "奖励通行证积分", 3, "分", padX, cy, innerW, clipMin, clipMax, mx, my, dt,
                () -> { m.reward.points = Math.max(0L, m.reward.points - 15L); updateFieldValueAndModel(3, String.valueOf(m.reward.points)); },
                () -> { m.reward.points += 15L; updateFieldValueAndModel(3, String.valueOf(m.reward.points)); });
        cy += 24.0F;

        int oItems = m.reward.items == null ? 0 : m.reward.items.size();
        V2.bold(c, "档位奖励物品（共 " + oItems + " 项 · 右侧点击添加）：",
                5.8F, ShopTheme.text2(), padX, cy + 5.0F, innerW, Text.Align.LEFT);
        cy += 14.0F;

        cy = renderInlineRichItemCards(c, items, m.reward, () -> m.reward, padX, cy, innerW, clipMin, clipMax, mx, my);
        cy += 6.0F;

        ONLINE_CENTER_SCROLL.setContent(cy - startY, vpH);
        scrollRegions.add(new ScrollRegion(ONLINE_CENTER_SCROLL, midX, vpY, midW, vpH, false));

        if (items != null) {
            items.popClip();
        }
        c.restoreToCount(saveOC);
        if (ONLINE_CENTER_SCROLL.canScroll()) {
            ONLINE_CENTER_SCROLL.drawBar(c, midX, vpY, midW, vpH, mx, my, dt);
        }

        renderRightItemRewardDock(c, f, items, used, "添加至：" + m.title, spec -> {
            appendOrMergeItem(m.reward, spec);
            dirty = true;
        }, dockX, y, dockW, h, mx, my, dt);
    }

    // ================================================================ 右侧双模式选择器（奖励物品库 / 任务目标类 JEI 选择器）

    private void renderQuestDualModeRightDock(Canvas c, AddonFrame f, ItemOverlay items, boolean[] used,
                                              QuestDefSpec q, QuestTaskType curType,
                                              boolean supportsTargets, QuestTargetCatalog.Kind targetKind,
                                              float x, float y, float w, float h,
                                              float mx, float my, float dt) {
        if (!supportsTargets) {
            dockTargetMode = false;
        }
        Draw.fill(c, x, y, w, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, x, y, w, h, 6.0F, 0.7F, ShopTheme.borderDim());

        float pad = 7.0F;
        float ix = x + pad;
        float iw = w - pad * 2.0F;

        // 顶部双模式切换栏：[ 添加奖励 ] vs [ 选择任务目标 ]
        float modeY = y + 6.0F;
        if (supportsTargets) {
            float halfW = (iw - 4.0F) / 2.0F;
            drawChip(c, "添加奖励物品", ix, modeY, halfW, 15.0F, !dockTargetMode,
                    inRect(mx, my, ix, modeY, halfW, 15.0F));
            hit(ix, modeY, halfW, 15.0F, () -> {
                dockTargetMode = false;
                PICKER_SCROLL.reset();
                QuestClient.playClickSound(1.03F);
            });
            drawChip(c, "选择任务目标", ix + halfW + 4.0F, modeY, halfW, 15.0F, dockTargetMode,
                    inRect(mx, my, ix + halfW + 4.0F, modeY, halfW, 15.0F));
            hit(ix + halfW + 4.0F, modeY, halfW, 15.0F, () -> {
                dockTargetMode = true;
                if (editingTargetRowIdx < 0 && !q.targets.isEmpty()) editingTargetRowIdx = 0;
                PICKER_SCROLL.reset();
                QuestClient.playClickSound(1.03F);
            });
        } else {
            Draw.fill(c, ix, modeY, iw, 15.0F, 3.5F, Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.22F : 0.12F));
            V2.bold(c, "添加至：" + q.title, 5.8F, ShopTheme.accent(), ix + 4.0F, modeY + 7.5F, iw - 8.0F, Text.Align.CENTER);
        }

        if (!dockTargetMode) {
            renderRightItemRewardDockBody(c, f, items, used, "奖励 → " + q.title, spec -> {
                appendOrMergeItem(q.reward, spec);
                dirty = true;
            }, ix, modeY + 18.0F, iw, y + h - (modeY + 18.0F) - 5.0F, mx, my, dt);
        } else {
            renderRightTargetPickerBody(c, f, items, used, q, curType, targetKind,
                    ix, modeY + 18.0F, iw, y + h - (modeY + 18.0F) - 5.0F, mx, my, dt);
        }
    }

    private void renderRightItemRewardDock(Canvas c, AddonFrame f, ItemOverlay items, boolean[] used,
                                           String targetHeader, Consumer<ItemRewardSpec> onAdd,
                                           float x, float y, float w, float h, float mx, float my, float dt) {
        Draw.fill(c, x, y, w, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, x, y, w, h, 6.0F, 0.7F, ShopTheme.borderDim());
        float pad = 7.0F;
        renderRightItemRewardDockBody(c, f, items, used, targetHeader, onAdd,
                x + pad, y + 6.0F, w - pad * 2.0F, h - 11.0F, mx, my, dt);
    }

    private void renderRightItemRewardDockBody(Canvas c, AddonFrame f, ItemOverlay items, boolean[] used,
                                               String targetHeader, Consumer<ItemRewardSpec> onAdd,
                                               float ix, float topY, float iw, float availH,
                                               float mx, float my, float dt) {
        Draw.fill(c, ix, topY, iw, 14.0F, 3.5F, Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.22F : 0.12F));
        V2.bold(c, targetHeader, 5.6F, ShopTheme.accent(), ix + 4.0F, topY + 7.0F, iw - 8.0F, Text.Align.CENTER);

        float swY = topY + 17.0F;
        float halfW = (iw - 4.0F) / 2.0F;
        drawChip(c, "全物品库", ix, swY, halfW, 15.0F, pickerCatalogMode, inRect(mx, my, ix, swY, halfW, 15.0F));
        hit(ix, swY, halfW, 15.0F, () -> {
            pickerCatalogMode = true;
            PICKER_SCROLL.reset();
            QuestClient.playClickSound(1.03F);
        });
        drawChip(c, "我的背包(含附魔)", ix + halfW + 4.0F, swY, halfW, 15.0F, !pickerCatalogMode,
                inRect(mx, my, ix + halfW + 4.0F, swY, halfW, 15.0F));
        hit(ix + halfW + 4.0F, swY, halfW, 15.0F, () -> {
            pickerCatalogMode = false;
            PICKER_SCROLL.reset();
            QuestClient.playClickSound(1.03F);
        });

        float gridTop = swY + 19.0F;
        var mc = Minecraft.getInstance();
        HolderLookup.Provider regs = mc.level != null ? mc.level.registryAccess() : null;

        float batchBarH = 20.0F;
        float batchY = topY + availH - batchBarH;

        if (pickerCatalogMode) {
            fields[SEARCH_IDX].placeholder("搜索物品名 / ID / @modid ...");
            renderFieldClipped(c, used, SEARCH_IDX, ix, gridTop, iw, 17.0F, topY, topY + availH, mx, my, dt);
            gridTop += 20.0F;

            List<QuestTargetCatalog.Entry> entries = QuestTargetCatalog.search(
                    QuestTargetCatalog.Kind.ITEM, fields[SEARCH_IDX].value(), false,
                    QuestClientState.syncedBiomes(), QuestClientState.syncedStructures(),
                    QuestClientState.worldRegistryRevision());

            V2.text(c, "匹配 " + entries.size() + " 项", 4.9F, ShopTheme.text3(), ix, gridTop + 4.0F, iw, Text.Align.RIGHT);
            gridTop += 10.0F;

            // 底部数量胶囊
            V2.text(c, "每次：", 5.3F, ShopTheme.text2(), ix, batchY + 10.0F, 30.0F, Text.Align.LEFT);
            int[] batches = {1, 4, 16, 64};
            float bx = ix + 28.0F;
            float bw = (iw - 28.0F - 9.0F) / 4.0F;
            for (int b : batches) {
                drawChip(c, "+" + b, bx, batchY + 2.5F, bw, 15.0F, pickerBatchCount == b,
                        inRect(mx, my, bx, batchY + 2.5F, bw, 15.0F));
                final int setB = b;
                hit(bx, batchY + 2.5F, bw, 15.0F, () -> {
                    pickerBatchCount = setB;
                    QuestClient.playClickSound(1.04F);
                });
                bx += bw + 3.0F;
            }

            float gridH = Math.max(36.0F, batchY - gridTop - 3.0F);
            renderVirtualIconGrid(c, f, items, entries, ix, gridTop, iw, gridH, mx, my, dt, entry -> {
                ItemStack picked = entry.icon().copy();
                ItemRewardSpec spec = QuestDataConfig.fromItemStack(picked, regs);
                spec.count = Math.min(Math.max(1, picked.getMaxStackSize()), pickerBatchCount);
                onAdd.accept(spec);
                QuestClient.playClickSound(1.15F);
            });
        } else {
            V2.text(c, "点击背包物品将按原数量与附魔加入", 5.2F, ShopTheme.text3(), ix, batchY + 10.0F, iw, Text.Align.CENTER);
            List<ItemStack> invStacks = collectPlayerInventoryStacks();
            float gridH = Math.max(36.0F, batchY - gridTop - 3.0F);
            renderPlayerInventoryGrid(c, items, invStacks, ix, gridTop, iw, gridH, mx, my, dt, picked -> {
                ItemRewardSpec spec = QuestDataConfig.fromItemStack(picked, regs);
                onAdd.accept(spec);
                QuestClient.playClickSound(1.15F);
            });
        }
    }

    private void renderRightTargetPickerBody(Canvas c, AddonFrame f, ItemOverlay items, boolean[] used,
                                             QuestDefSpec q, QuestTaskType curType,
                                             QuestTargetCatalog.Kind defaultKind,
                                             float ix, float topY, float iw, float availH,
                                             float mx, float my, float dt) {
        QuestTargetCatalog.Kind activeKind = "any".equals(curType.targetKind()) ? manualTargetKind : defaultKind;
        String targetSlotDesc = (editingTargetRowIdx >= 0 && editingTargetRowIdx < q.targets.size())
                ? ("点选填入 → 目标 #" + (editingTargetRowIdx + 1))
                : "点选 → 追加新子目标";
        Draw.fill(c, ix, topY, iw, 14.0F, 3.5F, Draw.alpha(ShopTheme.green(), ShopTheme.dark() ? 0.24F : 0.14F));
        V2.bold(c, targetSlotDesc, 5.5F, ShopTheme.green(), ix + 4.0F, topY + 7.0F, iw - 8.0F, Text.Align.CENTER);

        float cy = topY + 17.0F;

        // 类别/筛选小栏
        if ("any".equals(curType.targetKind())) {
            QuestTargetCatalog.Kind[] kinds = {
                    QuestTargetCatalog.Kind.ITEM, QuestTargetCatalog.Kind.BLOCK,
                    QuestTargetCatalog.Kind.ENTITY, QuestTargetCatalog.Kind.BIOME, QuestTargetCatalog.Kind.STRUCTURE
            };
            String[] kNames = {"物品", "方块", "生物", "群系", "结构"};
            float kw = (iw - 12.0F) / 5.0F;
            for (int i = 0; i < kinds.length; i++) {
                float kx = ix + i * (kw + 3.0F);
                drawChip(c, kNames[i], kx, cy, kw, 14.0F, manualTargetKind == kinds[i],
                        inRect(mx, my, kx, cy, kw, 14.0F));
                final QuestTargetCatalog.Kind setK = kinds[i];
                hit(kx, cy, kw, 14.0F, () -> {
                    manualTargetKind = setK;
                    PICKER_SCROLL.reset();
                    QuestClient.playClickSound(1.03F);
                });
            }
            cy += 17.0F;
        } else if (activeKind == QuestTargetCatalog.Kind.ENTITY) {
            float halfW = (iw - 4.0F) / 2.0F;
            drawChip(c, "生物/怪物", ix, cy, halfW, 14.0F, entityOnlyMobs, inRect(mx, my, ix, cy, halfW, 14.0F));
            hit(ix, cy, halfW, 14.0F, () -> { entityOnlyMobs = true; PICKER_SCROLL.reset(); QuestClient.playClickSound(1.03F); });
            drawChip(c, "全部实体", ix + halfW + 4.0F, cy, halfW, 14.0F, !entityOnlyMobs, inRect(mx, my, ix + halfW + 4.0F, cy, halfW, 14.0F));
            hit(ix + halfW + 4.0F, cy, halfW, 14.0F, () -> { entityOnlyMobs = false; PICKER_SCROLL.reset(); QuestClient.playClickSound(1.03F); });
            cy += 17.0F;
        } else if (activeKind == QuestTargetCatalog.Kind.BLOCK) {
            float thirdW = (iw - 6.0F) / 3.0F;
            drawChip(c, "方块库", ix, cy, thirdW, 14.0F, pickerCatalogMode && !blockIncludeAllItems, inRect(mx, my, ix, cy, thirdW, 14.0F));
            hit(ix, cy, thirdW, 14.0F, () -> { pickerCatalogMode = true; blockIncludeAllItems = false; PICKER_SCROLL.reset(); QuestClient.playClickSound(1.03F); });
            drawChip(c, "全物品", ix + thirdW + 3.0F, cy, thirdW, 14.0F, pickerCatalogMode && blockIncludeAllItems, inRect(mx, my, ix + thirdW + 3.0F, cy, thirdW, 14.0F));
            hit(ix + thirdW + 3.0F, cy, thirdW, 14.0F, () -> { pickerCatalogMode = true; blockIncludeAllItems = true; PICKER_SCROLL.reset(); QuestClient.playClickSound(1.03F); });
            drawChip(c, "背包", ix + (thirdW + 3.0F) * 2.0F, cy, thirdW, 14.0F, !pickerCatalogMode, inRect(mx, my, ix + (thirdW + 3.0F) * 2.0F, cy, thirdW, 14.0F));
            hit(ix + (thirdW + 3.0F) * 2.0F, cy, thirdW, 14.0F, () -> { pickerCatalogMode = false; PICKER_SCROLL.reset(); QuestClient.playClickSound(1.03F); });
            cy += 17.0F;
        } else if (activeKind == QuestTargetCatalog.Kind.ITEM) {
            float halfW = (iw - 4.0F) / 2.0F;
            drawChip(c, "全物品库", ix, cy, halfW, 14.0F, pickerCatalogMode, inRect(mx, my, ix, cy, halfW, 14.0F));
            hit(ix, cy, halfW, 14.0F, () -> { pickerCatalogMode = true; PICKER_SCROLL.reset(); QuestClient.playClickSound(1.03F); });
            drawChip(c, "我的背包", ix + halfW + 4.0F, cy, halfW, 14.0F, !pickerCatalogMode, inRect(mx, my, ix + halfW + 4.0F, cy, halfW, 14.0F));
            hit(ix + halfW + 4.0F, cy, halfW, 14.0F, () -> { pickerCatalogMode = false; PICKER_SCROLL.reset(); QuestClient.playClickSound(1.03F); });
            cy += 17.0F;
        }

        // 搜索框
        boolean useBackpack = (activeKind == QuestTargetCatalog.Kind.ITEM || activeKind == QuestTargetCatalog.Kind.BLOCK)
                && !pickerCatalogMode;
        if (!useBackpack) {
            fields[SEARCH_IDX].placeholder(searchPlaceholderFor(activeKind));
            renderFieldClipped(c, used, SEARCH_IDX, ix, cy, iw, 17.0F, topY, topY + availH, mx, my, dt);
            cy += 20.0F;
        }

        float footH = 14.0F;
        float listH = Math.max(40.0F, topY + availH - cy - footH - 2.0F);
        Consumer<QuestTargetCatalog.Entry> onPickEntry = entry -> applyPickedTarget(q, entry.id(), entry.displayName());

        if (useBackpack) {
            List<ItemStack> invStacks = collectPlayerInventoryStacks();
            renderPlayerInventoryGrid(c, items, invStacks, ix, cy, iw, listH, mx, my, dt, picked -> {
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(picked.getItem()).toString();
                String name = picked.getHoverName().getString();
                applyPickedTarget(q, id, name);
            });
            V2.text(c, "点击背包物品设为任务目标", 4.9F, ShopTheme.text3(), ix, topY + availH - 7.0F, iw, Text.Align.CENTER);
        } else {
            QuestTargetCatalog.Kind queryKind = (activeKind == QuestTargetCatalog.Kind.BLOCK && blockIncludeAllItems)
                    ? QuestTargetCatalog.Kind.ITEM : activeKind;
            boolean primaryOnly = (queryKind == QuestTargetCatalog.Kind.ENTITY) && entityOnlyMobs;
            List<QuestTargetCatalog.Entry> entries = QuestTargetCatalog.search(
                    queryKind, fields[SEARCH_IDX].value(), primaryOnly,
                    QuestClientState.syncedBiomes(), QuestClientState.syncedStructures(),
                    QuestClientState.worldRegistryRevision());

            if (queryKind == QuestTargetCatalog.Kind.ITEM || queryKind == QuestTargetCatalog.Kind.BLOCK) {
                renderVirtualIconGrid(c, f, items, entries, ix, cy, iw, listH, mx, my, dt, onPickEntry);
            } else {
                renderVirtualNamedList(c, f, items, entries, ix, cy, iw, listH, mx, my, dt, onPickEntry);
            }
            V2.text(c, "共 " + entries.size() + " 项 · 支持 @modid 筛选",
                    4.9F, ShopTheme.text3(), ix, topY + availH - 7.0F, iw, Text.Align.CENTER);
        }
    }

    private void applyPickedTarget(QuestDefSpec q, String targetId, String displayName) {
        if (q.targets == null) q.targets = new ArrayList<>();
        if (editingTargetRowIdx >= 0 && editingTargetRowIdx < q.targets.size()) {
            TaskTarget tt = q.targets.get(editingTargetRowIdx);
            tt.target = targetId;
            tt.label = displayName == null ? "" : displayName;
            q.syncLegacyFieldsFromTargets();
            updateFieldValueAndModel(TARGET_ID_BASE + editingTargetRowIdx, targetId);
        } else if (q.targets.size() < MAX_TARGET_ROWS) {
            q.targets.add(new TaskTarget(targetId, displayName == null ? "" : displayName, 1));
            q.syncLegacyFieldsFromTargets();
            editingTargetRowIdx = q.targets.size() - 1;
            boundContextKey = "";
        }
        dirty = true;
        QuestClient.playClickSound(1.15F);
    }

    /** 虚拟滚动物品/方块图标网格：30,000+ 项下每帧仅渲染可见的 ~25 格，0ms 延迟！ */
    private void renderVirtualIconGrid(Canvas c, AddonFrame f, ItemOverlay items,
                                       List<QuestTargetCatalog.Entry> entries,
                                       float ix, float gridTop, float iw, float gridH,
                                       float mx, float my, float dt,
                                       Consumer<QuestTargetCatalog.Entry> onPick) {
        if (entries.isEmpty()) {
            V2.text(c, "没有匹配项（试试换关键词或 @modid）", 5.4F, ShopTheme.text3(),
                    ix, gridTop + gridH / 2.0F, iw, Text.Align.CENTER);
            return;
        }
        float barReserve = 6.0F;
        float usableW = Math.max(30.0F, iw - barReserve);
        float cellSz = 26.0F;
        float gap = 3.5F;
        int cols = Math.max(1, (int) ((usableW + gap) / (cellSz + gap)));
        float actualGap = cols > 1 ? (usableW - cols * cellSz) / (cols - 1) : 0.0F;
        int numRows = (entries.size() + cols - 1) / cols;
        float rowStep = cellSz + 3.5F;
        float contentH = numRows * rowStep;
        PICKER_SCROLL.setContent(contentH, gridH);
        scrollRegions.add(new ScrollRegion(PICKER_SCROLL, ix, gridTop, iw, gridH, false));
        float offY = PICKER_SCROLL.offset();

        int firstRow = Math.max(0, (int) (offY / rowStep) - 1);
        int lastRow = Math.min(numRows - 1, (int) ((offY + gridH) / rowStep) + 1);

        int save = c.save();
        Draw.clip(c, ix, gridTop, iw, gridH);
        if (items != null) {
            items.pushClip(ix, gridTop, iw, gridH);
        }

        for (int r = firstRow; r <= lastRow; r++) {
            float sy = gridTop + r * rowStep - offY;
            for (int col = 0; col < cols; col++) {
                int idx = r * cols + col;
                if (idx >= entries.size()) break;
                QuestTargetCatalog.Entry entry = entries.get(idx);
                float sx = ix + col * (cellSz + actualGap);
                boolean over = my >= gridTop && my < gridTop + gridH && inRect(mx, my, sx, sy, cellSz, cellSz);
                Draw.fill(c, sx, sy, cellSz, cellSz, 4.0F,
                        over ? Draw.alpha(ShopTheme.accent(), 0.22F) : Draw.alpha(ShopTheme.text3(), 0.08F));
                Draw.stroke(c, sx, sy, cellSz, cellSz, 4.0F, over ? 1.0F : 0.6F,
                        over ? ShopTheme.accent() : ShopTheme.borderDim());
                if (items != null && !entry.icon().isEmpty()) {
                    items.add(entry.icon(), sx + 4.0F, sy + 4.0F, 18.0F, false);
                    if (over) {
                        if (entry.kind() == QuestTargetCatalog.Kind.ITEM) {
                            items.setHovered(entry.icon());
                        } else {
                            f.host().textTooltip(Component.literal(entry.displayName() + "  [" + entry.id() + "]"));
                        }
                    }
                }
                hitClipped(sx, sy, cellSz, cellSz, gridTop, gridTop + gridH, () -> onPick.accept(entry));
            }
        }

        if (items != null) {
            items.popClip();
        }
        c.restoreToCount(save);
        if (PICKER_SCROLL.canScroll()) {
            PICKER_SCROLL.drawBar(c, ix, gridTop, iw, gridH, mx, my, dt);
        }
    }

    /** 虚拟滚动图文列表（用于生物/怪物、群系、结构选择器，清晰显示中文名 + 注册 ID + 图标）。 */
    private void renderVirtualNamedList(Canvas c, AddonFrame f, ItemOverlay items,
                                        List<QuestTargetCatalog.Entry> entries,
                                        float ix, float listTop, float iw, float listH,
                                        float mx, float my, float dt,
                                        Consumer<QuestTargetCatalog.Entry> onPick) {
        if (entries.isEmpty()) {
            V2.text(c, "暂无可选项（可直接在左侧输入 ID / #标签）", 5.2F, ShopTheme.text3(),
                    ix, listTop + listH / 2.0F, iw, Text.Align.CENTER);
            return;
        }
        float rowH = 24.0F;
        float rowStep = rowH + 2.5F;
        float contentH = entries.size() * rowStep;
        PICKER_SCROLL.setContent(contentH, listH);
        scrollRegions.add(new ScrollRegion(PICKER_SCROLL, ix, listTop, iw, listH, false));
        float offY = PICKER_SCROLL.offset();
        float drawW = iw - (PICKER_SCROLL.canScroll() ? 6.0F : 0.0F);

        int first = Math.max(0, (int) (offY / rowStep) - 1);
        int last = Math.min(entries.size() - 1, (int) ((offY + listH) / rowStep) + 1);

        int save = c.save();
        Draw.clip(c, ix, listTop, iw, listH);
        if (items != null) {
            items.pushClip(ix, listTop, iw, listH);
        }

        for (int i = first; i <= last; i++) {
            QuestTargetCatalog.Entry entry = entries.get(i);
            float ry = listTop + i * rowStep - offY;
            boolean over = my >= listTop && my < listTop + listH && inRect(mx, my, ix, ry, drawW, rowH);
            Draw.fill(c, ix, ry, drawW, rowH, 4.0F,
                    over ? Draw.alpha(ShopTheme.accent(), 0.20F) : Draw.alpha(ShopTheme.text3(), 0.07F));
            Draw.stroke(c, ix, ry, drawW, rowH, 4.0F, over ? 0.9F : 0.5F,
                    over ? ShopTheme.accent() : ShopTheme.borderDim());

            if (items != null && !entry.icon().isEmpty()) {
                items.add(entry.icon(), ix + 4.0F, ry + 4.0F, 16.0F, false);
            }
            V2.bold(c, entry.displayName(), 5.8F, over ? ShopTheme.accent() : ShopTheme.text(),
                    ix + 23.0F, ry + 8.0F, drawW - 26.0F, Text.Align.LEFT);
            V2.text(c, entry.id(), 4.7F, ShopTheme.text3(),
                    ix + 23.0F, ry + 17.5F, drawW - 26.0F, Text.Align.LEFT);
            if (over) {
                f.host().textTooltip(Component.literal(entry.displayName() + "\n" + entry.id()));
            }
            hitClipped(ix, ry, drawW, rowH, listTop, listTop + listH, () -> onPick.accept(entry));
        }

        if (items != null) {
            items.popClip();
        }
        c.restoreToCount(save);
        if (PICKER_SCROLL.canScroll()) {
            PICKER_SCROLL.drawBar(c, ix, listTop, iw, listH, mx, my, dt);
        }
    }

    private void renderPlayerInventoryGrid(Canvas c, ItemOverlay items, List<ItemStack> invStacks,
                                           float ix, float gridTop, float iw, float gridH,
                                           float mx, float my, float dt, Consumer<ItemStack> onPick) {
        if (invStacks.isEmpty()) {
            V2.text(c, "你的背包目前是空的", 5.6F, ShopTheme.text3(),
                    ix, gridTop + gridH / 2.0F, iw, Text.Align.CENTER);
            return;
        }
        float usableW = Math.max(30.0F, iw - 6.0F);
        float cellSz = 26.0F;
        float gap = 3.5F;
        int cols = Math.max(1, (int) ((usableW + gap) / (cellSz + gap)));
        float actualGap = cols > 1 ? (usableW - cols * cellSz) / (cols - 1) : 0.0F;
        int numRows = (invStacks.size() + cols - 1) / cols;
        float rowStep = cellSz + 3.5F;
        PICKER_SCROLL.setContent(numRows * rowStep, gridH);
        scrollRegions.add(new ScrollRegion(PICKER_SCROLL, ix, gridTop, iw, gridH, false));
        float offY = PICKER_SCROLL.offset();

        int save = c.save();
        Draw.clip(c, ix, gridTop, iw, gridH);
        if (items != null) items.pushClip(ix, gridTop, iw, gridH);

        for (int i = 0; i < invStacks.size(); i++) {
            int r = i / cols;
            int col = i % cols;
            float sx = ix + col * (cellSz + actualGap);
            float sy = gridTop + r * rowStep - offY;
            if (sy + cellSz < gridTop - 2.0F || sy > gridTop + gridH + 2.0F) continue;
            ItemStack st = invStacks.get(i);
            boolean over = my >= gridTop && my < gridTop + gridH && inRect(mx, my, sx, sy, cellSz, cellSz);
            Draw.fill(c, sx, sy, cellSz, cellSz, 4.0F,
                    over ? Draw.alpha(ShopTheme.accent(), 0.22F) : Draw.alpha(ShopTheme.text3(), 0.08F));
            Draw.stroke(c, sx, sy, cellSz, cellSz, 4.0F, over ? 1.0F : 0.6F,
                    over ? ShopTheme.accent() : ShopTheme.borderDim());
            if (items != null) {
                items.add(st, sx + 4.0F, sy + 3.0F, 17.0F, false);
                if (over) items.setHovered(st);
            }
            if (st.getCount() > 1) {
                V2.bold(c, "×" + st.getCount(), 4.6F, ShopTheme.text2(), sx, sy + cellSz - 3.5F, cellSz, Text.Align.CENTER);
            }
            final ItemStack copy = st.copy();
            hitClipped(sx, sy, cellSz, cellSz, gridTop, gridTop + gridH, () -> onPick.accept(copy));
        }

        if (items != null) items.popClip();
        c.restoreToCount(save);
        if (PICKER_SCROLL.canScroll()) {
            PICKER_SCROLL.drawBar(c, ix, gridTop, iw, gridH, mx, my, dt);
        }
    }

    private static List<ItemStack> collectPlayerInventoryStacks() {
        List<ItemStack> out = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Inventory inv = mc.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st != null && !st.isEmpty()) {
                    out.add(st);
                }
            }
        }
        return out;
    }

    // ================================================================ 通用表单与可滚动物品奖励列表组件

    private interface BundleMutator {
        RewardBundleSpec writable();
    }

    /** 独立视口滚动版奖励物品列表（用于 Tab 0 免费/进阶双轨卡片，彻底修复 7~15+ 项截断）。 */
    private void renderScrollableRichItemCards(Canvas c, ItemOverlay items, RewardBundleSpec bundle,
                                               BundleMutator mutator, ScrollController scroll,
                                               float x, float y, float w, float h,
                                               float mx, float my, float dt) {
        if (bundle.items == null || bundle.items.isEmpty()) {
            float emptyH = Math.min(h, 42.0F);
            Draw.fill(c, x, y, w, emptyH, 5.0F, Draw.alpha(ShopTheme.text3(), 0.05F));
            Draw.stroke(c, x, y, w, emptyH, 5.0F, 0.6F, ShopTheme.borderDim());
            V2.text(c, "暂无物品 · 点击右侧物品库/背包添加",
                    5.4F, ShopTheme.text3(), x + 6.0F, y + emptyH / 2.0F, w - 12.0F, Text.Align.CENTER);
            scroll.setContent(0.0F, h);
            return;
        }
        float rowH = 24.0F;
        float rowStep = rowH + 3.0F;
        float contentH = bundle.items.size() * rowStep;
        scroll.setContent(contentH, h);
        scrollRegions.add(new ScrollRegion(scroll, x, y, w, h, false));

        int save = c.save();
        Draw.clip(c, x, y, w, h);
        if (items != null) items.pushClip(x, y, w, h);

        float drawW = w - (scroll.canScroll() ? 6.0F : 0.0F);
        renderInlineRichItemCards(c, items, bundle, mutator, x, y - scroll.offset(), drawW, y, y + h, mx, my);

        if (items != null) items.popClip();
        c.restoreToCount(save);
        if (scroll.canScroll()) {
            scroll.drawBar(c, x, y, w, h, mx, my, dt);
        }
    }

    /** 嵌在父级滚动容器中的奖励物品卡片列表，返回渲染结束后的新 y 坐标。 */
    private float renderInlineRichItemCards(Canvas c, ItemOverlay items, RewardBundleSpec bundle,
                                            BundleMutator mutator,
                                            float x, float y, float w,
                                            float clipMinY, float clipMaxY,
                                            float mx, float my) {
        HolderLookup.Provider regs = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.registryAccess() : null;
        if (bundle.items == null || bundle.items.isEmpty()) {
            float emptyH = 34.0F;
            if (y + emptyH > clipMinY && y < clipMaxY) {
                Draw.fill(c, x, y, w, emptyH, 5.0F, Draw.alpha(ShopTheme.text3(), 0.05F));
                Draw.stroke(c, x, y, w, emptyH, 5.0F, 0.6F, ShopTheme.borderDim());
                V2.text(c, "暂无物品奖励 · 点击右侧【全物品库 / 我的背包】即可添加",
                        5.4F, ShopTheme.text3(), x + 6.0F, y + emptyH / 2.0F, w - 12.0F, Text.Align.CENTER);
            }
            return y + emptyH;
        }

        float rowH = 24.0F;
        float rowStep = rowH + 3.0F;
        for (int i = 0; i < bundle.items.size(); i++) {
            float ry = y + i * rowStep;
            if (ry + rowH < clipMinY - 2.0F || ry > clipMaxY + 2.0F) {
                continue;
            }
            ItemRewardSpec spec = bundle.items.get(i);
            ItemStack st = QuestDataConfig.buildItemStack(spec, regs);

            Draw.fill(c, x, ry, w, rowH, 4.5F, Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.12F : 0.06F));
            Draw.stroke(c, x, ry, w, rowH, 4.5F, 0.6F, ShopTheme.borderDim());

            if (items != null && !st.isEmpty()) {
                items.add(st, x + 5.0F, ry + 4.0F, 16.0F, false);
                if (my >= clipMinY && my < clipMaxY && inRect(mx, my, x + 3.0F, ry, 22.0F, rowH)) {
                    items.setHovered(st);
                }
            }
            String dispName = !st.isEmpty() ? st.getHoverName().getString() : spec.item;
            V2.bold(c, dispName, 5.8F, ShopTheme.text(), x + 25.0F, ry + rowH / 2.0F,
                    Math.max(28.0F, w - 114.0F), Text.Align.LEFT);

            final int idx = i;
            float right = x + w - 4.0F;

            float rmW = 24.0F;
            right -= rmW;
            drawChip(c, "移除", right, ry + 4.0F, rmW, 16.0F, false,
                    my >= clipMinY && my < clipMaxY && inRect(mx, my, right, ry + 4.0F, rmW, 16.0F));
            hitClipped(right, ry + 4.0F, rmW, 16.0F, clipMinY, clipMaxY, () -> {
                RewardBundleSpec wr = mutator.writable();
                if (idx < wr.items.size()) {
                    wr.items.remove(idx);
                    dirty = true;
                    QuestClient.playClickSound(0.92F);
                }
            });

            right -= 18.0F;
            drawChip(c, "+", right, ry + 4.0F, 15.0F, 16.0F, false,
                    my >= clipMinY && my < clipMaxY && inRect(mx, my, right, ry + 4.0F, 15.0F, 16.0F));
            hitClipped(right, ry + 4.0F, 15.0F, 16.0F, clipMinY, clipMaxY, () -> {
                RewardBundleSpec wr = mutator.writable();
                if (idx < wr.items.size()) {
                    wr.items.get(idx).count = Math.min(999, wr.items.get(idx).count + 1);
                    dirty = true;
                    QuestClient.playClickSound(1.05F);
                }
            });

            right -= 27.0F;
            V2.bold(c, "×" + spec.count, 5.8F, ShopTheme.accent(), right, ry + rowH / 2.0F, 25.0F, Text.Align.CENTER);

            right -= 16.0F;
            drawChip(c, "-", right, ry + 4.0F, 15.0F, 16.0F, false,
                    my >= clipMinY && my < clipMaxY && inRect(mx, my, right, ry + 4.0F, 15.0F, 16.0F));
            hitClipped(right, ry + 4.0F, 15.0F, 16.0F, clipMinY, clipMaxY, () -> {
                RewardBundleSpec wr = mutator.writable();
                if (idx < wr.items.size()) {
                    wr.items.get(idx).count = Math.max(1, wr.items.get(idx).count - 1);
                    dirty = true;
                    QuestClient.playClickSound(0.98F);
                }
            });
        }
        return y + bundle.items.size() * rowStep;
    }

    private void renderDirectInputRow(Canvas c, boolean[] used, String label, int fieldIdx, String unit,
                                      float x, float y, float w, float clipMinY, float clipMaxY,
                                      float mx, float my, float dt,
                                      Runnable onMinus, Runnable onPlus) {
        V2.text(c, label, 5.9F, ShopTheme.text2(), x, y + 8.5F, w * 0.45F, Text.Align.LEFT);
        float right = x + w;
        if (onPlus != null) {
            drawChip(c, "+", right - 16.0F, y + 1.0F, 16.0F, 15.0F, false,
                    my >= clipMinY && my < clipMaxY && inRect(mx, my, right - 16.0F, y + 1.0F, 16.0F, 15.0F));
            hitClipped(right - 16.0F, y + 1.0F, 16.0F, 15.0F, clipMinY, clipMaxY, () -> {
                onPlus.run();
                QuestClient.playClickSound(1.05F);
            });
            right -= 19.0F;
        }
        if (onMinus != null) {
            drawChip(c, "-", right - 16.0F, y + 1.0F, 16.0F, 15.0F, false,
                    my >= clipMinY && my < clipMaxY && inRect(mx, my, right - 16.0F, y + 1.0F, 16.0F, 15.0F));
            hitClipped(right - 16.0F, y + 1.0F, 16.0F, 15.0F, clipMinY, clipMaxY, () -> {
                onMinus.run();
                QuestClient.playClickSound(0.98F);
            });
            right -= 19.0F;
        }
        if (unit != null && !unit.isBlank()) {
            float uw = V2.width(unit, 5.5F, false) + 4.0F;
            V2.text(c, unit, 5.5F, ShopTheme.text3(), right - uw, y + 8.5F, uw, Text.Align.RIGHT);
            right -= (uw + 3.0F);
        }
        float boxW = Math.min(112.0F, Math.max(46.0F, right - (x + w * 0.44F)));
        renderFieldClipped(c, used, fieldIdx, right - boxW, y, boxW, 17.0F, clipMinY, clipMaxY, mx, my, dt);
    }

    private void renderToggleRow(Canvas c, String label, boolean on, float x, float y, float w,
                                 float clipMinY, float clipMaxY, float mx, float my, Runnable onToggle) {
        V2.text(c, label, 5.9F, ShopTheme.text2(), x, y + 8.5F, w - 54.0F, Text.Align.LEFT);
        float bw = 46.0F;
        float bx = x + w - bw;
        drawChip(c, on ? "已开启" : "已关闭", bx, y + 1.0F, bw, 15.0F, on,
                my >= clipMinY && my < clipMaxY && inRect(mx, my, bx, y + 1.0F, bw, 15.0F));
        hitClipped(bx, y + 1.0F, bw, 15.0F, clipMinY, clipMaxY, () -> {
            onToggle.run();
            QuestClient.playClickSound(1.04F);
        });
    }

    private static void drawChip(Canvas c, String text, float x, float y, float w, float h, boolean active, boolean hover) {
        int bg = active
                ? Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.34F : 0.18F)
                : (hover ? Draw.alpha(ShopTheme.accent(), 0.12F) : Draw.alpha(ShopTheme.text3(), 0.08F));
        int border = active ? ShopTheme.accent() : (hover ? ShopTheme.border() : ShopTheme.borderDim());
        int fg = active ? ShopTheme.accent() : ShopTheme.text();
        Draw.fill(c, x, y, w, h, 3.5F, bg);
        Draw.stroke(c, x, y, w, h, 3.5F, active ? 0.9F : 0.6F, border);
        V2.bold(c, text, 5.4F, fg, x + 2.0F, y + h / 2.0F, Math.max(4.0F, w - 4.0F), Text.Align.CENTER);
    }

    // ================================================================ 交互事件分发

    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = scrollRegions.size() - 1; i >= 0; i--) {
                ScrollRegion sr = scrollRegions.get(i);
                if (!sr.horizontal() && sr.scroll().canScroll()
                        && sr.scroll().pressBar(mx, my, sr.x(), sr.y(), sr.w(), sr.h())) {
                    activeBarDrag = sr;
                    blurAllInputs();
                    return true;
                }
            }
        }

        int focusedIdx = -1;
        for (int i = 0; i < fields.length; i++) {
            SkiaTextField tf = fields[i];
            if (tf != null && tf.isVisible()
                    && my >= fieldClipMinY[i] && my < fieldClipMaxY[i]
                    && tf.mouseClicked(mx, my, button)) {
                focusedIdx = i;
                break;
            }
        }
        for (int i = 0; i < fields.length; i++) {
            if (i != focusedIdx && fields[i] != null && fields[i].isFocused()) {
                fields[i].blur();
            }
        }
        if (focusedIdx >= 0) {
            if (focusedIdx >= TARGET_ID_BASE && focusedIdx < TARGET_CNT_BASE) {
                editingTargetRowIdx = focusedIdx - TARGET_ID_BASE;
            } else if (focusedIdx >= TARGET_CNT_BASE && focusedIdx < FIELD_POOL_SIZE) {
                editingTargetRowIdx = focusedIdx - TARGET_CNT_BASE;
            }
            return true;
        }
        if (button != 0) {
            return false;
        }

        if (tabRects != null && my >= tabY && my < tabY + V2.CTRL) {
            for (int i = 0; i < tabRects.length; i++) {
                if (mx >= tabRects[i][0] && mx < tabRects[i][0] + tabRects[i][1]) {
                    subTab = i;
                    boundContextKey = "";
                    PICKER_SCROLL.reset();
                    QuestClient.playClickSound(1.02F);
                    return true;
                }
            }
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.contains((float) mx, (float) my)) {
                h.run().run();
                return true;
            }
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (activeBarDrag != null && button == 0) {
            return activeBarDrag.scroll().dragBar(my, activeBarDrag.h());
        }
        for (SkiaTextField tf : fields) {
            if (tf != null && tf.isVisible() && tf.mouseDragged(mx, my, button, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (activeBarDrag != null && button == 0) {
            activeBarDrag.scroll().releaseBar();
            activeBarDrag = null;
            return true;
        }
        for (SkiaTextField tf : fields) {
            if (tf != null && tf.isVisible() && tf.mouseReleased(mx, my, button)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double scrollY) {
        for (int i = scrollRegions.size() - 1; i >= 0; i--) {
            ScrollRegion sr = scrollRegions.get(i);
            if (sr.contains((float) mx, (float) my) && sr.scroll().canScroll()) {
                sr.scroll().wheel(scrollY * (sr.horizontal() ? 1.5D : 1.35D));
                return true;
            }
        }
        return false;
    }

    // ================================================================ 语义辅助方法

    private static boolean supportsTargetFilter(QuestTaskType t) {
        return switch (t) {
            case VANILLA_BREAK_BLOCK, VANILLA_KILL_MOB, VANILLA_CRAFT_ITEM, VANILLA_FISH,
                 SUBMIT_ITEM, VISIT_BIOME, VISIT_STRUCTURE,
                 SHOP_BUY_ANY, SHOP_BUY_DAILY, SHOP_BUY_MALL, SHOP_SELL_BUYBACK,
                 SHOP_SHIPPING_SELL, STALL_BUY, STALL_LIST, CUSTOM_EVENT -> true;
            default -> false;
        };
    }

    private static QuestTargetCatalog.Kind resolveCatalogKind(QuestTaskType t) {
        return switch (t.targetKind()) {
            case "block" -> QuestTargetCatalog.Kind.BLOCK;
            case "entity" -> QuestTargetCatalog.Kind.ENTITY;
            case "biome" -> QuestTargetCatalog.Kind.BIOME;
            case "structure" -> QuestTargetCatalog.Kind.STRUCTURE;
            default -> QuestTargetCatalog.Kind.ITEM;
        };
    }

    private static String targetPlaceholderFor(QuestTaskType t) {
        return switch (t.targetKind()) {
            case "block" -> "留空不限，或选方块 / #minecraft:logs";
            case "entity" -> "留空不限，或选生物 minecraft:zombie";
            case "biome" -> "选群系或输 ID，如 minecraft:plains";
            case "structure" -> "选结构或输 ID，如 minecraft:village_plains";
            default -> "留空不限，或选物品 / #c:ores";
        };
    }

    private static String searchPlaceholderFor(QuestTargetCatalog.Kind kind) {
        return switch (kind) {
            case BLOCK -> "搜索方块中文名 / ID / @modid ...";
            case ENTITY -> "搜索怪物中文名 / ID / @modid ...";
            case BIOME -> "搜索群系中文名 / ID / @modid ...";
            case STRUCTURE -> "搜索结构中文名 / ID / @modid ...";
            default -> "搜索物品中文名 / ID / @modid ...";
        };
    }

    private static String[][] filterPresetsFor(QuestTaskType t) {
        return switch (t) {
            case VANILLA_BREAK_BLOCK -> new String[][]{{"不限方块", ""}, {"任意矿石", "_ore"}, {"原木", "#minecraft:logs"}, {"石头", "stone"}};
            case VANILLA_KILL_MOB -> new String[][]{{"任意怪物", ""}, {"僵尸", "minecraft:zombie"}, {"骷髅", "minecraft:skeleton"}, {"苦力怕", "minecraft:creeper"}};
            case VANILLA_CRAFT_ITEM -> new String[][]{{"任意物品", ""}, {"木板", "#minecraft:planks"}, {"火把", "minecraft:torch"}, {"面包", "minecraft:bread"}};
            case SUBMIT_ITEM -> new String[][]{{"铁锭", "minecraft:iron_ingot"}, {"小麦", "minecraft:wheat"}, {"圆石", "minecraft:cobblestone"}};
            case VISIT_BIOME -> new String[][]{{"平原", "minecraft:plains"}, {"沙漠", "minecraft:desert"}, {"下界荒地", "minecraft:nether_wastes"}};
            case VISIT_STRUCTURE -> new String[][]{{"村庄", "village"}, {"下界要塞", "minecraft:fortress"}, {"远古城市", "minecraft:ancient_city"}};
            default -> new String[][]{{"不限目标", ""}};
        };
    }

    private static boolean isWeightBucket(int weight, int bucket) {
        return switch (bucket) {
            case 0 -> weight <= 9;
            case 1 -> weight >= 10 && weight <= 18;
            case 2 -> weight >= 19 && weight <= 28;
            default -> weight >= 29;
        };
    }

    private static void setQuestTaskType(QuestDefSpec q, QuestTaskType chosen) {
        q.type = chosen.name();
        q.requiredMod = switch (chosen) {
            case STOCK_SPOT_TRADE -> "module_shop_stock";
            case BLIND_PARTICIPATE -> "module_shop_blind";
            case LOTTERY_PULL -> "lottery";
            case VANILLA_BREAK_BLOCK, VANILLA_KILL_MOB, VANILLA_CRAFT_ITEM, VANILLA_FISH,
                 SUBMIT_ITEM, VISIT_BIOME, VISIT_STRUCTURE -> "minecraft";
            default -> "module_shop";
        };
    }

    private static void applySuggestedTitleAndDesc(QuestDefSpec q, boolean weekly) {
        QuestTaskType t = q.parsedType() != null ? q.parsedType() : QuestTaskType.SHOP_BUY_ANY;
        q.syncLegacyFieldsFromTargets();
        int n = q.totalTargetCount();
        String prefix = weekly ? "本周累计" : "";
        switch (t) {
            case SHOP_SIGN_IN -> { q.title = weekly ? "累计签到" : "商店签到"; q.description = prefix + "完成 " + n + " 次商店每日签到"; }
            case SHOP_BUY_ANY -> { q.title = "商店购买"; q.description = prefix + "在商店或购物城购买 " + n + " 次商品"; }
            case SHOP_BUY_DAILY -> { q.title = "每日商店采购"; q.description = prefix + "在每日商店购买 " + n + " 次商品"; }
            case SHOP_BUY_MALL -> { q.title = "购物城采购"; q.description = prefix + "在购物城购买 " + n + " 次商品"; }
            case SHOP_SELL_BUYBACK -> { q.title = "出售物品"; q.description = prefix + "在每日收购或出货箱出售 " + n + " 次物品"; }
            case SHOP_SHIPPING_SELL -> { q.title = "出货箱结算"; q.description = prefix + "通过出货箱成功结算 " + n + " 次"; }
            case STALL_BUY -> { q.title = "摊位与拍卖"; q.description = prefix + "在玩家摊位购买或参与拍卖 " + n + " 次"; }
            case STALL_LIST -> { q.title = "摊位上架"; q.description = prefix + "在玩家摊位上架 " + n + " 次商品"; }
            case AUCTION_BID -> { q.title = "拍卖出价"; q.description = prefix + "参与拍卖会出价 " + n + " 次"; }
            case DEMAND_CREATE -> { q.title = "发布求购"; q.description = prefix + "发布 " + n + " 条求购单"; }
            case PLAYER_TRADE -> { q.title = "玩家交易"; q.description = prefix + "完成 " + n + " 次玩家面对面交易"; }
            case STOCK_SPOT_TRADE -> { q.title = "股市交易"; q.description = prefix + "在股市完成 " + n + " 笔股票买卖"; }
            case BLIND_PARTICIPATE -> { q.title = "遗迹盲仓"; q.description = prefix + "参与 " + n + " 局真人遗迹盲仓"; }
            case LOTTERY_PULL -> { q.title = "抽卡抽奖"; q.description = prefix + "参与 " + n + " 次抽卡或刮刮乐"; }
            case VANILLA_BREAK_BLOCK -> { q.title = "挖掘方块"; q.description = prefix + "挖掘指定目标方块"; }
            case VANILLA_KILL_MOB -> { q.title = "讨伐怪物"; q.description = prefix + "击杀指定目标怪物"; }
            case VANILLA_CRAFT_ITEM -> { q.title = "合成物品"; q.description = prefix + "合成指定目标物品"; }
            case VANILLA_FISH -> { q.title = "垂钓收获"; q.description = prefix + "成功钓起 " + n + " 次物品"; }
            case SUBMIT_ITEM -> { q.title = "提交物资"; q.description = "收集并提交指定物资物品"; }
            case VISIT_BIOME -> { q.title = "探索群系"; q.description = "前往并抵达指定生物群系"; }
            case VISIT_STRUCTURE -> { q.title = "探访遗迹"; q.description = "探索并到达指定世界结构"; }
            default -> { q.title = "自定义任务"; q.description = prefix + "完成 " + n + " 次指定目标"; }
        }
    }

    private static String shortTaskLabel(QuestTaskType t) {
        return switch (t) {
            case SHOP_SIGN_IN -> "商店签到";
            case SHOP_BUY_ANY -> "任意购买";
            case SHOP_BUY_DAILY -> "每日商店";
            case SHOP_BUY_MALL -> "购物城";
            case SHOP_SELL_BUYBACK -> "出售物品";
            case SHOP_SHIPPING_SELL -> "出货箱";
            case STALL_BUY -> "摊位/拍卖";
            case STALL_LIST -> "摊位上架";
            case AUCTION_BID -> "拍卖出价";
            case DEMAND_CREATE -> "发布求购";
            case PLAYER_TRADE -> "面对面交易";
            case STOCK_SPOT_TRADE -> "股市买卖";
            case BLIND_PARTICIPATE -> "遗迹盲仓";
            case LOTTERY_PULL -> "抽卡抽奖";
            case VANILLA_BREAK_BLOCK -> "挖掘方块";
            case VANILLA_KILL_MOB -> "击杀怪物";
            case VANILLA_CRAFT_ITEM -> "合成物品";
            case VANILLA_FISH -> "钓鱼";
            case SUBMIT_ITEM -> "提交物品";
            case VISIT_BIOME -> "到达群系";
            case VISIT_STRUCTURE -> "到达结构";
            default -> "外部事件";
        };
    }

    private static String formatHumanDuration(int mins) {
        if (mins < 60) return mins + "分钟";
        int h = mins / 60;
        int m = mins % 60;
        return m == 0 ? (h + "小时") : (h + "小时" + m + "分");
    }

    private static String bumpSeasonTitle(String cur) {
        if (cur == null || cur.isBlank()) return "第 2 赛季";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(cur);
        if (m.find()) {
            int next = Integer.parseInt(m.group(1)) + 1;
            return cur.substring(0, m.start()) + next + cur.substring(m.end());
        }
        return cur + " · 新季";
    }

    private static PassLevelSpec findExplicitLevel(SeasonPassConfig pass, int level) {
        if (pass.levels == null) return null;
        for (PassLevelSpec s : pass.levels) {
            if (s != null && s.level == level) return s;
        }
        return null;
    }

    private static PassLevelSpec getOrCreateExplicitLevel(SeasonPassConfig pass, int level) {
        if (pass.levels == null) pass.levels = new ArrayList<>();
        PassLevelSpec found = findExplicitLevel(pass, level);
        if (found != null) {
            if (found.freeReward == null) found.freeReward = new RewardBundleSpec();
            if (found.premiumReward == null) found.premiumReward = new RewardBundleSpec();
            return found;
        }
        List<PassLevelSpec> resolved = pass.resolvedLevels();
        PassLevelSpec base = resolved.get(Math.max(0, Math.min(resolved.size() - 1, level - 1)));
        PassLevelSpec copy = new PassLevelSpec(
                level, 0L, base.highlight, cloneBundle(base.freeReward), cloneBundle(base.premiumReward));
        pass.levels.add(copy);
        pass.levels.sort((a, b) -> Integer.compare(a.level, b.level));
        return copy;
    }

    private static RewardBundleSpec cloneBundle(RewardBundleSpec src) {
        if (src == null) return new RewardBundleSpec();
        List<ItemRewardSpec> items = new ArrayList<>();
        if (src.items != null) {
            for (ItemRewardSpec it : src.items) {
                ItemRewardSpec c = new ItemRewardSpec(it.item, it.count, it.customName);
                if (it.enchantments != null) c.enchantments.putAll(it.enchantments);
                items.add(c);
            }
        }
        return new RewardBundleSpec(src.money, src.points, items);
    }

    private static void appendOrMergeItem(RewardBundleSpec bundle, ItemRewardSpec incoming) {
        if (bundle.items == null) bundle.items = new ArrayList<>();
        for (ItemRewardSpec existing : bundle.items) {
            if (existing.item.equals(incoming.item)
                    && existing.customName.equals(incoming.customName)
                    && existing.enchantments.equals(incoming.enchantments)) {
                existing.count = Math.min(999, existing.count + incoming.count);
                return;
            }
        }
        bundle.items.add(incoming);
    }

    private void hit(float x, float y, float w, float h, Runnable r) {
        if (w > 0.0F && h > 0.0F && r != null) {
            hits.add(new Hit(x, y, w, h, r));
        }
    }

    private void hitClipped(float x, float y, float w, float h, float clipMinY, float clipMaxY, Runnable r) {
        float top = Math.max(y, clipMinY);
        float bot = Math.min(y + h, clipMaxY);
        if (w > 0.0F && bot > top && r != null) {
            hits.add(new Hit(x, top, w, bot - top, r));
        }
    }

    private static int parseIntClamp(String s, int fallback, int min, int max) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(s.trim())));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLongClamp(String s, long fallback, long min, long max) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Long.parseLong(s.trim())));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLongBlankZero(String s, long fallback, long min, long max) {
        if (s == null || s.isBlank()) {
            return Math.max(min, 0L);
        }
        try {
            return Math.max(min, Math.min(max, Long.parseLong(s.trim())));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean inRect(float mx, float my, float rx, float ry, float rw, float rh) {
        return mx >= rx && mx < rx + rw && my >= ry && my < ry + rh;
    }
}
