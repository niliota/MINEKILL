package dev.ultracraft.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The sprite's source image, held in memory by Minecraft after loading (all animation frames).
 * The atlas export copies from it on the CPU instead of reading the GPU texture back.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("originalImage")
    NativeImage ultracraft$originalImage();
}
