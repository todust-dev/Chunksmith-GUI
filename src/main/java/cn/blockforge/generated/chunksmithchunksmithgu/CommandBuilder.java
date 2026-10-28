package cn.blockforge.generated.chunksmithchunksmithgu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 表单 → 指令序列的构建与校验。输出用 ChunkSmith 的根命令（cs/chunky，运行期由命令树探测决定）。
 */
public final class CommandBuilder {

    private static final Pattern WORLD_OK = Pattern.compile("[A-Za-z0-9_\\-.]+");

    private CommandBuilder() {
    }

    /** 校验表单，返回问题列表（空 = 全部合法）。 */
    public static List<String> validate() {
        List<String> problems = new ArrayList<>();
        String w = PanelState.world == null ? "" : PanelState.world.trim();
        if (w.isEmpty() || !WORLD_OK.matcher(w).matches())
            problems.add(Texts.t("validate.world"));
        if (PanelState.radius < 1 || PanelState.radius > 1_000_000)
            problems.add(Texts.t("validate.radius"));
        if (PanelState.radius2 < 0 || PanelState.radius2 > 1_000_000)
            problems.add(Texts.t("validate.radius2"));
        if (PanelState.shape.equals("star") && PanelState.radius2 < 1)
            problems.add(Texts.t("validate.star"));
        if (PanelState.shape.equals("star") && PanelState.radius2 > PanelState.radius)
            problems.add(Texts.t("validate.starMax"));
        if (PanelState.centerMode.equals("coords")) {
            if (PanelState.centerX < -30_000_000 || PanelState.centerX > 30_000_000)
                problems.add(Texts.t("validate.centerX"));
            if (PanelState.centerZ < -30_000_000 || PanelState.centerZ > 30_000_000)
                problems.add(Texts.t("validate.centerZ"));
        }
        return problems;
    }

    /**
     * 清理页专用校验（报告 P1-1）：只检查 trim 真正会用到的参数。
     * 之前 doTrim() 复用生成页的 validate()，里面带"星形必须有第二半径"的生成页规则，
     * 而清理页当时没有这个输入框，选星形清理会被一条指向不存在字段的错误拦住。
     * 现在清理页也提供第二半径输入框（与 P1-2 配套），规则对得上、提示也说得清。
     */
    public static List<String> validateTrim() {
        List<String> problems = new ArrayList<>();
        String w = PanelState.world == null ? "" : PanelState.world.trim();
        if (w.isEmpty() || !WORLD_OK.matcher(w).matches())
            problems.add(Texts.t("validate.world"));
        if (PanelState.radius < 1 || PanelState.radius > 1_000_000)
            problems.add(Texts.t("validate.trimRadius"));
        if (PanelState.radius2 < 0 || PanelState.radius2 > 1_000_000)
            problems.add(Texts.t("validate.radius2"));
        if (PanelState.shape.equals("star") && PanelState.radius2 < 1)
            problems.add(Texts.t("validate.trimStar"));
        if (PanelState.shape.equals("star") && PanelState.radius2 > PanelState.radius)
            problems.add(Texts.t("validate.trimStarMax"));
        if (PanelState.centerMode.equals("coords")) {
            if (PanelState.centerX < -30_000_000 || PanelState.centerX > 30_000_000)
                problems.add(Texts.t("validate.centerX"));
            if (PanelState.centerZ < -30_000_000 || PanelState.centerZ > 30_000_000)
                problems.add(Texts.t("validate.centerZ"));
        }
        return problems;
    }

    public static boolean needsRadius2() {
        return shapeHasRadius2(PanelState.shape);
    }

    /**
     * 哪些形状带第二半径。Chunksmith 的 {@code /cs radius} 支持一/两个参数
     * （一个 = 方形/圆，两个 = 椭圆/矩形半径 x z）；星形同样吃两个半径。
     * 之前只认 star，选椭圆时预览画的是椭圆、服务端收到的却是单半径——
     * 生成出来跟看到的不是一个形状。
     */
    public static boolean shapeHasRadius2(String shape) {
        return shape.equals("star") || shape.equals("oval");
    }

    /** 生成完整指令序列（含选择与启动，逐行）。 */
    public static List<String> buildSequence() {
        List<String> l = new ArrayList<>(buildSelection());
        l.add(startCommand());
        return l;
    }

    /** 最后补发的那条启动指令。 */
    public static String startCommand() {
        return PrereqStatus.rootCommand + " start";
    }

    /** 只生成“选范围”的部分（world/pattern/center/radius），不含 start。 */
    public static List<String> buildSelection() {
        String cs = PrereqStatus.rootCommand;
        List<String> l = new ArrayList<>();
        l.add(cs + " world " + PanelState.world.trim());
        l.add(cs + " " + shapeCmd() + " " + PanelState.shape);
        l.addAll(centerLines());
        l.add(radiusLine());
        return l;
    }

    /** 半径行：带第二半径的形状且填了值就发两个参数。 */
    private static String radiusLine() {
        String cs = PrereqStatus.rootCommand;
        if (shapeHasRadius2(PanelState.shape) && PanelState.radius2 > 0)
            return cs + " radius " + PanelState.radius + " " + PanelState.radius2;
        return cs + " radius " + PanelState.radius;
    }

    /** 选形状的子命令名（Chunksmith=pattern / Chunky=shape，按命令树自动认）。 */
    private static String shapeCmd() {
        return PrereqStatus.shapeCommand == null ? "pattern" : PrereqStatus.shapeCommand;
    }

