package cn.blockforge.generated.chunksmithchunksmithgu.gui;

import cn.blockforge.generated.chunksmithchunksmithgu.ChunkSmithGuiPaths;
import cn.blockforge.generated.chunksmithchunksmithgu.CommandBuilder;
import cn.blockforge.generated.chunksmithchunksmithgu.CommandRunner;
import cn.blockforge.generated.chunksmithchunksmithgu.PanelState;
import cn.blockforge.generated.chunksmithchunksmithgu.PrereqStatus;
import cn.blockforge.generated.chunksmithchunksmithgu.Settings;
import cn.blockforge.generated.chunksmithchunksmithgu.Texts;
import cn.blockforge.generated.chunksmithchunksmithgu.net.Net;
import cn.blockforge.generated.chunksmithchunksmithgu.net.NetClient;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 主面板：参考图的“木质边框 + 羊皮纸 + 左侧进度卡片 + 右侧按钮列 + 底部界面大小”布局。
 *
 * 所有控件按固定的虚拟分辨率 {@link #VW}x{@link #VH} 摆放，渲染时整体缩放并居中；
 * 鼠标坐标再反算回虚拟坐标，所以底部滑条调大小后，点击位置依然对得上。
 * 事件全部手动路由（面板没有用原版 AbstractWidget），命中的控件返回 true 即吞掉事件。
 */
public class PanelScreen extends Screen {

    public enum Tab { GENERATE, TASK, TRIM, HEAT, LOG, CONFIG }

    private static final Tab[] TABS = Tab.values();

    // 界面文案全部外置到 assets/chunksmith_modern_gui/lang/zh_cn.json，这里只留键名。
    // 想改字直接改语言文件（或写个资源包覆盖），不用重新编译。
    private static final String[] TAB_LABEL_KEYS = {
            "tab.generate", "tab.task", "tab.trim", "tab.heat", "tab.log", "tab.config"};
    private static final String[] TAB_TITLE_KEYS = {
            "title.generate", "title.task", "title.trim", "title.heat", "title.log", "title.config"};

    /** 虚拟设计分辨率：面板按这套坐标画，再整体缩放到屏幕。 */
    public static final int VW = 660;
    public static final int VH = 400;

    /** 形状 / 中心 / 日志过滤的「值」是要发给 Chunksmith 的指令参数，绝对不能翻译。 */
    private static final List<String> SHAPES = List.of("square", "circle", "diamond", "triangle", "star", "oval");
    private static final String[] SHAPE_NAME_KEYS =
            {"shape.square", "shape.circle", "shape.diamond", "shape.triangle", "shape.star", "shape.oval"};
    private static final List<String> CENTERS = List.of("here", "spawn", "coords");
    private static final String[] CENTER_NAME_KEYS = {"center.here", "center.spawn", "center.coords"};
    private static final String[] LOG_FILTER_KEYS = {"logfilter.all", "logfilter.warn", "logfilter.cmd"};

    /** 把一串键按当前语言翻出来。 */
    private static List<String> tr(String... keys) {
        List<String> out = new ArrayList<>(keys.length);
        for (String k : keys) out.add(Texts.t(k));
        return out;
    }

    // ---------- 变换（虚拟坐标 <-> 屏幕坐标） ----------
    private double scale = 1.0;
    private int ox, oy;

    // ---------- 控件 ----------
    private final List<Widget> chrome = new ArrayList<>();   // 标签条 + 底栏，切页时不动
    private final List<Widget> widgets = new ArrayList<>();  // 当前页内容
    private Widget focus;

    private Tab tab = Tab.GENERATE;

    // 生成页
    private Widget.TextArea previewTa;
    private boolean manualPreview = false;

    // 任务页
    private Widget.ScrollText taskStatus;

    // 清理页
    private Widget.ScrollText trimTa;

    // 热力图页
    private Widget.HeatCanvas canvas;
    private final List<String> dims = new ArrayList<>();
    private final List<String> dimNames = new ArrayList<>();
    private String heatDim = "minecraft:overworld";
    private int heatHalf = 128;
    private Widget.Label heatHalfLb;

    // 日志页
    private Widget.ScrollText logTa;
    private int logFilter = 0;

    // 配置页
    private Widget.ScrollText setsTa;
    private Widget.Label refreshLb;
    private Widget.Label heatRadiusLb;

    // 底栏
    private Widget.Slider scaleSlider;
    private Widget.Label scaleInfoLb;
    private Widget.Button playPauseBtn;
    private Widget.Label playPauseHint;
    private int pendingPercent = 100;

    // 提示条
    private String toastMsg = null;
    private long toastUntil = 0;

    private long lastFrameMs = System.currentTimeMillis();
    private float dt = 0.05f;
    private long lastAutoRefreshMs = 0;
    /** 面板刚打开时，用来问几次“到底有没有任务在跑”（前置会自己续跑上次的任务）。 */
    private int probeBudget = 0;
    private long nextProbeMs = 0;

    public PanelScreen() {
        super(Component.literal(Texts.t("panel.title")));
    }

    @Override
    protected void init() {
        pendingPercent = Settings.uiScalePercent;
        heatHalf = Settings.heatmapRadius;
        computeTransform();
        NetClient.requestHandshake();
        buildChrome();
        buildTab();
        // 前置会“续跑”上次没跑完的任务，刚进游戏时它已经在跑了；面板得主动问几次才知道
        probeBudget = 4;
        nextProbeMs = 0;
        lastAutoRefreshMs = 0;
        PrereqStatus.refreshTree();
    }

    @Override
    public boolean isPauseScreen() {
        return false;   // 后台正在生成时别把游戏暂停了
    }

    // ==================== 变换 ====================

    private void computeTransform() {
        double fit = Math.min((width - 8) / (double) VW, (height - 8) / (double) VH);
        fit = Math.max(0.35, fit);
        double want = Math.max(0.4, pendingPercent / 100.0);
        scale = Math.min(want, fit);
        ox = (int) Math.round((width - VW * scale) / 2.0);
        oy = (int) Math.round((height - VH * scale) / 2.0);
        // 控件开裁剪时要按这套变换换算，否则裁剪框会落错位置
        Widget.VIEW_OX = ox;
        Widget.VIEW_OY = oy;
        Widget.VIEW_SCALE = scale;
    }

    private boolean inside(double vx, double vy) {
        return vx >= 0 && vy >= 0 && vx < VW && vy < VH;
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        dt = Math.max(0.001f, Math.min(0.1f, (now - lastFrameMs) / 1000f));
        lastFrameMs = now;

        // 1.20.1 的 Screen#renderBackground(GuiGraphics) 在「世界里」画的就是
        // fillGradient(0, 0, width, height, -1072689136, -804253680) 这一层暗色蒙版。
        // 1.21.1 的同名方法换成了「模糊背景 + 菜单底图」，会把世界整片盖住、观感差异很大；
        // renderTransparentBackground 的字节码恰好就是上面那层 fillGradient，所以改调它。
        this.renderTransparentBackground(g);
        computeTransform();
        double vx = (mouseX - ox) / scale;
        double vy = (mouseY - oy) / scale;

        updateDynamic();
        maybeAutoRefresh(now);

        g.pose().pushPose();
        g.pose().translate(ox, oy, 0);
        g.pose().scale((float) scale, (float) scale, 1f);

        Theme.drawPanel(g, 0, 0, VW, VH, now);
        Theme.drawTitlePlate(g, VW / 2, 0, Texts.t(TAB_TITLE_KEYS[tab.ordinal()]));
        drawStatusStrip(g);

        for (Widget w : chrome) if (w.visible) w.render(g, (int) vx, (int) vy, dt);
        for (Widget w : widgets) if (w.visible) w.render(g, (int) vx, (int) vy, dt);

        drawPanelExtras(g, now);

        if (Settings.particles) {
            GuiParticles.tickRender(g, 0, 0, VW, VH, PanelState.task == PanelState.Task.RUNNING, now);
        }
        drawToast(g, now);

        g.pose().popPose();
    }

    /**
     * 面板开着时的进度轮询：
     *  - 任务在跑：按设置里的间隔问进度；
     *  - 本地以为没任务：刚打开面板时补问几次，看看前置是不是自己续跑了任务。
     */
    private void maybeAutoRefresh(long now) {
        int sec = Settings.progressRefreshSec;
        if (PanelState.task == PanelState.Task.RUNNING || PanelState.task == PanelState.Task.PAUSED) {
            if (sec <= 0 || !PrereqStatus.prereqOk()) return;
            if (now - lastAutoRefreshMs < sec * 1000L) return;
            lastAutoRefreshMs = now;
            CommandRunner.send(cs() + " progress");
            return;
        }
        if (PanelState.task != PanelState.Task.NONE) { probeBudget = 0; return; }
        if (probeBudget <= 0) return;
        // 服务端明确回了“未知命令”就不用再问了；否则即使前置检测一时不准，也亲自问一次
        if (PrereqStatus.serverRejected) return;
        if (now < nextProbeMs) return;
        probeBudget--;
        nextProbeMs = now + 2000;
        CommandRunner.send(cs() + " progress");
    }

    /** 标签条下方的一行前置状态：就绪绿色、缺失红色、版本不符橙色。 */
    private void drawStatusStrip(GuiGraphics g) {
        Font f = Minecraft.getInstance().font;
        String s;
        int color;
        if (PrereqStatus.localPresent()) {
            String name = PrereqStatus.localModName == null ? "ChunkSmith" : PrereqStatus.localModName;
            String ver = PrereqStatus.localVersion == null ? "" : " v" + PrereqStatus.localVersion;
            s = Texts.t("status.ready", name, ver, permText());
            color = Theme.OK;
        } else if (PrereqStatus.commandTreeSeen) {
            s = Texts.t("status.serverOnly", cs(), permText());
            color = Theme.OK;
        } else {
            s = Texts.t("status.missing");
            color = Theme.DANGER;
        }
        if (PrereqStatus.versionMismatch()) {
            s = Texts.t("status.versionMismatch",
                    PrereqStatus.localVersion, PrereqStatus.serverVersion, s);
            color = Theme.WARN;
        }
        g.drawString(f, s, 24, 62, color, true);
    }

    /** 底栏右侧的实时信息 + 缩放说明。 */
    private void drawPanelExtras(GuiGraphics g, long now) {
        Font f = Minecraft.getInstance().font;
        Minecraft mc = Minecraft.getInstance();
        int by = VH - 30;

        double fit = Math.max(0.35, Math.min((width - 8) / (double) VW, (height - 8) / (double) VH));
        double real = Math.min(Math.max(0.4, pendingPercent / 100.0), fit);
        if (scaleInfoLb != null) {
            scaleInfoLb.text = Texts.t("panel.scale.info", Math.round(real * 100),
                    Math.round(VW * real), Math.round(VH * real));
        }

        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024L * 1024L);
        g.drawString(f, Texts.t("panel.fps", mc.getFps()), 368, by + 4, Theme.INK_SOFT, false);
        g.drawString(f, Texts.t("panel.memory", usedMb), 416, by + 4, Theme.INK_SOFT, false);
    }

    private void drawToast(GuiGraphics g, long now) {
        if (toastMsg == null) return;
        if (now > toastUntil) { toastMsg = null; return; }
        Font f = Minecraft.getInstance().font;
        int alpha = (int) (210 * Math.min(1.0, (toastUntil - now) / 600.0));
        alpha = Math.max(50, alpha);
        int bg = (alpha << 24) | 0x2E1C0E;
        int w = f.width(toastMsg) + 18;
        int x = (VW - w) / 2, y = VH - 58;
        g.fill(x, y, x + w, y + 18, bg);
        Theme.stroke(g, x, y, x + w, y + 18, Theme.GOLD);
        g.drawString(f, toastMsg, x + 9, y + 5, 0xFFF7ECD2, false);
    }

    private void toast(String msg) {
        toastMsg = msg;
        toastUntil = System.currentTimeMillis() + 2400;
    }

    // ==================== 事件路由 ====================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        computeTransform();
        double vx = (mx - ox) / scale, vy = (my - oy) / scale;
        if (!inside(vx, vy)) return false;
        boolean hit = routeClick(vx, vy, button);
        if (!hit) clearWidgetFocus();
        return hit;
    }

    private boolean routeClick(double vx, double vy, int button) {
        int n = widgets.size();
        for (int i = n - 1; i >= 0; i--) {
            Widget w = widgets.get(i);
            if (w.active && w.visible && w.mouseClicked(vx, vy, button)) { focusOn(w); return true; }
        }
        n = chrome.size();
        for (int i = n - 1; i >= 0; i--) {
            Widget w = chrome.get(i);
            if (w.active && w.visible && w.mouseClicked(vx, vy, button)) { focusOn(w); return true; }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        computeTransform();
        double vx = (mx - ox) / scale, vy = (my - oy) / scale;
        for (Widget w : widgets) if (w.mouseReleased(vx, vy, button)) return true;
        for (Widget w : chrome) if (w.mouseReleased(vx, vy, button)) return true;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        computeTransform();
        double vx = (mx - ox) / scale, vy = (my - oy) / scale;
        for (Widget w : widgets) if (w.mouseDragged(vx, vy, button, dx / scale, dy / scale)) return true;
        for (Widget w : chrome) if (w.mouseDragged(vx, vy, button, dx / scale, dy / scale)) return true;
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        computeTransform();
        double vx = (mx - ox) / scale, vy = (my - oy) / scale;
        // 1.20.5 起 GuiEventListener#mouseScrolled 增加了横向分量 scrollX；
        // 面板控件只吃纵向分量，所以这里把 scrollY 当作原来的 amount 传下去。
        for (Widget w : widgets) if (w.active && w.visible && w.mouseScrolled(vx, vy, scrollY)) return true;
        for (Widget w : chrome) if (w.active && w.visible && w.mouseScrolled(vx, vy, scrollY)) return true;
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (focus != null) {
            if (focus.keyPressed(key, scan, mods)) return true;
            if (key == GLFW.GLFW_KEY_ESCAPE) { clearWidgetFocus(); return true; }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char ch, int mods) {
        if (focus != null && focus.charTyped(ch, mods)) return true;
        return super.charTyped(ch, mods);
    }

    private void focusOn(Widget w) {
        Widget target = (w != null && w.wantsFocus()) ? w : null;
        if (focus == target) {
            if (target != null) target.setFocus(true);
            return;
        }
        if (focus != null) focus.setFocus(false);
        focus = target;
        if (focus != null) focus.setFocus(true);
    }

    /**
     * 清掉当前聚焦的控件。
     *
     * <p>1.20.1 里这个方法叫 {@code clearFocus()}（private）；1.21.1 的 {@code Screen}
     * 新增了一个同名的 {@code public void clearFocus()}，private 无法覆盖 public，
     * 编译直接报「无法覆盖 Screen 中的 clearFocus()；正在尝试分配更低的访问权限」。
     * 两者语义完全无关（一个管我们自己的输入焦点，一个是原版的 ComponentPath 焦点机制），
     * 所以这里改名为 {@code clearWidgetFocus()}。</p>
     */
    private void clearWidgetFocus() {
        if (focus != null) focus.setFocus(false);
        focus = null;
    }

    // ==================== 底栏 + 标签条 ====================

    private void buildChrome() {
        chrome.clear();

        int tabW = 84, tabH = 20, gap = 4;
        int total = TABS.length * tabW + (TABS.length - 1) * gap;
        int tx = (VW - total) / 2;
        for (int i = 0; i < TABS.length; i++) {
            final Tab t = TABS[i];
            chrome.add(new TabButton(tx + i * (tabW + gap), 40, tabW, tabH,
                    Texts.t(TAB_LABEL_KEYS[i]), t, () -> switchTab(t)));
        }

        int by = VH - 30;
        chrome.add(new Widget.Label(20, by + 4, Texts.t("panel.uiScale"), false));

        // 滑条右边的数字输入框已按要求删除，比例直接显示在右侧信息文字里
        scaleSlider = new Widget.Slider(62, by, 132, 16, 50, 200, pendingPercent, 5);
        scaleSlider.onChange = v -> pendingPercent = v;
        chrome.add(scaleSlider);

        Widget.Button apply = new Widget.Button(200, by, 76, 16, Texts.t("btn.applyUiScale"));
        apply.onPress = () -> {
            Settings.uiScalePercent = pendingPercent;
            Settings.save();
            toast(Texts.t("toast.uiScaleSaved", pendingPercent));
        };
        chrome.add(apply);

        scaleInfoLb = new Widget.Label(282, by + 4, "", false);
        chrome.add(scaleInfoLb);

        // 右下角四个方形图标键：上一页 / 开始暂停 / 刷新 / 下一页
        int bx = 528;
        Widget.Button prev = new Widget.Button(bx, by, 24, 16, "");
        prev.iconPath = Icons.PREV;
        prev.onPress = () -> cycleTab(-1);
        chrome.add(prev);
        chrome.add(iconHint(bx, Texts.t("hint.prev")));

        playPauseBtn = new Widget.Button(bx + 28, by, 24, 16, "");
        playPauseBtn.iconPath = PanelState.task == PanelState.Task.RUNNING ? Icons.PAUSE : Icons.PLAY;
        playPauseBtn.onPress = () -> {
            boolean running = PanelState.task == PanelState.Task.RUNNING;
            CommandRunner.send(cs() + (running ? " pause" : " continue"));
            playPauseBtn.iconPath = running ? Icons.PLAY : Icons.PAUSE;
        };
        chrome.add(playPauseBtn);
        playPauseHint = iconHint(bx + 28, Texts.t("hint.pause"));
        chrome.add(playPauseHint);

        Widget.Button refresh = new Widget.Button(bx + 56, by, 24, 16, "");
        refresh.iconPath = Icons.REFRESH;
        refresh.onPress = () -> { PrereqStatus.refreshTree(); toast(Texts.t("toast.statusRefreshed")); };
        chrome.add(refresh);
        chrome.add(iconHint(bx + 56, Texts.t("hint.refresh")));

        Widget.Button next = new Widget.Button(bx + 84, by, 24, 16, "");
        next.iconPath = Icons.NEXT;
        next.onPress = () -> cycleTab(1);
        chrome.add(next);
        chrome.add(iconHint(bx + 84, Texts.t("hint.next")));
    }

    /** 图标键下方的一行中文说明（居中，画在底部木框上）。 */
    private Widget.Label iconHint(int btnX, String text) {
        Font f = Minecraft.getInstance().font;
        Widget.Label lb = new Widget.Label(btnX + (24 - f.width(text)) / 2, VH - 12, text, false);
        lb.colorOverride = 0xFFF0D9AE;
        return lb;
    }

    private void switchTab(Tab t) {
        if (tab == t) return;
        tab = t;
        buildTab();
        if (t == Tab.TASK && PanelState.task != PanelState.Task.RUNNING) {
            // 用户主动来看任务页，立刻核一次服务端状态
            probeBudget = Math.max(probeBudget, 2);
            nextProbeMs = 0;
        }
    }

    private void cycleTab(int dir) {
        int i = (tab.ordinal() + dir + TABS.length) % TABS.length;
        switchTab(TABS[i]);
    }

    /** 标签键：选中的那条画成按下态并带金色下划线。 */
    private final class TabButton extends Widget {
        private final String text;
        private final Tab target;
        private final Runnable onPress;

        TabButton(int x, int y, int w, int h, String text, Tab target, Runnable onPress) {
            super(x, y, w, h);
            this.text = text;
            this.target = target;
            this.onPress = onPress;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float dt) {
            boolean sel = PanelScreen.this.tab == target;
            Theme.buttonFace(g, x, y, w, h, contains(mx, my), sel, true, false);
            Font f = Minecraft.getInstance().font;
            g.drawString(f, text, x + (w - f.width(text)) / 2, y + (h - 8) / 2, sel ? Theme.INK : Theme.LABEL, true);
            if (sel) g.fill(x + 3, y + h - 2, x + w - 3, y + h - 1, Theme.GOLD);
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button == 0 && contains(mx, my)) {
                Widget.Button.playClick();
                onPress.run();
                return true;
            }
            return false;
        }
    }

    // ==================== 各标签页内容 ====================

    private void buildTab() {
        widgets.clear();
        focus = null;
        previewTa = null;
        taskStatus = null;
        trimTa = null;
        canvas = null;
        heatHalfLb = null;
        logTa = null;
        setsTa = null;
        refreshLb = null;
        heatRadiusLb = null;
        switch (tab) {
            case GENERATE -> buildGenerate();
            case TASK -> buildTask();
            case TRIM -> buildTrim();
            case HEAT -> buildHeat();
            case LOG -> buildLog();
            case CONFIG -> buildConfig();
        }
    }

    // ---- 生成页 ----
    private void buildGenerate() {
        manualPreview = false;
        int xL = 28, wL = 300;
        int y = 76;

        widgets.add(new Widget.Label(xL, y, Texts.t("gen.world"), false));
        Widget.Field wf = Widget.Field.str(xL, y + 11, wL, 16, PanelState.world);
        wf.onChanged = () -> { PanelState.world = wf.value; refreshPreview(); };
        widgets.add(wf);

        y += 34;
        widgets.add(new Widget.Label(xL, y, Texts.t("gen.shape"), false));
        Widget.Cycle shape = new Widget.Cycle(xL, y + 11, wL, 16, "",
                SHAPES, tr(SHAPE_NAME_KEYS), Math.max(0, SHAPES.indexOf(PanelState.shape)));
        shape.onChange = i -> { PanelState.shape = shape.value(); refreshPreview(); };
        widgets.add(shape);

        y += 34;
        widgets.add(new Widget.Label(xL, y, Texts.t("gen.center"), false));
        Widget.Cycle center = new Widget.Cycle(xL, y + 11, wL, 16, "",
                CENTERS, tr(CENTER_NAME_KEYS), Math.max(0, CENTERS.indexOf(PanelState.centerMode)));
        center.onChange = i -> { PanelState.centerMode = center.value(); refreshPreview(); };
        widgets.add(center);

        y += 34;
        widgets.add(new Widget.Label(xL, y, Texts.t("gen.coords"), false));
        Widget.Field xf = Widget.Field.num(xL, y + 11, 146, 16, PanelState.centerX, -30000000, 30000000);
        xf.onChanged = () -> { PanelState.centerX = (int) xf.longValue(); refreshPreview(); };
        widgets.add(xf);
        Widget.Field zf = Widget.Field.num(xL + 154, y + 11, 146, 16, PanelState.centerZ, -30000000, 30000000);
        zf.onChanged = () -> { PanelState.centerZ = (int) zf.longValue(); refreshPreview(); };
        widgets.add(zf);

        y += 34;
        widgets.add(new Widget.Label(xL, y, Texts.t("gen.radius"), false));
        Widget.Field rf = Widget.Field.num(xL, y + 11, 146, 16, PanelState.radius, 1, 1000000);
        rf.onChanged = () -> { PanelState.radius = (int) rf.longValue(); refreshPreview(); };
        widgets.add(rf);
        Widget.Field r2 = Widget.Field.num(xL + 154, y + 11, 146, 16, PanelState.radius2, 0, 1000000);
        r2.onChanged = () -> { PanelState.radius2 = (int) r2.longValue(); refreshPreview(); };
        widgets.add(r2);

        y += 34;
        Widget.Toggle ignore = new Widget.Toggle(xL, y, 190, 16, Texts.t("common.ignorePrereq"), PanelState.forceIgnorePrereq);
        ignore.onChange = b -> PanelState.forceIgnorePrereq = b;
        widgets.add(ignore);
        Widget.Toggle particles = new Widget.Toggle(xL + 198, y, 102, 16, Texts.t("common.particles"), Settings.particles);
        particles.onChange = b -> { Settings.particles = b; Settings.save(); };
        widgets.add(particles);

        int xR = 344, wR = VW - 28 - xR;
        widgets.add(new Widget.Label(xR, 76, Texts.t("gen.preview"), false));
        previewTa = new Widget.TextArea(xR, 88, wR, 190);
        previewTa.onEdited = ls -> manualPreview = true;
        refreshPreview();
        widgets.add(previewTa);

        Widget.Button start = new Widget.Button(xR, 286, wR, 22, Texts.t("btn.start"));
        start.iconPath = Icons.PLAY;
        start.onPress = this::doStart;
        widgets.add(start);

        int half = (wR - 6) / 2;
        Widget.Button copy = new Widget.Button(xR, 314, half, 18, Texts.t("btn.copyCmd"));
        copy.iconPath = Icons.COPY;
        copy.onPress = () -> {
            CommandRunner.copy(previewTa == null ? CommandBuilder.buildPreview() : previewTa.join());
            toast(Texts.t("toast.cmdCopied"));
        };
        widgets.add(copy);
        Widget.Button reset = new Widget.Button(xR + half + 6, 314, half, 18, Texts.t("btn.resetForm"));
        reset.stone = true;
        reset.onPress = () -> {
            PanelState.world = "world";
            PanelState.shape = "square";
            PanelState.centerMode = "here";
            PanelState.centerX = 0;
            PanelState.centerZ = 0;
            PanelState.radius = 500;
            PanelState.radius2 = 0;
            switchTab(Tab.TASK);
            switchTab(Tab.GENERATE);
            toast(Texts.t("toast.formReset"));
        };
        widgets.add(reset);
    }

    /** 预览是自动跟随表单的；用户手动改过就不覆盖。 */
    private void refreshPreview() {
        if (previewTa == null || manualPreview) return;
        previewTa.setLines(Arrays.asList(CommandBuilder.buildPreview().split("\n")));
    }

    private void doStart() {
        List<String> problems = CommandBuilder.validate();
        if (!problems.isEmpty()) {
            toast(Texts.t("toast.validateFailed", problems.get(0)));
            PanelState.log(PanelState.Level.WARN, Texts.t("log.formInvalid", String.join("；", problems)));
            return;
        }
        if (!PrereqStatus.prereqOk() && !PanelState.forceIgnorePrereq) {
            toast(Texts.t("toast.noPrereq"));
            return;
        }
        if (!PrereqStatus.isOperator() && !PanelState.forceIgnorePrereq && !Minecraft.getInstance().isSingleplayer()) {
            toast(Texts.t("toast.noPerm"));
            return;
        }
        List<String> lines = CommandBuilder.parsePreviewLines(
                previewTa == null ? CommandBuilder.buildPreview() : previewTa.join());
        if (lines.isEmpty()) {
            toast(Texts.t("toast.previewEmpty"));
            return;
        }
        CommandRunner.sendSequence(lines);
        toast(Texts.t("toast.sent", lines.size()));
    }

    // ---- 任务页 ----
    private void buildTask() {
        int x0 = 28, cw = 372;
        int y = 76;
        // 总进度：没有任务时画成空条（不跑流水灯），只有任务在跑但数字还没来时才是“未知”
        widgets.add(new Widget.RowCard(x0, y, cw, 46, Texts.t("task.card.total"), Icons.GRID,
                () -> PanelState.percent < 0
                        ? (PanelState.task == PanelState.Task.RUNNING ? -1 : 0)
                        : PanelState.percent / 100.0,
                () -> PanelState.percent < 0
                        ? Texts.t("value.unknown")
                        : Texts.t("task.percent.value", String.format("%.0f", PanelState.percent))));
        y += 54;
        widgets.add(new Widget.RowCard(x0, y, cw, 46, Texts.t("task.card.processed"), Icons.CHART,
                () -> (PanelState.processed >= 0 && PanelState.total > 0)
                        ? PanelState.processed / (double) PanelState.total
                        : (PanelState.task == PanelState.Task.RUNNING ? -1 : 0),
                () -> Texts.t("task.processed.value",
                        Theme.fmtCount(PanelState.processed), Theme.fmtCount(PanelState.total))));
        y += 54;
        widgets.add(new Widget.RowCard(x0, y, cw, 46, Texts.t("task.card.rate"), Icons.PLAY,
                () -> PanelState.rate < 0 ? 0 : Math.min(1.0, PanelState.rate / 50.0),
                () -> PanelState.rate < 0
                        ? Texts.t("value.unknown")
                        : Texts.t("task.rate.value", String.format("%.1f", PanelState.rate))));

        widgets.add(new Widget.Label(x0, 240, Texts.t("task.recent"), false));
        taskStatus = new Widget.ScrollText(x0, 252, cw, 110);
        widgets.add(taskStatus);

        int bx = 416, bw = 216;
        int by = 76, pitch = 30;
        Widget.Button forceStart = new Widget.Button(bx, by, bw, 24, Texts.t("btn.start"));
        forceStart.iconPath = Icons.PLAY;
        forceStart.onPress = this::doStart;
        widgets.add(forceStart);
        Widget.Button pause = new Widget.Button(bx, by += pitch, bw, 24, Texts.t("btn.pause"));
        pause.iconPath = Icons.PAUSE;
        pause.onPress = () -> { CommandRunner.send(cs() + " pause"); toast(Texts.t("toast.paused")); };
        widgets.add(pause);
        Widget.Button resume = new Widget.Button(bx, by += pitch, bw, 24, Texts.t("btn.resume"));
        resume.iconPath = Icons.PLAY;
        resume.onPress = () -> { CommandRunner.send(cs() + " continue"); toast(Texts.t("toast.resumed")); };
        widgets.add(resume);
        Widget.Button cancel = new Widget.Button(bx, by += pitch, bw, 24, Texts.t("btn.cancel"));
        cancel.iconPath = Icons.STOP;
        cancel.textColor = Theme.DANGER;
        cancel.onPress = () -> {
            CommandRunner.send(cs() + " cancel");
            toast(Texts.t("toast.cancelSent"));
        };
        widgets.add(cancel);
        // 服务端收到 /cs cancel 后仍要 /cs confirm 才算数，所以单独给一个确认键
        Widget.Button confirmCancel = new Widget.Button(bx, by += pitch, bw, 24, Texts.t("btn.confirmCancel"));
        confirmCancel.iconPath = Icons.STOP;
        confirmCancel.textColor = Theme.DANGER;
        confirmCancel.onPress = () -> {
            CommandRunner.send(cs() + " confirm");
            toast(Texts.t("toast.cancelConfirmed"));
        };
        widgets.add(confirmCancel);
        Widget.Button prog = new Widget.Button(bx, by += pitch, bw, 24, Texts.t("btn.refreshProgress"));
        prog.iconPath = Icons.REFRESH;
        prog.onPress = () -> { CommandRunner.send(cs() + " progress"); toast(Texts.t("toast.progressRequested")); };
        widgets.add(prog);
        Widget.Button clear = new Widget.Button(bx, by += pitch, bw, 24, Texts.t("btn.markDone"));
        clear.stone = true;
        clear.onPress = () -> {
            PanelState.clearTask();
            toast(Texts.t("toast.localCleared"));
        };
        widgets.add(clear);

        // 原来这里的“总进度条 + 百分比”和上面的总进度卡片重复，已按要求删掉，改放两条说明。
        Widget.Label tip1 = new Widget.Label(bx, 296, Texts.t("task.tip1"), false);
        tip1.colorOverride = Theme.INK_SOFT;
        widgets.add(tip1);
        Widget.Label tip2 = new Widget.Label(bx, 310, Texts.t("task.tip2"), false);
        tip2.colorOverride = Theme.INK_SOFT;
        widgets.add(tip2);
        Widget.Label tip3 = new Widget.Label(bx, 322, Texts.t("task.tip3"), false);
        tip3.colorOverride = Theme.INK_SOFT;
        widgets.add(tip3);
    }

    // ---- 清理页 ----
    private void buildTrim() {
        int x0 = 28, w0 = 300;
        Widget.Label warn = new Widget.Label(x0, 76, Texts.t("trim.warn"), false);
        warn.colorOverride = Theme.DANGER;
        widgets.add(warn);
        widgets.add(new Widget.Label(x0, 90, Texts.t("trim.warn2"), false));

        int y = 108;
        widgets.add(new Widget.Label(x0, y, Texts.t("trim.world"), false));
        Widget.Field wf = Widget.Field.str(x0, y + 11, w0, 16, PanelState.world);
        wf.onChanged = () -> { PanelState.world = wf.value; rebuildTrimPreview(); };
        widgets.add(wf);

        y += 30;
        widgets.add(new Widget.Label(x0, y, Texts.t("trim.shape"), false));
        Widget.Cycle shape = new Widget.Cycle(x0, y + 11, w0, 16, "",
                SHAPES, tr(SHAPE_NAME_KEYS), Math.max(0, SHAPES.indexOf(PanelState.shape)));
        shape.onChange = i -> { PanelState.shape = shape.value(); rebuildTrimPreview(); };
        widgets.add(shape);

        y += 30;
        widgets.add(new Widget.Label(x0, y, Texts.t("trim.center"), false));
        Widget.Cycle center = new Widget.Cycle(x0, y + 11, w0, 16, "",
                CENTERS, tr(CENTER_NAME_KEYS), Math.max(0, CENTERS.indexOf(PanelState.centerMode)));
        center.onChange = i -> { PanelState.centerMode = center.value(); rebuildTrimPreview(); };
        widgets.add(center);

        y += 30;
        widgets.add(new Widget.Label(x0, y, Texts.t("trim.coords"), false));
        Widget.Field xf = Widget.Field.num(x0, y + 11, 146, 16, PanelState.centerX, -30000000, 30000000);
        xf.onChanged = () -> { PanelState.centerX = (int) xf.longValue(); rebuildTrimPreview(); };
        widgets.add(xf);
        Widget.Field zf = Widget.Field.num(x0 + 154, y + 11, 146, 16, PanelState.centerZ, -30000000, 30000000);
        zf.onChanged = () -> { PanelState.centerZ = (int) zf.longValue(); rebuildTrimPreview(); };
        widgets.add(zf);

        y += 30;
        widgets.add(new Widget.Label(x0, y, Texts.t("trim.radius"), false));
        Widget.Field rf = Widget.Field.num(x0, y + 11, w0, 16, PanelState.radius, 1, 1000000);
        rf.onChanged = () -> { PanelState.radius = (int) rf.longValue(); rebuildTrimPreview(); };
        widgets.add(rf);

        y += 32;
        Widget.Toggle confirm = new Widget.Toggle(x0, y, 300, 16, Texts.t("trim.confirm"), PanelState.trimConfirmed);
        widgets.add(confirm);

        y += 24;
        Widget.Button run = new Widget.Button(x0, y, 300, 22, Texts.t("btn.trimRun"));
        run.iconPath = Icons.TRIM;
        run.textColor = Theme.DANGER;
        run.active = PanelState.trimConfirmed;
        confirm.onChange = b -> { PanelState.trimConfirmed = b; run.active = b; };
        run.onPress = () -> {
            if (!PanelState.trimConfirmed) { toast(Texts.t("toast.trimNeedConfirm")); return; }
            if (!PrereqStatus.prereqOk() && !PanelState.forceIgnorePrereq) {
                toast(Texts.t("toast.noPrereqShort"));
                return;
            }
            CommandRunner.sendSequence(CommandBuilder.buildTrim());
            toast(Texts.t("toast.trimSent"));
        };
        widgets.add(run);

        int xR = 344, wR = VW - 28 - xR;
        widgets.add(new Widget.Label(xR, 76, Texts.t("trim.cmds"), false));
        Widget.Label seqNote = new Widget.Label(xR, 90, Texts.t("trim.cmdsNote"), false);
        seqNote.colorOverride = Theme.INK_SOFT;
        widgets.add(seqNote);
        trimTa = new Widget.ScrollText(xR, 104, wR, 148);
        widgets.add(trimTa);
        Widget.Button copy = new Widget.Button(xR, 258, wR, 18, Texts.t("btn.copyTrim"));
        copy.iconPath = Icons.COPY;
        copy.onPress = () -> {
            CommandRunner.copy(String.join("\n", CommandBuilder.buildTrim()));
            toast(Texts.t("toast.copied"));
        };
        widgets.add(copy);
        rebuildTrimPreview();
    }

    private void rebuildTrimPreview() {
        if (trimTa == null) return;
        List<Widget.Line> out = new ArrayList<>();
        for (String s : CommandBuilder.buildTrim()) out.add(new Widget.Line("/ " + s, 0xFFE8D9AE));
        out.add(new Widget.Line(Texts.t("trim.previewNote",
                PanelState.shape, PanelState.centerMode, PanelState.radius), Theme.BTN_TEXT_OFF));
        trimTa.setLines(out);
    }

    // ---- 热力图页 ----
    private void buildHeat() {
        heatHalf = Settings.heatmapRadius;
        canvas = new Widget.HeatCanvas(28, 76, 400, 286);
        canvas.half = heatHalf;
        int[] c = heatCenterChunks();
        canvas.scx = c[0];
        canvas.scz = c[1];
        widgets.add(canvas);

        rebuildDims();

        int xR = 444, wR = 188;
        widgets.add(new Widget.Label(xR, 76, Texts.t("heat.dim"), false));
        Widget.Cycle dim = new Widget.Cycle(xR, 87, wR, 16, "",
                new ArrayList<>(dims), new ArrayList<>(dimNames), Math.max(0, dims.indexOf(heatDim)));
        dim.onChange = i -> { heatDim = dim.value(); };
        widgets.add(dim);

        widgets.add(new Widget.Label(xR, 116, Texts.t("heat.half"), false));
        Widget.Slider half = new Widget.Slider(xR, 127, wR, 16, 16, 256, heatHalf, 16);
        half.onChange = v -> {
            heatHalf = v;
            if (canvas != null) canvas.half = v;
        };
        widgets.add(half);
        heatHalfLb = new Widget.Label(xR, 148, "", false);
        widgets.add(heatHalfLb);

        Widget.Button pull = new Widget.Button(xR, 172, wR, 22, Texts.t("btn.pullHeat"));
        pull.iconPath = Icons.GRID;
        pull.onPress = this::pullHeatmap;
        widgets.add(pull);

        Widget.Button clear = new Widget.Button(xR, 200, wR, 18, Texts.t("btn.clearDisplay"));
        clear.stone = true;
        clear.onPress = () -> { PanelState.heat = null; toast(Texts.t("toast.heatCleared")); };
        widgets.add(clear);

        widgets.add(new Widget.Label(xR, 230, Texts.t("heat.legend.green"), false));
        Widget.Label l2 = new Widget.Label(xR, 244, Texts.t("heat.legend.gray"), false);
        l2.colorOverride = Theme.INK_SOFT;
        widgets.add(l2);
        Widget.Label l3 = new Widget.Label(xR, 258, Texts.t("heat.legend.gold"), false);
        l3.colorOverride = Theme.WARN;
        widgets.add(l3);
        Widget.Label l4 = new Widget.Label(xR, 280, Texts.t("heat.note1"), false);
        Widget.Label l5 = new Widget.Label(xR, 292, Texts.t("heat.note2"), false);
        widgets.add(l4);
        widgets.add(l5);
    }

    private void rebuildDims() {
        dims.clear();
        dimNames.clear();
        if (Net.serverWorlds != null && !Net.serverWorlds.isEmpty()) {
            dims.addAll(Net.serverWorlds);
        } else {
            dims.add("minecraft:overworld");
            dims.add("minecraft:the_nether");
            dims.add("minecraft:the_end");
        }
        for (String d : dims) dimNames.add(d.replace("minecraft:", ""));
        if (!dims.contains(heatDim)) heatDim = dims.get(0);
    }

    private int[] heatCenterChunks() {
        Minecraft mc = Minecraft.getInstance();
        if (PanelState.centerMode.equals("coords")) {
            return new int[]{PanelState.centerX >> 4, PanelState.centerZ >> 4};
        }
        if (mc.player != null) {
            return new int[]{mc.player.blockPosition().getX() >> 4, mc.player.blockPosition().getZ() >> 4};
        }
        return new int[]{0, 0};
    }

    private void pullHeatmap() {
        if (!PrereqStatus.prereqOk() && !PrereqStatus.localPresent()) {
            // 热力图是本模组自己读存档，不一定需要前置，这里只提醒一句
            PanelState.log(PanelState.Level.WARN, Texts.t("log.heatPrereqMissing"));
        }
        int[] c = heatCenterChunks();
        if (canvas != null) { canvas.scx = c[0]; canvas.scz = c[1]; canvas.half = heatHalf; }
        NetClient.requestHeatmap(heatDim, c[0], c[1], heatHalf);
        toast(Texts.t("toast.heatScanning", heatDim, heatHalf));
    }

    // ---- 日志页 ----
    private void buildLog() {
        widgets.add(new Widget.Label(28, 78, Texts.t("log.filter"), false));
        Widget.Cycle filter = new Widget.Cycle(64, 74, 150, 18, "",
                tr(LOG_FILTER_KEYS), logFilter);
        filter.onChange = i -> logFilter = i;
        widgets.add(filter);

        Widget.Button copyAll = new Widget.Button(300, 74, 78, 18, Texts.t("btn.copyAll"));
        copyAll.onPress = () -> {
            CommandRunner.copy(PanelState.logDump());
            toast(Texts.t("toast.logCopied"));
        };
        widgets.add(copyAll);
        Widget.Button copyErr = new Widget.Button(384, 74, 78, 18, Texts.t("btn.copyErrors"));
        copyErr.onPress = () -> {
            String s = PanelState.logErrorDump();
            CommandRunner.copy(s.isBlank() ? Texts.t("log.noErrors") : s);
            toast(Texts.t("toast.errorsCopied"));
        };
        widgets.add(copyErr);
        Widget.Button export = new Widget.Button(468, 74, 78, 18, Texts.t("btn.exportFile"));
        export.onPress = () -> exportText("log-" + stamp() + ".txt", PanelState.logDump());
        widgets.add(export);
        Widget.Button clear = new Widget.Button(552, 74, 80, 18, Texts.t("btn.clear"));
        clear.stone = true;
        clear.onPress = () -> { PanelState.clearLog(); toast(Texts.t("toast.logCleared")); };
        widgets.add(clear);

        logTa = new Widget.ScrollText(28, 100, 604, 262);
        widgets.add(logTa);
    }

    private List<Widget.Line> logLines() {
        List<Widget.Line> out = new ArrayList<>();
        for (PanelState.Entry e : PanelState.logSnapshot()) {
            if (logFilter == 1 && e.level != PanelState.Level.WARN && e.level != PanelState.Level.ERROR) continue;
            if (logFilter == 2 && e.level != PanelState.Level.CMD) continue;
            out.add(new Widget.Line(e.text, levelColor(e.level)));
        }
        if (out.isEmpty()) out.add(new Widget.Line(Texts.t("log.empty"), Theme.BTN_TEXT_OFF));
        return out;
    }

    private static int levelColor(PanelState.Level lv) {
        return switch (lv) {
            case CMD -> Theme.GOLD;
            case WARN -> Theme.WARN;
            case ERROR -> 0xFFFF7A66;
            case OK -> Theme.GREEN_HI;
            default -> Theme.TEXT_LIGHT;
        };
    }

    // ---- 配置页 ----
    private void buildConfig() {
        int x0 = 28, w0 = 280;
        widgets.add(new Widget.Label(x0, 76, Texts.t("cfg.options"), true));

        Widget.Toggle particles = new Widget.Toggle(x0, 94, w0, 16, Texts.t("common.particles"), Settings.particles);
        particles.onChange = b -> Settings.particles = b;
        widgets.add(particles);
        Widget.Toggle sounds = new Widget.Toggle(x0, 116, w0, 16, Texts.t("common.sounds"), Settings.sounds);
        sounds.onChange = b -> Settings.sounds = b;
        widgets.add(sounds);
        Widget.Toggle deco = new Widget.Toggle(x0, 138, w0, 16, Texts.t("common.decorations"), Settings.decorations);
        deco.onChange = b -> Settings.decorations = b;
        widgets.add(deco);

        widgets.add(new Widget.Label(x0, 164, Texts.t("cfg.refresh"), false));
        Widget.Slider refresh = new Widget.Slider(x0, 176, 190, 16, 0, 60, Settings.progressRefreshSec, 1);
        refresh.onChange = v -> Settings.progressRefreshSec = v;
        widgets.add(refresh);
        refreshLb = new Widget.Label(x0 + 198, 180, "", false);
        widgets.add(refreshLb);

        widgets.add(new Widget.Label(x0, 202, Texts.t("cfg.heatRadius"), false));
        Widget.Slider heatR = new Widget.Slider(x0, 214, 190, 16, 16, 256, Settings.heatmapRadius, 16);
        heatR.onChange = v -> Settings.heatmapRadius = v;
        widgets.add(heatR);
        heatRadiusLb = new Widget.Label(x0 + 198, 218, "", false);
        widgets.add(heatRadiusLb);

        Widget.Button save = new Widget.Button(x0, 244, 132, 20, Texts.t("btn.saveSettings"));
        save.onPress = () -> { Settings.save(); toast(Texts.t("toast.settingsSaved")); };
        widgets.add(save);
        Widget.Button def = new Widget.Button(x0 + 148, 244, 132, 20, Texts.t("btn.restoreDefaults"));
        def.stone = true;
        def.onPress = () -> {
            Settings.particles = true;
            Settings.sounds = true;
            Settings.decorations = true;
            Settings.progressRefreshSec = 5;
            Settings.heatmapRadius = 128;
            Settings.save();
            switchTab(Tab.GENERATE);
            switchTab(Tab.CONFIG);
            toast(Texts.t("toast.defaultsRestored"));
        };
        widgets.add(def);

        Widget.Button open = new Widget.Button(x0, 272, 280, 20, Texts.t("btn.openFolder"));
        open.iconPath = Icons.FOLDER;
        open.onPress = () -> {
            try {
                Path d = ChunkSmithGuiPaths.ensurePanelDir();
                Util.getPlatform().openFile(d.toFile());
            } catch (Throwable t) {
                toast(Texts.t("toast.openFolderFailed", t.getMessage()));
            }
        };
        widgets.add(open);

        // ---- 关于：作者与开源地址 ----
        widgets.add(new Widget.Label(28, 336, Texts.t("cfg.author"), false));
        Widget.Button repo = new Widget.Button(150, 330, 158, 18, Texts.t("btn.openRepo"));
        repo.stone = true;
        repo.onPress = () -> {
            try {
                Util.getPlatform().openUri("https://github.com/BarryAlen777/Chunksmith_modern_gui");
            } catch (Throwable t) {
                toast(Texts.t("toast.openLinkFailed", t.getMessage()));
            }
        };
        widgets.add(repo);

        int xR = 340, wR = VW - 28 - xR;
        widgets.add(new Widget.Label(xR, 76, Texts.t("cfg.sets"), true));
        setsTa = new Widget.ScrollText(xR, 94, wR, 176);
        widgets.add(setsTa);

        Widget.Button rollback = new Widget.Button(xR, 278, 138, 18, Texts.t("btn.rollback"));
        rollback.iconPath = Icons.ROLLBACK;
        rollback.onPress = () -> {
            String[] r = PanelState.popRollback();
            if (r == null) { toast(Texts.t("toast.nothingToRollback")); return; }
            CommandRunner.send(cs() + " set " + r[0] + " " + r[1]);
            toast(Texts.t("toast.rolledBack", r[0], r[1]));
        };
        widgets.add(rollback);
        Widget.Button export = new Widget.Button(xR + 146, 278, wR - 146, 18, Texts.t("btn.exportBackup"));
        export.onPress = () -> exportText("sets-" + stamp() + ".txt", PanelState.setsDump());
        widgets.add(export);
        Widget.Button clear = new Widget.Button(xR, 302, wR, 18, Texts.t("btn.clearBackup"));
        clear.stone = true;
        clear.onPress = () -> { PanelState.clearSets(); toast(Texts.t("toast.backupCleared")); };
        widgets.add(clear);
    }

    // ==================== 动态内容刷新 ====================

    private void updateDynamic() {
        if (playPauseBtn != null) {
            boolean running = PanelState.task == PanelState.Task.RUNNING;
            playPauseBtn.iconPath = running ? Icons.PAUSE : Icons.PLAY;
            if (playPauseHint != null) playPauseHint.text = Texts.t(running ? "hint.pause" : "hint.resume");
        }
        if (taskStatus != null) taskStatus.setLines(taskLines());
        if (logTa != null) logTa.setLines(logLines());
        if (setsTa != null) setsTa.setLines(setLines());
        if (heatHalfLb != null) heatHalfLb.text = Texts.t("task.halfInfo", heatHalf);
        if (refreshLb != null) refreshLb.text = Texts.t("cfg.refresh.value", Settings.progressRefreshSec);
        if (heatRadiusLb != null) heatRadiusLb.text = Texts.t("cfg.heatRadius.value", Settings.heatmapRadius);
        if (canvas != null) {
            canvas.half = heatHalf;
            int[] c = heatCenterChunks();
            canvas.scx = c[0];
            canvas.scz = c[1];
        }
    }

    private List<Widget.Line> taskLines() {
        List<Widget.Line> out = new ArrayList<>();
        boolean idle = PanelState.task == PanelState.Task.NONE;
        out.add(new Widget.Line(Texts.t("task.line.status", taskText(PanelState.task)), levelColor(
                PanelState.task == PanelState.Task.FAILED ? PanelState.Level.ERROR : PanelState.Level.INFO)));
        out.add(new Widget.Line(Texts.t("task.line.elapsed",
                idle ? Texts.t("value.unknown") : Theme.fmtDuration(PanelState.elapsedNow())), Theme.TEXT_LIGHT));
        out.add(new Widget.Line(Texts.t("task.line.eta",
                idle ? Texts.t("value.unknown") : Theme.fmtDuration(PanelState.etaMs)), Theme.TEXT_LIGHT));
        out.add(new Widget.Line(Texts.t("task.line.errors", PanelState.errors, PanelState.warnings),
                PanelState.errors > 0 ? 0xFFFF7A66 : Theme.TEXT_LIGHT));
        out.add(new Widget.Line(Texts.t("task.line.raw",
                PanelState.taskRawStatus == null || PanelState.taskRawStatus.isBlank()
                        ? Texts.t("task.raw.none") : PanelState.taskRawStatus), Theme.BTN_TEXT_OFF));
        return out;
    }

    private List<Widget.Line> setLines() {
        List<Widget.Line> out = new ArrayList<>();
        List<PanelState.SetRec> recs = PanelState.setsSnapshot();
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
        for (int i = recs.size() - 1; i >= 0 && out.size() < 40; i--) {
            PanelState.SetRec r = recs.get(i);
            out.add(new Widget.Line(Texts.t("task.set.rec",
                    fmt.format(new Date(r.time)), r.key,
                    r.prev == null ? Texts.t("task.set.initial") : r.prev, r.value), Theme.TEXT_LIGHT));
        }
        if (out.isEmpty()) out.add(new Widget.Line(Texts.t("task.sets.empty"), Theme.BTN_TEXT_OFF));
        return out;
    }

    // ==================== 工具 ====================

    private static String cs() {
        return PrereqStatus.rootCommand == null ? "cs" : PrereqStatus.rootCommand;
    }

    private static String permText() {
        return switch (PrereqStatus.perm) {
            case OPERATOR -> Texts.t("perm.operator");
            case READONLY -> Texts.t("perm.readonly");
            default -> Texts.t("perm.unknown");
        };
    }

    private static String taskText(PanelState.Task t) {
        return switch (t) {
            case RUNNING -> Texts.t("task.state.running");
            case PAUSED -> Texts.t("task.state.paused");
            case DONE -> Texts.t("task.state.done");
            case FAILED -> Texts.t("task.state.failed");
            default -> Texts.t("task.state.idle");
        };
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    private void exportText(String name, String content) {
        try {
            Path d = ChunkSmithGuiPaths.ensurePanelDir();
            Path f = d.resolve(name);
            Files.writeString(f, content, StandardCharsets.UTF_8);
            toast(Texts.t("toast.exported", f.getFileName()));
            PanelState.log(PanelState.Level.INFO, Texts.t("log.exported", f));
        } catch (IOException e) {
            toast(Texts.t("toast.exportFailed", e.getMessage()));
        }
    }
}
