package cn.blockforge.generated.betterrei.mixin;

import cn.blockforge.generated.betterrei.compat.RepairDisplayPatch;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Conditional mixin registration (audit r7 P2-1, probed properly since r13
 * P2-3, made classloader-proof in r15).
 *
 * Better REI's mixins target REI's impl classes (ViewsImpl,
 * EntryListSearchManager, ...). When REI is not installed those targets do
 * not exist, and Mixin reports a load failure for each one on every launch
 * run — harmless with the config's {@code required:false}, but noisy enough
 * that players think the mod itself is broken.
 *
 * The mixins are therefore not listed in {@code better_rei.mixins.json};
 * this plugin contributes them through {@link #getMixins()} only when REI is
 * actually installed. No REI -> no mixins -> no launch-log noise; the hotkey
 * still exists and explains the situation in chat.
 *
 * Failure-safety: every uncertainty resolves to "apply the mixins", so a
 * mis-detection can only fall back to the old noise, never silently disable
 * an optimization.
 *
 * Classloading (audit r15 P2-2): this class references <em>no</em> loader type
 * any more - only JDK, ASM and Mixin API classes, which Mixin itself provides
 * before it instantiates the plugin. Mixin builds the plugin from its
 * transformer classloader while the game is still being prepared; a direct
 * {@code import net.minecraftforge.fml.loading.FMLLoader} there would make the
 * plugin class fail to link if that class is not visible from the classloader
 * Mixin happens to use (a wrapped/hybrid launcher, a mixin service owned by
 * another host, ...). A {@code LinkageError} at that point happens before
 * {@link #getMixins()} can catch anything, so Mixin drops the whole config and
 * all four optimizations silently vanish. Forge is therefore reached by name
 * via reflection, so "loader classes invisible" degrades to "unknown -> apply
 * the mixins" instead of "no optimizations at all".
 */
public class BetterReiMixinPlugin implements IMixinConfigPlugin {

    private static final String REI_MOD_ID = "roughlyenoughitems";
    private static final String OUR_MOD_ID = "better_rei";

    /** Resolved reflectively on purpose; see the class comment (r15). */
    private static final String FML_LOADER_CLASS = "net.minecraftforge.fml.loading.FMLLoader";
    private static final String GET_LOADING_MOD_LIST = "getLoadingModList";
    private static final String GET_MOD_FILE_BY_ID = "getModFileById";

    /** mixin simple names, resolved against the "package" field in the json */
    private static final List<String> MIXINS = List.of(
            "EntryListSortCacheMixin",
            "EntryRegistryCacheClearMixin",
            "DisplayReloadTrackerMixin",
            "ViewsImplFastLookupMixin",
            "RepairDisplayGuardMixin",
            "PluginDiscoveryFuseMixin",
            "PluginManagerFuseMixin",
            "PluginWrapperFuseMixin",
            "ReipcProviderFuseMixin");

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        try {
            return isDedicatedServer() || !reiPresent() ? Collections.emptyList() : MIXINS;
        } catch (Throwable t) {
            // A broken probe must never cost the optimizations (audit r12 P2-1):
            // worst case the mixins are registered and Mixin skips the ones it
            // cannot apply, exactly like before this plugin existed.
            return MIXINS;
        }
    }

    private static boolean isDedicatedServer() {
        try {
            Class<?> loader = Class.forName(FML_LOADER_CLASS, false, BetterReiMixinPlugin.class.getClassLoader());
            return loader.getMethod("getDist").invoke(null) instanceof Enum<?> dist
                    && dist.name().equals("DEDICATED_SERVER");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * Is REI installed? True unless a probe can prove it is not (audit r12
     * P2-3). The old check read {@code ModList}, which Forge only fills long
     * after mixin configs are prepared, so it could never answer "no" and the
     * conditional loading was effectively always on.
     */
    private static boolean reiPresent() {
        Boolean fromModList = reiInLoadingModList();
        if (fromModList != null) {
            return fromModList;
        }
        Boolean fromClasspath = reiOnClassPath();
        return fromClasspath == null || fromClasspath;
    }

    /**
     * Reads Forge's <em>loading</em> mod list, which is built during the mod
     * directory scan — before any mixin config is prepared. Our own mod file
     * doubles as the control: only when the scan already saw Better REI can a
     * missing RoughlyEnoughItems be taken as final. Returns {@code null} when
     * the list is unreadable, the loader classes are invisible to this
     * classloader, or the scan is not finished — the caller then falls back
     * instead of guessing.
     *
     * <p>Reflective on purpose: this runs while Mixin is still instantiating
     * configs, so a hard type reference could take the whole plugin (and every
     * optimization with it) down at link time.
     */
    private static Boolean reiInLoadingModList() {
        try {
            ClassLoader loader = BetterReiMixinPlugin.class.getClassLoader();
            if (loader == null) return null;
            Class<?> fmlLoader = Class.forName(FML_LOADER_CLASS, false, loader);
            Object loading = fmlLoader.getMethod(GET_LOADING_MOD_LIST).invoke(null);
            if (loading == null) return null;
            // Looked up on the runtime class so a subclass/shaded copy still works.
            Method byId = loading.getClass().getMethod(GET_MOD_FILE_BY_ID, String.class);
            if (byId.invoke(loading, REI_MOD_ID) != null) {
                return Boolean.TRUE;
            }
            return byId.invoke(loading, OUR_MOD_ID) == null ? null : Boolean.FALSE;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Fallback probe: are REI's class files on the transforming classpath?
     * Returns {@code null} when that cannot be decided — the classloader might
     * not serve mod jars yet, in which case "not found" must not count as
     * "not installed".
     */
    private static Boolean reiOnClassPath() {
        ClassLoader loader = BetterReiMixinPlugin.class.getClassLoader();
        if (loader == null) return null;
        if (loader.getResource("me/shedaniel/rei/impl/client/view/ViewsImpl.class") != null) {
            return Boolean.TRUE;
        }
        // Our own class file is served by this very loader, so the loader does
        // see the mod jars: REI's absence is real rather than invisible.
        return loader.getResource("cn/blockforge/generated/betterrei/mixin/BetterReiMixinPlugin.class") == null
                ? null : Boolean.FALSE;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (targetClassName.equals(RepairDisplayPatch.TARGET)
                && mixinClassName.equals("cn.blockforge.generated.betterrei.mixin.RepairDisplayGuardMixin")) {
            RepairDisplayPatch.apply(targetClass);
        }
    }
}
