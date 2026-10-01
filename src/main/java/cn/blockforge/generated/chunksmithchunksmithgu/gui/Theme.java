package cn.blockforge.generated.chunksmithchunksmithgu.gui;

import cn.blockforge.generated.chunksmithchunksmithgu.ChunkSmithGuiMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * “自然像素”风格主题：原木边框 + 羊皮纸内容区 + 石砖按钮列 + 绿色像素进度条。
 * 贴图在 assets/&lt;modid&gt;/textures/gui/ 下，装饰与底色全部按参考图配色。
 */
public final class Theme {

    private Theme() {
    }

    public static final ResourceLocation PARCHMENT = rl("parchment");
    public static final ResourceLocation WOOD = rl("wood");
    public static final ResourceLocation STONE = rl("stone");
    public static final ResourceLocation CORNER = rl("corner_flora");

    private static ResourceLocation rl(String name) {
        // 1.21 起 ResourceLocation 的构造器改为 private，必须走静态工厂。
        return ResourceLocation.fromNamespaceAndPath(ChunkSmithGuiMod.MOD_ID, "textures/gui/" + name + ".png");
    }

    // ---- 调色板（ARGB），取自参考图 ----
    public static final int INK = 0xFF4B2F16;        // 主文字：深棕
    public static final int INK_SOFT = 0xFF6E4A26;   // 次要文字
    public static final int LABEL = 0xFF5E3F1F;      // 表单标签棕
    public static final int TEXT_LIGHT = 0xFFF7ECD2; // 深色槽上的亮字
    public static final int WOOD_EDGE = 0xFF2E1C0E;  // 木框外沿深棕
    public static final int WOOD_LIT = 0x55FFE0A0;   // 木框内沿高亮
    public static final int PARCH = 0xFFE9D6A9;      // 羊皮纸底
    public static final int CARD = 0xC9A9A48B;       // 内容卡片（灰绿）
    public static final int CARD_EDGE = 0xFF55503E;  // 卡片描边
    public static final int SLOT = 0xFF3A352C;       // 进度条/输入框底槽
    public static final int SLOT_EDGE = 0xFF1F1B14;
    public static final int GREEN = 0xFF67C23A;      // 进度绿
    public static final int GREEN_HI = 0xFF8FE06A;
    public static final int GREEN_DK = 0xFF3F7D22;
    public static final int BTN_FACE = 0xFFF3E5C0;   // 米色按钮面
    public static final int BTN_HOVER = 0xFFFFF2D4;
    public static final int BTN_BORDER = 0xFF8C6A3F;
    public static final int BTN_BORDER_DK = 0xFF5E4423;
    public static final int BTN_DISABLED = 0xFFC8BCA0;
    public static final int BTN_TEXT = 0xFF4B2F16;
    public static final int BTN_TEXT_OFF = 0xFF8A7F68;
    public static final int STONE_BTN = 0xFFB9BCBE;  // 石质按钮面（重置类）
    public static final int STONE_BTN_TX = 0xFF44484C;
    public static final int OK = 0xFF4FA83B;
    public static final int WARN = 0xFFC98A1B;
    public static final int DANGER = 0xFFB44231;
    public static final int FOCUS = 0xFFD9A441;      // 焦点金框
    public static final int GOLD = 0xFFE3A72F;
    public static final int PLATE_WOOD = 0xFFD8B483;    // 标题木牌牌面
    public static final int PLATE_WOOD_HI = 0xFFF0D9AE; // 牌面顶部高光
    public static final int PLATE_WOOD_DK = 0xFFA8814F; // 牌面底部阴影

    /** 边框厚度（虚拟像素）。 */
    public static final int FRAME = 14;

    /** 16px 原尺寸平铺一块贴图到矩形区域。 */
    public static void tile(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h) {
        for (int ty = 0; ty < h; ty += 16) {
            int th = Math.min(16, h - ty);
            for (int tx = 0; tx < w; tx += 16) {
                int tw = Math.min(16, w - tx);
                g.blit(tex, x + tx, y + ty, 0F, 0F, tw, th, 16, 16);
            }
        }
    }

