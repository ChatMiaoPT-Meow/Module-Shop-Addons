package cn.autoforged.module_shop_stock.client;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPage;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop_stock.client.view.AdminView;
import cn.autoforged.module_shop_stock.client.view.NewsBoardView;
import cn.autoforged.module_shop_stock.client.view.PortfolioView;
import cn.autoforged.module_shop_stock.client.view.QuoteView;
import cn.autoforged.module_shop_stock.client.view.StockView;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/**
 * 商店内容区的股市页（UI v2）：顶部工具条（标题 + 分段页签 + 当前页的搜索 / 筛选）、中间主体、
 * 底栏（左：计数说明；右：交易结果 / 市场新闻滚动播报 / 操作提示）。
 */
public final class StockMarketPage implements AddonPage {
    private enum Tab {
        QUOTE("gui.stock.v2.tab.quote"),
        PORTFOLIO("gui.stock.v2.tab.portfolio"),
        NEWS("gui.stock.v2.tab.news"),
        ADMIN("gui.stock.tab.admin");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    private final StockView[] views = new StockView[Tab.values().length];
    private Tab tab = Tab.QUOTE;
    private long nextHeartbeat;
    private float[][] segRects = new float[0][2];
    private float segY;
    private final float[] tickerRect = new float[4];
    private boolean tickerShown;

    public StockMarketPage() {
        this.views[Tab.QUOTE.ordinal()] = new QuoteView();
        this.views[Tab.PORTFOLIO.ordinal()] = new PortfolioView(this::openQuote);
        this.views[Tab.NEWS.ordinal()] = new NewsBoardView(this::openQuote);
        this.views[Tab.ADMIN.ordinal()] = new AdminView();
    }

