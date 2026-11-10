package cn.autoforged.module_shop_stock.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import cn.autoforged.module_shop.api.client.ShopTheme;
import cn.autoforged.module_shop.client.ui.render.Draw;
import cn.autoforged.module_shop.client.ui.render.Text;
import cn.autoforged.module_shop.client.ui.v2.ListTable;
import cn.autoforged.module_shop.client.ui.v2.V2;
import cn.autoforged.module_shop.client.ui.widget.SkiaTextField;
import cn.autoforged.module_shop_stock.client.StockMarketState;
import io.github.humbleui.skija.Canvas;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.nbt.CompoundTag;

/** 管理台（UI v2）：股票列表（ListTable）/ 新建与编辑 / 消息事件 / 审计列表。 */
public final class AdminView implements StockView {
    private enum Mode { LIST, FORM, NEWS, AUDIT }

    private static final float PAD = 8.0F;
    private static final float ACTION_H = 22.0F;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private static final ListTable STOCKS = new ListTable();
    private static final ListTable AUDIT = new ListTable();

    private final Ui.Hits hits = new Ui.Hits();
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
    private float[][] modeRects = new float[0][2];
    private float modeY;

    private static String tr(String key, Object... args) {
        return Ui.tr(key, args);
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
        this.symbol = field(host, 8, tr("gui.stock.admin_symbol"), false);
        this.name = field(host, 24, tr("gui.stock.admin_name"), false);
        this.sector = field(host, 8, tr("gui.stock.admin_sector"), false);
        this.base = field(host, 7, tr("gui.stock.admin_base"), true);
        this.vol = field(host, 5, tr("gui.stock.admin_vol"), false);
        this.depth = field(host, 7, tr("gui.stock.v2.admin.depth"), true);
        this.impact = field(host, 3, tr("gui.stock.admin_impact"), false);
        this.news = field(host, 60, tr("gui.stock.admin_news"), false);
    }

    private SkiaTextField field(AddonPageHost host, int maxLength, String placeholder, boolean digits) {
        SkiaTextField field = new SkiaTextField(host.screen(), host.font(), maxLength)
                .placeholder(placeholder).fontSize(V2.FS_S);
        if (digits) {
            field.editBox().setFilter(text -> text.chars().allMatch(Character::isDigit));
        }
        host.registerInput(field.editBox());
        return field;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void toolbar(Canvas c, AddonFrame f, float x, float y, float w) {
        if (!StockMarketState.admin) {
            return;
        }
        float cy = y + V2.BAR / 2.0F;
        String[] labels = {tr("gui.stock.admin_tab_stocks"), tr("gui.stock.admin_tab_new"),
                tr("gui.stock.admin_tab_news"), tr("gui.stock.admin_tab_audit")};
        this.modeY = cy - V2.CTRL / 2.0F;
        this.modeRects = V2.segmented(c, labels, this.mode.ordinal(), x, this.modeY, null);
        float[] last = this.modeRects[this.modeRects.length - 1];
        float rx = last[0] + last[1] + 10.0F;
        if (x + w - rx > 30.0F) {
            V2.text(c, tr("gui.stock.admin_stock_count", StockMarketState.adminStocks().size()), V2.FS_S,
                    ShopTheme.text3(), rx, cy, x + w - rx, Text.Align.RIGHT);
        }
    }

    @Override
    public void render(Canvas c, AddonFrame b) {
        this.hits.clear();
        if (!StockMarketState.admin) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastRequest > 6000L) {
            this.lastRequest = now;
            StockMarketState.requestAdmin();
        }
        hideInactiveFields();
        switch (this.mode) {
            case LIST -> renderList(c, b);
            case FORM -> renderForm(c, b);
            case NEWS -> renderNews(c, b);
            case AUDIT -> renderAudit(c, b);
            default -> {
            }
        }
    }

