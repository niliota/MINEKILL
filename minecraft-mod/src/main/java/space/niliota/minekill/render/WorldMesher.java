package space.niliota.minekill.render;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import space.niliota.minekill.bridge.Protocol;
import space.niliota.minekill.bridge.Stream;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The world layer, Minecraft side: block meshes for ULTRAKILL to draw natively.
 *
 * <p>Each dirty 16^3 section is meshed with Minecraft's OWN block and fluid renderers (models,
 * biome tint, face shading, ambient occlusion), captured as triangles instead of going to the
 * GPU, and sent through the STREAM file together with the block atlas. ULTRAKILL draws them in
 * its own scene, so its walls hide them and they hide its walls; no image ever crosses.
 *
 * <p>Approach after SkyCraft's WorldExporter (MIT, github.com/chasmlol/SkyCraft). Differences:
 * block atlas only so far, and colours keep Minecraft's own face shading because ULTRAKILL draws
 * these unlit (SkyCraft removes it and relights in Skyrim).
 *
 * <p>Render thread only (called from the client tick).
 */
public final class WorldMesher {

    private static final Logger LOG = LoggerFactory.getLogger("minekill");
    private static final int SECTIONS_PER_FRAME = 12;
    private static final long BUDGET_NANOS = 3_000_000L;

    private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
    /** Sections ULTRAKILL currently holds a non-empty mesh for. */
    private static final LongOpenHashSet SENT = new LongOpenHashSet();

    private final Stream stream;
    private final MeshBuilder mesh = new MeshBuilder();
    private AtlasExport atlas;
    private ModelBlockRenderer blockRenderer;
    private FluidRenderer fluidRenderer;
    private ClientLevel sentLevel;
    private int servedRequest = Integer.MIN_VALUE;
    private boolean resendPending = true;
    private int meshesSent;

    public WorldMesher(Stream stream) {
        this.stream = stream;
    }

    /** From LevelExtractorMixin. Any thread that touches the renderer; guarded. */
    public static void markDirty(int sx, int sy, int sz, boolean now) {
        long key = SectionPos.asLong(sx, sy, sz);
        synchronized (DIRTY) {
            if (now) DIRTY.addAndMoveToFirst(key);
            else DIRTY.add(key);
        }
    }

    /** Once per client frame while ULTRAKILL is live. */
    public void frame(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || !stream.ok()) return;

