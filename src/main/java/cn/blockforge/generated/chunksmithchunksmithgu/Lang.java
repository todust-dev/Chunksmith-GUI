package cn.blockforge.generated.chunksmithchunksmithgu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面板的中英文案表。
 *
 * <p>为什么不是原版那套 {@code lang/*.json} + {@code Component.translatable}：
 * 这个面板是自绘界面，文字全部用 {@code Font.drawString} 直接画字符串，
 * 根本不经过原版的翻译组件，所以原版语言文件对它无效。这里用一个
 * “中文原文 → 英文译文”的表，在<b>绘制那一刻</b>翻译，面板代码里的中文原文
 * 不需要改写，也顺便能翻译运行时拼出来的句子（"已耗时：12:34" 这种）。</p>
 *
 * <p>翻译分两步：先查整句，查不到再按词条做“最长优先”的片段替换，
 * 所以拼接文案（"前置 " + 名字 + " 已装"）也能翻出来。所有翻译结果都会缓存，
 * 切语言时清空缓存。语言开关在配置页，存进 {@link Settings#language}。</p>
 */
public final class Lang {
    public static final String ZH = "zh";
    public static final String EN = "en";

    /**
     * 词条表：一行一条 {@code 中文原文|英文译文}。
     * 片段替换按“最长优先”，所以短词（区块/任务/清理）不会把长词（已生成区块）吃掉。
     * 正则表达式、日志关键字、指令注释一律不在这里——它们要原样匹配服务端的输出。
     */
    private static final String DATA = """
            生成|Generate
            任务|Task
            清理|Trim
            热力图|Heatmap
            日志|Log
            配置|Config
            生成设置|Generation settings
            生成进度|Generation progress
            清理区块|Trim chunks
            运行日志|Runtime log
            面板配置|Panel settings
            方形|Square
            圆形|Circle
            菱形|Diamond
            三角形|Triangle
            星形|Star
            椭圆|Oval
            当前位置|Current position
            世界出生点|World spawn
            指定坐标|Custom coords
            全部|All
            仅警告/错误|Warnings & errors
            仅指令|Commands only
            指令面板|Command panel
            前置未就绪：服务端不认识 /|Not ready: server does not know /
            指令，或服务器没装前置|command, or the prerequisite mod is missing
            服务端 /|Server /
            服务端没有下发 /|Server did not provide /
            指令：服务器没装前置，或你这个账号没有管理权限|command: the mod is missing server-side, or you are not an admin
            可用，但你这个账号没有管理权限（开始/清理需要管理员）|is available, but you have no admin rights (start/trim need admin)
            服务端已装前置，但命令树里缺少：|Prerequisite is installed server-side, but the command tree lacks:
            可用 · 权限|available · perm
            已装 · 权限|installed · perm
            · 就绪|· ready
            前置版本不一致（本地|Version mismatch (local
            / 服务端|/ server
            前置|Prerequisite
            已装 · 等服务端命令树同步…|installed · waiting for the command tree to sync…
            没看到 /|Did not see /
            指令：可能服务端没装前置，也可能你这个账号没有管理员权限|command: the mod may be missing server-side, or you are not an admin
            未检测到 ChunkSmith 前置：把它放进 mods 文件夹；服务端已装时可勾选“忽略前置检测”|ChunkSmith not found: put it in mods/; tick “Ignore prerequisite check” if the server has it
            注意：已开启“跳过安全预检” ·|Note: “Skip safety checks” on ·
            内存|RAM
            界面大小|UI scale
            确认调整|Apply
            界面大小已保存：|UI scale saved:
            上一页|Prev
            暂停|Pause
            状态已刷新|Status refreshed
            刷新|Refresh
            下一页|Next
            世界名（/cs world）|World name (/cs world)
            世界名|World name
            形状|Shape
            中心模式|Center mode
            中心坐标 X / Z（指定坐标时生效）|Center X / Z (used with custom coords)
            中心坐标 X / Z（选“指定坐标”时生效）|Center X / Z (used with custom coords)
            主半径 / 第二半径（区块，星形必填、椭圆选填）|Main / second radius (chunks; star requires it, oval optional)
            保留半径 / 第二半径（区块，星形必填、椭圆选填）|Keep / second radius (chunks; star requires it, oval optional)
            忽略前置检测|Ignore prerequisite check
            运行粒子|Particles
            高级：跳过安全预检|Advanced: skip safety checks
            已开启“跳过安全预检”：权限预检与预览参数检查被绕过，发指令前请自己确认无误。|“Skip safety checks” enabled: permission and parameter checks are bypassed, double-check commands yourself.
            仅在你确定面板误判了你的权限/命令时使用|Only use this if you are sure the panel misjudged your permissions/commands
            开始生成|Start generation
            复制命令|Copy commands
            命令已复制到剪贴板|Commands copied to clipboard
            重置表单|Reset form
            表单已重置为默认值|Form reset to defaults
            恢复自动预览|Restore auto preview
            预览已恢复为跟随表单|Preview restored to follow the form
            命令预览（点某行可改，执行的就是它）|Command preview (click a line to edit; this is what runs)
            命令预览（已手动编辑，执行的就是它）|Command preview (edited by hand; this is what runs)
            生成参数已改动，重新点“开始生成”|Parameters changed, click “Start generation” again
            参数改过了，这次启动已取消，请重新点“开始生成”|Parameters changed, this start was cancelled; click “Start generation” again
            上一条两阶段指令还在等服务端确认，稍等一下再点|The previous two-stage command is still awaiting server confirmation, wait a moment
            上一条两阶段指令还在等服务端确认，稍等一下|The previous two-stage command is still awaiting server confirmation, wait a moment
            参数有误：|Invalid parameter:
            表单校验未通过：|Form validation failed:
            服务端没下发 /cs 指令：可能没装前置，也可能你这个账号没有管理员权限；确认无误可勾选“忽略前置检测”|Server did not provide /cs: the prerequisite may be missing, or your account is not an admin; tick “Ignore prerequisite check” if you are sure
            没检测到 ChunkSmith 前置，请先安装；确已安装可勾选“忽略前置检测”|ChunkSmith prerequisite not found, install it first; if it is installed, tick “Ignore prerequisite check”
            当前权限不足（需要管理员）。确认自己是管理端的话，可在生成页勾选独立的“跳过安全预检”|Not enough permissions (admin required). If you are sure you are an admin, tick “Skip safety checks” on the Generate tab
            命令预览是空的|Command preview is empty
            服务端命令树里缺少：|missing from the server command tree:
            已跳过安全预检，直接发送：|Safety checks skipped, sending directly:
            正在设置范围，3 秒内未被拒绝会自动开始生成；如有报错会中止|Setting the range; generation starts on its own if not rejected within 3s and aborts on errors
            刚发过一遍，稍等一下再点（避免连点重复发送）|Just sent; wait a moment before clicking again (avoids duplicates)
            已发送|Sent
            条指令|commands
            总进度|Total progress
            已生成区块|Chunks generated
            生成速度|Speed
            区块/秒|chunks/s
            最近状态与异常|Recent status & errors
            暂停生成|Pause generation
            已发送暂停|Pause sent
            继续生成|Resume generation
            已发送继续|Resume sent
            取消生成|Cancel generation
            已发送取消；服务端还要一次确认，请点下面的「确认取消」|Cancel sent; the server needs one more confirmation, click “Confirm cancel” below
            确认取消（/cs confirm）|Confirm cancel (/cs confirm)
            已发送 /cs confirm，取消已确认|/cs confirm sent, cancel confirmed
            刷新进度|Refresh progress
            已请求最新进度|Requested the latest progress
            标记为已结束|Mark as finished
            本地状态已清零（不影响服务端任务）|Local state cleared (the server-side job is untouched)
            面板只显示进度，不会自己开始生成。|The panel only shows progress; it never starts generation by itself.
            前置回「没有在运行的任务」时，|When the prerequisite reports “no running task”,
            任务会自动标记为已完成。|the task is marked as finished automatically.
            清理（trim）会删除所选范围外的区块，不能撤销。|Trim deletes chunks outside the selected area and cannot be undone.
            确认世界、中心与半径无误后再执行。|Double-check world, center and radius before running.
            按什么形状清理|Trim shape
            保留范围的中心（范围以内保留）|Center of the kept area (inside is kept)
            保留半径|Keep radius
            # 第一阶段：设好范围（3 秒未被拒绝才进第二阶段）|# Stage 1: set the range (stage 2 runs only if not rejected within 3s)
            # 第二阶段：服务端未报错才自动补发|# Stage 2: sent only if the server reports no error
            # 形状|# shape
            · 中心|· center
            我确认要清理|I confirm the trim
            执行清理|Run trim
            取消待执行的清理|Cancel pending trim
            当前没有待执行的清理|No pending trim
            用户点了“取消待执行的清理”|User clicked “Cancel pending trim”
            已取消：这次清理不会再发出去|Cancelled: this trim will not be sent
            只在发出范围设置后的 3 秒确认窗口内可点|Only clickable during the 3s window after the range is sent
            将要执行的指令|Commands to run
            先发范围设置，3 秒未被拒绝才自动补发 /cs trim|Sends the range first, then /cs trim automatically if not rejected within 3s
            复制清理指令|Copy trim commands
            已复制|Copied
            清理参数已改动|Trim parameters changed
            请先勾选确认|Tick the confirmation first
            上一次清理仍在等服务端确认|The previous trim is still awaiting server confirmation
            星形需要第二半径|Star shape needs a second radius
            清理校验未通过：|Trim validation failed:
            没检测到 Chunksmith 前置|ChunkSmith prerequisite not found
            跳过安全预检|Skip safety checks
            当前权限不足（需要管理员）。若确认自己是管理端，可在生成页勾选独立的“跳过安全预检”。|Not enough permissions (admin required). If you are an admin, tick “Skip safety checks” on the Generate tab.
            权限预检未通过，清理已阻止。|Permission precheck failed, trim blocked.
            已勾选忽略前置检测：跳过了命令树对 trim 的检查，服务端若不支持会自行拒绝。|“Ignore prerequisite check” is on: the trim command-tree check is skipped; the server rejects it if unsupported.
            上一次操作仍在等待服务端确认，请稍候|The previous action is still awaiting server confirmation, please wait
            正在设置范围，3 秒内未被拒绝才自动发送清理；点错可点「取消待执行的清理」拦下|Setting the range; the trim is sent automatically if not rejected within 3s, click “Cancel pending trim” to stop it
            维度|Dimension
            窗口半径（区块，最大 256）|Window radius (chunks, max 256)
            拉取热力图|Fetch heatmap
            清空显示|Clear view
            请求中|Requesting
            扫描中…|Scanning…
            已清空热力图|Heatmap cleared
            绿色 = 已生成|Green = generated
            灰色 = 存在但不在图案内|Grey = exists but outside the shape
            金色 = 图案内还没生成|Gold = inside the shape, not generated yet
            数据来自存档 region 文件头，|Data comes from the save region file headers,
            不加载区块、不影响服务器。|no chunks are loaded and the server is unaffected.
            前置缺失，但热力图仍会尝试读取本地/服务端存档。|Prerequisite missing, but the heatmap still tries to read the local/server save.
            正在扫描|Scanning
            当前 ±|Current ±
            区块|chunks
            秒|sec
            过滤|Filter
            复制全部|Copy all
            日志已复制|Log copied
            复制异常|Copy errors
            没有警告或错误|no warnings or errors
            异常日志已复制|Error log copied
            导出文件|Export file
            清空|Clear
            日志已清空|Log cleared
            （暂无日志）|(no log yet)
            面板选项|Panel options
            面板语言|Panel language
            面板语言已切换|Panel language switched
            完成提示音|Completion sound
            藤蔓角饰|Vine corner decoration
            进度自动刷新（秒，0 = 关）|Auto refresh progress (sec, 0 = off)
            热力图默认窗口（区块）|Default heatmap window (chunks)
            保存设置|Save settings
            设置已保存|Settings saved
            恢复默认|Restore defaults
            已恢复默认设置|Defaults restored
            打开面板文件夹|Open panel folder
            打不开文件夹：|Cannot open folder:
            作者|Author
            打开开源项目|Open source project
            打不开链接：|Cannot open link:
            /cs set 改动备份（可回滚）|/cs set change backups (rollback-able)
            回滚最近一次|Roll back latest
            没有可回滚的改动|Nothing to roll back
            回滚发送失败（不在世界里？），备份记录已放回|Rollback failed to send (not in a world?), backup entry restored
            已发送回滚|Rollback sent
            ，等服务端确认|, awaiting server confirmation
            导出备份|Export backups
            清空备份记录|Clear backups
            备份记录已清空|Backups cleared
            继续|Resume
            状态：|Status:
            已耗时：|Elapsed:
            预计剩余：|ETA:
            异常|Errors
            条 · 警告|· warnings
            条（本次会话累计|(session total
            原始回包：|Raw reply:
            （还没收到）|(not received yet)
            (初始)|(initial)
            （本次会话还没改过 /cs set）|(no /cs set changes this session)
            管理员|Admin
            只读|Read-only
            未知|Unknown
            正在生成|Running
            已暂停|Paused
            已完成|Finished
            空闲（没有正在跑的任务）|Idle (no task running)
            已导出：|Exported:
            已导出文件|Exported file
            导出失败：|Export failed:
            尚无数据：点右侧 拉取热力图|No data yet: click “Fetch heatmap” on the right
            配置目录准备失败：|Failed to prepare the config directory:
            清理已中止：|Trim aborted:
            启动已中止：|Start aborted:
            回滚确认超时：|Rollback confirmation timed out:
            （指令可能已经生效；备份记录已放回，可以再点一次回滚）|(the command may already have applied; the backup entry was restored, you can try again)
            set 确认超时，未记录备份：|set confirmation timed out, no backup recorded:
            （改动可能已生效，但面板无法确认，这条不能回滚）|(the change may already have applied, but the panel cannot confirm it, so this one cannot be rolled back)
            已中止：确认状态已失效|Aborted: the confirmation state expired
            已中止：连接已断开|Aborted: connection lost
            范围设置未被服务端拒绝，补发|Range was not rejected by the server, resending
            回滚已被服务端确认：|Rollback confirmed by the server:
            回滚未生效，备份记录已放回历史：|Rollback did not take effect, backup entry returned to history:
            设置未生效，不计入备份：|Setting did not take effect, not recorded as a backup:
            服务端在处理范围设置时报了错|The server reported an error while handling the range setting
            世界名只允许字母数字与 _ - .|World name may only contain letters, digits and _ - .
            半径需在 1 ~ 1000000 之间|Radius must be between 1 and 1000000
            第二半径需在 0（不用）~ 1000000 之间|Second radius must be between 0 (unused) and 1000000
            星形需要第二半径（大于 0，且不超过主半径）|Star needs a second radius (greater than 0, not exceeding the main radius)
            星形的第二半径不能超过主半径|The star's second radius cannot exceed the main radius
            X 坐标超出世界边界|X coordinate is outside the world border
            Z 坐标超出世界边界|Z coordinate is outside the world border
            星形必须有第二半径|Star requires a second radius
            保留半径需在 1 ~ 1000000 之间|Keep radius must be between 1 and 1000000
            清理星形需要第二半径（在下面的输入框里填，大于 0 且不超过保留半径）|Trimming a star needs a second radius (fill it in below: greater than 0 and not exceeding the keep radius)
            清理星形的第二半径不能超过保留半径|The trim star's second radius cannot exceed the keep radius
            只查前缀、参数随便发|Only the prefix is checked; arguments are sent as-is
            预览里有不属于 /|The preview contains lines that are not /
            的行：| lines:
            world 参数不合法（只允许字母数字与 _ - .）：|invalid world argument (letters, digits and _ - . only):
            不认识这个形状：|Unknown shape:
            （可用：square/circle/diamond/triangle/star/oval）|(available: square/circle/diamond/triangle/star/oval)
            radius 需要 1~2 个数字参数：|radius needs 1-2 numeric arguments:
            radius 参数不是整数：|radius argument is not an integer:
            radius 需在 1 ~ 1000000 之间：|radius must be between 1 and 1000000:
            center 需要 0 或 2 个坐标参数：|center needs 0 or 2 coordinate arguments:
            center 坐标不是整数：|center coordinates are not integers:
            center 坐标超出世界边界：|center coordinates are outside the world border:
            服务端装了它|the server has it
            还没同步完|not synced yet
            服务端真没装|the server really lacks it
            还在同步|still syncing
            设置保存失败（磁盘/权限问题？）：|Failed to save settings (disk/permission issue?):
            热力图请求被服务端拒绝：|Heatmap request rejected by the server:
            （| (
            ）·|) ·
            ）|)
            """;

    /** 整句命中表。 */
    private static final Map<String, String> EXACT = new LinkedHashMap<>();
    /** 片段替换表（按词条长度从长到短，保证长词优先）。 */
    private static final List<Map.Entry<String, String>> SEG = new ArrayList<>();
    /** 翻译结果缓存：同一句每帧都要画，不缓存的话每帧要跑几千次子串比较。 */
    private static final Map<String, String> CACHE = new HashMap<>(512);
    private static final int CACHE_MAX = 4096;

    private Lang() {
    }

    static {
        for (String raw : DATA.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            int i = line.indexOf('|');
            if (i <= 0) continue;
            String zh = line.substring(0, i).strip();
            // 英文这边不 strip：个别词条要保留开头的空格（比如全角括号翻成 " (")
            String en = line.substring(i + 1);
            if (zh.isEmpty() || en.isBlank()) continue;
            EXACT.putIfAbsent(zh, en);
        }
        SEG.addAll(EXACT.entrySet());
        SEG.sort((a, b) -> b.getKey().length() - a.getKey().length());
    }

    /** 当前是不是英文面板。 */
    public static boolean english() {
        return EN.equals(Settings.language);
    }

    /** 切语言后清缓存（语言开关调用）。 */
    public static void invalidate() {
        CACHE.clear();
    }

    /**
     * 把一句面板文案翻成当前语言。中文模式、或者本来就没有中文的串（指令、路径、数字）
     * 原样返回；查不到整句时按词条做片段替换，拼接出来的句子也能翻。
     */
    public static String t(String zh) {
        if (zh == null || zh.isEmpty() || !english()) return zh;
        String hit = EXACT.get(zh);
        if (hit != null) return hit;
        String cached = CACHE.get(zh);
        if (cached != null) return cached;
        if (!hasCjk(zh)) return zh;

        StringBuilder sb = new StringBuilder(zh.length() + 16);
        int i = 0;
        boolean changed = false;
        while (i < zh.length()) {
            Map.Entry<String, String> found = null;
            for (int k = 0; k < SEG.size(); k++) {
                Map.Entry<String, String> e = SEG.get(k);
                if (zh.startsWith(e.getKey(), i)) { found = e; break; }
            }
            if (found != null) {
                sb.append(found.getValue());
                i += found.getKey().length();
                // 中文“状态：正在生成”不空格，英文要“Status: Running”，这里补上冒号后的空格
                if (endsWithColon(found.getValue()) && i < zh.length() && zh.charAt(i) != ' ') {
                    sb.append(' ');
                }
                changed = true;
            } else {
                sb.append(zh.charAt(i));
                i++;
            }
        }
        if (!changed) return zh;
        String out = sb.toString();
        if (CACHE.size() > CACHE_MAX) CACHE.clear();
        CACHE.put(zh, out);
        return out;
    }

    private static boolean endsWithColon(String s) {
        return !s.isEmpty() && (s.charAt(s.length() - 1) == ':' || s.charAt(s.length() - 1) == '：');
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }
}
