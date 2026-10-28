package cn.blockforge.generated.chunksmithchunksmithgu.gui;

import cn.blockforge.generated.chunksmithchunksmithgu.ChunkSmithGuiMod;
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
    /**
     * 手动编辑过的预览行；null = 跟随表单自动生成。
     * 存在面板实例上而不是 TextArea 上，切到别的标签页再回来（包括任务页的“开始生成”）
     * 执行的都是同一套命令，不会静默回退成自动预览。
     */
    private List<String> manualPreviewLines = null;
    /** 上次“手动预览模式”发出指令的时间：连点保护用。 */
    private long lastManualSendMs = 0;
    private Widget.Label previewLb;
    private Widget.Button restoreBtn;

    // 任务页
    private Widget.ScrollText taskStatus;

    // 清理页
    private Widget.ScrollText trimTa;
    private Widget.Toggle trimConfirmToggle;
    private Widget.Button trimRunBtn;
    private Widget.Button trimCancelBtn;

    // 热力图页
    private Widget.HeatCanvas canvas;
    private Widget.Button heatPullBtn;
    private final List<String> dims = new ArrayList<>();
    private final List<String> dimNames = new ArrayList<>();
    private String heatDim = "minecraft:overworld";
    private int heatHalf = 128;
    private Widget.Label heatHalfLb;
    /** 上次握手回传的世界列表引用；变了就说明要重建维度下拉。 */
    private List<String> shownWorlds = null;

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
    /** 列表控件的增量刷新依据：只有对应版本变了才重建行。 */
    private int shownLogVersion = -1;
    private int shownSetsVersion = -1;
    /** 任务状态行的内容签名（P2-11）：不变就不重建行列表。 */
    private long shownTaskSig = 0L;
    /** 上一次看到的任务状态，用来在“转为完成”的那一帧触发边缘爆闪。 */
    private PanelState.Task lastTask = PanelState.Task.NONE;
    /** 复用 SimpleDateFormat（原来是每帧 new 一个）。 */
    private static final java.text.SimpleDateFormat HHMMSS = new java.text.SimpleDateFormat("HH:mm:ss");

    public PanelScreen() {
        super(Component.literal(Texts.t("panel.title", ChunkSmithGuiMod.MOD_NAME)));
    }

    @Override
    protected void init() {
        pendingPercent = Settings.uiScalePercent;
        heatHalf = Settings.heatmapRadius;
        computeTransform();
        PanelState.warmUpSets();   // 把上次的 /cs set 备份历史读回内存（回滚跨重启可用）
        NetClient.requestHandshake();
        buildChrome();
        buildTab();
        // 前置会“续跑”上次没跑完的任务，刚进游戏时它已经在跑了；面板得主动问几次才知道
        probeBudget = 4;
        nextProbeMs = 0;
        lastAutoRefreshMs = 0;
        PrereqStatus.refreshTree();
    }

    /** 输入框/文本区是否处于编辑状态——ClientKeys 靠它决定 P 键要不要关面板。 */
    public boolean isInputFocused() {
        return focus != null && focus.isEditing();
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

        // 上游在 1.20.1 里覆盖 renderBackground(GuiGraphics) 画一层 0x90000000 暗色，
        // 由 Screen#render 调用。1.21.1 的 renderBackground 换成了 4 参签名（默认是
        // 「模糊背景 + 菜单底图」，会把世界整片盖住），所以这里覆盖 4 参版本、画同一种暗色，
        // 并在下面显式调用一次。本类不调 super.render，父类不会再画第二次。
        this.renderBackground(g, 0, 0, 0);
        computeTransform();
        double vx = (mouseX - ox) / scale;
        double vy = (mouseY - oy) / scale;

        updateDynamic();
        maybeAutoRefresh(now);

        // pushPose/popPose 用 try/finally 配对：任何一个控件绘制抛异常都不会让姿势栈失衡，
        // 否则后续整个游戏界面的变换都会错位。
        g.pose().pushPose();
        try {
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
        } finally {
            g.pose().popPose();
        }
    }

    /**
     * （报告 P1-10）原版 renderBackground 每帧对整个屏幕做多档半透明铺满 + 背景暗化，
     * 面板本身是不透明的木框+羊皮纸，背景只露出四周一圈——直接改成一档深色半透明填充，
     * 视觉几乎无差别，每帧少几十次整屏 fill 和一套模糊。
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0x90000000);
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
            CommandRunner.sendQuiet(cs() + " progress");   // 自动轮询不进日志，免得刷屏
            return;
        }
        if (PanelState.task != PanelState.Task.NONE) { probeBudget = 0; return; }
        if (probeBudget <= 0) return;
        // 服务端明确回了“未知命令”就不用再问了；否则即使前置检测一时不准，也亲自问一次
        if (PrereqStatus.serverRejected) return;
        if (now < nextProbeMs) return;
        probeBudget--;
        nextProbeMs = now + 2000;
        CommandRunner.sendQuiet(cs() + " progress");
    }

    /** 标签条下方的一行前置状态：就绪绿色、缺失红色、版本不符橙色。 */
    private void drawStatusStrip(GuiGraphics g) {
        Font f = Minecraft.getInstance().font;
        String s;
        int color;
        // （报告 P2-4）状态条不再只看 localPresent()：服务端的权威信号优先。
        boolean tree = PrereqStatus.commandTreeSeen;
        if (PrereqStatus.serverRejected) {
            s = Texts.t("status.rejected", cs());
            color = Theme.DANGER;
        } else if (tree) {
            if (PrereqStatus.perm == PrereqStatus.Perm.READONLY) {
                // 命令树是按权限过滤后下发的。只读账号看不到 start/trim，
                // 以前会笼统报“命令树里缺少 /cs start”，看着像前置装坏了。
                s = Texts.t("status.treeReadonly", cs());
                color = Theme.WARN;
            } else {
                String miss = PrereqStatus.missingSubcommands();
                if (miss != null) {
                    s = Texts.t("status.treeMissing", miss);
                    color = Theme.WARN;
                } else {
                    s = Texts.t("status.treeReady", cs(), permText());
                    color = Theme.OK;
                }
            }
        } else if (PrereqStatus.localPresent()) {
            boolean single = Minecraft.getInstance().isSingleplayer();
            if (single) {
                s = Texts.t("status.ready",
                        PrereqStatus.localModName == null ? "ChunkSmith" : PrereqStatus.localModName,
                        PrereqStatus.localVersion == null ? "" : " v" + PrereqStatus.localVersion,
                        permText());
                color = Theme.OK;
            } else if (PrereqStatus.commandTreeSynced) {
                // 命令树同步好了却没有 cs 节点：要么服务端没装，要么玩家没权限（树被过滤掉了）。
                s = Texts.t("status.notDispatched", cs());
                color = Theme.DANGER;
            } else {
                s = Texts.t("status.localNoTree",
                        PrereqStatus.localModName == null ? "ChunkSmith" : PrereqStatus.localModName);
                color = Theme.WARN;
            }
        } else if (PrereqStatus.commandTreeSynced) {
            // 同样两种可能，别一口咬定“没装前置”——非管理员看到的命令树里本来就没有 /cs。
            s = Texts.t("status.noTree", cs());
            color = Theme.WARN;
        } else {
            s = Texts.t("status.missing");
            color = Theme.DANGER;
        }
        // （报告 P2-12）版本比对结果按输入串缓存，别每帧重新解析
        if (PrereqStatus.versionMismatchCached()) {
            s = Texts.t("status.versionMismatch",
                    PrereqStatus.localVersion, PrereqStatus.serverVersion, s);
            color = Theme.WARN;
        }
        if (PanelState.forceIgnoreSafety) {
            s = Texts.t("status.safetyBypassed", s);
            color = Theme.DANGER;
        }
        g.drawString(f, s, 24, 62, color, false);
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
        // FPS/内存文本放在缩放信息文本（约到 x=350）之后、按钮组（x=528）之前，大缩放下也不重叠
        g.drawString(f, Texts.t("panel.fps", mc.getFps()), 392, by + 4, Theme.INK_SOFT, false);
        g.drawString(f, Texts.t("panel.memory", usedMb), 448, by + 4, Theme.INK_SOFT, false);
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
        // （报告 P1-3）控件自己退出编辑（文本区按 Esc 结束单行编辑等）后，
        // focus 还指着它。不清理的话下面的"一律吞掉"会吃掉所有后续按键，键盘像卡死。
        if (focus != null && !focus.isEditing()) clearFocus();
        if (focus != null) {
            if (focus.keyPressed(key, scan, mods)) return true;
            if (key == GLFW.GLFW_KEY_ESCAPE) { clearWidgetFocus(); return true; }
            // 输入框聚焦时其它按键一律吞掉：原版 Screen 会拿 E 去开背包并顺手 onClose()，
            // 那会让用户在世界名里打字母 E 时面板直接消失、输入丢失。
            return true;
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
            g.drawString(f, text, x + (w - f.width(text)) / 2, y + (h - 8) / 2, sel ? Theme.INK : Theme.LABEL, false);
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
        // 控件是新建的，强制 updateDynamic 重新灌入行列表
        shownLogVersion = -1;
        shownSetsVersion = -1;
        shownTaskSig = Long.MIN_VALUE;
        previewTa = null;
        previewLb = null;
        restoreBtn = null;
        taskStatus = null;
        trimTa = null;
        trimConfirmToggle = null;
        trimRunBtn = null;
        trimCancelBtn = null;
        canvas = null;
        heatPullBtn = null;
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
        int xL = 28, wL = 300;
        int y = 76;

        widgets.add(new Widget.Label(xL, y, Texts.t("gen.world"), false));
        Widget.Field wf = Widget.Field.str(xL, y + 11, wL, 16, PanelState.world);
        wf.onChanged = () -> { PanelState.world = wf.value; onGenParamChange(); };
        widgets.add(wf);

        y += 34;
        widgets.add(new Widget.Label(xL, y, Texts.t("gen.shape"), false));
        Widget.Cycle shape = new Widget.Cycle(xL, y + 11, wL, 16, "",
                SHAPES, tr(SHAPE_NAME_KEYS), Math.max(0, SHAPES.indexOf(PanelState.shape)));
        shape.onChange = i -> { PanelState.shape = shape.value(); onGenParamChange(); };
        widgets.add(shape);

        y += 34;
        widgets.add(new Widget.Label(xL, y, Texts.t("gen.center"), false));
        Widget.Cycle center = new Widget.Cycle(xL, y + 11, wL, 16, "",
                CENTERS, tr(CENTER_NAME_KEYS), Math.max(0, CENTERS.indexOf(PanelState.centerMode)));
        center.onChange = i -> { PanelState.centerMode = center.value(); onGenParamChange(); };
        widgets.add(center);

        y += 34;
        widgets.add(new Widget.Label(xL, y, Texts.t("gen.coords"), false));
        // （报告 P1-9）输入非法时把状态写成越界哨兵而不是保留旧值：
        // 红框 + 校验拦截，不会出现"框里显示空、实际按 500 执行"的表里不一。
        Widget.Field xf = Widget.Field.num(xL, y + 11, 146, 16, PanelState.centerX, -30000000, 30000000);
        xf.onChanged = () -> { PanelState.centerX = xf.valid() ? (int) xf.longValue() : 30_000_001; onGenParamChange(); };
        widgets.add(xf);
        Widget.Field zf = Widget.Field.num(xL + 154, y + 11, 146, 16, PanelState.centerZ, -30000000, 30000000);
        zf.onChanged = () -> { PanelState.centerZ = zf.valid() ? (int) zf.longValue() : 30_000_001; onGenParamChange(); };
        widgets.add(zf);

        y += 34;
        widgets.add(new Widget.Label(xL, y, Texts.t("gen.radius"), false));
        Widget.Field rf = Widget.Field.num(xL, y + 11, 146, 16, PanelState.radius, 1, 1000000);
        rf.onChanged = () -> { PanelState.radius = rf.valid() ? (int) rf.longValue() : 0; onGenParamChange(); };
        widgets.add(rf);
        Widget.Field r2 = Widget.Field.num(xL + 154, y + 11, 146, 16, PanelState.radius2, 0, 1000000);
        r2.onChanged = () -> { PanelState.radius2 = r2.valid() ? (int) r2.longValue() : -1; onGenParamChange(); };
        widgets.add(r2);

        y += 34;
        Widget.Toggle ignore = new Widget.Toggle(xL, y, 190, 16, Texts.t("common.ignorePrereq"), PanelState.forceIgnorePrereq);
        ignore.onChange = b -> PanelState.forceIgnorePrereq = b;
        widgets.add(ignore);
        Widget.Toggle particles = new Widget.Toggle(xL + 198, y, 102, 16, Texts.t("common.particles"), Settings.particles);
        particles.onChange = b -> { Settings.particles = b; Settings.save(); };
        widgets.add(particles);
        // （报告 P1-4）“跳过安全预检”是独立的第二个开关：只管权限预检与预览参数安全网。
        // 和“忽略前置检测”分开，用户为了绕过前置误报时不会顺带废掉安全检查。
        y += 22;
        Widget.Toggle safety = new Widget.Toggle(xL, y, 210, 16, Texts.t("common.skipSafety"), PanelState.forceIgnoreSafety);
        safety.onChange = b -> {
            PanelState.forceIgnoreSafety = b;
            if (b) PanelState.log(PanelState.Level.WARN,
                    Texts.t("toast.safetyOn"));
        };
        if (PanelState.forceIgnoreSafety) safety.textColor = Theme.DANGER;
        widgets.add(safety);
        // 说明文字改到开关正下方独占一行：原来挂在开关右边（x=246）会压到右侧命令预览区，
        // 而且后添加的预览控件还会把它盖住，字看起来“糊在别人的框上”。
        Widget.Label safetyHint = new Widget.Label(xL, y + 20, Texts.t("hint.safety"), false);
        safetyHint.colorOverride = Theme.INK_SOFT;
        widgets.add(safetyHint);

        int xR = 344, wR = VW - 28 - xR;
        previewLb = new Widget.Label(xR, 76, previewCaption(), false);
        widgets.add(previewLb);
        previewTa = new Widget.TextArea(xR, 88, wR, 190);
        previewTa.onEdited = ls -> {
            manualPreviewLines = new ArrayList<>(ls);
            if (previewLb != null) previewLb.text = previewCaption();
            if (restoreBtn != null) restoreBtn.active = true;
        };
        // 恢复跟随表单时同步一次当前内容
        if (manualPreviewLines != null) previewTa.setLines(manualPreviewLines);
        else refreshPreview();
        widgets.add(previewTa);

        Widget.Button start = new Widget.Button(xR, 286, wR, 22, Texts.t("btn.start"));
        start.iconPath = Icons.PLAY;
        start.onPress = this::doStart;
        widgets.add(start);

        int third = (wR - 12) / 3;
        Widget.Button copy = new Widget.Button(xR, 314, third, 18, Texts.t("btn.copyCmd"));
        copy.iconPath = Icons.COPY;
        copy.onPress = () -> {
            CommandRunner.copy(String.join("\n", effectivePreviewLines()));
            toast(Texts.t("toast.cmdCopied"));
        };
        widgets.add(copy);
        Widget.Button reset = new Widget.Button(xR + third + 6, 314, third, 18, Texts.t("btn.resetForm"));
        reset.stone = true;
        reset.onPress = () -> {
            PanelState.world = "world";
            PanelState.shape = "square";
            PanelState.centerMode = "here";
            PanelState.centerX = 0;
            PanelState.centerZ = 0;
            PanelState.radius = 500;
            PanelState.radius2 = 0;
            manualPreviewLines = null;
            buildTab();     // 直接重建当前页即可，不用绕道任务页
            toast(Texts.t("toast.formReset"));
        };
        widgets.add(reset);
        restoreBtn = new Widget.Button(xR + 2 * (third + 6), 314, third, 18, Texts.t("btn.restorePreview"));
        restoreBtn.stone = true;
        restoreBtn.active = manualPreviewLines != null;
        restoreBtn.onPress = () -> {
            manualPreviewLines = null;
            refreshPreview();
            if (previewLb != null) previewLb.text = previewCaption();
            restoreBtn.active = false;
            toast(Texts.t("toast.previewRestored"));
        };
        widgets.add(restoreBtn);
    }

    private String previewCaption() {
        return manualPreviewLines == null
                ? Texts.t("gen.preview")
                : Texts.t("gen.previewManual");
    }

    /**
     * 预览是自动跟随表单的；用户手动改过就不覆盖。
     * 手动内容存在 {@link #manualPreviewLines}，不随标签页销毁。
     */
    private void refreshPreview() {
        if (manualPreviewLines != null) return;
        if (previewTa == null) return;
        previewTa.setLines(Arrays.asList(CommandBuilder.buildPreview().split("\n")));
    }

    /**
     * 生成页任何一个参数变了：刷新预览，并作废还在等第二阶段的那次启动。
     *
     * <p>以前只有清理页会这么做。点完“开始生成”再改半径/中心，3 秒后照样按旧的范围
     * 补发 {@code /cs start}，用户以为改动生效了，其实跑的是上一次的选择。
     * 清理和生成现在对称：改了参数就中止待发的那条危险指令。</p>
     */
    private void onGenParamChange() {
        refreshPreview();
        if (CommandRunner.isStartPending()) {
            CommandRunner.cancelStartPending(Texts.t("log.genParamsChanged"));
            toast(Texts.t("toast.genParamsChanged"));
        }
    }

    /** 当前要执行的命令行：手动编辑过的预览优先，否则用表单生成的。 */
    private List<String> effectivePreviewLines() {
        if (manualPreviewLines != null) return CommandBuilder.parsePreviewLines(String.join("\n", manualPreviewLines));
        return CommandBuilder.buildSequence();
    }

    private void doStart() {
        if (CommandRunner.isStartPending() || CommandRunner.isTrimPending()) {
            toast(Texts.t("toast.twoPhasePending"));
            return;
        }
        if (manualPreviewLines == null) {
            // 跟随表单：校验表单本身（报告 P1-8：手动预览模式不再拿无关表单字段卡人）
            List<String> problems = CommandBuilder.validate();
            if (!problems.isEmpty()) {
                toast(Texts.t("toast.validateFailed", problems.get(0)));
                PanelState.log(PanelState.Level.WARN, Texts.t("log.formInvalid", String.join("；", problems)));
                return;
            }
        }
        if (!PrereqStatus.prereqOk() && !PanelState.forceIgnorePrereq) {
            // 命令树是按权限过滤后下发的：非管理员（非 OP）看到的树里根本没有 /cs 节点，
            // 以前这种情况一律报“没检测到前置，请先安装”，把人往装模组的方向带。
            // 现在把两种可能都说清楚，并给出继续试的开关。
            if (PrereqStatus.commandTreeSynced && !PrereqStatus.commandTreeSeen) {
                toast(Texts.t("toast.noDispatch", cs()));
            } else {
                toast(Texts.t("toast.noPrereq"));
            }
            return;
        }
        if (!PrereqStatus.isOperator() && !PanelState.forceIgnoreSafety) {
            toast(Texts.t("toast.noPerm"));
            return;
        }
        List<String> lines = effectivePreviewLines();
        if (lines.isEmpty()) {
            toast(Texts.t("toast.previewEmpty"));
            return;
        }
        // 命令树检查（归“忽略前置检测”管）
        String missing = PrereqStatus.missingSubcommands();
        if (missing != null && !PanelState.forceIgnorePrereq) {
            toast(Texts.t("treeMissing", missing));
            PanelState.log(PanelState.Level.WARN, Texts.t("treeMissing", missing));
            return;
        }
        // 安全网：每一行都必须属于前置根命令，且 world/radius/center/pattern 参数在范围内
        // （报告 P1-8：手动预览现在过的也是这套参数级检查，不再只查前缀）。
        // 这是安全检查，只能由配置页的“跳过安全预检”独立开关放弃（报告 P1-4）。
        String bad = CommandBuilder.previewSanityCheck(lines);
        if (bad != null) {
            if (!PanelState.forceIgnoreSafety) {
                toast(bad);
                PanelState.log(PanelState.Level.WARN, bad);
                return;
            }
            PanelState.log(PanelState.Level.WARN, Texts.t("log.safetyBypassed", bad));
        }
        if (manualPreviewLines == null) {
            // 两阶段启动（报告 P1-6）：先发范围设置，服务端没报错才自动补发 start，
            // 避免 world/center/radius 被拒后 start 按旧选择悄悄跑起来。
            if (!CommandRunner.beginStartSequence(CommandBuilder.buildSelection(), CommandBuilder.startCommand())) {
                toast(Texts.t("toast.twoPhasePendingShort"));
                return;
            }
            toast(Texts.t("toast.rangeGenSent"));
            return;
        }
        // 手动编辑过的预览行数与次序由用户自己排，按原样连发。
        // 手动模式没有“等第二阶段”这个挂账，连点两下就会真的发两遍，所以这里补一个
        // 1.2 秒的连点保护（表单模式由 pendingStart 挡住重复点击）。
        long now = System.currentTimeMillis();
        if (now - lastManualSendMs < 1200) {
            toast(Texts.t("toast.tooFast"));
            return;
        }
        lastManualSendMs = now;
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
        wf.onChanged = () -> { PanelState.world = wf.value; onTrimParamChange(); };
        widgets.add(wf);

        y += 30;
        widgets.add(new Widget.Label(x0, y, Texts.t("trim.shape"), false));
        Widget.Cycle shape = new Widget.Cycle(x0, y + 11, w0, 16, "",
                SHAPES, tr(SHAPE_NAME_KEYS), Math.max(0, SHAPES.indexOf(PanelState.shape)));
        shape.onChange = i -> { PanelState.shape = shape.value(); onTrimParamChange(); };
        widgets.add(shape);

        y += 30;
        widgets.add(new Widget.Label(x0, y, Texts.t("trim.center"), false));
        Widget.Cycle center = new Widget.Cycle(x0, y + 11, w0, 16, "",
                CENTERS, tr(CENTER_NAME_KEYS), Math.max(0, CENTERS.indexOf(PanelState.centerMode)));
        center.onChange = i -> { PanelState.centerMode = center.value(); onTrimParamChange(); };
        widgets.add(center);

        y += 30;
        widgets.add(new Widget.Label(x0, y, Texts.t("trim.coords"), false));
        Widget.Field xf = Widget.Field.num(x0, y + 11, 146, 16, PanelState.centerX, -30000000, 30000000);
        xf.onChanged = () -> { PanelState.centerX = xf.valid() ? (int) xf.longValue() : 30_000_001; onTrimParamChange(); };
        widgets.add(xf);
        Widget.Field zf = Widget.Field.num(x0 + 154, y + 11, 146, 16, PanelState.centerZ, -30000000, 30000000);
        zf.onChanged = () -> { PanelState.centerZ = zf.valid() ? (int) zf.longValue() : 30_000_001; onTrimParamChange(); };
        widgets.add(zf);

        y += 30;
        // （报告 P1-1/P1-2）清理与生成对同一形状用同一套半径语义：
        // 星形必填、椭圆选填；buildTrimSelection() 通过 radiusLine() 一并发给服务端。
        widgets.add(new Widget.Label(x0, y, Texts.t("trim.radius"), false));
        Widget.Field rf = Widget.Field.num(x0, y + 11, 146, 16, PanelState.radius, 1, 1000000);
        rf.onChanged = () -> { PanelState.radius = rf.valid() ? (int) rf.longValue() : 0; onTrimParamChange(); };
        widgets.add(rf);
        Widget.Field r2 = Widget.Field.num(x0 + 154, y + 11, 146, 16, PanelState.radius2, 0, 1000000);
        r2.onChanged = () -> { PanelState.radius2 = r2.valid() ? (int) r2.longValue() : -1; onTrimParamChange(); };
        widgets.add(r2);

        y += 32;
        Widget.Toggle confirm = new Widget.Toggle(x0, y, 300, 16, Texts.t("trim.confirm"), PanelState.trimConfirmed);
        widgets.add(confirm);
        trimConfirmToggle = confirm;

        y += 24;
        Widget.Button run = new Widget.Button(x0, y, 300, 22, Texts.t("btn.trimRun"));
        run.iconPath = Icons.TRIM;
        run.textColor = Theme.DANGER;
        run.active = PanelState.trimConfirmed && !CommandRunner.isTrimPending();
        confirm.onChange = b -> { PanelState.trimConfirmed = b; run.active = b && !CommandRunner.isTrimPending(); };
        run.onPress = this::doTrim;
        widgets.add(run);
        trimRunBtn = run;

        // 发出范围设置后的那 3 秒里，"我确认要清理" 已经被作废、执行按钮也灰了，
        // 以前只能靠改一个参数才会中止（onTrimParamChange）。给一个明确的悔棋按钮：
        // 点错清理、或者想起世界/半径选错了，3 秒内点它就能拦下还没发出的 /cs trim。
        y += 26;
        Widget.Button cancelTrim = new Widget.Button(x0, y, 300, 18, Texts.t("btn.cancelTrim"));
        cancelTrim.stone = true;
        cancelTrim.active = false;
        cancelTrim.onPress = () -> {
            if (!CommandRunner.isTrimPending()) {
                toast(Texts.t("toast.trimNonePending"));
                return;
            }
            CommandRunner.cancelTrimPending(Texts.t("log.trimCancelledByUser"));
            toast(Texts.t("toast.trimCancelled"));
        };
        widgets.add(cancelTrim);
        trimCancelBtn = cancelTrim;
        Widget.Label cancelHint = new Widget.Label(x0, y + 20, Texts.t("hint.cancelTrimWindow"), false);
        cancelHint.colorOverride = Theme.INK_SOFT;
        widgets.add(cancelHint);

        int xR = 344, wR = VW - 28 - xR;
        widgets.add(new Widget.Label(xR, 76, Texts.t("trim.cmds"), false));
        Widget.Label seqNote = new Widget.Label(xR, 90, Texts.t("trim.cmdsNote", cs()), false);
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

    /** 改了任何一个清理参数，旧的“我确认要清理”立即作废（防止带着旧勾选误删）。 */
    private void onTrimParamChange() {
        PanelState.trimConfirmed = false;
        if (trimConfirmToggle != null && trimConfirmToggle.on) {
            trimConfirmToggle.on = false;
            trimConfirmToggle.syncLabel();
        }
        if (trimRunBtn != null) trimRunBtn.active = false;
        CommandRunner.cancelTrimPending(Texts.t("log.trimParamsChanged"));
        rebuildTrimPreview();
    }

    private void doTrim() {
        if (!PanelState.trimConfirmed) { toast(Texts.t("toast.trimNeedConfirm")); return; }
        if (CommandRunner.isTrimPending()) { toast(Texts.t("toast.trimPending")); return; }
        // 破坏性操作，先过清理专用校验（报告 P1-1：不再复用生成页 validate()，
        // 免得"星形需要第二半径"这类生成页规则拦住清理，且提示指向当前页存在的输入框）
        List<String> problems = CommandBuilder.validateTrim();
        if (!problems.isEmpty()) {
            toast(Texts.t("toast.validateFailed", problems.get(0)));
            PanelState.log(PanelState.Level.WARN, Texts.t("log.trimFormInvalid", String.join("；", problems)));
            return;
        }
        if (!PrereqStatus.prereqOk() && !PanelState.forceIgnorePrereq) {
            if (PrereqStatus.commandTreeSynced && !PrereqStatus.commandTreeSeen) {
                toast(Texts.t("toast.noDispatch", cs()));
            } else {
                toast(Texts.t("toast.noPrereqShort"));
            }
            return;
        }
        // 权限预检先做：命令树是按权限过滤的，非管理员根本看不到 /cs start、/cs trim，
        // 以前先查“缺少子命令”会让人看到“服务端命令树里缺少：/cs start”，
        // 明明是没权限，却像前置装坏了。这是安全检查，只能由"跳过安全预检"绕过（报告 P1-4）。
        if (!PrereqStatus.isOperator() && !PanelState.forceIgnoreSafety) {
            toast(Texts.t("toast.noPermTrim"));
            PanelState.log(PanelState.Level.WARN, Texts.t("log.trimPermBlocked"));
            return;
        }
        // 命令树检查：归"忽略前置检测"管（前置误报时可绕），但绕开要给明确警告（报告 P1-4）。
        // 清理只看 world / trim / radius / center——不拿生成页的 start 卡人。
        if (!PanelState.forceIgnorePrereq) {
            String missing = PrereqStatus.missingSubcommandsForTrim();
            if (missing != null) {
                toast(Texts.t("treeMissing", missing));
                return;
            }
        } else if (PrereqStatus.commandTreeSeen) {
            PanelState.log(PanelState.Level.WARN, Texts.t("log.trimIgnorePrereq"));
        }
        // 两阶段：先发范围设置，服务端没报错才自动补发 /cs trim
        if (!CommandRunner.beginTrimSequence(CommandBuilder.buildTrimSelection())) {
            toast(Texts.t("toast.opPending"));
            return;
        }
        toast(Texts.t("toast.rangeTrimSent"));
        // 清理是破坏性操作：勾选只对这一次有效，发出去就作废。
        // 否则 3 秒后第二阶段发出、按钮重新可用，连点两下第二次就不用再确认了。
        // （改任何一个清理参数也会走 onTrimParamChange()，把旧勾选作废并中止第二阶段。）
        PanelState.trimConfirmed = false;
        if (trimConfirmToggle != null) { trimConfirmToggle.on = false; trimConfirmToggle.syncLabel(); }
        if (trimRunBtn != null) trimRunBtn.active = false;
    }

    private void rebuildTrimPreview() {
        if (trimTa == null) return;
        List<Widget.Line> out = new ArrayList<>();
        out.add(new Widget.Line(Texts.t("trim.phase1"), Theme.BTN_TEXT_OFF));
        for (String s : CommandBuilder.buildTrimSelection()) out.add(new Widget.Line("/ " + s, 0xFFE8D9AE));
        out.add(new Widget.Line(Texts.t("trim.phase2"), Theme.BTN_TEXT_OFF));
        out.add(new Widget.Line("/ " + CommandBuilder.trimCommand(), Theme.DANGER));
        String radiusText = Texts.t("trim.radiusText", PanelState.radius);
        if (CommandBuilder.shapeHasRadius2(PanelState.shape) && PanelState.radius2 > 0) {
            radiusText = Texts.t("trim.radiusText2", PanelState.radius, PanelState.radius2);
        }
        out.add(new Widget.Line(Texts.t("trim.previewNote", PanelState.shape, PanelState.centerMode,
                radiusText), Theme.BTN_TEXT_OFF));
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
            // 滑完即写入设置：之前只改内存值，重开面板就回到默认（报告 P1-30）
            Settings.heatmapRadius = v;
            Settings.save();
            if (canvas != null) canvas.half = v;
        };
        widgets.add(half);
        heatHalfLb = new Widget.Label(xR, 148, "", false);
        widgets.add(heatHalfLb);

        Widget.Button pull = new Widget.Button(xR, 172, wR, 22, Texts.t("btn.pullHeat"));
        pull.iconPath = Icons.GRID;
        pull.onPress = this::pullHeatmap;
        widgets.add(pull);
        heatPullBtn = pull;

        Widget.Button clear = new Widget.Button(xR, 200, wR, 18, Texts.t("btn.clearDisplay"));
        clear.stone = true;
        // （报告 P2-6）清空同时作废"请求中"状态，否则按钮最长 12 秒卡在"扫描中…"
        clear.onPress = () -> {
            PanelState.heat = null;
            PanelState.heatRequestSentMs = 0;
            PanelState.heatNoServer = false;
            PanelState.nextHeatRequestId();   // 作废在途回包，清空后旧扫描结果不再回灌
            if (heatPullBtn != null) { heatPullBtn.active = true; heatPullBtn.label = Texts.t("btn.pullHeat"); }
            toast(Texts.t("toast.heatCleared"));
        };
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
        filter.onChange = i -> { logFilter = i; shownLogVersion = -1; };   // 换过滤条件立刻重建列表
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
        particles.onChange = b -> { Settings.particles = b; Settings.save(); };
        widgets.add(particles);
        Widget.Toggle sounds = new Widget.Toggle(x0, 116, w0, 16, Texts.t("common.sounds"), Settings.sounds);
        sounds.onChange = b -> { Settings.sounds = b; Settings.save(); };
        widgets.add(sounds);
        Widget.Toggle deco = new Widget.Toggle(x0, 138, w0, 16, Texts.t("common.decorations"), Settings.decorations);
        deco.onChange = b -> { Settings.decorations = b; Settings.save(); };
        widgets.add(deco);

        widgets.add(new Widget.Label(x0, 164, Texts.t("cfg.refresh"), false));
        Widget.Slider refresh = new Widget.Slider(x0, 176, 190, 16, 0, 60, Settings.progressRefreshSec, 1);
        refresh.onChange = v -> { Settings.progressRefreshSec = v; Settings.save(); };
        widgets.add(refresh);
        refreshLb = new Widget.Label(x0 + 198, 180, "", false);
        widgets.add(refreshLb);

        widgets.add(new Widget.Label(x0, 202, Texts.t("cfg.heatRadius"), false));
        Widget.Slider heatR = new Widget.Slider(x0, 214, 190, 16, 16, 256, Settings.heatmapRadius, 16);
        heatR.onChange = v -> { Settings.heatmapRadius = v; Settings.save(); };
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
            // 界面大小也一并恢复（之前漏了这项，恢复默认后滑条还是老值）
            Settings.uiScalePercent = 100;
            pendingPercent = 100;
            if (scaleSlider != null) scaleSlider.value = 100;
            Settings.save();
            buildTab();
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
            // （报告 P2-3）先弹出记录、发送失败立刻原样放回去：
            // 之前发送失败记录就丢了，用户以为还能回滚实际备份没了。
            PanelState.SetRec rec = PanelState.popRollback();
            if (rec == null) { toast(Texts.t("toast.nothingToRollback")); return; }
            String[] r = PanelState.rollbackTarget(rec);
            // 走回滚专用通道：服务端确认成功后不会再往历史里记一条（避免反复回滚来回记）
            if (!CommandRunner.sendRollbackSet(r[0], r[1], rec)) {
                PanelState.restoreRollback(rec);
                toast(Texts.t("toast.rollbackSendFailed"));
                return;
            }
            toast(Texts.t("toast.rollbackSent", r[0], r[1]));
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
        long now = System.currentTimeMillis();
        if (playPauseBtn != null) {
            boolean running = PanelState.task == PanelState.Task.RUNNING;
            playPauseBtn.iconPath = running ? Icons.PAUSE : Icons.PLAY;
            if (playPauseHint != null) playPauseHint.text = Texts.t(running ? "hint.pause" : "hint.resume");
        }
        // 任务转为“已完成”的那一帧：面板边缘来一次金色爆闪（粒子功能此前没接上，报告 P2-13）
        if (lastTask != PanelState.Task.DONE && PanelState.task == PanelState.Task.DONE) {
            GuiParticles.burst(0, 0, VW, VH);
        }
        lastTask = PanelState.task;

        if (taskStatus != null) {
            // （报告 P2-11）任务状态行按内容签名重建：数字没变就不每帧 new 5 个 Line
            long sig = taskSignature();
            if (sig != shownTaskSig) {
                taskStatus.setLines(taskLines());
                shownTaskSig = sig;
            }
        }
        // 日志 / 备份列表按版本号重建：数据没变就不每帧 new 几百个 Line 对象（报告 P1-19）
        if (logTa != null && shownLogVersion != PanelState.logVersion) {
            logTa.setLines(logLines());
            shownLogVersion = PanelState.logVersion;
        }
        if (setsTa != null && shownSetsVersion != PanelState.setsVersion) {
            setsTa.setLines(setLines());
            shownSetsVersion = PanelState.setsVersion;
        }
        if (heatHalfLb != null) heatHalfLb.text = Texts.t("task.halfInfo", heatHalf);
        if (refreshLb != null) refreshLb.text = Texts.t("cfg.refresh.value", Settings.progressRefreshSec);
        if (heatRadiusLb != null) heatRadiusLb.text = Texts.t("cfg.heatRadius.value", Settings.heatmapRadius);

        // 清理第一阶段还在等服务端确认时禁用“执行清理”
        if (trimRunBtn != null) trimRunBtn.active = PanelState.trimConfirmed && !CommandRunner.isTrimPending();
        // 只有确认窗口内（pendingTrim 非空）“取消待执行的清理”才可点
        if (trimCancelBtn != null) trimCancelBtn.active = CommandRunner.isTrimPending();

        // 握手晚于建页到达时，维度下拉跟着刷新（报告 P1-31）。只在内容真变了时重建一次。
        if (tab == Tab.HEAT && Net.serverWorlds != shownWorlds) {
            shownWorlds = Net.serverWorlds;
            rebuildDims();
            buildTab();
            return;
        }
        if (canvas != null) {
            canvas.half = heatHalf;
            int[] c = heatCenterChunks();
            canvas.scx = c[0];
            canvas.scz = c[1];
            // 请求进行中禁用“拉取热力图”，防止连点起多个并发扫描（报告 P1-32）
            if (heatPullBtn != null) {
                boolean pending = !PanelState.heatNoServer
                        && PanelState.heatRequestSentMs > 0
                        && now - PanelState.heatRequestSentMs < 12000
                        && (PanelState.heat == null || PanelState.heat.receivedMs < PanelState.heatRequestSentMs);
                heatPullBtn.active = !pending;
                heatPullBtn.label = pending ? Texts.t("btn.scanning") : Texts.t("btn.pullHeat");
            }
        }
    }

    /** 任务状态行的内容签名：任务状态、进度、剩余/耗时（秒级）、异常计数、原始回包哈希。 */
    private long taskSignature() {
        long h = 1469598103934665603L;
        h = mix(h, PanelState.task.ordinal());
        h = mix(h, (long) PanelState.percent);
        h = mix(h, PanelState.processed);
        h = mix(h, PanelState.total);
        h = mix(h, PanelState.elapsedNow() / 1000);
        h = mix(h, PanelState.etaMs / 1000);
        h = mix(h, PanelState.errors);
        h = mix(h, PanelState.warnings);
        h = mix(h, PanelState.sessionErrors);
        h = mix(h, PanelState.sessionWarnings);
        String raw = PanelState.taskRawStatus;
        h = mix(h, raw == null ? 0 : raw.hashCode());
        return h;
    }

    private static long mix(long h, long v) {
        h ^= v;
        return h * 1099511628211L;
    }

    private List<Widget.Line> taskLines() {
        List<Widget.Line> out = new ArrayList<>();
        boolean idle = PanelState.task == PanelState.Task.NONE;
        out.add(new Widget.Line(Texts.t("task.line.status", taskText(PanelState.task)), Theme.TEXT_LIGHT));
        out.add(new Widget.Line(Texts.t("task.line.elapsed",
                idle ? Texts.t("value.unknown") : Theme.fmtDuration(PanelState.elapsedNow())), Theme.TEXT_LIGHT));
        out.add(new Widget.Line(Texts.t("task.line.eta",
                idle ? Texts.t("value.unknown") : Theme.fmtDuration(PanelState.etaMs)), Theme.TEXT_LIGHT));
        // 当前任务计数与会话累计分开：清掉任务后不再显示“异常 N 条”的旧账（报告 P1-18）
        out.add(new Widget.Line(Texts.t("task.line.errorsSession", PanelState.errors, PanelState.warnings,
                PanelState.sessionErrors, PanelState.sessionWarnings),
                PanelState.errors > 0 ? 0xFFFF7A66 : Theme.TEXT_LIGHT));
        out.add(new Widget.Line(Texts.t("task.line.raw",
                PanelState.taskRawStatus == null || PanelState.taskRawStatus.isBlank()
                        ? Texts.t("task.raw.none") : PanelState.taskRawStatus), Theme.BTN_TEXT_OFF));
        return out;
    }

    private List<Widget.Line> setLines() {
        List<Widget.Line> out = new ArrayList<>();
        List<PanelState.SetRec> recs = PanelState.setsSnapshot();
        SimpleDateFormat fmt = HHMMSS;
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
