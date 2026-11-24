package cn.blockforge.generated.betterrei.compat;

import cn.blockforge.generated.betterrei.perf.BetterReiVersionGuard;
import me.shedaniel.rei.api.common.plugins.REIPlugin;
import me.shedaniel.rei.api.common.plugins.REIPluginProvider;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.util.TriConsumer;
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
import java.util.function.Supplier;

public final class PluginFuse {
    private static final Logger LOGGER = LoggerFactory.getLogger("better_rei");
    private static final Set<FailureKey> WARNED = ConcurrentHashMap.newKeySet();

    private record FailureKey(String phase, String plugin) {}

    private PluginFuse() {}

    /**
     * 插件发现阶段的保险丝。由 {@code PluginDiscoveryFuseMixin} 在 REI 的
     * {@code AnnotationUtils.scanAnnotation} 把候选插件交给回调之前调用。
     *
     * <p>REI 生成候选插件的方式是“惰性构造器”：真正的 {@code new} 发生在回调里
     * （{@code plugin.get()}），而这一次构造位于 REI 的逐条保护之外——某个插件类
     * 构造时抛异常，整条插件列表就中断了。这里提前在同一处 try 里把它构造出来：
     * 成功就交回同一个实例，失败就记一行日志并跳过这一个，其余插件照常注册。
     *
     * <p>逻辑放在这个普通类而不是 mixin 里，是因为 mixin 类的方法体不能引用
     * ASM 这类只存在于启动器类路径上的类，否则 Mixin 预处理时会解析不到 ClassInfo，
     * 直接把整个 REI 启动打崩（见 {@code PluginDiscoveryFuseMixin} 的说明）。
     */
    @SuppressWarnings("unchecked")
    public static void dispatch(TriConsumer<List<String>, Supplier<Object>, Class<?>> callback,
                               Object modIds, Object factory, Object pluginClass) {
        List<String> ids = modIds instanceof List<?> list ? (List<String>) list : Collections.emptyList();
        Supplier<Object> supplier = factory instanceof Supplier<?> s ? (Supplier<Object>) s : null;
        Class<?> candidate = pluginClass instanceof Class<?> clazz ? clazz : null;
        if (supplier == null || candidate == null || !REIPluginProvider.class.isAssignableFrom(candidate)
                || !BetterReiVersionGuard.matches()) {
            callback.accept(ids, supplier, candidate);
            return;
        }
        try {
            Object plugin = supplier.get();
            if (plugin == null) {
                discoveryFailed(ids, candidate, null);
                return;
            }
            callback.accept(ids, () -> plugin, candidate);
        } catch (Throwable failure) {
            discoveryFailed(ids, candidate, failure);
        }
    }

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
