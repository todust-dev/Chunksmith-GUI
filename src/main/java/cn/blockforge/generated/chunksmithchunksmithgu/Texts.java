package cn.blockforge.generated.chunksmithchunksmithgu;

import net.minecraft.network.chat.Component;

/**
 * 界面文案的统一入口（<b>已外置</b>）。
 *
 * <p>所有给人看的文字都搬到
 * <code>assets/chunksmith_modern_gui/lang/zh_cn.json</code> 里了，代码只留键名。
 * 改文案直接改那个 JSON，不用重新编译 Java；也可以用自己的资源包覆盖。</p>
 *
 * <p><b>占位符只支持 {@code %s}</b>。Minecraft 的 {@code TranslatableContents} 用的是
 * 自己那套模板解析器，遇到 {@code %s} 和 {@code %%} 以外的格式符（比如 {@code %.1f}）
 * 会直接抛 {@code TranslatableFormatException} 并回退成原样显示。所以数字要在代码里
 * 先用 {@code String.format} 化成字符串，再当 {@code %s} 传进来。
 * 文案里要显示一个百分号，就写两个：{@code %%}。</p>
 *
 * <p>键名一律带 {@code chunksmith_modern_gui.} 前缀，避免和别的模组撞车。</p>
 */
public final class Texts {

    private static final String PREFIX = ChunkSmithGuiMod.MOD_ID + ".";

    private Texts() {
    }

    /** 按当前语言取一条文案；{@code key} 不含 mod id 前缀。 */
    public static String t(String key, Object... args) {
        return Component.translatable(PREFIX + key, args).getString();
    }
}