    private void openQuote() {
        this.tab = Tab.QUOTE;
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

    private int tabCount() {
        return StockMarketState.admin ? 4 : 3;
    }

    private void switchTo(Tab next) {
        if (next == this.tab) {
            return;
        }
        this.views[this.tab.ordinal()].cancelInput();
        this.tab = next;
    }

    private static AddonFrame body(AddonFrame f) {
        return new AddonFrame(f.host(), f.x(), f.y() + V2.BAR, f.w(), f.h() - V2.BAR - V2.FOOT,
                f.mouseX(), f.mouseY(), f.dtMs());
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
        StockView view = this.views[this.tab.ordinal()];

        // 工具条：标题 + 页签 + 视图自己的那一段
        float cy = f.y() + V2.BAR / 2.0F;
        Draw.hairline(canvas, f.x(), f.y() + V2.BAR - 0.5F, f.w(), ShopTheme.borderDim());
        float titleW = V2.bold(canvas, Component.translatable("gui.stock.v2.title").getString(), V2.FS_T,
                ShopTheme.text(), f.x() + 8.0F, cy, 80.0F, Text.Align.LEFT);
        String[] labels = new String[tabCount()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = Component.translatable(Tab.values()[i].key).getString();
        }
        float sx = f.x() + 8.0F + titleW + 10.0F;
        this.segY = cy - V2.CTRL / 2.0F;
        this.segRects = V2.segmented(canvas, labels, this.tab.ordinal(), sx, this.segY, null);
        float[] last = this.segRects[this.segRects.length - 1];
        float tx = last[0] + last[1] + 10.0F;
        view.toolbar(canvas, f, tx, f.y(), Math.max(0.0F, f.x() + f.w() - 8.0F - tx));

        view.render(canvas, body(f));
        renderFoot(canvas, f, view);
    }

    // ------------------------------------------------------------------ 底栏

    private void renderFoot(Canvas canvas, AddonFrame f, StockView view) {
        float y = f.y() + f.h() - V2.FOOT;
        String left = view.footLeft();
        V2.foot(canvas, f.x(), y, f.w(), left, null);
        float lw = left == null ? 0.0F : Math.min(f.w() * 0.5F, V2.width(left, V2.FS_S, false)) + 16.0F;
        float rx = f.x() + lw;
        float rw = f.w() - lw - 8.0F;
        float cy = y + V2.FOOT / 2.0F;
        this.tickerShown = false;
        if (rw <= 8.0F) {
            return;
        }
        String message = StockMarketState.message().getString();
        boolean fresh = System.currentTimeMillis() - StockMarketState.messageAt() <= 12_000L;
        if (!message.isEmpty() && fresh) {
            int color = StockMarketState.messageError() ? ShopTheme.red() : ShopTheme.green();
            V2.text(canvas, message, V2.FS_S, color, rx, cy, rw, Text.Align.RIGHT);
        } else if (!StockMarketState.enabled || !StockMarketState.session) {
            V2.text(canvas, Component.translatable(StockMarketState.enabled ? "gui.stock.hint_session"
                    : "gui.stock.hint_closed").getString(), V2.FS_S, ShopTheme.gold(), rx, cy, rw, Text.Align.RIGHT);
        } else if (!StockMarketState.tickerNews().isEmpty()) {
            this.tickerShown = true;
            this.tickerRect[0] = rx;
            this.tickerRect[1] = y;
            this.tickerRect[2] = rw;
            this.tickerRect[3] = V2.FOOT;
            drawTicker(canvas, StockMarketState.tickerNews(), rx, y, rw);
        } else {
            String hint = view.footHint();
            V2.text(canvas, hint != null ? hint : defaultHint(), V2.FS_XS, ShopTheme.text3(), rx, cy, rw,
                    Text.Align.RIGHT);
        }
    }

    /** 底栏新闻滚动播报：近期快讯首尾相接从右往左循环，利好红、利空绿。点击跳到该股行情。 */
    private static void drawTicker(Canvas canvas, List<StockMarketState.News> news, float x, float y, float w) {
        final float gap = 18.0F;
        float total = 0.0F;
        float[] widths = new float[news.size()];
        for (int i = 0; i < news.size(); i++) {
            widths[i] = V2.width(news.get(i).banner(), V2.FS_S, false);
            total += widths[i] + gap;
        }
        int save = canvas.save();
        Draw.clip(canvas, x, y, w, V2.FOOT);
        float centerY = y + V2.FOOT / 2.0F;
        float offset = total <= w - 6.0F ? 0.0F : (System.currentTimeMillis() % 1_000_000L) / 1000.0F * 22.0F % total;
        float cursor = x + 3.0F - offset;
        for (int pass = 0; pass < 2 && cursor < x + w; pass++) {
            for (int i = 0; i < news.size() && cursor < x + w; i++) {
                StockMarketState.News n = news.get(i);
                if (cursor + widths[i] > x) {
                    int color = n.impact() >= 0 ? ShopTheme.red() : ShopTheme.green();
                    V2.text(canvas, n.banner(), V2.FS_S, color, cursor, centerY, widths[i] + 1.0F, Text.Align.LEFT);
                }
                cursor += widths[i] + gap;
            }
            if (offset == 0.0F) {
                break;
            }
        }
        canvas.restoreToCount(save);
    }

    private static String defaultHint() {
        return Component.translatable("gui.stock.hint",
                String.format(Locale.ROOT, "%.2f", StockMarketState.feeBps / 100.0D)).getString();
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(AddonFrame f, double mouseX, double mouseY, int button) {
        if (button == 0) {
            int hit = segHit(mouseX, mouseY);
            if (hit >= 0) {
                switchTo(Tab.values()[hit]);
                return true;
            }
            if (this.tickerShown && mouseX >= this.tickerRect[0] && mouseX < this.tickerRect[0] + this.tickerRect[2]
                    && mouseY >= this.tickerRect[1] && mouseY < this.tickerRect[1] + this.tickerRect[3]) {
                StockMarketState.select(StockMarketState.newsSymbol);
                switchTo(Tab.QUOTE);
                return true;
            }
        }
        return this.views[this.tab.ordinal()].mouseClicked(body(f), mouseX, mouseY, button);
    }

    private int segHit(double mx, double my) {
        if (my < this.segY || my >= this.segY + V2.CTRL) {
            return -1;
        }
        for (int i = 0; i < this.segRects.length && i < tabCount(); i++) {
            if (mx >= this.segRects[i][0] && mx < this.segRects[i][0] + this.segRects[i][1]) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mouseX, double mouseY, int button) {
        return this.views[this.tab.ordinal()].mouseReleased(body(f), mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mouseX, double mouseY, int button, double dragX, double dragY) {
        return this.views[this.tab.ordinal()].mouseDragged(body(f), mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mouseX, double mouseY, double amount) {
        return this.views[this.tab.ordinal()].mouseScrolled(body(f), mouseX, mouseY, amount);
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
