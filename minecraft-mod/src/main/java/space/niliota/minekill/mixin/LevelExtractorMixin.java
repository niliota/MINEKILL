package space.niliota.minekill.mixin;

import space.niliota.minekill.render.WorldMesher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change and chunk load reaches Minecraft's renderer through
 * {@code LevelExtractor.setSectionDirty(int, int, int, boolean)} (private in 26.3; the public
 * overloads and setBlockDirty/setBlocksDirty all funnel into it). The same 16^3 section is
 * then re-meshed for ULTRAKILL. {@code playerChanged} means the player just placed or broke a
 * block there, which jumps the queue so the change shows immediately.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void minekill$sectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged,
                                        CallbackInfo ci) {
        WorldMesher.markDirty(sectionX, sectionY, sectionZ, playerChanged);
    }
}
