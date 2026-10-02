package cn.blockforge.generated.chunksmithchunksmithgu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 面板的全局状态中心：表单参数、任务进度、日志缓冲、热力图缓存、备份记录。
 * 全部静态访问；网络线程写入的字段一律 volatile / 同步块保护。
 */
public final class PanelState {

    private PanelState() {
    }

    // ---------- 表单（生成页） ----------
    public static volatile String world = "world";
    public static volatile String shape = "square";     // square circle oval diamond triangle star
    public static volatile String centerMode = "here";  // here spawn coords
    public static volatile int centerX = 0;
    public static volatile int centerZ = 0;
    public static volatile int radius = 500;
    public static volatile int radius2 = 0;             // 星形/椭圆的第二半径，0=不用

    // ---------- 清理页（trim） ----------
    public static volatile boolean trimConfirmed = false;
    public static volatile int trimExtra = 0; // 额外保留半径（区块）

    // 服务端装了前置但客户端没装时，用户可手动忽略本地检测
    public static volatile boolean forceIgnorePrereq = false;

    // ---------- 任务状态 ----------
    public enum Task { NONE, RUNNING, PAUSED, DONE, FAILED }
    public static volatile Task task = Task.NONE;
    public static volatile String taskRawStatus = "";   // 最近一条原始状态文本（解析失败也有东西看）
    public static volatile double percent = -1;         // -1 = 未知
    public static volatile long processed = -1, total = -1;
    public static volatile double rate = -1;            // chunks/s
    public static volatile long etaMs = -1;
    public static volatile long elapsedMs = -1;
    public static volatile int errors = 0;
    public static volatile int warnings = 0;
    public static volatile long lastProgressMs = 0;
    /** 上一次由本面板发出指令的时间——只有它才代表“接下来的回包属于面板”。 */
    public static volatile long lastCommandSentMs = 0;
    public static volatile long startedAtMs = 0;
    public static volatile long completeFlashUntilMs = 0;

    /** 本地推算的耗时：从首次看到运行状态开始；没有任务时归零，结束/取消后不再增长。 */
    public static long elapsedNow() {
        if (task == Task.NONE) return 0;
        if (elapsedMs >= 0) return elapsedMs;
        return startedAtMs > 0 ? System.currentTimeMillis() - startedAtMs : 0;
    }

    /** 把一个任务状态彻底清回“空闲”，耗时/进度/速度一并复位。 */
    public static synchronized void clearTask() {
        task = Task.NONE;
        percent = -1;
        processed = -1;
        total = -1;
        rate = -1;
        etaMs = -1;
        elapsedMs = -1;
        startedAtMs = 0;
        taskRawStatus = "";
        completeFlashUntilMs = 0;
    }

    /** 任务完成：进度补满、耗时冻结、剩余清零。 */
    public static synchronized void finishTask() {
        long now = System.currentTimeMillis();
        task = Task.DONE;
        percent = 100;
        // 前置最后一条进度常常停在 97% 就直接说“没有任务了”，收尾时把数量补满
        if (total > 0) {
            if (processed < total) processed = total;
        } else if (processed >= 0) {
            total = processed;
        }
        etaMs = 0;
        if (elapsedMs < 0 && startedAtMs > 0) elapsedMs = now - startedAtMs;
        completeFlashUntilMs = now + 2500;
    }

    // ---------- 日志聚合 ----------
    public enum Level { CMD, INFO, WARN, ERROR, OK }
    public static final class Entry {
        public final long time; public final Level level; public final String text;
        public Entry(long t, Level l, String s) { time = t; level = l; text = s; }
        @Override public String toString() { return text; }
    }
    private static final ArrayDeque<Entry> LOG = new ArrayDeque<>();
    public static synchronized void log(Level lv, String text) {
        LOG.addLast(new Entry(System.currentTimeMillis(), lv, text));
        while (LOG.size() > 500) LOG.removeFirst();
    }
    public static synchronized List<Entry> logSnapshot() {
        List<Entry> l = new ArrayList<>(LOG);
        Collections.reverse(l); // 新的在上
        return l;
    }
    public static synchronized void clearLog() { LOG.clear(); }

