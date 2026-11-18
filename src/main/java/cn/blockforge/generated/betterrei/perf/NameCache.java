package cn.blockforge.generated.betterrei.perf;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.shedaniel.rei.impl.common.util.HashedEntryStackWrapper;

/**
 * Entry display-name cache for the optimized entry-list sort.
 *
 * REI re-wraps every search result on each keystroke: the entry registry
 * hands the async search its pre-normalized HNEntryStackWrapper list, and
 * the search pipeline then applies HashedEntryStackWrapper::normalize —
 * and HNEntryStackWrapper.normalize() *unconditionally* allocates a fresh
 * HashedEntryStackWrapper (the subclass never returns {@code this}; only
 * the base-class version has the "unchanged hash returns this" shortcut).
 * An identity-keyed cache therefore misses on every single search round
 * and keeps piling up dead wrapper instances (pinning their EntryStacks
 * against the GC) until the next registry reload.
 *
 * This cache is keyed by the wrapper's stable 64-bit exact hash instead:
 * two wrappers describing the same normalized entry share a key, so each
 * distinct entry pays the asFormatStrippedText() extraction exactly once
 * per registry reload. Primitive long keys (fastutil, shipped with
 * Minecraft) mean zero boxing *and* the cache never holds on to wrapper
 * or EntryStack instances. Dropped on every registry reload
 * (EntryRegistryCacheClearMixin), so names can never go stale.
 * Accessed from the async search thread -> synchronized wrapper.
 */
public final class NameCache {
    private static final Long2ObjectMap<String> NAMES =
            Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>(256));

    private NameCache() {}

    /** Cached display name for this wrapper, extracting it on first use. */
    public static String getOrCompute(HashedEntryStackWrapper wrapper) {
        return NAMES.computeIfAbsent(wrapper.hashExact(),
                h -> wrapper.unwrap().asFormatStrippedText().getString());
    }

    public static void clear() {
        NAMES.clear();
    }
}
