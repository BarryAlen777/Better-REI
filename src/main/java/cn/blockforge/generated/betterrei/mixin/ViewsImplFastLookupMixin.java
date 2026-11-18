package cn.blockforge.generated.betterrei.mixin;

import cn.blockforge.generated.betterrei.perf.BetterReiConfig;
import cn.blockforge.generated.betterrei.perf.BetterReiVersionGuard;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import me.shedaniel.rei.api.client.config.ConfigObject;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.api.common.util.CollectionUtils;
import me.shedaniel.rei.api.common.util.EntryStacks;
import me.shedaniel.rei.impl.client.registry.display.DisplayCache;
import me.shedaniel.rei.impl.client.registry.display.DisplayRegistryImpl;
import me.shedaniel.rei.impl.client.registry.display.DisplaysHolder;
import me.shedaniel.rei.impl.client.view.ViewsImpl;
import me.shedaniel.rei.impl.common.InternalLogger;
import me.shedaniel.rei.impl.display.DisplaySpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Faster recipes-for / usages-for view building for REI 12.1.
 *
 * Stock _buildMapFor walks every display of every category and probes each
 * one's ingredient stacks against the query. Here REI's own DisplayCache
 * postings first decide which categories actually have hits, and only those
 * categories' display lists are then walked (the walk itself stays
 * per-category on purpose — that is what keeps registry order, visibility
 * handling and merge semantics identical to stock). In a big pack the vast
 * majority of categories have no postings at all and are skipped outright.
 * This is a large constant-factor win, not a complexity-class change
 * (audit r7 P2-6 asked for an honest wording); result semantics —
 * membership, ordering, visibility, merging, logging, including the
 * upstream "only the first queried stack is consulted for cached displays"
 * quirk — are kept identical to the stock implementation.
 *
 * Design notes (crash-safety, per audit r5):
 * - No @Overwrite. A cancellable HEAD injection computes the fast result and
 *   cancels the call; if anything here is inapplicable or throws, stock
 *   ViewsImpl._buildMapFor runs verbatim — the real original method, not a
 *   copied approximation.
 * - require = 0 + config required:false: a renamed/removed target or shadow
 *   only skips this mixin with a log line, it can never abort launch.
 * - BetterReiVersionGuard: on an unverified REI build the fast path never
 *   executes, so semantic drift in same-named methods is impossible.
 * - Target name is stable in the shipped forge jar (not an MC override, so
 *   reobf never renames it).
 */
@Mixin(ViewsImpl.class)
public class ViewsImplFastLookupMixin {

    @Shadow
    private static void mergeAndOptimize(Map<DisplayCategory<?>, Set<Display>> displays, Map<DisplayCategory<?>, List<DisplaySpec>> resultSpec) {
    }

    @Shadow
    private static Map<DisplayCategory<?>, List<DisplaySpec>> sortDisplays(Map<DisplayCategory<?>, List<DisplaySpec>> unsorted) {
        return null;
    }

    @Shadow
    private static <T extends Display> void generateLiveDisplays(DisplayRegistry displayRegistry, DynamicDisplayGenerator<T> generator, ViewSearchBuilder builder, Consumer<T> displayConsumer) {
    }

    @Shadow
    private static <T extends Display> DynamicDisplayGenerator<T> wrapForError(DynamicDisplayGenerator<T> generator) {
        return null;
    }

    @Shadow
    private static <T> boolean isStackWorkStationOfCategory(CategoryRegistry.CategoryConfiguration<?> category, EntryStack<T> stack) {
        return false;
    }

    @Unique
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Display> better_rei$displaysOf(DisplayRegistry displayRegistry, CategoryIdentifier<?> categoryId) {
        return displayRegistry.get((CategoryIdentifier<Display>) (CategoryIdentifier<?>) categoryId);
    }

    @Unique
    private static void better_rei$collectHits(Map<CategoryIdentifier<?>, Set<Display>> hits, Collection<Display> posting) {
        for (Display display : posting) {
            hits.computeIfAbsent(display.getCategoryIdentifier(), k -> Collections.newSetFromMap(new IdentityHashMap<Display, Boolean>())).add(display);
        }
    }

    @Unique
    private static boolean better_rei$hasDisplayHolder() {
        DisplayRegistry registry = DisplayRegistry.getInstance();
        return registry instanceof DisplayRegistryImpl impl && impl.displaysHolder() != null;
    }

