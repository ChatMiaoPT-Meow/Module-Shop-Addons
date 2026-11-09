package cn.autoforged.module_shop_stock.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop_stock.client.StockMarketState;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import net.minecraft.network.chat.Component;

/** 委托页：展示买多/平多/卖空/平空四向限价单与市价单记录，支持挂单撤单。 */
public final class OrderView implements StockView {
    private static final float PAD = 4.0F;
    private static final float ROW_H = 20.0F;
    private static final float CANCEL_W = 26.0F;

    private final ScrollController scroll = new ScrollController();
    private long confirmId;
    private long confirmAt;

    @Override
    public void render(Canvas canvas, AddonFrame f) {
        float x = f.x() + PAD;
        float y = f.y() + 2.0F;
        float w = f.w() - PAD * 2.0F;
        float bottom = f.listBottom() - 2.0F;
        List<StockMarketState.OrderView> orders = StockMarketState.orders();

        int open = StockMarketState.openOrders().size();
        Draw.fill(canvas, x, y, w, 15.0F, 3.0F, Draw.alpha(ShopTheme.card(), 0.7F));
        Text.drawLine(canvas, Component.translatable("gui.stock.order_head", open,
                StockMarketState.maxOpenOrders).getString(),
                ShopTheme.regular(), Text.SMALL, ShopTheme.text2(), x + 4.0F, y + 8.0F, w - 8.0F, Text.Align.LEFT);

        float top = y + 18.0F;
        float viewH = Math.max(12.0F, bottom - top - 2.0F);
        this.scroll.setContent(orders.size() * ROW_H, viewH);
        this.scroll.update();
        if (orders.isEmpty()) {
            Text.drawLine(canvas, Component.translatable("gui.stock.no_order").getString(), ShopTheme.regular(),
                    Text.SMALL, ShopTheme.text3(), x, top + viewH / 2.0F, w, Text.Align.CENTER);
            return;
        }

        int saved = canvas.save();
        try {
            Draw.clip(canvas, x, top, w, viewH);
            for (int i = 0; i < orders.size(); i++) {
                float rowY = top + i * ROW_H - this.scroll.offset();
                if (rowY + ROW_H < top || rowY > top + viewH) {
                    continue;
                }
                renderRow(canvas, f, orders.get(i), x, rowY, w);
            }
        } finally {
            canvas.restoreToCount(saved);
        }
        this.scroll.drawBar(canvas, x, top, w, viewH, f.mouseX(), f.mouseY(), f.dtMs());
    }

    private void renderRow(Canvas canvas, AddonFrame f, StockMarketState.OrderView order, float x, float y, float w) {
        boolean isOpen = "OPEN".equals(order.state);
        boolean hover = f.mouseX() >= x && f.mouseX() < x + w && f.mouseY() >= y && f.mouseY() < y + ROW_H - 1.0F;
        if (hover) {
            Draw.fill(canvas, x, y, w, ROW_H - 1.0F, 2.0F, ShopTheme.rowHover());
        }
        String sideKey = switch (order.action) {
            case "SELL" -> "gui.stock.sell_long";
            case "SHORT" -> "gui.stock.open_short";
            case "COVER" -> "gui.stock.cover_short";
            default -> "gui.stock.buy_long";
        };
        String side = Component.translatable(sideKey).getString();
        String kind = Component.translatable(order.market ? "gui.stock.market" : "gui.stock.limit").getString();
        boolean bidColor = "BUY".equals(order.action) || "COVER".equals(order.action);
        Text.drawLine(canvas, order.symbol + " " + side + " · " + kind, ShopTheme.regular(), Text.SMALL,
                bidColor ? ShopTheme.red() : ShopTheme.green(), x + 3.0F, y + 7.0F, w * 0.66F, Text.Align.LEFT);
        Text.drawLine(canvas, StockMarketState.number(order.price), ShopTheme.regular(), Text.SMALL, ShopTheme.text(),
                x + w * 0.62F, y + 7.0F, w * 0.34F, Text.Align.RIGHT);

        String detail = isOpen
                ? Component.translatable("gui.stock.order_open_line",
                        StockMarketState.number(order.filled), StockMarketState.number(order.quantity)).getString()
                : Component.translatable("gui.stock.order_done_line",
                        StockMarketState.number(order.filled), StockMarketState.number(order.quantity),
                        Component.translatable(stateKey(order.state)).getString()).getString();
        Text.drawLine(canvas, detail, ShopTheme.regular(), Text.SMALL, ShopTheme.text3(), x + 3.0F, y + 16.0F,
                w - CANCEL_W - 8.0F, Text.Align.LEFT);

        if (isOpen) {
            boolean confirm = this.confirmId == order.id && System.currentTimeMillis() - this.confirmAt < 3000L;
            float bx = x + w - CANCEL_W - 2.0F;
            float by = y + 3.0F;
            Draw.fill(canvas, bx, by, CANCEL_W, ROW_H - 7.0F, 2.0F,
                    confirm ? ShopTheme.redTint() : ShopTheme.card());
            Draw.stroke(canvas, bx, by, CANCEL_W, ROW_H - 7.0F, 2.0F, 1.0F,
                    confirm ? ShopTheme.red() : ShopTheme.borderDim());
            Text.drawLine(canvas, Component.translatable(confirm ? "gui.stock.confirm" : "gui.stock.cancel_order")
                    .getString(), ShopTheme.regular(), Text.SMALL,
                    confirm ? ShopTheme.red() : ShopTheme.text2(), bx, by + (ROW_H - 7.0F) / 2.0F, CANCEL_W,
                    Text.Align.CENTER);
        }
    }

    private static String stateKey(String state) {
        return switch (state) {
            case "FILLED" -> "gui.stock.state_filled";
            case "CANCELLED" -> "gui.stock.state_cancelled";
            case "EXPIRED" -> "gui.stock.state_expired";
            default -> "gui.stock.state_open";
        };
    }

    @Override
    public boolean mouseClicked(AddonFrame f, double mouseX, double mouseY, int button) {
        float x = f.x() + PAD;
        float w = f.w() - PAD * 2.0F;
        float top = f.y() + 2.0F + 18.0F;
        float viewH = f.listBottom() - 2.0F - top - 2.0F;
        if (this.scroll.pressBar(mouseX, mouseY, x, top, w, viewH)) {
            return true;
        }
        if (mouseX < x || mouseX > x + w || mouseY < top || mouseY > top + viewH) {
            return false;
        }
        int index = (int) ((mouseY - top + this.scroll.offset()) / ROW_H);
        List<StockMarketState.OrderView> orders = StockMarketState.orders();
        if (index < 0 || index >= orders.size()) {
            return false;
        }
        StockMarketState.OrderView order = orders.get(index);
        if (!"OPEN".equals(order.state)) {
            return false;
        }
        if (mouseX < x + w - CANCEL_W - 4.0F) {
            return false;
        }
        if (this.confirmId == order.id && System.currentTimeMillis() - this.confirmAt < 3000L) {
            this.confirmId = 0L;
            StockMarketState.cancel(order.id);
        } else {
            this.confirmId = order.id;
            this.confirmAt = System.currentTimeMillis();
        }
        return true;
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mouseX, double mouseY, int button) {
        return this.scroll.releaseBar();
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mouseX, double mouseY, int button, double dragX, double dragY) {
        return this.scroll.dragBar(mouseY, f.listBottom() - 2.0F - (f.y() + 2.0F + 18.0F) - 2.0F);
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mouseX, double mouseY, double amount) {
        this.scroll.wheel(amount);
        return true;
    }
}
