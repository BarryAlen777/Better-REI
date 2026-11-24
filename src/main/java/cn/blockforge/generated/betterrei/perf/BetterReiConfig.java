package cn.blockforge.generated.betterrei.perf;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BetterReiConfig {
    /** What to do with REI's one-shot "item icon atlas" (see EntryAtlasPolicy). */
    public enum EntryAtlasMode {
        /** Keep the atlas only while it stays small (see entryAtlasMaxEntries). */
        AUTO,
        /** Always let REI build the atlas, however many items the pack has. */
        ALWAYS,
        /** Never let REI build the atlas; the item panel draws icons every frame. */
        NEVER
    }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue FAST_VIEW_LOOKUP;
    public static final ForgeConfigSpec.BooleanValue NAME_CACHE;
    public static final ForgeConfigSpec.EnumValue<EntryAtlasMode> ENTRY_ATLAS_MODE;
    public static final ForgeConfigSpec.IntValue ENTRY_ATLAS_MAX_ENTRIES;
    public static final ForgeConfigSpec.BooleanValue RECIPE_TREE;
    public static final ForgeConfigSpec.IntValue TREE_MAX_RECIPES;
    public static final ForgeConfigSpec.IntValue TREE_MAX_DEPTH;
    /** 顶栏"分类筛选"关掉的配方分类 id（audit r35 方案B），重启后仍然生效。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> TREE_HIDDEN_CATEGORIES;

    /** 由 {@code BetterRei} 在构造期装入；立即可存盘用。 */
    public static volatile ModConfig clientConfig;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Better REI - performance & recipe tree for REI 12.1.x on Forge 1.20.1");
        b.push("optimize");
        b.comment("Indexed recipe/usages lookup instead of scanning every display (big win in huge packs). Takes effect on next recipe view.");
        FAST_VIEW_LOOKUP = b.define("fastViewLookup", true);
        b.comment("Cache entry display names for entry-list sorting. Only matters when REI's item panel ordering is set to \"Name\" (Advanced -> Layout); the default \"Registry\" ordering never extracts names, so it stays a no-op there.");
        NAME_CACHE = b.define("nameCache", true);
        b.comment("REI paints every item of the entry list once into one huge texture the first time you open the item panel.",
                "The texture is (sqrt(entries) x 64) pixels per side: 30241 items = 11136x11136 = 496 MB, and building it froze the",
                "render thread for ~6 seconds in a measured 500-mod profile (4.0 s drawing the icons, 1.7 s reading them back).",
                "AUTO keeps it only while the texture stays at or below 4096x4096 (64 MB, under a second); ALWAYS = stock REI;",
                "NEVER = always off. With it off the panel draws its icons every frame: a few ms per frame, but no freeze.");
        ENTRY_ATLAS_MODE = b.defineEnum("entryAtlasMode", EntryAtlasMode.AUTO);
        b.comment("Highest entry count for which AUTO still allows REI's icon atlas.",
                "Each entry costs 64*64*4 = 16 KB of texture, so 4096 entries = 64 MB.");
        ENTRY_ATLAS_MAX_ENTRIES = b.defineInRange("entryAtlasMaxEntries", 4096, 256, 65536);
        b.pop();
        b.push("tree");
        b.comment("Enable the EMI-style recipe tree screen (default hotkey: Ctrl+R, works both in the world and inside any GUI).");
        RECIPE_TREE = b.define("recipeTree", true);
        b.comment("Max recipe rows shown per item before '+N more'.");
        TREE_MAX_RECIPES = b.defineInRange("treeMaxRecipes", 16, 1, 128);
        b.comment("Max 'Expand All' depth.");
        TREE_MAX_DEPTH = b.defineInRange("treeMaxDepth", 8, 2, 32);
        b.comment("Recipe category ids the tree's category filter hides (set by clicking '分类筛选' in the tree,",
                "no need to edit by hand). Example: [\"some_mod:dismantling\"].");
        TREE_HIDDEN_CATEGORIES = b.defineListAllowEmpty("treeHiddenCategories", List.of(), o -> o instanceof String);
        b.pop();
        SPEC = b.build();
    }

    private BetterReiConfig() {}

    /** All getters behave the same: before the config file is loaded, fall back to the default (on). */
    public static boolean fastViewLookupEnabled() {
        return !SPEC.isLoaded() || FAST_VIEW_LOOKUP.get();
    }

    public static boolean nameCacheEnabled() {
        return !SPEC.isLoaded() || NAME_CACHE.get();
    }

    public static EntryAtlasMode entryAtlasMode() {
        return SPEC.isLoaded() ? ENTRY_ATLAS_MODE.get() : EntryAtlasMode.AUTO;
    }

    public static int entryAtlasMaxEntries() {
        return SPEC.isLoaded() ? ENTRY_ATLAS_MAX_ENTRIES.get() : 4096;
    }

    public static boolean treeEnabled() {
        return !SPEC.isLoaded() || RECIPE_TREE.get();
    }

    public static int treeMaxRecipes() {
        return SPEC.isLoaded() ? TREE_MAX_RECIPES.get() : 16;
    }

    public static int treeMaxDepth() {
        return SPEC.isLoaded() ? TREE_MAX_DEPTH.get() : 8;
    }

    /** 分类筛选当前关掉的分类 id；配置没加载时按"什么都没关"处理。 */
    public static List<String> treeHiddenCategories() {
        if (!SPEC.isLoaded()) return List.of();
        List<? extends String> raw = TREE_HIDDEN_CATEGORIES.get();
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s != null && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    /** 写入分类筛选结果并立刻存盘——玩家点完勾选就生效，不用重开游戏。 */
    public static void setTreeHiddenCategories(List<String> ids) {
        if (!SPEC.isLoaded()) return;
        List<String> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        TREE_HIDDEN_CATEGORIES.set(sorted);
        ModConfig config = clientConfig;
        if (config != null) {
            try {
                config.save();
            } catch (Throwable ignored) {
                // 存不上只影响"重启后还记得"，当前会话里的筛选已经生效
            }
        }
    }
}
