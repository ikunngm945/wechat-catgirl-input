package org.dsh.qqcatime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 句子 → 颜文字 的记忆库（纯文本，可人工编辑）。
 *
 * <p>文件位置：{@code /data/user/0/com.tencent.wetype/qqime/memory.txt}
 * <pre>
 *   # 句子=颜文字（一行一条）
 *   你好=＝^･ω･^＝
 *   今天好累=(￣o￣) zzZ
 * </pre>
 *
 * <p>这是检索链的<b>第一级</b>：句子的「完全相同」比对命中就直接输出该颜文字，
 * <b>不联网、不走向量</b>。
 *
 * <p>写入来自：
 * <ol>
 *   <li>事后分析 —— 句子实际用掉某个颜文字后记下来；</li>
 *   <li>事前分析 —— 经向量模型匹配出颜文字的句子，也会回写到这里。</li>
 * </ol>
 *
 * <p>注意：以第一个 {@code =} 分割。句子里本身带 {@code =} 的不会记录
 * （会把颜文字切错），这种句子继续走向量 / 关键词规则。
 *
 * <p>键会经过 {@link #normKey} 归一化：存与查都去掉句末的
 * {@code 。．；;…}，所以「我喜欢你。」与「我喜欢你」是同一条；
 * 但 {@code ？} 与 {@code ！} <b>原样保留</b>，语气不同各存各的。
 */
public final class VecStore {

    /** 记忆库文件。 */
    public static final String FILE = Cat.DIR + "/memory.txt";

    /** v6.x 的旧文件（句子[TAB]颜文字[TAB]向量），仅用于一次性搬迁。 */
    private static final String OLD_FILE = Cat.DIR + "/vector.txt";

    /** 条目上限，超过后自动丢弃最旧的（TXT 是有序的，新的在后面）。 */
    public static final int MAX_ENTRIES = 20000;

    private static final String HEADER =
            "# 记忆库：句子=颜文字（一行一条）\n"
          + "# 这个文件由模块自动维护，也可以手动编辑。\n"
          + "# 「完全相同」的句子会直接使用这里的颜文字，不请求向量模型。\n"
          + "# 以第一个 = 分割，因此句子里请勿包含 = 号。\n"
          + "# 存进来时会去掉句末的 。．；;… —— 所以「我喜欢你。」与\n"
          + "# 「我喜欢你」是同一条；但 ？与！ 会原样保留，各存各的。\n";

    /** 句子 → 颜文字（完全相同比对用）。 */
    private static final Map<String, String> exact =
            new LinkedHashMap<String, String>();

    private static long stamp = -2;

    private VecStore() {
    }

    // ------------------------------------------------------------ 加载

    /** 有变化才重新读盘。 */
    public static synchronized void load() {
        File f = new File(FILE);
        long s = f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
        if (s == stamp) {
            return;
        }
        stamp = s;
        exact.clear();
        int merged = 0;

        if (s == -1L) {
            // 没有 memory.txt：看看有没有 v6.x 的 vector.txt，有就搬一次；
            // 都没有就在首次运行时释放内置的常见话术种子。
            if (migrateOld() || releaseSeed()) {
                // 刚写完盘，重新取一次 stamp 再往下走 ——
                // 不能沿用上面的 s，否则会被 s <= 0 挡掉，
                // 这次调用就用了空记忆库（下次才生效）。
                s = f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
                stamp = s;
            } else {
                return;   // 还没有记忆，正常
            }
        }
        if (s <= 0L || f.length() == 0) {
            return;
        }
        List<String> lines;
        try {
            lines = readLines(f);
        } catch (Throwable t) {
            Cat.log("memory: 读取失败 " + t.getClass().getSimpleName());
            return;
        }
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            int i = line.indexOf('=');
            if (i <= 0) {
                continue;
            }
            String sentence = normKey(line.substring(0, i).trim());
            String kao = line.substring(i + 1).trim();
            if (sentence.length() == 0 || kao.length() == 0
                    || sentence.indexOf('=') >= 0) {
                continue;
            }
            if (exact.containsKey(sentence)) {
                merged++;   // 归一化后撞车：保留先出现的那条
            } else {
                exact.put(sentence, kao);
            }
        }
        if (merged > 0) {
            // 盘上还是旧形态（句末标点没归一，同一句存了两三条）——
            // 顺手重写一次清干净，免得文件一直虚胖。
            if (rewrite()) {
                s = f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
                stamp = s;
            }
        }
        Cat.log("memory: 记忆库已加载 " + exact.size() + " 条"
                + (merged > 0 ? "（句末标点归一化合并 " + merged + " 条）" : ""));
    }

    /** 按内存里的内容重写 memory.txt（用于清掉盘上的旧形态重复条目）。 */
    private static synchronized boolean rewrite() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (Map.Entry<String, String> e : exact.entrySet()) {
            if (e.getKey().indexOf('=') >= 0) {
                continue;
            }
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        try {
            Cat.writeFile(FILE, sb.toString());
        } catch (Throwable t) {
            Cat.log("memory: 重写失败 " + t.getClass().getSimpleName());
            return false;
        }
        return true;
    }

    /** 把 v6.x 的 vector.txt 第一、二列搬成 memory.txt。 */
    private static boolean migrateOld() {
        File old = new File(OLD_FILE);
        if (!old.exists() || old.length() == 0) {
            return false;
        }
        List<String> lines;
        try {
            lines = readLines(old);
        } catch (Throwable t) {
            return false;
        }
        StringBuilder sb = new StringBuilder(HEADER);
        int n = 0;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            String[] c = line.split("\t");
            if (c.length < 2) {
                continue;
            }
            String sentence = normKey(oneLine(c[0]));
            String kao = oneLine(c[1]);
            if (sentence.length() == 0 || kao.length() == 0
                    || sentence.indexOf('=') >= 0) {
                continue;
            }
            sb.append(sentence).append('=').append(kao).append('\n');
            n++;
        }
        try {
            Cat.writeFile(FILE, sb.toString());
        } catch (Throwable t) {
            return false;
        }
        stamp = -2;
        Cat.log("memory: 已从 vector.txt 搬迁 " + n + " 条旧记忆");
        return true;
    }

    /**
     * 首次运行释放内置的常见话术种子。
     *
     * <p>只放「句子=颜文字」两列，不含向量 —— 让「么么哒」这类
     * 常见话术第一次就能走完全相同命中，不必等向量模型算。
     * 用户清空 / 删掉 memory.txt 后会再次释放。
     */
    private static boolean releaseSeed() {
        String seed = Defaults.MEMORY_SEED;
        if (seed == null || seed.length() == 0) {
            return false;
        }
        StringBuilder sb = new StringBuilder(HEADER);
        int n = 0;
        for (String raw : seed.split("\n")) {
            if (raw == null) {
                continue;
            }
            String line = oneLine(raw);
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            int i = line.indexOf('=');
            if (i <= 0) {
                continue;
            }
            sb.append(line).append('\n');
            n++;
        }
        if (n == 0) {
            return false;
        }
        try {
            Cat.writeFile(FILE, sb.toString());
        } catch (Throwable t) {
            return false;
        }
        stamp = -2;
        Cat.log("memory: 首次运行释放内置记忆 " + n + " 条");
        return true;
    }

    // ------------------------------------------------------------ 查询

    /**
     * 完全相同命中（不联网）。
     *
     * <p>要试两种形态，因为「传进来的句子」与「memory.txt 里存的句子」
     * 在<b>人称</b>上可能对不上：运行时传入的是 我→本喵 / 你→主人 替换之后的
     * 文本（`本喵想亲亲主人`），而内置种子与用户手写的多是原文
     * （`我想亲亲你`）。
     *
     * <p>句末标点这一维已经在 {@link #normKey} 里统一掉了（存与查都归一化），
     * 所以不再需要单独的标点变体。
     */
    public static synchronized String exact(String sentence) {
        if (sentence == null || sentence.length() == 0) {
            return null;
        }
        // 载入时键已经归一化过（句末 。．；;… 已去掉），所以这里只需
        // 归一化查询串，再试「替换后的人称」与「还原后的人称」两种写法。
        String key = normKey(sentence);
        if (key.length() == 0) {
            return null;
        }
        String hit = exact.get(key);
        if (hit != null && hit.length() > 0) {
            return hit;
        }
        String alt = back(key);
        if (!alt.equals(key)) {
            hit = exact.get(alt);
            if (hit != null && hit.length() > 0) {
                return hit;
            }
        }
        return null;
    }

    /** 把替换后的人称还原成「我 / 你」。 */
    private static String back(String s) {
        return s.replace("本喵", "我").replace("主人", "你");
    }

    /**
     * 记忆库的归一化形态：去掉句末的 {@code 。．；;…}。
     *
     * <p>这样「我喜欢你。」与「我喜欢你」只占一条。
     *
     * <p><b>但 ？与！ 一律保留</b> —— 它们是不同的语气，
     * 「真的吗？」和「真的吗！」「真的吗」各存各的。
     */
    private static String normKey(String s) {
        if (s == null) {
            return "";
        }
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '。' || c == '．' || c == '；' || c == ';' || c == '…') {
                end--;
            } else if (isBlank(c)) {
                end--;
            } else {
                break;
            }
        }
        return end == s.length() ? s : s.substring(0, end);
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == '\u3000' || c == '\u00a0';
    }

    public static synchronized int count() {
        return exact.size();
    }

    // ------------------------------------------------------------ 写入

    /**
     * 记下「句子 → 颜文字」。已经有的句子不重复写。
     *
     * <p>落盘前先做 {@link #normKey} 归一化 —— 句末的 。．；;… 去掉，
     * 这样「我喜欢你。」与「我喜欢你」只占一条；？与！ 保留，各存各的。
     */
    public static synchronized boolean remember(String sentence, String kaomoji) {
        if (sentence == null || sentence.length() == 0
                || kaomoji == null || kaomoji.length() == 0) {
            return false;
        }
        String s = normKey(oneLine(sentence));
        String k = oneLine(kaomoji);
        if (s.length() == 0 || k.length() == 0) {
            return false;
        }
        if (s.indexOf('=') >= 0) {
            // 句子里带 = 会把格式切错，这种句子不记，继续走向量/关键词。
            return false;
        }
        if (exact.containsKey(s)) {
            return false;
        }
        exact.put(s, k);
        File f = new File(FILE);
        boolean fresh = !f.exists() || f.length() == 0;
        if (!append(fresh
                ? HEADER + s + "=" + k + "\n"
                : s + "=" + k + "\n")) {
            exact.remove(s);
            return false;
        }
        stamp = -2;
        if (exact.size() > MAX_ENTRIES) {
            compact();
        }
        return true;
    }

    private static boolean append(String text) {
        try {
            File f = new File(FILE);
            if (text.startsWith("#") || !f.exists()) {
                // 首次写入带表头时直接整体覆盖
                if (!f.exists() || f.length() == 0) {
                    Cat.writeFile(FILE, text);
                    return true;
                }
            }
            java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(f, true), "UTF-8");
            try {
                w.write(text);
                w.flush();
            } finally {
                try { w.close(); } catch (Throwable t) { }
            }
            return true;
        } catch (Throwable t) {
            Cat.log("memory: 写入失败 " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 超过上限：只保留最后 MAX_ENTRIES 条重写（按文件顺序）。 */
    private static synchronized void compact() {
        List<String> lines;
        try {
            lines = readLines(new File(FILE));
        } catch (Throwable t) {
            return;
        }
        List<String> keep = new ArrayList<String>();
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.length() == 0 || line.startsWith("#")) {
                continue;
            }
            keep.add(line);
        }
        int from = Math.max(0, keep.size() - MAX_ENTRIES);
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = from; i < keep.size(); i++) {
            sb.append(keep.get(i)).append('\n');
        }
        Cat.writeFile(FILE, sb.toString());
        stamp = -2;
        Cat.log("memory: 已裁剪至 " + (keep.size() - from) + " 条");
    }

    // ------------------------------------------------------------ 工具

    private static List<String> readLines(File f) throws Exception {
        List<String> out = new ArrayList<String>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                out.add(line);
            }
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable t) { }
            }
        }
        return out;
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** 清空记忆库（供界面上的「清空」按钮）。 */
    public static synchronized boolean clear() {
        try {
            Cat.writeFile(FILE, HEADER);
        } catch (Throwable t) {
            return false;
        }
        exact.clear();
        stamp = -2;
        Cat.log("memory: 记忆库已清空");
        return true;
    }
}
