package cn.blockforge.generated.chunksmithchunksmithgu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
    /** 服务端命令树已同步到位（只是可能不含 cs 节点）。区别于"还没同步完"。 */
    public static volatile boolean commandTreeSynced = false;
    /** 服务端前置主命令名：cs 或 chunky。 */
    public static volatile String rootCommand = "cs";
    /** 选形状的子命令名：Chunksmith 叫 pattern，原版 Chunky 叫 shape（按命令树自动认）。 */
    public static volatile String shapeCommand = "pattern";
    /** 命令树里可见的子命令集合（按权限过滤后）。用于发送前的存在性校验。 */
    public static volatile Set<String> visibleSubcommands = Set.of();
    /** 上次扫描/刷新时间，节流用。 */
    private static long lastTreeScanMs = 0;

    public enum Perm { UNKNOWN, READONLY, OPERATOR }
    public static volatile Perm perm = Perm.UNKNOWN;

    /** 运行期被服务器拒了一次（未知命令）——置灰危险按钮，直到重新探测成功。 */
    public static volatile boolean serverRejected = false;

    /** 生成页会用到的子命令；命令树同步后缺哪个就说明服务端不支持。 */
    private static final String[] REQUIRED_SUBS = {"world", "start", "radius", "center"};
    /** 清理页要用的子命令：不需要 start，也不需要 pattern（范围靠 center/radius 表达）。 */
    private static final String[] REQUIRED_SUBS_TRIM = {"world", "trim", "radius", "center"};

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
            // 再模糊匹配名字。必须排除本模组自己：我们的 id/显示名里也含 "chunksmith"，
            // 不排除的话前置没装时会把自己当前置，面板谎报“就绪”。
            // 排序保证确定性：modId 含关键字的优先，其次按 modId 字典序。
            List<IModInfo> fuzzy = new ArrayList<>();
            for (IModInfo info : list.getMods()) {
                if (info.getModId().equals(ChunkSmithGuiMod.MOD_ID)) continue;
                String s = (info.getModId() + " " + info.getDisplayName()).toLowerCase(Locale.ROOT);
                if (s.contains("chunksmith") || s.contains("chunky")) fuzzy.add(info);
            }
            fuzzy.sort(Comparator
                    .comparing((IModInfo i) -> {
                        String id = i.getModId().toLowerCase(Locale.ROOT);
                        return id.contains("chunksmith") ? 0 : (id.contains("chunky") ? 1 : 2);
                    })
                    .thenComparing(IModInfo::getModId));
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
                commandTreeSynced = false;   // 断开连接：下一服重新等命令树同步
                perm = Perm.UNKNOWN;
                return false;
            }
            var d = con.getCommands();
            // 显式判空：命令树还没同步时直接返回，不靠异常兜底（异常会掩盖别的问题）
            if (d == null) {
                commandTreeSeen = false;
                visibleSubcommands = Set.of();
                return false;
            }
            // 拿到过命令树本体 = 同步已完成；之后还看不见 cs 节点就是"服务端真没装"，
            // 不再是"还在同步"（状态条用它区分这两种多人场景）
            commandTreeSynced = true;
            var cs = d.getRoot().getChild("cs");
            if (cs == null) cs = d.getRoot().getChild("chunky");
            if (cs == null) cs = d.getRoot().getChild("chunksmith");
            commandTreeSeen = cs != null;
            if (cs != null) {
                rootCommand = cs.getName();
                Set<String> subs = new HashSet<>();
                for (var child : cs.getChildren()) subs.add(child.getName());
                visibleSubcommands = subs;
                // 形状子命令在 Chunksmith 里叫 pattern、在 Chunky 里叫 shape，按实际命令树认
                if (subs.contains("pattern")) shapeCommand = "pattern";
                else if (subs.contains("shape")) shapeCommand = "shape";
                // 管理类子命令可见 ⇒ 至少 OP
                perm = (subs.contains("start") || subs.contains("trim")) ? Perm.OPERATOR : Perm.READONLY;
                serverRejected = false;
            } else {
                visibleSubcommands = Set.of();
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
        // 结果缓存 250ms（报告 P2-10）：面板开着时每帧都要问一次，别每次都去 synchronized 探树
        long now = System.currentTimeMillis();
        if (now - lastOkMs < OK_CACHE_MS) return cachedOk;
        lastOkMs = now;
        cachedOk = computePrereqOk();
        return cachedOk;
    }

    private static final long OK_CACHE_MS = 250;
    private static long lastOkMs = 0;
    private static volatile boolean cachedOk = false;

    @OnlyIn(Dist.CLIENT)
    private static boolean computePrereqOk() {
        refreshTree();
        if (serverRejected) return false;
        if (commandTreeSeen) return true;
        // “本地装着也算”只对单人成立（报告 P1-5）：单人本地有前置 = 服务端（本城）就有。
        // 多人必须等命令树同步确认——否则客户端装了前置、服务端没装时，
        // 面板会在命令树缺席的窗口里谎报“就绪”并把指令发出去。
        try {
            if (Minecraft.getInstance().isSingleplayer()) return localPresent();
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 树/拒绝状态发生变化时让缓存立刻失效（握手、"未知命令" 等路径调用）。 */
    public static void invalidateOkCache() {
        lastOkMs = 0;
    }

    /**
     * 玩家能不能执行管理类指令（开始/暂停/清理/设置）。客户端专属。
     *
     * <p>多人时客户端拿不到权威 OP 等级，但命令树是服务端按权限过滤后下发的：
     * 树里能看到 start/trim 就说明能用。单人且树还没同步时，直接问本城内服
     * 这个玩家的 OP 等级（≥2 算管理），不再用“命令方块是否启用”冒充权限判定。</p>
     */
    @OnlyIn(Dist.CLIENT)
    public static boolean isOperator() {
        refreshTree();
        if (perm == Perm.OPERATOR) return true;
        if (perm == Perm.READONLY) return false;   // 树里看不到管理类子命令 = 没权限
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.isSingleplayer() && mc.getSingleplayerServer() != null && mc.player != null) {
                return mc.getSingleplayerServer().getProfilePermissions(mc.player.getGameProfile()) >= 2;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 命令树里确认缺失的子命令（返回可读的中文提示；无问题返回 null）。客户端专属。 */
    @OnlyIn(Dist.CLIENT)
    public static String missingSubcommands() {
        return missingSubcommands(REQUIRED_SUBS, true);
    }

    /**
     * 清理（trim）要用的那份检查：需要 world / trim / radius / center，
     * <b>不需要 start</b>，也不需要 pattern——以前两处共用同一份全局清单
     * {world, start, radius, center}，服务端哪怕只是 start 没暴露，清理页也会
     * 拿“缺少 /cs start”把人拦下来。
     */
    @OnlyIn(Dist.CLIENT)
    public static String missingSubcommandsForTrim() {
        return missingSubcommands(REQUIRED_SUBS_TRIM, false);
    }

    private static String missingSubcommands(String[] required, boolean needShape) {
        if (!commandTreeSeen) return null;   // 树没同步时不下结论，交给服务端兜底
        Set<String> subs = visibleSubcommands;
        if (subs.isEmpty()) return null;
        List<String> missing = new ArrayList<>();
        for (String s : required) {
            if (!subs.contains(s)) missing.add("/" + rootCommand + " " + s);
        }
        if (needShape && missing.isEmpty()
                && !subs.contains(shapeCommand) && !subs.contains("pattern") && !subs.contains("shape")) {
            missing.add("/" + rootCommand + " " + shapeCommand);
        }
        return missing.isEmpty() ? null : String.join("、", missing);
    }

    /** 版本匹配检查：本地版本 vs 服务器握手回报版本，主版本号一致算匹配。 */
    public static volatile String serverVersion = null;
    public static boolean versionMismatch() {
        String a = major(localVersion), b = major(serverVersion);
        return a != null && b != null && !a.equals(b);
    }

    // （报告 P2-12）状态条每帧都要问版本是否不一致：两个版本串没变就直接给缓存值，
    // 不再每帧重新做字符串解析。
    private static String mmA = null, mmB = null;
    private static boolean mmCached = false;
    public static boolean versionMismatchCached() {
        if (!java.util.Objects.equals(mmA, localVersion) || !java.util.Objects.equals(mmB, serverVersion)) {
            mmA = localVersion; mmB = serverVersion;
            mmCached = versionMismatch();
        }
        return mmCached;
    }
    private static String major(String v) {
        if (v == null) return null;
        String s = v.trim();
        // 去掉 v1.2.3 这类前缀，也跳过构建号后缀前的非数字部分
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        s = s.substring(i);
        int j = 0;
        while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
        s = s.substring(0, j);
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : (s.isEmpty() ? null : s);
    }
}