    /** Fast indexed scan: consult the display cache postings directly. */
    @Unique
    private static void better_rei$searchIndexed(
            DisplayRegistry displayRegistry, DisplayRegistryImpl dri, DisplaysHolder holder,
            Set<CategoryIdentifier<?>> categories, Set<CategoryIdentifier<?>> filteringCategories,
            List<EntryStack<?>> recipesForStacks, List<EntryStack<?>> usagesForStacks,
            boolean processingVisibility, Map<DisplayCategory<?>, Set<Display>> result,
            boolean addAllForViewedCategories) {
        DisplayCache cache = holder.cache();
        Map<CategoryIdentifier<?>, Set<Display>> hits = new HashMap<>();
        if (!recipesForStacks.isEmpty()) {
            better_rei$collectHits(hits, cache.getDisplaysByOutput(recipesForStacks.get(0)));
        }
        if (!usagesForStacks.isEmpty()) {
            better_rei$collectHits(hits, cache.getDisplaysByInput(usagesForStacks.get(0)));
        }
        Set<Display> notCached = cache.getDisplaysNotCached();
        if (!notCached.isEmpty()) {
            for (Display display : notCached) {
                if ((!recipesForStacks.isEmpty() && ViewsImpl.isRecipesFor(holder, recipesForStacks, display))
                        || (!usagesForStacks.isEmpty() && ViewsImpl.isUsagesFor(holder, usagesForStacks, display))) {
                    better_rei$collectHits(hits, Collections.singletonList(display));
                }
            }
        }
        CategoryRegistry categoryRegistry = CategoryRegistry.getInstance();
        for (CategoryRegistry.CategoryConfiguration<?> configuration : categoryRegistry) {
            if (processingVisibility && categoryRegistry.isCategoryInvisible(configuration.getCategory())) continue;
            CategoryIdentifier<?> categoryId = configuration.getCategoryIdentifier();
            if (!filteringCategories.isEmpty() && !filteringCategories.contains(categoryId)) continue;
            if (categories.contains(categoryId)) {
                if (!addAllForViewedCategories) continue;
                List<Display> displays = better_rei$displaysOf(displayRegistry, categoryId);
                Set<Display> set = new LinkedHashSet<>();
                for (Display display : displays) {
                    if (!processingVisibility || dri.isDisplayVisible(configuration.getCategory(), display)) set.add(display);
                }
                if (!set.isEmpty()) ViewsImpl.getOrPutEmptyLinkedSet(result, configuration.getCategory()).addAll(set);
                continue;
            }
            Set<Display> matched = hits.get(categoryId);
            if (matched == null || matched.isEmpty()) continue;
            List<Display> displays = better_rei$displaysOf(displayRegistry, categoryId);
            if (displays.isEmpty()) continue;
            Set<Display> set = new LinkedHashSet<>();
            for (Display display : displays) {
                if (matched.contains(display) && (!processingVisibility || dri.isDisplayVisible(configuration.getCategory(), display))) {
                    set.add(display);
                }
            }
            if (!set.isEmpty()) ViewsImpl.getOrPutEmptyLinkedSet(result, configuration.getCategory()).addAll(set);
        }
    }

    @Inject(method = "_buildMapFor", at = @At("HEAD"), cancellable = true, require = 0)
    private static void better_rei$tryFastBuildMapFor(ViewSearchBuilder builder,
                                                       CallbackInfoReturnable<Map<DisplayCategory<?>, List<DisplaySpec>>> ci) {
        try {
            // Opt-outs: unverified REI build, config off, nothing indexed to
            // win with, or plugins still reloading — stock code handles all
            // of these exactly right on its own.
            if (!BetterReiVersionGuard.matches()) return;
            if (!BetterReiConfig.fastViewLookupEnabled()) return;
            if (PluginManager.areAnyReloading()) return;
            // audit r10 P3-3: the indexed path dereferences the display holder;
            // while REI has no holder yet (bootstrapping / special builds) only
            // the stock method, which handles a missing holder, can answer.
            if (!better_rei$hasDisplayHolder()) return;
            List<EntryStack<?>> recipesForStacks = builder.getRecipesFor();
            List<EntryStack<?>> usagesForStacks = builder.getUsagesFor();
            if (recipesForStacks.isEmpty() && usagesForStacks.isEmpty()) return;
            ci.setReturnValue(better_rei$buildFast(builder, recipesForStacks, usagesForStacks));
            ci.cancel();
        } catch (Throwable t) {
            BetterReiVersionGuard.warnFallbackOnce("fastViewLookup", t);
            // no cancel -> the untouched stock _buildMapFor runs instead
        }
    }

    @SuppressWarnings("unchecked")
    @Unique
    private static Map<DisplayCategory<?>, List<DisplaySpec>> better_rei$buildFast(
            ViewSearchBuilder builder, List<EntryStack<?>> recipesForStacks, List<EntryStack<?>> usagesForStacks) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        boolean processingVisibilityHandlers = builder.isProcessingVisibilityHandlers();
        Set<CategoryIdentifier<?>> categories = new HashSet<>(builder.getCategories());
        Set<CategoryIdentifier<?>> filteringCategories = builder.getFilteringCategories();
        Function<EntryStack<?>, Collection<EntryStack<?>>> wildcardFunction = stack -> {
            EntryStack<?> wildcard = stack.wildcard();
            if (EntryStacks.equalsFuzzy(wildcard, stack)) return Collections.emptyList();
            return Collections.singletonList(wildcard);
        };
        List<EntryStack<?>> recipesForStacksWildcard = CollectionUtils.flatMap(recipesForStacks, wildcardFunction);
        List<EntryStack<?>> usagesForStacksWildcard = CollectionUtils.flatMap(usagesForStacks, wildcardFunction);
        DisplayRegistry displayRegistry = DisplayRegistry.getInstance();
        DisplayRegistryImpl displayRegistryImpl = (DisplayRegistryImpl) displayRegistry;
        DisplaysHolder displaysHolder = displayRegistryImpl.displaysHolder();

