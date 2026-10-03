package org.dsh.qqcatime;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 颜文字标签库。
 *
 * <p>目录结构（都在 {@link Cat#DIR} 下）：
 * <pre>
 *   katxt            全部颜文字（兜底库，缺失时释放内置的 54 条）
 *   tags/            标签分类目录
 *     开心.txt        该标签下的颜文字，一行一个
 *     难过.txt
 *     ...
 *   rules.txt        关键词 → 标签 映射，一行一条，格式「关键词=标签」
 * </pre>
 *
 * <p>匹配规则：
 * <ul>
 *   <li>按句末标点把文本切成若干句，每句独立处理。</li>
 *   <li>句中出现某个关键词，即命中该关键词对应的标签。</li>
 *   <li>一句命中多个标签时，取<code>rules.txt</code>里<b>最后</b>出现的那个。</li>
 *   <li>没有命中任何标签时，从兜底库 katxt 中随机取。</li>
 * </ul>
 */
public final class TagLib {

    /** 标签目录。 */
    public static final String TAGS_DIR = Cat.DIR + "/tags";

    /** 关键词 → 标签 映射文件。 */
    public static final String RULES_FILE = Cat.DIR + "/rules.txt";

    /** 默认规则（首次运行释放，用户可自行修改）。 */
    public static final String[] DEFAULT_RULES = {
        "你好=开心",
        "哈哈=开心",
        "开心=开心",
        "高兴=开心",
        "喜欢=开心",
        "谢谢=开心",
        "难过=难过",
        "伤心=难过",
        "哭=难过",
        "难受=难过",
        "饿=饿",
        "吃=饿",
        "饭=饿",
        "累=累",
        "困=累",
        "睡=累",
        "生气=生气",
        "怒=生气",
        "讨厌=生气",
        "爱=爱心",
        "喜欢你=爱心",
        "想你=爱心",
    };

    /** 示例标签文件（首次运行释放，内容取自内置词库的前若干条）。 */
    public static final String[] DEFAULT_TAGS = {
        "开心", "难过", "饿", "累", "生气", "爱心",
    };

    private static final Random RND = new Random();

    /** 关键词 → 标签，保持文件顺序（用于「取最后命中」）。 */
    private static final Map<String, String> rules = new LinkedHashMap<String, String>();

    /** 标签 → 该标签下的颜文字。 */
    private static final Map<String, String[]> tagKaomoji =
            new HashMap<String, String[]>();

    private static long rulesStamp = -2;
    private static long tagsStamp = -2;

    private TagLib() {
    }

    // ------------------------------------------------------------ 加载

    /** 加载规则与标签库（有变化才重新读盘）。 */
    public static synchronized void load() {
        loadRules();
        loadTags();
    }

    private static void loadRules() {
        File f = new File(RULES_FILE);
        long stamp = stamp(f);
        if (stamp == rulesStamp) {
            return;
        }
        rulesStamp = stamp;

        if (stamp == -1L) {
            StringBuilder sb = new StringBuilder();
            for (String r : DEFAULT_RULES) {
                sb.append(r).append('\n');
            }
            Cat.writeFile(RULES_FILE, sb.toString());
        }

        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        List<String> lines = readLines(f);
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String kw = line.substring(0, eq).trim();
            String tag = line.substring(eq + 1).trim();
            if (kw.length() > 0 && tag.length() > 0) {
                out.put(kw, tag);
            }
        }
        if (out.isEmpty() && stamp != -1L) {
            for (String r : DEFAULT_RULES) {
                int eq = r.indexOf('=');
                out.put(r.substring(0, eq), r.substring(eq + 1));
            }
        }
        rules.clear();
        rules.putAll(out);
        Cat.log("rules: 已加载 " + rules.size() + " 条");
    }

    private static void loadTags() {
        File dir = new File(TAGS_DIR);
        long stamp = -1L;
        if (dir.isDirectory()) {
            long acc = 0;
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile()) {
                        acc = acc * 31 + f.getName().hashCode()
                                + f.lastModified() + f.length();
                    }
                }
            }
            stamp = acc;
        }
        if (stamp == tagsStamp) {
            return;
        }
        tagsStamp = stamp;

        if (!dir.isDirectory()) {
            releaseDefaultTags();
        }

        HashMap<String, String[]> out = new HashMap<String, String[]>();
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.isFile()) {
                    continue;
                }
                String name = f.getName();
                if (!name.endsWith(".txt")) {
                    continue;
                }
                String tag = name.substring(0, name.length() - 4);
                List<String> lines = readLines(f);
                if (lines.isEmpty()) {
                    continue;
                }
                out.put(tag, lines.toArray(new String[0]));
            }
        }
        tagKaomoji.clear();
        tagKaomoji.putAll(out);
        Cat.log("tags: 已加载 " + tagKaomoji.size() + " 个标签 " + tagKaomoji.keySet());
    }

    /** 首次运行：释放示例标签文件，内容取内置词库的前若干条。 */
    private static void releaseDefaultTags() {
        try {
            File dir = new File(TAGS_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
                dir.setReadable(true, false);
                dir.setExecutable(true, false);
            }
        } catch (Throwable t) {
            // 忽略
        }
        int idx = 0;
        for (String tag : DEFAULT_TAGS) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6 && idx < Cat.DEFAULT_KAO.length; i++, idx++) {
                sb.append(Cat.DEFAULT_KAO[idx]).append('\n');
            }
            Cat.writeFile(TAGS_DIR + "/" + tag + ".txt", sb.toString());
        }
        Cat.log("tags: 已释放 " + DEFAULT_TAGS.length + " 个示例标签文件");
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<String>();
        if (f == null || !f.isFile()) {
            return out;
        }
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
            // 忽略
        } finally {
            if (r != null) {
                try { r.close(); } catch (Throwable t) { }
            }
        }
        return out;
    }

    private static long stamp(File f) {
        return f.exists() ? (f.lastModified() * 1000L + f.length()) : -1L;
    }

    // ------------------------------------------------------------ 查询

    /**
     * 为一句话挑选颜文字。
     *
     * @param sentence 单句文本（不含句末标点）
     * @return 颜文字
     */
    public static String pick(String sentence) {
        String tag = matchTag(sentence);
        if (tag != null) {
            String[] arr = tagKaomoji.get(tag);
            if (arr != null && arr.length > 0) {
                return arr[RND.nextInt(arr.length)];
            }
        }
        // 未命中，或标签文件为空 -> 兜底全库随机
        return Cat.randomKaomoji();
    }

    /**
     * 匹配标签。一句命中多个时，取 rules.txt 里最后出现的那个。
     *
     * @return 标签名；无命中返回 null
     */
    public static String matchTag(String sentence) {
        if (sentence == null || sentence.length() == 0) {
            return null;
        }
        String hit = null;
        // 关键词要同时兼容「替换前 / 替换后」两种形态：
        // 用户输入「你好」，替换后变成「主人好」，若只用替换后的文本匹配，
        // 规则「你好=开心」就永远命中不了。因此两种形态都试。
        String alt = sentence
                .replace("本喵", "我")
                .replace("主人", "你");
        // rules 是 LinkedHashMap，按文件顺序遍历，后面的覆盖前面的
        for (Map.Entry<String, String> e : rules.entrySet()) {
            if (sentence.contains(e.getKey()) || alt.contains(e.getKey())) {
                hit = e.getValue();
            }
        }
        return hit;
    }

    /** 已加载的标签数量。 */
    public static int tagCount() {
        return tagKaomoji.size();
    }

    /** 规则条数。 */
    public static int ruleCount() {
        return rules.size();
    }
}
