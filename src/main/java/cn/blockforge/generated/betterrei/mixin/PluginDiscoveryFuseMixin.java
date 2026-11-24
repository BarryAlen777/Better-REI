package cn.blockforge.generated.betterrei.mixin;

import cn.blockforge.generated.betterrei.compat.PluginFuse;
import org.apache.logging.log4j.util.TriConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;
import java.util.function.Supplier;

/**
 * 在 REI 把“发现到的插件”交给回调之前插一道保险丝。
 *
 * <p><b>这个类里绝对不能出现 {@code org.objectweb.asm.*}，连参数类型都不行。</b>
 * Mixin 预处理 mixin 方法时，会为方法体里每一次方法调用的 owner（以及注入方法的
 * 参数类型）向游戏的类服务要 ClassInfo；ASM 只在启动器的启动类路径上，不在游戏
 * 的模块层里，于是 ClassInfo 拿到 null，抛
 * {@code ClassMetadataNotFoundException: org.objectweb.asm.Type}，整个 REI 构造失败，
 * 整包拒绝加载（1.5.0 的启动崩溃就是这么来的）。
 *
 * <p>所以这里只留一个“原样透传”的注入点：真正的判断、实例化和兜底都放进
 * {@link PluginFuse#dispatch}，那是个普通类，Mixin 不会去解析它的方法体。
 */
@Mixin(value = me.shedaniel.rei.forge.AnnotationUtils.class, remap = false)
abstract class PluginDiscoveryFuseMixin {
    // REI 会把构造失败的插件吞成 null，回调本身也在 REI 的逐条保护之外。
    @Redirect(method = "scanAnnotation(Lorg/objectweb/asm/Type;Ljava/util/function/Predicate;Lorg/apache/logging/log4j/util/TriConsumer;)V",
            at = @At(value = "INVOKE", target = "Lorg/apache/logging/log4j/util/TriConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"),
            require = 0)
    private static void better_rei$discover(TriConsumer<List<String>, Supplier<Object>, Class<?>> callback,
                                            Object modIds, Object factory, Object pluginClass) {
        PluginFuse.dispatch(callback, modIds, factory, pluginClass);
    }
}
