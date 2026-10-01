# Changelog

本文件记录**本分支相对上游的改动**。上游是
[BarryAlen777/Chunksmith_modern_gui](https://github.com/BarryAlen777/Chunksmith_modern_gui)（MIT）。
本分支只做「换加载器与 Minecraft 版本」这一层工作，不改功能——不改交互、不改文案、不改指令序列、
不改存档目录布局。项目简介、版本矩阵、构建与安装见 [README](README.md)。

**1.0.4-r23 — 从 Forge 1.20.1 移植到 Minecraft 1.21.1 + NeoForge 21.1.249（2026-10-01）**

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
