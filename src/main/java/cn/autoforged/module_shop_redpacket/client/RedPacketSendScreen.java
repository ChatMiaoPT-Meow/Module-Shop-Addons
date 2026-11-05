package cn.autoforged.module_shop_redpacket.client;

import cn.autoforged.module_shop.api.client.ShopModules;
import cn.autoforged.module_shop_redpacket.RedPacketManager;
import cn.autoforged.module_shop_redpacket.network.RedPacketNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/**
 * 发红包界面（从商店左侧功能区「红包」打开）。
 *
 * <p>个数 / 金额 / 拼手气或均分 / 可选口令 / 可选祝福语。客户端只做输入提示，真正的校验与扣款全在服务端。
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

    private boolean lucky = true;
    private EditBox countBox;
    private EditBox totalBox;
    private EditBox passwordBox;
    private EditBox greetingBox;
    private Button luckyButton;
    private Button evenButton;
    private Button sendButton;

    private long balance = -1L;
    private String currency = "";
    private int maxCount = 100;
    private long minTotal = 1L;
    private long maxTotal = Long.MAX_VALUE;
    private int expireMinutes = 1440;
    private Component message = Component.empty();
    private boolean messageError;
    private boolean waiting;

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
        String count = this.countBox == null ? "" : this.countBox.getValue();
        String total = this.totalBox == null ? "" : this.totalBox.getValue();
        String password = this.passwordBox == null ? "" : this.passwordBox.getValue();
        String greeting = this.greetingBox == null ? "" : this.greetingBox.getValue();

        this.luckyButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.module_shop_redpacket.mode_lucky"), b -> setLucky(true))
                .bounds(x + 14, y + 40, 107, 18).build());
        this.evenButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.module_shop_redpacket.mode_even"), b -> setLucky(false))
                .bounds(x + W - 14 - 107, y + 40, 107, 18).build());

        int fieldX = x + 84;
        int fieldW = W - 84 - 14;
        this.countBox = box(fieldX, y + 66, fieldW, 7, count, "gui.module_shop_redpacket.count_hint");
        this.countBox.setFilter(s -> s.chars().allMatch(Character::isDigit));
        this.totalBox = box(fieldX, y + 88, fieldW, 15, total, "gui.module_shop_redpacket.total_hint");
        this.totalBox.setFilter(s -> s.chars().allMatch(Character::isDigit));
        this.passwordBox = box(fieldX, y + 110, fieldW, RedPacketManager.MAX_PASSWORD, password,
                "gui.module_shop_redpacket.password_hint");
        this.greetingBox = box(fieldX, y + 132, fieldW, RedPacketManager.MAX_GREETING, greeting,
                "gui.module_shop_redpacket.greeting_hint");

        this.sendButton = addRenderableWidget(Button.builder(
                Component.translatable("gui.module_shop_redpacket.send"), b -> send())
                .bounds(x + 14, y + H - 30, 140, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(x + W - 14 - 76, y + H - 30, 76, 20).build());
        setLucky(this.lucky);
        setInitialFocus(this.countBox);
        if (this.balance < 0L) {
            CompoundTag tag = new CompoundTag();
            tag.putString("type", "status");
            RedPacketNetwork.sendToServer(tag);
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
        this.lucky = lucky;
        this.luckyButton.active = !lucky;
        this.evenButton.active = lucky;
    }

    void applyStatus(CompoundTag tag) {
        this.balance = tag.getLong("balance");
        this.currency = tag.getString("currency");
        this.maxCount = tag.getInt("maxCount");
        this.minTotal = tag.getLong("minTotal");
        this.maxTotal = tag.getLong("maxTotal");
        this.expireMinutes = tag.getInt("expireMinutes");
    }

    void applyResult(CompoundTag tag) {
        this.waiting = false;
        if (tag.getBoolean("ok")) {
            onClose();
            return;
        }
        Component error = null;
        if (this.minecraft != null && this.minecraft.level != null && tag.contains("error")) {
            error = Component.Serializer.fromJson(tag.getString("error"), this.minecraft.level.registryAccess());
        }
        showError(error == null ? Component.translatable("gui.module_shop_redpacket.send_failed") : error);
        // 余额可能变了：顺手刷新一次。
        CompoundTag status = new CompoundTag();
        status.putString("type", "status");
        RedPacketNetwork.sendToServer(status);
    }

    private void showError(Component text) {
        this.message = text;
        this.messageError = true;
    }

    private void send() {
        if (this.waiting) {
            return;
        }
        long count = parse(this.countBox.getValue());
        long total = parse(this.totalBox.getValue());
        if (count < 1L || count > this.maxCount) {
            showError(Component.translatable("msg.module_shop_redpacket.bad_count", this.maxCount));
            return;
        }
        if (total < this.minTotal || total > this.maxTotal) {
            showError(Component.translatable("msg.module_shop_redpacket.bad_total", this.minTotal, this.maxTotal,
                    currencyName()));
            return;
        }
        if (total < count) {
            showError(Component.translatable("msg.module_shop_redpacket.total_lt_count", currencyName()));
            return;
        }
        if (this.balance >= 0L && total > this.balance) {
            showError(Component.translatable("msg.module_shop_redpacket.insufficient", total, currencyName(),
                    this.balance));
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putString("type", "send");
        tag.putInt("count", (int) count);
        tag.putLong("total", total);
        tag.putBoolean("lucky", this.lucky);
        tag.putString("password", this.passwordBox.getValue());
        tag.putString("greeting", this.greetingBox.getValue());
        RedPacketNetwork.sendToServer(tag);
        this.waiting = true;
        this.message = Component.translatable("gui.module_shop_redpacket.sending");
        this.messageError = false;
    }

    private static long parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 18) {
            return -1L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private String currencyName() {
        return this.currency.isEmpty() ? "星币" : this.currency;
    }

    @Override
    public void tick() {
        super.tick();
        this.sendButton.active = !this.waiting;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        // 背景：红色信封头 + 米色纸面，在控件下面先画（控件由 super.render 画在后面会被盖住，所以这里只画装饰与文字）。
        g.drawCenteredString(this.font, this.title, x + W / 2, y + 12, GOLD);
        Component balanceLine = this.balance < 0L
                ? Component.translatable("gui.module_shop_redpacket.balance_loading")
                : Component.translatable("gui.module_shop_redpacket.balance", this.balance, currencyName());
        g.drawCenteredString(this.font, balanceLine, x + W / 2, y + 25, 0xFFFFE6C0);

        label(g, "gui.module_shop_redpacket.count", x, y + 70);
        label(g, this.lucky ? "gui.module_shop_redpacket.total_lucky" : "gui.module_shop_redpacket.total_even",
                x, y + 92);
        label(g, "gui.module_shop_redpacket.password", x, y + 114);
        label(g, "gui.module_shop_redpacket.greeting", x, y + 136);

        // 预览：均分显示每人多少；拼手气显示平均值。
        long count = parse(this.countBox.getValue());
        long total = parse(this.totalBox.getValue());
        Component preview;
        if (count > 0L && total >= count) {
            preview = this.lucky
                    ? Component.translatable("gui.module_shop_redpacket.preview_lucky", count, total, currencyName())
                    : (total % count == 0L
                            ? Component.translatable("gui.module_shop_redpacket.preview_even", count, total / count,
                                    currencyName())
                            : Component.translatable("gui.module_shop_redpacket.preview_even_rem", count,
                                    total / count, total / count + 1, currencyName()));
        } else {
            preview = Component.translatable("gui.module_shop_redpacket.preview_none", this.expireMinutes / 60,
                    this.expireMinutes % 60);
        }
        g.drawCenteredString(this.font, preview, x + W / 2, y + 154, MUTED);
        if (!this.message.getString().isEmpty()) {
            for (var line : this.font.split(this.message, W - 20)) {
                g.drawCenteredString(this.font, line, x + W / 2, y + 166, this.messageError ? 0xFFD32F2F : MUTED);
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
