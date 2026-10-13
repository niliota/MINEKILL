package dev.ultracraft.render;

import dev.ultracraft.bridge.Bridge;
import dev.ultracraft.bridge.Protocol;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What Minecraft's HUD shows, for ULTRAKILL to draw natively (HUD milestone). No image crosses:
 * this sends values (health, food, XP, selected slot) and, per hotbar slot, how to draw its item
 * from the atlases ULTRAKILL already holds.
 *
 * <p>Item icons: a full-cube block (stone, planks, grass) is drawn by ULTRAKILL as a small
 * isometric cube from its model's top and side textures and tints, the way Minecraft's GUI draws
 * block items. Anything else uses the sprite its GUI item model points at (item textures, torches,
 * flowers; non-cube blocks such as stairs fall back to their main texture, flat). Cached per item;
 * cleared when the atlases are rebuilt.
 */
public final class HudExport {
    private static final int SLOTS = 10;   // 9 hotbar + offhand
    /** Per slot: u0,v0,u1,v1, su0,sv0,su1,sv1, damage. */
    private final float[] rec = new float[SLOTS * 9];
    /** Per slot: kind, count, atlas. */
    private final int[] ints = new int[SLOTS * 3];
    private final int[] tintTop = new int[SLOTS];
    private final int[] tintSide = new int[SLOTS];
    private final Map<Item, Icon> icons = new HashMap<>();
    private final ItemStackRenderState renderState = new ItemStackRenderState();
    private final RandomSource random = RandomSource.create();
    private Object atlasIdentity;

    private record Icon(int kind, int atlas, float[] top, float[] side, int tintTop, int tintSide) {}

    public void frame(Minecraft mc, Bridge bridge) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return;
        Object identity = mc.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.ITEMS);
        if (identity != atlasIdentity) { icons.clear(); atlasIdentity = identity; }

        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = i < 9 ? p.getInventory().getItem(i) : p.getOffhandItem();
            fill(mc, i, stack);
        }
        Bridge.HudState h = new Bridge.HudState();
        h.selected = p.getInventory().getSelectedSlot();
        h.gameMode = mc.gameMode.getPlayerMode().getId();
        h.health = p.getHealth();
        h.maxHealth = p.getMaxHealth();
        h.absorption = p.getAbsorptionAmount();
        h.food = p.getFoodData().getFoodLevel();
        h.armor = p.getArmorValue();
        h.xpLevel = p.experienceLevel;
        h.xpProgress = p.experienceProgress;
        h.air = p.getAirSupply();
        h.maxAir = p.getMaxAirSupply();
        bridge.writeHudTable(System.currentTimeMillis(), h, SLOTS, rec, ints, tintTop, tintSide);
    }

    private void fill(Minecraft mc, int slot, ItemStack stack) {
        int r = slot * 9, k = slot * 3;
        java.util.Arrays.fill(rec, r, r + 9, 0f);
        ints[k] = 0; ints[k + 1] = 0; ints[k + 2] = 0;
        tintTop[slot] = 0; tintSide[slot] = 0;
        if (stack.isEmpty()) return;
        Icon icon = icons.computeIfAbsent(stack.getItem(), it -> iconFor(mc, stack));
        ints[k] = icon.kind();
        ints[k + 1] = stack.getCount();
        ints[k + 2] = icon.atlas();
        System.arraycopy(icon.top(), 0, rec, r, 4);
        if (icon.side() != null) System.arraycopy(icon.side(), 0, rec, r + 4, 4);
        rec[r + 8] = stack.isDamageableItem() && stack.isDamaged()
                ? 1f - (float) stack.getDamageValue() / stack.getMaxDamage() : -1f;
        tintTop[slot] = icon.tintTop();
        tintSide[slot] = icon.tintSide();
    }

    private Icon iconFor(Minecraft mc, ItemStack stack) {
        if (stack.getItem() instanceof BlockItem bi) {
            BlockState state = bi.getBlock().defaultBlockState();
            if (state.getRenderShape() == RenderShape.MODEL
                    && Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) {
                Icon cube = cubeIcon(mc, state);
                if (cube != null) return cube;
            }
        }
        // The sprite Minecraft's GUI item model would show.
        mc.getItemModelResolver().updateForTopItem(renderState, stack, ItemDisplayContext.GUI, mc.level, null, 0);
        random.setSeed(0);
        var material = renderState.pickParticleMaterial(random);
        if (material == null) return new Icon(0, 0, new float[4], null, 0, 0);
        TextureAtlasSprite s = material.sprite();
        int atlas = s.atlasLocation().equals(TextureAtlas.LOCATION_ITEMS) ? 1 : 0;
        return new Icon(1, atlas, rect(s), null, 0xFFFFFFFF, 0xFFFFFFFF);
    }

    /** Top and side textures (and tints) of a full-cube block's model, for the isometric icon. */
    private Icon cubeIcon(Minecraft mc, BlockState state) {
        var model = mc.getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        random.setSeed(42);
        model.collectParts(random, parts);
        BakedQuad top = firstQuad(parts, Direction.UP), side = firstQuad(parts, Direction.NORTH);
        if (top == null || side == null) return null;
        return new Icon(2, 0, rect(top.materialInfo().sprite()), rect(side.materialInfo().sprite()),
                tint(mc, state, top), tint(mc, state, side));
    }

    private static BakedQuad firstQuad(List<BlockStateModelPart> parts, Direction d) {
        for (var part : parts) {
            var quads = part.getQuads(d);
            if (!quads.isEmpty()) return quads.getFirst();
        }
        return null;
    }

    /** Minecraft's tint for a tinted quad (grass top, leaves), as rgba with r in the low byte; else white. */
    private static int tint(Minecraft mc, BlockState state, BakedQuad q) {
        if (!q.materialInfo().isTinted()) return 0xFFFFFFFF;
        var src = mc.getBlockColors().getTintSource(state, q.materialInfo().tintIndex());
        if (src == null) return 0xFFFFFFFF;
        int argb = src.color(state);
        return 0xFF000000 | (argb & 0xFF) << 16 | (argb & 0xFF00) | (argb >> 16 & 0xFF);
    }

    private static float[] rect(TextureAtlasSprite s) {
        return new float[] {s.getU0(), s.getV0(), s.getU1(), s.getV1()};
    }
}
