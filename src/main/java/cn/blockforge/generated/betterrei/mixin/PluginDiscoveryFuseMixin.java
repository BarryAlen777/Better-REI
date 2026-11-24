package cn.blockforge.generated.betterrei.mixin;

import cn.blockforge.generated.betterrei.compat.PluginFuse;
import cn.blockforge.generated.betterrei.perf.BetterReiVersionGuard;
import me.shedaniel.rei.api.common.plugins.REIPluginProvider;
import me.shedaniel.rei.forge.AnnotationUtils;
import org.apache.logging.log4j.util.TriConsumer;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Mixin(value = AnnotationUtils.class, remap = false)
abstract class PluginDiscoveryFuseMixin {
    // REI catches class-loading and constructor failures. A failed constructor returns null,
    // and the callback itself runs outside REI's per-candidate protection.
    @Redirect(method = "scanAnnotation(Lorg/objectweb/asm/Type;Ljava/util/function/Predicate;Lorg/apache/logging/log4j/util/TriConsumer;)V",
            at = @At(value = "INVOKE", target = "Lorg/apache/logging/log4j/util/TriConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"),
            require = 0)
    @SuppressWarnings("unchecked")
    private static void better_rei$discover(TriConsumer<List<String>, Supplier<Object>, Class<?>> callback,
                                            Object modIds, Object factory, Object pluginClass,
                                            Type annotationType, Predicate<Class<?>> filter,
                                            TriConsumer<List<String>, Supplier<Object>, Class<?>> originalCallback) {
        List<String> ids = (List<String>) modIds;
        Supplier<Object> supplier = (Supplier<Object>) factory;
        Class<?> candidate = (Class<?>) pluginClass;
        String annotation = annotationType.getClassName();
        boolean nativeRei = annotation.startsWith("me.shedaniel.rei.forge.REIPlugin");
        if ((!nativeRei && !annotation.equals("mezz.jei.api.JeiPlugin")) || !BetterReiVersionGuard.matches()) {
            callback.accept(ids, supplier, candidate);
            return;
        }

        try {
            // REIPC must be able to ignore a JEI plugin before constructing it.
            if (nativeRei && REIPluginProvider.class.isAssignableFrom(candidate)) {
                Object plugin = supplier.get();
                if (plugin == null) {
                    PluginFuse.discoveryFailed(ids, candidate, null);
                    return;
                }
                callback.accept(ids, () -> plugin, candidate);
            } else {
                callback.accept(ids, supplier, candidate);
            }
        } catch (Throwable failure) {
            PluginFuse.discoveryFailed(ids, candidate, failure);
        }
    }
}
