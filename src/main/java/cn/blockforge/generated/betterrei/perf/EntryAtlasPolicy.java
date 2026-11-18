package cn.blockforge.generated.betterrei.perf;

import me.shedaniel.rei.api.client.config.ConfigObject;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.impl.client.config.ConfigObjectImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Keeps REI's "cache entry rendering" atlas from freezing huge packs.
 *
 * <p>Stock REI paints every entry of the item panel once into a single
 * offscreen texture ({@code CachedEntryListRender.prepare()}), then draws the
 * panel from that texture. The texture is {@code ceil(sqrt(entries)) * 64}
 * pixels per side, i.e. 64*64*4 = 16 KB per entry. A measured 500-mod pack
 * with 30241 entries therefore asks for a 11136x11136 texture = 496 MB, and
 * the first inventory open froze the render thread for ~6.1 s:
 *
 * <ul>
 *   <li>4.0 s drawing 30241 item icons (a few mods' custom item renderers cost
 *       100-770 ms on their own),</li>
 *   <li>1.7 s reading the 496 MB texture back to the CPU,</li>
 *   <li>0.15 s uploading it again as a DynamicTexture, plus the FBO allocation.</li>
 * </ul>
 *
 * All of that happens once per game launch, on the render thread, inside the
 * first frame that draws the item panel — which is exactly the "opening the
 * inventory hangs for five seconds" report.
 *
 * <p>This class does not reimplement any of it: REI exposes the switch
 * ({@code doesCacheEntryRendering()}) in its own settings screen, and the
 * panel checks it every frame. We only flip it, once we can see how many
 * entries the pack actually has, and only when the atlas is big enough to
 * hurt. Nothing here is REI-version specific beyond two stable API calls, and
 * every step is wrapped so a renamed/absent method just means "leave stock
 * REI alone".
 */
public final class EntryAtlasPolicy {
    private static final Logger LOGGER = LoggerFactory.getLogger("better_rei");

    /** Give REI this many client ticks to build its entry registry, then stop asking. */
    private static final int MAX_WAIT_TICKS = 2400;

    /**
     * audit r38 P2-3：决定做完之后，每隔这么多 tick 瞄一眼条目数。条目数变了
     * （KubeJS 动态注册、数据包后补物品）阈值判断就过期了，重判一次。
     */
    private static final int RECHECK_INTERVAL_TICKS = 100;

    private static int ticks;
    private static boolean decided;
    /** 上次判定时 REI 的条目数（-1 = 还没判出来过） */
    private static int lastEntries = -1;
    private static int sinceRecheck;

    private EntryAtlasPolicy() {}

    /**
     * audit r38 P2-3：配置被重载时由 {@code BetterRei} 的事件监听调用——
     * entryAtlasMode / entryAtlasMaxEntries 可能已经变了，下一个 tick 重新判定，
     * 不用重启。只翻标记，不碰 REI 类。
     */
    public static void invalidate() {
        decided = false;
        lastEntries = -1;
        sinceRecheck = 0;
        // "等 REI 就绪"的窗口一并重开（放弃过一次的话没有它永远不再试）
        ticks = 0;
    }

    /** Called once per client tick from {@code TreeClient}; re-decides when entries or config change. */
    public static void tick() {
        if (decided) {
            // r38 P2-3: slow re-check — one getPreFilteredList().size() per 100 ticks,
            // nothing else. Only a changed entry count invalidates the decision.
            if (++sinceRecheck < RECHECK_INTERVAL_TICKS) return;
            sinceRecheck = 0;
            int now;
            try {
                List<?> list = EntryRegistry.getInstance().getPreFilteredList();
                now = list == null ? -1 : list.size();
            } catch (Throwable ignored) {
                return; // REI 正在重载：下一轮再看
            }
            if (now > 0 && now != lastEntries) {
                decided = false;
                ticks = 0;
            }
            return;
        }
        if (++ticks > MAX_WAIT_TICKS) {
            decided = true;
            LOGGER.info("Better REI: gave up waiting for REI's entry list, the icon-atlas check never ran.");
            return;
        }

        int entries;
        try {
            List<?> list = EntryRegistry.getInstance().getPreFilteredList();
            // Empty means REI has not finished its first reload yet; try again next tick.
            if (list == null || list.isEmpty()) return;
            entries = list.size();
        } catch (Throwable t) {
            // REI still bootstrapping (or a very different build): never a reason to crash.
            return;
        }

        decided = true;
        lastEntries = entries;
        try {
            apply(entries);
        } catch (Throwable t) {
            LOGGER.warn("Better REI: could not check REI's entry-atlas setting; stock REI behaviour is unchanged.", t);
        }
    }

