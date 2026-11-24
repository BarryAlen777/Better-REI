package cn.blockforge.generated.betterrei.tree;

import cn.blockforge.generated.betterrei.perf.BetterReiConfig;
import com.mojang.blaze3d.platform.InputConstants;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.VanillaEntryTypes;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.api.common.util.EntryStacks;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EMI-style recipe / usage tree over REI's own display data.
 * Item nodes expand into the recipes that produce (or consume) them; recipe
 * nodes expand into their ingredients. Everything is lazily built per click,
 * cycle-guarded and fan-out limited, so huge packs stay responsive.
 *
 * Ordering inside a category is made fully deterministic by sorting on
 * (category title, TreeIndex registration position) — REI's posting sets
 * are identity-hashed and would otherwise reshuffle across sessions
 * (audit r7 P2-2). If REI reloads while the tree is open, every stale
 * Display is dropped, but the tree is rebuilt by replaying the player's
 * expansion state and scroll offset (audit r7 P2-3, audit r10 P2-4).
 *
 * Layout (audit r13 "配方树太乱/字看不清"):
 * - the tree now sits on a real panel (opaque background + border), so the
 *   world, the hotbar and other HUD overlays no longer shine through the text;
 * - every row has its own fixed 20px slot with a depth guide line per level,
 *   so nesting is readable at a glance;
 * - a recipe row draws "图标 → 图标 + 产物名 (类别名)" instead of only the
 *   category title. Twelve stonecutting recipes used to render twelve
 *   identical "切石" lines; they now differ by their product, which is what
 *   the player actually wants to tell apart.
 *
 * Performance (audit r14 "是不是性能有问题，没有60帧"):
 * - only the rows inside the content box are drawn. The old loop started at
 *   index 0 and skipped every row above the viewport, which with a 4000-row
 *   "expand all" tree meant thousands of wasted iterations per frame;
 * - every row resolves its display name, amount, category title, input icons
 *   and trimmed text exactly once and keeps them on the node. Those calls
 *   allocate (Component, String, font.width) and used to run 30+ times per
 *   frame, which is exactly the kind of garbage that costs frames;
 * - the row count in the header and the footer hint are cached the same way.
 *
 * 备料数量 (audit r19 "补数量 + 进度", r22 allocation fixes):
 * - every item row now carries "需 N · 够 / 差 K" right-aligned, so the tree
 *   doubles as a shopping list: the number is the plan, the colour says
 *   whether the inventory already covers it;
 * - the plan follows ONE recipe per level (the first row, shown in gold as
 *   "· 做 N 次", P2-3), because unlike EMI this tree fans out every recipe of
 *   an item at once — counting all of them would need a resource-allocation
 *   solver. Rows whose item has siblings say "另 N 条未计" instead of
 *   silently pretending to be complete;
 * - the inventory is handed out TOP-DOWN (P1-1): a row only counts what the
 *   rows above it left over, so two branches can no longer both claim the
 *   same planks. Interchangeable slots try every candidate against the
 *   remaining pool (P2-1, hashes cached per row);
 * - deep ×64 chains saturate at "需 ≥N" instead of overflowing long into
 *   nonsense (P2-2);
 * - the target amount is adjustable top-left (hidden when the panel is too
 *   narrow, P3-3), and the header counts how many rows still fall short;
 * - cost: one inventory scan + one array walk per tree rebuild. The row texts
 *   that depend on the allocation are re-flowed whenever a row's share
 *   changes (P3-1). No per-frame scanning.
 *
 * Audit r24 (borrowed from two JEI optimisation mods the player supplied):
 * - "Expand All" no longer builds up to 4000 nodes inside the button click.
 *   The BFS now lives in a queue pumped with a per-frame nanosecond budget,
 *   TickWork-style (jeioptimizations): the tree fills in over a few frames,
 *   the footer says so, and "Collapse All" / a mode switch cancels the run;
 * - category titles are resolved once per category and reused (Precompute-
 *   style, same mod), instead of re-formatting every display's title on
 *   every single expansion.
 *
 * Audit r38 (问题清单 第一批 + 第二批):
 * - pump throttle (P1-1): mid-run the pump no longer pays a full
 *   flatten + inventory allocation every frame; the flat list refreshes
 *   every 256 new nodes WITHOUT the allocation pass, one full rebuild runs
 *   when it drains, and rows show no "需/够" column until then;
 * - the filter header's "Show all" is the only clickable clear target now
 *   (P1-2, was: any header click wiped every hidden category);
 * - the button row compresses proportionally on narrow panels (P2-1);
 * - arrows/PgUp/PgDn/Home/End drive the open dropdown, not the tree (P2-6);
 * - stale hidden ids are pruned against the live category registry when the
 *   dropdown opens (P2-8);
 * - dropdown row titles + header strings are trimmed/translated once, not
 *   per frame (P3-1); drag state resets across resize (P3-3); the footer
 *   says so when expand-all hits the 4000-node cap (P3-4);
 * - a manual EXPAND click no longer cancels a running expand-all — only
 *   collapsing conflicts with the pump (P2-5).
 */
public class RecipeTreeScreen extends Screen {

    // ---------------------------------------------------------------- layout

    private static final int ROW_H = 20;
    /** title + button row + separator, measured from the panel top */
    private static final int HEADER = 60;
    /** hint bar at the panel bottom */
    private static final int FOOTER = 26;
    private static final int INDENT = 12;
    private static final int MAX_DRAW_DEPTH = 8;
    /**
     * audit r35（"图标显示不全"，方案层一）：格子从 16 提到 18（原版槽格大小），
     * 图标整格铺满、底板和图标同一个矩形，不再留 1px 内缩。
     */
    private static final int ICON = 18;
    private static final int ARROW_W = 9;
    private static final int MAX_INPUT_ICONS = 3;

    private static final int C_PANEL = 0xF41A1B22;
    private static final int C_BORDER = 0xFF474A58;
    private static final int C_SEP = 0xFF3A3D49;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_SUB = 0xFF9BA2B0;
    private static final int C_AMOUNT = 0xFF9BE79B;
    private static final int C_SHORT = 0xFFFF9B6B;
    private static final int C_CYCLE = 0xFFFFA8A8;
    private static final int C_GOLD = 0xFFFFD966;
    private static final int C_ROW_ALT = 0x0CFFFFFF;
    private static final int C_HOVER = 0x33FFFFFF;
    private static final int C_HOVER_EDGE = 0x59FFFFFF;
    private static final int C_GUIDE = 0x2EFFFFFF;
    private static final int C_ICON_PLATE = 0x36000000;

    static final int ITEM = 0, RECIPE = 1, MORE = 2, LABEL = 3;

    static final class Node {
        final int kind;
        final int depth;
        final Node parent;
        EntryStack<?> stack;
        Display display;
        /** the ingredient slot this item row came from (needed to count "any of these" recipes) */
        EntryIngredient ingredient;
        Component label;
        int altCount = 1;
        /** position of this recipe row inside the parent item's pendingRecipes (P2-4) */
        int recipeIndex = -1;
        String recipeKey;
        /** position of this ingredient inside the parent display's entry list (P2-4) */
        int ingredientIndex = -1;
        boolean expanded, built, cycle;
        List<Node> children = new ArrayList<>();
        List<Display> pendingRecipes = new ArrayList<>();
        int shownCount;

        // ---- "how much do I need" (audit r19: 备料数量 + 进度)
        /** item rows: how many of this item the planned route needs (-1 = not counted) */
        long need = -1;
        /** item rows: how many of this item are LEFT for this row once the rows above it have been served (-1 = not counted) */
        long have = -1;
        /** item rows: fuzzy hash of {@code stack}, resolved once — the allocation reruns on every click (P2-1) */
        boolean hashReady;
        long cachedHash;
        /** item rows: fuzzy hashes of every interchangeable candidate, resolved once (P2-1) */
        long[] altHashes;
        /** recipe rows: how many times this recipe has to run (-1 = not the counted route) */
        long batches = -1;
        /** item rows: how many sibling recipes the amount above does NOT cover */
        int otherRecipes;

        // -------- render cache: resolved once per node, not once per frame
        boolean textReady;
        String nameText = "";
        int nameTextW;
        long amount = 1;
        String amountText;
        int amountTextW;
        String extraText;
        int extraTextW;
        /** item rows: "另 N 条未计" — siblings the amount above ignores */
        String otherText;
        int otherTextW;
        /** recipe rows: " · 做 N 次" on the counted route (audit r22 P2-3) */
        String batchText;
        int batchTextW;
        /** item rows: right-aligned "需 64 · 差 32" block, resolved once */
        String needText;
        int needTextW;
        String progressText;
        int progressTextW;
        int needBlockW;
        int needColor;
        /** recipe rows: the first few input icons, resolved once */
        List<EntryStack<?>> inputPreview;
        /** recipe rows: the product icon shown after the arrow */
        EntryStack<?> productStack;
        String overflowText;
        int overflowTextW;
        /** recipe rows: right-aligned category title; null means "no visible output" */
        String categoryText;
        int categoryTextW;
        /** trim() result for {@link #trimWidth}, so a row is measured once per window size */
        String trimmedText = "";
        int trimWidth = Integer.MIN_VALUE;

        Node(int kind, int depth, Node parent) {
            this.kind = kind;
            this.depth = depth;
            this.parent = parent;
        }
    }

    /**
     * audit r35 方案B：顶栏"分类筛选"。树里一个物品底下平铺所有能产出它的配方，
     * 有些模组（拆解台这类）给几乎每样东西都挂一条配方，把有用的路线淹掉了。
     * 这里记下被玩家关掉的分类 id（REI 的 "modid:path"），buildChildren 展开物品时
     * 先 removeIf 再排序；勾选结果写进 better_rei.toml，重启后仍然生效。
     */
    private final Set<String> hiddenCategories = new HashSet<>();
    /**
     * 下拉面板里的一行 = 一个 REI 配方分类。
     * r38 P3-1：标题的裁剪结果缓存在行上（面板宽度不变就只裁一次），旧版每帧
     * 对每行跑 trim + 逐字符 font.width。
     */
    private static final class CategoryOption {
        private final String id;
        private final String title;
        private final Renderer icon;
        int titleTrimW = Integer.MIN_VALUE;
        String titleTrimmed = "";

        CategoryOption(String id, String title, Renderer icon) {
            this.id = id;
            this.title = title;
            this.icon = icon;
        }

        String id() { return id; }
        String title() { return title; }
        Renderer icon() { return icon; }
    }
    private static final int FILTER_HEADER = 22;
    private static final int FILTER_ROW = 20;
    private static final int FILTER_MAX_ROWS = 12;

    // audit r38 P3-1：下拉表头两处文字整屏翻译一次就够，旧版每帧 translatable + font.width
    private String filterHeadText = "";
    private String filterAllText = "";
    private int filterAllTextW;
    private String filterEmptyText = "";
    private int filterHeadTrimKey = Integer.MIN_VALUE;
    private String filterHeadTrimmed = "";