    /**
     * 画面板主体：深棕外沿、木纹边框四边、羊皮纸内区、四角藤蔓花草装饰。
     * 坐标为虚拟像素（缩放由外层 pose 处理）。
     */
    public static void drawPanel(GuiGraphics g, int x, int y, int w, int h, long t) {
        // 外投影
        g.fill(x + 2, y + h - 1, x + w + 2, y + h + 4, 0x55000000);
        g.fill(x + w - 1, y + 2, x + w + 4, y + h, 0x40000000);
        // 边框四带：木纹平铺
        tile(g, WOOD, x, y, w, FRAME);
        tile(g, WOOD, x, y + h - FRAME, w, FRAME);
        tile(g, WOOD, x, y + FRAME, FRAME, h - 2 * FRAME);
        tile(g, WOOD, x + w - FRAME, y + FRAME, FRAME, h - 2 * FRAME);
        // 外沿深色勾边 + 内沿高光
        stroke(g, x - 1, y - 1, x + w + 1, y + h + 1, WOOD_EDGE);
        stroke(g, x, y, x + w, y + h, WOOD_EDGE);
        stroke(g, x + FRAME - 1, y + FRAME - 1, x + w - FRAME + 1, y + h - FRAME + 1, WOOD_EDGE);
        stroke(g, x + FRAME, y + FRAME, x + w - FRAME, y + h - FRAME, WOOD_LIT);
        // 角柱：边框四角的深色木桩
        post(g, x, y);
        post(g, x + w - FRAME, y);
        post(g, x, y + h - FRAME);
        post(g, x + w - FRAME, y + h - FRAME);
        // 内容区：羊皮纸
        tile(g, PARCHMENT, x + FRAME, y + FRAME, w - 2 * FRAME, h - 2 * FRAME);
        // 角落装饰：藤蔓花草（同贴图贴四角，略微内收）
        if (cn.blockforge.generated.chunksmithchunksmithgu.Settings.decorations) {
            g.blit(CORNER, x - 4, y - 6, 0F, 0F, 32, 32, 32, 32);
            g.blit(CORNER, x + w - 28, y - 6, 0F, 0F, 32, 32, 32, 32);
            g.blit(CORNER, x - 4, y + h - 26, 0F, 0F, 32, 32, 32, 32);
            g.blit(CORNER, x + w - 28, y + h - 26, 0F, 0F, 32, 32, 32, 32);
        }
    }

    /** 边框四角的木桩（比边框更深一档，带高光棱）。 */
    private static void post(GuiGraphics g, int x, int y) {
        tile(g, WOOD, x, y, FRAME, FRAME);
        g.fill(x, y, x + FRAME, y + 1, WOOD_EDGE);
        g.fill(x, y, x + 1, y + FRAME, WOOD_EDGE);
        g.fill(x + FRAME - 2, y, x + FRAME, y + FRAME, 0x66000000);
        g.fill(x, y + FRAME - 2, x + FRAME, y + FRAME, 0x66000000);
        g.fill(x + 2, y + 2, x + 4, y + 4, 0x55FFDFA8);
    }

    /** 顶部居中的木牌标题：整块用纯色块画，不用贴图拉伸，任何缩放下都清晰不发糊。 */
    public static void drawTitlePlate(GuiGraphics g, int centerX, int y, String title) {
        Font f = Minecraft.getInstance().font;
        int tw = Math.max(96, f.width(title) + 36);
        int x = centerX - tw / 2;
        int h = 22;
        // 外沿深棕 → 木框 → 牌面 → 顶高光 / 底阴影
        g.fill(x, y, x + tw, y + h, WOOD_EDGE);
        g.fill(x + 1, y + 1, x + tw - 1, y + h - 1, BTN_BORDER);
        g.fill(x + 2, y + 2, x + tw - 2, y + h - 2, PLATE_WOOD);
        g.fill(x + 2, y + 2, x + tw - 2, y + 3, PLATE_WOOD_HI);
        g.fill(x + 2, y + h - 4, x + tw - 2, y + h - 2, PLATE_WOOD_DK);
        // 两侧的小木钉
        g.fill(x + 5, y + h / 2 - 1, x + 7, y + h / 2 + 1, WOOD_EDGE);
        g.fill(x + tw - 7, y + h / 2 - 1, x + tw - 5, y + h / 2 + 1, WOOD_EDGE);
        g.drawString(f, title, centerX - f.width(title) / 2, y + (h - 8) / 2 + 1, INK, true);
    }

