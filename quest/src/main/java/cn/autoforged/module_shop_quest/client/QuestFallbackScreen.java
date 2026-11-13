package cn.autoforged.module_shop_quest.client;

import cn.autoforged.module_shop.api.client.ShopModules;
import cn.autoforged.module_shop_quest.client.QuestClientState.OnlineRow;
import cn.autoforged.module_shop_quest.client.QuestClientState.PassRow;
import cn.autoforged.module_shop_quest.client.QuestClientState.QuestRow;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 纯原版回退界面（无任何 AtomLib / Skija 类引用）：
 * 当运行于 Linux / macOS 或 AtomLib 降级时由 {@code AddonPageAdapter} 自动唤起，
 * 完整支持切换四大板块、使用星币解锁付费积分线、逐条领奖、一键全领及返回商店主界面。
 */
public final class QuestFallbackScreen extends Screen {
    private static final int PAGE_SIZE = 6;
    private static int tab = 0;
    private int page = 0;

    public QuestFallbackScreen() {
        super(Component.translatable("gui.module_shop_quest.page_title"));
    }

    @Override
    protected void init() {
        super.init();
        QuestClient.requestSync();
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        int left = (this.width - 420) / 2;
        int top = 26;

        String[] tabKeys = {
                "gui.module_shop_quest.tab_pass",
                "gui.module_shop_quest.tab_daily",
                "gui.module_shop_quest.tab_weekly",
                "gui.module_shop_quest.tab_online"
        };
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            addRenderableWidget(Button.builder(Component.translatable(tabKeys[i]), b -> {
                tab = idx;
                page = 0;
                rebuildButtons();
            }).bounds(left + i * 74, top, 70, 18).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("gui.module_shop_quest.btn_claim_all"),
                b -> QuestClient.sendClaimAll())
                .bounds(left + 302, top, 60, 18).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.module_shop_quest.btn_back"),
                b -> ShopModules.back())
                .bounds(left + 366, top, 54, 18).build());

        if (tab == 0 && QuestClientState.premiumEnabled() && !QuestClientState.premiumUnlocked()) {
            Component unlockTxt = Component.translatable("gui.module_shop_quest.btn_unlock_premium",
                    String.valueOf(QuestClientState.premiumUnlockCost()), "");
            addRenderableWidget(Button.builder(unlockTxt, b -> QuestClient.sendUnlockPremium())
                    .bounds(left + 260, top + 22, 160, 18).build());
        }

        int totalRows = currentRowCount();
        int maxPage = Math.max(0, (totalRows - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, maxPage));

        int listTop = top + 48;
        int start = page * PAGE_SIZE;
        int end = Math.min(totalRows, start + PAGE_SIZE);

        for (int idx = start; idx < end; idx++) {
            int rowY = listTop + (idx - start) * 24;
            final int rowIdx = idx;
            if (tab == 0) {
                PassRow r = QuestClientState.passRows().get(rowIdx);
                boolean reached = QuestClientState.seasonPoints() >= r.reqPoints();
                boolean canFree = reached && !r.freeClaimed();
                boolean canPrem = reached && QuestClientState.premiumUnlocked() && !r.premClaimed();
                if (canFree) {
                    addRenderableWidget(Button.builder(Component.translatable("gui.module_shop_quest.btn_claim_free"),
                            b -> QuestClient.sendClaimPass(r.level(), false, false))
                            .bounds(left + 310, rowY, 52, 18).build());
                }
                if (canPrem) {
                    addRenderableWidget(Button.builder(Component.translatable("gui.module_shop_quest.btn_claim_prem"),
                            b -> QuestClient.sendClaimPass(r.level(), true, false))
                            .bounds(left + 366, rowY, 52, 18).build());
                }
            } else if (tab == 1 || tab == 2) {
                List<QuestRow> rows = tab == 1 ? QuestClientState.dailyRows() : QuestClientState.weeklyRows();
                QuestRow r = rows.get(rowIdx);
                if (r.claimable()) {
                    addRenderableWidget(Button.builder(Component.translatable("gui.module_shop_quest.btn_claim"),
                            b -> QuestClient.sendClaimQuest(r.period(), r.id()))
                            .bounds(left + 360, rowY, 58, 18).build());
                }
            } else {
                OnlineRow r = QuestClientState.onlineRows().get(rowIdx);
                if (r.claimable()) {
                    addRenderableWidget(Button.builder(Component.translatable("gui.module_shop_quest.btn_claim"),
                            b -> QuestClient.sendClaimOnline(r.period(), r.id()))
                            .bounds(left + 360, rowY, 58, 18).build());
                }
            }
        }

        int pagerY = listTop + PAGE_SIZE * 24 + 6;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            if (page > 0) {
                page--;
                rebuildButtons();
            }
        }).bounds(left, pagerY, 28, 18).build());

        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            if (page < maxPage) {
                page++;
                rebuildButtons();
            }
        }).bounds(left + 84, pagerY, 28, 18).build());
    }

    private int currentRowCount() {
        return switch (tab) {
            case 0 -> QuestClientState.passRows().size();
            case 1 -> QuestClientState.dailyRows().size();
            case 2 -> QuestClientState.weeklyRows().size();
            default -> QuestClientState.onlineRows().size();
        };
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        int left = (this.width - 420) / 2;
        int top = 26;

        g.fill(left - 8, top - 18, left + 428, top + 226, 0xCC161A22);
        g.drawString(this.font, this.title, left, top - 12, 0xFFF5F7FA);

        String stat = String.format("Lv.%d/%d (%d PT) | 今日活跃 %dm | 本周活跃 %dm",
                QuestClientState.currentLevel(),
                QuestClientState.maxLevel(),
                QuestClientState.seasonPoints(),
                QuestClientState.dailyMinutes(),
                QuestClientState.weeklyMinutes());
        g.drawString(this.font, stat, left, top + 27, 0xFFAAB4C4);

        int totalRows = currentRowCount();
        int listTop = top + 48;
        int start = page * PAGE_SIZE;
        int end = Math.min(totalRows, start + PAGE_SIZE);

        for (int idx = start; idx < end; idx++) {
            int rowY = listTop + (idx - start) * 24;
            g.fill(left, rowY - 1, left + 420, rowY + 19, (idx % 2 == 0) ? 0x55242B38 : 0x551C222D);
            if (tab == 0) {
                PassRow r = QuestClientState.passRows().get(idx);
                String line = String.format("Lv.%d (%d PT) | 免费:+%d | 进阶:+%d",
                        r.level(), r.reqPoints(), r.freeReward().money(), r.premReward().money());
                g.drawString(this.font, line, left + 6, rowY + 5, 0xFFE6ECF5);
            } else if (tab == 1 || tab == 2) {
                List<QuestRow> rows = tab == 1 ? QuestClientState.dailyRows() : QuestClientState.weeklyRows();
                QuestRow r = rows.get(idx);
                String line = String.format("%s [%d/%d] (+%d ★ / +%d PT)",
                        r.title(), r.progress(), r.target(), r.reward().money(), r.reward().points());
                g.drawString(this.font, line, left + 6, rowY + 5, r.claimed() ? 0xFF7B8494 : 0xFFE6ECF5);
            } else {
                OnlineRow r = QuestClientState.onlineRows().get(idx);
                String line = String.format("%s [%d/%dm] (+%d ★ / +%d PT)",
                        r.title(), r.currentMin(), r.reqMin(), r.reward().money(), r.reward().points());
                g.drawString(this.font, line, left + 6, rowY + 5, r.claimed() ? 0xFF7B8494 : 0xFFE6ECF5);
            }
        }

        int maxPage = Math.max(1, (totalRows + PAGE_SIZE - 1) / PAGE_SIZE);
        g.drawCenteredString(this.font, (page + 1) + " / " + maxPage, left + 56, listTop + PAGE_SIZE * 24 + 11, 0xFFD0D7E2);

        String banner = QuestClientState.activeBanner();
        if (!banner.isBlank()) {
            g.drawString(this.font, banner, left + 130, listTop + PAGE_SIZE * 24 + 11,
                    QuestClientState.bannerOk() ? 0xFF55E08A : 0xFFFF6B6B);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        ShopModules.back();
    }
}
