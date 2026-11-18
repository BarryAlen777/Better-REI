package cn.blockforge.generated.betterrei.perf;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime compatibility gate for REI's internals.
 *
 * The optimizations reach into REI's impl classes (ViewsImpl, DisplayCache,
 * HashedEntryStackWrapper, ...). Those are not a stable API: if REI ships a
 * different build (12.1.786+) the method we inject into may still exist but
 * mean something else. Before any optimized code path runs we verify the
 * actually-installed REI version; on mismatch every optimization quietly
 * steps aside and stock REI runs unmodified — degraded, never crashed.
 *
 * This is the second line of defence: mods.toml already pins the exact
 * tested version, and the mixin config is optional (required:false), so a
 * failed mixin application only skips that mixin instead of aborting launch.
 */
public final class BetterReiVersionGuard {

    /** REI version this build was compiled and verified against. */
    public static final String EXPECTED_REI_VERSION = "12.1.785";
    public static final String REI_MOD_ID = "roughlyenoughitems";

    /** detectedVersion() sentinel when REI is not installed at all. */
    public static final String VERSION_MISSING = "<missing>";
    /**
     * detectedVersion() sentinel when the probe could not read the mod list
     * (called too early, reflection blocked...). Never cached (audit r10
     * P1-2): the next call simply retries.
     */
    public static final String VERSION_UNKNOWN = "<unknown>";

    private static final Logger LOGGER = LoggerFactory.getLogger("better_rei");
    private static final AtomicReference<String> DETECTED = new AtomicReference<>();
    private static final Set<String> FALLBACK_WARNED = ConcurrentHashMap.newKeySet();
    private static volatile boolean selfChecked;
    private static volatile boolean unknownWarned;

    /** mixin targets and the feature each one powers, for the startup self-check. */
    private static final String[][] MIXIN_TARGETS = {
            {"me.shedaniel.rei.impl.client.view.ViewsImpl", "fastViewLookup"},
            {"me.shedaniel.rei.impl.client.gui.widget.entrylist.EntryListSearchManager", "nameCacheSort"},
            {"me.shedaniel.rei.impl.common.entry.type.EntryRegistryImpl", "reloadHook"},
            // audit r10 P2-3: the fourth hook was missing from the self-check,
            // so a dead recipe-tree reload hook went unnoticed.
            {"me.shedaniel.rei.impl.client.registry.display.DisplayRegistryImpl", "treeReloadHook"},
    };

    private BetterReiVersionGuard() {}

    /** Detected REI version string, or "<missing>" / "<unknown>" if absent/unreadable. */
    public static String detectedVersion() {
        String v = DETECTED.get();
        if (v != null) return v;
        v = probe();
        if (VERSION_UNKNOWN.equals(v)) {
            // audit r10 P1-2: "<unknown>" means "not readable *yet*", so it must
            // not be cached: caching it disabled every optimization for the rest
            // of the session after one badly-timed ModList read.
            if (!unknownWarned) {
                unknownWarned = true;
                LOGGER.warn("Better REI: the installed REI version could not be read yet; retrying on the "
                        + "next check. Until it succeeds the REI-internal optimizations and the recipe tree "
                        + "stay off. Stock REI keeps working.");
            }
            return v;
        }
        if (DETECTED.compareAndSet(null, v)) return v;
        String cached = DETECTED.get();
        return cached != null ? cached : v;
    }

    /** True only when the running REI build is exactly the one we were compiled against. */
    public static boolean matches() {
        return EXPECTED_REI_VERSION.equals(detectedVersion());
    }

    /**
     * audit r7 P1-4: run the probe during mod construction so the log always
     * says up-front whether this build is live, instead of waiting for the
     * first hotkey press to produce the only warning line.
     */
    public static String logStartupStatus() {
        return detectedVersion();
    }

    /**
     * False only when REI is definitively not installed; an unreadable probe
     * counts as present (registering the hotkey is harmless, and a later
     * retry can still find REI).
     */
    public static boolean reiPresent() {
        return !VERSION_MISSING.equals(detectedVersion());
    }

