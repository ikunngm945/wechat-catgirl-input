package org.dsh.qqcatime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 文本转换与配置读取。
 *
 * 配置目录：/data/adb/QQIME/
 *   katxt      —— 颜文字词库（缺失时自动释放内置 54 条）
 *   whitelist  —— 包名白名单（缺失时自动释放默认 QQ）
 *   qqime.log  —— 运行日志
 *
 * 与路线 A（无障碍方案）保持同一套转换规则：
 *   我 → 本喵；你 → 主人；每个句末标点前补「喵」；末尾追加随机颜文字。
 */
public final class Cat {

    /**
     * 工作目录。
     *
     * 必须位于「输入法自己的数据目录」内：输入法进程跑在 untrusted_app 域，
     * 无法写入 /data/adb 或 /data/local/tmp。而 root 可以读写此处，
     * 因此脚本把主配置同步到这里，模块直接读这里。
     */
    public static final String DIR = "/data/user/0/com.tencent.wetype/qqime";
    public static final String KAO_FILE = DIR + "/katxt";
    public static final String WL_FILE = DIR + "/whitelist";
    public static final String LOG_FILE = DIR + "/qqime.log";

    /** 供分享导出的日志（别人拿到这个文件即可排查）。 */
    public static final String LOG_EXPORT = DIR + "/qqcat-log.txt";

    /** 自动发现的应用（输入法遇到过、但不在可见列表里的包名）。 */
    public static final String SEEN_FILE = DIR + "/seen";

    /** 句末标点。 */
    static final String PUNCT = "。．！？!?；;…";

    /** true：喵 加在标点前（你好喵。）。 */
    private static final boolean MIAO_BEFORE_PUNCT = true;

    /**
     * 改写标记：零宽字符（U+200B）。
     *
     * <p>v7.14 起，模型改写过的正文会用它包起来（`ZW 正文 ZW`）。
     * 下次再读到带标记的文字就直接原样返回，不再改写 —— 免得写回输入框
     * 的内容被反复送去模型。它是零宽的，聊天窗口里看不见。
     */
    public static final String ZW = "\u200B";

    /** LLM 失败时追加在原文后面的前缀（故意不用零宽，见需求）。 */
    public static final String ERR_PREFIX = "\n⚠ LLM 改写失败：";

    /** 失败提示的收尾换行：保证用户接着打的字仍落在提示行之后。 */
    private static final String ERR_END = "\n";

    /** 这段文字是否已经被零宽字符标记过。 */
    public static boolean isMarked(String s) {
        return s != null && s.indexOf(ZW) >= 0;
    }

    /** 去掉零宽标记（送去模型 / 匹配标签前必须先去掉）。 */
    public static String unmark(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        return s.replace(ZW, "");
    }

    /** 给改写结果套上零宽标记。 */
    public static String mark(String s) {
        if (s == null || s.length() == 0) {
            return s;
        }
        return ZW + s + ZW;
    }

