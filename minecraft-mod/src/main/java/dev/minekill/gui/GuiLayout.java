package dev.minekill.gui;

import net.minecraft.client.Minecraft;

/**
 * Minecraft lays its GUI out for ULTRAKILL's screen, not its own window.
 *
 * <p>Minecraft's GUI (hotbar centred at width/2, chat at the bottom left, ...) is positioned from
 * {@code Window.getGuiScaledWidth/Height()} (verified: {@code GuiGraphicsExtractor.guiWidth()}
 * calls it). ULTRAKILL sends its screen size every input sample; WindowMixin answers those getters
 * (and getGuiScale) with values computed here, so the captured GUI lands where it belongs on
 * ULTRAKILL's screen. Minecraft's own (background) window then shows its GUI laid out for the
 * other screen, which does not matter. Scale follows Minecraft's own rule for the player's GUI
 * Scale option, applied to ULTRAKILL's size.
 */
public final class GuiLayout {
    private static volatile int screenW, screenH, scale, scaledW, scaledH;

    private GuiLayout() {}

    public static boolean active() { return scale > 0; }
    public static int scale() { return scale; }
    public static int scaledWidth() { return scaledW; }
    public static int scaledHeight() { return scaledH; }

    /** From InputApplier, each input sample. 0x0 (an older ULTRAKILL build) switches it off. */
    public static void setScreen(Minecraft mc, int w, int h) {
        if (w <= 0 || h <= 0) { scale = 0; return; }
        if (w == screenW && h == screenH && scale > 0) return;
        screenW = w;
        screenH = h;
        int option = mc.options.guiScale().get();
        boolean unicode = mc.options.forceUnicodeFont().get();
        // Window.calculateScale, for ULTRAKILL's size instead of Minecraft's window.
        int s = 1;
        while (s != option && s < w && s < h && w / (s + 1) >= 320 && h / (s + 1) >= 240) s++;
        if (unicode && s % 2 != 0) s++;
        scaledW = w / s + (w % s != 0 ? 1 : 0);
        scaledH = h / s + (h % s != 0 ? 1 : 0);
        scale = s;
    }
}
