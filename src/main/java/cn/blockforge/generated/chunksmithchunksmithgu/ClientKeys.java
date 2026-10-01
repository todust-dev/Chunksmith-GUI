package cn.blockforge.generated.chunksmithchunksmithgu;

import cn.blockforge.generated.chunksmithchunksmithgu.gui.PanelScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 默认 P 键开面板；键位可在原版“控制 → 按键绑定”里修改（标准 KeyMapping）。
 *
 * <p>这个类整个都是客户端专属的，注册入口在 {@link ClientSetup}（仅客户端加载）。</p>
 *
 * <p>1.21.1/NeoForge：Forge 的 {@code TickEvent.ClientTickEvent} 带 {@code Phase}（START/END），
 * NeoForge 把它拆成了两个独立事件 {@code ClientTickEvent.Pre} / {@code ClientTickEvent.Post}。
 * 原来判断的是 {@code Phase.END}，对应现在的 {@code ClientTickEvent.Post}。</p>
 */
public final class ClientKeys {

    public static final KeyMapping TOGGLE = new KeyMapping(
            "key." + ChunkSmithGuiMod.MOD_ID + ".open",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            "key.categories." + ChunkSmithGuiMod.MOD_ID);

    private ClientKeys() {
    }

    /** NeoForge 游戏总线上的客户端 tick 监听。 */
    public static final class TickEvents {
        @SubscribeEvent
        public void onTick(ClientTickEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            if (TOGGLE.consumeClick()) {
                if (mc.screen == null) {
                    mc.setScreen(new PanelScreen());
                } else if (mc.screen instanceof PanelScreen) {
                    mc.setScreen(null);
                }
            }
            PrereqStatus.refreshTree();
        }
    }
}
