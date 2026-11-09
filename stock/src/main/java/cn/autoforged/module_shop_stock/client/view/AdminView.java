package cn.autoforged.module_shop_stock.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.widget.Button;
import cn.autoforged.module_shop.client.ui.widget.ScrollController;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop_stock.client.StockMarketState;
import io.github.humbleui.skija.Canvas;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** 管理台：股票列表 / 新建与编辑 / 消息事件 / 审计。 */
public final class AdminView implements StockView {
    private enum Mode { LIST, FORM, NEWS, AUDIT }

    private static final float PAD = 4.0F;
    private static final float ROW_H = 17.0F;
    private static final float FIELD_H = 14.0F;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ScrollController scroll = new ScrollController();
    private final Button[] tabs = new Button[4];
    private final Button save;
    private final Button cancel;
    private final Button publish;
    private final Button edit;
    private final Button halt;
    private final Button listing;
    private final Button remove;
    private final Button dividend;

    private SkiaTextField symbol;
    private SkiaTextField name;
    private SkiaTextField sector;
    private SkiaTextField base;
    private SkiaTextField vol;
    private SkiaTextField depth;
    private SkiaTextField impact;
    private SkiaTextField news;

    private Mode mode = Mode.LIST;
    private String selected = "";
    private boolean editing;
    private long confirmRemoveAt;
    private long lastRequest;

    public AdminView() {
        String[] labels = {"股票", "新建", "消息", "审计"};
        for (int i = 0; i < labels.length; i++) {
            Mode target = Mode.values()[i];
            this.tabs[i] = new Button(Button.Kind.TAB, labels[i], () -> open(target))
                    .radius(2.0F).fontSize(Text.SMALL);
        }
        this.save = new Button(Button.Kind.ACCENT, "保存", this::submitForm).radius(2.0F).fontSize(Text.SMALL);
        this.cancel = new Button(Button.Kind.NORMAL, "取消", this::resetForm).radius(2.0F).fontSize(Text.SMALL);
        this.publish = new Button(Button.Kind.ACCENT, "发布", this::submitNews).radius(2.0F).fontSize(Text.SMALL);
        this.edit = new Button(Button.Kind.NORMAL, "编辑", this::editSelected).radius(2.0F).fontSize(Text.SMALL);
        this.halt = new Button(Button.Kind.NORMAL, "停牌", this::toggleHalt).radius(2.0F).fontSize(Text.SMALL);
        this.listing = new Button(Button.Kind.NORMAL, "下架", this::toggleListing).radius(2.0F).fontSize(Text.SMALL);
        this.remove = new Button(Button.Kind.DANGER, "删除", this::removeSelected).radius(2.0F).fontSize(Text.SMALL);
        this.dividend = new Button(Button.Kind.NORMAL, "分红", this::payDividend).radius(2.0F).fontSize(Text.SMALL);
    }

    private void open(Mode target) {
        this.mode = target;
        this.confirmRemoveAt = 0L;
        if (target == Mode.FORM) {
            clearForm();
        }
    }

    @Override
    public void init(AddonPageHost host) {
        this.symbol = field(host, 8, "代码", false);
        this.name = field(host, 24, "名称", false);
        this.sector = field(host, 8, "行业", false);
        this.base = field(host, 7, "基准价", true);
        this.vol = field(host, 5, "波动", false);
        this.depth = field(host, 7, "深度", true);
        this.impact = field(host, 3, "涨跌%", false);
        this.news = field(host, 60, "消息内容", false);
    }

    private SkiaTextField field(AddonPageHost host, int maxLength, String placeholder, boolean digits) {
        SkiaTextField field = new SkiaTextField(host.screen(), host.font(), maxLength)
                .placeholder(placeholder).fontSize(Text.SMALL);
        if (digits) {
            field.editBox().setFilter(text -> text.chars().allMatch(Character::isDigit));
        }
        host.registerInput(field.editBox());
        return field;
    }

