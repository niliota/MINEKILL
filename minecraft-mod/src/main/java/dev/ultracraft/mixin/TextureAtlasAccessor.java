package dev.ultracraft.mixin;

import java.util.List;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** TextureAtlas keeps its size and sprite list private in 26.3; the atlas export needs both. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
    @Accessor("width")
    int ultracraft$width();

    @Accessor("height")
    int ultracraft$height();

    @Accessor("sprites")
    List<TextureAtlasSprite> ultracraft$sprites();
}
