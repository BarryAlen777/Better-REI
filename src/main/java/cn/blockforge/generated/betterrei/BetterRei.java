package cn.blockforge.generated.betterrei;

import cn.blockforge.generated.betterrei.perf.BetterReiConfig;
import cn.blockforge.generated.betterrei.perf.BetterReiVersionGuard;
import cn.blockforge.generated.betterrei.perf.EntryAtlasPolicy;
import cn.blockforge.generated.betterrei.tree.TreeClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BetterRei.MODID)
public final class BetterRei {
    public static final String MODID = "better_rei";

    public BetterRei() {
        // audit r38 P2-4: 走回官方注册入口。旧版为了拿"点完勾选立刻存盘"要的
        // ModConfig 引用，自己 new ModConfig + container.addConfig——那是半个
        // 内部 API，Forge 小版本一改注册/事件路径就可能不加载或不保存。
        // 注册表引用的正确拿法是监听配置生命周期事件（下面两条监听）。
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, BetterReiConfig.SPEC, "better_rei.toml");
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener((ModConfigEvent.Loading e) -> onConfigLifecycle(e, false));
        modBus.addListener((ModConfigEvent.Reloading e) -> onConfigLifecycle(e, true));
        // audit r7 P1-4: probe the installed REI right here so the log always
        // states at startup whether this build is active — every other layer
        // (optional mixin config, require = 0, version guard) is silent by
        // design, which used to make a disabled mod undiagnosable.
        BetterReiVersionGuard.logStartupStatus();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // audit r12 P3-1: the hotkey is registered even when REI is absent.
            // It cannot do anything useful then, but pressing it produces the
            // one clear "install REI" chat line instead of silence — and the
            // tree/version guards in TreeClient run before any REI class is
            // touched, so nothing there can fail on a REI-less install.
            TreeClient.init(modBus, MinecraftForge.EVENT_BUS);
        }
    }

    /**
     * better_rei.toml 被加载（启动）或被外部重新加载（改文件 / 模组选项里保存）时
     * 由 Forge 在 mod 总线上派发。这里收下 ModConfig 引用（P2-4），重载时再把
     * 图集判定的"已决定"标记抹掉——audit r38 P2-3：旧版决定一次就永不复检，
     * 玩家中途改 entryAtlasMode 要重启才生效。
     */
    private static void onConfigLifecycle(ModConfigEvent event, boolean reload) {
        if (event.getConfig().getSpec() != BetterReiConfig.SPEC) return;
        BetterReiConfig.clientConfig = event.getConfig();
        // EntryAtlasPolicy 引用 REI 类：按本模组的约定，没装 REI 就一行都不碰
        if (reload && FMLEnvironment.dist == Dist.CLIENT && BetterReiVersionGuard.reiPresent()) {
            EntryAtlasPolicy.invalidate();
        }
    }
}
