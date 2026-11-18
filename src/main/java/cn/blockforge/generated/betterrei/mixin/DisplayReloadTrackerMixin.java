package cn.blockforge.generated.betterrei.mixin;

import cn.blockforge.generated.betterrei.tree.TreeIndex;
import me.shedaniel.rei.impl.client.registry.display.DisplayRegistryImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bumps the recipe-tree reload generation when REI finishes rebuilding its
 * display registry (audit r7 P2-3): an open RecipeTreeScreen polls
 * TreeIndex.generation() and refreshes itself, and TreeIndex drops its
 * ordering snapshot + fallback posting index together with it.
 *
 * require = 0 like every injector here: if REI renames endReload the tree
 * simply keeps working with its (unchanged) snapshot semantics, nothing can
 * fail at application time, let alone abort launch.
 */
@Mixin(DisplayRegistryImpl.class)
public class DisplayReloadTrackerMixin {

    @Inject(method = "endReload", at = @At("TAIL"), require = 0)
    private void better_rei$treeGeneration(CallbackInfo ci) {
        TreeIndex.onRegistryReload();
    }
}
