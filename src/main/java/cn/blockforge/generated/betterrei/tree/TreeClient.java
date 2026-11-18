package cn.blockforge.generated.betterrei.tree;

import cn.blockforge.generated.betterrei.perf.BetterReiConfig;
import cn.blockforge.generated.betterrei.perf.BetterReiVersionGuard;
import cn.blockforge.generated.betterrei.perf.EntryAtlasPolicy;
import com.mojang.blaze3d.platform.InputConstants;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.overlay.ScreenOverlay;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers the Ctrl+R hotkey that opens {@link RecipeTreeScreen}.
 *
 * audit r13 "鼠标在 REI 列表里按 Ctrl+R 没反应": the binding used to be read
 * with {@code KeyMapping.consumeClick()} from the client tick. Vanilla only
 * feeds {@code KeyMapping.set/click} while {@code Minecraft.screen == null}
 * (verified in KeyboardHandler#keyPress: the whole KeyMapping block sits behind
 * an {@code if (screen == null)} jump), and Forge's InputEvent.Key is skipped
 * as well whenever the open screen returns {@code true} from keyPressed(). So
 * any open GUI — the inventory with REI's panel next to it, which is exactly
 * where the player hovers an entry — swallowed the hotkey. The binding is now
 * polled straight from the keyboard state every client tick, which sees the key
 * in and out of GUIs, still honours a rebind made in Options -> Controls (the
 * key code and the Ctrl modifier are read from the mapping, not hardcoded) and
 * fires once per press thanks to edge detection.
 *
 * {@link #tryOpen} itself must refuse while the player is typing: any screen
 * with focused text input (chat, signs, command blocks, renamed containers...)
 * silently swallows the hotkey instead of being replaced (audit r7 P1-1).
 * Root item priority: REI overlay hovered entry -> container slot under the
 * mouse -> carried (cursor) stack -> main-hand item.
 */
public final class TreeClient {

    private TreeClient() {}

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("better_rei/tree");

    private static final net.minecraft.client.KeyMapping OPEN_TREE = new net.minecraft.client.KeyMapping(
            "key.better_rei.tree",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.better_rei");

    private static int ticksInWorld;
    private static boolean selfChecked;
    /** edge detection for the polled hotkey */
    private static boolean hotkeyWasDown;

    /** audit r7 P1-2: at most one chat message of each kind per this window */
    private static final Map<String, Long> LAST_MESSAGE = new HashMap<>();
    private static final long MESSAGE_THROTTLE_MS = 3000L;

    public static void init(IEventBus modBus, IEventBus forgeBus) {
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(OPEN_TREE));
        forgeBus.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END) return;
            // Keeps REI's giant one-shot icon atlas from freezing big packs on the
            // first inventory open. Runs from the main menu on: it only needs
            // REI's entry registry, not a world. Guarded so a REI-less install
            // never even loads the class (it references REI types).
            if (BetterReiVersionGuard.reiPresent()) {
                try {
                    EntryAtlasPolicy.tick();
                } catch (Throwable ignored) {
                    // a broken probe must never cost anything else
                }
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || !mc.isWindowActive()) {
                hotkeyWasDown = false;
                return;
            }
            if (ticksInWorld < 200) ticksInWorld++;
            if (!selfChecked && ticksInWorld >= 200) {
                selfChecked = true;
                // audit r7 P1-4: once REI has had time to boot, confirm every
                // mixin actually landed and say so in the log — failures are
                // otherwise invisible behind the optional-config layers.
                BetterReiVersionGuard.selfCheckMixins();
            }
            boolean down = hotkeyDown(mc);
            if (down && !hotkeyWasDown) {
                tryOpen(mc);
            }
            hotkeyWasDown = down;
        });
    }

    /**
     * audit r13: is the recipe-tree binding held right now? Polls the GLFW key
     * state, so it also works inside GUIs (see the class comment). Returns false
     * for an unbound or mouse-bound hotkey.
     */
    private static boolean hotkeyDown(Minecraft mc) {
        try {
            InputConstants.Key key = OPEN_TREE.getKey();
            if (key == null || key.getType() != InputConstants.Type.KEYSYM) return false;
            int code = key.getValue();
            if (code == InputConstants.UNKNOWN.getValue()) return false;
            if (!OPEN_TREE.getKeyModifier().isActive(KeyConflictContext.UNIVERSAL)) return false;
            return InputConstants.isKeyDown(mc.getWindow().getWindow(), code);
        } catch (Throwable t) {
            LOGGER.debug("Could not read the recipe-tree hotkey state", t);
            return false;
        }
    }

    /** Throttled chat feedback (P1-2): spamming Ctrl+R can not flood the chat. */
    private static void sayThrottled(Minecraft mc, String key, Component message) {
        long now = System.currentTimeMillis();
        Long last = LAST_MESSAGE.get(key);
        if (last != null && now - last < MESSAGE_THROTTLE_MS) return;
        LAST_MESSAGE.put(key, now);
        mc.gui.getChat().addMessage(message);
    }

    /** True when the open screen owns a focused text box (P1-1, extended in audit r10 P2-2). */
    private static boolean isTextInputOpen(Screen screen) {
        if (screen == null) return false;
        if (screen instanceof ChatScreen) return true;
        // Sign editing (1.20.1: a TextFieldHelper, no EditBox child) and the
        // book-and-quill editor (its own cursor handling) must never be replaced.
        if (screen instanceof AbstractSignEditScreen || screen instanceof BookEditScreen) return true;
        GuiEventListener focused = screen.getFocused();
        if (focused instanceof EditBox || focused instanceof MultiLineEditBox) return true;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof EditBox edit && edit.isFocused()) return true;
            if (child instanceof MultiLineEditBox multi && multi.isFocused()) return true;
        }
        return false;
    }

    /**
     * audit r10 P2-2: REI's overlay search box is a widget of the overlay, not
     * a child of the open screen, so the guard above cannot see it. Ask REI
     * directly. Only called after the version check, so no REI-internal class
     * is touched on an unverified install.
     *
     * The focus check has to go through reflection, and the method name differs
     * between the two worlds (audit r12 P1-1): REI's 12.1.785 forge jar is
     * published with Minecraft names already reobfuscated, so {@code
     * TextField.isFocused()} is physically called {@code m_93696_} in the jar
     * that ships to players, while a dev workspace still sees {@code
     * isFocused}. Looking up only the dev name (the old code) always threw on a
     * player's install and silently disabled the guard — typing in REI's search
     * box got the screen stolen. Both spellings are tried, so neither
     * environment can regress.
     */
    private static final String[] FOCUS_METHOD_NAMES = {"m_93696_", "isFocused"};

    private static boolean isReiSearchFocused() {
        try {
            REIRuntime runtime = REIRuntime.getInstance();
            if (runtime == null) return false;
            Object field = runtime.getSearchTextField();
            if (field == null) return false;
            Class<?> type = field.getClass();
            for (String methodName : FOCUS_METHOD_NAMES) {
                try {
                    return Boolean.TRUE.equals(type.getMethod(methodName).invoke(field));
                } catch (NoSuchMethodException ignored) {
                    // other naming scheme — try the next one
                }
            }
            LOGGER.debug("REI's search field ({}) exposes neither {} nor {}; skipping the focus guard.",
                    type.getName(), FOCUS_METHOD_NAMES[0], FOCUS_METHOD_NAMES[1]);
            return false;
        } catch (Throwable t) {
            LOGGER.debug("Could not inspect REI's search field", t);
            return false;
        }
    }

    private static void tryOpen(Minecraft mc) {
        if (!BetterReiConfig.treeEnabled()) return;
        // Never steal the screen while the player is typing (P1-1): drop the
        // key press quietly, the typed text stays in the box.
        if (isTextInputOpen(mc.screen)) return;
        // Gate before touching any REI class: RecipeTreeScreen reads REI's
        // impl display cache, so it may only load against the verified build.
        if (!BetterReiVersionGuard.matches()) {
            String detected = BetterReiVersionGuard.detectedVersion();
            // P1-2: "<unknown>" is retried automatically on the next press, so
            // do not spam the player with a version warning for it.
            if (BetterReiVersionGuard.VERSION_UNKNOWN.equals(detected)) return;
            MutableComponent message = BetterReiVersionGuard.VERSION_MISSING.equals(detected)
                    // P3-4: "REI is simply not installed" gets its own text
                    ? Component.translatable("message.better_rei.tree.no_rei",
                            BetterReiVersionGuard.EXPECTED_REI_VERSION)
                    : Component.translatable("message.better_rei.tree.wrong_rei",
                            BetterReiVersionGuard.EXPECTED_REI_VERSION, detected);
            sayThrottled(mc, "wrong_rei", message.withStyle(ChatFormatting.RED));
            return;
        }
        // audit r10 P2-2: typing in REI's search box is typing, too.
        if (isReiSearchFocused()) return;
        Screen screen = mc.screen;
        if (screen instanceof RecipeTreeScreen tree) {
            mc.setScreen(tree.getPrevious());
            return;
        }
        LocalPlayer player = mc.player;
        EntryStack<?> root = null;
        try {
            REIRuntime runtime = REIRuntime.getInstance();
            if (runtime != null && runtime.isOverlayVisible()) {
                ScreenOverlay overlay = runtime.getOverlay().orElse(null);
                if (overlay != null) {
                    EntryStack<?> focused = overlay.getEntryList().getFocusedStack();
                    if (focused != null && !focused.isEmpty()) {
                        root = focused;
                    }
                }
            }
            if (root == null && screen instanceof AbstractContainerScreen<?> container) {
                Slot slot = container.getSlotUnderMouse();
                if (slot != null && !slot.getItem().isEmpty()) {
                    root = EntryStacks.of(slot.getItem().copy());
                }
            }
            if (root == null) {
                ItemStack carried = player.containerMenu.getCarried();
                if (!carried.isEmpty()) {
                    root = EntryStacks.of(carried.copy());
                }
            }
            if (root == null) {
                ItemStack hand = player.getMainHandItem();
                if (!hand.isEmpty()) {
                    root = EntryStacks.of(hand.copy());
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed to resolve recipe-tree root item", t);
        }
        if (root == null) {
            sayThrottled(mc, "no_item", Component.translatable("message.better_rei.tree.no_item")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        try {
            mc.setScreen(new RecipeTreeScreen(root, screen));
        } catch (Throwable t) {
            LOGGER.error("Failed to open recipe tree for {}", root, t);
            sayThrottled(mc, "error", Component.translatable("message.better_rei.tree.error",
                    String.valueOf(t)).withStyle(ChatFormatting.RED));
        }
    }
}
