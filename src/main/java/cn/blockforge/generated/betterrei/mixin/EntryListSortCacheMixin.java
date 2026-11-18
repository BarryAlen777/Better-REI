package cn.blockforge.generated.betterrei.mixin;

import cn.blockforge.generated.betterrei.perf.NameCache;
import cn.blockforge.generated.betterrei.perf.BetterReiConfig;
import cn.blockforge.generated.betterrei.perf.BetterReiVersionGuard;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.shedaniel.rei.api.client.config.ConfigObject;
import me.shedaniel.rei.api.client.gui.config.EntryPanelOrdering;
import me.shedaniel.rei.impl.client.gui.widget.entrylist.EntryListSearchManager;
import me.shedaniel.rei.impl.common.util.HashedEntryStackWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * When the entry panel is ordered by NAME, REI 12.1 sorts the search result
 * with ENTRY_NAME_COMPARER, which rebuilds asFormatStrippedText() inside
 * every comparison: ~2 n log n text builds + regex format stripping per
 * search keystroke. This decorates the list once with a hash-keyed name map
 * backed by NameCache, so each distinct entry pays one text extraction per
 * registry reload instead of per comparison.
 *
 * The decoration map is a fastutil Long2ObjectOpenHashMap (fastutil ships
 * with Minecraft itself, no new dependency): hashExact() is a primitive long,
 * and a boxed HashMap&lt;Long, String&gt; would allocate ~2 n log n Long keys
 * per search — a regression bigger than part of the win this mixin brings
 * (audit r7 P1-3).
 *
 * Crash-safety (per audit r5): no @Overwrite — a cancellable HEAD injection
 * only fires when the ordering really is NAME and the REI build is the one
 * we verified; in every other case (REGISTRY ordering, config off, version
 * mismatch, any exception) the untouched stock copyAndOrder runs. Note REI's
 * default ordering is REGISTRY, where stock performs no name extraction at
 * all and this mixin therefore stays inert.
 */
@Mixin(EntryListSearchManager.class)
public class EntryListSortCacheMixin {

    @Inject(method = "copyAndOrder", at = @At("HEAD"), cancellable = true, require = 0)
    private void better_rei$tryCachedOrder(List<HashedEntryStackWrapper> list,
                                            CallbackInfoReturnable<List<HashedEntryStackWrapper>> ci) {
        try {
            if (!BetterReiVersionGuard.matches()) return;
            if (!BetterReiConfig.nameCacheEnabled()) return;
            if (ConfigObject.getInstance().getItemListOrdering() != EntryPanelOrdering.NAME) return;

            List<HashedEntryStackWrapper> out = new ArrayList<>(list);
            Long2ObjectOpenHashMap<String> names = new Long2ObjectOpenHashMap<>(Math.max(16, out.size() * 2));
            for (HashedEntryStackWrapper wrapper : out) {
                long h = wrapper.hashExact();
                if (!names.containsKey(h)) {
                    names.put(h, NameCache.getOrCompute(wrapper));
                }
            }
            // TimSort is stable, so ties keep their relative (registry) order,
            // exactly like the stock comparator; descending = whole reverse.
            out.sort(Comparator.comparing(wrapper -> names.get(wrapper.hashExact())));
            if (!ConfigObject.getInstance().isItemListAscending()) {
                Collections.reverse(out);
            }
            ci.setReturnValue(out);
            ci.cancel();
        } catch (Throwable t) {
            BetterReiVersionGuard.warnFallbackOnce("nameCacheSort", t);
            // no cancel -> untouched stock copyAndOrder runs instead
        }
    }
}
