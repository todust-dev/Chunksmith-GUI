package cn.blockforge.generated.chunksmithchunksmithgu.gui;

import cn.blockforge.generated.chunksmithchunksmithgu.Texts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * 面板自绘控件集（自然像素风：米色木框按钮、深色槽、绿色分段进度条）。
 * 事件由 PanelScreen 统一路由：先把鼠标换算进虚拟坐标，再逐个命中测试。
 */
public abstract class Widget {
    public int x, y, w, h;
    public boolean visible = true;
    public boolean active = true;

    protected Widget(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
    }

    // ==================== 面板变换与裁剪 ====================

    /**
     * 面板是“按固定虚拟分辨率画好，再整体缩放居中”的（translate + scale）。
     * 但 {@link GuiGraphics#enableScissor} 只按窗口的 GUI 缩放换算，完全无视 pose 变换，
     * 直接传虚拟坐标会把裁剪框放错位置：输入框里的字被整行裁掉、日志文字反而溢出框外。
     * 所以 PanelScreen 每帧把当前变换写在这里，开裁剪前先手工换算成 GUI 坐标。
     */
    public static int VIEW_OX = 0, VIEW_OY = 0;
    public static double VIEW_SCALE = 1.0;

    /** 在虚拟坐标下开启裁剪（内部换算成 enableScissor 真正需要的 GUI 坐标）。 */
    protected static void clipOn(GuiGraphics g, int x0, int y0, int x1, int y1) {
        int ax = VIEW_OX + (int) Math.floor(x0 * VIEW_SCALE);
        int ay = VIEW_OY + (int) Math.floor(y0 * VIEW_SCALE);
        int bx = VIEW_OX + (int) Math.ceil(x1 * VIEW_SCALE);
        int by = VIEW_OY + (int) Math.ceil(y1 * VIEW_SCALE);
        g.enableScissor(ax, ay, Math.max(ax + 1, bx), Math.max(ay + 1, by));
    }

