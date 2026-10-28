package cn.blockforge.generated.chunksmithchunksmithgu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 面板自身的持久化设置：写在我们模组目录下的 properties 文件里。
 * 键位（默认 P）不在这——那是原版控制设置管的（KeyMapping 自动出现在“按键绑定”里）。
 */
public final class Settings {
    private static final Properties P = new Properties();

    // 任务运行时面板边缘的进度粒子
    public static boolean particles = true;
    // 完成提示音
    public static boolean sounds = true;
    // 面板打开时自动刷新进度的秒数（0 = 关）
    public static int progressRefreshSec = 5;
    // 热力图默认半窗宽（区块数）
    public static int heatmapRadius = 128;
    // 界面大小（百分比，100 = 面板按设计尺寸铺满能放下的空间）
    public static int uiScalePercent = 100;
    // 面板四角的藤蔓花草装饰
    public static boolean decorations = true;

    private static Path file() {
        return ChunkSmithGuiPaths.ensurePanelDir().resolve("panel-settings.properties");
    }

    public static void load() {
        P.clear();
        try {
            Path f = file();
            if (Files.isRegularFile(f)) {
                try (var reader = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    P.load(reader);
                }
            }
        } catch (IOException ignored) {
        }
        particles = Boolean.parseBoolean(P.getProperty("particles", "true"));
        sounds = Boolean.parseBoolean(P.getProperty("sounds", "true"));
        try { progressRefreshSec = Integer.parseInt(P.getProperty("refresh", "5")); } catch (NumberFormatException ignored) { }
        try { heatmapRadius = Integer.parseInt(P.getProperty("heatRadius", "128")); } catch (NumberFormatException ignored) { }
        try { uiScalePercent = Integer.parseInt(P.getProperty("uiScale", "100")); } catch (NumberFormatException ignored) { }
        decorations = Boolean.parseBoolean(P.getProperty("decorations", "true"));
        progressRefreshSec = Math.max(0, Math.min(60, progressRefreshSec));
        heatmapRadius = Math.max(16, Math.min(256, heatmapRadius));
        heatmapRadius = 16 * Math.round(heatmapRadius / 16.0f);
        uiScalePercent = Math.max(50, Math.min(200, uiScalePercent));
    }

    /** 保存设置；失败会通过面板日志提示（之前静默吞异常，UI 还显示“已保存”）。 */
    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            P.setProperty("particles", String.valueOf(particles));
            P.setProperty("sounds", String.valueOf(sounds));
            P.setProperty("refresh", String.valueOf(progressRefreshSec));
            P.setProperty("heatRadius", String.valueOf(heatmapRadius));
            P.setProperty("uiScale", String.valueOf(uiScalePercent));
            P.setProperty("decorations", String.valueOf(decorations));
            try (var writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                P.store(writer, "ChunkSmith Panel Settings");
            }
        } catch (IOException e) {
            PanelState.log(PanelState.Level.ERROR, Texts.t("log.settingsSaveFailed", e));
        }
    }
}
