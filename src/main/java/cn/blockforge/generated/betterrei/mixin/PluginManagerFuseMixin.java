package cn.blockforge.generated.betterrei.mixin;

import cn.blockforge.generated.betterrei.compat.PluginFuse;
import cn.blockforge.generated.betterrei.perf.BetterReiVersionGuard;
import com.google.common.base.Function;
import me.shedaniel.rei.api.common.plugins.REIPlugin;
import me.shedaniel.rei.api.common.plugins.REIPluginProvider;
import me.shedaniel.rei.impl.common.plugins.PluginManagerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;

@Mixin(value = PluginManagerImpl.class, remap = false)
abstract class PluginManagerFuseMixin {
    @Redirect(method = "registerPlugin(Lme/shedaniel/rei/api/common/plugins/REIPluginProvider;)V",
            at = @At(value = "INVOKE", target = "Lme/shedaniel/rei/api/common/plugins/REIPluginProvider;getPluginProviderName()Ljava/lang/String;"),
            require = 0)
    private String better_rei$registrationName(REIPluginProvider<?> provider) {
        return BetterReiVersionGuard.matches() ? PluginFuse.name(provider) : provider.getPluginProviderName();
    }

    // pre, reload and post enumerate this lazy list before REI's per-plugin catch.
    @Redirect(method = "lambda$getPluginWrapped$1(Lme/shedaniel/rei/api/common/plugins/REIPluginProvider;)Ljava/lang/Iterable;",
            at = @At(value = "INVOKE", target = "Lme/shedaniel/rei/api/common/plugins/REIPluginProvider;provide()Ljava/util/Collection;"),
            require = 0)
    private static Collection<? extends REIPlugin<?>> better_rei$wrappedPlugins(REIPluginProvider<?> provider) {
        return BetterReiVersionGuard.matches() ? PluginFuse.plugins(provider) : provider.provide();
    }

    @ModifyArg(method = "getPlugins()Lcom/google/common/collect/FluentIterable;",
            at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Iterables;transform(Ljava/lang/Iterable;Lcom/google/common/base/Function;)Ljava/lang/Iterable;"),
            index = 1, require = 0)
    private Function<REIPluginProvider<?>, Collection<? extends REIPlugin<?>>> better_rei$pluginList(
            Function<REIPluginProvider<?>, Collection<? extends REIPlugin<?>>> original) {
        return BetterReiVersionGuard.matches() ? PluginFuse::plugins : original;
    }
}
