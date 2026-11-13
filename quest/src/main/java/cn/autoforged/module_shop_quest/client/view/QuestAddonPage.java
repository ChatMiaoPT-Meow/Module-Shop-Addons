package cn.autoforged.module_shop_quest.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPage;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.ItemOverlay;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.Motion;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop_quest.client.QuestClient;
import cn.autoforged.module_shop_quest.client.QuestClientState;
import cn.autoforged.module_shop_quest.client.QuestClientState.OnlineRow;
import cn.autoforged.module_shop_quest.client.QuestClientState.PassRow;
import cn.autoforged.module_shop_quest.client.QuestClientState.QuestRow;
import cn.autoforged.module_shop_quest.client.QuestClientState.RewardView;
import io.github.humbleui.skija.Canvas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 通行证与任务主界面（全皮肤自适应 + 去 AI 味直白交互 + 内置 OP 可视化配置台）。
 */
public final class QuestAddonPage implements AddonPage {
    private static final int TAB_PASS = 0;
    private static final int TAB_DAILY = 1;
    private static final int TAB_WEEKLY = 2;
    private static final int TAB_ONLINE = 3;

    private static final int SUBVIEW_MAIN = 0;
    private static final int SUBVIEW_UNLOCK = 1;
    private static final int SUBVIEW_BUY_LEVEL = 2;
    private static final int SUBVIEW_ADMIN = 3;

    private static final int GOLD_BRIGHT = 0xFFF2C15A;
    private static final int GOLD_BORDER_LIGHT = 0xFFDC9B2E;
    private static final int GOLD_TEXT_LIGHT = 0xFF7A4706;
    private static final int GOLD_BTN_TEXT = 0xFF2B1C0C;

    private static final ScrollController PASS_H_SCROLL = new ScrollController(false);
    private static final ScrollController DAILY_V_SCROLL = new ScrollController(false);
    private static final ScrollController WEEKLY_V_SCROLL = new ScrollController(false);
    private static int activeTab = TAB_PASS;
    private static int subView = SUBVIEW_MAIN;
    private static int buyLevelDelta = 1;
    private static int spotlightIdx = -1;
    private static boolean initialPassCentered = false;
    private static String centeredSeasonId = "";

    private final QuestAdminView adminView = new QuestAdminView();

    private final float[] segHovers = new float[4];
    private float claimAllHover = 0.0F;
    private float configBtnHover = 0.0F;
    private float buyLvBtnHover = 0.0F;
    private float unlockCtaHover = 0.0F;
    private float backSubHover = 0.0F;
    private float tier1BtnHover = 0.0F;
    private float tier2BtnHover = 0.0F;
    private float confirmBuyLvHover = 0.0F;

    private float[][] segRects;
    private float segY;

    private float passViewportX, passViewportY, passViewportW, passViewportH;
    private boolean draggingPassCanvas = false;
    private double dragLastX = 0.0D;
    private double dragAccumDist = 0.0D;

    private float questViewportX, questViewportY, questViewportW, questViewportH;