        Map<DisplayCategory<?>, Set<Display>> result = Maps.newHashMap();
        better_rei$searchIndexed(displayRegistry, displayRegistryImpl, displaysHolder, categories, filteringCategories,
                recipesForStacks, usagesForStacks, processingVisibilityHandlers, result, true);

        int generatorsCount = 0;

        for (Map.Entry<CategoryIdentifier<?>, List<DynamicDisplayGenerator<?>>> entry : displayRegistry.getCategoryDisplayGenerators().entrySet()) {
            CategoryIdentifier<?> categoryId = entry.getKey();
            DisplayCategory<?> category = CategoryRegistry.getInstance().get(categoryId).getCategory();
            if (processingVisibilityHandlers && CategoryRegistry.getInstance().isCategoryInvisible(category)) continue;
            if (!filteringCategories.isEmpty() && !filteringCategories.contains(categoryId)) continue;
            Set<Display> set = new LinkedHashSet<>();
            generatorsCount += entry.getValue().size();

            for (DynamicDisplayGenerator<Display> generator : (List<DynamicDisplayGenerator<Display>>) (List<? extends DynamicDisplayGenerator<?>>) entry.getValue()) {
                generateLiveDisplays(displayRegistry, wrapForError(generator), builder, set::add);
            }

            if (!set.isEmpty()) {
                ViewsImpl.getOrPutEmptyLinkedSet(result, category).addAll(set);
            }
        }

        Consumer<Display> displayConsumer = display -> {
            CategoryIdentifier<?> categoryIdentifier = display.getCategoryIdentifier();
            if (!filteringCategories.isEmpty() && !filteringCategories.contains(categoryIdentifier)) return;
            ViewsImpl.getOrPutEmptyLinkedSet(result, CategoryRegistry.getInstance().get(categoryIdentifier).getCategory()).add(display);
        };
        for (DynamicDisplayGenerator<Display> generator : (List<DynamicDisplayGenerator<Display>>) (List<? extends DynamicDisplayGenerator<?>>) displayRegistry.getGlobalDisplayGenerators()) {
            generatorsCount++;
            generateLiveDisplays(displayRegistry, wrapForError(generator), builder, displayConsumer);
        }

        if (CollectionUtils.allMatch(result.values(), Set::isEmpty) && (!recipesForStacksWildcard.isEmpty() || !usagesForStacksWildcard.isEmpty())) {
            better_rei$searchIndexed(displayRegistry, displayRegistryImpl, displaysHolder, categories, filteringCategories,
                    recipesForStacksWildcard, usagesForStacksWildcard, processingVisibilityHandlers, result, false);
        }

        for (CategoryRegistry.CategoryConfiguration<?> configuration : CategoryRegistry.getInstance()) {
            if (processingVisibilityHandlers && CategoryRegistry.getInstance().isCategoryInvisible(configuration.getCategory())) continue;
            CategoryIdentifier<?> categoryId = configuration.getCategoryIdentifier();
            if (!filteringCategories.isEmpty() && !filteringCategories.contains(categoryId)) continue;
            if (categories.contains(categoryId)) continue;
            for (EntryStack<?> usagesFor : Iterables.concat(usagesForStacks, usagesForStacksWildcard)) {
                if (isStackWorkStationOfCategory(configuration, usagesFor)) {
                    categories.add(categoryId);
                    List<Display> displays = better_rei$displaysOf(displayRegistry, categoryId);
                    if (processingVisibilityHandlers) {
                        Set<Display> set = CollectionUtils.filterToSet(displays, display -> displayRegistryImpl.isDisplayVisible(configuration.getCategory(), display));
                        if (!set.isEmpty()) ViewsImpl.getOrPutEmptyLinkedSet(result, configuration.getCategory()).addAll(set);
                    } else {
                        ViewsImpl.getOrPutEmptyLinkedSet(result, configuration.getCategory()).addAll(displays);
                    }
                    break;
                }
            }
        }

        Map<DisplayCategory<?>, List<DisplaySpec>> merged = (Map<DisplayCategory<?>, List<DisplaySpec>>) (Map) new LinkedHashMap<>();
        for (Map.Entry<DisplayCategory<?>, Set<Display>> entry : result.entrySet()) {
            merged.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        if (builder.isMergingDisplays() && ConfigObject.getInstance().doMergeDisplayUnderOne()) {
            mergeAndOptimize(result, merged);
        }

        Map<DisplayCategory<?>, List<DisplaySpec>> sorted = sortDisplays(merged);

        String message = String.format("Built Recipe View in %s for %d categories, %d recipes for, %d usages for and %d live recipe generators.",
                stopwatch.stop(), categories.size(), recipesForStacks.size(), usagesForStacks.size(), generatorsCount);
        if (ConfigObject.getInstance().doDebugSearchTimeRequired()) {
            InternalLogger.getInstance().info(message);
        } else {
            InternalLogger.getInstance().trace(message);
        }
        return sorted;
    }
}