    /**
     * 按像素宽度折行：中文逐字断，英文短语优先在空格/斜杠处断。
     * 宁可多占一行，也不让文字跑到框外面去。
     */
    public static List<String> wrap(Font font, String text, int maxW) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) { out.add(""); return out; }
        if (maxW <= 8 || font.width(text) <= maxW) { out.add(text); return out; }
        int start = 0;
        while (start < text.length()) {
            int end = start, wSum = 0, lastBreak = -1;
            while (end < text.length()) {
                char c = text.charAt(end);
                int cw = font.width(String.valueOf(c));
                if (wSum + cw > maxW) break;
                if (c == ' ' || c == ',' || c == '/' || c == '|' || c == '·') lastBreak = end;
                wSum += cw;
                end++;
            }
            if (end >= text.length()) { out.add(text.substring(start)); break; }
            if (lastBreak > start + (end - start) / 2) end = lastBreak + 1;
            if (end <= start) end = start + 1;   // 单字符比整行还宽时至少推进一个字符，防死循环
            out.add(text.substring(start, end));
            start = end;
        }
        if (out.isEmpty()) out.add(text);
        return out;
    }

    public boolean contains(double mx, double my) {
        return visible && mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public abstract void render(GuiGraphics g, int mx, int my, float dt);

    public boolean mouseClicked(double mx, double my, int button) { return false; }
    public boolean mouseReleased(double mx, double my, int button) { return false; }
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) { return false; }
    public boolean mouseScrolled(double mx, double my, double amount) { return false; }
    public boolean keyPressed(int key, int sc, int mod) { return false; }
    public boolean charTyped(char ch, int mod) { return false; }
    public void setFocus(boolean f) { }
    public boolean wantsFocus() { return false; }
    /**
     * 是否真正处于编辑状态（报告 P1-3）。PanelScreen 用它判断挂着的 focus 是否已经"空转"：
     * 输入框按完 Enter、文本区按 Esc 退出单行编辑后，控件自己已经不再接收输入，
     * 但 PanelScreen.focus 还指着它——不清理的话后续所有按键都被吞掉，键盘像卡死一样。
     */
    public boolean isEditing() { return false; }

    // ==================== 按钮 ====================

    public static class Button extends Widget {
        public String label = "";
        public String iconPath = null;       // Icons 里的图标名（按钮统一按 12px 画）
        public Runnable onPress = () -> { };
        public int textColor = Theme.BTN_TEXT;
        public boolean stone;                // 石质外观（重置/取消类）
        private long pressUntil;

        public Button(int x, int y, int w, int h, String label) {
            super(x, y, w, h);
            this.label = label;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float dt) {
            boolean hov = contains(mx, my) && active;
            boolean down = System.currentTimeMillis() < pressUntil;
            Theme.buttonFace(g, x, y, w, h, hov, down, active, stone);
            Font fnt = Minecraft.getInstance().font;
            // 按钮图标用小图标尺寸（Icons.SMALL_SIZE = 12）：贴图 icons/btn/<名字>.png 就是这个边长，
            // 1:1 绘制不重采样。（原来是 11——16 格压到 11 个像素丢的笔画更多；12 正好贴合按钮
            // 内部高度：24x16 的按钮上下各留 2px、左右各留 4px。）
            int iconW = iconPath != null ? Icons.SMALL_SIZE : 0;
            int total = iconW + (iconW > 0 ? 4 : 0) + fnt.width(label);
            int tx = x + (w - total) / 2;
            int ty = y + (h - 8) / 2 + (down ? 1 : 0);
            if (iconPath != null) {
                Icons.draw(g, iconPath, tx, y + (h - iconW) / 2 + (down ? 1 : 0), iconW,
                        active ? (stone ? Theme.STONE_BTN_TX : Theme.BTN_TEXT) : Theme.BTN_TEXT_OFF);
            }
            int c = !active ? Theme.BTN_TEXT_OFF : (stone ? Theme.STONE_BTN_TX : textColor);
            g.drawString(fnt, label, tx + iconW + (iconW > 0 ? 4 : 0), ty, c, false);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (contains(mx, my) && active && button == 0) {
                pressUntil = System.currentTimeMillis() + 110;
                playClick();
                onPress.run();
                return true;
            }
            return false;
        }

        static void playClick() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
            }
        }
    }

    /** 循环选项按钮：方形 >（支持显示名与原始值分离）。 */
    public static class Cycle extends Button {
        public final List<String> options = new ArrayList<>();
        public final List<String> display = new ArrayList<>();
        public int index;
        public Consumer<Integer> onChange = i -> { };
        public String prefix;

        public Cycle(int x, int y, int w, int h, String prefix, List<String> opts, List<String> disp, int initial) {
            super(x, y, w, h, "");
            this.prefix = prefix;
            options.addAll(opts);
            if (disp != null && disp.size() == opts.size()) display.addAll(disp);
            else for (String o : opts) display.add(o);
            index = Math.max(0, Math.min(initial, options.size() - 1));
            syncLabel();
        }

        public Cycle(int x, int y, int w, int h, String prefix, List<String> opts, int initial) {
            this(x, y, w, h, prefix, opts, null, initial);
        }

        public String value() { return options.isEmpty() ? "" : options.get(index); }

        public void syncLabel() {
            if (display.isEmpty()) { label = prefix + Texts.t("widget.cycle.suffix"); return; }
            index = Math.max(0, Math.min(index, display.size() - 1));
            label = prefix + display.get(index) + Texts.t("widget.cycle.suffix");
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!contains(mx, my) || !active) return false;
            if (options.isEmpty()) return true;   // 空选项列表：吞掉点击但不取模（会除零）
            if (button == 1) index = (index + options.size() - 1) % options.size();
            else if (button == 0) index = (index + 1) % options.size();
            else return false;
            syncLabel();
            playClick();
            onChange.accept(index);
            return true;
        }
    }

    /** 开关按钮。 */
    public static class Toggle extends Button {
        public boolean on;
        public Consumer<Boolean> onChange = b -> { };
        public String name;

        public Toggle(int x, int y, int w, int h, String name, boolean on) {
            super(x, y, w, h, "");
            this.name = name;
            this.on = on;
            syncLabel();
        }

        public void syncLabel() {
            label = name + Texts.t(on ? "widget.toggle.on" : "widget.toggle.off");
            textColor = on ? Theme.OK : Theme.INK_SOFT;
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!contains(mx, my) || !active || button != 0) return false;
            on = !on;
            syncLabel();
            playClick();
            onChange.accept(on);
            return true;
        }
    }

    // ==================== 输入类 ====================

    /** 单行输入框（文本 / 整数），支持范围校验。 */
    public static class Field extends Widget {
        public String value;
        public final boolean numeric;
        public long min, max;
        public boolean focused;
        public int caret;
        public Runnable onChanged = () -> { };

        public Field(int x, int y, int w, int h, String initial, boolean numeric, long min, long max) {
            super(x, y, w, h);
            this.value = initial;
            this.numeric = numeric;
            this.min = min; this.max = max;
            this.caret = value.length();
        }

        public static Field num(int x, int y, int w, int h, long v, long min, long max) {
            return new Field(x, y, w, h, String.valueOf(v), true, min, max);
        }
        public static Field str(int x, int y, int w, int h, String v) {
            return new Field(x, y, w, h, v, false, 0, 0);
        }

        public long longValue() {
            try { return Long.parseLong(value.trim()); } catch (Exception e) { return min; }
        }

        public boolean valid() {
            if (!numeric) return value != null && !value.isBlank();
            try {
                long v = Long.parseLong(value.trim());
                return v >= min && v <= max;
            } catch (Exception e) { return false; }
        }

        @Override public boolean wantsFocus() { return active; }
        @Override public void setFocus(boolean f) { this.focused = f; }
        @Override public boolean isEditing() { return focused; }

        @Override
        public void render(GuiGraphics g, int mx, int my, float dt) {
            Theme.slot(g, x, y, w, h);
            if (active && focused) Theme.stroke(g, x - 2, y - 2, x + w + 2, y + h + 2, Theme.FOCUS);
            else if (!valid()) Theme.stroke(g, x, y, x + w, y + h, Theme.DANGER);
            Font font = Minecraft.getInstance().font;
            boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
            String shown = value == null ? "" : value;
            int tw = font.width(shown);
            int offset = 0;
            if (tw > w - 8) {
                int cw = font.width(shown.substring(0, Math.min(caret, shown.length())));
                offset = Math.max(0, Math.min(cw - (w - 10), tw - (w - 8)));
            }
            clipOn(g, x + 2, y + 2, x + w - 2, y + h - 2);
            try {
                g.drawString(font, shown, x + 4 - offset, y + (h - 8) / 2, active ? Theme.TEXT_LIGHT : Theme.BTN_TEXT_OFF, false);
                if (focused && blink) {
                    int cx = x + 4 - offset + font.width(shown.substring(0, Math.min(caret, shown.length())));
                    g.fill(cx, y + 3, cx + 1, y + h - 3, Theme.GOLD);
                }
            } finally {
                g.disableScissor();
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!active) return false;
            if (contains(mx, my)) {
                focused = true;
                caret = value.length();
                return true;
            }
            focused = false;
            return false;
        }

        @Override
        public boolean keyPressed(int key, int sc, int mod) {
            if (!focused || !active) return false;
            net.minecraft.client.KeyboardHandler kh = Minecraft.getInstance().keyboardHandler;
            if (net.minecraft.client.gui.screens.Screen.isPaste(key)) {
                String clip = kh.getClipboard();
                if (clip != null) insert(clip.replaceAll("\n", " "));
                return true;
            }
            if (net.minecraft.client.gui.screens.Screen.hasControlDown()) return false;
            switch (key) {
                case 259: // backspace
                    if (caret > 0) { value = value.substring(0, caret - 1) + value.substring(Math.min(caret, value.length())); caret--; changed(); }
                    return true;
                case 261: // delete
                    if (caret < value.length()) { value = value.substring(0, caret) + value.substring(caret + 1); changed(); }
                    return true;
                case 263: caret = Math.max(0, caret - 1); return true;   // left
                case 262: caret = Math.min(value.length(), caret + 1); return true; // right
                case 268: caret = 0; return true;                        // home
                case 269: caret = value.length(); return true;           // end
                case 257:                                                 // enter：提交但保留焦点
                    // （报告 P1-3）之前在这里把 focused 置 false，PanelScreen.focus 却还指着本框：
                    // 之后所有按键都被上层"聚焦时一律吞掉"的规则吃掉，直到再按一次 Esc。
                    // 现在 Enter 只做提交，光标留在框里，继续打字不丢。关焦点交给 Esc/点击别处。
                    onChanged.run(); return true;
                default:
                    return false;
            }
        }

        @Override
        public boolean charTyped(char ch, int mod) {
            if (!focused || !active) return false;
            if (ch == 10 || ch == 13) return false;
            if (numeric && !(Character.isDigit(ch) || (ch == '-' && caret == 0))) return false;
            if (value.length() >= 24) return false;
            insert(String.valueOf(ch));
            return true;
        }

        private void insert(String s) {
            value = value.substring(0, Math.min(caret, value.length())) + s + value.substring(Math.min(caret, value.length()));
            caret += s.length();
            changed();
        }

        private void changed() {
            if (value.length() > 24) value = value.substring(0, 24);
            caret = Math.min(caret, value.length());
            onChanged.run();
        }
    }

    /** 多行文本区（命令预览编辑用；整行可编辑）。 */
    public static class TextArea extends Widget {
        public List<String> lines = new ArrayList<>();
        public int editLine = -1;
        public String editBuf = "";
        public int caret;
        public Consumer<List<String>> onEdited = l -> { };
        protected int scroll;

        public TextArea(int x, int y, int w, int h) {
            super(x, y, w, h);
        }

        public void setLines(List<String> ls) {
            lines = new ArrayList<>(ls);
            editLine = -1;
            scroll = 0;
        }

        public String join() { return String.join("\n", lines); }

        @Override public boolean wantsFocus() { return active; }
        @Override public void setFocus(boolean f) { if (!f) editLine = -1; }
        @Override public boolean isEditing() { return editLine >= 0; }

        @Override
        public void render(GuiGraphics g, int mx, int my, float dt) {
            renderBase(g);
            Font font = Minecraft.getInstance().font;
            int lh = 12;
            int visible = Math.max(1, (h - 6) / lh);
            scroll = Math.max(0, Math.min(scroll, lines.size() - visible));
            clipOn(g, x + 2, y + 2, x + w - 2, y + h - 2);
            try {
            for (int i = 0; i < visible && i + scroll < lines.size(); i++) {
                int idx = i + scroll;
                String line = lines.get(idx);
                String shown = line;
                int ty = y + 4 + i * lh;
                if (idx == editLine) {
                    shown = editBuf;
                    g.fill(x + 3, ty - 2, x + w - 4, ty + lh - 2, 0x33D9A441);
                }
                g.drawString(font, shown, x + 6, ty, lineColor(line), false);
                if (idx == editLine && (System.currentTimeMillis() / 500) % 2 == 0) {
                    int cx = x + 6 + font.width(editBuf.substring(0, Math.min(caret, editBuf.length())));
                    g.fill(cx, ty, cx + 1, ty + 9, Theme.GOLD);
                }
            }
            } finally {
                g.disableScissor();
            }
            drawScrollbar(g, visible);
        }

        /** 命令行的着色：注释灰、trim 红、普通亮。 */
        protected int lineColor(String line) {
            if (line.startsWith("#")) return Theme.BTN_TEXT_OFF;
            if (line.contains("trim")) return Theme.DANGER;
            return 0xFFE8D9AE;
        }

        protected void renderBase(GuiGraphics g) {
            Theme.slot(g, x, y, w, h);
        }

        protected void drawScrollbar(GuiGraphics g, int visible) {
            if (lines.size() > visible) {
                int barH = Math.max(8, h * visible / lines.size());
                int barY = y + (int) ((h - barH) * (scroll / (double) Math.max(1, lines.size() - visible)));
                g.fill(x + w - 3, barY, x + w - 1, barY + barH, Theme.BTN_BORDER);
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!active || !contains(mx, my)) return false;
            Font font = Minecraft.getInstance().font;
            int lh = 12;
            int idx = (int) ((my - y - 4) / lh) + scroll;
            if (idx >= 0 && idx < lines.size()) {
                editLine = idx;
                editBuf = lines.get(idx);
                int acc = 0; caret = 0;
                for (int i = 0; i < editBuf.length(); i++) {
                    acc += font.width(String.valueOf(editBuf.charAt(i)));
                    if (x + 6 + acc > mx) break;
                    caret = i + 1;
                }
            } else {
                editLine = -1;
            }
            return true;
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (!contains(mx, my)) return false;
            scroll -= (int) Math.signum(amount) * 2;
            return true;
        }

        @Override
        public boolean keyPressed(int key, int sc, int mod) {
            if (editLine < 0 || !active) return false;
            if (net.minecraft.client.gui.screens.Screen.isPaste(key)) {
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                if (clip != null) {
                    String one = clip.replace("\n", " ");
                    editBuf = editBuf.substring(0, caret) + one + editBuf.substring(caret);
                    caret += one.length();
                    commit(true);
                }
                return true;
            }
            switch (key) {
                case 256 -> { editLine = -1; return true; }              // esc 退出编辑
                case 257, 335 -> {                                        // enter / 小键盘enter
                    editLine = Math.min(lines.size() - 1, editLine + 1);
                    if (editLine >= 0) { editBuf = lines.get(editLine); caret = editBuf.length(); }
                    return true;
                }
                case 259 -> {
                    if (caret > 0) { editBuf = editBuf.substring(0, caret - 1) + editBuf.substring(caret); caret--; commit(true); }
                    return true;
                }
                case 263 -> { caret = Math.max(0, caret - 1); return true; }
                case 262 -> { caret = Math.min(editBuf.length(), caret + 1); return true; }
                case 261 -> {
                    if (caret < editBuf.length()) { editBuf = editBuf.substring(0, caret) + editBuf.substring(caret + 1); commit(true); }
                    return true;
                }
                default -> { return false; }
            }
        }

        @Override
        public boolean charTyped(char ch, int mod) {
            if (editLine < 0 || !active || ch < 32) return false;
            editBuf = editBuf.substring(0, caret) + ch + editBuf.substring(caret);
            caret++;
            commit(true);
            return true;
        }

        private void commit(boolean live) {
            if (editLine >= 0 && editLine < lines.size()) lines.set(editLine, editBuf);
            if (live) onEdited.accept(lines);
        }
    }

    /** 只读滚动文本（日志用），支持逐行颜色，刷新不打断滚动位置。 */
    public static final class Line {
        public final String text; public final int color;
        public Line(String t, int c) { text = t; color = c; }
    }

    public static class ScrollText extends Widget {
        public List<Line> lines = new ArrayList<>();
        private int scroll;
        private int lastVisible;

        public ScrollText(int x, int y, int w, int h) {
            super(x, y, w, h);
        }

        /** 更新内容但保留滚动位置（顶部对齐时跟随新内容）。 */
        public void setLines(List<Line> ls) {
            boolean atTop = scroll <= 0;
            lines = ls;
            clampScroll();
            if (atTop) scroll = 0;
        }

        private void clampScroll() {
            scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - Math.max(1, lastVisible))));
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float dt) {
            Theme.slot(g, x, y, w, h);
            Font font = Minecraft.getInstance().font;
            int lh = 12;
            int maxRows = Math.max(1, (h - 6) / lh);
            lastVisible = maxRows;
            clampScroll();
            clipOn(g, x + 2, y + 2, x + w - 2, y + h - 2);
            try {
                // 长行先折成多行再画，画不满就停——文字既不会溢出框，也不会被切掉半截
                int innerW = Math.max(16, w - 12);
                int row = 0;
                for (int i = scroll; i < lines.size() && row < maxRows; i++) {
                    Line ln = lines.get(i);
                    for (String seg : wrap(font, ln.text, innerW)) {
                        if (row >= maxRows) break;
                        g.drawString(font, seg, x + 6, y + 4 + row * lh, ln.color, false);
                        row++;
                    }
                }
            } finally {
                g.disableScissor();
            }
            if (lines.size() > maxRows) {
                int barH = Math.max(8, h * maxRows / lines.size());
                int barY = y + (int) ((h - barH) * (scroll / (double) Math.max(1, lines.size() - maxRows)));
                g.fill(x + w - 3, barY, x + w - 1, barY + barH, Theme.BTN_BORDER);
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double amount) {
            if (!contains(mx, my)) return false;
            scroll -= (int) Math.signum(amount) * 3;
            clampScroll();
            return true;
        }
    }

    // ==================== 展示类 ====================

    /** 纯文本标签。 */
    public static class Label extends Widget {
        public String text;
        public final boolean big;
        public int colorOverride = 0;

        public Label(int x, int y, String text, boolean big) {
            super(x, y, 10, big ? 12 : 8);
            this.text = text;
            this.big = big;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float dt) {
            Font f = Minecraft.getInstance().font;
            int c = colorOverride != 0 ? colorOverride : (big ? Theme.INK : Theme.LABEL);
            // 关掉字体阴影：面板按非整数倍整体缩放，1px 的影子会被放大成看得见的“第二层字”，
            // 中文笔画本来就密，重影一叠特别刺眼（用户反馈图 2 的图例文字）。
            g.drawString(f, text, x, y, c, false);
        }
    }

    /** 参考图样式的进度行卡片：图标 + 名称 + 右侧数值 + 下方绿色条。 */
    public static class RowCard extends Widget {
        public String title;
        public String icon;                              // Icons 名，可 null
        public DoubleSupplier frac;                      // <0 未知
        public Supplier<String> valueText;               // 右上角数值
        public int valueColor = Theme.INK;

        public RowCard(int x, int y, int w, int h, String title, String icon,
                       DoubleSupplier frac, Supplier<String> valueText) {
            super(x, y, w, h);
            this.title = title; this.icon = icon; this.frac = frac; this.valueText = valueText;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float dt) {
            Font f = Minecraft.getInstance().font;
            Theme.card(g, x, y, w, h);
            if (icon != null) Icons.draw(g, icon, x + 8, y + 8, Icons.LARGE_SIZE, 0xFFFFFFFF);
            g.drawString(f, title, x + 30, y + 6, Theme.INK, false);
            String v = valueText == null ? "" : valueText.get();
            g.drawString(f, v, x + w - 10 - f.width(v), y + 6, valueColor, false);
            int bx = x + 30, bw = w - 40;
            int by = y + h - 14;
            double fr = frac == null ? -1 : frac.getAsDouble();
            Theme.slot(g, bx, by, bw, 9);
            if (fr >= 0) {
                int fw = (int) ((bw - 2) * Math.min(1.0, Math.max(0.0, fr)));
                if (fw > 0) Theme.barFill(g, bx + 1, by + 1, fw, 7, Theme.GREEN);
            } else {
                long t = System.currentTimeMillis() / 40;
                for (int i = 0; i < bw - 2; i += 8) {
                    int cx = (int) ((t + i) % (bw + 24)) - 24;
                    if (cx > 0 && cx < bw - 8) g.fill(bx + 1 + cx, by + 2, bx + 5 + cx, by + 7, 0x6667C23A);
                }
            }
        }
    }

    /** 横向滑条（界面大小用）。拖动即时回调。 */
    public static class Slider extends Widget {
        public int min, max, value, step;
        public Consumer<Integer> onChange = v -> { };
        private boolean dragging;

        public Slider(int x, int y, int w, int h, int min, int max, int value, int step) {
            super(x, y, w, h);
            this.min = min; this.max = max; this.value = value; this.step = step;
        }

        private double frac() { return (value - min) / (double) Math.max(1, max - min); }

        private void setFromMouse(double mx) {
            double fr = (mx - x - 4) / (double) Math.max(1, w - 8);
            fr = Math.max(0, Math.min(1, fr));
            int raw = (int) Math.round(min + fr * (max - min));
            raw = min + Math.round((raw - min) / (float) step) * step;
            int nv = Math.max(min, Math.min(max, raw));
            if (nv != value) { value = nv; onChange.accept(value); }
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float dt) {
            int ty = y + h / 2 - 3;
            Theme.slot(g, x, ty, w, 6);
            int fw = (int) ((w - 4) * frac());
            if (fw > 0) Theme.barFill(g, x + 2, ty + 1, fw, 4, Theme.GREEN);
            // 木质旋钮
            int kx = x + 2 + (int) ((w - 12) * frac());
            g.fill(kx, y, kx + 9, y + h, Theme.BTN_BORDER);
            g.fill(kx + 1, y + 1, kx + 8, y + h - 1, 0xFF8A5A32);
            g.fill(kx + 2, y + 2, kx + 7, y + 4, 0xFFB07A45);
            g.fill(kx + 2, y + h - 4, kx + 7, y + h - 2, 0x66000000);
            if ((contains(mx, my) || dragging) && active) {
                Theme.stroke(g, kx - 1, y - 1, kx + 10, y + h + 1, Theme.GOLD);
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (!active || button != 0) return false;
            if (contains(mx, my)) {
                dragging = true;
                setFromMouse(mx);
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
            if (dragging && button == 0) { setFromMouse(mx); return true; }
            return false;
        }

        @Override
        public boolean mouseReleased(double mx, double my, int button) {
            if (dragging) { dragging = false; return true; }
            return false;
        }
    }

    /**
     * 热力图画布：区块存在 = 绿点；图案范围 = 金色半透覆盖；刷新位图缓存。
     *
     * 性能策略（报告 P0-9）：
     *  - 位图缓存 key 用“量化后的中心”而不是原始中心：玩家小幅移动只平移旧缓存，
     *    跨过量化网格才重建，不再每帧做几万次 HashSet 查询；
     *  - 缓存分辨率上限 128；
     *  - 绘制时把相邻同色缓存格合并成一条横向矩形，fill 调用降一个数量级；
     *  - 所有填充裁剪进画布矩形，平移期间不会画到框外。
     */
    public static class HeatCanvas extends Widget {
        public int half = 128;
        public double scx, scz;          // 图案中心（区块）
        private int[][] cache;           // 每格颜色，null=空
        private int cacheHalf = -1;
        private long cacheKey = -1;
        private double cacheQcx, cacheQcz;   // 缓存锚定的量化中心
        private int cacheStep = 1;           // 一格 = 多少区块
        private int cacheQuant = 4;          // 中心量化步长（区块）

        public HeatCanvas(int x, int y, int w, int h) {
            super(x, y, w, h);
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float dt) {
            Theme.slot(g, x, y, w, h);
            cn.blockforge.generated.chunksmithchunksmithgu.PanelState.HeatData heat =
                    cn.blockforge.generated.chunksmithchunksmithgu.PanelState.heat;
            Font f = Minecraft.getInstance().font;
            if (heat == null) {
                String s = Texts.t("heat.empty");
                g.drawString(f, s, x + w / 2 - f.width(s) / 2, y + h / 2 - 4, Theme.BTN_TEXT_OFF, false);
                return;
            }
            int size = Math.min(w - 4, h - 4);
            int cx0 = x + (w - size) / 2, cy0 = y + (h - size) / 2;
            int n = rebuild(heat);
            if (n <= 0) return;
            float cell = size / (float) n;
            // 当前中心相对缓存锚点的偏移（换成缓存格单位）；缓存四周留了一个量化步的余量
            double oxCh = (scx - cacheQcx + cacheQuant / 2.0) / Math.max(1, cacheStep);
            double ozCh = (scz - cacheQcz + cacheQuant / 2.0) / Math.max(1, cacheStep);
            for (int r = 0; r < n; r++) {
                int[] row = cache[r];
                if (row == null) continue;
                int py0 = cy0 + (int) Math.floor((r - ozCh) * cell);
                int py1 = Math.max(py0 + 1, cy0 + (int) Math.ceil((r + 1 - ozCh) * cell));
                if (py1 <= cy0 || py0 >= cy0 + size) continue;
                py0 = Math.max(py0, cy0); py1 = Math.min(py1, cy0 + size);
                int c = 0;
                while (c < n) {
                    int col = row[c];
                    if (col == 0) { c++; continue; }
                    int runEnd = c + 1;
                    while (runEnd < n && row[runEnd] == col) runEnd++;
                    int px0 = cx0 + (int) Math.floor((c - oxCh) * cell);
                    int px1 = Math.max(px0 + 1, cx0 + (int) Math.ceil((runEnd - oxCh) * cell));
                    px0 = Math.max(px0, cx0); px1 = Math.min(px1, cx0 + size);
                    if (px0 < px1) g.fill(px0, py0, px1, py1, col);
                    c = runEnd;
                }
            }
            // 玩家位置十字
            g.fill(cx0 + size / 2 - 4, cy0 + size / 2, cx0 + size / 2 + 5, cy0 + size / 2 + 1, 0xFFFFFFFF);
            g.fill(cx0 + size / 2, cy0 + size / 2 - 4, cx0 + size / 2 + 1, cy0 + size / 2 + 5, 0xFFFFFFFF);
            String info = Texts.t("heat.canvas.info",
                    heat.dim, half, Theme.fmtCount(heat.chunks.size()));
            g.drawString(f, info, x + 4, y + h - 12, Theme.TEXT_LIGHT, false);
        }

        private long visualKey(cn.blockforge.generated.chunksmithchunksmithgu.PanelState.HeatData heat,
                               double qcx, double qcz) {
            long key = heat.receivedMs;
            key = 31 * key + Double.doubleToLongBits(qcx);
            key = 31 * key + Double.doubleToLongBits(qcz);
            key = 31 * key + cn.blockforge.generated.chunksmithchunksmithgu.PanelState.shape.hashCode();
            key = 31 * key + cn.blockforge.generated.chunksmithchunksmithgu.PanelState.radius;
            key = 31 * key + cn.blockforge.generated.chunksmithchunksmithgu.PanelState.radius2;
            return key;
        }

        /** 需要就重建缓存；返回缓存边长（0 = 无缓存）。 */
        private int rebuild(cn.blockforge.generated.chunksmithchunksmithgu.PanelState.HeatData heat) {
            // 量化步长按窗口大小自适应：小窗口（±16）用 2 区块网格，大窗口（±256）用 8；
            // 图案叠加最多偏移半个步长，窗口越大相对误差越小
            int quant = Math.max(2, Math.min(8, half / 16 * 2));
            double qcx = Math.round(scx / (double) quant) * quant;
            double qcz = Math.round(scz / (double) quant) * quant;
            long key = visualKey(heat, qcx, qcz);
            if (cache != null && cacheHalf == half && cacheKey == key && cacheQuant == quant) return cache.length;
            int margin = quant;                     // 缓存四周多出的区块余量（= 一个步长，两侧各半）
            int span = 2 * half + 1 + margin;
            int n = Math.min(span, 128);            // 位图分辨率上限（报告建议值）
            int step = Math.max(1, (span + n - 1) / n);
            n = (span + step - 1) / step;
            cache = new int[n][n];
            cacheHalf = half;
            cacheKey = key;
            cacheQcx = qcx;
            cacheQcz = qcz;
            cacheStep = step;
            cacheQuant = quant;
            int minC = (int) qcx - half - margin / 2, minR = (int) qcz - half - margin / 2;
            String shape = cn.blockforge.generated.chunksmithchunksmithgu.PanelState.shape;
            int r1 = cn.blockforge.generated.chunksmithchunksmithgu.PanelState.radius;
            int r2 = cn.blockforge.generated.chunksmithchunksmithgu.PanelState.radius2;
            for (int gz = 0; gz < span; gz += step) {
                for (int gx = 0; gx < span; gx += step) {
                    int wx = minC + gx, wz = minR + gz;
                    boolean exists = false;
                    outer:
                    for (int dz = 0; dz < step && !exists; dz++) {
                        for (int dx = 0; dx < step; dx++) {
                            if (heat.chunks.contains(cn.blockforge.generated.chunksmithchunksmithgu.PanelState.pos(wx + dx, wz + dz))) {
                                exists = true; break outer;
                            }
                        }
                    }
                    boolean inShape = PatternMath.inShape(shape, wx, wz, qcx, qcz, r1, r2);
                    int col = 0;
                    if (exists) col = inShape ? 0xFF7BC950 : 0x66A9A48B;
                    else if (inShape) col = 0x33E3A72F;
                    if (col != 0) cache[gz / step][gx / step] = col;
                }
            }
            return n;
        }
    }
}
