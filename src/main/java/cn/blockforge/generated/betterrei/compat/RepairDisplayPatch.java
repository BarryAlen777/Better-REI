package cn.blockforge.generated.betterrei.compat;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.service.MixinService;

/** 在 REI 原有的修复材料转换处同时传入正在注册的物品，供兜底和日志使用。 */
public final class RepairDisplayPatch {
    public static final String TARGET = "me.shedaniel.rei.plugin.client.DefaultClientPlugin";
    private static final String REGISTER_DESC = "(Lme/shedaniel/rei/api/client/registry/display/DisplayRegistry;)V";
    private static final String ENTRY_INGREDIENTS = "me/shedaniel/rei/api/common/util/EntryIngredients";
    private static final String INGREDIENT = "Lnet/minecraft/world/item/crafting/Ingredient;";
    private static final String ITEM = "Lnet/minecraft/world/item/Item;";
    private static final String ENTRY_INGREDIENT = "Lme/shedaniel/rei/api/common/entry/EntryIngredient;";
    private static final String GUARD_OWNER = "cn/blockforge/generated/betterrei/perf/RepairDisplayGuard";

    private RepairDisplayPatch() {}

    public static void apply(ClassNode target) {
        var logger = MixinService.getService().getLogger("better_rei");
        for (MethodNode method : target.methods) {
            if (method.name.equals("registerDisplays") && method.desc.equals(REGISTER_DESC) && patch(method)) {
                logger.info("Better REI: installed the null repair ingredient guard at both tool and armor call sites.");
                return;
            }
        }
        logger.warn(
                "Better REI: the tool/armor repair display call sites did not match REI 12.1.785. "
                        + "The null repair ingredient guard was not installed.");
    }

    private static boolean patch(MethodNode method) {
        MethodInsnNode toolCall = null;
        MethodInsnNode armorCall = null;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals(ENTRY_INGREDIENTS) || !call.name.equals("ofIngredient")
                    || !call.desc.equals("(" + INGREDIENT + ")" + ENTRY_INGREDIENT)) {
                continue;
            }
            AbstractInsnNode previous = call.getPrevious();
            while (previous != null && previous.getOpcode() < 0) {
                previous = previous.getPrevious();
            }
            if (isRepairGetter(previous, "net/minecraft/world/item/Tier", "m_6282_")) {
                if (toolCall != null) return false;
                toolCall = call;
            } else if (isRepairGetter(previous, "net/minecraft/world/item/ArmorMaterial", "m_6230_")) {
                if (armorCall != null) return false;
                armorCall = call;
            }
        }
        if (toolCall == null || armorCall == null) return false;

        // 先核对两处都在原循环的 item 变量作用域内，再一起替换。
        int itemSlot = findItemSlot(method, toolCall, armorCall);
        if (itemSlot < 0) return false;
        replaceCall(method.instructions, toolCall, itemSlot);
        replaceCall(method.instructions, armorCall, itemSlot);
        // 每个调用多压入一个物品引用，调用结束后的栈结构保持一致。
        method.maxStack++;
        return true;
    }

    private static boolean isRepairGetter(AbstractInsnNode insn, String owner, String srgName) {
        return insn instanceof MethodInsnNode getter && getter.getOpcode() == Opcodes.INVOKEINTERFACE
                && getter.owner.equals(owner) && getter.desc.equals("()" + INGREDIENT)
                && (getter.name.equals(srgName) || getter.name.equals("getRepairIngredient"));
    }

    private static int findItemSlot(MethodNode method, MethodInsnNode toolCall, MethodInsnNode armorCall) {
        if (method.localVariables == null) return -1;
        int tool = method.instructions.indexOf(toolCall);
        int armor = method.instructions.indexOf(armorCall);
        for (var local : method.localVariables) {
            if (!local.name.equals("item") || !local.desc.equals(ITEM)) continue;
            int start = method.instructions.indexOf(local.start);
            int end = method.instructions.indexOf(local.end);
            if (start >= 0 && start <= Math.min(tool, armor) && Math.max(tool, armor) < end) {
                return local.index;
            }
        }
        return -1;
    }

    private static void replaceCall(InsnList instructions, MethodInsnNode call, int itemSlot) {
        instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, itemSlot));
        instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD_OWNER,
                "convertRepairIngredient", "(" + INGREDIENT + ITEM + ")" + ENTRY_INGREDIENT, false));
    }
}
