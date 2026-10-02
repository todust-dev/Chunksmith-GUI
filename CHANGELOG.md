# Changelog

本文件记录**本分支相对上游的改动**。改动分两批记：**①** 换加载器与 MC 版本（2026-10-01），
**②** 界面文案与图标外置（2026-10-02，当天的两次工作合成一批，见下面的总览）。
上游是 [BarryAlen777/Chunksmith_modern_gui](https://github.com/BarryAlen777/Chunksmith_modern_gui)（MIT）。
项目简介、版本矩阵、构建与安装见 [README](README.md)。

| 批次 | 内容 | 日期 | 版本 |
|---|---|---|---|
| **①** | 从 Forge 1.20.1 移植到 Minecraft 1.21.1 + NeoForge 21.1.249（加载器 / API 适配） | 2026-10-01 | `1.0.4-r23` |
| **②** | 界面文案外置为标准语言文件（§4）+ 图标外置为独立 PNG，含按钮图标边长 11→12 的修正（§5）<br>*文案一句字面值都没改；图标只有按钮边长这一处视觉调整* | 2026-10-02 | 同上（未动版本号） |

两批都**不改功能**：不改交互、不改指令序列、不改存档目录布局。

---

## 批次 ① — 从 Forge 1.20.1 移植到 Minecraft 1.21.1 + NeoForge 21.1.249（2026-10-01）

下面每一条 API 结论都不是凭印象写的，而是用 `javap` 在
`~/.gradle/caches/neoformruntime/intermediate_results/compiledWithNeoForge_*.jar`（MC 1.21.1）
和 `neoforge-21.1.249-universal.jar` / `loader-4.0.44.jar` 上逐条核对过的签名。

---

## 1. API 迁移对照表（逐条）

### 1.1 加载器 / 事件

| 用途 | Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|---|
| 模组注解 | `net.minecraftforge.fml.common.Mod` | `net.neoforged.fml.common.Mod`（多了一个 `Dist[] dist()`） |
| 拿模组事件总线 | `FMLJavaModLoadingContext.get().getModEventBus()` | **构造器注入** `IEventBus`。FML 的 `FMLModContainer` 支持往 `@Mod` 类构造器注入 `IEventBus` / `ModContainer` / `Dist`（已在 `FMLModContainer` 字节码里确认这三个类型） |
| 双端注解 | `net.minecraftforge.api.distmarker.{Dist,OnlyIn}` | `net.neoforged.api.distmarker.{Dist,OnlyIn}`。类**不在** neoforge 本体里，而在 `net.neoforged:mergetool` 的 api jar 中（NeoForge 的传递依赖会带上），`@OnlyIn` 的语义与 RuntimeDistCleaner 行为不变 |
| 静态事件订阅者 | `@Mod.EventBusSubscriber(modid=…, bus = Bus.MOD, value = Dist.CLIENT)` | `@EventBusSubscriber(modid=…, value = Dist.CLIENT)`。<br>**关键变化**：`EventBusSubscriber.bus()` 在 1.21.1 已标 `@Deprecated(since="1.21.1", forRemoval=true)`；加载器改为**按每个 `@SubscribeEvent` 静态方法的参数类型自动分流**——参数实现 `IModBusEvent` 的注册到模组总线，其余注册到游戏总线（见 `AutomaticEventSubscriber#inject` 字节码）。本模组两个方法（`RegisterKeyMappingsEvent`、`FMLClientSetupEvent`）都是 `IModBusEvent`（后者的父类 `ModLifecycleEvent` 实现它），所以**去掉 `bus=` 后行为与原来完全一致** |
| 游戏总线 | `MinecraftForge.EVENT_BUS` | `NeoForge.EVENT_BUS` |
| 客户端 tick | `TickEvent.ClientTickEvent` + 判断 `Phase.END` | `net.neoforged.neoforge.client.event.ClientTickEvent.Post`（NeoForge 把带 `Phase` 的 tick 事件拆成了 `Pre`/`Post` 两个类） |
| 聊天回包 | `net.minecraftforge.client.event.ClientChatReceivedEvent` | `net.neoforged.neoforge.client.event.ClientChatReceivedEvent`，`getMessage()` 返回 `Component` 不变 |
| 键位注册 | `net.minecraftforge.client.event.RegisterKeyMappingsEvent` | `net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent`，`register(KeyMapping)` 不变 |
| 客户端启动 | `net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent` | `net.neoforged.fml.event.lifecycle.FMLClientSetupEvent`，不变 |
| 模组列表 | `net.minecraftforge.fml.ModList`、`net.minecraftforge.forgespi.language.IModInfo` | `net.neoforged.fml.ModList`、`net.neoforged.neoforgespi.language.IModInfo`。`getModContainerById(String)` → `Optional<? extends ModContainer>`、`getMods()`、`IModInfo.getVersion()` 仍返回 `org.apache.maven.artifact.versioning.ArtifactVersion`，所以 `PrereqStatus` 的逻辑一字未改 |
| 游戏目录 | `net.minecraftforge.fml.loading.FMLPaths` | `net.neoforged.fml.loading.FMLPaths` |

### 1.2 网络层（**本移植唯一被重写的部分**）

Forge 的 `SimpleChannel` 体系在 NeoForge 里已经整体不存在，换成原生的
`CustomPacketPayload` + `StreamCodec` + `PayloadRegistrar`：

| Forge 1.20.1 | NeoForge 1.21.1 |
|---|---|
| `NetworkRegistry.newSimpleChannel(name, ver, clientAccept, serverAccept)` | `RegisterPayloadHandlersEvent#registrar(version)` 拿到 `PayloadRegistrar` |
| `CHANNEL.registerMessage(id, Cls, encoder, decoder, handler)` | `registrar.playToServer(Type<T>, StreamCodec<? super RegistryFriendlyByteBuf,T>, IPayloadHandler<T>)` / `playToClient(…)` |
| 手写 `encode(FriendlyByteBuf)` / `decode(FriendlyByteBuf)` | `StreamCodec<FriendlyByteBuf,T>`。本模组用 `StreamCodec.of(encode, decode)` 显式写，不依赖 `ByteBufCodecs` 的推导，行为与原来逐字节一致 |
| `NetworkEvent.Context` + `ctx.enqueueWork` | `IPayloadContext` + `ctx.enqueueWork` |
| `CHANNEL.reply(msg, ctx)` | `PacketDistributor.sendToPlayer(serverPlayer, payload)` |
| `CHANNEL.sendToServer(msg)` | `PacketDistributor.sendToServer(payload)` |
| 包的 id 由注册序号隐式决定 | 每种包必须自带 `CustomPacketPayload.Type<T>`（内含 `ResourceLocation`）并实现 `type()` |

**两个必须说明的取舍：**

1. **协议版本字符串保持 `"1"`**，与原版一致。
2. **注册时必须加 `.optional()`**。原 Forge 版用
   `newSimpleChannel(name, () -> "1", "1"::equals, "1"::equals)`。Forge 的通道校验只要求
   「服务端声明的通道，客户端都得有」——服务端**没装**本模组时根本没有通道要校验，连接照常建立，
   这正是 `NetClient` 里那条「服务器未安装面板模组服务端部分」提示存在的前提。
   NeoForge 对应的开关是 `PayloadRegistrar#optional()`（默认 **false**），
   不加的话，装了本模组的客户端会被直接拒绝连进没装它的服务器——那是功能退化，所以必须加。

### 1.3 客户端 GUI

| 位置 | 1.20.1 写法 | 1.21.1 写法 | 原因 |
|---|---|---|---|
| `Theme.rl()` | `new ResourceLocation(ns, path)` | `ResourceLocation.fromNamespaceAndPath(ns, path)` | 1.21 起构造器改为 private，只能走静态工厂 |
| `PanelScreen.render` | `this.renderBackground(g)` | `this.renderTransparentBackground(g)` | 1.21.1 的 `renderBackground(g,mx,my,pt)` 变成了「模糊背景 + 菜单底图」，会把世界整片盖住；`renderTransparentBackground` 的**字节码恰好就是** 1.20.1 在「世界里」画的那条 `fillGradient(0,0,w,h,-1072689136,-804253680)`，所以换它才是原观感 |
| `PanelScreen.mouseScrolled` | `(double mx, double my, double amount)` | `(double mx, double my, double scrollX, double scrollY)` | 1.20.5 起 `GuiEventListener#mouseScrolled` 多了横向分量；面板控件只吃纵向，把 `scrollY` 当原来的 `amount` 传下去 |
| `PanelScreen.clearFocus()` | 私有方法，1.20.1 无冲突 | **改名 `clearWidgetFocus()`** | 1.21.1 的 `Screen` 新增了 `public void clearFocus()`，私有方法无法覆盖 public，否则编译报「正在尝试分配更低的访问权限」。面板自己维护 `focus` 字段，与原版 ComponentPath 焦点机制无关，改名后行为一致 |
| `GuiGraphics.blit(...)`、`fill(...)`、`drawString(...)`、`pose()`、`enableScissor/disableScissor` | — | **不变** | 逐个核对过签名，1.21.1 仍保留同一批重载 |

### 1.4 元数据

| 项 | 1.20.1 | 1.21.1 |
|---|---|---|
| 文件名 | `META-INF/mods.toml` | `META-INF/neoforge.mods.toml` |
| loader 版本 | `loaderVersion="[47,)"` | `loaderVersion="[4,)"`（FML 4.x） |
| 依赖写法 | `mandatory=true` | `type="required"` |
| 依赖 modId | `forge` | `neoforge` |
| 描述里的中文 | 直接写 TOML | 同；**但不能再放 `gradle.properties`**——`Properties` 按 ISO-8859-1 解析，中文会变乱码。所以中文描述写在 `src/main/templates/META-INF/neoforge.mods.toml` 里，并给 `ProcessResources` 显式设 `filteringCharset = 'UTF-8'` |

---

## 2. 逐文件改动清单

### 2.1 原样搬运（8 个，1.20.1 → 1.21.1 之间没有任何加载器/MC API 依赖变化）

```
Settings.java  PanelState.java  CommandBuilder.java  CommandRunner.java
gui/PatternMath.java  gui/Icons.java  gui/GuiParticles.java  gui/Widget.java
```

> 只针对**批次 ①** 而言。其中 `PanelState.java` / `CommandBuilder.java` / `gui/Icons.java` /
> `gui/Widget.java` 四个在**批次 ②** 里被改过（文案或图标外置），见 §4.2 与 §5。

### 2.2 只改 import / 少量适配（8 个）

| 文件 | 改动 |
|---|---|
| `ChunkSmithGuiMod.java` | `@Mod` 换包；构造器改为注入 `IEventBus` 并 `Net.register(modBus)` |
| `ChunkSmithGuiPaths.java` | `FMLPaths` 换包 |
| `PrereqStatus.java` | `Dist/OnlyIn/ModList/IModInfo/ModContainer` 换包；**逻辑一字未改** |
| `LogCapture.java` | 事件类换包 + 总线换 `NeoForge.EVENT_BUS`；**所有正则与状态机一字未改** |
| `ClientKeys.java` | tick 事件换 `ClientTickEvent.Post`；总线换 `NeoForge.EVENT_BUS` |
| `ClientSetup.java` | 注解换 `@EventBusSubscriber`，**去掉已弃用的 `bus=`**（见 §1.1） |
| `gui/Theme.java` | `ResourceLocation` 工厂方法 |
| `gui/PanelScreen.java` | `renderTransparentBackground` / `mouseScrolled` 四参 / `clearFocus` 改名（见 §1.3） |

### 2.3 重写（2 个）

- `net/Net.java`：四个包改用 `CustomPacketPayload` + `StreamCodec`，注册改用 `PayloadRegistrar`，
  服务端回包改用 `PacketDistributor.sendToPlayer`。**包体字段、编解码顺序、位图格式、
  region 扫描算法、维度目录解析全部与原来逐字节一致**（`StreamCodec.of` 手写编解码，不借助
  `ByteBufCodecs` 的自动推导，避免顺序/长度语义漂移）。
- `net/NetClient.java`：`CHANNEL.sendToServer(…)` → `PacketDistributor.sendToServer(…)`。

### 2.4 移植时发现并修掉的一个坑（原汁原味的新坑，不是上游的）

`HandshakeC2S` 是个无负载的「我要握手」标记包，一开始写成了
`StreamCodec.unit(new HandshakeC2S())`。看上去很自然，但 **`StreamCodec.unit` 的编码器会断言
「传进来的实例和注册时那个是同一个（`equals`）」**，而 `HandshakeC2S` 没有实现 `equals`，
于是 `NetClient` 里每次 `new HandshakeC2S()` 发出去就会抛
`IllegalStateException: Can't encode ...`。

这是离线自检（§3.1）抓到的，不是事后猜的。已改为：

```java
public static final HandshakeC2S INSTANCE = new HandshakeC2S();
public static final StreamCodec<FriendlyByteBuf, HandshakeC2S> CODEC =
        StreamCodec.of((buf, m) -> { }, buf -> INSTANCE);   // 编码不写字节，解码给单例
```

并把构造器收成 private、发送方统一用 `INSTANCE`。

---

## 3. 验证

### 3.1 离线自检（35 项，全部通过）

`_selftest/cn/blockforge/generated/chunksmithchunksmithgu/net/PortSelfCheck.java`
（**不进 jar**，也**不随本仓库分发**；用 `_selftest/init.gradle` 挂进 main 源集，
走 Gradle 解析好的完整 classpath 运行）：

- **四个包的编解码往返**：类型 id 唯一性与命名空间；`handshake_s2c` 有/无前置两条分支；
  非 ASCII 维度名；`heatmap_c2s` 的**负数坐标**（varint 必须保符号）；`heatmap_s2c`
  0 个 region / 3 个 region，**1024bit 位图逐字节比对**。
- **反向对照**：把位图改 1 bit、把 region 坐标改 1，往返比较必须判为「不同」——
  否则上面那条「逐字节一致」可能只是因为比较逻辑恒真。
- **指令构建回归**：方形/星形/椭圆/指定坐标的序列、`trim` 必须最后一条、
  表单校验的五条规则、预览解析要去注释/去行首斜杠/trim 空白。

> 顺带发现：`CommandBuilder.validate()` 里最后一条
> `shape.equals("star") && !needsRadius2()` 是**不可达死代码**，因为 `needsRadius2()`
> 的实现就是 `shape.equals("star")`。这是**上游自带**的写法，按「功能一致」原则**原样保留**，
> 没有顺手去改。

### 3.2 专用服务器加载

`./gradlew runServer`：模组被列进 Mod List、服务器到达 `Done (x.xxxs)!`，
日志里没有 `invalid dist DEDICATED_SERVER`、没有任何 `at cn.blockforge` 栈帧，
且 `<gameDir>/config/chunksmith_modern_gui/` 被创建出来——这条目录是
`Settings.load()` 的副作用，能反证 `@Mod` 构造器确实执行过、
`Net.register(modBus)` 确实挂上了监听器。

### 3.3 客户端（可视 / 端到端）——部分完成，卡在本机图形环境上

**已经证实的部分：**

客户端确实起来过，并且加载了本模组的资源包：

```
[Render thread/INFO] [minecraft/ReloadableResourceManager]:
Reloading ResourceManager: vanilla, mod_resources, mod/chunksmith_modern_gui, mod/neoforge
```

这一行能证明：客户端侧模组加载成功、`ClientSetup` 的两个模组总线事件都执行完了
（`RegisterKeyMappingsEvent` 注册 P 键、`FMLClientSetupEvent` 挂聊天捕获与 tick 轮询）、
所有客户端类（`PanelScreen` / `Theme` / `Widget` / `Icons` / `GuiParticles`）都能被解析、
贴图图集（含本模组的 `textures/gui/*.png`）正常建出来，
整个过程没有任何 `invalid dist DEDICATED_SERVER` 或类加载错误。

**没能拿到证据的部分，以及原因（全部是环境问题，不是移植问题）：**

1. 第一次客户端跑起来后停在 `AccessibilityOnboardingScreen`
   —— Minecraft 首次启动的「无障碍引导」界面会挡住 `--quickPlayMultiplayer` 自动进服。
   已通过预置 `run/options.txt` 的 `onboardAccessibility:false` 解决。
2. 解决后客户端能连上专用服务器（日志有 `ConnectScreen: Connecting to localhost, 25565`），
   但随后落到 `DisconnectedScreen`。原因是**外部专用服务器连同它的 Gradle 守护进程被我一起清掉**：
   守护进程一死，它拉起的服务器 JVM 就成了孤儿，stdin 关闭后不再处理新连接。
   这是自检编排的失误，与模组无关。
3. 于是改走单人 `--quickPlaySingleplayer selftest`（把专用服务器生成过的世界
   连同 4 个 `r.x.z.mca` 复制成单人存档），内置服务器在同进程内，最可靠。
   但这时 **X 显示的授权 cookie 已经失效**：

   ```
   Authorization required, but no authorization protocol specified
   [18:28:29] [main/ERROR] [EARLYDISPLAY/]: ERROR DISPLAY
   Failed to initialize the mod loading system and display. ... glfwInit failed.
   ```

   `/run/sddm/xauth_*` 不可读、`~/.Xauthority` 不存在，拿不到可用显示，
   客户端连窗口都建不出来。

**结论：`面板在 1.21.1 下真的渲染出来` + `握手/热力图包在真实客户端里收发成功` 这两条，
本次没有拿到直接证据。**

不过这两个风险点已被另外两条更硬的证据覆盖了大半：

- **网络层**：四个包的编解码往返在离线自检里逐字节验过（§3.1）；
  「包能否被 NeoForge 正确注册」则在专用服务器启动时验过（§3.2 已确认 `Net.register` 执行）。
  客户端与服务器之间唯一的未知量只剩「NeoForge 的 payload 管线本身」，
  而那是框架能力，不是本次改动的正确性问题。
- **GUI 层**：客户端能走到 `Reloading ResourceManager` 说明资源与类都没问题；
  渲染期的风险集中在 §1.3 那三处 API 适配，三处都是照着 `javap` 出来的签名改的。

自检脚手架留在开发环境的 `_selftest/` 里（**不在本仓库内**），
图形环境可用时可以直接复跑：

```bash
# 离线自检（纯逻辑 + 包编解码，不需要图形环境）
./gradlew -I ../_selftest/init.gradle runPortSelfCheck --no-daemon --console=plain

# 端到端（需要可用显示；跑完会在 run/ 下留一张 chunksmith-panel.png）
./gradlew -I ../_selftest/init.gradle runClient --no-daemon --console=plain
```

---

## 批次 ② — 界面文案与图标外置（2026-10-02）

这一批是**当天的两次工作合成一条**：

1. **文案外置**：把「给人看的文字」从 Java 源码搬到标准语言文件（§4）；
2. **图标外置**：把点阵图标换成独立 PNG，并按反馈把按钮图标边长从 11 调到 12（§5）。

两次都**没有改任何功能逻辑**（不改交互、不改指令序列、不改存档目录布局）。搬完之后改文案、改图标
都**不用重新编译**，也可以用自己的资源包覆盖：

- 改文案 → 编辑 `assets/chunksmith_modern_gui/lang/zh_cn.json`，不用重新编译；
- 改图标 → 编辑 `assets/chunksmith_modern_gui/textures/gui/icons/` 下的 `stat/<名字>.png`（大图标 16×16）
  或 `btn/<名字>.png`（小图标 12×12），不用重新编译；
- 两者都可以再用自己的资源包覆盖。

搬运过程做过「语义等价审计」（见 §7.3）：把新代码里的 `Texts.t("键", 参数…)` 按语言文件**反编译回
原来的字符串拼接**，再与改动前的源码比对 —— **四个受影响文件的「有效字符串集合」完全一致**，
即没有任何一句文案被改写、丢失或新增。

## 4. 文案外置到标准语言文件

### 4.1 新增 `Texts.java`

`Texts.t("键", 参数…)` = `Component.translatable("chunksmith_modern_gui." + 键, 参数).getString()`。
键名一律带 mod id 前缀防撞车。

### 4.2 逐文件替换

| 文件 | 改前中文字面量 | 现在 `Texts.t` 调用点 | 备注 |
|---|---|---|---|
| `gui/PanelScreen.java` | 174 | 150 | 其中 5 个「显示名表」改成键表 + `tr(...)` 辅助（标签 / 标题 / 形状 / 中心 / 日志过滤） |
| `CommandBuilder.java` | 10 | 8 | 6 条校验提示 + 预览头两行 |
| `gui/Widget.java` | 4 | 4 | 开关的「开 / 关」、下拉的「>」、热力图空态与画布信息 |
| `PanelState.java` | 2 | 2 | `/cs set` 备份导出文本的标题与「(初始)」 |
| `net/NetClient.java` | 2 | 2 | 两条警告日志 |
| **合计** | **190** | **166** | 166 处中 **161 处键是字面量**（已逐个对账）；另 5 处按变量 / 三元取键：`tr(k)`、两张键表、两处三元 |

语言文件：`assets/chunksmith_modern_gui/lang/zh_cn.json`，**183 条** `chunksmith_modern_gui.*` 键
（另有原本就存在的 2 条键位）。

### 4.3 两条必须遵守的规则

1. **占位符只支持 `%s` 和 `%%`**。`TranslatableContents` 用自己的模板解析器，遇到 `%.1f` 这类格式符
   会抛 `TranslatableFormatException` 并回退成原文显示。所以数字一律先在代码里
   `String.format("%.0f", x)` 化成字符串再当 `%s` 传；要显示一个百分号就写 `%%`。
   **副作用**：原来写成 `String.format("%.0f%%", x)` 的地方要拆成 `String.format("%.0f", x)`
   \+ 语言文件里的 `"%s%%"` —— 拼出来的字符串完全相同。
2. **语言值里 `%s` 的个数必须等于调用点实参个数**（已按调用点逐个核对 161 处）。

### 4.4 没有外置的东西（**不是漏了**）

- **指令参数的取值**：`square / circle / diamond / …`、`here / spawn / coords` 这些是要发给
  Chunksmith 的命令参数，翻译了就会改变发给服务端的内容。
- **`LogCapture` 里的 31 条中文匹配串**：那是解析服务端聊天回包用的正则与关键字
  （`Pattern.compile("(?i)(?:processed|已处理|…)")`、`lower.contains("取消")` 之类），
  属于**协议匹配**而不是给人看的文案。检查脚本会把它们单独列出来供人工确认。

### 4.5 `en_us.json` 本批次未动

按本轮要求**不写英文、也不拿中文当占位**，它仍然只有原来那 2 条键位。
代价要说明白：**游戏语言设成英文时面板会显示原始键名**（如 `chunksmith_modern_gui.tab.generate`），
因为那些键在 `en_us.json` 里没有值。

## 5. 图标外置为独立贴图

### 5.1 按用途分子目录（每个尺寸一张，文件名不带后缀，1:1 绘制）

一张贴图 = 一个绘制尺寸 = 1:1 绘制，运行时不做任何重采样：

```
assets/chunksmith_modern_gui/textures/gui/icons/
    btn/<名字>.png    小图标 12×12   × 14   按钮图标（Widget.Button）
    stat/<名字>.png   大图标 16×16   ×  3   任务卡片图标（Widget.RowCard）：play / grid / chart
```

只给「代码里真的会画到的图标」留文件：按钮里用到的才进 `btn/`，任务卡片里用到的才进 `stat/`。
**为什么小的 14 张、大的只有 3 张**：按钮图标一共用到 11 个不同图标、共 21 处按钮
（`play` 6 处、`pause` 4 处、`copy` 2 处、`refresh` 2 处、`stop` 2 处，`next` / `prev` / `grid` /
`trim` / `folder` / `rollback` 各 1 处），而 16px 只有任务页那三张卡片用（`grid` / `chart` / `play`）。
逐处清单（文件:行号）见 §7.2。
（`gear` / `log` / `close` 三个在上游点阵里有、但本模组代码里从来没画过，是上游遗留；仍按小图标给了
`btn/` 版，要删掉说一声。）

贴图内容全部由**本仓库原有的 `Icons.java` 点阵无条件导出**（`'#'`→纯白不透明、`'.'`→全透明；8bit RGBA）：

- `stat/<名字>.png`：上游点阵 1:1 取。
- `btn/<名字>.png`：把点阵按**覆盖率过半**缩到 12×12（每个目标像素覆盖 1.33 个源格，按面积过半判定）
  —— **不丢格、不重叠**，所以形状比例与原图一致。为什么不能用另外两种缩法，见 §5.3(b)。

导出后逐像素比对过：17 张都能追溯到上游点阵 —— 3 张 `stat/` 与点阵逐像素一致，
14 张 `btn/` 与「覆盖率过半」的结果逐像素一致。
环境里没有 PIL，用的是 stdlib `zlib + struct` 手写的最小 PNG 编码器。

### 5.2 `Icons.java`：从「逐格 `fill`」改成「读贴图 + 染色」

`Icons.draw(g, 名字, x, y, size)` 现在按尺寸挑子目录（`Icons.SMALL_DIR` / `Icons.LARGE_DIR`）：
12px 读 `icons/btn/<名字>.png`、16px 读 `icons/stat/<名字>.png`，按 `size` 1:1 画。
贴图是**白色 + alpha**，颜色由 `GuiGraphics#setColor` 调制 —— `position_tex` 着色器的片元就是
`fragColor = texture(...) * ColorModulator`，所以同一张图能跟着按钮状态在「正常 / 悬停 / 禁用变灰 /
石质」之间换色，与原逻辑一致。画完把 `setColor(1,1,1,1)` 复位（否则之后画的面板底、文字会被一起染色）。

### 5.3 ⚠ 两条硬规矩：`blit` 用 11 参数、尺寸必须 1:1

**（a）`GuiGraphics#blit` 必须用 11 参数那个重载**

| 重载 | 描述符 | 后果 |
|---|---|---|
| **11 参数（用这个）** | `(ResourceLocation;IIIIFFIIII)V` | 目标 `x,y,w,h` 与 UV `u,v,uW,vH` 分开 |
| 9 参数（**别用**） | `(ResourceLocation;IIFFIIII)V` | `w/h` 同时被当成 UV 宽度，只取到 `w×h` 那块 |

用错 9 参数的症状：图标只画了贴图**左上角**一块再撑满，右、下被裁掉。现在写的是
`blit(tex, x, y, size, size, 0, 0, size, size, size, size)` —— 目标边长、UV 范围、贴图边长三者都是
`size` ⇒ **1:1、不裁切、不拉伸**。已用 `javap` 核对编译产物：调用的是 11 参数，9 参数那个没有出现。

**（b）小尺寸那份不能靠运行时缩放，生成规则也要挑对**

16 个源格塞进 12 个像素，三种缩法实测差别很大（都是可数的，不是观感描述）：

| 缩法 | 结果 |
|---|---|
| **NEAREST**（16×16 贴图直接缩） | 采样点固定落在源第 0/2/3/4/6/7/8/10/11/12/14/15 列与行 → **第 1/5/9/13 列/行永远采不到**，细笔画整段消失（用 16×16 贴图缩到 11px 画按钮时，`refresh` 左下角那截小尾巴就整块没了） |
| **上游点阵那套「塌缩格补 1px」** | 一笔不丢，但 12px 下**有 4 个目标像素各承载 2 个源格**（第 2/5/8/11 列、行），两个源格 OR 进 1 个像素 → **笔画被加粗/糊**：`refresh` 的圆环从 8 宽变 9 宽、`prev` 的箭头出现 3 行等宽且上下不对称 |
| **覆盖率过半（现在用的）** | 每个目标像素按面积过半判定，既**不丢格也不重叠** → 形状比例贴近原图：`prev` 是 2→5→2 的对称锥形（原图 2→7→2），`refresh` 是干净的 8 宽圆环 |

所以 `btn/` 里的贴图是**离线**按「覆盖率过半」算好写进 PNG 的，运行时只做 1:1 绘制（不做任何重采样）。

### 5.4 补漏：3 条原本漏在外面的文案

本批次用**词法扫描**重新过了一遍「代码里还剩哪些中文字面量」，发现 3 条此前被漏掉的、确实给人看的文案
——`PanelScreen.exportText()` 里的：

```java
toast("已导出：" + f.getFileName());
PanelState.log(PanelState.Level.INFO, "已导出文件 " + f);
toast("导出失败：" + e.getMessage());
```

（漏检原因见 §7.3 末尾的注。）本批次已一并外置为
`toast.exported` / `log.exported` / `toast.exportFailed` 三条键。

### 5.5 按钮图标边长 11 → 12（修掉右下角图标「颗粒扭曲」）

`Widget.Button` 的 `iconW` 从 11 改成 12。11px 时是「16×16 贴图缩到 11」，最近邻会整列丢笔画，
右下角那 4 个按钮（上一页 / 继续·暂停 / 刷新 / 下一页）的箭头台阶和刷新的圆弧会错位。改成 12 之后，
小图标走的是**离线按覆盖率过半生成的 `btn/` 贴图**（§5.3(b)），于是：

- `icons/btn/<名字>.png` 的边长就是 12，与 `iconW` 一致 ⇒ **1:1 绘制，屏幕上不再有任何重采样**；
- 12 正好贴合按钮内部高度：按钮是 24×16，12px 图标**上下各留 2px、左右各留 4px**，
  既没顶到 `Theme.buttonFace` 的描边/高光/角点，也没有溢出（带文字的按钮同理，最窄的按钮也放得下）。

## 6. 本批次**没有**做的事（与批次 ①、以及与「视觉优化」的边界）

- **底栏保持原样**：没有删「确认调整」按钮、没有删底栏的分辨率读数、也没有改成「拖动即保存」。
  语言文件里 `panel.scale.info` 特意写成 `"%s%%  ·  %s×%s"`，并另加 `btn.applyUiScale` /
  `toast.uiScaleSaved` 两条键，就是为了**原样保住**现有底栏。
  （外置本身**必须**用对 `blit` 重载 —— 那是「不裁切」的正确性问题，不属于视觉优化。）
- **除了「按钮图标边长 11 → 12」这一处（§5.5，为的是让贴图 1:1、不做重采样）之外**，
  UI 布局、交互、指令序列、存档目录一律未动。
- **`README.md` 未改**（其中的面板功能描述不受本批次影响）；本文件即本批次的记录。
- **版本号未动**：仍是 `1.0.4-r23`。

## 7. 本批次的验证

### 7.1 键与调用点双向对账

对 `src/main/java` 全量扫描：

```
检查 1：代码引用但 lang 缺失的键     引用 183 个键，缺失 0 个
检查 2：lang 里有但代码没用到的键     多余 0 个
检查 3：%s 个数与实参个数对不上       核对 161 个调用点，对不上 0 处
检查 4：lang 值里的非法格式符         可疑 0 处
检查 5：源码残留中文字面量            只剩 LogCapture 的 31 条协议匹配串
```

### 7.2 图标一致性 + 代码/贴图对账 + 字节码里的 `blit` 重载

```
尺寸与颜色   17 张 PNG 边长都对（btn/ 12×12 / stat/ 16×16）、纯白 + alpha
一致性       3 张 stat/ == 上游点阵 1:1；14 张 btn/ == 上游点阵按「覆盖率过半」缩到 12（都逐像素一致）
代码 ↔ 贴图  代码用到的图标 ↔ 贴图文件逐一对上（缺一张就会画出原版缺失贴图的紫黑格）
命名         小图标在 btn/、大图标在 stat/，文件名一律不带后缀，icons/ 顶层无散落文件
blit         Icons.class 调用的是 (ResourceLocation;IIIIFFIIII)V（11 参数，正确）；
             9 参数重载 (ResourceLocation;IIFFIIII)V 未出现（正确）
尺寸常量     Widget.Button → Icons.SMALL_SIZE(12)，RowCard → Icons.LARGE_SIZE(16)

使用点对账（脚本从代码里抓的，文件:行号）：
  小图标 12px（Widget.Button，贴图 btn/<名字>.png）：
    play    6 处  PanelScreen.java:432、:436、:595、:688、:696、:1072
    pause   4 处  PanelScreen.java:432、:436、:692、:1072
    copy    2 处  PanelScreen.java:601、:812
    refresh 2 处  PanelScreen.java:443、:717
    stop    2 处  PanelScreen.java:700、:709
    next / prev / grid / trim / folder / rollback 各 1 处
    → 11 个图标、21 处按钮
  大图标 16px（Widget.RowCard，贴图 stat/<名字>.png）：
    grid / chart / play 各 1 处（PanelScreen.java:660 / :668 / :675，任务页那三张卡片）
  两个尺寸都要：grid、play；只用 16px：chart
```

三种 12px 压缩规则的量化对比（「颗粒扭曲」的来源），细节见 §5.3(b)：

| 缩法 | 目标像素承载 2 个源格 | 被丢掉的源列/行 | 结果 |
|---|---|---|---|
| NEAREST | 0 | 4（第 1/5/9/13） | 细笔画整段消失、粗块被削窄 |
| 上游塌缩（每格至少 1px） | **4**（第 2/5/8/11） | 0 | 笔画被加粗/糊，且局部不对称 |
| **覆盖率过半（现用）** | **0** | **0** | 形状比例与原图一致 |

观感对照（放大 16 倍，4 个底栏图标 × 四种画法）：`_work/icon-small-五画法.png`；
另外 `_work/按钮里的图标-12-vs-16.png` 是按 `Theme.buttonFace` 的真实画法模拟的 24×16 按钮，
左 12px（现状）、右 16px 1:1（贴到按钮内沿）——供判断要不要把按钮图标改成 16px。

### 7.3 语义等价审计（本批次新增的验证手段）

把新代码里的 `Texts.t("键", 参数…)` 按 `zh_cn.json` **反编译回等价的字符串拼接**，再与改动前的源码比对：

- **有效字符串集合**（源码字面量 + 反编译出来的值 + 键表展开后的值）：
  `PanelScreen` / `Widget` / `PanelState` / `CommandBuilder` **四个文件全部集合完全一致**。
  唯一允许的差异是 `"%.0f%%" → "%.0f" + "%"` 这类格式串拆分（拼出来相同，见 §4.3）。
- **骨架 token**（剥注释 + 抹掉所有字符串后）：剩下的差异只有
  「import / 键表与 `tr()` 辅助 / `Texts.t` 包裹」这三类结构性改写，外加**两处语义等价的可读性改写**，
  点名如下：
  1. `PanelScreen.drawStatusStrip`：为给 `%s` 传参，把两个内联三元表达式提升成局部变量
     `name` / `ver`（取值与原来完全相同）。
  2. `CommandBuilder.buildPreview`：先把半径串算成局部变量 `radius`（原来是 `append` 链），
     再一次性填进 `%s`；拼出的字符串相同。

基线来源：`PanelScreen` 用 `patch -R` 从改动前的差异文件反推还原；`Widget` / `PanelState` /
`CommandBuilder` 在批次 ① 里与上游 Forge 版**逐字节一致**，直接以上游为基线。

> **注（检查脚本自身的一个 bug，本批次已修）**：脚本原来用**正则**剥 Java 注释，
> `re.sub(r'//[^\n]*', '', s)` 会把**字符串里的 URL**（`"https://…"`）也当注释截断，
> 那一行于是剩下一个不成对的 `"`；之后按引号配对扫字面量时**整体错位**，
> 后面的真字面量被两两跳过 —— 表现是「源码里明明还有中文，检查却报 0 条」的**静默漏检**
> （§5.4 那 3 条就是这么被漏掉的）。现已改为按词法状态机剥注释，且字面量匹配不跨行。

### 7.4 构建、离线自检与产物对账

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
GRADLE_USER_HOME=/home/mdr/.gradle \
JAVA_TOOL_OPTIONS="-Xmx1536m -XX:MaxMetaspaceSize=512m \
  -XX:CompressedClassSpaceSize=256m -XX:ReservedCodeCacheSize=128m" \
./gradlew -I ../_selftest/init.gradle clean build runPortSelfCheck \
  --no-configuration-cache --no-daemon --console=plain
```

- `clean build` 成功；离线自检 **35/35 通过**（自检类不进 jar，脚手架说明见 §3.1）。
- 产物 `build/libs/chunksmith_modern_gui-neoforge-1.21.1-1.0.4-r23.jar`：
  **254365 字节，sha256 `083bef10ea47a98c90e3ee51644804390700a84538909d4eec3bb318051aeda3`**。
- jar 内对账：图标贴图 **17 张**（14 张 `btn/<名字>.png` + 3 张 `stat/<名字>.png`，与源文件逐字节一致）、
  `lang/zh_cn.json`（12509 字节）、`lang/en_us.json`（128 字节，未动）、`Texts.class`、
  `META-INF/LICENSE`（**仍与上游逐字节一致**）、共 47 个 class，**没有**自检类。

> 顺带说明一处与自检的耦合：脱游戏跑的自检里没有加载语言文件，
> `Component.translatable(键).getString()` 会回退成**键名本身**（实测日志里就是
> `[[chunksmith_modern_gui.validate.world]]`）。所以 `PortSelfCheck` 里那条
> 「星形没给第二半径」的断言改成了**键名或中文两种形态都接受**。

