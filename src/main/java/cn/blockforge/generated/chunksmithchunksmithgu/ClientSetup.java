package cn.blockforge.generated.chunksmithchunksmithgu;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端专属入口。
 *
 * <p>{@code value = Dist.CLIENT} 的 {@code @Mod.EventBusSubscriber} 只会被客户端加载：
 * Forge 在注入事件订阅者时会先比对当前运行端，专用服务器直接跳过这个类，
 * 所以它里面出现的 {@code net.minecraft.client.*} 引用不会把服务端带崩。</p>
 *
 * <p>对应关系：
 * <ul>
 *   <li>{@link RegisterKeyMappingsEvent}（模组总线）→ 注册默认的 P 键；</li>
 *   <li>{@link FMLClientSetupEvent}（模组总线）→ 挂上聊天回包捕获和每 tick 的按键轮询。</li>
 * </ul></p>
 */
@Mod.EventBusSubscriber(modid = ChunkSmithGuiMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    /** 注册 P 键；键位会出现在原版「控制 → 按键绑定」里，可改。 */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ClientKeys.TOGGLE);
    }

    /** 客户端启动：聊天回包捕获 + 面板开关键轮询。 */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LogCapture.init();
        MinecraftForge.EVENT_BUS.register(new ClientKeys.TickEvents());
        PanelState.warmUpSets();   // 把 /cs set 备份历史从配置目录读回内存
        event.enqueueWork(ClientSetup::defaultLanguageOnce);
    }

    /**
     * 玩家还没在配置页选过语言时，按游戏语言定面板语言：
     * 游戏是中文就用中文，其他语言一律用英文（面板只做了中英两套）。
     * 选过之后就以玩家选的为准，不再被游戏语言覆盖。
     */
    private static void defaultLanguageOnce() {
        if (Settings.languageChosen) return;
        String code = "";
        try {
            code = net.minecraft.client.Minecraft.getInstance().options.languageCode;
        } catch (Throwable ignored) {
        }
        Settings.language = code != null && code.toLowerCase(java.util.Locale.ROOT).startsWith("zh")
                ? Lang.ZH : Lang.EN;
        Settings.languageChosen = true;
        Lang.invalidate();
        Settings.save();
    }
}