    private record HitZone(float x, float y, float w, float h, Runnable action) {
        boolean contains(float mx, float my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<HitZone> hitZones = new ArrayList<>();

    private static ItemStack coinIconCache;
    private static ItemStack ptIconCache;

    @Override
    public void init(AddonPageHost host) {
        adminView.ensureInputInitialized(host);
        QuestClient.requestSync();
        if (subView == SUBVIEW_ADMIN) {
            QuestClient.sendFetchAdminConfig();
        }
    }

    @Override
    public void render(Canvas c, AddonFrame f) {
        hitZones.clear();
        PASS_H_SCROLL.update();
        DAILY_V_SCROLL.update();
        WEEKLY_V_SCROLL.update();

        float x = f.x();
        float y = f.y();
        float w = f.w();
        float h = f.h();
        float mx = f.mouseX();
        float my = f.mouseY();
        float dt = f.dtMs();
        ItemOverlay items = f.host().items();

        renderTopBar(c, f, x, y, w, mx, my, dt);

        float bodyY = y + V2.BAR + 2.0F;
        float footTop = y + h - V2.FOOT;
        float bodyH = Math.max(80.0F, footTop - bodyY - 2.0F);

        if (!QuestClientState.received()) {
            V2.empty(c, tr("gui.module_shop_quest.loading"), x, bodyY, w, bodyH);
            V2.foot(c, x, footTop, w,
                    tr("gui.module_shop_quest.foot_balance", V2.money(f.host().balance()), f.host().currencyName()),
                    "");
            return;
        }

        if (!initialPassCentered || !QuestClientState.seasonId().equals(centeredSeasonId)) {
            centeredSeasonId = QuestClientState.seasonId();
            initialPassCentered = true;
            int targetLv = Math.max(1, QuestClientState.currentLevel());
            PASS_H_SCROLL.scrollTo(Math.max(0.0F, (targetLv - 2) * 58.0F), false);
        }

        if (subView == SUBVIEW_ADMIN) {
            adminView.render(c, f, items, x, bodyY, w, bodyH, mx, my, dt);
            renderFootBar(c, f, x, footTop, w);
            return;
        }
        adminView.closeInlineEditor();
        if (subView == SUBVIEW_UNLOCK) {
            renderUnlockShowcaseView(c, f, items, x, bodyY, w, bodyH, mx, my, dt);
            renderFootBar(c, f, x, footTop, w);
            return;
        }
        if (subView == SUBVIEW_BUY_LEVEL) {
            renderBuyLevelView(c, f, items, x, bodyY, w, bodyH, mx, my, dt);
            renderFootBar(c, f, x, footTop, w);
            return;
        }

        switch (activeTab) {
            case TAB_PASS -> renderBattlePassView(c, f, items, x, bodyY, w, bodyH, mx, my, dt);
            case TAB_DAILY -> renderCommissionGridView(c, f, items, DAILY_V_SCROLL,
                    QuestClientState.dailyRows(), true, x, bodyY, w, bodyH, mx, my, dt);
            case TAB_WEEKLY -> renderCommissionGridView(c, f, items, WEEKLY_V_SCROLL,
                    QuestClientState.weeklyRows(), false, x, bodyY, w, bodyH, mx, my, dt);
            case TAB_ONLINE -> renderOnlineTimelineView(c, f, items, x, bodyY, w, bodyH, mx, my, dt);
            default -> {
            }
        }

        renderFootBar(c, f, x, footTop, w);
    }

    // ================================================================ 动态皮肤适配色板（完美兼容樱花粉亮色与暗色皮肤）

    private static int goldAccentText() {
        return ShopTheme.dark() ? GOLD_BRIGHT : GOLD_TEXT_LIGHT;
    }

    private static int goldBorder() {
        return ShopTheme.dark() ? Draw.alpha(GOLD_BRIGHT, 0.72F) : Draw.alpha(GOLD_BORDER_LIGHT, 0.68F);
    }

    private static int featuredTop() {
        return ShopTheme.dark()
                ? 0xFF282036
                : Draw.lerp(ShopTheme.card(), 0xFFFFECC0, 0.42F);
    }

    private static int featuredBot() {
        return ShopTheme.dark()
                ? 0xFF1C1626
                : Draw.lerp(ShopTheme.card(), ShopTheme.accentTint(), 0.48F);
    }

    private static int medalTop() {
        return ShopTheme.dark()
                ? 0xFF34231A
                : Draw.lerp(ShopTheme.card(), 0xFFFFE5AD, 0.52F);
    }

    private static int medalBot() {
        return ShopTheme.dark()
                ? 0xFF221610
                : Draw.lerp(ShopTheme.card(), 0xFFFFD89E, 0.36F);
    }

    private static int softSubCardTop() {
        return ShopTheme.dark()
                ? 0xFF241F32
                : Draw.lerp(ShopTheme.card(), ShopTheme.accentTint(), 0.35F);
    }

    private static int softSubCardBot() {
        return ShopTheme.dark()
                ? 0xFF1C1828
                : Draw.lerp(ShopTheme.card(), ShopTheme.accentTint(), 0.18F);
    }

    // ================================================================ 1. 顶部导航栏

    private void renderTopBar(Canvas c, AddonFrame f, float x, float y, float w,
                              float mx, float my, float dt) {
        float cy = y + V2.BAR / 2.0F;
        float ctrlY = cy - V2.CTRL / 2.0F;
        Draw.hairline(c, x, y + V2.BAR - 0.5F, w, ShopTheme.borderDim());

        float cursorX = x + 8.0F;
        if (subView != SUBVIEW_MAIN) {
            String backLbl = tr("gui.module_shop_quest.btn_back_pass");
            float bw = V2.buttonWidth(backLbl) + 8.0F;
            boolean overBack = inRect(mx, my, cursorX, ctrlY, bw, V2.CTRL);
            backSubHover = Motion.hover(backSubHover, overBack, dt);
            V2.button(c, backLbl, V2.Btn.NORMAL, cursorX, ctrlY, bw, V2.CTRL, backSubHover);
            registerHit(cursorX, ctrlY, bw, V2.CTRL, () -> {
                adminView.closeInlineEditor();
                subView = SUBVIEW_MAIN;
                QuestClient.playClickSound(0.98F);
            });
            cursorX += bw + 10.0F;

            String subTitle = switch (subView) {
                case SUBVIEW_UNLOCK -> "解锁" + QuestClientState.premiumTrackName() + " / " + QuestClientState.deluxeTrackName();
                case SUBVIEW_BUY_LEVEL -> "购买通行证等级";
                case SUBVIEW_ADMIN -> "通行证与任务配置管理";
                default -> "";
            };
            V2.bold(c, subTitle, V2.FS_T, ShopTheme.text(), cursorX, cy, Math.max(80.0F, w - cursorX - 160.0F), Text.Align.LEFT);
            segRects = null;
        } else {
            String[] labels = {
                    withCount(tr("gui.module_shop_quest.tab_pass"), QuestClientState.countPassClaimable()),
                    withCount(tr("gui.module_shop_quest.tab_daily"),
                            QuestClientState.countQuestClaimable(QuestClientState.dailyRows())),
                    withCount(tr("gui.module_shop_quest.tab_weekly"),
                            QuestClientState.countQuestClaimable(QuestClientState.weeklyRows())),
                    withCount(tr("gui.module_shop_quest.tab_online"), QuestClientState.countOnlineClaimable())
            };
            if (segRects != null && segRects.length == 4) {
                for (int i = 0; i < 4; i++) {
                    boolean over = mx >= segRects[i][0] && mx < segRects[i][0] + segRects[i][1]
                            && my >= ctrlY && my < ctrlY + V2.CTRL;
                    segHovers[i] = Motion.hover(segHovers[i], over, dt);
                }
            }
            segY = ctrlY;
            segRects = V2.segmented(c, labels, activeTab, cursorX, ctrlY, segHovers);
        }

        // 右侧按钮：一键领取 + OP 可视化配置管理
        float rightCursor = x + w - 8.0F;
        if (subView != SUBVIEW_ADMIN) {
            int totalClaimable = QuestClientState.totalClaimable();
            String claimAllLabel = totalClaimable > 0
                    ? tr("gui.module_shop_quest.btn_claim_all_count", totalClaimable)
                    : tr("gui.module_shop_quest.btn_claim_all");
            float claimW = V2.buttonWidth(claimAllLabel) + 10.0F;
            float claimX = rightCursor - claimW;
            boolean overClaim = totalClaimable > 0 && inRect(mx, my, claimX, ctrlY, claimW, V2.CTRL);
            claimAllHover = Motion.hover(claimAllHover, overClaim, dt);
            if (totalClaimable > 0) {
                V2.button(c, claimAllLabel, V2.Btn.PRIMARY, claimX, ctrlY, claimW, V2.CTRL, claimAllHover);
                registerHit(claimX, ctrlY, claimW, V2.CTRL, QuestClient::sendClaimAll);
            } else {
                V2.button(c, claimAllLabel, V2.Btn.DISABLED, claimX, ctrlY, claimW, V2.CTRL, 0.0F);
            }
            rightCursor = claimX - 6.0F;
        }

        if (QuestClientState.isOp() && subView != SUBVIEW_ADMIN) {
            String cfgLabel = tr("gui.module_shop_quest.btn_config");
            float cw = V2.buttonWidth(cfgLabel) + 8.0F;
            float cx = rightCursor - cw;
            boolean overCfg = inRect(mx, my, cx, ctrlY, cw, V2.CTRL);
            configBtnHover = Motion.hover(configBtnHover, overCfg, dt);
            V2.button(c, cfgLabel, V2.Btn.NORMAL, cx, ctrlY, cw, V2.CTRL, configBtnHover);
            registerHit(cx, ctrlY, cw, V2.CTRL, () -> {
                subView = SUBVIEW_ADMIN;
                QuestClient.sendFetchAdminConfig();
                QuestClient.playClickSound(1.06F);
            });
        }
    }

    // ================================================================ 2. 【通行证】横向双轨阶梯视图

    private void renderBattlePassView(Canvas c, AddonFrame f, ItemOverlay items,
                                      float x, float y, float w, float h,
                                      float mx, float my, float dt) {
        List<PassRow> rows = QuestClientState.passRows();
        if (rows.isEmpty()) {
            V2.empty(c, tr("gui.module_shop_quest.empty_pass"), x, y, w, h);
            return;
        }

        float pad = 8.0F;
        float innerX = x + pad;
        float innerW = w - pad * 2.0F;

        float heroH = clamp(h * 0.165F, 48.0F, 60.0F);
        renderPassHeroHeader(c, f, innerX, y + 1.0F, innerW, heroH, mx, my, dt);

        float mainY = y + heroH + 7.0F;
        float mainH = Math.max(120.0F, h - heroH - 9.0F);

        boolean showShowcase = innerW >= 460.0F;
        float rightW = showShowcase ? clamp(innerW * 0.24F, 140.0F, 184.0F) : 0.0F;
        float matrixW = showShowcase ? (innerW - rightW - 8.0F) : innerW;

        renderHorizontalPassMatrix(c, f, items, innerX, mainY, matrixW, mainH, mx, my, dt, rows);

        if (showShowcase) {
            renderSeasonSpotlightPanel(c, f, items, innerX + matrixW + 8.0F, mainY, rightW, mainH, mx, my, dt, rows);
        }
    }

    private void renderPassHeroHeader(Canvas c, AddonFrame f, float x, float y, float w, float h,
                                      float mx, float my, float dt) {
        Draw.outerShadow(c, x, y, w, h, 6.0F, 5.0F, 1.5F, Draw.alpha(ShopTheme.shadow(), 0.20F));
        Draw.gradientH(c, x, y, w, h, 6.0F, softSubCardTop(), ShopTheme.card());
        Draw.stroke(c, x, y, w, h, 6.0F, 0.7F, ShopTheme.borderDim());

        int lv = QuestClientState.currentLevel();
        int maxLv = QuestClientState.maxLevel();
        float badgeR = Math.min(20.5F, h * 0.40F);
        float badgeCx = x + 12.0F + badgeR;
        float badgeCy = y + h / 2.0F;

        Draw.gradientV(c, badgeCx - badgeR, badgeCy - badgeR, badgeR * 2.0F, badgeR * 2.0F, badgeR,
                featuredTop(), featuredBot());
        Draw.stroke(c, badgeCx - badgeR, badgeCy - badgeR, badgeR * 2.0F, badgeR * 2.0F, badgeR, 1.6F, goldBorder());
        V2.bold(c, String.valueOf(lv), 12.5F, ShopTheme.text(),
                badgeCx - badgeR, badgeCy - 2.2F, badgeR * 2.0F, Text.Align.CENTER);
        V2.text(c, "Lv", 5.0F, ShopTheme.text2(),
                badgeCx - badgeR, badgeCy + badgeR * 0.50F, badgeR * 2.0F, Text.Align.CENTER);

        float rightEdge = x + w - 10.0F;
        if (QuestClientState.allowBuyLevels() && lv < maxLv) {
            String buyLbl = tr("gui.module_shop_quest.btn_buy_level");
            float bw = V2.width(buyLbl, V2.FS, true) + 16.0F;
            float bh = 18.0F;
            float bx = rightEdge - bw;
            float by = y + (h - bh) / 2.0F;
            boolean over = inRect(mx, my, bx, by, bw, bh);
            buyLvBtnHover = Motion.hover(buyLvBtnHover, over, dt);
            renderGoldPillButton(c, buyLbl, bx, by, bw, bh, buyLvBtnHover, false);
            registerHit(bx, by, bw, bh, () -> {
                buyLevelDelta = 1;
                subView = SUBVIEW_BUY_LEVEL;
                QuestClient.playClickSound(1.06F);
            });
            rightEdge = bx - 8.0F;
        }

        if (QuestClientState.prevSeasonUnclaimed() > 0) {
            String gLbl = tr("gui.module_shop_quest.btn_prev_grace", QuestClientState.prevSeasonUnclaimed());
            float gw = V2.buttonWidth(gLbl) + 8.0F;
            float gh = 18.0F;
            float gx = rightEdge - gw;
            float gy = y + (h - gh) / 2.0F;
            V2.button(c, gLbl, V2.Btn.PRIMARY, gx, gy, gw, gh, inRect(mx, my, gx, gy, gw, gh) ? 1.0F : 0.0F);
            registerHit(gx, gy, gw, gh, QuestClient::sendClaimAll);
            rightEdge = gx - 8.0F;
        }

        float midX = badgeCx + badgeR + 12.0F;
        float midW = Math.max(100.0F, rightEdge - midX - 4.0F);
        float row1Cy = y + h * 0.28F;
        float barY = y + h * 0.54F;
        float row3Cy = y + h * 0.80F;

        float snW = V2.bold(c, QuestClientState.seasonName(), 8.4F, ShopTheme.text(),
                midX, row1Cy, midW * 0.52F, Text.Align.LEFT);
        String remStr = tr("gui.module_shop_quest.season_remain",
                QuestClientState.formatCountdown(QuestClientState.secondsUntilSeasonEnd()));
        V2.tag(c, remStr, ShopTheme.accent(), midX + snW + 6.0F, row1Cy);

        long pts = QuestClientState.seasonPoints();
        long prevReq = QuestClientState.levelPrevReq();
        long nextReq = QuestClientState.levelNextReq();
        float pct = (lv >= maxLv || nextReq <= prevReq)
                ? 1.0F
                : clamp((float) (pts - prevReq) / (float) (nextReq - prevReq), 0.0F, 1.0F);

        String xpRight = lv >= maxLv
                ? tr("gui.module_shop_quest.pass_maxed", V2.money(pts))
                : (V2.money(Math.max(0L, pts - prevReq)) + " / " + V2.money(Math.max(1L, nextReq - prevReq)));
        V2.bold(c, xpRight, 7.0F, goldAccentText(), midX, row1Cy, midW, Text.Align.RIGHT);

        float barH = 5.2F;
        Draw.fill(c, midX, barY - barH / 2.0F, midW, barH, barH / 2.0F,
                Draw.alpha(ShopTheme.text3(), ShopTheme.dark() ? 0.24F : 0.14F));
        if (pct > 0.005F) {
            float fillW = Math.max(barH, midW * pct);
            Draw.gradientH(c, midX, barY - barH / 2.0F, fillW, barH, barH / 2.0F, ShopTheme.accent(), GOLD_BRIGHT);
        }

        V2.text(c, "本季积分：" + V2.money(pts), V2.FS_XS, ShopTheme.text2(), midX, row3Cy, midW * 0.48F, Text.Align.LEFT);
        String rollHint = tr("gui.module_shop_quest.rollover_hint",
                QuestClientState.rolloverPercent(), V2.money(QuestClientState.rolloverCap()));
        V2.text(c, rollHint, V2.FS_XS, ShopTheme.text3(), midX, row3Cy, midW, Text.Align.RIGHT);
    }

    private void renderHorizontalPassMatrix(Canvas c, AddonFrame f, ItemOverlay items,
                                            float x, float y, float w, float h,
                                            float mx, float my, float dt, List<PassRow> rows) {
        Draw.outerShadow(c, x, y, w, h, 6.0F, 5.0F, 1.5F, Draw.alpha(ShopTheme.shadow(), 0.18F));
        Draw.fill(c, x, y, w, h, 6.0F, ShopTheme.card());
        Draw.stroke(c, x, y, w, h, 6.0F, 0.7F, ShopTheme.borderDim());

        float pad = 5.0F;
        float scrollerBarH = 13.0F;
        float gridX = x + pad;
        float gridY = y + pad;
        float gridW = w - pad * 2.0F;
        float gridH = h - pad * 2.0F - scrollerBarH;

        float trackHeadW = clamp(gridW * 0.16F, 66.0F, 82.0F);
        boolean showPinnedMilestone = gridW >= 340.0F;
        float colW = clamp(gridH * 0.21F, 50.0F, 58.0F);
        float colGap = 4.0F;
        float pinW = showPinnedMilestone ? (colW + 4.0F) : 0.0F;

        float headH = 19.0F;
        float spineH = 11.0F;
        float tracksH = Math.max(80.0F, gridH - headH - spineH);
        float freeH = Math.round(tracksH * 0.41F);
        float premH = tracksH - freeH;

        float freeY = gridY + headH;
        float spineY = freeY + freeH;
        float premY = spineY + spineH;

        renderPinnedTrackHeaders(c, gridX, gridY, trackHeadW, headH, freeY, freeH, spineY, spineH, premY, premH);

        float vpX = gridX + trackHeadW + 4.0F;
        float vpW = Math.max(60.0F, gridW - trackHeadW - pinW - (showPinnedMilestone ? 8.0F : 4.0F));
        passViewportX = vpX;
        passViewportY = gridY;
        passViewportW = vpW;
        passViewportH = gridH;

        float totalContentW = rows.size() * (colW + colGap) - colGap;
        PASS_H_SCROLL.setContent(totalContentW, vpW);
        float scrollOffset = PASS_H_SCROLL.offset();

        int curLv = QuestClientState.currentLevel();
        long curPts = QuestClientState.seasonPoints();
        boolean premUnlocked = QuestClientState.premiumUnlocked();
        boolean premEnabled = QuestClientState.premiumEnabled();
        String currency = f.host().currencyName();

        int save = c.save();
        Draw.clip(c, vpX, gridY, vpW, gridH);
        if (items != null) {
            items.pushClip(vpX, gridY, vpW, gridH);
        }

        int rightmostVisibleLevel = 1;
        for (int i = 0; i < rows.size(); i++) {
            float cx = vpX + i * (colW + colGap) - scrollOffset;
            if (cx + colW < vpX - 2.0F) continue;
            if (cx > vpX + vpW + 2.0F) break;
            PassRow r = rows.get(i);
            if (cx + colW * 0.6F <= vpX + vpW) {
                rightmostVisibleLevel = r.level();
            }
            boolean allowHover = mx >= vpX && mx < vpX + vpW;
            renderSinglePassColumn(c, f, items, r, cx, gridY, colW, headH, freeY, freeH,
                    spineY, spineH, premY, premH, curLv, curPts, premEnabled, premUnlocked,
                    currency, allowHover ? mx : -9999.0F, allowHover ? my : -9999.0F, false, vpX, vpX + vpW);
        }

        if (items != null) {
            items.popClip();
        }
        c.restoreToCount(save);

        if (showPinnedMilestone) {
            PassRow pinned = findNextMilestoneRow(rows, rightmostVisibleLevel);
            float px = vpX + vpW + 4.0F;
            Draw.line(c, px - 2.0F, gridY + 2.0F, px - 2.0F, gridY + gridH - 2.0F, 1.0F, goldBorder());
            renderSinglePassColumn(c, f, items, pinned, px + 1.0F, gridY, pinW - 2.0F, headH,
                    freeY, freeH, spineY, spineH, premY, premH, curLv, curPts, premEnabled, premUnlocked,
                    currency, mx, my, true, px, px + pinW);
        }

        float barY = gridY + gridH + 3.0F;
        renderPassHorizontalScrubber(c, gridX, barY, gridW, scrollerBarH - 2.0F, vpW, totalContentW, mx, my);
    }

    private void renderPinnedTrackHeaders(Canvas c, float x, float headY, float w, float headH,
                                          float freeY, float freeH, float spineY, float spineH,
                                          float premY, float premH) {
        Draw.fill(c, x, headY, w, headH - 1.5F, 4.0F, Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.14F : 0.08F));
        V2.bold(c, "等级奖励", V2.FS_S, ShopTheme.text2(), x, headY + (headH - 1.5F) / 2.0F, w, Text.Align.CENTER);

        // 免费奖励头卡
        Draw.gradientV(c, x, freeY, w, freeH, 5.0F, ShopTheme.card(), softSubCardBot());
        Draw.stroke(c, x, freeY, w, freeH, 5.0F, 0.7F, ShopTheme.border());
        drawVectorGiftEmblem(c, x + w / 2.0F, freeY + freeH * 0.38F, Math.min(12.5F, freeH * 0.21F), ShopTheme.accent());
        V2.bold(c, QuestClientState.freeTrackName(), 6.6F, ShopTheme.text(),
                x + 3.0F, freeY + freeH * 0.74F, w - 6.0F, Text.Align.CENTER);

        // 脊线
        float sCy = spineY + spineH / 2.0F;
        Draw.line(c, x + 6.0F, sCy, x + w - 6.0F, sCy, 1.0F, ShopTheme.borderDim());
        drawDiamond(c, x + w / 2.0F, sCy, 2.6F, goldBorder());

        // 进阶通行证头卡（亮色模式柔和香槟樱粉渐变，暗色模式深紫鎏金渐变）
        Draw.gradientV(c, x, premY, w, premH, 5.0F, featuredTop(), featuredBot());
        Draw.stroke(c, x, premY, w, premH, 5.0F, 0.9F, goldBorder());

        drawVectorCrestEmblem(c, x + w / 2.0F, premY + premH * 0.36F, Math.min(14.0F, premH * 0.19F),
                goldAccentText(), false);
        V2.bold(c, QuestClientState.premiumTrackName(), 6.6F, ShopTheme.text(),
                x + 3.0F, premY + premH * 0.70F, w - 6.0F, Text.Align.CENTER);

        String subState = !QuestClientState.premiumEnabled()
                ? "未开放"
                : (QuestClientState.deluxeUnlocked() ? "典藏已解锁"
                : (QuestClientState.premiumUnlocked() ? "已解锁" : "未解锁 · 点击查看"));
        int subCol = QuestClientState.premiumUnlocked() ? ShopTheme.green() : goldAccentText();
        V2.bold(c, subState, 5.1F, subCol, x + 3.0F, premY + premH * 0.85F, w - 6.0F, Text.Align.CENTER);

        if (QuestClientState.premiumEnabled() && !QuestClientState.deluxeUnlocked()) {
            registerHit(x, premY, w, premH, () -> {
                subView = SUBVIEW_UNLOCK;
                QuestClient.playClickSound(1.05F);
            });
        }
    }

