package cn.autoforged.module_shop_stock.client.view;

import cn.autoforged.module_shop.api.client.AddonFrame;
import cn.autoforged.module_shop.api.client.AddonPageHost;
import io.github.humbleui.skija.Canvas;

/**
 * 商店内容区里的一个视图（UI v2）。页面负责标题 / 页签 / 底栏，视图负责：
 * 工具条中页签右侧的那一段（{@link #toolbar}）、中间的主体（{@link #render}）和底栏左侧文字。
 * 鼠标事件传入的是绝对坐标，视图自己在绘制时记下命中区（工具条与主体共用同一份）。
 */
public interface StockView {
    default void init(AddonPageHost host) {
    }

    /** 工具条里页签之后的空白段：[x, x+w]，纵向中心 cy，高 V2.BAR。 */
    default void toolbar(Canvas canvas, AddonFrame frame, float x, float y, float w) {
    }

    /** 主体区（工具条与底栏之间）。 */
    void render(Canvas canvas, AddonFrame frame);

    /** 底栏左侧说明（null = 不显示）。 */
    default String footLeft() {
        return null;
    }

    /** 底栏右侧操作提示（null = 不显示）。 */
    default String footHint() {
        return null;
    }

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
