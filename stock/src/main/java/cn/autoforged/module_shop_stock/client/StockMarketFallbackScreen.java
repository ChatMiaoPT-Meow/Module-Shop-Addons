package cn.autoforged.module_shop_stock.client;

import cn.autoforged.module_shop.api.client.ShopModules;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** AtomLib 不可用时的原版回退界面。 */
public final class StockMarketFallbackScreen extends Screen {
    private static final int PER_PAGE = 8;

    private final List<Button> stockButtons = new ArrayList<>();
    private EditBox quantity;
    private Button buy;
    private Button sell;
    private int page;

    public StockMarketFallbackScreen() {
        super(Component.translatable("gui.stock.title"));
    }

    @Override
    protected void init() {
        this.stockButtons.clear();
        int left = this.width / 2 - 118;
        int top = this.height / 2 - 86;
        addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            this.page = Math.max(0, this.page - 1);
            rebuildWidgets();
        }).bounds(left + 178, top + 22, 18, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> {
            this.page++;
            rebuildWidgets();
        }).bounds(left + 220, top + 22, 18, 16).build());

        List<StockMarketState.Def> defs = StockMarketState.defs();
        int from = Math.min(this.page * PER_PAGE, Math.max(0, defs.size() - 1));
        for (int i = 0; i < PER_PAGE && from + i < defs.size(); i++) {
            StockMarketState.Def def = defs.get(from + i);
            int x = left + (i % 2) * 118;
            int y = top + 42 + (i / 2) * 20;
            stockButtons.add(addRenderableWidget(Button.builder(Component.literal(def.symbol),
                    button -> StockMarketState.select(def.symbol)).bounds(x, y, 112, 18).build()));
        }

        this.quantity = new EditBox(this.font, left, top + 126, 236, 18,
                Component.translatable("gui.stock.quantity"));
        this.quantity.setFilter(text -> text.chars().allMatch(Character::isDigit));
        this.quantity.setMaxLength(7);
        this.quantity.setValue(StockMarketState.quantity);
        this.quantity.setResponder(text -> StockMarketState.quantity = text);
        addRenderableWidget(this.quantity);

        this.buy = addRenderableWidget(Button.builder(Component.translatable("gui.stock.buy"),
                button -> StockMarketState.submit(true)).bounds(left, top + 148, 116, 20).build());
        this.sell = addRenderableWidget(Button.builder(Component.translatable("gui.stock.sell"),
                button -> StockMarketState.submit(false)).bounds(left + 120, top + 148, 116, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.stock.refresh"),
                button -> StockMarketState.requestSnapshot()).bounds(left, top + 172, 116, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.stock.back"),
                button -> onClose()).bounds(left + 120, top + 172, 116, 18).build());

        if (!StockMarketState.hasSnapshot) {
            StockMarketState.requestSnapshot();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        int center = this.width / 2;
        int top = this.height / 2 - 86;
        String currency = StockMarketState.currency(
                Component.translatable("gui.stock.currency_default").getString());
        graphics.drawCenteredString(this.font, this.title, center, top, 0xFFFFFFFF);
        long delta = StockMarketState.index - StockMarketState.indexPrev;
        graphics.drawCenteredString(this.font, Component.translatable("gui.stock.index",
                StockMarketState.number(StockMarketState.index),
                StockMarketState.percent(StockMarketState.indexPrev <= 0L ? 0L
                        : delta * 10_000L / StockMarketState.indexPrev)).getString(),
                center, top + 12, delta >= 0L ? 0xFFFF7777 : 0xFF77CC99);
        graphics.drawString(this.font, Component.translatable("gui.stock.cash",
                StockMarketState.number(Math.max(0L, StockMarketState.balance)), currency).getString(),
                center - 118, top + 30, 0xFFC8C8C8, false);
        graphics.drawString(this.font, Component.translatable("gui.stock.assets",
                StockMarketState.number(StockMarketState.totalAssets())).getString(),
                center + 10, top + 30, 0xFFC8C8C8, false);

        StockMarketState.Quote quote = StockMarketState.selectedQuote();
        StockMarketState.Def def = StockMarketState.selectedDef();
        boolean pending = StockMarketState.isPending();
        this.buy.active = StockMarketState.enabled && !pending && quote != null && def != null && def.enabled;
        this.sell.active = StockMarketState.enabled && !pending && quote != null
                && StockMarketState.position(StockMarketState.selected).available() > 0L;
        if (def == null || quote == null) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.stock.loading"),
                    center, top + 110, 0xFFC8C8C8);
            return;
        }
        String price = def.label() + " · " + StockMarketState.number(quote.price) + " " + currency
                + " " + StockMarketState.percent(quote.changeBps());
        graphics.drawCenteredString(this.font, price, center, top + 110,
                quote.changeBps() >= 0L ? 0xFFFF7777 : 0xFF77CC99);
        StockMarketState.Position position = StockMarketState.position(quote.symbol);
        graphics.drawCenteredString(this.font, Component.translatable("gui.stock.position_line",
                StockMarketState.number(position.quantity),
                StockMarketState.number(position.averageCost()),
                StockMarketState.number(StockMarketState.positionValue(quote.symbol)))
                .getString(), center, top + 121, 0xFFC8C8C8);
        Component message = StockMarketState.message();
        if (!message.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, message, center, top + 196,
                    StockMarketState.messageError() ? 0xFFFF7777 : 0xFF77CC99);
        }
    }

    @Override
    public void onClose() {
        ShopModules.back();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
