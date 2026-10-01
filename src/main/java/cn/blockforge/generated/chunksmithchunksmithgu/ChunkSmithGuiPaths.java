package cn.blockforge.generated.chunksmithchunksmithgu;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 模组自己的文件目录：游戏目录/config/chunksmith_modern_gui/。
 * 旧版本曾经直接写到游戏目录/chunksmith_panel/，首次启动时会自动迁移文件。
 */
public final class ChunkSmithGuiPaths {
    private ChunkSmithGuiPaths() {
    }

    /** 游戏运行目录（run directory）。客户端与服务端同一取法。 */
    public static Path gameDir() {
        // NeoForge：FMLPaths 仍在 net.neoforged.fml.loading 下，API 与 Forge 版一致。
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
    }

    public static Path configDir() {
        return gameDir().resolve("config");
    }

    public static Path panelDir() {
        return configDir().resolve("chunksmith_modern_gui");
    }

    private static Path legacyPanelDir() {
        return gameDir().resolve("chunksmith_panel");
    }

    /** 确保目录存在，并把旧版本留下的文件迁移到模组专属配置目录。 */
    public static Path ensurePanelDir() {
        Path d = panelDir();
        try {
            Files.createDirectories(d);
            Path legacy = legacyPanelDir();
            if (Files.isDirectory(legacy) && !legacy.equals(d)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(legacy)) {
                    for (Path source : files) {
                        Path target = d.resolve(source.getFileName().toString());
                        if (!Files.exists(target)) {
                            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return d;
    }
}