    private void renderSinglePassColumn(Canvas c, AddonFrame f, ItemOverlay items, PassRow r,
                                        float cx, float headY, float colW, float headH,
                                        float freeY, float freeH, float spineY, float spineH,
                                        float premY, float premH,
                                        int curLv, long curPts, boolean premEnabled, boolean premUnlocked,
                                        String currency, float mx, float my, boolean pinnedPreview,
                                        float clipMinX, float clipMaxX) {
        boolean reached = curPts >= r.reqPoints();
        boolean isCurrent = r.level() == curLv;
        boolean milestone = r.highlight();

        // 1. 等级头
        int headBg = milestone
                ? (ShopTheme.dark() ? 0xFF42311A : 0xFFFFECC2)
                : (reached ? Draw.lerp(ShopTheme.card(), ShopTheme.accentTint(), 0.38F)
                : Draw.alpha(ShopTheme.text3(), 0.08F));
        Draw.fill(c, cx, headY, colW, headH - 1.5F, 4.0F, headBg);
        if (pinnedPreview || milestone || isCurrent) {
            Draw.stroke(c, cx, headY, colW, headH - 1.5F, 4.0F, isCurrent ? 1.1F : 0.7F,
                    milestone || pinnedPreview ? goldBorder() : ShopTheme.accent());
        }
        String lvStr = (milestone ? "★ " : "") + r.level() + "级";
        int lvCol = milestone ? goldAccentText() : (reached ? ShopTheme.text() : ShopTheme.text2());
        V2.bold(c, lvStr, 6.1F, lvCol, cx, headY + (headH - 1.5F) / 2.0F, colW, Text.Align.CENTER);

        // 2. 免费格
        int freeBg = reached ? ShopTheme.card() : Draw.alpha(ShopTheme.text3(), ShopTheme.dark() ? 0.08F : 0.04F);
        Draw.fill(c, cx, freeY, colW, freeH, 4.0F, freeBg);
        Draw.stroke(c, cx, freeY, colW, freeH, 4.0F, 0.6F, isCurrent ? ShopTheme.accent() : ShopTheme.borderDim());

        boolean freeHas = r.freeReward().money() > 0L || !r.freeReward().items().isEmpty();
        boolean freeCanClaim = reached && freeHas && !r.freeClaimed();

        float slotSz = clamp(Math.min(colW - 10.0F, freeH - 18.0F), 28.0F, 37.0F);
        float slotX = cx + (colW - slotSz) / 2.0F;
        float slotY = freeY + (r.freeReward().money() > 0L && !r.freeReward().items().isEmpty()
                ? Math.max(3.0F, (freeH - slotSz - 11.0F) / 2.0F)
                : (freeH - slotSz) / 2.0F);

        renderFreeCellSlot(c, f, items, r.freeReward(), slotX, slotY, slotSz,
                reached, r.freeClaimed(), freeCanClaim, currency, mx, my, clipMinX, clipMaxX);

        if (r.freeReward().money() > 0L && !r.freeReward().items().isEmpty()) {
            String mTag = "+" + V2.money(r.freeReward().money()) + " " + currency;
            V2.bold(c, mTag, 4.9F, r.freeClaimed() ? ShopTheme.text3() : goldAccentText(),
                    cx + 1.5F, slotY + slotSz + 6.0F, colW - 3.0F, Text.Align.CENTER);
        }

        if (freeCanClaim && mx >= clipMinX && mx < clipMaxX) {
            final int claimLv = r.level();
            registerHit(cx, freeY, colW, freeH, () -> QuestClient.sendClaimPass(claimLv, false, false));
        } else if (pinnedPreview && mx >= clipMinX && mx < clipMaxX) {
            final int jumpLv = r.level();
            registerHit(cx, headY, colW, headH, () -> {
                PASS_H_SCROLL.scrollTo(Math.max(0.0F, (jumpLv - 3) * (colW + 4.0F)), true);
                QuestClient.playClickSound(1.05F);
            });
        }

        // 3. 进度脊线
        float sCy = spineY + spineH / 2.0F;
        Draw.line(c, cx - 2.0F, sCy, cx + colW + 2.0F, sCy, 1.8F,
                reached ? GOLD_BRIGHT : Draw.alpha(ShopTheme.text3(), 0.20F));
        drawDiamond(c, cx + colW / 2.0F, sCy, isCurrent ? 3.5F : 2.5F,
                reached ? GOLD_BRIGHT : ShopTheme.border());

        // 4. 进阶格（皮肤自适应柔和香槟色 / 暗紫金，双品质卡上下堆叠）
        Draw.gradientV(c, cx, premY, colW, premH, 4.0F,
                reached ? featuredTop() : Draw.alpha(featuredTop(), 0.72F),
                featuredBot());
        Draw.stroke(c, cx, premY, colW, premH, 4.0F, milestone ? 0.9F : 0.6F,
                milestone ? goldBorder() : ShopTheme.borderDim());

        if (premEnabled) {
            boolean premHas = r.premReward().money() > 0L || !r.premReward().items().isEmpty();
            boolean premCanClaim = reached && premUnlocked && premHas && !r.premClaimed();

            float pSlotSz = clamp(Math.min(colW - 10.0F, (premH - 14.0F) / 2.0F), 26.0F, 36.0F);
            float pSlotX = cx + (colW - pSlotSz) / 2.0F;

            List<SlotVisual> visuals = buildPaidSlotVisuals(r.premReward(), currency);
            if (visuals.size() >= 2) {
                float gapY = Math.min(5.0F, Math.max(2.5F, premH - pSlotSz * 2.0F - 8.0F));
                float startY = premY + (premH - (pSlotSz * 2.0F + gapY)) / 2.0F;
                renderSlotVisual(c, f, items, visuals.get(0), pSlotX, startY, pSlotSz,
                        !premUnlocked, r.premClaimed(), premCanClaim, mx, my, clipMinX, clipMaxX);
                renderSlotVisual(c, f, items, visuals.get(1), pSlotX, startY + pSlotSz + gapY, pSlotSz,
                        !premUnlocked, r.premClaimed(), premCanClaim, mx, my, clipMinX, clipMaxX);
            } else if (visuals.size() == 1) {
                float startY = premY + (premH - pSlotSz) / 2.0F;
                renderSlotVisual(c, f, items, visuals.get(0), pSlotX, startY, pSlotSz,
                        !premUnlocked, r.premClaimed(), premCanClaim, mx, my, clipMinX, clipMaxX);
            }

            if (mx >= clipMinX && mx < clipMaxX) {
                if (premCanClaim) {
                    final int claimLv = r.level();
                    registerHit(cx, premY, colW, premH, () -> QuestClient.sendClaimPass(claimLv, true, false));
                } else if (!premUnlocked && premHas) {
                    registerHit(cx, premY, colW, premH, () -> {
                        subView = SUBVIEW_UNLOCK;
                        QuestClient.playClickSound(1.05F);
                    });
                }
            }
        }
    }

    private PassRow findNextMilestoneRow(List<PassRow> rows, int afterLevel) {
        for (PassRow r : rows) {
            if (r.level() > afterLevel && r.highlight()) {
                return r;
            }
        }
        return rows.get(rows.size() - 1);
    }

    private void renderPassHorizontalScrubber(Canvas c, float x, float y, float w, float h,
                                              float vpW, float contentW, float mx, float my) {
        float btnW = 16.0F;
        drawMiniStepBtn(c, "‹", x, y, btnW, h, inRect(mx, my, x, y, btnW, h));
        registerHit(x, y, btnW, h, () -> {
            PASS_H_SCROLL.scrollTo(Math.max(0.0F, PASS_H_SCROLL.offset() - vpW * 0.75F), true);
            QuestClient.playClickSound(1.02F);
        });

        String jumpLbl = "当前等级";
        float jw = V2.width(jumpLbl, 5.2F, true) + 10.0F;
        float jx = x + w - jw;
        drawMiniStepBtn(c, jumpLbl, jx, y, jw, h, inRect(mx, my, jx, y, jw, h));
        registerHit(jx, y, jw, h, () -> {
            int curLv = Math.max(1, QuestClientState.currentLevel());
            PASS_H_SCROLL.scrollTo(Math.max(0.0F, (curLv - 2) * 58.0F), true);
            QuestClient.playClickSound(1.05F);
        });

        float rx = jx - 3.0F - btnW;
        drawMiniStepBtn(c, "›", rx, y, btnW, h, inRect(mx, my, rx, y, btnW, h));
        registerHit(rx, y, btnW, h, () -> {
            PASS_H_SCROLL.scrollTo(Math.min(PASS_H_SCROLL.maxScroll(), PASS_H_SCROLL.offset() + vpW * 0.75F), true);
            QuestClient.playClickSound(1.02F);
        });

        float trackX = x + btnW + 4.0F;
        float trackW = Math.max(30.0F, rx - trackX - 4.0F);
        float trackCy = y + h / 2.0F;
        Draw.fill(c, trackX, trackCy - 2.0F, trackW, 4.0F, 2.0F,
                Draw.alpha(ShopTheme.text3(), ShopTheme.dark() ? 0.22F : 0.14F));
        if (PASS_H_SCROLL.maxScroll() > 1.0F && contentW > 0.0F) {
            float thumbRatio = clamp(vpW / contentW, 0.08F, 1.0F);
            float thumbW = Math.max(18.0F, trackW * thumbRatio);
            float pct = clamp(PASS_H_SCROLL.offset() / PASS_H_SCROLL.maxScroll(), 0.0F, 1.0F);
            float thumbX = trackX + (trackW - thumbW) * pct;
            Draw.gradientH(c, thumbX, trackCy - 2.0F, thumbW, 4.0F, 2.0F, ShopTheme.accent(), GOLD_BRIGHT);
            registerHit(trackX, y, trackW, h, () -> {
                float clickPct = clamp((mx - trackX - thumbW / 2.0F) / Math.max(1.0F, trackW - thumbW), 0.0F, 1.0F);
                PASS_H_SCROLL.scrollTo(clickPct * PASS_H_SCROLL.maxScroll(), true);
            });
        }
    }

    // ---------------------------------------------------------------- 右侧阶段大奖预览面板（亮/暗皮肤自适应）

    private void renderSeasonSpotlightPanel(Canvas c, AddonFrame f, ItemOverlay items,
                                            float x, float y, float w, float h,
                                            float mx, float my, float dt, List<PassRow> rows) {
        Draw.outerShadow(c, x, y, w, h, 6.0F, 6.0F, 1.5F, Draw.alpha(ShopTheme.shadow(), 0.20F));
        Draw.gradientV(c, x, y, w, h, 6.0F, featuredTop(), featuredBot());
        Draw.stroke(c, x, y, w, h, 6.0F, 0.9F, goldBorder());

        List<PassRow> milestones = new ArrayList<>();
        for (PassRow r : rows) {
            if (r.highlight()) milestones.add(r);
        }
        if (milestones.isEmpty()) milestones.add(rows.get(rows.size() - 1));
        if (spotlightIdx < 0 || spotlightIdx >= milestones.size()) {
            spotlightIdx = milestones.size() - 1;
        }
        PassRow spot = milestones.get(spotlightIdx);

        float topCy = y + 14.0F;
        if (milestones.size() > 1) {
            drawMiniStepBtn(c, "‹", x + 7.0F, topCy - 6.5F, 13.0F, 13.0F, inRect(mx, my, x + 7.0F, topCy - 6.5F, 13.0F, 13.0F));
            registerHit(x + 6.0F, topCy - 8.0F, 16.0F, 16.0F, () -> {
                spotlightIdx = (spotlightIdx - 1 + milestones.size()) % milestones.size();
                QuestClient.playClickSound(1.06F);
            });
            drawMiniStepBtn(c, "›", x + w - 20.0F, topCy - 6.5F, 13.0F, 13.0F, inRect(mx, my, x + w - 20.0F, topCy - 6.5F, 13.0F, 13.0F));
            registerHit(x + w - 22.0F, topCy - 8.0F, 16.0F, 16.0F, () -> {
                spotlightIdx = (spotlightIdx + 1) % milestones.size();
                QuestClient.playClickSound(1.06F);
            });
        }
        String spotTitle = spot.level() == rows.get(rows.size() - 1).level()
                ? (spot.level() + " 级满级奖励")
                : (spot.level() + " 级重点奖励");
        V2.bold(c, spotTitle, 7.0F, ShopTheme.text(), x + 22.0F, topCy, w - 44.0F, Text.Align.CENTER);

        float ctaH = 22.0F;
        float bottomAreaTop = y + h - ctaH - 8.0F;
        float showcaseCenterY = y + 26.0F + (bottomAreaTop - (y + 26.0F)) * 0.43F;
        float cardW = Math.min(w - 32.0F, 88.0F);
        float cardH = clamp((bottomAreaTop - y - 74.0F), 64.0F, 100.0F);
        float cardX = x + (w - cardW) / 2.0F;
        float cardY = showcaseCenterY - cardH / 2.0F;

        Draw.glow(c, x + w / 2.0F, showcaseCenterY, Math.max(cardW, cardH) * 0.75F,
                Draw.alpha(GOLD_BRIGHT, ShopTheme.dark() ? 0.28F : 0.22F));
        Draw.stroke(c, cardX + 3.0F, cardY - 2.5F, cardW, cardH, 6.0F, 0.7F, goldBorder());
        Draw.gradientV(c, cardX, cardY, cardW, cardH, 6.0F, medalTop(), ShopTheme.card());
        Draw.stroke(c, cardX, cardY, cardW, cardH, 6.0F, 1.2F, goldBorder());

        RewardView spotReward = spot.premReward().hasAny() ? spot.premReward() : spot.freeReward();
        ItemStack heroStack = !spotReward.items().isEmpty() ? spotReward.items().get(0) : coinStack();
        float iconSz = Math.min(28.0F, cardH * 0.40F);
        float iconX = cardX + (cardW - iconSz) / 2.0F;
        float iconY = cardY + cardH * 0.16F;
        if (items != null && !heroStack.isEmpty()) {
            items.add(heroStack, iconX, iconY, iconSz, false);
            if (inRect(mx, my, cardX, cardY, cardW, cardH)) {
                items.setHovered(heroStack);
            }
        }

        String itemName = stripFormatting(heroStack.getHoverName().getString());
        if (heroStack.getCount() > 1) itemName += " ×" + heroStack.getCount();
        V2.bold(c, itemName, 6.1F, ShopTheme.text(), cardX + 4.0F, cardY + cardH * 0.71F, cardW - 8.0F, Text.Align.CENTER);

        if (spotReward.money() > 0L) {
            String bonusCoin = "+" + V2.money(spotReward.money()) + " " + f.host().currencyName();
            V2.bold(c, bonusCoin, 5.7F, goldAccentText(), cardX + 4.0F, cardY + cardH * 0.87F, cardW - 8.0F, Text.Align.CENTER);
        }

        long totalPaidCoins = 0L;
        int totalPaidStacks = 0;
        for (PassRow row : rows) {
            totalPaidCoins += row.premReward().money();
            totalPaidStacks += row.premReward().items().size();
        }
        float sumCy1 = cardY + cardH + (bottomAreaTop - (cardY + cardH)) * 0.42F;
        float sumCy2 = cardY + cardH + (bottomAreaTop - (cardY + cardH)) * 0.76F;
        if (bottomAreaTop - (cardY + cardH) >= 22.0F) {
            V2.text(c, "进阶满级总计额外可领", 5.3F, ShopTheme.text2(), x + 8.0F, sumCy1, w - 16.0F, Text.Align.CENTER);
            String totalLine = "+" + V2.money(totalPaidCoins) + " " + f.host().currencyName() + " · " + totalPaidStacks + " 项物品";
            V2.bold(c, totalLine, 6.1F, goldAccentText(), x + 6.0F, sumCy2, w - 12.0F, Text.Align.CENTER);
        }

        float btnX = x + 9.0F;
        float btnW = w - 18.0F;
        if (!QuestClientState.premiumEnabled()) {
            V2.button(c, tr("gui.module_shop_quest.free_only_mode"), V2.Btn.DISABLED, btnX, bottomAreaTop, btnW, ctaH, 0.0F);
        } else if (!QuestClientState.premiumUnlocked()) {
            String ctaLbl = tr("gui.module_shop_quest.btn_unlock_cta", QuestClientState.premiumTrackName());
            boolean over = inRect(mx, my, btnX, bottomAreaTop, btnW, ctaH);
            unlockCtaHover = Motion.hover(unlockCtaHover, over, dt);
            renderGoldPillButton(c, ctaLbl, btnX, bottomAreaTop, btnW, ctaH, unlockCtaHover, true);
            registerHit(btnX, bottomAreaTop, btnW, ctaH, () -> {
                subView = SUBVIEW_UNLOCK;
                QuestClient.playClickSound(1.08F);
            });
        } else if (QuestClientState.deluxeEnabled() && !QuestClientState.deluxeUnlocked()) {
            String ctaLbl = tr("gui.module_shop_quest.btn_upgrade_deluxe", QuestClientState.deluxeTrackName());
            boolean over = inRect(mx, my, btnX, bottomAreaTop, btnW, ctaH);
            unlockCtaHover = Motion.hover(unlockCtaHover, over, dt);
            renderGoldPillButton(c, ctaLbl, btnX, bottomAreaTop, btnW, ctaH, unlockCtaHover, true);
            registerHit(btnX, bottomAreaTop, btnW, ctaH, () -> {
                subView = SUBVIEW_UNLOCK;
                QuestClient.playClickSound(1.08F);
            });
        } else {
            renderGoldPillButton(c, "已解锁典藏通行证", btnX, bottomAreaTop, btnW, ctaH, 0.0F, false);
            registerHit(btnX, bottomAreaTop, btnW, ctaH, () -> {
                subView = SUBVIEW_UNLOCK;
                QuestClient.playClickSound(1.02F);
            });
        }
    }

