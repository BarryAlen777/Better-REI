package cn.blockforge.generated.betterrei.tree;

import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import me.shedaniel.rei.impl.client.registry.display.DisplayCache;
import me.shedaniel.rei.impl.client.registry.display.DisplayRegistryImpl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads REI's own display posting index (the same cache the vanilla
 * "view recipes" path uses) to answer "which displays produce / consume this
 * stack" in O(hits) instead of scanning every display.
 *
 * Extra structures maintained per registry reload "generation":
 * - {@link #currentOrder()}: first-registered position of every display, so
 *   the tree can break ties deterministically (REI's posting sets are
 *   identity-hashed -> their iteration order changes every session; audit
 *   r7 P2-2).
 * - {@link #ensureFallback}: a local hashFuzzy -> displays index built once
 *   per generation for the case where the player turned REI's own display
 *   cache off — previously every tree node then cost a full scan of all
 *   displays (audit r7 P2-4).
 *
 * Generation is bumped by the reload mixins; both caches additionally
 * self-heal on a display-count change (dynamic adds without a reload).
 * All lookups run on the client thread.
 */
public final class TreeIndex {

    private TreeIndex() {}

    // ------------------------------------------------------------ generation

    private static final AtomicLong RELOAD_GEN = new AtomicLong();

    /** Called from the display-registry reload mixin when REI rebuilds. */
    public static void onRegistryReload() {
        RELOAD_GEN.incrementAndGet();
        orderSnapshot = null;
        fallbackIndex = null;
    }

    public static long generation() {
        return RELOAD_GEN.get();
    }

    // ---------------------------------------------------------------- cache

    /**
     * REI only fills its display holder once the registry has been built; before
     * that (or on a repacked REI) every dereference below threw an NPE that
     * surfaced as "recipe tree failed to open". Callers now treat a missing
     * holder as "no data yet" instead of an error.
     */
    public static boolean hasDisplayHolder() {
        DisplayRegistry registry = DisplayRegistry.getInstance();
        return registry instanceof DisplayRegistryImpl impl && impl.displaysHolder() != null;
    }

    private static DisplayCache cache() {
        return ((DisplayRegistryImpl) DisplayRegistry.getInstance()).displaysHolder().cache();
    }

    private static Map<CategoryIdentifier<?>, List<Display>> allDisplays() {
        return ((DisplayRegistryImpl) DisplayRegistry.getInstance()).getAll();
    }

    // -------------------------------------------------- registration order

    private record OrderSnapshot(long generation, int displayCount, IdentityHashMap<Display, Integer> index) {}

    private static volatile OrderSnapshot orderSnapshot;

    private static int count(Map<CategoryIdentifier<?>, List<Display>> all) {
        int n = 0;
        for (List<Display> list : all.values()) n += list.size();
        return n;
    }

    /** Identity map of display -> first-registered position (stable in-session). */
    public static IdentityHashMap<Display, Integer> currentOrder() {
        if (!hasDisplayHolder()) return new IdentityHashMap<>();
        Map<CategoryIdentifier<?>, List<Display>> all = allDisplays();
        long gen = RELOAD_GEN.get();
        int total = count(all);
        OrderSnapshot snap = orderSnapshot;
        if (snap != null && snap.generation() == gen && snap.displayCount() == total) {
            return snap.index();
        }
        IdentityHashMap<Display, Integer> index = new IdentityHashMap<>(Math.max(16, total * 2));
        int i = 0;
        for (List<Display> list : all.values()) {
            for (Display display : list) {
                if (!index.containsKey(display)) {
                    index.put(display, i++);
                }
            }
        }
        orderSnapshot = new OrderSnapshot(gen, total, index);
        return index;
    }

    // ----------------------------------------------------- fallback index

    private static final class FallbackIndex {
        final long generation;
        final int displayCount;
        final Map<Long, List<Display>> byInput = new HashMap<>();
        final Map<Long, List<Display>> byOutput = new HashMap<>();

        FallbackIndex(long generation, int displayCount) {
            this.generation = generation;
            this.displayCount = displayCount;
        }
    }

    private static volatile FallbackIndex fallbackIndex;

    /**
     * hashFuzzy -> displays posting index mirroring REI's own DisplayCache
     * keying (which also pairs hashFuzzy with equalsFuzzy), rebuilt once per
     * generation whenever REI's internal cache is disabled.
     */
    private static FallbackIndex ensureFallback() {
        Map<CategoryIdentifier<?>, List<Display>> all = allDisplays();
        long gen = RELOAD_GEN.get();
        int total = count(all);
        FallbackIndex idx = fallbackIndex;
        if (idx != null && idx.generation == gen && idx.displayCount == total) {
            return idx;
        }
        idx = new FallbackIndex(gen, total);
        for (List<Display> list : all.values()) {
            for (Display display : list) {
                for (EntryIngredient ingredient : display.getInputEntries()) {
                    for (EntryStack<?> stack : ingredient) {
                        idx.byInput.computeIfAbsent(EntryStacks.hashFuzzy(stack), k -> new ArrayList<>(2)).add(display);
                    }
                }
                for (EntryIngredient ingredient : display.getOutputEntries()) {
                    for (EntryStack<?> stack : ingredient) {
                        idx.byOutput.computeIfAbsent(EntryStacks.hashFuzzy(stack), k -> new ArrayList<>(2)).add(display);
                    }
                }
            }
        }
        fallbackIndex = idx;
        return idx;
    }

    private static Collection<Display> fallbackLookup(EntryStack<?> stack, boolean output) {
        FallbackIndex idx = ensureFallback();
        Map<Long, List<Display>> postings = output ? idx.byOutput : idx.byInput;
        Set<Display> result = Collections.newSetFromMap(new IdentityHashMap<>());
        collectFallbackMatches(postings, stack, output, result);
        if (result.isEmpty()) {
            EntryStack<?> wildcard = stack.wildcard();
            if (!EntryStacks.equalsFuzzy(wildcard, stack)) {
                collectFallbackMatches(postings, wildcard, output, result);
            }
        }
        return result;
    }

    private static void collectFallbackMatches(Map<Long, List<Display>> postings, EntryStack<?> query,
                                               boolean output, Set<Display> result) {
        List<Display> candidates = postings.get(EntryStacks.hashFuzzy(query));
        if (candidates == null) return;
        for (Display display : candidates) {
            // The hash only narrows the search. Match against the same query that found the bucket.
            if (contains(display, query, output)) result.add(display);
        }
    }

    // --------------------------------------------------------------- lookups

    /** Displays whose OUTPUT contains this stack (i.e. recipes that make it). */
    public static Collection<Display> producing(EntryStack<?> stack) {
        return lookup(stack, true);
    }

    /** Displays whose INPUT contains this stack (i.e. recipes that use it). */
    public static Collection<Display> consuming(EntryStack<?> stack) {
        return lookup(stack, false);
    }

    private static Collection<Display> lookup(EntryStack<?> stack, boolean output) {
        if (!hasDisplayHolder()) return Collections.emptySet();
        DisplayCache cache = cache();
        if (!cache.doesCache()) {
            // P2-4: REI's own index is switched off -> use our once-per-
            // generation local postings instead of scanning all displays
            // per tree node.
            return fallbackLookup(stack, output);
        }
        Set<Display> result = Collections.newSetFromMap(new IdentityHashMap<>());
        result.addAll(output ? cache.getDisplaysByOutput(stack) : cache.getDisplaysByInput(stack));
        if (result.isEmpty()) {
            EntryStack<?> wildcard = stack.wildcard();
            if (!EntryStacks.equalsFuzzy(wildcard, stack)) {
                result.addAll(output ? cache.getDisplaysByOutput(wildcard) : cache.getDisplaysByInput(wildcard));
            }
        }
        Set<Display> notCached = cache.getDisplaysNotCached();
        if (!notCached.isEmpty()) {
            for (Display display : notCached) {
                if (contains(display, stack, output)) {
                    result.add(display);
                }
            }
        }
        return result;
    }

    public static boolean contains(Display display, EntryStack<?> stack, boolean output) {
        for (EntryIngredient ingredient : output ? display.getOutputEntries() : display.getInputEntries()) {
            for (EntryStack<?> entry : ingredient) {
                if (EntryStacks.equalsFuzzy(entry, stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Best-effort visible amount for an entry icon. */
    public static long amountOf(EntryStack<?> stack) {
        Object value = stack.getValue();
        if (value instanceof net.minecraft.world.item.ItemStack itemStack) {
            return Math.max(1L, itemStack.getCount());
        }
        if (value instanceof dev.architectury.fluid.FluidStack fluidStack) {
            return Math.max(1L, (long) fluidStack.getAmount());
        }
        return 1L;
    }
}
