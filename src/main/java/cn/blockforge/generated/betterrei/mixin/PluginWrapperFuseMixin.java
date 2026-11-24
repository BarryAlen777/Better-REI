package cn.blockforge.generated.betterrei.mixin;

import cn.blockforge.generated.betterrei.compat.PluginFuse;
import cn.blockforge.generated.betterrei.perf.BetterReiVersionGuard;
import me.shedaniel.rei.api.common.plugins.REIPlugin;
import me.shedaniel.rei.api.common.plugins.REIPluginProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "me.shedaniel.rei.impl.common.plugins.PluginManagerImpl$PluginWrapper", remap = false)
abstract class PluginWrapperFuseMixin {
    @Shadow @Final private REIPlugin<?> plugin;
    @Shadow @Final private REIPluginProvider<?> provider;

    @Redirect(method = "getPriority()D",
            at = @At(value = "INVOKE", target = "Lme/shedaniel/rei/api/common/plugins/REIPlugin;getPriority()D"),
            require = 0)
    private double better_rei$priority(REIPlugin<?> plugin) {
        return BetterReiVersionGuard.matches() ? PluginFuse.priority(plugin) : plugin.getPriority();
    }

    @Inject(method = "getPluginProviderName()Ljava/lang/String;", at = @At("HEAD"), cancellable = true, require = 0)
    private void better_rei$displayName(CallbackInfoReturnable<String> result) {
        if (BetterReiVersionGuard.matches()) {
            // This wrapper already holds a plugin from the provider. Don't call provide() a
            // second time just to build the name, especially while reporting another error.
            String providerName = PluginFuse.name(provider);
            String pluginName = provider == plugin ? providerName : PluginFuse.name(plugin);
            result.setReturnValue(pluginName.equals(providerName) ? providerName : pluginName + " of " + providerName);
        }
    }
}