    // ================================================================ 3. 【双档解锁专页】（全皮肤自适应清爽版）

    private void renderUnlockShowcaseView(Canvas c, AddonFrame f, ItemOverlay items,
                                          float x, float y, float w, float h,
                                          float mx, float my, float dt) {
        float pad = 8.0F;
        float ix = x + pad;
        float iw = w - pad * 2.0F;
        String currency = f.host().currencyName();
        long balance = f.host().balance();

        boolean showLeftArt = iw >= 480.0F;
        float leftW = showLeftArt ? clamp(iw * 0.32F, 156.0F, 228.0F) : 0.0F;
        float rightX = showLeftArt ? (ix + leftW + 10.0F) : ix;
        float rightW = showLeftArt ? (iw - leftW - 10.0F) : iw;

        if (showLeftArt) {
            Draw.outerShadow(c, ix, y, leftW, h, 7.0F, 6.0F, 1.5F, Draw.alpha(ShopTheme.shadow(), 0.22F));
            Draw.gradientV(c, ix, y, leftW, h, 7.0F, featuredTop(), ShopTheme.card());
            Draw.stroke(c, ix, y, leftW, h, 7.0F, 1.0F, goldBorder());

            float centerX = ix + leftW / 2.0F;
            float centerY = y + h * 0.39F;
            Draw.glow(c, centerX, centerY, Math.min(leftW, h) * 0.50F, Draw.alpha(GOLD_BRIGHT, 0.26F));

            float cw = Math.min(leftW * 0.54F, 102.0F);
            float ch = cw * 1.16F;
            Draw.gradientD(c, centerX - cw * 0.40F, centerY - ch * 0.50F, cw, ch, 6.0F, softSubCardTop(), ShopTheme.card());
            Draw.stroke(c, centerX - cw * 0.40F, centerY - ch * 0.50F, cw, ch, 6.0F, 0.8F, ShopTheme.border());

            Draw.gradientV(c, centerX - cw * 0.54F, centerY - ch * 0.42F, cw, ch, 6.0F, medalTop(), ShopTheme.card());
            Draw.stroke(c, centerX - cw * 0.54F, centerY - ch * 0.42F, cw, ch, 6.0F, 1.3F, goldBorder());

            List<PassRow> passRows = QuestClientState.passRows();
            ItemStack finaleItem = (!passRows.isEmpty() && !passRows.get(passRows.size() - 1).premReward().items().isEmpty())
                    ? passRows.get(passRows.size() - 1).premReward().items().get(0)
                    : new ItemStack(Items.NETHER_STAR);
            if (items != null) {
                items.add(finaleItem, centerX - cw * 0.04F - 16.0F, centerY - 14.0F, 32.0F, false);
                if (inRect(mx, my, centerX - cw * 0.54F, centerY - ch * 0.42F, cw, ch)) {
                    items.setHovered(finaleItem);
                }
            }

            V2.bold(c, QuestClientState.seasonName(), 8.0F, ShopTheme.text(),
                    ix + 10.0F, y + h * 0.74F, leftW - 20.0F, Text.Align.CENTER);
            String desc = "解锁后立即激活 1–" + QuestClientState.maxLevel() + " 级进阶奖励，之前已升到的等级也会全部补发。";
            var tl = Text.wrapped(desc, ShopTheme.regular(), 5.8F, ShopTheme.text2(), leftW - 22.0F, 3);
            Text.draw(c, tl, ix + 11.0F, y + h * 0.80F);

            String balLine = "当前余额：" + V2.money(balance) + " " + currency;
            V2.bold(c, balLine, 6.2F, ShopTheme.green(), ix + 10.0F, y + h - 12.0F, leftW - 20.0F, Text.Align.CENTER);
        }

        boolean hasDeluxe = QuestClientState.deluxeEnabled();
        float gap = 8.0F;
        float tier1H = hasDeluxe ? (h - gap) * 0.46F : Math.min(h, 210.0F);
        float tier2H = hasDeluxe ? (h - gap - tier1H) : 0.0F;

        renderStandardTierCard(c, f, items, rightX, y, rightW, tier1H, balance, currency, mx, my, dt);
        if (hasDeluxe) {
            renderDeluxeMedalCard(c, f, items, rightX, y + tier1H + gap, rightW, tier2H, balance, currency, mx, my, dt);
        }
    }

    private void renderStandardTierCard(Canvas c, AddonFrame f, ItemOverlay items,
                                        float x, float y, float w, float h,
                                        long balance, String currency, float mx, float my, float dt) {
        Draw.outerShadow(c, x, y, w, h, 7.0F, 6.0F, 1.5F, Draw.alpha(ShopTheme.shadow(), 0.20F));
        Draw.gradientH(c, x, y, w, h, 7.0F, softSubCardTop(), ShopTheme.card());
        Draw.stroke(c, x, y, w, h, 7.0F, 1.0F, Draw.alpha(ShopTheme.accent(), 0.45F));

        float leftBadgeW = clamp(w * 0.25F, 84.0F, 114.0F);
        float bannerW = 34.0F;
        float bannerX = x + (leftBadgeW - bannerW) / 2.0F;
        Draw.gradientV(c, bannerX, y + 1.0F, bannerW, h * 0.68F, 2.0F,
                Draw.alpha(ShopTheme.accent(), ShopTheme.dark() ? 0.42F : 0.20F),
                Draw.alpha(ShopTheme.accent(), 0.02F));
        float crestCy = y + h * 0.40F;
        drawVectorCrestEmblem(c, x + leftBadgeW / 2.0F, crestCy, Math.min(21.0F, h * 0.23F), ShopTheme.accent(), false);
        V2.bold(c, QuestClientState.premiumTrackName(), 8.0F, ShopTheme.text(),
                x + 6.0F, y + h * 0.80F, leftBadgeW - 12.0F, Text.Align.CENTER);

        float rx = x + leftBadgeW + 8.0F;
        float rw = Math.max(100.0F, w - leftBadgeW - 18.0F);

        long bonusPts = QuestClientState.premiumBonusPoints();
        String instantTxt = bonusPts > 0L
                ? ("解锁 1–" + QuestClientState.maxLevel() + " 级进阶奖励  ·  立得 +" + V2.money(bonusPts) + " 积分")
                : ("解锁 1–" + QuestClientState.maxLevel() + " 级全部进阶奖励");
        V2.bold(c, instantTxt, 7.0F, ShopTheme.text(), rx, y + h * 0.16F, rw, Text.Align.LEFT);
        V2.text(c, "● 满级累计额外可领", 5.8F, ShopTheme.text2(), rx, y + h * 0.31F, rw, Text.Align.LEFT);

        List<SlotVisual> summarySlots = buildSeasonPaidSummaryVisuals(currency);
        float slotSz = clamp(h * 0.26F, 26.0F, 36.0F);
        float slotY = y + h * 0.38F;
        int maxShow = Math.min(summarySlots.size(), Math.max(1, (int) ((rw + 4.0F) / (slotSz + 5.0F))));
        for (int i = 0; i < maxShow; i++) {
            renderSlotVisual(c, f, items, summarySlots.get(i),
                    rx + i * (slotSz + 5.0F), slotY, slotSz, false, false, false, mx, my, x, x + w);
        }

        float btnH = clamp(h * 0.19F, 19.0F, 24.0F);
        float btnW = Math.min(rw, 168.0F);
        float btnX = rx + rw - btnW;
        float btnY = y + h - btnH - 8.0F;
        long cost = QuestClientState.premiumUnlockCost();
        boolean unlocked = QuestClientState.premiumUnlocked();
        boolean canAfford = balance >= cost;

        if (unlocked) {
            renderGoldPillButton(c, "已解锁", btnX, btnY, btnW, btnH, 0.0F, false);
        } else if (canAfford) {
            String lbl = V2.money(cost) + " " + currency + " 解锁";
            boolean over = inRect(mx, my, btnX, btnY, btnW, btnH);
            tier1BtnHover = Motion.hover(tier1BtnHover, over, dt);
            renderGoldPillButton(c, lbl, btnX, btnY, btnW, btnH, tier1BtnHover, true);
            registerHit(btnX, btnY, btnW, btnH, () -> {
                QuestClient.sendUnlockPremium();
                subView = SUBVIEW_MAIN;
            });
        } else {
            V2.button(c, "星币不足（需 " + V2.money(cost) + "）", V2.Btn.DISABLED, btnX, btnY, btnW, btnH, 0.0F);
        }
    }

    private void renderDeluxeMedalCard(Canvas c, AddonFrame f, ItemOverlay items,
                                       float x, float y, float w, float h,
                                       long balance, String currency, float mx, float my, float dt) {
        Draw.outerShadow(c, x, y, w, h, 7.0F, 7.0F, 1.5F, Draw.alpha(ShopTheme.shadow(), 0.24F));
        Draw.gradientH(c, x, y, w, h, 7.0F, medalTop(), medalBot());
        Draw.stroke(c, x, y, w, h, 7.0F, 1.3F, goldBorder());

        float leftBadgeW = clamp(w * 0.25F, 84.0F, 114.0F);
        float bannerW = 38.0F;
        float bannerX = x + (leftBadgeW - bannerW) / 2.0F;
        Draw.gradientV(c, bannerX, y + 1.0F, bannerW, h * 0.72F, 2.0F,
                ShopTheme.dark() ? 0x88A83B33 : 0x33D95B52, 0x05D95B52);
        float crestCy = y + h * 0.41F;
        Draw.glow(c, x + leftBadgeW / 2.0F, crestCy, 28.0F, Draw.alpha(GOLD_BRIGHT, 0.28F));
        drawVectorCrestEmblem(c, x + leftBadgeW / 2.0F, crestCy, Math.min(24.0F, h * 0.25F), goldAccentText(), true);
        V2.bold(c, QuestClientState.deluxeTrackName(), 8.0F, goldAccentText(),
                x + 6.0F, y + h * 0.82F, leftBadgeW - 12.0F, Text.Align.CENTER);

        float rx = x + leftBadgeW + 8.0F;
        float rw = Math.max(100.0F, w - leftBadgeW - 18.0F);

        V2.bold(c, "包含【" + QuestClientState.premiumTrackName() + "】全部内容",
                7.0F, ShopTheme.text(), rx, y + h * 0.15F, rw, Text.Align.LEFT);

        int plusLv = QuestClientState.deluxeBonusLevels();
        String line2 = plusLv > 0
                ? ("同时立即提升 " + plusLv + " 级通行证等级")
                : "同时立即发放典藏额外礼包";
        V2.bold(c, line2, 7.2F, goldAccentText(), rx, y + h * 0.28F, rw, Text.Align.LEFT);

        V2.bold(c, "● 额外立即领取", 5.8F, ShopTheme.text2(), rx, y + h * 0.41F, rw, Text.Align.LEFT);

        List<SlotVisual> extraVisuals = buildPaidSlotVisuals(QuestClientState.deluxeReward(), currency);
        float slotSz = clamp(h * 0.25F, 26.0F, 36.0F);
        float slotY = y + h * 0.47F;
        int maxShow = Math.min(extraVisuals.size(), Math.max(1, (int) ((rw + 4.0F) / (slotSz + 5.0F))));
        for (int i = 0; i < maxShow; i++) {
            renderSlotVisual(c, f, items, extraVisuals.get(i),
                    rx + i * (slotSz + 5.0F), slotY, slotSz, false, QuestClientState.deluxeUnlocked(),
                    false, mx, my, x, x + w);
        }

        float btnH = clamp(h * 0.19F, 20.0F, 25.0F);
        float btnW = Math.min(rw, 190.0F);
        float btnX = rx + rw - btnW;
        float btnY = y + h - btnH - 8.0F;

        boolean deluxeDone = QuestClientState.deluxeUnlocked();
        boolean upgrading = QuestClientState.premiumUnlocked() && !deluxeDone;
        long effCost = QuestClientState.deluxeEffectiveCost();
        long origCost = upgrading
                ? Math.max(effCost, QuestClientState.deluxeOrigCost() - QuestClientState.premiumUnlockCost())
                : QuestClientState.deluxeOrigCost();
        boolean canAfford = balance >= effCost;

        if (deluxeDone) {
            renderGoldPillButton(c, "已解锁典藏版", btnX, btnY, btnW, btnH, 0.0F, false);
        } else if (!canAfford) {
            V2.button(c, "星币不足（需 " + V2.money(effCost) + "）", V2.Btn.DISABLED, btnX, btnY, btnW, btnH, 0.0F);
        } else {
            boolean over = inRect(mx, my, btnX, btnY, btnW, btnH);
            tier2BtnHover = Motion.hover(tier2BtnHover, over, dt);
            renderGoldPriceStrikeButton(c,
                    (upgrading ? "补差升级 " : "") + V2.money(effCost) + " " + currency,
                    origCost > effCost ? V2.money(origCost) : "",
                    btnX, btnY, btnW, btnH, tier2BtnHover);
            registerHit(btnX, btnY, btnW, btnH, () -> {
                QuestClient.sendUnlockDeluxe();
                subView = SUBVIEW_MAIN;
            });
        }
    }