        int request = stream.resyncRequest();
        if (request != servedRequest || level != sentLevel || atlas == null || atlas.stale(mc)) {
            servedRequest = request;
            resendPending = true;
        }
        if (resendPending && !resendEverything(mc, level)) return;   // ring full; retry next frame
        meshDirty(level);
    }

    /** CLEAR_ALL + atlas, and every loaded non-empty section queued again. */
    private boolean resendEverything(Minecraft mc, ClientLevel level) {
        if (atlas == null || atlas.stale(mc) || level != sentLevel) atlas = AtlasExport.build(mc);
        blockRenderer = new ModelBlockRenderer(mc.options.ambientOcclusion().get(), true, mc.getBlockColors());
        fluidRenderer = new FluidRenderer(mc.getModelManager().getFluidStateModelSet());

        if (!stream.tryWrite(Protocol.STREAM_MSG_CLEAR_ALL)) return false;
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(atlas.width).putInt(atlas.height).flip();
        if (!stream.tryWrite(Protocol.STREAM_MSG_ATLAS, header, atlas.pixels.duplicate().clear())) return false;
        if (!sendExtraAtlas(mc, net.minecraft.data.AtlasIds.ITEMS, Protocol.STREAM_ATLAS_ITEMS)) return false;
        if (!sendExtraAtlas(mc, net.minecraft.data.AtlasIds.GUI, Protocol.STREAM_ATLAS_GUI)) return false;
        if (!sendGuiSprites(mc)) return false;
        space.niliota.minekill.gui.GuiTextures.resendAll();   // font sheets too
        AtlasAnimator.INSTANCE.resend();              // fresh atlases carry first frames again
        LOG.info("[MINEKILL] world: sent {}x{} block atlas ({} MB) for resync request {}",
                atlas.width, atlas.height, atlas.width * atlas.height * 4 / (1024 * 1024), servedRequest);

        sentLevel = level;
        SENT.clear();
        int radius = mc.options.getEffectiveRenderDistance() + 1;
        int pcx = SectionPos.blockToSectionCoord(mc.player.getBlockX());
        int pcz = SectionPos.blockToSectionCoord(mc.player.getBlockZ());
        for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
            for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    if (!sections[i].hasOnlyAir()) markDirty(cx, chunk.getSectionYFromSectionIndex(i), cz, false);
                }
            }
        }
        stream.markResyncServed(servedRequest);
        resendPending = false;
        return true;
    }

    /** Item icons and HUD sprites (for the native HUD), from Minecraft's loaded atlases. */
    private boolean sendExtraAtlas(Minecraft mc, net.minecraft.resources.Identifier id, int atlasId) {
        AtlasExport a = AtlasExport.build(mc, id);
        ByteBuffer h = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(atlasId).putInt(a.width).putInt(a.height).flip();
        boolean ok = stream.tryWrite(Protocol.STREAM_MSG_EXTRA_ATLAS, h, a.pixels.duplicate().clear());
        if (ok) LOG.info("[MINEKILL] world: sent {} atlas {}x{}", id, a.width, a.height);
        return ok;
    }

    /** HUD sprites ULTRAKILL draws, by name, with their rectangles in the GUI atlas. */
    static final String[] GUI_SPRITES = {
        "hud/hotbar", "hud/hotbar_selection", "hud/hotbar_offhand_left", "hud/crosshair",
        "hud/experience_bar_background", "hud/experience_bar_progress",
        "hud/heart/container", "hud/heart/full", "hud/heart/half",
        "hud/food_empty", "hud/food_half", "hud/food_full",
        "hud/armor_empty", "hud/armor_half", "hud/armor_full",
    };

    private boolean sendGuiSprites(Minecraft mc) {
        var gui = mc.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.GUI);
        ByteBuffer b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(GUI_SPRITES.length);
        for (String name : GUI_SPRITES) {
            var sprite = gui.getSprite(net.minecraft.resources.Identifier.withDefaultNamespace(name));
            byte[] n = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            b.putShort((short) n.length).put(n);
            b.putFloat(sprite.getU0()).putFloat(sprite.getV0()).putFloat(sprite.getU1()).putFloat(sprite.getV1());
            b.putInt(sprite.contents().width()).putInt(sprite.contents().height());
        }
        return stream.tryWrite(Protocol.STREAM_MSG_GUI_SPRITES, b.flip());
    }

    private void meshDirty(ClientLevel level) {
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int meshed = 0;
        while (meshed < SECTIONS_PER_FRAME && System.nanoTime() < deadline) {
            long key;
            synchronized (DIRTY) {
                if (DIRTY.isEmpty()) return;
                key = DIRTY.removeFirstLong();
            }
            int r = meshSection(level, key);
            if (r < 0) {
                markDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), true);   // ring full
                return;
            }
            meshed += r;
        }
    }

    /** @return 1 if meshing work was done, 0 if nothing to do, -1 if the ring was full. */
    private int meshSection(ClientLevel level, long key) {
        int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
        if (chunk == null) return 0;                       // unloaded: ULTRAKILL keeps what it has
        int index = level.getSectionIndexFromSectionY(sy);
        LevelChunkSection section = index >= 0 && index < chunk.getSections().length ? chunk.getSections()[index] : null;
        boolean empty = section == null || section.hasOnlyAir();
        if (empty && !SENT.contains(key)) return 0;        // nothing there, nothing to remove

        mesh.reset();
        if (!empty) {
            BlockPos origin = SectionPos.of(sx, sy, sz).origin();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                        BlockState state = chunk.getBlockState(pos);
                        if (state.isAir()) continue;
                        FluidState fluid = state.getFluidState();
                        if (!fluid.isEmpty()) {
                            fluidRenderer.tesselate(level, pos, mesh, state, fluid);
                        }
                        if (state.getRenderShape() == RenderShape.MODEL) {
                            blockRenderer.tesselateBlock(mesh, x, y, z, level, pos.immutable(), state,
                                    models.get(state), state.getSeed(pos));
                        }
                    }
                }
            }
        }

        ByteBuffer header = ByteBuffer.allocate(Protocol.STREAM_SECTION_HEADER).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(sx).putInt(sy).putInt(sz).putInt(mesh.vertices).flip();
        if (!stream.tryWrite(Protocol.STREAM_MSG_SECTION, header, mesh.bytes())) return -1;
        if (mesh.vertices > 0) SENT.add(key);
        else SENT.remove(key);
        if (++meshesSent <= 10 || meshesSent % 200 == 0) {
            LOG.info("[MINEKILL] world: section {} {} {} -> {} vertices ({} sections in ULTRAKILL)",
                    sx, sy, sz, mesh.vertices, SENT.size());
        }
        return 1;
    }

    public String describe() {
        int dirty;
        synchronized (DIRTY) {
            dirty = DIRTY.size();
        }
        return String.format("world sections=%d dirty=%d sent=%d streamPending=%dKB",
                SENT.size(), dirty, meshesSent, stream.ok() ? stream.pending() / 1024 : -1);
    }

    /**
     * Collects Minecraft's block quads and fluid quads as triangles in the STREAM vertex layout:
     * section-relative position, block-atlas UV, RGBA colour (tint x shade x AO), light/flags.
     */
    private static final class MeshBuilder
            implements net.minecraft.client.renderer.block.BlockQuadOutput, FluidRenderer.Output, VertexConsumer {
        private static final int[] QUAD_TO_TRIS = {0, 1, 2, 0, 2, 3};

        private ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
        int vertices;
        private final float[] fq = new float[4 * 7];
        private int fqCount;
        private int fluidLayer;

        void reset() {
            buf.clear();
            vertices = 0;
            fqCount = 0;
        }

        ByteBuffer bytes() {
            return buf.duplicate().flip();
        }

        private void ensure(int bytes) {
            if (buf.remaining() < bytes) {
                ByteBuffer bigger = ByteBuffer.allocateDirect(Math.max(buf.capacity() * 2, buf.position() + bytes))
                        .order(ByteOrder.LITTLE_ENDIAN);
                buf.flip();
                bigger.put(buf);
                buf = bigger;
            }
        }

        /** Vertex flag bits for Minecraft's section layer: 16 translucent, 17 cutout, neither = solid. */
        private static int layerBits(ChunkSectionLayer layer) {
            return layer == ChunkSectionLayer.TRANSLUCENT ? 1 << 16 : layer == ChunkSectionLayer.CUTOUT ? 1 << 17 : 0;
        }

        private void vertex(float x, float y, float z, float u, float v, int argb, int light, int layerBits) {
            buf.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
            buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
            int block = (light >> 4) & 0xF;
            int sky = (light >> 20) & 0xF;
            buf.putInt(block | (sky << 8) | layerBits);
            vertices++;
        }

        // ---- blocks ----
        @Override
        public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            ensure(6 * Protocol.STREAM_VERTEX_SIZE);
            int layer = layerBits(quad.materialInfo().layer());
            int emission = quad.materialInfo().lightEmission();
            for (int k : QUAD_TO_TRIS) {
                var p = quad.position(k);
                long uv = quad.packedUV(k);
                vertex(p.x() + x, p.y() + y, p.z() + z, UVPair.unpackU(uv), UVPair.unpackV(uv),
                        instance.getColor(k), instance.getLightCoordsWithEmission(k, emission), layer);
            }
        }

        // ---- fluids: FluidRenderer emits world-space vertices in quads of four ----
        @Override
        public VertexConsumer getBuilder(ChunkSectionLayer layer) {
            fluidLayer = layerBits(layer);
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                              float nx, float ny, float nz) {
            int o = fqCount * 7;
            fq[o] = x;
            fq[o + 1] = y;
            fq[o + 2] = z;
            fq[o + 3] = u;
            fq[o + 4] = v;
            fq[o + 5] = Float.intBitsToFloat(color);
            fq[o + 6] = Float.intBitsToFloat(light);
            if (++fqCount == 4) {
                fqCount = 0;
                ensure(6 * Protocol.STREAM_VERTEX_SIZE);
                for (int k : QUAD_TO_TRIS) {
                    int b = k * 7;
                    // Section-relative, like block quads: verified in the 26.3 bytecode,
                    // FluidRenderer.tesselate positions vertices at BlockPos.getX/Y/Z() & 15.
                    vertex(fq[b], fq[b + 1], fq[b + 2], fq[b + 3], fq[b + 4],
                            Float.floatToRawIntBits(fq[b + 5]), Float.floatToRawIntBits(fq[b + 6]), fluidLayer);
                }
            }
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