    /** 灰绿内容卡片：分区用的底。 */
    public static void card(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, CARD);
        stroke(g, x - 1, y - 1, x + w + 1, y + h + 1, CARD_EDGE);
        // 角上的像素刻痕（参考图卡片四角的短边线）
        g.fill(x + 1, y + 1, x + 5, y + 2, 0x66FFFFFF);
        g.fill(x + w - 5, y + h - 1, x + w - 1, y + h, 0x66000000);
    }

    /** 深色槽：输入框 / 进度条 / 列表的凹底。 */
    public static void slot(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, SLOT);
        stroke(g, x - 1, y - 1, x + w + 1, y + h + 1, SLOT_EDGE);
        g.fill(x, y, x + w, y + 1, 0x66000000);
    }

    /** 1px 空心矩形。 */
    public static void stroke(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        g.fill(x0, y0, x1, y0 + 1, color);
        g.fill(x0, y1 - 1, x1, y1, color);
        g.fill(x0, y0, x0 + 1, y1, color);
        g.fill(x1 - 1, y0, x1, y1, color);
    }

    /** 米色像素按钮面（参考图“开始生成”那种）；hover 提亮，按下内凹。 */
    public static void buttonFace(GuiGraphics g, int x, int y, int w, int h, boolean hov, boolean down, boolean active, boolean stone) {
        int face = !active ? BTN_DISABLED : (stone ? (hov ? STONE_BTN | 0x00101010 : STONE_BTN) : (hov ? BTN_HOVER : BTN_FACE));
        int oy = down ? 1 : 0;
        g.fill(x, y + oy, x + w, y + h + oy, face);
        stroke(g, x - 1, y - 1 + oy, x + w + 1, y + h + 1 + oy, BTN_BORDER);
        if (down) {
            g.fill(x + 1, y + 1, x + w - 1, y + 3, 0x44000000);
            g.fill(x + 1, y + 1, x + 2, y + h - 1, 0x33000000);
        } else {
            g.fill(x + 1, y + oy + 1, x + w - 1, y + oy + 2, 0x66FFFFFF);
            g.fill(x + 1, y + h - 2 + oy, x + w - 1, y + h + oy, 0x3366461F);
        }
        // 四角深色刻点（参考图按钮的像素细节）
        int c = down ? 0x00000000 : BTN_BORDER_DK;
        g.fill(x, y + oy, x + 2, y + oy + 2, c);
        g.fill(x + w - 2, y + oy, x + w, y + oy + 2, c);
        g.fill(x, y + h - 2 + oy, x + 2, y + h + oy, c);
        g.fill(x + w - 2, y + h - 2 + oy, x + w, y + h + oy, c);
    }

    /** 绿色像素进度条填充：3px 一格带缝隙，顶亮底暗。 */
    public static void barFill(GuiGraphics g, int x, int y, int w, int h, int color) {
        for (int i = 0; i < w; i += 4) {
            int seg = Math.min(3, w - i);
            g.fill(x + i, y, x + i + seg, y + h, color);
        }
        if (color == GREEN) {
            for (int i = 0; i < w; i += 4) {
                int seg = Math.min(3, w - i);
                g.fill(x + i, y, x + i + seg, y + 1, GREEN_HI);
            }
        }
        g.fill(x, y + h - 1, x + w, y + h, 0x55000000);
    }

    /** 通用小文本绘制。 */
    public static void text(GuiGraphics g, String s, int x, int y, int color, boolean shadow) {
        Font f = Minecraft.getInstance().font;
        g.drawString(f, s, x, y, color, shadow);
    }

    /** 数字/耗时格式化。 */
    public static String fmtDuration(long ms) {
        if (ms < 0) return "--";
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        if (h > 0) return h + "h" + m + "m" + ss + "s";
        if (m > 0) return m + "m" + ss + "s";
        return ss + "s";
    }

    public static String fmtCount(long n) {
        if (n < 0) return "--";
        if (n >= 1_000_000) return String.format("%.2fM", n / 1e6);
        if (n >= 10_000) return String.format("%.1fk", n / 1e3);
        return String.valueOf(n);
    }
}