    // ================================================================ 4. 【购买等级页】

    private void renderBuyLevelView(Canvas c, AddonFrame f, ItemOverlay items,
                                    float x, float y, float w, float h,
                                    float mx, float my, float dt) {
        float pad = 8.0F;
        float ix = x + pad;
        float iw = w - pad * 2.0F;

        int curLv = QuestClientState.currentLevel();
        int maxLv = QuestClientState.maxLevel();
        int remainLv = Math.max(0, maxLv - curLv);
        if (remainLv <= 0) {
            V2.empty(c, tr("msg.module_shop_quest.already_max_level"), ix, y, iw, h);
            return;
        }
        buyLevelDelta = Math.max(1, Math.min(buyLevelDelta, remainLv));
        int targetLv = curLv + buyLevelDelta;

        float topH = clamp(h * 0.28F, 68.0F, 90.0F);
        Draw.fill(c, ix, y, iw, topH, 6.0F, ShopTheme.card());
        Draw.stroke(c, ix, y, iw, topH, 6.0F, 0.8F, goldBorder());

        String transTxt = "Lv." + curLv + "   ───▶   Lv." + targetLv + "  （+" + buyLevelDelta + " 级）";
        V2.bold(c, transTxt, 11.0F, ShopTheme.text(), ix, y + topH * 0.40F, iw, Text.Align.CENTER);

        String[] stepLabels = {"-5", "-1", "+1", "+5", "+10", "满级"};
        int[] stepDeltas = {-5, -1, 1, 5, 10, remainLv};
        float btnW = 38.0F;
        float btnH = 17.0F;
        float totalBtnsW = stepLabels.length * btnW + (stepLabels.length - 1) * 6.0F;
        float startBx = ix + (iw - totalBtnsW) / 2.0F;
        float stepBy = y + topH - btnH - 8.0F;

        for (int i = 0; i < stepLabels.length; i++) {
            float bx = startBx + i * (btnW + 6.0F);
            boolean over = inRect(mx, my, bx, stepBy, btnW, btnH);
            drawMiniStepBtn(c, stepLabels[i], bx, stepBy, btnW, btnH, over);
            final int idx = i;
            registerHit(bx, stepBy, btnW, btnH, () -> {
                buyLevelDelta = (idx == 5) ? remainLv : Math.max(1, Math.min(remainLv, buyLevelDelta + stepDeltas[idx]));
                QuestClient.playClickSound(1.05F);
            });
        }

        float botBarH = 38.0F;
        float previewY = y + topH + 7.0F;
        float previewH = Math.max(60.0F, h - topH - botBarH - 14.0F);
        Draw.fill(c, ix, previewY, iw, previewH, 6.0F, ShopTheme.card());
        Draw.stroke(c, ix, previewY, iw, previewH, 6.0F, 0.7F, ShopTheme.borderDim());

        String unlockedNote = QuestClientState.premiumUnlocked()
                ? "购买后可立即领取以下免费 + 进阶奖励："
                : "购买后可立即领取以下免费奖励：";
        V2.bold(c, unlockedNote, 6.8F, ShopTheme.text(), ix + 12.0F, previewY + 13.0F, iw - 24.0F, Text.Align.LEFT);

        List<SlotVisual> previewVisuals = collectRangeRewardVisuals(
                curLv + 1, targetLv, QuestClientState.premiumUnlocked(), f.host().currencyName());
        float slotSz = clamp(previewH * 0.42F, 30.0F, 42.0F);
        float slotsStartY = previewY + 26.0F + Math.max(0.0F, (previewH - 30.0F - slotSz) / 2.0F);
        int maxCols = Math.max(1, (int) ((iw - 24.0F) / (slotSz + 6.0F)));
        int count = Math.min(previewVisuals.size(), maxCols);
        float rowW = count * (slotSz + 6.0F) - 6.0F;
        float startSx = ix + (iw - rowW) / 2.0F;
        for (int i = 0; i < count; i++) {
            renderSlotVisual(c, f, items, previewVisuals.get(i),
                    startSx + i * (slotSz + 6.0F), slotsStartY, slotSz, false, false, false, mx, my, ix, ix + iw);
        }

        float footCardY = previewY + previewH + 7.0F;
        Draw.fill(c, ix, footCardY, iw, botBarH, 6.0F, ShopTheme.card());
        Draw.stroke(c, ix, footCardY, iw, botBarH, 6.0F, 0.7F, ShopTheme.borderDim());

        long totalCost = QuestClientState.buyLevelCost() * buyLevelDelta;
        long bal = f.host().balance();
        String currency = f.host().currencyName();
        boolean enough = bal >= totalCost;

        String costInfo = V2.money(QuestClientState.buyLevelCost()) + " × " + buyLevelDelta + " 级  =  "
                + V2.money(totalCost) + " " + currency;
        V2.bold(c, costInfo, 7.5F, enough ? goldAccentText() : ShopTheme.red(),
                ix + 14.0F, footCardY + botBarH / 2.0F, iw * 0.62F, Text.Align.LEFT);

        float okW = 142.0F;
        float okH = 22.0F;
        float okX = ix + iw - okW - 12.0F;
        float okY = footCardY + (botBarH - okH) / 2.0F;
        if (enough) {
            String okLbl = "确认购买 +" + buyLevelDelta + " 级";
            boolean over = inRect(mx, my, okX, okY, okW, okH);
            confirmBuyLvHover = Motion.hover(confirmBuyLvHover, over, dt);
            renderGoldPillButton(c, okLbl, okX, okY, okW, okH, confirmBuyLvHover, true);
            final int buyCnt = buyLevelDelta;
            registerHit(okX, okY, okW, okH, () -> {
                QuestClient.sendBuyLevels(buyCnt);
                subView = SUBVIEW_MAIN;
            });
        } else {
            V2.button(c, tr("gui.module_shop_quest.btn_not_enough_money"), V2.Btn.DISABLED, okX, okY, okW, okH, 0.0F);
        }
    }

    // ================================================================ 5. 【今日任务 / 本周任务】双列卡片网格

    private void renderCommissionGridView(Canvas c, AddonFrame f, ItemOverlay items,
                                          ScrollController scroll, List<QuestRow> rows, boolean isDaily,
                                          float x, float y, float w, float h,
                                          float mx, float my, float dt) {
        float pad = 8.0F;
        float ix = x + pad;
        float iw = w - pad * 2.0F;

        float dashH = clamp(h * 0.165F, 48.0F, 58.0F);
        renderQuestDashboardBanner(c, f, rows, isDaily, ix, y + 1.0F, iw, dashH, mx, my);

        if (rows.isEmpty()) {
            V2.empty(c, tr("gui.module_shop_quest.empty_quests"), ix, y + dashH + 6.0F, iw, h - dashH - 8.0F);
            return;
        }

        float gridY = y + dashH + 7.0F;
        float gridH = Math.max(80.0F, h - dashH - 9.0F);
        questViewportX = ix;
        questViewportY = gridY;
        questViewportW = iw;
        questViewportH = gridH;

        int cols = iw >= 430.0F ? 2 : 1;
        int numRows = (rows.size() + cols - 1) / cols;

        float gapX = 8.0F;
        float gapY = 8.0F;
        float cardW = (iw - gapX * (cols - 1)) / cols;
        float naturalCardH = numRows <= 2
                ? clamp((gridH - gapY * Math.max(0, numRows - 1)) / Math.max(1, numRows), 84.0F, 134.0F)
                : 90.0F;
        float contentH = numRows * naturalCardH + Math.max(0, numRows - 1) * gapY;
        scroll.setContent(contentH, gridH);
        float offsetY = scroll.offset();

        int save = c.save();
        Draw.clip(c, ix, gridY, iw, gridH);
        if (items != null) {
            items.pushClip(ix, gridY, iw, gridH);
        }

        for (int i = 0; i < rows.size(); i++) {
            int rIdx = i / cols;
            int cIdx = i % cols;
            // 若最后一行只有 1 张卡（例如 3 条周任务），最后一张可自然拉宽占满整行，视觉整洁不留硬凑说明卡！
            boolean isLastOdd = (cols == 2) && (i == rows.size() - 1) && (rows.size() % 2 == 1);
            float actualW = isLastOdd ? iw : cardW;
            float cx = ix + cIdx * (cardW + gapX);
            float cy = gridY + rIdx * (naturalCardH + gapY) - offsetY;
            if (cy + naturalCardH < gridY - 2.0F || cy > gridY + gridH + 2.0F) continue;
            boolean allowHover = my >= gridY && my < gridY + gridH;
            renderCommissionCard(c, f, items, rows.get(i), cx, cy, actualW, naturalCardH,
                    allowHover ? mx : -9999.0F, allowHover ? my : -9999.0F);
        }

        if (items != null) {
            items.popClip();
        }
        c.restoreToCount(save);

        if (scroll.canScroll()) {
            V2.scrollbar(c, ix + iw - 3.0F, gridY, gridH, contentH, offsetY, 1.0F);
        }
    }

    private void renderQuestDashboardBanner(Canvas c, AddonFrame f, List<QuestRow> rows, boolean isDaily,
                                            float x, float y, float w, float h, float mx, float my) {
        Draw.outerShadow(c, x, y, w, h, 6.0F, 5.0F, 1.5F, Draw.alpha(ShopTheme.shadow(), 0.18F));
        Draw.gradientH(c, x, y, w, h, 6.0F, softSubCardTop(), ShopTheme.card());
        Draw.stroke(c, x, y, w, h, 6.0F, 0.7F, ShopTheme.borderDim());

        int done = 0;
        long totalPts = 0L;
        long totalCoins = 0L;
        for (QuestRow r : rows) {
            totalPts += r.reward().points();
            totalCoins += r.reward().money();
            if (r.completed()) done++;
        }

        float ringR = Math.min(18.0F, h * 0.37F);
        float ringCx = x + 12.0F + ringR;
        float ringCy = y + h / 2.0F;
        boolean allDone = !rows.isEmpty() && done >= rows.size();
        int ringColor = allDone ? ShopTheme.green() : ShopTheme.accent();

        Draw.fill(c, ringCx - ringR, ringCy - ringR, ringR * 2.0F, ringR * 2.0F, ringR, Draw.alpha(ringColor, 0.12F));
        Draw.stroke(c, ringCx - ringR, ringCy - ringR, ringR * 2.0F, ringR * 2.0F, ringR, 1.8F, ringColor);
        V2.bold(c, done + "/" + rows.size(), 8.2F, ringColor, ringCx - ringR, ringCy, ringR * 2.0F, Text.Align.CENTER);

        float midX = ringCx + ringR + 12.0F;
        float rightCardW = w >= 500.0F ? 136.0F : 0.0F;
        float midW = Math.max(100.0F, w - (midX - x) - rightCardW - 16.0F);

        String title = isDaily
                ? tr("gui.module_shop_quest.daily_progress_stat", done, rows.size())
                : tr("gui.module_shop_quest.weekly_progress_stat", done, rows.size());
        float tw = V2.bold(c, title, 8.0F, ShopTheme.text(), midX, y + h * 0.32F, midW * 0.58F, Text.Align.LEFT);

        String resetTxt = isDaily
                ? tr("gui.module_shop_quest.daily_reset_in",
                QuestClientState.formatCountdown(QuestClientState.secondsUntilDailyReset()))
                : tr("gui.module_shop_quest.weekly_reset_in",
                QuestClientState.formatCountdown(QuestClientState.secondsUntilWeeklyReset()));
        V2.tag(c, resetTxt, ShopTheme.accent2(), midX + tw + 6.0F, y + h * 0.32F);

        String poolYield = "本页合计：+" + V2.money(totalCoins) + " " + f.host().currencyName()
                + "  ·  +" + V2.money(totalPts) + " 通行证积分";
        V2.text(c, poolYield, 6.0F, ShopTheme.text2(), midX, y + h * 0.68F, midW, Text.Align.LEFT);

        if (rightCardW > 0.0F) {
            float rx = x + w - rightCardW - 8.0F;
            float ry = y + 7.0F;
            float rh = h - 14.0F;
            Draw.gradientH(c, rx, ry, rightCardW, rh, 5.0F, featuredTop(), featuredBot());
            Draw.stroke(c, rx, ry, rightCardW, rh, 5.0F, 0.7F, goldBorder());
            V2.bold(c, "通行证 Lv." + QuestClientState.currentLevel() + "/" + QuestClientState.maxLevel(),
                    6.5F, ShopTheme.text(), rx + 8.0F, ry + rh * 0.36F, rightCardW - 16.0F, Text.Align.LEFT);
            V2.text(c, "查看等级奖励 →", 5.4F, goldAccentText(),
                    rx + 8.0F, ry + rh * 0.72F, rightCardW - 16.0F, Text.Align.LEFT);
            registerHit(rx, ry, rightCardW, rh, () -> {
                activeTab = TAB_PASS;
                QuestClient.playClickSound(1.04F);
            });
        }
    }

