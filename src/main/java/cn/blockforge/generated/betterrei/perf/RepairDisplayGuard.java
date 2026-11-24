package cn.blockforge.generated.betterrei.perf;

import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.util.EntryIngredients;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 仅保护 REI 内置插件的工具与盔甲修复展示。 */
public final class RepairDisplayGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("better_rei");
    private static final Set<Item> WARNED_ITEMS = ConcurrentHashMap.newKeySet();

    private RepairDisplayGuard() {}

    public static EntryIngredient convertRepairIngredient(Ingredient ingredient, Item item) {
        if (ingredient != null || !BetterReiVersionGuard.matches()) {
            return EntryIngredients.ofIngredient(ingredient);
        }

        if (WARNED_ITEMS.add(item)) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            LOGGER.warn("Better REI 跳过了物品 {} 的修复展示，原因是该物品返回的修复材料为 null。"
                            + "REI 将继续注册后续配方，同一物品本次启动只记录一次。",
                    id != null ? id : item.getClass().getName());
        }
        // REI 本来就会跳过没有修复材料的物品。
        return EntryIngredient.empty();
    }
}