    /**
     * audit r7 P1-4: every failure layer is silent by design (optional mixin
     * config, require = 0), so a mixin that never applied would be invisible.
     * Once REI has booted, check each target class for our "better_rei$"
     * members — they only exist if the mixin was actually applied.
     */
    public static void selfCheckMixins() {
        if (selfChecked) return;
        selfChecked = true;
        // audit r12 P3-2: with REI missing there is nothing to hook, and probing
        // its classes would only print four alarming "could not load" warnings
        // for a situation the startup probe already explained.
        if (VERSION_MISSING.equals(detectedVersion())) {
            LOGGER.info("Better REI self-check skipped: REI is not installed, so there is nothing to hook.");
            return;
        }
        int applied = 0;
        for (String[] target : MIXIN_TARGETS) {
            try {
                Class<?> c = Class.forName(target[0], false, BetterReiVersionGuard.class.getClassLoader());
                boolean present = false;
                // Mixin 0.8 renames merged @Inject handlers (e.g. the one here for
                // "reloadHook" physically lands as "handler$zzb000$better_rei$clearNameCache"),
                // so the prefix check saw a hook as "not applied" even though it was live —
                // a false alarm confirmed offline against REI 12.1.785's shipped classes.
                // Match the marker anywhere in the name, which covers both spellings.
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getName().contains("better_rei$")) {
                        present = true;
                        break;
                    }
                }
                if (present) {
                    applied++;
                } else {
                    LOGGER.warn("Better REI: the mixin powering '{}' did not apply to {} (conflict or "
                            + "repacked REI?). That optimization is off; stock REI keeps working.",
                            target[1], target[0]);
                }
            } catch (Throwable t) {
                LOGGER.warn("Better REI: could not load mixin target {} for the self-check; the '{}' "
                        + "optimization is off. Stock REI keeps working.", target[0], target[1]);
            }
        }
        if (applied == MIXIN_TARGETS.length) {
            if (matches()) {
                LOGGER.info("Better REI self-check passed: {}/{} optimization hooks are live against REI {}.",
                        applied, MIXIN_TARGETS.length, detectedVersion());
            } else {
                // audit r10 P2-3: "applied" is not "enabled". On an unverified
                // REI build the mixins were applied but the version guard stops
                // every optimized path, so claiming "passed/live" was wrong.
                LOGGER.info("Better REI self-check: all {}/{} optimization hooks were applied, but they stay "
                                + "DISABLED because the installed REI {} is not the verified {}. Stock REI keeps "
                                + "working normally.",
                        applied, MIXIN_TARGETS.length, detectedVersion(), EXPECTED_REI_VERSION);
            }
        } else {
            LOGGER.warn("Better REI self-check: only {}/{} optimization hooks are present; the ones warned "
                            + "about above stay off. Stock REI keeps working normally.",
                    applied, MIXIN_TARGETS.length);
        }
    }

    private static String probe() {
        String found;
        try {
            found = ModList.get().getModContainerById(REI_MOD_ID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse(VERSION_MISSING);
        } catch (Throwable t) {
            LOGGER.debug("Could not read the installed REI version", t);
            return VERSION_UNKNOWN;
        }
        if (VERSION_MISSING.equals(found)) {
            LOGGER.warn("Better REI is a companion for RoughlyEnoughItems but REI is not installed — "
                    + "the recipe tree and all optimizations stay disabled, and the Ctrl+R hotkey only "
                    + "prints an install reminder. Install REI or remove Better REI; nothing else is "
                    + "affected.");
            return found;
        }
        if (!EXPECTED_REI_VERSION.equals(found)) {
            LOGGER.warn("Better REI was built for RoughlyEnoughItems {} but found {} on this install. "
                            + "All REI-internal optimizations and the recipe tree are disabled; stock REI "
                            + "keeps working normally. Check for a newer Better REI release.",
                    EXPECTED_REI_VERSION, found);
        } else {
            LOGGER.info("Better REI active against RoughlyEnoughItems {}.", found);
        }
        return found;
    }

    /**
     * An optimized path threw (e.g. an unexpected REI internal change):
     * log it once per feature, then stay quiet — the caller falls through
     * to stock REI code, so gameplay is unaffected.
     */
    public static void warnFallbackOnce(String feature, Throwable t) {
        if (FALLBACK_WARNED.add(feature)) {
            LOGGER.warn("Better REI optimization '{}' threw an unexpected error and fell back to stock REI "
                    + "code. Further fallbacks for this feature will not be logged.", feature, t);
        } else {
            LOGGER.debug("Better REI optimization '{}' fell back to stock REI code.", feature, t);
        }
    }
}
