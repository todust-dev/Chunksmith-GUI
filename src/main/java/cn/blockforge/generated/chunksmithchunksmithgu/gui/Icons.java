package cn.blockforge.generated.chunksmithchunksmithgu.gui;

import cn.blockforge.generated.chunksmithchunksmithgu.ChunkSmithGuiMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 面板图标（<b>已外置为贴图</b>）。
 *
 * <p>原来这里是 15 个 16x16 的字符点阵（{@code '#'}=填充、{@code '.'}=透明），
 * 运行时逐格 {@code g.fill()} 画出来。现在改成读贴图，<b>按用途分目录、文件名一律不带后缀</b>：</p>
 *
 * <pre>assets/chunksmith_modern_gui/textures/gui/icons/btn/&lt;名字&gt;.png    小图标 12x12（按钮用）
 *assets/chunksmith_modern_gui/textures/gui/icons/stat/&lt;名字&gt;.png   大图标 16x16（任务卡片用）</pre>
 *
 * <p><b>一张贴图 = 一个绘制尺寸 = 1:1 绘制</b>，运行时不做任何重采样，所以既不会裁切也不会拉伸。
 * 目前用到两个尺寸：按钮 {@link #SMALL_SIZE}px（{@link Widget.Button}）、
 * 任务卡片 {@link #LARGE_SIZE}px（{@link Widget.RowCard}）。想改图标直接用画图软件编辑对应的
 * PNG 就行，不用碰 Java 代码。</p>
 *
 * <p><b>为什么不用「一张 16x16 缩到 12px 画」</b>：16 个格子在 12 个像素里放不下，怎么压都有损。
 * 三种缩法实测（16→12）：最近邻（NEAREST）固定丢掉源第 1/5/9/13 列 ⇒ 细笔画整段消失；
 * 上游点阵那套「塌缩格强制补 1px」虽然不丢格，却有 4 个目标像素各承载 2 个源格 ⇒ 笔画被加粗、
 * 局部不对称。{@code btn/} 里那份是<b>离线按「覆盖率过半」</b>（每个目标像素覆盖 1.33 个源格，
 * 按面积过半判定）生成的，既不丢格也不重叠，形状比例最贴近 16px 原图。</p>
 *
 * <p><b>贴图约定</b>：白色不透明 + 其余透明。颜色由 {@link #draw} 通过
 * {@code GuiGraphics#setColor} 调制——{@code position_tex} 着色器的片元就是
 * {@code fragColor = texture(...) * ColorModulator}，而 {@code setColor} 设的正是
 * {@code ColorModulator}。因此同一张贴图能跟着按钮状态在
 * 「正常 / 悬停 / 禁用变灰 / 石质」之间换色，与原点阵版本行为一致。</p>
 *
 * <p><b>改动注意</b>：{@code GuiGraphics} 有多个 {@code blit} 重载，只有 11 参数那个能分别指定
 * 「目标边长」和「UV 范围」（{@code blit(tex, x, y, w, h, u, v, uW, vH, texW, texH)}）。
 * 9 参数那个（{@code blit(tex, x, y, uOffset, vOffset, w, h, texW, texH)}）会把 {@code w/h}
 * 同时当作 UV 宽度，贴图只被取左上角一块——改这里时别用错。</p>
 */
public final class Icons {

    /** 按钮用的小图标边长；贴图在 {@value #SMALL_DIR} 子目录下。 */
    public static final int SMALL_SIZE = 12;

    /** 任务卡片用的大图标边长；贴图在 {@value #LARGE_DIR} 子目录下。 */
    public static final int LARGE_SIZE = 16;

    /** 按钮小图标的子目录名：{@code icons/btn/prev.png}。 */
    public static final String SMALL_DIR = "btn";

    /** 任务卡片大图标的子目录名：{@code icons/stat/chart.png}。 */
    public static final String LARGE_DIR = "stat";

    public static final String PLAY = "play";
    public static final String PAUSE = "pause";
    public static final String STOP = "stop";
    public static final String CHART = "chart";
    public static final String GRID = "grid";
    public static final String GEAR = "gear";
    public static final String LOG = "log";
    public static final String TRIM = "trim";
    public static final String PREV = "prev";
    public static final String NEXT = "next";
    public static final String REFRESH = "refresh";
    public static final String COPY = "copy";
    public static final String FOLDER = "folder";
    public static final String ROLLBACK = "rollback";
    public static final String CLOSE = "close";

    /** 已知图标名。名字对不上就不画，避免画出原版那张「缺失贴图」的紫黑格子。 */
    private static final Set<String> KNOWN = Set.of(
            PLAY, PAUSE, STOP, CHART, GRID, GEAR, LOG, TRIM,
            PREV, NEXT, REFRESH, COPY, FOLDER, ROLLBACK, CLOSE);

    /** 贴图地址只在首次用到时拼一次；键是「子目录/文件名」。 */
    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();

    private Icons() {
    }

    /**
     * 按尺寸挑子目录：{@link #SMALL_SIZE} 用 {@value #SMALL_DIR}，其余用 {@value #LARGE_DIR}
     * （目前只有 16px）。某个图标没被某个尺寸用到时就没有对应文件。
     */
    private static ResourceLocation texture(String name, int size) {
        String dir = size == SMALL_SIZE ? SMALL_DIR : LARGE_DIR;
        return CACHE.computeIfAbsent(dir + "/" + name, key -> ResourceLocation.fromNamespaceAndPath(
                ChunkSmithGuiMod.MOD_ID, "textures/gui/icons/" + key + ".png"));
    }

    public static void draw(GuiGraphics g, String name, int x, int y, int size) {
        draw(g, name, x, y, size, 0xFFFFFFFF);
    }

    /** 在 (x, y) 处画一个边长 {@code size} 的图标，用 {@code color}（ARGB）染色。 */
    public static void draw(GuiGraphics g, String name, int x, int y, int size, int color) {
        if (name == null || !KNOWN.contains(name) || size <= 0) return;
        float a = ((color >>> 24) & 0xFF) / 255.0F;
        if (a <= 0.0F) return;
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float gg = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        g.setColor(r, gg, b, a);
        try {
            // 该尺寸的贴图边长正好是 size，目标也是 size ⇒ 1:1，UV 取满整张。
            // 必须用 11 参数这个重载（w,h 与 uW,vH 分开），9 参数那个会把 w/h 当成 UV 宽度而裁切。
            g.blit(texture(name, size), x, y, size, size, 0.0F, 0.0F, size, size, size, size);
        } finally {
            // 必须复位。setColor 改的是着色器全局 uniform（ColorModulator），
            // 不复位的话，之后画的面板底、文字、进度条会全部被染成这个颜色。
            g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
}