    private static void apply(int entries) {
        BetterReiConfig.EntryAtlasMode mode = BetterReiConfig.entryAtlasMode();
        int limit = BetterReiConfig.entryAtlasMaxEntries();
        boolean currentlyOn = ConfigObject.getInstance().doesCacheEntryRendering();

        int side = 4;
        while (side * side < entries) side++;
        int pixels = side * 64;
        long megabytes = (long) pixels * pixels * 4L / (1024L * 1024L);

        boolean wantOn = switch (mode) {
            case ALWAYS -> true;
            case NEVER -> false;
            // AUTO only ever steps in to prevent the freeze; a player who turned
            // the atlas off in REI's own settings screen keeps that choice.
            case AUTO -> entries <= limit ? currentlyOn : false;
        };

        if (wantOn == currentlyOn) {
            LOGGER.info("Better REI: REI's item-icon atlas is {}, {} entries would need a {}x{}px texture (~{} MB). No change ({}).",
                    currentlyOn ? "ON" : "OFF", entries, pixels, pixels, megabytes, mode);
            return;
        }

        ConfigObject config = ConfigObject.getInstance();
        if (!(config instanceof ConfigObjectImpl impl)) {
            LOGGER.warn("Better REI: this REI build does not expose the entry-atlas switch; leaving it alone "
                    + "(if the first inventory open hangs, turn off 'Cache Entry Rendering' in REI's settings).");
            return;
        }
        impl.setDoesCacheEntryRendering(wantOn);

        if (ConfigObject.getInstance().doesCacheEntryRendering() == wantOn) {
            if (wantOn) {
                LOGGER.info("Better REI: turned REI's item-icon atlas back ON for {} entries (~{} MB, {}).", entries, megabytes, mode);
            } else if (currentlyOn && mode == BetterReiConfig.EntryAtlasMode.AUTO) {
                // audit r38 P2-2: AUTO 关掉的这个"开"可能是玩家在 REI 设置里
                // 亲手打开的。旧版只留一条 info，等于盖掉玩家的选择还不出声。
                LOGGER.warn("Better REI: entryAtlasMode=AUTO turned REI's item-icon cache OFF ({} entries would "
                                + "build a {}x{}px texture, ~{} MB, measured ~6 s of frozen render thread at first "
                                + "item-panel open). If YOU enabled 'Cache Entry Rendering' in REI's settings on "
                                + "purpose and want to keep it despite the freeze risk, set entryAtlasMode=ALWAYS "
                                + "in better_rei.toml — this override will not repeat blindly.",
                        entries, pixels, pixels, megabytes);
            } else {
                LOGGER.info("Better REI: turned REI's item-icon atlas OFF - {} entries would build a {}x{}px texture "
                                + "(~{} MB) the first time the item panel opens, which measured ~6 s of frozen render "
                                + "thread. The panel now draws icons every frame instead. Set entryAtlasMode=ALWAYS "
                                + "in better_rei.toml to get the atlas back.",
                        entries, pixels, pixels, megabytes);
            }
        } else {
            LOGGER.warn("Better REI: REI ignored the entry-atlas switch ({}); if the item panel still hangs on "
                    + "first open, turn off 'Cache Entry Rendering' by hand in REI's settings.", mode);
        }
    }
}