    private void renderCommissionCard(Canvas c, AddonFrame f, ItemOverlay items, QuestRow r,
                                      float x, float y, float w, float h, float mx, float my) {
        boolean over = inRect(mx, my, x, y, w, h);
        boolean canClaim = r.claimable();
        int catColor = sourceColor(r.requiredMod(), r.period());

        Draw.outerShadow(c, x, y, w, h, 6.0F, canClaim ? 7.0F : 4.5F, canClaim ? 0.0F : 1.5F,
                canClaim ? Draw.alpha(GOLD_BRIGHT, 0.25F) : Draw.alpha(ShopTheme.shadow(), 0.16F));
        Draw.fill(c, x, y, w, h, 6.0F,
                over ? Draw.lerp(ShopTheme.card(), ShopTheme.rowHover(), 0.45F) : ShopTheme.card());
        Draw.stroke(c, x, y, w, h, 6.0F, canClaim ? 1.1F : 0.7F,
                canClaim ? goldBorder() : (over ? ShopTheme.border() : ShopTheme.borderDim()));

        Draw.fill(c, x + 2.5F, y + 7.0F, 2.8F, h - 14.0F, 1.4F, catColor);

        float innerX = x + 11.0F;
        float topCy = y + 14.0F;
        float tagW = V2.tag(c, sourceLabel(r.requiredMod(), r.period()), catColor, innerX, topCy);

        List<SlotVisual> rewards = buildQuestRewardVisuals(r.reward(), f.host().currencyName());
        float rSlotSz = clamp(h * 0.33F, 26.0F, 32.0F);
        float totalRw = rewards.size() * (rSlotSz + 4.0F) - 4.0F;
        float rStartX = x + w - 9.0F - totalRw;
        float rSlotY = y + 8.0F;
        for (int i = 0; i < rewards.size(); i++) {
            renderSlotVisual(c, f, items, rewards.get(i),
                    rStartX + i * (rSlotSz + 4.0F), rSlotY, rSlotSz,
                    false, r.claimed(), canClaim, mx, my, x, x + w);
        }

        float textMaxW = Math.max(60.0F, rStartX - (innerX + tagW + 6.0F) - 6.0F);
        V2.bold(c, r.title(), 8.0F, r.invalidated() ? ShopTheme.text3() : ShopTheme.text(),
                innerX + tagW + 5.0F, topCy, textMaxW, Text.Align.LEFT);

        if (r.desc() != null && !r.desc().isBlank()) {
            var descLayout = Text.wrapped(r.desc(), ShopTheme.regular(), 6.0F,
                    ShopTheme.text2(), Math.max(80.0F, rStartX - innerX - 8.0F), 2);
            Text.draw(c, descLayout, innerX, y + 25.0F);
        }

        float btnW = 64.0F;
        float btnH = 18.0F;
        float btnX = x + w - 9.0F - btnW;
        float btnY = y + h - btnH - 8.0F;

        float barX = innerX;
        float barW = Math.max(50.0F, btnX - barX - 12.0F);
        float barCy = btnY + btnH * 0.65F;
        float pct = clamp((float) r.progress() / (float) Math.max(1, r.target()), 0.0F, 1.0F);

        V2.bold(c, r.progress() + " / " + r.target(), 5.8F, r.completed() ? ShopTheme.green() : ShopTheme.text2(),
                barX, btnY + 3.0F, barW, Text.Align.LEFT);

        Draw.fill(c, barX, barCy - 2.3F, barW, 4.6F, 2.3F,
                Draw.alpha(ShopTheme.text3(), ShopTheme.dark() ? 0.24F : 0.14F));
        if (pct > 0.005F) {
            float fw = Math.max(4.6F, barW * pct);
            if (r.completed()) {
                Draw.fill(c, barX, barCy - 2.3F, fw, 4.6F, 2.3F, ShopTheme.green());
            } else {
                Draw.gradientH(c, barX, barCy - 2.3F, fw, 4.6F, 2.3F, ShopTheme.accent(), GOLD_BRIGHT);
            }
        }

        if (r.invalidated()) {
            V2.button(c, tr("gui.module_shop_quest.state_invalid"), V2.Btn.DISABLED, btnX, btnY, btnW, btnH, 0.0F);
        } else if (r.claimed()) {
            V2.button(c, "✓ 已领取", V2.Btn.DISABLED, btnX, btnY, btnW, btnH, 0.0F);
        } else if (canClaim) {
            V2.button(c, tr("gui.module_shop_quest.btn_claim"), V2.Btn.PRIMARY, btnX, btnY, btnW, btnH,
                    inRect(mx, my, btnX, btnY, btnW, btnH) ? 1.0F : 0.0F);
            registerHit(btnX, btnY, btnW, btnH, () -> QuestClient.sendClaimQuest(r.period(), r.id()));
        } else {
            V2.button(c, tr("gui.module_shop_quest.state_ongoing"), V2.Btn.DISABLED, btnX, btnY, btnW, btnH, 0.0F);
        }
    }

    // ================================================================ 6. 【在线奖励】全皮肤自适应双排时间轴

    private void renderOnlineTimelineView(Canvas c, AddonFrame f, ItemOverlay items,
                                          float x, float y, float w, float h,
                                          float mx, float my, float dt) {
        List<OnlineRow> all = QuestClientState.onlineRows();
        if (all.isEmpty()) {
            V2.empty(c, tr("gui.module_shop_quest.empty_online"), x, y, w, h);
            return;
        }

        List<OnlineRow> daily = new ArrayList<>();
        List<OnlineRow> weekly = new ArrayList<>();
        int graceCount = 0;
        for (OnlineRow r : all) {
            if ("weekly".equals(r.period())) weekly.add(r);
            else if ("prev_daily".equals(r.period())) graceCount++;
            else daily.add(r);
        }

        float pad = 8.0F;
        float ix = x + pad;
        float iw = w - pad * 2.0F;

        float teleH = clamp(h * 0.18F, 50.0F, 62.0F);
        renderOnlineTelemetryHeader(c, ix, y + 1.0F, iw, teleH, daily, weekly, graceCount, mx, my);

        float remY = y + teleH + 7.0F;
        float remH = Math.max(120.0F, h - teleH - 9.0F);
        float rowGap = 8.0F;
        float trackH = (remH - rowGap) / 2.0F;

        renderOnlineTrackRow(c, f, items, "每日在线奖励", daily, false, ix, remY, iw, trackH, mx, my);
        renderOnlineTrackRow(c, f, items, "每周在线奖励", weekly, true, ix, remY + trackH + rowGap, iw, trackH, mx, my);
    }

    private void renderOnlineTelemetryHeader(Canvas c, float x, float y, float w, float h,
                                             List<OnlineRow> daily, List<OnlineRow> weekly,
                                             int graceCount, float mx, float my) {
        float gap = 8.0F;
        float cardW = (w - gap) / 2.0F;

        // 左卡：今日在线
        Draw.outerShadow(c, x, y, cardW, h, 6.0F, 4.5F, 1.5F, Draw.alpha(ShopTheme.shadow(), 0.18F));
        Draw.gradientH(c, x, y, cardW, h, 6.0F, softSubCardTop(), ShopTheme.card());
        Draw.stroke(c, x, y, cardW, h, 6.0F, 0.7F, ShopTheme.borderDim());

        int dayMin = QuestClientState.dailyMinutes();
        int dayMaxTarget = daily.isEmpty() ? 60 : daily.get(daily.size() - 1).reqMin();
        boolean paused = QuestClientState.afkPaused() && QuestClientState.showAfkHint();

        V2.bold(c, "今日在线：" + dayMin + " 分钟", 9.0F, ShopTheme.text(),
                x + 12.0F, y + h * 0.32F, cardW * 0.65F, Text.Align.LEFT);
        V2.tag(c, paused ? tr("gui.module_shop_quest.afk_paused_tag") : tr("gui.module_shop_quest.afk_active_tag"),
                paused ? ShopTheme.gold() : ShopTheme.green(), x + cardW - 72.0F, y + h * 0.32F);

        if (graceCount > 0) {
            float gw = 74.0F;
            float gx = x + cardW - gw - 10.0F;
            float gy = y + h * 0.56F;
            V2.button(c, "昨日补领 (" + graceCount + ")", V2.Btn.PRIMARY, gx, gy, gw, 15.0F,
                    inRect(mx, my, gx, gy, gw, 15.0F) ? 1.0F : 0.0F);
            registerHit(gx, gy, gw, 15.0F, QuestClient::sendClaimAll);
        }

        float dPct = clamp((float) dayMin / (float) Math.max(1, dayMaxTarget), 0.0F, 1.0F);
        float dBarW = graceCount > 0 ? (cardW - 102.0F) : (cardW - 24.0F);
        Draw.fill(c, x + 12.0F, y + h * 0.60F, dBarW, 4.5F, 2.2F, Draw.alpha(ShopTheme.text3(), 0.15F));
        if (dPct > 0.005F) {
            Draw.gradientH(c, x + 12.0F, y + h * 0.60F, Math.max(4.5F, dBarW * dPct), 4.5F, 2.2F,
                    ShopTheme.green(), ShopTheme.accent());
        }
        String dReset = tr("gui.module_shop_quest.daily_reset_in",
                QuestClientState.formatCountdown(QuestClientState.secondsUntilDailyReset()));
        V2.text(c, dReset, 5.2F, ShopTheme.text3(), x + 12.0F, y + h * 0.83F, dBarW, Text.Align.LEFT);

        // 右卡：本周在线（亮色皮肤同系柔和香槟色调，告别突兀黑块）
        float rx = x + cardW + gap;
        Draw.outerShadow(c, rx, y, cardW, h, 6.0F, 4.5F, 1.5F, Draw.alpha(ShopTheme.shadow(), 0.18F));
        Draw.gradientH(c, rx, y, cardW, h, 6.0F, featuredTop(), ShopTheme.card());
        Draw.stroke(c, rx, y, cardW, h, 6.0F, 0.7F, goldBorder());

        int weekMin = QuestClientState.weeklyMinutes();
        int weekMaxTarget = weekly.isEmpty() ? 360 : weekly.get(weekly.size() - 1).reqMin();
        V2.bold(c, "本周在线：" + weekMin + " 分钟", 9.0F, ShopTheme.text(),
                rx + 12.0F, y + h * 0.32F, cardW - 24.0F, Text.Align.LEFT);

        float wPct = clamp((float) weekMin / (float) Math.max(1, weekMaxTarget), 0.0F, 1.0F);
        float wBarW = cardW - 24.0F;
        Draw.fill(c, rx + 12.0F, y + h * 0.60F, wBarW, 4.5F, 2.2F, Draw.alpha(ShopTheme.text3(), 0.15F));
        if (wPct > 0.005F) {
            Draw.gradientH(c, rx + 12.0F, y + h * 0.60F, Math.max(4.5F, wBarW * wPct), 4.5F, 2.2F,
                    ShopTheme.accent(), GOLD_BRIGHT);
        }
        String wReset = tr("gui.module_shop_quest.weekly_reset_in",
                QuestClientState.formatCountdown(QuestClientState.secondsUntilWeeklyReset()));
        V2.text(c, wReset, 5.2F, ShopTheme.text3(), rx + 12.0F, y + h * 0.83F, wBarW, Text.Align.LEFT);
    }

    private void renderOnlineTrackRow(Canvas c, AddonFrame f, ItemOverlay items,
                                      String sectionTitle, List<OnlineRow> tiers, boolean isWeekly,
                                      float x, float y, float w, float h, float mx, float my) {
        Draw.outerShadow(c, x, y, w, h, 6.0F, 4.5F, 1.5F, Draw.alpha(ShopTheme.shadow(), 0.16F));
        if (isWeekly) {
            Draw.gradientV(c, x, y, w, h, 6.0F, featuredTop(), ShopTheme.card());
            Draw.stroke(c, x, y, w, h, 6.0F, 0.8F, goldBorder());
        } else {
            Draw.fill(c, x, y, w, h, 6.0F, ShopTheme.card());
            Draw.stroke(c, x, y, w, h, 6.0F, 0.7F, ShopTheme.borderDim());
        }

        V2.bold(c, sectionTitle, 7.2F, ShopTheme.text(), x + 12.0F, y + 11.5F, w - 24.0F, Text.Align.LEFT);
        if (tiers.isEmpty()) return;

        float cardsTop = y + 21.0F;
        float cardsH = Math.max(54.0F, h - 26.0F);
        float padX = 10.0F;
        float gap = 10.0F;
        int n = tiers.size();
        float cardW = (w - padX * 2.0F - gap * (n - 1)) / n;

        float nodeY = cardsTop + 11.0F;
        Draw.line(c, x + padX + cardW * 0.5F, nodeY, x + w - padX - cardW * 0.5F, nodeY,
                2.0F, Draw.alpha(isWeekly ? GOLD_BORDER_LIGHT : ShopTheme.accent(), 0.28F));

        String currency = f.host().currencyName();
        for (int i = 0; i < n; i++) {
            OnlineRow r = tiers.get(i);
            float cx = x + padX + i * (cardW + gap);
            boolean done = r.completed();
            boolean canClaim = r.claimable();

            int cBg = done
                    ? Draw.lerp(ShopTheme.card(), ShopTheme.accentTint(), 0.30F)
                    : Draw.alpha(ShopTheme.card(), 0.90F);
            Draw.fill(c, cx, cardsTop, cardW, cardsH, 5.5F, cBg);
            Draw.stroke(c, cx, cardsTop, cardW, cardsH, 5.5F, canClaim ? 1.2F : 0.7F,
                    canClaim ? goldBorder() : ShopTheme.borderDim());

            float nodeCx = cx + cardW / 2.0F;
            drawDiamond(c, nodeCx - V2.width(r.reqMin() + " 分钟", 6.4F, true) / 2.0F - 6.0F, nodeY,
                    2.6F, done ? GOLD_BRIGHT : ShopTheme.text3());
            V2.bold(c, r.reqMin() + " 分钟", 6.6F, done ? ShopTheme.accent() : ShopTheme.text(),
                    cx + 6.0F, nodeY, cardW - 12.0F, Text.Align.CENTER);

            List<SlotVisual> visuals = buildQuestRewardVisuals(r.reward(), currency);
            float slotSz = clamp(cardsH * 0.38F, 24.0F, 34.0F);
            float rowW = visuals.size() * (slotSz + 5.0F) - 5.0F;
            float sx = cx + (cardW - rowW) / 2.0F;
            float sy = cardsTop + 20.0F + Math.max(0.0F, (cardsH - 44.0F - slotSz) / 2.0F);
            for (int k = 0; k < visuals.size(); k++) {
                renderSlotVisual(c, f, items, visuals.get(k),
                        sx + k * (slotSz + 5.0F), sy, slotSz, false, r.claimed(), canClaim, mx, my, cx, cx + cardW);
            }

            float btnH = clamp(cardsH * 0.21F, 16.0F, 19.0F);
            float btnW = Math.min(cardW - 16.0F, 110.0F);
            float bx = cx + (cardW - btnW) / 2.0F;
            float by = cardsTop + cardsH - btnH - 5.0F;

            if (r.claimed()) {
                V2.button(c, "✓ 已领取", V2.Btn.DISABLED, bx, by, btnW, btnH, 0.0F);
            } else if (canClaim) {
                V2.button(c, tr("gui.module_shop_quest.btn_claim"), V2.Btn.PRIMARY, bx, by, btnW, btnH,
                        inRect(mx, my, bx, by, btnW, btnH) ? 1.0F : 0.0F);
                registerHit(bx, by, btnW, btnH, () -> QuestClient.sendClaimOnline(r.period(), r.id()));
            } else {
                int diff = Math.max(1, r.reqMin() - r.currentMin());
                V2.button(c, "还差 " + diff + " 分钟", V2.Btn.DISABLED, bx, by, btnW, btnH, 0.0F);
            }
        }
    }