    /** 中心：当前位置 / 世界出生点 / 指定坐标。 */
    private static List<String> centerLines() {
        String cs = PrereqStatus.rootCommand;
        List<String> l = new ArrayList<>();
        switch (PanelState.centerMode) {
            case "spawn" -> l.add(cs + " spawn");
            case "coords" -> l.add(cs + " center " + PanelState.centerX + " " + PanelState.centerZ);
            default -> l.add(cs + " center");
        }
        return l;
    }

    /** 预览文本（多行，行号注释用 # 开头，执行时跳过）。 */
    public static String buildPreview() {
        List<String> seq = buildSequence();
        // 星形/椭圆才带第二半径，拼成 "500" 或 "500/200"
        String radius = String.valueOf(PanelState.radius);
        if (shapeHasRadius2(PanelState.shape) && PanelState.radius2 > 0) radius += "/" + PanelState.radius2;
        StringBuilder sb = new StringBuilder();
        sb.append(Texts.t("preview.header")).append('\n');
        sb.append(Texts.t("preview.summary", PanelState.shape, radius, PanelState.centerMode)).append('\n');
        for (String s : seq) sb.append(s).append('\n');
        return sb.toString();
    }

    /** 第二阶段的清理指令本身。 */
    public static String trimCommand() {
        return PrereqStatus.rootCommand + " trim";
    }

    /** trim 清理指令：Chunksmith（原版 Chunky 也是）的 trim 用的是“当前选择”，
     * 本身不接受形状/半径参数。所以先把世界、形状、中心、保留半径设好，最后再跑 trim，
     * 否则服务端会因为参数不合法直接拒绝，看着就是“清理不动”。
     */
    public static List<String> buildTrim() {
        List<String> l = new ArrayList<>(buildTrimSelection());
        l.add(PrereqStatus.rootCommand + " trim");
        return l;
    }

    /** trim 的第一阶段：只设范围，不含 trim 本身。
     *  半径走 radiusLine()（报告 P1-2）：星形/椭圆带第二半径时，清理范围和生成范围
     *  语义一致，之前只发单半径，椭圆/星形的清理范围与服务端预期对不上。
     */
    public static List<String> buildTrimSelection() {
        String cs = PrereqStatus.rootCommand;
        List<String> l = new ArrayList<>();
        l.add(cs + " world " + PanelState.world.trim());
        l.add(cs + " " + shapeCmd() + " " + PanelState.shape);
        l.addAll(centerLines());
        l.add(radiusLine());
        return l;
    }

    /** 把编辑过的预览文本拆成可执行行。 */
    public static List<String> parsePreviewLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("/")) t = t.substring(1);
            out.add(t);
        }
        return out;
    }

    /** 面板认识的形状名（手动预览里 pattern/shape 行只接受这些）。 */
    private static final List<String> KNOWN_SHAPES =
            List.of("square", "circle", "diamond", "triangle", "star", "oval");

    /** 已知的、参数不该越界的子命令之外，其它子命令一律放行（前置后面加命令不用改这里）。 */
    private static final Set<String> NO_ARG_CHECK = Set.of("start", "trim", "progress");

    /**
     * 手动编辑过的预览的防线（报告 P1-8）：
     *  1) 每一行都必须是本面板认可的根命令（{@code cs}/{@code chunky}），防止误粘贴别的命令；
     *  2) 对 {@code world/pattern/shape/radius/center} 做参数级范围检查，
     *     和表单校验同一套边界——手动预览不再"只查前缀、参数随便发"。
     * 返回 null = 没问题；否则返回中文问题描述。
     */
    public static String previewSanityCheck(List<String> lines) {
        String root = PrereqStatus.rootCommand == null ? "cs" : PrereqStatus.rootCommand;
        for (String l : lines) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String body = t.startsWith("/") ? t.substring(1).trim() : t;
            if (!body.equals(root) && !body.startsWith(root + " ")) {
                return Texts.t("preview.errRoot", root, t);
            }
            String[] parts = body.split("\\s+");
            if (parts.length < 2) continue;              // 光 "/cs" 没什么可查
            String sub = parts[1].toLowerCase(Locale.ROOT);
            if (NO_ARG_CHECK.contains(sub)) continue;
            if (sub.equals("world")) {
                if (parts.length >= 3 && !WORLD_OK.matcher(parts[2]).matches())
                    return Texts.t("preview.errWorld", t);
            } else if (sub.equals("pattern") || sub.equals("shape")) {
                if (parts.length >= 3 && !KNOWN_SHAPES.contains(parts[2].toLowerCase(Locale.ROOT)))
                    return Texts.t("preview.errShape", parts[2]);
            } else if (sub.equals("radius")) {
                int cnt = parts.length - 2;
                if (cnt < 1 || cnt > 2) return Texts.t("preview.errRadiusCount", t);
                for (int i = 2; i < parts.length; i++) {
                    Integer v = parseIntOrNull(parts[i]);
                    if (v == null) return Texts.t("preview.errRadiusInt", t);
                    if (v < 1 || v > 1_000_000) return Texts.t("preview.errRadiusRange", t);
                }
            } else if (sub.equals("center")) {
                int cnt = parts.length - 2;
                if (cnt == 0) continue;                   // 当前位置
                if (cnt != 2) return Texts.t("preview.errCenterCount", t);
                for (int i = 2; i < parts.length; i++) {
                    Integer v = parseIntOrNull(parts[i]);
                    if (v == null) return Texts.t("preview.errCenterInt", t);
                    if (v < -30_000_000 || v > 30_000_000) return Texts.t("preview.errCenterRange", t);
                }
            }
        }
        return null;
    }

    private static Integer parseIntOrNull(String s) {
        try { return Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
    }
}