    private final Screen previous;
    private Node root;
    private final List<Node> flat = new ArrayList<>();
    private boolean usagesMode;
    private Button filterButton;
    private boolean filterOpen;
    private List<CategoryOption> filterOptions = List.of();
    private int filterScroll;
    private int filterViewportRows;
    /** 最近一次画出来的下拉面板矩形，供点击/滚轮命中判断 */
    private Rectangle filterRect;
    // r37：下拉滚动条按住拖动（之前只能点一下跳位）
    private boolean filterScrollDragging;
    private int filterScrollGrab;
    private double scroll;
    private Button modeButton;
    /** REI reload generation this tree was built against (P2-3). */
    private long generation;
    /** countdown of the "tree was refreshed" notice drawn above the hint bar */
    private int reloadNotice;

    // ---- 备料数量 (audit r19): how many of the root stack the player plans to make
    private static final long TARGET_MIN = 1;
    private static final long TARGET_MAX = 512;
    /**
     * 需求量封顶（audit r22 P2-2）：目标 512 × 每层 ×64 连乘到七八层就会溢出
     * long 变成负数——整列数字消失或显示错的小数字。乘法在这里饱和，界面用
     * "需 ≥N" 明说这个数已经大到不用当真了。
     */
    private static final long NEED_CAP = 999_999_999L;
    /**
     * 一个可替换槽最多数几个候选（audit r22 P2-1）。旧版超过 32 就只数第一个，
     * "任意杂物"槽明明有货也报"差"；现在 hash 只算一次存到节点上，扫描的是
     * long 数组，放大到 512 也没有每帧开销。
     */
    private static final int MAX_ALTERNATIVES = 512;
    private long targetCount = 1;
    /**
     * hashFuzzy -> amount carried, snapshotted when the screen opens. The tree
     * is read-only while a screen is up, so re-reading the 40 inventory slots
     * on every frame would be pure waste; clicks reset the tree and rebuild it.
     */
    private Map<Long, Long> inventory = Map.of();
    private String targetText = "";
    private int targetTextW;
    private int targetTextX, targetMinusX, targetPlusX, targetBtnY;
    private boolean hoverTargetMinus, hoverTargetPlus;
    /** 面板太窄放不下目标控件时为 false，那时它既不画也不接受点击（audit r22 P3-3） */
    private boolean targetVisible;
    /** items on screen whose plan still falls short — shown next to the row count */
    private int missingItems;

    // scrollbar dragging (audit r14 "滚动条不能上下拖动")
    private boolean scrollbarDragging;
    /** distance between the mouse and the top of the grabbed thumb */
    private int scrollbarGrab;

    // ---- progressive expand-all (audit r24, borrowed from jeioptimizations' TickWork)
    /**
     * "展开全部" used to build every node (up to 4000) inside the button click —
     * one long freeze on a big tree. The BFS now lives in a queue that the frame
     * loop pumps with a hard nanosecond budget, TickWork-style: the tree fills
     * in over a few frames while scrolling/hovering keeps running at frame rate.
     */
    private final Deque<Node> expandQueue = new ArrayDeque<>();
    private boolean expandingAll;
    /** total-node safety cap left in the current run (same 4000 as before) */
    private int expandRemaining;
    /** per-frame slice, ~1/4 of one 60 fps frame — big enough to finish quickly, small enough to stay smooth */
    private static final long EXPAND_BUDGET_NANOS = 4_000_000L;
    /**
     * audit r38 P1-1：泵期间不再每帧 rebuildFlat（flatten 整树 + 全行备料分配
     * 是对"还会再变"的中间状态做白工）。攒够 256 个新节点才刷一次可见行表
     * （只铺平不分配），泵结束统一补一次全量——旧版把一次 6 秒的卡换成
     * 整段过程的掉帧，节流后两头都省。
     */
    private static final int EXPAND_REBUILD_EVERY = 256;
    /** 距上次刷行表已新建的节点数 */
    private int expandSinceRebuild;
    /** r38 P3-4：上一轮"展开全部"是被 4000 节点上限截停的，页脚要说明，别静默 */
    private boolean expandCapped;

    /**
     * category id -> title, resolved once per screen (audit r24, borrowed from
     * jeioptimizations' Precompute: the expensive string is computed once and
     * reused, instead of re-formatting every display on every expansion).
     * Cleared when REI reloads under the tree.
     */
    private final Map<CategoryIdentifier<?>, String> titleCache = new HashMap<>();

    // frame caches (audit r14: the tree was rebuilding these every frame)
    private int cachedRowCount = -1;
    private int cachedMissingItems = -1;
    /** 模式也参与缓存键：缺项后缀只在配方模式出现（audit r22 P3-2） */
    private boolean cachedUsagesMode;
    private String cachedCountText = "";
    private int cachedCountW;
    private String hintText = "";
    private String reloadedText = "";
    private String expandingText = "";
    /** r38 P3-4：展开撞上限那次跑完后页脚改说这句 */
    private String cappedText = "";
    private int footerTrimWidth = Integer.MIN_VALUE;
    private String footerHint = "";
    private String footerReloaded = "";
    private String footerExpanding = "";
    private String footerCapped = "";
    /** width of the "→" separator, measured once */
    private int arrowSepW = -1;
    /** width of the "·" separator in the amount block, measured once */
    private int dotWidth = -1;
    /** rendered title width, measured once (used to keep the target control off the title) */
    private int titleW = -1;
    /** 标题放不下时裁到这个宽度重缓存（audit r22 P3-3），key 是可用宽度 */
    private int headerTitleKey = Integer.MIN_VALUE;
    private String headerTitleTrim = "";
    /** 表头至少给标题留这么多像素，再窄就宁可收起目标控件 */
    private static final int MIN_HEADER_TITLE = 60;

    // panel geometry, recomputed every frame (window can be resized)
    private int panelX, panelY, panelW, panelH;
    private int contentLeft, contentRight, contentTop, contentBottom;

    public RecipeTreeScreen(EntryStack<?> rootStack, Screen previous) {
        super(Component.translatable("gui.better_rei.tree.title", rootStack.asFormattedText().copy().withStyle(ChatFormatting.YELLOW)));
        this.previous = previous;
        // r35 方案B：上次的分类筛选结果从配置里恢复
        hiddenCategories.addAll(BetterReiConfig.treeHiddenCategories());
        this.root = new Node(ITEM, 0, null);
        this.root.stack = rootStack;
        this.root.need = targetCount;
        this.generation = TreeIndex.generation();
        expand(this.root);
    }

    public Screen getPrevious() {
        return previous;
    }

    // --------------------------------------------------------------- layout

    private void layout() {
        panelX = 8;
        panelY = 6;
        panelW = Math.max(200, this.width - 16);
        panelH = Math.max(140, this.height - 12);
        contentLeft = panelX + 7;
        contentRight = panelX + panelW - 7;
        contentTop = panelY + HEADER;
        contentBottom = panelY + panelH - FOOTER;
        if (contentBottom < contentTop + ROW_H) contentBottom = contentTop + ROW_H;
    }

    private int rowLevel(Node node) {
        return Math.min(node.depth, MAX_DRAW_DEPTH);
    }

    private int rowLeft(Node node) {
        return contentLeft + rowLevel(node) * INDENT;
    }

