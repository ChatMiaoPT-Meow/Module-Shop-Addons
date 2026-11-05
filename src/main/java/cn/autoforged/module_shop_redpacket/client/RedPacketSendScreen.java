package cn.autoforged.module_shop_redpacket.client;

import cn.autoforged.module_shop.api.client.ShopModules;
import cn.autoforged.module_shop_redpacket.RedPacketManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 发红包的**回退界面**（原版控件）：AtomLib 不可用（例如非 Windows）时，点侧栏「红包」打开它；
 * AtomLib 可用时用的是内容区页面 {@link RedPacketPage}。两者共用 {@link RedPacketState}。
 */
public class RedPacketSendScreen extends Screen {
    private static final int W = 248;
    private static final int H = 214;
    private static final int RED = 0xFFD84343;
    private static final int RED_DARK = 0xFFA82E2E;
    private static final int PAPER = 0xFFFFF4E0;
    private static final int GOLD = 0xFFFFC94A;
    private static final int TEXT = 0xFF4A2A10;
    private static final int MUTED = 0xFF8A6A50;

    private EditBox countBox;
    private EditBox totalBox;
    private EditBox passwordBox;
    private EditBox greetingBox;
    private Button luckyButton;
    private Button evenButton;
    private Button sendButton;


    public RedPacketSendScreen() {
        super(Component.translatable("gui.module_shop_redpacket.send_title"));
    }

    private int left() {
        return (this.width - W) / 2;
    }

    private int top() {
        return (this.height - H) / 2;
    }

    @Override
    protected void init() {
        int x = left();
        int y = top();

        this.luckyButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.module_shop_redpacket.mode_lucky"), b -> setLucky(true))
                .bounds(x + 14, y + 40, 107, 18).build());
        this.evenButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.module_shop_redpacket.mode_even"), b -> setLucky(false))
                .bounds(x + W - 14 - 107, y + 40, 107, 18).build());

        int fieldX = x + 84;
        int fieldW = W - 84 - 14;
        this.countBox = box(fieldX, y + 66, fieldW, 4, RedPacketState.count, "gui.module_shop_redpacket.count_hint");
        this.countBox.setFilter(s -> s.chars().allMatch(Character::isDigit));
        this.countBox.setResponder(v -> RedPacketState.count = v);
        this.totalBox = box(fieldX, y + 88, fieldW, 15, RedPacketState.total, "gui.module_shop_redpacket.total_hint");
        this.totalBox.setFilter(s -> s.chars().allMatch(Character::isDigit));
        this.totalBox.setResponder(v -> RedPacketState.total = v);
        this.passwordBox = box(fieldX, y + 110, fieldW, RedPacketManager.MAX_PASSWORD, RedPacketState.password,
                "gui.module_shop_redpacket.password_hint");
        this.passwordBox.setResponder(v -> RedPacketState.password = v);
        this.greetingBox = box(fieldX, y + 132, fieldW, RedPacketManager.MAX_GREETING, RedPacketState.greeting,
                "gui.module_shop_redpacket.greeting_hint");
        this.greetingBox.setResponder(v -> RedPacketState.greeting = v);

        this.sendButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.module_shop_redpacket.send"), b -> send())
                .bounds(x + 14, y + H - 30, 140, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(x + W - 14 - 76, y + H - 30, 76, 20).build());
        setLucky(RedPacketState.lucky);
        setInitialFocus(this.countBox);
        if (RedPacketState.balance < 0L) {
            RedPacketState.requestStatus();
        }
    }

    private EditBox box(int x, int y, int w, int max, String value, String hintKey) {
        EditBox box = new EditBox(this.font, x, y, w, 16, Component.empty());
        box.setMaxLength(max);
        box.setValue(value);
        box.setHint(Component.translatable(hintKey).withColor(0xFF9A9A9A));
        return addRenderableWidget(box);
    }

    private void setLucky(boolean lucky) {
        RedPacketState.lucky = lucky;
        this.luckyButton.active = !lucky;
        this.evenButton.active = lucky;
    }

    /** 服务端回包：发出成功就关掉界面（回到商店），失败留在界面上显示原因。 */
    void onResult(boolean ok) {
        if (ok) {
            onClose();
        }
    }

    private void send() {
        RedPacketState.submit("");
    }

    @Override
    public void tick() {
        super.tick();
        this.sendButton.active = !RedPacketState.waiting;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        // 背景：红色信封头 + 米色纸面，在控件下面先画（控件由 super.render 画在后面会被盖住，所以这里只画装饰与文字）。
        g.drawCenteredString(this.font, this.title, x + W / 2, y + 12, GOLD);
        String currency = RedPacketState.currencyName("");
        Component balanceLine = RedPacketState.balance < 0L
                ? Component.translatable("gui.module_shop_redpacket.balance_loading")
                : Component.translatable("gui.module_shop_redpacket.balance", RedPacketState.balance, currency);
        g.drawCenteredString(this.font, balanceLine, x + W / 2, y + 25, 0xFFFFE6C0);

        label(g, "gui.module_shop_redpacket.count", x, y + 70);
        label(g, "gui.module_shop_redpacket.total_lucky", x, y + 92);
        label(g, "gui.module_shop_redpacket.password", x, y + 114);
        label(g, "gui.module_shop_redpacket.greeting", x, y + 136);
        g.drawCenteredString(this.font, RedPacketState.preview(""), x + W / 2, y + 154, MUTED);
        if (!RedPacketState.message.getString().isEmpty()) {
            for (var line : this.font.split(RedPacketState.message, W - 20)) {
                g.drawCenteredString(this.font, line, x + W / 2, y + 166,
                        RedPacketState.messageError ? 0xFFD32F2F : MUTED);
                break;
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        g.fill(x - 1, y - 1, x + W + 1, y + H + 1, RED_DARK);
        g.fill(x, y, x + W, y + H, PAPER);
        // 信封头
        g.fill(x, y, x + W, y + 36, RED);
        g.fill(x, y + 36, x + W, y + 38, GOLD);
        // 底部按钮区
        g.fill(x, y + H - 38, x + W, y + H, 0xFFF6E2C2);
    }

    private void label(GuiGraphics g, String key, int x, int y) {
        g.drawString(this.font, Component.translatable(key), x + 14, y, TEXT, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        ShopModules.back();
    }
}