    // ================================================================ 7. 亮/暗双模品质奖励卡槽（修复 3D 物品遮挡已领对勾）

    private record SlotVisual(ItemStack stack, String bottomTag, int rarity, String customTooltip) {
    }

    private void renderFreeCellSlot(Canvas c, AddonFrame f, ItemOverlay items, RewardView reward,
                                    float x, float y, float size, boolean reached, boolean claimed,
                                    boolean claimable, String currency, float mx, float my,
                                    float clipMinX, float clipMaxX) {
        if (!reward.items().isEmpty()) {
            ItemStack st = reward.items().get(0);
            SlotVisual sv = new SlotVisual(st, "×" + st.getCount(), inferItemRarity(st, reward.money()), "");
            renderSlotVisual(c, f, items, sv, x, y, size, false, claimed, claimable, mx, my, clipMinX, clipMaxX);
        } else if (reward.money() > 0L) {
            SlotVisual sv = new SlotVisual(coinStack(), "+" + V2.money(reward.money()),
                    inferCoinRarity(reward.money()), "+" + V2.money(reward.money()) + " " + currency);
            renderSlotVisual(c, f, items, sv, x, y, size, false, claimed, claimable, mx, my, clipMinX, clipMaxX);
        }
    }

    private List<SlotVisual> buildPaidSlotVisuals(RewardView reward, String currency) {
        List<SlotVisual> out = new ArrayList<>(4);
        for (ItemStack st : reward.items()) {
            out.add(new SlotVisual(st, "×" + st.getCount(), inferItemRarity(st, 0L), ""));
        }
        if (reward.money() > 0L) {
            out.add(new SlotVisual(coinStack(), "+" + V2.money(reward.money()),
                    inferCoinRarity(reward.money()), "+" + V2.money(reward.money()) + " " + currency));
        }
        if (reward.points() > 0L) {
            out.add(new SlotVisual(ptStack(), "+" + reward.points() + "PT", 3,
                    "通行证积分 +" + reward.points()));
        }
        return out;
    }

    private List<SlotVisual> buildQuestRewardVisuals(RewardView reward, String currency) {
        List<SlotVisual> out = new ArrayList<>(4);
        for (int i = 0; i < Math.min(2, reward.items().size()); i++) {
            ItemStack st = reward.items().get(i);
            out.add(new SlotVisual(st, "×" + st.getCount(), inferItemRarity(st, 0L), ""));
        }
        if (reward.money() > 0L) {
            out.add(new SlotVisual(coinStack(), "+" + V2.money(reward.money()),
                    inferCoinRarity(reward.money()), "+" + V2.money(reward.money()) + " " + currency));
        }
        if (reward.points() > 0L) {
            out.add(new SlotVisual(ptStack(), "+" + reward.points() + "PT", 4,
                    "通行证积分 +" + reward.points()));
        }
        return out;
    }

    private List<SlotVisual> buildSeasonPaidSummaryVisuals(String currency) {
        List<PassRow> rows = QuestClientState.passRows();
        long totalMoney = 0L;
        Map<String, ItemStack> milestoneAgg = new LinkedHashMap<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            PassRow r = rows.get(i);
            totalMoney += r.premReward().money();
            if (r.highlight()) {
                for (ItemStack st : r.premReward().items()) {
                    String key = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                    if (!milestoneAgg.containsKey(key) && milestoneAgg.size() < 4) {
                        milestoneAgg.put(key, st.copy());
                    }
                }
            }
        }
        List<SlotVisual> out = new ArrayList<>();
        if (totalMoney > 0L) {
            out.add(new SlotVisual(coinStack(), "+" + V2.money(totalMoney), 5,
                    "进阶通行证满级合计 +" + V2.money(totalMoney) + " " + currency));
        }
        for (ItemStack st : milestoneAgg.values()) {
            out.add(new SlotVisual(st, "×" + st.getCount(), inferItemRarity(st, 200L), ""));
        }
        return out;
    }

    private List<SlotVisual> collectRangeRewardVisuals(int fromLv, int toLv, boolean includePaid, String currency) {
        long coins = 0L;
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, ItemStack> proto = new LinkedHashMap<>();
        for (PassRow r : QuestClientState.passRows()) {
            if (r.level() < fromLv || r.level() > toLv) continue;
            coins += r.freeReward().money();
            for (ItemStack st : r.freeReward().items()) {
                String k = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                counts.merge(k, st.getCount(), Integer::sum);
                proto.putIfAbsent(k, st);
            }
            if (includePaid && QuestClientState.premiumEnabled()) {
                coins += r.premReward().money();
                for (ItemStack st : r.premReward().items()) {
                    String k = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                    counts.merge(k, st.getCount(), Integer::sum);
                    proto.putIfAbsent(k, st);
                }
            }
        }
        List<SlotVisual> out = new ArrayList<>();
        if (coins > 0L) {
            out.add(new SlotVisual(coinStack(), "+" + V2.money(coins), 5,
                    "累计 +" + V2.money(coins) + " " + currency));
        }
        for (var e : counts.entrySet()) {
            ItemStack st = proto.get(e.getKey());
            out.add(new SlotVisual(st, "×" + e.getValue(), inferItemRarity(st, 0L), ""));
        }
        return out;
    }

    private void renderSlotVisual(Canvas c, AddonFrame f, ItemOverlay items, SlotVisual sv,
                                  float x, float y, float size, boolean locked, boolean claimed,
                                  boolean claimable, float mx, float my, float clipMinX, float clipMaxX) {
        boolean dark = ShopTheme.dark();
        int topCol;
        int botCol;
        int rimCol;
        int pillBg;
        int pillFg;

        if (dark) {
            switch (sv.rarity) {
                case 5 -> { topCol = 0xFF6E481C; botCol = 0xFFB87F2D; rimCol = 0xFFFFDC85; pillBg = 0xBB16111D; pillFg = 0xFFFFEAB0; }
                case 4 -> { topCol = 0xFF452C73; botCol = 0xFF7449BD; rimCol = 0xFFD5B3FF; pillBg = 0xBB16111D; pillFg = 0xFFEEE0FF; }
                case 3 -> { topCol = 0xFF224573; botCol = 0xFF3A70B8; rimCol = 0xFF9BCEFF; pillBg = 0xBB16111D; pillFg = 0xFFDDF0FF; }
                default -> { topCol = 0xFF205448; botCol = 0xFF348772; rimCol = 0xFF9AEBD5; pillBg = 0xBB16111D; pillFg = 0xFFDDFBF2; }
            }
        } else {
            // 亮色皮肤清透浅色宝石品质卡（柔和融入樱花粉/白色卡片，绝不发黑发脏）
            switch (sv.rarity) {
                case 5 -> { topCol = 0xFFFFF8E5; botCol = 0xFFFFE4A3; rimCol = 0xFFDF9B28; pillBg = 0xDDFFF2CC; pillFg = 0xFF734205; }
                case 4 -> { topCol = 0xFFF8F2FF; botCol = 0xFFE8D5FF; rimCol = 0xFF9664EB; pillBg = 0xDDF0E3FF; pillFg = 0xFF4B218A; }
                case 3 -> { topCol = 0xFFEEF7FF; botCol = 0xFFD2E8FF; rimCol = 0xFF4B8CE0; pillBg = 0xDDE0F0FF; pillFg = 0xFF18498A; }
                default -> { topCol = 0xFFEEFCF7; botCol = 0xFFCDF3E4; rimCol = 0xFF30A882; pillBg = 0xDDD9F7EB; pillFg = 0xFF115E46; }
            }
        }

        if (claimed) {
            topCol = Draw.lerp(topCol, ShopTheme.card(), 0.62F);
            botCol = Draw.lerp(botCol, ShopTheme.card(), 0.62F);
            pillBg = Draw.alpha(ShopTheme.green(), dark ? 0.28F : 0.16F);
            pillFg = ShopTheme.green();
        }

        boolean hovered = mx >= Math.max(x, clipMinX) && mx < Math.min(x + size, clipMaxX)
                && my >= y && my < y + size;

        if (claimable) {
            Draw.outerShadow(c, x, y, size, size, 4.5F, 5.0F, 0.0F, Draw.alpha(GOLD_BRIGHT, 0.50F));
        }
        Draw.gradientV(c, x, y, size, size, 4.5F, topCol, botCol);
        Draw.fill(c, x + 1.0F, y + size - 8.5F, size - 2.0F, 7.5F, 2.5F, pillBg);
        Draw.line(c, x + 2.5F, y + size - 8.8F, x + size - 2.5F, y + size - 8.8F, 0.8F,
                claimed ? Draw.alpha(ShopTheme.green(), 0.45F) : Draw.alpha(rimCol, 0.65F));
        Draw.stroke(c, x, y, size, size, 4.5F, claimable ? 1.3F : (hovered ? 1.1F : 0.7F),
                claimable ? GOLD_BORDER_LIGHT : (hovered ? ShopTheme.accent() : Draw.alpha(rimCol, 0.68F)));

        float iconSz = clamp(size * 0.52F, 13.0F, 19.0F);
        float ix = x + (size - iconSz) / 2.0F;
        float iy = y + (size - 8.5F - iconSz) / 2.0F + 0.5F;
        if (items != null && !sv.stack.isEmpty() && x + size > clipMinX && x < clipMaxX) {
            items.add(sv.stack, ix, iy, iconSz, false);
            if (hovered) {
                if (sv.customTooltip != null && !sv.customTooltip.isBlank()) {
                    f.host().textTooltip(Component.literal(sv.customTooltip));
                } else {
                    items.setHovered(sv.stack);
                }
            }
        }

        String tagTxt = claimed ? "✓ 已领" : sv.bottomTag;
        V2.bold(c, tagTxt, 5.0F, pillFg, x + 1.0F, y + size - 4.6F, size - 2.0F, Text.Align.CENTER);

        // 右上角/左上角状态小徽标（避开中央 3D 物品渲染区，绝不被遮挡！）
        if (claimed) {
            float cr = 4.2F;
            float ccx = x + size - 5.2F;
            float ccy = y + 5.2F;
            Draw.fill(c, ccx - cr, ccy - cr, cr * 2.0F, cr * 2.0F, cr, ShopTheme.green());
            drawCheckmark(c, ccx, ccy, 2.3F, 0xFFFFFFFF);
        } else if (locked) {
            drawMiniLock(c, x + 5.5F, y + 5.5F, goldAccentText());
        } else if (claimable) {
            drawMiniStarIcon(c, x + size - 5.0F, y + 5.0F, 3.0F, GOLD_BORDER_LIGHT);
        }
    }

    private static int inferItemRarity(ItemStack st, long bonusMoney) {
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        if (id.contains("nether_star") || id.contains("totem") || id.contains("enchanted_golden_apple")
                || id.contains("netherite_ingot") || id.contains("beacon") || bonusMoney >= 600L) {
            return 5;
        }
        if (id.contains("diamond") || id.contains("netherite") || id.contains("emerald_block")
                || id.contains("golden_apple")) {
            return 4;
        }
        if (id.contains("emerald") || id.contains("golden_carrot") || id.contains("gold") || id.contains("iron")) {
            return 3;
        }
        return 2;
    }

    private static int inferCoinRarity(long money) {
        if (money >= 450L) return 5;
        if (money >= 150L) return 4;
        if (money >= 60L) return 3;
        return 2;
    }

    // ---------------------------------------------------------------- 按钮与矢量图标辅助

