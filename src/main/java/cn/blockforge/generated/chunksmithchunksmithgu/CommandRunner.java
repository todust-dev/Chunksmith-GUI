package cn.blockforge.generated.chunksmithchunksmithgu;

import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 指令执行器：统一走玩家聊天指令通道（单人=本城内服，多人=服务器校验权限），
 * 执行前记入面板日志。
 *
 * <p>三个安全机制：</p>
 * <ul>
 *   <li><b>set 备份等确认</b>：发 {@code /cs set} 只挂 pending，服务端回
 *       “Set key to value” 才写入备份历史（由 {@link LogCapture} 驱动 {@link #confirmSet}）；
 *       出错回包会作废 pending（{@link #failPending}），不会把没生效的改动记成备份；
 *       超时没人确认也会写日志说明这次没记备份。</li>
 *   <li><b>trim 两阶段</b>：清理（trim）不可逆，先把 world/pattern/center/radius 发出去，
 *       等一个短窗口内没有报错回包，才补发 {@code /cs trim}；任何错误都中止后续清理。</li>
 *   <li><b>start 两阶段</b>（与 trim 同一套机制）：生成也先只发范围设置，
 *       未被拒绝才补发启动指令，避免 world/center/radius 被拒后用旧选择启动。</li>
 * </ul>
 */
public final class CommandRunner {

    /** 挂在等待服务端确认的 set 改动。 */
    private static volatile PendingSet pendingSet;
    /** 两阶段清理：非空表示选择已发出、trim 待发。 */
    private static volatile PendingSeq pendingTrim;
    /** 两阶段启动：非空表示范围已发出、start 待发。 */
    private static volatile PendingSeq pendingStart;

    private static final class PendingSet {
        final String key, value; final boolean rollback; final long sentMs;
        /** 回滚挂账对应的、已从历史弹出的记录：服务端明确报错时放回去（报告 P2-3）。 */
        PanelState.SetRec restoreOnFail;
        PendingSet(String k, String v, boolean r) { key = k; value = v; rollback = r; sentMs = System.currentTimeMillis(); }
    }

    /** 通用“两阶段”挂账：设置已发出，等窗口内无错误再补发 finalCmd。 */
    private static final class PendingSeq {
        final String finalCmd; final long sentMs; final BooleanSupplier stillValid;
        PendingSeq(String cmd, BooleanSupplier stillValid) {
            finalCmd = cmd; sentMs = System.currentTimeMillis(); this.stillValid = stillValid;
        }
    }

    /**
     * 设置发出后等多久补发 trim/start（毫秒）。
     * 这是个启发式窗口——服务端错误回包晚于窗口时就拦不住了。
     * 从 2.2s 放宽到 3s 给慢服更多反应时间（UI 上写明"未报错才自动补发"）。
     */
    private static final long TRIM_DELAY_MS = 3000;
    /** set 确认等待上限，超时后丢弃 pending（并写日志），避免旧回包对错位。 */
    private static final long SET_PENDING_MS = 10_000;

    private CommandRunner() {
    }

    public static boolean send(String cmd) {
        return send(cmd, true);
    }

    /**
     * 面板自动轮询用的发送：指令照样执行，但不写进日志页。
     *
     * <p>进度自动刷新默认 5 秒一次，以前每次 {@code /cs progress} 都记一条 CMD，
     * 任务跑十几分钟日志页就被同一行刷满，真正的服务端回包反而看不见
     * （用户反馈「日志全变成了指令」）。手动点“刷新进度”仍然会记。</p>
     */
    public static boolean sendQuiet(String cmd) {
        return send(cmd, false);
    }

    private static boolean send(String cmd, boolean logIt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return false;
        mc.getConnection().sendCommand(cmd);
        if (logIt) PanelState.log(PanelState.Level.CMD, "/ " + cmd);
        PanelState.lastCommandSentMs = System.currentTimeMillis();
        String[] parts = cmd.split("\\s+");
        // /cs set —— 不立刻记备份，等服务端 “Set key to value” 确认回包（见 confirmSet）
        if (parts.length >= 4 && parts[0].equals(PrereqStatus.rootCommand) && parts[1].equals("set")) {
            pendingSet = new PendingSet(parts[2], parts[3], false);
        }
        // 粗略状态机：start 后立即进入“运行中”给按钮反馈；
        // pause/continue/cancel 不再乐观改状态——以聊天回包为准（解析器会按
        // “Task paused / running / cancelled” 推进状态），发送失败或被拒不会把 UI 带歪。
        if (parts.length >= 2) {
            switch (parts[1]) {
                case "start", "forcestart" -> {
                    PanelState.task = PanelState.Task.RUNNING;
                    PanelState.startedAtMs = System.currentTimeMillis();
                    PanelState.elapsedMs = -1;
                    PanelState.etaMs = -1;
                    PanelState.completeFlashUntilMs = 0;
                    PanelState.percent = 0;
                    PanelState.processed = -1;
                    PanelState.total = -1;
                    PanelState.rate = -1;
                    PanelState.taskRawStatus = "";
                    PanelState.errors = 0; PanelState.warnings = 0;
                }
                default -> { }
            }
        }
        return true;
    }

    /** 按顺序发送多条（跳过 # 注释）。 */
    public static boolean sendSequence(List<String> lines) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return false;
        boolean ok = true;
        for (String l : lines) {
            if (l == null || l.isBlank() || l.trim().startsWith("#")) continue;
            ok &= send(l.trim());
        }
        return ok;
    }

    // ==================== 两阶段序列（trim / start 共用） ====================

    /** 发出 trim 的“选范围”部分；服务端没报错才会由 {@link #tick()} 补发真正的 trim。 */
    public static boolean beginTrimSequence(List<String> setupLines) {
        if (pendingTrim != null || pendingStart != null) return false;   // 上一次操作还在等确认
        if (!sendSequence(setupLines)) return false;
        // 这里不再回读 PanelState.trimConfirmed：面板在发出后就把勾选作废（一次确认只对一次清理有效），
        // 要中止第二阶段走 cancelTrimPending（改参数 / 服务端报错都会调）。
        pendingTrim = new PendingSeq(PrereqStatus.rootCommand + " trim", () -> true);
        return true;
    }

    /** 发出 start 的“选范围”部分；服务端没报错才补发启动指令（报告 P1-6）。 */
    public static boolean beginStartSequence(List<String> setupLines, String finalCmd) {
        if (pendingTrim != null || pendingStart != null) return false;
        if (!sendSequence(setupLines)) return false;
        pendingStart = new PendingSeq(finalCmd, () -> true);
        return true;
    }

    /** 当前是否有清理在等第二阶段（供 UI 禁用按钮）。 */
    public static boolean isTrimPending() {
        return pendingTrim != null;
    }

    /** 当前是否有启动在等第二阶段。 */
    public static boolean isStartPending() {
        return pendingStart != null;
    }

    /** 取消尚未发出的第二阶段清理。 */
    public static void cancelTrimPending(String reason) {
        if (pendingTrim != null) {
            pendingTrim = null;
            PanelState.log(PanelState.Level.ERROR, Texts.t("log.trimAborted", reason));
        }
    }

    /** 取消尚未发出的第二阶段启动（不报错，只是作废）。 */
    public static void cancelStartPending(String reason) {
        if (pendingStart != null) {
            pendingStart = null;
            PanelState.log(PanelState.Level.WARN, Texts.t("log.startAborted", reason));
        }
    }

    /**
     * 每客户端 tick 调用：驱动两阶段序列的延时与 set 确认超时。
     * 窗口内任何错误回包都会走 {@link #failPending()} 把计划作废；
     * 安静到点才补发最后那条危险指令（trim / start）。
     */
    public static void tick() {
        advance(pendingTrim, Texts.t("task.kind.trim"));
        advance(pendingStart, Texts.t("task.kind.start"));
        // set 确认超时不再静默丢弃（报告 P1-7）：明确告诉用户这次结果，别让人以为有备份
        PendingSet ps = pendingSet;
        if (ps != null && System.currentTimeMillis() - ps.sentMs > SET_PENDING_MS) {
            pendingSet = null;
            if (ps.rollback) {
                // 回滚超时：弹出的那条备份记录以前就这么没了，日志却让用户“重新点回滚”——
                // 已经没得点。现在原样放回历史：回滚只是把同一个值再设一遍，重复点击无害，
                // 比自己把备份弄丢稳。
                if (ps.restoreOnFail != null) PanelState.restoreRollback(ps.restoreOnFail);
                PanelState.log(PanelState.Level.WARN,
                        Texts.t("log.rollbackTimeout", ps.key, ps.value));
            } else {
                PanelState.log(PanelState.Level.WARN,
                        Texts.t("log.setTimeout", ps.key, ps.value));
            }
        }
    }

    private static void advance(PendingSeq p, String what) {
        if (p == null) return;
        if (System.currentTimeMillis() - p.sentMs < TRIM_DELAY_MS) return;
        // 先摘对应的挂账，避免重入
        if (p == pendingTrim) pendingTrim = null; else pendingStart = null;
        if (!p.stillValid.getAsBoolean()) {
            PanelState.log(PanelState.Level.WARN, Texts.t("log.seqAbortedStale", what));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            PanelState.log(PanelState.Level.WARN, Texts.t("log.seqAbortedNoConn", what));
            return;
        }
        PanelState.log(PanelState.Level.INFO, Texts.t("log.resendFinal", p.finalCmd));
        send(p.finalCmd);
    }

    // ==================== set 备份确认 ====================

    /** 面板“回滚”专用：发 cs set 恢复旧值，但确认成功后不再记新备份（记录已弹出）。
     *  popped 是刚从历史里弹出的记录：服务端明确报错或确认超时都会放回去（重复回滚无害）。 */
    public static boolean sendRollbackSet(String key, String value, PanelState.SetRec popped) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return false;
        mc.getConnection().sendCommand(PrereqStatus.rootCommand + " set " + key + " " + value);
        PanelState.log(PanelState.Level.CMD, "/ " + PrereqStatus.rootCommand + " set " + key + " " + value);
        PanelState.lastCommandSentMs = System.currentTimeMillis();
        pendingSet = new PendingSet(key, value, true);
        pendingSet.restoreOnFail = popped;
        return true;
    }

    /** LogCapture 解析到 “Set key to value” 时调用：只有对得上 pending 的改动才记入备份。 */
    public static void confirmSet(String key, String value) {
        PendingSet ps = pendingSet;
        if (ps == null) return;   // 面板外的 set，不猜旧值、不记备份
        if (System.currentTimeMillis() - ps.sentMs > SET_PENDING_MS) { pendingSet = null; return; }
        // 回包结尾带句号（英文 “.” / 中文 “。”），有时还带引号，先归一化再比
        key = trimPunct(key);
        value = trimPunct(value);
        if (!ps.key.equalsIgnoreCase(key) || !ps.value.equals(value)) return;
        pendingSet = null;
        if (ps.rollback) {
            PanelState.log(PanelState.Level.OK, Texts.t("log.rollbackConfirmed", key, value));
        } else {
            PanelState.recordSetConfirmed(key, value);
        }
    }

    /**
     * 去掉回包两侧的标点/引号。
     *
     * <p>服务端的 format_set 是 {@code "<key> is now <value>."}——带句号。以前拿
     * “false.” 去跟面板挂着的 “false” 比，永远不相等，等于 set 备份一条都记不上，
     * 回滚也就永远没有东西可回。中文回包还会带 “。” 和引号。</p>
     */
    private static String trimPunct(String s) {
        if (s == null) return "";
        String t = s.trim();
        String punct = "\"'“”‘’，,。.；;：:、!！?？";
        int start = 0, end = t.length();
        while (start < end && punct.indexOf(t.charAt(start)) >= 0) start++;
        while (end > start && punct.indexOf(t.charAt(end - 1)) >= 0) end--;
        return t.substring(start, end);
    }

    /** 服务端回了错误：作废挂着的 set 备份、清理计划与启动计划。 */
    public static void failPending() {
        PendingSet ps = pendingSet;
        if (ps != null && System.currentTimeMillis() - ps.sentMs <= SET_PENDING_MS) {
            pendingSet = null;
            if (ps.rollback && ps.restoreOnFail != null) {
                // 回滚被服务端明确拒绝：刚弹出的备份记录原样放回，别让人以为还能回滚
                PanelState.restoreRollback(ps.restoreOnFail);
                PanelState.log(PanelState.Level.WARN, Texts.t("log.rollbackRejected", ps.key));
            } else {
                PanelState.log(PanelState.Level.WARN, Texts.t("log.setRejected", ps.key, ps.value));
            }
        }
        if (pendingTrim != null) {
            cancelTrimPending(Texts.t("log.serverErrorOnRange"));
        }
        if (pendingStart != null) {
            cancelStartPending(Texts.t("log.serverErrorOnRange"));
        }
    }

    /** 复制文本到系统剪贴板。 */
    public static void copy(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.keyboardHandler.setClipboard(text);
    }
}
