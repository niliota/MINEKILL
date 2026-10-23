package space.niliota.minekill.gui;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import space.niliota.minekill.bridge.Protocol;
import space.niliota.minekill.bridge.Stream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.data.AtlasIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which texture a captured GUI quad samples, as a key ULTRAKILL understands, and CPU mirrors of
 * Minecraft's font sheets so ULTRAKILL has their pixels (no images of rendered frames cross; these
 * are the source glyph bitmaps).
 *
 * <p>Atlases (GUI, blocks, items) are recognised by identity and already exported by the world
 * layer. Font sheets ({@code FontTexture}, 256x256) are filled on the GPU as text first appears:
 * FontTextureMixin redirects each glyph's {@code GlyphBitmap.upload(x, y, texture)} through
 * {@link #onGlyphUpload}, which copies the glyph's CPU pixels into a mirror of that sheet. Mirrors
 * exist from game start, so glyphs uploaded before ULTRAKILL connected are not lost.
 */
public final class GuiTextures {
    private static final Logger LOG = LoggerFactory.getLogger("minekill");

    private static final class Mirror {
        final int key, w, h;
        final byte[] rgba;
        int dx0 = Integer.MAX_VALUE, dy0 = Integer.MAX_VALUE, dx1 = -1, dy1 = -1;
        boolean full = true;
        Mirror(int key, int w, int h) { this.key = key; this.w = w; this.h = h; this.rgba = new byte[w * h * 4]; }
        void dirty(int x, int y, int ww, int hh) {
            dx0 = Math.min(dx0, x); dy0 = Math.min(dy0, y);
            dx1 = Math.max(dx1, x + ww); dy1 = Math.max(dy1, y + hh);
        }
    }

    private static final Map<GpuTexture, Mirror> MIRRORS = new IdentityHashMap<>();
    private static final Map<GpuTexture, Integer> KEYS = new IdentityHashMap<>();
    private static final Set<String> UNKNOWN_LOGGED = new HashSet<>();
    private static final Set<Class<?>> UNSUPPORTED_LOGGED = new HashSet<>();
    private static int nextKey = Protocol.GUI_TEX_DYNAMIC_BASE;
    private static Object atlasIdentity;

    private GuiTextures() {}

    /** Map the GUI, block and item atlases to their fixed keys; false while they do not exist yet. */
    private static boolean ensureAtlasKeys(Minecraft mc) {
        try {
            Object identity = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.GUI).getTexture();
            if (identity != atlasIdentity) {                 // first time, or a resource reload
                KEYS.clear();
                KEYS.put(mc.getAtlasManager().getAtlasOrThrow(AtlasIds.GUI).getTexture(), Protocol.GUI_TEX_GUI);
                KEYS.put(mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getTexture(), Protocol.GUI_TEX_BLOCKS);
                KEYS.put(mc.getAtlasManager().getAtlasOrThrow(AtlasIds.ITEMS).getTexture(), Protocol.GUI_TEX_ITEMS);
                atlasIdentity = identity;
            }
            return true;
        } catch (RuntimeException notReady) {
            return false;
        }
    }

    /** The key for a texture view, or -1 for one ULTRAKILL cannot have (quads with it are skipped). */
    public static synchronized int key(Minecraft mc, GpuTextureView view) {
        if (view == null) return Protocol.GUI_TEX_NONE;
        GpuTexture t = view.texture();
        // The atlases' GPU textures only exist once resources have loaded; asking earlier throws
        // "Texture does not exist" (crashed the 2026-10-13 build at the first loading screen).
        // Until then, map nothing and try again next frame.
        if (!ensureAtlasKeys(mc)) return -1;
        Integer k = KEYS.get(t);
        if (k != null) return k;
        Mirror m = MIRRORS.get(t);
        if (m != null) return m.key;
        Mirror loaded = loadFromResources(mc, t, t.getLabel());
        if (loaded != null) return loaded.key;
        if (UNKNOWN_LOGGED.add(t.getLabel())) {
            LOG.info("[MINEKILL] gui: texture '{}' ({}x{}) is not mirrored; its quads are skipped",
                    t.getLabel(), t.getWidth(0), t.getHeight(0));
        }
        return -1;
    }

    /**
     * The key for a texture Minecraft binds by location (entity models: the player's skin, armour),
     * or -1. The location is also the resource path, in case the GPU texture's label is not.
     */
    public static synchronized int keyForLocation(Minecraft mc, net.minecraft.resources.Identifier id) {
        GpuTexture t;
        net.minecraft.client.renderer.texture.AbstractTexture abs;
        try {
            abs = mc.getTextureManager().getTexture(id);
            t = abs.getTexture();
        } catch (RuntimeException notReady) {
            return -1;
        }
        if (!ensureAtlasKeys(mc)) return -1;
        Integer fixed = KEYS.get(t);
        if (fixed != null) return fixed;
        Mirror m = MIRRORS.get(t);
        if (m != null) return m.key;
        // Other atlases (chests, beds, signs, banners, shulker boxes): rebuilt on the CPU from
        // their sprites, like the block atlas. Keyed by the GPU texture, so a reload re-mirrors.
        if (abs instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas) {
            int[] size = new int[2];
            byte[] px = space.niliota.minekill.render.AtlasExport.rgba(atlas, size);
            m = new Mirror(nextKey++, size[0], size[1]);
            System.arraycopy(px, 0, m.rgba, 0, px.length);
            MIRRORS.put(t, m);
            LOG.info("[MINEKILL] gui: mirrored atlas '{}' ({}x{})", id, m.w, m.h);
            return m.key;
        }
        // Downloaded skins are DynamicTextures that keep their pixels on the CPU.
        if (abs instanceof net.minecraft.client.renderer.texture.DynamicTexture dyn && dyn.getPixels() != null) {
            m = mirrorImage(t, dyn.getPixels());
            LOG.info("[MINEKILL] gui: mirrored '{}' ({}x{}) from its pixels", id, m.w, m.h);
            return m.key;
        }
        int k = key(mc, abs.getTextureView());
        if (k >= 0) return k;
        Mirror loaded = loadFromResources(mc, t, id.toString());
        return loaded != null ? loaded.key : -1;
    }

    /**
     * Standalone GUI textures (the inventory and container backgrounds, menu backgrounds, title
     * logos) are plain PNGs, not atlas sprites. Their GPU texture's label is the resource path, so
     * the same PNG is read from Minecraft's resource manager (the player's own install and resource
     * packs) once, mirrored whole and streamed like a font sheet. Textures that are not resources
     * (skins, maps) stay unmirrored.
     */
    private static Mirror loadFromResources(Minecraft mc, GpuTexture t, String label) {
        if (label == null || !label.endsWith(".png") || UNKNOWN_LOGGED.contains(label)) return null;
        var id = net.minecraft.resources.Identifier.tryParse(label);
        if (id == null) return null;
        var res = mc.getResourceManager().getResource(id);
        if (res.isEmpty()) {
            if (UNKNOWN_LOGGED.add(label)) LOG.info("[MINEKILL] gui: texture '{}' is not a resource; its quads are skipped", label);
            return null;
        }
        try (var in = res.get().open(); NativeImage img = NativeImage.read(in)) {
            Mirror m = mirrorImage(t, img);
            LOG.info("[MINEKILL] gui: mirrored '{}' ({}x{}) from resources", label, m.w, m.h);
            return m;
        } catch (java.io.IOException | RuntimeException e) {
            UNKNOWN_LOGGED.add(label);
            LOG.warn("[MINEKILL] gui: could not load '{}' from resources: {}", label, e.toString());
            return null;
        }
    }

    /** A whole image as a new mirror of {@code t}, sent by the next flush. */
    private static Mirror mirrorImage(GpuTexture t, NativeImage img) {
        Mirror m = new Mirror(nextKey++, img.getWidth(), img.getHeight());
        for (int y = 0; y < m.h; y++) {
            for (int x = 0; x < m.w; x++) {
                int argb = img.getPixel(x, y), o = (y * m.w + x) * 4;
                m.rgba[o] = (byte) (argb >> 16);
                m.rgba[o + 1] = (byte) (argb >> 8);
                m.rgba[o + 2] = (byte) argb;
                m.rgba[o + 3] = (byte) (argb >>> 24);
            }
        }
        MIRRORS.put(t, m);
        return m;
    }

    // Reflection into BitmapProvider's private glyph types (package-private record + holder).
    private static Field outerField, imageField;
    private static Method offX, offY, gw, gh, imageData;

    /** From FontTextureMixin, for every glyph placed on a font sheet. */
    public static synchronized void onGlyphUpload(GlyphBitmap bitmap, int x, int y, GpuTexture tex) {
        Mirror m = MIRRORS.computeIfAbsent(tex, t -> new Mirror(nextKey++, t.getWidth(0), t.getHeight(0)));
        try {
            Class<?> c = bitmap.getClass();
            if (c.getName().startsWith("net.minecraft.client.gui.font.glyphs.SpecialGlyphs$")) {
                // The font's generated glyphs: WHITE (5x8 solid, used for text backgrounds such as
                // the panel behind name tags, underlines and strikethrough) and MISSING (a box).
                // Their image is a field of the enum constant the bitmap belongs to.
                if (specialOuter == null) {
                    specialOuter = c.getDeclaredField("this$0");
                    specialOuter.setAccessible(true);
                    specialImage = specialOuter.getType().getDeclaredField("image");
                    specialImage.setAccessible(true);
                }
                NativeImage img = (NativeImage) specialImage.get(specialOuter.get(bitmap));
                copyGlyph(m, img, 0, 0, img.getWidth(), img.getHeight(), x, y);
                return;
            }
            if (!c.getName().startsWith("net.minecraft.client.gui.font.providers.BitmapProvider$Glyph")) {
                if (UNSUPPORTED_LOGGED.add(c)) LOG.info("[MINEKILL] gui: glyph bitmap type {} not mirrored yet", c.getName());
                return;
            }
            if (outerField == null) {
                outerField = c.getDeclaredField("this$0");
                outerField.setAccessible(true);
                Class<?> g = outerField.getType();
                offX = g.getDeclaredMethod("offsetX"); offY = g.getDeclaredMethod("offsetY");
                gw = g.getDeclaredMethod("width"); gh = g.getDeclaredMethod("height");
                imageData = g.getDeclaredMethod("imageData");
                for (Method mm : new Method[] {offX, offY, gw, gh, imageData}) mm.setAccessible(true);
                imageField = imageData.getReturnType().getDeclaredField("image");
                imageField.setAccessible(true);
            }
            Object glyph = outerField.get(bitmap);
            int ox = (int) offX.invoke(glyph), oy = (int) offY.invoke(glyph);
            int w = (int) gw.invoke(glyph), h = (int) gh.invoke(glyph);
            NativeImage img = (NativeImage) imageField.get(imageData.invoke(glyph));
            copyGlyph(m, img, ox, oy, w, h, x, y);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (UNSUPPORTED_LOGGED.add(bitmap.getClass())) LOG.warn("[MINEKILL] gui: glyph mirror failed: {}", e.toString());
        }
    }

    private static Field specialOuter, specialImage;

    /** Copy a w x h region of {@code img} at (ox, oy) to the mirror at (x, y), and mark it dirty. */
    private static void copyGlyph(Mirror m, NativeImage img, int ox, int oy, int w, int h, int x, int y) {
        for (int j = 0; j < h; j++) {
            int ty = y + j;
            if (ty < 0 || ty >= m.h) continue;
            for (int i = 0; i < w; i++) {
                int tx = x + i;
                if (tx < 0 || tx >= m.w) continue;
                int argb = img.getPixel(ox + i, oy + j);
                int o = (ty * m.w + tx) * 4;
                m.rgba[o] = (byte) (argb >> 16);
                m.rgba[o + 1] = (byte) (argb >> 8);
                m.rgba[o + 2] = (byte) argb;
                m.rgba[o + 3] = (byte) (argb >>> 24);
            }
        }
        m.dirty(x, y, w, h);
    }

    /** Everything again (ULTRAKILL resynced). */
    public static synchronized void resendAll() {
        for (Mirror m : MIRRORS.values()) m.full = true;
    }

    /** Send new sheets whole and changed regions as patches. Render thread, while ULTRAKILL is live. */
    public static synchronized void flush(Stream stream) {
        for (Mirror m : MIRRORS.values()) {
            int x0, y0, x1, y1;
            if (m.full) { x0 = 0; y0 = 0; x1 = m.w; y1 = m.h; }
            else if (m.dx1 > m.dx0) { x0 = m.dx0; y0 = m.dy0; x1 = m.dx1; y1 = m.dy1; }
            else continue;
            int pw = x1 - x0, ph = y1 - y0;
            ByteBuffer h = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(m.key).putInt(m.w).putInt(m.h).putInt(x0).putInt(y0).putInt(pw).putInt(ph).flip();
            ByteBuffer px = ByteBuffer.allocate(pw * ph * 4);
            for (int j = 0; j < ph; j++) px.put(m.rgba, ((y0 + j) * m.w + x0) * 4, pw * 4);
            px.flip();
            if (!stream.tryWrite(Protocol.STREAM_MSG_TEXTURE, h, px)) return;   // ring full: retry next frame
            m.full = false;
            m.dx0 = m.dy0 = Integer.MAX_VALUE;
            m.dx1 = m.dy1 = -1;
        }
    }
}
