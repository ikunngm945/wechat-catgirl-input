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

    /** 内置词库（54 条）。 */
    public static final String[] DEFAULT_KAO = {
        "~o( =∩ω∩= )m",
        "≡ω≡",
        "^⌯\uD81A\uDD66⌯^ ੭ ^",
        "⌯'ㅅ'⌯",
        "=^\uD81A\uDD66^=",
        "⌯•ㅅ•⌯",
        "ฅ•̀∀•́ฅ",
        "ฅ ̳͒•ˑ̫• ̳͒ฅ♡",
        "ฅ(̳•·̫•̳ฅ)♡",
        "ฅ^••^ฅ",
        "=^•ω•^=",
        "₍^ >ヮ<^₎",
        "/ᐠ - ˕ -マ Ⳋ",
        "ฅ^•ﻌ•^ฅ",
        "ฅ՞•ﻌ•՞ฅ",
        "(ฅ´ω`ฅ)",
        "ฅ(*`ω´*)ฅ",
        "ฅ꒰ ⸝˶• •˶⸝꒱ฅ",
        "₍˄·͈༝·͈˄*₎◞ ̑̑",
        "!!^⌯\uD81A\uDD66⌯^ ੭!!",
        "₍^⸝⸝> ·̫ <⸝⸝ ^₎",
        "ฅ^._.^ฅ",
        "₍\uD83C\uDF80˄•͈༝•͈˄₎ฅ˒˒",
        "^•͈༝•^ฅ",
        "꒰ఎ(^ . ֑ .^)໒꒱",
        "ฅ●ω●ฅ",
        "₍⸍⸌·͈༝·͈⸍⸌₎◞",
        "(>^ω^<)",
        "ฅ^-﹃-^ฅ",
        "^ ̳ට ̫ ට ̳^",
        "୧₍˄·͈༝·͈˄₎୨",
        "^ ̳ᴗ  ̫ ᴗ ̳^",
        "˓˓ก(⸍⸌̣ʷ̣̫⸍̣⸌₎ค˒˒",
        "ヽ(ฅ≧へ≦)ฅ",
        "(`･ω･´)ฅ",
        "(=^･ᴥ･^=)",
        "(^ω^ฅ)",
        "ฅ(≧▽≦)ฅ",
        "ฅ(=´▽`=)ฅ",
        "ヾ((๑˘ㅂ˘๑)ฅ",
        "(ฅ◑ω◑ฅ)",
        "(๑•̀ω•́ฅ)",
        "(ฅ>ω<*ฅ)",
        "(=^.^=)",
        "(=´ᴥ`)",
        "(=ↀωↀ=)",
        "(=^-ω-^=)",
        "ฅ(*°ω°*ฅ)",
        "ヽ(=^･ω･^=)丿",
        "(^•ᴥ•^)",
        "( Φ ω Φ )",
        "(=^x^=)",
        "ฅ( ̳• ◡ • ̳)ฅ",
        "o( =•ω•= )m",
    };

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
                while (j < s.length() && (s.charAt(j) == '喵' || isBlank(s.charAt(j)))) {
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
     * 无句末标点时原样返回；已处理妥当且颜文字在末尾时也原样返回（保证幂等）。
     */
    public static String transform(String input) {
        if (input == null || input.length() == 0) {
            return input;
        }
        String stripped = stripKaomoji(input);
        // 没有任何「未喵化的标点」=> 保持原样，不打扰正常打字
        if (!needsMiao(stripped)) {
            return input;
        }
        // 加喵统一交给 withKaomojiPerSentence 逐句处理，这里不再重复加
        String core = stripSentenceMiao(stripped)
                .replace("我", "本喵").replace("你", "主人");
        return withKaomojiPerSentence(core);
    }

    /**
     * 宽松转换：供「语音输入自动发送」使用。
     *
     * 语音识别结果常常不带句末标点（尤其短句，如「你好 你好」），
     * 若沿用 transform() 的「无标点不动」规则，语音消息就永远不会被喵化。
     * 这里改为：人称替换 + 追加颜文字必定执行；句末标点则照常加「喵」。
     *
     * @return 转换后的文本；确实无可改动时原样返回（保证幂等）
     */
    public static String transformLoose(String input) {
        if (input == null || input.length() == 0) {
            return input;
        }
        String stripped = stripKaomoji(input);
        String core = stripSentenceMiao(stripped)
                .replace("我", "本喵").replace("你", "主人");

        // 确实没有任何改动，且末尾已是颜文字 -> 保持原样
        if (core.equals(stripped) && endsWithKaomoji(input)) {
            return input;
        }
        // 加喵与选颜文字都放到「逐句」里做，保证没标点的句子也能补上喵
        return withKaomojiPerSentence(core);
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
     * 在一句末尾补「喵」。
     *
     * 有句末标点时插在标点前（你好喵。）；没有标点时直接加在末尾（你好喵）。
     * 已经以「喵」结尾则原样返回，保证可重复调用。
     */
    private static String endWithMiao(String sentence) {
        if (sentence == null || sentence.length() == 0) {
            return sentence;
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
        if (j >= 0 && sentence.charAt(j) == '喵') {
            return sentence;   // 已有喵，不重复加
        }
        // 去掉标点前的空白，把喵贴紧正文
        StringBuilder head = new StringBuilder(sentence.substring(0, punctStart));
        while (head.length() > 0 && isBlank(head.charAt(head.length() - 1))) {
            head.setLength(head.length() - 1);
        }
        return head + "喵" + sentence.substring(punctStart);
    }

    private static String withKaomojiPerSentence(String core) {
        TagLib.load();
        StringBuilder sb = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            cur.append(c);
            if (isPunct(c)) {
                String one = cur.toString();
                sb.append(endWithMiao(one));
                String k = TagLib.pick(one);
                if (k.length() > 0) {
                    sb.append(k);
                }
                cur.setLength(0);
            }
        }
        // 末尾残句（没有标点收尾）也要补喵 + 颜文字
        if (cur.length() > 0) {
            String tail = cur.toString();
            if (tail.trim().length() > 0) {
                sb.append(endWithMiao(tail));
                String k = TagLib.pick(tail);
                if (k.length() > 0) {
                    sb.append(k);
                }
            } else {
                sb.append(tail);
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