    @Override
    public String footLeft() {
        return switch (this.mode) {
            case LIST -> tr("gui.stock.v2.admin.foot_list", StockMarketState.adminStocks().size(),
                    this.selected.isEmpty() ? "—" : this.selected);
            case FORM -> tr(this.editing ? "gui.stock.v2.admin.foot_edit" : "gui.stock.v2.admin.foot_new");
            case NEWS -> tr("gui.stock.v2.admin.foot_news");
            case AUDIT -> tr("gui.stock.v2.admin.foot_audit", StockMarketState.audit().size());
        };
    }

    private StockMarketState.AdminStock currentStock() {
        for (StockMarketState.AdminStock stock : StockMarketState.adminStocks()) {
            if (stock.symbol.equals(this.selected)) {
                return stock;
            }
        }
        return null;
    }

    private void renderList(Canvas c, AddonFrame b) {
        float x = b.x();
        float tableH = b.h() - ACTION_H;
        List<StockMarketState.AdminStock> stocks = StockMarketState.adminStocks();
        float[][] cols = V2.columns(x, b.w(), PAD, 4.0F, 46, 0, 44, 50, 44, 36, 48);
        String[] heads = {tr("gui.stock.v2.col.code"), tr("gui.stock.v2.col.name"), tr("gui.stock.v2.col.sector"),
                tr("gui.stock.v2.col.price"), tr("gui.stock.v2.col.holders"), tr("gui.stock.v2.col.orders"),
                tr("gui.stock.v2.col.status")};
        Text.Align[] aligns = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.RIGHT, Text.Align.RIGHT,
                Text.Align.RIGHT, Text.Align.LEFT};
        STOCKS.layout(x, b.y(), b.w(), tableH, cols, heads, aligns, null);
        STOCKS.sortCol = -1;
        if (stocks.isEmpty()) {
            V2.tableHead(c, x, b.y(), b.w(), cols, heads, aligns, -1, false);
            V2.empty(c, tr("gui.stock.loading"), x, b.y() + V2.HEAD, b.w(), tableH - V2.HEAD);
        } else {
            STOCKS.render(c, b.host().items(), b.mouseX(), b.mouseY(), b.dtMs(), stocks.size(), (cv, row, ry, hover) -> {
                StockMarketState.AdminStock s = stocks.get(row);
                if (s.symbol.equals(this.selected)) {
                    STOCKS.selected(cv, ry);
                }
                float cy = ry + V2.ROW / 2.0F;
                V2.bold(cv, s.symbol, V2.FS_S, ShopTheme.text(), cols[0][0], cy, cols[0][1], Text.Align.LEFT);
                V2.text(cv, s.name, V2.FS, s.enabled ? ShopTheme.text() : ShopTheme.text3(), cols[1][0], cy,
                        cols[1][1], Text.Align.LEFT);
                V2.text(cv, s.sector, V2.FS_S, ShopTheme.text3(), cols[2][0], cy, cols[2][1], Text.Align.LEFT);
                V2.bold(cv, V2.money(s.price), V2.FS_S, ShopTheme.text(), cols[3][0], cy, cols[3][1],
                        Text.Align.RIGHT);
                V2.text(cv, String.valueOf(s.holders), V2.FS_S, ShopTheme.text2(), cols[4][0], cy, cols[4][1],
                        Text.Align.RIGHT);
                V2.text(cv, String.valueOf(s.orders), V2.FS_S, ShopTheme.text2(), cols[5][0], cy, cols[5][1],
                        Text.Align.RIGHT);
                float cursor = cols[6][0];
                if (s.halted) {
                    cursor += V2.tag(cv, tr("gui.stock.v2.tag.halt"), ShopTheme.gold(), cursor, cy);
                }
                if (!s.enabled) {
                    V2.tag(cv, tr("gui.stock.v2.tag.delisted"), ShopTheme.text3(), cursor, cy);
                } else if (!s.halted) {
                    V2.tag(cv, tr("gui.stock.v2.admin.status_ok"), ShopTheme.accent(), cursor, cy);
                }
            });
        }

