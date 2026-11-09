package cn.autoforged.module_shop_stock.client;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPage;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop_stock.client.view.AdminView;
import cn.autoforged.module_shop_stock.client.view.BoardView;
import cn.autoforged.module_shop_stock.client.view.OrderView;
import cn.autoforged.module_shop_stock.client.view.PositionView;
import cn.autoforged.module_shop_stock.client.view.QuoteView;
import cn.autoforged.module_shop_stock.client.view.StockView;
import io.github.humbleui.skija.Canvas;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * 商店内容区的股市页：底部切页 + 实时市场新闻播报栏，页内视图各自绘制。
 */
public final class StockMarketPage implements AddonPage {
    private enum Tab {
        QUOTE("gui.stock.tab.quote"),
        POSITION("gui.stock.tab.position"),
        ORDER("gui.stock.tab.order"),
        BOARD("gui.stock.tab.board"),
        ADMIN("gui.stock.tab.admin");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private static final float PAD = 4.0F;
    private static final float TAB_W = 30.0F;
    private static final float TAB_GAP = 2.0F;
    private static final float TAB_H = 14.0F;

    private final StockView[] views = new StockView[Tab.values().length];
    private Tab tab = Tab.QUOTE;
    private long nextHeartbeat;

    public StockMarketPage() {
        QuoteView quote = new QuoteView();
        this.views[Tab.QUOTE.ordinal()] = quote;
        this.views[Tab.POSITION.ordinal()] = new PositionView(() -> this.tab = Tab.QUOTE);
        this.views[Tab.ORDER.ordinal()] = new OrderView();
        this.views[Tab.BOARD.ordinal()] = new BoardView();
        this.views[Tab.ADMIN.ordinal()] = new AdminView();
    }

    @Override
    public void init(AddonPageHost host) {
        for (StockView view : this.views) {
            view.init(host);
        }
        StockMarketState.requestSnapshot();
        this.nextHeartbeat = System.currentTimeMillis() + 40_000L;
        if (this.tab == Tab.ADMIN && !StockMarketState.admin) {
            this.tab = Tab.QUOTE;
        }
    }

    @Override
    public void render(Canvas canvas, AddonFrame f) {
        long now = System.currentTimeMillis();
        if (now >= this.nextHeartbeat) {
            this.nextHeartbeat = now + 40_000L;
            StockMarketState.heartbeat();
        }
        if (this.tab == Tab.ADMIN && !StockMarketState.admin) {
            this.tab = Tab.QUOTE;
        }
        this.views[this.tab.ordinal()].render(canvas, f);
        renderFooter(canvas, f);
    }

    private int tabCount() {
        return StockMarketState.admin ? 5 : 4;
    }

    private float tabX(int index, AddonFrame f) {
        return f.x() + PAD + index * (TAB_W + TAB_GAP);
    }

    private void renderFooter(Canvas canvas, AddonFrame f) {
        float x = f.x() + PAD;
        float width = f.w() - PAD * 2.0F;
        float top = f.barTop();
        Draw.hairline(canvas, x, top - 1.0F, width, ShopTheme.borderDim());

        int count = tabCount();
        float tabY = top + 2.0F;
        for (int i = 0; i < count; i++) {
            Tab value = Tab.values()[i];
            boolean selected = this.tab == value;
            float bx = tabX(i, f);
            boolean hover = f.mouseX() >= bx && f.mouseX() < bx + TAB_W
                    && f.mouseY() >= tabY && f.mouseY() < tabY + TAB_H;
            if (selected) {
                Draw.fill(canvas, bx, tabY, TAB_W, TAB_H, 2.0F, ShopTheme.accentTint());
            } else if (hover) {
                Draw.fill(canvas, bx, tabY, TAB_W, TAB_H, 2.0F, ShopTheme.rowHover());
            }
            Text.drawLine(canvas, Component.translatable(value.key).getString(),
                    selected ? ShopTheme.bold() : ShopTheme.regular(), Text.SMALL,
                    selected ? ShopTheme.accent() : ShopTheme.text2(),
                    bx, tabY + TAB_H / 2.0F, TAB_W, Text.Align.CENTER);
        }

        float messageX = tabX(count, f) + 2.0F;
        float messageW = Math.max(0.0F, x + width - messageX);
        String message = StockMarketState.message().getString();
        boolean stale = System.currentTimeMillis() - StockMarketState.messageAt() > 12_000L;
        if (!message.isEmpty() && !stale) {
            int color = StockMarketState.messageError() ? ShopTheme.red() : ShopTheme.green();
            Text.drawLine(canvas, message, ShopTheme.regular(), Text.SMALL, color, messageX,
                    tabY + TAB_H / 2.0F, messageW, Text.Align.LEFT);
        } else if (!StockMarketState.tickerNews().isEmpty()) {
            drawTicker(canvas, StockMarketState.tickerNews(), messageX, tabY, messageW);
        } else {
            Text.drawLine(canvas, hint(), ShopTheme.regular(), Text.SMALL, ShopTheme.text3(), messageX,
                    tabY + TAB_H / 2.0F, messageW, Text.Align.LEFT);
        }
    }

