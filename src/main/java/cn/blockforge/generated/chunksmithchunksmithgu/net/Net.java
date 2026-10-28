package cn.blockforge.generated.chunksmithchunksmithgu.net;

import cn.blockforge.generated.chunksmithchunksmithgu.ChunkSmithGuiMod;
import cn.blockforge.generated.chunksmithchunksmithgu.PanelState;
import cn.blockforge.generated.chunksmithchunksmithgu.PrereqStatus;
import cn.blockforge.generated.chunksmithchunksmithgu.Texts;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 面板与服务器之间的两条通道：
 *  Handshake —— 登录时问服务端“有没有前置、什么版本、有哪些世界”；
 *  Heatmap   —— 让服务端扫 region 文件头，回传每个 32x32 区块的 1024bit 位图（体积小、不碰区块对象）。
 *
 * <p><b>1.20.1/Forge → 1.21.1/NeoForge 的改法</b>：Forge 的
 * {@code NetworkRegistry.newSimpleChannel} + {@code SimpleChannel#registerMessage} 在 NeoForge 里已经没有了。
 * 现在用的是原生的 {@link CustomPacketPayload}：每种包自己声明
 * {@link CustomPacketPayload.Type 类型 id} 和 {@link StreamCodec 编解码器}，
 * 在模组总线的 {@link RegisterPayloadHandlersEvent} 上一次性注册，
 * 发送走 {@link PacketDistributor}，服务端回包也走 {@link PacketDistributor#sendToPlayer}
 * （不再有 {@code SimpleChannel#reply}）。</p>
 *
 * <p><b>为什么必须是 {@code optional()} 注册</b>：原 Forge 版用
 * {@code newSimpleChannel(name, () -> "1", "1"::equals, "1"::equals)}。Forge 的通道校验只要求
 * “服务端声明的通道，客户端都得有”，服务端**没有**装本模组时根本没有通道要校验，连接照常建立
 * ——这正是 {@code NetClient} 里那条「服务器未安装面板模组服务端部分」提示存在的前提。
 * NeoForge 对应的是 {@code registrar(...).optional()}，语义一致，所以这里必须显式加上，
 * 否则装了本模组的客户端会被拒绝连进没装它的服务器。</p>
 *
 * <p><b>协议号现在是 {@value #PROTOCOL}</b>（原 Forge 版一直停在 "1"）。{@code HandshakeS2C}
 * 加过 {@code MAX_WORLDS} 截断、{@code HeatmapS2C} 先加了 requestId、又加了末尾的 reason 字符串，
 * 包体结构已经变了；协议号不一致时 NeoForge 不会建通道，面板退化成「服务器未安装服务端部分」的
 * 可读提示，比按错位字段解析、最后抛异常断线安全。对端没装本模组（原版/他模服）仍然放行。</p>
 *
 * <p><b>dist 安全</b>：这个类是客户端、服务端都会加载的公共类。它里面出现的
 * {@link MinecraftServer} / {@link ServerPlayer} 都是双端类，没有问题；而
 * {@code Minecraft} / 单人服务器这些客户端专属类型只在 {@link NetClient} 里出现。
 * 一旦公共类里引用了客户端类，专用服务器验证本类时会被 NeoForge 的 RuntimeDistCleaner
 * 以 “invalid dist DEDICATED_SERVER” 拦下。</p>
 */
public final class Net {

    /** 本模组的包 id（{@code chunksmith_modern_gui:<path>}）。 */
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ChunkSmithGuiMod.MOD_ID, path);
    }

    /** 通道协议号。改过包体结构就必须 +1，理由见类注释。 */
    public static final String PROTOCOL = "2";

    private Net() {
    }

    // ==================== 注册 ====================

    /** 由 {@link ChunkSmithGuiMod} 在模组构造器里调用。 */
    public static void register(IEventBus modBus) {
        modBus.addListener(Net::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(PROTOCOL).optional();
        r.playToServer(HandshakeC2S.TYPE, HandshakeC2S.CODEC, Net::handleHandshake);
        r.playToClient(HandshakeS2C.TYPE, HandshakeS2C.CODEC, Net::handleHandshakeResp);
        r.playToServer(HeatmapC2S.TYPE, HeatmapC2S.CODEC, Net::handleHeatmap);
        r.playToClient(HeatmapS2C.TYPE, HeatmapS2C.CODEC, Net::handleHeatmapResp);
    }

    // ==================== 握手 ====================

    /**
     * 客户端 → 服务端：只表示“我要握手”，没有负载。
     *
     * <p>这里刻意<b>不用</b> {@code StreamCodec.unit(...)}：unit 的编码器会断言
     * “传进去的实例就是当初注册的那一个（equals）”。本类没有 equals，
     * 而发送方每次 {@code new HandshakeC2S()} 都是新对象，于是包一发出去就会抛
     * {@code IllegalStateException: Can't encode '...Net$HandshakeC2S@...', expected '...@...'}。
     * 直接写一个空编码器：编码不写任何字节，解码统一返回 {@link #INSTANCE}。</p>
     */
    public static final class HandshakeC2S implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HandshakeC2S> TYPE =
                new CustomPacketPayload.Type<>(id("handshake_c2s"));
        /** 全局唯一实例，发送方统一用它。 */
        public static final HandshakeC2S INSTANCE = new HandshakeC2S();
        public static final StreamCodec<FriendlyByteBuf, HandshakeC2S> CODEC =
                StreamCodec.of((buf, m) -> { }, buf -> INSTANCE);

        private HandshakeC2S() {
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 世界列表最多回传条数，编解码两侧一致，不会静默截断错位。 */
    private static final int MAX_WORLDS = 64;

    /** 服务端 → 客户端：前置模组 id / 版本 / 维度列表。 */
    public static final class HandshakeS2C implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HandshakeS2C> TYPE =
                new CustomPacketPayload.Type<>(id("handshake_s2c"));
        public static final StreamCodec<FriendlyByteBuf, HandshakeS2C> CODEC =
                StreamCodec.of(HandshakeS2C::encode, HandshakeS2C::decode);

        public final String prereqId;      // 服务端前置模组 id（null=没有）
        public final String prereqVersion; // 版本串
        public final List<String> worlds;  // 可选世界/维度列表

        HandshakeS2C(String id, String v, List<String> w) {
            prereqId = id; prereqVersion = v;
            worlds = w.size() > MAX_WORLDS ? new ArrayList<>(w.subList(0, MAX_WORLDS)) : w;
        }

        static void encode(FriendlyByteBuf b, HandshakeS2C m) {
            b.writeBoolean(m.prereqId != null);
            if (m.prereqId != null) {
                b.writeUtf(m.prereqId);
                b.writeUtf(m.prereqVersion == null ? "" : m.prereqVersion);
            }
            b.writeVarInt(m.worlds.size());
            for (String w : m.worlds) b.writeUtf(w);
        }

        static HandshakeS2C decode(FriendlyByteBuf b) {
            String id = null, v = null;
            if (b.readBoolean()) { id = b.readUtf(64); v = b.readUtf(32); }
            int n = b.readVarInt();
            if (n < 0 || n > MAX_WORLDS) throw new IllegalArgumentException("bad world count: " + n);
            List<String> w = new ArrayList<>();
            for (int i = 0; i < n; i++) w.add(b.readUtf(96));
            return new HandshakeS2C(id, v, w);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void handleHandshake(HandshakeC2S m, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        String id = null, v = null;
        for (String probe : new String[]{"chunksmith", "chunkysmith", "chunky"}) {
            var container = net.neoforged.fml.ModList.get().getModContainerById(probe);
            if (container.isPresent()) {
                id = container.get().getModInfo().getModId();
                v = container.get().getModInfo().getVersion().toString();
                break;
            }
        }
        List<String> worlds = new ArrayList<>();
        if (sp.getServer() != null) {
            for (ResourceKey<Level> k : sp.getServer().levelKeys()) {
                worlds.add(k.location().toString());
            }
        }
        PacketDistributor.sendToPlayer(sp, new HandshakeS2C(id, v, worlds));
    }

    /** 世界（维度）下拉列表，客户端缓存。 */
    public static volatile List<String> serverWorlds = null;

    private static void handleHandshakeResp(HandshakeS2C m, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            serverWorlds = m.worlds;
            if (m.prereqId != null) {
                PrereqStatus.serverVersion = m.prereqVersion;
                if (PrereqStatus.localModId == null) {
                    // 服务端有、客户端没有：以服务端信息为准
                    PrereqStatus.localModId = m.prereqId;
                    PrereqStatus.localModName = m.prereqId;
                    PrereqStatus.localVersion = m.prereqVersion;
                }
                PrereqStatus.serverRejected = false;
            } else {
                PrereqStatus.serverVersion = null;
                // 服务端明确说没装前置：多人下 prereqOk() 由命令树/本地兜底的
                // isSingleplayer 分支挡住（见 PrereqStatus.computePrereqOk），
                // 这里不写本地字段（Net 是双端类，不碰客户端状态）。
            }
            PrereqStatus.invalidateOkCache();
        });
    }

    // ==================== 热力图 ====================

    public static final class HeatmapC2S implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HeatmapC2S> TYPE =
                new CustomPacketPayload.Type<>(id("heatmap_c2s"));
        public static final StreamCodec<FriendlyByteBuf, HeatmapC2S> CODEC =
                StreamCodec.of(HeatmapC2S::encode, HeatmapC2S::decode);

        public final int requestId;
        public final String dim;
        public final int cx, cz, half;

        public HeatmapC2S(int id, String d, int x, int z, int h) {
            requestId = id; dim = d; cx = x; cz = z; half = h;
        }

        static void encode(FriendlyByteBuf b, HeatmapC2S m) {
            b.writeVarInt(m.requestId);
            b.writeUtf(m.dim); b.writeVarInt(m.cx); b.writeVarInt(m.cz); b.writeVarInt(m.half);
        }

        static HeatmapC2S decode(FriendlyByteBuf b) {
            int id = b.readVarInt();
            String d = b.readUtf(128);
            if (d.contains("..") || d.contains("/") || d.contains("\\")) {
                throw new IllegalArgumentException("bad dimension id");   // 拒绝路径穿越
            }
            int cx = b.readVarInt();
            int cz = b.readVarInt();
            int half = b.readVarInt();
            if (half < 0 || half > 512) throw new IllegalArgumentException("bad half: " + half);
            return new HeatmapC2S(id, d, cx, cz, half);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 每个 region（32x32 区块）一张 1024bit 位图，按位存在与否。 */
    public static final class HeatmapS2C implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HeatmapS2C> TYPE =
                new CustomPacketPayload.Type<>(id("heatmap_s2c"));
        public static final StreamCodec<FriendlyByteBuf, HeatmapS2C> CODEC =
                StreamCodec.of(HeatmapS2C::encode, HeatmapS2C::decode);

        public final int requestId;
        public final String dim;
        public final int cx, cz, half;
        public final int regions;            // 条目数
        public final int[] rx, rz;           // region 坐标
        public final byte[][] bits;          // 每 region 128 字节位图
        /** 拒绝原因（报告 P2-2）：空串 = 正常回包；客户端据此区分"被拒"和"真没区块"。 */
        public final String reason;

        HeatmapS2C(int id, String d, int x, int z, int h, int[] rx, int[] rz, byte[][] bits) {
            this(id, d, x, z, h, rx, rz, bits, "");
        }

        HeatmapS2C(int id, String d, int x, int z, int h, int[] rx, int[] rz, byte[][] bits, String reason) {
            requestId = id; dim = d; cx = x; cz = z; half = h; regions = rx.length;
            this.rx = rx; this.rz = rz; this.bits = bits;
            this.reason = reason == null ? "" : reason;
        }

        static void encode(FriendlyByteBuf b, HeatmapS2C m) {
            b.writeVarInt(m.requestId);
            b.writeUtf(m.dim); b.writeVarInt(m.cx); b.writeVarInt(m.cz); b.writeVarInt(m.half);
            b.writeVarInt(m.regions);
            for (int i = 0; i < m.regions; i++) {
                b.writeVarInt(m.rx[i]); b.writeVarInt(m.rz[i]);
                b.writeBytes(m.bits[i]);
            }
            b.writeUtf(m.reason);   // 追加在末尾：老客户端解析不到这字段也只是忽略
        }

        static HeatmapS2C decode(FriendlyByteBuf b) {
            int id = b.readVarInt();
            String d = b.readUtf(128);
            int x = b.readVarInt(), z = b.readVarInt(), h = b.readVarInt();
            // 条目数必须是 0..65536 的合法值；负数（恶意/损坏包）直接拒绝，
            // 否则 new int[n] 会抛 NegativeArraySizeException 崩掉客户端网络线程
            int n = b.readVarInt();
            if (n < 0 || n > 65536) throw new IllegalArgumentException("bad region count: " + n);
            int[] rx = new int[n], rz = new int[n];
            byte[][] bits = new byte[n][];
            for (int i = 0; i < n; i++) {
                if (b.readableBytes() < 8) throw new IllegalArgumentException("truncated heatmap payload");
                rx[i] = b.readVarInt(); rz[i] = b.readVarInt();
                bits[i] = new byte[128];
                if (b.readableBytes() < 128) throw new IllegalArgumentException("truncated bitmap");
                b.readBytes(bits[i]);
            }
            String reason = b.isReadable() ? b.readUtf(64) : "";
            return new HeatmapS2C(id, d, x, z, h, rx, rz, bits, reason);
        }

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // 客户端主动发起的那两个方法（requestHandshake / requestHeatmap）在 NetClient 里。
    // 它们要用 net.minecraft.client.Minecraft，属于客户端专属代码，绝不能留在这个
    // 服务端也会加载的公共类里——否则专用服务器验证 Net 时会去加载客户端类并以
    // “invalid dist DEDICATED_SERVER” 报错。

    // ==================== 服务端扫描的安全护栏 ====================

    /** 扫描用有界线程池：最多 2 个并发扫描 + 4 个排队，满了直接拒绝（而不是刷一个请求起一个线程）。 */
    private static final ThreadPoolExecutor HEAT_EXECUTOR = new ThreadPoolExecutor(
            1, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(4),
            r -> {
                Thread t = new Thread(r, "ChunksmithPanel-Heatmap");
                t.setDaemon(true);
                return t;
            });

    /** 每玩家上次请求时间：冷却 3 秒。 */
    private static final ConcurrentHashMap<UUID, Long> HEAT_LAST_REQ = new ConcurrentHashMap<>();
    private static final long HEAT_COOLDOWN_MS = 3000;
    /** （报告 P2-1）冷却表兜底清理：条目过多时把早于 1 小时的先扔掉，玩家退出也不会漏驻留。 */
    private static final long HEAT_REQ_EXPIRY_MS = 60 * 60_000L;

    /**
     * 拒绝原因在线上走的是<b>键名</b>而不是成品文案：服务端的语言设置和客户端不一定一样，
     * 直接发中文等于把服务端的语言强加给客户端。客户端收到后用 {@link #reasonText} 查本地 lang。
     * 键名带 mod id 前缀，和普通文本区分得开（老版本/上游发来的纯文本会原样显示）。
     */
    private static final char REASON_DETAIL_SEP = '\u0000';

    private static String reasonKey(String key) {
        return ChunkSmithGuiMod.MOD_ID + "." + key;
    }

    private static String reasonKey(String key, String detail) {
        return reasonKey(key) + REASON_DETAIL_SEP + detail;
    }

    /** 拒绝包：空位图 + 可读原因（报告 P2-2）。 */
    private static HeatmapS2C deny(HeatmapC2S m, String reason) {
        return new HeatmapS2C(m.requestId, m.dim, m.cx, m.cz, m.half,
                new int[0], new int[0], new byte[0][], reason);
    }

    private static void handleHeatmap(HeatmapC2S m, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        // 权限、冷却、发包都要碰服务端状态，统一回主线程做
        ctx.enqueueWork(() -> {
            MinecraftServer server = sp.getServer();
            // 拒绝包：空位图 + 带原因（报告 P2-2），客户端能区分"被拒"和"这个范围真没区块"

            // 权限：热力图暴露世界布局，至少要求一级 OP
            if (server == null || !sp.createCommandSourceStack().hasPermission(1)) {
                PacketDistributor.sendToPlayer(sp, deny(m, reasonKey("server.heatPerm")));
                return;
            }
            // 维度必须真实存在（同时挡住任何路径拼接的尝试）
            if (resolveLevel(server, m.dim) == null) {
                PacketDistributor.sendToPlayer(sp, deny(m, reasonKey("server.heatNoDim")));
                return;
            }
            // 每玩家冷却
            long now = System.currentTimeMillis();
            Long last = HEAT_LAST_REQ.get(sp.getUUID());
            if (last != null && now - last < HEAT_COOLDOWN_MS) {
                PacketDistributor.sendToPlayer(sp, deny(m, reasonKey("server.heatCooldown")));
                return;
            }
            if (HEAT_LAST_REQ.size() > 256) {
                long cutoff = now - HEAT_REQ_EXPIRY_MS;
                HEAT_LAST_REQ.entrySet().removeIf(en -> en.getValue() < cutoff);
            }
            HEAT_LAST_REQ.put(sp.getUUID(), now);

            final String dim = m.dim;
            try {
                HEAT_EXECUTOR.execute(() -> {
                    HeatmapS2C reply;
                    try {
                        ScanResult r = scanRegions(server, dim, m.cx, m.cz, m.half);
                        reply = new HeatmapS2C(m.requestId, dim, m.cx, m.cz, m.half, r.rx, r.rz, r.bits);
                    } catch (Throwable t) {
                        reply = deny(m, reasonKey("server.heatScanFailed", String.valueOf(t)));
                    }
                    // 回包切回服务端主线程发（扫描线程不碰连接状态）
                    final HeatmapS2C payload = reply;
                    server.execute(() -> {
                        if (!sp.hasDisconnected()) PacketDistributor.sendToPlayer(sp, payload);
                    });
                });
            } catch (RejectedExecutionException ex) {
                // 队列已满：直接回带原因的拒绝包，避免积压
                PacketDistributor.sendToPlayer(sp, deny(m, reasonKey("server.heatBusy")));
            }
        });
    }

    /** 在服务器真实维度里找 dim；找不到返回 null（拒绝任意字符串拼路径）。 */
    private static ResourceKey<Level> resolveLevel(MinecraftServer server, String dim) {
        if (dim == null) return null;
        String want = dim.toLowerCase(Locale.ROOT);
        for (ResourceKey<Level> k : server.levelKeys()) {
            if (k.location().toString().toLowerCase(Locale.ROOT).equals(want)) return k;
        }
        return null;
    }

    /** 把线上收到的拒绝原因（键名 [+ 细节]）翻成当前语言的文案；不是键名的纯文本原样返回。 */
    private static String reasonText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String prefix = ChunkSmithGuiMod.MOD_ID + ".";
        int sep = raw.indexOf(REASON_DETAIL_SEP);
        String key = sep >= 0 ? raw.substring(0, sep) : raw;
        if (!key.startsWith(prefix)) return raw;               // 老版本/上游发来的纯文本
        String bare = key.substring(prefix.length());
        return sep >= 0 ? Texts.t(bare, raw.substring(sep + 1)) : Texts.t(bare);
    }

    private static void handleHeatmapResp(HeatmapS2C m, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (m.requestId != PanelState.heatRequestId) return;
            // 被拒时给可读原因（报告 P2-2）：之前"权限不足/冷却/维度不存在"和
            // "这块区域真没区块"都画成空图，用户完全分不清。
            if (m.reason != null && !m.reason.isEmpty()) {
                PanelState.log(PanelState.Level.WARN, Texts.t("log.heatDenied", reasonText(m.reason)));
            }
            Set<Long> set = new HashSet<>();
            for (int i = 0; i < m.regions; i++) {
                byte[] bits = m.bits[i];
                int bx = m.rx[i] << 5, bz = m.rz[i] << 5;
                for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                    int idx = x + (z << 5);
                    if ((bits[idx >> 3] & (1 << (idx & 7))) != 0) {
                        int wx = bx + x, wz = bz + z;
                        if (Math.abs(wx - m.cx) <= m.half && Math.abs(wz - m.cz) <= m.half) {
                            set.add(PanelState.pos(wx, wz));
                        }
                    }
                }
            }
            PanelState.heat = new PanelState.HeatData(set, m.dim, m.half, System.currentTimeMillis());
        });
    }

    // ==================== region 扫描实现（服务端/单人共用） ====================

    static final class ScanResult {
        final int[] rx; final int[] rz; final byte[][] bits;
        ScanResult(int n) { rx = new int[n]; rz = new int[n]; bits = new byte[n][]; }
        PanelState.HeatData toData(String dim, int cx, int cz, int half) {
            Set<Long> set = new HashSet<>();
            for (int i = 0; i < rx.length; i++) {
                if (bits[i] == null) continue;
                int bx = rx[i] << 5, bz = rz[i] << 5;
                for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                    int idx = x + (z << 5);
                    if ((bits[i][idx >> 3] & (1 << (idx & 7))) != 0) {
                        int wx = bx + x, wz = bz + z;
                        if (Math.abs(wx - cx) <= half && Math.abs(wz - cz) <= half) set.add(PanelState.pos(wx, wz));
                    }
                }
            }
            return new PanelState.HeatData(set, dim, half, System.currentTimeMillis());
        }
    }

    /**
     * 找到维度对应的存档目录，兼容 1.20 的 dimensions/ 路径和旧版回退路径。
     *
     * <p>dim 必须是 {@link #resolveLevel} 已经在服务端维度里验证过的字符串，
     * 这里再兜底拒一次路径穿越字符，防止别处直接调用拼出越界路径。</p>
     */
    static Path dimensionRoot(MinecraftServer server, String dim) {
        Path root = server.getWorldPath(LevelResource.ROOT).normalize();
        String d = dim == null ? "minecraft:overworld" : dim.toLowerCase(Locale.ROOT);
        if (d.contains("..") || d.contains("/") || d.contains("\\")) {
            return root;   // 宁可回主世界，也绝不拼接可疑路径
        }
        if (d.equals("minecraft:overworld") || d.endsWith(":overworld")) return root;
        if (d.equals("minecraft:the_nether") || d.endsWith(":the_nether")) return root.resolve("DIM-1");
        if (d.equals("minecraft:the_end") || d.endsWith(":the_end")) return root.resolve("DIM1");
        int colon = d.indexOf(':');
        String namespace = colon >= 0 ? d.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? d.substring(colon + 1) : d;
        Path modern = root.resolve("dimensions").resolve(namespace).resolve(path);
        if (Files.isDirectory(modern.resolve("region")) || !Files.exists(root.resolve("DIM_" + namespace + "_" + path))) {
            return modern;
        }
        return root.resolve("DIM_" + namespace + "_" + path);
    }

    static ScanResult scanRegions(MinecraftServer server, String dim, int cx, int cz, int half) {
        half = Math.max(16, Math.min(half, 256)); // 钳制窗口：±256 区块以内，包体可控
        Path dir = dimensionRoot(server, dim).resolve("region");
        // 扫描上限兜底：即使窗口很大，也只扫有限个 region 文件
        int r0x = Math.floorDiv(cx - half, 32), r1x = Math.floorDiv(cx + half, 32);
        int r0z = Math.floorDiv(cz - half, 32), r1z = Math.floorDiv(cz + half, 32);
        List<Object[]> found = new ArrayList<>();
        for (int rz = r0z; rz <= r1z; rz++) {
            for (int rx = r0x; rx <= r1x; rx++) {
                Path f = dir.resolve("r." + rx + "." + rz + ".mca");
                if (!Files.isRegularFile(f)) continue;
                byte[] header = new byte[4096];
                try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
                    raf.readFully(header);
                } catch (IOException e) {
                    continue;
                }
                byte[] bitmap = new byte[128];
                for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                    int idx = x + (z << 5);
                    int off = (header[idx * 4] & 0xFF) << 16 | (header[idx * 4 + 1] & 0xFF) << 8 | (header[idx * 4 + 2] & 0xFF);
                    int cnt = header[idx * 4 + 3] & 0xFF;
                    if (off != 0 && cnt >= 1 && cnt <= 255) {
                        int bi = idx;
                        bitmap[bi >> 3] |= (byte) (1 << (bi & 7));
                    }
                }
                found.add(new Object[]{rx, rz, bitmap});
            }
        }
        ScanResult r = new ScanResult(found.size());
        for (int i = 0; i < found.size(); i++) {
            Object[] o = found.get(i);
            r.rx[i] = (int) o[0]; r.rz[i] = (int) o[1]; r.bits[i] = (byte[]) o[2];
        }
        return r;
    }
}
