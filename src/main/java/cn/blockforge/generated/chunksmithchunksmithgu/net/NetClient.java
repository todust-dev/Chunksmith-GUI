package cn.blockforge.generated.chunksmithchunksmithgu.net;

import cn.blockforge.generated.chunksmithchunksmithgu.PanelState;
import cn.blockforge.generated.chunksmithchunksmithgu.Texts;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * 客户端专用的网络入口：只有面板界面会调用这两个方法。
 *
 * <p>为什么单独成类：{@link Net} 是客户端、服务端都会加载的公共类，
 * 里面一旦出现 {@code Minecraft} / 单人服务器 {@code IntegratedServer} 这类
 * 客户端专属类型，专用服务器在验证 {@code Net} 时就会去加载它们，然后被 NeoForge 的
 * Runtime Dist Cleaner 以 “Attempted to load class ... for invalid dist DEDICATED_SERVER”
 * 拦下，模组直接加载失败、服务器启动中断。</p>
 *
 * <p>这个类只被 GUI 引用，而 GUI 只在客户端加载，所以专用服务器永远碰不到它。</p>
 *
 * <p>1.21.1/NeoForge：发送从 {@code CHANNEL.sendToServer(...)} 改为
 * {@link PacketDistributor#sendToServer}。</p>
 */
public final class NetClient {

    private NetClient() {
    }

    /** 服务端连接上是否装了我们这条通道（ vanilla/未安装 时为 false）。 */
    private static boolean serverHasChannel() {
        try {
            var listener = Minecraft.getInstance().getConnection();
            if (listener == null) return false;
            // NeoForge 的对应物：查这条连接协商出来的 payload 集合里有没有我们的握手包
            // （protocol 传 null = 对所有阶段都查）
            return NetworkRegistry.hasChannel(listener.getConnection(), null, Net.HandshakeC2S.TYPE.id());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 面板打开时向服务端问一次前置/世界信息（服务端没装模组则静默跳过）。 */
    public static void requestHandshake() {
        try {
            if (!serverHasChannel()) {
                Net.serverWorlds = null;
                return;
            }
            PacketDistributor.sendToServer(Net.HandshakeC2S.INSTANCE);
        } catch (Throwable t) {
            Net.serverWorlds = null;
        }
    }

    /** 客户端发起：单人直接本地扫；多人发请求给服务端。 */
    public static void requestHeatmap(String dim, int cx, int cz, int half) {
        int requestId = PanelState.nextHeatRequestId();
        PanelState.heatRequestSentMs = System.currentTimeMillis();
        PanelState.heatNoServer = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            // 单人：后台线程直接扫本地存档，不走网络
            var localServer = mc.getSingleplayerServer();
            Thread t = new Thread(() -> {
                try {
                    Net.ScanResult r = Net.scanRegions(localServer, dim, cx, cz, half);
                    if (requestId == PanelState.heatRequestId) {
                        PanelState.heat = r.toData(dim, cx, cz, half);
                    }
                } catch (Throwable e) {
                    if (requestId == PanelState.heatRequestId) {
                        PanelState.log(PanelState.Level.WARN, Texts.t("log.heatScanFailed", e));
                    }
                }
            }, "ChunksmithPanel-Heatmap-SP");
            t.setDaemon(true);
            t.start();
            return;
        }
        // 多人：Forge 对未安装的通道不会抛异常，而是静默丢包 —— 必须主动探测，
        // 否则面板会永远停在“请求中”。探测不到就直接给出可读提示。
        if (!serverHasChannel()) {
            PanelState.heatNoServer = true;
            PanelState.log(PanelState.Level.WARN, Texts.t("log.noServerSide"));
            return;
        }
        try {
            PacketDistributor.sendToServer(new Net.HeatmapC2S(requestId, dim, cx, cz, half));
        } catch (Throwable t) {
            PanelState.heatNoServer = true;
            PanelState.log(PanelState.Level.WARN, Texts.t("log.heatSendFailed", t));
        }
    }
}