    /** 全部日志导出文本（新的在上）。 */
    public static synchronized String logDump() {
        StringBuilder sb = new StringBuilder();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss");
        List<Entry> copy = new ArrayList<>(LOG);
        Collections.reverse(copy);
        for (Entry e : copy) {
            sb.append('[').append(fmt.format(new java.util.Date(e.time))).append("] ")
              .append(e.level).append(" | ").append(e.text).append('\n');
        }
        return sb.toString();
    }

    public static synchronized String logErrorDump() {
        StringBuilder sb = new StringBuilder();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss");
        List<Entry> copy = new ArrayList<>(LOG);
        Collections.reverse(copy);
        for (Entry e : copy) {
            if (e.level == Level.WARN || e.level == Level.ERROR) {
                sb.append('[').append(fmt.format(new java.util.Date(e.time))).append("] ")
                  .append(e.level).append(" | ").append(e.text).append('\n');
            }
        }
        return sb.toString();
    }

    /** set 历史文本（key / 旧值 → 新值）。 */
    public static synchronized String setsDump() {
        StringBuilder sb = new StringBuilder(Texts.t("dump.setsHeader") + '\n');
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (SetRec r : SETS) {
            sb.append(fmt.format(new java.util.Date(r.time))).append("  ")
              .append(r.key).append(": ").append(r.prev == null ? Texts.t("dump.setInitial") : r.prev)
              .append(" -> ").append(r.value).append('\n');
        }
        return sb.toString();
    }

    // ---------- 热力图 ----------
    public static final class HeatData {
        public final Set<Long> chunks;   // (x<<32)|z 已存在于存档的区块
        public final Set<Long> errors;   // 从日志提取的异常区块坐标（尽力而为）
        public final String dim;
        public final int spawnX, spawnZ;
        public final int halfWindow;
        public final boolean truncated;
        public final long receivedMs;
        public HeatData(Set<Long> chunks, String dim, int sx, int sz, int half, boolean trunc, long recv) {
            this.chunks = chunks; this.errors = Collections.synchronizedSet(new HashSet<>());
            this.dim = dim; this.spawnX = sx; this.spawnZ = sz;
            this.halfWindow = half; this.truncated = trunc; this.receivedMs = recv;
        }
    }
    public static volatile HeatData heat = null;
    public static volatile long heatRequestSentMs = 0;
    /** 每次拉取递增，防止较早的异步回包覆盖较新的热力图。 */
    public static volatile int heatRequestId = 0;

    public static synchronized int nextHeatRequestId() {
        return ++heatRequestId;
    }
    public static volatile boolean heatNoServer = false; // 发送失败/超时 => 服务端没装本模组

    // ---------- /cs set 历史与备份 ----------
    public static final class SetRec {
        public final long time; public final String key; public final String value; public String prev;
        public SetRec(long t, String k, String v, String p) { time = t; key = k; value = v; prev = p; }
    }
    private static final List<SetRec> SETS = new ArrayList<>();
    public static synchronized void recordSet(String key, String value) {
        String prev = null;
        for (int i = SETS.size() - 1; i >= 0; i--) {
            if (SETS.get(i).key.equals(key)) { prev = SETS.get(i).value; break; }
        }
        SETS.add(new SetRec(System.currentTimeMillis(), key, value, prev));
        while (SETS.size() > 200) SETS.remove(0);
    }
    public static synchronized List<SetRec> setsSnapshot() { return new ArrayList<>(SETS); }
    /** 回滚最近一次 set：恢复为该键的上一已知值。返回 true 表示有可回滚记录。 */
    public static synchronized String[] popRollback() {
        for (int i = SETS.size() - 1; i >= 0; i--) {
            SetRec r = SETS.get(i);
            if (r.prev != null) {
                SETS.remove(i);
                return new String[]{r.key, r.prev};
            }
        }
        return null;
    }
    public static synchronized void clearSets() { SETS.clear(); }

    /** 打包区块坐标为 long。 */
    public static long pos(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
}