    private static void renderGoldPillButton(Canvas c, String label, float x, float y, float w, float h,
                                             float hover, boolean glow) {
        float r = h / 2.0F;
        if (glow) {
            Draw.outerShadow(c, x, y, w, h, r, 5.0F + hover * 2.0F, 0.0F, Draw.alpha(GOLD_BRIGHT, 0.34F + hover * 0.18F));
        }
        int cTop = Draw.lerp(0xFFFBE398, 0xFFFFEEB2, hover);
        int cBot = Draw.lerp(0xFFE6AC40, 0xFFF2BC52, hover);
        Draw.gradientV(c, x, y, w, h, r, cTop, cBot);
        Draw.stroke(c, x, y, w, h, r, 0.8F, 0xFFE29D2B);
        V2.bold(c, label, 6.6F, GOLD_BTN_TEXT, x + 6.0F, y + h / 2.0F, w - 12.0F, Text.Align.CENTER);
    }

    private static void renderGoldPriceStrikeButton(Canvas c, String mainPrice, String strikePrice,
                                                    float x, float y, float w, float h, float hover) {
        float r = h / 2.0F;
        Draw.outerShadow(c, x, y, w, h, r, 5.5F + hover * 2.0F, 0.0F, Draw.alpha(GOLD_BRIGHT, 0.38F + hover * 0.18F));
        int cTop = Draw.lerp(0xFFFBE398, 0xFFFFEEB2, hover);
        int cBot = Draw.lerp(0xFFE6AC40, 0xFFF2BC52, hover);
        Draw.gradientV(c, x, y, w, h, r, cTop, cBot);
        Draw.stroke(c, x, y, w, h, r, 0.9F, 0xFFE29D2B);

        float cy = y + h / 2.0F;
        if (strikePrice == null || strikePrice.isBlank()) {
            V2.bold(c, mainPrice, 7.4F, GOLD_BTN_TEXT, x + 6.0F, cy, w - 12.0F, Text.Align.CENTER);
            return;
        }
        float wMain = V2.width(mainPrice, 7.4F, true);
        float wOld = V2.width(strikePrice, 6.5F, false);
        float totalW = wMain + 7.0F + wOld;
        float startX = x + (w - totalW) / 2.0F;
        V2.bold(c, mainPrice, 7.4F, GOLD_BTN_TEXT, startX, cy, wMain + 4.0F, Text.Align.LEFT);
        float oldX = startX + wMain + 7.0F;
        V2.text(c, strikePrice, 6.5F, 0xB8593E18, oldX, cy, wOld + 4.0F, Text.Align.LEFT);
        Draw.line(c, oldX - 1.0F, cy + 0.3F, oldX + wOld + 1.0F, cy + 0.3F, 1.1F, 0xCC593E18);
    }

    private static void drawMiniStepBtn(Canvas c, String label, float x, float y, float w, float h, boolean over) {
        Draw.fill(c, x, y, w, h, 3.0F,
                over ? Draw.alpha(ShopTheme.accent(), 0.20F) : Draw.alpha(ShopTheme.text3(), 0.10F));
        Draw.stroke(c, x, y, w, h, 3.0F, 0.6F, over ? ShopTheme.accent() : ShopTheme.borderDim());
        V2.bold(c, label, 5.4F, over ? ShopTheme.accent() : ShopTheme.text2(), x, y + h / 2.0F, w, Text.Align.CENTER);
    }

    private static void drawVectorGiftEmblem(Canvas c, float cx, float cy, float r, int color) {
        float bw = r * 1.4F;
        float bh = r * 1.15F;
        Draw.fill(c, cx - bw / 2.0F, cy - bh * 0.35F, bw, bh, 2.5F, Draw.alpha(color, 0.14F));
        Draw.stroke(c, cx - bw / 2.0F, cy - bh * 0.35F, bw, bh, 2.5F, 1.2F, color);
        Draw.stroke(c, cx - bw * 0.56F, cy - bh * 0.58F, bw * 1.12F, bh * 0.28F, 1.5F, 1.2F, color);
        Draw.line(c, cx, cy - bh * 0.58F, cx, cy + bh * 0.65F, 1.3F, color);
        Draw.stroke(c, cx - r * 0.48F, cy - bh * 0.92F, r * 0.48F, r * 0.38F, r * 0.2F, 1.1F, color);
        Draw.stroke(c, cx, cy - bh * 0.92F, r * 0.48F, r * 0.38F, r * 0.2F, 1.1F, color);
    }

    private static void drawVectorCrestEmblem(Canvas c, float cx, float cy, float r, int color, boolean winged) {
        if (winged) {
            for (int dir : new int[]{-1, 1}) {
                Draw.line(c, cx + dir * r * 0.65F, cy - r * 0.15F, cx + dir * r * 1.32F, cy - r * 0.48F, 1.4F, color);
                Draw.line(c, cx + dir * r * 0.70F, cy + r * 0.08F, cx + dir * r * 1.22F, cy - r * 0.12F, 1.2F, color);
                Draw.line(c, cx + dir * r * 0.60F, cy + r * 0.28F, cx + dir * r * 1.05F, cy + r * 0.22F, 1.0F, color);
            }
        }
        Draw.fill(c, cx - r, cy - r, r * 2.0F, r * 2.0F, r, Draw.alpha(color, 0.12F));
        Draw.stroke(c, cx - r, cy - r, r * 2.0F, r * 2.0F, r, 1.5F, color);
        float ir = r * 0.74F;
        Draw.stroke(c, cx - ir, cy - ir, ir * 2.0F, ir * 2.0F, ir, 0.8F, Draw.alpha(color, 0.55F));
        drawMiniStarIcon(c, cx, cy, r * 0.50F, color);
    }

    private static void drawMiniStarIcon(Canvas c, float cx, float cy, float r, int color) {
        Draw.line(c, cx - r, cy, cx + r, cy, 1.3F, color);
        Draw.line(c, cx, cy - r, cx, cy + r, 1.3F, color);
        float d = r * 0.58F;
        Draw.line(c, cx - d, cy - d, cx + d, cy + d, 0.9F, color);
        Draw.line(c, cx - d, cy + d, cx + d, cy - d, 0.9F, color);
    }

    private static void drawDiamond(Canvas c, float cx, float cy, float r, int color) {
        Draw.line(c, cx, cy - r, cx + r, cy, 1.2F, color);
        Draw.line(c, cx + r, cy, cx, cy + r, 1.2F, color);
        Draw.line(c, cx, cy + r, cx - r, cy, 1.2F, color);
        Draw.line(c, cx - r, cy, cx, cy - r, 1.2F, color);
        Draw.fill(c, cx - r * 0.45F, cy - r * 0.45F, r * 0.9F, r * 0.9F, r * 0.2F, color);
    }

    private static void drawCheckmark(Canvas c, float cx, float cy, float r, int color) {
        Draw.line(c, cx - r * 0.85F, cy + r * 0.05F, cx - r * 0.18F, cy + r * 0.72F, 1.3F, color);
        Draw.line(c, cx - r * 0.18F, cy + r * 0.72F, cx + r * 0.92F, cy - r * 0.65F, 1.3F, color);
    }

    private static void drawMiniLock(Canvas c, float cx, float cy, int color) {
        Draw.stroke(c, cx - 2.0F, cy - 3.6F, 4.0F, 3.6F, 2.0F, 0.9F, color);
        Draw.fill(c, cx - 2.8F, cy - 0.8F, 5.6F, 4.2F, 1.0F, color);
    }

    // ================================================================ 8. 底栏与鼠标/滚轮/拖拽交互路由

    private void renderFootBar(Canvas c, AddonFrame f, float x, float footTop, float w) {
        String leftInfo = tr("gui.module_shop_quest.foot_summary",
                V2.money(f.host().balance()),
                f.host().currencyName(),
                V2.money(QuestClientState.seasonPoints()),
                QuestClientState.currentLevel(),
                QuestClientState.maxLevel());

        String banner = QuestClientState.activeBanner();
        if (!banner.isBlank()) {
            V2.foot(c, x, footTop, w, leftInfo, "");
            int color = QuestClientState.bannerOk() ? ShopTheme.green() : ShopTheme.red();
            V2.bold(c, banner, V2.FS_XS, color, x + w * 0.42F, footTop + V2.FOOT / 2.0F,
                    w * 0.58F - 8.0F, Text.Align.RIGHT);
            return;
        }

        String rightHint = subView == SUBVIEW_ADMIN
                ? "配置文件目录：config/module_shop_quest/（点击右上角保存即刻全服生效）"
                : "";
        V2.foot(c, x, footTop, w, leftInfo, rightHint);
    }

    @Override
    public boolean mouseClicked(AddonFrame f, double mx, double my, int button) {
        QuestClient.pingUiActivityThrottled();
        if (button == 1 && subView != SUBVIEW_MAIN) {
            adminView.closeInlineEditor();
            subView = SUBVIEW_MAIN;
            QuestClient.playClickSound(0.96F);
            return true;
        }

        if (subView == SUBVIEW_ADMIN && my > f.y() + V2.BAR) {
            if (adminView.mouseClicked(mx, my, button)) {
                return true;
            }
        }

        if (button != 0) {
            return false;
        }

        if (subView == SUBVIEW_MAIN && segRects != null && my >= segY && my < segY + V2.CTRL) {
            for (int i = 0; i < segRects.length; i++) {
                if (mx >= segRects[i][0] && mx < segRects[i][0] + segRects[i][1]) {
                    if (activeTab != i) {
                        activeTab = i;
                        QuestClient.playClickSound(1.02F);
                    }
                    return true;
                }
            }
        }

        if (subView == SUBVIEW_MAIN && activeTab == TAB_PASS
                && inRect((float) mx, (float) my, passViewportX, passViewportY, passViewportW, passViewportH)) {
            draggingPassCanvas = true;
            dragLastX = mx;
            dragAccumDist = 0.0D;
            return true;
        }

        for (int i = hitZones.size() - 1; i >= 0; i--) {
            HitZone hz = hitZones.get(i);
            if (hz.contains((float) mx, (float) my)) {
                hz.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mx, double my, int button, double dx, double dy) {
        if (subView == SUBVIEW_ADMIN && adminView.mouseDragged(mx, my, button, dx, dy)) {
            return true;
        }
        if (draggingPassCanvas && subView == SUBVIEW_MAIN && activeTab == TAB_PASS) {
            double delta = mx - dragLastX;
            dragLastX = mx;
            dragAccumDist += Math.abs(delta);
            PASS_H_SCROLL.scrollTo(clamp(PASS_H_SCROLL.offset() - (float) delta, 0.0F, PASS_H_SCROLL.maxScroll()), false);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mx, double my, int button) {
        if (subView == SUBVIEW_ADMIN && adminView.mouseReleased(mx, my, button)) {
            return true;
        }
        if (draggingPassCanvas) {
            boolean wasClick = dragAccumDist <= 4.0D;
            draggingPassCanvas = false;
            dragAccumDist = 0.0D;
            if (wasClick) {
                for (int i = hitZones.size() - 1; i >= 0; i--) {
                    HitZone hz = hitZones.get(i);
                    if (hz.contains((float) mx, (float) my)) {
                        hz.action().run();
                        return true;
                    }
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mx, double my, double scrollY) {
        QuestClient.pingUiActivityThrottled();
        if (subView == SUBVIEW_ADMIN) {
            return adminView.mouseScrolled(mx, my, scrollY);
        }
        if (subView != SUBVIEW_MAIN) {
            return false;
        }
        if (activeTab == TAB_PASS
                && inRect((float) mx, (float) my, passViewportX - 80.0F, passViewportY, passViewportW + 150.0F, passViewportH + 20.0F)) {
            PASS_H_SCROLL.wheel(scrollY * 1.6D);
            return true;
        }
        if (activeTab == TAB_DAILY
                && inRect((float) mx, (float) my, questViewportX, questViewportY, questViewportW, questViewportH)) {
            DAILY_V_SCROLL.wheel(scrollY);
            return true;
        }
        if (activeTab == TAB_WEEKLY
                && inRect((float) mx, (float) my, questViewportX, questViewportY, questViewportW, questViewportH)) {
            WEEKLY_V_SCROLL.wheel(scrollY);
            return true;
        }
        return false;
    }

    @Override
    public void cancelInput() {
        draggingPassCanvas = false;
        dragAccumDist = 0.0D;
        adminView.closeInlineEditor();
    }

    // ================================================================ 通用辅助

    private void registerHit(float x, float y, float w, float h, Runnable action) {
        if (w > 0.0F && h > 0.0F && action != null) {
            hitZones.add(new HitZone(x, y, w, h, action));
        }
    }

    private static ItemStack coinStack() {
        if (coinIconCache == null || coinIconCache.isEmpty()) {
            coinIconCache = new ItemStack(Items.SUNFLOWER);
        }
        return coinIconCache;
    }

    private static ItemStack ptStack() {
        if (ptIconCache == null || ptIconCache.isEmpty()) {
            ptIconCache = new ItemStack(Items.NETHER_STAR);
        }
        return ptIconCache;
    }

    private static String stripFormatting(String s) {
        return s == null ? "" : s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    private static int sourceColor(String requiredMod, String period) {
        if ("prev_daily".equals(period)) return ShopTheme.gold();
        if ("module_shop_stock".equals(requiredMod)) return ShopTheme.gold();
        if ("module_shop_blind".equals(requiredMod)) return 0xFF9B6BFF;
        if ("lottery".equals(requiredMod)) return 0xFFE86A92;
        if ("minecraft".equals(requiredMod)) return ShopTheme.green();
        return ShopTheme.accent();
    }

    private static String sourceLabel(String requiredMod, String period) {
        if ("prev_daily".equals(period)) return tr("gui.module_shop_quest.src_grace");
        if ("module_shop_stock".equals(requiredMod)) return tr("gui.module_shop_quest.src_stock");
        if ("module_shop_blind".equals(requiredMod)) return tr("gui.module_shop_quest.src_blind");
        if ("lottery".equals(requiredMod)) return tr("gui.module_shop_quest.src_lottery");
        if ("minecraft".equals(requiredMod)) return tr("gui.module_shop_quest.src_vanilla");
        return tr("gui.module_shop_quest.src_shop");
    }

    private static String withCount(String base, int count) {
        return count > 0 ? (base + " (" + count + ")") : base;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean inRect(float mx, float my, float rx, float ry, float rw, float rh) {
        return mx >= rx && mx < rx + rw && my >= ry && my < ry + rh;
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }
}
