package space.niliota.minekill.mixin;

import java.util.Map;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Sampler name -> TextureBinding(Identifier location, ...); TextureBinding is package-private. */
@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
    @Accessor("textures")
    Map<String, Object> minekill$textures();

    @Accessor("textureTransform")
    net.minecraft.client.renderer.rendertype.TextureTransform minekill$textureTransform();
}