    @Override
    protected void init() {
        layout();
        // audit r38 P3-3：resize 会重跑 init()，但按钮几何全换、下拉矩形还是旧帧的——
        // 拖拽不复位的话，改完窗口大小动一下鼠标分类列表就自己滑。背后那根树滚动条同款。
        filterScrollDragging = false;
        scrollbarDragging = false;
        hintText = Component.translatable("gui.better_rei.tree.hint").getString();
        reloadedText = Component.translatable("gui.better_rei.tree.reloaded").getString();
        expandingText = Component.translatable("gui.better_rei.tree.expanding").getString();
        cappedText = Component.translatable("gui.better_rei.tree.expand_capped", EXPAND_ALL_CAP).getString();
        // audit r38 P3-1：下拉表头文字进缓存（旧版每帧现场翻译）
        filterHeadText = Component.translatable("gui.better_rei.tree.filter.title").getString();
        filterAllText = Component.translatable("gui.better_rei.tree.filter.all").getString();
        filterAllTextW = this.font.width(filterAllText);
        filterEmptyText = Component.translatable("gui.better_rei.tree.filter.empty").getString();
        filterHeadTrimKey = Integer.MIN_VALUE;
        footerTrimWidth = Integer.MIN_VALUE;
        snapshotInventory();
        updateTargetText();
        int gap = 6;
        int w1 = 104, w2 = 84, w3 = 84, w4 = 96;
        // audit r38 P2-1：GUI 宽 <398 时四颗固定宽按钮会画到面板外。
        // 放不下就整组等比压缩，单颗下限 64px——再挤也只是文字截短，不再溢出。
        int base = w1 + w2 + w3 + w4;
        int avail = panelW - 12;
        if (base + gap * 3 > avail) {
            double k = (avail - gap * 3) / (double) base;
            w1 = Math.max(64, (int) Math.round(w1 * k));
            w2 = Math.max(64, (int) Math.round(w2 * k));
            w3 = Math.max(64, (int) Math.round(w3 * k));
            w4 = Math.max(64, (int) Math.round(w4 * k));
        }
        if (w1 + w2 + w3 + w4 + gap * 3 > avail) {
            // 极窄面板（GUI scale 4 的 800×600）：64 的下限也放不下，再压一轮，
            // 硬下限 40——文字会省略号，但绝不再画到面板外。
            double k = (avail - gap * 3) / (double) (w1 + w2 + w3 + w4);
            w1 = Math.max(40, (int) Math.round(w1 * k));
            w2 = Math.max(40, (int) Math.round(w2 * k));
            w3 = Math.max(40, (int) Math.round(w3 * k));
            w4 = Math.max(40, (int) Math.round(w4 * k));
        }
        // r35 方案B：第四个按钮是"分类筛选"。整组居中，但起点不许跑进面板左边距之外
        int bx = Math.max(panelX + 6, (this.width - (w1 + w2 + w3 + w4 + gap * 3)) / 2);
        int by = panelY + 28;
        this.modeButton = Button.builder(modeLabel(), b -> {
            usagesMode = !usagesMode;
            resetTree();
            b.setMessage(modeLabel());
        }).bounds(bx, by, w1, 20).build();
        addRenderableWidget(modeButton);
        addRenderableWidget(Button.builder(Component.translatable("gui.better_rei.tree.expand_all"), (b) -> expandAll())
                .bounds(bx + w1 + gap, by, w2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.better_rei.tree.collapse_all"), (b) -> collapseAll())
                .bounds(bx + w1 + gap + w2 + gap, by, w3, 20).build());
        this.filterButton = Button.builder(filterLabel(), b -> {
            if (filterOpen) {
                filterOpen = false;
                filterScrollDragging = false;
            } else {
                refreshFilterOptions();
                filterScroll = 0;
                filterOpen = true;
            }
        }).bounds(bx + w1 + gap + w2 + gap + w3 + gap, by, w4, 20).build();
        addRenderableWidget(filterButton);
        rebuildFlat();
    }

    /** 按钮文字：没藏分类就写"分类筛选"，藏了就把数量标出来。 */
    private Component filterLabel() {
        return hiddenCategories.isEmpty()
                ? Component.translatable("gui.better_rei.tree.filter")
                : Component.translatable("gui.better_rei.tree.filter.hide", hiddenCategories.size());
    }

    /** 打开下拉时现取一遍 REI 的分类注册表（按标题排序，坏一个不拖累整表）。 */
    private void refreshFilterOptions() {
        List<CategoryOption> out = new ArrayList<>();
        // audit r38 P2-8：注册表里"真实存在"的全部 id——交集按它算，
        // 不是只按成功建出画面的行（某分类只是图标坏了，不该顺手取消它的隐藏）
        Set<String> live = new HashSet<>();
        boolean complete = true;
        try {
            for (CategoryRegistry.CategoryConfiguration<?> cfg : CategoryRegistry.getInstance()) {
                String id;
                try {
                    id = cfg.getCategoryIdentifier().getIdentifier().toString();
                } catch (Throwable ignored) {
                    complete = false;
                    continue;
                }
                live.add(id);
                try {
                    DisplayCategory<?> cat = cfg.getCategory();
                    out.add(new CategoryOption(id, cat.getTitle().getString(), cat.getIcon()));
                } catch (Throwable ignored) {
                    // 某个模组分类坏了标题/图标：这一行不显示，但它仍然"存在"
                }
            }
        } catch (Throwable ignored) {
            complete = false;
        }
        out.sort(Comparator.comparing(CategoryOption::title));
        filterOptions = out;
        // r38 P2-8：换整合包 / REI 改分类 id 后，旧 id 会永远留在 better_rei.toml 里，
        // 按钮上的"隐藏 N"计数也跟着虚高。注册表可用时求一次交集，清掉并存盘。
        // r40 P2-1：但重载中途注册表可能只填了一半（live 非空却缺项），retainAll
        // 会把仍然有效的隐藏 id 当死 id 删掉并立刻写盘——重载期间跳过清理，
        // 下拉列表照常显示，下一次点开再清（最坏只是僵尸 id 晚一轮退场）。
        if (complete && !live.isEmpty() && !hiddenCategories.isEmpty() && !PluginManager.areAnyReloading()) {
            if (hiddenCategories.retainAll(live)) {
                BetterReiConfig.setTreeHiddenCategories(new ArrayList<>(hiddenCategories));
                if (filterButton != null) filterButton.setMessage(filterLabel());
                // 被清掉的都是已经不存在的分类——本来就没藏住任何东西，树不用重建
            }
        }
    }

    /** 勾 / 取消一个分类：存配置 + 按当前展开状态重放建树（隐藏/恢复的分支自己消失/回来）。 */
    private void toggleCategoryHidden(String id) {
        if (!hiddenCategories.remove(id)) hiddenCategories.add(id);
        BetterReiConfig.setTreeHiddenCategories(new ArrayList<>(hiddenCategories));
        if (filterButton != null) filterButton.setMessage(filterLabel());
        reloadTree();
    }

    private void clearFilter() {
        if (hiddenCategories.isEmpty()) return;
        hiddenCategories.clear();
        BetterReiConfig.setTreeHiddenCategories(List.of());
        if (filterButton != null) filterButton.setMessage(filterLabel());
        reloadTree();
    }

    private Component modeLabel() {
        return Component.translatable(usagesMode ? "gui.better_rei.tree.mode.usages" : "gui.better_rei.tree.mode.recipes");
    }

    // ------------------------------------------------------------- building

    /** Rebuild the whole tree from the still-held root stack (mode switch: fresh tree). */
    private void resetTree() {
        cancelExpandAll();
        EntryStack<?> stack = this.root.stack;
        this.root = new Node(ITEM, 0, null);
        this.root.stack = stack;
        this.root.need = targetCount;
        this.scroll = 0;
        this.generation = TreeIndex.generation();
        expand(this.root);
    }

    /**
     * audit r10 P2-4: a REI reload invalidates every Display, so the tree has
     * to be rebuilt — but the player's expansion state and scroll offset are
     * kept: every expanded branch is replayed on the rebuilt tree.
     *
     * audit r12 P2-2: a route used to carry positions only (the 3rd recipe,
     * the 2nd ingredient), so after a reload that changed recipe order or
     * content it cheerfully re-expanded a *different* branch. Every route step
     * now also carries a content key: category, inputs and outputs for a recipe
     * row, and the ingredient's fuzzy hash for an item row. A step matches a row
     * with the same key, and a branch whose key is gone stays collapsed.
     */
    private void reloadTree() {
        cancelExpandAll();
        titleCache.clear(); // categories may have changed with the reload
        boolean rootWasExpanded = root.expanded;
        List<Route> routes = captureExpandedRoutes();
        double keepScroll = this.scroll;
        EntryStack<?> stack = this.root.stack;
        this.root = new Node(ITEM, 0, null);
        this.root.stack = stack;
        this.root.need = targetCount;
        this.generation = TreeIndex.generation();
        this.root.expanded = rootWasExpanded;
        if (rootWasExpanded) {
            buildChildren(this.root);
            restoreRoutes(routes);
        }
        this.scroll = keepScroll;
        rebuildFlat(); // also clamps the scroll to the new content height
    }

    // --------------------------------------------- expansion restore (P2-4)

    /** One expanded path from the root: indices page the rows, keys prove identity. */
    private record Route(int[] indices, String[] keys) {}

    private List<Route> captureExpandedRoutes() {
        List<Route> routes = new ArrayList<>();
        captureRoutes(this.root, new ArrayList<>(), new ArrayList<>(), routes);
        return routes;
    }

    private void captureRoutes(Node node, List<Integer> prefix, List<String> keys, List<Route> out) {
        if (!node.expanded) return;
        if (node != root) out.add(new Route(toArray(prefix), keys.toArray(new String[0])));
        for (Node child : node.children) {
            if (!child.expanded) continue;
            if (child.kind == RECIPE) {
                prefix.add(child.recipeIndex);
                keys.add(recipeKey(child));
                captureRoutes(child, prefix, keys, out);
                prefix.remove(prefix.size() - 1);
                keys.remove(keys.size() - 1);
            } else if (node.kind == RECIPE && child.kind == ITEM) {
                prefix.add(child.ingredientIndex);
                keys.add(stackKey(child.stack));
                captureRoutes(child, prefix, keys, out);
                prefix.remove(prefix.size() - 1);
                keys.remove(keys.size() - 1);
            }
        }
    }

    private static int[] toArray(List<Integer> prefix) {
        int[] route = new int[prefix.size()];
        for (int i = 0; i < route.length; i++) route[i] = prefix.get(i);
        return route;
    }

    /** Stable identity of a recipe row across a registry reload. */
    private static String recipeKey(Node recipeNode) {
        if (recipeNode.recipeKey == null) {
            Display display = recipeNode.display;
            StringBuilder key = new StringBuilder(String.valueOf(display.getCategoryIdentifier()));
            var location = display.getDisplayLocation();
            if (location.isPresent()) {
                key.append('|').append(location.get());
            } else {
                appendIngredientKeys(key, display.getInputEntries());
                key.append('>');
                appendIngredientKeys(key, display.getOutputEntries());
            }
            recipeNode.recipeKey = key.toString();
        }
        return recipeNode.recipeKey;
    }

    private static void appendIngredientKeys(StringBuilder key, List<EntryIngredient> ingredients) {
        for (EntryIngredient ingredient : ingredients) {
            key.append('[');
            for (EntryStack<?> stack : ingredient) {
                key.append(EntryStacks.hashExact(stack)).append(':').append(TreeIndex.amountOf(stack)).append(',');
            }
            key.append(']');
        }
    }

    private static String stackKey(EntryStack<?> stack) {
        return stack == null ? "" : Long.toString(EntryStacks.hashFuzzy(stack));
    }

    private void restoreRoutes(List<Route> routes) {
        for (Route route : routes) {
            restoreRoute(route);
        }
    }

    private void restoreRoute(Route route) {
        int[] indices = route.indices();
        String[] keys = route.keys();
        if (indices.length == 0) return;
        Node node = root;
        for (int i = 0; i < indices.length; i++) {
            if (!node.built) buildChildren(node);
            Node next;
            if (node.kind == ITEM) {
                // make sure the page holding this recipe row exists again
                while (indices[i] >= node.shownCount && node.shownCount < node.pendingRecipes.size()) {
                    showMore(node);
                }
                next = findRecipeChild(node, indices[i], keys[i]);
            } else if (node.kind == RECIPE) {
                next = findIngredientChild(node, indices[i], keys[i]);
            } else {
                return;
            }
            if (next == null) return; // content changed -> keep this branch collapsed
            node = next;
            node.expanded = true;
            // rows below an expanded node must exist, otherwise the replayed
            // node would show up as expanded but empty
            buildChildren(node);
        }
    }

    /** Same content key beats same position: recipe order may shift on reload. */
    private static Node findRecipeChild(Node itemNode, int index, String key) {
        Node fallback = null;
        for (Node child : itemNode.children) {
            if (child.kind != RECIPE || !key.equals(recipeKey(child))) continue;
            if (child.recipeIndex == index) return child;
            if (fallback == null) fallback = child;
        }
        return fallback;
    }

    private static Node findIngredientChild(Node recipeNode, int index, String key) {
        Node fallback = null;
        for (Node child : recipeNode.children) {
            if (child.kind != ITEM || !key.equals(stackKey(child.stack))) continue;
            if (child.ingredientIndex == index) return child;
            if (fallback == null) fallback = child;
        }
        return fallback;
    }

    private void expand(Node node) {
        buildChildren(node);
        // a cycle node (A needs B needs A) has no children by construction:
        // expanding it would show a "▼" with nothing underneath
        node.expanded = !node.cycle && !node.children.isEmpty();
        rebuildFlat();
    }

    private void collapse(Node node) {
        node.expanded = false;
        rebuildFlat();
    }

    private void toggle(Node node) {
        if (node.cycle) return;
        // audit r38 P2-5：只有"折叠"和正在跑的展开全部真冲突（泵会把你刚关的
        // 分支再打开），这时才取消泵。手动展开和泵同向，旧版误点一下展开键
        // 就把剩下几千个节点全放弃了。
        if (node.expanded) {
            cancelExpandAll();
            collapse(node);
        } else {
            expand(node);
        }
    }

    private void buildChildren(Node node) {
        if (node.built) return;
        node.built = true;
        node.children = new ArrayList<>();
        if (node.kind == ITEM) {
            if (isCycle(node)) {
                node.cycle = true;
                return;
            }
            List<Display> found = new ArrayList<>(usagesMode ? TreeIndex.consuming(node.stack) : TreeIndex.producing(node.stack));
            // r35 方案B：顶栏分类筛选先摘掉被关分类的配方，再排序
            int totalBeforeFilter = found.size();
            if (!hiddenCategories.isEmpty()) {
                found.removeIf(d -> hiddenCategories.contains(d.getCategoryIdentifier().getIdentifier().toString()));
            }
            // P2-2: fully deterministic order — category title first, then
            // the display's registration position, so the layout is identical
            // across sessions and across two opens of the same item.
            if (found.size() > 1) {
                Map<Display, String> titles = new IdentityHashMap<>(found.size() * 2);
                for (Display display : found) {
                    titles.put(display, displayTitle(display));
                }
                IdentityHashMap<Display, Integer> order = TreeIndex.currentOrder();
                found.sort(Comparator.comparing((Display d) -> titles.getOrDefault(d, ""))
                        .thenComparingInt(d -> order.getOrDefault(d, Integer.MAX_VALUE)));
            }
            if (found.isEmpty()) {
                // 区分"本来就找不到"和"都被筛选关了"——后者要告诉玩家去哪打开
                node.children.add(labelNode(totalBeforeFilter == 0
                        ? (usagesMode ? "gui.better_rei.tree.no_usages" : "gui.better_rei.tree.no_recipes")
                        : "gui.better_rei.tree.filtered_out", node));
                return;
            }
            node.pendingRecipes = found;
            showMore(node);
            assignBatches(node);
        } else if (node.kind == RECIPE) {
            List<EntryIngredient> ingredients = usagesMode ? node.display.getOutputEntries() : node.display.getInputEntries();
            for (int index = 0; index < ingredients.size(); index++) {
                EntryIngredient ingredient = ingredients.get(index);
                if (ingredient.isEmpty()) continue;
                Node child = new Node(ITEM, node.depth + 1, node);
                child.stack = ingredient.get(0);
                child.ingredient = ingredient;
                child.altCount = ingredient.size();
                child.ingredientIndex = index;
                // 每批次消耗量 × 批次 = 这一行要几个（连乘到深层会爆 long，饱和封顶，P2-2）
                child.need = node.batches > 0 ? mulCap(node.batches, TreeIndex.amountOf(child.stack)) : -1;
                node.children.add(child);
            }
            if (node.children.isEmpty()) {
                node.children.add(labelNode("gui.better_rei.tree.no_entries", node));
            }
        } else if (node.kind == MORE) {
            // handled by showMore via parent
        }
    }

    private void showMore(Node itemNode) {
        int cap = BetterReiConfig.treeMaxRecipes();
        int from = itemNode.shownCount;
        int to = Math.min(itemNode.pendingRecipes.size(), from + cap);
        itemNode.children.removeIf(n -> n.kind == MORE);
        for (int i = from; i < to; i++) {
            Node recipeNode = new Node(RECIPE, itemNode.depth + 1, itemNode);
            recipeNode.display = itemNode.pendingRecipes.get(i);
            recipeNode.recipeIndex = i;
            itemNode.children.add(recipeNode);
        }
        itemNode.shownCount = to;
        if (to < itemNode.pendingRecipes.size()) {
            Node more = new Node(MORE, itemNode.depth + 1, itemNode);
            more.label = Component.translatable("gui.better_rei.tree.more", itemNode.pendingRecipes.size() - to);
            itemNode.children.add(more);
        }
    }

    /** P3-3: right-click on the "more" row drops back to the first page. */
    private void collapseShown(Node itemNode) {
        int cap = BetterReiConfig.treeMaxRecipes();
        int keep = Math.min(cap, itemNode.pendingRecipes.size());
        itemNode.children.removeIf(n -> n.kind == MORE);
        int recipes = 0;
        for (Node n : itemNode.children) if (n.kind == RECIPE) recipes++;
        for (int i = itemNode.children.size() - 1; i >= 0 && recipes > keep; i--) {
            if (itemNode.children.get(i).kind == RECIPE) {
                itemNode.children.remove(i);
                recipes--;
            }
        }
        itemNode.shownCount = keep;
        if (keep < itemNode.pendingRecipes.size()) {
            Node more = new Node(MORE, itemNode.depth + 1, itemNode);
            more.label = Component.translatable("gui.better_rei.tree.more", itemNode.pendingRecipes.size() - keep);
            itemNode.children.add(more);
        }
    }

    private Node labelNode(String key, Node parent) {
        Node node = new Node(LABEL, parent.depth + 1, parent);
        node.label = Component.translatable(key).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        return node;
    }

    // ------------------------------------------------------- 备料数量 (r19)

    /**
     * 折中方案：每层只沿排第一的那条配方算需求量。
     * <p>
     * EMI 的树一个物品只有一条合成路线，所以"做 1 个要多少原料"有唯一答案；
     * 这里的树是一个物品底下平铺所有配方、可以同时展开好几条，全算会互相
     * 打架，只沿第一条算才是确定的，行尾再用"另 N 条未计"说明这次没算它们。
     */
    private void assignBatches(Node itemNode) {
        if (usagesMode) return;
        if (itemNode.need <= 0) return;
        Node first = null;
        for (Node child : itemNode.children) {
            if (child.kind == RECIPE) {
                first = child;
                break;
            }
        }
        if (first == null) return; // "找不到配方" 的那种占位行
        long perCraft = outputAmount(first.display, itemNode.stack);
        if (perCraft <= 0) perCraft = 1;
        first.batches = ceilDiv(itemNode.need, perCraft);
        itemNode.otherRecipes = Math.max(0, itemNode.pendingRecipes.size() - 1);
    }

    private static long ceilDiv(long value, long unit) {
        return (value + unit - 1) / unit;
    }

    /** 饱和乘法（audit r22 P2-2）：深层 ×64 连乘会溢出成负数，封顶到 NEED_CAP。 */
    private static long mulCap(long a, long b) {
        if (a <= 0 || b <= 0) return 0L;
        if (a > NEED_CAP / b) return NEED_CAP;
        long r = a * b;
        return r > NEED_CAP ? NEED_CAP : r;
    }

    /** 一条配方产出多少个目标物品（同一物品占多个输出槽时累加）。 */
    private static long outputAmount(Display display, EntryStack<?> target) {
        if (target == null) return 1;
        long total = 0;
        for (EntryIngredient ingredient : display.getOutputEntries()) {
            for (EntryStack<?> stack : ingredient) {
                if (EntryStacks.equalsFuzzy(stack, target)) {
                    total += TreeIndex.amountOf(stack);
                }
            }
        }
        return total;
    }

    /**
     * 打开界面时把背包扫一遍存成 hash -> 数量。
     * 界面开着的时候玩家动不了背包，所以只在 init / 重载时读一次就够了。
     */
    private void snapshotInventory() {
        Map<Long, Long> counts = new HashMap<>();
        if (this.minecraft != null && this.minecraft.player != null) {
            Inventory inventory = this.minecraft.player.getInventory();
            for (ItemStack stack : inventory.items) addToSnapshot(counts, stack);
            for (ItemStack stack : inventory.offhand) addToSnapshot(counts, stack);
        }
        this.inventory = counts;
        this.footerTrimWidth = Integer.MIN_VALUE; // 提示文字里带着缺项数
    }

    private static void addToSnapshot(Map<Long, Long> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        counts.merge(EntryStacks.hashFuzzy(EntryStacks.of(stack)), (long) stack.getCount(), Long::sum);
    }

    /** 物品行的 hash 只算一次：分配整列每次点击都要重跑（audit r22 P2-1）。 */
    private static long nodeHash(Node node) {
        if (!node.hashReady) {
            node.cachedHash = node.stack == null ? 0L : EntryStacks.hashFuzzy(node.stack);
            node.hashReady = true;
        }
        return node.cachedHash;
    }

    /**
     * "任意木板"这类可替换槽的候选 hash，整组缓存一次。
     * 旧版只认前 32 个候选（怕每帧扫描），现在扫的是缓存好的 long 数组，
     * 上限放到 512，"任意杂物"槽明明有货也不会再报"差"。
     */
    private static long[] alternateHashes(Node node) {
        if (node.altHashes == null) {
            int limit = Math.min(node.ingredient.size(), MAX_ALTERNATIVES);
            long[] hashes = new long[limit];
            int n = 0;
            for (EntryStack<?> stack : node.ingredient) {
                if (n >= limit) break;
                if (stack == null || stack.isEmpty()) continue;
                hashes[n++] = EntryStacks.hashFuzzy(stack);
            }
            node.altHashes = n == limit ? hashes : Arrays.copyOf(hashes, n);
        }
        return node.altHashes;
    }

    /**
     * 从"还剩多少可用"的池子里给这一行分料，分多少是多少。
     * 同一格木板被上一条分支分走之后，这一行只能拿剩下的——这才是真够不够。
     */
    private long allocateFrom(HashMap<Long, Long> left, Node node) {
        long want = node.need;
        if (want <= 0 || left.isEmpty()) return 0L;
        if (node.ingredient != null && node.altCount > 1) {
            long taken = 0L;
            for (long hash : alternateHashes(node)) {
                Long avail = left.get(hash);
                if (avail == null || avail <= 0) continue;
                long use = Math.min(avail, want - taken);
                left.put(hash, avail - use);
                taken += use;
                if (taken >= want) break;
            }
            return taken;
        }
        long hash = nodeHash(node);
        Long avail = left.get(hash);
        if (avail == null || avail <= 0) return 0L;
        long use = Math.min(avail, want);
        left.put(hash, avail - use);
        return use;
    }

    /**
     * 把"还能拿出多少"自上而下分给当前可见的每一行，顺便数一下还缺几项。
     * <p>
     * audit r22 P1-1：旧版每行都拿整个背包比一次、从不扣减，两条分支都要
     * 木板时两行都说"够"，照清单拿料必缺。现在按列表顺序消耗，和 EMI 的
     * 购物清单一致：排在前的先拿，后面的行只能用剩下的。
     * audit r22 P3-1：一行的分配结果变了就让它的文字缓存失效，
     * 否则折叠上面一条分支后，下面还挂着过期的"差 N"。
     */
    private void recomputeProgress() {
        int missing = 0;
        if (!usagesMode) {
            HashMap<Long, Long> left = new HashMap<>(inventory);
            for (Node node : flat) {
                if (node.kind != ITEM) continue;
                if (node.need <= 0) {
                    node.have = -1;
                    continue;
                }
                long taken = allocateFrom(left, node);
                if (taken != node.have) {
                    node.have = taken;
                    node.textReady = false;
                }
                if (taken < node.need) missing++;
            }
        }
        this.missingItems = missing;
    }

    private void adjustTarget(long delta) {
        long next = Math.max(TARGET_MIN, Math.min(TARGET_MAX, targetCount + delta));
        if (next == targetCount) return;
        targetCount = next;
        updateTargetText();
        resetTree();
    }

    private void updateTargetText() {
        targetText = Component.translatable("gui.better_rei.tree.target", targetCount).getString();
        targetTextW = this.font.width(targetText);
        targetMinusX = panelX + 10;
        targetTextX = targetMinusX + 12 + 4;
        targetPlusX = targetTextX + targetTextW + 4;
        targetBtnY = panelY + 5;
    }

    private boolean overTargetButton(double mouseX, double mouseY, int buttonX) {
        return mouseX >= buttonX && mouseX < buttonX + 12 && mouseY >= targetBtnY && mouseY < targetBtnY + 12;
    }

    private boolean isCycle(Node node) {
        if (node.stack == null) return false;
        long h = EntryStacks.hashFuzzy(node.stack);
        for (Node p = node.parent; p != null; p = p.parent) {
            if (p.kind == ITEM && p.stack != null && EntryStacks.hashFuzzy(p.stack) == h
                    && EntryStacks.equalsFuzzy(p.stack, node.stack)) {
                return true;
            }
        }
        return false;
    }

    /** "展开全部"的总节点安全上限（r38 P3-4：撞上限要在页脚说明，不能静默） */
    private static final int EXPAND_ALL_CAP = 4000;

    private void expandAll() {
        expandQueue.clear();
        expandQueue.add(root);
        expandRemaining = EXPAND_ALL_CAP;
        expandingAll = true;
        expandCapped = false;
        expandSinceRebuild = 0;
    }

    /**
     * Pump the expand-all queue with a per-frame time budget (TickWork idea
     * from jeioptimizations). At least one node is always processed so the run
     * can never starve.
     *
     * audit r38 P1-1: the run no longer pays for a full rebuildFlat() on every
     * pumped frame. The old tail cost (flatten the whole tree + re-hand out
     * the inventory to every row) was O(rows) per frame — exactly the frame
     * drops the pump was supposed to remove. Mid-run the flat list is only
     * refreshed every EXPAND_REBUILD_EVERY new nodes and WITHOUT the
     * allocation pass (the tree still grows, any allocation is stale the next
     * frame anyway); one full rebuild with progress runs when the queue
     * drains. Item rows built mid-run show no "需/够" column until then
     * (ensureText skips it while have == -1) instead of a wrong red "差".
     */
    private void pumpExpandAll() {
        if (!expandingAll) return;
        long deadline = System.nanoTime() + EXPAND_BUDGET_NANOS;
        int maxDepth = BetterReiConfig.treeMaxDepth();
        int made = 0;
        boolean did = false;
        while (!expandQueue.isEmpty() && expandRemaining > 0) {
            if (did && System.nanoTime() >= deadline) break;
            Node node = expandQueue.poll();
            if (node.depth < maxDepth) {
                buildChildren(node);
                if (node.kind == ITEM && !node.cycle && !node.children.isEmpty()) {
                    node.expanded = true;
                } else if (node.kind == RECIPE) {
                    node.expanded = true;
                }
                for (Node child : node.children) {
                    expandQueue.add(child);
                    expandRemaining--;
                }
                made += node.children.size();
            }
            did = true;
        }
        if (expandQueue.isEmpty() || expandRemaining <= 0) {
            expandingAll = false;
            // r38 P3-4: queue drained = done; ran out of budget = truncated, say so
            expandCapped = expandRemaining <= 0 && !expandQueue.isEmpty();
        }
        if (!did) return;
        if (expandingAll) {
            expandSinceRebuild += made;
            if (expandSinceRebuild >= EXPAND_REBUILD_EVERY) {
                expandSinceRebuild = 0;
                rebuildFlatLite();
            }
        } else {
            rebuildFlat();
        }
    }

    private void cancelExpandAll() {
        expandingAll = false;
        expandQueue.clear();
        expandCapped = false;
    }

    private void collapseAll() {
        cancelExpandAll();
        collapseNode(root);
        rebuildFlat();
    }

    private void collapseNode(Node node) {
        node.expanded = false;
        if (node.built) {
            for (Node child : node.children) {
                collapseNode(child);
            }
        }
    }

    private void rebuildFlat() {
        rebuildFlatLite();
        recomputeProgress();
    }

    /** audit r38 P1-1: flatten + scroll clamp without the inventory allocation pass. */
    private void rebuildFlatLite() {
        layout();
        flat.clear();
        flatten(root);
        double max = maxScroll();
        if (scroll > max) scroll = max;
        if (scroll < 0) scroll = 0;
    }

    private void flatten(Node node) {
        flat.add(node);
        if (node.expanded) {
            for (Node child : node.children) {
                flatten(child);
            }
        }
    }

    private double maxScroll() {
        return Math.max(0, flat.size() * ROW_H - (contentBottom - contentTop));
    }

    private int visibleHeight() {
        return Math.max(ROW_H, contentBottom - contentTop);
    }

    // ------------------------------------------------------------- rendering

    private Component categoryTitle(Display display) {
        CategoryIdentifier<?> id = display.getCategoryIdentifier();
        return CategoryRegistry.getInstance().tryGet((CategoryIdentifier<Display>) (CategoryIdentifier<?>) id)
                .map(cfg -> cfg.getCategory().getTitle())
                .orElseGet(() -> Component.literal(id.toString()));
    }

    /**
     * The title as a plain string, resolved once per category for the whole
     * screen (audit r24, Precompute-style: expensive strings are computed and
     * reused, not re-formatted for every display on every expansion).
     */
    private String displayTitle(Display display) {
        return titleCache.computeIfAbsent(display.getCategoryIdentifier(),
                id -> categoryTitle(display).getString());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        // audit r24: fill in a progressive "expand all" before drawing the frame
        pumpExpandAll();
        layout();
        hoverTargetMinus = !usagesMode && targetVisible && overTargetButton(mouseX, mouseY, targetMinusX);
        hoverTargetPlus = !usagesMode && targetVisible && overTargetButton(mouseX, mouseY, targetPlusX);
        // dim the world first — the panel below is what makes the text readable
        renderBackground(guiGraphics);
        drawPanel(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, delta);

        Node hovered = rowAt(mouseX, mouseY);
        // clip rows to the content box: neither the title/buttons above nor the
        // hint bar below may be painted over by a half-scrolled row
        guiGraphics.enableScissor(contentLeft - 2, contentTop, contentRight + 2, contentBottom);
        // audit r14: jump straight to the first visible row instead of walking
        // (and skipping) everything above the viewport.
        int first = Math.max(0, (int) Math.floor(scroll / ROW_H));
        int end = Math.min(flat.size(), first + visibleHeight() / ROW_H + 2);
        int rowTop = contentTop - (int) Math.round(scroll - first * (double) ROW_H);
        for (int i = first; i < end; i++, rowTop += ROW_H) {
            if (rowTop >= contentBottom) break;
            Node node = flat.get(i);
            drawRow(guiGraphics, node, rowTop, node == hovered, (i & 1) == 1);
        }
        guiGraphics.disableScissor();

        drawHeader(guiGraphics);
        drawScrollbar(guiGraphics, mouseX, mouseY);
        drawFooter(guiGraphics);
        // r35 方案B：分类筛选下拉盖在所有东西最上面
        drawFilterDropdown(guiGraphics, mouseX, mouseY);
    }

    private void drawPanel(GuiGraphics guiGraphics) {
        guiGraphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, C_PANEL);
        guiGraphics.fill(panelX, panelY, panelX + panelW, panelY + 1, C_BORDER);
        guiGraphics.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, C_BORDER);
        guiGraphics.fill(panelX, panelY, panelX + 1, panelY + panelH, C_BORDER);
        guiGraphics.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, C_BORDER);
    }

    private void drawHeader(GuiGraphics guiGraphics) {
        // P3-2: 模式参与缓存键——缺项后缀只在配方模式拼，切换模式必须重算
        if (cachedRowCount != flat.size() || cachedMissingItems != missingItems || cachedUsagesMode != usagesMode) {
            cachedRowCount = flat.size();
            cachedMissingItems = missingItems;
            cachedUsagesMode = usagesMode;
            cachedCountText = Component.translatable("gui.better_rei.tree.count", flat.size()).getString();
            if (!usagesMode && missingItems > 0) {
                cachedCountText += " · " + Component.translatable("gui.better_rei.tree.missing", missingItems).getString();
            }
            cachedCountW = this.font.width(cachedCountText);
        }
        // P3-3: 左（目标控件）· 中（标题）· 右（行数）三块先各占一段，标题在
        // 剩余空间里居中、放不下就裁短，谁也不压谁
        int countX = panelX + panelW - 10 - cachedCountW;
        guiGraphics.drawString(this.font, cachedCountText, countX, panelY + 11, C_SUB, true);
        int left = panelX + 8;
        int right = countX - 8;
        targetVisible = false;
        if (!usagesMode) {
            int blockW = targetPlusX + 12 - targetMinusX;
            if (left + blockW + 10 <= right - MIN_HEADER_TITLE) {
                targetVisible = true;
                left = targetPlusX + 12 + 10;
            }
        }
        int avail = right - left;
        if (avail > 4) {
            if (titleW < 0) titleW = this.font.width(this.title);
            int cx = left + avail / 2;
            if (titleW <= avail) {
                guiGraphics.drawCenteredString(this.font, this.title, cx, panelY + 9, 0xFFFFFFFF);
            } else {
                if (headerTitleKey != avail) {
                    headerTitleKey = avail;
                    headerTitleTrim = trim(this.title.getString(), avail);
                }
                guiGraphics.drawCenteredString(this.font, headerTitleTrim, cx, panelY + 9, 0xFFFFFFFF);
            }
        }
        if (targetVisible) drawTargetControl(guiGraphics);
        int sepY = panelY + HEADER - 8;
        guiGraphics.fill(panelX + 1, sepY, panelX + panelW - 1, sepY + 1, C_SEP);
    }

    /** "[−] 目标 ×1 [+]" — 想在树里做几个根物品就调这里。太窄时整块不画（targetVisible）。 */
    private void drawTargetControl(GuiGraphics guiGraphics) {
        drawSmallButton(guiGraphics, targetMinusX, "−", hoverTargetMinus);
        guiGraphics.drawString(this.font, targetText, targetTextX, targetBtnY + 2, C_SUB, true);
        drawSmallButton(guiGraphics, targetPlusX, "+", hoverTargetPlus);
    }

    private void drawSmallButton(GuiGraphics guiGraphics, int x, String glyph, boolean hovered) {
        guiGraphics.fill(x, targetBtnY, x + 12, targetBtnY + 12, hovered ? 0x66FFFFFF : 0x33FFFFFF);
        guiGraphics.fill(x, targetBtnY, x + 12, targetBtnY + 1, 0x77FFFFFF);
        int glyphW = this.font.width(glyph);
        guiGraphics.drawString(this.font, glyph, x + (12 - glyphW) / 2, targetBtnY + 2, 0xFFFFFFFF, true);
    }

    private void drawFooter(GuiGraphics guiGraphics) {
        int y = panelY + panelH - FOOTER;
        guiGraphics.fill(panelX + 1, y, panelX + panelW - 1, y + 1, C_SEP);
        int maxW = panelW - 24;
        if (footerTrimWidth != maxW) {
            footerTrimWidth = maxW;
            footerHint = trim(hintText, maxW);
            footerReloaded = trim(reloadedText, maxW);
            footerExpanding = trim(expandingText, maxW);
            footerCapped = trim(cappedText, maxW);
        }
        // audit r24: while a progressive expand-all is running the footer says
        // so (gold), so the filling tree reads as "working", not as "lagging".
        String shown;
        int color;
        if (expandingAll) {
            shown = footerExpanding;
            color = C_GOLD;
        } else if (reloadNotice > 0) {
            shown = footerReloaded;
            color = C_GOLD;
        } else if (expandCapped) {
            // r38 P3-4: 撞 4000 节点上限截停——得让玩家知道剩下的没展开
            shown = footerCapped;
            color = C_GOLD;
        } else {
            shown = footerHint;
            color = C_SUB;
        }
        guiGraphics.drawString(this.font, shown, panelX + 10, y + 7, color, true);
    }

    // ------------------------------------------------ 分类筛选下拉 (r35 方案B)

    private static final int FILTER_BG = 0xFA12131A;

    /** 下拉贴在"分类筛选"按钮正下方，右对齐到面板右边。 */
    private Rectangle computeFilterRect(int w, int h) {
        int rx = filterButton.getX() + filterButton.getWidth() - w;
        rx = Math.max(panelX + 4, Math.min(rx, panelX + panelW - 4 - w));
        return new Rectangle(rx, filterButton.getY() + filterButton.getHeight() + 3, w, h);
    }

    private void clampFilterScroll() {
        int max = Math.max(0, filterOptions.size() - filterViewportRows);
        filterScroll = Math.max(0, Math.min(max, filterScroll));
    }

    private boolean inFilterRect(double mx, double my) {
        Rectangle r = filterRect;
        return r != null && mx >= r.x && mx < r.x + r.width && my >= r.y && my < r.y + r.height;
    }

    private void drawFilterDropdown(GuiGraphics g, int mouseX, int mouseY) {
        filterRect = null;
        if (!filterOpen || filterButton == null) return;
        int w = Math.min(240, panelW - 16);
        int avail = panelY + panelH - 6 - (filterButton.getY() + filterButton.getHeight() + 3);
        filterViewportRows = Math.max(1, Math.min(FILTER_MAX_ROWS, (avail - FILTER_HEADER - 4) / FILTER_ROW));
        clampFilterScroll();
        Rectangle r = computeFilterRect(w, FILTER_HEADER + filterViewportRows * FILTER_ROW + 4);
        filterRect = r;
        g.fill(r.x, r.y, r.x + r.width, r.y + r.height, FILTER_BG);
        g.fill(r.x, r.y, r.x + r.width, r.y + 1, C_BORDER);
        g.fill(r.x, r.y + r.height - 1, r.x + r.width, r.y + r.height, C_BORDER);
        g.fill(r.x, r.y, r.x + 1, r.y + r.height, C_BORDER);
        g.fill(r.x + r.width - 1, r.y, r.x + r.width, r.y + r.height, C_BORDER);

        // 表头：左说明、右"全部显示"。r38 P3-1：两处文字都吃缓存，不再每帧翻译
        int allX = r.x + r.width - 8 - filterAllTextW;
        int headMax = allX - r.x - 12;
        if (filterHeadTrimKey != headMax) {
            filterHeadTrimKey = headMax;
            filterHeadTrimmed = trim(filterHeadText, headMax);
        }
        g.drawString(this.font, filterHeadTrimmed, r.x + 8, r.y + 7, C_SUB, true);
        boolean allHot = !hiddenCategories.isEmpty();
        boolean allHover = allHot && overFilterAll(mouseX, mouseY);
        g.drawString(this.font, filterAllText, allX, r.y + 7,
                allHover ? C_GOLD : allHot ? C_AMOUNT : C_SEP, true);

        // 空状态：refreshFilterOptions 的注释早就承诺"面板显示'暂无分类'"，代码却没画。补上
        if (filterOptions.isEmpty()) {
            g.drawString(this.font, trim(filterEmptyText, r.width - 16), r.x + 8,
                    r.y + FILTER_HEADER + 6, C_SUB, true);
            return;
        }

        // 分类行：勾选框 + 整格图标 + 标题（r38 P3-1：宽度没变就直接用行上缓存的裁剪结果）
        int iconX = r.x + 24;
        int textX = iconX + ICON + 6;
        int maxTextW = r.x + r.width - 8 - textX - (filterOptions.size() > filterViewportRows ? 6 : 0);
        for (int i = 0; i < filterViewportRows; i++) {
            int idx = filterScroll + i;
            if (idx >= filterOptions.size()) break;
            CategoryOption opt = filterOptions.get(idx);
            boolean hidden = hiddenCategories.contains(opt.id());
            int ry = r.y + FILTER_HEADER + i * FILTER_ROW;
            if (mouseX >= r.x + 2 && mouseX < r.x + r.width - 6 && mouseY >= ry && mouseY < ry + FILTER_ROW) {
                g.fill(r.x + 2, ry, r.x + r.width - 6, ry + FILTER_ROW, C_HOVER);
            }
            int bx = r.x + 8, by = ry + 4;
            g.fill(bx, by, bx + 12, by + 12, 0xFF0A0B0F);
            g.fill(bx, by, bx + 12, by + 1, C_SEP);
            g.fill(bx, by + 11, bx + 12, by + 12, C_SEP);
            g.fill(bx, by, bx + 1, by + 12, C_SEP);
            g.fill(bx + 11, by, bx + 12, by + 12, C_SEP);
            if (!hidden) g.fill(bx + 3, by + 3, bx + 9, by + 9, C_GOLD);
            g.fill(iconX, ry + 1, iconX + ICON, ry + 1 + ICON, C_ICON_PLATE);
            try {
                Renderer icon = opt.icon();
                if (icon instanceof EntryStack<?> es) {
                    drawStackIcon(g, es, iconX, ry + 1);
                } else {
                    icon.render(g, new Rectangle(iconX, ry + 1, ICON, ICON), -1, -1, 0f);
                }
            } catch (Throwable ignored) {
                // 分类图标由模组提供，画不出来就留空底板
            }
            if (opt.titleTrimW != maxTextW) {
                opt.titleTrimW = maxTextW;
                opt.titleTrimmed = trim(opt.title(), maxTextW);
            }
            g.drawString(this.font, opt.titleTrimmed, textX, ry + 6, hidden ? C_SUB : C_TEXT, true);
        }

        // 迷你滚动条（r37：几何抽成方法，画、点、拖共用一套算法）
        if (filterOptions.size() > filterViewportRows) {
            int trackTop = filterTrackTop();
            int thumbH = filterThumbHeight();
            int thumbY = filterThumbTop();
            g.fill(r.x + r.width - 5, trackTop, r.x + r.width - 3, trackTop + filterTrackHeight(), 0x28FFFFFF);
            g.fill(r.x + r.width - 5, thumbY, r.x + r.width - 3, thumbY + thumbH, 0x99FFFFFF);
        }
    }

    /** 下拉滚动条轨道顶端 y（下拉矩形内的列表区顶部）。 */
    private int filterTrackTop() {
        Rectangle r = filterRect;
        return r == null ? 0 : r.y + FILTER_HEADER;
    }

    /** 下拉滚动条轨道高度 = 可见行总高。 */
    private int filterTrackHeight() {
        return filterViewportRows * FILTER_ROW;
    }

    /** 下拉滚动条滑块高度：可见占比，最低 12px 保证按得住。 */
    private int filterThumbHeight() {
        return Math.max(12, filterTrackHeight() * filterViewportRows / Math.max(1, filterOptions.size()));
    }

    /** 当前滚动量对应的滑块顶端 y。 */
    private int filterThumbTop() {
        int travel = Math.max(1, filterTrackHeight() - filterThumbHeight());
        double frac = filterScroll / (double) Math.max(1, filterOptions.size() - filterViewportRows);
        return filterTrackTop() + (int) Math.round(travel * frac);
    }

    /** 把滑块顶到指定 y，反推出滚动量并夹紧。 */
    private void scrollFilterThumbTo(int thumbTop) {
        int travel = Math.max(1, filterTrackHeight() - filterThumbHeight());
        double frac = (thumbTop - filterTrackTop()) / (double) travel;
        filterScroll = (int) Math.round(frac * (filterOptions.size() - filterViewportRows));
        clampFilterScroll();
    }

    /** 鼠标是否压在下拉滚动条（含 1px 富余）的轨道上。 */
    private boolean isOverFilterScrollbar(double mx, double my) {
        Rectangle r = filterRect;
        return r != null && filterOptions.size() > filterViewportRows
                && mx >= r.x + r.width - 7 && my >= filterTrackTop() && my < filterTrackTop() + filterTrackHeight();
    }

    /** 鼠标是否压在表头右侧"全部显示"那几个字上（audit r38 P1-2：热区以前是整条表头）。 */
    private boolean overFilterAll(double mx, double my) {
        Rectangle r = filterRect;
        if (r == null) return false;
        int allX = r.x + r.width - 8 - filterAllTextW;
        return mx >= allX - 2 && mx < allX + filterAllTextW + 2
                && my >= r.y + 3 && my < r.y + FILTER_HEADER;
    }

    /** 下拉开着时的点击；返回是否已消费。 */
    private boolean handleFilterClick(double mx, double my) {
        Rectangle r = filterRect;
        if (r == null) return false;
        if (my < r.y + FILTER_HEADER) {
            // r38 P1-2：只有点在"全部显示"上、且确实有东西可清，才清空筛选。
            // 表头其余区域（"勾选 = 显示"那行说明）以前点哪都全清——纯误触源。
            if (!hiddenCategories.isEmpty() && overFilterAll(mx, my)) {
                clearFilter();
            }
            return true;
        }
        if (isOverFilterScrollbar(mx, my)) {
            int thumbTop = filterThumbTop();
            int thumbH = filterThumbHeight();
            if (my >= thumbTop && my < thumbTop + thumbH) {
                filterScrollGrab = (int) my - thumbTop; // 按住滑块：保留偏移，不跳位
            } else {
                filterScrollGrab = thumbH / 2; // 点空白轨道：滑块中心跳到这点
                scrollFilterThumbTo((int) my - filterScrollGrab);
            }
            filterScrollDragging = true;
            return true;
        }
        int row = filterScroll + (int) ((my - r.y - FILTER_HEADER) / FILTER_ROW);
        if (row >= 0 && row < filterOptions.size()
                && my < r.y + FILTER_HEADER + filterViewportRows * FILTER_ROW) {
            toggleCategoryHidden(filterOptions.get(row).id());
        }
        return true;
    }

    // ------------------------------------------------------ scrollbar (r14)

    private int scrollbarX() {
        return panelX + panelW - 5;
    }

    private int scrollbarThumbHeight() {
        int height = visibleHeight();
        double total = flat.size() * (double) ROW_H;
        if (total <= 0) return height;
        return Math.max(16, (int) (height * (height / total)));
    }

    private int scrollbarThumbTop() {
        int travel = invisibleTravel();
        double max = maxScroll();
        if (travel <= 0 || max <= 0) return contentTop;
        return contentTop + (int) Math.round(travel * (scroll / max));
    }

    private int invisibleTravel() {
        return visibleHeight() - scrollbarThumbHeight();
    }

    private boolean isOverScrollbar(double mouseX, double mouseY) {
        if (maxScroll() <= 0) return false;
        int barX = scrollbarX();
        return mouseX >= barX - 2 && mouseX <= barX + 4 && mouseY >= contentTop && mouseY < contentBottom;
    }

    /** Moves the view so the thumb's top edge sits at {@code thumbTop} (screen y). */
    private void scrollToThumbTop(int thumbTop) {
        int travel = invisibleTravel();
        if (travel <= 0) return;
        int clamped = Math.max(0, Math.min(travel, thumbTop - contentTop));
        scroll = maxScroll() * (clamped / (double) travel);
    }

    private void drawScrollbar(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        double max = maxScroll();
        if (max <= 0) return;
        int barX = scrollbarX();
        int top = contentTop;
        int height = visibleHeight();
        boolean active = scrollbarDragging || isOverScrollbar(mouseX, mouseY);
        guiGraphics.fill(barX - 1, top, barX + 3, top + height, active ? 0x44FFFFFF : 0x28FFFFFF);
        int thumbH = scrollbarThumbHeight();
        int thumbY = scrollbarThumbTop();
        guiGraphics.fill(barX - 2, thumbY, barX + 3, thumbY + thumbH, active ? 0xCCFFFFFF : 0x99FFFFFF);
    }

    /** Same as a plain drawString with a width limit, but a row only measures its text again when the window width changed. */
    private void gTextCached(GuiGraphics guiGraphics, Node node, String text, int x, int y, int color, int maxWidth) {
        if (node.trimWidth != maxWidth) {
            node.trimmedText = trim(text, maxWidth);
            node.trimWidth = maxWidth;
        }
        guiGraphics.drawString(this.font, node.trimmedText, x, y, color, true);
    }

    private String trim(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (maxWidth <= 0) return "";
        if (this.font.width(text) <= maxWidth) return text;
        String ellipsis = "…";
        int budget = maxWidth - this.font.width(ellipsis);
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int w = this.font.width(String.valueOf(c));
            if (used + w > budget) break;
            out.append(c);
            used += w;
        }
        return out + ellipsis;
    }

    /**
     * Resolves and caches everything a row needs to paint itself (audit r14).
     * {@code asFormattedText()}, {@code font.width(...)} and REI's category
     * lookup all allocate objects; doing that for every visible row on every
     * frame was the tree's real frame-time cost.
     */
    private void ensureText(Node node) {
        if (node.textReady) return;
        node.textReady = true;
        switch (node.kind) {
            case ITEM -> {
                EntryStack<?> stack = node.stack;
                if (stack == null) {
                    node.nameText = "?";
                    node.nameTextW = this.font.width(node.nameText);
                    return;
                }
                node.nameText = stack.asFormattedText().getString();
                node.nameTextW = this.font.width(node.nameText);
                node.amount = TreeIndex.amountOf(stack);
                if (node.amount > 1) {
                    node.amountText = " ×" + node.amount;
                    node.amountTextW = this.font.width(node.amountText);
                }
                if (node.altCount > 1) {
                    node.extraText = " " + Component.translatable("gui.better_rei.tree.alt", node.altCount).getString();
                    node.extraTextW = this.font.width(node.extraText);
                }
                // ---- 备料：这一行要几个 / 背包够不够（配方模式才有意义）
                // have < 0 = 还没分到（泵中途，P1-1）——宁可整列不显示，
                // 也不要在展开过程中先亮一片错误的红色"差 N"。
                if (!usagesMode && node.need > 0 && node.have >= 0) {
                    long have = Math.max(0L, node.have);
                    boolean enough = node.have >= node.need;
                    if (node.need > 1) {
                        // 饱和到封顶值的数量用"需 ≥N"显示，别装作是个精确数（P2-2）
                        node.needText = Component.translatable(
                                node.need >= NEED_CAP ? "gui.better_rei.tree.need_huge" : "gui.better_rei.tree.need",
                                node.need).getString();
                        node.needTextW = this.font.width(node.needText);
                    }
                    node.progressText = enough
                            ? Component.translatable("gui.better_rei.tree.enough").getString()
                            : Component.translatable("gui.better_rei.tree.short", node.need - have).getString();
                    node.progressTextW = this.font.width(node.progressText);
                    node.needColor = enough ? C_AMOUNT : C_SHORT;
                    if (dotWidth < 0) dotWidth = this.font.width("·");
                    node.needBlockW = enough && node.need <= 1 ? node.progressTextW
                            : (node.needTextW > 0 ? node.needTextW + 5 + dotWidth + 5 : 0) + node.progressTextW;
                }
                // ---- 折中方案的另一半：说明这个数量没算上哪些配方
                if (!usagesMode && node.otherRecipes > 0) {
                    node.otherText = " " + Component.translatable("gui.better_rei.tree.other_recipes", node.otherRecipes).getString();
                    node.otherTextW = this.font.width(node.otherText);
                }
            }
            case RECIPE -> {
                List<EntryStack<?>> inputs = firstStacks(node.display.getInputEntries());
                int shown = Math.min(inputs.size(), MAX_INPUT_ICONS);
                node.inputPreview = shown == 0 ? List.of() : new ArrayList<>(inputs.subList(0, shown));
                if (inputs.size() > MAX_INPUT_ICONS) {
                    node.overflowText = "+" + (inputs.size() - MAX_INPUT_ICONS);
                    node.overflowTextW = this.font.width(node.overflowText);
                }
                List<EntryStack<?>> outputs = firstStacks(node.display.getOutputEntries());
                EntryStack<?> product = outputs.isEmpty() ? null : outputs.get(0);
                node.productStack = product;
                if (product != null) {
                    String name = product.asFormattedText().getString();
                    long amount = TreeIndex.amountOf(product);
                    node.nameText = amount > 1 ? name + " ×" + amount : name;
                    node.nameTextW = this.font.width(node.nameText);
                    node.categoryText = displayTitle(node.display);
                    node.categoryTextW = this.font.width(node.categoryText);
                }
                // product == null keeps categoryText null: the row has no visible output
                // P2-3: 被计数的那条配方显示"做 N 次"，玩家才知道整列数量是按这条算的
                if (!usagesMode && node.batches > 0) {
                    node.batchText = " · " + Component.translatable("gui.better_rei.tree.batches", node.batches).getString();
                    node.batchTextW = this.font.width(node.batchText);
                }
            }
            default -> {
                node.nameText = node.label == null ? "" : node.label.getString();
                node.nameTextW = this.font.width(node.nameText);
            }
        }
    }

    private void drawRow(GuiGraphics guiGraphics, Node node, int y, boolean hovered, boolean alt) {
        int x = rowLeft(node);
        int textY = y + 6;
        // depth guides: one faint vertical line per nesting level
        for (int d = 0; d < rowLevel(node); d++) {
            int gx = contentLeft + d * INDENT + 4;
            guiGraphics.fill(gx, y, gx + 1, y + ROW_H, C_GUIDE);
        }
        if (alt) {
            guiGraphics.fill(contentLeft - 2, y, contentRight, y + ROW_H, C_ROW_ALT);
        }
        if (hovered) {
            guiGraphics.fill(contentLeft - 2, y, contentRight, y + ROW_H, C_HOVER);
            guiGraphics.fill(contentLeft - 2, y, contentRight, y + 1, C_HOVER_EDGE);
            guiGraphics.fill(contentLeft - 2, y + ROW_H - 1, contentRight, y + ROW_H, C_HOVER_EDGE);
        }

        switch (node.kind) {
            case ITEM -> drawItemRow(guiGraphics, node, x, y, textY);
            case RECIPE -> drawRecipeRow(guiGraphics, node, x, y, textY);
            case MORE -> {
                ensureText(node);
                guiGraphics.drawString(this.font, "▸", x, textY, C_GOLD, true);
                gTextCached(guiGraphics, node, node.nameText, x + ARROW_W,
                        textY, C_GOLD, contentRight - 8 - (x + ARROW_W));
            }
            default -> {
                ensureText(node);
                gTextCached(guiGraphics, node, node.nameText, x + ARROW_W, textY, C_SUB,
                        contentRight - 8 - (x + ARROW_W));
            }
        }
    }

    /**
     * r36 层二：物品图标不再走 REI 的"直绘"通道（它自己操模型视图矩阵，
     * ImmediatelyFast / ModernUI / ResolutionScaler 这类 GUI 渲染优化模组容易把它切掉半截），
     * 改走原版 {@link GuiGraphics#renderItem(ItemStack, int, int)} ——REI 面板和背包同款，
     * ImmediatelyFast 专门适配过这条通道。为保住层一的整格效果，用矩阵把 16 的图标放大到 18。
     * 非物品类型（流体等）仍退回 REI 自己的渲染器。
     */
    private static void drawStackIcon(GuiGraphics g, EntryStack<?> stack, int x, int y) {
        if (stack != null && stack.getType() == VanillaEntryTypes.ITEM
                && stack.getValue() instanceof ItemStack item && !item.isEmpty()) {
            PoseStack pose = g.pose();
            PoseStack.Pose original = pose.last();
            pose.pushPose();
            try {
                pose.translate((double) x, (double) y, 0.0);
                pose.scale(ICON / 16.0F, ICON / 16.0F, 1.0F);
                g.renderItem(item, 0, 0);
                return;
            } catch (RuntimeException | LinkageError ignored) {
                // 原版或模组的物品绘制器失败时，仍尝试 REI 的绘制器。
            } finally {
                // 原版 renderItem 在绘制中途抛错时也可能留下一层矩阵。
                while (pose.last() != original && !pose.clear()) {
                    pose.popPose();
                }
            }
        }
        if (stack != null) {
            stack.render(g, new Rectangle(x, y, ICON, ICON), -1, -1, 0f);
        }
    }

    private void drawItemRow(GuiGraphics guiGraphics, Node node, int x, int y, int textY) {
        String arrow = node.cycle ? "⟳" : (node.expanded ? "▼" : "▶");
        guiGraphics.drawString(this.font, arrow, x, textY, node.cycle ? C_CYCLE : C_SUB, true);
        int iconX = x + ARROW_W;
        if (node.stack != null) {
            // 整格（audit r35 层一）：底板和图标同一个 18×18 矩形，上下各留 1px，
            // 不再内缩——原来 16 的图标画在 17/18 的底板里，高清图标的边缘细节被挤掉
            guiGraphics.fill(iconX, y + 1, iconX + ICON, y + 1 + ICON, C_ICON_PLATE);
            drawStackIcon(guiGraphics, node.stack, iconX, y + 1);
        }
        ensureText(node);
        int textX = iconX + ICON + 4;
        // 右侧固定一列显示"要几个 / 有没有"，名字先让出这段宽度
        int reserve = node.needBlockW;
        int max = contentRight - 8 - reserve - 6 - textX;
        if (max < 48) { // 窄窗口/太深的层级：宁可少显示一列，也别把物品名挤没
            reserve = 0;
            max = contentRight - 8 - textX;
        }
        max = Math.max(0, max);
        gTextCached(guiGraphics, node, node.nameText, textX, textY, C_TEXT, max);
        textX += Math.min(node.nameTextW, max);
        if (node.amountText != null && textX + node.amountTextW < contentRight - 6) {
            guiGraphics.drawString(this.font, node.amountText, textX + 1, textY, C_AMOUNT, true);
            textX += node.amountTextW + 2;
        }
        if (node.extraText != null && textX + node.extraTextW < contentRight - 6) {
            guiGraphics.drawString(this.font, node.extraText, textX + 1, textY, C_SUB, true);
            textX += node.extraTextW + 2;
        }
        if (node.otherText != null && textX + node.otherTextW < contentRight - 6 - reserve) {
            guiGraphics.drawString(this.font, node.otherText, textX + 1, textY, C_SUB, true);
        }
        if (reserve > 0) drawNeedBlock(guiGraphics, node, textY);
    }

    /** "需 64 · 差 32"：数字右对齐贴在行尾，一眼扫下来就是一列备料清单。 */
    private void drawNeedBlock(GuiGraphics guiGraphics, Node node, int textY) {
        int right = contentRight - 8;
        if (node.needText != null) {
            int needX = right - node.needBlockW;
            guiGraphics.drawString(this.font, node.needText, needX, textY, C_TEXT, true);
            int dotX = needX + node.needTextW + 5;
            guiGraphics.drawString(this.font, "·", dotX, textY, C_SUB, true);
            guiGraphics.drawString(this.font, node.progressText, dotX + dotWidth + 5, textY, node.needColor, true);
        } else {
            guiGraphics.drawString(this.font, node.progressText, right - node.progressTextW, textY, node.needColor, true);
        }
    }

    /**
     * A recipe row reads left to right: inputs → output (product name).
     * The category name is pushed to the right edge, so the row is still
     * identifiable when several categories look alike.
     */
    private void drawRecipeRow(GuiGraphics guiGraphics, Node node, int x, int y, int textY) {
        String arrow = node.expanded ? "▼" : "▶";
        guiGraphics.drawString(this.font, arrow, x, textY, C_SUB, true);
        ensureText(node);
        int cursor = x + ARROW_W;
        List<EntryStack<?>> inputs = node.inputPreview;
        if (inputs != null) {
            for (EntryStack<?> stack : inputs) {
                guiGraphics.fill(cursor, y + 1, cursor + ICON, y + 1 + ICON, C_ICON_PLATE);
                drawStackIcon(guiGraphics, stack, cursor, y + 1);
                cursor += ICON + 2;
            }
        }
        if (node.overflowText != null) {
            guiGraphics.drawString(this.font, node.overflowText, cursor + 1, textY, C_SUB, true);
            cursor += node.overflowTextW + 2;
        }
        String sep = "→";
        guiGraphics.drawString(this.font, sep, cursor + 2, textY, C_SUB, true);
        if (arrowSepW < 0) arrowSepW = this.font.width(sep);
        cursor += arrowSepW + 4;
        if (node.categoryText == null) { // display has no visible output
            if (node.batchText != null) {
                guiGraphics.drawString(this.font, node.batchText, cursor, textY, C_GOLD, true);
            }
            return;
        }
        if (node.productStack != null) {
            guiGraphics.fill(cursor, y + 1, cursor + ICON, y + 1 + ICON, C_ICON_PLATE);
            drawStackIcon(guiGraphics, node.productStack, cursor, y + 1);
            cursor += ICON + 4;
        }
        int batchW = node.batchTextW; // P2-3: "· 做 N 次" 紧跟产物名，金色标出这条就是被计数的路线
        int catX = contentRight - 4 - node.categoryTextW;
        if (catX - cursor < 46 + batchW) { // no room for name + 次数 + category -> drop the category label
            int maxW = Math.max(0, contentRight - 6 - batchW - cursor);
            gTextCached(guiGraphics, node, node.nameText, cursor, textY, C_TEXT, maxW);
            cursor += Math.min(node.nameTextW, maxW);
        } else {
            int maxW = Math.max(0, catX - 6 - batchW - cursor);
            gTextCached(guiGraphics, node, node.nameText, cursor, textY, C_TEXT, maxW);
            cursor += Math.min(node.nameTextW, maxW);
            guiGraphics.drawString(this.font, node.categoryText, catX, textY, C_SUB, true);
        }
        if (node.batchText != null && cursor + batchW < contentRight - 4) {
            guiGraphics.drawString(this.font, node.batchText, cursor, textY, C_GOLD, true);
        }
    }

    /** First visible stack of every ingredient, in order (empty ingredients skipped). */
    private static List<EntryStack<?>> firstStacks(List<EntryIngredient> ingredients) {
        List<EntryStack<?>> out = new ArrayList<>(ingredients.size());
        for (EntryIngredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            EntryStack<?> stack = ingredient.get(0);
            if (stack == null || stack.isEmpty()) continue;
            out.add(stack);
        }
        return out;
    }

    // -------------------------------------------------------------- input

    private Node rowAt(double mouseX, double mouseY) {
        if (mouseY < contentTop || mouseY >= contentBottom) return null;
        if (mouseX < contentLeft - 2 || mouseX > contentRight + 2) return null;
        int idx = (int) ((mouseY - contentTop + scroll) / ROW_H);
        if (idx < 0 || idx >= flat.size()) return null;
        return flat.get(idx);
    }

    @Override
    public void tick() {
        super.tick();
        // P2-3: REI reloaded underneath us -> rebuild from the saved root
        // stack, replaying the expansion state (P2-4), then say so on screen.
        if (TreeIndex.generation() != this.generation) {
            reloadTree();
            this.reloadNotice = 120;
            // r35：REI 重载后分类注册表可能变了，下拉开着就当场重取一遍
            if (filterOpen) refreshFilterOptions();
        } else if (reloadNotice > 0) {
            reloadNotice--;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // r35 方案B：下拉开着时它吃掉一切点击——面板里点勾选，外面点哪都只关面板，
        // 绝不顺手把底下的树行点开
        if (filterOpen && (button == 0 || button == 1)) {
            if (inFilterRect(mouseX, mouseY)) {
                if (button == 0) handleFilterClick(mouseX, mouseY);
                return true;
            }
            filterOpen = false;
            filterScrollDragging = false;
            return true;
        }
        if (button == 0 && !usagesMode && targetVisible) {
            if (overTargetButton(mouseX, mouseY, targetMinusX)) {
                adjustTarget(-1);
                return true;
            }
            if (overTargetButton(mouseX, mouseY, targetPlusX)) {
                adjustTarget(1);
                return true;
            }
        }
        if (button == 0 && isOverScrollbar(mouseX, mouseY)) {
            int thumbH = scrollbarThumbHeight();
            int thumbTop = scrollbarThumbTop();
            if (mouseY >= thumbTop && mouseY < thumbTop + thumbH) {
                // grabbed the thumb itself: keep the click offset so it does not jump
                scrollbarGrab = (int) mouseY - thumbTop;
            } else {
                // clicked the empty track: jump there, centred like a real scrollbar
                scrollbarGrab = thumbH / 2;
                scrollToThumbTop((int) mouseY - scrollbarGrab);
            }
            scrollbarDragging = true;
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        Node node = rowAt(mouseX, mouseY);
        if (node == null) return false;
        if (node.kind == MORE) {
            if (button == 1) collapseShown(node.parent);
            else showMore(node.parent);
            rebuildFlat();
            return true;
        }
        if (node.kind == ITEM && node.stack != null) {
            int iconX = rowLeft(node) + ARROW_W;
            if (mouseX >= iconX && mouseX < iconX + ICON) {
                if (button == 1) {
                    ViewSearchBuilder.builder().addUsagesFor(node.stack).open();
                } else {
                    ViewSearchBuilder.builder().addRecipesFor(node.stack).open();
                }
                return true;
            }
        }
        if (node.kind == ITEM || node.kind == RECIPE) {
            toggle(node);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (filterScrollDragging) { // r37：分类下拉滚动条按住拖
            scrollFilterThumbTo((int) mouseY - filterScrollGrab);
            return true;
        }
        if (scrollbarDragging) {
            scrollToThumbTop((int) mouseY - scrollbarGrab);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (filterScrollDragging) {
            filterScrollDragging = false;
            return true;
        }
        if (scrollbarDragging) {
            scrollbarDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // r35：滚轮悬停在下拉上时翻分类列表，而不是背后的树
        if (filterOpen && inFilterRect(mouseX, mouseY)) {
            filterScroll -= (int) Math.round(delta * 3);
            clampFilterScroll();
            return true;
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll - delta * ROW_H * 1.5));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // r35：下拉开着时 ESC 先关下拉，再按才关界面
        if (filterOpen && keyCode == InputConstants.KEY_ESCAPE) {
            filterOpen = false;
            filterScrollDragging = false;
            return true;
        }
        // audit r38 P2-6：下拉开着时 ↑/↓/PgUp/PgDn/Home/End 翻的是列表，不是背后那棵树
        // （滚轮早就转给列表了，键盘和鼠标行为要一致）
        if (filterOpen) {
            switch (keyCode) {
                case InputConstants.KEY_DOWN -> { filterScroll++; clampFilterScroll(); return true; }
                case InputConstants.KEY_UP -> { filterScroll--; clampFilterScroll(); return true; }
                case InputConstants.KEY_PAGEDOWN -> { filterScroll += filterViewportRows; clampFilterScroll(); return true; }
                case InputConstants.KEY_PAGEUP -> { filterScroll -= filterViewportRows; clampFilterScroll(); return true; }
                case InputConstants.KEY_HOME -> { filterScroll = 0; return true; }
                case InputConstants.KEY_END -> { filterScroll = filterOptions.size(); clampFilterScroll(); return true; }
                default -> { /* TAB 等其余键照常走下面的树快捷键 */ }
            }
        }
        if (keyCode == InputConstants.KEY_TAB) {
            usagesMode = !usagesMode;
            resetTree();
            if (modeButton != null) modeButton.setMessage(modeLabel());
            return true;
        }
        int page = Math.max(ROW_H, visibleHeight() - ROW_H);
        switch (keyCode) {
            case InputConstants.KEY_DOWN -> { scrollBy(ROW_H); return true; }
            case InputConstants.KEY_UP -> { scrollBy(-ROW_H); return true; }
            case InputConstants.KEY_PAGEDOWN -> { scrollBy(page); return true; }
            case InputConstants.KEY_PAGEUP -> { scrollBy(-page); return true; }
            case InputConstants.KEY_HOME -> { scroll = 0; return true; }
            case InputConstants.KEY_END -> { scroll = maxScroll(); return true; }
            default -> { /* fall through to vanilla */ }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void scrollBy(int amount) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll + amount));
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(previous);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
