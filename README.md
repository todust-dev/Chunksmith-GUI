# Chunksmith modern gui — NeoForge 1.21.1

Chunksmith（`/cs` 指令族）前置模组的现代化图形面板，**从 Forge 1.20.1 移植到 NeoForge 1.21.1**。

- **原作者**：BarryAlen777
- **上游仓库**：https://github.com/BarryAlen777/Chunksmith_modern_gui
- **许可证**：MIT（见 [LICENSE](LICENSE)，随 jar 一起分发在 `META-INF/LICENSE`）
- **本仓库**：**非官方（unofficial）移植分支**，只换加载器与 Minecraft 版本；目标只有一个：
  **功能一致**——不改交互、不改文案、不改指令序列、不改存档目录布局。
  模组本体、界面设计与文案均归原作者所有。

逐条 API 对照、逐文件改动清单、以及每一步的验证证据见 **[CHANGELOG.md](CHANGELOG.md)**。

## 版本矩阵

| 项目 | 上游 | 本仓库 | 说明 |
|---|---|---|---|
| Minecraft | 1.20.1 | **1.21.1** | |
| 加载器 | Forge 47.4.0 | **NeoForge 21.1.249** | |
| 构建插件 | ForgeGradle `[6.0,6.2)` | **ModDevGradle 2.0.141** | NFRT 直接产出官方（Mojang）映射，**不再需要 `reobfJar`** |
| Java | 17 | **21** | MC 1.21 要求 21 |
| 元数据文件 | `META-INF/mods.toml` | **`META-INF/neoforge.mods.toml`** | 文件名与字段名都变了，见 CHANGELOG §2 |
| 资源包格式 | `pack_format 15` | **`pack_format 34`** | |
| Java 包名 | `cn.blockforge.generated.chunksmithchunksmithgu` | **不变** | 保持逐行可比对，便于 diff 上游 |
| mod id / 版本 | `chunksmith_modern_gui` / `1.0.4-r23` | **不变** | |
| 产物名 | `chunksmith_modern_gui-1.0.4-r23.jar` | `chunksmith_modern_gui-neoforge-1.21.1-1.0.4-r23.jar` | 加后缀避免与 1.20.1 Forge 版同名 jar 冲突 |

## 构建

需要 **JDK 21**：

```bash
./gradlew build --no-configuration-cache --no-daemon --console=plain
# 产物：build/libs/chunksmith_modern_gui-neoforge-1.21.1-1.0.4-r23.jar
```

其它命令：

```bash
./gradlew runClient      # 开发客户端（需要图形环境）
./gradlew runServer      # 开发专用服务器（已加 --nogui）
```

### 地址空间受限的机器

开发用的那台机器 shell `ulimit -v` 硬上限是 4 GiB（`ulimit -Hv` 也是 4194304，提不上去）。
Gradle 守护进程、以及 NeoFormRuntime 自己 fork 出来的 JVM，默认堆是物理内存的 1/4（≈4 GB），
再加 1 GB compressed class space，虚拟地址空间直接超限，报
`Could not allocate compressed class space: 1073741824 bytes`。要**两头一起压**：

- **Gradle 侧**已写在 `gradle.properties`：
  `-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:CompressedClassSpaceSize=192m -XX:ReservedCodeCacheSize=128m`
- **NeoFormRuntime 侧**（它 fork 出来的 JVM 不受 `gradle.properties` 管）用环境变量：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export GRADLE_USER_HOME=/home/mdr/.gradle
export JAVA_TOOL_OPTIONS="-Xmx1536m -XX:MaxMetaspaceSize=512m \
  -XX:CompressedClassSpaceSize=256m -XX:ReservedCodeCacheSize=128m"

./gradlew build --no-configuration-cache --no-daemon --console=plain
```

另外：**不要用 `nohup ... &` 把 Gradle 构建脱离出去**——脱离后的 shell 会丢库路径，
`sleep` / `grep` 会以 `cannot open shared object file` / `Function not implemented` 失败。
用调用方自带的后台机制跑。

## 安装

1. Minecraft **1.21.1 + NeoForge 21.1.x**。
2. 把 jar 放进 `.minecraft/mods`（**客户端必须装**；服务端装了才有热力图与握手信息）。
3. 需要 Chunksmith 前置（https://modrinth.com/plugin/chunksmith），服务端也要装。
4. 进游戏按 **P** 打开指令面板；键位可在「控制 → 按键绑定」里改。

## 面板功能（与 1.20.1 版一致，六个标签页）

| 标签页 | 内容 |
|---|---|
| 生成 | 世界名 / 形状 / 中心模式 / 中心坐标 / 主半径+第二半径、忽略前置检测、运行粒子、命令预览（可逐行编辑）、开始生成、复制命令、重置表单 |
| 任务 | 总进度 / 已生成区块 / 生成速度三张卡片，最近状态与异常，开始·暂停·继续·取消·确认取消·刷新进度·标记为已结束 |
| 清理 | trim 危险操作（需勾选确认），会先设好选择再发 `/cs trim` |
| 热力图 | 维度选择、窗口半径滑条、拉取/清空，绿=已生成、灰=存在但不在图案内、金=图案内还没生成 |
| 日志 | 全部 / 仅警告错误 / 仅指令 三种过滤，复制全部·复制异常·导出文件·清空 |
| 配置 | 运行粒子、完成提示音、藤蔓角饰、进度自动刷新秒数、热力图默认窗口、保存/恢复默认、打开面板文件夹、`/cs set` 改动备份与回滚、打开开源项目 |

配置写在 `<游戏目录>/config/chunksmith_modern_gui/panel-settings.properties`，
导出的日志/备份也在这个目录；旧版 `<游戏目录>/chunksmith_panel/` 里的文件首次启动会自动迁移过来。
