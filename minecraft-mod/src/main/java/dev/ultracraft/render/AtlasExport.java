package dev.ultracraft.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.ultracraft.mixin.SpriteContentsAccessor;
import dev.ultracraft.mixin.TextureAtlasAccessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;

/**
 * Minecraft's block atlas as one RGBA image for ULTRAKILL, built on the CPU from each sprite's
 * source image (first animation frame), placed exactly where the sprite's UVs point. Block
 * quads already carry block-atlas UVs, so ULTRAKILL samples this with them unchanged.
 *
 * <p>Comes from whatever Minecraft has loaded: the player's own install and resource packs.
 * Nothing is shipped with the mod. Approach after SkyCraft's SkyAtlas (MIT,
 * github.com/chasmlol/SkyCraft), reduced to the block atlas.
 */
public final class AtlasExport {
    final int width, height;
    final ByteBuffer pixels;
    /** Identity of the sprite list: a resource reload replaces it, which makes this copy stale. */
    private final Object spritesIdentity;

    private AtlasExport(TextureAtlas atlas) {
        TextureAtlasAccessor a = (TextureAtlasAccessor) atlas;
        this.width = a.ultracraft$width();
        this.height = a.ultracraft$height();
        this.pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
        List<TextureAtlasSprite> sprites = a.ultracraft$sprites();
        for (TextureAtlasSprite sprite : sprites) copy(sprite);
        this.spritesIdentity = sprites;
    }

    /** The atlas this was built from (blocks, items or gui). */
    private net.minecraft.resources.Identifier atlasId = AtlasIds.BLOCKS;

    /** Any loaded atlas (chests, beds, signs, shulker boxes, ...) as RGBA bytes, row-major. */
    public static byte[] rgba(TextureAtlas atlas, int[] sizeOut) {
        AtlasExport e = new AtlasExport(atlas);
        byte[] out = new byte[e.width * e.height * 4];
        e.pixels.duplicate().clear().get(out);
        sizeOut[0] = e.width;
        sizeOut[1] = e.height;
        return out;
    }

    static AtlasExport build(Minecraft mc) {
        return build(mc, AtlasIds.BLOCKS);
    }

    /** Any of Minecraft's atlases: AtlasIds.BLOCKS, ITEMS (item icons) or GUI (HUD sprites). */
    static AtlasExport build(Minecraft mc, net.minecraft.resources.Identifier id) {
        AtlasExport e = new AtlasExport(mc.getAtlasManager().getAtlasOrThrow(id));
        e.atlasId = id;
        return e;
    }

    boolean stale(Minecraft mc) {
        TextureAtlasAccessor a = (TextureAtlasAccessor) mc.getAtlasManager().getAtlasOrThrow(atlasId);
        return a.ultracraft$sprites() != spritesIdentity;
    }

    /**
     * Copy a sprite's first frame to where its UVs point. Sprites sit in a padded cell; the
     * padding is filled with the nearest edge pixel, as Minecraft does, so sampling at a
     * sprite's edge never picks up its neighbour.
     */
    private void copy(TextureAtlasSprite sprite) {
        NativeImage image = ((SpriteContentsAccessor) sprite.contents()).ultracraft$originalImage();
        if (image == null) return;
        int w = Math.min(sprite.contents().width(), image.getWidth());
        int h = Math.min(sprite.contents().height(), image.getHeight());
        int x0 = Math.round(sprite.getU0() * width), y0 = Math.round(sprite.getV0() * height);
        int pad = Math.max(0, Math.min(x0 - sprite.getX(), y0 - sprite.getY()));
        for (int y = -pad; y < h + pad; y++) {
            int ty = y0 + y;
            if (ty < 0 || ty >= height) continue;
            int sy = Math.clamp(y, 0, h - 1);
            for (int x = -pad; x < w + pad; x++) {
                int tx = x0 + x;
                if (tx < 0 || tx >= width) continue;
                int argb = image.getPixel(Math.clamp(x, 0, w - 1), sy);
                int o = (ty * width + tx) * 4;
                pixels.put(o, (byte) (argb >> 16));
                pixels.put(o + 1, (byte) (argb >> 8));
                pixels.put(o + 2, (byte) argb);
                pixels.put(o + 3, (byte) (argb >>> 24));
            }
        }
    }
}
