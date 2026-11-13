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
import cn.autoforged.module_shop_quest.config.QuestDataConfig;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 人性化全画布可视化配置工作台（v2）：
 * <ul>
 *     <li><b>原地直接输入</b>：所有文字与数字框均为原地即点即输的 {@link SkiaTextField}，无需弹窗或逗号拼接；</li>
 *     <li><b>右侧常驻物品库 + 背包面板</b>：支持按中文名/ID 搜索全游戏注册物品（含模组物品）并选数量（+1/+4/+16/+64）一键塞入奖励，
 *     同时支持切换「我的背包」直接拾取带附魔/自定义名的物品；</li>
 *     <li><b>零术语任务构建器</b>：按「商店交易 / 附属玩法 / 原版生存」直观按钮点选任务类型，智能显隐目标过滤预设与刷新概率档位。</li>
 * </ul>
 */
public final class QuestAdminView {
    private static final int FIELD_POOL_SIZE = 10;
    private static final int SEARCH_IDX = 9;

    // 精选高频常用奖励物品置顶展示（未输入搜索词时排在最前，方便腐竹秒选）
    private static final String[] POPULAR_ITEM_IDS = {
            "minecraft:diamond", "minecraft:emerald", "minecraft:golden_apple",
            "minecraft:enchanted_golden_apple", "minecraft:netherite_ingot", "minecraft:netherite_scrap",
            "minecraft:nether_star", "minecraft:totem_of_undying", "minecraft:experience_bottle",
            "minecraft:diamond_block", "minecraft:emerald_block", "minecraft:gold_ingot",
            "minecraft:iron_ingot", "minecraft:golden_carrot", "minecraft:cooked_beef",
            "minecraft:cooked_salmon", "minecraft:bread", "minecraft:torch",
            "minecraft:coal", "minecraft:lapis_lazuli", "minecraft:redstone",
            "minecraft:quartz", "minecraft:amethyst_shard", "minecraft:ender_pearl",
            "minecraft:blaze_rod", "minecraft:shulker_shell", "minecraft:elytra",
            "minecraft:beacon", "minecraft:trident", "minecraft:name_tag"
    };

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
            QuestTaskType.VANILLA_CRAFT_ITEM, QuestTaskType.VANILLA_FISH
    };

    private static int subTab = 0; // 0=等级奖励, 1=通行证设置, 2=每日与每周任务, 3=在线时长奖励
    private static int selectedLevel = 1; // -1=普通默认, 0=每5级重点默认, 1..N=具体等级
    private static boolean pickTargetPremium = false;
    private static boolean editWeeklyPool = false;
    private static int selectedQuestIdx = 0;
    private static boolean editWeeklyOnline = false;
    private static int selectedOnlineIdx = 0;

    // 右侧物品选择器状态
    private static boolean pickerCatalogMode = true; // true=全物品库搜索, false=我的背包
    private static int pickerBatchCount = 1; // 1, 4, 16, 64
    private static String lastSearchQuery = "";
    private static List<ItemStack> cachedCatalogItems = List.of();
    private static boolean dirty = false;

    private static final ScrollController LEVEL_STRIP_SCROLL = new ScrollController(false);
    private static final ScrollController LEFT_LIST_SCROLL = new ScrollController(false);
    private static final ScrollController PICKER_SCROLL = new ScrollController(false);
    private static final ScrollController CENTER_SCROLL = new ScrollController(false);

    private final SkiaTextField[] fields = new SkiaTextField[FIELD_POOL_SIZE];
    @SuppressWarnings("unchecked")
    private final Consumer<String>[] fieldListeners = new Consumer[FIELD_POOL_SIZE];
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

    private final List<Hit> hits = new ArrayList<>();
    private float lvStripX, lvStripY, lvStripW, lvStripH;
    private float leftListX, leftListY, leftListW, leftListH;
    private float pickerX, pickerY, pickerW, pickerH;
    private float centerX, centerY, centerW, centerH;

    public void ensureInputInitialized(AddonPageHost host) {
        if (fields[0] != null) {
            return;
        }
        for (int i = 0; i < FIELD_POOL_SIZE; i++) {
            final int slot = i;
            SkiaTextField tf = new SkiaTextField(host.screen(), host.font(), i == SEARCH_IDX ? 48 : 96)
                    .fontSize(6.2F);
            tf.visible(false);
            tf.onChange(val -> {
                if (!suppressFieldCallback && fieldListeners[slot] != null) {
                    fieldListeners[slot].accept(val);
                    if (slot != SEARCH_IDX) {
                        dirty = true;
                    }
                }
            });
            host.registerInput(tf.editBox());
            fields[i] = tf;
        }
        fields[SEARCH_IDX].placeholder("搜索物品中文名或ID（如 钻石 / 合金）...");
    }

    public void closeInlineEditor() {
        for (SkiaTextField tf : fields) {
            if (tf != null) {
                tf.blur();
                tf.visible(false);
            }
        }
    }

    private void hideFormFieldsExcept(int countUsed, boolean showSearch) {
        for (int i = 0; i < SEARCH_IDX; i++) {
            if (fields[i] != null && i >= countUsed) {
                fields[i].visible(false);
            }
        }
        if (fields[SEARCH_IDX] != null && !showSearch) {
            fields[SEARCH_IDX].visible(false);
        }
    }

    private void setFieldSilently(int idx, String val, String placeholder, Consumer<String> listener) {
        if (fields[idx] == null) return;
        fieldListeners[idx] = listener;
        fields[idx].placeholder(placeholder == null ? "" : placeholder);
        suppressFieldCallback = true;
        fields[idx].setValue(val == null ? "" : val);
        suppressFieldCallback = false;
    }

    private void updateFieldValueAndModel(int idx, String newVal) {
        if (fields[idx] == null) return;
        fields[idx].setValue(newVal);
        dirty = true;
    }

    public void render(Canvas c, AddonFrame f, ItemOverlay items,
                       float x, float y, float w, float h, float mx, float my, float dt) {
        ensureInputInitialized(f.host());
        hits.clear();
        LEVEL_STRIP_SCROLL.update();
        LEFT_LIST_SCROLL.update();
        PICKER_SCROLL.update();
        CENTER_SCROLL.update();

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

        // 右侧常驻「全物品库搜索 / 我的背包」面板宽度（充分利用宽屏右侧空间！）
        float dockW = clamp(iw * 0.285F, 178.0F, 226.0F);
        float mainW = iw - dockW - 8.0F;
        float dockX = ix + mainW + 8.0F;

        switch (subTab) {
            case 0 -> renderPassLevelsStudio(c, f, items, ix, bodyY, mainW, bodyH, dockX, dockW, mx, my, dt);
            case 1 -> renderPassSettingsStudio(c, f, items, ix, bodyY, mainW, bodyH, dockX, dockW, mx, my, dt);
            case 2 -> renderQuestDesignerStudio(c, f, items, ix, bodyY, mainW, bodyH, dockX, dockW, mx, my, dt);
            case 3 -> renderOnlineDesignerStudio(c, f, items, ix, bodyY, mainW, bodyH, dockX, dockW, mx, my, dt);
            default -> {
            }
        }
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
            String rerollLbl = "保存并立即刷新我的任务";
            float rw = V2.buttonWidth(rerollLbl) + 10.0F;
            float rx = right - rw;
            boolean overR = inRect(mx, my, rx, ctrlY, rw, V2.CTRL);
            rerollSaveHover = Motion.hover(rerollSaveHover, overR, dt);
            V2.button(c, rerollLbl, V2.Btn.NORMAL, rx, ctrlY, rw, V2.CTRL, rerollSaveHover);
            hit(rx, ctrlY, rw, V2.CTRL, () -> {
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

        String reloadLbl = "放弃未存并重读";
        float rlw = V2.buttonWidth(reloadLbl) + 6.0F;
        float rlx = right - rlw;
        if (rlx > x + 260.0F) {
            V2.button(c, reloadLbl, V2.Btn.GHOST, rlx, ctrlY, rlw, V2.CTRL,
                    inRect(mx, my, rlx, ctrlY, rlw, V2.CTRL) ? 1.0F : 0.0F);
            hit(rlx, ctrlY, rlw, V2.CTRL, () -> {
                dirty = false;
                QuestClient.sendAdminReload();
            });
        }
    }

    // ================================================================ 1. 【等级奖励】工作台

    private void renderPassLevelsStudio(Canvas c, AddonFrame f, ItemOverlay items,
                                        float x, float y, float w, float h,
                                        float dockX, float dockW, float mx, float my, float dt) {
        SeasonPassConfig pass = QuestClientState.editPass();
        PointsRuleSpec rule = pass.pointsRule;
        int maxLv = Math.max(1, rule.maxLevel);
        if (selectedLevel > maxLv) selectedLevel = maxLv;

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
            PassLevelSpec res = resolved.get(selectedLevel - 1);
            freeBundle = explicitSpec != null ? explicitSpec.freeReward : res.freeReward;
            premBundle = explicitSpec != null ? explicitSpec.premiumReward : res.premiumReward;
        }

        String ctx = "tab0:" + selectedLevel + ":" + (explicitSpec != null);
        if (!ctx.equals(boundContextKey)) {
            boundContextKey = ctx;
            setFieldSilently(0, String.valueOf(freeBundle.money), "0", v -> {
                RewardBundleSpec target = selectedLevel >= 1
                        ? getOrCreateExplicitLevel(pass, selectedLevel).freeReward : freeBundle;
                target.money = parseLongClamp(v, target.money, 0L, 99999999L);
            });
            setFieldSilently(1, String.valueOf(premBundle.money), "0", v -> {
                RewardBundleSpec target = selectedLevel >= 1
                        ? getOrCreateExplicitLevel(pass, selectedLevel).premiumReward : premBundle;
                target.money = parseLongClamp(v, target.money, 0L, 99999999L);
            });
        }
        hideFormFieldsExcept(2, pickerCatalogMode);

        // 顶部等级快速导航条
        float stripH = 26.0F;
        Draw.fill(c, x, y, w, stripH, 5.5F, ShopTheme.card());
        Draw.stroke(c, x, y, w, stripH, 5.5F, 0.7F, ShopTheme.borderDim());

        float navBtnW = 16.0F;
        drawChip(c, "‹", x + 4.0F, y + 4.0F, navBtnW, stripH - 8.0F, false, inRect(mx, my, x + 4.0F, y + 4.0F, navBtnW, stripH - 8.0F));
        hit(x + 4.0F, y + 4.0F, navBtnW, stripH - 8.0F, () -> LEVEL_STRIP_SCROLL.wheel(4.0D));

        drawChip(c, "›", x + w - navBtnW - 4.0F, y + 4.0F, navBtnW, stripH - 8.0F, false,
                inRect(mx, my, x + w - navBtnW - 4.0F, y + 4.0F, navBtnW, stripH - 8.0F));
        hit(x + w - navBtnW - 4.0F, y + 4.0F, navBtnW, stripH - 8.0F, () -> LEVEL_STRIP_SCROLL.wheel(-4.0D));

        lvStripX = x + navBtnW + 7.0F;
        lvStripY = y;
        lvStripW = w - (navBtnW + 7.0F) * 2.0F;
        lvStripH = stripH;

        float totalW = 74.0F + 74.0F + maxLv * 40.0F + (maxLv + 1) * 4.0F;
        LEVEL_STRIP_SCROLL.setContent(totalW, lvStripW);

        int save = c.save();
        Draw.clip(c, lvStripX, y, lvStripW, stripH);
        float cx = lvStripX - LEVEL_STRIP_SCROLL.offset();
        for (int idx = -1; idx <= maxLv; idx++) {
            float cw = idx <= 0 ? 72.0F : 38.0F;
            if (cx + cw >= lvStripX && cx <= lvStripX + lvStripW) {
                boolean sel = selectedLevel == idx;
                boolean customized = idx >= 1 && findExplicitLevel(pass, idx) != null;
                String lbl = idx == -1 ? "普通级通用"
                        : (idx == 0 ? "每" + rule.milestoneEveryLevels + "级通用" : ((customized ? "★ " : "") + idx + "级"));
                boolean over = mx >= lvStripX && mx < lvStripX + lvStripW && inRect(mx, my, cx, y + 4.0F, cw, stripH - 8.0F);
                drawChip(c, lbl, cx, y + 4.0F, cw, stripH - 8.0F, sel, over);
                if (mx >= lvStripX && mx < lvStripX + lvStripW) {
                    final int target = idx;
                    hit(cx, y + 4.0F, cw, stripH - 8.0F, () -> {
                        selectedLevel = target;
                        boundContextKey = "";
                        QuestClient.playClickSound(1.04F);
                    });
                }
            }
            cx += cw + 4.0F;
        }
        c.restoreToCount(save);

        // 等级状态与快捷操作条（支持「复制上一级奖励」「设为重点级」「恢复通用模板」）
        float infoY = y + stripH + 5.0F;
        float infoH = 22.0F;
        Draw.fill(c, x, infoY, w, infoH, 5.0F, ShopTheme.card());
        Draw.stroke(c, x, infoY, w, infoH, 5.0F, 0.6F, ShopTheme.borderDim());

        if (selectedLevel <= 0) {
            String desc = selectedLevel == -1
                    ? "通用模板：所有没单独改过的普通等级，都会自动发放下面这套奖励"
                    : "重点级通用模板：每逢 " + rule.milestoneEveryLevels + " 的倍数级且未单独修改时，自动发放下面这套奖励";
            V2.bold(c, desc, 6.2F, ShopTheme.text(), x + 10.0F, infoY + infoH / 2.0F, w - 20.0F, Text.Align.LEFT);
        } else {
            long reqPts = pass.resolvedLevels().get(selectedLevel - 1).requiredPoints;
            String state = explicitSpec != null
                    ? ("正在编辑：第 " + selectedLevel + " 级（独立配置 · 升到此级需累计 " + reqPts + " 积分）")
                    : ("正在编辑：第 " + selectedLevel + " 级（当前沿用通用模板 · 改任意项自动独立保存）");
            V2.bold(c, state, 6.2F, explicitSpec != null ? ShopTheme.accent() : ShopTheme.text2(),
                    x + 10.0F, infoY + infoH / 2.0F, w * 0.56F, Text.Align.LEFT);

            float rx = x + w - 6.0F;
            if (explicitSpec != null) {
                float bw = 64.0F;
                rx -= bw;
                drawChip(c, "恢复通用模板", rx, infoY + 3.0F, bw, infoH - 6.0F, false, inRect(mx, my, rx, infoY + 3.0F, bw, infoH - 6.0F));
                final int lvClear = selectedLevel;
                hit(rx, infoY + 3.0F, bw, infoH - 6.0F, () -> {
                    pass.levels.removeIf(s -> s != null && s.level == lvClear);
                    dirty = true;
                    boundContextKey = "";
                    QuestClient.playClickSound(0.95F);
                });
                rx -= 5.0F;
            }
            if (selectedLevel > 1) {
                float bw = 64.0F;
                rx -= bw;
                drawChip(c, "复制上一级", rx, infoY + 3.0F, bw, infoH - 6.0F, false, inRect(mx, my, rx, infoY + 3.0F, bw, infoH - 6.0F));
                final int cur = selectedLevel;
                hit(rx, infoY + 3.0F, bw, infoH - 6.0F, () -> {
                    PassLevelSpec prevResolved = pass.resolvedLevels().get(cur - 2);
                    PassLevelSpec target = getOrCreateExplicitLevel(pass, cur);
                    target.freeReward = cloneBundle(prevResolved.freeReward);
                    target.premiumReward = cloneBundle(prevResolved.premiumReward);
                    dirty = true;
                    boundContextKey = "";
                    QuestClient.playClickSound(1.06F);
                });
                rx -= 5.0F;
            }
            boolean isHi = explicitSpec != null ? explicitSpec.highlight
                    : (selectedLevel % Math.max(1, rule.milestoneEveryLevels) == 0);
            float hw = 64.0F;
            rx -= hw;
            drawChip(c, isHi ? "★ 重点展示" : "☆ 普通展示", rx, infoY + 3.0F, hw, infoH - 6.0F, isHi,
                    inRect(mx, my, rx, infoY + 3.0F, hw, infoH - 6.0F));
            hit(rx, infoY + 3.0F, hw, infoH - 6.0F, () -> {
                PassLevelSpec target = getOrCreateExplicitLevel(pass, selectedLevel);
                target.highlight = !isHi;
                dirty = true;
                boundContextKey = "";
                QuestClient.playClickSound(1.04F);
            });
        }

        // 左右并列双轨可视化奖励大卡片
        float cardsY = infoY + infoH + 5.0F;
        float cardsH = Math.max(80.0F, h - (cardsY - y));
        float colW = (w - 8.0F) / 2.0F;

        renderVisualTrackEditorCard(c, f, items,
                pass.freeTrackName,
                !pickTargetPremium,
                () -> pickTargetPremium = false,
                freeBundle,
                0,
                () -> selectedLevel >= 1 ? getOrCreateExplicitLevel(pass, selectedLevel).freeReward : freeBundle,
                x, cardsY, colW, cardsH, mx, my, dt);

        renderVisualTrackEditorCard(c, f, items,
                pass.premiumTrack.trackName,
                pickTargetPremium,
                () -> pickTargetPremium = true,
                premBundle,
                1,
                () -> selectedLevel >= 1 ? getOrCreateExplicitLevel(pass, selectedLevel).premiumReward : premBundle,
                x + colW + 8.0F, cardsY, colW, cardsH, mx, my, dt);

        // 右侧常驻物品选择器
        String targetName = pickTargetPremium ? pass.premiumTrack.trackName : pass.freeTrackName;
        renderRightItemDock(c, f, items, "添加至：" + targetName, spec -> {
            RewardBundleSpec dest = selectedLevel >= 1
                    ? (pickTargetPremium
                    ? getOrCreateExplicitLevel(pass, selectedLevel).premiumReward
                    : getOrCreateExplicitLevel(pass, selectedLevel).freeReward)
                    : (pickTargetPremium ? premBundle : freeBundle);
            appendOrMergeItem(dest, spec);
            dirty = true;
            boundContextKey = "";
        }, dockX, y, dockW, h, mx, my, dt);
    }

    private void renderVisualTrackEditorCard(Canvas c, AddonFrame f, ItemOverlay items,
                                             String title, boolean activeTarget, Runnable onSelect,
                                             RewardBundleSpec bundle, int moneyFieldIdx,
                                             BundleMutator mutator,
                                             float x, float y, float w, float h,
                                             float mx, float my, float dt) {
        Draw.fill(c, x, y, w, h, 6.0F,
                activeTarget ? Draw.lerp(ShopTheme.card(), ShopTheme.accentTint(), 0.22F) : ShopTheme.card());
        Draw.stroke(c, x, y, w, h, 6.0F, activeTarget ? 1.2F : 0.7F,
                activeTarget ? ShopTheme.accent() : ShopTheme.borderDim());

        // 标题头 + 右侧添加目标提示
        V2.bold(c, (activeTarget ? "● " : "○ ") + title, 7.2F,
                activeTarget ? ShopTheme.accent() : ShopTheme.text(),
                x + 10.0F, y + 13.0F, w * 0.52F, Text.Align.LEFT);
        String targetTag = activeTarget ? "← 右侧点物品将加入此处" : "点击切换为添加目标";
        V2.text(c, targetTag, 5.4F, activeTarget ? ShopTheme.accent() : ShopTheme.text3(),
                x + 10.0F, y + 13.0F, w - 20.0F, Text.Align.RIGHT);
        hit(x, y, w, 24.0F, () -> {
            onSelect.run();
            QuestClient.playClickSound(1.02F);
        });

        // 原地星币输入框 + 人类快捷预设胶囊
        float mRowY = y + 26.0F;
        V2.bold(c, "奖励星币", 6.2F, ShopTheme.text2(), x + 10.0F, mRowY + 8.5F, 52.0F, Text.Align.LEFT);
        float tfX = x + 56.0F;
        float tfW = Math.max(54.0F, w - 56.0F - 102.0F);
        fields[moneyFieldIdx].bounds(tfX, mRowY, tfW, 17.0F);
        fields[moneyFieldIdx].visible(true);
        fields[moneyFieldIdx].render(c, mx, my, dt);

        String[] presets = {"清零", "+50", "+200"};
        long[] deltas = {0L, 50L, 200L};
        float px = tfX + tfW + 4.0F;
        for (int i = 0; i < presets.length; i++) {
            float pw = 29.0F;
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
        V2.bold(c, "奖励物品列表（共 " + (bundle.items == null ? 0 : bundle.items.size()) + " 项）",
                6.0F, ShopTheme.text2(), x + 10.0F, mRowY + 31.0F, w - 20.0F, Text.Align.LEFT);

        float listTop = mRowY + 40.0F;
        float listH = y + h - listTop - 6.0F;
        renderRichItemCardsList(c, items, bundle, mutator, x + 8.0F, listTop, w - 16.0F, listH, mx, my);
    }

    // ================================================================ 2. 【通行证设置】原地多框直填工作台

    private void renderPassSettingsStudio(Canvas c, AddonFrame f, ItemOverlay items,
                                          float x, float y, float w, float h,
                                          float dockX, float dockW, float mx, float my, float dt) {
        SeasonPassConfig pass = QuestClientState.editPass();
        PointsRuleSpec rule = pass.pointsRule;
        PremiumTrackSpec prem = pass.premiumTrack;

        String ctx = "tab1:" + pass.seasonId;
        if (!ctx.equals(boundContextKey)) {
            boundContextKey = ctx;
            setFieldSilently(0, pass.seasonName, "第 1 赛季", v -> { if (!v.isBlank()) pass.seasonName = v.trim(); });
            setFieldSilently(1, String.valueOf(rule.maxLevel), "30", v -> rule.maxLevel = parseIntClamp(v, rule.maxLevel, 1, 200));
            setFieldSilently(2, String.valueOf(rule.basePointsPerLevel), "100", v -> rule.basePointsPerLevel = parseLongClamp(v, rule.basePointsPerLevel, 10L, 100000L));
            setFieldSilently(3, String.valueOf(rule.buyLevelStarCoinCost), "150", v -> rule.buyLevelStarCoinCost = parseLongClamp(v, rule.buyLevelStarCoinCost, 1L, 9999999L));
            setFieldSilently(4, String.valueOf(prem.unlockStarCoinCost), "2880", v -> prem.unlockStarCoinCost = parseLongClamp(v, prem.unlockStarCoinCost, 0L, 99999999L));
            setFieldSilently(5, String.valueOf(prem.unlockBonusPoints), "200", v -> prem.unlockBonusPoints = parseLongClamp(v, prem.unlockBonusPoints, 0L, 999999L));
            setFieldSilently(6, String.valueOf(prem.deluxeStarCoinCost), "4880", v -> {
                prem.deluxeStarCoinCost = parseLongClamp(v, prem.deluxeStarCoinCost, prem.unlockStarCoinCost, 99999999L);
                if (prem.deluxeOriginalStarCoinCost < prem.deluxeStarCoinCost) prem.deluxeOriginalStarCoinCost = prem.deluxeStarCoinCost;
            });
            setFieldSilently(7, String.valueOf(prem.deluxeOriginalStarCoinCost), "5880", v ->
                    prem.deluxeOriginalStarCoinCost = parseLongClamp(v, prem.deluxeOriginalStarCoinCost, prem.deluxeStarCoinCost, 99999999L));
            setFieldSilently(8, String.valueOf(prem.deluxeExtraReward.money), "680", v ->
                    prem.deluxeExtraReward.money = parseLongClamp(v, prem.deluxeExtraReward.money, 0L, 99999999L));
        }
        hideFormFieldsExcept(9, pickerCatalogMode);

        float colW = (w - 8.0F) / 2.0F;

        // 左栏：赛季周期、满级积分节奏与买级设置
        Draw.fill(c, x, y, colW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, x, y, colW, h, 6.0F, 0.7F, ShopTheme.borderDim());
        V2.bold(c, "赛季节奏与等级设置", 7.4F, ShopTheme.text(), x + 10.0F, y + 13.0F, colW - 20.0F, Text.Align.LEFT);

        float ly = y + 26.0F;
        renderDirectInputRow(c, "当前赛季名称", 0, "", x + 10.0F, ly, colW - 20.0F, mx, my, dt, null, null);
        ly += 23.0F;

        renderDirectInputRow(c, "最高等级", 1, "级", x + 10.0F, ly, colW - 20.0F, mx, my, dt,
                () -> { rule.maxLevel = Math.max(1, rule.maxLevel - 5); updateFieldValueAndModel(1, String.valueOf(rule.maxLevel)); },
                () -> { rule.maxLevel = Math.min(200, rule.maxLevel + 5); updateFieldValueAndModel(1, String.valueOf(rule.maxLevel)); });
        ly += 23.0F;

        renderDirectInputRow(c, "每级基础积分", 2, "分", x + 10.0F, ly, colW - 20.0F, mx, my, dt,
                () -> { rule.basePointsPerLevel = Math.max(10L, rule.basePointsPerLevel - 20L); updateFieldValueAndModel(2, String.valueOf(rule.basePointsPerLevel)); },
                () -> { rule.basePointsPerLevel += 20L; updateFieldValueAndModel(2, String.valueOf(rule.basePointsPerLevel)); });
        ly += 21.0F;

        // 大白话自动换算满级所需总积分预览
        List<PassLevelSpec> previewLv = pass.resolvedLevels();
        long maxTotalPts = previewLv.isEmpty() ? 0L : previewLv.get(previewLv.size() - 1).requiredPoints;
        V2.text(c, "→ 按当前规则，升满 " + rule.maxLevel + " 级共需累计 " + V2.money(maxTotalPts) + " 积分",
                5.5F, ShopTheme.accent(), x + 10.0F, ly + 5.0F, colW - 20.0F, Text.Align.LEFT);
        ly += 16.0F;

        Draw.hairline(c, x + 10.0F, ly, colW - 20.0F, ShopTheme.borderDim());
        ly += 7.0F;

        renderToggleRow(c, "允许玩家用星币购买等级", rule.allowBuyLevels, x + 10.0F, ly, colW - 20.0F, mx, my,
                () -> { rule.allowBuyLevels = !rule.allowBuyLevels; dirty = true; });
        ly += 22.0F;

        renderDirectInputRow(c, "买 1 级价格", 3, "星币", x + 10.0F, ly, colW - 20.0F, mx, my, dt,
                () -> { rule.buyLevelStarCoinCost = Math.max(1L, rule.buyLevelStarCoinCost - 25L); updateFieldValueAndModel(3, String.valueOf(rule.buyLevelStarCoinCost)); },
                () -> { rule.buyLevelStarCoinCost += 25L; updateFieldValueAndModel(3, String.valueOf(rule.buyLevelStarCoinCost)); });
        ly += 26.0F;

        Draw.hairline(c, x + 10.0F, ly, colW - 20.0F, ShopTheme.borderDim());
        ly += 8.0F;

        // 一键开启新赛季按钮（不用玩家手动理解 seasonId！）
        V2.text(c, "换季操作（自动归档旧季宽限补领并按 " + pass.rolloverPercent + "% 结转积分）：",
                5.6F, ShopTheme.text2(), x + 10.0F, ly + 6.0F, colW - 20.0F, Text.Align.LEFT);
        ly += 15.0F;
        float newSeasonBtnW = colW - 20.0F;
        drawChip(c, "一键开启下一赛季（准备好后点右上保存生效）", x + 10.0F, ly, newSeasonBtnW, 18.0F, false,
                inRect(mx, my, x + 10.0F, ly, newSeasonBtnW, 18.0F));
        hit(x + 10.0F, ly, newSeasonBtnW, 18.0F, () -> {
            pass.seasonId = "season_" + (System.currentTimeMillis() / 1000L);
            pass.seasonName = bumpSeasonTitle(pass.seasonName);
            pass.seasonStartEpochSecond = System.currentTimeMillis() / 1000L;
            dirty = true;
            boundContextKey = "";
            QuestClient.playClickSound(1.12F);
        });

        // 右栏：进阶通行证 + 典藏通行证独立定价与礼包
        float rx = x + colW + 8.0F;
        Draw.fill(c, rx, y, colW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, rx, y, colW, h, 6.0F, 0.7F, ShopTheme.borderDim());
        V2.bold(c, "进阶版与典藏版定价及额外礼包", 7.4F, ShopTheme.text(), rx + 10.0F, y + 13.0F, colW - 20.0F, Text.Align.LEFT);

        float ry = y + 26.0F;
        renderDirectInputRow(c, "进阶版解锁价", 4, "星币", rx + 10.0F, ry, colW - 20.0F, mx, my, dt, null, null);
        ry += 22.0F;

        renderDirectInputRow(c, "进阶立送积分", 5, "分", rx + 10.0F, ry, colW - 20.0F, mx, my, dt, null, null);
        ry += 23.0F;

        renderToggleRow(c, "开启第二档【典藏通行证】", prem.deluxeEnabled, rx + 10.0F, ry, colW - 20.0F, mx, my,
                () -> { prem.deluxeEnabled = !prem.deluxeEnabled; dirty = true; });
        ry += 21.0F;

        renderDirectInputRow(c, "典藏版实际售价", 6, "星币", rx + 10.0F, ry, colW - 20.0F, mx, my, dt, null, null);
        ry += 22.0F;

        renderDirectInputRow(c, "典藏划线原价", 7, "星币", rx + 10.0F, ry, colW - 20.0F, mx, my, dt, null, null);
        ry += 22.0F;

        // 直升等级步进条
        V2.text(c, "典藏立即直升等级", 6.0F, ShopTheme.text2(), rx + 10.0F, ry + 8.5F, colW * 0.5F, Text.Align.LEFT);
        float stepRight = rx + colW - 10.0F;
        drawChip(c, "+", stepRight - 18.0F, ry + 1.0F, 18.0F, 15.0F, false, inRect(mx, my, stepRight - 18.0F, ry + 1.0F, 18.0F, 15.0F));
        hit(stepRight - 18.0F, ry + 1.0F, 18.0F, 15.0F, () -> { prem.deluxeBonusLevels = Math.min(100, prem.deluxeBonusLevels + 1); dirty = true; QuestClient.playClickSound(1.05F); });
        drawChip(c, "+" + prem.deluxeBonusLevels + " 级", stepRight - 66.0F, ry + 1.0F, 45.0F, 15.0F, true, false);
        drawChip(c, "-", stepRight - 87.0F, ry + 1.0F, 18.0F, 15.0F, false, inRect(mx, my, stepRight - 87.0F, ry + 1.0F, 18.0F, 15.0F));
        hit(stepRight - 87.0F, ry + 1.0F, 18.0F, 15.0F, () -> { prem.deluxeBonusLevels = Math.max(0, prem.deluxeBonusLevels - 1); dirty = true; QuestClient.playClickSound(0.98F); });
        ry += 21.0F;

        renderDirectInputRow(c, "典藏额外送星币", 8, "星币", rx + 10.0F, ry, colW - 20.0F, mx, my, dt, null, null);
        ry += 22.0F;

        V2.bold(c, "典藏额外礼包物品（右侧点物品直接加入）：", 5.8F, ShopTheme.text2(), rx + 10.0F, ry + 6.0F, colW - 20.0F, Text.Align.LEFT);
        ry += 13.0F;
        renderRichItemCardsList(c, items, prem.deluxeExtraReward, () -> prem.deluxeExtraReward,
                rx + 8.0F, ry, colW - 16.0F, Math.max(36.0F, y + h - ry - 5.0F), mx, my);

        renderRightItemDock(c, f, items, "添加至：典藏额外礼包", spec -> {
            appendOrMergeItem(prem.deluxeExtraReward, spec);
            dirty = true;
        }, dockX, y, dockW, h, mx, my, dt);
    }

    // ================================================================ 3. 【每日与每周任务】零术语人性化设计器

    private void renderQuestDesignerStudio(Canvas c, AddonFrame f, ItemOverlay items,
                                           float x, float y, float w, float h,
                                           float dockX, float dockW, float mx, float my, float dt) {
        QuestPoolsConfig pools = QuestClientState.editPools();
        List<QuestDefSpec> list = editWeeklyPool ? pools.weeklyPool : pools.dailyPool;
        if (selectedQuestIdx >= list.size()) {
            selectedQuestIdx = Math.max(0, list.size() - 1);
        }

        float leftW = clamp(w * 0.30F, 136.0F, 178.0F);
        float midX = x + leftW + 8.0F;
        float midW = w - leftW - 8.0F;

        // 1. 左栏：清爽双行任务卡片列表
        Draw.fill(c, x, y, leftW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, x, y, leftW, h, 6.0F, 0.7F, ShopTheme.borderDim());

        float halfToggleW = (leftW - 14.0F) / 2.0F;
        drawChip(c, "每日任务 (" + pools.dailyPool.size() + ")", x + 5.0F, y + 5.0F, halfToggleW, 16.0F, !editWeeklyPool,
                inRect(mx, my, x + 5.0F, y + 5.0F, halfToggleW, 16.0F));
        hit(x + 5.0F, y + 5.0F, halfToggleW, 16.0F, () -> { editWeeklyPool = false; selectedQuestIdx = 0; boundContextKey = ""; });

        drawChip(c, "每周任务 (" + pools.weeklyPool.size() + ")", x + 9.0F + halfToggleW, y + 5.0F, halfToggleW, 16.0F, editWeeklyPool,
                inRect(mx, my, x + 9.0F + halfToggleW, y + 5.0F, halfToggleW, 16.0F));
        hit(x + 9.0F + halfToggleW, y + 5.0F, halfToggleW, 16.0F, () -> { editWeeklyPool = true; selectedQuestIdx = 0; boundContextKey = ""; });

        float addBtnY = y + 24.0F;
        V2.button(c, "+ 新建一条任务", V2.Btn.NORMAL, x + 5.0F, addBtnY, leftW - 10.0F, 16.0F,
                inRect(mx, my, x + 5.0F, addBtnY, leftW - 10.0F, 16.0F) ? 1.0F : 0.0F);
        hit(x + 5.0F, addBtnY, leftW - 10.0F, 16.0F, () -> {
            String newId = (editWeeklyPool ? "w_task_" : "d_task_") + (System.currentTimeMillis() % 100000L);
            list.add(new QuestDefSpec(newId, "商店购买", editWeeklyPool ? "本周累计购买 5 次商品" : "在商店购买 1 次商品",
                    "custom_" + (list.size() + 1), "module_shop", QuestTaskType.SHOP_BUY_ANY, "",
                    editWeeklyPool ? 5 : 1, 15,
                    new RewardBundleSpec(editWeeklyPool ? 180L : 40L, editWeeklyPool ? 180L : 40L, List.of())));
            selectedQuestIdx = list.size() - 1;
            dirty = true;
            boundContextKey = "";
            QuestClient.playClickSound(1.08F);
        });

        leftListX = x + 5.0F;
        leftListY = y + 44.0F;
        leftListW = leftW - 10.0F;
        leftListH = h - 48.0F;
        float itemCardH = 29.0F;
        LEFT_LIST_SCROLL.setContent(list.size() * itemCardH, leftListH);

        int save = c.save();
        Draw.clip(c, leftListX, leftListY, leftListW, leftListH);
        for (int i = 0; i < list.size(); i++) {
            float ry = leftListY + i * itemCardH - LEFT_LIST_SCROLL.offset();
            if (ry + itemCardH < leftListY || ry > leftListY + leftListH) continue;
            QuestDefSpec q = list.get(i);
            boolean sel = i == selectedQuestIdx;
            boolean over = my >= leftListY && my < leftListY + leftListH
                    && inRect(mx, my, leftListX, ry, leftListW, itemCardH - 2.5F);
            int bg = sel ? Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.30F : 0.16F)
                    : (over ? Draw.alpha(ShopTheme.accent(), 0.08F) : Draw.alpha(ShopTheme.text3(), 0.06F));
            Draw.fill(c, leftListX, ry, leftListW, itemCardH - 2.5F, 4.0F, bg);
            Draw.stroke(c, leftListX, ry, leftListW, itemCardH - 2.5F, 4.0F, sel ? 0.9F : 0.5F,
                    sel ? ShopTheme.accent() : ShopTheme.borderDim());

            V2.bold(c, q.title, 6.2F, sel ? ShopTheme.accent() : ShopTheme.text(),
                    leftListX + 6.0F, ry + 8.5F, leftListW - 12.0F, Text.Align.LEFT);
            long rm = q.reward != null ? q.reward.money : 0L;
            long rp = q.reward != null ? q.reward.points : 0L;
            String sub = "目标 " + q.targetCount + " 次 · +" + rm + "★ +" + rp + "分";
            V2.text(c, sub, 5.1F, ShopTheme.text3(), leftListX + 6.0F, ry + 19.5F, leftListW - 12.0F, Text.Align.LEFT);

            if (my >= leftListY && my < leftListY + leftListH) {
                final int pick = i;
                hit(leftListX, ry, leftListW, itemCardH - 2.5F, () -> {
                    selectedQuestIdx = pick;
                    boundContextKey = "";
                    QuestClient.playClickSound(1.03F);
                });
            }
        }
        c.restoreToCount(save);

        // 2. 中栏：直观可视化任务表单（独立输入框 + 分类行为按钮墙 + 概率档位 + 奖励卡片）
        Draw.fill(c, midX, y, midW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, midX, y, midW, h, 6.0F, 0.7F, ShopTheme.borderDim());

        if (list.isEmpty()) {
            hideFormFieldsExcept(0, pickerCatalogMode);
            V2.empty(c, "点击左侧「+ 新建一条任务」开始创建", midX, y, midW, h);
            return;
        }

        QuestDefSpec q = list.get(selectedQuestIdx);
        if (q.reward == null) q.reward = new RewardBundleSpec();
        QuestTaskType curType = q.parsedType() != null ? q.parsedType() : QuestTaskType.SHOP_SIGN_IN;
        boolean supportsFilter = supportsTargetFilter(curType);

        String ctx = "tab2:" + editWeeklyPool + ":" + selectedQuestIdx + ":" + q.id;
        if (!ctx.equals(boundContextKey)) {
            boundContextKey = ctx;
            setFieldSilently(0, q.title, "例如：商店签到", v -> { if (!v.isBlank()) q.title = v.trim(); });
            setFieldSilently(1, q.description, "向玩家展示的目标说明", v -> q.description = v.trim());
            setFieldSilently(2, String.valueOf(q.targetCount), "1", v -> q.targetCount = parseIntClamp(v, q.targetCount, 1, 99999));
            setFieldSilently(3, q.targetFilter, "留空代表不限目标", v -> q.targetFilter = v.trim());
            setFieldSilently(4, String.valueOf(q.reward.money), "40", v -> q.reward.money = parseLongClamp(v, q.reward.money, 0L, 99999999L));
            setFieldSilently(5, String.valueOf(q.reward.points), "40", v -> q.reward.points = parseLongClamp(v, q.reward.points, 0L, 999999L));
        }
        hideFormFieldsExcept(6, pickerCatalogMode);
        if (!supportsFilter) {
            fields[3].visible(false);
        }

        float padX = midX + 10.0F;
        float innerW = midW - 20.0F;
        float cy = y + 8.0F;

        // 顶栏：当前任务标题 + 一键自动生成文案 + 删除按钮
        V2.bold(c, "任务设置", 7.2F, ShopTheme.text(), padX, cy + 7.0F, 70.0F, Text.Align.LEFT);
        float topBtnRight = midX + midW - 10.0F;
        if (list.size() > 1) {
            float delW = 46.0F;
            topBtnRight -= delW;
            drawChip(c, "删除任务", topBtnRight, cy, delW, 15.0F, false, inRect(mx, my, topBtnRight, cy, delW, 15.0F));
            hit(topBtnRight, cy, delW, 15.0F, () -> {
                list.remove(selectedQuestIdx);
                selectedQuestIdx = Math.max(0, selectedQuestIdx - 1);
                dirty = true;
                boundContextKey = "";
                QuestClient.playClickSound(0.92F);
            });
            topBtnRight -= 5.0F;
        }
        float autoTextW = 74.0F;
        topBtnRight -= autoTextW;
        drawChip(c, "自动填写名称与描述", topBtnRight, cy, autoTextW, 15.0F, false, inRect(mx, my, topBtnRight, cy, autoTextW, 15.0F));
        hit(topBtnRight, cy, autoTextW, 15.0F, () -> {
            applySuggestedTitleAndDesc(q, editWeeklyPool);
            updateFieldValueAndModel(0, q.title);
            updateFieldValueAndModel(1, q.description);
            QuestClient.playClickSound(1.06F);
        });
        cy += 19.0F;

        // 行 1：任务名称 + 任务说明并排直输！
        float nameBoxW = Math.min(118.0F, innerW * 0.34F);
        V2.text(c, "名称", 5.8F, ShopTheme.text2(), padX, cy + 8.5F, 26.0F, Text.Align.LEFT);
        fields[0].bounds(padX + 24.0F, cy, nameBoxW - 24.0F, 17.0F);
        fields[0].visible(true);
        fields[0].render(c, mx, my, dt);

        float descLblX = padX + nameBoxW + 6.0F;
        V2.text(c, "说明", 5.8F, ShopTheme.text2(), descLblX, cy + 8.5F, 26.0F, Text.Align.LEFT);
        fields[1].bounds(descLblX + 24.0F, cy, innerW - (nameBoxW + 6.0F) - 24.0F, 17.0F);
        fields[1].visible(true);
        fields[1].render(c, mx, my, dt);
        cy += 21.0F;

        // 行 2：直观行为类型点选矩阵（不用再盲点 +/- 翻页！）
        V2.bold(c, "玩家需要完成什么行为？（点击直接选择）", 5.8F, ShopTheme.text2(), padX, cy + 5.0F, innerW, Text.Align.LEFT);
        cy += 11.0F;
        cy = renderActionPillsWrap(c, q, SHOP_ACTIONS, padX, cy, innerW, mx, my);
        cy = renderActionPillsWrap(c, q, ADDON_ACTIONS, padX, cy, innerW, mx, my);
        cy = renderActionPillsWrap(c, q, VANILLA_ACTIONS, padX, cy, innerW, mx, my);
        cy += 3.0F;

        // 行 3：需要次数 + 刷新抽中概率档位（全人类语言！）
        V2.text(c, "需要次数", 5.8F, ShopTheme.text2(), padX, cy + 8.5F, 44.0F, Text.Align.LEFT);
        drawChip(c, "-", padX + 42.0F, cy + 1.0F, 15.0F, 15.0F, false, inRect(mx, my, padX + 42.0F, cy + 1.0F, 15.0F, 15.0F));
        hit(padX + 42.0F, cy + 1.0F, 15.0F, 15.0F, () -> {
            q.targetCount = Math.max(1, q.targetCount - 1);
            updateFieldValueAndModel(2, String.valueOf(q.targetCount));
            QuestClient.playClickSound(0.98F);
        });
        fields[2].bounds(padX + 59.0F, cy, 38.0F, 17.0F);
        fields[2].visible(true);
        fields[2].render(c, mx, my, dt);
        drawChip(c, "+", padX + 99.0F, cy + 1.0F, 15.0F, 15.0F, false, inRect(mx, my, padX + 99.0F, cy + 1.0F, 15.0F, 15.0F));
        hit(padX + 99.0F, cy + 1.0F, 15.0F, 15.0F, () -> {
            q.targetCount++;
            updateFieldValueAndModel(2, String.valueOf(q.targetCount));
            QuestClient.playClickSound(1.04F);
        });

        // 出现概率四档胶囊
        float probX = padX + 124.0F;
        V2.text(c, "抽中概率", 5.8F, ShopTheme.text2(), probX, cy + 8.5F, 44.0F, Text.Align.LEFT);
        String[] wLabels = {"少见", "普通", "常见", "高频"};
        int[] wVals = {6, 14, 22, 35};
        float wx = probX + 42.0F;
        for (int i = 0; i < wLabels.length; i++) {
            float ww = Math.min(32.0F, (innerW - (wx - padX) - (3 - i) * 3.0F) / (4 - i));
            boolean activeW = isWeightBucket(q.weight, i);
            drawChip(c, wLabels[i], wx, cy + 1.0F, ww, 15.0F, activeW, inRect(mx, my, wx, cy + 1.0F, ww, 15.0F));
            final int wSet = wVals[i];
            hit(wx, cy + 1.0F, ww, 15.0F, () -> { q.weight = wSet; dirty = true; QuestClient.playClickSound(1.03F); });
            wx += ww + 3.0F;
        }
        cy += 20.0F;

        // 行 4：智能目标过滤（仅在支持过滤的任务类型下显示人性化预设！）
        if (supportsFilter) {
            V2.text(c, "指定目标", 5.8F, ShopTheme.text2(), padX, cy + 8.5F, 44.0F, Text.Align.LEFT);
            float fx = padX + 42.0F;
            String[][] presets = filterPresetsFor(curType);
            for (String[] pr : presets) {
                float pw = V2.width(pr[0], 5.4F, true) + 10.0F;
                boolean act = (q.targetFilter == null ? "" : q.targetFilter).equals(pr[1]);
                drawChip(c, pr[0], fx, cy + 1.0F, pw, 15.0F, act, inRect(mx, my, fx, cy + 1.0F, pw, 15.0F));
                final String val = pr[1];
                hit(fx, cy + 1.0F, pw, 15.0F, () -> {
                    q.targetFilter = val;
                    updateFieldValueAndModel(3, val);
                    QuestClient.playClickSound(1.03F);
                });
                fx += pw + 3.0F;
            }
            float remFilterW = Math.max(48.0F, padX + innerW - fx);
            fields[3].bounds(fx, cy, remFilterW, 17.0F);
            fields[3].visible(true);
            fields[3].render(c, mx, my, dt);
            cy += 20.0F;
        }

        Draw.hairline(c, padX, cy + 1.0F, innerW, ShopTheme.borderDim());
        cy += 5.0F;

        // 行 5：独立奖励星币框 + 独立奖励积分框
        float halfRw = (innerW - 10.0F) / 2.0F;
        V2.bold(c, "奖励星币", 5.8F, ShopTheme.text2(), padX, cy + 8.5F, 44.0F, Text.Align.LEFT);
        fields[4].bounds(padX + 42.0F, cy, halfRw - 74.0F, 17.0F);
        fields[4].visible(true);
        fields[4].render(c, mx, my, dt);
        drawChip(c, "+20", padX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F, false, inRect(mx, my, padX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F));
        hit(padX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F, () -> {
            q.reward.money += 20L;
            updateFieldValueAndModel(4, String.valueOf(q.reward.money));
            QuestClient.playClickSound(1.04F);
        });

        float ptX = padX + halfRw + 10.0F;
        V2.bold(c, "奖励积分", 5.8F, ShopTheme.text2(), ptX, cy + 8.5F, 44.0F, Text.Align.LEFT);
        fields[5].bounds(ptX + 42.0F, cy, halfRw - 74.0F, 17.0F);
        fields[5].visible(true);
        fields[5].render(c, mx, my, dt);
        drawChip(c, "+20", ptX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F, false, inRect(mx, my, ptX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F));
        hit(ptX + halfRw - 29.0F, cy + 1.0F, 28.0F, 15.0F, () -> {
            q.reward.points += 20L;
            updateFieldValueAndModel(5, String.valueOf(q.reward.points));
            QuestClient.playClickSound(1.04F);
        });
        cy += 20.0F;

        // 行 6：可视化物品奖励卡片区
        float remItemsH = Math.max(30.0F, y + h - cy - 5.0F);
        renderRichItemCardsList(c, items, q.reward, () -> q.reward, padX, cy, innerW, remItemsH, mx, my);

        // 3. 右栏：常驻物品库 / 背包点选器
        renderRightItemDock(c, f, items, "添加至：" + q.title, spec -> {
            appendOrMergeItem(q.reward, spec);
            dirty = true;
        }, dockX, y, dockW, h, mx, my, dt);
    }

    private float renderActionPillsWrap(Canvas c, QuestDefSpec q, QuestTaskType[] types,
                                        float x, float startY, float maxW, float mx, float my) {
        float cx = x;
        float cy = startY;
        QuestTaskType active = q.parsedType();
        for (QuestTaskType t : types) {
            String lbl = shortTaskLabel(t);
            float pw = V2.width(lbl, 5.3F, true) + 9.0F;
            if (cx + pw > x + maxW && cx > x) {
                cx = x;
                cy += 16.0F;
            }
            boolean isSel = t == active;
            drawChip(c, lbl, cx, cy, pw, 14.0F, isSel, inRect(mx, my, cx, cy, pw, 14.0F));
            hit(cx, cy, pw, 14.0F, () -> {
                setQuestTaskType(q, t);
                dirty = true;
                boundContextKey = "";
                QuestClient.playClickSound(1.04F);
            });
            cx += pw + 3.0F;
        }
        return cy + 16.5F;
    }

    // ================================================================ 4. 【在线时长奖励】人性化工作台

    private void renderOnlineDesignerStudio(Canvas c, AddonFrame f, ItemOverlay items,
                                            float x, float y, float w, float h,
                                            float dockX, float dockW, float mx, float my, float dt) {
        OnlineRewardsConfig online = QuestClientState.editOnline();
        List<OnlineMilestoneSpec> list = editWeeklyOnline ? online.weeklyMilestones : online.dailyMilestones;
        if (selectedOnlineIdx >= list.size()) {
            selectedOnlineIdx = Math.max(0, list.size() - 1);
        }

        float leftW = clamp(w * 0.30F, 136.0F, 178.0F);
        float midX = x + leftW + 8.0F;
        float midW = w - leftW - 8.0F;

        Draw.fill(c, x, y, leftW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, x, y, leftW, h, 6.0F, 0.7F, ShopTheme.borderDim());

        float halfW = (leftW - 14.0F) / 2.0F;
        drawChip(c, "每日在线 (" + online.dailyMilestones.size() + ")", x + 5.0F, y + 5.0F, halfW, 16.0F, !editWeeklyOnline,
                inRect(mx, my, x + 5.0F, y + 5.0F, halfW, 16.0F));
        hit(x + 5.0F, y + 5.0F, halfW, 16.0F, () -> { editWeeklyOnline = false; selectedOnlineIdx = 0; boundContextKey = ""; });

        drawChip(c, "每周在线 (" + online.weeklyMilestones.size() + ")", x + 9.0F + halfW, y + 5.0F, halfW, 16.0F, editWeeklyOnline,
                inRect(mx, my, x + 9.0F + halfW, y + 5.0F, halfW, 16.0F));
        hit(x + 9.0F + halfW, y + 5.0F, halfW, 16.0F, () -> { editWeeklyOnline = true; selectedOnlineIdx = 0; boundContextKey = ""; });

        V2.button(c, "+ 新增一档时长奖励", V2.Btn.NORMAL, x + 5.0F, y + 24.0F, leftW - 10.0F, 16.0F,
                inRect(mx, my, x + 5.0F, y + 24.0F, leftW - 10.0F, 16.0F) ? 1.0F : 0.0F);
        hit(x + 5.0F, y + 24.0F, leftW - 10.0F, 16.0F, () -> {
            int nextMin = list.isEmpty() ? 15 : (list.get(list.size() - 1).requiredMinutes + (editWeeklyOnline ? 60 : 15));
            String id = (editWeeklyOnline ? "week_" : "day_") + nextMin + "m_" + (System.currentTimeMillis() % 10000L);
            String title = (editWeeklyOnline ? "本周在线 " : "今日在线 ") + nextMin + " 分钟";
            list.add(new OnlineMilestoneSpec(id, title, nextMin, new RewardBundleSpec(60L, 40L, List.of())));
            selectedOnlineIdx = list.size() - 1;
            dirty = true;
            boundContextKey = "";
            QuestClient.playClickSound(1.08F);
        });

        for (int i = 0; i < list.size(); i++) {
            float ry = y + 44.0F + i * 31.0F;
            if (ry + 29.0F > y + h) break;
            OnlineMilestoneSpec m = list.get(i);
            boolean sel = i == selectedOnlineIdx;
            boolean over = inRect(mx, my, x + 5.0F, ry, leftW - 10.0F, 28.0F);
            Draw.fill(c, x + 5.0F, ry, leftW - 10.0F, 28.0F, 4.0F,
                    sel ? Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.30F : 0.16F)
                    : (over ? Draw.alpha(ShopTheme.accent(), 0.08F) : Draw.alpha(ShopTheme.text3(), 0.06F)));
            Draw.stroke(c, x + 5.0F, ry, leftW - 10.0F, 28.0F, 4.0F, sel ? 0.9F : 0.5F,
                    sel ? ShopTheme.accent() : ShopTheme.borderDim());
            V2.bold(c, formatHumanDuration(m.requiredMinutes) + " · " + m.title, 6.0F,
                    sel ? ShopTheme.accent() : ShopTheme.text(), x + 11.0F, ry + 9.0F, leftW - 22.0F, Text.Align.LEFT);
            long rm = m.reward != null ? m.reward.money : 0L;
            long rp = m.reward != null ? m.reward.points : 0L;
            V2.text(c, "+" + rm + " 星币 · +" + rp + " 积分", 5.1F, ShopTheme.text3(),
                    x + 11.0F, ry + 19.5F, leftW - 22.0F, Text.Align.LEFT);
            final int pick = i;
            hit(x + 5.0F, ry, leftW - 10.0F, 28.0F, () -> {
                selectedOnlineIdx = pick;
                boundContextKey = "";
                QuestClient.playClickSound(1.03F);
            });
        }

        Draw.fill(c, midX, y, midW, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, midX, y, midW, h, 6.0F, 0.7F, ShopTheme.borderDim());
        if (list.isEmpty()) {
            hideFormFieldsExcept(0, pickerCatalogMode);
            return;
        }

        OnlineMilestoneSpec m = list.get(selectedOnlineIdx);
        if (m.reward == null) m.reward = new RewardBundleSpec();

        String ctx = "tab3:" + editWeeklyOnline + ":" + selectedOnlineIdx + ":" + m.id;
        if (!ctx.equals(boundContextKey)) {
            boundContextKey = ctx;
            setFieldSilently(0, m.title, "今日在线 15 分钟", v -> { if (!v.isBlank()) m.title = v.trim(); });
            setFieldSilently(1, String.valueOf(m.requiredMinutes), "15", v -> m.requiredMinutes = parseIntClamp(v, m.requiredMinutes, 1, 10080));
            setFieldSilently(2, String.valueOf(m.reward.money), "40", v -> m.reward.money = parseLongClamp(v, m.reward.money, 0L, 99999999L));
            setFieldSilently(3, String.valueOf(m.reward.points), "35", v -> m.reward.points = parseLongClamp(v, m.reward.points, 0L, 999999L));
        }
        hideFormFieldsExcept(4, pickerCatalogMode);

        float padX = midX + 10.0F;
        float innerW = midW - 20.0F;
        float cy = y + 10.0F;

        V2.bold(c, "在线时长档位设置（" + formatHumanDuration(m.requiredMinutes) + "）",
                7.2F, ShopTheme.text(), padX, cy + 7.0F, innerW - 60.0F, Text.Align.LEFT);
        if (list.size() > 1) {
            float delX = midX + midW - 56.0F;
            drawChip(c, "删除档位", delX, cy, 46.0F, 15.0F, false, inRect(mx, my, delX, cy, 46.0F, 15.0F));
            hit(delX, cy, 46.0F, 15.0F, () -> {
                list.remove(selectedOnlineIdx);
                selectedOnlineIdx = Math.max(0, selectedOnlineIdx - 1);
                dirty = true;
                boundContextKey = "";
                QuestClient.playClickSound(0.92F);
            });
        }
        cy += 22.0F;

        renderDirectInputRow(c, "档位显示名称", 0, "", padX, cy, innerW, mx, my, dt, null, null);
        cy += 23.0F;

        renderDirectInputRow(c, "需要在线时长", 1, "分钟", padX, cy, innerW, mx, my, dt,
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

        renderDirectInputRow(c, "奖励星币数量", 2, "星币", padX, cy, innerW, mx, my, dt,
                () -> { m.reward.money = Math.max(0L, m.reward.money - 20L); updateFieldValueAndModel(2, String.valueOf(m.reward.money)); },
                () -> { m.reward.money += 20L; updateFieldValueAndModel(2, String.valueOf(m.reward.money)); });
        cy += 23.0F;

        renderDirectInputRow(c, "奖励通行证积分", 3, "分", padX, cy, innerW, mx, my, dt,
                () -> { m.reward.points = Math.max(0L, m.reward.points - 15L); updateFieldValueAndModel(3, String.valueOf(m.reward.points)); },
                () -> { m.reward.points += 15L; updateFieldValueAndModel(3, String.valueOf(m.reward.points)); });
        cy += 24.0F;

        V2.bold(c, "档位奖励物品（从右侧物品库或背包点击添加）：", 6.0F, ShopTheme.text2(), padX, cy + 5.0F, innerW, Text.Align.LEFT);
        cy += 13.0F;
        renderRichItemCardsList(c, items, m.reward, () -> m.reward, padX, cy, innerW, Math.max(40.0F, y + h - cy - 6.0F), mx, my);

        renderRightItemDock(c, f, items, "添加至：" + m.title, spec -> {
            appendOrMergeItem(m.reward, spec);
            dirty = true;
        }, dockX, y, dockW, h, mx, my, dt);
    }

    // ================================================================ 右侧常驻【全物品库搜索 + 我的背包】选择器

    private void renderRightItemDock(Canvas c, AddonFrame f, ItemOverlay items,
                                     String targetHeader, Consumer<ItemRewardSpec> onAdd,
                                     float x, float y, float w, float h, float mx, float my, float dt) {
        Draw.fill(c, x, y, w, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, x, y, w, h, 6.0F, 0.7F, ShopTheme.borderDim());

        float pad = 7.0F;
        float ix = x + pad;
        float iw = w - pad * 2.0F;

        // 顶部当前添加目标提示
        Draw.fill(c, ix, y + 6.0F, iw, 15.0F, 3.5F, Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.22F : 0.12F));
        V2.bold(c, targetHeader, 5.8F, ShopTheme.accent(), ix + 5.0F, y + 13.5F, iw - 10.0F, Text.Align.CENTER);

        // 切换：[全物品库搜索] vs [我的背包]
        float swY = y + 25.0F;
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
            fields[SEARCH_IDX].visible(false);
            PICKER_SCROLL.reset();
            QuestClient.playClickSound(1.03F);
        });

        float gridTop = swY + 19.0F;
        var mc = Minecraft.getInstance();
        HolderLookup.Provider regs = mc.level != null ? mc.level.registryAccess() : null;

        List<ItemStack> displayStacks;
        if (pickerCatalogMode) {
            fields[SEARCH_IDX].bounds(ix, gridTop, iw, 17.0F);
            fields[SEARCH_IDX].visible(true);
            fields[SEARCH_IDX].render(c, mx, my, dt);
            gridTop += 21.0F;
            displayStacks = getFilteredCatalogStacks(fields[SEARCH_IDX].value());
        } else {
            fields[SEARCH_IDX].visible(false);
            displayStacks = new ArrayList<>();
            if (mc.player != null) {
                Inventory inv = mc.player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack st = inv.getItem(i);
                    if (st != null && !st.isEmpty()) {
                        displayStacks.add(st);
                    }
                }
            }
        }

        // 底部单次添加数量快捷档：[+1] [+4] [+16] [+64]
        float batchBarH = 20.0F;
        float batchY = y + h - batchBarH - 5.0F;
        if (pickerCatalogMode) {
            V2.text(c, "单次点击：", 5.4F, ShopTheme.text2(), ix, batchY + 10.0F, 44.0F, Text.Align.LEFT);
            int[] batches = {1, 4, 16, 64};
            float bx = ix + 40.0F;
            float bw = (iw - 40.0F - 9.0F) / 4.0F;
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
        } else {
            V2.text(c, "点击背包物品将按原数量与附魔加入", 5.3F, ShopTheme.text3(), ix, batchY + 10.0F, iw, Text.Align.CENTER);
        }

        // 中部可滚动多列物品图标网格
        float gridH = Math.max(40.0F, batchY - gridTop - 3.0F);
        pickerX = ix;
        pickerY = gridTop;
        pickerW = iw;
        pickerH = gridH;

        if (displayStacks.isEmpty()) {
            V2.text(c, pickerCatalogMode ? "没有匹配的物品" : "你的背包目前是空的",
                    5.8F, ShopTheme.text3(), ix, gridTop + gridH / 2.0F, iw, Text.Align.CENTER);
            return;
        }

        float cellSz = 28.0F;
        float gap = 4.0F;
        int cols = Math.max(1, (int) ((iw + gap) / (cellSz + gap)));
        float actualGap = cols > 1 ? (iw - cols * cellSz) / (cols - 1) : 0.0F;
        int numRows = (displayStacks.size() + cols - 1) / cols;
        float contentH = numRows * (cellSz + 4.0F);
        PICKER_SCROLL.setContent(contentH, gridH);
        float offY = PICKER_SCROLL.offset();

        int save = c.save();
        Draw.clip(c, ix, gridTop, iw, gridH);
        if (items != null) {
            items.pushClip(ix, gridTop, iw, gridH);
        }

        for (int i = 0; i < displayStacks.size(); i++) {
            int r = i / cols;
            int col = i % cols;
            float sx = ix + col * (cellSz + actualGap);
            float sy = gridTop + r * (cellSz + 4.0F) - offY;
            if (sy + cellSz < gridTop - 2.0F || sy > gridTop + gridH + 2.0F) continue;

            ItemStack st = displayStacks.get(i);
            boolean over = my >= gridTop && my < gridTop + gridH && inRect(mx, my, sx, sy, cellSz, cellSz);
            Draw.fill(c, sx, sy, cellSz, cellSz, 4.0F,
                    over ? Draw.alpha(ShopTheme.accent(), 0.22F) : Draw.alpha(ShopTheme.text3(), 0.08F));
            Draw.stroke(c, sx, sy, cellSz, cellSz, 4.0F, over ? 1.0F : 0.6F,
                    over ? ShopTheme.accent() : ShopTheme.borderDim());

            if (items != null) {
                items.add(st, sx + 5.0F, sy + 4.0F, 18.0F, false);
                if (over) {
                    items.setHovered(st);
                }
            }
            if (!pickerCatalogMode && st.getCount() > 1) {
                V2.bold(c, "×" + st.getCount(), 4.8F, ShopTheme.text2(), sx, sy + cellSz - 4.0F, cellSz, Text.Align.CENTER);
            }

            if (my >= gridTop && my < gridTop + gridH) {
                final ItemStack picked = st.copy();
                hit(sx, sy, cellSz, cellSz, () -> {
                    ItemRewardSpec spec = QuestDataConfig.fromItemStack(picked, regs);
                    if (pickerCatalogMode) {
                        int maxStack = Math.max(1, picked.getMaxStackSize());
                        spec.count = Math.min(maxStack, pickerBatchCount);
                    }
                    onAdd.accept(spec);
                    QuestClient.playClickSound(1.15F);
                });
            }
        }

        if (items != null) {
            items.popClip();
        }
        c.restoreToCount(save);
    }

    private static List<ItemStack> getFilteredCatalogStacks(String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (!cachedCatalogItems.isEmpty() && q.equals(lastSearchQuery)) {
            return cachedCatalogItems;
        }
        lastSearchQuery = q;
        List<ItemStack> out = new ArrayList<>(120);

        if (q.isEmpty()) {
            for (String idStr : POPULAR_ITEM_IDS) {
                ResourceLocation rl = ResourceLocation.tryParse(idStr.toLowerCase(Locale.ROOT));
                if (rl != null) {
                    Item it = BuiltInRegistries.ITEM.getOptional(rl).orElse(Items.AIR);
                    if (it != Items.AIR) {
                        out.add(new ItemStack(it, 1));
                    }
                }
            }
            for (Item it : BuiltInRegistries.ITEM) {
                if (it == Items.AIR) continue;
                if (out.size() >= 150) break;
                ItemStack st = new ItemStack(it, 1);
                boolean dup = false;
                for (ItemStack ex : out) {
                    if (ex.getItem() == it) { dup = true; break; }
                }
                if (!dup) out.add(st);
            }
        } else {
            for (Item it : BuiltInRegistries.ITEM) {
                if (it == Items.AIR) continue;
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(it);
                ItemStack st = new ItemStack(it, 1);
                String cnName = st.getHoverName().getString().toLowerCase(Locale.ROOT);
                if (cnName.contains(q) || key.toString().toLowerCase(Locale.ROOT).contains(q)) {
                    out.add(st);
                    if (out.size() >= 150) break;
                }
            }
        }
        cachedCatalogItems = out;
        return out;
    }

    // ================================================================ 通用表单与大号物品卡片列表组件

    private interface BundleMutator {
        RewardBundleSpec writable();
    }

    private void renderRichItemCardsList(Canvas c, ItemOverlay items, RewardBundleSpec bundle,
                                         BundleMutator mutator,
                                         float x, float y, float w, float h, float mx, float my) {
        HolderLookup.Provider regs = Minecraft.getInstance().level != null
                ? Minecraft.getInstance().level.registryAccess() : null;
        if (bundle.items == null || bundle.items.isEmpty()) {
            Draw.fill(c, x, y, w, Math.min(h, 42.0F), 5.0F, Draw.alpha(ShopTheme.text3(), 0.05F));
            Draw.stroke(c, x, y, w, Math.min(h, 42.0F), 5.0F, 0.6F, ShopTheme.borderDim());
            V2.text(c, "暂无物品奖励 · 点击右侧【全物品库 / 我的背包】中的图标即可添加",
                    5.6F, ShopTheme.text3(), x + 6.0F, y + Math.min(h, 42.0F) / 2.0F, w - 12.0F, Text.Align.CENTER);
            return;
        }

        float rowH = 24.0F;
        for (int i = 0; i < bundle.items.size(); i++) {
            float ry = y + i * (rowH + 3.0F);
            if (ry + rowH > y + h + 2.0F) break;
            ItemRewardSpec spec = bundle.items.get(i);
            ItemStack st = QuestDataConfig.buildItemStack(spec, regs);

            Draw.fill(c, x, ry, w, rowH, 4.5F, Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.12F : 0.06F));
            Draw.stroke(c, x, ry, w, rowH, 4.5F, 0.6F, ShopTheme.borderDim());

            if (items != null && !st.isEmpty()) {
                items.add(st, x + 5.0F, ry + 4.0F, 16.0F, false);
                if (inRect(mx, my, x + 3.0F, ry, 22.0F, rowH)) {
                    items.setHovered(st);
                }
            }
            String dispName = !st.isEmpty() ? st.getHoverName().getString() : spec.item;
            V2.bold(c, dispName, 6.0F, ShopTheme.text(), x + 25.0F, ry + rowH / 2.0F,
                    Math.max(30.0F, w - 118.0F), Text.Align.LEFT);

            final int idx = i;
            float right = x + w - 4.0F;
            // [移除]
            float rmW = 26.0F;
            right -= rmW;
            drawChip(c, "移除", right, ry + 4.0F, rmW, 16.0F, false, inRect(mx, my, right, ry + 4.0F, rmW, 16.0F));
            hit(right, ry + 4.0F, rmW, 16.0F, () -> {
                RewardBundleSpec wr = mutator.writable();
                if (idx < wr.items.size()) {
                    wr.items.remove(idx);
                    dirty = true;
                    QuestClient.playClickSound(0.92F);
                }
            });

            // [+]
            right -= 19.0F;
            drawChip(c, "+", right, ry + 4.0F, 16.0F, 16.0F, false, inRect(mx, my, right, ry + 4.0F, 16.0F, 16.0F));
            hit(right, ry + 4.0F, 16.0F, 16.0F, () -> {
                RewardBundleSpec wr = mutator.writable();
                if (idx < wr.items.size()) {
                    wr.items.get(idx).count = Math.min(999, wr.items.get(idx).count + 1);
                    dirty = true;
                    QuestClient.playClickSound(1.05F);
                }
            });

            // 数量显示
            right -= 28.0F;
            V2.bold(c, "×" + spec.count, 6.0F, ShopTheme.accent(), right, ry + rowH / 2.0F, 26.0F, Text.Align.CENTER);

            // [-]
            right -= 17.0F;
            drawChip(c, "-", right, ry + 4.0F, 16.0F, 16.0F, false, inRect(mx, my, right, ry + 4.0F, 16.0F, 16.0F));
            hit(right, ry + 4.0F, 16.0F, 16.0F, () -> {
                RewardBundleSpec wr = mutator.writable();
                if (idx < wr.items.size()) {
                    wr.items.get(idx).count = Math.max(1, wr.items.get(idx).count - 1);
                    dirty = true;
                    QuestClient.playClickSound(0.98F);
                }
            });
        }
    }

    private void renderDirectInputRow(Canvas c, String label, int fieldIdx, String unit,
                                      float x, float y, float w, float mx, float my, float dt,
                                      Runnable onMinus, Runnable onPlus) {
        V2.text(c, label, 6.0F, ShopTheme.text2(), x, y + 8.5F, w * 0.45F, Text.Align.LEFT);
        float right = x + w;
        if (onPlus != null) {
            drawChip(c, "+", right - 16.0F, y + 1.0F, 16.0F, 15.0F, false, inRect(mx, my, right - 16.0F, y + 1.0F, 16.0F, 15.0F));
            hit(right - 16.0F, y + 1.0F, 16.0F, 15.0F, () -> { onPlus.run(); QuestClient.playClickSound(1.05F); });
            right -= 19.0F;
        }
        if (onMinus != null) {
            drawChip(c, "-", right - 16.0F, y + 1.0F, 16.0F, 15.0F, false, inRect(mx, my, right - 16.0F, y + 1.0F, 16.0F, 15.0F));
            hit(right - 16.0F, y + 1.0F, 16.0F, 15.0F, () -> { onMinus.run(); QuestClient.playClickSound(0.98F); });
            right -= 19.0F;
        }
        if (unit != null && !unit.isBlank()) {
            float uw = V2.width(unit, 5.6F, false) + 4.0F;
            V2.text(c, unit, 5.6F, ShopTheme.text3(), right - uw, y + 8.5F, uw, Text.Align.RIGHT);
            right -= (uw + 3.0F);
        }
        float boxW = Math.min(112.0F, Math.max(48.0F, right - (x + w * 0.44F)));
        fields[fieldIdx].bounds(right - boxW, y, boxW, 17.0F);
        fields[fieldIdx].visible(true);
        fields[fieldIdx].render(c, mx, my, dt);
    }

    private void renderToggleRow(Canvas c, String label, boolean on, float x, float y, float w,
                                 float mx, float my, Runnable onToggle) {
        V2.text(c, label, 6.0F, ShopTheme.text2(), x, y + 8.5F, w - 58.0F, Text.Align.LEFT);
        float bw = 48.0F;
        float bx = x + w - bw;
        drawChip(c, on ? "已开启" : "已关闭", bx, y + 1.0F, bw, 15.0F, on, inRect(mx, my, bx, y + 1.0F, bw, 15.0F));
        hit(bx, y + 1.0F, bw, 15.0F, () -> {
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
        V2.bold(c, text, 5.5F, fg, x + 2.5F, y + h / 2.0F, w - 5.0F, Text.Align.CENTER);
    }

    // ================================================================ 交互事件分发

    public boolean mouseClicked(double mx, double my, int button) {
        int focusedIdx = -1;
        for (int i = 0; i < fields.length; i++) {
            SkiaTextField tf = fields[i];
            if (tf != null && tf.isVisible() && tf.mouseClicked(mx, my, button)) {
                focusedIdx = i;
                break;
            }
        }
        for (int i = 0; i < fields.length; i++) {
            if (i != focusedIdx && fields[i] != null) {
                fields[i].blur();
            }
        }
        if (focusedIdx >= 0) {
            return true;
        }
        if (button != 0) return false;

        if (tabRects != null && my >= tabY && my < tabY + V2.CTRL) {
            for (int i = 0; i < tabRects.length; i++) {
                if (mx >= tabRects[i][0] && mx < tabRects[i][0] + tabRects[i][1]) {
                    subTab = i;
                    boundContextKey = "";
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
        for (SkiaTextField tf : fields) {
            if (tf != null && tf.isVisible() && tf.mouseDragged(mx, my, button, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        for (SkiaTextField tf : fields) {
            if (tf != null && tf.isVisible() && tf.mouseReleased(mx, my, button)) {
                return true;
            }
        }
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double scrollY) {
        if (inRect((float) mx, (float) my, pickerX, pickerY, pickerW, pickerH)) {
            PICKER_SCROLL.wheel(scrollY * 1.4D);
            return true;
        }
        if (subTab == 0 && inRect((float) mx, (float) my, lvStripX, lvStripY, lvStripW, lvStripH)) {
            LEVEL_STRIP_SCROLL.wheel(scrollY * 1.5D);
            return true;
        }
        if ((subTab == 2 || subTab == 3) && inRect((float) mx, (float) my, leftListX, leftListY, leftListW, leftListH)) {
            LEFT_LIST_SCROLL.wheel(scrollY);
            return true;
        }
        return false;
    }

    // ================================================================ 语义辅助方法

    private static boolean supportsTargetFilter(QuestTaskType t) {
        return switch (t) {
            case VANILLA_BREAK_BLOCK, VANILLA_KILL_MOB, VANILLA_CRAFT_ITEM, VANILLA_FISH,
                 SHOP_BUY_ANY, SHOP_BUY_DAILY, SHOP_BUY_MALL, SHOP_SELL_BUYBACK,
                 SHOP_SHIPPING_SELL, STALL_BUY, STALL_LIST, CUSTOM_EVENT -> true;
            default -> false;
        };
    }

    private static String[][] filterPresetsFor(QuestTaskType t) {
        return switch (t) {
            case VANILLA_BREAK_BLOCK -> new String[][]{{"不限方块", ""}, {"任意矿石", "_ore"}, {"原木木头", "_log"}, {"石头类", "stone"}};
            case VANILLA_KILL_MOB -> new String[][]{{"任意怪物", ""}, {"僵尸", "zombie"}, {"骷髅", "skeleton"}, {"苦力怕", "creeper"}};
            case VANILLA_CRAFT_ITEM -> new String[][]{{"任意物品", ""}, {"木板", "planks"}, {"火把", "torch"}, {"面包", "bread"}};
            default -> new String[][]{{"不限物品", ""}};
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
            case VANILLA_BREAK_BLOCK, VANILLA_KILL_MOB, VANILLA_CRAFT_ITEM, VANILLA_FISH -> "minecraft";
            default -> "module_shop";
        };
    }

    private static void applySuggestedTitleAndDesc(QuestDefSpec q, boolean weekly) {
        QuestTaskType t = q.parsedType() != null ? q.parsedType() : QuestTaskType.SHOP_BUY_ANY;
        int n = Math.max(1, q.targetCount);
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
            case VANILLA_BREAK_BLOCK -> { q.title = "挖掘方块"; q.description = prefix + "挖掘 " + n + " 个指定方块"; }
            case VANILLA_KILL_MOB -> { q.title = "击杀怪物"; q.description = prefix + "击杀 " + n + " 只敌对生物"; }
            case VANILLA_CRAFT_ITEM -> { q.title = "合成物品"; q.description = prefix + "合成 " + n + " 件物品"; }
            case VANILLA_FISH -> { q.title = "钓鱼"; q.description = prefix + "成功钓起 " + n + " 次物品"; }
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

    private static int parseIntClamp(String s, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(s.trim())));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long parseLongClamp(String s, long fallback, long min, long max) {
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
