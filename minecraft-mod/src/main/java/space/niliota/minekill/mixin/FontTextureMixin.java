package space.niliota.minekill.mixin;

import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.renderpearl.api.textures.GpuTexture;
import space.niliota.minekill.gui.GuiTextures;
import net.minecraft.client.gui.font.FontTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mirrors every glyph Minecraft places on a font sheet. Verified in the 26.3 bytecode:
 * {@code FontTexture.add(GlyphInfo, GlyphBitmap)} reserves a slot in its 256x256 sheet and calls
 * {@code bitmap.upload(slot.x, slot.y, getTexture())} once. This keeps that upload and also copies
 * the glyph's CPU pixels into ULTRAKILL's copy of the sheet (GuiTextures).
 */
@Mixin(FontTexture.class)
public abstract class FontTextureMixin {
    @Redirect(method = "add", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/font/GlyphBitmap;upload(IILcom/mojang/renderpearl/api/textures/GpuTexture;)V"))
    private void minekill$mirrorGlyph(GlyphBitmap bitmap, int x, int y, GpuTexture texture) {
        bitmap.upload(x, y, texture);
        GuiTextures.onGlyphUpload(bitmap, x, y, texture);
    }
}
