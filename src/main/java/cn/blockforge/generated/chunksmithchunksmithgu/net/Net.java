package cn.blockforge.generated.chunksmithchunksmithgu.net;

import cn.blockforge.generated.chunksmithchunksmithgu.ChunkSmithGuiMod;
import cn.blockforge.generated.chunksmithchunksmithgu.PanelState;
import cn.blockforge.generated.chunksmithchunksmithgu.PrereqStatus;
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
 * <p><b>dist 安全</b>：这个类是客户端、服务端都会加载的公共类。它里面出现的
 * {@link MinecraftServer} / {@link ServerPlayer} 都是双端类，没有问题；而
 * {@code Minecraft} / 单人服务器这些客户端专属类型只在 {@link NetClient} 里出现。
 * 一旦公共类里引用了客户端类，专用服务器验证本类时会被 NeoForge 的 RuntimeDistCleaner
 * 以 “invalid dist DEDICATED_SERVER” 拦下。</p>
 */
public final class Net {

    /** 协议版本；与原 Forge 版的 {@code PROTOCOL = "1"} 保持一致。 */
    public static final String PROTOCOL = "1";

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ChunkSmithGuiMod.MOD_ID, path);
    }

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

    /** 服务端 → 客户端：前置模组 id / 版本 / 维度列表。 */
    public static final class HandshakeS2C implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<HandshakeS2C> TYPE =
                new CustomPacketPayload.Type<>(id("handshake_s2c"));
        public static final StreamCodec<FriendlyByteBuf, HandshakeS2C> CODEC =
                StreamCodec.of(HandshakeS2C::encode, HandshakeS2C::decode);

        public final String prereqId;      // 服务端前置模组 id（null=没有）
        public final String prereqVersion; // 版本串
        public final List<String> worlds;  // 可选世界/维度列表

        public HandshakeS2C(String id, String v, List<String> w) {
            prereqId = id; prereqVersion = v; worlds = w;
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
            List<String> w = new ArrayList<>();
            for (int i = 0; i < n && i < 64; i++) w.add(b.readUtf(96));
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
            }
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
            return new HeatmapC2S(b.readVarInt(), b.readUtf(128), b.readVarInt(), b.readVarInt(), b.readVarInt());
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

        HeatmapS2C(int id, String d, int x, int z, int h, int[] rx, int[] rz, byte[][] bits) {
            requestId = id; dim = d; cx = x; cz = z; half = h;
            regions = rx.length; this.rx = rx; this.rz = rz; this.bits = bits;
        }

        static void encode(FriendlyByteBuf b, HeatmapS2C m) {
            b.writeVarInt(m.requestId);
            b.writeUtf(m.dim); b.writeVarInt(m.cx); b.writeVarInt(m.cz); b.writeVarInt(m.half);
            b.writeVarInt(m.regions);
            for (int i = 0; i < m.regions; i++) {
                b.writeVarInt(m.rx[i]); b.writeVarInt(m.rz[i]);
                b.writeBytes(m.bits[i]);
            }
        }

        static HeatmapS2C decode(FriendlyByteBuf b) {
            int id = b.readVarInt();
            String d = b.readUtf(128);
            int x = b.readVarInt(), z = b.readVarInt(), h = b.readVarInt();
            int n = Math.min(b.readVarInt(), 65536);
            int[] rx = new int[n], rz = new int[n];
            byte[][] bits = new byte[n][];
            for (int i = 0; i < n; i++) {
                rx[i] = b.readVarInt(); rz[i] = b.readVarInt();
                bits[i] = new byte[128];
                b.readBytes(bits[i]);
            }
            return new HeatmapS2C(id, d, x, z, h, rx, rz, bits);
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

    private static void handleHeatmap(HeatmapC2S m, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;
        MinecraftServer server = sp.getServer();
        if (server == null) return;
        // 文件 IO 放后台线程，别卡服务端 tick
        Thread worker = new Thread(() -> {
            try {
                ScanResult r = scanRegions(server, m.dim, m.cx, m.cz, m.half);
                if (sp.hasDisconnected() || sp.getServer() != server) return;
                PacketDistributor.sendToPlayer(sp,
                        new HeatmapS2C(m.requestId, m.dim, m.cx, m.cz, m.half, r.rx, r.rz, r.bits));
            } catch (Throwable ignored) {
            }
        }, "ChunkSmithPanel-Heatmap");
        worker.setDaemon(true);
        worker.start();
    }

    private static void handleHeatmapResp(HeatmapS2C m, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (m.requestId != PanelState.heatRequestId) return;
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
            PanelState.heat = new PanelState.HeatData(set, m.dim, 0, 0, m.half, false, System.currentTimeMillis());
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
            return new PanelState.HeatData(set, dim, 0, 0, half, false, System.currentTimeMillis());
        }
    }

    /** 找到维度对应的存档目录，兼容 1.20 的 dimensions/ 路径和旧版回退路径。 */
    static Path dimensionRoot(MinecraftServer server, String dim) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        String d = dim == null ? "minecraft:overworld" : dim.toLowerCase(Locale.ROOT);
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
        Path dir = dimensionRoot(server, dim).resolve("region");
        half = Math.max(16, Math.min(half, 256)); // 钳制窗口：±256 区块以内，包体可控
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
