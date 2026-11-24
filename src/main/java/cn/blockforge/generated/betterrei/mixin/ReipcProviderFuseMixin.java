package cn.blockforge.generated.betterrei.mixin;

import cn.blockforge.generated.betterrei.compat.PluginFuse;
import cn.blockforge.generated.betterrei.perf.BetterReiVersionGuard;
import me.shedaniel.rei.api.common.plugins.REIPlugin;
import me.shedaniel.rei.api.common.plugins.REIPluginProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;

/** REIPC groups all JEI plugins under one provider. Protect each child before the list is combined. */
@Pseudo
@Mixin(targets = "me.shedaniel.rei.jeicompat.JEIPluginDetectorForge", remap = false)
abstract class ReipcProviderFuseMixin {
    @Redirect(method = "lambda$provide$4(Lme/shedaniel/rei/api/common/plugins/REIPluginProvider;)Ljava/util/stream/Stream;",
            at = @At(value = "INVOKE", target = "Lme/shedaniel/rei/api/common/plugins/REIPluginProvider;provide()Ljava/util/Collection;"),
            require = 0)
    private static Collection<? extends REIPlugin<?>> better_rei$compatPlugin(REIPluginProvider<?> provider) {
        return BetterReiVersionGuard.matches() ? PluginFuse.plugins(provider) : provider.provide();
    }
}
