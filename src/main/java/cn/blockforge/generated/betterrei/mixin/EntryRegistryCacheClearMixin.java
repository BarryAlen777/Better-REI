package cn.blockforge.generated.betterrei.mixin;

import cn.blockforge.generated.betterrei.perf.NameCache;
import me.shedaniel.rei.impl.common.entry.type.EntryRegistryImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the cached entry names whenever REI rebuilds its entry registry,
 * so cached display names can never go stale across resource reloads.
 * require = 0: if REI ever renames startReload this injector just does
 * nothing (name cache stays, entries stay correct — only the memoization
 * is lost), it never blocks mixin application, let alone launch.
 */
@Mixin(EntryRegistryImpl.class)
public class EntryRegistryCacheClearMixin {

    @Inject(method = "startReload", at = @At("HEAD"), require = 0)
    private void better_rei$clearNameCache(CallbackInfo ci) {
        NameCache.clear();
    }
}
