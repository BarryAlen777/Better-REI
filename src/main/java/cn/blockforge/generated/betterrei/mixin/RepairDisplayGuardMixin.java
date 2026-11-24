package cn.blockforge.generated.betterrei.mixin;

import cn.blockforge.generated.betterrei.compat.RepairDisplayPatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** 由 BetterReiMixinPlugin 在两个修复材料转换调用处接入兜底。 */
@Pseudo
@Mixin(targets = RepairDisplayPatch.TARGET, remap = false)
public abstract class RepairDisplayGuardMixin {
}
