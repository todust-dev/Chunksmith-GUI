package cn.blockforge.generated.chunksmithchunksmithgu;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 客户端专属入口。
 *
 * <p>{@code value = Dist.CLIENT} 的 {@code @EventBusSubscriber} 只会被客户端加载：
 * NeoForge 在注入事件订阅者时会先比对当前运行端，专用服务器直接跳过这个类，
 * 所以它里面出现的 {@code net.minecraft.client.*} 引用不会把服务端带崩。</p>
 *
 * <p>对应关系：
 * <ul>
 *   <li>{@link RegisterKeyMappingsEvent} → 注册默认的 P 键；</li>
 *   <li>{@link FMLClientSetupEvent} → 挂上聊天回包捕获和每 tick 的按键轮询。</li>
 * </ul></p>
 *
 * <p><b>1.21.1/NeoForge 的两点变化</b>：<br>
 * ① 注解从 {@code net.minecraftforge.fml.common.Mod.EventBusSubscriber} 换到
 * {@code net.neoforged.fml.common.EventBusSubscriber}，语义不变。<br>
 * ② <b>不要再写 {@code bus = ...}</b>。Forge 时代必须显式声明 {@code bus = Bus.MOD}，
 * 但 NeoForge 1.21.1 起 {@code bus()} 已被标记为 {@code @Deprecated(forRemoval = true)}：
 * 加载器会看每个 {@code @SubscribeEvent} 静态方法的<b>参数类型</b>——实现
 * {@code IModBusEvent} 的进模组总线，其余的进游戏总线。
 * 本类两个方法的参数事件（{@code RegisterKeyMappingsEvent}、{@code FMLClientSetupEvent}）
 * 都是 {@code IModBusEvent}，所以会自动挂到模组总线上，与原版行为一致。</p>
 */
@EventBusSubscriber(modid = ChunkSmithGuiMod.MOD_ID, value = Dist.CLIENT)
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
        NeoForge.EVENT_BUS.register(new ClientKeys.TickEvents());
    }
}