    /**
     * 零宽标记是否成对（数量为偶数且每个开头标记都有配对的收尾标记）。
     *
     * <p>mark() 总是插入一对，所以文件里零宽的数量必定是偶数。
     * 用户退格删掉一个就会变成奇数 —— 这就是「用户动过这段文字」的信号。
     */
    private static boolean isPaired(String s) {
        if (s == null) {
            return true;
        }
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ZW.charAt(0)) {
                n++;
            }
        }
        return n % 2 == 0;
    }

    /** 这段文字是不是「上一轮追加的失败提示」。 */
    public static boolean isErrorText(String s) {
        return s != null && s.indexOf(ERR_PREFIX) >= 0;
    }

    /**
     * 认定「这段是我们自己产出过的改写正文」所需的最短长度。
     *
     * <p>太短的串（「喵」「哦」）到处都是，按内容比对会误伤用户原文，
     * 所以宁可放过短的，也不要错认。
     */
    private static final int MIN_PROTECT = 6;

    /**
     * 从 {@code at} 起匹配一段已登记的改写正文（含后面本地补的颜文字）。
     *
     * <p>零宽标记本该成对，但真机实测发现宿主写回输入框时会吃掉行尾那个
     * （日志证据：{@code WRITE from=[\u200B正文] to=[正文]}），于是下次读到的
     * 文本「看着像自由文字，其实已经改写过」。更麻烦的是用户接着打字时，
     * 旧段落会落在自由文字<b>中间</b>（实测 {@code from=[hello呀hello hello呀～
     * 本喵来啦喵～…]}），只看开头认不出来，那段旧文字就被整句重新送模型：
     * 实测多花 4.2 秒，还把已经改好的话又改了一遍。
     *
     * @return 命中片段的长度；没命中返回 0
     */
    private static int matchOutputLen(String[] outs, String s, int at) {
        if (outs == null || outs.length == 0 || at < 0 || at >= s.length()) {
            return 0;
        }
        int best = 0;
        for (String o : outs) {
            if (o == null || o.length() < MIN_PROTECT) {
                continue;
            }
            if (s.startsWith(o, at) && o.length() > best) {
                best = o.length();
            }
        }
        if (best == 0) {
            return 0;
        }
        int n = at + best;
        // 改写结果后面常常跟着本地补的颜文字，那串也属于这段已完成的文字。
        // 不一起带走的话，它会掉到后面被当自由文字重新处理：旧颜文字被丢掉、
        // 还会多问一次向量模型。
        TagLib.load();
        boolean moved = true;
        while (moved) {
            moved = false;
            for (String k : kaomoji) {
                if (k.length() > 0 && s.startsWith(k, n)) {
                    n += k.length();
                    moved = true;
                    break;
                }
            }
        }
        return n - at;
    }

    /** 自由文字里最早出现的一段「自己产出过的改写正文」；没有返回 -1。 */
    private static int firstOutputAt(String[] outs, String s) {
        return firstOutputAt(outs, s, 0);
    }

    /**
     * 从 {@code from} 起，最早出现的一段「自己产出过的改写正文」；没有返回 -1。
     *
     * <p>用 {@link String#indexOf(String,int)} 逐个输出找最早位置，而不是对每个
     * 下标都试一遍：轮询每几百毫秒跑一次，已登记的输出可能上千条、整段文字可能
     * 几百字，逐下标暴力比对会明显卡顿。
     */
    private static int firstOutputAt(String[] outs, String s, int from) {
        if (outs == null || outs.length == 0 || s == null) {
            return -1;
        }
        int best = -1;
        for (String o : outs) {
            if (o == null || o.length() < MIN_PROTECT) {
                continue;
            }
            int p = s.indexOf(o, from);
            if (p >= 0 && (best < 0 || p < best)) {
                best = p;
            }
        }
        return best;
    }

    /**
     * 处理一段自由文字：里面若混着「自己产出过的改写正文」，那部分原样保留，
     * 只有真正的新文字才送去改写。
     *
     * @param sb   输出累积（受保护的旧段落会补回零宽标记）
     * @param free 自由文字累积（用于判断「这句拿到改写结果了没」）
     */
    private static void appendFree(StringBuilder sb, StringBuilder free,
                                   String[] outs, String seg, boolean loose) {
        if (seg == null || seg.length() == 0) {
            return;
        }
        int at = firstOutputAt(outs, seg);
        if (at < 0) {
            free.append(seg);
            sb.append(loose ? transformLoosePart(seg) : transformPart(seg));
            return;
        }
        if (at > 0) {
            appendFree(sb, free, outs, seg.substring(0, at), loose);
        }
        int n = matchOutputLen(outs, seg, at);
        sb.append(mark(seg.substring(at, at + n)));
        if (at + n < seg.length()) {
            appendFree(sb, free, outs, seg.substring(at + n), loose);
        }
    }

    /** 去掉自由文字里混着的「自己产出过的改写正文」，只留下真正的新文字。 */
    private static String stripOutputs(String[] outs, String s) {
        if (s == null || s.length() == 0) {
            return s;
        }
        int at = firstOutputAt(outs, s);
        if (at < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int z = firstOutputAt(outs, s, i);
            if (z < 0) {
                sb.append(s.substring(i));
                break;
            }
            if (z > i) {
                sb.append(s.substring(i, z));
            }
            int n = matchOutputLen(outs, s, z);
            if (n <= 0) {
                sb.append(s.substring(z));
                break;
            }
            i = z + n;
        }
        return sb.toString();
    }

    /**
     * 内置默认词库（来自 config/katxt，由 tools/gen_config.py 生成）。
     * 首次运行释放；也可通过「恢复默认设置」还原。
     */
    public static final String[] DEFAULT_KAO = splitLines(Defaults.KAOMOJI);

    /** 默认白名单。 */
    public static final String[] DEFAULT_WL = {
        "com.tencent.mobileqq",
        "com.tencent.mobileqqi",
        "com.tencent.tim",
    };

    private static String[] kaomoji = DEFAULT_KAO;
    private static long kaoStamp = -2;
    private static String lastAppended = null;

    private static List<String> whitelist = new ArrayList<String>();
    private static long wlStamp = -2;

    /** 已自动记录过的包名（避免重复写盘）。 */
    private static final List<String> seen = new ArrayList<String>();
    private static long seenStamp = -2;

    private static final Random RND = new Random();

    private Cat() {
    }

    // ------------------------------------------------------------ 日志

    public static synchronized void log(String s) {
        try {
            File f = new File(LOG_FILE);
            if (f.exists() && f.length() > 512 * 1024) {
                f.delete();
            }
            ensureDir();
            Writer w = new OutputStreamWriter(new FileOutputStream(LOG_FILE, true), "UTF-8");
            w.write(ts() + " " + s + "\n");
            w.flush();
            w.close();
        } catch (Throwable t) {
            // 忽略
        }
    }

    /** 简单时间戳（不依赖 SimpleDateFormat，减少出错面）。 */
    private static String ts() {
        long t = System.currentTimeMillis();
        long sec = t / 1000L;
        long ms = t % 1000L;
        long daySec = sec % 86400L;
        long h = daySec / 3600L;
        long mi = (daySec % 3600L) / 60L;
        long se = daySec % 60L;
        return two(h) + ":" + two(mi) + ":" + two(se) + "." + three(ms);
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String three(long v) {
        if (v < 10) {
            return "00" + v;
        }
        if (v < 100) {
            return "0" + v;
        }
        return String.valueOf(v);
    }

    /**
     * 构建完整日志报告（含环境信息），供用户复制/分享给开发者排查。
     *
     * @param extraInfo 附加说明，可为 null
     */
    public static synchronized String buildLogReport(String extraInfo) {
        StringBuilder sb = new StringBuilder(8192);
        try {
            sb.append("==== QQ猫娘模块 日志 ====\n");
            sb.append("导出时间: ").append(ts()).append('\n');
            sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
                    .append(" (SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
            sb.append("机型: ").append(android.os.Build.MANUFACTURER).append(' ')
                    .append(android.os.Build.MODEL).append('\n');
            sb.append("模块版本: ").append(Version.NAME).append(" (").append(Version.CODE).append(")\n");
            sb.append("配置目录: ").append(DIR).append('\n');
            sb.append("词库: ").append(kaomoji == null ? 0 : kaomoji.length).append(" 条\n");
            sb.append("白名单: ").append(whitelist).append('\n');
            try {
                sb.append("标签: ").append(TagLib.tagCount())
                        .append(" 个，规则: ").append(TagLib.ruleCount()).append(" 条\n");
            } catch (Throwable t) {
                sb.append("标签: 读取失败\n");
            }
            sb.append("进程: ").append(android.os.Process.myPid())
                    .append(" uid=").append(android.os.Process.myUid()).append('\n');
            if (extraInfo != null && extraInfo.length() > 0) {
                sb.append("补充: ").append(extraInfo).append('\n');
            }
            sb.append("======== 运行日志 ========\n");

            File f = new File(LOG_FILE);
            if (f.isFile()) {
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                } finally {
                    if (r != null) {
                        try { r.close(); } catch (Throwable t) { }
                    }
                }
            } else {
                sb.append("(日志文件不存在)\n");
            }
        } catch (Throwable t) {
            sb.append("\n[报告构建出错 ").append(t.getClass().getSimpleName()).append("]\n");
        }
        return sb.toString();
    }

    /** 面板上显示用的日志（只取末尾若干行，避免界面卡顿）。 */
    public static synchronized String readLogForDisplay() {
        try {
            StringBuilder head = new StringBuilder();
            head.append("Android ").append(android.os.Build.VERSION.RELEASE)
                    .append(" | 模块 ").append(Version.NAME)
                    .append(" | 词库 ").append(kaomoji == null ? 0 : kaomoji.length)
                    .append(" 条 | 标签 ").append(TagLib.tagCount())
                    .append(" | 规则 ").append(TagLib.ruleCount()).append('\n');
            head.append("---- 最近日志 ----\n");

            File f = new File(LOG_FILE);
            if (!f.isFile()) {
                return head + "(暂无日志)";
            }
            // 只取文件末尾 12KB，避免大日志拖慢界面
            long len = f.length();
            long from = len > 12288 ? len - 12288 : 0;
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                raf.seek(from);
                byte[] buf = new byte[(int) (len - from)];
                raf.readFully(buf);
                String tail = new String(buf, "UTF-8");
                if (from > 0) {
                    int nl = tail.indexOf('\n');
                    if (nl >= 0) {
                        tail = tail.substring(nl + 1);
                    }
                }
                return head + tail;
            } finally {
                raf.close();
            }
        } catch (Throwable t) {
            return "读取日志失败: " + t.getClass().getSimpleName();
        }
    }

    /** 导出日志到文件（有 root 时可直接取走）。 */
    public static synchronized String exportLog(String extraInfo) {
        try {
            String report = buildLogReport(extraInfo);
            ensureDir();
            Writer w = new OutputStreamWriter(new FileOutputStream(LOG_EXPORT), "UTF-8");
            w.write(report);
            w.flush();
            w.close();
            log("导出日志 -> " + LOG_EXPORT + " (" + report.length() + " 字符)");
            return LOG_EXPORT;
        } catch (Throwable t) {
            log("导出日志失败 " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 清空运行日志。 */
    public static synchronized boolean clearLog() {
        try {
            File f = new File(LOG_FILE);
            if (f.exists()) {
                f.delete();
            }
            log("日志已清空");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void ensureDir() {
        try {
            File d = new File(DIR);
            if (!d.exists()) {
                d.mkdirs();
                d.setReadable(true, false);
                d.setExecutable(true, false);
            }
        } catch (Throwable t) {
            // 忽略
        }
    }

    // ------------------------------------------------------------ 配置加载

    private static long stampOf(File f) {
        return f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
    }

    /** 词库热重载：内容缺失或为空时使用内置词库。 */
    public static void loadKaomoji() {
        File f = new File(KAO_FILE);
        long stamp = stampOf(f);
        if (stamp == kaoStamp) {
            return;
        }
        kaoStamp = stamp;

        if (stamp == -1L) {
            // 首次运行：释放内置词库
            StringBuilder sb = new StringBuilder();
            for (String k : DEFAULT_KAO) {
                sb.append(k).append('\n');
            }
            writeFile(KAO_FILE, sb.toString());
            kaomoji = DEFAULT_KAO;
            log("已释放内置词库 " + DEFAULT_KAO.length + " 条 -> " + KAO_FILE);
            kaoStamp = stampOf(new File(KAO_FILE));
            return;
        }

        List<String> out = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0 && !line.startsWith("#")) {
                    out.add(line);
                }
            }
        } catch (Throwable t) {
            // 回退
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable t) { }
            }
        }
        if (out.isEmpty()) {
            kaomoji = DEFAULT_KAO;
            log("词库为空，使用内置 " + DEFAULT_KAO.length + " 条");
        } else {
            kaomoji = out.toArray(new String[0]);
            log("词库已加载 " + kaomoji.length + " 条");
        }
    }

    /** 白名单热重载：缺失时释放默认 QQ/TIM。 */
    public static void loadWhitelist() {
        File f = new File(WL_FILE);
        long stamp = stampOf(f);
        if (stamp == wlStamp) {
            return;
        }
        wlStamp = stamp;

        if (stamp == -1L) {
            StringBuilder sb = new StringBuilder();
            for (String k : DEFAULT_WL) {
                sb.append(k).append('\n');
            }
            writeFile(WL_FILE, sb.toString());
            buildWhitelist(DEFAULT_WL);
            log("已释放默认白名单 " + DEFAULT_WL.length + " 个 -> " + WL_FILE);
            wlStamp = stampOf(new File(WL_FILE));
            return;
        }

        List<String> out = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0 && !line.startsWith("#")) {
                    out.add(line);
                }
            }
        } catch (Throwable t) {
            // 回退
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable t) { }
            }
        }
        if (out.isEmpty()) {
            buildWhitelist(DEFAULT_WL);
            log("白名单为空，使用默认 " + DEFAULT_WL.length + " 个");
        } else {
            buildWhitelist(out.toArray(new String[0]));
            log("白名单已加载 " + whitelist.size() + " 个");
        }
    }

    private static void buildWhitelist(String[] items) {
        List<String> out = new ArrayList<String>();
        for (String s : items) {
            String t = s.trim();
            if (t.length() > 0) {
                out.add(t);
            }
        }
        whitelist = out;
    }

    /** 该包名是否在白名单内。 */
    public static boolean allowed(String pkg) {
        if (pkg == null) {
            return false;
        }
        return whitelist.contains(pkg);
    }

    /**
     * 勾选/取消某个包名并写回文件。
     * UI 跑在输入法进程内，直接写自己的数据目录，不需要 root。
     */
    public static synchronized boolean setWhitelisted(String pkg, boolean on) {
        if (pkg == null || pkg.length() == 0) {
            return false;
        }
        boolean cur = whitelist.contains(pkg);
        if (on == cur) {
            return true;
        }
        List<String> out = new ArrayList<String>(whitelist);
        if (on) {
            out.add(pkg);
        } else {
            out.remove(pkg);
        }
        StringBuilder sb = new StringBuilder();
        for (String k : out) {
            sb.append(k).append('\n');
        }
        writeFile(WL_FILE, sb.toString());
        whitelist = out;
        wlStamp = stampOf(new File(WL_FILE));
        log("whitelist: " + (on ? "添加 " : "移除 ") + pkg
                + "，现 " + whitelist.size() + " 个");
        return true;
    }

    /** 当前白名单快照。 */
    public static List<String> whitelistList() {
        return new ArrayList<String>(whitelist);
    }

    // ------------------------------------------------------------ 自动发现

    /** 加载 /seen（缺失时留空，不释放默认值）。 */
    public static synchronized void loadSeen() {
        File f = new File(SEEN_FILE);
        long stamp = stampOf(f);
        if (stamp == seenStamp) {
            return;
        }
        seenStamp = stamp;
        List<String> out = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() > 0 && !line.startsWith("#") && !out.contains(line)) {
                    out.add(line);
                }
            }
        } catch (Throwable t) {
            // 文件不存在属正常
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable t) { }
            }
        }
        synchronized (seen) {
            seen.clear();
            seen.addAll(out);
        }
    }

    /**
     * 记录一个「输入法实际遇到、但不在应用列表里」的包名。
     * 用于发现那些没有 MAIN 入口、无法被 queryIntentActivities 枚举的应用。
     *
     * @return 是否是新发现
     */
    public static synchronized boolean noteSeen(String pkg) {
        if (pkg == null || pkg.length() == 0) {
            return false;
        }
        // 排除输入法自身与模块自身；已在白名单、或已记录过，则不重复
        if ("com.tencent.wetype".equals(pkg)
                || "org.dsh.qqcatime".equals(pkg)
                || whitelist.contains(pkg)) {
            return false;
        }
        synchronized (seen) {
            if (seen.contains(pkg)) {
                return false;
            }
            seen.add(pkg);
            StringBuilder sb = new StringBuilder();
            for (String k : seen) {
                sb.append(k).append('\n');
            }
            writeFile(SEEN_FILE, sb.toString());
        }
        seenStamp = stampOf(new File(SEEN_FILE));
        log("seen: 新发现 " + pkg + "，累计 " + seenCount());
        return true;
    }

    /** 已发现的应用快照（不含白名单中的）。 */
    public static List<String> seenList() {
        synchronized (seen) {
            return new ArrayList<String>(seen);
        }
    }

    public static int seenCount() {
        synchronized (seen) {
            return seen.size();
        }
    }

    /** 清空发现列表。 */
    public static synchronized boolean clearSeen() {
        synchronized (seen) {
            seen.clear();
        }
        try {
            writeFile(SEEN_FILE, "");
        } catch (Throwable t) {
            return false;
        }
        seenStamp = stampOf(new File(SEEN_FILE));
        log("seen: 已清空");
        return true;
    }

    public static List<String> whitelist() {
        return whitelist;
    }

    /** 把多行文本切成数组（去空行、去注释行）。 */
    public static String[] splitLines(String text) {
        if (text == null || text.length() == 0) {
            return new String[0];
        }
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.length() > 0 && !t.startsWith("#")) {
                out.add(t);
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * 恢复默认设置：把内置的词库、规则、标签重新写回配置目录。
     * 用户误删配置后可用。
     *
     * @return 是否成功
     */
    public static synchronized boolean restoreDefaults() {
        try {
            ensureDir();
            // 1) 词库
            writeFile(KAO_FILE, Defaults.KAOMOJI);
            reloadKaomoji();
            // 2) 标签
            File tagsDir = new File(TagLib.TAGS_DIR);
            if (!tagsDir.isDirectory()) {
                tagsDir.mkdirs();
            }
            for (File f : tagsDir.listFiles()) {
                if (f.isFile() && f.getName().endsWith(".txt")) {
                    f.delete();
                }
            }
            for (int i = 0; i < Defaults.TAG_NAMES.length
                    && i < Defaults.TAG_BODIES.length; i++) {
                writeFile(TagLib.TAGS_DIR + "/" + Defaults.TAG_NAMES[i] + ".txt",
                        Defaults.TAG_BODIES[i]);
            }
            // 3) 规则
            writeFile(TagLib.RULES_FILE, Defaults.RULES);
            // 4) 让 TagLib 强制重载
            TagLib.forceReload();
            // 4.5) 替换规则与开关恢复默认
            Config.restoreDefaults();
            // 5) 白名单恢复默认（仅当为空）
            File wl = new File(WL_FILE);
            if (!wl.isFile() || wl.length() == 0) {
                StringBuilder sb = new StringBuilder();
                for (String k : DEFAULT_WL) {
                    sb.append(k).append('\n');
                }
                writeFile(WL_FILE, sb.toString());
                loadWhitelist();
            }
            log("已恢复默认设置：词库 " + kaomoji.length + " 条，标签 "
                    + TagLib.tagCount() + " 个，规则 " + TagLib.ruleCount() + " 条");
            return true;
        } catch (Throwable t) {
            log("恢复默认失败 " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 强制重新读取词库（供编辑后刷新）。 */
    public static void reloadKaomoji() {
        kaoStamp = -2;
        loadKaomoji();
    }

    /** 从全部颜文字中随机取一条（兜底用）。 */
    public static String randomKaomoji() {
        if (kaomoji == null || kaomoji.length == 0) {
            return "";
        }
        return kaomoji[RND.nextInt(kaomoji.length)];
    }

    public static int kaomojiCount() {
        return kaomoji.length;
    }

    /** 当前词库的全部颜文字（只读副本，供向量索引使用）。 */
    public static List<String> kaomojiList() {
        List<String> out = new ArrayList<String>();
        if (kaomoji != null) {
            for (String k : kaomoji) {
                if (k != null && k.length() > 0) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    public static void writeFile(String path, String content) {
        try {
            ensureDir();
            Writer w = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
            w.write(content);
            w.flush();
            w.close();
        } catch (Throwable t) {
            log("写文件失败 " + path + " " + t.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------ 文本转换

    private static String stripKaomoji(String s) {
        String r = s;
        if (lastAppended != null && lastAppended.length() > 0) {
            r = r.replace(lastAppended, "");
        }
        for (String k : kaomoji) {
            if (k.length() > 0) {
                r = r.replace(k, "");
            }
        }
        return r;
    }

    private static boolean isPunct(char c) {
        return PUNCT.indexOf(c) >= 0;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\u3000';
    }

    /**
     * 句子去掉标点和空白后是否还有实际内容。
     * 「。」「555。」这类残句没内容可比，不该拿去问向量模型（浪费额度也拿不到好结果）。
     */
    public static boolean hasContent(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isBlank(c) && !isPunct(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否存在「尚未喵化」的句末标点 —— 这才是触发条件。
     * 判定时向前跳过空白；已有喵的标点不再重复处理，因此不会反复改写。
     */
    private static boolean needsMiao(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isPunct(s.charAt(i))) {
                continue;
            }
            int j = i - 1;
            while (j >= 0 && isBlank(s.charAt(j))) {
                j--;
            }
            if (j < 0 || s.charAt(j) != '喵') {
                return true;
            }
        }
        return false;
    }

    /** 去掉句末原有的「喵」，使结果与历史无关。 */
    private static String stripSentenceMiao(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '喵') {
                int j = i + 1;
                // 只跳过空白，不跳过紧邻的其它「喵」：
                // 否则「喵喵喵喵喵喵。」会被逐个吞掉只剩一个「喵」。
                // 这里只需删掉紧贴标点的那一个「喵」（幂等），中间的喵是用户自己打的。
                while (j < s.length() && isBlank(s.charAt(j))) {
                    j++;
                }
                if (j < s.length() && isPunct(s.charAt(j))) {
                    i++;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }


    /**
     * 核心转换。
     *
     * <p>v7.14 起：
     * <ul>
     *   <li>模型改写过的段落用零宽字符包起来，下次读到就原样保留；</li>
     *   <li>改写失败（接口报错 / 超时 / 没网）时，把失败原因追加在原文后面，
     *       不再静默回退本地规则；</li>
     *   <li>失败提示本身不加零宽标记，所以它不会让这段话被当成「已改写」。</li>
     * </ul>
     */
    public static String transform(String input) {
        return transformGuarded(input, false);
    }

    /**
     * 逐段转换：零宽标记包住的段落原样保留，只处理其余的自由文字。
     *
     * <p>用户接着往后打字时，输入框里会同时存在「已改写的旧段」与
     * 「还没处理的新段」，所以不能看到标记就整段跳过 —— 要把受保护的
     * 段落切出来，只对自由段落动手。
     */
    private static String transformGuarded(String input, boolean loose) {
        if (input == null || input.length() == 0) {
            return input;
        }
        // 总开关关掉：整个模块都不生效（不替换、不加后缀、不加颜文字）
        if (!Config.masterEnabled()) {
            return input;
        }
        // 先把上一轮自己追加的失败提示摘掉，这一轮按最新状态重新决定
        String clean = stripErrors(input);
        // 标记必须成对。用户按一次退格就可能删掉结尾那个零宽，
        // 只剩开头一个 —— 这种「落单」状态绝不能整段当成「已改写段落」，
        // 否则从那个零宽往后的所有内容（含用户接着打的字）会被永久冻结。
        // 但也不能一律当自由文字：宿主写回输入框时也会吃掉行尾零宽
        // （真机实测），那种情况整段会被重新送模型。所以整段清掉标记后
        // 再按内容认一遍（见 appendFree / matchOutputLen）。
        boolean paired = isPaired(clean);
        if (!paired) {
            clean = unmark(clean);
        }
        String[] outs = Llm.enabled() ? Llm.outputSnapshot() : null;
        // 整段就是一段已经改好的旧文字、后面没有新内容：原样返回
        // （顺手把落单零宽与失败提示清掉），别补回标记 —— 宿主若每次都吃
        // 行尾零宽，补一次它吃一次，会变成「写回→被吃→再写回」的死循环。
        if (matchOutputLen(outs, clean, 0) >= clean.length() && clean.length() > 0) {
            return clean;
        }
        StringBuilder sb = new StringBuilder();
        // 只有自由文字才需要判断「这句拿到改写结果了没」。
        // 受保护段（含被认回来的旧段落）绝不能混进去 —— 否则那段文字
        // 永远查不到结果，会被误判成失败、每轮都追加一次失败提示。
        StringBuilder free = new StringBuilder();
        int i = 0;
        while (i < clean.length()) {
            int z = paired ? clean.indexOf(ZW, i) : -1;
            if (z < 0) {
                appendFree(sb, free, outs, clean.substring(i), loose);
                break;
            }
            if (z > i) {
                appendFree(sb, free, outs, clean.substring(i, z), loose);
            }
            int z2 = clean.indexOf(ZW, z + 1);
            if (z2 < 0) {
                sb.append(clean.substring(z));   // paired 已保证成对，兜底而已
                break;
            }
            // 已改写的段落原样保留（它本来就是按标记受保护的）
            sb.append(clean.substring(z, z2 + 1));
            i = z2 + 1;
        }
        String out = sb.toString();
        // 这句还没拿到改写结果：把失败原因追加在原文后面
        String base = llmInput(free.toString());
        if (Llm.enabled() && base.length() > 0 && Llm.rewrite(base) == null) {
            out = appendError(out, Llm.errorFor(base));
        }
        return out;
    }

    /** 把失败原因追加在文字后面；没有原因就原样返回。 */
    private static String appendError(String text, String why) {
        if (why == null || why.length() == 0) {
            return text;
        }
        return text + ERR_PREFIX + why + ERR_END;
    }

    /**
     * 去掉上一轮追加的失败提示（它会独占一行）。
     */
    private static String stripErrors(String s) {
        if (s == null || s.indexOf(ERR_PREFIX) < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            int z = s.indexOf(ERR_PREFIX, i);
            if (z < 0) {
                sb.append(s.substring(i));
                break;
            }
            sb.append(s.substring(i, z));
            int nl = s.indexOf('\n', z + ERR_PREFIX.length());
            if (nl < 0) {
                break;   // 提示一直写到结尾
            }
            i = nl + 1;
        }
        return sb.toString();
    }

    /**
     * 转换一段「自由文字」（不含零宽标记与失败提示）。
     *
     * <p>LLM 开着时不再回退本地规则：拿到结果就套上零宽标记返回，
     * 没拿到就原样返回（失败原因由调用方追加）。
     */
    private static String transformPart(String input) {
        if (input == null || input.length() == 0) {
            return input;
        }
        String stripped = stripKaomoji(input);
        String base = llmInput(input);
        if (base.length() == 0) {
            return input;   // 已经是模型输出，链条到此为止
        }
        if (Llm.enabled()) {
            String rewritten = Llm.rewrite(base);
            if (rewritten == null || rewritten.length() == 0) {
                return input;   // 还没落定 / 失败：这一轮先不动
            }
            // LLM 已改写：正文就是模型输出，句末后缀已由模型加过，
            // 所以不再看 needsMiao（那里的「喵」判定对模型输出不适用）。
            // 稳定判据：去掉末尾颜文字后的正文已经等于模型输出 -> 不用再动，
            // 否则每一轮都会重新随机挑一次颜文字。
            if (endsWithKaomoji(input) && stripped.equals(rewritten)) {
                return input;
            }
            // 把「改写后的正文」登记成它自己的结果：写回后轮询会再读一遍，
            // 不登记就会每轮重新请求模型。
            Llm.noteIdentity(rewritten);
            return mark(withKaomojiPerSentence(rewritten, true));
        }
        String core = Config.applyReplace(base);
        if (core.length() == 0) {
            return input;
        }
        // 没有任何「未喵化的标点」=> 保持原样，不打扰正常打字
        if (!needsMiao(stripped)) {
            return input;
        }
        return withKaomojiPerSentence(core, false);
    }

    /**
     * 只留下需要处理的自由文字：去掉已改写的段落与失败提示。
     *
     * <p>轮询与发送前都会先拿 {@link #llmInput} 判断「这句要不要送模型」，
     * 这一步必须先把标记剥掉，否则会把零宽字符一起发给模型。
     */
    public static String freeText(String input) {
        if (input == null || input.length() == 0) {
            return "";
        }
        String[] outs = Llm.enabled() ? Llm.outputSnapshot() : null;
        // 零宽落单时（宿主吃掉行尾那个，或用户自己退格删的）先全部剥掉，
        // 剩下的整段都按内容判断，判定与 transformGuarded 保持一致。
        String s = isPaired(input) ? input : unmark(input);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int z = s.indexOf(ZW, i);
            if (z < 0) {
                sb.append(s.substring(i));
                break;
            }
            if (z > i) {
                sb.append(s.substring(i, z));
            }
            int z2 = s.indexOf(ZW, z + 1);
            if (z2 < 0) {
                break;
            }
            i = z2 + 1;
        }
        // 去掉混在自由文字里的「自己产出过的改写正文」——宿主吃掉行尾零宽后
        // 那段旧文字会落在这里，不认出来就会整段重新送模型（实测多花 4.2 秒）。
        return stripErrors(stripOutputs(outs, sb.toString()));
    }

    /**
     * LLM 的输入文本：去掉颜文字与零宽标记，必要时去掉句末原有的「喵」。
     *
     * <p>两种模式差别很大：
     * <ul>
     *   <li><b>开了 LLM</b>：正文由模型产出，「喵」是模型自己加的，
     *       所以这里<b>不能</b>剥掉它 —— 只去掉末尾颜文字。另外，
     *       如果这段文字本身就是模型改写过的结果（写回后轮询又读到），
     *       返回空串表示「到此为止，别再加工」。</li>
     *   <li><b>没开 LLM</b>：沿用本地规则，先剥掉句末原有的「喵」，
     *       保证结果与历史无关。</li>
     * </ul>
     */
    public static String llmInput(String input) {
        if (input == null) {
            return "";
        }
        String s = stripKaomoji(freeText(input));
        if (s.length() == 0) {
            return "";
        }
        if (Llm.enabled()) {
            return Llm.isOutput(s) ? "" : s;
        }
        return stripSentenceMiao(s);
    }

    /**
     * 取「用于挑颜文字的正文」。
     *
     * <p>v7.14 起 LLM 开着时不再静默回退本地规则：拿到结果就用模型输出，
     * 没拿到就返回空串（调用方会原样返回原文并追加失败原因）。
     */
    public static String coreText(String input) {
        String base = llmInput(input);
        if (base.length() == 0) {
            return "";
        }
        if (Llm.enabled()) {
            String r = Llm.rewrite(base);
            if (r != null && r.length() > 0) {
                return r;
            }
            return "";
        }
        return Config.applyReplace(base);
    }

    /**
     * 这句的 LLM 改写是否「已经落定」—— 要么拿到了结果，要么确定失败。
     *
     * <p>还没落定时不要拿改写前的文字去问向量：那句话随后会被模型改写，
     * 匹配到的颜文字对不上改写后的内容，纯属浪费额度。
     */
    public static boolean llmSettled(String input) {
        if (!Llm.enabled() || !Llm.preEnabled()) {
            return true;
        }
        String raw = llmInput(input);
        if (!hasContent(raw)) {
            return true;
        }
        return Llm.rewrite(raw) != null || Llm.failedFor(raw);
    }

    /**
     * 宽宽松转换：供「语音输入自动发送」使用。
     *
     * <p>语音识别结果常常不带句末标点（尤其短句，如「你好 你好」），
     * 若沿用 transform() 的「无标点不动」规则，语音消息就永远不会被喵化。
     * 这里改为：人称替换 + 追加颜文字必定执行；句末标点则照常加「喵」。
     *
     * @return 转换后的文本；确实无可改动时原样返回（保证幂等）
     */
    public static String transformLoose(String input) {
        return transformGuarded(input, true);
    }

    /** {@link #transformLoose} 的单段实现。 */
    private static String transformLoosePart(String input) {
        if (input == null || input.length() == 0) {
            return input;
        }
        String stripped = stripKaomoji(input);
        String base = llmInput(input);
        if (base.length() == 0) {
            return input;   // 已经是模型输出，链条到此为止
        }
        if (Llm.enabled()) {
            String rewritten = Llm.rewrite(base);
            if (rewritten == null || rewritten.length() == 0) {
                return input;
            }
            // 稳定判据：正文已经落定，且末尾已有颜文字 -> 保持原样，别反复改写
            if (endsWithKaomoji(input) && stripped.equals(rewritten)) {
                return input;
            }
            Llm.noteIdentity(rewritten);
            return mark(withKaomojiPerSentence(rewritten, true));
        }
        String core = Config.applyReplace(base);
        if (core.length() == 0) {
            return input;
        }
        return withKaomojiPerSentence(core, false);
    }

    /**
     * 按句末标点切分，为每一句挑选并追加颜文字。
     *
     * 每句独立匹配标签（命中多个取最后一条规则），
     * 未命中则从全部颜文字中随机取。
     */
    /**
     * 按句末标点切分，为每一句挑选并追加颜文字。
     *
     * 每句独立匹配标签（命中多个取最后一条规则），
     * 未命中则从全部颜文字中随机取。
     *
     * 注意：这里只做「追加」，绝不重复输出原句，避免文本被复制放大。
     */
    /**
     * 在一句末尾补后缀（默认「喵」，可在 settings.txt 里改或留空）。
     *
     * 有句末标点时插在标点前（你好喵。）；没有标点时直接加在末尾（你好喵）。
     * 已经以该后缀结尾则原样返回，保证可重复调用。
     * 后缀为空表示不加。
     */
    private static String endWithSuffix(String sentence, String suffix) {
        if (sentence == null || sentence.length() == 0) {
            return sentence;
        }
        if (suffix == null || suffix.length() == 0) {
            return sentence;   // 后缀关闭
        }
        // 跳过末尾连续的标点
        int i = sentence.length() - 1;
        int punctStart = sentence.length();
        while (i >= 0 && isPunct(sentence.charAt(i))) {
            punctStart = i;
            i--;
        }
        // 跳过标点前的空白
        int j = punctStart - 1;
        while (j >= 0 && isBlank(sentence.charAt(j))) {
            j--;
        }
        // 已经以该后缀结尾 -> 不重复加
        int slen = suffix.length();
        if (j >= 0 && j - slen + 1 >= 0
                && sentence.regionMatches(j - slen + 1, suffix, 0, slen)) {
            return sentence;
        }
        // 去掉标点前的空白，把后缀贴紧正文
        StringBuilder head = new StringBuilder(sentence.substring(0, punctStart));
        while (head.length() > 0 && isBlank(head.charAt(head.length() - 1))) {
            head.setLength(head.length() - 1);
        }
        return head + suffix + sentence.substring(punctStart);
    }

    /**
     * 按句末标点切句，每句含结尾标点。
     * 末尾没有标点的残句也算一句。
     *
     * <p>切法与 {@link #withKaomojiPerSentence} 完全一致，
     * 供「事前向量预取」在改写之前先取出需要处理的句子。
     */
    public static List<String> splitSentences(String core) {
        List<String> out = new ArrayList<String>();
        if (core == null || core.length() == 0) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            cur.append(c);
            if (isPunct(c)) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * 是否「有活可干」—— 供轮询在改写前先判断，避免白算一遍。
     * 判定标准与 {@link #transform} 相同：存在尚未喵化的句末标点。
     */
    public static boolean needsWork(String input) {
        if (input == null || input.length() == 0) {
            return false;
        }
        return needsMiao(stripKaomoji(input));
    }

    /**
     * 是否以句末标点结尾 —— 用于判断这是不是一个「已经打完的句子」。
     *
     * <p>事前向量预取只对打完的句子发起，避免打字过程中乱发网络请求。
     */
    public static boolean endsWithPunct(String s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        int i = s.length() - 1;
        while (i >= 0 && isBlank(s.charAt(i))) {
            i--;
        }
        return i >= 0 && isPunct(s.charAt(i));
    }

    /**
     * 取「真正需要挑选颜文字的句子」列表。
     *
     * <p>走的是与改写完全相同的预处理（去颜文字 → 去喵 → 替换人称），
     * 因此这里得到的句子就是随后 {@link #withKaomojiPerSentence} 里
     * 拿去选颜文字的那一批，供事前向量预取与事后记录保持同一套键。
     */
    public static List<String> sentencesFor(String input) {
        List<String> none = new ArrayList<String>();
        if (input == null || input.length() == 0) {
            return none;
        }
        String core = coreText(input);
        return splitSentences(core);
    }

    private static String withKaomojiPerSentence(String core) {
        return withKaomojiPerSentence(core, false);
    }

    /**
     * @param llmApplied 正文是否来自模型改写。是的话句末后缀已由模型处理，
     *                   本地不再补，避免出现「主人好喵喵。」。
     */
    private static String withKaomojiPerSentence(String core, boolean llmApplied) {
        TagLib.load();
        String suf = (!llmApplied && Config.suffixEnabled()) ? Config.suffix() : "";
        boolean kaoOn = Config.kaomojiEnabled();
        StringBuilder sb = new StringBuilder();
        List<String> sents = splitSentences(core);
        for (int i = 0; i < sents.size(); i++) {
            String one = sents.get(i);
            boolean last = (i == sents.size() - 1);
            boolean ended = one.length() > 0 && isPunct(one.charAt(one.length() - 1));
            // 末尾纯空白的残句原样保留，不加后缀也不加颜文字
            if (last && !ended && one.trim().length() == 0) {
                sb.append(one);
                continue;
            }
            sb.append(endWithSuffix(one, suf));
            if (kaoOn) {
                TagLib.Pick p = TagLib.pickEx(one);
                String k = p.kaomoji;
                if (k.length() > 0) {
                    sb.append(k);
                    // 只有「真的问过向量模型、并且命中了」的结果才写进记忆库。
                    //
                    // 关键词规则或全库随机挑出来的**不写** —— 否则一次随机
                    // 命中会被永久固化成「完全相同命中」，之后每次同样的句子
                    // 都强行用那个可能是错的颜文字，且再也不会去问向量。
                    // 用户要求（m03997）：没经过向量的禁止写入 memory.txt。
                    if (TagLib.SRC_VECTOR.equals(p.source)) {
                        Vector.note(one, k);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 末尾是否是词库里的某个颜文字。 */
    private static boolean endsWithKaomoji(String s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        for (String k : kaomoji) {
            if (k.length() > 0 && s.endsWith(k)) {
                return true;
            }
        }
        return false;
    }
}
