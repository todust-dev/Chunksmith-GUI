package cn.blockforge.generated.chunksmithchunksmithgu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 前置检测：ChunkSmith（/cs 指令族）是否存在、版本是否匹配、玩家权限几级。
 *
 * 三层信号：
 *  1) 本地模组列表 —— 单机/整合包场景直接能看到 ChunkSmith 模组本体；
 *  2) 服务器命令树 —— 原版发给客户端的命令树已按权限过滤：树里有 cs/chunky
 *     节点即代表"服务端装了它"，能看到哪个子命令就代表玩家能用哪个；
 *  3) 实时聊天回流 —— 执行后服务器回 "Unknown command" 会立即把状态打回缺失。
 */
public final class PrereqStatus {

    /** 本地找到的前置模组（可能没有）。 */
    public static volatile String localModId = null;
    public static volatile String localModName = null;
    public static volatile String localVersion = null;

    /** 命令树是否暴露 /cs（或 /chunky）。登录 / 换服 / 定时刷新。 */
    public static volatile boolean commandTreeSeen = false;
    /** 服务端前置主命令名：cs 或 chunky。 */
    public static volatile String rootCommand = "cs";
    /** 选形状的子命令名：Chunksmith 叫 pattern，原版 Chunky 叫 shape（按命令树自动认）。 */
    public static volatile String shapeCommand = "pattern";
    /** 上次扫描/刷新时间，节流用。 */
    private static long lastTreeScanMs = 0;

    public enum Perm { UNKNOWN, READONLY, OPERATOR }
    public static volatile Perm perm = Perm.UNKNOWN;

    /** 运行期被服务器拒了一次（未知命令）——置灰危险按钮，直到重新探测成功。 */
    public static volatile boolean serverRejected = false;

    private PrereqStatus() {
    }

    /** 模组启动时扫一遍本地模组列表。 */
    public static void scan() {
        localModId = null;
        localModName = null;
        localVersion = null;
        try {
            ModList list = ModList.get();
            // 优先精确 id
            for (String id : new String[]{"chunksmith", "chunkysmith", "chunky"}) {
                Optional<? extends net.neoforged.fml.ModContainer> c = list.getModContainerById(id);
                if (c.isPresent()) {
                    IModInfo info = c.get().getModInfo();
                    localModId = info.getModId();
                    localModName = info.getDisplayName();
                    localVersion = info.getVersion().toString();
                    return;
                }
            }
            // 再模糊匹配名字
            List<IModInfo> fuzzy = new ArrayList<>();
            for (IModInfo info : list.getMods()) {
                String s = (info.getModId() + " " + info.getDisplayName()).toLowerCase(Locale.ROOT);
                if (s.contains("chunksmith") || s.contains("chunky")) fuzzy.add(info);
            }
            if (!fuzzy.isEmpty()) {
                IModInfo info = fuzzy.get(0);
                localModId = info.getModId();
                localModName = info.getDisplayName();
                localVersion = info.getVersion().toString();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 本地是否装着前置（单机场景的决定性信号）。 */
    public static boolean localPresent() {
        return localModId != null;
    }

    /**
     * 重新探测服务器命令树（1 秒节流）。返回缓存值。
     * 树里出现 cs/chunky 根节点 ⇒ 服务端存在前置；
     * 其子节点里有 start/trim/set ⇒ 当前玩家是管理级（树按权限过滤过）。
     *
     * <p>只认客户端连接，所以标 {@code @OnlyIn(Dist.CLIENT)}：专用服务器加载本类时
     * NeoForge 的 RuntimeDistCleaner 会把整个方法删掉，服务端不会因为这里引用
     * {@code Minecraft} 而报 “invalid dist DEDICATED_SERVER”。</p>
     */
    @OnlyIn(Dist.CLIENT)
    public static synchronized boolean refreshTree() {
        long now = System.currentTimeMillis();
        if (now - lastTreeScanMs < 1000) return commandTreeSeen;
        lastTreeScanMs = now;
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientPacketListener con = mc.getConnection();
            if (con == null) {
                commandTreeSeen = false;
                perm = Perm.UNKNOWN;
                return false;
            }
            var d = con.getCommands();
            var cs = d.getRoot().getChild("cs");
            if (cs == null) cs = d.getRoot().getChild("chunky");
            if (cs == null) cs = d.getRoot().getChild("chunksmith");
            commandTreeSeen = cs != null;
            if (cs != null) {
                rootCommand = cs.getName();
                // 形状子命令在 Chunksmith 里叫 pattern、在 Chunky 里叫 shape，按实际命令树认
                if (cs.getChild("pattern") != null) shapeCommand = "pattern";
                else if (cs.getChild("shape") != null) shapeCommand = "shape";
                // 管理类子命令可见 ⇒ 至少 OP
                perm = (cs.getChild("start") != null || cs.getChild("trim") != null) ? Perm.OPERATOR : Perm.READONLY;
                serverRejected = false;
            } else {
                perm = Perm.UNKNOWN;
            }
        } catch (Throwable t) {
            commandTreeSeen = false;
        }
        return commandTreeSeen;
    }

    /** 面板可用的“前置是否到位”总判定。客户端专属。 */
    @OnlyIn(Dist.CLIENT)
    public static boolean prereqOk() {
        refreshTree();
        if (serverRejected) return false;
        if (commandTreeSeen) return true;
        // 命令树还没同步（单人刚开局等）时，本地装着也算通过
        return localPresent();
    }

    /** 玩家能不能执行管理类指令（开始/暂停/清理/设置）。客户端专属。 */
    @OnlyIn(Dist.CLIENT)
    public static boolean isOperator() {
        refreshTree();
        if (perm == Perm.OPERATOR) return true;
        Minecraft mc = Minecraft.getInstance();
        // 单人且开了作弊：本地就是权威，允许尝试（服务器仍会兜底校验）
        return mc.isSingleplayer() && mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isCommandBlockEnabled();
    }

    /** 版本匹配检查：本地版本 vs 服务器握手回报版本，主版本号一致算匹配。 */
    public static volatile String serverVersion = null;
    public static boolean versionMismatch() {
        String a = major(localVersion), b = major(serverVersion);
        return a != null && b != null && !a.equals(b);
    }
    private static String major(String v) {
        if (v == null) return null;
        String s = v.trim();
        int i = 0;
        while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
        s = s.substring(0, i);
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : (s.isEmpty() ? null : s);
    }
}