    @Override
    public void render(Canvas canvas, AddonFrame f) {
        if (!StockMarketState.admin) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastRequest > 6000L) {
            this.lastRequest = now;
            StockMarketState.requestAdmin();
        }
        float x = f.x() + PAD;
        float y = f.y() + 2.0F;
        float w = f.w() - PAD * 2.0F;
        float bottom = f.listBottom() - 2.0F;

        float tabW = 36.0F;
        for (int i = 0; i < this.tabs.length; i++) {
            this.tabs[i].selected(this.mode == Mode.values()[i])
                    .bounds(x + i * (tabW + 2.0F), y, tabW, 12.0F);
            this.tabs[i].render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        }
        Text.drawLine(canvas, StockMarketState.adminStocks().size() + " 只", ShopTheme.regular(), Text.SMALL,
                ShopTheme.text3(), x + w - 40.0F, y + 6.0F, 38.0F, Text.Align.RIGHT);

        hideInactiveFields();
        switch (this.mode) {
            case LIST -> renderList(canvas, f, x, y + 15.0F, w, bottom);
            case FORM -> renderForm(canvas, f, x, y + 15.0F, w);
            case NEWS -> renderNews(canvas, f, x, y + 15.0F, w);
            case AUDIT -> renderAudit(canvas, x, y + 15.0F, w, bottom);
            default -> {
            }
        }
    }

    private void renderList(Canvas canvas, AddonFrame f, float x, float y, float w, float bottom) {
        Text.drawLine(canvas, Component.translatable("gui.stock.admin_columns").getString(), ShopTheme.regular(),
                Text.SMALL, ShopTheme.text3(), x + 3.0F, y + 4.0F, w - 6.0F, Text.Align.LEFT);
        float top = y + 9.0F;
        float viewH = Math.max(20.0F, bottom - top - 20.0F);
        List<StockMarketState.AdminStock> stocks = StockMarketState.adminStocks();
        this.scroll.setContent(stocks.size() * ROW_H, viewH);
        this.scroll.update();

        int saved = canvas.save();
        try {
            Draw.clip(canvas, x, top, w, viewH);
            for (int i = 0; i < stocks.size(); i++) {
                float rowY = top + i * ROW_H - this.scroll.offset();
                if (rowY + ROW_H < top || rowY > top + viewH) {
                    continue;
                }
                StockMarketState.AdminStock stock = stocks.get(i);
                boolean hover = f.mouseY() >= rowY && f.mouseY() < rowY + ROW_H - 1.0F;
                if (stock.symbol.equals(this.selected)) {
                    Draw.fill(canvas, x, rowY, w, ROW_H - 1.0F, 2.0F, ShopTheme.accentTint());
                } else if (hover) {
                    Draw.fill(canvas, x, rowY, w, ROW_H - 1.0F, 2.0F, ShopTheme.rowHover());
                }
                Text.drawLine(canvas, stock.symbol, ShopTheme.bold(), Text.SMALL, ShopTheme.text(), x + 3.0F,
                        rowY + 8.0F, 40.0F, Text.Align.LEFT);
                String tag = (stock.halted ? "停 " : "") + (stock.enabled ? "" : "下 ");
                Text.drawLine(canvas, tag + stock.name, ShopTheme.regular(), 6.5F,
                        stock.halted ? ShopTheme.gold() : stock.enabled ? ShopTheme.text2() : ShopTheme.text3(),
                        x + 45.0F, rowY + 8.0F, w * 0.44F, Text.Align.LEFT);
                Text.drawLine(canvas, stock.holders + "人 " + StockMarketState.number(stock.price),
                        ShopTheme.regular(), Text.SMALL, ShopTheme.text3(), x + w * 0.62F, rowY + 8.0F, w * 0.36F,
                        Text.Align.RIGHT);
            }
        } finally {
            canvas.restoreToCount(saved);
        }
        this.scroll.drawBar(canvas, x, top, w, viewH, f.mouseX(), f.mouseY(), f.dtMs());

        float buttonY = bottom - 14.0F;
        float buttonW = (w - 8.0F) / 5.0F;
        StockMarketState.AdminStock current = currentStock();
        boolean confirm = this.confirmRemoveAt > 0L && System.currentTimeMillis() - this.confirmRemoveAt < 4000L;
        this.edit.enabled(current != null).bounds(x, buttonY, buttonW, 13.0F).visible(true);
        this.halt.label(current != null && current.halted ? "复牌" : "停牌")
                .enabled(current != null).bounds(x + buttonW + 2.0F, buttonY, buttonW, 13.0F).visible(true);
        this.listing.label(current != null && current.enabled ? "下架" : "上架")
                .enabled(current != null).bounds(x + (buttonW + 2.0F) * 2, buttonY, buttonW, 13.0F).visible(true);
        this.remove.label(confirm ? "确认删" : "删除")
                .enabled(current != null).bounds(x + (buttonW + 2.0F) * 3, buttonY, buttonW, 13.0F).visible(true);
        this.dividend.enabled(StockMarketState.fund > 0L)
                .bounds(x + (buttonW + 2.0F) * 4, buttonY, buttonW, 13.0F).visible(true);
        for (Button button : new Button[]{this.edit, this.halt, this.listing, this.remove, this.dividend}) {
            button.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        }
    }

    private StockMarketState.AdminStock currentStock() {
        for (StockMarketState.AdminStock stock : StockMarketState.adminStocks()) {
            if (stock.symbol.equals(this.selected)) {
                return stock;
            }
        }
        return null;
    }

    private void renderForm(Canvas canvas, AddonFrame f, float x, float y, float w) {
        float gap = 4.0F;
        float half = (w - gap) / 2.0F;
        float row2 = y + FIELD_H + 3.0F;
        float row3 = row2 + FIELD_H + 3.0F;

        this.symbol.bounds(x, y, half, FIELD_H).visible(true).enabled(!this.editing);
        this.name.bounds(x + half + gap, y, half, FIELD_H).visible(true);
        this.sector.bounds(x, row2, half, FIELD_H).visible(true);
        this.base.bounds(x + half + gap, row2, half, FIELD_H).visible(true);
        this.vol.bounds(x, row3, half, FIELD_H).visible(true);
        this.depth.bounds(x + half + gap, row3, half, FIELD_H).visible(true);
        for (SkiaTextField field : new SkiaTextField[]{this.symbol, this.name, this.sector, this.base, this.vol,
                this.depth}) {
            field.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        }

        float buttonY = row3 + FIELD_H + 4.0F;
        this.save.bounds(x, buttonY, half, FIELD_H).visible(true);
        this.cancel.bounds(x + half + gap, buttonY, half, FIELD_H).visible(true);
        this.save.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.cancel.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        Text.drawLine(canvas, Component.translatable("gui.stock.admin_form_hint").getString(),
                ShopTheme.regular(), Text.SMALL, ShopTheme.text3(), x, buttonY + FIELD_H + 7.0F, w, Text.Align.LEFT);
    }

    private void submitForm() {
        String symbolText = this.symbol.value().trim().toUpperCase(java.util.Locale.ROOT);
        CompoundTag tag = new CompoundTag();
        tag.putString("symbol", symbolText);
        tag.putString("name", this.name.value());
        tag.putString("sector", this.sector.value());
        tag.putString("base", this.base.value());
        tag.putString("vol", this.vol.value());
        tag.putString("depth", this.depth.value());
        StockMarketState.adminAction(this.editing ? "edit" : "add", tag);
    }

    private void resetForm() {
        clearForm();
        open(Mode.LIST);
        StockMarketState.requestAdmin();
    }

    private void clearForm() {
        this.editing = false;
        if (this.symbol != null) {
            this.symbol.setValue("");
            this.name.setValue("");
            this.sector.setValue("");
            this.base.setValue("");
            this.vol.setValue("0.045");
            this.depth.setValue("3500");
        }
    }

    private void editSelected() {
        StockMarketState.AdminStock stock = currentStock();
        if (stock == null) {
            return;
        }
        this.editing = true;
        StockMarketState.Def def = StockMarketState.def(stock.symbol);
        this.symbol.setValue(stock.symbol);
        this.name.setValue(def == null ? stock.name : def.label());
        this.sector.setValue(stock.sector);
        this.base.setValue(String.valueOf(stock.base));
        this.vol.setValue(String.format(java.util.Locale.ROOT, "%.3f", stock.volatility));
        this.depth.setValue(String.valueOf(stock.depth));
        open(Mode.FORM);
    }

    private void renderNews(Canvas canvas, AddonFrame f, float x, float y, float w) {
        this.symbol.bounds(x, y, 66.0F, FIELD_H).visible(true).enabled(true);
        this.impact.bounds(x + 70.0F, y, 44.0F, FIELD_H).visible(true);
        this.publish.bounds(x + 118.0F, y, 66.0F, FIELD_H).visible(true);
        this.news.bounds(x, y + FIELD_H + 4.0F, w, FIELD_H).visible(true);
        this.symbol.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.impact.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.news.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        this.publish.render(canvas, f.mouseX(), f.mouseY(), f.dtMs());
        Text.drawLine(canvas, Component.translatable("gui.stock.admin_news_hint").getString(),
                ShopTheme.regular(), Text.SMALL, ShopTheme.text3(), x, y + FIELD_H * 2.0F + 14.0F, w, Text.Align.LEFT);
    }

    private void submitNews() {
        CompoundTag tag = new CompoundTag();
        tag.putString("symbol", this.symbol.value().trim().toUpperCase(java.util.Locale.ROOT));
        tag.putString("impact", this.impact.value());
        tag.putString("text", this.news.value());
        StockMarketState.adminAction("news", tag);
    }

    private void renderAudit(Canvas canvas, float x, float y, float w, float bottom) {
        List<StockMarketState.AuditRow> rows = StockMarketState.audit();
        if (rows.isEmpty()) {
            Text.drawLine(canvas, Component.translatable("gui.stock.no_audit").getString(), ShopTheme.regular(),
                    6.5F, ShopTheme.text3(), x, y + 20.0F, w, Text.Align.CENTER);
            return;
        }
        int max = (int) Math.min(rows.size(), Math.floor((bottom - y - 4.0F) / 14.0F));
        for (int i = 0; i < max; i++) {
            StockMarketState.AuditRow row = rows.get(i);
            float rowY = y + i * 14.0F;
            String line = CLOCK.format(Instant.ofEpochMilli(row.time)) + " " + row.actor + " " + row.action
                    + " " + row.detail;
            Text.drawLine(canvas, line, ShopTheme.regular(), Text.SMALL, ShopTheme.text2(), x + 2.0F, rowY + 7.0F,
                    w - 4.0F, Text.Align.LEFT);
        }
    }

    private void toggleHalt() {
        StockMarketState.AdminStock stock = currentStock();
        if (stock != null) {
            StockMarketState.adminAction("toggleHalt", symbolTag(stock.symbol));
        }
    }

    private void toggleListing() {
        StockMarketState.AdminStock stock = currentStock();
        if (stock != null) {
            StockMarketState.adminAction("toggleEnabled", symbolTag(stock.symbol));
        }
    }

    private void removeSelected() {
        StockMarketState.AdminStock stock = currentStock();
        if (stock == null) {
            return;
        }
        boolean risky = stock.holders > 0 || stock.orders > 0;
        long now = System.currentTimeMillis();
        if (risky && (this.confirmRemoveAt == 0L || now - this.confirmRemoveAt > 4000L)) {
            this.confirmRemoveAt = now;
            return;
        }
        CompoundTag tag = symbolTag(stock.symbol);
        tag.putBoolean("force", risky);
        this.confirmRemoveAt = 0L;
        this.selected = "";
        StockMarketState.adminAction("remove", tag);
    }

    private void payDividend() {
        StockMarketState.adminAction("dividend", new CompoundTag());
    }

    private static CompoundTag symbolTag(String symbol) {
        CompoundTag tag = new CompoundTag();
        tag.putString("symbol", symbol);
        return tag;
    }

    @Override
    public boolean mouseClicked(AddonFrame f, double mouseX, double mouseY, int button) {
        if (!StockMarketState.admin) {
            return false;
        }
        for (Button tab : this.tabs) {
            if (tab.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        for (SkiaTextField field : visibleFields()) {
            if (field.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        for (Button action : activeButtons()) {
            if (action.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        if (this.mode == Mode.LIST) {
            return clickList(f, mouseX, mouseY);
        }
        return false;
    }

    private boolean clickList(AddonFrame f, double mouseX, double mouseY) {
        float x = f.x() + PAD;
        float w = f.w() - PAD * 2.0F;
        float top = f.y() + 2.0F + 15.0F + 9.0F;
        float viewH = Math.max(20.0F, f.listBottom() - 2.0F - top - 20.0F);
        if (this.scroll.pressBar(mouseX, mouseY, x, top, w, viewH)) {
            return true;
        }
        if (mouseX < x || mouseX > x + w || mouseY < top || mouseY > top + viewH) {
            return false;
        }
        int index = (int) ((mouseY - top + this.scroll.offset()) / ROW_H);
        List<StockMarketState.AdminStock> stocks = StockMarketState.adminStocks();
        if (index < 0 || index >= stocks.size()) {
            return false;
        }
        this.selected = stocks.get(index).symbol;
        this.confirmRemoveAt = 0L;
        return true;
    }

    private SkiaTextField[] visibleFields() {
        return switch (this.mode) {
            case FORM -> new SkiaTextField[]{this.symbol, this.name, this.sector, this.base, this.vol, this.depth};
            case NEWS -> new SkiaTextField[]{this.symbol, this.impact, this.news};
            default -> new SkiaTextField[0];
        };
    }

    private void hideInactiveFields() {
        SkiaTextField[] active = visibleFields();
        for (SkiaTextField field : new SkiaTextField[]{this.symbol, this.name, this.sector, this.base, this.vol,
                this.depth, this.impact, this.news}) {
            if (field == null) {
                continue;
            }
            boolean keep = false;
            for (SkiaTextField shown : active) {
                if (shown == field) {
                    keep = true;
                    break;
                }
            }
            if (!keep) {
                field.visible(false);
            }
        }
    }

    private Button[] activeButtons() {
        return switch (this.mode) {
            case LIST -> new Button[]{this.edit, this.halt, this.listing, this.remove, this.dividend};
            case FORM -> new Button[]{this.save, this.cancel};
            case NEWS -> new Button[]{this.publish};
            default -> new Button[0];
        };
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mouseX, double mouseY, int button) {
        boolean handled = this.scroll.releaseBar();
        for (Button tab : this.tabs) {
            handled |= tab.mouseReleased(mouseX, mouseY, button);
        }
        for (Button action : activeButtons()) {
            handled |= action.mouseReleased(mouseX, mouseY, button);
        }
        for (SkiaTextField field : visibleFields()) {
            handled |= field.mouseReleased(mouseX, mouseY, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.mode == Mode.LIST && this.scroll.dragBar(mouseY,
                Math.max(20.0F, f.listBottom() - 2.0F - (f.y() + 2.0F + 15.0F + 9.0F) - 20.0F))) {
            return true;
        }
        for (SkiaTextField field : visibleFields()) {
            if (field.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mouseX, double mouseY, double amount) {
        if (this.mode == Mode.LIST) {
            this.scroll.wheel(amount);
            return true;
        }
        return false;
    }

    @Override
    public void clickedOutside() {
        for (SkiaTextField field : visibleFields()) {
            field.blur();
        }
    }

    @Override
    public void cancelInput() {
        for (Button tab : this.tabs) {
            tab.cancelPress();
        }
        for (Button action : activeButtons()) {
            action.cancelPress();
        }
        for (SkiaTextField field : new SkiaTextField[]{this.symbol, this.name, this.sector, this.base, this.vol,
                this.depth, this.impact, this.news}) {
            if (field != null) {
                field.blur();
                field.visible(false);
            }
        }
    }
}
