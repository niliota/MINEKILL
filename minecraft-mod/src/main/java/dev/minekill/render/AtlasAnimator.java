package dev.minekill.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.minekill.bridge.Protocol;
import dev.minekill.bridge.Stream;
import dev.minekill.mixin.SpriteContentsAccessor;
import dev.minekill.mixin.TextureAtlasAccessor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Animated sprites (water, lava, fire, portals, sea lanterns, prismarine, magma, ...) for
 * ULTRAKILL. The atlas copies carry each sprite's first frame; this follows Minecraft's own
 * animation timing (frame list and per-frame times from the sprite's .mcmeta, at 20 ticks a
 * second) and, whenever a sprite's frame changes, streams just that sprite's pixels for the new
 * frame (STREAM_MSG_ATLAS_PATCH), taken from the source image Minecraft keeps in memory.
 * ULTRAKILL copies the patch into its atlas on the GPU. Frame interpolation is not reproduced.
 */
public final class AtlasAnimator {
    private static final Logger LOG = LoggerFactory.getLogger("minekill");

    private static final class Anim {
        int atlasId, x, y, w, h, rowSize, total;
        int[] index, time;
        NativeImage image;
        int lastFrame = -1;
    }

    public static final AtlasAnimator INSTANCE = new AtlasAnimator();

    private final List<Anim> anims = new ArrayList<>();
    private Object blocksIdentity, itemsIdentity;
    private boolean failed;

    /** Every frame, after the atlases are on ULTRAKILL's side. */
    public void frame(Minecraft mc, Stream stream) {
        if (failed) return;
        try {
            var am = mc.getAtlasManager();
            TextureAtlas blocks = am.getAtlasOrThrow(AtlasIds.BLOCKS), items = am.getAtlasOrThrow(AtlasIds.ITEMS);
            Object bi = ((TextureAtlasAccessor) blocks).minekill$sprites(), ii = ((TextureAtlasAccessor) items).minekill$sprites();
            if (bi != blocksIdentity || ii != itemsIdentity) {   // first time, or a resource reload
                blocksIdentity = bi;
                itemsIdentity = ii;
                anims.clear();
                collect(blocks, Protocol.STREAM_ATLAS_BLOCKS);
                collect(items, Protocol.STREAM_ATLAS_ITEMS);
                LOG.info("[MINEKILL] animation: {} animated sprites", anims.size());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            failed = true;
            LOG.warn("[MINEKILL] animation: disabled: {}", e.toString());
            return;
        }
        long tick = System.nanoTime() / 50_000_000L;
        for (Anim a : anims) {
            int t = (int) (tick % a.total), f = 0;
            while (t >= a.time[f]) { t -= a.time[f]; f++; }
            int frame = a.index[f];
            if (frame == a.lastFrame) continue;
            if (send(a, frame, stream)) a.lastFrame = frame;
        }
    }

    /** Forget sent frames: ULTRAKILL got fresh atlases (first frames) after a resync. */
    public void resend() {
        for (Anim a : anims) a.lastFrame = -1;
    }

    private static Field animatedField, framesField, rowSizeField;
    private static Method frameIndex, frameTime;

    private void collect(TextureAtlas atlas, int atlasId) throws ReflectiveOperationException {
        TextureAtlasAccessor acc = (TextureAtlasAccessor) atlas;
        int aw = acc.minekill$width(), ah = acc.minekill$height();
        for (TextureAtlasSprite sprite : acc.minekill$sprites()) {
            var contents = sprite.contents();
            if (!contents.isAnimated()) continue;
            if (animatedField == null) {
                animatedField = contents.getClass().getDeclaredField("animatedTexture");
                animatedField.setAccessible(true);
            }
            Object anim = animatedField.get(contents);
            if (anim == null) continue;
            if (framesField == null) {
                framesField = anim.getClass().getDeclaredField("frames");
                framesField.setAccessible(true);
                rowSizeField = anim.getClass().getDeclaredField("frameRowSize");
                rowSizeField.setAccessible(true);
            }
            List<?> frames = (List<?>) framesField.get(anim);
            if (frames.isEmpty()) continue;
            if (frameIndex == null) {
                frameIndex = frames.get(0).getClass().getDeclaredMethod("index");
                frameIndex.setAccessible(true);
                frameTime = frames.get(0).getClass().getDeclaredMethod("time");
                frameTime.setAccessible(true);
            }
            Anim a = new Anim();
            a.atlasId = atlasId;
            a.w = contents.width();
            a.h = contents.height();
            a.x = Math.round(sprite.getU0() * aw);
            a.y = Math.round(sprite.getV0() * ah);
            a.rowSize = rowSizeField.getInt(anim);
            a.image = ((SpriteContentsAccessor) contents).minekill$originalImage();
            if (a.image == null || a.rowSize <= 0) continue;
            a.index = new int[frames.size()];
            a.time = new int[frames.size()];
            for (int i = 0; i < frames.size(); i++) {
                a.index[i] = (int) frameIndex.invoke(frames.get(i));
                a.time[i] = Math.max(1, (int) frameTime.invoke(frames.get(i)));
                a.total += a.time[i];
            }
            anims.add(a);
        }
    }

    private boolean send(Anim a, int frame, Stream stream) {
        int fx = (frame % a.rowSize) * a.w, fy = (frame / a.rowSize) * a.h;
        if (fx + a.w > a.image.getWidth() || fy + a.h > a.image.getHeight()) return true;   // bad metadata: skip
        ByteBuffer header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(a.atlasId).putInt(a.x).putInt(a.y).putInt(a.w).putInt(a.h).flip();
        ByteBuffer px = ByteBuffer.allocate(a.w * a.h * 4);
        for (int y = 0; y < a.h; y++) {
            for (int x = 0; x < a.w; x++) {
                int argb = a.image.getPixel(fx + x, fy + y);
                px.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            }
        }
        return stream.tryWrite(Protocol.STREAM_MSG_ATLAS_PATCH, header, px.flip());
    }
}
