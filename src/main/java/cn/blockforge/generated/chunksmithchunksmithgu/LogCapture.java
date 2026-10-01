package cn.blockforge.generated.chunksmithchunksmithgu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天回流捕获：把 Chunksmith 的指令回包收进面板日志，并解析成任务状态。
 * 解析全部是容错正则——解析不到就保留原文展示，绝不臆造数字。
 *
 * 回包文案直接取自 Chunksmith 的前身 Chunky 的 lang（en.json / zh_CN.json），
 * Chunksmith 沿用同一套翻译键（task_update / task_done / task_stopped）：
 *
 *   运行中 EN: Task running for &lt;world&gt;. Processed: 2623 chunks (62.08%),
 *              ETA: 0:00:45, Rate: 35.4 cps, Current: -34, -35
 *   运行中 ZH: 任务运行于世界 &lt;world&gt;。 处理了 2623 个区块 (62.08%),
 *              预计距完成还有: 0:00:45, 速率: 35.4 区块/秒, 当前区块进度: -34, -35
 *   已完成 EN: Task finished for &lt;world&gt;. Processed: 2623 chunks (97.11%), Total time: 0:05:00
 *   已完成 ZH: 任务结束于 &lt;world&gt;。 处理了 2623 个区块 (97.11%), 总运行时长: 0:05:00
 *   没任务 EN: No tasks running.     ZH: 没有在运行的任务。
 *   取消完 EN: Task cancelled for &lt;world&gt;.   ZH: 任务取消于 &lt;world&gt;。
 *   暂停中 EN: Task paused for &lt;world&gt;.      ZH: 任务暂停了 &lt;world&gt;。
 *
 * 两个必须专门认的点：
 *   1. 完成回包里的百分比常常是 97.xx（前置按自己的配额算 100%，玩家看到 97%），
 *      所以“已完成”这一行要无条件补满，不能拿这行的百分比去覆盖。
 *   2. 中文完成文案里没有“完成”二字，只写“任务结束于 … 总运行时长: …”，
 *      只认英文关键词会让中文客户端永远卡在“正在生成”。
 *
 * <p>1.21.1/NeoForge：事件类改为 {@code net.neoforged.neoforge.client.event.ClientChatReceivedEvent}
 * （{@code getMessage()} 不变），事件总线改为 {@link NeoForge#EVENT_BUS}。</p>
 */
public final class LogCapture {

    public static final LogCapture INSTANCE = new LogCapture();

    /** 最近 6 秒内我们发过指令 ⇒ 期间的系统回包基本都属于面板。 */
    private static final long REPLY_WINDOW_MS = 6000;

    private static final Pattern P_PCT = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)\\s*%");
    // EN "Processed: 2623 chunks" / ZH "处理了 2623 个区块"（Chunky 完成行用的就是“处理了”）
    private static final Pattern P_PROCESSED = Pattern.compile("(?i)(?:processed|已处理|已生成|处理了|已处理了)[^0-9]{0,4}(\\d[\\d,.]*)");
    // 备用：其它版本的 "1234/5000 chunks" 或 "1234 of 5000"
    private static final Pattern P_COUNT = Pattern.compile("(\\d[\\d,.]*)\\s*(?:/|\\bof\\b|of)\\s*(\\d[\\d,.]*)");
    // "35.4 cps" / "12.3 chunks/s" / "12 区块/秒"
    private static final Pattern P_RATE = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:chunks?/s|c/s|cps|区块/秒)");
    // ETA: 0:00:45 / ETA: 1:02:03 / 预计距完成还有: 0:00:45（中文夹着“距完成还有: ”，位数放宽）
    private static final Pattern P_ETA_CLOCK = Pattern.compile("(?i)(?:eta|剩余|预计)[^0-9]{0,16}(?:(\\d+):)?(\\d{1,2}):(\\d{2})");
    // 备用：ETA: 1h 2m 3s
    private static final Pattern P_ETA_TEXT = Pattern.compile("(?i)(?:eta|剩余|预计)[^0-9]*?(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?");

    private LogCapture() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.register(INSTANCE);
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        try { handle(e.getMessage()); } catch (Throwable ignored) { }
    }

    private void handle(Component comp) {
        String text = stripCodes(comp.getString()).trim();
        if (text.isEmpty()) return;
        String lower = text.toLowerCase(Locale.ROOT);

        boolean mine = lower.contains("chunky") || lower.contains("chunksmith") || lower.contains("cs ")
                || lower.contains("chunk") || lower.contains("区块") || isReplyFresh();
        if (!mine) return;

        boolean cancelish = lower.contains("cancel") || lower.contains("取消")
                || lower.contains("confirm") || lower.contains("确认");
        // “没有一个在跑的任务”同时是“跑完了”和“从没开始”的回包
        boolean noTask = isNoTask(lower, text);
        // 完成回包（task_done）与取消完成回包（format_cancel）要分开处理
        boolean taskDone = !cancelish && isTaskDone(lower, text);
        boolean cancelDone = isCancelDone(lower, text);

        PanelState.Level lv = PanelState.Level.INFO;
        if (lower.contains("error") || lower.contains("failed") || lower.contains("exception")
                || lower.contains("失败") || lower.contains("错误") || lower.contains("unknown command")) {
            lv = PanelState.Level.ERROR;
        } else if (cancelish || lower.contains("pause") || lower.contains("warn")
                || lower.contains("暂停") || lower.contains("警告") || lower.contains("注意")) {
            lv = PanelState.Level.WARN;
        } else if (taskDone || noTask) {
            lv = PanelState.Level.OK;
        }

        if (lv == PanelState.Level.ERROR) PanelState.errors++;
        if (lv == PanelState.Level.WARN) PanelState.warnings++;

        // —— 服务器拒绝识别 ——
        if (lower.contains("unknown command") || lower.contains("未知命令")) {
            PrereqStatus.serverRejected = true;
            PrereqStatus.commandTreeSeen = false;
        }
        if (lower.contains("does not have permission") || lower.contains(" insufficient permission")) {
            PrereqStatus.perm = PrereqStatus.Perm.READONLY;
        } else if (!PrereqStatus.commandTreeSeen && lv != PanelState.Level.ERROR) {
            // 有任何正常回包说明前置在跑
            PrereqStatus.serverRejected = false;
        }

        // 只有真正的进度行才允许改数字。Chunksmith 每次 /cs progress 在进度行后面还会跟一条
        // “I/O throttle active - concurrency: 22/200”，里面的 22/200 不是区块数；背压通知里
        // 还有 “43% of the heap” 这种百分比。它们进日志可以，进进度条就会把三项全带偏。
        boolean progressLine = isProgressLine(lower, text) && !isNoticeLine(lower);

        // 先解析数字，再按结果决定状态机：完成回包不能被后面的百分比再顶回“正在生成”
        parseProgress(lower, text, taskDone, progressLine);

        // 取消完成：服务端已经不跑了，本地要跟着回到空闲，否则面板会一直挂着“正在生成”。
        // 只有真正的进度行 / 明确的“没有任务”回包才能判定完成——否则回复窗口期内路人的
        // 一句普通聊天（碰巧含 done / 完成）就会把任务标成已完成，轮询随之停掉，总进度看起来就冻住了。
        if (cancelDone) {
            cancelNow();
        } else if (lv == PanelState.Level.OK && !cancelish && (progressLine || noTask)
                && (PanelState.task == PanelState.Task.RUNNING || PanelState.task == PanelState.Task.PAUSED)) {
            completeNow();
        }

        // 同一行原文别重复刷。限流/背压这类通知不覆盖「原始回包」，
        // 否则状态框末尾永远挂着 “concurrency: 22/200”，看着像区块进度。
        boolean pausedLine = lower.contains("pause") || lower.contains("暂停");
        boolean meaningful = progressLine || taskDone || noTask || cancelish || pausedLine
                || lv != PanelState.Level.INFO;
        if (meaningful && !text.equals(PanelState.taskRawStatus)) {
            PanelState.taskRawStatus = text;
        }
        PanelState.log(lv, text);
    }

    /** 播报完成：补满进度、冻结耗时、响一声。 */
    private static void completeNow() {
        boolean wasRunning = PanelState.task == PanelState.Task.RUNNING;
        PanelState.finishTask();
        Minecraft mc = Minecraft.getInstance();
        if (wasRunning && mc.player != null && Settings.sounds) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.4F));
        }
    }

    /** 取消完成：清空本地任务状态，但保留最后一条原文让用户看得见服务端说了什么。 */
    private static void cancelNow() {
        String keep = PanelState.taskRawStatus;
        PanelState.clearTask();
        if (keep != null && !keep.isBlank()) PanelState.taskRawStatus = keep;
    }

    /**
     * “没有任务在跑”的回包。中英文都要认：
     *   EN: No tasks running. / No tasks to cancel. / No tasks to continue.
     *   ZH: 没有在运行的任务。/ 没有可取消的任务。/ 没有可再次运行的加载任务。/ 没有预生成任务可以暂停
     * 中文这些句子没有一个含“没有任务”连写，所以要看“没有”和“任务”同时出现。
     */
    private static boolean isNoTask(String lower, String raw) {
        return lower.contains("no task") || lower.contains("no tasks")
                || lower.contains("not running")
                || lower.contains("没有正在运行")
                || (raw.contains("没有") && raw.contains("任务"));
    }

    /**
     * 任务正常跑完（Chunky/Chunksmith 的 task_done）。中文文案里没有“完成”二字：
     *   EN: Task finished for &lt;world&gt;. Processed: N chunks (P%), Total time: H:MM:SS
     *   ZH: 任务结束于 &lt;world&gt;。 处理了 N 个区块 (P%), 总运行时长: H:MM:SS
     */
    private static boolean isTaskDone(String lower, String raw) {
        return lower.contains("task finished") || lower.contains("finished for")
                || lower.contains("total time")
                || raw.contains("任务结束") || raw.contains("总运行时长")
                || lower.contains("complete") || lower.contains("done")
                || raw.contains("完成") || raw.contains("结束");
    }

    /**
     * 取消已经生效（不是“请输入 /cs confirm”那句提示，也不是“没有可取消的任务”）。
     *   EN: Task cancelled for &lt;world&gt;. / Cancelling all tasks.
     *   ZH: 任务取消于 &lt;world&gt;。/ 正在中止所有任务。
     */
    private static boolean isCancelDone(String lower, String raw) {
        if (lower.contains("no task") || raw.contains("没有")) return false;
        return lower.contains("cancelled for") || lower.contains("cancelling all tasks")
                || lower.contains("cancel all")
                || raw.contains("任务取消于") || raw.contains("中止所有任务");
    }

    /**
     * 真正的任务进度行。Chunksmith / Chunky 的进度文案固定带 “Processed:”（英文）
     * 或 “处理了 / 已处理”（中文）：
     *   EN: Task running for X. Processed: N chunks (P%), ETA: …, Rate: … cps, …
     *   ZH: 任务运行于世界 X。 处理了 N 个区块 (P%), 预计距完成还有: …
     * 限流、背压、校验失败、LOD 汇总这些通知都没有这个特征词，但它们同样带
     * “22/200”“43%”之类的数字，所以必须先分辨行类型，再决定要不要解析数字。
     */
    private static boolean isProgressLine(String lower, String raw) {
        return lower.contains("processed:") || raw.contains("处理了") || raw.contains("已处理");
    }

    /**
     * 已知的非进度通知行（限流 / 背压 / 常驻 / 堆内存 / 校验 / LOD 汇总）。
     * 进度行本身不会出现这些词，所以它们是一道“防止今后新增文案再混进来”的保险。
     */
    private static boolean isNoticeLine(String lower) {
        return lower.contains("throttle") || lower.contains("backpressure")
                || lower.contains("concurrency") || lower.contains("heap")
                || lower.contains("residency") || lower.contains("verification")
                || lower.contains("lod for");
    }

    private void parseProgress(String lower, String raw, boolean doneLine, boolean progressLine) {
        // 取消 / 确认这条链路里的回包不能被当成“还在跑”（回包常带着进度百分比）
        boolean cancelish = lower.contains("cancel") || lower.contains("取消")
                || lower.contains("confirm") || lower.contains("确认");
        boolean noTask = isNoTask(lower, raw);
        boolean paused = lower.contains("pause") || lower.contains("paused") || lower.contains("暂停");

        // 先把这一条里能认出来的数字全解析出来，再统一决定状态机怎么走。
        // 非进度行一律不碰数字：限流通知里的 “22/200”、背压通知里的 “43%” 都不是区块进度。
        double pct = -1, rate = -1;
        long proc = -1, totalPair = -1, eta = -1;

        if (progressLine) {
            Matcher m = P_PCT.matcher(raw);
            if (m.find()) pct = Double.parseDouble(m.group(1));

            m = P_PROCESSED.matcher(raw);
            if (m.find()) proc = parseNum(m.group(1));
            // 备用格式只在确认是区块 / 区域行时才用，杜绝 “5 of 200 samples” 之类的误命中
            if (proc < 0 && (lower.contains("chunk") || lower.contains("region")
                    || raw.contains("区块") || raw.contains("区域"))) {
                m = P_COUNT.matcher(raw);
                if (m.find()) {
                    long a = parseNum(m.group(1)), b = parseNum(m.group(2));
                    if (a >= 0 && b > 0) { proc = a; totalPair = b; }
                }
            }

            m = P_RATE.matcher(raw);
            if (m.find()) rate = Double.parseDouble(m.group(1));

            m = P_ETA_CLOCK.matcher(raw);
            if (m.find()) {
                eta = clockMs(num(m.group(1)), num(m.group(2)), num(m.group(3)));
            } else {
                m = P_ETA_TEXT.matcher(raw);
                if (m.find() && (m.group(1) != null || m.group(2) != null || m.group(3) != null)) {
                    eta = clockMs(num(m.group(1)), num(m.group(2)), num(m.group(3)));
                }
            }
        }

        boolean hasNumbers = progressLine && (pct >= 0 || proc >= 0);
        long now = System.currentTimeMillis();

        if (PanelState.task == PanelState.Task.DONE) {
            // 完成后的余包不能把补满的进度顶回去；只有安静几秒后又冒出进度，才当成新任务
            if (!(hasNumbers && now > PanelState.completeFlashUntilMs + 3000)) return;
            PanelState.task = PanelState.Task.RUNNING;
            PanelState.startedAtMs = now;
            PanelState.elapsedMs = -1;
            PanelState.percent = -1;
            PanelState.processed = -1;
            PanelState.total = -1;
        }

        // 完成回包（task_done）单独收尾：这里的百分比往往停在 97.xx，绝不能拿它当最终进度
        if (doneLine && progressLine) {
            if (PanelState.task == PanelState.Task.NONE) {
                // 前置自己续跑的任务没被面板看到就已经跑完，也要正确亮一次“已完成”
                PanelState.task = PanelState.Task.RUNNING;
                PanelState.startedAtMs = now;
                PanelState.elapsedMs = -1;
            }
            if (proc >= 0) PanelState.processed = proc;
            if (totalPair > 0) PanelState.total = totalPair;
            if (pct > 0 && proc >= 0) {
                long total = Math.round(proc * 100.0 / pct);
                if (total >= proc) PanelState.total = total;
            }
            if (rate >= 0) PanelState.rate = rate;
            PanelState.percent = 100;
            PanelState.etaMs = 0;
            PanelState.lastProgressMs = now;
            return;   // 剩下的收尾交给 handle() 里的 completeNow()
        }

        if (pct >= 0) PanelState.percent = pct;
        if (proc >= 0) PanelState.processed = proc;
        if (totalPair > 0) PanelState.total = totalPair;
        // Chunksmith 只报“已处理 + 百分比”，总量由两者反推（四舍五入，误差极小）
        if (pct > 0 && proc >= 0) {
            long total = Math.round(proc * 100.0 / pct);
            if (total >= proc) PanelState.total = total;
        }
        if (rate >= 0) PanelState.rate = rate;
        if (eta >= 0) PanelState.etaMs = eta;

        if (hasNumbers || rate >= 0 || eta >= 0) PanelState.lastProgressMs = now;

        if (cancelish || noTask) return;
        if (paused) {
            if (PanelState.task == PanelState.Task.RUNNING) PanelState.task = PanelState.Task.PAUSED;
            return;
        }
        // 本地以为没任务、但服务端报来了进度 ⇒ 例如前置自己续跑了上次的任务
        if (PanelState.task == PanelState.Task.NONE && hasNumbers) {
            PanelState.task = PanelState.Task.RUNNING;
            PanelState.startedAtMs = now;
            PanelState.elapsedMs = -1;
            PanelState.completeFlashUntilMs = 0;
        }
        // 进度已经到头就直接收工，别等 “No tasks running.”（只认真正的进度行，防止被通知行里的数字误判）
        if (progressLine && PanelState.task == PanelState.Task.RUNNING
                && (pct >= 100 || (proc >= 0 && PanelState.total > 0 && proc >= PanelState.total))) {
            completeNow();
        }
    }

    private static long clockMs(long h, long m, long s) {
        return (h * 3600 + m * 60 + s) * 1000;
    }

    private static long parseNum(String s) {
        try { return Long.parseLong(s.replace(",", "").replace(".", "")); } catch (NumberFormatException e) { return -1; }
    }
    private static long num(String s) { return s == null ? 0 : Long.parseLong(s); }

    private static String stripCodes(String s) {
        return s.replaceAll("(?i)§[0-9a-fk-or]", "");
    }

    private static boolean isReplyFresh() {
        return System.currentTimeMillis() - PanelState.lastCommandSentMs < REPLY_WINDOW_MS;
    }

    /** 供面板聚合复制：最近 n 条。 */
    public static String dumpRecent(int n) {
        List<PanelState.Entry> l = PanelState.logSnapshot();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, l.size()); i++) sb.append(l.get(i).text).append('\n');
        return sb.toString();
    }
}
