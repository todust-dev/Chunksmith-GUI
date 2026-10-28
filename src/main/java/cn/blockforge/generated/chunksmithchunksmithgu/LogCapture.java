package cn.blockforge.generated.chunksmithchunksmithgu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天回流捕获：把 Chunksmith 的指令回包收进面板日志，并解析成任务状态。
 * 解析全部是容错正则——解析不到就保留原文展示，绝不臆造数字。
 *
 * <p>只处理 <b>系统消息</b>：玩家聊天消息一定带 sender UUID，直接跳过。
 * 否则任务运行时玩家一句 “done” / “我没有任务” 就能把面板状态机带乱。</p>
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

    /**
     * 最近 2.5 秒内我们发过指令 ⇒ 期间的系统回包基本都属于面板。
     * 窗口刻意小于默认自动刷新间隔（5 秒），避免任务运行期窗口常驻、把无关系统消息
     * （进服提示、别的模组的播报）也当成面板回包。
     */
    private static final long REPLY_WINDOW_MS = 2500;

    private static final Pattern P_PCT = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)\\s*%");
    // EN "Processed: 2623 chunks" / ZH "处理了 2623 个区块"（Chunky 完成行用的就是“处理了”）
    private static final Pattern P_PROCESSED = Pattern.compile("(?i)(?:processed|已处理|已生成|处理了|已处理了)[^0-9]{0,4}(\\d[\\d,.]*)");
    // 备用：其它版本的 "1234/5000 chunks" 或 "1234 of 5000"
    private static final Pattern P_COUNT = Pattern.compile("(\\d[\\d,.]*)\\s*(?:/|\\bof\\b|of)\\s*(\\d[\\d,.]*)");
    // "35.4 cps" / "12.3 chunks/s" / "12 chunks/sec" / "12 区块/秒"
    private static final Pattern P_RATE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(?:chunks?\\s*/\\s*s(?:ec(?:onds?)?)?|c/s|cps|区块\\s*/\\s*秒)");
    // ETA: 0:00:45 / ETA: 1:02:03 / 预计距完成还有: 0:00:45（中文夹着“距完成还有: ”，位数放宽）
    private static final Pattern P_ETA_CLOCK = Pattern.compile("(?i)(?:eta|剩余|预计)[^0-9]{0,16}(?:(\\d+):)?(\\d{1,2}):(\\d{2})");
    // 备用：ETA: 1h 2m 3s
    private static final Pattern P_ETA_TEXT = Pattern.compile("(?i)(?:eta|剩余|预计)[^0-9]*?(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?");
    // /cs set 的成功回包。Chunksmith 4.x 的 format_set 是 “<key> is now <value>.”
    // （例如 “autoPauseOnOverload is now false.”），不是 “Set <key> to <value>”——
    // 老正则只认后者，等于备份记录永远记不上。旧的 Set/to 句式仍保留，兼容老版本/别的实现。
    // 不锚定行首：回包常带 “[Chunksmith] ” 前缀。误命中风险低：confirmSet 还要求
    // key/value 与面板挂着的 pending 完全一致。
    //
    // 两个坑（都实测过）：
    //  1) 回包结尾带句号（format_set 就是 “<key> is now <value>.”），非贪婪也吃不到句号，
    //     捕获出来是 “false.”，和面板挂着的 “false” 不相等 ⇒ 备份永远记不上（英文也一样）。
    //     所以句尾标点统一交给 CommandRunner.confirmSet 归一化，这里不再用 \.? 硬凑。
    //  2) 中文回包以前从行首锚定（^已?将?…），带 “[Chunksmith] ” 前缀时 ^ 直接失配；
    //     而且只认“已将…设置为”，漏了“现在是 / 现在为 / 已设置为 / 已设为”几种写法。
    //     现在去掉锚点、把常见句式都收进可选前缀，值末尾允许中文/英文标点。
    private static final Pattern P_SET_OK = Pattern.compile("(?i)\\bset\\s+(\\S+)\\s+to\\s+(\\S+?)\\s*$");
    private static final Pattern P_SET_NOW = Pattern.compile("(?i)(\\S+)\\s+is\\s+now\\s+(\\S+?)\\s*$");
    private static final Pattern P_SET_OK_ZH = Pattern.compile(
            "(?:已?将)?\\s*(\\S+?)\\s*(?:现在(?:是|为)|已?设置(?:为|成)|已?设为)\\s*(\\S+?)[。.!！；;，,]*\\s*$");

    /**
     * 失败 / 服务端拒绝的措辞表（小写比对）。
     *
     * <p>三处共用这一份：<b>日志定级</b>、<b>作废 /cs set 备份</b>、<b>中止两阶段清理</b>。
     * 以前只认 error / failed / exception / 失败 / 错误 这几个词，服务端回
     * “Invalid world.” “World not found.” “You don't have permission.” 时全被归成 INFO，
     * 清理流程照样在 3 秒后补发 {@code /cs trim}——参数根本没生效，清掉的是旧范围。</p>
     */
    private static final String[] FAIL_TERMS = {
            "error", "exception", "failed", "failure", "could not", "cannot", "can't", "unable",
            "invalid", "unknown", "not found", "does not exist", "doesn't exist", "no such",
            "not allowed", "not permitted", "denied", "rejected", "refused", "forbidden",
            "unsuccessful", "not successful", "not a valid", "no setting called",
            "does not have permission", "doesn't have permission", "don't have permission",
            "not have permission",
            "no permission", "insufficient permission", "missing permission",
            "out of range", "must be",
            "失败", "错误", "异常", "无效", "不存在", "未知", "无法", "不支持", "不合法",
            "不允许", "没有权限", "权限不足", "无权限", "拒绝"
    };

    /** 权限被拒的措辞（用来把面板切成只读）。中文各种“没有权限 / 权限不足”都算。 */
    private static final String[] PERM_DENIED = {
            "does not have permission", "doesn't have permission", "don't have permission",
            "not have permission", "no permission", "insufficient permission",
            "missing permission", "permission denied", "not permitted",
            "没有权限", "权限不足", "无权限", "没有足够权限"
    };

    private static boolean isFailureText(String lower) {
        // “No errors.” / “0 errors” 这种正常播报别当成失败（P2-2：FAIL_TERMS 里的 error 会误命中）
        if (lower.contains("no error") || lower.contains("0 error")) return false;
        for (String t : FAIL_TERMS) if (lower.contains(t)) return true;
        return false;
    }

    private static boolean isPermissionDenied(String lower) {
        for (String t : PERM_DENIED) if (lower.contains(t)) return true;
        return false;
    }

    private LogCapture() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.register(INSTANCE);
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent e) {
        try {
            // 只处理系统消息。Forge 1.20.1 里系统消息的 sender 不是 null，而是
            // Util.NIL_UUID（全 0 UUID）——以前写成 `if (e.getSender() != null) return;`，
            // 等于把所有系统消息全丢掉：/cs 的进度、完成、取消回包一条都进不来，
            // 任务页永远“正在生成”、进度/速度是空的，日志里也只剩面板自己发的指令。
            // 现在用 Forge 提供的 isSystem()：玩家聊天 = false，指令回包 / 系统播报 = true。
            if (!e.isSystem()) return;
            handle(e.getMessage());
        } catch (Throwable ignored) { }
    }

    private void handle(Component comp) {
        String text = stripCodes(comp.getString()).trim();
        if (text.isEmpty()) return;
        String lower = text.toLowerCase(Locale.ROOT);

        boolean mine = lower.contains("chunky") || lower.contains("chunksmith") || lower.contains("cs ")
                || lower.contains("chunk") || lower.contains("区块") || lower.contains("task")
                || lower.contains("任务") || isReplyFresh();
        if (!mine) return;

        boolean cancelish = lower.contains("cancel") || lower.contains("取消")
                || lower.contains("confirm") || lower.contains("确认");
        // “没有一个在跑的任务”同时是“跑完了”和“从没开始”的回包
        boolean noTask = isNoTask(lower, text);
        // 完成回包（task_done）与取消完成回包（format_cancel）要分开处理
        boolean taskDone = !cancelish && isTaskDone(lower, text);
        boolean cancelDone = isCancelDone(lower, text);

        PanelState.Level lv = PanelState.Level.INFO;
        // 失败/拒绝措辞统一走 FAIL_TERMS：只认 error/failed/exception 那三个词时，
        // “Invalid world.” “World not found.” “You don't have permission.” 会被当成 INFO。
        boolean failed = isFailureText(lower);
        if (failed) {
            lv = PanelState.Level.ERROR;
        } else if (cancelish || lower.contains("pause") || lower.contains("warn")
                || lower.contains("暂停") || lower.contains("警告") || lower.contains("注意")) {
            lv = PanelState.Level.WARN;
        } else if (taskDone || noTask) {
            lv = PanelState.Level.OK;
        }

        if (lv == PanelState.Level.ERROR) { PanelState.errors++; PanelState.sessionErrors++; }
        if (lv == PanelState.Level.WARN) { PanelState.warnings++; PanelState.sessionWarnings++; }

        // —— 服务器拒绝识别 ——
        if (lower.contains("unknown command") || lower.contains("未知命令")) {
            PrereqStatus.serverRejected = true;
            PrereqStatus.commandTreeSeen = false;
            PrereqStatus.invalidateOkCache();
        }
        if (isPermissionDenied(lower)) {
            PrereqStatus.perm = PrereqStatus.Perm.READONLY;
        } else if (!PrereqStatus.commandTreeSeen && lv != PanelState.Level.ERROR) {
            // 有任何正常回包说明前置在跑
            PrereqStatus.serverRejected = false;
        }

        // /cs set 是否真被服务端接受：只有确认回包才写入备份历史（面板自己发过 pending 才算数）。
        // 失败回包和成功回包只差几个词——“Could not set foo to bar.” / “Failed to set foo to bar”
        // / “将 foo 设置为 bar 失败”都能被成功正则命中，所以先过失败表再谈确认：
        // 不然一次失败的 set 会被记成备份，回滚时还会把刚弹出的记录丢掉并提示“已被服务端确认”。
        if (!failed) {
            Matcher setm = P_SET_OK.matcher(text);
            if (setm.find()) {
                CommandRunner.confirmSet(setm.group(1), setm.group(2));
            } else if ((setm = P_SET_NOW.matcher(text)).find()) {
                CommandRunner.confirmSet(setm.group(1), setm.group(2));
            } else if ((setm = P_SET_OK_ZH.matcher(text)).find()) {
                CommandRunner.confirmSet(setm.group(1), setm.group(2));
            }
        }

        // 出错时把面板挂着的“等确认”状态作废：set 备份不记、trim 序列中止
        if (lv == PanelState.Level.ERROR) {
            CommandRunner.failPending();
        }

        // 只有真正的进度行才允许改数字。Chunksmith 每次 /cs progress 在进度行后面还会跟一条
        // “I/O throttle active - concurrency: 22/200”，里面的 22/200 不是区块数；背压通知里
        // 还有 “43% of the heap” 这种百分比。它们进日志可以，进进度条就会把三项全带偏。
        boolean progressLine = isProgressLine(lower, text) && !isNoticeLine(lower);

        // 先解析数字，再按结果决定状态机：完成回包不能被后面的百分比再顶回“正在生成”
        parseProgress(lower, text, taskDone, progressLine);

        // 取消完成：服务端已经不跑了，本地要跟着回到空闲，否则面板会一直挂着“正在生成”。
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
     * 现在只有系统消息会走到这里（玩家聊天已在 onChat 被挡掉），
     * “没有 + 任务”这种宽匹配误伤普通聊天的风险不复存在。
     */
    private static boolean isNoTask(String lower, String raw) {
        if (lower.contains("no task") || lower.contains("no tasks") || lower.contains("not running")) return true;
        if (raw.contains("没有在运行") || raw.contains("没有正在运行")) return true;
        // 中文各种“没有…任务”句式（没有可取消的任务。/ 没有可再次运行的加载任务。…）：
        // 再要求带上任务动词或刚发过指令，避免别的模组的系统播报恰好含“没有…任务”时误判
        return raw.contains("没有") && raw.contains("任务")
                && (raw.contains("运行") || raw.contains("取消") || raw.contains("继续")
                    || raw.contains("暂停") || raw.contains("加载") || isReplyFresh());
    }

    /**
     * 任务正常跑完（Chunky/Chunksmith 的 task_done）。中文文案里没有“完成”二字：
     *   EN: Task finished for &lt;world&gt;. Processed: N chunks (P%), Total time: H:MM:SS
     *   ZH: 任务结束于 &lt;world&gt;。 处理了 N 个区块 (P%), 总运行时长: H:MM:SS
     *
     * <p>只认这些明确句式。裸的 done / complete / 完成 / 结束 一律不算——
     * “incomplete”“还没完成”都会命中宽关键词，把没跑完的任务标成已完成。</p>
     */
    private static boolean isTaskDone(String lower, String raw) {
        return lower.contains("task finished") || lower.contains("finished for")
                || lower.contains("total time")
                || raw.contains("任务结束") || raw.contains("总运行时长");
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
        return (lower.contains("processed:") || raw.contains("处理了") || raw.contains("已处理"))
                && (lower.contains("task") || raw.contains("任务")
                    || lower.contains("chunk") || raw.contains("区块") || isReplyFresh());
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

        // 只给了 “1234/5000 chunks” 而没给百分比的回包：百分比由数量反推，
        // 别让“总进度”一直空着（用户反馈：任务在跑，总进度和速度都没数字）。
        if (pct < 0 && proc >= 0 && totalPair > 0) {
            pct = Math.min(100.0, proc * 100.0 / totalPair);
        }

        boolean hasNumbers = progressLine && (pct >= 0 || proc >= 0);
        long now = System.currentTimeMillis();

        if (PanelState.task == PanelState.Task.DONE) {
            // 完成后的余包不能把补满的进度顶回去；安静一会儿后又冒出进度，才当成新任务。
            // 冷却从 5.5s 缩到 3.5s：完成 → 紧接着再 start 一个任务时不会长时间看不见新进度。
            if (!(hasNumbers && now > PanelState.completeFlashUntilMs + 1000)) return;
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

        if (cancelish || noTask) return;
        if (paused) {
            if (PanelState.task == PanelState.Task.RUNNING) PanelState.task = PanelState.Task.PAUSED;
            return;
        }
        // “继续/恢复”后服务端回的运行中进度行：本地可能还停在 PAUSED，要跟回 RUNNING
        if (PanelState.task == PanelState.Task.PAUSED && progressLine) {
            PanelState.task = PanelState.Task.RUNNING;
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
}