        // 操作条
        float by = b.y() + tableH;
        Draw.hairline(c, x, by, b.w(), ShopTheme.borderDim());
        float bx = x + PAD;
        float bh = V2.CTRL;
        float cyBtn = by + (ACTION_H - bh) / 2.0F;
        StockMarketState.AdminStock current = currentStock();
        boolean has = current != null;
        boolean confirm = this.confirmRemoveAt > 0L && System.currentTimeMillis() - this.confirmRemoveAt < 4000L;
        String haltLabel = tr(has && current.halted ? "gui.stock.admin_resume" : "gui.stock.admin_halt");
        String listLabel = tr(has && !current.enabled ? "gui.stock.admin_list" : "gui.stock.admin_delist");
        String removeLabel = tr(confirm ? "gui.stock.admin_confirm_remove" : "gui.stock.admin_remove");
        String[] ids = {"edit", "halt", "listing", "remove"};
        String[] labels = {tr("gui.stock.admin_edit"), haltLabel, listLabel, removeLabel};
        for (int i = 0; i < ids.length; i++) {
            float bw = Math.max(36.0F, V2.buttonWidth(labels[i]));
            V2.Btn kind = !has ? V2.Btn.DISABLED : i == 3 ? V2.Btn.DANGER : V2.Btn.NORMAL;
            Ui.button(c, this.hits, ids[i], labels[i], kind, bx, cyBtn, bw, bh, b.mouseX(), b.mouseY());
            bx += bw + 4.0F;
        }
        bx += 6.0F;
        String dividend = tr("gui.stock.admin_dividend");
        float dw = Math.max(36.0F, V2.buttonWidth(dividend));
        Ui.button(c, this.hits, "dividend", dividend, StockMarketState.fund > 0L ? V2.Btn.PRIMARY : V2.Btn.DISABLED,
                bx, cyBtn, dw, bh, b.mouseX(), b.mouseY());
        V2.text(c, tr("gui.stock.v2.admin.fund", V2.money(StockMarketState.fund)), V2.FS_S, ShopTheme.text2(),
                bx + dw + 8.0F, by + ACTION_H / 2.0F, x + b.w() - PAD - (bx + dw + 8.0F), Text.Align.LEFT);
    }

    private void placeField(Canvas c, AddonFrame b, SkiaTextField field, String caption, float x, float y, float w) {
        V2.section(c, caption, x, y + 3.0F, w);
        field.bounds(x, y + 8.0F, w, V2.CTRL).visible(true);
        field.render(c, b.mouseX(), b.mouseY(), b.dtMs());
    }

    private void renderForm(Canvas c, AddonFrame b) {
        float x = b.x() + PAD;
        float w = b.w() - PAD * 2.0F;
        float gap = 8.0F;
        float colW = (w - gap) / 2.0F;
        float y = b.y() + 8.0F;
        float row = 8.0F + V2.CTRL + 7.0F;
        this.symbol.enabled(!this.editing);
        placeField(c, b, this.symbol, tr("gui.stock.admin_symbol"), x, y, colW);
        placeField(c, b, this.name, tr("gui.stock.admin_name"), x + colW + gap, y, colW);
        placeField(c, b, this.sector, tr("gui.stock.admin_sector"), x, y + row, colW);
        placeField(c, b, this.base, tr("gui.stock.admin_base"), x + colW + gap, y + row, colW);
        placeField(c, b, this.vol, tr("gui.stock.admin_vol"), x, y + row * 2.0F, colW);
        placeField(c, b, this.depth, tr("gui.stock.v2.admin.depth"), x + colW + gap, y + row * 2.0F, colW);
        float by = y + row * 3.0F + 2.0F;
        Ui.button(c, this.hits, "save", tr("gui.stock.admin_save"), V2.Btn.PRIMARY, x, by, colW, V2.CTRL,
                b.mouseX(), b.mouseY());
        Ui.button(c, this.hits, "cancel", tr("gui.stock.admin_cancel"), V2.Btn.NORMAL, x + colW + gap, by, colW,
                V2.CTRL, b.mouseX(), b.mouseY());
        V2.text(c, tr("gui.stock.admin_form_hint"), V2.FS_S, ShopTheme.text3(), x, by + V2.CTRL + 9.0F, w,
                Text.Align.LEFT);
    }

    private void renderNews(Canvas c, AddonFrame b) {
        float x = b.x() + PAD;
        float w = b.w() - PAD * 2.0F;
        float y = b.y() + 8.0F;
        float gap = 6.0F;
        float sw = 80.0F;
        float iw = 56.0F;
        float pubW = Math.max(40.0F, V2.buttonWidth(tr("gui.stock.admin_publish")) + 12.0F);
        placeField(c, b, this.symbol, tr("gui.stock.admin_symbol"), x, y, sw);
        placeField(c, b, this.impact, tr("gui.stock.admin_impact"), x + sw + gap, y, iw);
        Ui.button(c, this.hits, "publish", tr("gui.stock.admin_publish"), V2.Btn.PRIMARY, x + sw + iw + gap * 2.0F,
                y + 8.0F, pubW, V2.CTRL, b.mouseX(), b.mouseY());
        float ny = y + 8.0F + V2.CTRL + 7.0F;
        placeField(c, b, this.news, tr("gui.stock.admin_news"), x, ny, w);
        V2.text(c, tr("gui.stock.admin_news_hint"), V2.FS_S, ShopTheme.text3(), x, ny + 8.0F + V2.CTRL + 9.0F, w,
                Text.Align.LEFT);
    }

    private void renderAudit(Canvas c, AddonFrame b) {
        List<StockMarketState.AuditRow> rows = StockMarketState.audit();
        float[][] cols = V2.columns(b.x(), b.w(), PAD, 6.0F, 66, 70, 60, 0);
        String[] heads = {tr("gui.stock.v2.col.time"), tr("gui.stock.v2.col.actor"), tr("gui.stock.v2.col.action"),
                tr("gui.stock.v2.col.detail")};
        Text.Align[] aligns = {Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT, Text.Align.LEFT};
        AUDIT.layout(b.x(), b.y(), b.w(), b.h(), cols, heads, aligns, null);
        AUDIT.sortCol = -1;
        if (rows.isEmpty()) {
            V2.tableHead(c, b.x(), b.y(), b.w(), cols, heads, aligns, -1, false);
            V2.empty(c, tr("gui.stock.no_audit"), b.x(), b.y() + V2.HEAD, b.w(), b.h() - V2.HEAD);
            return;
        }
        AUDIT.render(c, b.host().items(), b.mouseX(), b.mouseY(), b.dtMs(), rows.size(), (cv, row, ry, hover) -> {
            StockMarketState.AuditRow r = rows.get(row);
            float cy = ry + V2.ROW / 2.0F;
            V2.text(cv, CLOCK.format(Instant.ofEpochMilli(r.time)), V2.FS_S, ShopTheme.text2(), cols[0][0], cy,
                    cols[0][1], Text.Align.LEFT);
            V2.text(cv, r.actor, V2.FS_S, ShopTheme.text(), cols[1][0], cy, cols[1][1], Text.Align.LEFT);
            V2.bold(cv, r.action, V2.FS_S, ShopTheme.text(), cols[2][0], cy, cols[2][1], Text.Align.LEFT);
            V2.text(cv, r.detail, V2.FS_S, ShopTheme.text2(), cols[3][0], cy, cols[3][1], Text.Align.LEFT);
        });
    }

    // ------------------------------------------------------------------ 动作

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
        this.mode = Mode.FORM;
        this.confirmRemoveAt = 0L;
    }

    private void submitNews() {
        CompoundTag tag = new CompoundTag();
        tag.putString("symbol", this.symbol.value().trim().toUpperCase(java.util.Locale.ROOT));
        tag.putString("impact", this.impact.value());
        tag.putString("text", this.news.value());
        StockMarketState.adminAction("news", tag);
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

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(AddonFrame f, double mx, double my, int button) {
        if (!StockMarketState.admin) {
            return false;
        }
        for (SkiaTextField field : visibleFields()) {
            if (field.mouseClicked(mx, my, button)) {
                return true;
            }
        }
        if (button == 0) {
            int seg = Ui.segHit(this.modeRects, this.modeY, mx, my);
            if (seg >= 0) {
                open(Mode.values()[seg]);
                return true;
            }
            String id = this.hits.at(mx, my);
            if (id != null) {
                switch (id) {
                    case "edit" -> editSelected();
                    case "halt" -> toggleHalt();
                    case "listing" -> toggleListing();
                    case "remove" -> removeSelected();
                    case "dividend" -> payDividend();
                    case "save" -> submitForm();
                    case "cancel" -> resetForm();
                    case "publish" -> submitNews();
                    default -> {
                    }
                }
                return true;
            }
            if (this.mode == Mode.LIST && STOCKS.pressBar(mx, my)) {
                return true;
            }
            if (this.mode == Mode.AUDIT && AUDIT.pressBar(mx, my)) {
                return true;
            }
        }
        if (this.mode == Mode.LIST) {
            ListTable.Hit hit = STOCKS.click(mx, my);
            if (hit != null) {
                List<StockMarketState.AdminStock> stocks = StockMarketState.adminStocks();
                if (hit.row() >= 0 && hit.row() < stocks.size()) {
                    this.selected = stocks.get(hit.row()).symbol;
                    this.confirmRemoveAt = 0L;
                }
                return true;
            }
        } else if (this.mode == Mode.AUDIT) {
            return AUDIT.click(mx, my) != null;
        }
        return false;
    }

    private SkiaTextField[] visibleFields() {
        return switch (this.mode) {
            case FORM -> new SkiaTextField[]{this.symbol, this.name, this.sector, this.base, this.vol, this.depth};
            case NEWS -> new SkiaTextField[]{this.symbol, this.impact, this.news};
            default -> new SkiaTextField[0];
        };
    }

    private SkiaTextField[] allFields() {
        return new SkiaTextField[]{this.symbol, this.name, this.sector, this.base, this.vol, this.depth, this.impact,
                this.news};
    }

    private void hideInactiveFields() {
        SkiaTextField[] active = visibleFields();
        for (SkiaTextField field : allFields()) {
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
        if (this.mode == Mode.NEWS && this.symbol != null) {
            this.symbol.enabled(true);
        }
    }

    @Override
    public boolean mouseReleased(AddonFrame f, double mx, double my, int button) {
        boolean handled = STOCKS.releaseBar() | AUDIT.releaseBar();
        for (SkiaTextField field : visibleFields()) {
            handled |= field.mouseReleased(mx, my, button);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(AddonFrame f, double mx, double my, int button, double dx, double dy) {
        if (STOCKS.dragBar(my) || AUDIT.dragBar(my)) {
            return true;
        }
        for (SkiaTextField field : visibleFields()) {
            if (field.mouseDragged(mx, my, button, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(AddonFrame f, double mx, double my, double amount) {
        boolean page = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        if (this.mode == Mode.LIST) {
            return STOCKS.wheel(mx, my, amount, page);
        }
        if (this.mode == Mode.AUDIT) {
            return AUDIT.wheel(mx, my, amount, page);
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
        STOCKS.releaseBar();
        AUDIT.releaseBar();
        for (SkiaTextField field : allFields()) {
            if (field != null) {
                field.blur();
                field.visible(false);
            }
        }
    }
}