    /** 底栏新闻滚动播报：所有近期快讯首尾相接从右往左循环滚动，利好红、利空绿。 */
    private static void drawTicker(Canvas canvas, java.util.List<StockMarketState.News> news,
            float x, float tabY, float w) {
        if (w <= 8.0F) {
            return;
        }
        final float gap = 18.0F;
        float total = 0.0F;
        float[] widths = new float[news.size()];
        for (int i = 0; i < news.size(); i++) {
            widths[i] = Text.width(news.get(i).banner(), ShopTheme.regular(), Text.SMALL);
            total += widths[i] + gap;
        }
        Draw.fill(canvas, x, tabY + 1.0F, w, TAB_H - 2.0F, 2.0F, Draw.alpha(ShopTheme.accentTint(), 0.55F));
        int save = canvas.save();
        Draw.clip(canvas, x + 2.0F, tabY, w - 4.0F, TAB_H);
        float centerY = tabY + TAB_H / 2.0F;
        float offset = total <= w - 6.0F ? 0.0F : (System.currentTimeMillis() % 1_000_000L) / 1000.0F * 22.0F % total;
        float cursor = x + 3.0F - offset;
        for (int pass = 0; pass < 2 && cursor < x + w; pass++) {
            for (int i = 0; i < news.size() && cursor < x + w; i++) {
                StockMarketState.News n = news.get(i);
                if (cursor + widths[i] > x) {
                    int color = n.impact() >= 0 ? ShopTheme.red() : ShopTheme.green();
                    Text.drawLine(canvas, n.banner(), ShopTheme.regular(), Text.SMALL, color,
                            cursor, centerY, widths[i] + 1.0F, Text.Align.LEFT);
                }
                cursor += widths[i] + gap;
            }
            if (offset == 0.0F) {
                break;
            }
        }
        canvas.restoreToCount(save);
    }

    private String hint() {
        if (!StockMarketState.enabled) {
            return Component.translatable("gui.stock.hint_closed").getString();
        }
        if (!StockMarketState.session) {
            return Component.translatable("gui.stock.hint_session").getString();
        }
        return Component.translatable("gui.stock.hint",
                String.format(Locale.ROOT, "%.2f", StockMarketState.feeBps / 100.0D)).getString();
    }

    @Override
    public boolean mouseClicked(AddonFrame f, double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        int count = tabCount();
        float tabY = f.barTop() + 2.0F;
        for (int i = 0; i < count; i++) {
            float bx = tabX(i, f);
            if (mouseX >= bx && mouseX < bx + TAB_W && mouseY >= tabY && mouseY < tabY + TAB_H) {
                this.tab = Tab.values()[i];
                return true;
            }
        }
        // 点击底部新闻播报条可直接跳转到播报对应的股票行情
        float messageX = tabX(count, f) + 2.0F;
        if (!StockMarketState.tickerNews().isEmpty() && mouseX >= messageX && mouseY >= tabY && mouseY < tabY + TAB_H) {
            StockMarketState.select(StockMarketState.newsSymbol);
            this.tab = Tab.QUOTE;
            return true;
        }
        return this.views[this.tab.ordinal()].mouseClicked(f, mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mouseX, double mouseY, int button) {
        return this.views[this.tab.ordinal()].mouseReleased(f, mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mouseX, double mouseY, int button, double dragX, double dragY) {
        return this.views[this.tab.ordinal()].mouseDragged(f, mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mouseX, double mouseY, double amount) {
        return this.views[this.tab.ordinal()].mouseScrolled(f, mouseX, mouseY, amount);
    }

    @Override
    public void clickedOutside() {
        this.views[this.tab.ordinal()].clickedOutside();
    }

    @Override
    public void cancelInput() {
        for (StockView view : this.views) {
            view.cancelInput();
        }
    }
}
