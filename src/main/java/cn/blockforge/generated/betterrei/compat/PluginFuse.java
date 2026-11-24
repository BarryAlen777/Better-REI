package cn.blockforge.generated.betterrei.compat;

import me.shedaniel.rei.api.common.plugins.REIPlugin;
import me.shedaniel.rei.api.common.plugins.REIPluginProvider;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

public final class PluginFuse {
    private static final Logger LOGGER = LoggerFactory.getLogger("better_rei");
    private static final Set<FailureKey> WARNED = ConcurrentHashMap.newKeySet();

    private record FailureKey(String phase, String plugin) {}

    private PluginFuse() {}

    /** Consume lazy provider collections here so their iterator cannot abort REI's outer loop. */
    public static Collection<? extends REIPlugin<?>> plugins(REIPluginProvider<?> provider) {
        List<REIPlugin<?>> plugins = new ArrayList<>();
        try {
            for (Object candidate : provider.provide()) {
                if (candidate instanceof REIPlugin<?> plugin) {
                    plugins.add(plugin);
                } else {
                    warnOnce("读取插件列表", name(provider), "跳过无效的插件条目", null);
                }
            }
        } catch (Throwable failure) {
            propagateIfInterruptedOrFatal(failure);
            warnOnce("读取插件列表", name(provider), "跳过该提供方未能读取的条目，继续读取其他插件", failure);
        }
        return plugins;
    }

    public static String name(REIPluginProvider<?> provider) {
        try {
            return Objects.requireNonNull(provider.getPluginProviderName(), "getPluginProviderName returned null");
        } catch (Throwable failure) {
            propagateIfInterruptedOrFatal(failure);
            String fallback = provider.getClass().getName();
            warnOnce("读取插件名称", fallback, "改用类名", failure);
            return fallback;
        }
    }

    public static double priority(REIPlugin<?> plugin) {
        try {
            return plugin.getPriority();
        } catch (Throwable failure) {
            propagateIfInterruptedOrFatal(failure);
            warnOnce("读取插件优先级", plugin.getClass().getName(), "本次排序使用 REI 的默认优先级", failure);
            return 0.0;
        }
    }

    public static void discoveryFailed(List<?> modIds, Class<?> candidate, Throwable failure) {
        if (failure != null) {
            propagateIfInterruptedOrFatal(failure);
        }
        warnOnce("发现插件", candidate.getName() + " " + modIds,
                "跳过该插件，继续发现其他插件", failure);
    }

    private static void propagateIfInterruptedOrFatal(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) ExceptionUtils.rethrow(failure);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                ExceptionUtils.rethrow(failure);
            }
            if (cause instanceof CancellationException || cause instanceof VirtualMachineError || cause instanceof ThreadDeath) {
                ExceptionUtils.rethrow(failure);
            }
        }
    }

    private static void warnOnce(String phase, String plugin, String action, Throwable failure) {
        if (WARNED.add(new FailureKey(phase, plugin))) {
            LOGGER.warn("Better REI 插件保险丝 · {} · {} · {}", phase, plugin, action, failure);
        }
    }
}
