package cn.autoforged.module_shop_stock.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import io.github.humbleui.skija.Canvas;

/** 商店内容区里的一个视图。 */
public interface StockView {
    default void init(AddonPageHost host) {
    }

    void render(Canvas canvas, AddonFrame frame);

    default boolean mouseClicked(AddonFrame frame, double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseReleased(AddonFrame frame, double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseDragged(AddonFrame frame, double mouseX, double mouseY, int button, double dragX,
            double dragY) {
        return false;
    }

    default boolean mouseScrolled(AddonFrame frame, double mouseX, double mouseY, double amount) {
        return false;
    }

    default void clickedOutside() {
    }

    default void cancelInput() {
    }
}
